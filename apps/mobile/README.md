# Android development shell (#15)

This Capacitor 8.5.3 project bundles the existing garden HTML, CSS and icons into an Android
APK. Its sample fern is synthetic and read-only. This build does not save plants, sync, identify
photos or schedule reminders. The browser client and Java server continue to work as before.

## Reproduce the development APK

Use Node 22+, Java 21, Android SDK platform 36 and build tools 35.0.0 and 36.0.0. Capacitor's
generated Android project uses Gradle 8.14.3 and Android Gradle Plugin 8.13.0. Capacitor 8
supports Android API 24+ with a current Android WebView. From the repository root:

```sh
cd apps/mobile
npm ci --ignore-scripts
npm run apk:debug
```

The command copies the current static UI from `apps/api/src/main/resources/static` into the
ignored `www/` folder, replaces its online entry script with the read-only native preview, then
syncs Capacitor and builds `android/app/build/outputs/apk/debug/app-debug.apk`. There is no
`server.url`; the preview needs no garden server. The native asset step omits the web manifest
and service worker and never runs the web app's PWA install code. Run `npm run sync` after UI
changes before using Android Studio. CI runs this same build and uploads an artifact named
`little-garden-android-debug-<commit SHA>` from the exact PR commit.
The merged debug APK manifest currently declares `INTERNET` and an app-scoped dynamic-receiver
permission; it does not request camera, storage or notification permission in this slice.

The focused asset check is `node --test tests/mobile-assets.test.mjs` from the repository root.
After `npm ci` at the root and `npx playwright install chromium`, run
`npm run test:mobile-shell` to load the bundle with browser networking disabled.

The application ID is `com.eternalliquet.littlegarden`, version code 1, version name 0.1.0.
Increase version code for every distributed update while keeping the application ID and the
same signing key. The CI artifact is **debug signed** by its build environment and is not a
release. CI jobs may use fresh debug keys, so even successive CI APKs cannot be promised as
in-place updates. A locally built debug APK normally has a different signing key too. A future
release key needs separately authorized custody and setup; no key or credential is in this
repository. Android backup is disabled until restore behavior is designed and tested. An update
with the same signature preserves app data; uninstall or clear storage removes it. This
synthetic read-only preview has no user data or tested update path.

## Optional sideload, with device owner's approval

Download the CI artifact or use the locally built APK. On Android, open that APK and follow the
system's install prompts for that file source. If ADB is already configured,
`adb install -r path/to/app-debug.apk` is an optional route; it does not fix a signing-key mismatch. To replace
an APK signed with another key, uninstalling loses that app's local data. Do not do this once
real care data exists without a verified migration/backup. No phone installation is part of
this PR.

For this slice, an offline smoke check opens the bundled preview with networking disabled and
finds the "Android shell preview" text and sample fern. A browser offline smoke and APK build
are useful packaging evidence but do not establish physical-phone behavior. #16 adds local
care, #17 reminders, #18 server reconciliation, and #19 the phone acceptance checklist.
