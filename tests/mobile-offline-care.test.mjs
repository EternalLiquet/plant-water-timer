import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {mkdtempSync, rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {openGardenStore, localDate} from '../apps/mobile/garden-store.mjs';

function sqliteAdapter(file) {
  const sqlite = new DatabaseSync(file);
  sqlite.exec('PRAGMA foreign_keys = ON');
  return {
    async execute(sql) { sqlite.exec(sql); },
    async run(sql, values = []) { return {changes: {changes: sqlite.prepare(sql).run(...values).changes}}; },
    async query(sql, values = []) { return {values: sqlite.prepare(sql).all(...values)}; },
    async beginTransaction() { sqlite.exec('BEGIN'); },
    async commitTransaction() { sqlite.exec('COMMIT'); },
    async rollbackTransaction() { sqlite.exec('ROLLBACK'); },
    close() { sqlite.close(); }
  };
}

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
