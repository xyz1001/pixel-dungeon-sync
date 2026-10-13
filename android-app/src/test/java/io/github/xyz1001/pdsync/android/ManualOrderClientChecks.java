package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import okhttp3.Request;
import okio.Buffer;
import static io.github.xyz1001.pdsync.android.ManualOrderClient.*;

/** Injected fixtures only: no HTTP implementation, socket, real account, or payment. */
public final class ManualOrderClientChecks {
    private static int checks;
    private static final String BASE = "http://127.0.0.1:18080";
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_ID = "fedcba9876543210fedcba9876543210";
    private static final String KEY = "stable-synthetic-request-001";
    private static final String SECRET = "NEVER_ECHO_SYNTHETIC_SECRET";
    private static final String NUMBERS = "\"amount_minor\":123,\"days\":0,\"quota_bytes\":0,\"upload_kbps\":0,\"download_kbps\":0";
    private static final String PLAN = "{\"id\":\"fixture_year\",\"currency\":\"CNY\",\"term\":\"calendar_year\","
            + NUMBERS + ",\"display_name\":\"仅沙箱 🧪\",\"payment_instructions\":\"合成测试\\n没有真实收款\","
            + "\"payment_image_path\":\"/api/order/payment-image/fixture_year\",\"expiry_policy\":\"sandbox_ledger_only\"}";
    private static final String ORDER = "{\"id\":\"" + ID + "\",\"plan_id\":\"fixture_year\",\"currency\":\"CNY\","
            + "\"term\":\"calendar_year\"," + NUMBERS
            + ",\"state\":\"created\",\"remark_code\":\"0007\",\"created_ms\":0,\"target_ms\":null}";
    private static final String ENTITLEMENT = "{\"source\":\"sandbox_ledger\",\"expires_ms\":null,\"active\":false}";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0};

    public static void main(String[] args) throws Exception {
        constructionAndRequests();
        endpointAndInputGuards();
        strictEnvelopeAndJson();
        exactFieldsAndNumbers();
        identityStateAndReferences();
        imagesAndBounds();
        allContractErrors();
        transportFailuresAndRedaction();
        cancellationIsExplicitAndSafe();
        check(args.length == 1, "host script supplies checked-in Python producer fixtures");
        serverInterop(args[0]);
        System.out.println("Manual sandbox order client: " + checks + " host checks passed; no network used.");
    }

    private static void constructionAndRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> keys = new ArrayList<>();
        ManualOrderClient client = new ManualOrderClient(BASE + "/", request -> {
            calls.incrementAndGet();
            check(request.url().host().equals("127.0.0.1") && request.url().port() == 18080, "literal local origin");
            check(request.header("Host").equals("127.0.0.1:18080"), "explicit host and port");
            check(request.url().query() == null && request.url().fragment() == null, "no query or fragment");
            check(request.header("Authorization") == null && request.header("Cookie") == null
                    && request.header("Origin") == null, "no fabricated authentication or browser session");
            check(request.headers().size() == 3, "only Host, Accept and Cache-Control headers");
            check("no-store".equals(request.header("Cache-Control")), "never cache private sandbox results");
            String path = request.url().encodedPath();
            if (path.equals("/api/order/payment-image/fixture_year")) {
                check(request.body() == null && request.method().equals("GET"), "image GET has no body");
                check(request.header("Accept").equals("image/png"), "image MIME requested");
                return new Response(200, "image/png", PNG);
            }
            check(request.header("Accept").equals("application/json"), "JSON requested");
            switch (path) {
                case "/api/order/plans":
                    getOnly(request); return json(200, wrap("[" + PLAN + "]"));
                case "/api/order/mine":
                    getOnly(request); return json(200, wrap("[" + ORDER + "]"));
                case "/api/order/entitlement":
                    getOnly(request); return json(200, wrap(ENTITLEMENT));
                case "/api/order/status/" + ID:
                    getOnly(request); return json(200, wrap(ORDER));
                case "/api/order/create": {
                    JsonObject body = requestBody(request);
                    check(body.size() == 2 && body.containsKey("plan_id") && body.containsKey("request_key"), "exact create fields");
                    check(field(body, "plan_id").equals("fixture_year"), "caller plan preserved");
                    keys.add(field(body, "request_key"));
                    return json(200, wrap(ORDER));
                }
                case "/api/order/submit":
                case "/api/order/cancel": {
                    JsonObject body = requestBody(request);
                    check(body.size() == 1 && field(body, "order_id").equals(ID), "exact order mutation field");
                    return json(200, wrap(ORDER.replace("created\"", path.endsWith("submit") ? "review\"" : "cancelled\"")));
                }
                default: throw new AssertionError("Unexpected route");
            }
        });
        check(calls.get() == 0, "construction is inert");
        check(ManualOrderClient.class.getConstructors().length == 1, "no default endpoint or transport constructor");
        List<Plan> plans = client.plans();
        Plan plan = plans.get(0);
        check(plan.amountMinor == 123 && plan.days == 0 && plan.quotaBytes == 0 && plan.uploadKbps == 0
                && plan.downloadKbps == 0, "schema zero quotas are accepted unchanged");
        check(plan.displayName.equals("仅沙箱 🧪") && plan.paymentInstructions.contains("\n"), "valid Unicode and escaped newline retained");
        check(plan.expiryPolicy.equals("sandbox_ledger_only") && plan.term.equals("calendar_year"), "sandbox-only policy");
        expectUnsupported(() -> plans.clear());
        List<Order> orders = client.mine();
        expectUnsupported(() -> orders.clear());
        check(orders.get(0).remarkCode.equals("0007"), "four digit code preserves leading zeroes");
        check(!client.entitlement().active && client.entitlement().expiresMs == null, "empty sandbox ledger stays unknown for remote rights");
        check(client.status(ID).id.equals(ID), "status id");
        client.create(plan.id, KEY);
        client.create(plan.id, KEY);
        check(keys.equals(Arrays.asList(KEY, KEY)), "caller persisted key preserved on every explicit attempt");
        check(client.submit(ID).state == State.REVIEW, "submit is claim only");
        check(client.cancel(ID).state == State.CANCELLED, "cancelled state is terminal");
        check(Arrays.equals(client.paymentImage(plan), PNG), "only explicit plan image endpoint");
        check(calls.get() == 10, "exactly one request per invocation; no retry or prefetch");
        for (Class<?> model : new Class<?>[] {Plan.class, Order.class, Entitlement.class}) {
            for (java.lang.reflect.Field field : model.getFields()) {
                check(Modifier.isFinal(field.getModifiers()), "immutable public model field");
            }
        }
        for (java.lang.reflect.Method method : ManualOrderClient.class.getDeclaredMethods()) {
            check(!Arrays.asList("approve", "reject", "retry", "queue", "register", "login").contains(method.getName()), "no admin/auth API");
        }
    }

    private static void endpointAndInputGuards() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Transport denied = request -> { calls.incrementAndGet(); throw new AssertionError("Invalid input reached transport"); };
        for (String base : new String[] {null, "", "http://127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:65536",
                "http://127.0.0.1:00080", "https://127.0.0.1:8080", "http://localhost:8080", "http://[::1]:8080",
                "http://127.1:8080", "http://2130706433:8080", "http://0x7f000001:8080", "http://127.0.0.2:8080",
                "http://user:pass@127.0.0.1:8080", "http://127.0.0.1:8080/api/", "http://127.0.0.1:8080?x",
                "http://127.0.0.1:8080/#x", "http://127.0.0.1:8080/%2e", "http://127.0.0.1:8080//",
                "http://127.0.0.1.evil.invalid:8080", "HTTP://127.0.0.1:8080", " " + BASE, BASE + "\n"}) {
            try { new ManualOrderClient(base, denied); throw new AssertionError("Accepted invalid base"); }
            catch (IllegalArgumentException expected) { check(!expected.getMessage().contains("pass"), "safe construction diagnostic"); }
        }
        try { new ManualOrderClient(BASE, null); throw new AssertionError("Null transport accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        new ManualOrderClient("http://127.0.0.1:1", denied);
        new ManualOrderClient("http://127.0.0.1:65535/", denied);
        new ManualOrderClient("http://127.0.0.1:80", request -> {
            check("127.0.0.1:80".equals(request.header("Host")), "port80 retains explicit host port despite URL normalization");
            return json(200, wrap("[]"));
        }).plans();
        ManualOrderClient client = new ManualOrderClient(BASE, denied);
        for (String id : new String[] {null, "", "short", ID.toUpperCase(), ID + "/", ID + "?x", "../" + ID, "\n" + ID}) {
            fail(() -> client.status(id), FailureKind.INVALID_INPUT, false);
            fail(() -> client.submit(id), FailureKind.INVALID_INPUT, false);
            fail(() -> client.cancel(id), FailureKind.INVALID_INPUT, false);
        }
        for (String id : new String[] {null, "", "INVALID", "x".repeat(49), "../x", "x/y", "x?query", "x%20y", "x#hash"}) {
            fail(() -> client.create(id, KEY), FailureKind.INVALID_INPUT, false);
        }
        for (String key : new String[] {null, "", "x".repeat(15), "x".repeat(81), "\n" + KEY, KEY + " ", "中文".repeat(16), KEY + "/"}) {
            fail(() -> client.create("fixture_year", key), FailureKind.INVALID_INPUT, false);
        }
        fail(() -> client.paymentImage(null), FailureKind.INVALID_INPUT, false);
        check(calls.get() == 0, "invalid arguments do not execute any transport");
        for (String key : new String[] {"x".repeat(16), "X_-9".repeat(20)}) {
            new ManualOrderClient(BASE, request -> {
                check(field(requestBody(request), "request_key").equals(key), "idempotency key boundaries are unchanged");
                return json(200, wrap(ORDER));
            }).create("fixture_year", key);
        }
    }

    private static void strictEnvelopeAndJson() throws Exception {
        String good = wrap(ORDER);
        for (String bad : new String[] {"", "null", "[]", "true", "{}", good + " garbage", good + good,
                good.replace("\"sandbox\":true", "\"sandbox\":false"), good.replace("\"sandbox\":true", "\"sandbox\":\"true\""),
                good.replace("\"sandbox\":true", "\"sandbox\":1"), good.replace("\"sandbox\":true,", ""),
                good.replace("\"sandbox\":true", "\"sandbox\":true,\"sandbox\":true"),
                good.replace("\"sandbox\":true", "\"sandbox\":true,\"sand\\u0062ox\":true"),
                good.replace("\"sandbox\":true", "\"sandbox\":true,\"token\":\"" + SECRET + "\""),
                good.replace("\"sandbox\"", "sandbox"), good.replace("\"sandbox\"", "'sandbox'"),
                good.replace("\"sandbox\":true", "\"sandbox\":True"), good.replace("\"sandbox\":true", "\"sandbox\":undefined"),
                good.replace("\"sandbox\":true", "\"sandbox\":true,"), "/*comment*/" + good,
                good.replace("\"state\":\"created\"", "\"state\":created"),
                good.replace("\"created\"", "\"cre\nated\""), good.replace("\"created\"", "\"cre\\x20ated\""),
                good.replace("\"created\"", "\"\\ud800\""), good.replace("\"created\"", "\"\\udc00\""),
                good.replace("\"created\"", "\"\\u１２３４\""), good.replace("\"created\"", "\"\\uZZZZ\""),
                good.replace("\"created\"", "\"created\u0000\""), "\ufeff" + good, good + "\u000b",
                "{\"sandbox\":true,\"data\":" + "[".repeat(10000) + "0" + "]".repeat(10000) + "}"}) {
            fail(() -> fixed(200, bad).status(ID), FailureKind.INVALID_RESPONSE, false);
        }
        for (String type : new String[] {null, "", "text/html", "text/plain", "application/jsonp", "application/json; charset=latin1",
                "application/json; charset=utf-8; password=" + SECRET, "application/json\r\n"}) {
            fail(() -> new ManualOrderClient(BASE, request -> new Response(200, type, utf8(good))).status(ID), FailureKind.INVALID_RESPONSE, false);
        }
        for (String type : new String[] {"application/json", "application/json; charset=utf-8", "APPLICATION/JSON; CHARSET=\"UTF-8\""}) {
            check(new ManualOrderClient(BASE, request -> new Response(200, type, utf8(good))).status(ID).state == State.CREATED, "supported JSON MIME");
        }
        byte[] badUtf8 = utf8(good);
        badUtf8[2] = (byte) 0xff;
        fail(() -> new ManualOrderClient(BASE, request -> new Response(200, "application/json", badUtf8)).status(ID), FailureKind.INVALID_RESPONSE, false);
        fail(() -> new ManualOrderClient(BASE, request -> null).plans(), FailureKind.INVALID_RESPONSE, false);
    }

    private static void exactFieldsAndNumbers() throws Exception {
        JsonObject parsed = (JsonObject) Json.Default.parseToJsonElement(ORDER);
        for (String key : parsed.keySet()) {
            String removed = mutate(ORDER, key, null);
            fail(() -> fixed(200, wrap(removed)).status(ID), FailureKind.INVALID_RESPONSE, false);
            fail(() -> fixed(200, wrap(mutate(ORDER, key, "[]"))).status(ID), FailureKind.INVALID_RESPONSE, false);
        }
        for (String field : new String[] {"amount_minor", "days", "quota_bytes", "upload_kbps", "download_kbps", "created_ms", "target_ms"}) {
            for (String invalid : new String[] {"-1", "1.0", "1e3", "01", "+1", "-0", "1.", "NaN", "Infinity", "true", "\"0\"", "9223372036854775808"}) {
                fail(() -> fixed(200, wrap(mutate(ORDER, field, invalid))).status(ID), FailureKind.INVALID_RESPONSE, false);
            }
            for (String valid : new String[] {"0", "1", "9223372036854775807"}) {
                check(fixed(200, wrap(mutate(ORDER, field, valid))).status(ID) != null, "nonnegative integer boundary retained");
            }
        }
        for (String key : ((JsonObject) Json.Default.parseToJsonElement(PLAN)).keySet()) {
            fail(() -> fixed(200, wrap("[" + mutate(PLAN, key, null) + "]")).plans(), FailureKind.INVALID_RESPONSE, false);
        }
        for (String value : new String[] {PLAN.replace("\"amount_minor\":123", "\"amount_minor\":0"),
                PLAN.replace("calendar_year", "fixed_days"), PLAN.replace("仅沙箱 🧪", "")}) {
            check(fixed(200, wrap("[" + value + "]")).plans().size() == 1, "do not invent additional product limits beyond response schema");
        }
        for (String value : new String[] {mutate(ORDER, "owner", "\"" + SECRET + "\""),
                mutate(ORDER, "password", "\"" + SECRET + "\""), mutate(ORDER, "receipt_ref", "\"" + SECRET + "\""),
                ORDER.replace("\"days\":0", "\"days\":0,\"days\":0")}) {
            fail(() -> fixed(200, wrap(value)).status(ID), FailureKind.INVALID_RESPONSE, false);
        }
        check(fixed(200, wrap("[]")).mine().isEmpty() && fixed(200, wrap("[]")).plans().isEmpty(), "empty collections accepted");
        fail(() -> fixed(200, wrap("[" + ORDER + "," + ORDER + "]")).mine(), FailureKind.INVALID_RESPONSE, false);
        fail(() -> fixed(200, wrap("[" + PLAN + "," + PLAN + "]")).plans(), FailureKind.INVALID_RESPONSE, false);
        StringBuilder rows = new StringBuilder("[");
        for (int i = 0; i < 101; i++) {
            if (i != 0) rows.append(',');
            rows.append(ORDER.replace(ID, String.format("%032x", i)));
        }
        rows.append(']');
        fail(() -> fixed(200, wrap(rows.toString())).mine(), FailureKind.INVALID_RESPONSE, false);
        String hundred = rows.substring(0, rows.lastIndexOf("," + ORDER.substring(0, 7))) + "]";
        check(fixed(200, wrap(hundred)).mine().size() == 100, "maximum mine page accepted");
        for (String value : new String[] {mutate(ENTITLEMENT, "source", "\"production\""), mutate(ENTITLEMENT, "expires_ms", "-1"),
                mutate(ENTITLEMENT, "expires_ms", "1.0"), mutate(ENTITLEMENT, "active", "1"), mutate(ENTITLEMENT, "active", "\"true\""),
                mutate(ENTITLEMENT, "active", null), mutate(ENTITLEMENT, "expires_ms", null), mutate(ENTITLEMENT, "token", "\"" + SECRET + "\"")}) {
            fail(() -> fixed(200, wrap(value)).entitlement(), FailureKind.INVALID_RESPONSE, false);
        }
        Entitlement entitlement = fixed(200, wrap(ENTITLEMENT.replace("null", "0").replace("false", "true"))).entitlement();
        check(entitlement.active && entitlement.expiresMs == 0, "preserve schema-valid ledger values without inferring subscription rules");
    }

    private static void identityStateAndReferences() throws Exception {
        for (String bad : new String[] {"CREATED", "paid", "expired", "", "unknown", " approved"}) {
            fail(() -> fixed(200, wrap(mutate(ORDER, "state", "\"" + bad + "\""))).status(ID), FailureKind.INVALID_RESPONSE, false);
        }
        for (State state : State.values()) {
            check(fixed(200, wrap(mutate(ORDER, "state", "\"" + state.name().toLowerCase(java.util.Locale.ROOT) + "\"")))
                    .status(ID).state == state, "all documented states map exactly");
        }
        for (String code : new String[] {"7", "007", "00007", "１２３４", "abcd", "-007", "0\n07"}) {
            fail(() -> fixed(200, wrap(mutate(ORDER, "remark_code", "\"" + code + "\""))).status(ID), FailureKind.INVALID_RESPONSE, false);
        }
        fail(() -> fixed(200, wrap(mutate(ORDER, "remark_code", "7"))).status(ID), FailureKind.INVALID_RESPONSE, false);
        for (String code : new String[] {"0000", "0007", "9999"}) {
            check(fixed(200, wrap(mutate(ORDER, "remark_code", "\"" + code + "\""))).status(ID).remarkCode.equals(code), "remark string boundary");
        }
        for (String key : new String[] {"id", "plan_id", "currency", "term"}) {
            fail(() -> fixed(200, wrap(mutate(ORDER, key, "\"../unsafe\""))).status(ID), FailureKind.INVALID_RESPONSE, false);
        }
        String mismatch = wrap(ORDER.replace(ID, OTHER_ID));
        fail(() -> fixed(200, mismatch).status(ID), FailureKind.INVALID_RESPONSE, false);
        fail(() -> fixed(200, mismatch).submit(ID), FailureKind.INVALID_RESPONSE, true);
        fail(() -> fixed(200, mismatch).cancel(ID), FailureKind.INVALID_RESPONSE, true);
        fail(() -> fixed(200, wrap(ORDER)).create("different_plan", KEY), FailureKind.INVALID_RESPONSE, true);
        check(fixed(200, wrap(ORDER.replace("created\"", "approved\""))).create("fixture_year", KEY).state == State.APPROVED,
                "idempotent create accepts original completed order");
    }

    private static void imagesAndBounds() throws Exception {
        Plan plan = fixed(200, wrap("[" + PLAN + "]")).plans().get(0);
        for (String path : new String[] {"https://evil.invalid/pay", "http://127.0.0.1:18080/api/order/payment-image/fixture_year",
                "//127.0.0.1:18080/api/order/payment-image/fixture_year", "/api/order/payment-image/other",
                "/api/order/payment-image/fixture_year?token=x", "/api/order/payment-image/fixture_year#x",
                "/api/order/payment-image/%66ixture_year", "/api/order/payment-image/../fixture_year", "/api/order/payment-image/fixture_year/"}) {
            String value = mutate(PLAN, "payment_image_path", "\"" + path + "\"");
            fail(() -> fixed(200, wrap("[" + value + "]")).plans(), FailureKind.INVALID_RESPONSE, false);
        }
        for (String type : new String[] {null, "image/jpeg", "image/svg+xml", "text/html", "image/png; password=" + SECRET}) {
            fail(() -> new ManualOrderClient(BASE, request -> new Response(200, type, PNG)).paymentImage(plan), FailureKind.INVALID_RESPONSE, false);
        }
        for (byte[] bad : new byte[][] {null, new byte[0], new byte[7], utf8("<svg>"), utf8("not a PNG"), new byte[MAX_IMAGE_BYTES + 1]}) {
            fail(() -> new ManualOrderClient(BASE, request -> new Response(200, "image/png", bad)).paymentImage(plan),
                    bad != null && bad.length > MAX_IMAGE_BYTES ? FailureKind.RESPONSE_TOO_LARGE : FailureKind.INVALID_RESPONSE, false);
        }
        byte[] max = new byte[MAX_IMAGE_BYTES];
        System.arraycopy(PNG, 0, max, 0, PNG.length);
        Response snapshot = new Response(200, "image/png", max);
        Arrays.fill(max, (byte) 0);
        ManualOrderClient client = new ManualOrderClient(BASE, request -> snapshot);
        byte[] first = client.paymentImage(plan);
        check(first.length == MAX_IMAGE_BYTES && first[0] == (byte) 0x89, "bounded response defensively snapshots caller array");
        Arrays.fill(first, (byte) 0);
        check(client.paymentImage(plan)[0] == (byte) 0x89, "returned image cannot mutate retained snapshot");
        byte[] padded = new byte[MAX_JSON_BYTES];
        Arrays.fill(padded, (byte) ' ');
        byte[] valid = utf8(wrap(ORDER));
        System.arraycopy(valid, 0, padded, 0, valid.length);
        check(new ManualOrderClient(BASE, request -> new Response(200, "application/json", padded)).status(ID) != null, "exact JSON limit accepted");
        fail(() -> new ManualOrderClient(BASE, request -> new Response(200, "application/json", new byte[MAX_JSON_BYTES + 1])).status(ID), FailureKind.RESPONSE_TOO_LARGE, false);
        fail(() -> new ManualOrderClient(BASE, request -> new Response(200, "application/json", new byte[MAX_JSON_BYTES + 1])).create("fixture_year", KEY), FailureKind.RESPONSE_TOO_LARGE, true);
        fail(() -> fixed(503, "{\"error\":\"PAYMENTS_DISABLED\"}").paymentImage(plan), FailureKind.SERVICE_UNAVAILABLE, false);
    }

    private static void allContractErrors() throws Exception {
        String[] groups = {
            "400 INVALID_REQUEST INVALID_JSON INVALID_HOST INVALID_REQUEST_KEY UNKNOWN_PLAN INVALID_CHANNEL INVALID_RECEIPT_REFERENCE INVALID_CURSOR",
            "401 AUTHENTICATION_REQUIRED", "403 FORBIDDEN BROWSER_SESSION_UNSUPPORTED", "404 NOT_FOUND ORDER_NOT_FOUND",
            "405 METHOD_NOT_ALLOWED", "409 PENDING_ORDER_EXISTS IDEMPOTENCY_CONFLICT INVALID_STATE PAYMENT_NOT_SUBMITTED RECEIPT_ALREADY_USED APPROVAL_CONFLICT APPROVAL_IN_PROGRESS APPROVAL_NOT_RESERVED",
            "413 REQUEST_TOO_LARGE", "415 JSON_REQUIRED", "503 PAYMENTS_DISABLED SANDBOX_DISPLAY_REQUIRED REMARK_CODES_EXHAUSTED ENTITLEMENT_RETRY_REQUIRED DATABASE_UNAVAILABLE SERVICE_UNAVAILABLE"};
        for (String group : groups) {
            String[] words = group.split(" ");
            int status = Integer.parseInt(words[0]);
            for (int i = 1; i < words.length; i++) {
                String code = words[i];
                FailureKind kind = status == 401 ? FailureKind.AUTHENTICATION_REQUIRED : status == 403 ? FailureKind.FORBIDDEN
                        : status == 409 ? FailureKind.CONFLICT : status == 503 ? FailureKind.SERVICE_UNAVAILABLE : FailureKind.REQUEST_REJECTED;
                ManualOrderClient client = fixed(status, "{\"error\":\"" + code + "\"}");
                OrderException error = fail(() -> client.create("fixture_year", KEY), kind, status == 503);
                check(error.httpStatus == status && code.equals(error.errorCode), "known sanitized error status and code");
                fail(() -> client.mine(), kind, false);
            }
        }
        for (int status : new int[] {0, 199, 201, 204, 299, 500, 600}) {
            fail(() -> fixed(status, wrap(ORDER)).status(ID), FailureKind.INVALID_RESPONSE, false);
        }
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            fail(() -> fixed(status, SECRET).create("fixture_year", KEY), FailureKind.REDIRECT_REJECTED, true);
        }
        for (String bad : new String[] {"{}", "[]", "null", "{\"error\":1}", "{\"error\":\"" + SECRET + "\"}",
                "{\"error\":\"PAYMENTS_DISABLED\",\"message\":\"" + SECRET + "\"}", "{\"error\":\"payments_disabled\"}",
                "{\"error\":\"PAYMENTS_DISABLED\",\"error\":\"PAYMENTS_DISABLED\"}", "{\"error\":\"FORBIDDEN\"}"}) {
            fail(() -> fixed(503, bad).plans(), FailureKind.INVALID_RESPONSE, false);
        }
        fail(() -> fixed(200, "{\"error\":\"PAYMENTS_DISABLED\"}").plans(), FailureKind.INVALID_RESPONSE, false);
        fail(() -> fixed(401, "{\"token\":\"" + SECRET + "\"}").plans(), FailureKind.INVALID_RESPONSE, false);
    }

    private static void transportFailuresAndRedaction() throws Exception {
        for (boolean timeout : new boolean[] {false, true}) {
            AtomicInteger calls = new AtomicInteger();
            ManualOrderClient client = new ManualOrderClient(BASE, request -> {
                calls.incrementAndGet();
                IOException error = timeout ? new InterruptedIOException(SECRET) : new IOException(SECRET);
                error.addSuppressed(new RuntimeException(SECRET));
                throw error;
            });
            fail(() -> client.create("fixture_year", KEY), timeout ? FailureKind.TIMEOUT : FailureKind.NETWORK_ERROR, true);
            check(calls.get() == 1, "unknown creation outcome is never retried");
            fail(() -> client.status(ID), timeout ? FailureKind.TIMEOUT : FailureKind.NETWORK_ERROR, false);
            check(calls.get() == 2, "one explicit status attempt");
        }
        fail(() -> new ManualOrderClient(BASE, request -> { throw new IllegalStateException(SECRET); }).create("fixture_year", KEY), FailureKind.INVALID_RESPONSE, true);
        for (Object value : new Object[] {fixed(200, wrap(ORDER)).status(ID), fixed(200, wrap("[" + PLAN + "]")).plans().get(0),
                fixed(200, wrap(ENTITLEMENT)).entitlement(), json(200, SECRET)}) {
            check(!value.toString().contains(ID) && !value.toString().contains(SECRET), "model and response diagnostics hide payload");
        }
    }

    private static void cancellationIsExplicitAndSafe() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        ManualOrderClient client = new ManualOrderClient(BASE, new Transport() {
            @Override public Response execute(Request request) {
                calls.incrementAndGet();
                return json(200, wrap("[]"));
            }
            @Override public void cancelPending() { cancels.incrementAndGet(); }
        });
        client.cancelPending();
        check(cancels.get() == 1 && calls.get() == 0, "explicit cancellation only forwards cancellation");
        client.plans();
        client.cancelPending();
        check(cancels.get() == 2 && calls.get() == 1, "cancellation does not retry or create a request");
        new ManualOrderClient(BASE, new Transport() {
            @Override public Response execute(Request request) { throw new AssertionError("No request expected"); }
            @Override public void cancelPending() { throw new IllegalStateException(SECRET); }
        }).cancelPending();
        check(true, "cancellation exception does not escape or disclose transport content");
        check(fixed(200, wrap(mutate(ORDER, "amount_minor", "9007199254740993"))).status(ID).amountMinor
                == 9007199254740993L, "minor-unit amount does not round through floating point");
    }

    private static void serverInterop(String file) throws Exception {
        List<String> fixtures = Files.readAllLines(Paths.get(file), StandardCharsets.UTF_8);
        check(fixtures.size() == 9, "fixtures cover actual adapter data plus default denial and disablement");
        Plan plan = fixed(200, fixtures.get(0)).plans().get(0);
        check(plan.id.equals("fixture_year") && plan.amountMinor == 123 && plan.paymentImagePath.endsWith(plan.id), "real checked-in plan producer");
        Order created = fixed(200, fixtures.get(1)).mine().get(0);
        check(created.state == State.CREATED && created.remarkCode.length() == 4, "real create producer");
        check(fixed(200, fixtures.get(2)).submit(created.id).state == State.REVIEW, "real submit producer");
        check(fixed(200, fixtures.get(3)).entitlement().expiresMs == null, "real empty entitlement producer");
        check(fixed(200, fixtures.get(4)).status(created.id).state == State.APPROVED, "real approved producer");
        check(fixed(200, fixtures.get(5)).entitlement().active, "real sandbox ledger approval");
        Order cancelled = fixed(200, fixtures.get(6)).mine().get(0);
        check(cancelled.state == State.CANCELLED, "real cancelled producer");
        fail(() -> fixed(401, fixtures.get(7)).plans(), FailureKind.AUTHENTICATION_REQUIRED, false);
        fail(() -> fixed(503, fixtures.get(8)).plans(), FailureKind.SERVICE_UNAVAILABLE, false);
    }

    private static String wrap(String data) { return "{\"sandbox\":true,\"data\":" + data + "}"; }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static Response json(int status, String body) { return new Response(status, "application/json; charset=utf-8", utf8(body)); }
    private static ManualOrderClient fixed(int status, String body) { return new ManualOrderClient(BASE, request -> json(status, body)); }

    private static String mutate(String json, String key, String replacement) {
        JsonObject root = (JsonObject) Json.Default.parseToJsonElement(json);
        StringBuilder result = new StringBuilder("{");
        boolean found = false;
        for (String field : root.keySet()) {
            if (field.equals(key)) {
                found = true;
                if (replacement == null) continue;
            }
            if (result.length() != 1) result.append(',');
            result.append('"').append(field).append("\":").append(field.equals(key) ? replacement : root.get(field).toString());
        }
        if (!found && replacement != null) {
            if (result.length() != 1) result.append(',');
            result.append('"').append(key).append("\":").append(replacement);
        }
        return result.append('}').toString();
    }

    private static void getOnly(Request request) {
        check(request.method().equals("GET") && request.body() == null, "GET has no request body");
    }

    private static JsonObject requestBody(Request request) throws IOException {
        check(request.method().equals("POST") && request.body() != null && request.body().isOneShot(), "single-attempt POST");
        check(request.body().contentType().toString().equals("application/json; charset=utf-8"), "JSON POST MIME");
        check(!request.body().toString().contains(KEY), "payload-safe diagnostics");
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        check(buffer.size() == request.body().contentLength() && buffer.size() <= 4096, "bounded exact request byte length");
        return (JsonObject) Json.Default.parseToJsonElement(buffer.readUtf8());
    }

    private static String field(JsonObject object, String key) {
        check(object.get(key) instanceof JsonPrimitive && ((JsonPrimitive) object.get(key)).isString(), "string field");
        return ((JsonPrimitive) object.get(key)).getContent();
    }

    private interface Checked { void run() throws Exception; }
    private static OrderException fail(Checked action, FailureKind kind, boolean unknown) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + kind); }
        catch (OrderException error) {
            check(error.kind == kind, "failure kind: expected " + kind + " but got " + error.kind);
            check(error.outcomeUnknown == unknown, "outcome ambiguity is explicit");
            check(error.getCause() == null && error.getSuppressed().length == 0, "no upstream exception attached");
            check(!error.toString().contains(SECRET) && !error.userMessage().contains(SECRET), "diagnostic is safely redacted");
            return error;
        }
    }

    private static void expectUnsupported(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Mutable result collection"); }
        catch (UnsupportedOperationException expected) { checks++; }
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
}
