package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import okhttp3.Request;
import okio.Buffer;

/** Deterministic host-only controller checks: no sockets, credentials, Android, or real payment. */
public final class ManualPaymentSessionChecks {
    private static final String A = "synthetic-session-a", B = "synthetic-session-b";
    private static final String ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String ID2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String PLAN = "fixture_a";
    private static int checks;

    public static void main(String[] args) throws Exception {
        disabledMakesNoRequests();
        bootstrapCreateReviewApproveAndDav();
        createTimeoutPreservesIdentity();
        queuedWorkIsCancelled();
        staleBootstrapAndDavRepliesAreIgnored();
        accountSwitchRejectsOldOrderAndImage();
        refreshedImageRejectsOldCompletion(false);
        refreshedImageRejectsOldCompletion(true);
        refreshedImageCancelsQueuedDownload();
        imageFailureCanRefresh();
        foregroundPreservesPollingBudget();
        unsupportedContractMapsToLockedFlow();
        rejectedExecutorFailsClosed();
        cancellationConfirmationIsScopeBound();
        copyUsesDonationValidity();
        System.out.println("ManualPaymentSessionChecks: " + checks
                + " checks passed (queued host fixtures; no sockets/device/account/payment)");
    }

    private static void disabledMakesNoRequests() throws Exception {
        try (Harness h = new Harness(false)) {
            h.session.enter(A);
            h.session.create(); h.session.submit(); h.session.cancel(); h.session.refresh();
            h.session.testDav(); h.session.selectPlan(PLAN);
            h.session.foreground(false); h.session.foreground(true);
            h.ui.advance(1_000_000);
            h.session.leave(); h.session.enter(B);
            eq(ManualPaymentFlow.Phase.DISABLED, h.session.view().phase, "default constructor stays disabled");
            check(!h.session.canContinue(), "disabled session cannot unlock Next");
            eq(0, h.io.pending(), "disabled session queues no IO");
            eq(0, h.transport.requests.size(), "disabled session performs zero transport requests");
            eq(0, h.dav.calls, "disabled session performs zero DAV requests");
            eq(0, h.store.writes, "disabled session writes no draft");
            eq(0, h.ui.delayed.size(), "disabled session schedules no polling");
        }
    }

