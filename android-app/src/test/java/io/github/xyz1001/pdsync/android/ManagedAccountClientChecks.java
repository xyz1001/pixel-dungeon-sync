package io.github.xyz1001.pdsync.android;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.Authenticator;
import okhttp3.CookieJar;
import okhttp3.Credentials;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import static io.github.xyz1001.pdsync.android.ManagedAccountClient.*;

/** Synthetic, in-memory transport checks only. Never connect, register, upload, or use real accounts. */
public final class ManagedAccountClientChecks {
    private static int checks;
    private static final String USER = "synthetic.Legacy-owner";
    private static final String PASSWORD = "synthetic-private-password-中";
    private static final String AUTH = Credentials.basic(USER, PASSWORD, StandardCharsets.UTF_8);
    private static final String VALID = "{\"code\":200,\"data\":{\"source\":\"managed_ledger\",\"username\":\""
            + USER + "\",\"expires_ms\":1800000000000,\"active\":true,\"managed\":true}}";

    public static void main(String[] args) throws Exception {
        constructionAndEntitlement();
        accountExpiryAndPublicAccess();
        invalidInputs();
        strictEntitlement();
        strictJson();
        httpFailuresAndRedaction();
        responseLimitsAndClosure();
        appreciationContract();
        imagePolicy();
        transportPolicy();
        cancellation();
        System.out.println("Managed official account client: " + checks + " host checks passed; no network used.");
    }

    private static void constructionAndEntitlement() throws Exception {
        AtomicInteger count = new AtomicInteger();
        ManagedAccountClient client = client(request -> {
            count.incrementAndGet();
            check(ENTITLEMENT_URL.equals(request.url().toString()), "fixed real entitlement path");
            check("GET".equals(request.method()) && request.body() == null, "read-only bodyless GET");
            check(AUTH.equals(request.header("Authorization")), "current Basic credentials, exact UTF-8");
            check("application/json".equals(request.header("Accept")), "accept JSON");
            check("no-store".equals(request.header("Cache-Control")), "no cache request");
            check(request.header("Cookie") == null && request.header("Referer") == null, "no unrelated identifiers");
            check(!request.url().toString().contains(USER) && !request.url().toString().contains(PASSWORD), "no URL credentials");
            return json(request, VALID);
        });
        check(count.get() == 0, "construction has no I/O");
        new ManagedAccountClient(DAV_URL, USER, PASSWORD);
        Entitlement value = client.entitlement();
        check(count.get() == 1, "one requested read");
        check(value.username.equals(USER) && value.source.equals("managed_ledger"), "matched real ledger identity");
        check(value.expiresMs == 1800000000000L && value.active && value.managed, "typed immutable metadata");
        check(!value.toString().contains(USER) && !client.toString().contains(USER), "diagnostics hide account");
        check(!client.toString().contains(PASSWORD) && !client.toString().contains(AUTH), "diagnostics hide credentials");
        for (java.lang.reflect.Field field : Entitlement.class.getDeclaredFields()) {
            check(Modifier.isFinal(field.getModifiers()), "all entitlement fields final");
            check(!field.getName().equals("password"), "result retains no password");
        }
        Entitlement unlimited = success(VALID.replace("1800000000000", "null"));
        check(unlimited.expiresMs == null && unlimited.active, "null expiration supported without inventing date");
        Entitlement expired = success(VALID.replace("1800000000000", "1").replace("\"active\":true", "\"active\":false"));
        check(expired.expiresMs == 1L && !expired.active, "inactive remains ordinary metadata until a synchronization attempt");
        Entitlement unmanaged = success(VALID.replace("\"managed\":true", "\"managed\":false"));
        check(!unmanaged.managed && unmanaged.active, "unmanaged account preserves server-reported active status");
        check(success(VALID.replace("1800000000000", Long.toString(Long.MAX_VALUE))).expiresMs == Long.MAX_VALUE,
                "expiration integer upper boundary");
        check(success(VALID.replace("1800000000000", "0")).expiresMs == 0, "expiration zero retained");
    }

