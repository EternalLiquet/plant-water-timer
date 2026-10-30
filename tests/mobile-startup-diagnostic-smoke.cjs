const {chromium} = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({executablePath: process.env.CHROMIUM_PATH || undefined, headless: true});
  try {
    const context = await browser.newContext({offline: true});
    const root = path.resolve('apps/mobile/android/app/src/startupTest/assets/public');
    await context.route('http://diagnostic.local/**', route => {
      const name = new URL(route.request().url()).pathname.slice(1);
      const file = path.resolve(root, name);
      if (!file.startsWith(root + path.sep)) return route.abort();
      return route.fulfill({body: fs.readFileSync(file), contentType: name.endsWith('.html') ? 'text/html' :
        name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : 'image/png'});
    });
    const secret = 'PRIVATE_SENTINEL /data/private/photo.jpg token=secret plant=Fern';
    await context.addInitScript(secret => {
      window.androidBridge = {};
      window.Capacitor = {
        PluginHeaders: [{name: 'CapacitorSQLite', methods: ['checkConnectionsConsistency', 'createConnection']
          .map(name => ({name, rtype: 'promise'}))}],
        nativePromise: async (plugin, method) => {
          if (method === 'checkConnectionsConsistency') return {result: false};
          throw new Error('permission denied ' + secret);
        }
      };
    }, secret);
    const page = await context.newPage();
    const logs = [];
    page.on('console', message => logs.push(message.text()));
    await page.goto('http://diagnostic.local/index.html');
    await page.waitForFunction(() => document.getElementById('startup-diagnostic')?.textContent.includes('Error:'));
    const text = await page.locator('#startup-diagnostic').textContent();
    assert.match(text, /Little Garden Test/);
    assert.match(text, /PR28 baseline 9e5c698/);
    assert.match(text, /Stage: connection-createConnection/);
    assert.match(text, /Error: STORAGE_ACCESS_DENIED/);
    assert.equal(await page.locator('#add-open').isDisabled(), true);
    assert.doesNotMatch(await page.locator('body').textContent(), /PRIVATE_SENTINEL|photo\.jpg|token=secret|plant=Fern/);
    assert.doesNotMatch(logs.join('\n'), /PRIVATE_SENTINEL|token=secret/);
    console.log('Diagnostic bundle native-boundary failure and privacy smoke passed');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