    private static void bootstrapCreateReviewApproveAndDav() throws Exception {
        try (Harness h = new Harness(true)) {
            h.queueBootstrap("[]");
            h.session.enter(A);
            eq(0, h.transport.requests.size(), "enter queues IO without inline network work");
            h.roundTrip();
            check(h.session.view().busy && !h.session.view().canCreate, "plans alone cannot create");
            h.roundTrip();
            check(h.session.view().busy && !h.session.view().canCreate, "mine alone cannot create");
            h.roundTrip();
            check(h.session.view().canCreate && !h.session.canContinue(), "bootstrap enables create only");
            eq("donation", h.session.view().plans.get(0).paymentMode, "bootstrap retains donation mode");
            eq(null, h.session.view().plans.get(0).amountMinor, "bootstrap never fabricates a quoted price");
            eq(null, h.session.view().plans.get(0).currency, "bootstrap has no quoted currency");
            h.session.selectPlan("fixture_b");
            eq("fixture_b", h.session.view().selectedPlanId, "session forwards explicit plan choice");
            h.session.selectPlan(PLAN);
            h.transport.json("POST", "/api/order/create", order(ID, "created"));
            h.transport.png(1);
            h.session.create(); h.session.create(); h.session.submit(); h.session.cancel(); h.session.testDav();
            eq(1, h.io.pending(), "repeated and competing clicks have one in-flight create");
            eq(1, h.store.writes, "stable key durably saved before transport executes");
            String key = h.store.load(A).requestKey;
            check(key != null && key.matches("[A-Za-z0-9_-]{16,80}"), "create has valid persisted key");
            h.roundTrip();
            eq(key, h.transport.field(h.transport.last(), "request_key"), "wire create uses saved key");
            eq(Set.of("plan_id", "request_key"), h.transport.bodies.get(h.transport.last()).keySet(),
                    "create sends only plan and idempotency key, never a donated amount");
            eq(ID, h.session.view().order.id, "returned order ID retained");
            eq("0007", h.session.view().order.remarkCode, "returned leading-zero remark retained");
            eq(null, h.session.view().order.actualAmountMinor, "created order has no verified payment amount");
            eq(null, h.session.view().order.actualCurrency, "created order has no verified payment currency");
            check(h.session.view().canSubmit && !h.session.canContinue(), "created remains blocked from Next");
            h.roundTrip();
            eq((byte) 1, h.session.paymentImage()[8], "injected image displayed only after matching order");
            byte[] copy = h.session.paymentImage(); copy[8] = 9;
            eq((byte) 1, h.session.paymentImage()[8], "payment image getter is defensive");
            h.ui.advance(700_000);
            h.transport.json("POST", "/api/order/submit", order(ID, "review"));
            h.session.submit(); h.session.submit(); h.session.create();
            eq(1, h.io.pending(), "repeated submit queues one request");
            h.roundTrip();
            eq(ID, h.transport.field(h.transport.last(), "order_id"), "submit uses returned ID");
            eq(Set.of("order_id"), h.transport.bodies.get(h.transport.last()).keySet(),
                    "payment claim never supplies an amount or currency");
            eq(ManualPaymentFlow.Phase.REVIEW, h.session.view().phase, "submit enters review, not approval");
            eq(null, h.session.view().order.actualAmountMinor, "review does not treat the claim as verified payment");
            check(h.session.paymentImage() == null, "review never exposes a stale created-order payment image");
            check(!h.session.canContinue() && !h.session.view().canTestDav, "review cannot bypass DAV gate");
            eq(2_000L, h.ui.nextDelay(), "submit after long dwell starts fresh bounded polling");
            h.ui.advance(1_999);
            eq(0, h.io.pending(), "poll does not run early");
            h.transport.json("GET", "/api/order/status/" + ID, order(ID, "applying"));
            h.ui.advance(1); h.roundTrip();
            eq(ManualPaymentFlow.Phase.APPLYING, h.session.view().phase, "poll can observe applying");
            eq(Long.valueOf(123), h.session.view().order.actualAmountMinor, "applying retains reviewer-confirmed amount");
            eq("CNY", h.session.view().order.actualCurrency, "applying retains reviewer-confirmed currency");
            eq(4_000L, h.ui.nextDelay(), "review polling backs off");
            h.transport.json("GET", "/api/order/status/" + ID, order(ID, "approved"));
            h.ui.advance(4_000); h.roundTrip();
            check(h.session.view().canTestDav && !h.session.canContinue(), "approval still requires explicit DAV test");
            eq(Long.valueOf(123), h.session.view().order.actualAmountMinor, "approval preserves actual amount");
            eq(null, h.session.view().order.amountMinor, "approval does not turn actual amount into a quoted price");
            eq(0, h.ui.delayed.size(), "approval removes automatic polling");
            h.dav.failures = 1;
            h.session.testDav(); h.session.testDav();
            eq(1, h.io.pending(), "duplicate DAV clicks are single-flight");
            h.roundTrip();
            check(!h.session.canContinue(), "DAV failure keeps Next disabled");
            eq("连接测试未通过", h.session.view().message, "DAV exception is replaced by safe user copy");
            h.session.testDav(); h.roundTrip();
            check(h.session.canContinue(), "approved plus explicit DAV success enables Next");
            eq(ManualPaymentFlow.Phase.READY, h.session.view().phase, "ready phase after actual verifier success");
            eq(2, h.dav.calls, "only explicit verifier attempts run");
            h.session.leave();
            check(!h.session.canContinue(), "leaving revokes Next");
            check(h.changes > 0, "listener receives state changes");
            h.assertSettled();
        }
    }

