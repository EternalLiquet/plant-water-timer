import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {mkdtempSync, rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {openGardenStore, localDate, connectGardenDatabase} from '../apps/mobile/garden-store.mjs';

// Mirrors @capacitor-community/sqlite: native connections outlive a WebView reload, while the JS
// SQLiteConnection wrapper starts empty. Consistency checks close native connections JS does not know.
function fakeSqlitePlugin(nativeConnections) {
  const known = new Map();
  return {
    async checkConnectionsConsistency() {
      const consistent = [...nativeConnections].every(name => known.has(name));
      if (!consistent) { nativeConnections.clear(); known.clear(); }
      return {result: consistent};
    },
    async isConnection(name) { return {result: known.has(name)}; },
    async retrieveConnection(name) { return known.get(name); },
    async createConnection(name) {
      if (nativeConnections.has(name)) throw new Error(`Connection ${name} already exists`);
      nativeConnections.add(name);
      const db = {opens: 0, isOpen: false,
        async open() { if (this.isOpen) throw new Error('already open'); this.opens++; this.isOpen = true; },
        async isDBOpen() { return {result: this.isOpen}; }};
      known.set(name, db);
      return db;
    }
  };
}

test('a WebView reload reopens the phone garden despite a leftover native connection', async () => {
  const nativeConnections = new Set();
  await connectGardenDatabase(fakeSqlitePlugin(nativeConnections));
  const afterReload = await connectGardenDatabase(fakeSqlitePlugin(nativeConnections));
  assert.equal((await afterReload.isDBOpen()).result, true);
});

test('opening the phone garden twice in one page reuses the open connection', async () => {
  const sqlite = fakeSqlitePlugin(new Set());
  const first = await connectGardenDatabase(sqlite);
  const second = await connectGardenDatabase(sqlite);
  assert.equal(second, first);
  assert.equal(second.opens, 1);
});

test('an unnamed plant is rejected with a recognizable validation error', async () => {
  const db = sqliteAdapter(':memory:');
  try {
    const store = await openGardenStore(db);
    await assert.rejects(store.createPlant({name: '  ', species: '', intervalDays: 7, zone: 'UTC'}),
      error => error.code === 'invalid-plant');
    await assert.rejects(store.createPlant({name: 'Fern', species: '', intervalDays: 7, zone: 'Not/AZone'}),
      error => error.code === 'invalid-plant');
  } finally { db.close(); }
});

function sqliteAdapter(file) {
  const sqlite = new DatabaseSync(file);
  sqlite.exec('PRAGMA foreign_keys = ON');
  return {
    beforeCommit: null,
    async execute(sql) { sqlite.exec(sql); },
    async run(sql, values = []) { return {changes: {changes: sqlite.prepare(sql).run(...values).changes}}; },
    async query(sql, values = []) { return {values: sqlite.prepare(sql).all(...values)}; },
    async beginTransaction() { sqlite.exec('BEGIN'); },
    async commitTransaction() { await this.beforeCommit?.(); sqlite.exec('COMMIT'); },
    async rollbackTransaction() { sqlite.exec('ROLLBACK'); },
    close() { sqlite.close(); }
  };
}

// Holds a transaction just before COMMIT until the test releases it, or makes the commit fail.
function commitGate() {
  let markReached;
  let release;
  const reached = new Promise(resolve => { markReached = resolve; });
  const released = new Promise((resolve, reject) => { release = {commit: resolve, fail: reject}; });
  return {reached, release, async hold() { markReached(); await released; }};
}

const nextTurn = () => new Promise(resolve => setImmediate(resolve));

test('reads made during a watering save never show a watering that rolls back', async () => {
  const db = sqliteAdapter(':memory:');
  try {
    const store = await openGardenStore(db);
    const plant = await store.createPlant({name: 'Fern', species: '', intervalDays: 7, zone: 'UTC'});
    const gate = commitGate();
    db.beforeCommit = gate.hold;
    const watering = store.recordWatering(plant.id, '2026-10-14');
    await gate.reached;
    const history = store.listWaterings(plant.id);
    const plants = store.listPlants();
    gate.release.fail(new Error('disk full'));
    await assert.rejects(watering, /disk full/);
    db.beforeCommit = null;
    assert.deepEqual(await history, []);
    assert.equal((await plants)[0].lastWatered, null);
    assert.equal((await store.recordWatering(plant.id, '2026-10-14')).created, true);
  } finally { db.close(); }
});

test('reads made during a watering save wait for it and show it once committed', async () => {
  const db = sqliteAdapter(':memory:');
  try {
    const store = await openGardenStore(db);
    const plant = await store.createPlant({name: 'Fern', species: '', intervalDays: 7, zone: 'UTC'});
    const gate = commitGate();
    db.beforeCommit = gate.hold;
    const watering = store.recordWatering(plant.id, '2026-10-14');
    await gate.reached;
    let historyRead = false;
    const history = store.listWaterings(plant.id).then(rows => { historyRead = true; return rows; });
    const plants = store.listPlants();
    await nextTurn();
    assert.equal(historyRead, false);
    gate.release.commit();
    await watering;
    assert.deepEqual((await history).map(row => row.date), ['2026-10-14']);
    assert.equal((await plants)[0].lastWatered, '2026-10-14');
  } finally { db.close(); }
});

test('a plant and one watering survive store restart without duplicate same-day events', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'garden-care-'));
  const file = join(dir, 'garden.db');
  try {
    let db = sqliteAdapter(file);
    let store = await openGardenStore(db);
    const plant = await store.createPlant({name: 'Kitchen fern', species: 'Fern', intervalDays: 7, zone: 'America/New_York'});
    const first = await store.recordWatering(plant.id, '2026-10-14');
    const duplicate = await store.recordWatering(plant.id, '2026-10-14');
    assert.equal(first.created, true);
    assert.equal(duplicate.created, false);
    assert.equal(duplicate.id, first.id);
    db.close();

    db = sqliteAdapter(file);
    store = await openGardenStore(db);
    assert.deepEqual((await store.listPlants()).map(({name, species, intervalDays, zone, lastWatered}) =>
      ({name, species, intervalDays, zone, lastWatered})),
      [{name: 'Kitchen fern', species: 'Fern', intervalDays: 7, zone: 'America/New_York', lastWatered: '2026-10-14'}]);
    assert.equal((await store.listWaterings(plant.id)).length, 1);
    db.close();
  } finally { rmSync(dir, {recursive: true, force: true}); }
});

