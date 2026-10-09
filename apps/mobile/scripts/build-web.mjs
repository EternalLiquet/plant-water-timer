import {cpSync, mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';

const mobile = resolve(fileURLToPath(new URL('..', import.meta.url)));
const staticDir = resolve(mobile, '../api/src/main/resources/static');
const output = resolve(mobile, 'www');
rmSync(output, {recursive: true, force: true});
mkdirSync(output, {recursive: true});
for (const file of ['style.css', 'icon-192.png', 'icon-512.png', 'garden-utils.mjs']) {
  cpSync(resolve(staticDir, file), resolve(output, file));
}
let html = readFileSync(resolve(staticDir, 'index.html'), 'utf8');
for (const [before, after] of [
  ['<link rel="manifest" href="/manifest.webmanifest">\n', ''],
  ['src="/app.js"', 'src="./native-shell.js"'],
  ['href="/style.css"', 'href="./style.css"'],
  ['href="/icon-192.png"', 'href="./icon-192.png"'],
  ['href="/" aria-label="Little Garden home"', 'href="./" aria-label="Little Garden home"']
]) {
  if (!html.includes(before)) throw new Error(`Source UI changed: missing ${before}`);
  html = html.replace(before, after);
}
writeFileSync(resolve(output, 'index.html'), html);
cpSync(resolve(mobile, 'native-shell.js'), resolve(output, 'native-shell.js'));