    private static void createTimeoutPreservesIdentity() throws Exception {
        try (Harness h = new Harness(true)) {
            h.boot(A, "[]");
            h.transport.failure("POST", "/api/order/create", new InterruptedIOException("synthetic timeout"));
            h.session.create(); h.roundTrip();
            String key = h.transport.field(h.transport.last(), "request_key");
            check(h.session.view().outcomeUnknown && h.session.view().canCreate, "timed-out create allows explicit stable retry");
            h.session.selectPlan("fixture_b");
            eq(PLAN, h.session.view().selectedPlanId, "unknown create outcome pins plan");
            h.transport.json("POST", "/api/order/create", order(ID, "created"));
            h.transport.png(2);
            h.session.create(); h.session.create(); h.roundTrip();
            eq(key, h.transport.field(h.transport.last(), "request_key"), "timeout retry preserves exact wire request key");
            h.roundTrip();
            eq(ID, h.store.load(A).orderId, "returned ID persisted after retry");
            eq(key, h.store.load(A).requestKey, "successful retry keeps original key");
            h.session.leave();
            h.queueBootstrap("[]");
            h.transport.json("GET", "/api/order/status/" + ID, order(ID, "created"));
            h.session.enter(A);
            h.roundTrip(); h.roundTrip(); h.roundTrip();
            check(!h.session.view().canCreate, "known ID absent from mine cannot create replacement");
            h.roundTrip();
            eq(ID, h.session.view().order.id, "Back and return resolves same known ID via status");
            eq("0007", h.session.view().order.remarkCode, "Back and return retain exact server remark");
            eq(key, h.store.load(A).requestKey, "Back and return preserves stable key");
            h.assertSettled();
        }
    }

    private static void queuedWorkIsCancelled() throws Exception {
        try (Harness h = new Harness(true)) {
            h.session.enter(A);
            h.session.foreground(false);
            eq(1, h.io.cancelled(), "background cancels queued bootstrap future");
            h.io.runAll(); h.ui.flush();
            eq(0, h.transport.requests.size(), "cancelled queued bootstrap never reaches transport");
            h.session.create(); h.session.refresh(); h.session.testDav();
            eq(0, h.io.pending(), "background user actions do not queue IO");
            h.queueBootstrap("[]");
            h.session.foreground(true);
            h.roundTrip(); h.roundTrip(); h.roundTrip();
            h.session.create();
            String key = h.store.load(A).requestKey;
            h.session.leave();
            h.io.runAll(); h.ui.flush();
            eq(3, h.transport.requests.size(), "leave cancels queued create before transport");
            eq(key, h.store.load(A).requestKey, "cancelled create preserves durable retry identity");
            h.queueBootstrap("[" + order(ID, "review") + "]");
            h.session.enter(A); h.roundTrip(); h.roundTrip(); h.roundTrip();
            eq(1, h.ui.delayed.size(), "review schedules one poll");
            h.ui.advance(2_000);
            eq(1, h.io.pending(), "due poll waits on injected IO executor");
            h.session.foreground(false);
            h.io.runAll(); h.ui.flush();
            eq(6, h.transport.requests.size(), "background cancels queued status transport");
            eq(0, h.ui.delayed.size(), "background removes poll timer");
            check(h.transport.cancels >= 3, "lifecycle forwards best-effort transport cancellation");
            h.assertSettled();
        }
        Harness h = new Harness(true);
        h.session.enter(A);
        int changes = h.changes;
        h.session.close(); h.session.close();
        h.io.runAll(); h.ui.flush();
        h.session.enter(B); h.session.foreground(true); h.session.create(); h.session.refresh(); h.session.testDav();
        eq(0, h.transport.requests.size(), "closed session cannot start requests or revive queued work");
        eq(changes, h.changes, "close detaches listener");
        check(h.io.isShutdown(), "close shuts down owned IO executor");
        check(!h.session.canContinue() && h.session.paymentImage() == null, "close clears image and continuation");
    }

    private static void staleBootstrapAndDavRepliesAreIgnored() throws Exception {
        try (Harness h = new Harness(true)) {
            h.transport.json("GET", "/api/order/plans", plans());
            h.session.enter(A); h.io.runNext();
            eq(1, h.ui.posted.size(), "completed IO waits for UI callback");
            h.session.foreground(false); h.ui.flush();
            check(h.session.view().plans.isEmpty(), "background discards posted plans success");
            eq(0, h.io.pending(), "stale plans callback cannot chain mine request");
            h.queueBootstrap("[]", true);
            h.session.foreground(true); h.roundTrip(); h.roundTrip(); h.roundTrip();
            check(h.session.view().canTestDav && !h.session.canContinue(), "active entitlement needs a DAV test");
            h.session.testDav(); h.io.runNext();
            h.session.leave(); h.ui.flush();
            check(!h.session.canContinue(), "DAV success posted before leave cannot unlock Next afterward");
            h.assertSettled();
        }
        try (Harness h = new Harness(true)) {
            h.transport.failure("GET", "/api/order/plans", new IOException("synthetic failure"));
            h.session.enter(A); h.io.runNext();
            h.queueBootstrap("[]"); h.session.enter(B);
            h.ui.flush();
            check(h.session.view().busy, "old failure cannot clear new account's busy state");
            h.roundTrip(); h.roundTrip(); h.roundTrip();
            check(h.session.view().canCreate, "new account boot survives stale failure");
            h.assertSettled();
        }
    }

