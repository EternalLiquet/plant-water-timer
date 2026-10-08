import {today, todayInZone, formatDate, dueLabel, sortForCare, careSummary} from './garden-utils.mjs';

const $ = id => document.getElementById(id);
const uuid = () => crypto.randomUUID();
const deviceZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

let session = null;
let plants = [];
let daySignature = '';
let idCounter = 0;
const plantDays = () => plants.map(p => todayInZone(p.zone)).join(',');
// One id per plant per calendar day, so a retried tap is saved once.
const wateringRequests = new Map();

function message(id, text, error = false) {
  $(id).textContent = text;
  $(id).classList.toggle('error', error);
}

/* ---------- Server calls ---------- */

async function loadSession() {
  const response = await fetch('/api/session', {cache: 'no-store', credentials: 'same-origin'});
  if (!response.ok) throw new Error("Your garden isn't responding. Refresh the page to try again.");
  session = await response.json();
  return session;
}

async function api(path, {method = 'GET', body} = {}) {
  const headers = {};
  if (method !== 'GET') headers[session.csrfHeader] = session.csrfToken;
  if (body && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(path, {method, headers, body, credentials: 'same-origin', cache: 'no-store'});
  } catch {
    throw new Error("Couldn't reach your garden. Check your internet connection and try again.");
  }
  if (response.status === 401) {
    showSignedOut('You were signed out after a while away. Sign in again to keep going.');
    throw new Error('Please sign in again.');
  }
  let data = null;
  try { data = await response.json(); } catch { /* Some responses have no body. */ }
  if (!response.ok) {
    if (response.status === 403) throw new Error('This page is out of date. Refresh the page and try again.');
    if (response.status === 413) throw new Error('That photo is too large. Choose one under 5 MB.');
    throw new Error(data?.message || "That didn't save. Please try again.");
  }
  return data;
}

/* ---------- Signing in and out ---------- */

function showSignedOut(text = '') {
  $('garden').hidden = true;
  $('sign-in').hidden = false;
  for (const dialog of document.querySelectorAll('dialog')) if (dialog.open) dialog.close();
  message('login-message', text, !!text);
  // Fetch a fresh security token so the next sign-in works even though the old session ended.
  loadSession().catch(() => {});
}

async function postLogin(password) {
  const form = new URLSearchParams({username: 'gardener', password, _csrf: session.csrfToken});
  return fetch('/login', {method: 'POST', body: form, credentials: 'same-origin', cache: 'no-store'});
}

$('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  const password = $('password').value;
  if (!password) { message('login-message', 'Enter your garden password.', true); return; }
  $('login-submit').disabled = true;
  message('login-message', 'Opening your garden…');
  try {
    await loadSession();
    let response = await postLogin(password);
    // A page left open can hold an expired token. Get a fresh one and try once more.
    if (response.status === 403) { await loadSession(); response = await postLogin(password); }
    if (response.status === 429) {
      message('login-message', 'Too many tries. Wait a minute, then try again.', true);
    } else if (!response.ok || new URL(response.url).searchParams.get('login') === 'failed') {
      message('login-message', "That password didn't work. Please try again.", true);
      $('password').select();
    } else {
      $('password').value = '';
      await start();
    }
  } catch {
    message('login-message', "Couldn't reach your garden. Check your internet connection and try again.", true);
  } finally {
    $('login-submit').disabled = false;
  }
});

$('sign-out').addEventListener('click', async () => {
  try { await api('/logout', {method: 'POST'}); location.assign('/'); }
  catch (error) { message('page-message', error.message, true); }
});

/* ---------- Plant list ---------- */

async function refresh() {
  plants = await api('/api/garden/plants');
  render();
}

function withName(button, label, name) {
  // Visible text stays short; screen readers hear which plant the button is for.
  // The spoken name starts with the visible words so voice control still matches them.
  button.textContent = label;
  button.setAttribute('aria-label', `${label}, ${name}`);
}

function render() {
  daySignature = plantDays();
  const container = $('plants');
  container.replaceChildren();
  $('empty').hidden = plants.length > 0;
  $('care-summary').textContent = careSummary(plants);
  for (const plant of sortForCare(plants)) container.append(card(plant));
}

