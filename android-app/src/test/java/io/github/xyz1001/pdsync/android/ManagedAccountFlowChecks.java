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
        String panel = Files.readString(base.resolve("ManagedAccountPanel.java"));
        Path shared = sourceRoot.resolve("core/src/main/java/io/github/xyz1001/pdsync/android");
        String login = Files.readString(shared.resolve("OfficialServiceRegistrationClient.java"));
        String official = method(main, "private void renderOfficialConnection()");
        String settings = method(main, "private void renderSettings()");
        check(official.contains("button(officialAppreciationLabel()") && official.contains("appreciationAccountForDraft()"), "Official page has an explicitly selected appreciation identity");
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
                "Only a successful real DAV check grants Next; expiry display alone never grants proof");
        String verify = method(main, "private static ManagedAccountClient.Entitlement verifyOfficialConnection(");
        check(verify.contains("entitlement.requireSyncAccess()") && verify.contains("facade.connect()")
                && verify.indexOf("entitlement.requireSyncAccess()") < verify.indexOf("facade.connect()")
                && !verify.contains("testConnection") && verify.contains("if (!result.isSuccess())"),
                "Inactive account stops before DAV and active accounts still require a real successful probe");
        String apply = method(main, "Object m17xc0060660(");
        check(apply.contains("if (isOfficialAccount(connection)) verifyOfficialConnection(connection, androidSyncFacadeFacade, getApplicationContext())")
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
        check(panel.contains("赞赏时请备注用户名") && panel.contains("flow.copyUsername(view.generation"), "Minimal panel copies the displayed identity with its current generation");
        String restore = method(main, "private AndroidSyncFacade.OperationResult restoreForCurrentAccount(");
        check(restore.contains("isOfficialAccount(account)") && restore.contains(".entitlement()"), "Restore requires current official account authority");
        check(restore.contains("this.connection != account") && restore.contains("entitlement.requireSyncAccess()"),
                "Only a stable active official account may begin restore");
        check(!restore.contains("restoreSelectedReadOnly") && restore.contains("sync().restoreSelected(inspection, snapshotId, replace)")
                && restore.indexOf("entitlement.requireSyncAccess()") < restore.indexOf("sync().restoreSelected"),
                "Inactive official stops before download; active and custom restore use the existing path");
        check(!main.contains("restoreSelectedReadOnly") && !main.contains("facade.testConnection()"), "Deprecated expired-read-only UI paths are removed");
        String publicChoice = method(main, "private SecureConnectionStore.Connection appreciationAccountForDraft()");
        check(publicChoice.contains("officialAccountForDraft()") && publicChoice.contains("this.officialUser, \"\"")
                && !publicChoice.contains("this.verifiedOfficial =") && !publicChoice.contains("this.testedDraft ="),
                "Unverified username opens public QR without forwarding the typed password or creating login proof");
        check(reads.indexOf("client.paymentImage(appreciation)") < reads.indexOf("client.entitlement()")
                && reads.contains("if (client.canReadEntitlement())") && reads.contains("flow.complete(ticket)"),
                "Public QR loads independently before optional private metadata");
        check(panel.contains("用户名（未验证）") && panel.contains("ManagedAccountFlow.accessStatus(view)"),
                "Panel labels local identity and shows only proven expiry");
        String cache = method(main, "private static void rememberEntitlement(");
        check(cache.contains("source.username.equals(entitlement.username)") && cache.contains("entitlement.serverTimeMs")
                && !cache.contains("source.password") && !cache.contains("token"), "Display cache contains authenticated metadata only");
        check(verify.indexOf(".entitlement()") < verify.indexOf("rememberEntitlement(app, account, entitlement)"),
                "Connection cache writes require successful authenticated metadata");
        check(main.contains("赞赏续期") && main.contains("无法登录，请检查账号或续期状态。"), "Known expiry and ambiguous official authentication have distinct copy");
        check(login.contains("case INVALID_CREDENTIALS: message = \"无法登录，请检查账号或续期状态。\""),
                "Registration/login 401 does not claim wrong password or confirmed expiry");
        check(reads.contains("selectedClient = ManagedAccountClient.publicAppreciation()")
                && reads.contains("final String initialMessage = configurationMessage"),
                "Invalid saved credentials cannot prevent fetching the independent public QR");
        for (String forbidden : new String[] {"沙箱", "我已赞赏", "订单", "待审核", "下一步"})
            check(!panel.contains(forbidden), "Production appreciation panel omits " + forbidden);
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