    private static void accountSwitchRejectsOldOrderAndImage() throws Exception {
        try (Harness h = new Harness(true)) {
            h.boot(A, "[]");
            h.transport.json("POST", "/api/order/create", order(ID, "approved"));
            h.session.create(); h.io.runNext();
            String aKey = h.store.load(A).requestKey;
            h.queueBootstrap("[]"); h.session.enter(B); h.ui.flush();
            check(h.session.view().order == null && !h.session.canContinue(), "old account approved result is ignored");
            eq(null, h.store.load(A).orderId, "stale create cannot persist old response after account switch");
            h.roundTrip(); h.roundTrip(); h.roundTrip();
            h.transport.json("POST", "/api/order/create", order(ID2, "created"));
            h.transport.png(4);
            h.session.create(); h.roundTrip(); h.io.runNext();
            check(!aKey.equals(h.store.load(B).requestKey), "separate account receives separate idempotency key");
            eq(ID2, h.store.load(B).orderId, "new account keeps only its returned ID");
            h.queueBootstrap("[]"); h.session.enter(A); h.ui.flush();
            check(h.session.paymentImage() == null, "old account image completion cannot cross account scope");
            h.roundTrip(); h.roundTrip(); h.roundTrip();
            eq(aKey, h.store.load(A).requestKey, "switch back restores original unresolved create key");
            check(h.session.view().order == null && h.session.view().canCreate, "new owner's order never replaces old draft");
            h.assertSettled();
        }
    }

    private static void refreshedImageRejectsOldCompletion(boolean oldFailure) throws Exception {
        try (Harness h = new Harness(true)) {
            h.boot(A, "[]");
            h.transport.json("POST", "/api/order/create", order(ID, "created"));
            if (oldFailure) h.transport.failure("GET", imagePath(), new IOException("old image failure"));
            else h.transport.png(1);
            h.session.create(); h.roundTrip(); h.io.runNext();
            Runnable old = h.ui.takePost();
            h.transport.json("GET", "/api/order/status/" + ID, order(ID, "created"));
            h.transport.png(2);
            h.session.refresh();
            check(h.session.paymentImage() == null, "refresh clears prior image immediately");
            h.roundTrip(); h.roundTrip();
            eq((byte) 2, h.session.paymentImage()[8], "refresh loads replacement image from same path");
            old.run();
            eq((byte) 2, h.session.paymentImage()[8], "old " + (oldFailure ? "failure" : "success")
                    + " cannot overwrite replacement image with same account/order/path");
            eq("", h.session.imageMessage(), "old image failure cannot poison current image status");
            h.assertSettled();
        }
    }

    private static void refreshedImageCancelsQueuedDownload() throws Exception {
        try (Harness h = new Harness(true)) {
            h.boot(A, "[]");
            h.transport.json("POST", "/api/order/create", order(ID, "created"));
            h.session.create(); h.roundTrip();
            eq(1, h.io.pending(), "created order queues image IO");
            h.transport.json("GET", "/api/order/status/" + ID, order(ID, "created"));
            h.transport.png(3);
            h.session.refresh();
            eq(1, h.io.cancelled(), "refresh cancels old queued image future");
            h.io.runNext();
            h.roundTrip(); h.roundTrip();
            eq((byte) 3, h.session.paymentImage()[8], "only refreshed image download reaches transport");
            h.assertSettled();
        }
    }

