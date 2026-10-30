import {execFileSync} from 'node:child_process';
import {instrumentStartup} from './instrument-startup.mjs';
import {cpSync, mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
import {build} from 'esbuild';
import {nativeIndexHtml} from './native-index.mjs';

const mobile = resolve(fileURLToPath(new URL('..', import.meta.url)));
const staticDir = resolve(mobile, '../api/src/main/resources/static');
const diagnostic = process.argv.includes('--startup-test');
const output = resolve(mobile, diagnostic ? 'android/app/src/startupTest/assets/public' : 'www');
rmSync(output, {recursive: true, force: true});
mkdirSync(output, {recursive: true});
for (const file of ['style.css', 'icon-192.png', 'icon-512.png', 'garden-utils.mjs']) {
  cpSync(resolve(staticDir, file), resolve(output, file));
}
const html = nativeIndexHtml(readFileSync(resolve(staticDir, 'index.html'), 'utf8'));
writeFileSync(resolve(output, 'index.html'), html);
const entry = diagnostic ? {stdin: {
  contents: instrumentStartup(readFileSync(resolve(mobile, 'native-shell.js'), 'utf8')),
  resolveDir: mobile, sourcefile: 'native-shell-startup-test.js'
}} : {entryPoints: [resolve(mobile, 'native-shell.js')]};
await build({...entry, outfile: resolve(output, 'native-shell.js'),
  define: diagnostic ? {__STARTUP_BUILD__: JSON.stringify(execFileSync('git', ['rev-parse', '--short=12', 'HEAD'], {cwd: mobile, encoding: 'utf8'}).trim())} : {},
  bundle: true, format: 'esm', platform: 'browser', target: 'es2022'});
