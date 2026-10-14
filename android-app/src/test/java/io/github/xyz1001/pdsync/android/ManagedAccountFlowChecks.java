package io.github.xyz1001.pdsync.android;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

/** Host-only UI state/source checks. No service requests, real accounts, Android, or secrets. */
public final class ManagedAccountFlowChecks {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
    private static ManagedAccountFlow visible(String user) {
        ManagedAccountFlow flow = new ManagedAccountFlow(user);
        flow.foreground(true);
        return flow;
    }

    private static void identityAndEntitlementAreServerBound() {
        ManagedAccountFlow flow = new ManagedAccountFlow("account-a");
        check(flow.view().username == null && !flow.view().entitlementLoaded,
                "The expected credential identity is never displayed as authenticated server data");
        check(flow.refresh() == null, "No account requests in background");
        flow.foreground(true);
        ManagedAccountFlow.Ticket request = flow.refresh();
        check(request != null && flow.view().busy && flow.refresh() == null, "Refresh is single-flight");
        check(!flow.image(request, new byte[] {1}), "No code before authenticated entitlement");
        check(flow.entitlement(request, "account-a", 1_800_000_000_000L, false, true), "Expired account can load entitlement");
        check(flow.view().username.equals("account-a") && !flow.view().active, "Exact server username and inactive status are shown");
        check(flow.image(request, new byte[] {1, 2, 3}), "Inactive account can still see appreciation code");
        check(!flow.view().busy && flow.view().canRefresh, "Completion offers only manual refresh");
        AtomicReference<String> copied = new AtomicReference<>();
        check(flow.copyUsername(flow.view().generation, copied::set) && copied.get().equals("account-a"), "Copy uses verified username");
        ManagedAccountFlow.Ticket again = flow.refresh();
        check(flow.view().username == null && flow.view().image == null && flow.view().expiresMs == null,
                "Refresh hides previous code, identity and expiry until current confirmation");
        check(!flow.entitlement(again, "account-b", 123L, true, true), "Mismatched authenticated account is rejected");
        check(flow.view().username == null && flow.view().expiresMs == null && !flow.view().busy,
                "Mismatched response cannot leak another account or leave a spinner");
        check(!flow.image(again, new byte[] {3}), "Mismatched identity cannot be followed by a code");
        ManagedAccountFlow.Ticket invalid = flow.refresh();
        check(!flow.entitlement(invalid, "account-a", -1L, true, true), "Negative expiry fails closed");
    }

    private static void backgroundNavigationAndAccountSwitchDiscardOldResponses() {
        ManagedAccountFlow flow = visible("account-a");
        ManagedAccountFlow.Ticket first = flow.refresh();
        flow.entitlement(first, "account-a", 42L, true, true);
        long firstGeneration = flow.view().generation;
        flow.foreground(false);
        check(flow.view().username == null && flow.view().expiresMs == null && flow.view().image == null,
                "Background clears account presentation");
        check(!flow.entitlement(first, "account-a", 99L, true, true)
                && !flow.image(first, new byte[] {9}) && !flow.fail(first, "stale"), "Background callbacks are discarded");
        check(!flow.copyUsername(firstGeneration, ignored -> { throw new AssertionError(); }), "Old button cannot copy in background");
        flow.foreground(true);
        check(!flow.view().busy && flow.view().canRefresh, "Resume never silently polls or registers");
        ManagedAccountFlow.Ticket second = flow.refresh();
        check(second.generation != first.generation, "Fresh foreground request owns a new generation");
        check(!flow.entitlement(first, "account-a", 99L, true, true), "Old request remains rejected after resume");
        flow.entitlement(second, "account-a", 101L, true, true);
        check(!flow.copyUsername(firstGeneration, ignored -> { throw new AssertionError(); }), "Captured button from old generation remains invalid");
        ManagedAccountFlow replacement = visible("account-b");
        ManagedAccountFlow.Ticket other = replacement.refresh();
        check(!replacement.entitlement(second, "account-b", 33L, true, true), "A ticket cannot cross account-flow owners");
        check(!flow.entitlement(other, "account-a", 33L, true, true), "Another account cannot alter original flow");
        flow.close();
        check(!flow.image(second, new byte[] {3}) && !flow.fail(second, "stale"), "Leaving cancels all old updates");
        check(flow.view().username == null && flow.view().expiresMs == null && flow.view().image == null,
                "Leaving removes old account data");
        flow.foreground(true);
        check(flow.refresh() == null, "A closed account flow cannot resume");
        check(replacement.entitlement(other, "account-b", null, true, false), "Replacement authenticates independently");
    }

