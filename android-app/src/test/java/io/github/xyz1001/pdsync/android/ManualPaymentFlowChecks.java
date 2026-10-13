package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Host-only scripted contract data. Never opens sockets or uses an account/device. */
public final class ManualPaymentFlowChecks {
    private static final String A = "synthetic-account-a", B = "synthetic-account-b";
    private static final String ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", ID2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static int checks;

    private static final class MemoryStore implements ManualPaymentFlow.DraftStore {
        final Map<String, ManualPaymentFlow.Draft> values = new HashMap<>();
        boolean failRead, failWrite;
        int writes;
        @Override public ManualPaymentFlow.Draft load(String scope) throws IOException {
            if (failRead) throw new IOException("synthetic read failure");
            return values.get(scope);
        }
        @Override public void save(ManualPaymentFlow.Draft value) throws IOException {
            if (failWrite) throw new IOException("synthetic write failure");
            writes++;
            values.put(value.accountScope, value);
        }
    }

    public static void main(String[] args) throws Exception {
        disabledAndBootstrap();
        durableSingleFlightAndUnknownCreate();
        staleCancellationAndAccountIsolation();
        restoreMineAndPlanBinding();
        stateAndDavGates();
        activeEntitlementNeedsDav();
        boundedForegroundPolling();
        malformedCallbacksAndStorageFailures();
        atomicDraftFile();
        System.out.println("ManualPaymentFlowChecks: " + checks + " checks passed (host-only; no HTTP/device/account)");
    }

    private static void disabledAndBootstrap() throws Exception {
        MemoryStore store = new MemoryStore();
        ManualPaymentFlow disabled = new ManualPaymentFlow(store);
        eq(null, disabled.enter(A, 0), "default disabled emits no ticket");
        eq(ManualPaymentFlow.Phase.DISABLED, disabled.snapshot().phase, "disabled phase");
        check(!disabled.snapshot().sandbox && !disabled.snapshot().canCreate, "disabled not ready");
        eq(null, disabled.create(), "disabled cannot create");
        eq(0, store.writes, "disabled never writes drafts");
        ManualPaymentFlow f = new ManualPaymentFlow(true, store);
        ManualPaymentFlow.Ticket p = f.enter(A, 0);
        action(p, ManualPaymentFlow.Action.LOAD_PLANS);
        busyRejectsAll(f, 0);
        ManualPaymentFlow.Ticket m = f.acceptPlans(p, plans());
        action(m, ManualPaymentFlow.Action.LOAD_MINE);
        check(!f.snapshot().canCreate, "plans alone not sufficient");
        eq(null, f.acceptPlans(p, plans()), "duplicate plans cannot issue another mine");
        ManualPaymentFlow.Ticket e = f.acceptMine(m, Collections.emptyList());
        action(e, ManualPaymentFlow.Action.LOAD_ENTITLEMENT);
        check(!f.snapshot().canCreate, "mine alone not sufficient");
        eq(null, f.acceptEntitlement(e, entitlement()), "bootstrap complete");
        check(f.snapshot().sandbox && f.snapshot().canCreate, "explicit sandbox ready");
        eq("fixture_a", f.snapshot().selectedPlanId, "first server plan selected");
        check(!f.snapshot().canContinue && f.snapshot().entitlement.expiresMs == null,
                "empty sandbox ledger never enables Next");
        try { f.snapshot().plans.clear(); throw new AssertionError("mutable plan list"); }
        catch (UnsupportedOperationException expected) { checks++; }
        check(f.selectPlan("fixture_b"), "explicit plan choice");
        f.leave();
        boot(f, A, 10, Collections.emptyList());
        eq("fixture_b", f.snapshot().selectedPlanId, "Back and return preserve selection");
    }

