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


class AndroidUiFlowContract(unittest.TestCase):
    def test_four_steps_keep_existing_forward_and_back_navigation(self):
        for method, title in (
            ("renderDiscovery", "1 / 4  选择游戏"),
            ("renderPath", "2 / 4  设置存档路径"),
            ("renderConnection", "3 / 4  连接云端"),
            ("renderComparison", "4 / 4  选择存档"),
        ):
            self.assertIn(title, body(MAIN, "private void " + method + "()"))
        self.assertIn("navigate(SyncPage.PATH)", body(MAIN, "void m103x3c55a874("))
        self.assertIn("navigate(SyncPage.DISCOVERY)", body(MAIN, "void m109x58567797("))
        self.assertIn("navigate(SyncPage.CONNECTION)", body(MAIN, "void m110xcdd09dd8("))
        self.assertIn("refreshComparison(SyncPage.COMPARISON)", body(MAIN, "void m18x35802ca1("))
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

    def test_connection_password_can_start_empty_and_is_not_required_by_ui(self):
        self.assertIn('private String draftPassword = ""', MAIN)
        render = body(MAIN, "private void renderConnection()")
        self.assertIn('field("密码", this.draftPassword, true)', render)
        self.assertNotIn("密码可留空", render)
        self.assertNotIn("准备本应用的同步目录", render)
        self.assertNotIn("draftPassword.isEmpty()", render)
        self.assertNotIn("passwordInput.getText().length()", render)
        self.assertIn("setSaveEnabled(false)", body(MAIN, "private EditText field("))

    def test_connection_has_no_static_explanation_and_preserves_settings_back(self):
        render = body(MAIN, "private void renderConnection()")
        self.assertIn('this.onboardingActive ? "上一步" : "返回"', render)
        self.assertIn('this.onboardingActive ? "下一步" : "保存"', render)
        self.assertFalse(re.search(r'^        text\("', render, re.MULTILINE))
        self.assertIn('navigate(this.onboardingActive ? SyncPage.PATH : SyncPage.SETTINGS)',
                      body(MAIN, "void m89x4f84e35a("))
        for token in ('field("WebDAV 地址"', 'field("用户名"', 'field("密码"', 'button("测试连接"'):
            self.assertIn(token, render)

    def test_all_editable_connection_fields_use_executed_proof_policy(self):
        render = body(MAIN, "private void renderConnection()")
        changed = body(render, "public void onTextChanged(")
        before = changed.index("String before = MainActivity.this.draftFingerprint()")
        capture = changed.index("MainActivity.this.captureDraft()")
        invalidate = changed.index("AndroidUiFlowPolicy.invalidateProofOnEdit(")
        continuation = changed.index("AndroidUiFlowPolicy.canContinue(")
        self.assertLess(before, capture)
        self.assertLess(capture, invalidate)
        self.assertLess(invalidate, continuation)
        self.assertIn("MainActivity.this.testedDraft, before, MainActivity.this.draftFingerprint()", changed)
        self.assertIn("MainActivity.this.busy", changed)
        for field in ("endpointInput", "userInput", "passwordInput"):
            self.assertIn("this." + field + ".addTextChangedListener(textWatcher)", render)
        self.assertGreaterEqual(render.count("AndroidUiFlowPolicy.canContinue("), 2)

    def test_leaving_connection_invalidates_test_before_changing_page(self):
        navigate = body(MAIN, "private void navigate(")
        guard = "if (this.page == SyncPage.CONNECTION && syncPage != SyncPage.CONNECTION) this.testedDraft = null;"
        self.assertIn(guard, navigate)
        self.assertLess(navigate.index(guard), navigate.index("this.page = syncPage"))
        self.assertIn("navigate(this.onboardingActive ? SyncPage.PATH : SyncPage.SETTINGS)", body(MAIN, "void m89x4f84e35a("))
        self.assertIn("this.testedDraft = null", body(MAIN, "private void loadDraft()"))
        applied = body(MAIN, "void m18x35802ca1(")
        tested = body(applied, "if (!z)")
        saved = body(applied, "else")
        self.assertIn("this.testedDraft = str", tested)
        self.assertIn("this.testedDraft = null", saved)
        self.assertLess(saved.index("this.testedDraft = null"), saved.index("navigate(SyncPage.COMPARISON)"))
        self.assertLess(saved.index("navigate(SyncPage.COMPARISON)"), saved.index("refreshComparison("))

    def test_apply_requires_current_success_and_failed_dav_cannot_create_proof(self):
        apply = body(MAIN, "private void applyConnection(")
        self.assertIn("strDraftFingerprint.equals(this.testedDraft)", apply)
        self.assertLess(apply.index("this.testedDraft = null"), apply.index("work("))
        connect = body(MAIN, "void lambda$applyConnection$55(")
        self.assertIn(".connect()", connect)
        self.assertIn("!connectionStatusConnect.isSuccess()", connect)
        self.assertIn("throw new IOException", connect)
        worker = body(MAIN, "Object m17xc0060660(")
        self.assertIn("ConnectionUpdatePolicy.apply(", worker)
        self.assertNotIn("this.testedDraft =", worker)
        success = body(MAIN, "void m18x35802ca1(")
        self.assertIn("this.testedDraft =", success)

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
