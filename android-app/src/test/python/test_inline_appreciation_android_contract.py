"""Source contracts for Android Step 4's retained, read-only appreciation panel.

These inspect lifecycle wiring, not real Android rotation or document-provider execution.
Controller/image-export runtime behavior is covered by the shared host tests.
"""
import re
import unittest
from pathlib import Path
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / "android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java").read_text()
ANDROID = REPO / "android-app/src/main/kotlin/io/github/xyz1001/pdsync/android"
ADAPTER = (ANDROID / "AndroidConnectionComposition.kt").read_text()
ONBOARDING = (ANDROID / "AndroidOnboardingComposition.kt").read_text()
CONTROLLER = (REPO / "shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedConnectionController.kt").read_text()


class InlineAppreciationAndroidContract(unittest.TestCase):
    def test_step_four_embeds_optional_panel_without_new_navigation(self):
        render = body(MAIN, "private void renderSharedComparison()")
        self.assertIn("appreciationSession = comparisonAppreciationSession()", render)
        self.assertIn("appreciationSession, new SharedComparisonActions()", render)
        adapter = body(ONBOARDING, "fun showComparison(")
        self.assertIn("appreciationSession?.let { session ->", adapter)
        self.assertIn("SharedInlineAppreciation(session.controller,", adapter)
        self.assertIn("SharedComparisonScreen(state, actions, appreciation = appreciation)", adapter)
        for forbidden in ("showManagedAccount", "sharedAppreciationOnly = true", "SharedConnectionScreen("):
            self.assertNotIn(forbidden, render + adapter)

    def test_only_durable_official_account_can_create_a_session(self):
        session = body(MAIN, "private AndroidConnectionComposition.Session comparisonAppreciationSession()")
        exclusion = body(session, "if (!isOfficialAccount(source) || source.isTemporary())")
        self.assertIn("closeComparisonAppreciation()", exclusion)
        self.assertIn("return null", exclusion)
        self.assertLess(session.index("!isOfficialAccount(source)"), session.index("createReadOnlyAppreciation("))
        official = body(MAIN, "private static boolean isOfficialAccount(")
        self.assertIn("PROVIDER_OFFICIAL.equals(account.provider)", official)
        self.assertIn("DAV_URL.equals(account.endpoint)", official)
        self.assertIn("!account.username.isEmpty()", official)

    def test_same_account_rerenders_reuse_session_and_replacement_closes_old_owner(self):
        session = body(MAIN, "private AndroidConnectionComposition.Session comparisonAppreciationSession()")
        self.assertEqual(session.count("createReadOnlyAppreciation("), 1)
        self.assertIn("if (this.initialLoad) return this.sharedComparisonAppreciationSession", session)
        for field in ("endpoint", "username", "password", "profile", "provider"):
            self.assertIn(f"source.{field}.equals(previous.{field})", session)
        replacement = body(session, "if (previous == null")
        self.assertLess(replacement.index("closeComparisonAppreciation()"), replacement.index("createReadOnlyAppreciation("))
        route = body(MAIN, "public void render()")
        self.assertIn("if (this.page != SyncPage.COMPARISON || this.sharedAppreciationOnly) closeComparisonAppreciation()", route)
        close_other = body(MAIN, "private void closeSharedConnection()")
        self.assertNotIn("closeComparisonAppreciation()", close_other)
        self.assertIn("if (this.sharedComparisonAppreciationSession == null) this.retainedConnectionUi = null", close_other)

    def test_rotation_retains_the_exact_export_and_account_without_activity(self):
        retained = body(MAIN, "private static final class RetainedConnectionUi")
        self.assertIn("comparisonAppreciationSession = activity.sharedComparisonAppreciationSession", retained)
        self.assertIn("comparisonAppreciationAccount = activity.sharedComparisonAppreciationAccount", retained)
        self.assertNotRegex(retained, r"(?:final|private|public)\s+(?:MainActivity|Activity|Context|View)\s+\w+\s*[;=]")
        restore = body(MAIN, "private void restoreConnectionUi(")
        self.assertIn("this.sharedComparisonAppreciationSession = retained.comparisonAppreciationSession", restore)
        self.assertIn("this.sharedComparisonAppreciationAccount = retained.comparisonAppreciationAccount", restore)
        self.assertIn("this.sharedComparisonAppreciationSession.attachReadOnlyHost()", restore)
        retain = body(MAIN, "public Object onRetainCustomNonConfigurationInstance()")
        self.assertIn("this.sharedComparisonAppreciationSession == null", retain)
        self.assertIn("this.sharedComparisonAppreciationSession.getController().detach()", retain)
        for forbidden in ("createReadOnlyAppreciation", "openAppreciation", "refreshAppreciation", "requestImageSave"):
            self.assertNotIn(forbidden, retain + restore)
        saved = body(MAIN, "protected void onSaveInstanceState(")
        for secret in ("appreciation", "password", "session", "credential"):
            self.assertNotIn(secret, saved.lower())

    def test_pause_resume_never_refetch_or_close_the_panel(self):
        pause = body(MAIN, "protected void onPause()")
        resume = body(MAIN, "protected void onResume()")
        self.assertIn("sharedComparisonAppreciationSession.getController().foreground(false)", pause)
        self.assertIn("sharedComparisonAppreciationSession.getController().foreground(true)", resume)
        for forbidden in ("closeComparisonAppreciation", "openAppreciation", "refreshAppreciation", "createReadOnlyAppreciation"):
            self.assertNotIn(forbidden, pause + resume)
        foreground = body(CONTROLLER, "fun foreground(")
        self.assertNotIn("refreshAppreciation", foreground)
        self.assertNotIn("cancelAppreciationRequest", foreground)
        self.assertIn("if (!entered)", foreground)

    def test_actual_leave_or_final_destroy_closes_export_and_controller(self):
        navigate = body(MAIN, "private void navigate(")
        self.assertIn("if (syncPage != SyncPage.COMPARISON) closeComparisonAppreciation()", navigate)
        destroy = body(MAIN, "protected void onDestroy()")
        self.assertIn("if (!isChangingConfigurations()) closeComparisonAppreciation()", destroy)
        close = body(MAIN, "private void closeComparisonAppreciation()")
        self.assertIn("this.sharedComparisonAppreciationSession = null", close)
        self.assertIn("this.sharedComparisonAppreciationAccount = null", close)
        self.assertIn("session.close()", close)
        session_close = body(ADAPTER, "override fun close()")
        for action in ("imageExport.close()", "controller.detach()", "controller.close()", "io.shutdownNow()"):
            self.assertIn(action, session_close)

    def test_read_only_ports_deny_activation_before_any_runtime_or_store_write(self):
        factory = body(ADAPTER, "fun createReadOnlyAppreciation(")
        self.assertIn("ConnectionEntryModel.Origin.SETTINGS", factory)
        self.assertIn("AndroidConnectionPorts(context.applicationContext, readOnly = true)", factory)
        persist = body(ADAPTER, "private fun persistAndActivate(")
        for write in ("SecureConnectionStore(app).save(candidate)", "AutoSyncService.cancelForConnectionChange(app)",
                      "SyncRuntime.setConnection(candidate)", "SyncRuntime.setOnboardingProgress("):
            self.assertLess(persist.index("check(!readOnly)"), persist.index(write))
        probe = body(ADAPTER, "override fun testConnection(")
        self.assertLess(probe.index("check(!readOnly)"), probe.index("SyncRuntime.facade(app, candidate).connect()"))
        host = body(ADAPTER, "private object ReadOnlyHost")
        for callback in ("onBack(destination: ConnectionEntryModel.Back)", "onCommitted(config: SharedConnectionConfig)",
                         "onBusyChanged(busy: Boolean)"):
            self.assertIn(f"override fun {callback} = Unit", host)

    def test_panel_busy_never_disables_comparison_finish(self):
        adapter = body(ONBOARDING, "fun showComparison(")
        self.assertIn("busy = busy,", adapter)
        self.assertIn("imageSaveBusy = imageSave.busy", adapter)
        for method in ("private boolean isCurrentComparisonAction(", "private void finishSharedComparison(",
                       "private boolean usableSharedComparison("):
            self.assertNotIn("appreciation", body(MAIN, method).lower())

    def test_same_system_picker_and_frozen_image_are_used_by_both_hosts(self):
        adapter = body(ONBOARDING, "fun showComparison(")
        self.assertLess(adapter.index("rememberImageSave(appreciationSession)"),
                        adapter.index("appreciationSession?.let"))
        self.assertIn("rememberImageSave(session)", body(ADAPTER, "fun show("))
        picker = body(ADAPTER, "internal fun rememberImageSave(")
        self.assertIn('ActivityResultContracts.CreateDocument("image/png")', picker)
        self.assertIn("session?.selectedImageDestination(it)", picker)
        self.assertIn("remember(session, image, source)", picker)
        self.assertIn("messageFor(source, png)", picker)
        request = ADAPTER[ADAPTER.index("fun requestImageSave()"):ADAPTER.index("fun selectedImageDestination(")]
        self.assertIn("controller.currentAppreciationPng(), controller.currentAppreciationScope()", request)
        for forbidden in ("compress(", "refreshAppreciation", "paymentImage(", "appreciation()"):
            self.assertNotIn(forbidden, picker + request)


if __name__ == "__main__":
    unittest.main()