    private static void imageFailureCanRefresh() throws Exception {
        try (Harness h = new Harness(true)) {
            h.boot(A, "[]");
            h.transport.json("POST", "/api/order/create", order(ID, "created"));
            h.transport.failure("GET", imagePath(), new IOException("synthetic private diagnostic"));
            h.session.create(); h.roundTrip(); h.roundTrip();
            check(h.session.paymentImage() == null, "failed image has no bytes");
            eq("赞赏码暂不可用，请刷新。", h.session.imageMessage(), "image error is fixed safe copy");
            h.transport.json("GET", "/api/order/status/" + ID, order(ID, "created"));
            h.transport.png(5);
            h.session.refresh(); h.roundTrip(); h.roundTrip();
            eq((byte) 5, h.session.paymentImage()[8], "explicit refresh retries failed image");
            eq("", h.session.imageMessage(), "successful refresh clears image failure");
            h.assertSettled();
        }
    }

    private static void foregroundPreservesPollingBudget() throws Exception {
        try (Harness h = new Harness(true)) {
            h.boot(A, "[" + order(ID, "review") + "]");
            long start = h.ui.now;
            int polls = 0;
            while (!h.ui.delayed.isEmpty()) {
                h.transport.json("GET", "/api/order/status/" + ID, order(ID, "review"));
                h.ui.advance(h.ui.nextDelay()); h.roundTrip();
                check(++polls <= ManualPaymentFlow.MAX_POLL_REQUESTS, "session polling is bounded");
            }
            check(polls > 1 && h.ui.now - start < ManualPaymentFlow.POLL_BUDGET_MILLIS, "backoff stops within time/request budget");
            h.session.foreground(false);
            h.ui.advance(ManualPaymentFlow.POLL_BUDGET_MILLIS);
            h.queueBootstrap("[" + order(ID, "review") + "]");
            h.session.foreground(true); h.roundTrip(); h.roundTrip(); h.roundTrip();
            eq(0, h.ui.delayed.size(), "foreground reload cannot extend exhausted order polling budget");
            h.transport.json("GET", "/api/order/status/" + ID, order(ID, "review"));
            h.session.refresh(); h.roundTrip();
            eq(2_000L, h.ui.nextDelay(), "explicit refresh may start a new bounded polling window");
            h.session.leave();
            eq(0, h.ui.delayed.size(), "leave removes restarted poll timer");
            h.assertSettled();
        }
    }

    private static void unsupportedContractMapsToLockedFlow() throws Exception {
        // Explicit negative regression: a fixed-price quote is unsupported even in a v2-shaped response.
        String unsupported = order(ID, "approved").replace("\"amount_minor\":null", "\"amount_minor\":123");
        byte[] envelope = ("{\"sandbox\":true,\"data\":" + unsupported + "}").getBytes(StandardCharsets.UTF_8);
        ManualOrderClient client = new ManualOrderClient("http://127.0.0.1:18080",
                request -> new ManualOrderClient.Response(200, "application/json", envelope));
        try {
            client.status(ID);
            throw new AssertionError("fixed-price response must be unsupported");
        } catch (ManualOrderClient.OrderException failure) {
            eq(ManualOrderClient.FailureKind.UNSUPPORTED_CONTRACT, failure.kind, "client identifies unsupported contract");
            eq(null, failure.errorCode, "unsupported local parsing failure has no wire error code");
        }
        try (Harness h = new Harness(true)) {
            h.boot(A, "[" + order(ID, "review") + "]");
            h.transport.json("GET", "/api/order/status/" + ID, order(ID, "approved"));
            h.session.refresh(); h.roundTrip();
            h.session.testDav(); h.roundTrip();
            check(h.session.canContinue(), "approved session starts with verified continuation");
            h.transport.json("GET", "/api/order/status/" + ID, unsupported);
            h.session.refresh(); h.roundTrip();
            eq(ManualPaymentFlow.Phase.ERROR, h.session.view().phase, "unsupported parsing kind locks session flow");
            eq("赞赏服务版本不支持", h.session.view().message, "session maps failure kind despite null wire error code");
            check(!h.session.canContinue() && !h.session.view().canTestDav, "unsupported response revokes prior DAV success");
            h.session.create(); h.session.submit(); h.session.testDav();
            eq(0, h.io.pending(), "unsupported contract actions enqueue no requests");
            eq(0, h.ui.delayed.size(), "unsupported contract leaves no poll timer");
            h.queueBootstrap("[" + order(ID, "approved") + "]");
            h.session.refresh(); h.roundTrip();
            eq("/api/order/plans", h.transport.last().url().encodedPath(), "explicit refresh starts at plans");
            check(!h.session.view().canTestDav, "plans cannot restore approved DAV gate");
            h.roundTrip();
            eq("/api/order/mine", h.transport.last().url().encodedPath(), "refresh reloads orders");
            check(!h.session.view().canTestDav, "orders cannot skip fresh entitlement");
            h.roundTrip();
            eq("/api/order/entitlement", h.transport.last().url().encodedPath(), "refresh reloads entitlement");
            check(h.session.view().canTestDav && !h.session.canContinue(), "full bootstrap restores only fresh DAV eligibility");
            h.session.testDav(); h.roundTrip();
            check(h.session.canContinue(), "fresh DAV test restores session continuation");
            h.assertSettled();
        }
    }

