package com.eternalliquet.littlegarden.transport;

import static org.junit.Assert.*;
import static com.eternalliquet.littlegarden.transport.GardenSessionTransport.Reason.*;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.*;

/** Synthetic loopback HTTPS only. No backend, garden credentials, or Android mocks. */
public class GardenSessionTransportTest {
    private static SSLContext trusted;
    private static X509TrustManager trust;
    private static Path certificateDirectory;
    private HttpsServer server;
    private ExecutorService executor;
    private GardenSessionTransport client;
    private URI origin;
    private volatile boolean authenticated, configured = true, expired, stale;
    private volatile String mode = "", loginForm = "", mutationCsrf = "", mutationCookie = "";
    private final List<String> requestCookies = Collections.synchronizedList(new ArrayList<>());
    private volatile byte[] uploaded;
    private volatile String uploadType;
    private final AtomicInteger sessions = new AtomicInteger();
    private final AtomicInteger uploads = new AtomicInteger(), landings = new AtomicInteger();
    private static final String PASSWORD = "synthetic & password+only";

    @BeforeClass public static void temporaryCertificate() throws Exception {
        certificateDirectory = Files.createTempDirectory("garden-https-test-");
        Path store = certificateDirectory.resolve("fixture.p12");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
            "-keystore", store.toString(), "-storepass", "synthetic-only", "-noprompt")
            .redirectErrorStream(true).redirectOutput(certificateDirectory.resolve("keytool.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(store)) { keys.load(input, "synthetic-only".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "synthetic-only".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        trust = (X509TrustManager) tm.getTrustManagers()[0];
        trusted = SSLContext.getInstance("TLS");
        trusted.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    }

    @AfterClass public static void removeCertificate() throws Exception {
        if (certificateDirectory != null) try (var paths = Files.walk(certificateDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Before public void startFixture() throws Exception {
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(trusted));
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        origin = URI.create("https://localhost:" + server.getAddress().getPort());
        client = new GardenSessionTransport(origin, trusted.getSocketFactory(), trust, 500);
    }

    @After public void stopFixture() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private void cookie(HttpExchange exchange, String value) {
        String attributes = "; Path=/; Secure; HttpOnly; SameSite=Strict";
        if (mode.equals("persistent-cookie")) attributes += "; Max-Age=3600";
        if (mode.equals("domain-cookie")) attributes += "; Domain=localhost";
        if (mode.equals("weak-cookie")) attributes = "; Path=/; HttpOnly; SameSite=Strict";
        exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=" + value + attributes);
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1); exchange.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String cookie = Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"), "");
        requestCookies.add(cookie);
        if (path.equals("/api/session")) {
            sessions.incrementAndGet();
            if (mode.equals("retry-session")) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                respond(exchange, 503, "{}"); return;
            }
            if (mode.equals("html")) {
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, 4); exchange.getResponseBody().write("oops".getBytes()); exchange.close(); return;
            }
            if (mode.equals("malformed")) { respond(exchange, 200, "{broken"); return; }
            if (mode.equals("oversized")) { respond(exchange, 200, " ".repeat(70000)); return; }
            if (!cookie.equals("JSESSIONID=rotated")) cookie(exchange, "initial");
            boolean signedIn = authenticated && cookie.equals("JSESSIONID=rotated") && !expired;
            respond(exchange, 200, "{\"configured\":" + configured + ",\"authenticated\":" + signedIn +
                ",\"csrfHeader\":\"X-CSRF-TOKEN\",\"csrfToken\":\"" + (signedIn ? "fresh-csrf" : "login-csrf") + "\"}");
        } else if (path.equals("/login")) {
            loginForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (mode.equals("limited")) { respond(exchange, 429, "{}"); return; }
            if (!cookie.equals("JSESSIONID=initial") || !loginForm.contains("_csrf=login-csrf")) { respond(exchange, 403, "{}"); return; }
            if (mode.startsWith("redirect:")) { redirect(exchange, mode.substring(9)); return; }
            if (mode.equals("wrong")) { redirect(exchange, "/?login=failed"); return; }
            authenticated = !mode.equals("login-no-session"); cookie(exchange, "rotated"); redirect(exchange, "/");
        } else if (path.equals("/api/garden/photos")) {
            uploads.incrementAndGet();
            uploaded = exchange.getRequestBody().readAllBytes();
            uploadType = exchange.getRequestHeaders().getFirst("Content-Type");
            mutationCookie = cookie;
            mutationCsrf = exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN");
            if (expired) { respond(exchange, mode.equals("expiry-csrf") ? 403 : 401, "{}"); return; }
            if (stale || !"fresh-csrf".equals(mutationCsrf)) { respond(exchange, 403, "{}"); return; }
            if (mode.equals("retry-upload")) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                respond(exchange, 503, "{}"); return;
            }
            if (mode.equals("disconnect")) { exchange.close(); return; }
            if (mode.equals("timeout")) {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                exchange.close(); return;
            }
            if (mode.startsWith("redirect:")) { redirect(exchange, mode.substring(9)); return; }
            respond(exchange, 200, "{\"id\":\"synthetic\"}");
        } else if (path.equals("/logout")) {
            if (mode.equals("logout-failure")) { respond(exchange, 503, "{}"); return; }
            if (!cookie.equals("JSESSIONID=rotated") || !"fresh-csrf".equals(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"))) {
                respond(exchange, 403, "{}"); return;
            }
            authenticated = false;
            exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=; Path=/; Max-Age=0");
            redirect(exchange, "/");
        } else { landings.incrementAndGet(); respond(exchange, 200, "{}"); }
    }

