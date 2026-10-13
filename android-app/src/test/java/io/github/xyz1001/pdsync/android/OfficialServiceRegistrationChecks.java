package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import okhttp3.Authenticator;
import okhttp3.CookieJar;
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
import static io.github.xyz1001.pdsync.android.OfficialServiceRegistrationClient.*;

/** Every HTTP response here is synthetic. Even the production transport-policy test opens no socket. */
public final class OfficialServiceRegistrationChecks {
    private static int checks;
    private static final String USER = "synthetic-user-never-register";
    private static final String PASSWORD = "synthetic-password-never-secret";
    private static final String VALID = "{\"code\":200,\"message\":\"success\",\"data\":{"
            + "\"webdav_url\":\"" + DAV_URL + "\",\"username\":\"" + USER
            + "\",\"quota_mb\":5,\"quota_bytes\":5242880,\"upload_bandwidth_kb\":100,\"download_bandwidth_kb\":100}}";

    public static void main(String[] args) throws Exception {
        constructionAndSuccess();
        inputValidationAndLegacyLogin();
        transportPolicy();
        httpFailures();
        strictResponseTypes();
        quotaMetadataIsNotClientPolicy();
        quotaMbAllowsServerFloat();
        check(args.length == 1, "real server account_info fixtures supplied by host script");
        serverAccountInfoInterop(args[0]);
        endpointAndCredentialPolicy();
        bodyLimitsAndClosure();
        unknownOutcomesAndRedaction();
        System.out.println("Official service v2 login/registration: " + checks + " host checks passed; no network used.");
    }

    private static void constructionAndSuccess() throws Exception {
        for (boolean registration : new boolean[] {true, false}) {
            AtomicInteger calls = new AtomicInteger();
            OfficialServiceRegistrationClient client = new OfficialServiceRegistrationClient(request -> {
                calls.incrementAndGet();
                check(REGISTER_URL.equals(request.url().toString()), "fixed v2 service URL");
                check("POST".equals(request.method()), "POST only");
                check(request.header("Authorization") == null, "no authorization header");
                check(request.header("Cookie") == null, "no cookies");
                check("application/json".equals(request.header("Accept")), "accept JSON");
                check(request.body().contentType().toString().startsWith("application/json"), "JSON body");
                check(request.body().isOneShot(), "non-replayable request body");
                JsonObject body = requestJson(request);
                check(body.size() == 4, "exactly four v2 fields; never empty object");
                JsonPrimitive version = (JsonPrimitive) body.get("version");
                check(!version.isString() && "2".equals(version.getContent()), "numeric version two");
                check((registration ? "register" : "login").equals(field(body, "action")), "explicit v2 action");
                check(USER.equals(field(body, "username")) && PASSWORD.equals(field(body, "password")),
                        "submitted credentials in JSON body only");
                check(!request.toString().contains(PASSWORD) && !request.body().toString().contains(PASSWORD),
                        "request diagnostics redacted");
                return response(request, 200, VALID);
            });
            check(calls.get() == 0, "injected construction performs no I/O");
            new OfficialServiceRegistrationClient();
            Account account = registration ? client.register(USER, PASSWORD) : client.login(USER, PASSWORD);
            check(calls.get() == 1, "exactly one explicit transport invocation");
            check(DAV_URL.equals(account.endpoint), "DAV endpoint");
            check(USER.equals(account.username) && PASSWORD.equals(account.password), "submitted credentials retained");
            check(account.quotaMb == 5 && account.quotaBytes == 5242880
                    && account.uploadBandwidthKb == 100 && account.downloadBandwidthKb == 100, "v2 limits");
            check(!account.toString().contains(USER) && !account.toString().contains(PASSWORD), "redacted account");
            check(!account.toString().contains(DAV_URL), "no private result fields in toString");
        }
        String password = " quoted\"password\\and unicode\u4e2d\u6587 ";
        Account escaped = new OfficialServiceRegistrationClient(request -> {
            check(password.equals(field(requestJson(request), "password")), "JSON credential escaping is lossless");
            return response(request, 200, VALID);
        }).register(USER, password);
        check(password.equals(escaped.password), "exact submitted password is DAV password");
        Account asymmetric = success(VALID.replace("\"upload_bandwidth_kb\":100", "\"upload_bandwidth_kb\":125")
                .replace("\"download_bandwidth_kb\":100", "\"download_bandwidth_kb\":65"));
        check(asymmetric.uploadBandwidthKb == 125 && asymmetric.downloadBandwidthKb == 65, "separate bandwidth limits");
        check(Arrays.stream(OfficialServiceRegistrationClient.class.getDeclaredMethods())
                .noneMatch(method -> "register".equals(method.getName()) && method.getParameterCount() == 0),
                "no legacy no-argument registration API");
    }

