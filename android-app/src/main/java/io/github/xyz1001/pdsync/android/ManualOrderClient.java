package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonElementKt;
import kotlinx.serialization.json.JsonNull;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;
import pdsync.AppendOnlyKt;

/**
 * Contract-only client for the explicitly configured local manual-order sandbox.
 * Construction does no I/O. There is deliberately no default endpoint, HTTP client,
 * authentication adapter, registration, administrator action, polling, or retry.
 * All successful models represent a sandbox ledger, never production subscription rights.
 */
public final class ManualOrderClient {
    public static final int MAX_JSON_BYTES = 256 * 1024;
    public static final int MAX_IMAGE_BYTES = 256 * 1024;
    private static final String IMAGE_PREFIX = "/api/order/payment-image/";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern BASE = Pattern.compile("http://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/?");
    private static final Pattern PLAN_ID = Pattern.compile("[a-z0-9_-]{1,48}");
    private static final Pattern ORDER_ID = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9_-]{16,80}");
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final Pattern JSON_TYPE = Pattern.compile("application/json(?:[ \\t]*;[ \\t]*charset=(?:utf-8|\"utf-8\"))?", Pattern.CASE_INSENSITIVE);
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private final String baseUrl;
    private final String host;
    private final Transport transport;

    /**
     * Explicit sandbox test seam. No implementation is supplied. An adapter must enforce
     * these response bounds while reading, never follow redirects/replay a request, and
     * use only its explicitly approved sandbox authentication. Do not inject an existing
     * account's DAV transport, passwords, logging interceptors, cookies, or real identity.
     */
    public interface Transport {
        Response execute(Request request) throws IOException;
        /** Best-effort cancellation; a future adapter must cancel its outstanding Call. */
        default void cancelPending() {}
    }

    /** Immutable bounded response snapshot; oversized bodies are discarded before copying. */
    public static final class Response {
        public final int status;
        public final String contentType;
        private final int bodyLength;
        private final byte[] body;

        public Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.bodyLength = body == null ? -1 : body.length;
            this.body = body == null || body.length > Math.max(MAX_JSON_BYTES, MAX_IMAGE_BYTES)
                    ? null : body.clone();
        }

        @Override public String toString() { return "Sandbox response [body hidden]"; }
    }

    public enum State { CREATED, REVIEW, APPLYING, APPROVED, CANCELLED, REJECTED }

    public enum FailureKind {
        INVALID_INPUT, AUTHENTICATION_REQUIRED, FORBIDDEN, REQUEST_REJECTED,
        CONFLICT, SERVICE_UNAVAILABLE, REDIRECT_REJECTED, INVALID_RESPONSE, UNSUPPORTED_CONTRACT,
        RESPONSE_TOO_LARGE, TIMEOUT, NETWORK_ERROR
    }

    /** Safe diagnostic: no response text, request data, original cause, or suppressed errors. */
    public static final class OrderException extends IOException {
        public final FailureKind kind;
        /** Zero when there is no validated HTTP failure response. */
        public final int httpStatus;
        /** Only a recognized API.md error code, otherwise null. Never raw response text. */
        public final String errorCode;
        /** A POST may have committed. Reconcile/reuse its original key; never replace it. */
        public final boolean outcomeUnknown;

        private OrderException(FailureKind kind, int httpStatus, String errorCode, boolean outcomeUnknown) {
            super(messageFor(kind, outcomeUnknown));
            this.kind = kind;
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
            this.outcomeUnknown = outcomeUnknown;
        }

        public String userMessage() { return getMessage(); }
    }

    public static final class Plan {
        public final String id, currency, term, displayName, paymentInstructions, paymentImagePath, expiryPolicy, paymentMode;
        /** Donation offers carry no quoted amount or currency. Null is never converted to zero. */
        public final Long amountMinor;
        public final long days, quotaBytes, uploadKbps, downloadKbps;

        private Plan(JsonObject value) throws OrderException {
            requireDonationContract(value, true);
            exact(value, "id", "currency", "amount_minor", "days", "quota_bytes", "upload_kbps",
                    "download_kbps", "term", "display_name", "payment_instructions", "payment_image_path", "expiry_policy", "payment_mode");
            id = matching(value, "id", PLAN_ID);
            currency = nullCurrency(value, "currency");
            amountMinor = nullAmount(value, "amount_minor");
            days = integer(value, "days");
            quotaBytes = integer(value, "quota_bytes");
            uploadKbps = integer(value, "upload_kbps");
            downloadKbps = integer(value, "download_kbps");
            term = term(value);
            displayName = string(value, "display_name");
            paymentInstructions = string(value, "payment_instructions");
            paymentImagePath = string(value, "payment_image_path");
            expiryPolicy = string(value, "expiry_policy");
            paymentMode = string(value, "payment_mode");
            if (!(IMAGE_PREFIX + id).equals(paymentImagePath)) {
                throw invalid();
            }
        }

        @Override public String toString() { return "Sandbox plan [fields hidden]"; }
    }

    public static final class Order {
        public final String id, planId, currency, term, remarkCode, actualCurrency;
        public final long days, quotaBytes, uploadKbps, downloadKbps, createdMs;
        /** Quoted amount remains null; actual amount is reviewer-confirmed only. */
        public final Long amountMinor, actualAmountMinor, targetMs;
        public final State state;

        private Order(JsonObject value) throws OrderException {
            requireDonationContract(value, false);
            exact(value, "id", "plan_id", "currency", "remark_code", "amount_minor", "days", "quota_bytes",
                    "upload_kbps", "download_kbps", "created_ms", "term", "state", "target_ms",
                    "actual_amount_minor", "actual_currency");
            id = matching(value, "id", ORDER_ID);
            planId = matching(value, "plan_id", PLAN_ID);
            currency = nullCurrency(value, "currency");
            remarkCode = matching(value, "remark_code", Pattern.compile("[0-9]{4}"));
            amountMinor = nullAmount(value, "amount_minor");
            days = integer(value, "days");
            quotaBytes = integer(value, "quota_bytes");
            uploadKbps = integer(value, "upload_kbps");
            downloadKbps = integer(value, "download_kbps");
            createdMs = integer(value, "created_ms");
            targetMs = nullableInteger(value, "target_ms");
            actualAmountMinor = nullableInteger(value, "actual_amount_minor");
            actualCurrency = value.get("actual_currency") == JsonNull.INSTANCE ? null
                    : matching(value, "actual_currency", Pattern.compile("[A-Z]{3}"));
            term = term(value);
            switch (string(value, "state")) {
                case "created": state = State.CREATED; break;
                case "review": state = State.REVIEW; break;
                case "applying": state = State.APPLYING; break;
                case "approved": state = State.APPROVED; break;
                case "cancelled": state = State.CANCELLED; break;
                case "rejected": state = State.REJECTED; break;
                default: throw invalid();
            }
            boolean approvalRecorded = state == State.APPLYING || state == State.APPROVED;
            if (approvalRecorded ? actualAmountMinor == null || actualAmountMinor <= 0 || actualCurrency == null
                    : actualAmountMinor != null || actualCurrency != null) throw invalid();
        }

        @Override public String toString() { return "Sandbox order [fields hidden]"; }
    }

    public static final class Entitlement {
        public final String source;
        public final Long expiresMs;
        public final boolean active;

        private Entitlement(JsonObject value) throws OrderException {
            exact(value, "source", "expires_ms", "active");
            source = string(value, "source");
            if (!"sandbox_ledger".equals(source)) throw invalid();
            expiresMs = nullableInteger(value, "expires_ms");
            active = bool(value, "active");
            // Preserve exactly what this local ledger reports; do not infer remote rights.
        }

        @Override public String toString() { return "Sandbox entitlement [fields hidden]"; }
    }

    /** Accept only an explicit literal IPv4-loopback base root, with an explicit port. */
    public ManualOrderClient(String baseUrl, Transport transport) {
        Matcher match = BASE.matcher(baseUrl == null ? "" : baseUrl);
        if (!match.matches() || Integer.parseInt(match.group(1)) > 65535 || transport == null) {
            throw new IllegalArgumentException("Explicit sandbox loopback base and transport required");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.host = "127.0.0.1:" + match.group(1);
        this.transport = transport;
    }

    /** Cancellation never waits, retries, logs, or exposes a transport exception.
     * Callers must also invalidate their generation: cancellation can race completion. */
    public void cancelPending() {
        try { transport.cancelPending(); }
        catch (RuntimeException ignored) { /* Keep cancellation diagnostics private. */ }
    }

    public List<Plan> plans() throws OrderException {
        return jsonRequest("/api/order/plans", null, data -> {
            List<Plan> plans = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonElement entry : array(data)) {
                Plan plan = new Plan(object(entry));
                if (!ids.add(plan.id)) throw invalid();
                plans.add(plan);
            }
            return Collections.unmodifiableList(plans);
        });
    }

    public List<Order> mine() throws OrderException {
        return jsonRequest("/api/order/mine", null, data -> {
            JsonArray rows = array(data);
            if (rows.size() > 100) throw invalid();
            List<Order> orders = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonElement entry : rows) {
                Order order = new Order(object(entry));
                if (!ids.add(order.id)) throw invalid();
                orders.add(order);
            }
            return Collections.unmodifiableList(orders);
        });
    }

    public Entitlement entitlement() throws OrderException {
        return jsonRequest("/api/order/entitlement", null, data -> new Entitlement(object(data)));
    }

    public Order status(String orderId) throws OrderException {
        input(orderId, ORDER_ID);
        return jsonRequest("/api/order/status/" + orderId, null, data -> sameOrder(data, orderId));
    }

    /** One attempt only. The caller owns persisting/reusing this exact request key. */
    public Order create(String planId, String requestKey) throws OrderException {
        input(planId, PLAN_ID);
        input(requestKey, REQUEST_KEY);
        Map<String, JsonElement> fields = new LinkedHashMap<>();
        fields.put("plan_id", JsonElementKt.JsonPrimitive(planId));
        fields.put("request_key", JsonElementKt.JsonPrimitive(requestKey));
        return jsonRequest("/api/order/create", fields, data -> {
            Order order = new Order(object(data));
            // An idempotent create may legitimately return an already-terminal order.
            if (!planId.equals(order.planId)) throw invalid();
            return order;
        });
    }

    public Order submit(String orderId) throws OrderException { return orderRequest("submit", orderId); }
    public Order cancel(String orderId) throws OrderException { return orderRequest("cancel", orderId); }

    public byte[] paymentImage(Plan plan) throws OrderException {
        if (plan == null || !(IMAGE_PREFIX + plan.id).equals(plan.paymentImagePath)) {
            throw failure(FailureKind.INVALID_INPUT);
        }
        Request request = requestBuilder(plan.paymentImagePath, "image/png").get().build();
        return execute(request, false, response -> {
            requireSuccess(response);
            bytesPresent(response, MAX_IMAGE_BYTES);
            if (!"image/png".equalsIgnoreCase(response.contentType) || response.body.length < PNG_SIGNATURE.length) {
                throw invalid();
            }
            for (int i = 0; i < PNG_SIGNATURE.length; i++) {
                if (response.body[i] != PNG_SIGNATURE[i]) throw invalid();
            }
            return response.body.clone();
        });
    }

    private Order orderRequest(String action, String orderId) throws OrderException {
        input(orderId, ORDER_ID);
        Map<String, JsonElement> fields = Collections.singletonMap("order_id", JsonElementKt.JsonPrimitive(orderId));
        return jsonRequest("/api/order/" + action, fields, data -> sameOrder(data, orderId));
    }

    private static Order sameOrder(JsonElement data, String expectedId) throws OrderException {
        Order order = new Order(object(data));
        if (!expectedId.equals(order.id)) throw invalid();
        return order;
    }

    private Request.Builder requestBuilder(String path, String accept) {
        return new Request.Builder().url(baseUrl + path).header("Host", host)
                .header("Accept", accept).header("Cache-Control", "no-store");
    }

    private interface Decoder<T> { T decode(JsonElement data) throws OrderException; }
    private interface ResponseDecoder<T> { T decode(Response response) throws OrderException; }

    private <T> T jsonRequest(String path, Map<String, JsonElement> fields, Decoder<T> decoder) throws OrderException {
        Request.Builder request = requestBuilder(path, "application/json");
        final byte[] payload = fields == null ? null : new JsonObject(fields).toString().getBytes(StandardCharsets.UTF_8);
        if (payload == null) request.get();
        else request.post(new RequestBody() {
            @Override public MediaType contentType() { return JSON; }
            @Override public long contentLength() { return payload.length; }
            @Override public void writeTo(BufferedSink sink) throws IOException { sink.write(payload); }
            @Override public boolean isOneShot() { return true; }
            @Override public String toString() { return "Sandbox request [fields hidden]"; }
        });
        try {
            return execute(request.build(), fields != null, response -> {
                requireSuccess(response);
                JsonObject root = jsonObject(response);
                exact(root, "sandbox", "data");
                if (!bool(root, "sandbox")) throw invalid();
                return decoder.decode(root.get("data"));
            });
        } finally {
            if (payload != null) Arrays.fill(payload, (byte) 0);
        }
    }

    private <T> T execute(Request request, boolean mutation, ResponseDecoder<T> decoder) throws OrderException {
        try {
            Response response = transport.execute(request);
            if (response == null) throw invalid();
            return decoder.decode(response);
        } catch (OrderException error) {
            boolean unknown = mutation && (error.kind == FailureKind.UNSUPPORTED_CONTRACT
                    || error.kind == FailureKind.INVALID_RESPONSE
                    || error.kind == FailureKind.RESPONSE_TOO_LARGE || error.kind == FailureKind.SERVICE_UNAVAILABLE
                    || error.kind == FailureKind.REDIRECT_REJECTED || error.kind == FailureKind.NETWORK_ERROR
                    || error.kind == FailureKind.TIMEOUT);
            // Rebuild rather than retaining transport exceptions, causes or suppressed text.
            throw new OrderException(error.kind, error.httpStatus, error.errorCode, unknown);
        } catch (InterruptedIOException timeout) {
            throw new OrderException(FailureKind.TIMEOUT, 0, null, mutation);
        } catch (IOException network) {
            throw new OrderException(FailureKind.NETWORK_ERROR, 0, null, mutation);
        } catch (RuntimeException invalid) {
            throw new OrderException(FailureKind.INVALID_RESPONSE, 0, null, mutation);
        }
    }

    private static void requireSuccess(Response response) throws OrderException {
        if (response.status == 200) return;
        if (response.status >= 300 && response.status < 400) throw failure(FailureKind.REDIRECT_REJECTED);
        if (response.status < 400 || response.status > 599) throw invalid();
        JsonObject error = jsonObject(response);
        exact(error, "error");
        String code = string(error, "error");
        if (!knownError(response.status, code)) throw invalid();
        FailureKind kind;
        switch (response.status) {
            case 401: kind = FailureKind.AUTHENTICATION_REQUIRED; break;
            case 403: kind = FailureKind.FORBIDDEN; break;
            case 409: kind = FailureKind.CONFLICT; break;
            case 503: kind = FailureKind.SERVICE_UNAVAILABLE; break;
            default: kind = FailureKind.REQUEST_REJECTED; break;
        }
        throw new OrderException(kind, response.status, code, false);
    }

    private static boolean knownError(int status, String code) {
        String codes;
        switch (status) {
            case 400: codes = "INVALID_REQUEST INVALID_JSON INVALID_HOST INVALID_REQUEST_KEY UNKNOWN_PLAN INVALID_CHANNEL INVALID_RECEIPT_REFERENCE INVALID_CURSOR INVALID_ACTUAL_AMOUNT INVALID_ACTUAL_CURRENCY"; break;
            case 401: codes = "AUTHENTICATION_REQUIRED"; break;
            case 403: codes = "FORBIDDEN BROWSER_SESSION_UNSUPPORTED"; break;
            case 404: codes = "NOT_FOUND ORDER_NOT_FOUND"; break;
            case 405: codes = "METHOD_NOT_ALLOWED"; break;
            case 409: codes = "PENDING_ORDER_EXISTS IDEMPOTENCY_CONFLICT INVALID_STATE PAYMENT_NOT_SUBMITTED RECEIPT_ALREADY_USED APPROVAL_CONFLICT APPROVAL_IN_PROGRESS APPROVAL_NOT_RESERVED"; break;
            case 413: codes = "REQUEST_TOO_LARGE"; break;
            case 415: codes = "JSON_REQUIRED"; break;
            case 503: codes = "PAYMENTS_DISABLED SANDBOX_DISPLAY_REQUIRED REMARK_CODES_EXHAUSTED ENTITLEMENT_RETRY_REQUIRED DATABASE_UNAVAILABLE SERVICE_UNAVAILABLE"; break;
            default: return false;
        }
        return Arrays.asList(codes.split(" ")).contains(code);
    }

    private static JsonObject jsonObject(Response response) throws OrderException {
        bytesPresent(response, MAX_JSON_BYTES);
        if (response.contentType == null || !JSON_TYPE.matcher(response.contentType).matches()) throw invalid();
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body)).toString();
        } catch (CharacterCodingException malformed) {
            throw invalid();
        }
        checkJsonLexemes(text);
        AppendOnlyKt.requireUniqueJsonKeys(text);
        JsonObject root = object(Json.Default.parseToJsonElement(text));
        validateValues(root);
        return root;
    }

    private static void bytesPresent(Response response, int limit) throws OrderException {
        if (response.bodyLength > limit) throw failure(FailureKind.RESPONSE_TOO_LARGE);
        if (response.body == null || response.body.length == 0) throw invalid();
    }

    /** Reject malformed string lexemes and bound nesting before either JSON parser. */
    private static void checkJsonLexemes(String text) throws OrderException {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c < 0x20) throw invalid();
                if (c == '"') inString = false;
                else if (c == '\\') {
                    if (++i == text.length()) throw invalid();
                    c = text.charAt(i);
                    if (c == 'u') {
                        for (int j = 0; j < 4; j++) {
                            if (++i == text.length() || Character.digit(text.charAt(i), 16) < 0
                                    || text.charAt(i) > 127) throw invalid();
                        }
                    } else if ("\"\\/bfnrt".indexOf(c) < 0) throw invalid();
                }
            } else if (c == '"') inString = true;
            else if (c == '{' || c == '[') { if (++depth > 8) throw invalid(); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw invalid(); }
            else if ((c < 0x20 && c != '\t' && c != '\r' && c != '\n') || c > 127) throw invalid();
        }
        if (inString || depth != 0) throw invalid();
    }

    private static void validateValues(JsonElement element) throws OrderException {
        if (element instanceof JsonObject) {
            for (Map.Entry<String, JsonElement> field : ((JsonObject) element).entrySet()) {
                if (unpairedSurrogate(field.getKey())) throw invalid();
                validateValues(field.getValue());
            }
        } else if (element instanceof JsonArray) {
            for (JsonElement child : (JsonArray) element) validateValues(child);
        } else if (element instanceof JsonPrimitive) {
            JsonPrimitive value = (JsonPrimitive) element;
            String content = value.getContent();
            if (value.isString()) {
                if (unpairedSurrogate(content)) throw invalid();
            } else if (!"null".equals(content) && !"true".equals(content) && !"false".equals(content)
                    && !JSON_NUMBER.matcher(content).matches()) throw invalid();
        } else throw invalid();
    }

    private static JsonObject object(JsonElement value) throws OrderException {
        if (!(value instanceof JsonObject)) throw invalid();
        return (JsonObject) value;
    }

    private static JsonArray array(JsonElement value) throws OrderException {
        if (!(value instanceof JsonArray)) throw invalid();
        return (JsonArray) value;
    }

    private static void exact(JsonObject object, String... keys) throws OrderException {
        if (!object.keySet().equals(new HashSet<>(Arrays.asList(keys)))) throw invalid();
    }

    private static String string(JsonObject object, String key) throws OrderException {
        JsonElement value = object.get(key);
        if (!(value instanceof JsonPrimitive) || !((JsonPrimitive) value).isString()) throw invalid();
        return ((JsonPrimitive) value).getContent();
    }

    private static String matching(JsonObject object, String key, Pattern pattern) throws OrderException {
        String value = string(object, key);
        if (!pattern.matcher(value).matches()) throw invalid();
        return value;
    }

    private static long integer(JsonObject object, String key) throws OrderException {
        JsonElement value = object.get(key);
        if (!(value instanceof JsonPrimitive) || ((JsonPrimitive) value).isString()) throw invalid();
        String content = ((JsonPrimitive) value).getContent();
        if (!content.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(content); }
        catch (NumberFormatException overflow) { throw invalid(); }
    }

    private static Long nullableInteger(JsonObject object, String key) throws OrderException {
        if (object.get(key) == JsonNull.INSTANCE) return null;
        return integer(object, key);
    }

    private static boolean bool(JsonObject object, String key) throws OrderException {
        JsonElement value = object.get(key);
        if (!(value instanceof JsonPrimitive) || ((JsonPrimitive) value).isString()) throw invalid();
        String content = ((JsonPrimitive) value).getContent();
        if ("true".equals(content)) return true;
        if ("false".equals(content)) return false;
        throw invalid();
    }

    private static String term(JsonObject object) throws OrderException {
        String term = string(object, "term");
        if (!"calendar_year".equals(term)) throw failure(FailureKind.UNSUPPORTED_CONTRACT);
        return term;
    }

    /** Identify unsupported fixed-price/term semantics without inventing a wire version field. */
    private static void requireDonationContract(JsonObject value, boolean plan) throws OrderException {
        if (value.containsKey("amount_minor")) nullAmount(value, "amount_minor");
        if (value.containsKey("currency")) nullCurrency(value, "currency");
        if (value.containsKey("term")) term(value);
        if (value.containsKey("days") && integer(value, "days") != 0) throw failure(FailureKind.UNSUPPORTED_CONTRACT);
        if (plan && value.containsKey("payment_mode") && !"donation".equals(string(value, "payment_mode"))) {
            throw failure(FailureKind.UNSUPPORTED_CONTRACT);
        }
        if (plan && value.containsKey("expiry_policy") && !"sandbox_upload_only".equals(string(value, "expiry_policy"))) {
            throw failure(FailureKind.UNSUPPORTED_CONTRACT);
        }
    }

    private static Long nullAmount(JsonObject value, String key) throws OrderException {
        if (value.get(key) == JsonNull.INSTANCE) return null;
        integer(value, key); // Malformed amounts remain malformed responses, never a zero quote.
        throw failure(FailureKind.UNSUPPORTED_CONTRACT);
    }

    private static String nullCurrency(JsonObject value, String key) throws OrderException {
        if (value.get(key) == JsonNull.INSTANCE) return null;
        matching(value, key, Pattern.compile("[A-Z]{3}"));
        throw failure(FailureKind.UNSUPPORTED_CONTRACT);
    }

    private static void input(String value, Pattern pattern) throws OrderException {
        if (value == null || !pattern.matcher(value).matches()) throw failure(FailureKind.INVALID_INPUT);
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

    private static OrderException invalid() { return failure(FailureKind.INVALID_RESPONSE); }
    private static OrderException failure(FailureKind kind) { return new OrderException(kind, 0, null, false); }

    private static String messageFor(FailureKind kind, boolean unknown) {
        String text;
        switch (kind) {
            case INVALID_INPUT: text = "沙箱订单参数无效。"; break;
            case AUTHENTICATION_REQUIRED: text = "沙箱身份验证未完成。"; break;
            case FORBIDDEN: text = "沙箱未允许这次操作。"; break;
            case CONFLICT: text = "沙箱订单状态有冲突，请刷新原订单。"; break;
            case SERVICE_UNAVAILABLE: text = "沙箱服务暂不可用。"; break;
            case UNSUPPORTED_CONTRACT: text = "赞赏服务版本不支持。"; break;
            case REDIRECT_REJECTED: text = "已拒绝沙箱地址跳转。"; break;
            case RESPONSE_TOO_LARGE: text = "沙箱响应超过大小限制。"; break;
            case TIMEOUT: text = "沙箱请求超时。"; break;
            case NETWORK_ERROR: text = "沙箱连接中断。"; break;
            case REQUEST_REJECTED: text = "沙箱未接受这次请求。"; break;
            default: text = "沙箱响应不符合约定。"; break;
        }
        return text + (unknown ? "操作结果未确认，请查询原订单并保留原请求标识，不要新建替代订单。" : "");
    }
}
