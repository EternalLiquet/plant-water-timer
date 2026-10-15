const {chromium} = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../apps/mobile/www');
const appUrl = 'http://app.local/index.html';

// Runs in the page before the bundle. Test hooks: __failNextWatering makes the next watering throw;
// __holdNextCreate makes the next createPlant wait until __finishCreate(true | false) is called.
function installTestStore() {
  const photos = new Map();
  window.__gardenTestPhotos = {
    async save({data}) {
      if (window.__failPhoto) throw new Error("photo write failed");
      const photoId = `e6bd3c38-a1ac-4bfa-b91d-${String(photos.size + 1).padStart(12, '0')}`; photos.set(photoId, data); return {photoId};
    },
    async read({photoId}) {
      if (window.__holdPhotoRead) {
        window.__holdPhotoRead = false;
        await new Promise(resolve => { window.__finishPhotoRead = resolve; });
      }
      return {data: photos.get(photoId)};
    }
  };
  const plants = [];
  const waterings = [];
  window.__gardenTestStore = {
    async createPlant(input) {
      if (window.__holdNextCreate) {
        window.__holdNextCreate = false;
        await new Promise((resolve, reject) => {
          window.__finishCreate = succeeds => succeeds ? resolve() : reject(new Error('storage busy'));
        });
      }
      const plant = {id: `plant-${plants.length + 1}`, ...input, lastWatered: null};
      plants.push(plant);
      return plant;
    },
    async listPlants() {
      return plants.map(plant => ({...plant,
        lastWatered: waterings.filter(event => event.plantId === plant.id)
          .map(event => event.date).sort().at(-1) || null}));
    },
    async recordWatering(plantId, date) {
      if (window.__failNextWatering) { window.__failNextWatering = false; throw new Error('storage busy'); }
      if (waterings.some(event => event.plantId === plantId && event.date === date)) return {created: false};
      waterings.push({plantId, date});
      return {created: true};
    },
    async listWaterings(plantId) { return waterings.filter(event => event.plantId === plantId); }
  };
}

async function newAppContext(browser, options = {}) {
  const context = await browser.newContext({offline: true, ...options});
  await context.route('http://app.local/**', async route => {
    const relative = decodeURIComponent(new URL(route.request().url()).pathname).slice(1);
    const file = path.resolve(root, relative);
    if (!file.startsWith(root + path.sep)) return route.abort();
    const type = file.endsWith('.html') ? 'text/html' :
      file.endsWith('.js') ? 'text/javascript' :
      file.endsWith('.css') ? 'text/css' : 'image/png';
    let body = fs.readFileSync(file);
    // ?changed-ui simulates a later web UI edit that removes a field the phone shell hides.
    if (type === 'text/html' && new URL(route.request().url()).searchParams.has('changed-ui')) {
      body = body.toString('utf8').replace(/<input id="last-watered"[^>]*>/, '');
    }
    await route.fulfill({body, contentType: type});
  });
  await context.addInitScript(installTestStore);
  return context;
}

async function addPlant(page, name) {
  await page.locator('#add-open').click();
  await page.locator('#plant-name').fill(name);
  await page.locator('#save-plant').click();
}

async function waterAndWaitForSave(page) {
  await page.evaluate(() => { document.getElementById('page-message').textContent = ''; });
  await page.locator('.plant .water').click();
  await page.waitForFunction(() => /Saved on this phone: watering recorded/.test(
    document.getElementById('page-message').textContent));
}