function card(plant) {
  const node = $('plant-template').content.firstElementChild.cloneNode(true);
  const plantToday = todayInZone(plant.zone);
  node.querySelector('.plant-name').textContent = plant.name;
  const species = node.querySelector('.species');
  species.textContent = plant.species || '';
  species.hidden = !plant.species;

  const image = node.querySelector('img');
  const placeholder = node.querySelector('.placeholder');
  if (plant.photoId) {
    image.src = `/api/garden/photos/${plant.photoId}`;
    image.alt = `Photo of ${plant.name}`;
    placeholder.hidden = true;
    image.addEventListener('error', () => { image.hidden = true; placeholder.hidden = false; });
  } else {
    image.hidden = true;
  }

  const due = node.querySelector('.due');
  due.textContent = dueLabel(plant.nextCheck, plantToday);
  due.classList.toggle('attention', !!plant.nextCheck && plant.nextCheck <= plantToday);
  node.querySelector('.last').textContent = plant.lastWatered
    ? `Last watered ${plant.lastWatered === plantToday ? 'today' : formatDate(plant.lastWatered, undefined, plantToday)}`
    : 'No watering recorded yet';

  const water = node.querySelector('.water');
  const undo = node.querySelector('.undo');
  const status = node.querySelector('.card-message');
  status.id = `card-status-${++idCounter}`;
  const done = plant.lastWatered === plantToday;
  water.classList.toggle('done', done);
  water.disabled = done;
  withName(water, done ? '✓ Watered today' : 'Watered today', plant.name);
  undo.hidden = !done || !plant.lastEventId;
  withName(undo, 'Undo', plant.name);
  withName(node.querySelector('.history'), 'History', plant.name);
  withName(node.querySelector('.edit'), 'Edit', plant.name);

  water.addEventListener('click', async () => {
    if (water.disabled) return;
    water.disabled = true;
    water.setAttribute('aria-busy', 'true');
    withName(water, 'Saving…', plant.name);
    message(status.id, '');
    const date = todayInZone(plant.zone);
    const key = `${plant.id}:${date}`;
    if (!wateringRequests.has(key)) wateringRequests.set(key, uuid());
    try {
      const updated = await api(`/api/garden/plants/${plant.id}/water`,
        {method: 'POST', body: {eventId: wateringRequests.get(key), date, zone: plant.zone}});
      wateringRequests.delete(key);
      replacePlant(updated);
      message('page-message', `Nice! ${plant.name} is watered. ${dueLabel(updated.nextCheck, date)}.`);
    } catch (error) {
      message(status.id, error.message, true);
      water.disabled = false;
      water.removeAttribute('aria-busy');
      withName(water, 'Watered today', plant.name);
    }
  });
  undo.addEventListener('click', async () => {
    undo.disabled = true;
    try {
      replacePlant(await api(`/api/garden/plants/${plant.id}/water/${plant.lastEventId}/undo`, {method: 'POST'}));
      message('page-message', `Took back today's watering for ${plant.name}.`);
    } catch (error) {
      message(status.id, error.message, true);
      undo.disabled = false;
    }
  });
  node.querySelector('.history').addEventListener('click', () => openHistory(plant));
  node.querySelector('.edit').addEventListener('click', () => openEdit(plant));
  return node;
}

function replacePlant(updated) {
  plants = plants.map(p => (p.id === updated.id ? updated : p));
  render();
}

/* ---------- Add a plant ---------- */

// Pressing Save while the photo is still being looked at saves as soon as that finishes.
let pendingSave = false;
let formBusy = false, photoBusy = false, photoId = null, previewUrl = null, photoGeneration = 0, requestId = null;

function clearPreview() {
  if (previewUrl) URL.revokeObjectURL(previewUrl);
  previewUrl = null;
  $('photo-preview').hidden = true;
  $('photo-preview').removeAttribute('src');
  $('candidates').replaceChildren();
}

async function discard(id) {
  // Unused photos stay private and are removed automatically within a day if this fails.
  if (id) try { await api(`/api/garden/photos/${id}`, {method: 'DELETE'}); } catch { /* see above */ }
}

