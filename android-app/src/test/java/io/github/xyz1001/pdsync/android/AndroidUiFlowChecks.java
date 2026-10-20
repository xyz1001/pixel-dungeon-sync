package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.util.Objects;
import pdsync.EmptySetupCompletion;
import pdsync.OnboardingGate;
import pdsync.OnboardingKind;
import pdsync.OnboardingProgress;
import pdsync.presentation.AppPresentation;
import pdsync.presentation.SyncPage;

/** Executes production guards and the unchanged PC/core policy. No device, Root, or network calls. */
public final class AndroidUiFlowChecks {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static String draft(String endpoint, String user, String password, String profile) {
        return endpoint + "\u0000" + user + "\u0000" + password + "\u0000" + profile;
    }

    private static void connectionProofRequiresSuccessfulCurrentTest() {
        String initial = draft("https://dav.example.test/saves", "player", "", "default");
        check(!AndroidUiFlowPolicy.canContinue(null, initial, false), "A new draft has no DAV success proof");
        check(AndroidUiFlowPolicy.canContinue(initial, initial, false), "Successful current test allows next");
        check(!AndroidUiFlowPolicy.canContinue(initial, initial, true), "Busy work disables next");
        check(!AndroidUiFlowPolicy.canContinue(initial, initial + "changed", false), "A mismatched proof never allows next");
        check(!AndroidUiFlowPolicy.canContinue(null, null, false), "Missing draft and proof are not success");

        String unchanged = AndroidUiFlowPolicy.invalidateProofOnEdit(initial, initial, new String(initial));
        check(Objects.equals(initial, unchanged), "Redraw and same-value text callbacks keep valid proof");
        check(AndroidUiFlowPolicy.canContinue(unchanged, initial, false), "Redraw preserves current success");

        String[] edits = {
            draft("https://another.example.test/saves", "player", "", "default"),
            draft("https://dav.example.test/saves", "other", "", "default"),
            draft("https://dav.example.test/saves", "player", "password", "default"),
            draft("https://dav.example.test/saves", "player", "", "other-profile")
        };
        for (String edited : edits) {
            String proof = AndroidUiFlowPolicy.invalidateProofOnEdit(initial, initial, edited);
            check(proof == null, "Every connection field edit clears success");
            check(!AndroidUiFlowPolicy.canContinue(proof, edited, false), "Edited draft requires test");
            proof = AndroidUiFlowPolicy.invalidateProofOnEdit(proof, edited, initial);
            check(!AndroidUiFlowPolicy.canContinue(proof, initial, false), "Reverting a field cannot resurrect success");
        }
    }

    private static void passwordMayBeEmptyAndFailedDavDoesNotSave() throws Exception {
        ConnectionEndpointPolicy.validate("https://dav.example.test/saves", "player", "");
        checks++;
        ConnectionEndpointPolicy.validate("https://dav.example.test/saves", "", "");
        checks++;
        ConnectionEndpointPolicy.validate("http://127.0.0.1:8080/saves", "", "");
        checks++;
        final int[] writes = {0};
        ConnectionUpdatePolicy.apply(false, false, () -> {}, () -> writes[0]++);
        check(writes[0] == 0, "Testing alone does not save a connection");
        try {
            ConnectionUpdatePolicy.apply(true, false, () -> { throw new IOException("DAV failed"); }, () -> writes[0]++);
            throw new AssertionError("Failed DAV test was accepted");
        } catch (IOException expected) {
            check(writes[0] == 0, "Failed DAV cannot save a connection");
        }
    }