async function careJourney(context) {
  const page = await context.newPage();
  await page.goto(appUrl);
  assert.match(await page.locator('#empty p').textContent(), /Add a name/);
  await addPlant(page, 'Kitchen fern');
  assert.equal(await page.locator('.plant-name').first().textContent(), 'Kitchen fern');
  assert.match(await page.locator('#page-message').textContent(), /Saved on this phone/);
  await page.evaluate(() => { window.__failNextWatering = true; });
  await page.locator('.plant .water').click();
  assert.match(await page.locator('#page-message').textContent(), /couldn’t confirm the watering/);
  assert.equal(await page.locator('.plant .water').isEnabled(), true);
  await page.locator('.plant .water').click();
  assert.match(await page.locator('.plant .last').textContent(), /Last watered/);
  assert.match(await page.locator('#page-message').textContent(), /Saved on this phone/);
  await page.evaluate(() => {
    const original = window.__gardenTestStore.listPlants;
    window.__gardenTestStore.listPlants = async () => {
      window.__gardenTestStore.listPlants = original;
      throw new Error('read failure after commit');
    };
  });
  await addPlant(page, 'Second fern');
  assert.match(await page.locator('#page-message').textContent(), /Saved on this phone, but/);
  await page.close();
}

async function changedUiShowsOpenError(context) {
  const page = await context.newPage();
  await page.goto(appUrl + '?changed-ui');
  assert.equal(await page.locator('#care-summary').textContent(), 'The phone garden could not open');
  assert.equal(await page.locator('#add-open').isDisabled(), true);
  await page.close();
}

// Save plant A slowly, cancel, start plant B, then let A finish. B's form and draft must survive.
async function earlierSaveLeavesNewerFormAlone(context, earlierSaveSucceeds) {
  const page = await context.newPage();
  await page.goto(appUrl);
  await page.evaluate(() => { window.__holdNextCreate = true; });
  await addPlant(page, 'Plant A');
  await page.waitForFunction(() => window.__finishCreate);
  await page.locator('#add-cancel').click();
  await page.locator('#add-open').click();
  await page.locator('#plant-name').fill('Plant B');
  await page.evaluate(succeeds => window.__finishCreate(succeeds), earlierSaveSucceeds);
  const expected = earlierSaveSucceeds ? 'Saved on this phone: plant added.' :
    'I couldn’t confirm Plant A was saved. Check your plants before adding it again.';
  await page.waitForFunction(text => document.getElementById('page-message').textContent === text, expected);
  assert.equal(await page.locator('#add-dialog').evaluate(dialog => dialog.open), true);
  assert.equal(await page.locator('#plant-name').inputValue(), 'Plant B');
  assert.equal(await page.locator('#form-message').textContent(), '');
  assert.equal(await page.locator('#save-plant').isEnabled(), true);
  assert.deepEqual(await page.locator('.plant-name').allTextContents(), earlierSaveSucceeds ? ['Plant A'] : []);
  await page.locator('#save-plant').click();
  await page.waitForFunction(() => !document.getElementById('add-dialog').open);
  assert.ok((await page.locator('.plant-name').allTextContents()).includes('Plant B'));
  await page.close();
}

// The app stays open across New York midnights without reloading. The plant's zone, not UTC,
// decides "today", and the button re-enables on resume, on becoming visible, and on rollover.
async function wateringReopensOnTheNextPlantDay(browser) {
  const context = await newAppContext(browser, {timezoneId: 'America/New_York'});
  const page = await context.newPage();
  await page.clock.install({time: new Date('2026-10-15T03:50:00Z')}); // 23:50 on Oct 14 in New York
  await page.goto(appUrl);
  await addPlant(page, 'Night fern');
  const water = page.locator('.plant .water');
  await waterAndWaitForSave(page);
  await page.clock.pauseAt(new Date('2026-10-15T03:55:00Z'));
  assert.equal(await water.isDisabled(), true, 'already watered on the New York day');

  // Backgrounded past midnight: timers are paused, then Capacitor fires "resume".
  await page.clock.setSystemTime(new Date('2026-10-15T04:05:00Z'));
  await page.evaluate(() => document.dispatchEvent(new Event('resume')));
  assert.equal(await water.isEnabled(), true, 'resume refreshes for the new day');

  // Left open across midnight with no lifecycle event.
  await waterAndWaitForSave(page);
  assert.equal(await water.isDisabled(), true);
  await page.clock.fastForward('24:00:00');
  assert.equal(await water.isEnabled(), true, 'the open app notices the next day');

  // Hidden and shown again past midnight.
  await waterAndWaitForSave(page);
  await page.clock.setSystemTime(new Date('2026-10-17T04:10:00Z'));
  await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  assert.equal(await water.isEnabled(), true, 'becoming visible refreshes for the new day');
  await context.close();
}