function openAdd() {
  if ($('add-dialog').open) return;
  $('plant-form').reset();
  photoId = null; requestId = uuid(); photoGeneration++; photoBusy = false; pendingSave = false;
  clearPreview();
  message('photo-message', ''); message('form-message', '');
  $('last-watered').max = today();
  $('last-watered').min = '1900-01-01';
  $('save-plant').disabled = false;
  $('add-dialog').showModal();
  $('plant-name').focus({preventScroll: true});
}

function closeAdd() {
  if (formBusy) return;
  photoGeneration++;
  void discard(photoId);
  photoId = null; photoBusy = false; pendingSave = false;
  clearPreview();
  $('add-dialog').close();
  $('add-open').focus();
}

$('add-open').addEventListener('click', openAdd);
$('first-add').addEventListener('click', openAdd);
$('add-close').addEventListener('click', closeAdd);
$('add-cancel').addEventListener('click', closeAdd);
$('add-dialog').addEventListener('cancel', event => { event.preventDefault(); closeAdd(); });

$('photo').addEventListener('change', async () => {
  const file = $('photo').files[0];
  if (!file) return;
  const generation = ++photoGeneration;
  void discard(photoId);
  photoId = null;
  clearPreview();
  // A newer choice replaces any photo still being looked at.
  photoBusy = false;
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 5 * 1024 * 1024) {
    $('photo').value = '';
    if (pendingSave) { pendingSave = false; message('form-message', ''); }
    message('photo-message', 'Please choose a JPG or PNG photo under 5 MB.', true);
    return;
  }
  previewUrl = URL.createObjectURL(file);
  $('photo-preview').src = previewUrl;
  $('photo-preview').hidden = false;
  photoBusy = true;
  message('photo-message', 'Looking at your photo… You can fill in the rest while you wait.');
  const body = new FormData();
  body.append('file', file, 'plant-photo');
  try {
    const result = await api('/api/garden/photos', {method: 'POST', body});
    if (generation !== photoGeneration || !$('add-dialog').open) { void discard(result.photoId); return; }
    photoId = result.photoId;
    message('photo-message', result.status === 'unavailable'
      ? "Plant suggestions aren't available right now. Your photo is kept; just type a name below."
      : result.candidates.length === 0
        ? "We couldn't tell what this is. Your photo is kept; just type a name below."
        : result.status === 'suggestions'
          ? 'It might be one of these. Tap one if it looks right.'
          : "We're not sure what this is. These are rough guesses; you can type your own.");
    for (const candidate of result.candidates) $('candidates').append(candidateButton(candidate));
  } catch (error) {
    if (generation === photoGeneration) message('photo-message', `${error.message} You can still add the plant by name.`, true);
  } finally {
    if (generation === photoGeneration) { photoBusy = false; if (pendingSave) { pendingSave = false; $('plant-form').requestSubmit(); } }
  }
});

function candidateButton(candidate) {
  const button = document.createElement('button');
  button.type = 'button';
  button.setAttribute('aria-pressed', 'false');
  const name = document.createElement('span');
  name.textContent = candidate.scientificName;
  const chance = document.createElement('span');
  chance.className = 'chance';
  chance.textContent = `${Math.round(candidate.confidence * 100)}% likely`;
  button.append(name, chance);
  button.addEventListener('click', () => {
    $('species').value = candidate.scientificName;
    if (!$('plant-name').value.trim()) $('plant-name').value = candidate.scientificName;
    for (const sibling of $('candidates').children) sibling.setAttribute('aria-pressed', 'false');
    button.setAttribute('aria-pressed', 'true');
    message('photo-message', 'Added as the plant type. You can change the name to anything you like.');
  });
  return button;
}

function intervalError(value) {
  const days = Number(value);
  return Number.isInteger(days) && days >= 1 && days <= 365 ? '' : 'Choose a number of days from 1 to 365.';
}

