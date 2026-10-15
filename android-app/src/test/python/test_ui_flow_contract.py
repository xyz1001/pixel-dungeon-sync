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
    def test_three_steps_keep_existing_forward_and_back_navigation(self):
        self.assertIn('title = "选择游戏", eyebrow = "1 / 3"', GAME_SCREEN)
        self.assertIn('showGameSelection(this, gameSelectionState()', body(MAIN, "private void renderDiscovery()"))
        self.assertIn('title = "设置存档路径", eyebrow = "2 / 3"', PATH_SCREEN)
        self.assertNotIn("SharedComparisonScreen", ADAPTER)
        self.assertIn("navigate(SyncPage.PATH)", body(MAIN, "void m103x3c55a874("))
        self.assertIn("navigate(SyncPage.DISCOVERY)", body(MAIN, "void m109x58567797("))
        self.assertIn("navigate(SyncPage.CONNECTION)", body(MAIN, "void m110xcdd09dd8("))
        self.assertIn("refreshComparison(SyncPage.HOME)", body(MAIN, "private boolean drainSharedCommit()"))

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







    def test_diagnostic_copy_is_created_only_inside_expanded_details(self):
        render = body(MAIN, "public void render()")
        expanded = body(render, "if (this.detailsExpanded)")
        self.assertIn("text(this.details)", expanded)
        self.assertIn("this::copyDiagnostic", expanded)
        self.assertNotIn("copyDiagnostic", render.replace(expanded, ""))
        self.assertNotIn("复制脱敏诊断", render)
        self.assertNotIn("SyncPage.COMPARISON", render)
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
