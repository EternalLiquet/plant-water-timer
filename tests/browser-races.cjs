// Run inside the existing authenticated smoke journey. Only HTTP responses are delayed;
// the production DOM, event handlers and browser form behavior stay real.
const assert = require('node:assert/strict');
function deferred() { let resolve; const promise = new Promise(yes => resolve = yes); return {promise, resolve}; }
async function holdPost(page, url) {
  const hit = deferred(), release = deferred();
  const handler = async route => {
    if (route.request().method() !== 'POST') return route.continue();
    hit.resolve();
    const result = await release.promise;
    if (result === 'fail') return route.fulfill({status: 500, contentType: 'application/json', body: JSON.stringify({message: 'Synthetic request failed.'})});
    if (result === 'unavailable') {
      const response = await route.fetch(); const body = await response.json();
      console.log('PHOTO_FALLBACK_BOUNDARY', JSON.stringify({status: response.status(), photoId: body.photoId, message: body.message}));
      return route.fulfill({response, json: {...body, status: 'unavailable', candidates: []}});
    }
    return route.continue();
  };
  await page.route(url, handler);
  return {hit: hit.promise, async finish(result = 'success') {
    const response = page.waitForResponse(r => r.url() === url && r.request().method() === 'POST');
    release.resolve(result); await (await response).finished();
    await page.evaluate(() => Promise.resolve());
    await page.unroute(url, handler);
  }};
}
const card = (page, name) => page.locator('.plant').filter({has: page.getByRole('heading', {name, exact: true})});
async function addNamed(page, name) {
  await page.locator('#add-open').click();
  await page.locator('#plant-name').fill(name);
  await page.getByRole('button', {name: 'Save plant', exact: true}).click();
  await page.getByRole('heading', {name, exact: true}).waitFor();
}
async function runAsyncRaceChecks(page, base) {
  // Failed upload must cancel the queued save, not silently drop the selected photo.
  let creates = 0;
  const countCreates = request => { if (request.url() === base + '/api/garden/plants' && request.method() === 'POST') creates++; };
  page.on('request', countCreates);
  await page.locator('#add-open').click(); await page.locator('#plant-name').fill('Photo failure draft');
  const failed = await holdPost(page, base + '/api/garden/photos');
  await page.locator('#photo').setInputFiles('tests/fixtures/synthetic-plant.png'); await failed.hit;
  await page.getByRole('button', {name: 'Save plant', exact: true}).click();
  await failed.finish('fail');
  await page.waitForFunction(() => document.getElementById('form-message').textContent.includes('photo was not added'));
  assert.equal(creates, 0); assert.equal(await page.locator('#add-dialog').isVisible(), true);
  assert.equal(await page.locator('#plant-name').inputValue(), 'Photo failure draft');
  await page.getByRole('button', {name: 'Save plant', exact: true}).click();
  await page.getByRole('heading', {name: 'Photo failure draft', exact: true}).waitFor(); assert.equal(creates, 1);
  page.off('request', countCreates);

  // Successful retained photo + unavailable identification is still a valid manual fallback.
  await page.locator('#add-open').click(); await page.locator('#plant-name').fill('Fallback Fern');
  const fallback = await holdPost(page, base + '/api/garden/photos');
  await page.locator('#photo').setInputFiles('tests/fixtures/synthetic-plant.png'); await fallback.hit;
  await page.getByRole('button', {name: 'Save plant', exact: true}).click(); await fallback.finish('unavailable');
  await page.getByRole('heading', {name: 'Fallback Fern', exact: true}).waitFor();
  assert.match(await card(page, 'Fallback Fern').locator('img').getAttribute('src'), /\/api\/garden\/photos\//);

  await addNamed(page, 'Race Basil');
  const a = 'Living room Monstera', b = 'Race Basil';
  const aid = await card(page, a).getAttribute('data-plant-id'), bid = await card(page, b).getAttribute('data-plant-id');
  const aWater = base + `/api/garden/plants/${aid}/water`, bWater = base + `/api/garden/plants/${bid}/water`;
  const watering = await holdPost(page, aWater);
  await page.getByRole('button', {name: `Watered today, ${a}`, exact: true}).click(); await watering.hit;
  await page.getByRole('button', {name: `Watered today, ${b}`, exact: true}).click();
  await page.getByRole('button', {name: `Watered today, done, ${b}`, exact: true}).waitFor();
  assert.equal(await card(page, a).locator('.water').isDisabled(), true);
  await watering.finish('fail');
  await page.waitForFunction(id => [...document.querySelectorAll('.plant')].find(p => p.dataset.plantId === id)?.querySelector('.card-message').textContent.includes('Synthetic request failed'), aid);
  assert.equal(await card(page, a).locator('.water').isEnabled(), true);
  // Retry A succeeds, then B rerenders the cards while A's Undo is awaiting a failure.
  await page.getByRole('button', {name: `Watered today, ${a}`, exact: true}).click();
  await page.getByRole('button', {name: `Watered today, done, ${a}`, exact: true}).waitFor();
  const undoPath = `${aWater}/*/undo`;
  const undoHit = deferred(), undoRelease = deferred(); let undoUrl;
  const undoHandler = async route => { undoUrl = route.request().url(); undoHit.resolve(); await undoRelease.promise; await route.fulfill({status: 500, contentType: 'application/json', body: '{"message":"Synthetic undo failed."}'}); };
  await page.route(undoPath, undoHandler);
  await page.getByRole('button', {name: `Undo, ${a}`, exact: true}).click(); await undoHit.promise;
  await page.getByRole('button', {name: `Undo, ${b}`, exact: true}).click();
  await page.getByRole('button', {name: `Watered today, ${b}`, exact: true}).waitFor();
  assert.equal(await card(page, a).locator('.undo').isDisabled(), true);
  const undoResponse = page.waitForResponse(r => r.url() === undoUrl); undoRelease.resolve(); await (await undoResponse).finished();
  await page.waitForFunction(id => [...document.querySelectorAll('.plant')].find(p => p.dataset.plantId === id)?.querySelector('.card-message').textContent.includes('Synthetic undo failed'), aid);
  await page.unroute(undoPath, undoHandler);

  // A's old completion must not reset B's new draft or unlock B's own pending save.
  const pastA = await holdPost(page, aWater), pastB = await holdPost(page, bWater);
  await page.getByRole('button', {name: `History, ${a}`, exact: true}).click();
  await page.locator('#past-date').fill('2026-01-06'); await page.locator('#past-add').click(); await pastA.hit;
  await page.getByRole('button', {name: 'Close history', exact: true}).click();
  await page.getByRole('button', {name: `History, ${b}`, exact: true}).click();
  assert.equal(await page.locator('#past-add').isEnabled(), true);
  await page.locator('#past-date').fill('2026-01-07'); await page.locator('#past-add').click(); await pastB.hit;
  await pastA.finish();
  assert.equal(await page.locator('#past-date').inputValue(), '2026-01-07');
  assert.equal(await page.locator('#past-message').textContent(), ''); assert.equal(await page.locator('#past-add').isDisabled(), true);
  await pastB.finish(); await page.waitForFunction(() => document.getElementById('past-add').disabled === false);
  await page.getByRole('button', {name: 'Close history', exact: true}).click();
  console.log('PASS: queued upload rejection/manual fallback, cross-card water/undo failure, and switched-history pending saves.');
}
module.exports = {runAsyncRaceChecks};
