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
        check(!flow.view().busy && flow.view().canRefresh, "Completion clears the in-flight marker");
        AtomicReference<String> copied = new AtomicReference<>();
        check(flow.copyUsername(flow.view().generation, copied::set) && copied.get().equals("account-a"), "Copy uses verified username");
        ManagedAccountFlow.Ticket again = flow.refresh();
        check(flow.view().username == null && Arrays.equals(flow.view().image, new byte[] {1, 2, 3}) && flow.view().cachedExpiry,
                "Reload hides live proof but preserves the public image and historical verified expiry");
        check(!flow.entitlement(again, "account-b", 123L, true, true), "Mismatched authenticated account is rejected");
        check(flow.view().username == null && !flow.view().busy,
                "Mismatched response cannot claim another account or leave a spinner");
        check(!flow.image(again, new byte[] {3}), "Mismatched identity cannot be followed by a code");
        ManagedAccountFlow.Ticket invalid = flow.refresh();
        check(flow.entitlement(invalid, "account-a", -1L, false, true, 0L),
                "Normalized pre-epoch service deadline remains authenticated metadata");
        check(flow.view().expiresMs == -1L && flow.view().expired && !flow.view().active,
                "Negative normalized deadline is expired rather than unlimited");
    }

    private static void backgroundPreservesDisplayAndNavigationDiscardsOldResponses() {
        ManagedAccountFlow flow = visible("account-a");
        ManagedAccountFlow.Ticket first = flow.refresh();
        flow.entitlement(first, "account-a", 42L, true, true);
        flow.image(first, new byte[] {7});
        long firstGeneration = flow.view().generation;
        flow.foreground(false);
        check(flow.view().username.equals("account-a") && flow.view().localUsername.equals("account-a")
                && flow.view().expiresMs == 42L && Arrays.equals(flow.view().image, new byte[] {7}),
                "Background preserves identity, trusted expiry and the public image");
        check(flow.view().entitlementLoaded && flow.view().generation == firstGeneration
                && flow.view().busy && !flow.view().canRefresh && flow.refresh() == null,
                "Focus changes preserve the pending generation and cannot start a read");
        check(flow.entitlement(first, "account-a", 99L, true, true)
                && flow.image(first, new byte[] {9}) && flow.complete(first),
                "An existing read can finish in background without leaving a stuck spinner");
        check(!flow.copyUsername(firstGeneration, ignored -> { throw new AssertionError(); }), "Copy remains foreground-only");
        flow.foreground(true);
        check(!flow.view().busy && flow.view().canRefresh && flow.view().generation == firstGeneration
                && flow.view().username.equals("account-a") && flow.view().expiresMs == 99L
                && Arrays.equals(flow.view().image, new byte[] {9}),
                "Resume keeps the finished display and never starts or invalidates a read");
        ManagedAccountFlow.Ticket second = flow.refresh();
        check(second.generation != first.generation, "An explicit fresh request owns a new generation");
        check(!flow.entitlement(first, "account-a", 99L, true, true), "Completed request remains rejected");
        flow.entitlement(second, "account-a", 101L, true, true);
        check(!flow.copyUsername(firstGeneration, ignored -> { throw new AssertionError(); }), "Captured button from old generation remains invalid");
        ManagedAccountFlow replacement = visible("account-b");
        ManagedAccountFlow.Ticket other = replacement.refresh();
        check(!replacement.entitlement(second, "account-b", 33L, true, true), "A ticket cannot cross account-flow owners");
        check(!flow.entitlement(other, "account-a", 33L, true, true), "Another account cannot alter original flow");
        flow.foreground(false);
        flow.close();
        check(!flow.image(second, new byte[] {3}) && !flow.fail(second, "stale")
                && !flow.entitlement(second, "account-a", 103L, true, true) && !flow.complete(second),
                "Leaving cancels every paused callback");
        check(flow.view().username == null && flow.view().expiresMs == null && flow.view().image == null,
                "Leaving removes old account data");
        flow.foreground(true);
        check(flow.refresh() == null, "A closed account flow cannot resume");
        check(replacement.entitlement(other, "account-b", null, true, false), "Replacement authenticates independently");

        ManagedAccountFlow failed = visible("account-c");
        ManagedAccountFlow.Ticket pending = failed.refresh();
        failed.foreground(false);
        check(failed.metadata(pending, "a".repeat(64)) && failed.image(pending, new byte[] {5})
                && failed.fail(pending, "temporarily unavailable"), "Pending public image and failure can arrive while paused");
        failed.foreground(true);
        check(!failed.view().busy && failed.view().image[0] == 5
                && failed.view().message.equals("temporarily unavailable"), "Resume retains the completed result or error");
    }

    private static void liveExpiryUsesElapsedTimeWithoutDesktopBootIdentity() {
        final long[] elapsed = {500L};
        ManagedAccountFlow.Clock clock = new ManagedAccountFlow.Clock() {
            public long elapsedMs() { return elapsed[0]; }
            public int bootCount() { return -1; }
        };
        TrustedEntitlementCache.Record cached = new TrustedEntitlementCache.Record(
                TrustedEntitlementCache.OFFICIAL_ENDPOINT, "account-a", 1_100L, true, 1_000L, 500L, -1);
        ManagedAccountFlow flow = new ManagedAccountFlow("account-a", cached, clock);
        flow.foreground(true);
        elapsed[0] = 600L;
        check(!flow.view().expired && flow.view().cachedExpiry,
                "Persisted unknown-boot metadata cannot prove elapsed time from another process");
        ManagedAccountFlow.Ticket ticket = flow.refresh();
        flow.entitlement(ticket, "account-a", 1_100L, true, true, 1_000L);
        flow.complete(ticket);
        flow.foreground(false);
        elapsed[0] = 699L;
        check(!flow.view().expired, "Live account stays unexpired before its server-derived deadline");
        elapsed[0] = 700L;
        check(flow.view().expired && ManagedAccountFlow.accessStatus(flow.view()).equals("服务已到期"),
                "Live metadata expires on monotonic elapsed time even with desktop bootCount -1");
        flow.foreground(true);
        check(flow.view().expired && flow.view().username.equals("account-a") && flow.view().expiresMs == 1_100L,
                "Resume preserves identity and expiry while reflecting genuine elapsed expiry");
        ticket = flow.refresh();
        check(flow.view().expired && flow.view().cachedExpiry,
                "Explicit refresh retains the same-process trusted clock until a new response arrives");
        flow.entitlement(ticket, "account-a", 1_300L, true, true, 1_100L);
        flow.complete(ticket);
        check(!flow.view().expired, "Authenticated renewal replaces the live clock anchor");
        elapsed[0] = 600L;
        check(!flow.view().expired, "A regressed elapsed clock cannot manufacture expiry");
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

    private static void normalizedExpiryStatusAndUnknownRemainDistinct() {
        ManagedAccountFlow flow = visible("account-a");
        ManagedAccountFlow.Ticket ticket = flow.refresh();
        flow.entitlement(ticket, "account-a", -100L, false, true, 0L, true, true);
        flow.complete(ticket);
        check(flow.view().expiresMs == -100L && flow.view().expired,
                "Flow consumes the normalized pre-epoch date without rejecting or subtracting again");
        ticket = flow.refresh();
        flow.entitlement(ticket, "account-a", -100L, false, true, 0L, false, true);
        flow.complete(ticket);
        check(!flow.view().expired && ManagedAccountFlow.accessStatus(flow.view()).isEmpty(),
                "Explicitly disabled account is never falsely described as expired");
        ticket = flow.refresh();
        flow.entitlement(ticket, "account-a", null, false, true, 100L, true, false);
        flow.complete(ticket);
        check(!flow.view().expiryConfirmed && !flow.view().expired
                && ManagedAccountFlow.expiry(flow.view()).equals("有效期 未知"),
                "Unconfirmed null remains unknown instead of masquerading as unlimited");
        ticket = flow.refresh();
        flow.entitlement(ticket, "account-a", null, false, true, null, true, true);
        flow.complete(ticket);
        check(flow.view().expiryConfirmed && !flow.view().expired
                && ManagedAccountFlow.accessStatus(flow.view()).isEmpty(),
                "Missing server clock does not fabricate disabled or expired unlimited state");
        flow.close();
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
        check(settings.contains("isOfficialAccount(this.connection)") && settings.contains("showManagedAccount(MainActivity.this.connection)"),
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
        check(verify.contains("check(!candidate.official)") && verify.contains("if (!ports.testConnection(candidate)) throw ProbeRejected()"),
                "Only custom connections use the DAV verification path");
        String commit = method(controller, "fun continueConnection()");
        check(commit.contains("officialSession?.takeIf { hasOfficialAccess(it) }")
                && commit.contains("else if (hasProof()) proof!!.config else savedDraftForRevalidation()")
                && commit.contains("else verify(candidate, pending)")
                && commit.indexOf("verify(candidate, pending)") < commit.indexOf("ports.saveAndActivate(candidate)"),
                "Official commit requires current authenticated access; custom commit requires fresh DAV verification");
        String reads = method(controller, "fun refreshAppreciation()");
        check(reads.contains("client.entitlement()") && reads.contains("client.appreciation()") && reads.contains("client.paymentImage(metadata)"),
                "Shared account page reads current official metadata and image");
        for (String forbidden : new String[] {".register(", ".login(", ".save(", "ports.testConnection(", "ports.saveAndActivate("})
            check(!reads.contains(forbidden), "Appreciation refresh has no mutation/auth/DAV side effect: " + forbidden);
        check(reads.indexOf("client.paymentImage(metadata)") < reads.indexOf("client.entitlement()")
                && reads.contains("client.canReadEntitlement()"), "Public QR loads before optional private metadata");
        check(reads.contains("appreciationFlow !== flow") && reads.contains("appreciationClient !== client")
                && reads.contains("flow.accepts(ticket)") && reads.contains("alive.get()")
                && !reads.contains("foreground.get()"),
                "Delayed account callbacks require a live page and current source generation, not focus");
        String selection = controller.substring(controller.indexOf("private fun officialAccountForDraft()"),
                controller.indexOf("private fun hasOfficialAccess("));
        check(selection.contains("officialSession?.config") && selection.contains("savedOfficialHistory?.first")
                && selection.contains("isOfficial(it) && it == draft()"),
                "Only exact matching authenticated accounts or trusted saved history select account display");
        String publicChoice = method(controller, "fun openAppreciation()");
        check(publicChoice.contains("officialAccountForDraft() ?: return") && publicChoice.contains("refreshAppreciation()"),
                "Appreciation automatically loads only for an eligible account");
        String foreground = method(controller, "fun foreground(");
        check(!foreground.contains("cancelAppreciationRequest()") && !foreground.contains("refreshAppreciation()")
                && foreground.contains("appreciationFlow?.foreground(value)"),
                "Focus changes preserve the account flow and do not cancel or reload its transport");
        check(method(controller, "private fun closeAppreciation()").contains("cancelAppreciationRequest()")
                && method(controller, "private fun cancelAppreciationRequest()").contains("cancelPending()"),
                "Leaving the account page still cancels its transport");
        check(main.contains("session = activity.sharedConnectionSession") && main.contains("this.sharedConnectionSession = retained.session"),
                "Rotation retains the same controller and in-flight authentication");
        String saved = method(main, "protected void onSaveInstanceState(");
        check(!saved.contains("password") && !saved.contains("official") && !saved.contains("session"),
                "Account credentials and controller never enter saved-state Bundle");
        check(screen.contains("controller::refreshAppreciation") && screen.contains("SharedMessages.REFRESH.text()")
                && screen.contains("enabled = appreciation.canRefresh && !appreciation.busy"),
                "Approved refresh uses the existing guarded read and is disabled while busy");
        check(screen.contains("SharedMessages.SUPPORT_INCLUDE_USERNAME") && screen.contains("controller::copyUsername") && screen.contains("SharedMessages.UNVERIFIED_USERNAME"),
                "Shared page has explicit identity copy and labels unverified usernames");
        String restoreEntry = method(main, "private AndroidSyncFacade.OperationResult restoreForCurrentAccount(");
        check(restoreEntry.contains("restoreForCurrentAccount(inspection, snapshotId, replace, null)"),
                "Ordinary restore delegates to the same proof-aware current-account path");
        String restore = method(main, "String snapshotId, boolean replace, ComparisonProofState.Ticket proof)");
        String authority = method(main, "private void requireOfficialSyncAccess(");
        check(authority.contains("isOfficialAccount(account)") && authority.contains(".entitlement()"), "Restore requires current official authority");
        check(authority.contains("this.connection != account") && authority.contains("entitlement.requireSyncAccess()"),
                "Only a stable active official account may begin restore");
        check(!restore.contains("restoreSelectedReadOnly") && restore.contains("owner.restoreSelected(inspection, snapshotId, replace)")
                && restore.indexOf("requireOfficialSyncAccess(account)") < restore.indexOf("owner.restoreSelected"),
                "Inactive official stops before download; active and custom keep existing restore path");
        check(restore.contains("if (proof != null) requireSharedComparisonProof(proof)")
                && restore.contains("proof == null ? sync() : (AndroidSyncFacade) proof.context.facade"),
                "Comparison restore also retains its frozen facade/proof identity");
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
        backgroundPreservesDisplayAndNavigationDiscardsOldResponses();
        liveExpiryUsesElapsedTimeWithoutDesktopBootIdentity();
        failuresAndImageCopiesAreIsolated();
        normalizedExpiryStatusAndUnknownRemainDistinct();
        publicQrSurvivesUnknownAuthentication();
        if (args.length > 0) activityIntegrationGuards(Path.of(args[0]));
        System.out.println("ManagedAccountFlowChecks: " + checks + " checks passed");
    }
}