    private static void accountExpiryAndPublicAccess() throws Exception {
        Entitlement active = success(VALID);
        active.requireSyncAccess();
        check(active.serverTimeMs == null, "legacy response does not invent a server clock");
        Entitlement inactive = success(VALID.replace("\"active\":true", "\"active\":false"));
        fail(inactive::requireSyncAccess, FailureKind.ACCOUNT_INACTIVE);
        Entitlement expired = success(VALID.replace("\"active\":true", "\"active\":false")
                .replace("\"managed\":true", "\"managed\":true,\"server_time_ms\":1800000000000"));
        check(expired.serverTimeMs == 1800000000000L, "authenticated server clock retained");
        fail(expired::requireSyncAccess, FailureKind.ACCOUNT_EXPIRED);
        try { expired.requireSyncAccess(); throw new AssertionError(); }
        catch (ManagedAccountException e) { check(e.userMessage().equals("服务已到期"), "confirmed expiry has exact short copy"); }
        for (String value : new String[] {"null", "-1", "1.0", "\"1\"", "true", "9223372036854775808"})
            invalid(VALID.replace("\"managed\":true", "\"managed\":true,\"server_time_ms\":" + value));
        Entitlement noDate = success(VALID.replace("1800000000000", "null")
                .replace("\"active\":true", "\"active\":false")
                .replace("\"managed\":true", "\"managed\":true,\"server_time_ms\":1800000000000"));
        fail(noDate::requireSyncAccess, FailureKind.ACCOUNT_INACTIVE);
        Entitlement notYetExpired = success(VALID.replace("\"active\":true", "\"active\":false")
                .replace("\"managed\":true", "\"managed\":true,\"server_time_ms\":1"));
        fail(notYetExpired::requireSyncAccess, FailureKind.ACCOUNT_INACTIVE);
        AtomicInteger calls = new AtomicInteger();
        byte[] png = png(1, 1);
        String metadata = appreciationJson(png);
        ManagedAccountClient publicClient = ManagedAccountClient.publicAppreciation(request -> {
            calls.incrementAndGet();
            check(request.header("Authorization") == null, "public client cannot transmit credentials");
            return APPRECIATION_URL.equals(request.url().toString()) ? json(request, metadata)
                    : response(request, 200, ResponseBody.create(png, MediaType.get("image/png")));
        });
        check(!publicClient.canReadEntitlement(), "unverified input has no private account authority");
        fail(publicClient::entitlement, FailureKind.AUTHENTICATION_REQUIRED);
        check(calls.get() == 0, "no private request for unverified form input");
        check(Arrays.equals(png, publicClient.paymentImage(publicClient.appreciation())), "public QR is available without login");
        check(calls.get() == 2, "exactly public metadata and image reads");
        try { client(r -> response(r, 401, ResponseBody.create("", MediaType.get("application/json")))).entitlement(); }
        catch (ManagedAccountException e) {
            check(e.kind == FailureKind.AUTHENTICATION_REQUIRED && e.userMessage().equals("无法登录，请检查账号或续期状态。"),
                    "401 does not guess expired or wrong password");
        }
    }

    private static void invalidInputs() throws Exception {
        AtomicInteger count = new AtomicInteger();
        Transport forbidden = request -> { count.incrementAndGet(); throw new AssertionError("No invalid input may be sent"); };
        for (String endpoint : new String[] {null, "", "http://webdav.xyz1001.top:8443/dav/", "https://evil.invalid/dav/",
                ORIGIN + "/dav/", ORIGIN, ORIGIN + "//", ORIGIN + "/?q=1", ORIGIN + "/#x", ORIGIN + "/dav", ORIGIN + "/dav//", ORIGIN + "/dav/?q=1", ORIGIN + "/dav/#x",
                "https://user@webdav.xyz1001.top:8443/dav/", "https://webdav.xyz1001.top/dav/",
                "https://WEBDAV.xyz1001.top:8443/dav/", "http://127.0.0.1:18080/", ORIGIN + "/%64av/"}) {
            fail(() -> new ManagedAccountClient(endpoint, USER, PASSWORD, forbidden), FailureKind.INVALID_INPUT);
        }
        for (String username : new String[] {null, "", "bad:name", "bad\nname", "用户名", ".bad", "x".repeat(65)}) {
            fail(() -> new ManagedAccountClient(DAV_URL, username, PASSWORD, forbidden), FailureKind.INVALID_INPUT);
        }
        for (String password : new String[] {null, "", "bad\npassword", "bad\u0085password", "\ud800", "x".repeat(513), "中".repeat(171)}) {
            fail(() -> new ManagedAccountClient(DAV_URL, USER, password, forbidden), FailureKind.INVALID_INPUT);
        }
        for (String password : new String[] {"x", "$legacy", "{legacy", " keep spaces ", "x".repeat(512), "中".repeat(170)}) {
            new ManagedAccountClient(DAV_URL, "_Legacy.Name-1", password, forbidden);
            check(true, "legacy login credential compatibility");
        }
        check(count.get() == 0, "all configuration validation precedes transport");
    }

