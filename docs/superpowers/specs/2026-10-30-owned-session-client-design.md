# Owned native session client

The owner approved a pinned app-owned OkHttp dependency and necessary transitives. Keep
API 24 support, private in-memory JSESSIONID/CSRF handling, fixed HTTPS origin, bounded
responses/errors, and no automatic mutation replay. Do not touch Capacitor's global cookie
handler, backend settings, credentials, permissions, signing or production activation.

Approaches: keeping URLConnection cannot isolate Capacitor's handler; HttpEngine would
restrict online device support and replace TLS test hooks. The owner selected an app-owned
OkHttp client instead. Use official stable 4.12.1 (API 21+, Java 8+) with CookieJar.NO_COOKIES,
no redirects/authenticators/connection retries/cache, one-shot POST bodies,
and a per-call one-network-exchange guard. Keep existing parsing/session API and loopback
TLS fixtures; only the package-private fixture constructor receives a synthetic trust manager.

The production plugin remains null/gated. This fix makes the transport compatible with an
unrelated process cookie handler but does not establish Android runtime or device acceptance.

Dependency compatibility: use maintained 4.12.1, including the October 22 compromised-certificate
fix, with the existing compile/target SDK 36, min SDK 24, AGP 8.13.0 and Gradle 8.14.3.
The earlier 5.5.1 choice required SDK 37 and was replaced after compatibility review.
Keep preinstalled-tool checks; no SDK installations or legal agreement acceptance.
Official 4.x support lasts until 2028-08-31. No fast-fallback option exists in 4.x.
