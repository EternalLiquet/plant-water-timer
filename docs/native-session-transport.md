# Native HTTPS session transport proof (#25)

This local implementation is based on PR #24 head
`71460d5e1c5efc4522ab019717a8c7e4fe4fd396`. Rebase onto its final merged version before
integrating with the photo flow. No identification, garden sync, UI connection, backend
security change or production endpoint is introduced.

## Adapter and security boundary

`GardenSessionTransport` is Java source in the Android app's main source set. It uses
`HttpsURLConnection` and Android's existing `org.json`, with no new app dependency.
The public constructor accepts one explicitly chosen bare HTTPS origin. Fixed operations
are session lookup, form login as `gardener`, photo multipart transport and logout.
Consumers must call these synchronous methods on a worker thread. No plugin/UI calls
the adapter yet; enabling a real connection remains gated on Android runtime evidence.

The adapter uses normal platform certificate and hostname verification. A package-private
socket-factory parameter permits the tests to trust their generated certificate; it never
disables certificate or hostname checks. No global trust settings are changed.

The adapter owns one host-only `JSESSIONID` in memory. It accepts only Secure, HttpOnly,
SameSite=Strict session cookies at path `/`; persistent lifetimes or domain cookies are
rejected rather than silently reinterpreted. Deletion/expired cookies clear local state.
Thus a backend with `SECURE_COOKIES=false` is deliberately not compatible; this work does
not configure or attest to any deployment. Every request is first-party to the pinned
origin, preserving the scope intended by SameSite. This is not a general browser cookie jar.

`HttpsURLConnection` can consult a process-global CookieHandler, so the adapter refuses
to run if one is already installed instead of sharing it or altering it. The actual
Android application's default handler and lifecycle must be checked before integration.
Concurrent replacement of that global handler by another component is outside this proof.

Redirect following is disabled for every request. Login/logout accept only a 302/303
to the same origin's `/` (login also permits `/?login=failed`), without following it.
Login always reloads `/api/session` to confirm authentication and retrieve fresh CSRF
after cookie rotation. Uploads carry that CSRF token and never follow a redirect.
Responses are limited to 64 KiB and connections/reads have 10-second timeouts.

No cookie, password, CSRF token or image bytes are logged or persisted. A fresh adapter,
new process or explicit `close()` has no session. Foreground continuity can use the same
adapter instance, but its real Android lifecycle is unverified. `logout()` clears local
credentials even if the remote operation fails; failure is returned, never presented as
proof that the server invalidated the session. In-memory Strings are not a secure-wipe promise.

Failures have bounded categories rather than server response bodies or exception details.
HTTP 401 clears authentication. HTTP 403 refreshes session state to distinguish expiry from
stale CSRF, but never replays the mutation. Streaming POSTs and closed connections avoid
buffered authentication/redirect retries. A timeout or disconnect during a POST is an
`UNCERTAIN_OUTCOME`; callers must not automatically replay it. TLS rejection is distinct.
Photo transport accepts only bounded JPEG/PNG payloads and a generated filename, using the
server's `file` multipart field; image content validation remains the photo pipeline/server's job.

## Reproduce the JVM proof

With Java 21 and the repository's Gradle wrapper, from the repository root:

```sh
apps/api/gradlew -p apps/mobile/transport-tests test
```

This small standalone Gradle test project compiles the exact Android transport source
without an SDK. Its Android-compatible JSON implementation and JUnit are **test-only**.
The fixture binds only loopback HTTPS and generates a temporary two-day certificate with
the JDK's keytool. It uses invented passwords and arbitrary synthetic image bytes, and
removes its temporary key store. No backend or external test service is contacted.

Tests exercise real HTTP/TLS requests: login form bytes; rotated cookies/fresh CSRF;
exact multipart bytes; wrong password, unconfigured and rate-limited failures; malformed,
HTML and oversized responses; logout; 401 and 403 expiry; stale CSRF; no mutation before
sign-in; invalid trust and hostname mismatch; redirect rejection; disconnect/timeout with
one upload attempt; cookie constraints and ownership; and independent adapter instances.

## Recorded evidence and remaining gates

