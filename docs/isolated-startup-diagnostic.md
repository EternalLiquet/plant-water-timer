# Little Garden Test — isolated PR28 startup diagnostic

Preparation only. Do not install automatically or copy/migrate the original garden.
The installed morning app and PR29 have different signing certificates; PR29 is not an
in-place update. This test app uses a different application ID and its own empty sandbox.

- Package: `com.eternalliquet.littlegarden.startuptest`
- Launcher/activity label: **Little Garden Test**
- Baseline: PR28 `9e5c698b3313c19fe95b20a8f67454c1cc483cad`
- `native-shell.js` and `garden-store.mjs` remain byte-for-byte baseline files, verified by
  pinned SHA-256 tests. PR29's migration repair is deliberately absent.
- The build derives an instrumented shell only into `src/startupTest/assets/public`.
  Normal debug/release assets do not import the diagnostic module.
- Existing debug signing is reused for this isolated build type; production signing is
  unchanged and no persistent signing credentials are generated/configured by this change.
- The online bridge remains unconfigured, and bridge payload logging remains disabled.

The screen displays a public baseline/build identifier, last startup stage and an allowlisted
error category. It does not display raw exception messages, stacks, SQL, paths, tokens, plant
content or photos. Native calls retain their receiver/arguments/results and original errors;
transaction rollback cannot overwrite the first failing native-operation stage. An unknown
error is `STARTUP_ERROR`, not its payload. Existing native-library logs are not expanded.

Build: `npm run apk:startup-test` from `apps/mobile`. CI verifies the APK application ID,
label, diagnostic asset marker, plugin metadata, public signing certificate and APK SHA-256.
The browser diagnostic smoke substitutes only the native bridge boundary and checks that a
secret sentinel in an exception never appears in the DOM or console. It is not Android
runtime evidence. The known hosted environment has no preinstalled emulator system images.

If a human later chooses to test it, the first observation should be the first launch of this
new package, before adding any plants or importing anything. Record only the displayed stage,
error category and build ID, plus Android/WebView versions. A second launch can distinguish
the known reopen defect from a first-launch defect. The original installed app must remain
installed and untouched. Passing this test would not repair or recover its existing data.
