# Android Photo Evidence Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for this bounded validation slice.

**Goal:** Make the existing native photo privacy and preservation contract executable without connecting a backend.

**Architecture:** Android instrumentation invokes the real GardenPhotosPlugin with synthetic PluginCall results and an isolated cache-directory context. Bitmap decoding, EXIF processing and file IO remain real. Existing Node tests continue to cover SQLite migration/history and explicit UI Save.

**Tech Stack:** Existing Android/JUnit instrumentation dependencies, Capacitor 8.5.3, Node 22, existing Android CI.

**Spec:** `docs/android-garden-contract.md`, issue #25 and the existing mobile README.

## Global Constraints

- Preserve `loggingBehavior: none` and all existing local data behavior.
- No private backend requests, real photos/credentials, new dependencies, signing changes or SDK agreements.
- No emulator authorization/security changes. New PR remains draft; no merge authorization.
- Saved transport recovery is blocked by the supported Library helper's Windows `os.setxattr` failure. Do not reconstruct or blindly stack inaccessible patches.

## Review Focus

- Synthetic EXIF/GPS must actually be present before verifying removal.
- Rotation must change pixel placement, not only dimensions.
- New plugin instances must read identical stored bytes; failure must preserve existing references.
- Tests must never write to or delete actual garden storage.
- Compiled tests are not executed tests; packaging evidence cannot imply device acceptance.

### Task 1: Native photo contract tests

**Files:** Create `apps/mobile/android/app/src/androidTest/java/com/eternalliquet/littlegarden/GardenPhotosRuntimeTest.java`.

**Interfaces:** Calls existing `GardenPhotosPlugin.save(PluginCall)` and `read(PluginCall)`; captures resolve/reject only. No production seam or behavior change.

- [x] Add synthetic JPEG with known colors, orientation, description and GPS; assert fixture metadata first.
- [x] Add tests for real save/read metadata removal and pixel rotation across all EXIF orientations (execution pending).
- [x] Add tests for stable bytes across new plugin instances and invalid operations (execution pending).
- [x] Add packaged CapConfig/Logger assertion (execution pending).
- [ ] Compile instrumentation APK and run available existing tests; run instrumentation only on an authorized disposable emulator.

### Task 2: Maintain executable evidence

**Files:** Modify `.github/workflows/android-ci.yml` and `apps/mobile/README.md`.

- [x] Add `assembleDebugAndroidTest` to the existing Android Gradle check and retain the instrumentation APK artifact.
- [x] Document explicit-serial synthetic-only invocation and distinguish unit/browser/compiled/runtime evidence.
- [x] Independently review the branch, push and open draft PR #26 linked to #25.

## Following slices (not implemented by this validation PR)

Recover and audit saved transport against merged master, then implement a session bridge/UI with synthetic TLS and expiry/offline tests. Explicit identification upload remains gated on native session acceptance. No garden sync/reminders in this batch.

## Execution record

- Baseline: merged master `5b91dbfeb1e40b52cda7e591209464b73d937831`.
- Ruling: this is regression coverage of existing code, not new production behavior; no artificial production change merely to manufacture a red test.
- Library recovery attempted with current supported helper; network sandbox retry reached Windows xattr failure. Final ZIP absent. Existing saved commit objects also absent from this clone.
- Prior disposable emulator booted but ADB was unauthorized; do not change authorization to run tests.
- Local Node suite: 40/40 passed; reviewer independently ran mobile asset tests: 3/3 passed.
- Review corrected the stripped JPEG orientation assertion: Android synthesizes UNDEFINED (0), not NORMAL (1). No native red/green execution is claimed.
- Local offline compilation blocked by uncached Android Gradle plugin 8.13.0 and google-services 4.4.4. Hosted Android CI is the compilation gate.
- CI exposed a task-scoping error: the app instrumentation APK compiled, but the unqualified task also assembled unused Cordova library instrumentation and hit its duplicate Kotlin classes. Scope the added task and documented runtime invocation to `:app:`; retain existing unit tests and every app assertion. No dependency changes.
