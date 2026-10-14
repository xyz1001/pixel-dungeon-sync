package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Deterministic host-only controller checks: no sockets, credentials, Android, or real payment. */
public final class ManualPaymentSessionChecks {
    private static final String USER_A = "Synthetic.A_7", USER_B = "synthetic_b";
    private static final String A = OfficialServiceRegistrationClient.DAV_URL + "\n" + USER_A;
    private static final String B = OfficialServiceRegistrationClient.DAV_URL + "\n" + USER_B;
    private static final String PLAN = "fixture_a";
    private static int checks;

    public static void main(String[] args) throws Exception {
        disabledMakesNoRequests();
        bootstrapImageEntitlementAndDav();
        singleFlightRefreshAndPolling();
        queuedWorkIsCancelled();
        staleAccountAndImageRepliesAreIgnored();
        staleEntitlementAndDavRepliesAreIgnored();
        imageFailureStillLoadsEntitlement();
        foregroundPreservesPollingBudget();
        unsupportedContractFailsClosed();
        rejectedExecutorFailsClosed();
        expiryIsOnlyServerConfirmed();
        authenticatedUsernameCopyIsExactAndExplicit();
        missingIdentityFailsClosed();
        usernameCallbacksAreIdentityAndGenerationBound();
        System.out.println("ManualPaymentSessionChecks: " + checks
                + " checks passed (GET-only queued host fixtures; no sockets/device/account/payment)");
    }
    private static void disabledMakesNoRequests() throws Exception {
        try (Harness h = new Harness(false)) {
            h.enter(A); h.session.refresh(); h.session.testDav();
            h.session.foreground(false); h.session.foreground(true); h.ui.advance(1_000_000);
            h.session.leave(); h.enter(B);
            eq(ManualPaymentFlow.Phase.DISABLED, h.session.view().phase, "production remains disabled");
            check(!h.session.canContinue(), "disabled Next");
            eq(0, h.io.pending(), "disabled no IO");
            eq(0, h.transport.requests.size(), "disabled no HTTP");
            eq(0, h.dav.calls, "disabled no DAV");
            eq(0, h.store.writes, "disabled never writes legacy draft");
            eq(0, h.ui.delayed.size(), "disabled no polls");
        }
    }
    private static void bootstrapImageEntitlementAndDav() throws Exception {
        try (Harness h = new Harness(true)) {
            h.queueBootstrap(259_200_000L, true, 1); h.enter(A);
            eq(0, h.transport.requests.size(), "enter queues IO");
            h.roundTrip();
            eq(1, h.io.pending(), "plans directly queue static image");
            eq(null, h.session.view().entitlement, "image needs no entitlement or order first");
            h.roundTrip();
            eq((byte) 1, h.session.paymentImage()[8], "static code displayed before entitlement response");
            byte[] copy = h.session.paymentImage(); copy[8] = 9;
            eq((byte) 1, h.session.paymentImage()[8], "defensive image snapshot");
            h.roundTrip();
            eq(Long.valueOf(259_200_000L), h.session.view().entitlement.expiresMs, "trial expiry exactly matches server");
            check(h.session.view().canTestDav && !h.session.canContinue(), "active trial still requires DAV");
            eq(0, h.store.writes, "read-only display never writes a legacy draft");
            h.dav.failures = 1;
            h.session.testDav(); h.session.testDav(); h.session.refresh();
            eq(1, h.io.pending(), "DAV and competing clicks remain single-flight");
            h.roundTrip();
            check(!h.session.canContinue(), "failed DAV cannot continue");
            eq("连接测试未通过", h.session.view().message, "DAV failure uses safe copy");
            h.session.testDav(); h.roundTrip();
            check(h.session.canContinue(), "confirmed active plus DAV enables Next");
            eq(0, h.ui.delayed.size(), "ready removes polling");
            eq(3, h.transport.requests.size(), "DAV never creates or queries orders");
            h.session.leave(); check(!h.session.canContinue(), "leave revokes Next");
            h.assertSettled();
        }
    }
    private static void singleFlightRefreshAndPolling() throws Exception {
        try (Harness h = new Harness(true)) {
            h.boot(A, null, false, 1);
            check(!h.session.view().canTestDav && !h.session.canContinue(), "no expiry means no access");
            h.queueBootstrap(1000L, true, 2);
            h.session.refresh(); h.session.refresh(); h.session.testDav();
            eq(null, h.session.paymentImage(), "refresh clears old image");
            eq(1, h.io.pending(), "repeated refresh creates one request");
            h.roundTrip(); h.session.refresh();
            eq(1, h.io.pending(), "refresh during image does not start another chain");
            h.roundTrip(); h.session.refresh();
            eq((byte) 2, h.session.paymentImage()[8], "ignored duplicate refresh does not clear current code");
            eq(1, h.io.pending(), "refresh during entitlement remains single-flight");
            h.roundTrip();
            eq(Long.valueOf(1000L), h.session.view().entitlement.expiresMs, "admin renewal observed only from server");
            check(!h.session.canContinue(), "renewal itself cannot unlock Next");
            eq(2_000L, h.ui.nextDelay(), "finite foreground polling starts");
            h.transport.json("GET", "/api/order/entitlement", entitlement(2000L, true));
            h.ui.advance(2000); h.roundTrip();
            eq(Long.valueOf(2000L), h.session.view().entitlement.expiresMs, "poll sees later server renewal");
            eq(4_000L, h.ui.nextDelay(), "poll backoff");
            h.assertSettled();
        }
    }
    private static void queuedWorkIsCancelled() throws Exception {
        try (Harness h = new Harness(true)) {
            h.enter(A); h.session.foreground(false);
            eq(1, h.io.cancelled(), "background cancels queued bootstrap");
            h.io.runAll(); h.ui.flush();
            eq(0, h.transport.requests.size(), "cancelled request never reaches transport");
            h.session.refresh(); h.session.testDav();
            eq(0, h.io.pending(), "background queues nothing");
            h.queueBootstrap(null, false, 1); h.session.foreground(true);
            h.roundTrip(); h.roundTrip(); h.roundTrip();
            h.ui.advance(2000); eq(1, h.io.pending(), "due poll queued");
            h.session.foreground(false); h.io.runAll(); h.ui.flush();
            eq(3, h.transport.requests.size(), "background cancels poll transport");
            eq(0, h.ui.delayed.size(), "background removes delayed poll");
            eq(null, h.session.paymentImage(), "background does not expose image");
            h.queueResume(null, false, 2); h.session.foreground(true);
            h.roundTrip(); h.roundTrip();
            h.session.leave(); h.ui.advance(100_000);
            eq(0, h.io.pending(), "leave removes all polls");
            check(h.transport.cancels >= 3, "lifecycle cancels transport");
            h.assertSettled();
        }
        Harness h = new Harness(true);
        h.enter(A); int changes = h.changes; h.session.close(); h.session.close();
        h.io.runAll(); h.ui.flush(); h.enter(B); h.session.foreground(true); h.session.refresh();
        eq(0, h.transport.requests.size(), "closed never revives requests");
        eq(changes, h.changes, "closed listener detached");
        check(h.io.isShutdown(), "owned executor closed");
    }
    private static void staleAccountAndImageRepliesAreIgnored() throws Exception {
        for (boolean failure : new boolean[] {false, true}) {
            try (Harness h = new Harness(true)) {
                h.transport.json("GET", "/api/order/plans", plans());
                if (failure) h.transport.failure("GET", imagePath(), new IOException("old image failure"));
                else h.transport.png(1);
                h.enter(A); h.roundTrip(); h.io.runNext();
                Runnable old = h.ui.takePost();
                h.boot(B, 333L, true, 2);
                old.run();
                eq((byte) 2, h.session.paymentImage()[8], "old image result cannot overwrite new account image");
                eq("", h.session.imageMessage(), "old error cannot poison new image status");
                eq(USER_B, h.session.authenticatedUsername(), "old callback cannot restore prior identity");
                h.assertSettled();
            }
            try (Harness h = new Harness(true)) {
                if (failure) h.transport.failure("GET", "/api/order/plans", new IOException("old plans failure"));
                else h.transport.json("GET", "/api/order/plans", plans());
                h.enter(A); h.io.runNext();
                h.queueBootstrap(null, false, 3); h.enter(B); h.ui.flush();
                check(h.session.view().plans.isEmpty() && h.session.view().busy, "old plans cannot alter new flow");
                h.roundTrip(); h.roundTrip(); h.roundTrip(); h.assertSettled();
            }
        }
        try (Harness h = new Harness(true)) {
            h.transport.json("GET", "/api/order/plans", plans()); h.transport.png(1);
            h.enter(A); h.roundTrip(); h.io.runNext(); Runnable old = h.ui.takePost();
            h.session.foreground(false);
            h.queueResume(null, false, 4); h.session.foreground(true); h.roundTrip(); h.roundTrip();
            old.run(); eq((byte) 4, h.session.paymentImage()[8], "same-account old image rejected across foreground generation");
            h.assertSettled();
        }
    }
    private static void staleEntitlementAndDavRepliesAreIgnored() throws Exception {
        try (Harness h = new Harness(true)) {
            h.queueBootstrap(999L, true, 1); h.enter(A); h.roundTrip(); h.roundTrip(); h.io.runNext();
            h.boot(B, null, false, 2); h.ui.flush();
            eq(null, h.session.view().entitlement.expiresMs, "old active expiry cannot cross account");
            check(!h.session.canContinue() && !h.session.view().canTestDav, "old entitlement cannot enable test");
            h.boot(A, 123L, true, 3); h.session.testDav(); h.io.runNext();
            h.session.leave(); h.ui.flush();
            check(!h.session.canContinue(), "late DAV success cannot revive departed page");
            eq(null, h.session.authenticatedUsername(), "late DAV cannot restore username");
            h.assertSettled();
        }
    }
    private static void imageFailureStillLoadsEntitlement() throws Exception {
        try (Harness h = new Harness(true)) {
            h.transport.json("GET", "/api/order/plans", plans());
            h.transport.failure("GET", imagePath(), new IOException("private fixture failure"));
            h.transport.json("GET", "/api/order/entitlement", entitlement(123L, true));
            h.enter(A); h.roundTrip(); h.roundTrip(); h.roundTrip();
            eq(null, h.session.paymentImage(), "failed image omitted");
            eq("赞赏码暂不可用，请刷新。", h.session.imageMessage(), "safe image error");
            check(h.session.view().canTestDav, "image failure cannot hide existing active entitlement");
            h.queueBootstrap(123L, true, 2); h.session.refresh(); h.roundTrip(); h.roundTrip(); h.roundTrip();
            eq((byte) 2, h.session.paymentImage()[8], "refresh recovers image");
            eq("", h.session.imageMessage(), "refresh clears image error");
            h.assertSettled();
        }
    }
    private static void foregroundPreservesPollingBudget() throws Exception {
        try (Harness h = new Harness(true)) {
            h.boot(A, null, false, 1); h.session.foreground(false); h.ui.advance(900_000);
            h.queueResume(123L, true, 2); h.session.foreground(true); h.roundTrip(); h.roundTrip();
            eq(0, h.ui.delayed.size(), "resume does not reset elapsed poll budget");
            h.session.foreground(true);
            eq(0, h.io.pending(), "duplicate foreground callback queues nothing");
            eq((byte) 2, h.session.paymentImage()[8], "duplicate foreground preserves image");
            h.queueBootstrap(123L, true, 3); h.session.refresh(); h.roundTrip(); h.roundTrip(); h.roundTrip();
            eq(2000L, h.ui.nextDelay(), "explicit refresh restarts bounded polling");
            h.assertSettled();
        }
    }
    private static void unsupportedContractFailsClosed() throws Exception {
        try (Harness h = new Harness(true)) {
            h.transport.json("GET", "/api/order/plans", plans().replace("calendar_year", "fixed_days"));
            h.enter(A); h.roundTrip();
            eq(ManualPaymentFlow.Phase.ERROR, h.session.view().phase, "unsupported contract is locked");
            check(!h.session.canContinue() && h.session.paymentImage() == null, "unsupported contract displays no payment image");
            eq(0, h.ui.delayed.size(), "unsupported contract does not retry automatically");
            h.queueBootstrap(321L, true, 1); h.session.refresh(); h.roundTrip(); h.roundTrip(); h.roundTrip();
            check(h.session.view().canTestDav && !h.session.canContinue(), "valid refreshed contract still needs DAV");
            h.assertSettled();
        }
    }
    private static void rejectedExecutorFailsClosed() throws Exception {
        try (Harness h = new Harness(true)) {
            h.io.shutdown(); h.enter(A);
            eq(ManualPaymentFlow.Phase.ERROR, h.session.view().phase, "rejected executor produces recoverable error");
            check(!h.session.canContinue() && !h.session.view().busy, "rejected executor does not strand busy");
            eq(0, h.transport.requests.size(), "rejection makes no HTTP request");
        }
    }
    private static void expiryIsOnlyServerConfirmed() throws Exception {
        java.util.TimeZone prior = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            eq("赞赏时请备注用户名", ManualPaymentCopy.usernameRemark(), "one short reminder");
            eq("到期时间：未获取", ManualPaymentCopy.expiry(null), "missing response never fabricates expiry");
            try (Harness h = new Harness(true)) {
                h.boot(A, null, false, 1);
                eq("到期时间：暂无", ManualPaymentCopy.expiry(h.session.view().entitlement), "null server expiry shown as absent");
                h.boot(A, 259_200_000L, true, 2);
                eq("到期时间：1970-01-04 00:00", ManualPaymentCopy.expiry(h.session.view().entitlement), "trial date only formats server milliseconds");
                h.boot(A, 259_200_000L, false, 3);
                eq("到期时间：1970-01-04 00:00", ManualPaymentCopy.expiry(h.session.view().entitlement), "inactive expiry not locally extended");
            }
        } finally { java.util.TimeZone.setDefault(prior); }
    }
    private static void authenticatedUsernameCopyIsExactAndExplicit() throws Exception {
        try (Harness h = new Harness(true)) {
            h.boot(A, null, false, 1);
            eq(USER_A, h.session.authenticatedUsername(), "username is authenticated exact case");
            List<String> copied = new ArrayList<>(); Runnable copy = h.session.usernameCopyAction(copied::add);
            check(copied.isEmpty(), "render never writes clipboard"); copy.run();
            eq(List.of(USER_A), copied, "explicit copy writes username only");
            eq(3, h.transport.requests.size(), "copy makes no HTTP request"); h.assertSettled();
        }
    }
    private static void missingIdentityFailsClosed() throws Exception {
        try (Harness h = new Harness(true)) {
            List<String> copied = new ArrayList<>(); Runnable absent = h.session.usernameCopyAction(copied::add);
            h.session.enter(null); h.session.refresh(); h.session.testDav(); absent.run();
            eq(null, h.session.authenticatedUsername(), "missing identity hidden");
            eq(0, h.io.pending(), "missing identity no actions");
            h.boot(A, null, false, 1); absent.run(); check(copied.isEmpty(), "absent callback cannot copy later identity");
            h.session.enter(null); h.session.foreground(true);
            eq(null, h.session.authenticatedUsername(), "revocation cannot be reversed by foreground");
            eq(null, h.session.paymentImage(), "revocation clears code"); h.assertSettled();
        }
        try (Harness h = new Harness(false)) {
            h.enter(A); List<String> copied = new ArrayList<>(); h.session.usernameCopyAction(copied::add).run();
            eq(null, h.session.authenticatedUsername(), "production-disabled identity hidden");
            check(copied.isEmpty(), "production-disabled cannot copy");
        }
    }
    private static void usernameCallbacksAreIdentityAndGenerationBound() throws Exception {
        try (Harness h = new Harness(true)) {
            List<String> copied = new ArrayList<>();
            h.boot(A, null, false, 1); Runnable oldAccount = h.session.usernameCopyAction(copied::add);
            h.boot(B, null, false, 2); oldAccount.run(); check(copied.isEmpty(), "account switch invalidates callback");
            h.session.usernameCopyAction(copied::add).run(); eq(List.of(USER_B), copied, "new account copies exact username");
            Runnable oldPage = h.session.usernameCopyAction(copied::add); h.session.leave(); oldPage.run();
            h.boot(B, null, false, 3); oldPage.run(); eq(1, copied.size(), "same-account reentry does not revive callback");
            Runnable oldForeground = h.session.usernameCopyAction(copied::add); h.session.foreground(false); oldForeground.run();
            eq(null, h.session.authenticatedUsername(), "background hides username");
            h.queueResume(null, false, 4); h.session.foreground(true); h.roundTrip(); h.roundTrip(); oldForeground.run();
            eq(1, copied.size(), "foreground does not revive old callback");
            Runnable oldLogin = h.session.usernameCopyAction(copied::add); h.boot(B, null, false, 5); oldLogin.run();
            eq(1, copied.size(), "replacement login invalidates callback");
            Runnable beforeClose = h.session.usernameCopyAction(copied::add); h.session.close(); beforeClose.run();
            eq(null, h.session.authenticatedUsername(), "close clears identity"); eq(1, copied.size(), "close invalidates clipboard callback");
            h.assertSettled();
        }
    }
    /** Authentication response fixture only; no socket, real account or registration is used. */
    private static ManualPaymentSession.AuthenticatedIdentity authenticatedIdentity(String scope) throws Exception {
        String username = A.equals(scope) ? USER_A : B.equals(scope) ? USER_B : null;
        if (username == null) throw new AssertionError("Unknown fixture account scope");
        OfficialServiceRegistrationClient client = new OfficialServiceRegistrationClient(request -> {
            String json = "{\"code\":200,\"message\":\"success\",\"data\":{"
                    + "\"webdav_url\":\"" + OfficialServiceRegistrationClient.DAV_URL + "\",\"username\":\"" + username
                    + "\",\"quota_mb\":0,\"quota_bytes\":0,\"upload_bandwidth_kb\":0,\"download_bandwidth_kb\":0}}";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(ResponseBody.create(json, MediaType.get("application/json"))).build();
        });
        return ManualPaymentSession.AuthenticatedIdentity.fromOfficialAccount(
                client.login(username, "synthetic-password-never-secret"));
    }

    private static final class MemoryStore implements ManualPaymentFlow.DraftStore {
        final Map<String, ManualPaymentFlow.Draft> drafts = new HashMap<>();
        int reads, writes;
        @Override public ManualPaymentFlow.Draft load(String scope) { reads++; return drafts.get(scope); }
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
            eq("GET", request.method(), "new appreciation flow only sends GET");
            check(Set.of("/api/order/plans", imagePath(), "/api/order/entitlement")
                    .contains(request.url().encodedPath()), "new flow cannot query or mutate orders");
            eq(null, request.body(), "read-only request has no body");
            check(!steps.isEmpty(), "unexpected transport request " + request.method() + " " + request.url().encodedPath());
            Step step = steps.removeFirst();
            eq(step.method, request.method(), "transport method");
            eq(step.path, request.url().encodedPath(), "transport path");
            eq(null, request.header("Authorization"), "host fixtures never attach credentials");
            if (step.failure != null) throw step.failure;
            return step.response;
        }
        @Override public void cancelPending() { cancels++; }
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
        void enter(String scope) throws Exception { session.enter(authenticatedIdentity(scope)); }
        void queueBootstrap(Long expiry, boolean active, int marker) {
            transport.json("GET", "/api/order/plans", plans());
            queueResume(expiry, active, marker);
        }
        void queueResume(Long expiry, boolean active, int marker) {
            transport.png(marker);
            transport.json("GET", "/api/order/entitlement", entitlement(expiry, active));
        }
        void boot(String scope, Long expiry, boolean active, int marker) throws Exception {
            queueBootstrap(expiry, active, marker); enter(scope); roundTrip(); roundTrip(); roundTrip();
        }
        void roundTrip() throws Exception { io.runNext(); ui.flush(); }
        void assertSettled() {
            eq(0, store.reads, "new flow never reads legacy drafts");
            eq(0, store.writes, "new flow never writes legacy drafts");
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
    private static String entitlement(Long expiry, boolean active) {
        return "{\"source\":\"sandbox_ledger\",\"expires_ms\":" + expiry + ",\"active\":" + active + "}";
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void eq(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + " actual=" + actual);
    }
}