    private static void durableSingleFlightAndUnknownCreate() throws Exception {
        MemoryStore store = new MemoryStore();
        ManualPaymentFlow f = new ManualPaymentFlow(true, store);
        boot(f, A, 0, Collections.emptyList());
        ManualPaymentFlow.Ticket create = f.create();
        action(create, ManualPaymentFlow.Action.CREATE);
        ManualPaymentFlow.Draft saved = store.values.get(A);
        eq(create.requestKey, saved.requestKey, "key saved before ticket returned");
        eq(A, saved.accountScope, "owner saved");
        eq("fixture_a", saved.planId, "plan saved");
        check(create.requestKey.matches("[A-Za-z0-9_-]{16,80}"), "stable idempotency key syntax");
        busyRejectsAll(f, 1);
        eq(1, store.writes, "repeated create cannot write a new key");
        check(!f.selectPlan("fixture_b"), "busy cannot change plan");
        check(f.fail(create, "TIMEOUT"), "unknown create accepted as failure");
        check(f.snapshot().outcomeUnknown && f.snapshot().canCreate, "unknown outcome can explicitly retry");
        check(!f.selectPlan("fixture_b"), "unknown create pins plan");
        ManualPaymentFlow.Ticket retry = f.create();
        eq(create.requestKey, retry.requestKey, "timeout retry reuses same key");
        f.leave();
        check(!f.acceptOrder(retry, order("created")), "left page rejects create response");
        boot(f, A, 20, Collections.emptyList());
        ManualPaymentFlow.Ticket returned = f.create();
        eq(create.requestKey, returned.requestKey, "return reuses uncertain key");
        ManualPaymentFlow restarted = new ManualPaymentFlow(true, store);
        boot(restarted, A, 30, Collections.emptyList());
        eq(create.requestKey, restarted.create().requestKey, "new model reuses persisted key");
    }

    private static void staleCancellationAndAccountIsolation() throws Exception {
        MemoryStore store = new MemoryStore();
        ManualPaymentFlow f = new ManualPaymentFlow(true, store);
        ManualPaymentFlow.Ticket stalePlans = f.enter(A, 0);
        ManualPaymentFlow.Ticket currentPlans = f.enter(B, 1);
        check(currentPlans.generation > stalePlans.generation, "account change increments generation");
        eq(null, f.acceptPlans(stalePlans, plans()), "old account plans rejected");
        check(!f.fail(stalePlans, "TIMEOUT"), "old account errors rejected");
        check(f.snapshot().plans.isEmpty() && f.snapshot().order == null, "account display cleared");
        finishBoot(f, currentPlans, Collections.emptyList());
        ManualPaymentFlow.Ticket bCreate = f.create();
        eq(B, bCreate.accountScope, "ticket bound to new owner");
        f.setForeground(false, 2);
        check(!f.isCurrent(bCreate) && !f.snapshot().busy, "background invalidates busy ticket");
        check(!f.acceptOrder(bCreate, order("created")), "background create response rejected");
        check(!f.fail(bCreate, "TIMEOUT"), "cancelled call error rejected");
        eq(ManualPaymentFlow.NO_POLL, f.nextDelayMillis(3), "background no polling");
        eq(null, f.refresh(3), "background cannot refresh");
        f.setForeground(true, 3);
        check(!f.snapshot().busy, "foreground does not secretly start request");
        ManualPaymentFlow.Ticket bRetry = f.create();
        eq(bCreate.requestKey, bRetry.requestKey, "foreground retry preserves key");
        f.leave();
        boot(f, A, 5, Collections.emptyList());
        ManualPaymentFlow.Ticket aCreate = f.create();
        check(!aCreate.requestKey.equals(bCreate.requestKey), "different owners never share draft keys");
        check(!f.acceptOrder(bRetry, order("approved")), "late approved from other owner rejected");
        check(!f.snapshot().canContinue && f.snapshot().order == null, "old owner never unlocks Next");
        f.leave();
        boot(f, B, 6, Collections.emptyList());
        eq(bCreate.requestKey, f.create().requestKey, "switching back recovers that owner's original key");
    }

    private static void restoreMineAndPlanBinding() throws Exception {
        MemoryStore store = new MemoryStore();
        ManualPaymentFlow f = new ManualPaymentFlow(true, store);
        boot(f, A, 0, Collections.singletonList(order("review")));
        eq(ID, f.snapshot().order.id, "mine recovers unknown order id");
        eq(ID, store.values.get(A).orderId, "recovered id durably saved");
        eq(null, store.values.get(A).requestKey, "no fabricated key for recovered order");
        check(!f.snapshot().canCreate && !f.selectPlan("fixture_b"), "pending pins existing plan");
        check(f.snapshot().paymentDisplayMatches, "matching snapshot can show fixture display");
        check(!ManualPaymentFlow.matches(plans().get(1), f.snapshot().order), "different plan display rejected");
        ManualOrderClient.Order differentPrice = orderRaw(orderJson(ID, "fixture_a", "review", 99));
        check(!ManualPaymentFlow.matches(plans().get(0), differentPrice), "changed price image display rejected");
        f.leave();
        boot(f, A, 10, Collections.singletonList(order("applying")));
        eq(ManualPaymentFlow.Phase.APPLYING, f.snapshot().phase, "pending survives page return");
        check(!f.snapshot().canCancel && !f.snapshot().canSubmit, "applying never replaced or redeclared");
        f.leave();
        ManualPaymentFlow.Ticket p = f.enter(A, 20);
        ManualPaymentFlow.Ticket m = f.acceptPlans(p, plans());
        ManualPaymentFlow.Ticket e = f.acceptMine(m, Collections.emptyList());
        ManualPaymentFlow.Ticket status = f.acceptEntitlement(e, entitlement());
        action(status, ManualPaymentFlow.Action.STATUS);
        eq(ID, status.orderId, "known missing mine row queried directly");
        check(!f.snapshot().canCreate, "missing known order cannot spawn replacement");
        check(f.acceptOrder(status, order("approved")), "known approved recovered");
        check(!f.snapshot().canContinue, "recovered approval still needs DAV");
    }

