package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Read-only sandbox flow, scripted values only; no sockets or real accounts. */
public final class ManualPaymentFlowChecks {
    private static final String A = "synthetic-account-a", B = "synthetic-account-b";
    private static int checks;
    private static final class UntouchedStore implements ManualPaymentFlow.DraftStore {
        @Override public ManualPaymentFlow.Draft load(String scope) { throw new AssertionError("Legacy draft read"); }
        @Override public void save(ManualPaymentFlow.Draft value) { throw new AssertionError("Legacy draft write"); }
    }
    public static void main(String[] args) throws Exception {
        disabledAndReadOnlyBootstrap();
        entitlementAndDavGates();
        staleResponsesAndAccountIsolation();
        boundedForegroundPolling();
        malformedAndUnsupportedResponses();
        legacyDraftsRemainUntouched();
        System.out.println("ManualPaymentFlowChecks: " + checks + " checks passed (read-only host fixtures)");
    }
    private static void disabledAndReadOnlyBootstrap() throws Exception {
        ManualPaymentFlow disabled = new ManualPaymentFlow(new UntouchedStore());
        eq(null, disabled.enter(A, 0), "default emits no requests");
        eq(ManualPaymentFlow.Phase.DISABLED, disabled.snapshot().phase, "default disabled");
        eq(null, disabled.refresh(1), "disabled refresh blocked");
        eq(null, disabled.beginDavTest(), "disabled DAV blocked");
        eq(ManualPaymentFlow.NO_POLL, disabled.nextDelayMillis(2000), "disabled does not poll");
        ManualPaymentFlow f = new ManualPaymentFlow(true, new UntouchedStore());
        ManualPaymentFlow.Ticket p = f.enter(A, 0);
        action(p, ManualPaymentFlow.Action.LOAD_PLANS); busyRejects(f);
        ManualPaymentFlow.Ticket i = f.acceptPlans(p, plans());
        action(i, ManualPaymentFlow.Action.LOAD_IMAGE); busyRejects(f);
        eq("fixture_a", i.plan.id, "static image uses first server plan directly");
        eq(null, f.acceptPlans(p, plans()), "duplicate plan response ignored");
        ManualPaymentFlow.Ticket e = f.imageFinished(i);
        action(e, ManualPaymentFlow.Action.LOAD_ENTITLEMENT); busyRejects(f);
        f.acceptEntitlement(e, entitlement(null, false));
        check(!f.snapshot().canContinue && !f.snapshot().canTestDav, "no entitlement cannot pass DAV gate");
        eq(null, f.snapshot().entitlement.expiresMs, "no locally fabricated expiry");
        eq("", f.snapshot().message, "no routine order narrative");
        try { f.snapshot().plans.clear(); throw new AssertionError("mutable plans"); }
        catch (UnsupportedOperationException expected) { checks++; }
        f.leave();
        check(f.snapshot().plans.isEmpty() && f.snapshot().entitlement == null, "leave clears display");
    }
    private static void entitlementAndDavGates() throws Exception {
        ManualPaymentFlow f = new ManualPaymentFlow(true);
        boot(f, A, 0, 259_200_000L, true);
        eq(Long.valueOf(259_200_000L), f.snapshot().entitlement.expiresMs, "trial uses exact server expiry");
        check(f.snapshot().canTestDav && !f.snapshot().canContinue, "active trial needs DAV");
        ManualPaymentFlow.Ticket d = f.beginDavTest(); busyRejects(f);
        f.fail(d, "raw-secret-fixture");
        eq("连接测试未通过", f.snapshot().message, "failure never exposes raw input");
        check(!f.snapshot().canContinue, "failed DAV blocks Next");
        d = f.beginDavTest(); check(f.acceptDavSuccess(d), "successful DAV accepted");
        check(f.snapshot().canContinue, "active and DAV success enable Next");
        eq(ManualPaymentFlow.NO_POLL, f.nextDelayMillis(2000), "ready no longer polls");
        f.invalidateDav(); check(!f.snapshot().canContinue, "changed DAV inputs revoke proof");
        d = f.beginDavTest(); f.invalidateDav();
        check(!f.acceptDavSuccess(d), "old verifier cannot restore proof");
        finish(f, f.refresh(10), 123_456_789_123L, true);
        eq(Long.valueOf(123_456_789_123L), f.snapshot().entitlement.expiresMs, "renewal displays exact server expiry");
        check(f.snapshot().canTestDav && !f.snapshot().canContinue, "renewal never fabricates DAV success");
        d = f.beginDavTest(); f.acceptDavSuccess(d);
        finish(f, f.refresh(20), 123_456_789_123L, false);
        check(!f.snapshot().canTestDav && !f.snapshot().canContinue, "server inactive overrides previous success");
        eq(null, f.beginDavTest(), "inactive cannot start DAV");
    }
    private static void staleResponsesAndAccountIsolation() throws Exception {
        ManualPaymentFlow f = new ManualPaymentFlow(true);
        ManualPaymentFlow.Ticket old = f.enter(A, 0), current = f.enter(B, 1);
        eq(null, f.acceptPlans(old, plans()), "old account plans rejected");
        check(!f.fail(old, "TIMEOUT"), "old account failure rejected");
        check(f.snapshot().plans.isEmpty(), "new account starts empty");
        ManualPaymentFlow.Ticket image = f.acceptPlans(current, plans());
        f.setForeground(false, 2);
        eq(null, f.imageFinished(image), "background rejects image completion");
        eq(null, f.refresh(3), "background blocks refresh");
        eq(ManualPaymentFlow.NO_POLL, f.nextDelayMillis(3), "background stops poll");
        f.setForeground(true, 4);
        image = f.resume(4); action(image, ManualPaymentFlow.Action.LOAD_IMAGE);
        ManualPaymentFlow.Ticket entitlement = f.imageFinished(image);
        f.leave();
        eq(null, f.acceptEntitlement(entitlement, entitlement(999L, true)), "left page rejects entitlement");
        check(!f.snapshot().canContinue && f.snapshot().entitlement == null, "late entitlement cannot revive page");
        boot(f, A, 5, 123L, true);
        ManualPaymentFlow.Ticket dav = f.beginDavTest(); f.enter(B, 6);
        check(!f.acceptDavSuccess(dav), "old account DAV rejected");
    }
    private static void boundedForegroundPolling() throws Exception {
        ManualPaymentFlow f = new ManualPaymentFlow(true);
        boot(f, A, 0, null, false);
        long now = 0; int count = 0;
        while (f.nextDelayMillis(now) != ManualPaymentFlow.NO_POLL) {
            now += f.nextDelayMillis(now);
            ManualPaymentFlow.Ticket ticket = f.poll(now);
            action(ticket, ManualPaymentFlow.Action.LOAD_ENTITLEMENT);
            eq(null, f.poll(now), "repeated timer single-flight");
            f.acceptEntitlement(ticket, entitlement(null, false));
            check(++count <= ManualPaymentFlow.MAX_POLL_REQUESTS, "finite poll count");
        }
        check(count > 1 && now < ManualPaymentFlow.POLL_BUDGET_MILLIS, "backoff has a finite window");
        f.setForeground(false, now); f.setForeground(true, 900_000);
        ManualPaymentFlow.Ticket e = f.imageFinished(f.resume(900_000));
        f.acceptEntitlement(e, entitlement(321L, true));
        eq(ManualPaymentFlow.NO_POLL, f.nextDelayMillis(900_000), "resume does not extend budget");
        finish(f, f.refresh(900_000), 321L, true);
        eq(2000L, f.nextDelayMillis(900_000), "explicit refresh resets finite window");
        e = f.poll(902_000); f.acceptEntitlement(e, entitlement(654L, true));
        eq(Long.valueOf(654L), f.snapshot().entitlement.expiresMs, "active account can observe later renewal");
        check(!f.snapshot().canContinue, "poll never grants DAV proof");
    }
    private static void malformedAndUnsupportedResponses() throws Exception {
        ManualPaymentFlow f = new ManualPaymentFlow(true);
        ManualPaymentFlow.Ticket p = f.enter(A, 0);
        f.acceptPlans(p, Arrays.asList(plans().get(0), null));
        eq(ManualPaymentFlow.Phase.ERROR, f.snapshot().phase, "null plan fails closed");
        p = f.refresh(1); f.acceptPlans(p, List.of(plans().get(0), plans().get(0)));
        check(!f.snapshot().canTestDav, "duplicate plan rejected");
        p = f.refresh(2); f.fail(p, "UNSUPPORTED_CONTRACT");
        eq(ManualPaymentFlow.NO_POLL, f.nextDelayMillis(3), "unsupported contract stops polls");
        finish(f, f.refresh(3), 999L, true);
        check(f.snapshot().canTestDav, "valid explicit refresh recovers");
        p = f.refresh(4);
        ManualPaymentFlow.Ticket e = f.acceptPlans(p, Collections.emptyList());
        action(e, ManualPaymentFlow.Action.LOAD_ENTITLEMENT);
        f.acceptEntitlement(e, entitlement(888L, true));
        check(f.snapshot().canTestDav, "no image plan does not block existing entitlement");
        e = f.poll(2004); f.acceptEntitlement(e, null);
        check(!f.snapshot().canTestDav && !f.snapshot().canContinue, "unconfirmed response cannot reuse active proof");
        eq(Long.valueOf(888L), f.snapshot().entitlement.expiresMs, "last confirmed expiry is retained without extension");
    }
    private static void legacyDraftsRemainUntouched() throws Exception {
        Path root = Files.createTempDirectory("pdsync-legacy-drafts-");
        try {
            ManualOrderDraftFile store = new ManualOrderDraftFile(root.toFile());
            ManualPaymentFlow.Draft draft = new ManualPaymentFlow.Draft(A, "old-plan", "aaaaaaaaaaaaaaaa", null);
            store.save(draft);
            Path file;
            try (java.util.stream.Stream<Path> files = Files.list(root)) { file = files.findFirst().orElseThrow(); }
            byte[] original = Files.readAllBytes(file);
            boot(new ManualPaymentFlow(true, store), A, 0, 456L, true);
            check(Arrays.equals(original, Files.readAllBytes(file)), "uncertain legacy local draft never replayed or changed");
            eq(draft.requestKey, store.load(A).requestKey, "legacy file contract preserved");
            Files.write(file, new byte[] {1, 2, 3});
            try { store.load(A); throw new AssertionError("corrupt legacy file accepted"); }
            catch (IOException expected) { checks++; }
            boot(new ManualPaymentFlow(true, store), A, 0, null, false);
            eq(3L, Files.size(file), "corrupt draft neither blocks new display nor gets deleted");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(file);
            }
        }
    }
    private static void busyRejects(ManualPaymentFlow f) {
        check(f.snapshot().busy && !f.snapshot().canContinue, "in-flight is busy and cannot continue");
        eq(null, f.refresh(0), "repeated refresh single-flight");
        eq(null, f.poll(0), "poll cannot overlap");
        eq(null, f.beginDavTest(), "DAV cannot overlap");
    }
    private static void boot(ManualPaymentFlow f, String scope, long now, Long expiry, boolean active) throws Exception {
        finish(f, f.enter(scope, now), expiry, active);
    }
    private static void finish(ManualPaymentFlow f, ManualPaymentFlow.Ticket p, Long expiry, boolean active) throws Exception {
        action(p, ManualPaymentFlow.Action.LOAD_PLANS);
        ManualPaymentFlow.Ticket i = f.acceptPlans(p, plans()); action(i, ManualPaymentFlow.Action.LOAD_IMAGE);
        ManualPaymentFlow.Ticket e = f.imageFinished(i); action(e, ManualPaymentFlow.Action.LOAD_ENTITLEMENT);
        f.acceptEntitlement(e, entitlement(expiry, active));
    }
    private static List<ManualOrderClient.Plan> plans() throws Exception {
        return client("[{\"id\":\"fixture_a\",\"currency\":null,\"term\":\"calendar_year\",\"amount_minor\":null,"
                + "\"days\":0,\"quota_bytes\":1024,\"upload_kbps\":1,\"download_kbps\":1,\"display_name\":\"Fixture\","
                + "\"payment_instructions\":\"Fixture\",\"payment_image_path\":\"/api/order/payment-image/fixture_a\","
                + "\"expiry_policy\":\"sandbox_upload_only\",\"payment_mode\":\"donation\"}]").plans();
    }
    private static ManualOrderClient.Entitlement entitlement(Long expiry, boolean active) throws Exception {
        return client("{\"source\":\"sandbox_ledger\",\"expires_ms\":" + expiry + ",\"active\":" + active + "}").entitlement();
    }
    private static ManualOrderClient client(String data) {
        byte[] bytes = ("{\"sandbox\":true,\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
        return new ManualOrderClient("http://127.0.0.1:18080", request -> new ManualOrderClient.Response(200, "application/json", bytes));
    }
    private static void action(ManualPaymentFlow.Ticket ticket, ManualPaymentFlow.Action action) {
        check(ticket != null && ticket.action == action, "expected action " + action);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static void eq(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + " actual=" + actual);
    }
}
