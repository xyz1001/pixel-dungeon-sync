"""Standalone legacy preview safety; no claim of device/lifecycle execution.

Production request lifecycle is executed by PathPreviewStateChecks and shared UI by JVM tests.
The wizard does not mount this screen. Its private preview helpers retain save-access proof.
"""
import unittest
from pathlib import Path
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / "android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java").read_text()
ADAPTER = (REPO / "android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidOnboardingComposition.kt").read_text()
SCREEN = (REPO / "shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedSavePathScreen.kt").read_text()


class SharedSavePathAndroidContract(unittest.TestCase):
    def test_removed_setup_step_is_not_mounted_by_render(self):
        route = body(MAIN, "public void render()")
        self.assertIn("AndroidUiFlowPolicy.currentPage(this.page)", route)
        for removed in ("renderSharedSavePath()", "schedulePathPreviewEntry()", "previewPath("):
            self.assertNotIn(removed, route)
        forward = body(MAIN, "void m103x3c55a874()")
        self.assertNotIn("SyncPage.PATH", forward)
        self.assertNotIn("previewPath(", forward)
        self.assertIn("renderSharedConnection()", route)
        # This standalone, read-only adapter remains usable without becoming a setup route.
        self.assertIn("activity.setContent", ADAPTER)
        self.assertIn("SharedSavePathScreen(state, actions, game)", ADAPTER)
        self.assertIn("AndroidAppViewport {", ADAPTER)
        for renderer in ("renderDiscovery()", "renderHome()"):
            self.assertIn(renderer, route)

    def test_android_is_read_only_and_uses_resolved_path_and_actual_summary(self):
        for capability in ("editable = false", "canChooseDirectory = false", "canUseDefault = false"):
            self.assertIn(capability, ADAPTER)
        for disabled in ("override fun editPath(path: String) = Unit",
                         "override fun chooseDirectory() = Unit", "override fun useDefault() = Unit"):
            self.assertIn(disabled, ADAPTER)
        render = body(MAIN, "private void renderSharedSavePath()")
        self.assertIn("showSavePath(this, savePathLabel(), this.localPreview", render)
        self.assertIn("readyForSaves() && this.localPreview != null", render)
        for fabricated in ("new SaveSummary", "SaveSummary(", "Summary(", "File(", "SyncRuntime."):
            self.assertNotIn(fabricated, body(ADAPTER, "fun showSavePath("))

    def test_operation_start_and_system_back_update_shared_busy_state(self):
        work = body(MAIN, "private <T> void work(DiagnosticAction action, String str, final Task<T> task, final Done<T> done, boolean preserveAutomaticHistory)")
        branch = body(work, "if (this.page == SyncPage.PATH && !this.sharedAppreciationOnly)")
        self.assertIn("renderSharedSavePath()", branch)
        self.assertNotIn("this.status", branch)
        self.assertNotIn("disable(", branch)
        self.assertLess(work.index("this.busy = true"), work.index(branch))
        self.assertLess(work.index(branch), work.index("this.f9io.execute"))
        back = body(MAIN, "public void onBackPressed()")
        self.assertIn("if (this.page == SyncPage.PATH && !this.sharedAppreciationOnly) renderSharedSavePath()", back)
        self.assertIn("else if (this.status != null)", back)
        fallback = body(work, "else if (this.status == null || this.root == null || this.renderedPage != this.page)")
        self.assertIn("render()", fallback)
        self.assertNotIn("this.status.", fallback)
        self.assertNotIn("disable(", fallback)
        route = body(MAIN, "public void render()")
        self.assertIn("MainActivity.this.scroll != renderedScroll || MainActivity.this.isDestroyed()", route)

    def test_only_one_guarded_activity_entry_event_can_auto_preview(self):
        render = body(MAIN, "private void renderSharedSavePath()")
        self.assertIn("schedulePathPreviewEntry()", render)
        schedule = body(MAIN, "private void schedulePathPreviewEntry()")
        for guard in ("this.rootGame != null", "this.localPreview != null", "this.pathPreviewState.attempted()",
                      "this.busy", "this.page != SyncPage.PATH", "!this.resumed", "!readyForSaves()",
                      "isDestroyed()", "this.pathPreviewPosted"):
            self.assertIn(guard, schedule)
        self.assertLess(schedule.index("this.pathPreviewPosted = true"), schedule.index(".post(this.pathPreviewEntry)"))
        callback = body(MAIN, "void m111x434ac419()")
        for guard in ("this.rootGame != null", "this.page != SyncPage.PATH", "this.localPreview != null",
                      "this.pathPreviewState.attempted()", "this.busy", "!this.resumed", "!readyForSaves()", "isDestroyed()"):
            self.assertIn(guard, callback)
        self.assertIn("previewPath(false)", callback)
        for lifecycle in ("protected void onPause()", "protected void onDestroy()"):
            self.assertIn("invalidatePathPreview()", body(MAIN, lifecycle))
        self.assertIn("removeCallbacks(this.pathPreviewEntry)", body(MAIN, "private void cancelPathPreviewEntry()"))
        # The settings-only modal may request initial text focus, but no composable starts a
        # save read or authorization. The display-only path screen remains effect-free.
        standalone = SCREEN.split("/** Settings-only editor.")[0]
        for forbidden in ("LaunchedEffect(", "SideEffect(", "previewPath", "requestRootAuthorization"):
            self.assertNotIn(forbidden, ADAPTER + standalone)
        self.assertEqual(1, SCREEN.count("LaunchedEffect("))
        self.assertIn("LaunchedEffect(Unit) { if (state.editable && !state.busy) focusRequester.requestFocus() }", SCREEN)
        for forbidden in ("previewPath", "requestRootAuthorization", "SaveSummary.read", "Files.", ".inspect("):
            self.assertNotIn(forbidden, ADAPTER + SCREEN)

    def test_callbacks_require_current_render_foreground_page_and_real_preview(self):
        guard = body(MAIN, "private boolean isCurrentPathAction(")
        for condition in ("renderToken == this.sharedPathRenderToken", "this.page == SyncPage.PATH",
                          "!this.sharedAppreciationOnly", "this.resumed", "!this.busy", "!isDestroyed()"):
            self.assertIn(condition, guard)
        render = body(MAIN, "private void renderSharedSavePath()")
        self.assertIn("++this.sharedPathRenderToken", render)
        self.assertEqual(render.count("isCurrentPathAction(renderToken)"), 4)
        self.assertIn("requestPathNext()", render)
        self.assertIn("selectedGameIdentity()", render)
        for callback in ("m108xe2dc5156()", "m109x58567797()", "requestPathNext()", "launchGameForInitialization()"):
            self.assertIn(callback, render)

    def test_existing_root_proof_private_export_and_global_lock_remain(self):
        preview = body(MAIN, "private void previewPath(boolean continueWhenReady)")
        self.assertIn("this.busy || !this.resumed || isDestroyed()", preview)
        self.assertIn("if (!canRequestSaveAccess())", preview)
        self.assertIn("requestRootAuthorization()", preview)
        self.assertIn("this.pathPreviewState.begin(", preview)
        operation = body(MAIN, "private SaveSummary readPathPreview(")
        self.assertLess(operation.index("checkRootAccess(request, backend)"), operation.index("exportTo("))
        self.assertIn("TrustedPrivateDirectory.resolve", operation)
        self.assertIn("Process.myUid()", operation)
        self.assertIn('createTemporaryDirectory("save-preview-")', operation)
        self.assertLess(operation.index("exportTo("), operation.index("SaveSummary.Companion.read"))
        self.assertIn("try (TrustedPrivateDirectory.TemporaryDirectory temporaryDirectory", operation)
        self.assertIn("hasAnyRestoreJournal()", operation)
        self.assertIn("isCurrentPathRead(request)", operation)
        worker = preview
        self.assertIn("synchronized (SyncRuntime.OPERATION_LOCK)", worker)
        for mutation in (".upload(", ".restore(", "setOnboardingProgress", ".save("):
            self.assertNotIn(mutation, preview + operation)


    def test_preview_never_owns_or_clears_hard_busy(self):
        preview = body(MAIN, "private void previewPath(boolean continueWhenReady)")
        finish = body(MAIN, "private void finishPathPreview(")
        for code in (preview, finish):
            self.assertNotIn("this.busy =", code)
            self.assertNotIn("work(", code)
        self.assertIn("this.f9io.execute", preview)
        self.assertIn("DiagnosticAction.INSPECT", preview)
        self.assertIn("synchronized (SyncRuntime.OPERATION_LOCK)", preview)
        work = body(MAIN, "private <T> void work(DiagnosticAction action, String str, final Task<T> task, final Done<T> done, boolean preserveAutomaticHistory)")
        self.assertLess(work.index("invalidatePathPreview()"), work.index("this.busy = true"))
        self.assertIn("if (this.busy)", body(MAIN, "private void navigate("))
        self.assertIn("if (this.busy)", body(MAIN, "public void onBackPressed()"))

    def test_request_identity_is_distinct_from_render_and_validated_before_applying(self):
        context = body(MAIN, "private PathPreviewState.Context currentPathPreviewContext()")
        for proof in ("SyncRuntime.currentSelectionToken(this)", "SyncRuntime.currentNamespace(this)", "savePathLabel()"):
            self.assertIn(proof, context)
        guard = body(MAIN, "private boolean isCurrentPathRead(")
        self.assertIn("currentPathPreviewContext()", guard)
        self.assertIn("isForegroundPath()", guard)
        self.assertNotIn("sharedPathRenderToken", guard)
        finish = body(MAIN, "private void finishPathPreview(")
        self.assertLess(finish.index("Completion.STALE"), finish.index("this.localPreview = outcome.summary"))
        self.assertLess(finish.index("Completion.STALE"), finish.index("reportPathPreviewError("))
        self.assertIn("readyForSaves()", finish)
        self.assertIn("outcome.summary != null", finish)
        self.assertIn("Completion.ADVANCE", finish)
        self.assertNotIn("sharedPathRenderToken", finish)
        next_action = body(MAIN, "private void requestPathNext()")
        self.assertIn("this.pathPreviewState.queueNext(", next_action)
        self.assertIn("renderSharedSavePath()", next_action)
        self.assertIn("previewPath(true)", next_action)
        for lifecycle in ("protected void onPause()", "protected void onDestroy()", "private void navigate("):
            self.assertIn("invalidatePathPreview()", body(MAIN, lifecycle))
        self.assertIn("resetPathPreview()", body(MAIN, "public void refreshReadiness()"))
        self.assertIn("resetPathPreview()", body(MAIN, "private void resetAccessSelection()"))

    def test_root_preview_does_not_mutate_activity_readiness_from_worker(self):
        root = body(MAIN, "private InstallationReadiness checkRootAccess(PathPreviewState.Request request,")
        for proof in ("isCurrentPathRead(request)", "this.installer.reconcile()", "chosen.selectionToken()",
                      "backend.targetGame()", "backend.capabilities().exportSupported", "this.installer.hasActiveRequest()",
                      "hasAnyRestoreJournal()", "backend.inspectReady()", "AccessStatus.RECOVERY_NEEDED"):
            self.assertIn(proof, root)
        for mutation in ("this.readinessState =", "this.rootGame =", "this.prepared =", "this.notice =",
                         "this.selectedNamespace =", "this.accessCapabilities =", "readInstallationReadiness()"):
            self.assertNotIn(mutation, root)
        finish = body(MAIN, "private void finishPathPreview(")
        self.assertIn("this.readinessState != outcome.expectedReadiness", finish)
        self.assertLess(finish.index("Completion.STALE"), finish.index("this.readinessState = outcome.rootReadiness"))

    def test_failed_read_is_not_generic_error_reset_or_automatic_retry(self):
        error = body(MAIN, "private void reportPathPreviewError(")
        self.assertIn("captureDiagnostic(failure)", error)
        self.assertIn("withAccessCode(code)", error)
        for forbidden in ("resetPathPreview()", "reportError(", "previewPath(", "this.busy ="):
            self.assertNotIn(forbidden, error)
        entry = body(MAIN, "private void schedulePathPreviewEntry()")
        self.assertIn("this.pathPreviewState.attempted()", entry)
        self.assertIn("this.pathPreviewState.inFlight()", entry)
        next_action = body(MAIN, "private void requestPathNext()")
        self.assertIn("this.pathPreviewState.failed() && !readyForSaves()", next_action)
        self.assertIn("m108xe2dc5156()", next_action)
        readiness = body(MAIN, "void m108xe2dc5156()")
        self.assertIn("else if (this.rootGame != null) previewPath(false)", readiness)
        self.assertIn("refreshReadiness()", readiness)

    def test_android_diagnostics_are_only_global_export(self):
        render = body(MAIN, "private void renderSharedSavePath()")
        self.assertIn('pathNotice, ""', render)
        self.assertNotIn('!this.diagnosticReport.isEmpty()', render)
        self.assertNotIn('copyDiagnostic()', render)
        self.assertIn('wizardDiagnosticExport', MAIN)
        # Shared optional details remain available to desktop; Android passes empty/false.
        self.assertIn("canCopyDiagnostics = canCopyDiagnostics", ADAPTER)


if __name__ == "__main__":
    unittest.main()