    private static void stateAndDavGates() throws Exception {
        MemoryStore store = new MemoryStore();
        ManualPaymentFlow f = new ManualPaymentFlow(true, store);
        boot(f, A, 0, Collections.emptyList());
        ManualPaymentFlow.Ticket create = f.create();
        check(f.acceptOrder(create, order("created")), "created accepted");
        eq(ManualPaymentFlow.Phase.CREATED, f.snapshot().phase, "created remains unpaid");
        check(f.snapshot().canSubmit && f.snapshot().canCancel && !f.snapshot().canContinue,
                "created actions cannot skip payment or DAV");
        eq(null, f.beginDavTest(), "created not approved");
        ManualPaymentFlow.Ticket submit = f.submit();
        busyRejectsAll(f, 1);
        check(f.acceptOrder(submit, order("review")), "declaring paid moves to review only");
        check(!f.snapshot().canSubmit && !f.snapshot().canContinue, "review not paid approval");
        ManualPaymentFlow.Ticket status = f.refresh(20);
        check(f.acceptOrder(status, order("applying")), "applying accepted");
        check(!f.snapshot().canCreate && !f.snapshot().canTestDav, "applying cannot create or test as approved");
        status = f.refresh(30);
        check(f.acceptOrder(status, order("approved")), "approved accepted");
        check(f.snapshot().canTestDav && !f.snapshot().canContinue, "approved is not DAV success");
        ManualPaymentFlow.Ticket dav = f.beginDavTest();
        action(dav, ManualPaymentFlow.Action.DAV_TEST);
        busyRejectsAll(f, 31);
        check(f.fail(dav, "TIMEOUT"), "DAV failure accepted");
        check(!f.snapshot().canContinue, "DAV failure blocks Next");
        dav = f.beginDavTest();
        check(f.acceptDavSuccess(dav), "explicit DAV success accepted");
        check(f.snapshot().canContinue && f.snapshot().phase == ManualPaymentFlow.Phase.READY,
                "only approved plus DAV success enables Next");
        check(!f.acceptDavSuccess(dav), "duplicate DAV completion rejected");
        f.invalidateDav();
        check(!f.snapshot().canContinue, "editing credentials invalidates Next");
        dav = f.beginDavTest();
        f.invalidateDav();
        check(!f.acceptDavSuccess(dav), "editing during DAV rejects old success");
        dav = f.beginDavTest();
        f.setForeground(false, 40);
        check(!f.acceptDavSuccess(dav) && !f.snapshot().canContinue, "background DAV cannot unlock Next");
        f.setForeground(true, 45);
        dav = f.beginDavTest();
        check(f.acceptDavSuccess(dav), "retest required after foreground");
        f.leave();
        check(!f.snapshot().canContinue, "leaving revokes continue");
        boot(f, A, 50, Collections.singletonList(order("approved")));
        check(!f.snapshot().canContinue, "page return needs fresh DAV");
        ManualPaymentFlow terminal = new ManualPaymentFlow(true, new MemoryStore());
        boot(terminal, A, 0, Collections.emptyList());
        ManualPaymentFlow.Ticket first = terminal.create();
        terminal.acceptOrder(first, order("created"));
        ManualPaymentFlow.Ticket cancel = terminal.cancel();
        check(terminal.acceptOrder(cancel, order("cancelled")), "explicit cancel terminal");
        check(terminal.selectPlan("fixture_b"), "terminal cancellation allows plan change");
        terminal.leave();
        boot(terminal, A, 100, Collections.singletonList(order("cancelled")));
        eq("fixture_b", terminal.snapshot().selectedPlanId, "Back preserves a newly chosen plan after terminal order");
        check(!first.requestKey.equals(terminal.create().requestKey), "new purchase after cancellation gets new key");
    }

