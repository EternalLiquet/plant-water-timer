import {registerPlugin} from '@capacitor/core';
import {installSessionUi} from './session-ui.mjs';
import {openGardenStore, localDate, connectGardenDatabase} from './garden-store.mjs';

const byId = id => document.getElementById(id);
const photos = globalThis.__gardenTestPhotos || registerPlugin('GardenPhotos');

function present(element, description) {
  if (!element) throw new Error(`Source UI changed: missing ${description}`);
  return element;
}

// Hides a web-only form field together with its label and help text.
function hideField(id) {
  const input = present(byId(id), `#${id}`);
  const row = input.closest('.number-unit') || input;
  const help = row.nextElementSibling?.matches('p.small') ? row.nextElementSibling : null;
  for (const element of [present(document.querySelector(`label[for="${id}"]`), `label for #${id}`), row,
    present(help, `help text for #${id}`)]) {
    element.hidden = true;
  }
}

function prepareNativeUi() {
  present(byId('garden'), '#garden').hidden = false;
  present(byId('help-open'), '#help-open').hidden = true;
  present(byId('sign-out'), '#sign-out').hidden = true;
  present(byId('install'), '#install').hidden = true;
  present(byId('add-open'), '#add-open').hidden = false;
  present(byId('empty')?.querySelector('p'), '#empty text').textContent = 'Add a name to start caring for your first plant on this phone.';
  byId('care-summary').textContent = 'Opening the garden on this phone…';
  present(document.querySelector('.heading .muted'), 'heading text').textContent = 'Check the soil before watering. Your plants and watering notes are saved on this phone.';
  present(byId('add-dialog')?.querySelector('.photo-picker .small'), 'photo help').textContent =
    'JPG or PNG, up to 5 MB. Photos stay on this phone. Online identification is not connected; type a name below.';
  const removePhoto = document.createElement('button');
  removePhoto.id = 'remove-photo'; removePhoto.type = 'button'; removePhoto.textContent = 'Continue without a photo';
  byId('photo-message').after(removePhoto);
  hideField('last-watered');
  hideField('interval');
  present(byId('past-form'), '#past-form').hidden = true;
}

async function openNativeStore() {
  const {CapacitorSQLite, SQLiteConnection} = await import('@capacitor-community/sqlite');
  return openGardenStore(await connectGardenDatabase(new SQLiteConnection(CapacitorSQLite)));
}

function showMessage(message, error = false) {
  const element = byId('page-message');
  element.textContent = message;
  element.classList.toggle('error', error);
}

