package io.github.xyz1001.pdsync.android;

import pdsync.i18n.CoreSessionMessages;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.net.ssl.SSLException;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonNull;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import okhttp3.Authenticator;
import okhttp3.CookieJar;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import pdsync.AppendOnlyKt;

/**
 * Read-only official account metadata and public appreciation assets;
 * construction performs no network I/O. Invoke reads on a background thread. Diagnostics contain
 * closed categories only, never credentials or payloads. This client never registers accounts,
 * contacts DAV, creates orders, or uploads anything.
 * Entitlement.active gates all official-account DAV synchronization, including downloads.
 * Public appreciation reads never send credentials. A 401 alone does not prove expiry.
 */
final class ManagedAccountClient {
    static final String ORIGIN = "https://webdav.xyz1001.top:8443";
    static final String DAV_URL = ORIGIN + "/";
    static final String ENTITLEMENT_URL = ORIGIN + "/api/account/entitlement";
    static final String APPRECIATION_URL = ORIGIN + "/api/account/appreciation";
    static final String IMAGE_PATH = "/api/account/appreciation/image";
    static final String IMAGE_URL = ORIGIN + IMAGE_PATH;
    static final int MAX_JSON_BYTES = 32 * 1024;
    static final int MAX_IMAGE_BYTES = 1024 * 1024;
    static final int MAX_IMAGE_DIMENSION = 2048;
    static final long MAX_IMAGE_PIXELS = 2_000_000L;
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final String username;
    private final String authorization;
    private final Transport transport;

    interface Transport {
        Response execute(Request request) throws IOException;
        default void cancelPending() { }
    }

    enum FailureKind {
        INVALID_INPUT, AUTHENTICATION_REQUIRED, ACCOUNT_EXPIRED, ACCOUNT_INACTIVE, FORBIDDEN, NOT_AVAILABLE, RATE_LIMITED,
        SERVER_ERROR, REQUEST_REJECTED, REDIRECT_REJECTED, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, IMAGE_INTEGRITY_ERROR, TIMEOUT, TLS_ERROR, NETWORK_ERROR
    }

    static final class ManagedAccountException extends IOException {
        final FailureKind kind;
        private ManagedAccountException(FailureKind kind) {
            super(messageFor(kind));
            this.kind = kind;
            // Never preserve a transport message, response, cause, or suppressed diagnostic.
        }
        String userMessage() { return getMessage(); }
        pdsync.DiagnosticFailure diagnosticFailure() {
            switch (kind) {
                case NETWORK_ERROR: return pdsync.DiagnosticFailure.NETWORK_IO;
                case TIMEOUT: return pdsync.DiagnosticFailure.TIMEOUT;
                case TLS_ERROR: return pdsync.DiagnosticFailure.TLS;
                case AUTHENTICATION_REQUIRED: return pdsync.DiagnosticFailure.AUTHENTICATION;
                case ACCOUNT_EXPIRED: case ACCOUNT_INACTIVE: case FORBIDDEN: return pdsync.DiagnosticFailure.AUTHORIZATION;
                case SERVER_ERROR: case RATE_LIMITED: case REQUEST_REJECTED: case REDIRECT_REJECTED: return pdsync.DiagnosticFailure.HTTP;
                case NOT_AVAILABLE: return pdsync.DiagnosticFailure.UNAVAILABLE;
                default: return pdsync.DiagnosticFailure.VALIDATION;
            }
        }
    }

