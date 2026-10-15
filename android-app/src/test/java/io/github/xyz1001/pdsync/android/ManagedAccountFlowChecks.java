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
        check(flow.image(request, new byte[] {1}), "Public code is independent of authenticated entitlement");
        check(flow.view().username == null && flow.view().localUsername.equals("account-a"), "Local name is not authentication proof");
        check(flow.entitlement(request, "account-a", 1_800_000_000_000L, false, true), "Expired account can load entitlement");
        check(flow.view().username.equals("account-a") && !flow.view().active, "Exact server username and inactive status are shown");
        check(flow.image(request, new byte[] {1, 2, 3}), "Inactive account can still see appreciation code");
        check(flow.complete(request), "Completion closes the public and private read sequence");
        check(!flow.view().busy && flow.view().canRefresh, "Completion offers only manual refresh");
        AtomicReference<String> copied = new AtomicReference<>();
        check(flow.copyUsername(flow.view().generation, copied::set) && copied.get().equals("account-a"), "Copy uses verified username");
        ManagedAccountFlow.Ticket again = flow.refresh();
        check(flow.view().username == null && flow.view().image == null && flow.view().cachedExpiry,
                "Refresh hides live proof and image but may retain explicitly historical verified expiry");
        check(!flow.entitlement(again, "account-b", 123L, true, true), "Mismatched authenticated account is rejected");
        check(flow.view().username == null && !flow.view().busy,
                "Mismatched response cannot claim another account or leave a spinner");
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
        flow.complete(request);
        bytes[0] = 9;
        check(Arrays.equals(flow.view().image, new byte[] {1, 2, 3}), "Caller cannot mutate stored image");
        byte[] snapshot = flow.view().image;
        snapshot[1] = 9;
        check(Arrays.equals(flow.view().image, new byte[] {1, 2, 3}), "View cannot mutate stored image");
        check(ManagedAccountFlow.expiry(flow.view()).equals("有效期 未设置"), "Null expiry is not fabricated as a trial or unlimited term");
        request = flow.refresh();
        flow.entitlement(request, "account-a", 1_800_000_000_000L, false, true);
        check(ManagedAccountFlow.expiry(flow.view()).equals("有效至 2027-01-15"), "Date comes directly from server milliseconds");
        check(ManagedAccountFlow.accessStatus(flow.view()).isEmpty(), "Inactive without a trusted clock does not prove expiry");
    }

    private static void publicQrSurvivesUnknownAuthentication() {
        ManagedAccountFlow flow = visible("account-a");
        ManagedAccountFlow.Ticket ticket = flow.refresh();
        flow.image(ticket, new byte[] {7});
        check(flow.fail(ticket, "无法登录，请检查账号或续期状态。"), "Authentication failure completes optional metadata read");
        check(flow.view().image[0] == 7 && flow.view().username == null && flow.view().expiresMs == null,
                "401 keeps public QR and local username without fabricating private date");
        check(!flow.view().expired && ManagedAccountFlow.accessStatus(flow.view()).isEmpty(), "401 is never expiry proof");
        check(ManagedAccountFlow.expiry(flow.view()).equals("有效期 未知"), "New device shows no fabricated date");
        AtomicReference<String> copy = new AtomicReference<>();
        check(flow.copyUsername(flow.view().generation, copy::set) && copy.get().equals("account-a"), "Explicitly unverified name can still be copied for appreciation");
        ticket = flow.refresh();
        flow.entitlement(ticket, "account-a", 100L, false, true, 100L);
        flow.image(ticket, new byte[] {8}); flow.complete(ticket);
        check(flow.view().expired && ManagedAccountFlow.accessStatus(flow.view()).equals("服务已到期"), "Authenticated time and deadline establish expired display");
        ticket = flow.refresh();
        flow.entitlement(ticket, "account-a", 200L, true, true, 101L); flow.complete(ticket);
        check(!flow.view().expired && flow.view().active, "Authenticated renewal clears expired display");
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
        Path common = sourceRoot.resolve("shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android");
        String controller = Files.readString(common.resolve("SharedConnectionController.kt"));
        String screen = Files.readString(common.resolve("SharedConnectionScreen.kt"));
        String login = Files.readString(sourceRoot.resolve("core/src/main/java/io/github/xyz1001/pdsync/android/OfficialServiceRegistrationClient.java"));
        String settings = method(main, "private void renderSettings()");
        check(settings.contains("isOfficialAccount(this.connection)") && settings.contains("showManagedAccount(this.connection)"),
                "Saved official account remains directly reachable from Settings");
        check(method(main, "private void showManagedAccount(").contains("getController().openAppreciationOnly()"),
                "Settings appreciation mounts the same shared controller");
        check(!main.contains("ManagedAccountPanel.render") && !main.contains("renderOfficialConnection()"),
                "No native production connection/account renderer remains");
        String allowed = method(main, "private static boolean isOfficialAccount(");
        check(allowed.contains("PROVIDER_OFFICIAL.equals(account.provider)")
                && allowed.contains("OfficialServiceRegistrationClient.DAV_URL.equals(account.endpoint)"),
                "Saved credentials require both official provider and exact official endpoint");
        String verify = method(controller, "private fun verify(");
        check(verify.contains("entitlement.requireSyncAccess()") && verify.contains("if (!ports.testConnection(candidate)) throw ProbeRejected()")
                && verify.indexOf("entitlement.requireSyncAccess()") < verify.indexOf("ports.testConnection(candidate)"),
                "Inactive account stops before DAV and active metadata alone cannot grant proof");
        String commit = method(controller, "fun continueConnection()");
        check(commit.indexOf("if (!hasProof())") < commit.indexOf("ports.saveAndActivate(candidate)")
                && commit.indexOf("verify(candidate, pending)") < commit.indexOf("ports.saveAndActivate(candidate)"),
                "Explicit commit still requires current proof and a fresh test");
        String reads = method(controller, "fun refreshAppreciation()");
        check(reads.contains("client.entitlement()") && reads.contains("client.appreciation()") && reads.contains("client.paymentImage(metadata)"),
                "Shared account page reads current official metadata and image");
        for (String forbidden : new String[] {".register(", ".login(", ".save(", "ports.testConnection(", "ports.saveAndActivate("})
            check(!reads.contains(forbidden), "Appreciation refresh has no mutation/auth/DAV side effect: " + forbidden);
        check(reads.indexOf("client.paymentImage(metadata)") < reads.indexOf("client.entitlement()")
                && reads.contains("client.canReadEntitlement()"), "Public QR loads before optional private metadata");
        check(reads.contains("appreciationFlow !== flow") && reads.contains("appreciationClient !== client")
                && reads.contains("flow.accepts(ticket)") && reads.contains("foreground.get()"),
                "Delayed account callbacks require current visible source generation");
        String selection = method(controller, "private fun officialAccountForDraft()");
        check(selection.contains("verifiedOfficial?.takeIf") && selection.contains("saved?.takeIf") && selection.contains("it == current"),
                "Only exact matching saved/authenticated credentials select private account access");
        String publicChoice = method(controller, "fun openAppreciation()");
        check(publicChoice.contains("officialAccountForDraft()") && publicChoice.contains("copy(password = \"\")"),
                "Unverified username can load public QR without forwarding typed password");
        check(method(controller, "fun foreground(").contains("cancelAppreciationRequest()")
                && method(controller, "private fun cancelAppreciationRequest()").contains("cancelPending()"),
                "Background invalidates and cancels account transport");
        check(main.contains("session = activity.sharedConnectionSession") && main.contains("this.sharedConnectionSession = retained.session"),
                "Rotation retains the same controller and in-flight authentication");
        String saved = method(main, "protected void onSaveInstanceState(");
        check(!saved.contains("password") && !saved.contains("official") && !saved.contains("session"),
                "Account credentials and controller never enter saved-state Bundle");
        check(screen.contains("赞赏时请备注用户名") && screen.contains("controller::copyUsername") && screen.contains("用户名（未验证）"),
                "Shared page has explicit identity copy and labels unverified usernames");
        String restore = method(main, "private AndroidSyncFacade.OperationResult restoreForCurrentAccount(");
        check(restore.contains("isOfficialAccount(account)") && restore.contains(".entitlement()"), "Restore requires current official authority");
        check(restore.contains("this.connection != account") && restore.contains("entitlement.requireSyncAccess()"),
                "Only a stable active official account may begin restore");
        check(!restore.contains("restoreSelectedReadOnly") && restore.contains("sync().restoreSelected(inspection, snapshotId, replace)")
                && restore.indexOf("entitlement.requireSyncAccess()") < restore.indexOf("sync().restoreSelected"),
                "Inactive official stops before download; active and custom keep existing restore path");
        String cache = method(controller, "private fun remember(");
        check(cache.contains("source.username == entitlement.username") && cache.contains("entitlement.serverTimeMs")
                && !cache.contains("source.password"), "Cache accepts authenticated matching metadata, never credentials");
        check(login.contains("case INVALID_CREDENTIALS: message = \"无法登录，请检查账号或续期状态。\""),
                "Authentication 401 does not claim wrong password or confirmed expiry");
        for (String forbidden : new String[] {"沙箱", "我已赞赏", "订单", "待审核"})
            check(!screen.contains(forbidden), "Production shared page omits " + forbidden);
    }

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        identityAndEntitlementAreServerBound();
        backgroundNavigationAndAccountSwitchDiscardOldResponses();
        failuresAndImageCopiesAreIsolated();
        publicQrSurvivesUnknownAuthentication();
        if (args.length > 0) activityIntegrationGuards(Path.of(args[0]));
        System.out.println("ManagedAccountFlowChecks: " + checks + " checks passed");
    }
}
