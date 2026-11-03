package io.github.xyz1001.pdsync.android;

import pdsync.i18n.CoreSessionMessages;

import pdsync.DiagnosticLog;
import pdsync.DiagnosticDetails;
import pdsync.DiagnosticHttpMethod;
import pdsync.DiagnosticFailure;
import pdsync.DiagnosticResult;
import pdsync.DiagnosticStage;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.net.URI;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonElementKt;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import okhttp3.Authenticator;
import okhttp3.CookieJar;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import pdsync.AppendOnlyKt;

/**
 * Explicit, single-attempt v2 login/registration. Construction does no I/O. Invoke either
 * operation on a background thread only after the user requests it. This client neither
 * persists credentials nor connects to WebDAV. It never falls back to the v1 empty request.
 *
 * A failed registration may already have succeeded. Never automatically retry. Recognized
 * trial-provisioning errors direct the user to retry registration with the same credentials;
 * generic unknown outcomes retain login guidance, which cannot repair trial provisioning.
 */
final class OfficialServiceRegistrationClient {
    static final String REGISTER_URL = "https://webdav.xyz1001.top/api/register";
    static final String DAV_URL = "https://webdav.xyz1001.top/";
    static final int MAX_RESPONSE_BYTES = 32 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private final Transport transport;

    /** Test seam: responses are consumed and closed by the explicit operation. */
    interface Transport {
        Response execute(Request request) throws IOException;
    }

    enum FailureKind {
        REGISTRATION_USERNAME_INVALID, LOGIN_USERNAME_INVALID, REGISTRATION_PASSWORD_LENGTH,
        LOGIN_PASSWORD_LENGTH, PASSWORD_CHARACTERS_INVALID, REGISTRATION_PASSWORD_PREFIX,
        PASSWORD_REJECTED, REQUEST_FORMAT_REJECTED, INVALID_CREDENTIALS, USERNAME_TAKEN,
        REGISTRATION_RETRY_REQUIRED, REQUEST_TOO_LARGE,
        UNSUPPORTED_MEDIA_TYPE, RATE_LIMITED, UPSTREAM_ERROR, SERVER_ERROR, REQUEST_REJECTED,
        REDIRECT_REJECTED, INVALID_RESPONSE, RESPONSE_TOO_LARGE,
        TIMEOUT, NETWORK_ERROR, TLS_ERROR
    }

    static final class RegistrationException extends IOException {
        final FailureKind kind;
        /** True means an account may exist; recovery guidance depends on the failure kind. */
        final boolean accountMayHaveBeenCreated;

        private RegistrationException(FailureKind kind, boolean accountMayHaveBeenCreated) {
            super(messageFor(kind, accountMayHaveBeenCreated));
            this.kind = kind;
            this.accountMayHaveBeenCreated = accountMayHaveBeenCreated;
            // Do not attach the original exception: it may contain credentials or response text.
        }

        String userMessage() { return getMessage(); }
    }

    static final class Account {
        final String endpoint;
        final String username;
        final String password;
        final double quotaMb;
        final long quotaBytes;
        final long uploadBandwidthKb;
        final long downloadBandwidthKb;

        private Account(String endpoint, String username, String password,
                        double quotaMb, long quotaBytes, long uploadBandwidthKb, long downloadBandwidthKb) {
            this.endpoint = endpoint;
            this.username = username;
            this.password = password;
            this.quotaMb = quotaMb;
            this.quotaBytes = quotaBytes;
            this.uploadBandwidthKb = uploadBandwidthKb;
            this.downloadBandwidthKb = downloadBandwidthKb;
        }

        @Override public String toString() {
            return "Official service account [private fields hidden]";
        }
    }

    OfficialServiceRegistrationClient() {
        OkHttpClient client = newHttpClient();
        this.transport = new Transport() {
            @Override public Response execute(Request request) throws IOException {
                return client.newCall(request).execute();
            }
        };
    }