    private static void failuresAndImageCopiesAreIsolated() {
        ManagedAccountFlow flow = visible("account-a");
        ManagedAccountFlow.Ticket request = flow.refresh();
        check(flow.fail(request, "暂时不可用。"), "Initial read error terminates request");
        check(!flow.view().entitlementLoaded && flow.view().username == null, "Error never claims an authenticated name");
        request = flow.refresh();
        flow.entitlement(request, "account-a", 1_800_000_000_000L, false, true);
        check(flow.fail(request, "赞赏暂不可用。"), "Missing QR is a display error");
        check(flow.view().username.equals("account-a") && flow.view().expiresMs == 1_800_000_000_000L,
                "Missing metadata preserves the independently authenticated current entitlement");
        check(flow.view().image == null && !flow.view().busy, "A failed image does not reuse an old code");
        request = flow.refresh();
        flow.entitlement(request, "account-a", null, true, false);
        byte[] bytes = {1, 2, 3};
        flow.image(request, bytes);
        bytes[0] = 9;
        check(Arrays.equals(flow.view().image, new byte[] {1, 2, 3}), "Caller cannot mutate stored image");
        byte[] snapshot = flow.view().image;
        snapshot[1] = 9;
        check(Arrays.equals(flow.view().image, new byte[] {1, 2, 3}), "View cannot mutate stored image");
        check(ManagedAccountFlow.expiry(flow.view()).equals("有效期 未设置"), "Null expiry is not fabricated as a trial or unlimited term");
        request = flow.refresh();
        flow.entitlement(request, "account-a", 1_800_000_000_000L, false, true);
        check(ManagedAccountFlow.expiry(flow.view()).equals("有效至 2027-01-15（已到期）"), "Expired date comes directly from server milliseconds");
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        check(start >= 0, "Source method exists: " + signature);
        int brace = source.indexOf('{', start), depth = 1, end = brace + 1;
        while (depth != 0 && end < source.length()) {
            char value = source.charAt(end++);
            if (value == '{') depth++;
            if (value == '}') depth--;
        }
        return source.substring(brace, end);
    }

