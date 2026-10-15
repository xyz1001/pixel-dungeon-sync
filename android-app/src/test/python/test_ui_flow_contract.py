"""Activity wiring checks paired with executed production policy/core tests.

These checks cover render structure and Android callbacks that cannot run on the host JVM.
They do not claim device or visual UI coverage.
"""
import re
import unittest
from pathlib import Path

from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / "main/java/io/github/xyz1001/pdsync/android"
MAIN = (ROOT / "MainActivity.java").read_text()
STORE = (ROOT / "SecureConnectionStore.java").read_text()
CONNECTION_FILE = (ROOT / "WebDavConnectionFile.java").read_text()
SHARED = Path(__file__).resolve().parents[4] / "shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android"
GAME_SCREEN = (SHARED / "SharedGameSelectionScreen.kt").read_text()
PATH_SCREEN = (SHARED / "SharedSavePathScreen.kt").read_text()
PATH_STATE = (SHARED / "SharedSavePathState.kt").read_text()
COMPARISON = (SHARED / "SharedComparisonScreen.kt").read_text()
COMPARISON_STATE = (SHARED / "SharedComparisonState.kt").read_text()
ADAPTER = (Path(__file__).resolve().parents[4] / "android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidOnboardingComposition.kt").read_text()


class AndroidUiFlowContract(unittest.TestCase):
    def test_four_steps_keep_existing_forward_and_back_navigation(self):
        self.assertIn('title = "选择游戏", eyebrow = "1 / 4"', GAME_SCREEN)
        self.assertIn('showGameSelection(this, gameSelectionState()', body(MAIN, "private void renderDiscovery()"))
        self.assertIn('title = "设置存档路径", eyebrow = "2 / 4"', PATH_SCREEN)
        self.assertIn('title = "选择存档", eyebrow = "4 / 4"', COMPARISON)
        self.assertIn("navigate(SyncPage.PATH)", body(MAIN, "void m103x3c55a874("))
        self.assertIn("navigate(SyncPage.DISCOVERY)", body(MAIN, "void m109x58567797("))
        self.assertIn("navigate(SyncPage.CONNECTION)", body(MAIN, "void m110xcdd09dd8("))
        self.assertIn("refreshComparison(SyncPage.COMPARISON)", body(MAIN, "private boolean drainSharedCommit()"))
        self.assertIn("navigate(SyncPage.CONNECTION)", body(MAIN, "void m80x76ddc5af("))

    def test_first_step_keeps_installed_game_and_apk_pickers(self):
        discovery = body(MAIN, "private void renderDiscovery()")
        self.assertIn("chooseInstalled()", discovery)
        self.assertIn("pickApks()", discovery)
        self.assertIn("InstalledAppPickerDialog", body(MAIN, "public void chooseInstalled()"))
        picker = body(MAIN, "public void pickApks()")
        self.assertIn("android.intent.action.OPEN_DOCUMENT", picker)
        self.assertIn("android.intent.extra.ALLOW_MULTIPLE", picker)
        self.assertIn("application/vnd.android.package-archive", picker)
        self.assertIn("PICK_APK", MAIN)

    def test_path_preview_uses_resolved_path_and_actual_slot_play_summary(self):
        path = body(MAIN, "private void renderSharedSavePath()")
        self.assertIn("showSavePath(this, savePathLabel(), this.localPreview", path)
        for actual_summary in ("it.slots.size", "it.slots.map", "it.plays", "it.files"):
            self.assertIn(actual_summary, PATH_STATE)
        self.assertIn("savePathSummary(state.summary)", PATH_SCREEN)
        self.assertIn("readyForSaves() && this.localPreview != null", path)
        label = body(MAIN, "private String savePathLabel()")
        self.assertIn("this.rootGame.dataDir", label)
        self.assertIn("getApplicationInfo(gamePackage, 0).dataDir", label)
        self.assertIn('"files"', label)

    # Connection and appreciation moved to shared Compose. Runtime state-machine tests
    # live with the shared sources; Android route/adapter/lifecycle proof is in
    # test_shared_connection_android_contract.py, rather than asserting deleted native widgets.

    def test_empty_layout_has_one_shared_primary_and_no_native_transfer_controls(self):
        route = body(MAIN, "public void render()")
        shared = body(route, "if (this.page == SyncPage.COMPARISON)")
        self.assertIn("renderSharedComparison()", shared)
        self.assertIn("return;", shared)
        self.assertLess(route.index(shared), route.index("ScrollView scrollView"))
        simple = body(COMPARISON, "if (state.isSimpleCompletion)")
        self.assertIn("Text(state.headline", simple)
        for operation in ("ComparisonChoiceCard", "upload", "restore", "稍后处理"):
            self.assertNotIn(operation, simple)
        self.assertEqual(1, COMPARISON.count("SharedActionButton(state.primaryLabel"))
        self.assertIn("SharedComparisonCompletion.EMPTY", COMPARISON_STATE)
        self.assertIn("case EMPTY: if (kind() == OnboardingKind.BOTH_EMPTY) completeEmpty()",
                      body(MAIN, "private void finishSharedComparison("))

    def test_empty_layout_and_completion_use_current_observation_guard(self):
        empty = body(MAIN, "private boolean showEmptyCompletion()")
        self.assertIn("AndroidUiFlowPolicy.showEmptyCompletion(", empty)
        for evidence in ("kind().name()", "this.cloudVerified", "getNeedsRestoreRecovery()", "isRetryablePending()"):
            self.assertIn(evidence, empty)
        complete = body(MAIN, "public void completeEmpty()")
        self.assertIn("AndroidUiFlowPolicy.canFinishEmpty(", complete)
        self.assertIn("this.page == SyncPage.COMPARISON && this.onboardingActive", complete)
        self.assertIn("this.busy", complete)
        self.assertLess(complete.index("AndroidUiFlowPolicy.canFinishEmpty("), complete.index("work("))

    def test_comparison_completion_is_exclusive_and_preserves_unavailable_actions(self):
        dispatch = body(MAIN, "private void finishSharedComparison(")
        for mode in ("EMPTY", "CONFIRM", "ADOPT", "KEEP_LOCAL", "UPLOAD_LOCAL", "RESTORE_REMOTE"):
            self.assertIn("case " + mode + ":", dispatch)
        self.assertIn("if (!usableSharedComparison()", dispatch)
        for actual in ("observed?.needsRestoreRecovery == true", "observed?.isRetryablePending == true",
                       "observed?.canCompleteWithoutTransfer == true", "observed?.canAdoptRemote == true",
                       "observed?.canKeepLocalProgress == true"):
            self.assertIn(actual, ADAPTER)
        self.assertIn("needsRestoreRecovery -> SharedComparisonPrimary.OPEN_RECOVERY", COMPARISON_STATE)
        self.assertIn("retryablePending -> SharedComparisonPrimary.RETRY_UPLOAD", COMPARISON_STATE)
        self.assertIn("!hasObservation -> SharedComparisonPrimary.REFRESH", COMPARISON_STATE)
        self.assertIn("readiness", body(MAIN, "void m55x8211a061("))
        self.assertNotIn("foot(", body(MAIN, "private void renderSharedComparison()"))

    def test_exact_identical_state_renders_only_one_completion_before_any_choice(self):
        self.assertIn("kind == OnboardingKind.IDENTICAL && canCompleteWithoutTransfer", COMPARISON_STATE)
        self.assertIn("kind == OnboardingKind.IDENTICAL && canAdoptRemote", COMPARISON_STATE)
        self.assertIn("!(isSimpleCompletion && kind == OnboardingKind.IDENTICAL)", COMPARISON_STATE)
        simple = body(COMPARISON, "if (state.isSimpleCompletion)")
        self.assertNotIn("ComparisonChoiceCard", simple)
        self.assertNotIn("SharedActionButton", simple)
        dispatch = body(MAIN, "private void finishSharedComparison(")
        self.assertIn("kind() == OnboardingKind.IDENTICAL && this.inspection.getCanCompleteWithoutTransfer()", dispatch)
        self.assertIn("kind() == OnboardingKind.IDENTICAL && this.inspection.getCanAdoptRemote()", dispatch)
        confirm = body(MAIN, "private void completeReadyComparison()")
        self.assertIn("requireSharedComparisonProof(proof)", confirm)
        self.assertIn("owner.completeOnboarding(observed)", confirm)
        self.assertLess(confirm.index("owner.completeOnboarding(observed)"),
                        confirm.index("SyncRuntime.setOnboardingProgress"))
        self.assertIn("sync().adoptIdentical(inspection)",
                      body(MAIN, "AndroidSyncFacade.OperationResult m15lambda$adopt$72$"))

    def test_empty_finish_revalidates_and_records_only_a_real_no_transfer_decision(self):
        finish = body(MAIN, "Object m29xb53dc5d7()")
        self.assertLess(finish.index("readComparison()"), finish.index("showEmptyCompletion()"))
        self.assertLess(finish.index("EmptySetupCompletion.canFinish"), finish.index(".completeOnboarding(this.inspection)"))
        self.assertLess(finish.index(".completeOnboarding(this.inspection)"), finish.index("SyncRuntime.setOnboardingProgress"))
        self.assertIn("OnboardingProgress.COMPLETE", finish)
        for operation in (".upload(", ".restore", "createSnapshot", "createCommit"):
            self.assertNotIn(operation, finish)
        completed = body(MAIN, "void m30x2ab7ec18(")
        self.assertIn("this.onboardingActive = false", completed)
        self.assertIn("this.page = SyncPage.HOME", completed)

    def test_startup_keeps_pc_core_baseline_gate_before_home_selection(self):
        startup = body(MAIN, "Object m68x764cc8a4(")
        self.assertLess(startup.index("readComparison()"), startup.index("OnboardingGate.resumedProgress"))
        self.assertIn("syncHistory.getBaselineCommit() == null", startup)
        self.assertIn("this.progress = onboardingProgressResumedProgress", startup)
        route = body(MAIN, "void m69xebc6eee5(")
        self.assertIn("this.progress != OnboardingProgress.COMPLETE", route)
        self.assertIn("this.page = this.onboardingActive ? SyncPage.COMPARISON : SyncPage.HOME", route)

    def test_diagnostic_copy_is_created_only_inside_expanded_details(self):
        render = body(MAIN, "public void render()")
        expanded = body(render, "if (this.detailsExpanded)")
        self.assertIn("text(this.details)", expanded)
        self.assertIn("this::copyDiagnostic", expanded)
        self.assertNotIn("copyDiagnostic", render.replace(expanded, ""))
        self.assertNotIn("复制脱敏诊断", render)
        self.assertIn("!(this.page == SyncPage.COMPARISON && showEmptyCompletion() && !this.feedbackError)", render)
        self.assertIn("this.detailsExpanded = false", body(MAIN, "private void navigate("))

    def test_short_home_automatic_status_preserves_real_service_and_conflict_result(self):
        home = body(MAIN, "private void renderHome()")
        self.assertIn("automaticStatusBrief()", home)
        self.assertIn('if (!automaticStatus.isEmpty()) homeStatus += "\\n" + automaticStatus', home)
        brief = body(MAIN, "private String automaticStatusBrief()")
        self.assertIn("AutoSyncService.status(this)", brief)
        self.assertIn('result.contains("HISTORY_CONFLICT")', brief)
        self.assertIn("有存档冲突，自动上传已跳过。", brief)
        self.assertIn("this.details = safeText(result)", brief)
        self.assertIn("return result;", brief)
        self.assertIn("SyncRuntime.isAutoUploadPaused(this)", brief)
        self.assertIn("this.progress != OnboardingProgress.COMPLETE", brief)
        for operation in (".upload(", "startService(", ".connect()", "refreshComparison("):
            self.assertNotIn(operation, brief)


if __name__ == "__main__":
    unittest.main()