    static final class Entitlement {
        final String source;
        final String username;
        /** Raw expires_ms received from the existing official Basic entitlement endpoint. */
        final Long serverExpiresMs;
        /** True service deadline, normalized once to serverExpiresMs minus one UTC calendar year. */
        final Long expiresMs;
        final Long serverTimeMs;
        final boolean active;
        final boolean enabled;
        /** The original ledger may return null/active=false when its expiry is unconfirmed. */
        final boolean hasConfirmedExpiry;
        final boolean managed;
        private Entitlement(String source, String username, Long serverExpiresMs, Long expiresMs,
                            boolean enabled, boolean hasConfirmedExpiry, boolean managed, Long serverTimeMs) {
            this.source = source;
            this.username = username;
            this.serverExpiresMs = serverExpiresMs;
            this.expiresMs = expiresMs;
            this.serverTimeMs = serverTimeMs;
            this.enabled = enabled;
            this.hasConfirmedExpiry = hasConfirmedExpiry;
            // Finite eligibility uses the adjusted date, never the old server active conclusion.
            this.active = enabled && hasConfirmedExpiry && serverTimeMs != null
                    && (expiresMs == null || serverTimeMs < expiresMs);
            this.managed = managed;
        }
        /** Metadata authentication is separate from permission to perform DAV I/O. */
        void requireSyncAccess() throws ManagedAccountException {
            if (!enabled) throw failure(FailureKind.ACCOUNT_INACTIVE);
            if (!hasConfirmedExpiry || serverTimeMs == null) throw failure(FailureKind.INVALID_RESPONSE);
            if (expiresMs != null && serverTimeMs >= expiresMs) throw failure(FailureKind.ACCOUNT_EXPIRED);
            if (!active) throw failure(FailureKind.ACCOUNT_INACTIVE);
        }
        @Override public String toString() { return "Official entitlement [private fields hidden]"; }
    }

    static final class Appreciation {
        final int version;
        final String paymentMode;
        final String imagePath;
        final String imageSha256;
        final String term;
        /** Literal rule "username"; the UI uses the authenticated account name as the remark. */
        final String remark;
        private Appreciation(String imageSha256) {
            this.version = 1;
            this.paymentMode = "donation";
            this.imagePath = IMAGE_PATH;
            this.imageSha256 = imageSha256;
            this.term = "calendar_year";
            this.remark = "username";
        }
        @Override public String toString() { return "Official appreciation metadata"; }
    }

    ManagedAccountClient(String endpoint, String username, String password) throws ManagedAccountException {
        this(endpoint, username, password, defaultTransport());
    }

    ManagedAccountClient(String endpoint, String username, String password, Transport transport)
            throws ManagedAccountException {
        if (!DAV_URL.equals(endpoint) || username == null || password == null
                || !username.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")
                || password.isEmpty() || password.length() > 512 || hasControl(password)
                || unpairedSurrogate(password) || password.getBytes(StandardCharsets.UTF_8).length > 512) {
            recordEntitlement(pdsync.DiagnosticResult.STARTED, pdsync.DiagnosticFailure.NONE);
            recordEntitlement(pdsync.DiagnosticResult.FAILED, pdsync.DiagnosticFailure.VALIDATION);
            throw failure(FailureKind.INVALID_INPUT);
        }
        this.username = username;
        this.authorization = Credentials.basic(username, password, StandardCharsets.UTF_8);
        this.transport = Objects.requireNonNull(transport, "Transport required");
    }

    static ManagedAccountClient publicAppreciation() { return publicAppreciation(defaultTransport()); }

    static ManagedAccountClient publicAppreciation(Transport transport) {
        return new ManagedAccountClient(transport);
    }

    private ManagedAccountClient(Transport transport) {
        this.username = null;
        this.authorization = null;
        this.transport = Objects.requireNonNull(transport, "Transport required");
    }

    boolean canReadEntitlement() { return authorization != null; }