    private static void onlyVerifiedEmptyComparisonOffersFinish() {
        String[] kinds = {null, "", "UNKNOWN", "BOTH_EMPTY", "LOCAL_ONLY", "REMOTE_ONLY", "IDENTICAL", "DIFFERENT", "BRANCHED", "BLOCKED"};
        for (String kind : kinds) {
            for (int flags = 0; flags < 64; flags++) {
                boolean verified = (flags & 1) != 0;
                boolean recovery = (flags & 2) != 0;
                boolean pending = (flags & 4) != 0;
                boolean page = (flags & 8) != 0;
                boolean busy = (flags & 16) != 0;
                boolean configured = (flags & 32) != 0;
                boolean empty = "BOTH_EMPTY".equals(kind) && verified && !recovery && !pending;
                check(AndroidUiFlowPolicy.showEmptyCompletion(kind, verified, recovery, pending) == empty,
                        "Empty layout requires a complete current observation: " + kind + "/" + flags);
                check(AndroidUiFlowPolicy.canFinishEmpty(kind, verified, recovery, pending, page, busy, configured)
                                == (empty && page && !busy && configured),
                        "Finish remains guarded after navigation, interruption, or lost configuration");
                if (kind != null && !kind.isEmpty() && !"UNKNOWN".equals(kind)) {
                    OnboardingKind coreKind = OnboardingKind.valueOf(kind);
                    String blocker = verified && !recovery && !pending ? null : "not verified";
                    check(AndroidUiFlowPolicy.canFinishEmpty(kind, verified, recovery, pending, page, busy, configured)
                                    == EmptySetupCompletion.canFinish(coreKind, blocker, page, busy, configured),
                            "Android empty finish agrees with the PC/core gate");
                }
            }
        }
        check("配置完成".equals(EmptySetupCompletion.INSTANCE.getMessage()), "Empty completion uses the PC success message");
    }

    private static void identicalCompletionUsesExactCoreProofAndOneAction() {
        String[] kinds = {null, "", "UNKNOWN", "BOTH_EMPTY", "LOCAL_ONLY", "REMOTE_ONLY", "IDENTICAL", "DIFFERENT", "BRANCHED", "BLOCKED"};
        for (String kind : kinds) {
            for (int flags = 0; flags < 32; flags++) {
                boolean verified = (flags & 1) != 0;
                boolean recovery = (flags & 2) != 0;
                boolean pending = (flags & 4) != 0;
                boolean confirm = (flags & 8) != 0;
                boolean adopt = (flags & 16) != 0;
                AndroidUiFlowPolicy.IdenticalCompletion expected = AndroidUiFlowPolicy.IdenticalCompletion.NONE;
                if ("IDENTICAL".equals(kind) && verified && !recovery && !pending) {
                    if (confirm) expected = AndroidUiFlowPolicy.IdenticalCompletion.CONFIRM;
                    else if (adopt) expected = AndroidUiFlowPolicy.IdenticalCompletion.ADOPT;
                }
                check(AndroidUiFlowPolicy.identicalCompletion(kind, verified, recovery, pending, confirm, adopt) == expected,
                        "Exact hash identity and valid core lineage must select one completion: " + kind + "/" + flags);
            }
        }
        check(AndroidUiFlowPolicy.identicalCompletion("IDENTICAL", true, false, false, true, true)
                        == AndroidUiFlowPolicy.IdenticalCompletion.CONFIRM,
                "SAME_TIP chooses confirmation once even when adoption is also possible");
        check(AndroidUiFlowPolicy.identicalCompletion("IDENTICAL", true, false, false, false, true)
                        == AndroidUiFlowPolicy.IdenticalCompletion.ADOPT,
                "NONE/ANCESTOR uses explicit association rather than fabricating a baseline");
        check(AndroidUiFlowPolicy.identicalCompletion("IDENTICAL", true, false, false, false, false)
                        == AndroidUiFlowPolicy.IdenticalCompletion.NONE,
                "Equal bytes alone do not permit completing an unrelated blocked lineage");
        check(AndroidUiFlowPolicy.identicalCompletion("DIFFERENT", true, false, false, true, true)
                        == AndroidUiFlowPolicy.IdenticalCompletion.NONE,
                "Similar labels, timestamps or counts never suppress genuinely different bytes");
    }

