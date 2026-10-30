// Derive diagnostics from unchanged PR28 startup code; never rewrite the production source.
export function instrumentStartup(source) {
  const replacements = [
    ['    const plants = await store.listPlants();', "    const plants = await store.listPlants();\n    startupDiagnostic.stage('garden-render');"],
    ["  const {CapacitorSQLite, SQLiteConnection} = await import('@capacitor-community/sqlite');",
      "  startupDiagnostic.stage('sqlite-module');\n  const {CapacitorSQLite, SQLiteConnection} = await import('@capacitor-community/sqlite');"],
    ['new SQLiteConnection(CapacitorSQLite)', 'startupDiagnostic.connection(new SQLiteConnection(CapacitorSQLite))'],
    ['  prepareNativeUi();', "  startupDiagnostic.stage('ui');\n  prepareNativeUi();"],
    ['  await start(globalThis.__gardenTestStore || await openNativeStore());',
      '  await start(globalThis.__gardenTestStore || await openNativeStore());\n  startupDiagnostic.ready();'],
    ["  console.error('Little Garden could not open the phone garden', error);", '  startupDiagnostic.fail(error);']
  ];
  for (const [before, after] of replacements) {
    if (source.split(before).length !== 2) throw new Error('PR28 startup diagnostic anchor changed');
    source = source.replace(before, after);
  }
  return "import {startupDiagnostic as createStartupDiagnostic} from './startup-diagnostics.mjs';\n" +
    'const startupDiagnostic = createStartupDiagnostic(__STARTUP_BUILD__);\n' + source;
}
