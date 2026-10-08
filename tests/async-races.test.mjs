import test from 'node:test';
import assert from 'node:assert/strict';
import {setup, deferred, plant} from './helpers/app-harness.mjs';
const photo = {type: 'image/jpeg', size: 123};

async function queuedPhoto(h) {
  h.run('openAdd()'); h.el('plant-name').value = 'Fern'; h.el('interval').value = '7'; h.el('photo').files = [photo];
  const changing = h.el('photo').fire('change');
  await h.el('plant-form').fire('submit');
  return {changing};
}

test('failed upload cancels queued Save and preserves the form for an explicit next action', async () => {
  const h = setup(), upload = deferred(), saves = [];
  h.api((path, options) => path === '/api/garden/photos' ? upload.promise : (saves.push(options.body), Promise.resolve(plant('a'))));
  const {changing} = await queuedPhoto(h);
  upload.reject(new Error('Photo upload failed.')); await changing; await h.el('plant-form').submitting;
  assert.equal(saves.length, 0, 'upload rejection must not automatically create a photo-less plant');
  assert.equal(h.el('add-dialog').open, true);
  assert.equal(h.el('plant-name').value, 'Fern');
  assert.match(h.el('photo-message').textContent, /failed/i);
  assert.match(h.el('form-message').textContent, /photo/i);
  await h.el('plant-form').fire('submit');
  assert.equal(saves.length, 1, 'an explicit later Save can continue without a photo');
  assert.equal(saves[0].photoId, null);
});

test('successful photo with unavailable identification still permits queued manual-name saving', async () => {
  const h = setup(), upload = deferred(), saves = [];
  h.api((path, options) => path === '/api/garden/photos' ? upload.promise : (saves.push(options.body), Promise.resolve(plant('a'))));
  const {changing} = await queuedPhoto(h);
  upload.resolve({photoId: 'kept-photo', status: 'unavailable', candidates: []}); await changing; await h.el('plant-form').submitting;
  assert.equal(saves.length, 1); assert.equal(saves[0].photoId, 'kept-photo');
});

for (const result of ['success', 'failure']) test(`old history ${result} cannot change a newly opened plant's draft`, async () => {
  const h = setup(), writing = deferred();
  h.api((path, options) => options?.method === 'POST' ? writing.promise : Promise.resolve([]));
  h.context.a = plant('a'); h.context.b = plant('b');
  await h.run('openHistory(a)'); h.el('past-date').value = '2026-10-01';
  const adding = h.el('past-form').fire('submit');
  h.el('history-dialog').close(); await h.run('openHistory(b)'); h.el('past-date').value = '2026-10-03';
  if (result === 'success') writing.resolve(plant('a')); else writing.reject(new Error('Fern save failed.'));
  await adding;
  assert.equal(h.el('past-date').value, '2026-10-03');
  assert.equal(h.el('past-message').textContent, '');
  assert.equal(h.el('history-title').textContent, 'Basil: watering history');
});

for (const action of ['water', 'undo']) {
  test(`${action} error remains visible after another card rerenders the plant list`, async () => {
    const a = plant('a', action === 'undo' ? {lastWatered: '2026-10-08', lastEventId: 'event-a'} : {});
    const h = setup({cards: true, initialPlants: [a, plant('b')]}), pending = deferred();
    h.api(path => path.includes('/a/') ? pending.promise : Promise.resolve(plant('b', {lastWatered: '2026-10-08', lastEventId: 'event-b'})));
    h.run('render()');
    const operation = h.card('a').querySelector(`.${action}`).fire('click');
    await h.card('b').querySelector('.water').fire('click');
    pending.reject(new Error('Fern request failed.'));
    await assert.doesNotReject(operation, 'a stale DOM status ID must not throw');
    assert.equal(h.card('a').querySelector('.card-message').textContent, 'Fern request failed.');
    h.run('render()');
    assert.equal(h.card('a').querySelector('.card-message').textContent, 'Fern request failed.');
  });
  test(`${action} remains pending and cannot be submitted twice after a rerender`, async () => {
    const a = plant('a', action === 'undo' ? {lastWatered: '2026-10-08', lastEventId: 'event-a'} : {});
    const h = setup({cards: true, initialPlants: [a, plant('b')]}), pending = deferred(); let requests = 0;
    h.api(path => { if (path.includes('/a/')) { requests++; return pending.promise; } return Promise.resolve(plant('b')); });
    h.run('render()'); const operation = h.card('a').querySelector(`.${action}`).fire('click');
    await h.card('b').querySelector('.water').fire('click');
    assert.equal(h.card('a').querySelector(`.${action}`).disabled, true);
    await h.card('a').querySelector(`.${action}`).fire('click'); assert.equal(requests, 1);
    pending.resolve(a); await operation;
  });
}