On 2026-10-28 the initial ten tests failed against an unimplemented adapter, then passed.
Independent static review found discarded persistent-cookie expiry; a new test failed
before the implementation was tightened to session-cookie-only support. The expanded
17-test suite passed under Java 21 using cached JUnit 4.13.2 and Android JSON jars.
The review also prompted hostname-mismatch and expired-CSRF coverage. No unresolved
static-review blocker remains for a local checkpoint.

The normal Gradle dependency attempt failed: direct JVM requests could not resolve the
repository hosts; using the environment's existing proxy failed JVM certificate validation.
Official Google/Maven POMs were reachable with normal certificate-validating curl. No TLS
verification was disabled, trust store changed, SDK downloaded or license accepted.
The test suite was compiled/run directly with the existing cached jars; the exact command
script and red/green logs are retained in the cloud workspace's handoff evidence directory.

Still pending, and required before any real connection:

- Android APK compilation: this environment lacks an Android SDK and resolved Android
  Gradle dependencies. Installing an SDK may require separately approved license acceptance.
- Native execution on a disposable emulator: real Android TLS, CookieHandler compatibility,
  cookie continuity, foreground behavior and process restart, plus no hidden POST replay.
  JVM success is not Android runtime evidence.
- Existing native-photo runtime checks from the separate PR #24 follow-up, including
  real bitmap/EXIF/private-file behavior and interrupted offline saves.
- Physical-phone/private-network acceptance and deployed backend readiness remain separate
  and unverified. No real garden credentials, personal photos, phone installation, server
  mutation, publication or deployment was used for this proof.

## Cloud continuation (2026-10-30)

Recovered the original patch from the parent's readable source handoff: 39,025 UTF-8 LF
bytes, SHA-256 `8d5337abd2509a80e29f41cc35eb38ae4979c22d00ae94025589a457fbec2d72`.
`git apply --check` passed on session-controller checkpoint
`04a3dd6b3d02f932a0996feac50e593804921ab2`, based on merged PR24. The cloud Library
materialization failed before producing a file; the source handoff did not bypass its
metadata flow. The transport source was recovered unchanged before the review fix documented below.

`GardenSessionBridge` now maps the transport to boolean-only session results;
`GardenSessionPlugin` serializes work off the UI thread, drops the password from the call
payload after reading it, and returns only bounded error codes. Destruction queues local
credential disposal after pending work. The JavaScript adapter maps those codes into the
existing controller. The optional sign-in panel never hides or edits local garden records,
clears submitted passwords, and requires explicit actions for session checks. No polling,
retries, origin picker, uploads or identification controls are added.

**The production plugin is deliberately constructed without a transport.** Its availability
check is local, and its session/login/logout methods reject without network traffic.
There is no endpoint or runtime enable switch. The UI displays that sign-in is unavailable;
synthetic browser fixtures exercise the enabled UI behavior only. Enabling a real native
transport remains a separate reviewed step after the Android runtime gates above.

Cloud verification: the original 17 loopback-TLS tests passed with Java 21 and the cached
Android-compatible JSON/JUnit 4.13.2 jars. Two additional bridge tests first failed because
the bridge was absent, then passed (19 total). The normal Gradle wrapper download failed
DNS; cached Gradle compiled production source offline but lacked JUnit module metadata.
Direct javac/JUnit execution is JVM evidence only. Existing Android CI now runs this separate
Gradle test project as well, before the APK build. Local SDK/emulator execution remains
unavailable. PR26's photo instrumentation remains separate and unexecuted.

Independent cloud review found two pre-activation defects; both received failing regression
tests before fixes. Anonymous checks can no longer erase an unconfirmed logout after the
native jar forgets its cookie, including expiry during a pending failed logout. The UI
removes the impossible logout retry and explains that the server session may still be active.
A newly confirmed login starts a new session. Native session parsing now rejects contradictory
`authenticated: true, configured: false` and clears authentication before any mutation.
The final local suites pass: 55 Node tests, offline bundled-browser journeys, 20 loopback
TLS/bridge JVM tests, and 71 API tests plus the API build. Independent review found no other
important defects; Android build/runtime status must be read from the exact-head CI/acceptance
record, not inferred from these JVM/browser results.
