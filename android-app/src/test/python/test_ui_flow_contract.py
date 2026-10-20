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
CONNECTION_SCREEN = (SHARED / "SharedConnectionScreen.kt").read_text()
PATH_STATE = (SHARED / "SharedSavePathState.kt").read_text()
COMPARISON = (SHARED / "SharedComparisonScreen.kt").read_text()
COMPARISON_STATE = (SHARED / "SharedComparisonState.kt").read_text()
ADAPTER = (Path(__file__).resolve().parents[4] / "android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidOnboardingComposition.kt").read_text()


class AndroidUiFlowContract(unittest.TestCase):
    def test_two_steps_go_from_game_to_connection_and_back_to_game(self):
        self.assertIn('title = SharedMessages.CHOOSE_GAME.text(), eyebrow = SharedMessages.STEP_ONE.text()', GAME_SCREEN)
        self.assertIn('showGameSelection(this, gameSelectionState()', body(MAIN, "private void renderDiscovery()"))
        self.assertIn('eyebrow = if (setup && appreciation == null) SharedMessages.STEP_TWO.text() else null', CONNECTION_SCREEN)
        self.assertNotIn("SharedComparisonScreen", ADAPTER)
        forward = body(MAIN, "void m103x3c55a874()")
        self.assertEqual(2, forward.count("navigate(SyncPage.CONNECTION)"))
        for removed in ("SyncPage.PATH", "previewPath(", "requestPathNext("):
            self.assertNotIn(removed, forward)
        back = body(MAIN, "public void onBack(ConnectionEntryModel.Back destination)")
        self.assertIn("navigate(this.onboardingActive ? SyncPage.DISCOVERY : SyncPage.SETTINGS)", back)
        self.assertNotIn("SyncPage.PATH", back)
        self.assertIn("ConnectionEntryModel.Back.STAY", back)
        self.assertIn("ConnectionEntryModel.Back.CHOICE", back)

    def test_connection_completion_renders_home_before_current_save_inspection(self):
        commit = body(MAIN, "private boolean drainSharedCommit()")
        self.assertLess(commit.index("if (committed == null)"), commit.index("this.progress = OnboardingProgress.COMPLETE"))
        self.assertLess(commit.index("this.progress = OnboardingProgress.COMPLETE"), commit.index("this.page = SyncPage.HOME"))
        self.assertLess(commit.index("this.onboardingActive = false"), commit.index("this.page = SyncPage.HOME"))
        self.assertLess(commit.index("this.page = SyncPage.HOME"), commit.index("render();"))
        self.assertLess(commit.index("render();"), commit.index("refreshComparison(SyncPage.HOME)"))
        self.assertIn("this.cloudVerified = false", commit)
        self.assertIn("this.inspection = null", commit)
        for fabricated in ("new SaveSummary", "SaveSummary(", "recordBaseline", "clearBaseline", "adoptRemote", ".upload(", ".restore("):
            self.assertNotIn(fabricated, commit)

    def test_legacy_routes_are_normalized_before_render_or_navigation(self):
        render = body(MAIN, "public void render()")
        self.assertLess(render.index("AndroidUiFlowPolicy.currentPage(this.page)"), render.index("renderSharedConnection()"))
        for obsolete in ("renderSharedSavePath()", "renderSharedComparison()", "schedulePathPreviewEntry()", "previewPath("):
            self.assertNotIn(obsolete, render)
        navigation = body(MAIN, "private void navigate(")
        self.assertLess(navigation.index("AndroidUiFlowPolicy.currentPage(syncPage)"), navigation.index("this.page = syncPage"))
        created = body(MAIN, "public void onCreate(")
        self.assertIn("AndroidUiFlowPolicy.restoredPage(this.page)", created)
        self.assertLess(created.index("AndroidUiFlowPolicy.restoredPage(this.page)"), created.index("render();"))
        for signature in ("private void restoreConnectionUi(", "void m69xebc6eee5("):
            self.assertIn("AndroidUiFlowPolicy.restoredPage(retained.page)", body(MAIN, signature))
        self.assertNotIn("AndroidUiFlowPolicy.restoredPage", render + navigation)

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

    def test_standalone_path_preview_uses_resolved_path_and_actual_slot_play_summary(self):
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







    def test_android_never_creates_details_or_copy_widgets(self):
        render = body(MAIN, "public void render()")
        for removed in ('detailsExpanded', 'text(this.details)', 'copyDiagnostic', '详情', '复制诊断', 'SharedMessages.DETAILS.text()', 'SharedMessages.COPY_DIAGNOSTICS.text()'):
            self.assertNotIn(removed, render)
        self.assertNotIn("SyncPage.COMPARISON", render)
        self.assertNotIn('withDiagnosticToolbar', MAIN)
        self.assertIn('wizardDiagnosticAction()', MAIN)

    def test_short_home_automatic_status_preserves_real_service_and_conflict_result(self):
        home = body(MAIN, "private void renderHome()")
        self.assertIn("homeStatus(content)", home)
        home = body(MAIN, "private String homeStatus(")
        self.assertIn("automaticStatusBrief()", home)
        self.assertIn('if (!automaticStatus.isEmpty()) homeStatus += "\\n" + automaticStatus', home)
        brief = body(MAIN, "private String automaticStatusBrief()")
        self.assertIn("AutoSyncService.status(this)", brief)
        self.assertIn('switch (AutoSyncService.resultKind(this))', brief)
        self.assertIn('case "HISTORY_CONFLICT":', brief)
        self.assertNotIn('result.contains(', brief)
        self.assertIn('A_SAVE_CONFLICT_EXISTS_AUTO_UPLOAD_115.text()', brief)
        self.assertIn("this.details = safeText(result)", brief)
        self.assertIn("return result;", brief)
        self.assertIn("SyncRuntime.isAutoUploadPaused(this)", brief)
        self.assertIn("this.progress != OnboardingProgress.COMPLETE", brief)
        for operation in (".upload(", "startService(", ".connect()", "refreshComparison("):
            self.assertNotIn(operation, brief)


if __name__ == "__main__":
    unittest.main()