$('plant-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (formBusy) return;
  const name = $('plant-name').value.trim();
  if (!name) { message('form-message', 'Give your plant a name, like "Kitchen fern".', true); $('plant-name').focus(); return; }
  const daysProblem = intervalError($('interval').value);
  if (daysProblem) { message('form-message', daysProblem, true); $('interval').focus(); return; }
  const last = $('last-watered').value;
  if (last && last > today()) { message('form-message', "Last watered can't be in the future.", true); $('last-watered').focus(); return; }
  if (photoBusy) {
    pendingSave = true;
    message('form-message', 'Saving as soon as the photo is ready…');
    return;
  }
  formBusy = true;
  $('save-plant').disabled = true;
  $('save-plant').textContent = 'Saving…';
  message('form-message', '');
  try {
    const plant = await api('/api/garden/plants', {method: 'POST', body: {
      requestId, name, species: $('species').value.trim(), photoId,
      lastWatered: last || null, intervalDays: Number($('interval').value), zone: deviceZone()}});
    photoId = null;
    plants = plants.filter(p => p.id !== plant.id);
    plants.unshift(plant);
    render();
    formBusy = false;
    closeAdd();
    message('page-message', `${plant.name} added.`);
  } catch (error) {
    message('form-message', error.message, true);
  } finally {
    formBusy = false;
    $('save-plant').disabled = false;
    $('save-plant').textContent = 'Save plant';
  }
});

/* ---------- Edit or delete a plant ---------- */

let editing = null, editBusy = false;

function openEdit(plant) {
  editing = plant;
  $('edit-name').value = plant.name;
  $('edit-species').value = plant.species || '';
  $('edit-interval').value = plant.intervalDays;
  message('edit-message', '');
  $('delete-confirm').hidden = true;
  $('delete-start').hidden = false;
  $('edit-dialog').showModal();
}

function closeEdit() {
  if (editBusy) return;
  $('edit-dialog').close();
}

$('edit-close').addEventListener('click', closeEdit);
$('edit-cancel').addEventListener('click', closeEdit);
$('edit-dialog').addEventListener('cancel', event => { event.preventDefault(); closeEdit(); });

$('edit-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (editBusy || !editing) return;
  const name = $('edit-name').value.trim();
  if (!name) { message('edit-message', 'Give your plant a name.', true); $('edit-name').focus(); return; }
  const daysProblem = intervalError($('edit-interval').value);
  if (daysProblem) { message('edit-message', daysProblem, true); $('edit-interval').focus(); return; }
  editBusy = true;
  $('save-edit').disabled = true;
  try {
    const updated = await api(`/api/garden/plants/${editing.id}`, {method: 'PATCH', body: {
      name, species: $('edit-species').value.trim(), intervalDays: Number($('edit-interval').value)}});
    replacePlant(updated);
    editBusy = false;
    closeEdit();
    message('page-message', `Saved changes to ${updated.name}.`);
  } catch (error) {
    message('edit-message', error.message, true);
  } finally {
    editBusy = false;
    $('save-edit').disabled = false;
  }
});

$('delete-start').addEventListener('click', () => {
  $('delete-question').textContent = `Delete ${editing.name} and its watering history? This can't be undone.`;
  $('delete-start').hidden = true;
  $('delete-confirm').hidden = false;
  $('delete-keep').focus();
});
$('delete-keep').addEventListener('click', () => {
  $('delete-confirm').hidden = true;
  $('delete-start').hidden = false;
  $('delete-start').focus();
});
$('delete-yes').addEventListener('click', async () => {
  if (editBusy || !editing) return;
  editBusy = true;
  $('delete-yes').disabled = true;
  const removed = editing;
  try {
    await api(`/api/garden/plants/${removed.id}`, {method: 'DELETE'});
    plants = plants.filter(p => p.id !== removed.id);
    render();
    editBusy = false;
    closeEdit();
    message('page-message', `${removed.name} was deleted.`);
  } catch (error) {
    message('edit-message', error.message, true);
  } finally {
    editBusy = false;
    $('delete-yes').disabled = false;
  }
});

/* ---------- History ---------- */

let historyPlant = null, historyGeneration = 0;

async function openHistory(plant) {
  historyPlant = plant;
  $('history-title').textContent = `${plant.name}: watering history`;
  $('past-form').reset();
  $('past-date').max = todayInZone(plant.zone);
  $('past-date').min = '1900-01-01';
  message('past-message', '');
  if (!$('history-dialog').open) $('history-dialog').showModal();
  await loadHistory();
}

