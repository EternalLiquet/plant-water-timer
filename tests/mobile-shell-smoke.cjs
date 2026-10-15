const {chromium} = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

async function main() {
  const root = path.resolve(__dirname, '../apps/mobile/www');
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.CHROMIUM_PATH ? {executablePath: process.env.CHROMIUM_PATH} : {})
  });
  try {
    const context = await browser.newContext({offline: true});
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
    const page = await context.newPage();
    await page.addInitScript(() => {
      const plants = [];
      const waterings = [];
      window.__gardenTestStore = {
        async createPlant(input) {
          const plant = {id: 'plant-1', ...input, lastWatered: null};
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
    });
    await page.goto('http://app.local/index.html');
    assert.match(await page.locator('#empty p').textContent(), /Add a name/);
    await page.locator('#add-open').click();
    await page.locator('#plant-name').fill('Kitchen fern');
    await page.locator('#save-plant').click();
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
    await page.locator('#add-open').click();
    await page.locator('#plant-name').fill('Second fern');
    await page.locator('#save-plant').click();
    assert.match(await page.locator('#page-message').textContent(), /Saved on this phone, but/);

    const changedPage = await context.newPage();
    await changedPage.goto('http://app.local/index.html?changed-ui');
    assert.equal(await changedPage.locator('#care-summary').textContent(), 'The phone garden could not open');
    assert.equal(await changedPage.locator('#add-open').isDisabled(), true);
    console.log('Offline bundled shell smoke passed');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