async function start(store) {
  // One entry per rendered card. "Today" depends on each plant's zone and on the clock, so the
  // Watered today buttons are recalculated whenever the day might have changed.
  let wateringCards = [];
  const canWaterToday = card => !card.saving && card.plant.lastWatered !== localDate(card.plant.zone);
  function refreshWateringButtons() {
    for (const card of wateringCards) card.button.disabled = !canWaterToday(card);
  }

  let renderGeneration = 0;
  async function render() {
    const generation = ++renderGeneration;
    const plants = await store.listPlants();
    const renderedCards = [];
    const fragment = document.createDocumentFragment();
    for (const plant of plants) {
      const card = byId('plant-template').content.firstElementChild.cloneNode(true);
      card.querySelector('.plant-name').textContent = plant.name;
      card.querySelector('.species').textContent = plant.species;
      card.querySelector('.due').textContent = 'Check the soil; water only if dry.';
      card.querySelector('.last').textContent = plant.lastWatered ? `Last watered ${plant.lastWatered}` : 'No watering recorded yet';
      const image = card.querySelector('img');
      image.hidden = true;
      if (plant.photoId) {
        try {
          const {data} = await photos.read({photoId: plant.photoId});
          image.src = `data:image/jpeg;base64,${data}`;
          image.hidden = false;
        } catch {
          image.alt = 'Photo could not be opened. Your plant and watering history are still saved.';
          image.hidden = false;
        }
      }
      card.querySelector('.edit').hidden = true;
      card.querySelector('.undo').hidden = true;
      const water = card.querySelector('.water');
      const wateringCard = {plant, button: water, saving: false};
      renderedCards.push(wateringCard);
      water.textContent = 'Watered today';
      water.disabled = !canWaterToday(wateringCard);
      water.addEventListener('click', async () => {
        const date = localDate(plant.zone);
        wateringCard.saving = true;
        water.disabled = true;
        let result;
        try {
          result = await store.recordWatering(plant.id, date);
        } catch {
          // Same-day waterings are deduplicated, so retrying cannot record this one twice.
          showMessage('I couldn’t confirm the watering was saved. Tap Watered today again; it won’t be counted twice.', true);
          wateringCard.saving = false;
          water.disabled = !canWaterToday(wateringCard);
          return;
        }
        // Keeps this card right if the refresh below fails and the day changes later.
        if (!plant.lastWatered || date > plant.lastWatered) plant.lastWatered = date;
        wateringCard.saving = false;
        try {
          await render();
          showMessage(result.created ? 'Saved on this phone: watering recorded.' : 'Watering already recorded for today on this phone.');
        } catch {
          showMessage('Saved on this phone, but I couldn’t refresh the list. Reopen the app to see it.', true);
        }
      });
      card.querySelector('.history').addEventListener('click', async () => {
        try {
          const waterings = await store.listWaterings(plant.id);
          byId('history-title').textContent = `Watering history for ${plant.name}`;
          byId('history-list').replaceChildren();
          for (const event of waterings) {
            const item = document.createElement('p');
            item.textContent = `Watered ${event.date}`;
            byId('history-list').append(item);
          }
          if (waterings.length === 0) byId('history-list').textContent = 'No watering recorded yet.';
          byId('history-dialog').showModal();
        } catch { showMessage('I couldn’t open watering history. Please try again.', true); }
      });
      fragment.append(card);
    }
    if (generation !== renderGeneration) return;
    byId('plants').replaceChildren(fragment);
    byId('empty').hidden = plants.length > 0;
    byId('care-summary').textContent = `${plants.length} ${plants.length === 1 ? 'plant' : 'plants'} saved on this phone`;
    wateringCards = renderedCards;
  }

  // Capacitor fires "resume" when the app returns to the foreground; background JS timers are
  // paused, so the one-minute check alone would miss a day change while the app was away.
  document.addEventListener('resume', refreshWateringButtons);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') refreshWateringButtons();
  });
  setInterval(refreshWateringButtons, 60_000);

  // Each opening of the add form gets a new number. A save that finishes after its form was
  // cancelled or reopened reports on the page instead of changing the newer form.
  let addFormOpening = 0;
  let selectedPhoto = null, previewUrl = null, photoInvalid = false, formSaving = false;
  function clearPhoto() {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = null; selectedPhoto = null; photoInvalid = false;
    byId('photo').value = '';
    byId('photo-preview').hidden = true;
    byId('photo-preview').removeAttribute('src');
    byId('photo-message').textContent = '';
  }
  byId('photo').addEventListener('change', () => {
    const file = byId('photo').files[0];
    clearPhoto();
    if (!file) return;
    if (!['image/jpeg', 'image/png'].includes(file.type) || file.size === 0 || file.size > 5 * 1024 * 1024) {
      photoInvalid = true;
      byId('photo-message').textContent = 'Choose a JPG or PNG under 5 MB, or continue without a photo.';
      return;
    }
    selectedPhoto = file;
    previewUrl = URL.createObjectURL(file);
    byId('photo-preview').src = previewUrl;
    byId('photo-preview').hidden = false;
    byId('photo-message').textContent = 'Online identification is not connected. Please type a name and confirm it with Save plant.';
  });
  byId('remove-photo').addEventListener('click', clearPhoto);
  byId('add-dialog').addEventListener('close', clearPhoto);
  function setSaving(value) {
    formSaving = value;
    for (const id of ['save-plant', 'photo', 'remove-photo']) byId(id).disabled = value;
  }
  async function photoData(file) {
    const bytes = new Uint8Array(await file.arrayBuffer());
    let binary = '';
    for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
    return btoa(binary);
  }
  function openAdd() {
    addFormOpening++;
    byId('form-message').textContent = '';
    byId('plant-form').reset();
    clearPhoto();
    setSaving(false);
    byId('add-dialog').showModal();
    byId('plant-name').focus();
  }
  byId('add-open').addEventListener('click', openAdd);
  byId('first-add').addEventListener('click', openAdd);
  for (const id of ['add-close', 'add-cancel']) {
    byId(id).addEventListener('click', () => byId('add-dialog').close());
  }
  byId('history-close').addEventListener('click', () => byId('history-dialog').close());
  byId('plant-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (formSaving) return;
    const opening = addFormOpening;
    const stillThisForm = () => opening === addFormOpening && byId('add-dialog').open;
    const name = byId('plant-name').value.trim();
    if (!name || name.length > 100 || photoInvalid) {
      byId('form-message').textContent = photoInvalid ? 'Choose a valid photo or continue without one.' : 'Enter a plant name to continue.';
      return;
    }
    const file = selectedPhoto;
    const species = byId('species').value;
    setSaving(true);
    byId('form-message').textContent = '';
    let photoId = null;
    if (file) {
      try { photoId = (await photos.save({data: await photoData(file), mimeType: file.type})).photoId; }
      catch {
        if (stillThisForm()) {
          byId('form-message').textContent = 'The photo was not saved. Choose a JPG or PNG under 5 MB and try again, or continue without a photo.';
          setSaving(false);
        } else showMessage('The photo and plant were not saved. Please try again.', true);
        return;
      }
    }
    try {
      await store.createPlant({
        name,
        species, photoId,
        intervalDays: 7,
        zone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
      });
    } catch (error) {
      if (stillThisForm()) {
        byId('form-message').textContent = error.code === 'invalid-plant' ? 'Enter a plant name to continue.' :
          'I couldn’t confirm this plant was saved. Reopen the app and check the list before trying again.';
        setSaving(false);
      } else if (error.code !== 'invalid-plant') {
        showMessage(`I couldn’t confirm ${name} was saved. Check your plants before adding it again.`, true);
      }
      return;
    }
    if (stillThisForm()) {
      byId('add-dialog').close();
      setSaving(false);
    }
    try {
      await render();
      showMessage('Saved on this phone: plant added.');
    } catch {
      showMessage('Saved on this phone, but I couldn’t refresh the list. Reopen the app to see it.', true);
    }
  });
  await render();
}

try {
  prepareNativeUi();
  void installSessionUi(globalThis.__gardenTestSession || registerPlugin('GardenSession'), document);
  await start(globalThis.__gardenTestStore || await openNativeStore());
} catch (error) {
  console.error('Little Garden could not open the phone garden', error);
  byId('care-summary').textContent = 'The phone garden could not open';
  showMessage('I couldn’t open your saved plants. Please close and reopen the app; your records were not erased.', true);
  byId('add-open').disabled = true;
}