async function loadHistory() {
  const plant = historyPlant;
  const generation = ++historyGeneration;
  $('history-list').textContent = 'Loading…';
  try {
    const events = await api(`/api/garden/plants/${plant.id}/history`);
    if (generation !== historyGeneration || !$('history-dialog').open) return;
    $('history-list').replaceChildren();
    if (!events.length) { $('history-list').textContent = 'No watering recorded yet.'; return; }
    const list = document.createElement('ul');
    list.className = 'history';
    const plantToday = todayInZone(plant.zone);
    for (const entry of events) {
      const row = document.createElement('li');
      row.className = 'history-entry';
      const text = document.createElement('p');
      const label = () => `${entry.date === plantToday ? 'Today' : formatDate(entry.date, undefined, plantToday)}${entry.undone ? ' (taken back)' : ''}`;
      text.textContent = label();
      text.classList.toggle('muted', entry.undone);
      row.append(text);
      if (!entry.undone) {
        const undo = document.createElement('button');
        undo.className = 'quiet';
        withName(undo, 'Undo', `${plant.name}, ${formatDate(entry.date)}`);
        undo.addEventListener('click', async () => {
          undo.disabled = true;
          try {
            replacePlant(await api(`/api/garden/plants/${plant.id}/water/${entry.id}/undo`, {method: 'POST'}));
            entry.undone = true;
            text.textContent = label();
            text.classList.add('muted');
            undo.remove();
          } catch (error) {
            undo.disabled = false;
            message('past-message', error.message, true);
          }
        });
        row.append(undo);
      }
      list.append(row);
    }
    $('history-list').append(list);
  } catch (error) {
    if (generation === historyGeneration) $('history-list').textContent = error.message;
  }
}

$('past-form').addEventListener('submit', async event => {
  event.preventDefault();
  const plant = historyPlant;
  const date = $('past-date').value;
  if (!plant) return;
  if (!date) { message('past-message', 'Pick the day you watered.', true); return; }
  if (date > todayInZone(plant.zone)) { message('past-message', "That day hasn't happened yet.", true); return; }
  $('past-add').disabled = true;
  try {
    replacePlant(await api(`/api/garden/plants/${plant.id}/water`, {method: 'POST', body: {eventId: uuid(), date, zone: plant.zone}}));
    $('past-form').reset();
    message('past-message', `Added ${formatDate(date)}.`);
    await loadHistory();
  } catch (error) {
    message('past-message', error.message, true);
  } finally {
    $('past-add').disabled = false;
  }
});

$('history-close').addEventListener('click', () => $('history-dialog').close());
$('history-dialog').addEventListener('close', () => { historyGeneration++; historyPlant = null; });

/* ---------- Help, install, connection ---------- */

$('help-open').addEventListener('click', () => $('help-dialog').showModal());
$('help-close').addEventListener('click', () => $('help-dialog').close());

let installPrompt;
window.addEventListener('beforeinstallprompt', event => { event.preventDefault(); installPrompt = event; $('install').hidden = false; });
$('install').addEventListener('click', async () => {
  if (installPrompt) { await installPrompt.prompt(); installPrompt = null; $('install').hidden = true; }
});
window.addEventListener('online', () => message('page-message', "You're back online."));
window.addEventListener('offline', () => message('page-message', "You're offline. Changes won't save until you reconnect.", true));

/* ---------- Start ---------- */

async function start() {
  try {
    await loadSession();
    if (session.authenticated) {
      $('sign-in').hidden = true;
      $('garden').hidden = false;
      await refresh();
    } else {
      $('garden').hidden = true;
      $('sign-in').hidden = false;
      if (!session.configured) {
        $('login-form').hidden = true;
        message('login-message', "This garden isn't set up yet. Ask the person who set it up to finish.");
      }
    }
  } catch (error) {
    $('garden').hidden = true;
    $('sign-in').hidden = false;
    message('login-message', error.message, true);
  }
}

start();
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
setInterval(() => {
  if (session?.authenticated && plantDays() !== daySignature) render();
  $('last-watered').max = today();
}, 60000);
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && session?.authenticated) refresh().catch(error => message('page-message', error.message, true));
});
