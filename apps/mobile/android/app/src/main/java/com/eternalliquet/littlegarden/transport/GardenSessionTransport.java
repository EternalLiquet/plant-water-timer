package com.eternalliquet.littlegarden.transport;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Authenticator;
import okhttp3.ConnectionPool;
import okhttp3.CookieJar;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Native, first-party HTTPS only. Owns one in-memory session; never shares WebView cookies. */
public final class GardenSessionTransport implements AutoCloseable {
    public enum Reason { INVALID_ORIGIN, UNCONFIGURED, WRONG_PASSWORD, RATE_LIMITED,
        MALFORMED_RESPONSE, SIGN_IN_REQUIRED, CSRF_REJECTED, UNSAFE_REDIRECT,
        UNTRUSTED_TLS, NETWORK, UNCERTAIN_OUTCOME, HTTP_ERROR, INVALID_PHOTO }
    public static final class Failure extends Exception {
        public final Reason reason;
        Failure(Reason reason) { super(reason.name()); this.reason = reason; }
    }
    public static final class Session {
        public final boolean authenticated, configured;
        Session(boolean authenticated, boolean configured) {
            this.authenticated = authenticated; this.configured = configured;
        }
    }
    private static final class ExchangeGuard { final AtomicBoolean used = new AtomicBoolean(); }
    private static final class ReplayBlocked extends IOException { }
    private final URI origin;
    private final OkHttpClient http;
    private String sessionCookie, csrf;
    private boolean authenticated;
    private static final int MAX_RESPONSE = 65536, MAX_PHOTO = 5 * 1024 * 1024;

    public GardenSessionTransport(URI origin) throws Failure { this(origin, null, null, 10000); }

