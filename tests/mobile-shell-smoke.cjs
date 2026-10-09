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
      await route.fulfill({body: fs.readFileSync(file), contentType: type});
    });
    const page = await context.newPage();
    await page.goto('http://app.local/index.html');
    assert.equal(await page.locator('#care-summary').textContent(), 'Android shell preview');
    assert.equal(await page.locator('.plant-name').first().textContent(), 'Example fern');
    assert.equal(await page.locator('#add-open').isHidden(), true);
    assert.equal(await page.locator('.plant button:visible').count(), 0);
    console.log('Offline bundled shell smoke passed');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
