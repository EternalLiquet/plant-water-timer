# Android Session State Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for this bounded integration prerequisite.

**Goal:** Define and test the Android UI's session lifecycle independently of saved native transport recovery.

**Architecture:** A pure JavaScript controller accepts a narrow asynchronous transport interface and exposes immutable public state. It has no database, file, fetch, credential storage, upload or retry capability. A later bridge adapter maps recovered native method/result names to this interface; do not duplicate the saved HTTPS implementation.

**Tech Stack:** ES modules, existing Node test runner.

**Spec:** Issue #25; `docs/android-garden-contract.md`; existing backend `GardenSecurity.java`.

## Global Constraints

- Never hide or mutate local plants/photos/history on sign-in failure, expiry or sign-out.
- Keep all raw passwords, cookies, CSRF tokens, backend errors and photo bytes out of public state.
- No real backend traffic, new dependencies, security changes or automatic requests/retries.
- No identification/upload enablement until the native transport/runtime acceptance gate is met.

## Review Focus

- A completed old login must not undo a newer sign-out/expiry intent.
- Sign-out must run after any outstanding native request, so late cookies cannot silently restore the session.
- Timeout/failed logout cannot be reported as confirmed sign-out.
- Malformed responses cannot create an authenticated UI state.
- Wrong-password, unconfigured, rate-limit and offline failures need distinct bounded text, never raw errors.

### Task 1: Controller and synthetic tests

**Files:** `apps/mobile/session-state.mjs`, `tests/mobile-session-state.test.mjs`.

**Interface:** transport `getSession()`, `signIn({password})`, `signOut()`; successful calls return `{authenticated: boolean, configured: boolean}`. The adapter may throw an error with a bounded `code`. No tokens leave the transport. `createSessionState(transport)` returns `getState()`, `refresh()`, `signIn(password)`, `signOut()`, `expire()`.

- [ ] Write tests for initial local-only/no-request state, explicit confirmed login, malformed responses, bounded failures and credential exclusion; observe missing-module failure.
- [ ] Implement minimal controller with serialized transport operations and generation-based stale-result suppression.
- [ ] Add adversarial tests for duplicate submissions, sign-out during login, expiry during refresh, and uncertain logout with no retries.
- [ ] Run targeted and full Node tests; independent review; commit/push draft checkpoint.

### Task 2: Integration after supported saved-work recovery

- [ ] Inspect recovered transport and review notes; map exact native methods/results into this controller contract.
- [ ] Wire session controls beside the local garden, clear password inputs after submission, and test real UI sign-in/expiry/offline journeys with synthetic transport.
- [ ] Keep production identification unavailable until native transport/runtime tests pass; then add explicit photo-send UI in its own reviewed slice.

## Execution record

- Baseline: merged master `5b91dbfeb1e40b52cda7e591209464b73d937831`.
- Ruling: transport recovery and native runtime are blocked independently; implement the small UI state contract now rather than duplicate the large saved HTTPS transport. No APK behavior change is claimed for this controller-only checkpoint.
- Sleep/migration checkpoint: controller and 10 synthetic tests implemented; full Node suite 50/50 passed. Independent review and UI wiring remain pending.
- Preserved native transport arrived through a separate source-code handoff. XML entities decoded to exactly 39,025 bytes with expected SHA-256 `8d5337abd2509a80e29f41cc35eb38ae4979c22d00ae94025589a457fbec2d72`. Verified derivative is retained outside this repo as `../session-source-handoff-verified.patch`; not applied. The original Library ZIP was not materialized on Windows and no metadata guard was bypassed.
- Next worker: inspect all patch paths, run `git apply --check`, then apply narrowly and run the separate transport JVM tests. Map native `session/login/logout` and bounded enum failures to this controller's `getSession/signIn/signOut` interface. Keep real connectivity gated on Android runtime acceptance.
