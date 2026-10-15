# Android offline care and local photos

This Capacitor app bundles the garden UI and uses a private on-phone SQLite database. In this
slice you can choose a photo, explicitly name the plant and save it and record one watering per plant calendar day. The card shows
the latest watering and History shows saved dates. The app says “Saved on this phone” after a
successful write; it makes no server-save claim. Check the soil and decide whether to water.

The local schema is version 2; a transactional migration adds a nullable photo reference to version 1. Plants have stable UUIDs, a plant time zone and a check
interval; watering records have UUIDs and calendar dates. Duplicate same-day taps reuse the
existing record. Dates are interpreted in the plant's stored time zone. A failed schema upgrade
or storage open stops care actions and displays an error without clearing the database.

This is deliberately smaller than issue #16's complete offline garden: online identification, edits,
Undo, delete, outbox, sync, server pairing, reminders and migration from another garden remain
for later reviewed slices. There is no network garden connection. Android backup remains disabled,
so uninstalling or clearing app storage removes this phone's records. Debug APKs from different
build machines may have different signing keys; an update path and recovery are untested. Do not
use this draft with irreplaceable real care data.

## Build and test

Use Node 22.13+ (the store tests use the built-in `node:sqlite`), Java 21, Android SDK platform 36
and build tools 35.0.0 and 36.0.0. From a clean checkout, at the repository root:

```sh
npm ci --ignore-scripts --prefix apps/mobile
npm ci --ignore-scripts
npx playwright install chromium
node --test tests/mobile-offline-care.test.mjs tests/mobile-assets.test.mjs
npm run test:mobile-shell
cd apps/mobile
npm run apk:debug
```

Install the mobile dependencies first: the asset test builds the bundle with esbuild from
`apps/mobile/node_modules`. The browser smoke needs Playwright's Chromium. On a fresh Linux machine
use `npx playwright install --with-deps chromium` to add its system libraries, or set
`CHROMIUM_PATH` to an existing Chromium and skip the download.

The Android build uses the Capacitor SQLite plugin and bundles the existing garden HTML/CSS
without the browser app's network script, manifest or service worker. There is no `server.url`.
The browser smoke uses a synthetic store and disables networking; SQLite persistence is tested
separately against a real SQLite file in Node. A passing browser smoke or APK build does not
prove phone runtime behavior. No phone installation is part of this PR.

The application ID remains `com.eternalliquet.littlegarden`. The CI APK is debug signed and is
not a release. Keep the same application ID and signing key for any future data-preserving
update; no release key is created here.

## Local photo prerequisite for identification

Choose a JPG or PNG up to 5 MiB and 20 megapixels, type the name/type yourself, then press
Save plant. Choosing a photo never identifies or saves a plant automatically. Online identification
is visibly unavailable; manual naming works offline. No photo or name is sent to a server.
The existing backend remains the intended identification provider, but its private deployment
alone does not establish native cookie/CSRF transport. Before connecting it, complete the trusted
HTTPS origin, redirects, cookie/session expiry, CSRF and sign-out tests in
`docs/android-garden-contract.md`. Do not add a bearer token or weaken the web security boundary.

A small native bridge checks file size, signature, decoded format and dimensions, downsamples to
at most 2048 pixels on the longest side, applies JPEG orientation, and re-encodes pixels to JPEG
without copying EXIF/GPS or the original filename. Files live under private app storage; SQLite
stores only generated UUID references. The same application ID and signing key are still required
for data-preserving updates. Uninstall/clear-data still removes photos and care records.

The photo file is written before the plant reference. A photo write failure leaves the form open
and never silently saves without it. If the database write then fails ambiguously, keep the file:
deleting it could remove a photo referenced by a successful but unacknowledged write. Such files
and interrupted-process temporary files may remain private and unused; cleanup/export is deferred.
No existing files or care records are deleted. A missing photo does not block watering/history.

Node tests cover v1 migration rollback and preserved history/references; the offline browser smoke
uses a synthetic native photo bridge. Java unit tests cover native input limits and reference
validation. Bitmap encoding, metadata stripping, orientation and native file persistence still need
Android runtime/device checks; browser tests do not prove those. No device installation is performed.