    private static void strictEntitlement() throws Exception {
        for (String bad : new String[] {
                VALID.replace("managed_ledger", "sandbox"), VALID.replace("managed_ledger", "webdav"),
                VALID.replace(USER, "synthetic-other-account"), VALID.replace(USER, USER.toUpperCase()),
                VALID.replace("\"source\":\"managed_ledger\",", ""), VALID.replace("\"username\":\"" + USER + "\",", ""),
                VALID.replace("\"expires_ms\":1800000000000,", ""), VALID.replace("\"active\":true,", ""),
                VALID.replace(",\"managed\":true", ""), VALID.replace("\"active\":true", "\"active\":\"true\""),
                VALID.replace("\"managed\":true", "\"managed\":1"), VALID.replace("\"active\":true", "\"active\":null"),
                VALID.replace("1800000000000", "\"1800000000000\""), VALID.replace("1800000000000", "1.0"),
                VALID.replace("1800000000000", "1e3"), VALID.replace("1800000000000", "-1"),
                VALID.replace("1800000000000", "9223372036854775808"), VALID.replace("1800000000000", "false"),
                VALID.replace("\"code\":200", "\"code\":\"200\""), VALID.replace("\"code\":200", "\"code\":201"),
                VALID.replace("\"code\":200", "\"code\":200,\"sandbox\":false"),
                VALID.replace("\"active\":true", "\"active\":true,\"sandbox\":true"),
                "{\"sandbox\":true,\"data\":{\"active\":true}}", "{\"code\":200,\"data\":[]}"}) {
            invalid(bad);
        }
    }

    private static void strictJson() throws Exception {
        for (String bad : new String[] {
                "", "null", "[]", VALID + " null", VALID.replace("\"code\":200", "\"code\":200,\"code\":200"),
                VALID.replace("\"active\":true", "\"active\":true,\"act\\u0069ve\":true"),
                VALID.replace("\"code\":200", "\"code\":0200"), VALID.replace("\"code\":200", "\"code\":+200"),
                VALID.replace("\"code\":200", "\"code\":NaN"), VALID.replace("\"code\":200", "\"code\":Infinity"),
                VALID.replace("\"code\":200", "\"code\":200,\"extra\":nope"),
                VALID.replace("\"code\":200", "\"code\":200,\"extra\":\"\\x01\""),
                VALID.replace("\"code\":200", "\"code\":200,\"extra\":\"\\uD800\""),
                VALID.replace("\"code\":200", "\"code\":200,\"\\uDC00\":1"),
                VALID.replace("\"code\":200", "\"code\":200,\"extra\":\"literal\nnewline\""),
                VALID.replace("\"code\":200", "\"code\":200,\"extra\":" + "[".repeat(9) + "0" + "]".repeat(9)),
                VALID.replace("\"code\":200", "code:200"), VALID.replace("\"code\":200", "\"code\":200,"),
                VALID.replace("\"code\":200", "\"code\":200,\"extra\":{\"a\":1,\"a\":2}")}) {
            invalid(bad);
        }
        check(success(VALID.replace("\"code\":200", "\"code\":200,\"message\":\"成功\",\"future\":[null,1,true]")) != null,
                "valid extra metadata does not relax core contract");
        fail(() -> client(r -> response(r, 200, ResponseBody.create(new byte[] {(byte) 0xc0, (byte) 0x80},
                MediaType.get("application/json")))).entitlement(), FailureKind.INVALID_RESPONSE);
        for (String type : new String[] {"text/html", "text/plain", "application/json; charset=ISO-8859-1", "application/json; charset=not-a-charset"}) {
            fail(() -> client(r -> response(r, 200, ResponseBody.create(VALID, MediaType.get(type)))).entitlement(),
                    FailureKind.INVALID_RESPONSE);
        }
    }