    private static void activeEntitlementNeedsDav() throws Exception {
        ManualPaymentFlow f = new ManualPaymentFlow(true, new MemoryStore());
        ManualPaymentFlow.Ticket plans = f.enter(A, 0);
        ManualPaymentFlow.Ticket mine = f.acceptPlans(plans, plans());
        ManualPaymentFlow.Ticket ent = f.acceptMine(mine, Collections.emptyList());
        ManualOrderClient.Entitlement active = client("{\"source\":\"sandbox_ledger\",\"expires_ms\":1000,\"active\":true}").entitlement();
        f.acceptEntitlement(ent, active);
        check(f.snapshot().order == null && f.snapshot().canTestDav, "active sandbox ledger does not force purchase");
        check(!f.snapshot().canContinue, "active entitlement alone is not DAV success");
        ManualPaymentFlow.Ticket dav = f.beginDavTest();
        eq(null, dav.orderId, "existing entitlement DAV needs no fabricated order");
        check(f.acceptDavSuccess(dav) && f.snapshot().canContinue, "active entitlement plus explicit DAV unlocks Next");
        f.leave();
        boot(f, B, 1, Collections.emptyList());
        check(!f.snapshot().canTestDav && !f.snapshot().canContinue, "active entitlement cannot cross accounts");
        check(!f.acceptDavSuccess(dav), "old entitlement DAV result rejected");
    }

    private static void boundedForegroundPolling() throws Exception {
        ManualPaymentFlow f = new ManualPaymentFlow(true, new MemoryStore());
        boot(f, A, 0, Collections.singletonList(order("review")));
        long now = 0;
        eq(2000L, f.nextDelayMillis(now), "initial poll delay");
        eq(null, f.poll(1999), "no early poll");
        int calls = 0;
        while (true) {
            long delay = f.nextDelayMillis(now);
            if (delay == ManualPaymentFlow.NO_POLL) break;
            now += delay;
            ManualPaymentFlow.Ticket poll = f.poll(now);
            action(poll, ManualPaymentFlow.Action.STATUS);
            calls++;
            eq(ManualPaymentFlow.NO_POLL, f.nextDelayMillis(now), "busy never polls");
            eq(null, f.poll(now), "duplicate timer emits no request");
            check(f.acceptOrder(poll, order("review")), "poll response accepted");
            check(calls <= ManualPaymentFlow.MAX_POLL_REQUESTS, "request count bounded");
        }
        check(calls > 1 && now < ManualPaymentFlow.POLL_BUDGET_MILLIS, "backoff terminates within total window");
        eq(ManualPaymentFlow.NO_POLL, f.nextDelayMillis(500_000), "unchanged review stops at budget");
        ManualPaymentFlow.Ticket manual = f.refresh(500_000);
        f.acceptOrder(manual, order("applying"));
        eq(2000L, f.nextDelayMillis(500_000), "manual refresh resets budget");
        ManualPaymentFlow.Ticket poll = f.poll(502_000);
        f.setForeground(false, 502_001);
        check(!f.acceptOrder(poll, order("approved")), "background invalidates poll completion");
        eq(ManualPaymentFlow.NO_POLL, f.nextDelayMillis(502_001), "background never polls");
        f.setForeground(true, 700_000);
        eq(ManualPaymentFlow.NO_POLL, f.nextDelayMillis(700_000), "resume does not extend budget");
        manual = f.refresh(700_000);
        f.acceptOrder(manual, order("review"));
        f.leave();
        eq(ManualPaymentFlow.NO_POLL, f.nextDelayMillis(700_001), "left page never polls");
        boot(f, A, 800_000, Collections.singletonList(order("review")));
        eq(2000L, f.nextDelayMillis(800_000), "explicit page entry starts bounded window");
        f.setForeground(false, 800_100);
        f.setForeground(true, 950_000);
        finishBoot(f, f.resume(950_000), Collections.singletonList(order("review")));
        eq(ManualPaymentFlow.NO_POLL, f.nextDelayMillis(950_000), "resume reload does not extend exhausted budget");
        ManualPaymentFlow slowUser = new ManualPaymentFlow(true, new MemoryStore());
        boot(slowUser, A, 0, Collections.singletonList(order("created")));
        slowUser.acceptOrder(slowUser.submit(), order("review"));
        eq(2000L, slowUser.nextDelayMillis(800_000), "new review starts window after long payment page dwell");
    }

