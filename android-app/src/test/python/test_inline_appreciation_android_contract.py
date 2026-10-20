"""Step 2/settings retained image-export contracts, not Android device execution."""
import unittest
from pathlib import Path
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / "android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java").read_text()
ANDROID = REPO / "android-app/src/main/kotlin/io/github/xyz1001/pdsync/android"
ADAPTER = (ANDROID / "AndroidConnectionComposition.kt").read_text()
ONBOARDING = (ANDROID / "AndroidOnboardingComposition.kt").read_text()
CONTROLLER = (REPO / "shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedConnectionController.kt").read_text()
SCREEN = (REPO / "shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedConnectionScreen.kt").read_text()


class InlineAppreciationAndroidContract(unittest.TestCase):
    def test_no_fourth_page_or_second_appreciation_session_remains(self):
        for old in ("sharedComparisonAppreciation", "comparisonAppreciationSession", "createReadOnlyAppreciation",
                    "ReadOnlyHost", "SharedComparisonScreen", "showComparison("):
            self.assertNotIn(old, MAIN + ADAPTER + ONBOARDING)
        self.assertIn("SharedAppreciationContent(", SCREEN)
        self.assertIn("controller.openInlineAppreciation()", SCREEN)
        self.assertIn("state.showInlineAppreciation", SCREEN)

    def test_rotation_retains_exact_connection_session_without_activity(self):
        retained = body(MAIN, "private static final class RetainedConnectionUi")
        self.assertIn("session = activity.sharedConnectionSession", retained)
        self.assertIn("pendingCommit = activity.pendingSharedCommit", retained)
        self.assertNotRegex(retained, r"(?:final|private|public)\s+(?:MainActivity|Activity|Context|View)\s+\w+\s*[;=]")
        restore = body(MAIN, "private void restoreConnectionUi(")
        self.assertIn("this.sharedConnectionSession = retained.session", restore)
        self.assertIn("getController().attach(this)", restore)
        retain = body(MAIN, "public Object onRetainCustomNonConfigurationInstance()")
        self.assertIn("this.sharedConnectionSession.getController().detach()", retain)
        for forbidden in ("openAppreciation", "refreshAppreciation", "requestImageSave", "AndroidConnectionComposition.create"):
            self.assertNotIn(forbidden, retain + restore)
        saved = body(MAIN, "protected void onSaveInstanceState(")
        for secret in ("appreciation", "password", "session", "credential"):
            self.assertNotIn(secret, saved.lower())

    def test_pause_resume_preserve_qr_and_returned_document_picker(self):
        pause = body(MAIN, "protected void onPause()")
        resume = body(MAIN, "protected void onResume()")
        self.assertIn("sharedConnectionSession.getController().foreground(false)", pause)
        self.assertIn("sharedConnectionSession.getController().foreground(true)", resume)
        for forbidden in ("closeSharedConnection", "refreshAppreciation", "requestImageSave"):
            self.assertNotIn(forbidden, pause + resume)
        foreground = body(CONTROLLER, "fun foreground(")
        self.assertNotIn("refreshAppreciation", foreground)
        self.assertNotIn("cancelAppreciationRequest", foreground)

    def test_actual_leave_and_final_destroy_close_export_controller_and_executor(self):
        self.assertIn("if (syncPage != SyncPage.CONNECTION) closeSharedConnection()", body(MAIN, "private void navigate("))
        self.assertIn("if (!isChangingConfigurations()) closeSharedConnection()", body(MAIN, "protected void onDestroy()"))
        self.assertIn("session.close()", body(MAIN, "private void closeSharedConnection()"))
        close = body(ADAPTER, "override fun close()")
        for action in ("imageExport.close()", "controller.detach()", "controller.close()", "io.shutdownNow()"):
            self.assertIn(action, close)

    def test_picker_is_unconditional_and_saves_frozen_png_for_setup_and_settings(self):
        show = body(ADAPTER, "fun show(")
        self.assertLess(show.index("rememberImageSave(session)"), show.index("SharedConnectionScreen("))
        picker = body(ADAPTER, "internal fun rememberImageSave(")
        self.assertIn('ActivityResultContracts.CreateDocument("image/png")', picker)
        self.assertIn("session?.selectedImageDestination(it)", picker)
        self.assertIn("remember(session, image, source)", picker)
        self.assertIn("messageFor(source, png)", picker)
        self.assertIn("controller.currentAppreciationPng(), controller.currentAppreciationScope()", ADAPTER)
        for forbidden in ("compress(", "refreshAppreciation", "paymentImage("):
            self.assertNotIn(forbidden, picker)
        settings = body(MAIN, "private void showManagedAccount(")
        self.assertIn("ConnectionEntryModel.Origin.SETTINGS", settings)
        self.assertIn("getController().openAppreciationOnly()", settings)


if __name__ == "__main__":
    unittest.main()