test('backdated watering does not move last watered backwards', async () => {
  const db = sqliteAdapter(':memory:');
  try {
    const store = await openGardenStore(db);
    const plant = await store.createPlant({name: 'Fern', species: '', intervalDays: 7, zone: 'UTC'});
    await store.recordWatering(plant.id, '2026-10-14');
    await store.recordWatering(plant.id, '2026-10-11');
    assert.equal((await store.listPlants())[0].lastWatered, '2026-10-14');
  } finally { db.close(); }
});

test('simultaneous same-day taps make one watering and preserve one event ID', async () => {
  const db = sqliteAdapter(':memory:');
  try {
    const store = await openGardenStore(db);
    const plant = await store.createPlant({name: 'Fern', species: '', intervalDays: 7, zone: 'UTC'});
    const results = await Promise.all(Array.from({length: 4}, () => store.recordWatering(plant.id, '2026-10-14')));
    assert.equal(results.filter(result => result.created).length, 1);
    assert.equal(new Set(results.map(result => result.id)).size, 1);
    assert.equal((await store.listWaterings(plant.id)).length, 1);
  } finally { db.close(); }
});

test('a phone instant uses the plant time zone to choose its watering date', () => {
  const instant = new Date('2026-10-14T01:30:00Z');
  assert.equal(localDate('America/New_York', instant), '2026-10-13');
  assert.equal(localDate('Asia/Tokyo', instant), '2026-10-14');
});

test('a newer local schema is rejected without erasing records', async () => {
  const db = sqliteAdapter(':memory:');
  try {
    const store = await openGardenStore(db);
    await store.createPlant({name: 'Fern', species: '', intervalDays: 7, zone: 'UTC'});
    await db.execute('PRAGMA user_version = 2;');
    await assert.rejects(openGardenStore(db), /newer app version/);
    assert.equal((await db.query('SELECT count(*) AS total FROM plants')).values[0].total, 1);
  } finally { db.close(); }
});