    // Package-private injection for a synthetic CA fixture; public callers use platform TLS defaults.
    GardenSessionTransport(URI origin, SSLSocketFactory tls, X509TrustManager trust, int timeout) throws Failure {
        if (origin == null || !"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null ||
                origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null ||
                !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath())) ||
                origin.getPort() == 0 || origin.getPort() > 65535 || timeout < 1) {
            throw new Failure(Reason.INVALID_ORIGIN);
        }
        this.origin = origin.resolve("/");
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .addNetworkInterceptor(chain -> {
                ExchangeGuard guard = chain.request().tag(ExchangeGuard.class);
                // retryOnConnectionFailure(false) alone does not cover 503 Retry-After: 0.
                if (guard == null || !guard.used.compareAndSet(false, true)) throw new ReplayBlocked();
                return chain.proceed(chain.request());
            })
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .cache(null).connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS).callTimeout(timeout, TimeUnit.MILLISECONDS);
        // Tests supply their generated CA only. Production retains platform trust/hostname checks.
        if (tls != null) builder.sslSocketFactory(tls, Objects.requireNonNull(trust));
        this.http = builder.build();
    }

    public synchronized Session session() throws Failure {
        try {
            Response response = request("GET", "/api/session", null, null, false);
            status(response);
            JSONObject json = json(response);
            Object signedIn = json.get("authenticated"), configured = json.get("configured");
            Object token = json.get("csrfToken"), header = json.get("csrfHeader");
            if (!(signedIn instanceof Boolean) || !(configured instanceof Boolean) ||
                    ((Boolean) signedIn && !(Boolean) configured) ||
                    !(token instanceof String) || !"X-CSRF-TOKEN".equals(header) ||
                    ((String) token).isEmpty() || ((String) token).length() > 4096 ||
                    ((String) token).contains("\r") || ((String) token).contains("\n")) {
                throw new Failure(Reason.MALFORMED_RESPONSE);
            }
            authenticated = (Boolean) signedIn;
            csrf = (String) token;
            if (authenticated && sessionCookie == null) throw new Failure(Reason.MALFORMED_RESPONSE);
            return new Session(authenticated, (Boolean) configured);
        } catch (Failure error) { close(); throw error; }
        catch (Exception error) { close(); throw new Failure(Reason.MALFORMED_RESPONSE); }
    }

    public synchronized void login(String password) throws Failure {
        close();
        try {
            if (password == null || password.isEmpty()) throw new Failure(Reason.WRONG_PASSWORD);
            if (!session().configured) throw new Failure(Reason.UNCONFIGURED);
            String form = "username=gardener&password=" + encode(password) + "&_csrf=" + encode(csrf);
            Response response = request("POST", "/login", form.getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded", false);
            if (response.code >= 300 && response.code < 400) allowLoginLogoutRedirect(response, true);
            else status(response);
            // A redirect or HTML landing page is never proof of successful authentication.
            if (!session().authenticated) throw new Failure(Reason.WRONG_PASSWORD);
        } catch (Failure error) { close(); throw error; }
    }

    /** Synthetic transport proof only; no UI calls this until native runtime acceptance. */
    public synchronized void uploadPhoto(byte[] bytes, String mime) throws Failure {
        requireSession();
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PHOTO ||
                !("image/jpeg".equals(mime) || "image/png".equals(mime))) throw new Failure(Reason.INVALID_PHOTO);
        String boundary = "garden-" + UUID.randomUUID();
        String filename = "image/png".equals(mime) ? "photo.png" : "photo.jpg";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" +
                filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(bytes);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException impossible) { throw new Failure(Reason.INVALID_PHOTO); }
        Response response = request("POST", "/api/garden/photos", body.toByteArray(),
            "multipart/form-data; boundary=" + boundary, true);
        if (response.code == 403) {
            // Refresh state only. Never replay a photo, even after a definite CSRF rejection.
            if (!session().authenticated) throw new Failure(Reason.SIGN_IN_REQUIRED);
            throw new Failure(Reason.CSRF_REJECTED);
        }
        status(response);
        json(response);
    }

    public synchronized void logout() throws Failure {
        try {
            requireSession();
            Response response = request("POST", "/logout", new byte[0], "application/x-www-form-urlencoded", true);
            if (response.code >= 300 && response.code < 400) allowLoginLogoutRedirect(response, false);
            else status(response);
        } finally { close(); }
    }

    /** Drops all local credentials; a new instance/process always requires sign-in again. */
    @Override public synchronized void close() {
        sessionCookie = null; csrf = null; authenticated = false;
        http.connectionPool().evictAll();
    }

    private void requireSession() throws Failure {
        if (!authenticated || csrf == null || sessionCookie == null) throw new Failure(Reason.SIGN_IN_REQUIRED);
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private static final class Response {
        final int code;
        final String type, location;
        final byte[] bytes;
        Response(int code, String type, String location, byte[] bytes) {
            this.code = code; this.type = type; this.location = location; this.bytes = bytes;
        }
    }

    private void status(Response response) throws Failure {
        if (response.code == 401) { close(); throw new Failure(Reason.SIGN_IN_REQUIRED); }
        if (response.code == 403) throw new Failure(Reason.CSRF_REJECTED);
        if (response.code == 429) throw new Failure(Reason.RATE_LIMITED);
        if (response.code >= 300 && response.code < 400) throw new Failure(Reason.UNSAFE_REDIRECT);
        if (response.code < 200 || response.code >= 300) throw new Failure(Reason.HTTP_ERROR);
    }

    private JSONObject json(Response response) throws Failure {
        if (response.type == null || !"application/json".equalsIgnoreCase(response.type.split(";", 2)[0].trim())) {
            throw new Failure(Reason.MALFORMED_RESPONSE);
        }
        try {
            JSONTokener parser = new JSONTokener(new String(response.bytes, StandardCharsets.UTF_8));
            Object value = parser.nextValue();
            if (!(value instanceof JSONObject) || parser.nextClean() != 0) throw new Failure(Reason.MALFORMED_RESPONSE);
            return (JSONObject) value;
        } catch (Exception error) { throw new Failure(Reason.MALFORMED_RESPONSE); }
    }

    private void allowLoginLogoutRedirect(Response response, boolean login) throws Failure {
        try {
            URI target = origin.resolve(response.location);
            boolean same = "https".equalsIgnoreCase(target.getScheme()) &&
                origin.getHost().equalsIgnoreCase(target.getHost()) && port(origin) == port(target) &&
                target.getRawUserInfo() == null && target.getRawFragment() == null;
            boolean known = "/".equals(target.getRawPath()) && (target.getRawQuery() == null ||
                (login && "login=failed".equals(target.getRawQuery())));
            if (!same || !known || !(response.code == 302 || response.code == 303)) throw new Failure(Reason.UNSAFE_REDIRECT);
            // Do not follow even approved redirects. Reload /api/session explicitly after login.
        } catch (Exception error) { throw new Failure(Reason.UNSAFE_REDIRECT); }
    }

    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }

    private void receiveCookies(Map<String, List<String>> headers) throws Failure {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!"Set-Cookie".equalsIgnoreCase(entry.getKey())) continue;
            for (String raw : entry.getValue()) {
                if (raw.length() > 4096) throw new Failure(Reason.MALFORMED_RESPONSE);
                try {
                    for (HttpCookie cookie : HttpCookie.parse(raw)) {
                        if (!"JSESSIONID".equals(cookie.getName())) continue;
                        if (cookie.getDomain() != null || !"/".equals(cookie.getPath())) throw new Failure(Reason.MALFORMED_RESPONSE);
                        if (cookie.hasExpired() || cookie.getValue().isEmpty()) { close(); continue; }
                        // The server contract uses a session cookie. Reject persistent lifetimes
                        // rather than silently dropping an Expires/Max-Age deadline from this jar.
                        if (cookie.getMaxAge() != -1 || !cookie.getSecure() || !cookie.isHttpOnly() ||
                                !raw.matches("(?is).*;\\s*SameSite\\s*=\\s*Strict\\s*(;.*)?") ||
                                !cookie.getValue().matches("[A-Za-z0-9._~-]{1,512}")) throw new Failure(Reason.MALFORMED_RESPONSE);
                        sessionCookie = "JSESSIONID=" + cookie.getValue();
                    }
                } catch (IllegalArgumentException error) { throw new Failure(Reason.MALFORMED_RESPONSE); }
            }
        }
    }

    private Response request(String method, String path, byte[] body, String contentType, boolean mutation) throws Failure {
        // No java.net CookieHandler lookup or WebView integration: all cookies are explicit.
        Request.Builder request = new Request.Builder().url(origin.resolve(path).toString())
            .tag(ExchangeGuard.class, new ExchangeGuard())
            .header("Accept", "application/json").header("Accept-Encoding", "identity");
        if (sessionCookie != null) request.header("Cookie", sessionCookie);
        if (mutation) request.header("X-CSRF-TOKEN", csrf);
        RequestBody payload = body == null ? null : new RequestBody() {
            @Override public MediaType contentType() { return MediaType.get(contentType); }
            @Override public long contentLength() { return body.length; }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) throws IOException { sink.write(body); }
        };
        request.method(method, payload);
        try (okhttp3.Response response = http.newCall(request.build()).execute()) {
            receiveCookies(response.headers().toMultimap());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream stream = response.body().byteStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (output.size() + count > MAX_RESPONSE) throw new Failure(Reason.MALFORMED_RESPONSE);
                    output.write(buffer, 0, count);
                }
            }
            return new Response(response.code(), response.header("Content-Type"), response.header("Location"), output.toByteArray());
        } catch (ReplayBlocked error) { throw new Failure(Reason.HTTP_ERROR); }
        catch (SSLException error) { throw new Failure(Reason.UNTRUSTED_TLS); }
        catch (IOException error) { throw new Failure(body != null ? Reason.UNCERTAIN_OUTCOME : Reason.NETWORK); }
    }
}
