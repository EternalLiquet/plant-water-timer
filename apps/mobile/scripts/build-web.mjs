import {cpSync, mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
import {nativeIndexHtml} from './native-index.mjs';

const mobile = resolve(fileURLToPath(new URL('..', import.meta.url)));
const staticDir = resolve(mobile, '../api/src/main/resources/static');
const output = resolve(mobile, 'www');
rmSync(output, {recursive: true, force: true});
mkdirSync(output, {recursive: true});
for (const file of ['style.css', 'icon-192.png', 'icon-512.png', 'garden-utils.mjs']) {
  cpSync(resolve(staticDir, file), resolve(output, file));
}
const html = nativeIndexHtml(readFileSync(resolve(staticDir, 'index.html'), 'utf8'));
writeFileSync(resolve(output, 'index.html'), html);
cpSync(resolve(mobile, 'native-shell.js'), resolve(output, 'native-shell.js'));
