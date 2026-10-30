import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {SQLiteConnection} from '../apps/mobile/node_modules/@capacitor-community/sqlite/dist/esm/definitions.js';
import {connectGardenDatabase, openGardenStore} from '../apps/mobile/garden-store.mjs';

// Real plugin JS wrapper, with only the Android SQL/bridge boundary substituted.
// UtilsSQLite.getStatementsArray in sqlite 8.1.1 splits on ';\n'; Database.execute calls
// execSQL once per resulting command (one SQLite statement, unlike DatabaseSync.exec).
function nativeBoundary() {
  const sql = new DatabaseSync(':memory:');
  let connected = false, opened = false;
  const plugin = {
    async checkConnectionsConsistency({dbNames}) {
      if (!dbNames.length) { connected = false; opened = false; }
      return {result: connected};
    },
    async createConnection() { connected = true; },
    async isDBOpen() { return {result: opened}; },
    async open() { opened = true; },
    async execute({statements}) {
      for (const statement of statements.split(';\n').filter(s => s.trim())) sql.prepare(statement).run();
      return {changes: {changes: 0}};
    },
    async query({statement, values}) { return {values: sql.prepare(statement).all(...values)}; },
    async run({statement, values}) { return {changes: {changes: sql.prepare(statement).run(...values).changes}}; },
    async beginTransaction() { sql.exec('BEGIN'); },
    async commitTransaction() { sql.exec('COMMIT'); },
    async rollbackTransaction() { sql.exec('ROLLBACK'); }
  };
  return {sql, async open() { return openGardenStore(await connectGardenDatabase(new SQLiteConnection(plugin))); }};
}

const photoId = 'e6bd3c38-a1ac-4bfa-b91d-000000000001';
const plant = {name: 'Synthetic fern', species: 'Fern', intervalDays: 7, zone: 'UTC', photoId};

test('native SQL execution saves the schema version and reopens plants, photos and history', async () => {
  const fixture = nativeBoundary();
  try {
    const store = await fixture.open();
    const created = await store.createPlant(plant);
    await store.recordWatering(created.id, '2026-10-30');
    assert.equal(fixture.sql.prepare('PRAGMA user_version').get().user_version, 2);
    const reopened = await fixture.open();
    assert.deepEqual(await reopened.listPlants(), [{id: created.id, ...plant, lastWatered: '2026-10-30'}]);
    assert.equal((await reopened.listWaterings(created.id)).length, 1);
  } finally { fixture.sql.close(); }
});

test('repairs the old native version-1-with-photo-column state without changing saved records', async () => {
  const fixture = nativeBoundary();
  try {
    const store = await fixture.open();
    const created = await store.createPlant(plant);
    await store.recordWatering(created.id, '2026-10-30');
    fixture.sql.exec('PRAGMA user_version = 1;');
    const before = fixture.sql.prepare('SELECT * FROM plants').all();
    const events = fixture.sql.prepare('SELECT * FROM waterings').all();
    const reopened = await fixture.open();
    assert.equal((await reopened.listPlants())[0].photoId, photoId);
    assert.deepEqual(fixture.sql.prepare('SELECT * FROM plants').all(), before);
    assert.deepEqual(fixture.sql.prepare('SELECT * FROM waterings').all(), events);
    assert.equal(fixture.sql.prepare('PRAGMA user_version').get().user_version, 2);
  } finally { fixture.sql.close(); }
});

test('an unrecognized version-1 photo column is refused without rewriting the garden', async () => {
  const fixture = nativeBoundary();
  try {
    fixture.sql.exec('CREATE TABLE plants(id TEXT, photo_id INTEGER); PRAGMA user_version = 1;');
    await assert.rejects(fixture.open(), /photo column/);
    assert.equal(fixture.sql.prepare('PRAGMA user_version').get().user_version, 1);
    assert.equal(fixture.sql.prepare('PRAGMA table_info(plants)').all()[1].type, 'INTEGER');
  } finally { fixture.sql.close(); }
});