    private static void httpFailuresAndRedaction() throws Exception {
        int[] statuses = {201, 204, 301, 302, 303, 307, 308, 400, 401, 403, 404, 408, 413, 429, 500, 503};
        FailureKind[] kinds = {FailureKind.INVALID_RESPONSE, FailureKind.INVALID_RESPONSE,
                FailureKind.REDIRECT_REJECTED, FailureKind.REDIRECT_REJECTED, FailureKind.REDIRECT_REJECTED,
                FailureKind.REDIRECT_REJECTED, FailureKind.REDIRECT_REJECTED, FailureKind.REQUEST_REJECTED,
                FailureKind.AUTHENTICATION_REQUIRED, FailureKind.FORBIDDEN, FailureKind.NOT_AVAILABLE,
                FailureKind.TIMEOUT, FailureKind.RESPONSE_TOO_LARGE, FailureKind.RATE_LIMITED,
                FailureKind.SERVER_ERROR, FailureKind.SERVER_ERROR};
        for (int i = 0; i < statuses.length; i++) {
            int status = statuses[i];
            AtomicInteger count = new AtomicInteger();
            fail(() -> client(r -> {
                count.incrementAndGet();
                return response(r, status, ResponseBody.create(PASSWORD + AUTH, MediaType.get("text/plain")))
                        .newBuilder().header("Location", "https://evil.invalid/" + USER).header("Retry-After", "0").build();
            }).entitlement(), kinds[i]);
            check(count.get() == 1, "HTTP error is one attempt without fallback");
        }
        for (String url : new String[] {"https://evil.invalid/account", ORIGIN + "/api/order/entitlement", DAV_URL}) {
            fail(() -> client(r -> json(r.newBuilder().url(url).build(), VALID)).entitlement(), FailureKind.REDIRECT_REJECTED);
        }
        fail(() -> client(r -> json(r, VALID).newBuilder().priorResponse(new Response.Builder().request(r)
                .protocol(Protocol.HTTP_1_1).code(503).message("synthetic").build()).build()).entitlement(),
                FailureKind.REDIRECT_REJECTED);
        fail(() -> client(r -> null).entitlement(), FailureKind.INVALID_RESPONSE);
        fail(() -> client(r -> { throw new IOException(PASSWORD + AUTH); }).entitlement(), FailureKind.NETWORK_ERROR);
        fail(() -> client(r -> { throw new SocketTimeoutException(PASSWORD + AUTH); }).entitlement(), FailureKind.TIMEOUT);
        fail(() -> client(r -> { throw new SSLHandshakeException(PASSWORD + AUTH); }).entitlement(), FailureKind.TLS_ERROR);
        fail(() -> client(r -> { throw new IllegalStateException(PASSWORD + AUTH); }).entitlement(), FailureKind.INVALID_RESPONSE);
    }

    private static void responseLimitsAndClosure() throws Exception {
        for (long length : new long[] {-1, MAX_JSON_BYTES + 1L}) {
            TrackingBody body = new TrackingBody(new byte[MAX_JSON_BYTES + 1], length, "application/json", false);
            fail(() -> client(r -> response(r, 200, body)).entitlement(), FailureKind.RESPONSE_TOO_LARGE);
            check(body.closed, "oversized response closed");
            check(body.bytesRead <= MAX_JSON_BYTES + 1, "unknown body bounded to limit plus one");
            if (length > MAX_JSON_BYTES) check(body.bytesRead == 0, "oversized declared body rejected unread");
        }
        for (String data : new String[] {VALID, "{}"}) {
            TrackingBody body = new TrackingBody(data.getBytes(StandardCharsets.UTF_8), -1, "application/json", false);
            if (data.equals(VALID)) client(r -> response(r, 200, body)).entitlement();
            else fail(() -> client(r -> response(r, 200, body)).entitlement(), FailureKind.INVALID_RESPONSE);
            check(body.closed, "response closed on success or parse failure");
        }
        TrackingBody closeFailure = new TrackingBody("{}".getBytes(StandardCharsets.UTF_8), -1, "application/json", true);
        fail(() -> client(r -> response(r, 200, closeFailure)).entitlement(), FailureKind.INVALID_RESPONSE);
        TrackingBody body = new TrackingBody(PASSWORD.getBytes(StandardCharsets.UTF_8), -1, "text/plain", false);
        fail(() -> client(r -> response(r, 401, body)).entitlement(), FailureKind.AUTHENTICATION_REQUIRED);
        check(body.closed && body.bytesRead == 0, "untrusted error bodies not read");
        String padded = VALID + " ".repeat(MAX_JSON_BYTES - VALID.getBytes(StandardCharsets.UTF_8).length);
        check(success(padded) != null, "exact JSON body byte boundary accepted");
    }

