# Session Cookie Isolation Implementation Plan

> Use superpowers:executing-plans for this bounded follow-up.

**Goal:** isolate native session requests from Capacitor/WebView cookies without changing global state.
**Architecture:** retain GardenSessionTransport's public API and private cookie/CSRF parsing;
replace its connection boundary with a private OkHttp client. All calls remain synchronous
on the existing bridge worker. A one-network-exchange guard prevents library status follow-ups.
**Tech Stack:** Java 21 tests; Android API 24+; OkHttp 5.5.1 and required transitives.
**Spec:** docs/superpowers/specs/2026-10-30-owned-session-client-design.md

## Constraints and review focus

- No real endpoints/credentials, new permissions, backend configuration, signing, global cookie changes or activation.
- Keep loggingBehavior:none and unrelated photo/data/UI behavior intact.
- Review outgoing cookie contamination, incoming Set-Cookie capture, handler replacement races,
  library retry statuses, and TLS/timeout/response-body lifetime.

## Task 1: Isolated request boundary

Files: GardenSessionTransport.java, GardenSessionTransportTest.java, Android app/build.gradle,
transport-tests/build.gradle; existing native-session-transport.md.

- [x] Replace the old global-handler rejection test with a hostile-handler isolation regression;
  assert successful login/mutation/logout, no unrelated cookies sent/captured, same global handler,
  and ordinary unrelated URLConnection traffic still uses its handler.
- [x] Run the new test before changes; expect UNSUPPORTED_COOKIE_CONTEXT failure.
- [x] Pin official stable client in both build projects; inspect Android manifest/transitive metadata.
- [x] Replace URLConnection request implementation with isolated OkHttp. Preserve synthetic TLS
  verification using the fixture's X509TrustManager rather than trust-all or verifier overrides.
- [x] Add/reuse tests for exact multipart, unsafe redirects, uncertain POST outcomes, 503/Retry-After,
  cookie rotation, handler replacement, and bounded responses. Observe new regressions fail first.
- [x] Run focused JVM and complete Node/browser/API checks, independent full-change review,
  fix important findings with red/green tests, commit/push coherent draft branch.
- [ ] Create stacked draft PR against feat/android-session-state and verify final-head hosted checks/APK.

Execution: base 4031f6d; prior checkpoint remains unchanged. PR27 metadata update succeeded once
following explicit owner authorization. Workflow-list Forbidden is not retried.

Local validation: 22 real loopback TLS/JVM tests, 55 Node tests and bundled offline browser
journeys pass. API test/build validates with cached tasks unchanged. Independent review found
no code blocker; documentation clarified and targeted POST503 coverage added. Hosted Android
build and final-head artifact remain pending publication. No Android runtime pass is claimed.