    /** No inherited DAV client, credentials, cookie jar, cache, logging or TLS overrides. */
    static OkHttpClient newHttpClient() {
        return new OkHttpClient.Builder()
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES).cache(null)
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
                // GET has no one-shot body. OkHttp may otherwise replay e.g. 503 Retry-After: 0
                // even with connection retries disabled. Refuse a second network exchange.
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                        .tag(SingleAttempt.class, new SingleAttempt()).build()))
                .addNetworkInterceptor(chain -> {
                    SingleAttempt attempt = chain.request().tag(SingleAttempt.class);
                    if (attempt == null || !attempt.used.compareAndSet(false, true)) {
                        throw new IOException("Official request replay rejected");
                    }
                    return chain.proceed(chain.request());
                }).build();
    }

    private static final class SingleAttempt { final AtomicBoolean used = new AtomicBoolean(); }

    private static Transport defaultTransport() {
        return httpTransport(newHttpClient());
    }

    private static final java.util.concurrent.atomic.AtomicLong transportIds = new java.util.concurrent.atomic.AtomicLong();
    /** Package-private injection seam uses the same production Call/diagnostic path in host tests. */
    static Transport httpTransport(OkHttpClient client) {
        final long id = transportIds.incrementAndGet();
        return new Transport() {
            @Override public Response execute(Request request) throws IOException {
                okhttp3.Call call = client.newCall(request);
                long started = System.nanoTime();
                try {
                    Response response = call.execute();
                    diagnostic(() -> pdsync.DiagnosticLog.http(pdsync.DiagnosticHttpMethod.GET, response.code(),
                            transportDetails(request, id, started, null, call.isCanceled())));
                    ResponseBody body = response.body();
                    if (body == null) return response;
                    // execute() completes at headers. Preserve safe evidence for later body I/O too.
                    return response.newBuilder().body(new ResponseBody() {
                        private okio.BufferedSource source;
                        private final AtomicBoolean recorded = new AtomicBoolean();
                        @Override public MediaType contentType() { return body.contentType(); }
                        @Override public long contentLength() { return body.contentLength(); }
                        @Override public okio.BufferedSource source() {
                            if (source == null) source = okio.Okio.buffer(new okio.ForwardingSource(body.source()) {
                                @Override public long read(okio.Buffer sink, long byteCount) throws IOException {
                                    try { return super.read(sink, byteCount); }
                                    catch (IOException failure) {
                                        if (recorded.compareAndSet(false, true))
                                            recordTransportFailure(request, id, started, failure, call.isCanceled());
                                        throw failure;
                                    }
                                }
                            });
                            return source;
                        }
                    }).build();
                } catch (IOException failure) {
                    recordTransportFailure(request, id, started, failure, call.isCanceled());
                    throw failure; // execute() sanitizes before any exception leaves this client.
                }
            }
            @Override public void cancelPending() { client.dispatcher().cancelAll(); }
        };
    }

    private static void recordTransportFailure(Request request, long id, long started,
                                               IOException failure, boolean canceled) {
        // Capture the original interrupt state before the diagnostic-only disk write.
        pdsync.DiagnosticDetails details = transportDetails(request, id, started, failure, canceled);
        diagnostic(() -> pdsync.DiagnosticLog.networkFailure(failure, details));
    }

    private static void diagnostic(Runnable event) {
        // FileChannel rejects an interrupted thread. Restore the flag before any operation resumes.
        boolean interrupted = Thread.interrupted();
        try { event.run(); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static pdsync.DiagnosticDetails transportDetails(Request request, long id, long started,
                                                              IOException failure, boolean canceled) {
        pdsync.DiagnosticDetails.Builder details = pdsync.DiagnosticDetails.builder()
                .networkTarget(ENTITLEMENT_URL.equals(request.url().toString())
                        ? pdsync.DiagnosticDetails.NetworkTarget.ENTITLEMENT : pdsync.DiagnosticDetails.NetworkTarget.ASSET)
                .number(pdsync.DiagnosticDetails.Field.TRANSPORT_CLIENT_ID, id)
                .requestDurationMs(Math.max(0, (System.nanoTime() - started) / 1000000));
        if (failure != null) details.transportFailure(failure, canceled, Thread.currentThread().isInterrupted());
        return details.build();
    }

    /** Cancellation can race completion; callers must also invalidate their UI generation. */
    void cancelPending() {
        try { transport.cancelPending(); }
        catch (RuntimeException ignored) { /* Do not expose private transport diagnostics. */ }
    }

    Entitlement entitlement() throws ManagedAccountException {
        recordEntitlement(pdsync.DiagnosticResult.STARTED, pdsync.DiagnosticFailure.NONE);
        try {
            Entitlement result = readEntitlement();
            recordEntitlement(pdsync.DiagnosticResult.SUCCEEDED, pdsync.DiagnosticFailure.NONE);
            return result;
        } catch (ManagedAccountException failure) {
            recordEntitlement(pdsync.DiagnosticResult.FAILED, failure.diagnosticFailure());
            throw failure;
        } catch (RuntimeException malformed) {
            recordEntitlement(pdsync.DiagnosticResult.FAILED, pdsync.DiagnosticFailure.VALIDATION);
            throw invalid();
        }
    }

    private static void recordEntitlement(pdsync.DiagnosticResult result, pdsync.DiagnosticFailure failure) {
        diagnostic(() -> pdsync.DiagnosticLog.record(pdsync.DiagnosticStage.CONNECTION, result, failure,
                pdsync.DiagnosticDetails.builder().networkTarget(pdsync.DiagnosticDetails.NetworkTarget.ENTITLEMENT).build()));
    }

    private Entitlement readEntitlement() throws ManagedAccountException {
        if (!canReadEntitlement()) throw failure(FailureKind.AUTHENTICATION_REQUIRED);
        return execute(request(ENTITLEMENT_URL, true, "application/json"), response -> {
            JsonObject data = data(response);
            String source = string(data, "source", 64);
            if (!"managed_ledger".equals(source)
                    || !username.equals(string(data, "username", 64))) throw invalid();
            Long raw = data.get("expires_ms") == JsonNull.INSTANCE ? null : integer(data, "expires_ms");
            Long serverTime = data.containsKey("server_time_ms") ? integer(data, "server_time_ms") : null;
            long status = data.containsKey("status") ? integer(data, "status") : 1;
            if (status != 0 && status != 1) throw invalid();
            boolean serverActive = bool(data, "active");
            // Original ledger null/false can mean "not confirmed", not an unlimited account.
            // Numeric zero is explicit unlimited; null/true retains the old unlimited contract.
            boolean hasConfirmedExpiry = raw != null || serverActive;
            // This is the only normalization boundary. Every display and access check consumes
            // expiresMs directly; zero/null remain unlimited. UTC calendar arithmetic preserves
            // the time and milliseconds and clamps February 29 to February 28 where necessary.
            Long expires = raw == null || raw == 0 ? null : Instant.ofEpochMilli(raw)
                    .atZone(ZoneOffset.UTC).minusYears(1).toInstant().toEpochMilli();
            return new Entitlement(source, username, raw, expires, status == 1, hasConfirmedExpiry,
                    bool(data, "managed"), serverTime);
        });
    }

    Appreciation appreciation() throws ManagedAccountException {
        return execute(request(APPRECIATION_URL, false, "application/json"), response -> {
            JsonObject data = data(response);
            if (integer(data, "version") != 1 || !"donation".equals(string(data, "payment_mode", 32))
                    || !IMAGE_PATH.equals(string(data, "image_path", 256))
                    || !"calendar_year".equals(string(data, "term", 32))
                    || !"username".equals(string(data, "remark", 32))) throw invalid();
            String hash = string(data, "image_sha256", 64);
            if (!hash.matches("[0-9a-f]{64}")) throw invalid();
            return new Appreciation(hash);
        });
    }

    /** Fixed same-origin public PNG. No Authorization, Cookie, Referer, QR parsing or DAV I/O. */
    byte[] paymentImage(Appreciation metadata) throws ManagedAccountException {
        if (metadata == null || !IMAGE_PATH.equals(metadata.imagePath)
                || !metadata.imageSha256.matches("[0-9a-f]{64}")) throw failure(FailureKind.INVALID_INPUT);
        return execute(request(IMAGE_URL, false, "image/png"), response -> {
            ResponseBody body = body(response, "image", "png");
            byte[] bytes = readBounded(body, MAX_IMAGE_BYTES);
            validatePng(bytes);
            final byte[] actual;
            try { actual = MessageDigest.getInstance("SHA-256").digest(bytes); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw invalid(); }
            byte[] expected = new byte[32];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (byte) Integer.parseInt(metadata.imageSha256.substring(i * 2, i * 2 + 2), 16);
            }
            if (!MessageDigest.isEqual(expected, actual)) throw failure(FailureKind.IMAGE_INTEGRITY_ERROR);
            return bytes;
        });
    }

    private Request request(String url, boolean authenticated, String accept) throws ManagedAccountException {
        // This guard remains even for constant paths: credentials can only leave for this origin.
        try {
            URI uri = new URI(url);
            if (!"https".equals(uri.getScheme()) || !"webdav.xyz1001.top".equals(uri.getHost())
                    || uri.getPort() != 8443 || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(ENTITLEMENT_URL.equals(url) || APPRECIATION_URL.equals(url) || IMAGE_URL.equals(url))
                    || (authenticated && !ENTITLEMENT_URL.equals(url))) throw failure(FailureKind.INVALID_INPUT);
        } catch (java.net.URISyntaxException invalid) { throw failure(FailureKind.INVALID_INPUT); }
        Request.Builder builder = new Request.Builder().url(url).get().header("Accept", accept)
                .header("Cache-Control", "no-store");
        if (authenticated) builder.header("Authorization", authorization);
        return builder.build();
    }

    private interface Decoder<T> { T decode(Response response) throws IOException; }

    private <T> T execute(Request request, Decoder<T> decoder) throws ManagedAccountException {
        try (Response response = transport.execute(request)) {
            if (response == null) throw invalid();
            if (!request.url().equals(response.request().url()) || response.priorResponse() != null) {
                throw failure(FailureKind.REDIRECT_REJECTED);
            }
            int status = response.code();
            if (status != 200) throw httpFailure(status);
            return decoder.decode(response);
        } catch (ManagedAccountException error) {
            throw failure(error.kind); // Closing may attach sensitive suppressed text; discard it.
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw invalid();
        } catch (InterruptedIOException timeout) {
            throw failure(FailureKind.TIMEOUT);
        } catch (SSLException tls) {
            throw failure(FailureKind.TLS_ERROR);
        } catch (IOException network) {
            throw failure(FailureKind.NETWORK_ERROR);
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static ManagedAccountException httpFailure(int status) {
        if (status >= 300 && status < 400) return failure(FailureKind.REDIRECT_REJECTED);
        if (status == 401) return failure(FailureKind.AUTHENTICATION_REQUIRED);
        if (status == 403) return failure(FailureKind.FORBIDDEN);
        if (status == 404) return failure(FailureKind.NOT_AVAILABLE);
        if (status == 408) return failure(FailureKind.TIMEOUT);
        if (status == 413) return failure(FailureKind.RESPONSE_TOO_LARGE);
        if (status == 429) return failure(FailureKind.RATE_LIMITED);
        if (status >= 500 && status <= 599) return failure(FailureKind.SERVER_ERROR);
        if (status >= 400 && status <= 499) return failure(FailureKind.REQUEST_REJECTED);
        return invalid();
    }

    private static ResponseBody body(Response response, String type, String subtype) throws ManagedAccountException {
        ResponseBody body = response.body();
        if (body == null) throw invalid();
        MediaType media = body.contentType();
        if (media == null || !type.equals(media.type()) || !subtype.equals(media.subtype())) throw invalid();
        if ("json".equals(subtype) && media.parameter("charset") != null
                && !"utf-8".equalsIgnoreCase(media.parameter("charset"))) throw invalid();
        return body;
    }

    private static byte[] readBounded(ResponseBody body, int limit) throws IOException {
        if (body.contentLength() > limit) throw failure(FailureKind.RESPONSE_TOO_LARGE);
        byte[] buffer = new byte[limit + 1];
        try {
            InputStream stream = body.byteStream();
            int used = 0;
            while (true) {
                int count = stream.read(buffer, used, buffer.length - used);
                if (count == -1) break;
                if (count == 0) throw invalid();
                used += count;
                if (used > limit) throw failure(FailureKind.RESPONSE_TOO_LARGE);
            }
            if (used == 0) throw invalid();
            return Arrays.copyOf(buffer, used);
        } finally { Arrays.fill(buffer, (byte) 0); }
    }

    private static JsonObject data(Response response) throws IOException {
        byte[] bytes = readBounded(body(response, "application", "json"), MAX_JSON_BYTES);
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } finally { Arrays.fill(bytes, (byte) 0); }
        checkJsonLexemes(text);
        AppendOnlyKt.requireUniqueJsonKeys(text);
        JsonObject root = object(Json.Default.parseToJsonElement(text));
        validateValues(root);
        if (root.containsKey("sandbox") || integer(root, "code") != 200) throw invalid();
        JsonObject data = object(root.get("data"));
        if (data.containsKey("sandbox")) throw invalid();
        return data;
    }

    /** Validate tokens before permissive tree parsing, and cap recursion before both parsers. */
    private static void checkJsonLexemes(String text) throws ManagedAccountException {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c < 0x20) throw invalid();
                if (c == '"') quoted = false;
                else if (c == '\\') {
                    if (++i == text.length()) throw invalid();
                    c = text.charAt(i);
                    if (c == 'u') {
                        for (int j = 0; j < 4; j++) {
                            if (++i == text.length() || text.charAt(i) > 127
                                    || Character.digit(text.charAt(i), 16) < 0) throw invalid();
                        }
                    } else if ("\"\\/bfnrt".indexOf(c) < 0) throw invalid();
                }
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 8) throw invalid(); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw invalid(); }
            else if ((c < 0x20 && c != '\t' && c != '\r' && c != '\n') || c > 127) throw invalid();
        }
        if (quoted || depth != 0) throw invalid();
    }

    private static void validateValues(JsonElement value) throws ManagedAccountException {
        if (value instanceof JsonObject) {
            for (Map.Entry<String, JsonElement> field : ((JsonObject) value).entrySet()) {
                if (unpairedSurrogate(field.getKey())) throw invalid();
                validateValues(field.getValue());
            }
        } else if (value instanceof JsonArray) {
            for (JsonElement child : (JsonArray) value) validateValues(child);
        } else if (value instanceof JsonPrimitive) {
            JsonPrimitive primitive = (JsonPrimitive) value;
            String content = primitive.getContent();
            if (primitive.isString()) {
                if (unpairedSurrogate(content)) throw invalid();
            } else if (!"null".equals(content) && !"true".equals(content) && !"false".equals(content)
                    && !JSON_NUMBER.matcher(content).matches()) throw invalid();
        } else throw invalid();
    }

    private static JsonObject object(JsonElement value) throws ManagedAccountException {
        if (!(value instanceof JsonObject)) throw invalid();
        return (JsonObject) value;
    }
    private static String string(JsonObject data, String key, int limit) throws ManagedAccountException {
        JsonElement value = data.get(key);
        if (!(value instanceof JsonPrimitive) || !((JsonPrimitive) value).isString()) throw invalid();
        String content = ((JsonPrimitive) value).getContent();
        if (content.isEmpty() || content.length() > limit || hasControl(content) || unpairedSurrogate(content)) throw invalid();
        return content;
    }
    private static long integer(JsonObject data, String key) throws ManagedAccountException {
        JsonElement value = data.get(key);
        if (!(value instanceof JsonPrimitive) || ((JsonPrimitive) value).isString()) throw invalid();
        String content = ((JsonPrimitive) value).getContent();
        if (!content.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(content); }
        catch (NumberFormatException overflow) { throw invalid(); }
    }
    private static boolean bool(JsonObject data, String key) throws ManagedAccountException {
        JsonElement value = data.get(key);
        if (!(value instanceof JsonPrimitive) || ((JsonPrimitive) value).isString()) throw invalid();
        String content = ((JsonPrimitive) value).getContent();
        if ("true".equals(content)) return true;
        if ("false".equals(content)) return false;
        throw invalid();
    }

    /** Bounded PNG container validation; does not interpret payment content or decode QR data. */
    private static void validatePng(byte[] bytes) throws ManagedAccountException {
        if (bytes.length < 57) throw invalid();
        for (int i = 0; i < PNG_SIGNATURE.length; i++) if (bytes[i] != PNG_SIGNATURE[i]) throw invalid();
        int offset = 8;
        boolean header = false, data = false, end = false, palette = false;
        int width = 0, height = 0, bitDepth = 0, color = 0, interlace = 0;
        String previous = null;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        while (offset < bytes.length) {
            if (bytes.length - offset < 12) throw invalid();
            long length = uint(bytes, offset);
            if (length > bytes.length - offset - 12L) throw invalid();
            int size = (int) length;
            for (int i = offset + 4; i < offset + 8; i++) {
                int c = bytes[i] & 255;
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) throw invalid();
            }
            if ((bytes[offset + 6] & 0x20) != 0) throw invalid(); // Reserved PNG type bit.
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(bytes, offset + 4, size + 4);
            if (crc.getValue() != uint(bytes, offset + 8 + size)) throw invalid();
            if (!header && !"IHDR".equals(type)) throw invalid();
            if ("IHDR".equals(type)) {
                if (header || size != 13) throw invalid();
                long rawWidth = uint(bytes, offset + 8), rawHeight = uint(bytes, offset + 12);
                if (rawWidth > MAX_IMAGE_DIMENSION || rawHeight > MAX_IMAGE_DIMENSION) throw invalid();
                width = (int) rawWidth; height = (int) rawHeight;
                if (width == 0 || height == 0 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION
                        || (long) width * height > MAX_IMAGE_PIXELS) throw invalid();
                bitDepth = bytes[offset + 16] & 255; color = bytes[offset + 17] & 255;
                interlace = bytes[offset + 20] & 255;
                boolean validDepth = color == 0 ? (bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16)
                        : color == 3 ? (bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8)
                        : (color == 2 || color == 4 || color == 6) && (bitDepth == 8 || bitDepth == 16);
                if (!validDepth || bytes[offset + 18] != 0 || bytes[offset + 19] != 0
                        || (bytes[offset + 20] != 0 && bytes[offset + 20] != 1)) throw invalid();
                header = true;
            } else if ("PLTE".equals(type)) {
                if (palette || data || color == 0 || color == 4 || size == 0 || size % 3 != 0 || size > 768
                        || (color == 3 && size / 3 > (1 << bitDepth))) throw invalid();
                palette = true;
            } else if ("IDAT".equals(type)) {
                if ((data && !"IDAT".equals(previous)) || (color == 3 && !palette)) throw invalid();
                data = true;
                compressed.write(bytes, offset + 8, size);
            } else if ("IEND".equals(type)) {
                if (!data || size != 0 || offset + 12 != bytes.length) throw invalid();
                end = true;
            } else if ((bytes[offset + 4] & 0x20) == 0 || "acTL".equals(type)
                    || "fcTL".equals(type) || "fdAT".equals(type)) throw invalid();
            previous = type;
            offset += size + 12;
        }
        if (!header || !data || !end) throw invalid();
        validateScanlines(compressed.toByteArray(), width, height, bitDepth, color, interlace);
    }

    /** Verify bounded zlib scanlines a row at a time; never allocate a bitmap or interpret pixels. */
    private static void validateScanlines(byte[] compressed, int width, int height, int depth, int color, int interlace)
            throws ManagedAccountException {
        int samples = color == 2 ? 3 : color == 4 ? 2 : color == 6 ? 4 : 1;
        int[][] passes = interlace == 0 ? new int[][] {{0, 0, 1, 1}}
                : new int[][] {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4},
                              {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            for (int[] pass : passes) {
                int columns = Math.max(0, (width - pass[0] + pass[2] - 1) / pass[2]);
                int rows = Math.max(0, (height - pass[1] + pass[3] - 1) / pass[3]);
                if (columns == 0 || rows == 0) continue;
                byte[] row = new byte[1 + (columns * samples * depth + 7) / 8];
                for (int i = 0; i < rows; i++) {
                    int used = 0;
                    while (used < row.length) {
                        int count = inflater.inflate(row, used, row.length - used);
                        if (count == 0) throw invalid();
                        used += count;
                    }
                    if ((row[0] & 255) > 4) throw invalid();
                }
            }
            if (inflater.inflate(new byte[1]) != 0 || !inflater.finished() || inflater.getRemaining() != 0) throw invalid();
        } catch (DataFormatException malformed) { throw invalid(); }
        finally { inflater.end(); }
    }
    private static long uint(byte[] bytes, int at) {
        return ((long) (bytes[at] & 255) << 24) | ((long) (bytes[at + 1] & 255) << 16)
                | ((long) (bytes[at + 2] & 255) << 8) | (bytes[at + 3] & 255);
    }
    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
    private static boolean unpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) return true;
            } else if (Character.isLowSurrogate(c)) return true;
        }
        return false;
    }
    private static ManagedAccountException invalid() { return failure(FailureKind.INVALID_RESPONSE); }
    private static ManagedAccountException failure(FailureKind kind) { return new ManagedAccountException(kind); }
    private static String messageFor(FailureKind kind) {
        switch (kind) {
            case INVALID_INPUT: return CoreSessionMessages.INVALID_BUILT_IN_ACCOUNT_CONFIGURATION.text();
            case AUTHENTICATION_REQUIRED: return CoreSessionMessages.UNABLE_TO_SIGN_IN_CHECK_YOUR_ACCOUNT_OR_RENEWAL_STATUS.text();
            case ACCOUNT_EXPIRED: return CoreSessionMessages.SERVICE_EXPIRED.text();
            case ACCOUNT_INACTIVE: return CoreSessionMessages.SYNC_IS_UNAVAILABLE_CHECK_YOUR_ACCOUNT_OR_RENEWAL_STATUS.text();
            case FORBIDDEN: return CoreSessionMessages.THE_SERVICE_DID_NOT_ALLOW_ACCESS_TO_THIS_INFORMATION.text();
            case NOT_AVAILABLE: return CoreSessionMessages.THIS_SERVICE_IS_CURRENTLY_UNAVAILABLE.text();
            case RATE_LIMITED: return CoreSessionMessages.TOO_MANY_REQUESTS_REFRESH_LATER.text();
            case SERVER_ERROR: return CoreSessionMessages.THE_BUILT_IN_SERVICE_IS_TEMPORARILY_UNAVAILABLE.text();
            case REQUEST_REJECTED: return CoreSessionMessages.THE_SERVICE_REJECTED_THIS_QUERY.text();
            case REDIRECT_REJECTED: return CoreSessionMessages.THE_SERVICE_REDIRECT_WAS_REJECTED.text();
            case RESPONSE_TOO_LARGE: return CoreSessionMessages.THE_SERVICE_RESPONSE_IS_TOO_LARGE.text();
            case IMAGE_INTEGRITY_ERROR: return CoreSessionMessages.THE_SUPPORT_IMAGE_COULD_NOT_BE_VERIFIED.text();
            case TIMEOUT: return CoreSessionMessages.THE_QUERY_TIMED_OUT_REFRESH_LATER.text();
            case TLS_ERROR: return CoreSessionMessages.UNABLE_TO_VERIFY_THE_SECURE_CONNECTION_TO_THE_SERVICE.text();
            case NETWORK_ERROR: return CoreSessionMessages.THE_NETWORK_CONNECTION_WAS_INTERRUPTED.text();
            default: return CoreSessionMessages.THE_SERVICE_RETURNED_AN_INVALID_RESPONSE.text();
        }
    }
    @Override public String toString() { return "Official account client [private fields hidden]"; }
}
