import {openGardenStore, localDate, connectGardenDatabase} from './garden-store.mjs';

const byId = id => document.getElementById(id);

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
  present(byId('add-dialog')?.querySelector('.photo-picker'), 'photo picker').hidden = true;
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
  async function render() {
    const plants = await store.listPlants();
    byId('plants').replaceChildren();
    byId('empty').hidden = plants.length > 0;
    byId('care-summary').textContent = `${plants.length} ${plants.length === 1 ? 'plant' : 'plants'} saved on this phone`;
    for (const plant of plants) {
      const card = byId('plant-template').content.firstElementChild.cloneNode(true);
      card.querySelector('.plant-name').textContent = plant.name;
      card.querySelector('.species').textContent = plant.species;
      card.querySelector('.due').textContent = 'Check the soil; water only if dry.';
      card.querySelector('.last').textContent = plant.lastWatered ? `Last watered ${plant.lastWatered}` : 'No watering recorded yet';
      card.querySelector('img').hidden = true;
      card.querySelector('.edit').hidden = true;
      card.querySelector('.undo').hidden = true;
      const today = localDate(plant.zone);
      const water = card.querySelector('.water');
      water.textContent = 'Watered today';
      water.disabled = plant.lastWatered === today;
      water.addEventListener('click', async () => {
        water.disabled = true;
        let result;
        try {
          result = await store.recordWatering(plant.id, localDate(plant.zone));
        } catch {
          // Same-day waterings are deduplicated, so retrying cannot record this one twice.
          showMessage('I couldn’t confirm the watering was saved. Tap Watered today again; it won’t be counted twice.', true);
          water.disabled = false;
          return;
        }
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
      byId('plants').append(card);
    }
  }

  function openAdd() {
    byId('form-message').textContent = '';
    byId('plant-form').reset();
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
    const save = byId('save-plant');
    save.disabled = true;
    byId('form-message').textContent = '';
    try {
      await store.createPlant({
        name: byId('plant-name').value,
        species: byId('species').value,
        intervalDays: 7,
        zone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
      });
    } catch (error) {
      byId('form-message').textContent = error.code === 'invalid-plant' ? 'Enter a plant name to continue.' :
        'I couldn’t confirm this plant was saved. Reopen the app and check the list before trying again.';
      save.disabled = false;
      return;
    }
    byId('add-dialog').close();
    save.disabled = false;
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
  await start(globalThis.__gardenTestStore || await openNativeStore());
} catch (error) {
  console.error('Little Garden could not open the phone garden', error);
  byId('care-summary').textContent = 'The phone garden could not open';
  showMessage('I couldn’t open your saved plants. Please close and reopen the app; your records were not erased.', true);
  byId('add-open').disabled = true;
}
