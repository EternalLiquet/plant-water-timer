# MVP validation

## Local verification (2026-10-08)

- Java 21 / Gradle 8.14.3: 58 API/domain/security tests and production build passed.
- Client syntax and seven Node interface/date/offline-cache checks passed.
- Local identification companion: 12 boundary/security/cancellation tests passed.
- Real CPU inference with the pinned OpenPlants model passed. A licensed Monstera fixture was
  correctly ranked first. This is a qualitative one-image check, not a recognition benchmark.
- Real authenticated HTTP end-to-end passed: sign in, sanitize/upload photo, local identification,
  create plant, next check date, log watering, repeated-request deduplication, history, Undo,
  protected photo retrieval and sign out. No real user data was used.
- Stopped-service backup/restore and application restart preserved the same plant ID, dates,
  watering history and sanitized photo in an independent synthetic data directory.
- Photo tests cover decoded-image validation, EXIF orientation/removal, owner isolation,
  unavailable/uncertain provider responses, 24-hour abandoned-photo expiry and cap recovery,
  referenced-photo retention, concurrent attachment/discard, and owner-only POSIX files.
- Existing shared-permission data directories are rejected instead of changing their permissions.
- Original owner-header write routes are blocked even with forged headers and valid CSRF.
- Unit red/green checks were observed for date rules, image sanitization, uncertainty preservation,
  password login, photo retention and private storage. Initial full-context test execution was
  blocked by the cloud JDK/proxy and Mockito self-attachment setup; these were fixed before the
  final full suite. Do not treat those infrastructure failures as behavioral red tests.

## Browser verification

The cloud shell's Chromium launch cannot create its required Unix socket. The supported cloud
browser also rejects the loopback URL with `ERR_BLOCKED_BY_CLIENT`; no hostname workaround was
used. Therefore local browser screenshots and interaction tests are **not** claimed.

GitHub Actions run [37754621108](https://github.com/EternalLiquet/plant-water-timer/actions/runs/37754621108)
passed all three jobs for head `d2634ba`: API/build/client checks, identification boundary, and
390×844 browser interaction. Its three actual screenshots were inspected: readable dark layout,
clear primary action and no horizontal overflow. The browser journey covered photo confirmation,
watering, history, reload, Undo, cancellation/reopening, literal text rendering and logout access.
It used synthetic data and an identification stub; real-model HTTP verification is separate.
A subsequent small file-picker polish and isolated CI data directory are tracked by the PR's
latest checks. Consult those checks for the current exact head before merging.

## External release gates

Not performed by this implementation:

- Live HTTPS deployment, domain setup or production access changes.
- Physical-phone camera and home-screen installation testing.
- A first-time nontechnical person's usability session.
- Broad model accuracy, unknown/non-plant rejection or botanical care validation.

No API keys, paid accounts, real-user photos or model weights are committed. The model is an
explicit, checksum-verified operator download. This is a private single-garden release, not a
multi-user service. Notifications, offline saves, AI-generated watering advice and account
export/deletion screens are not implemented. Operator privacy/backup instructions are in
[Run your garden](run-your-garden.md).
