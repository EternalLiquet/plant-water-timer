# Owned native session client

The owner approved a pinned app-owned OkHttp dependency and necessary transitives. Keep
API 24 support, private in-memory JSESSIONID/CSRF handling, fixed HTTPS origin, bounded
responses/errors, and no automatic mutation replay. Do not touch Capacitor's global cookie
handler, backend settings, credentials, permissions, signing or production activation.

Approaches: keeping URLConnection cannot isolate Capacitor's handler; HttpEngine would
restrict online device support and replace TLS test hooks. The owner selected an app-owned
OkHttp client instead. Use official stable 5.5.1 (API 21+, Java 8+) with CookieJar.NO_COOKIES,
no redirects/authenticators/connection retries/fast fallback/cache, one-shot POST bodies,
and a per-call one-network-exchange guard. Keep existing parsing/session API and loopback
TLS fixtures; only the package-private fixture constructor receives a synthetic trust manager.

The production plugin remains null/gated. This fix makes the transport compatible with an
unrelated process cookie handler but does not establish Android runtime or device acceptance.

Dependency compatibility finding: the official 5.5.1 AAR requires compile SDK 37.
Use compile SDK 37 only; keep min SDK 24 and target SDK 36. Existing hosted runners must
already provide SDK37/build-tools35/36; replace SDK installation/license acceptance with
preflight checks. No new legal agreements, runtime permission or target behavior changes.
Avoid downgrading to 5.4.0: upstream 5.5.1 includes a current certificate-compromise defense.