    private static void rejectedExecutorFailsClosed() throws Exception {
        try (Harness h = new Harness(true)) {
            h.io.shutdown();
            h.session.enter(A);
            check(!h.session.view().busy && !h.session.canContinue(), "rejected bootstrap fails closed without stuck busy");
            eq(ManualPaymentFlow.Phase.ERROR, h.session.view().phase, "rejected executor exposes error state");
            eq("暂时无法查询，请重试", h.session.view().message, "rejected executor has short safe error");
            eq(0, h.transport.requests.size(), "rejected bootstrap never reaches transport");
            h.assertSettled();
        }
        try (Harness h = new Harness(true)) {
            h.boot(A, "[]");
            h.io.shutdown();
            h.session.create();
            check(!h.session.view().busy && h.session.view().outcomeUnknown, "rejected create retains uncertain draft without stuck busy");
            String key = h.store.load(A).requestKey;
            h.session.create();
            eq(key, h.store.load(A).requestKey, "retry after executor rejection preserves create key");
            eq(3, h.transport.requests.size(), "rejected create makes zero additional transport calls");
            h.assertSettled();
        }
        try (Harness h = new Harness(true)) {
            h.boot(A, "[]");
            h.transport.json("POST", "/api/order/create", order(ID, "created"));
            h.session.create(); h.io.runNext();
            h.io.shutdown(); h.ui.flush();
            check(!h.session.view().busy && h.session.view().canSubmit, "rejected image executor preserves accepted order state");
            check(h.session.paymentImage() == null, "rejected image executor never fabricates bytes");
            eq("赞赏码暂不可用，请刷新。", h.session.imageMessage(), "rejected image executor has short safe error");
            h.assertSettled();
        }
    }

    private static void cancellationConfirmationIsScopeBound() throws Exception {
        try (Harness h = new Harness(true)) {
            h.boot(A, "[" + order(ID, "review") + "]");
            Runnable staleAccount = h.session.cancellationConfirmation();
            h.boot(B, "[" + order(ID, "review") + "]");
            staleAccount.run(); staleAccount.run();
            eq(0, h.io.pending(), "old confirmation cannot cancel same order ID under another account");
            eq(6, h.transport.requests.size(), "stale account confirmation makes no transport call");
            Runnable staleAttachment = h.session.cancellationConfirmation();
            h.session.leave();
            h.boot(B, "[" + order(ID, "review") + "]");
            staleAttachment.run();
            eq(0, h.io.pending(), "old page confirmation cannot cancel after leave and return");
            h.transport.json("POST", "/api/order/cancel", order(ID, "cancelled"));
            Runnable current = h.session.cancellationConfirmation();
            current.run(); current.run();
            eq(1, h.io.pending(), "current confirmation dispatches one cancel despite repeated callback");
            h.roundTrip();
            eq(ID, h.transport.field(h.transport.last(), "order_id"), "confirmed cancellation targets captured current order");
            eq(ManualPaymentFlow.Phase.CANCELLED, h.session.view().phase, "matching confirmation accepts returned cancellation");
            check(!h.session.canContinue(), "cancelled order cannot unlock Next");
            eq(0, h.ui.delayed.size(), "cancellation removes review poll timer");
            h.assertSettled();
        }
    }

    private static void copyUsesDonationValidity() {
        String validity = ManualPaymentCopy.validity();
        eq("确认赞赏后一年有效，未到期顺延一年。", validity, "short donation validity copy");
        check(!validity.contains("年费") && !validity.contains("价格") && !validity.contains("CNY")
                && !validity.contains("¥") && !validity.contains("￥"), "validity copy has no fixed price or annual fee");
    }

