import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {diagnosticCode, watchNative} from '../apps/mobile/startup-diagnostics.mjs';
import {instrumentStartup} from '../apps/mobile/scripts/instrument-startup.mjs';

test('startup diagnostics show allowlisted categories without exception payloads', () => {
  const secret = 'token=private /data/user/0/private/photo.jpg my plant';
  assert.equal(diagnosticCode(new Error('duplicate column name: photo_id ' + secret)), 'DUPLICATE_PHOTO_COLUMN');
  assert.equal(diagnosticCode(new Error(secret)), 'STARTUP_ERROR');
  assert.equal(diagnosticCode(new TypeError(secret)), 'JS_TYPE_ERROR');
});

test('native diagnostic wrapper preserves receivers, arguments, results and original rejection', async () => {
  const stages = [];
  const error = new Error('private content');
  const native = {value: 7, async query(...args) { assert.equal(this, native); return [this.value, ...args]; },
    async execute() { throw error; }};
  const failures = [];
  const wrapped = watchNative(native, s => stages.push(s), 'database', s => failures.push(s));
  assert.deepEqual(await wrapped.query('SELECT 1', [2]), [7, 'SELECT 1', [2]]);
  await assert.rejects(wrapped.execute('private SQL'), e => e === error);
  assert.deepEqual(stages, ['database-query', 'database-execute']);
  assert.deepEqual(failures, ['database-execute']);
});

test('diagnostic generation keeps PR28 storage and startup provenance and fails on changed anchors', () => {
  // SHA-256 of the unchanged files from verified PR28 commit 9e5c698.
  const hashes = {'native-shell.js': '05a4d42c7c65f706bb00b5720414ab6255167550556e57d25e5aad383a13b5e3', 'garden-store.mjs': 'ae908b6ac13f17e95b66be36ef22146157a2de28f764532a1b7f407852afee3e'};
  for (const [file, hash] of Object.entries(hashes)) {
    assert.equal(createHash('sha256').update(readFileSync(`apps/mobile/${file}`)).digest('hex'), hash);
  }
  const source = readFileSync('apps/mobile/native-shell.js', 'utf8');
  const instrumented = instrumentStartup(source);
  assert.match(instrumented, /startupDiagnostic\.fail\(error\)/);
  assert.doesNotMatch(instrumented, /console\.error/);
  assert.throws(() => instrumentStartup(source.replace('prepareNativeUi();', 'missing();')), /anchor/);
});