    private static void inputValidationAndLegacyLogin() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OfficialServiceRegistrationClient forbidden = new OfficialServiceRegistrationClient(request -> {
            calls.incrementAndGet();
            throw new AssertionError("Invalid input must not reach transport");
        });
        for (String username : new String[] {null, "", "ab", "Aname", "0name", "a.name", " abc", "abc ",
                "a:b", "中文账号", "a".repeat(33), "bad\nname", "bad\u0000name", "\ud800"}) {
            expectFailure(() -> forbidden.register(username, PASSWORD), FailureKind.INVALID_INPUT, false);
        }
        for (String password : new String[] {null, "", "1234567", "x".repeat(129), "$password", "{password",
                "bad\rpassword", "bad\u007fpassword", "bad\u0085password", "\ud800"}) {
            expectFailure(() -> forbidden.register(USER, password), FailureKind.INVALID_INPUT, false);
        }
        for (String username : new String[] {null, "", "a:b", "bad\nname", "x".repeat(65), "\ud800",
                ".legacy", "-legacy", "Legacy+Name", "用户账号"}) {
            expectFailure(() -> forbidden.login(username, PASSWORD), FailureKind.INVALID_INPUT, false);
        }
        for (String password : new String[] {null, "", "bad\npassword", "x".repeat(513), "\ud800",
                "中".repeat(171), "\ud83d\ude00".repeat(129)}) {
            expectFailure(() -> forbidden.login(USER, password), FailureKind.INVALID_INPUT, false);
        }
        check(calls.get() == 0, "all invalid inputs rejected before transport");
        for (String username : new String[] {"abc", "a_-", "a".repeat(32)}) {
            Account account = echoingClient().register(username, "12345678");
            check(username.equals(account.username), "registration username boundaries");
        }
        check(echoingClient().register(USER, "x".repeat(128)).password.length() == 128, "registration maximum password");
        check(echoingClient().register(USER, "\ud83d\ude00".repeat(8)).password.codePointCount(0, 16) == 8,
                "registration length counts Unicode characters");
        for (String username : new String[] {"A", "12", "Legacy.Account-One", "_", "a".repeat(64)}) {
            for (String password : new String[] {"x", "$legacy", "{legacy", " keep spaces ", "x".repeat(129)}) {
                Account account = echoingClient().login(username, password);
                check(username.equals(account.username) && password.equals(account.password), "legacy login rules kept separate");
            }
        }
        for (String password : new String[] {"x".repeat(512), "中".repeat(170), "\ud83d\ude00".repeat(128)}) {
            check(password.equals(echoingClient().login("Legacy.Name", password).password),
                    "legacy password UTF-8 byte boundary");
        }
    }

    private static OfficialServiceRegistrationClient echoingClient() {
        return new OfficialServiceRegistrationClient(request -> {
            JsonObject body = requestJson(request);
            return response(request, 200, VALID.replace(USER, field(body, "username")));
        });
    }

    private static JsonObject requestJson(Request request) throws IOException {
        Buffer body = new Buffer();
        request.body().writeTo(body);
        check(body.size() == request.body().contentLength(), "accurate UTF-8 request length");
        String text = body.readUtf8();
        check(!"{}".equals(text), "never legacy empty registration request");
        return (JsonObject) Json.Default.parseToJsonElement(text);
    }

    private static String field(JsonObject object, String key) {
        JsonPrimitive value = (JsonPrimitive) object.get(key);
        check(value.isString(), "request field is a string");
        return value.getContent();
    }

    private static void transportPolicy() {
        OkHttpClient client = newHttpClient();
        check(!client.retryOnConnectionFailure(), "connection retry disabled");
        check(!client.followRedirects() && !client.followSslRedirects(), "all redirects disabled");
        check(client.authenticator() == Authenticator.NONE, "no auth follow-up");
        check(client.proxyAuthenticator() == Authenticator.NONE, "no proxy auth follow-up");
        check(client.cookieJar() == CookieJar.NO_COOKIES && client.cache() == null, "no cookie or response cache");
        check(client.interceptors().isEmpty() && client.networkInterceptors().isEmpty(), "no logging/inherited interceptors");
        check(client.callTimeoutMillis() == 20000 && client.connectTimeoutMillis() == 8000
                && client.readTimeoutMillis() == 10000 && client.writeTimeoutMillis() == 10000, "bounded timeouts");
        OkHttpClient standard = new OkHttpClient();
        check(client.hostnameVerifier() == standard.hostnameVerifier(), "default hostname validation");
        check(client.certificatePinner().equals(standard.certificatePinner()), "default TLS pin policy");
        check(client.x509TrustManager().getAcceptedIssuers().length > 0, "system trust store retained");
    }

    private static void httpFailures() throws Exception {
        failResponse(429, "{\"code\":429,\"message\":\"" + PASSWORD + "\"}", FailureKind.RATE_LIMITED, false);
        failResponse(400, PASSWORD, FailureKind.INVALID_INPUT, false);
        failResponse(409, PASSWORD, FailureKind.USERNAME_TAKEN, false);
        failResponse(413, PASSWORD, FailureKind.REQUEST_TOO_LARGE, false);
        failResponse(415, PASSWORD, FailureKind.UNSUPPORTED_MEDIA_TYPE, false);
        failResponse(502, PASSWORD, FailureKind.UPSTREAM_ERROR, true);
        failResponse(500, PASSWORD, FailureKind.SERVER_ERROR, true);
        failResponse(503, PASSWORD, FailureKind.SERVER_ERROR, true);
        failResponse(408, PASSWORD, FailureKind.TIMEOUT, true);
        failResponse(401, PASSWORD, FailureKind.INVALID_CREDENTIALS, false);
        failResponse(403, PASSWORD, FailureKind.REQUEST_REJECTED, false);
        failResponse(421, PASSWORD, FailureKind.REQUEST_REJECTED, false);
        failResponse(201, VALID, FailureKind.INVALID_RESPONSE, true);
        failResponse(204, "", FailureKind.INVALID_RESPONSE, true);
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            AtomicInteger calls = new AtomicInteger();
            expectFailure(() -> new OfficialServiceRegistrationClient(request -> {
                calls.incrementAndGet();
                return response(request, status, PASSWORD).newBuilder()
                        .header("Location", "https://other.invalid/account").build();
            }).register(USER, PASSWORD), FailureKind.REDIRECT_REJECTED, true);
            check(calls.get() == 1, "redirect never followed");
        }
        AtomicInteger calls = new AtomicInteger();
        expectFailure(() -> new OfficialServiceRegistrationClient(request -> {
            calls.incrementAndGet();
            check(request.body().isOneShot(), "503 body remains non-replayable");
            return response(request, 503, PASSWORD).newBuilder().header("Retry-After", "0").build();
        }).register(USER, PASSWORD), FailureKind.SERVER_ERROR, true);
        check(calls.get() == 1, "no client retry for Retry-After zero");
        expectFailure(() -> new OfficialServiceRegistrationClient(request -> response(
                request.newBuilder().url("https://other.invalid/api/register").build(), 200, VALID)).register(USER, PASSWORD),
                FailureKind.REDIRECT_REJECTED, true);
    }

    private static void strictResponseTypes() throws Exception {
        for (String invalid : new String[] {"", "[]", "null", "true", "{", VALID + " garbage",
                VALID.replace("\"code\":200", "\"code\":0200"),
                VALID.replace("\"data\":{", "\"extra\":NaN,\"data\":{"),
                VALID.replace("\"data\":{", "\"extra\":not_json,\"data\":{"),
                VALID.replace("\"code\":200", "\"code\":\"200\""),
                VALID.replace("\"code\":200", "\"code\":200.0"),
                VALID.replace("\"code\":200", "\"code\":201"),
                VALID.replace("\"message\":\"success\"", "\"message\":true"),
                VALID.replace("\"message\":\"success\",", ""),
                VALID.replace("\"username\":\"" + USER + "\"", "\"username\":123"),
                VALID.replace("\"data\":{", "\"data\":{\"password\":\"forbidden-server-password\","),
                VALID.replace("\"data\":{", "\"data\":{\"token\":\"forbidden-token\","),
                VALID.replace("\"quota_mb\":5", "\"quota_mb\":\"5\""),
                VALID.replace("\"quota_mb\":5", "\"quota_mb\":false"),
                VALID.replace("\"quota_mb\":5", "\"quota_mb\":-5"),
                VALID.replace("\"quota_bytes\":5242880", "\"quota_bytes\":9223372036854775808"),
                VALID.replace("\"quota_bytes\":5242880,", ""),
                VALID.replace("\"upload_bandwidth_kb\":100", "\"upload_bandwidth_kb\":null"),
                VALID.replace("\"download_bandwidth_kb\":100", "\"download_bandwidth_kb\":\"100\""),
                VALID.replace(",\"download_bandwidth_kb\":100", ""),
                VALID.replace(USER, "another-user"),
                VALID.replace("\"code\":200", "\"code\":429,\"code\":200"),
                VALID.replace("\"code\":200", "\"co\\u0064e\":429,\"code\":200"),
                VALID.replace("\"data\":{", "\"data\":{},\"data\":{"),
                VALID.replace("\"data\":{", "\"\\u0064ata\":{},\"data\":{"),
                VALID.replace("\"username\":", "\"username\":\"other\",\"username\":"),
                VALID.replace("\"username\":", "\"\\u0075sername\":\"other\",\"username\":"),
                VALID.replace("\"upload_bandwidth_kb\":", "\"upload_bandwidth_kb\":50,\"upload_bandwidth_kb\":"),
                VALID.replace("\"username\":", "\"usern\\u0061me\":\"other\",\"username\":"),
                VALID.replace("\"data\":{", "\"extra\":{\"x\":1,\"x\":2},\"data\":{"),
                VALID.replace("\"data\":{", "\"extra\":[[[[[[[[[0]]]]]]]]],\"data\":{")}) {
            failResponse(200, invalid, FailureKind.INVALID_RESPONSE, true);
        }
        success(VALID.replace("\"data\":{", "\"future_field\":true,\"data\":{"));
    }

    private static void quotaMetadataIsNotClientPolicy() throws Exception {
        String zero = VALID.replace("\"quota_mb\":5", "\"quota_mb\":0")
                .replace("\"quota_bytes\":5242880", "\"quota_bytes\":0")
                .replace("\"upload_bandwidth_kb\":100", "\"upload_bandwidth_kb\":0")
                .replace("\"download_bandwidth_kb\":100", "\"download_bandwidth_kb\":0");
        for (boolean registration : new boolean[] {true, false}) {
            OfficialServiceRegistrationClient client = new OfficialServiceRegistrationClient(request -> response(request, 200, zero));
            Account account = registration ? client.register(USER, PASSWORD) : client.login(USER, PASSWORD);
            check(account.quotaMb == 0 && account.quotaBytes == 0
                    && account.uploadBandwidthKb == 0 && account.downloadBandwidthKb == 0,
                    "zero/unlimited server metadata is accepted for either action");
            String fractional = VALID.replace("\"quota_bytes\":5242880", "\"quota_bytes\":5242881");
            client = new OfficialServiceRegistrationClient(request -> response(request, 200, fractional));
            account = registration ? client.register(USER, PASSWORD) : client.login(USER, PASSWORD);
            check(account.quotaMb == 5 && account.quotaBytes == 5242881, "quota fields need not be exact MiB multiples");
        }
        String[] fields = {"quota_bytes", "upload_bandwidth_kb", "download_bandwidth_kb"};
        String[] originals = {"5242880", "100", "100"};
        for (int i = 0; i < fields.length; i++) {
            String prefix = "\"" + fields[i] + "\":";
            success(VALID.replace(prefix + originals[i], prefix + Long.MAX_VALUE));
            for (String invalid : new String[] {"-1", "\"1\"", "false", "null", "1.5", "1e2", "9223372036854775808"}) {
                failResponse(200, VALID.replace(prefix + originals[i], prefix + invalid), FailureKind.INVALID_RESPONSE, true);
            }
        }
    }

    private static void quotaMbAllowsServerFloat() throws Exception {
        for (String valid : new String[] {"0", "0.0", "5.0", "5e0", "5.25", "2.5E+1", "9.223372036854776E18"}) {
            String json = VALID.replace("\"quota_mb\":5", "\"quota_mb\":" + valid);
            Account account = success(json);
            check(account.quotaMb == Double.parseDouble(valid), "finite nonnegative quota_mb number accepted");
        }
        for (String invalid : new String[] {"-1", "-0.1", "-1e-9999", "\"5.0\"", "true", "null", "NaN",
                "Infinity", "-Infinity", "1e309", "{}", "[]"}) {
            failResponse(200, VALID.replace("\"quota_mb\":5", "\"quota_mb\":" + invalid),
                    FailureKind.INVALID_RESPONSE, true);
        }
    }

    private static void serverAccountInfoInterop(String fixturePath) throws Exception {
        java.util.List<String> fixtures = Files.readAllLines(Path.of(fixturePath), StandardCharsets.UTF_8);
        check(fixtures.size() == 4, "Python generated normal, zero, and fractional quota fixtures");
        check(fixtures.get(0).contains("\"quota_mb\": 5.0"), "exact Python json.dumps 5.0 response covered");
        for (String json : fixtures) {
            JsonObject data = (JsonObject) ((JsonObject) Json.Default.parseToJsonElement(json)).get("data");
            long quota = Long.parseLong(((JsonPrimitive) data.get("quota_bytes")).getContent());
            long upload = Long.parseLong(((JsonPrimitive) data.get("upload_bandwidth_kb")).getContent());
            long download = Long.parseLong(((JsonPrimitive) data.get("download_bandwidth_kb")).getContent());
            for (boolean registration : new boolean[] {true, false}) {
                OfficialServiceRegistrationClient client = new OfficialServiceRegistrationClient(request -> response(request, 200, json));
                Account account = registration ? client.register(USER, PASSWORD) : client.login(USER, PASSWORD);
                check(account.quotaMb == quota / (1024.0 * 1024), "actual Python account_info quota_mb accepted");
                check(account.quotaBytes == quota && account.uploadBandwidthKb == upload
                        && account.downloadBandwidthKb == download, "actual server integer metadata retained");
                check(PASSWORD.equals(account.password), "server fixture contains no password; submitted password retained");
            }
        }
    }

    private static void endpointAndCredentialPolicy() throws Exception {
        for (String endpoint : new String[] {"http://webdav.xyz1001.top:8443/dav/",
                "https://other.invalid:8443/dav/", "https://webdav.xyz1001.top.evil.invalid:8443/dav/",
                "https://webdav.xyz1001.top/dav/", "https://webdav.xyz1001.top:443/dav/",
                "https://user@webdav.xyz1001.top:8443/dav/", DAV_URL + "?query=value", DAV_URL + "#part",
                "https://webdav.xyz1001.top:8443/dav", "https://webdav.xyz1001.top:8443/other/",
                "https://webdav.xyz1001.top:8443/dav/../dav/", "https://webdav.xyz1001.top:8443/%64av/",
                "https://webdav.xyz1001.top.:8443/dav/", " " + DAV_URL, "//webdav.xyz1001.top:8443/dav/"}) {
            failResponse(200, VALID.replace(DAV_URL, endpoint), FailureKind.INVALID_RESPONSE, true);
        }
        for (String username : new String[] {"", "bad:name", "bad\\nname", "bad\\u0000name", "x".repeat(1025)}) {
            failResponse(200, VALID.replace(USER, username), FailureKind.INVALID_RESPONSE, true);
        }
    }

    private static void bodyLimitsAndClosure() throws Exception {
        TrackingBody valid = new TrackingBody(VALID.getBytes(StandardCharsets.UTF_8), -1);
        new OfficialServiceRegistrationClient(request -> response(request, 200, valid)).register(USER, PASSWORD);
        check(valid.closed, "success body closed");
        TrackingBody invalid = new TrackingBody("{".getBytes(StandardCharsets.UTF_8), -1);
        expectFailure(() -> new OfficialServiceRegistrationClient(request -> response(request, 200, invalid)).register(USER, PASSWORD),
                FailureKind.INVALID_RESPONSE, true);
        check(invalid.closed, "invalid body closed");
        TrackingBody declared = new TrackingBody(new byte[1], MAX_RESPONSE_BYTES + 1);
        expectFailure(() -> new OfficialServiceRegistrationClient(request -> response(request, 200, declared)).register(USER, PASSWORD),
                FailureKind.RESPONSE_TOO_LARGE, true);
        check(declared.bytesRead == 0 && declared.closed, "declared oversize rejected before read and closed");
        TrackingBody chunked = new TrackingBody(new byte[MAX_RESPONSE_BYTES * 3], -1);
        expectFailure(() -> new OfficialServiceRegistrationClient(request -> response(request, 200, chunked)).register(USER, PASSWORD),
                FailureKind.RESPONSE_TOO_LARGE, true);
        check(chunked.bytesRead <= MAX_RESPONSE_BYTES + 8192 && chunked.closed, "unknown length bounded and closed");
        TrackingBody falseLength = new TrackingBody(new byte[MAX_RESPONSE_BYTES * 2], 2);
        expectFailure(() -> new OfficialServiceRegistrationClient(request -> response(request, 200, falseLength)).register(USER, PASSWORD),
                FailureKind.RESPONSE_TOO_LARGE, true);
        check(falseLength.closed, "understated content length cannot bypass cap");
        TrackingBody error = new TrackingBody(new byte[MAX_RESPONSE_BYTES * 3], -1);
        expectFailure(() -> new OfficialServiceRegistrationClient(request -> response(request, 429, error)).register(USER, PASSWORD),
                FailureKind.RATE_LIMITED, false);
        check(error.bytesRead == 0 && error.closed, "error body ignored and closed");
        TrackingBody invalidUtf8 = new TrackingBody(new byte[] {(byte) 0xc3, (byte) 0x28}, -1);
        expectFailure(() -> new OfficialServiceRegistrationClient(request -> response(request, 200, invalidUtf8)).register(USER, PASSWORD),
                FailureKind.INVALID_RESPONSE, true);
        byte[] exactLimit = new byte[MAX_RESPONSE_BYTES];
        Arrays.fill(exactLimit, (byte) ' ');
        System.arraycopy(VALID.getBytes(StandardCharsets.UTF_8), 0, exactLimit, 0, VALID.length());
        new OfficialServiceRegistrationClient(request -> response(request, 200, new TrackingBody(exactLimit, -1))).register(USER, PASSWORD);
        check(true, "valid JSON exactly at size limit");
    }

    private static void unknownOutcomesAndRedaction() throws Exception {
        for (IOException problem : new IOException[] {new SocketTimeoutException(PASSWORD),
                new IOException(PASSWORD), new SSLHandshakeException(PASSWORD)}) {
            FailureKind kind = problem instanceof SocketTimeoutException ? FailureKind.TIMEOUT
                    : problem instanceof SSLHandshakeException ? FailureKind.TLS_ERROR : FailureKind.NETWORK_ERROR;
            for (boolean registration : new boolean[] {true, false}) {
                AtomicInteger calls = new AtomicInteger();
                OfficialServiceRegistrationClient client = new OfficialServiceRegistrationClient(request -> {
                    calls.incrementAndGet();
                    throw problem;
                });
                expectFailure(() -> {
                    if (registration) client.register(USER, PASSWORD);
                    else client.login(USER, PASSWORD);
                }, kind, registration);
                check(calls.get() == 1, "network failure never retried or changed action");
            }
        }
        expectFailure(() -> new OfficialServiceRegistrationClient(request -> { throw new IllegalStateException(PASSWORD); })
                .register(USER, PASSWORD), FailureKind.INVALID_RESPONSE, true);
        expectFailure(() -> new OfficialServiceRegistrationClient(request -> null).register(USER, PASSWORD), FailureKind.INVALID_RESPONSE, true);
        ResponseBody unsafeClose = new TrackingBody("{".getBytes(StandardCharsets.UTF_8), -1) {
            @Override public void close() { throw new IllegalStateException(PASSWORD); }
        };
        expectFailure(() -> new OfficialServiceRegistrationClient(request -> response(request, 200, unsafeClose)).register(USER, PASSWORD),
                FailureKind.INVALID_RESPONSE, true);
    }

    private static Account success(String json) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Account result = new OfficialServiceRegistrationClient(request -> {
            calls.incrementAndGet();
            return response(request, 200, json);
        }).register(USER, PASSWORD);
        check(calls.get() == 1, "success single attempt");
        return result;
    }

    private static void failResponse(int status, String text, FailureKind kind, boolean unknown) throws Exception {
        for (boolean registration : new boolean[] {true, false}) {
            AtomicInteger calls = new AtomicInteger();
            OfficialServiceRegistrationClient client = new OfficialServiceRegistrationClient(request -> {
                calls.incrementAndGet();
                return response(request, status, text);
            });
            expectFailure(() -> {
                if (registration) client.register(USER, PASSWORD);
                else client.login(USER, PASSWORD);
            }, kind, registration && unknown);
            check(calls.get() == 1, "failed response single attempt; no v1 fallback");
        }
    }

    private interface Attempt { void run() throws Exception; }

    private static void expectFailure(Attempt attempt, FailureKind expected, boolean unknown) throws Exception {
        try {
            attempt.run();
            throw new AssertionError("Expected " + expected);
        } catch (RegistrationException actual) {
            check(actual.kind == expected, "failure category: " + expected);
            check(actual.accountMayHaveBeenCreated == unknown, "creation outcome uncertainty");
            check(actual.userMessage().contains("相同账号和密码登录") == unknown, "uncertain registration directs same-credential login");
            check(actual.getCause() == null && actual.getSuppressed().length == 0, "no raw exception chain");
            check(!actual.toString().contains(PASSWORD) && !actual.toString().contains(USER), "redacted failure");
            check(actual.userMessage().equals(actual.getMessage()) && actual.userMessage().length() < 50,
                    "short safe user-facing message");
        }
    }

    private static Response response(Request request, int status, String text) {
        return response(request, status, ResponseBody.create(text, MediaType.get("application/json")));
    }

    private static Response response(Request request, int status, ResponseBody body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("synthetic").body(body).build();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static class TrackingBody extends ResponseBody {
        final long length;
        final BufferedSource source;
        int bytesRead;
        boolean closed;

        TrackingBody(byte[] bytes, long length) {
            this.length = length;
            Buffer buffer = new Buffer().write(bytes);
            source = Okio.buffer(new Source() {
                @Override public long read(Buffer sink, long count) {
                    long read = buffer.read(sink, count);
                    if (read > 0) bytesRead += read;
                    return read;
                }
                @Override public Timeout timeout() { return Timeout.NONE; }
                @Override public void close() { closed = true; }
            });
        }

        @Override public MediaType contentType() { return MediaType.get("application/json"); }
        @Override public long contentLength() { return length; }
        @Override public BufferedSource source() { return source; }
    }
}
