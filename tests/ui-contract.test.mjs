import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const root = 'apps/api/src/main/resources/static/';
test('phone app has a clear four-action journey and ordinary inputs', () => {
 const html=fs.readFileSync(root+'index.html','utf8');
 for(const text of ['Add plant','Watered today','Last watered','type="date"','capture="environment"','Plant name']) assert.ok(html.includes(text),text);
 assert.ok(!html.includes('X-Owner-Id')); assert.ok(html.includes('lang="en"'));
});
test('install manifest starts at the private app, without offline-write promises', () => {
 const manifest=JSON.parse(fs.readFileSync(root+'manifest.webmanifest','utf8'));
 assert.equal(manifest.start_url,'/');assert.equal(manifest.display,'standalone');
 assert.ok(manifest.icons.some(x=>x.sizes==='192x192'));assert.ok(manifest.icons.some(x=>x.sizes==='512x512'));
});
test('offline shell never caches private records or photos',()=>{
 const sw=fs.readFileSync(root+'sw.js','utf8');
 assert.ok(sw.includes("!SHELL.includes(url.pathname)"));
 const list=sw.match(/const SHELL=\[(.*?)\]/s)[1];assert.ok(!list.includes('/api/'));
});
