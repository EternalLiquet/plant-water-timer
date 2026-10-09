import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, existsSync} from 'node:fs';
import {execFileSync} from 'node:child_process';

test('Android asset step bundles the existing UI into an offline shell', () => {
  execFileSync(process.execPath, ['apps/mobile/scripts/build-web.mjs']);
  const root = 'apps/mobile/www/';
  const index = readFileSync(root + 'index.html', 'utf8');
  const shell = readFileSync(root + 'native-shell.js', 'utf8');
  const config = readFileSync('apps/mobile/capacitor.config.json', 'utf8');
  assert.equal(readFileSync(root + 'style.css', 'utf8'),
    readFileSync('apps/api/src/main/resources/static/style.css', 'utf8'));
  assert.match(index, /id="plant-template"/);
  assert.match(index, /native-shell\.js/);
  assert.doesNotMatch(index, /src="\/app\.js"|rel="manifest"/);
  assert.doesNotMatch(shell, /fetch\(|serviceWorker\.register|\/api\/|https?:\/\//);
  assert.equal(existsSync(root + 'sw.js'), false);
  assert.equal(existsSync(root + 'manifest.webmanifest'), false);
  assert.equal(JSON.parse(config).server, undefined);
});