test('old history completion does not unlock a newer dialog save', async () => {
  const h = setup(), first = deferred(), second = deferred();
  h.api((path, options) => options?.method === 'POST' ? (path.includes('/a/') ? first.promise : second.promise) : Promise.resolve([]));
  h.context.a = plant('a'); h.context.b = plant('b');
  await h.run('openHistory(a)'); h.el('past-date').value = '2026-10-01';
  const a = h.el('past-form').fire('submit');
  h.el('history-dialog').close(); await h.run('openHistory(b)');
  assert.equal(h.el('past-add').disabled, false, 'a new dialog must not inherit the old request lock');
  h.el('past-date').value = '2026-10-03'; const b = h.el('past-form').fire('submit');
  first.resolve(plant('a')); await a;
  assert.equal(h.el('past-add').disabled, true, 'old finally must not unlock the current request');
  assert.equal(h.el('past-date').value, '2026-10-03');
  second.resolve(plant('b')); await b;
  assert.equal(h.el('past-add').disabled, false);
  assert.equal(h.el('past-date').value, '');
});

test('closing and reopening the same plant still invalidates its older history save', async () => {
  const h = setup(), writing = deferred();
  h.api((path, options) => options?.method === 'POST' ? writing.promise : Promise.resolve([]));
  h.context.a = plant('a'); await h.run('openHistory(a)'); h.el('past-date').value = '2026-10-01';
  const operation = h.el('past-form').fire('submit');
  h.el('history-dialog').close(); await h.run('openHistory(a)'); h.el('past-date').value = '2026-10-02';
  writing.resolve(plant('a')); await operation;
  assert.equal(h.el('past-date').value, '2026-10-02');
  assert.equal(h.el('past-message').textContent, '');
});

test('a replaced upload failure does not cancel the newer queued photo save', async () => {
  const h = setup(), first = deferred(), second = deferred(), saves = []; let uploads = 0;
  h.api((path, options) => path === '/api/garden/photos' ? (++uploads === 1 ? first.promise : second.promise)
    : (saves.push(options.body), Promise.resolve(plant('a'))));
  const {changing} = await queuedPhoto(h);
  h.el('photo').files = [photo]; const replacement = h.el('photo').fire('change');
  first.reject(new Error('Old upload failed.')); await changing;
  assert.equal(saves.length, 0);
  second.resolve({photoId: 'new-photo', status: 'suggestions', candidates: []}); await replacement; await h.el('plant-form').submitting;
  assert.equal(saves.length, 1); assert.equal(saves[0].photoId, 'new-photo');
});

test('closing a queued upload does not save or overwrite a reopened form', async () => {
  const h = setup(), upload = deferred(), saves = [], discarded = [];
  h.api((path, options) => path === '/api/garden/photos' ? upload.promise
    : options?.method === 'DELETE' ? (discarded.push(path), Promise.resolve())
    : (saves.push(options.body), Promise.resolve(plant('a'))));
  const {changing} = await queuedPhoto(h);
  h.run('closeAdd(); openAdd();'); h.el('plant-name').value = 'New draft';
  upload.resolve({photoId: 'old-photo', status: 'unavailable', candidates: []}); await changing;
  assert.equal(saves.length, 0); assert.equal(h.el('plant-name').value, 'New draft');
  assert.equal(h.el('photo-message').textContent, ''); assert.equal(discarded.length, 1);
});

test('repeated queued Save creates only one plant when the photo succeeds', async () => {
  const h = setup(), upload = deferred(), saves = [];
  h.api((path, options) => path === '/api/garden/photos' ? upload.promise : (saves.push(options.body), Promise.resolve(plant('a'))));
  const {changing} = await queuedPhoto(h); await h.el('plant-form').fire('submit');
  upload.resolve({photoId: 'kept-photo', status: 'unavailable', candidates: []}); await changing; await h.el('plant-form').submitting;
  assert.equal(saves.length, 1);
});

test('an old history Undo error cannot appear in another plant dialog', async () => {
  const h = setup(), writing = deferred();
  h.api((path, options) => options?.method === 'POST' ? writing.promise : Promise.resolve(path.includes('/a/') ? [{id: 'event-a', date: '2026-10-01', undone: false}] : []));
  h.context.a = plant('a'); h.context.b = plant('b'); await h.run('openHistory(a)');
  const undo = h.el('history-list').children[0].children[0].children[1];
  const operation = undo.fire('click'); h.el('history-dialog').close(); await h.run('openHistory(b)');
  writing.reject(new Error('Fern undo failed.')); await operation;
  assert.equal(h.el('past-message').textContent, '');
});

test('retrying a failed photo replaces the stale save-without-photo warning', async () => {
  const h = setup(), first = deferred(), second = deferred(); let uploads = 0;
  h.api(path => path === '/api/garden/photos' ? (++uploads === 1 ? first.promise : second.promise) : Promise.resolve());
  const {changing} = await queuedPhoto(h); first.reject(new Error('Photo upload failed.')); await changing;
  assert.match(h.el('form-message').textContent, /photo was not added/i);
  h.el('photo').files = [photo]; const retry = h.el('photo').fire('change');
  second.resolve({photoId: 'retry-photo', status: 'unavailable', candidates: []}); await retry;
  assert.equal(h.el('form-message').textContent, '');
  assert.match(h.el('photo-message').textContent, /photo is kept/i);
});