    private static void malformedCallbacksAndStorageFailures() throws Exception {
        MemoryStore store = new MemoryStore();
        ManualPaymentFlow f = new ManualPaymentFlow(true, store);
        boot(f, A, 0, Collections.emptyList());
        store.failWrite = true;
        eq(null, f.create(), "draft persistence failure emits zero create requests");
        check(!f.snapshot().canCreate && !f.snapshot().canContinue, "write failure fails closed");
        store.failWrite = false;
        boot(f, A, 1, Collections.emptyList());
        ManualPaymentFlow.Ticket create = f.create();
        check(!f.acceptOrder(create, orderRaw(orderJson(ID, "fixture_b", "created", 1))),
                "wrong plan create response rejected");
        check(f.snapshot().outcomeUnknown, "malformed response retains unknown outcome key");
        ManualPaymentFlow.Ticket retry = f.create();
        eq(create.requestKey, retry.requestKey, "malformed create response retains original key");
        f.acceptOrder(retry, order("created"));
        ManualPaymentFlow.Ticket status = f.refresh(2);
        check(!f.acceptOrder(status, orderRaw(orderJson(ID2, "fixture_a", "approved", 1))),
                "status response cannot substitute order identity");
        check(!f.snapshot().canContinue, "substituted order cannot unlock Next");
        f.leave();
        ManualPaymentFlow.Ticket p = f.enter(A, 3);
        ManualPaymentFlow.Ticket m = f.acceptPlans(p, plans());
        eq(null, f.acceptMine(m, Arrays.asList(order("review"), orderRaw(orderJson(ID2, "fixture_a", "created", 1)))),
                "multiple pending orders fail closed");
        check(!f.snapshot().canCreate, "invalid mine cannot create");
        store.failRead = true;
        eq(null, f.enter(A, 4), "read failure prevents all requests");
        check(!f.snapshot().canCreate, "unreadable old key never silently replaced");
        store.failRead = false;
        ManualPaymentFlow.Ticket recovery = f.refresh(5);
        action(recovery, ManualPaymentFlow.Action.LOAD_PLANS);
        check(!f.fail(status, "raw-password-never-display"), "old failure cannot replace new loading message");
        check(!f.snapshot().message.contains("password"), "raw diagnostics not displayed");
    }