    private static void activityIntegrationGuards(Path sourceRoot) throws Exception {
        Path base = sourceRoot.resolve("android-app/src/main/java/io/github/xyz1001/pdsync/android");
        String main = Files.readString(base.resolve("MainActivity.java"));
        String panel = Files.readString(base.resolve("ManagedAccountPanel.java"));
        String official = method(main, "private void renderOfficialConnection()");
        String settings = method(main, "private void renderSettings()");
        check(official.contains("button(\"赞赏\"") && official.contains("officialAccountForDraft()"), "Official page has a source-bound appreciation entry");
        check(settings.contains("isOfficialAccount(this.connection)") && settings.contains("showManagedAccount(this.connection)"),
                "The existing saved official account is reachable directly from Settings");
        String allowed = method(main, "private static boolean isOfficialAccount(");
        check(allowed.contains("PROVIDER_OFFICIAL.equals(account.provider)")
                && allowed.contains("OfficialServiceRegistrationClient.DAV_URL.equals(account.endpoint)"),
                "Saved credentials require both official provider and exact official endpoint");
        String selection = method(main, "private SecureConnectionStore.Connection officialAccountForDraft()");
        check(selection.contains("this.verifiedOfficial") && selection.contains("this.connection")
                && selection.contains("candidate.username.equals(this.officialUser)")
                && selection.contains("candidate.password.equals(this.officialPassword)"),
                "Edit-box values can only select matching verified or saved credentials");
        String auth = method(main, "private void authenticateOfficial(");
        check(auth.contains("client.register(username, password) : client.login(username, password)"), "Real explicit official auth is preserved");
        check(auth.contains("new OfficialAuthResult(candidate, false"), "Failed DAV or entitlement retains authenticated account access");
        String resume = method(main, "private void resumeOfficialAttempt()");
        check(resume.contains("result.davVerified ? attempt.proof : null") && !resume.contains(".active"),
                "Only a successful real DAV check grants Next; upload eligibility itself is never the Next gate");
        String verify = method(main, "private static ManagedAccountClient.Entitlement verifyOfficialConnection(");
        check(verify.contains("entitlement.active ? facade.connect() : facade.testConnection()")
                && verify.contains("if (!result.isSuccess())"), "Expired account verification is read-only and must succeed");
        String apply = method(main, "Object m17xc0060660(");
        check(apply.contains("if (isOfficialAccount(connection)) verifyOfficialConnection(connection, androidSyncFacadeFacade)")
                && apply.contains("else MainActivity.lambda$applyConnection$55(androidSyncFacadeFacade)"),
                "Save rechecks official entitlement while custom DAV behavior stays unchanged");
        String reads = method(main, "private void refreshManagedAccount()");
        check(reads.contains("client.entitlement()") && reads.contains("client.appreciation()") && reads.contains("client.paymentImage(appreciation)"),
                "Panel reads current entitlement and official metadata/image");
        check(!reads.contains(".register(") && !reads.contains(".save(") && !reads.contains(".connect(") && !reads.contains(".login("),
                "Refreshing appreciation cannot authenticate, register, save, or test DAV as a side effect");
        check(method(main, "protected void onPause()").contains("this.managedAccountFlow.foreground(false)")
                && method(main, "protected void onPause()").contains("cancelManagedAccountRequest()"), "Background invalidates and cancels reads");
        check(method(main, "private void navigate(").contains("closeManagedAccount()")
                && method(main, "protected void onDestroy()").contains("closeManagedAccount()"), "Leaving and destruction close the account display");
        check(method(main, "private void cancelManagedAccountRequest()").contains("client.cancelPending()")
                && method(main, "private void cancelManagedAccountRequest()").contains("cancel(true)"), "Cancellation reaches transport and executor");
        String current = method(main, "private boolean currentManagedRequest(");
        check(current.contains("this.resumed") && current.contains("this.managedAccountFlow == flow")
                && current.contains("this.managedAccountClient == client") && current.contains("flow.accepts(ticket)"), "Posted callbacks require the current visible source generation");
        check(main.contains("ConnectionAuthAttempt<OfficialAuthResult>") && main.contains("attempt = activity.officialAttempt")
                && main.contains("this.officialAttempt = retained.attempt"), "Rotation retains the same at-most-once authentication operation");
        String saved = method(main, "protected void onSaveInstanceState(");
        check(!saved.contains("password") && !saved.contains("official") && !saved.contains("managed"), "Credentials and account state are never persisted in Bundle");
        check(panel.contains("赞赏时请备注用户名") && panel.contains("flow.copyUsername(view.generation"), "Minimal panel copies only the currently confirmed username");
        String restore = method(main, "private AndroidSyncFacade.OperationResult restoreForCurrentAccount(");
        check(restore.contains("isOfficialAccount(account)") && restore.contains(".entitlement()"), "Read-only restore requires current official account authority");
        check(restore.contains("this.connection != account") && restore.contains("if (!entitlement.active)"), "Only a stable, confirmed inactive account selects download mode");
        check(restore.contains("facade.restoreSelectedReadOnly(inspection, snapshotId, replace)")
                && restore.contains("facade.restoreSelected(inspection, snapshotId, replace)"), "Active and custom restores preserve their existing behavior");
        for (String forbidden : new String[] {"沙箱", "我已赞赏", "订单", "待审核", "下一步"})
            check(!panel.contains(forbidden), "Production appreciation panel omits " + forbidden);
    }

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        identityAndEntitlementAreServerBound();
        backgroundNavigationAndAccountSwitchDiscardOldResponses();
        failuresAndImageCopiesAreIsolated();
        if (args.length > 0) activityIntegrationGuards(Path.of(args[0]));
        System.out.println("ManagedAccountFlowChecks: " + checks + " checks passed");
    }
}