    private static final class MemoryStore implements ManualPaymentFlow.DraftStore {
        final Map<String, ManualPaymentFlow.Draft> drafts = new HashMap<>();
        int writes;
        @Override public ManualPaymentFlow.Draft load(String scope) { return drafts.get(scope); }
        @Override public void save(ManualPaymentFlow.Draft draft) { writes++; drafts.put(draft.accountScope, draft); }
    }

    private static final class QueuedIo extends AbstractExecutorService {
        final Deque<Runnable> queued = new ArrayDeque<>();
        boolean shutdown;
        @Override public void execute(Runnable task) {
            if (shutdown) throw new RejectedExecutionException("closed fixture executor");
            queued.addLast(task);
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> abandoned = new ArrayList<>(queued);
            for (Runnable task : queued) if (task instanceof Future<?>) ((Future<?>) task).cancel(true);
            queued.clear();
            return abandoned;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queued.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        int pending() {
            int count = 0;
            for (Runnable task : queued) if (!(task instanceof Future<?>) || !((Future<?>) task).isCancelled()) count++;
            return count;
        }
        int cancelled() {
            int count = 0;
            for (Runnable task : queued) if (task instanceof Future<?> && ((Future<?>) task).isCancelled()) count++;
            return count;
        }
        void runNext() throws Exception {
            check(!queued.isEmpty(), "expected queued IO task");
            Runnable next = queued.removeFirst();
            next.run();
            if (next instanceof Future<?> && !((Future<?>) next).isCancelled()) {
                try { ((Future<?>) next).get(); }
                catch (ExecutionException failure) { throw new AssertionError("fixture IO task failed", failure.getCause()); }
            }
        }
        void runAll() throws Exception { while (!queued.isEmpty()) runNext(); }
    }

    private static final class FakeUi implements ManualPaymentSession.Ui {
        private static final class Delayed {
            final long due;
            final Runnable action;
            Delayed(long due, Runnable action) { this.due = due; this.action = action; }
        }
        final Deque<Runnable> posted = new ArrayDeque<>();
        final List<Delayed> delayed = new ArrayList<>();
        long now;
        @Override public long nowMillis() { return now; }
        @Override public void post(Runnable action) { posted.addLast(action); }
        @Override public void after(long delay, Runnable action) {
            check(delay >= 0, "nonnegative UI delay");
            delayed.add(new Delayed(now + delay, action));
        }
        @Override public void remove(Runnable action) { delayed.removeIf(value -> value.action == action); }
        Runnable takePost() { check(!posted.isEmpty(), "expected posted UI completion"); return posted.removeFirst(); }
        void flush() { while (!posted.isEmpty()) takePost().run(); }
        long nextDelay() {
            check(!delayed.isEmpty(), "expected delayed poll");
            return delayed.stream().mapToLong(value -> value.due).min().orElseThrow() - now;
        }
        void advance(long delta) {
            check(delta >= 0, "monotonic fixture clock");
            now += delta;
            List<Delayed> due = new ArrayList<>();
            for (Delayed value : delayed) if (value.due <= now) due.add(value);
            delayed.removeAll(due);
            for (Delayed value : due) value.action.run();
        }
    }

