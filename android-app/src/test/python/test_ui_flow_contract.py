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


class AndroidUiFlowContract(unittest.TestCase):
    def test_four_steps_keep_existing_forward_and_back_navigation(self):
        for method, title in (
            ("renderDiscovery", "1 / 4  选择游戏"),
            ("renderPath", "2 / 4  设置存档路径"),
            ("renderComparison", "4 / 4  选择存档"),
        ):
            self.assertIn(title, body(MAIN, "private void " + method + "()"))
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
        path = body(MAIN, "private void renderPath()")
        self.assertIn("text(savePathLabel())", path)
        self.assertIn("this.localPreview.getSlots().size()", path)
        self.assertIn("this.localPreview.getSlots().entrySet()", path)
        self.assertIn("this.localPreview.getPlays()", path)
        self.assertIn("this.localPreview.getFiles()", path)
        self.assertIn("readyForSaves() && this.localPreview != null", path)
        label = body(MAIN, "private String savePathLabel()")
        self.assertIn("this.rootGame.dataDir", label)
        self.assertIn("getApplicationInfo(gamePackage, 0).dataDir", label)
        self.assertIn('"files"', label)

    # Connection and appreciation moved to shared Compose. Runtime state-machine tests
    # live with the shared sources; Android route/adapter/lifecycle proof is in
    # test_shared_connection_android_contract.py, rather than asserting deleted native widgets.

    def test_empty_layout_returns_before_all_normal_comparison_and_transfer_controls(self):
        render = body(MAIN, "private void renderComparison()")
        empty = body(render, "if (showEmptyCompletion())")
        self.assertIn('heading(this.feedbackError ? "4 / 4  配置尚未完成" : "4 / 4  配置完成")', empty)
        self.assertIn('if (!this.feedbackError) text("配置完成")', empty)
        self.assertEqual(["上一步", "完成"], re.findall(r'foot\("([^"]+)"', empty))
        self.assertIn("this::completeEmpty", empty)
        self.assertIn("navigate(SyncPage.CONNECTION)", empty)
        self.assertRegex(empty, r"return;\s*}$")
        self.assertLess(render.index(empty), render.index("text(comparisonCopy())"))
        for operation in ("versionChoice", "getLocalFileCount", "重新比较", "稍后处理", "上传", "恢复", ".upload(", ".restore", "sync()", "readComparison()"):
            self.assertNotIn(operation, empty)

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
        render = body(MAIN, "private void renderComparison()")
        empty = body(render, "if (showEmptyCompletion())")
        identical = body(render, "if (identical != AndroidUiFlowPolicy.IdenticalCompletion.NONE)")
        keep_guard = "if (inspection != null && inspection.getCanKeepLocalProgress())"
        ready_guard = "else if (inspection != null && inspection.getCanCompleteWithoutTransfer())"
        adopt_guard = "else if (!inspection2.getCanKeepLocalProgress() && !inspection2.getCanCompleteWithoutTransfer())"
        keep = body(render, keep_guard)
        ready = body(render, ready_guard)
        adopt = body(render, adopt_guard)

        # BOTH_EMPTY returns early; local-progress and SAME_TIP completion are
        # mutually exclusive. Adoption must not add a second completion button
        # when either earlier branch already supplied one.
        self.assertRegex(empty, r"return;\s*}$")
        self.assertIn('foot("完成", this::completeLocalProgress)', keep)
        self.assertIn('foot("完成", this::completeReadyComparison)', ready)
        self.assertRegex(render, re.escape(keep) + r"\s*" + re.escape(ready_guard))
        self.assertIn('foot("完成", new Runnable()', adopt)
        self.assertIn("MainActivity.this.adopt()", adopt)
        self.assertEqual(1, adopt.count('foot("完成"'))
        remainder = render
        for section in (empty, identical, keep, ready, adopt):
            remainder = remainder.replace(section, "", 1)
        self.assertNotIn('foot("完成"', remainder)

        # NONE/ANCESTOR adoption still exists, but null or non-adoptable
        # inspections take the original restore branch without dereferencing
        # inspection2. Missing cloud proof still returns before any choices.
        unavailable = body(render, "if (inspection2 == null || !inspection2.getCanAdoptRemote())")
        self.assertRegex(render, re.escape(unavailable) + r"\s*" + re.escape(adopt_guard))
        restore = body(unavailable, "if (inspection3 != null && !inspection3.getRemoteVersions().isEmpty())")
        self.assertIn('foot("恢复所选云端存档", this::requestRestore).setEnabled(restoreSupported() && this.selected != null && kind() != OnboardingKind.BLOCKED)', restore)
        unverified = body(render, "if (!this.cloudVerified && kind() != OnboardingKind.BLOCKED)")
        self.assertEqual(["上一步", "重新比较"], re.findall(r'foot\("([^"]+)"', unverified))
        self.assertRegex(unverified, r"return;\s*}$")
        self.assertLess(render.index(unverified), render.index(keep_guard))
        self.assertIn('foot("完成", this::completeEmpty).setEnabled(!this.busy && this.onboardingActive && this.connection != null)', empty)
        self.assertIn("this::upload).setEnabled(kind() != OnboardingKind.BLOCKED || this.inspection.isRetryablePending())", render)

    def test_exact_identical_state_renders_only_one_completion_before_any_choice(self):
        render = body(MAIN, "private void renderComparison()")
        identical = body(render, "if (identical != AndroidUiFlowPolicy.IdenticalCompletion.NONE)")
        self.assertEqual(["完成"], re.findall(r'foot\("([^\"]+)"', identical))
        self.assertIn("identical == AndroidUiFlowPolicy.IdenticalCompletion.CONFIRM", identical)
        self.assertIn("? this::completeReadyComparison : this::adopt", identical)
        self.assertRegex(identical, r"return;\s*}$")
        before = render[:render.index(identical)]
        for evidence in ("AndroidUiFlowPolicy.identicalCompletion(",
                         "kind() == null ? null : kind().name(), this.cloudVerified",
                         "observed.getNeedsRestoreRecovery()", "observed.isRetryablePending()",
                         "observed.getCanCompleteWithoutTransfer()", "observed.getCanAdoptRemote()"):
            self.assertIn(evidence, before)
        for remaining in ('text("本机 · "', "versionChoice(", "LinearLayout linearLayoutRow = row()",
                          '"上传本机，保留两份"', 'foot("恢复所选云端存档"', 'foot("稍后处理"'):
            self.assertLess(render.index(identical), render.index(remaining))
        self.assertNotIn("getLocalFileCount()", before[before.index("AndroidSyncFacade.Inspection observed"):])
        # Completion callbacks keep their original locked core revalidation;
        # rendering a single button cannot fabricate or advance a baseline.
        confirm = body(MAIN, "private void completeReadyComparison()")
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
        self.assertIn("if (!automaticStatus.isEmpty()) text(automaticStatus)", home)
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
