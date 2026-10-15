// Phone-only schema v1. No server identity, sync receipt, or migration of browser data is implied.
const schema = `
CREATE TABLE plants (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 100),
  species TEXT NOT NULL,
  interval_days INTEGER NOT NULL CHECK(interval_days BETWEEN 1 AND 365),
  zone TEXT NOT NULL
);
CREATE TABLE waterings (
  id TEXT PRIMARY KEY,
  plant_id TEXT NOT NULL REFERENCES plants(id),
  watered_on TEXT NOT NULL,
  recorded_at TEXT NOT NULL,
  UNIQUE(plant_id, watered_on)
);
PRAGMA user_version = 1;
`;

function validDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const timestamp = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(timestamp) && new Date(timestamp).toISOString().slice(0, 10) === value;
}

function invalidPlant(message) {
  return Object.assign(new Error(message), {code: 'invalid-plant'});
}

function validatePlant({name, species, intervalDays, zone}) {
  const trimmed = name?.trim();
  if (!trimmed || trimmed.length > 100 || (species || '').length > 100 ||
      !Number.isInteger(intervalDays) || intervalDays < 1 || intervalDays > 365) {
    throw invalidPlant('Enter a plant name and a check interval from 1 to 365 days.');
  }
  if (typeof zone !== 'string' || !zone) throw invalidPlant('Choose a valid plant time zone.');
  try { new Intl.DateTimeFormat('en', {timeZone: zone}); }
  catch { throw invalidPlant('Choose a valid plant time zone.'); }
  return {name: trimmed, species: (species || '').trim(), intervalDays, zone};
}

// The native plugin keeps connections across WebView reloads, so a fresh page must sync with it
// before creating one; otherwise createConnection fails with "already exists".
export async function connectGardenDatabase(sqlite, name = 'little-garden') {
  const consistent = (await sqlite.checkConnectionsConsistency()).result;
  const known = (await sqlite.isConnection(name, false)).result;
  const database = consistent && known ? await sqlite.retrieveConnection(name, false) :
    await sqlite.createConnection(name, false, 'no-encryption', 1, false);
  if (!(await database.isDBOpen()).result) await database.open();
  return database;
}

async function transaction(db, action) {
  await db.beginTransaction();
  try {
    const result = await action();
    await db.commitTransaction();
    return result;
  } catch (error) {
    try { await db.rollbackTransaction(); } catch { /* Retain the original storage error. */ }
    throw error;
  }
}

export async function openGardenStore(db) {
  await db.execute('PRAGMA foreign_keys = ON;', false);
  const version = Number((await db.query('PRAGMA user_version;')).values[0]?.user_version ?? 0);
  if (version > 1) throw new Error('This garden was saved by a newer app version.');
  if (version === 0) await transaction(db, () => db.execute(schema, false));
  let writeQueue = Promise.resolve();
  function serializeWrite(action) {
    const result = writeQueue.then(action);
    writeQueue = result.catch(() => {});
    return result;
  }
  return {
    async createPlant(input) {
      return serializeWrite(async () => {
        const plant = validatePlant(input);
        const id = crypto.randomUUID();
        await db.run('INSERT INTO plants(id, name, species, interval_days, zone) VALUES (?, ?, ?, ?, ?)',
          [id, plant.name, plant.species, plant.intervalDays, plant.zone], false);
        return {id, ...plant};
      });
    },
    async listPlants() {
      const rows = (await db.query(`SELECT p.id, p.name, p.species, p.interval_days, p.zone,
        MAX(w.watered_on) AS last_watered FROM plants p LEFT JOIN waterings w ON w.plant_id = p.id
        GROUP BY p.id ORDER BY p.name COLLATE NOCASE, p.id`)).values;
      return rows.map(row => ({id: row.id, name: row.name, species: row.species,
        intervalDays: row.interval_days, zone: row.zone, lastWatered: row.last_watered}));
    },
    async recordWatering(plantId, date) {
      if (!validDate(date)) throw new Error('Choose a valid watering date.');
      return serializeWrite(() => transaction(db, async () => {
        const plant = (await db.query('SELECT id FROM plants WHERE id = ?', [plantId])).values[0];
        if (!plant) throw new Error('This plant is no longer in this garden.');
        const id = crypto.randomUUID();
        await db.run(`INSERT INTO waterings(id, plant_id, watered_on, recorded_at)
          VALUES (?, ?, ?, ?) ON CONFLICT(plant_id, watered_on) DO NOTHING`,
          [id, plantId, date, new Date().toISOString()], false);
        const row = (await db.query('SELECT id FROM waterings WHERE plant_id = ? AND watered_on = ?',
          [plantId, date])).values[0];
        return {id: row.id, created: row.id === id};
      }));
    },
    async listWaterings(plantId) {
      return (await db.query('SELECT id, watered_on AS date FROM waterings WHERE plant_id = ? ORDER BY watered_on DESC',
        [plantId])).values;
    }
  };
}

export function localDate(zone, instant = new Date()) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(instant).map(part => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}