    private static final class ScriptedTransport implements ManualOrderClient.Transport {
        private static final class Step {
            final String method, path;
            final ManualOrderClient.Response response;
            final IOException failure;
            Step(String method, String path, ManualOrderClient.Response response, IOException failure) {
                this.method = method; this.path = path; this.response = response; this.failure = failure;
            }
        }
        final Deque<Step> steps = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();
        final Map<Request, JsonObject> bodies = new IdentityHashMap<>();
        int cancels;
        void json(String method, String path, String data) {
            steps.addLast(new Step(method, path, new ManualOrderClient.Response(200, "application/json",
                    ("{\"sandbox\":true,\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8)), null));
        }
        void png(int marker) {
            steps.addLast(new Step("GET", imagePath(), new ManualOrderClient.Response(200, "image/png",
                    new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, (byte) marker}), null));
        }
        void failure(String method, String path, IOException failure) { steps.addLast(new Step(method, path, null, failure)); }
        @Override public ManualOrderClient.Response execute(Request request) throws IOException {
            requests.add(request);
            if (request.body() != null) {
                Buffer buffer = new Buffer(); request.body().writeTo(buffer);
                bodies.put(request, (JsonObject) Json.Default.parseToJsonElement(buffer.readUtf8()));
            }
            check(!steps.isEmpty(), "unexpected transport request " + request.method() + " " + request.url().encodedPath());
            Step step = steps.removeFirst();
            eq(step.method, request.method(), "transport method");
            eq(step.path, request.url().encodedPath(), "transport path");
            eq(null, request.header("Authorization"), "host fixtures never attach credentials");
            if (step.failure != null) throw step.failure;
            return step.response;
        }
        @Override public void cancelPending() { cancels++; }
        Request last() { return requests.get(requests.size() - 1); }
        String field(Request request, String key) {
            check(bodies.containsKey(request), "expected captured POST body");
            return ((JsonPrimitive) bodies.get(request).get(key)).getContent();
        }
    }

    private static final class FakeDav implements ManualPaymentSession.DavVerifier {
        int calls, failures;
        @Override public void verify() throws IOException {
            calls++;
            if (failures > 0) { failures--; throw new IOException("synthetic private verifier failure"); }
        }
    }

    private static final class Harness implements AutoCloseable {
        final MemoryStore store = new MemoryStore();
        final QueuedIo io = new QueuedIo();
        final FakeUi ui = new FakeUi();
        final ScriptedTransport transport = new ScriptedTransport();
        final FakeDav dav = new FakeDav();
        final ManualPaymentSession session;
        int changes;
        Harness(boolean enabled) {
            ManualPaymentFlow flow = enabled ? new ManualPaymentFlow(true, store) : new ManualPaymentFlow(store);
            session = new ManualPaymentSession(flow, new ManualOrderClient("http://127.0.0.1:18080", transport), io, ui, dav);
            session.listener(() -> changes++);
        }
        void queueBootstrap(String mine) { queueBootstrap(mine, false); }
        void queueBootstrap(String mine, boolean active) {
            transport.json("GET", "/api/order/plans", plans());
            transport.json("GET", "/api/order/mine", mine);
            transport.json("GET", "/api/order/entitlement", "{\"source\":\"sandbox_ledger\",\"expires_ms\":"
                    + (active ? "999999999" : "null") + ",\"active\":" + active + "}");
        }
        void boot(String scope, String mine) throws Exception {
            queueBootstrap(mine); session.enter(scope); roundTrip(); roundTrip(); roundTrip();
        }
        void roundTrip() throws Exception { io.runNext(); ui.flush(); }
        void assertSettled() {
            eq(0, io.pending(), "no unexpected queued IO");
            eq(0, ui.posted.size(), "no unprocessed UI callback");
            eq(0, transport.steps.size(), "all scripted transport responses consumed");
        }
        @Override public void close() { session.close(); }
    }

    private static String imagePath() { return "/api/order/payment-image/" + PLAN; }
    private static String common() {
        return "\"currency\":null,\"term\":\"calendar_year\",\"amount_minor\":null"
                + ",\"days\":0,\"quota_bytes\":1024,\"upload_kbps\":1,\"download_kbps\":1";
    }
    private static String plans() { return "[" + plan(PLAN) + "," + plan("fixture_b") + "]"; }
    private static String plan(String id) {
        return "{\"id\":\"" + id + "\"," + common() + ",\"display_name\":\"Synthetic session fixture\","
                + "\"payment_instructions\":\"No payment\",\"payment_image_path\":\"/api/order/payment-image/" + id
                + "\",\"expiry_policy\":\"sandbox_upload_only\",\"payment_mode\":\"donation\"}";
    }
    private static String order(String id, String state) {
        boolean reserved = state.equals("approved") || state.equals("applying");
        return "{\"id\":\"" + id + "\",\"plan_id\":\"" + PLAN + "\"," + common()
                + ",\"remark_code\":\"0007\",\"created_ms\":1,\"target_ms\":"
                + (reserved ? "100" : "null")
                + ",\"actual_amount_minor\":" + (reserved ? "123" : "null")
                + ",\"actual_currency\":" + (reserved ? "\"CNY\"" : "null")
                + ",\"state\":\"" + state + "\"}";
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void eq(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + " actual=" + actual);
    }
}