async function photoManualJourney(context) {
  const page = await context.newPage();
  await page.goto(appUrl);
  await page.locator('#add-open').click();
  assert.equal(await page.locator('#photo').isVisible(), true);
  await page.locator('#photo').setInputFiles(path.resolve(__dirname, 'fixtures/synthetic-plant.png'));
  assert.equal(await page.locator('#photo-preview').isVisible(), true);
  assert.match(await page.locator('#photo-message').textContent(), /type a name/);
  assert.equal(await page.locator('#plant-name').inputValue(), '');
  assert.equal(await page.locator('.plant').count(), 0, 'choosing a photo cannot save a plant');
  await page.locator('#plant-name').fill('Confirmed fern');
  await page.locator('#species').fill('My manual plant type');
  await page.evaluate(() => { window.__failPhoto = true; });
  await page.locator('#save-plant').click();
  await page.waitForFunction(() => /photo.*not saved/i.test(document.getElementById('form-message').textContent));
  assert.equal(await page.locator('.plant').count(), 0, 'failed photo must not silently save without it');
  await page.evaluate(() => { window.__failPhoto = false; });
  await page.locator('#save-plant').click();
  await page.waitForFunction(() => !document.getElementById('add-dialog').open);
  assert.equal(await page.locator('.plant-name').textContent(), 'Confirmed fern');
  assert.equal(await page.locator('.plant .species').textContent(), 'My manual plant type');
  assert.equal(await page.locator('.plant img').isVisible(), true);
  await page.locator('#add-open').click();
  assert.equal(await page.locator('#photo-preview').isVisible(), false);
  await page.locator('#photo').setInputFiles({name: 'invalid.jpg', mimeType: 'text/plain', buffer: Buffer.from('bad')});
  await page.locator('#plant-name').fill('No photo');
  await page.locator('#save-plant').click();
  assert.equal(await page.locator('.plant').count(), 1);
  await page.locator('#remove-photo').click();
  await page.locator('#save-plant').click();
  await page.waitForFunction(() => !document.getElementById('add-dialog').open);
  assert.equal(await page.locator('.plant').count(), 2);
  await page.close();
}

async function overlappingPhotoRendersShowOnlyLatestGarden(context) {
  const page = await context.newPage();
  await page.goto(appUrl);
  await page.locator('#add-open').click();
  await page.locator('#photo').setInputFiles(path.resolve(__dirname, 'fixtures/synthetic-plant.png'));
  await page.locator('#plant-name').fill('Photo plant');
  await page.locator('#save-plant').click();
  await page.waitForFunction(() => document.querySelectorAll('.plant').length === 1);
  await page.evaluate(() => { window.__holdPhotoRead = true; });
  await addPlant(page, 'Second plant');
  await page.waitForFunction(() => window.__finishPhotoRead);
  await addPlant(page, 'Third plant');
  await page.waitForFunction(() => document.querySelectorAll('.plant').length === 3);
  await page.evaluate(() => window.__finishPhotoRead());
  // A later store read queues behind both writes; allow the released render to complete.
  await page.evaluate(async () => { await window.__gardenTestStore.listPlants(); await new Promise(r => setTimeout(r, 0)); });
  assert.deepEqual(await page.locator('.plant-name').allTextContents(), ['Photo plant', 'Second plant', 'Third plant']);
  await page.close();
}

async function main() {
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.CHROMIUM_PATH ? {executablePath: process.env.CHROMIUM_PATH} : {})
  });
  try {
    const context = await newAppContext(browser);
    await overlappingPhotoRendersShowOnlyLatestGarden(context);
    await photoManualJourney(context);
    await careJourney(context);
    await changedUiShowsOpenError(context);
    await earlierSaveLeavesNewerFormAlone(context, true);
    await earlierSaveLeavesNewerFormAlone(context, false);
    await wateringReopensOnTheNextPlantDay(browser);
    console.log('Offline bundled shell smoke passed');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
