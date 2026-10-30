import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, existsSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {nativeIndexHtml} from '../apps/mobile/scripts/native-index.mjs';

// Splits esbuild's unminified output into its per-module sections, dropping comments and license banners.
function bundledModules(bundle) {
  const modules = new Map();
  let name = null;
  for (const line of bundle.split(/\r?\n/)) {
    if (line.startsWith('/*! Bundled license information')) break;
    const marker = /^\/\/ (apps\/mobile\/\S+)$/.exec(line);
    if (marker) { name = marker[1]; modules.set(name, ''); continue; }
    if (name && !line.trim().startsWith('//')) modules.set(name, modules.get(name) + line + '\n');
  }
  return modules;
}

test('Android asset step accepts a Windows CRLF checkout of the web UI', () => {
  const source = readFileSync('apps/api/src/main/resources/static/index.html', 'utf8')
    .replace(/\r\n/g, '\n').replace(/\n/g, '\r\n');
  const index = nativeIndexHtml(source);
  assert.match(index, /src="\.\/native-shell\.js"/);
  assert.doesNotMatch(index, /rel="manifest"/);
});

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
  const modules = bundledModules(shell);
  assert.deepEqual([...modules.keys()].sort(), [
    'apps/mobile/garden-store.mjs',
    'apps/mobile/native-shell.js',
    'apps/mobile/node_modules/@capacitor-community/sqlite/dist/esm/definitions.js',
    'apps/mobile/node_modules/@capacitor-community/sqlite/dist/esm/index.js',
    'apps/mobile/node_modules/@capacitor-community/sqlite/dist/esm/web.js',
    'apps/mobile/node_modules/@capacitor/core/dist/index.js'
  ]);
  for (const [name, code] of modules) {
    // Capacitor core ships an unused web fetch fallback for its HTTP plugin; nothing here calls it.
    const allowed = name.endsWith('@capacitor/core/dist/index.js') ? /fetch\(url, requestInit\)/g : null;
    const checked = allowed ? code.replace(allowed, '') : code;
    assert.doesNotMatch(checked, /fetch\(|serviceWorker\.register|\/api\/|https?:\/\//, name);
  }
  assert.equal(existsSync(root + 'sw.js'), false);
  assert.equal(existsSync(root + 'manifest.webmanifest'), false);
  assert.equal(JSON.parse(config).server, undefined);
});

test('Android packaging disables bridge payload logging even in debug builds', () => {
  const config = JSON.parse(readFileSync('apps/mobile/capacitor.config.json', 'utf8'));
  assert.equal(config.loggingBehavior, 'none');
  // Android-specific settings take precedence over the shared setting.
  assert.equal(config.android?.loggingBehavior ?? config.loggingBehavior, 'none');
  execFileSync(process.execPath, ['scripts/build-web.mjs'], {cwd: 'apps/mobile'});
  execFileSync(process.execPath, ['node_modules/@capacitor/cli/bin/capacitor', 'copy', 'android'],
    {cwd: 'apps/mobile'});
  const packaged = JSON.parse(readFileSync(
    'apps/mobile/android/app/src/main/assets/capacitor.config.json', 'utf8'));
  assert.equal(packaged.loggingBehavior, 'none');
  assert.equal(packaged.android?.loggingBehavior ?? packaged.loggingBehavior, 'none');
});