    OfficialServiceRegistrationClient(Transport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    /** Separate from the DAV transport: no inherited credentials, logging, cookies or retries. */
    static OkHttpClient newHttpClient() {
        return new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                // Keep OkHttp's system trust manager and hostname verifier. No TLS bypass.
                .build();
    }

    Account register(String username, String password) throws RegistrationException {
        validateCredentials(username, password, true);
        return request("register", username, password);
    }

    Account login(String username, String password) throws RegistrationException {
        validateCredentials(username, password, false);
        return request("login", username, password);
    }

    private static void validateCredentials(String username, String password, boolean registration)
            throws RegistrationException {
        if (username == null || !username.matches(registration
                ? "[a-z0-9]{3,32}" : "[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")) {
            throw failure(registration ? FailureKind.REGISTRATION_USERNAME_INVALID
                    : FailureKind.LOGIN_USERNAME_INVALID, false);
        }
        if (password == null || password.isEmpty() || password.length() > 4096) {
            throw failure(registration ? FailureKind.REGISTRATION_PASSWORD_LENGTH
                    : FailureKind.LOGIN_PASSWORD_LENGTH, false);
        }
        if (hasControl(password) || hasUnpairedSurrogate(password)) {
            throw failure(FailureKind.PASSWORD_CHARACTERS_INVALID, false);
        }
        if (registration && (password.codePointCount(0, password.length()) < 6
                || password.codePointCount(0, password.length()) > 128)) {
            throw failure(FailureKind.REGISTRATION_PASSWORD_LENGTH, false);
        }
        if (registration && (password.startsWith("$") || password.startsWith("{"))) {
            throw failure(FailureKind.REGISTRATION_PASSWORD_PREFIX, false);
        }
        // Login permits legacy naming, short passwords and $/{ prefixes. Never trim,
        // lowercase or otherwise change a submitted credential to make it acceptable.
        if (!registration && password.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw failure(FailureKind.LOGIN_PASSWORD_LENGTH, false);
        }
    }

    private static DiagnosticDetails networkContext(long started) {
        return DiagnosticDetails.builder().networkTarget(DiagnosticDetails.NetworkTarget.OFFICIAL_AUTH)
                .requestDurationMs(Math.max(0, (System.nanoTime() - started) / 1_000_000)).build();
    }

    private Account request(String action, String username, String password) throws RegistrationException {
        final boolean registration = "register".equals(action);
        Map<String, JsonElement> fields = new LinkedHashMap<>();
        fields.put("version", JsonElementKt.JsonPrimitive((Number) Integer.valueOf(2)));
        fields.put("action", JsonElementKt.JsonPrimitive(action));
        fields.put("username", JsonElementKt.JsonPrimitive(username));
        fields.put("password", JsonElementKt.JsonPrimitive(password));
        final byte[] payload = new JsonObject(fields).toString().getBytes(StandardCharsets.UTF_8);
        Request request = new Request.Builder().url(REGISTER_URL)
                .header("Accept", "application/json")
                .post(new RequestBody() {
                    @Override public MediaType contentType() { return JSON; }
                    @Override public long contentLength() { return payload.length; }
                    @Override public void writeTo(BufferedSink sink) throws IOException { sink.write(payload); }
                    // Also prevent HTTP replay (e.g. 503 Retry-After: 0), not just connection retries.
                    @Override public boolean isOneShot() { return true; }
                    @Override public String toString() { return "Official service request [private fields hidden]"; }
                }).build();
        final long requestStarted = System.nanoTime();
        try (Response response = transport.execute(request)) {
            if (response == null) throw failure(FailureKind.INVALID_RESPONSE, true);
            int status = response.code();
            DiagnosticLog.http(DiagnosticHttpMethod.POST, status, networkContext(requestStarted));
            if (!REGISTER_URL.equals(response.request().url().toString())) {
                throw failure(FailureKind.REDIRECT_REJECTED, true);
            }
            if (status != 200) {
                if (status == 400) {
                    throw failure(inputFailure(response.body(), registration), false);
                }
                if (registration && isRecoverableTrialFailure(status, response.body())) {
                    throw failure(FailureKind.REGISTRATION_RETRY_REQUIRED, true);
                }
                throw httpFailure(status);
            }
            ResponseBody body = response.body();
            if (body == null) throw failure(FailureKind.INVALID_RESPONSE, true);
            return parseAccount(readBoundedUtf8(body), username, password);
        } catch (RegistrationException failure) {
            DiagnosticLog.record(DiagnosticStage.CONNECTION, DiagnosticResult.FAILED, diagnosticFailure(failure.kind));
            // Closing may add suppressed transport text. Rebuild a safe exception, and never
            // suggest that a login (which cannot create an account) created one.
            throw failure(failure.kind, registration && failure.accountMayHaveBeenCreated);
        } catch (CharacterCodingException invalidUtf8) {
            throw failure(FailureKind.INVALID_RESPONSE, registration);
        } catch (InterruptedIOException timeout) {
            DiagnosticLog.networkFailure(timeout, networkContext(requestStarted));
            throw failure(FailureKind.TIMEOUT, registration);
        } catch (SSLException tls) {
            DiagnosticLog.networkFailure(tls, networkContext(requestStarted));
            throw failure(FailureKind.TLS_ERROR, registration);
        } catch (IOException network) {
            DiagnosticLog.networkFailure(network, networkContext(requestStarted));
            throw failure(FailureKind.NETWORK_ERROR, registration);
        } catch (RuntimeException invalid) {
            throw failure(FailureKind.INVALID_RESPONSE, registration);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private static DiagnosticFailure diagnosticFailure(FailureKind kind) {
        switch (kind) {
            case INVALID_CREDENTIALS: return DiagnosticFailure.AUTHENTICATION;
            case TIMEOUT: return DiagnosticFailure.TIMEOUT;
            case TLS_ERROR: return DiagnosticFailure.TLS;
            case NETWORK_ERROR: return DiagnosticFailure.NETWORK_IO;
            case RATE_LIMITED: case UPSTREAM_ERROR: case SERVER_ERROR: case REQUEST_REJECTED:
                return DiagnosticFailure.HTTP;
            default: return DiagnosticFailure.VALIDATION;
        }
    }

    private static RegistrationException httpFailure(int status) {
        if (status == 401) return failure(FailureKind.INVALID_CREDENTIALS, false);
        if (status == 409) return failure(FailureKind.USERNAME_TAKEN, false);
        if (status == 413) return failure(FailureKind.REQUEST_TOO_LARGE, false);
        if (status == 415) return failure(FailureKind.UNSUPPORTED_MEDIA_TYPE, false);
        if (status == 429) return failure(FailureKind.RATE_LIMITED, false);
        if (status == 502) return failure(FailureKind.UPSTREAM_ERROR, true);
        if (status >= 300 && status < 400) return failure(FailureKind.REDIRECT_REJECTED, true);
        if (status >= 500) return failure(FailureKind.SERVER_ERROR, true);
        if (status == 408) return failure(FailureKind.TIMEOUT, true);
        if (status >= 400 && status < 500) return failure(FailureKind.REQUEST_REJECTED, false);
        return failure(FailureKind.INVALID_RESPONSE, true);
    }

    /** Only validated public error codes identify a field; never echo server text. */
    private static FailureKind inputFailure(ResponseBody body, boolean registration) {
        String error = publicError(400, body);
        if ("INVALID_USERNAME".equals(error)) return registration
                ? FailureKind.REGISTRATION_USERNAME_INVALID : FailureKind.LOGIN_USERNAME_INVALID;
        if ("INVALID_PASSWORD".equals(error)) return FailureKind.PASSWORD_REJECTED;
        if ("INVALID_REQUEST".equals(error)) return FailureKind.REQUEST_FORMAT_REJECTED;
        // Proxy/protocol/upstream 400 responses do not prove that credentials are malformed.
        return FailureKind.REQUEST_REJECTED;
    }

    private static String publicError(int status, ResponseBody body) {
        if (body == null) return null;
        try {
            String text = readBoundedUtf8(body);
            checkNesting(text);
            AppendOnlyKt.requireUniqueJsonKeys(text);
            JsonObject root = object(Json.Default.parseToJsonElement(text));
            validateJsonValues(root);
            if (integer(root, "code") != status) return null;
            string(root, "message", 1024, true); // Validate only; never display server text.
            return string(root, "error", 64, false);
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    /** Only the opt-in registration contract can change status-based recovery guidance. */
    private static boolean isRecoverableTrialFailure(int status, ResponseBody body) {
        if ((status != 409 && status != 503) || body == null) return false;
        String error = publicError(status, body);
        if (status == 409) return "REGISTRATION_IN_PROGRESS".equals(error);
        return "REGISTRATION_TRIAL_RETRY_REQUIRED".equals(error)
                || "REGISTRATION_DATABASE_UNAVAILABLE".equals(error)
                || "REGISTRATION_UPSTREAM_UNAVAILABLE".equals(error);
    }

    private static String readBoundedUtf8(ResponseBody body) throws IOException {
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw failure(FailureKind.RESPONSE_TOO_LARGE, true);
        }
        byte[] bytes = new byte[MAX_RESPONSE_BYTES + 1];
        try {
            InputStream input = body.byteStream();
            int used = 0;
            while (true) {
                int count = input.read(bytes, used, bytes.length - used);
                if (count == -1) break;
                used += count;
                if (used > MAX_RESPONSE_BYTES) throw failure(FailureKind.RESPONSE_TOO_LARGE, true);
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, used)).toString();
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static Account parseAccount(String text, String submittedUsername, String submittedPassword)
            throws RegistrationException {
        checkNesting(text);
        AppendOnlyKt.requireUniqueJsonKeys(text);
        JsonObject root = object(Json.Default.parseToJsonElement(text));
        validateJsonValues(root);
        long code = integer(root, "code");
        string(root, "message", 1024, true); // Required type, but never displayed or logged.
        if (code != 200) throw failure(FailureKind.INVALID_RESPONSE, true);
        JsonObject data = object(root.get("data"));
        String endpoint = string(data, "webdav_url", 4096, false);
        String username = string(data, "username", 1024, false);
        // The v2 response must never supply a DAV password or substitute a token.
        if (data.containsKey("password") || data.containsKey("token") || !username.equals(submittedUsername)) {
            throw failure(FailureKind.INVALID_RESPONSE, true);
        }
        double quotaMb = nonnegativeNumber(data, "quota_mb");
        long quotaBytes = integer(data, "quota_bytes");
        long uploadBandwidth = integer(data, "upload_bandwidth_kb");
        long downloadBandwidth = integer(data, "download_bandwidth_kb");
        validateEndpoint(endpoint);
        if (username.indexOf(':') >= 0 || hasControl(username)) {
            throw failure(FailureKind.INVALID_RESPONSE, true);
        }
        // These are server metadata, not client-side limits. Zero/unlimited and a quota that
        // is not an exact MiB multiple remain valid. Python account_info emits quota_mb as
        // a float; the other three fields retain strict integer type/range validation.
        return new Account(endpoint, username, submittedPassword, quotaMb, quotaBytes, uploadBandwidth, downloadBandwidth);
    }

    private static void validateEndpoint(String endpoint) throws RegistrationException {
        try {
            URI uri = new URI(endpoint);
            if (!"https".equals(uri.getScheme()) || !"webdav.xyz1001.top".equals(uri.getHost())
                    || !(uri.getPort() == -1 || uri.getPort() == 443) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !"/".equals(uri.getRawPath()) || !DAV_URL.equals(endpoint)) {
                throw failure(FailureKind.INVALID_RESPONSE, true);
            }
        } catch (java.net.URISyntaxException invalid) {
            throw failure(FailureKind.INVALID_RESPONSE, true);
        }
    }

    private static JsonObject object(JsonElement element) throws RegistrationException {
        if (!(element instanceof JsonObject)) throw failure(FailureKind.INVALID_RESPONSE, true);
        return (JsonObject) element;
    }

    /** Json tree parsing retains raw primitive tokens, so validate even future/unknown fields. */
    private static void validateJsonValues(JsonElement element) throws RegistrationException {
        if (element instanceof JsonObject) {
            for (String key : ((JsonObject) element).keySet()) {
                if (hasUnpairedSurrogate(key)) throw failure(FailureKind.INVALID_RESPONSE, true);
            }
            for (JsonElement child : ((JsonObject) element).values()) validateJsonValues(child);
        } else if (element instanceof JsonArray) {
            for (JsonElement child : (JsonArray) element) validateJsonValues(child);
        } else if (element instanceof JsonPrimitive) {
            JsonPrimitive primitive = (JsonPrimitive) element;
            String value = primitive.getContent();
            if (primitive.isString()) {
                if (hasUnpairedSurrogate(value)) throw failure(FailureKind.INVALID_RESPONSE, true);
            } else if (!"null".equals(value) && !"true".equals(value) && !"false".equals(value)
                    && !JSON_NUMBER.matcher(value).matches()) {
                throw failure(FailureKind.INVALID_RESPONSE, true);
            }
        } else throw failure(FailureKind.INVALID_RESPONSE, true);
    }

    private static String string(JsonObject object, String key, int limit, boolean allowEmpty)
            throws RegistrationException {
        JsonElement element = object.get(key);
        if (!(element instanceof JsonPrimitive) || !((JsonPrimitive) element).isString()) {
            throw failure(FailureKind.INVALID_RESPONSE, true);
        }
        String value = ((JsonPrimitive) element).getContent();
        if ((!allowEmpty && value.isEmpty()) || value.length() > limit || hasUnpairedSurrogate(value)) {
            throw failure(FailureKind.INVALID_RESPONSE, true);
        }
        return value;
    }

    private static long integer(JsonObject object, String key) throws RegistrationException {
        JsonElement element = object.get(key);
        if (!(element instanceof JsonPrimitive) || ((JsonPrimitive) element).isString()) {
            throw failure(FailureKind.INVALID_RESPONSE, true);
        }
        String value = ((JsonPrimitive) element).getContent();
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw failure(FailureKind.INVALID_RESPONSE, true);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw failure(FailureKind.INVALID_RESPONSE, true);
        }
    }

    private static double nonnegativeNumber(JsonObject object, String key) throws RegistrationException {
        JsonElement element = object.get(key);
        if (!(element instanceof JsonPrimitive) || ((JsonPrimitive) element).isString()) {
            throw failure(FailureKind.INVALID_RESPONSE, true);
        }
        String text = ((JsonPrimitive) element).getContent();
        if (!JSON_NUMBER.matcher(text).matches()) throw failure(FailureKind.INVALID_RESPONSE, true);
        try {
            double value = Double.parseDouble(text);
            // Decimal sign validation also rejects a negative value that underflows to -0.0.
            if (!Double.isFinite(value) || new BigDecimal(text).signum() < 0) {
                throw failure(FailureKind.INVALID_RESPONSE, true);
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw failure(FailureKind.INVALID_RESPONSE, true);
        }
    }

    private static boolean hasControl(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) return true;
        }
        return false;
    }

    private static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) return true;
            } else if (Character.isLowSurrogate(current)) return true;
        }
        return false;
    }

    /** Bound parser recursion, including unrecognized extra fields, before building a JSON tree. */
    private static void checkNesting(String text) throws RegistrationException {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') inString = false;
            } else if (value == '"') inString = true;
            else if (value == '{' || value == '[') {
                if (++depth > 8) throw failure(FailureKind.INVALID_RESPONSE, true);
            } else if (value == '}' || value == ']') depth--;
        }
    }

    private static RegistrationException failure(FailureKind kind, boolean accountMayHaveBeenCreated) {
        return new RegistrationException(kind, accountMayHaveBeenCreated);
    }

    private static String messageFor(FailureKind kind, boolean accountMayHaveBeenCreated) {
        String message;
        switch (kind) {
            case REGISTRATION_USERNAME_INVALID:
                message = CoreSessionMessages.USE_3_32_LOWERCASE_LETTERS_OR_DIGITS_A_USERNAME_MAY.text(); break;
            case LOGIN_USERNAME_INVALID:
                message = CoreSessionMessages.USE_1_64_LETTERS_DIGITS_OR_DO_NOT_START_WITH.text(); break;
            case REGISTRATION_PASSWORD_LENGTH: message = CoreSessionMessages.REGISTRATION_PASSWORDS_MUST_CONTAIN_6_128_CHARACTERS.text(); break;
            case LOGIN_PASSWORD_LENGTH: message = CoreSessionMessages.THE_PASSWORD_CANNOT_BE_EMPTY_OR_EXCEED_512_BYTES.text(); break;
            case PASSWORD_CHARACTERS_INVALID: message = CoreSessionMessages.THE_PASSWORD_CANNOT_CONTAIN_CONTROL_OR_INVALID_CHARACTERS.text(); break;
            case REGISTRATION_PASSWORD_PREFIX: message = CoreSessionMessages.THE_PASSWORD_CANNOT_START_WITH_OR.text(); break;
            case PASSWORD_REJECTED: message = CoreSessionMessages.THE_SERVICE_REJECTED_THE_PASSWORD_FORMAT_CHECK_ITS_LENGTH_AND.text(); break;
            case REQUEST_FORMAT_REJECTED: message = CoreSessionMessages.THE_SERVICE_DOES_NOT_SUPPORT_THIS_REQUEST_FORMAT.text(); break;
            case INVALID_CREDENTIALS: message = CoreSessionMessages.UNABLE_TO_SIGN_IN_CHECK_YOUR_ACCOUNT_OR_RENEWAL_STATUS.text(); break;
            case USERNAME_TAKEN: message = CoreSessionMessages.THIS_ACCOUNT_IS_ALREADY_IN_USE_SIGN_IN_OR_CHOOSE.text(); break;
            case REGISTRATION_RETRY_REQUIRED:
                return CoreSessionMessages.REGISTRATION_IS_INCOMPLETE_RETRY_LATER_WITH_THE_SAME_USERNAME_AND.text();
            case REQUEST_TOO_LARGE: message = CoreSessionMessages.THE_SUBMITTED_INFORMATION_IS_TOO_LARGE.text(); break;
            case UNSUPPORTED_MEDIA_TYPE: message = CoreSessionMessages.THE_SERVICE_DOES_NOT_SUPPORT_THIS_REQUEST_FORMAT.text(); break;
            case RATE_LIMITED: message = CoreSessionMessages.TOO_MANY_REQUESTS_THE_SERVICE_HAS_TEMPORARILY_LIMITED_ACCESS.text(); break;
            case UPSTREAM_ERROR: message = CoreSessionMessages.THE_SERVICE_CANNOT_CONNECT_TO_STORAGE_RIGHT_NOW.text(); break;
            case SERVER_ERROR: message = CoreSessionMessages.THE_SERVICE_IS_TEMPORARILY_UNAVAILABLE.text(); break;
            case REQUEST_REJECTED: message = CoreSessionMessages.THE_SERVICE_REJECTED_THIS_REQUEST.text(); break;
            case REDIRECT_REJECTED: message = CoreSessionMessages.THE_SERVICE_ADDRESS_REDIRECTED.text(); break;
            case INVALID_RESPONSE: message = CoreSessionMessages.THE_SERVICE_RETURNED_AN_INVALID_RESPONSE.text(); break;
            case RESPONSE_TOO_LARGE: message = CoreSessionMessages.THE_SERVICE_RESPONSE_IS_TOO_LARGE.text(); break;
            case TIMEOUT: message = CoreSessionMessages.THE_REQUEST_TIMED_OUT.text(); break;
            case NETWORK_ERROR: message = CoreSessionMessages.THE_NETWORK_CONNECTION_WAS_INTERRUPTED.text(); break;
            case TLS_ERROR: message = CoreSessionMessages.UNABLE_TO_VERIFY_THE_SECURE_CONNECTION_TO_THE_SERVICE.text(); break;
            default: message = CoreSessionMessages.UNABLE_TO_COMPLETE_THIS_REQUEST.text(); break;
        }
        return message + (accountMayHaveBeenCreated ? CoreSessionMessages.REGISTRATION_COULD_NOT_BE_CONFIRMED_SIGN_IN_WITH_THE_SAME.text() : "");
    }
}