    private static void startupKeepsConfigurationSeparateFromBaseline() {
        check(OnboardingGate.resumedProgress(OnboardingProgress.COMPLETE, true, true) == OnboardingProgress.COMPLETE,
                "Configured trusted target with baseline resumes completed/Home");
        check(OnboardingGate.resumedProgress(OnboardingProgress.LEGACY, true, true) == OnboardingProgress.COMPLETE,
                "Existing verified legacy configuration resumes Home");
        check(OnboardingGate.resumedProgress(OnboardingProgress.COMPLETE, true, false) == OnboardingProgress.COMPLETE,
                "Completed configuration resumes Home without manufacturing a sync baseline");
        for (OnboardingProgress progress : OnboardingProgress.values()) {
            check(OnboardingGate.resumedProgress(progress, false, true) == OnboardingProgress.PENDING,
                    "Untrusted target cannot resume Home: " + progress);
        }
        check(!OnboardingGate.allowsAutomatic(OnboardingProgress.PENDING, false, true), "Pending setup cannot start automatic sync");
        check(!OnboardingGate.allowsAutomatic(OnboardingProgress.COMPLETE, true, true), "Reentered wizard blocks automatic sync");
        check(!OnboardingGate.allowsAutomatic(OnboardingProgress.COMPLETE, false, false), "Home still requires current cloud verification");
        check(OnboardingGate.allowsAutomatic(OnboardingProgress.COMPLETE, false, true), "Verified completed Home keeps existing automatic gate");
    }

    private static void pendingConfigurationCannotSkipConnection() {
        check(OnboardingGate.resumedProgress(OnboardingProgress.PENDING, true, false) == OnboardingProgress.PENDING,
                "New setup remains pending until Step 2 completes");
        check(OnboardingGate.resumedProgress(OnboardingProgress.PENDING, true, true) == OnboardingProgress.PENDING,
                "An old save baseline cannot complete the connection step");
        check(OnboardingGate.resumedProgress(OnboardingProgress.COMPLETE, true, false) == OnboardingProgress.COMPLETE,
                "Two-step completion is independent of save ancestry");
    }

    private static void twoStepPresentationMatchesPc() {
        check("1 / 2  选择游戏".equals(AppPresentation.INSTANCE.getDISCOVERY_TITLE()), "Step one is game selection");
        check("存档位置".equals(AppPresentation.INSTANCE.getPATH_TITLE()), "Standalone path information has no setup step number");
        check("2 / 2  连接云端".equals(AppPresentation.INSTANCE.getCONNECTION_TITLE()), "Step two completes configuration");
    }

    private static void obsoleteRoutesNormalizeAndLiveConfirmationRemains() {
        for (SyncPage page : SyncPage.values()) {
            boolean obsolete = page == SyncPage.PATH || page == SyncPage.COMPARISON;
            SyncPage current = obsolete ? SyncPage.CONNECTION : page;
            SyncPage restored = page == SyncPage.CONFIRM ? SyncPage.HOME : current;
            check(AndroidUiFlowPolicy.currentPage(page) == current,
                    "Only removed path/comparison pages redirect during navigation: " + page);
            check(AndroidUiFlowPolicy.currentPage(AndroidUiFlowPolicy.currentPage(page)) == current,
                    "Repeated render/navigation normalization is stable: " + page);
            check(AndroidUiFlowPolicy.restoredPage(page) == restored,
                    "Restoration also discards an interrupted confirmation: " + page);
            check(AndroidUiFlowPolicy.restoredPage(AndroidUiFlowPolicy.restoredPage(page)) == restored,
                    "Repeated restoration normalization is stable: " + page);
            check(AndroidUiFlowPolicy.currentPage(AndroidUiFlowPolicy.restoredPage(page)) == restored,
                    "Rendering a restored page cannot revive an obsolete route: " + page);
        }
        check(AndroidUiFlowPolicy.currentPage(SyncPage.CONFIRM) == SyncPage.CONFIRM,
                "A live user confirmation must remain reachable");
        check(AndroidUiFlowPolicy.restoredPage(SyncPage.CONFIRM) == SyncPage.HOME,
                "Activity recreation must not reuse a stale confirmation");
    }

    public static void main(String[] args) throws Exception {
        connectionProofRequiresSuccessfulCurrentTest();
        passwordMayBeEmptyAndFailedDavDoesNotSave();
        onlyVerifiedEmptyComparisonOffersFinish();
        identicalCompletionUsesExactCoreProofAndOneAction();
        startupKeepsConfigurationSeparateFromBaseline();
        pendingConfigurationCannotSkipConnection();
        twoStepPresentationMatchesPc();
        obsoleteRoutesNormalizeAndLiveConfirmationRemains();
        System.out.println("Android UI flow: " + checks + " checks passed");
    }
}