    private interface Action { void run() throws Exception; }
    private void fails(GardenSessionTransport.Reason reason, Action action) throws Exception {
        try { action.run(); fail("Expected " + reason); }
        catch (GardenSessionTransport.Failure error) { assertEquals(reason, error.reason); }
    }

    @Test public void loginRotatesCookieAndRetrievesFreshCsrfBeforeExactMultipartUpload() throws Exception {
        client.login(PASSWORD);
        assertTrue(client.session().authenticated);
        byte[] photo = {0, 1, 2, 13, 10, (byte)255};
        client.uploadPhoto(photo, "image/png");
        assertEquals("username=gardener&password=synthetic+%26+password%2Bonly&_csrf=login-csrf", loginForm);
        assertEquals("JSESSIONID=rotated", mutationCookie);
        assertEquals("fresh-csrf", mutationCsrf);
        assertTrue(uploadType.startsWith("multipart/form-data; boundary="));
        String boundary = uploadType.substring(uploadType.indexOf('=') + 1);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        expected.write(photo);
        expected.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected.toByteArray(), uploaded);
        assertEquals(0, landings.get());
    }

    @Test public void wrongPasswordUnconfiguredAndRateLimitedAreDistinct() throws Exception {
        mode = "wrong"; fails(WRONG_PASSWORD, () -> client.login(PASSWORD));
        configured = false; mode = ""; fails(UNCONFIGURED, () -> client.login(PASSWORD));
        configured = true; mode = "limited"; fails(RATE_LIMITED, () -> client.login(PASSWORD));
    }

    @Test public void malformedHtmlAndOversizedSessionResponsesAreBounded() throws Exception {
        for (String value : new String[]{"html", "malformed", "oversized"}) {
            mode = value; fails(MALFORMED_RESPONSE, () -> client.session());
        }
    }

    @Test public void logoutInvalidatesRemoteAndLocalSession() throws Exception {
        client.login(PASSWORD); client.logout();
        assertFalse(authenticated);
        fails(SIGN_IN_REQUIRED, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        assertFalse(client.session().authenticated);
        assertEquals(0, uploads.get());
    }

    @Test public void expiryAndStaleCsrfNeverReplayMutation() throws Exception {
        client.login(PASSWORD); stale = true;
        fails(CSRF_REJECTED, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        assertEquals(1, uploads.get());
        stale = false; expired = true;
        fails(SIGN_IN_REQUIRED, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        fails(SIGN_IN_REQUIRED, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        assertEquals(2, uploads.get());
    }

    @Test public void downgradeAndCrossOriginRedirectsAreNeverFollowed() throws Exception {
        for (String target : new String[]{"http://localhost:" + server.getAddress().getPort(),
                "https://127.0.0.1:" + server.getAddress().getPort(), "https://example.invalid/"}) {
            mode = "redirect:" + target;
            fails(UNSAFE_REDIRECT, () -> client.login(PASSWORD));
        }
        mode = ""; client.login(PASSWORD);
        mode = "redirect:https://example.invalid/";
        fails(UNSAFE_REDIRECT, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        assertEquals(0, landings.get());
    }

    @Test public void publicConstructorRejectsUntrustedCertificate() throws Exception {
        try (GardenSessionTransport untrusted = new GardenSessionTransport(origin)) {
            fails(UNTRUSTED_TLS, untrusted::session);
        }
    }

    @Test public void trustedCertificateStillMustMatchTheHostname() throws Exception {
        URI mismatch = URI.create("https://127.0.0.1:" + server.getAddress().getPort());
        try (GardenSessionTransport wrongHost = new GardenSessionTransport(mismatch, trusted.getSocketFactory(), trust, 500)) {
            fails(UNTRUSTED_TLS, wrongHost::session);
        }
    }

    @Test public void expiredSessionRejectedByCsrfClearsAuthenticationWithoutReplay() throws Exception {
        client.login(PASSWORD); expired = true; mode = "expiry-csrf";
        fails(SIGN_IN_REQUIRED, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        fails(SIGN_IN_REQUIRED, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        assertEquals(1, uploads.get());
    }

    @Test public void successfulLoginRedirectWithoutAuthenticatedSessionIsNotSuccess() throws Exception {
        mode = "login-no-session";
        fails(WRONG_PASSWORD, () -> client.login(PASSWORD));
        fails(SIGN_IN_REQUIRED, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        assertEquals(0, landings.get());
    }

    @Test public void disconnectReportsUncertainOutcomeWithoutReplay() throws Exception {
        client.login(PASSWORD);
        mode = "disconnect";
        fails(UNCERTAIN_OUTCOME, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        assertEquals(1, uploads.get());
    }

    @Test public void timeoutReportsUncertainOutcomeWithoutReplay() throws Exception {
        // Separate fixture: a failed localhost route can defer IPv4 behind IPv6 in OkHttp4.
        // This server deliberately binds only IPv4 loopback; exercise an actual read timeout.
        client.login(PASSWORD);
        mode = "timeout";
        fails(UNCERTAIN_OUTCOME, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        assertEquals(1, uploads.get());
    }

    @Test public void closingClientLosesSessionAndAnotherInstanceDoesNotInheritCookies() throws Exception {
        client.login(PASSWORD); client.close();
        fails(SIGN_IN_REQUIRED, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        try (GardenSessionTransport fresh = new GardenSessionTransport(origin, trusted.getSocketFactory(), trust, 500)) {
            assertFalse(fresh.session().authenticated);
        }
    }

    @Test public void originMustBeBareHttpsWithoutCredentials() throws Exception {
        for (String uri : new String[]{"http://localhost", "https://user:password@localhost", "https://localhost/api", "https://localhost?x=1", "https://localhost/#x"}) {
            fails(INVALID_ORIGIN, () -> new GardenSessionTransport(URI.create(uri)));
        }
    }

    @Test public void sessionCookieMustStayHostOnlySecureAndNonPersistent() throws Exception {
        for (String value : new String[]{"persistent-cookie", "domain-cookie", "weak-cookie"}) {
            mode = value; fails(MALFORMED_RESPONSE, () -> client.login(PASSWORD));
        }
    }

    @Test public void unrelatedGlobalCookiesAreNeitherSentNorCapturedAndOwnerStillWorks() throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        CookieHandler unrelated = new CookieHandler() {
            @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
                reads.incrementAndGet();
                return Map.of("Cookie", List.of("WEBVIEW=unrelated-synthetic"));
            }
            @Override public void put(URI uri, Map<String, List<String>> headers) {
                writes.incrementAndGet();
            }
        };
        try {
            CookieHandler.setDefault(unrelated);
            client.close();
            client = new GardenSessionTransport(origin, trusted.getSocketFactory(), trust, 500);
            client.login(PASSWORD);
            client.uploadPhoto(new byte[]{1}, "image/jpeg");
            client.logout();
            assertEquals(0, reads.get());
            assertEquals(0, writes.get());
            assertTrue(requestCookies.stream().noneMatch(c -> c.contains("WEBVIEW")));
            assertSame(unrelated, CookieHandler.getDefault());
            HttpsURLConnection other = (HttpsURLConnection) origin.resolve("/api/session").toURL().openConnection();
            other.setSSLSocketFactory(trusted.getSocketFactory());
            try (InputStream input = other.getInputStream()) { input.readAllBytes(); }
            finally { other.disconnect(); }
            assertTrue(reads.get() > 0);
            assertTrue(writes.get() > 0);
            assertTrue(requestCookies.get(requestCookies.size() - 1).contains("WEBVIEW=unrelated-synthetic"));
        } finally { CookieHandler.setDefault(previous); }
    }

    @Test public void failedRemoteLogoutStillDropsLocalSessionButDoesNotClaimInvalidation() throws Exception {
        client.login(PASSWORD); mode = "logout-failure";
        fails(HTTP_ERROR, () -> client.logout());
        assertTrue(authenticated);
        fails(SIGN_IN_REQUIRED, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
    }

    @Test public void mutationWithoutSessionIsRefusedBeforeNetwork() throws Exception {
        fails(SIGN_IN_REQUIRED, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        assertEquals(0, uploads.get());
    }
    @Test public void bridgeIsUnavailableWithoutAnAcceptedTransportAndCannotSendRequests() throws Exception {
        try (GardenSessionBridge bridge = new GardenSessionBridge(null)) {
            assertFalse(bridge.available());
            for (Action action : new Action[]{() -> bridge.session(), () -> bridge.login(PASSWORD), () -> bridge.logout()}) {
                try { action.run(); fail("Expected unavailable bridge"); }
                catch (GardenSessionBridge.Unavailable expected) { }
            }
            assertEquals("", loginForm);
            assertEquals(0, uploads.get());
        }
    }

    @Test public void bridgeReturnsConfirmedSessionAndDoesNotInventLogoutSuccess() throws Exception {
        try (GardenSessionBridge bridge = new GardenSessionBridge(client)) {
            assertTrue(bridge.available());
            assertFalse(bridge.session().authenticated);
            assertTrue(bridge.login(PASSWORD).authenticated);
            mode = "logout-failure";
            fails(HTTP_ERROR, () -> bridge.logout());
            assertTrue(authenticated);
            mode = "";
            assertTrue(bridge.login(PASSWORD).authenticated);
            assertFalse(bridge.logout().authenticated);
            assertFalse(authenticated);
        }
    }

    @Test public void contradictorySessionFlagsClearNativeAuthenticationBeforeAnyMutation() throws Exception {
        client.login(PASSWORD);
        configured = false;
        fails(MALFORMED_RESPONSE, () -> client.session());
        fails(SIGN_IN_REQUIRED, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        assertEquals(0, uploads.get());
    }

    @Test public void serverRetryAdviceDoesNotReplayPhotoUpload() throws Exception {
        client.login(PASSWORD);
        mode = "retry-upload";
        fails(HTTP_ERROR, () -> client.uploadPhoto(new byte[]{1}, "image/jpeg"));
        assertEquals(1, uploads.get());
    }

    @Test public void serverRetryAdviceDoesNotReplaySessionLookup() throws Exception {
        mode = "retry-session";
        fails(HTTP_ERROR, () -> client.session());
        assertEquals(1, sessions.get());
    }

}