    private static void appreciationContract() throws Exception {
        byte[] png = png(1, 1);
        String valid = appreciationJson(png);
        Appreciation value = client(r -> {
            check(APPRECIATION_URL.equals(r.url().toString()), "real appreciation metadata path");
            check("GET".equals(r.method()) && r.body() == null && r.header("Authorization") == null,
                    "public read-only appreciation metadata never sends credentials");
            return json(r, valid);
        }).appreciation();
        check(value.version == 1 && value.paymentMode.equals("donation") && value.term.equals("calendar_year")
                && value.remark.equals("username") && value.imagePath.equals(IMAGE_PATH), "strict metadata values");
        for (java.lang.reflect.Field field : Appreciation.class.getDeclaredFields()) check(Modifier.isFinal(field.getModifiers()), "metadata immutable");
        for (String bad : new String[] {valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"version\":1", "\"version\":\"1\""), valid.replace("\"version\":1,", ""),
                valid.replace("donation", "fixed_price"), valid.replace("calendar_year", "365_days"),
                valid.replace("\"remark\":\"username\"", "\"remark\":\"" + USER + "\""),
                valid.replace(IMAGE_PATH, "https://evil.invalid/payment.png"), valid.replace(IMAGE_PATH, "/api/order/payment-image/plan"),
                valid.replace(IMAGE_PATH, IMAGE_PATH + "?id=1"), valid.replace(IMAGE_PATH, "/dav/payment.png"),
                valid.replace(hash(png), "f".repeat(63)), valid.replace(hash(png), "Z".repeat(64)),
                valid.replace("\"code\":200", "\"code\":200,\"sandbox\":true")}) {
            fail(() -> client(r -> json(r, bad)).appreciation(), FailureKind.INVALID_RESPONSE);
        }
        fail(() -> client(r -> response(r, 404, ResponseBody.create("unconfigured", MediaType.get("text/plain"))))
                .appreciation(), FailureKind.NOT_AVAILABLE);
    }

    private static void imagePolicy() throws Exception {
        byte[] png = png(1, 1);
        Appreciation metadata = metadata(png);
        AtomicInteger count = new AtomicInteger();
        ManagedAccountClient client = client(r -> {
            count.incrementAndGet();
            check(IMAGE_URL.equals(r.url().toString()), "fixed same-official-origin PNG");
            check("GET".equals(r.method()) && r.body() == null, "image is read-only GET");
            check(r.header("Authorization") == null && r.header("Cookie") == null && r.header("Referer") == null,
                    "image request never leaks account authentication");
            check("image/png".equals(r.header("Accept")), "PNG only");
            return response(r, 200, ResponseBody.create(png, MediaType.get("image/png")));
        });
        check(Arrays.equals(png, client.paymentImage(metadata)), "verified image returned unchanged");
        check(count.get() == 1, "one image read");
        fail(() -> client.paymentImage(null), FailureKind.INVALID_INPUT);
        check(count.get() == 1, "invalid metadata no request");
        Appreciation mismatched = metadata(png(2, 1));
        fail(() -> client.paymentImage(mismatched), FailureKind.IMAGE_INTEGRITY_ERROR);
        for (int status : new int[] {301, 302, 307, 308}) {
            fail(() -> client(r -> response(r, status, ResponseBody.create(png, MediaType.get("image/png")))
                    .newBuilder().header("Location", "https://evil.invalid/").build()).paymentImage(metadata), FailureKind.REDIRECT_REJECTED);
        }
        fail(() -> client(r -> response(r.newBuilder().url("https://evil.invalid/image").build(), 200,
                ResponseBody.create(png, MediaType.get("image/png")))).paymentImage(metadata), FailureKind.REDIRECT_REJECTED);
        for (byte[] bad : new byte[][] {new byte[0], new byte[80], Arrays.copyOf(png, png.length - 1),
                Arrays.copyOf(png, png.length + 1), png(0, 1), png(2049, 1), png(1, 2049), png(2000, 1001), corrupt(png), hugeChunk(png),
                badChunk(png, "acTL", new byte[8]), badChunk(png, "abca", new byte[0]), invalidScanlinePng()}) {
            fail(() -> client(r -> response(r, 200, ResponseBody.create(bad, MediaType.get("image/png"))))
                    .paymentImage(metadata(bad)), FailureKind.INVALID_RESPONSE);
        }
        for (String type : new String[] {"image/jpeg", "text/html", "application/octet-stream"}) {
            fail(() -> client(r -> response(r, 200, ResponseBody.create(png, MediaType.get(type))))
                    .paymentImage(metadata), FailureKind.INVALID_RESPONSE);
        }
        for (long length : new long[] {-1, MAX_IMAGE_BYTES + 1L}) {
            TrackingBody large = new TrackingBody(new byte[MAX_IMAGE_BYTES + 1], length, "image/png", false);
            fail(() -> client(r -> response(r, 200, large)).paymentImage(metadata), FailureKind.RESPONSE_TOO_LARGE);
            check(large.closed && large.bytesRead <= MAX_IMAGE_BYTES + 1, "image bounded and closed");
        }
    }

    private static void transportPolicy() throws Exception {
        OkHttpClient client = newHttpClient();
        check(!client.retryOnConnectionFailure() && !client.followRedirects() && !client.followSslRedirects(), "redirects and retries off");
        check(client.authenticator() == Authenticator.NONE && client.proxyAuthenticator() == Authenticator.NONE, "no authentication follow-up");
        check(client.cookieJar() == CookieJar.NO_COOKIES && client.cache() == null, "no cookie cache");
        check(client.connectTimeoutMillis() == 8000 && client.readTimeoutMillis() == 10000
                && client.writeTimeoutMillis() == 10000 && client.callTimeoutMillis() == 20000, "20s total request bound");
        check(client.hostnameVerifier() == new OkHttpClient().hostnameVerifier(), "normal hostname validation");
        check(client.x509TrustManager().getAcceptedIssuers().length > 0, "normal trusted certificates");
        check(client.interceptors().size() == 1 && client.networkInterceptors().size() == 1, "only explicit one-attempt guards");
        Request request = new Request.Builder().url(ENTITLEMENT_URL).get().build();
        final Request[] tagged = {null};
        Interceptor.Chain application = chain(request, r -> { tagged[0] = r; return json(r, VALID); });
        client.interceptors().get(0).intercept(application).close();
        check(tagged[0] != null, "attempt state installed without any network");
        AtomicInteger count = new AtomicInteger();
        Interceptor.Chain network = chain(tagged[0], r -> { count.incrementAndGet(); return json(r, VALID); });
        client.networkInterceptors().get(0).intercept(network).close();
        try {
            client.networkInterceptors().get(0).intercept(network);
            throw new AssertionError("Must reject OkHttp HTTP-status replays too");
        } catch (IOException expected) { check(count.get() == 1, "second network exchange blocked before proceed"); }
    }

    private static void cancellation() throws Exception {
        AtomicInteger cancelled = new AtomicInteger();
        ManagedAccountClient client = client(new Transport() {
            @Override public Response execute(Request request) { throw new AssertionError("Cancellation performs no request"); }
            @Override public void cancelPending() { cancelled.incrementAndGet(); throw new IllegalStateException(PASSWORD); }
        });
        client.cancelPending();
        check(cancelled.get() == 1, "safe cancellable UI requests");
    }

    private interface Checked { void run() throws Exception; }
    private static void fail(Checked work, FailureKind kind) throws Exception {
        ByteArrayOutputStream logs = new ByteArrayOutputStream();
        PrintStream oldOut = System.out, oldErr = System.err;
        try (PrintStream capture = new PrintStream(logs, true, StandardCharsets.UTF_8)) {
            System.setOut(capture); System.setErr(capture);
            try { work.run(); throw new AssertionError("Expected " + kind); }
            catch (ManagedAccountException error) {
                check(error.kind == kind, "failure kind " + kind + " instead of " + error.kind);
                check(error.getCause() == null && error.getSuppressed().length == 0, "safe exceptions have no nested diagnostics");
                StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
                check(!trace.toString().contains(PASSWORD) && !trace.toString().contains(AUTH)
                        && !trace.toString().contains(USER), "exception redacts account, password, and Basic token");
                check(error.userMessage().equals(error.getMessage()), "safe UI error text");
            }
        } finally { System.setOut(oldOut); System.setErr(oldErr); }
        check(logs.size() == 0, "client emits no stdout/stderr diagnostics");
    }
    private static void invalid(String json) throws Exception { fail(() -> success(json), FailureKind.INVALID_RESPONSE); }
    private static Entitlement success(String json) throws Exception { return client(r -> json(r, json)).entitlement(); }
    private static ManagedAccountClient client(Transport transport) throws ManagedAccountException {
        return new ManagedAccountClient(DAV_URL, USER, PASSWORD, transport);
    }
    private static Appreciation metadata(byte[] png) throws Exception {
        String json = appreciationJson(png);
        return client(r -> json(r, json)).appreciation();
    }
    private static String appreciationJson(byte[] png) throws Exception {
        return "{\"code\":200,\"data\":{\"version\":1,\"payment_mode\":\"donation\",\"image_path\":\"" + IMAGE_PATH
                + "\",\"image_sha256\":\"" + hash(png) + "\",\"term\":\"calendar_year\",\"remark\":\"username\"}}";
    }
    private static Response json(Request request, String json) { return response(request, 200, ResponseBody.create(json, MediaType.get("application/json"))); }
    private static Response response(Request request, int status, ResponseBody body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic").body(body).build();
    }
    private static Interceptor.Chain chain(Request request, Transport proceed) {
        return (Interceptor.Chain) Proxy.newProxyInstance(Interceptor.Chain.class.getClassLoader(), new Class<?>[] {Interceptor.Chain.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("request")) return request;
                    if (method.getName().equals("proceed")) return proceed.execute((Request) args[0]);
                    throw new AssertionError("Unexpected chain method: " + method.getName());
                });
    }
    private static String hash(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value)) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(header)) {
            out.writeInt(width); out.writeInt(height); out.write(new byte[] {8, 6, 0, 0, 0});
        }
        chunk(result, "IHDR", header.toByteArray());
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(compressed)) {
            byte[] row = new byte[Math.max(1, width * 4 + 1)];
            for (int i = 0; i < Math.max(1, height); i++) zip.write(row);
        }
        chunk(result, "IDAT", compressed.toByteArray()); chunk(result, "IEND", new byte[0]);
        return result.toByteArray();
    }
    private static byte[] badChunk(byte[] png, String type, byte[] data) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(png, 0, 33); chunk(result, type, data); result.write(png, 33, png.length - 33);
        return result.toByteArray();
    }
    private static byte[] invalidScanlinePng() throws IOException {
        byte[] original = png(1, 1);
        ByteArrayOutputStream result = new ByteArrayOutputStream(); result.write(original, 0, 33);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(compressed)) { zip.write(new byte[] {5, 0, 0, 0, 0}); }
        chunk(result, "IDAT", compressed.toByteArray()); chunk(result, "IEND", new byte[0]); return result.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream output = new DataOutputStream(out);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(data.length); output.write(name); output.write(data);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(data); output.writeInt((int) crc.getValue());
    }
    private static byte[] corrupt(byte[] value) { byte[] result = value.clone(); result[29] ^= 1; return result; }
    private static byte[] hugeChunk(byte[] value) { byte[] result = value.clone(); Arrays.fill(result, 8, 12, (byte) 255); return result; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }

    private static final class TrackingBody extends ResponseBody {
        final long declared;
        final String type;
        final BufferedSource source;
        boolean closed;
        long bytesRead;
        TrackingBody(byte[] bytes, long declared, String type, boolean failClose) {
            this.declared = declared; this.type = type;
            Buffer contents = new Buffer().write(bytes);
            source = Okio.buffer(new Source() {
                @Override public long read(Buffer sink, long count) {
                    long n = contents.read(sink, count); if (n > 0) bytesRead += n; return n;
                }
                @Override public Timeout timeout() { return Timeout.NONE; }
                @Override public void close() {
                    closed = true;
                    if (failClose) throw new IllegalStateException(PASSWORD + AUTH);
                }
            });
        }
        @Override public MediaType contentType() { return MediaType.get(type); }
        @Override public long contentLength() { return declared; }
        @Override public BufferedSource source() { return source; }
    }
}
