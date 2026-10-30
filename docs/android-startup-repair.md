# Android garden startup repair — 2026-10-30

The user reported that the morning PR28 APK cannot open the phone garden, including after
closing and reopening the app. The root cause of that specific phone's first failure is
not yet confirmed: no device diagnostic or Android runtime execution is available here.

## Reproduced defect and narrow repair

The locked sqlite 8.1.1 Android implementation splits `execute` batches at `;\n`
(`SQLite/UtilsSQLite.getStatementsArray`), then calls SQLCipher `execSQL` once per command
(`SQLite/Database.execute`). The app's photo migration placed `ALTER TABLE` and
`PRAGMA user_version = 2` on one line. Single-statement execution adds `photo_id` but leaves
version 1. A later startup attempts the ALTER again and fails with a duplicate column.
The prior Node adapter used SQLite's full-batch `exec`, masking this boundary mismatch.

The new regressions use the real plugin JavaScript SQLiteConnection wrapper and a native
SQL-boundary model over actual SQLite. Before the repair, they fail with version 1 instead
of 2 and duplicate `photo_id`. After the repair, version assignment executes separately
inside the same transaction. An already-present nullable TEXT photo column is recognized
as the stranded state; only the version marker changes. Unknown column definitions fail
closed. Plants, photo references and complete watering rows remain unchanged. No database,
photo or history reset/deletion is introduced.

## Packaging/runtime evidence

`npm run sync` discovers sqlite 8.1.1 and emits the CapacitorSQLitePlugin class in
`capacitor.plugins.json`; BridgeActivity loads those generated plugin classes. The custom
photo/session plugins are also registered before bridge creation. No missing-registration
defect was established by this inspection.

`GardenStartupInstrumentedTest` launches the actual packaged MainActivity twice, checking
native SQLite registration, completed garden rendering and enabled Add plant. It uses no
fake garden store or online endpoint. Run only on a disposable test device. CI compiles the
test APK and reports whether preinstalled emulator system images exist; compilation is not
a runtime pass. The ordinary offline browser smoke uses a fake garden store, so its passing
result is not evidence that native SQLCipher startup works.

## Safe-update gate

The morning APK came from run 41018402442, head
`9e5c698b3313c19fe95b20a8f67454c1cc483cad`, artifact 12960606626. The workflow uses Gradle's
default debug signing and uploads only the APK; it does not retain the generated private
signing key. A new hosted build therefore must not be called an in-place update without
matching its signer to the installed APK and having access to the original signing key.
The APK contains the public certificate, not the private key needed to sign an update.
No signing-key change or key recovery from unrelated storage is authorized or attempted.

A fresh supported connector download returned a file reference for the morning archive,
but local materialization of its temporary URL returned HTTP403. No alternate route was
used. CI records the replacement APK's public certificate for comparison. Until signer
compatibility is established, the new artifact is a review/test build, not a safe update
recommendation. Do not uninstall the installed app, clear its data or reset its garden.