    private static void atomicDraftFile() throws Exception {
        Path root = Files.createTempDirectory("pdsync-sandbox-drafts-");
        try {
            Path dir = root.resolve("private");
            ManualOrderDraftFile store = new ManualOrderDraftFile(dir.toFile());
            eq(null, store.load(A), "absent private draft reads empty");
            ManualPaymentFlow f = new ManualPaymentFlow(true, store);
            boot(f, A, 0, Collections.emptyList());
            ManualPaymentFlow.Ticket create = f.create();
            eq(create.requestKey, store.load(A).requestKey, "key exists on disk before CREATE");
            ManualPaymentFlow restarted = new ManualPaymentFlow(true, new ManualOrderDraftFile(dir.toFile()));
            boot(restarted, A, 1, Collections.emptyList());
            eq(create.requestKey, restarted.create().requestKey, "new file-store object preserves uncertain key");
            store.save(new ManualPaymentFlow.Draft(B, "fixture_b", "bbbbbbbbbbbbbbbb", ID2));
            eq(create.requestKey, store.load(A).requestKey, "second account never overwrites first");
            eq(ID2, store.load(B).orderId, "second account roundtrips");
            AtomicInteger fileCount = new AtomicInteger();
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                files.forEach(file -> { check(file.getFileName().toString().matches("sandbox-order-[a-f0-9]{64}\\.draft"),
                        "hashed filename contains no account/path input"); fileCount.incrementAndGet(); });
            }
            eq(2, fileCount.get(), "successful atomic write leaves no temp files");
            store.save(new ManualPaymentFlow.Draft("../path/scope", "fixture_a", "cccccccccccccccc", null));
            eq("cccccccccccccccc", store.load("../path/scope").requestKey, "scope cannot escape private directory");
            Path corrupt;
            try (java.util.stream.Stream<Path> files = Files.list(dir)) { corrupt = files.findFirst().orElseThrow(); }
            byte[] original = Files.readAllBytes(corrupt);
            Files.write(corrupt, new byte[]{1, 2, 3});
            boolean failed = false;
            for (String account : Arrays.asList(A, B, "../path/scope")) {
                try { store.load(account); } catch (IOException expected) { failed = true; }
            }
            check(failed, "corrupt draft cannot masquerade as empty ledger");
            check(Files.size(corrupt) == 3, "corrupt file preserved for diagnosis");
            Files.write(corrupt, original);
            Path link = root.resolve("link");
            Files.createSymbolicLink(link, dir);
            try { new ManualOrderDraftFile(link.toFile()).load(A); throw new AssertionError("symlink directory read"); }
            catch (IOException expected) { checks++; }
            try { new ManualOrderDraftFile(link.toFile()).save(new ManualPaymentFlow.Draft(A, "fixture_a", "dddddddddddddddd", null));
                throw new AssertionError("symlink directory write"); }
            catch (IOException expected) { checks++; }
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(file);
            }
        }
    }

    private static void busyRejectsAll(ManualPaymentFlow f, long now) {
        check(f.snapshot().busy, "busy visible");
        eq(null, f.create(), "busy duplicate create");
        eq(null, f.submit(), "busy duplicate submit");
        eq(null, f.cancel(), "busy duplicate cancel");
        eq(null, f.refresh(now), "busy duplicate refresh");
        eq(null, f.poll(now), "busy duplicate poll");
        eq(null, f.beginDavTest(), "busy duplicate DAV");
        check(!f.snapshot().canContinue, "busy cannot continue");
    }
    private static void boot(ManualPaymentFlow f, String scope, long now, List<ManualOrderClient.Order> mine) throws Exception {
        finishBoot(f, f.enter(scope, now), mine);
    }
    private static void finishBoot(ManualPaymentFlow f, ManualPaymentFlow.Ticket plans,
                                   List<ManualOrderClient.Order> mine) throws Exception {
        action(plans, ManualPaymentFlow.Action.LOAD_PLANS);
        ManualPaymentFlow.Ticket m = f.acceptPlans(plans, plans());
        action(m, ManualPaymentFlow.Action.LOAD_MINE);
        ManualPaymentFlow.Ticket e = f.acceptMine(m, mine);
        action(e, ManualPaymentFlow.Action.LOAD_ENTITLEMENT);
        eq(null, f.acceptEntitlement(e, entitlement()), "bootstrap ends without extra request");
    }
    private static List<ManualOrderClient.Plan> plans() throws Exception {
        return client("[" + planJson("fixture_a", 1) + "," + planJson("fixture_b", 2) + "]").plans();
    }
    private static ManualOrderClient.Entitlement entitlement() throws Exception {
        return client("{\"source\":\"sandbox_ledger\",\"expires_ms\":null,\"active\":false}").entitlement();
    }
    private static ManualOrderClient.Order order(String state) throws Exception {
        return orderRaw(orderJson(ID, "fixture_a", state, 1));
    }
    private static ManualOrderClient.Order orderRaw(String json) throws Exception { return client("[" + json + "]").mine().get(0); }
    private static ManualOrderClient client(String data) {
        byte[] bytes = ("{\"sandbox\":true,\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
        return new ManualOrderClient("http://127.0.0.1:18080", request -> new ManualOrderClient.Response(200, "application/json", bytes));
    }
    private static String common(long amount) {
        return "\"currency\":\"CNY\",\"term\":\"fixed_days\",\"amount_minor\":" + amount
                + ",\"days\":1,\"quota_bytes\":1024,\"upload_kbps\":1,\"download_kbps\":1";
    }
    private static String planJson(String id, long amount) {
        return "{\"id\":\"" + id + "\"," + common(amount) + ",\"display_name\":\"Synthetic fixture\","
                + "\"payment_instructions\":\"No payment\",\"payment_image_path\":\"/api/order/payment-image/" + id
                + "\",\"expiry_policy\":\"sandbox_ledger_only\"}";
    }
    private static String orderJson(String id, String plan, String state, long amount) {
        return "{\"id\":\"" + id + "\",\"plan_id\":\"" + plan + "\"," + common(amount)
                + ",\"remark_code\":\"1234\",\"created_ms\":1,\"target_ms\":"
                + (state.equals("approved") || state.equals("applying") ? "100" : "null")
                + ",\"state\":\"" + state + "\"}";
    }
    private static void action(ManualPaymentFlow.Ticket ticket, ManualPaymentFlow.Action action) {
        check(ticket != null && ticket.action == action, "expected action " + action);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void eq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + ": expected=" + expected + " actual=" + actual);
    }
}
