"""Android home seam checks, paired with executed callback-owner and shared UI tests.

These checks do not claim Android device/lifecycle execution or real-account verification.
"""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()
ADAPTER = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidOnboardingComposition.kt').read_text()
SCREEN = (REPO / 'shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedHomeScreen.kt').read_text()


class SharedHomeAndroidContract(unittest.TestCase):
    def test_home_routes_to_shared_composition_before_native_hierarchy(self):
        render = body(MAIN, 'public void render()')
        home = body(render, 'if (this.page == SyncPage.HOME)')
        self.assertIn('renderHome()', home)
        self.assertIn('return;', home)
        self.assertLess(render.index(home), render.index('ScrollView scrollView'))
        host = body(MAIN, 'private void renderHome()')
        self.assertIn('AndroidOnboardingComposition.showHome(this, this.history, this.historyPage, selectedGameIdentity()', host)
        for old_layout in ('new TimelineView', 'heading(', '\n        text(', 'addButton(', 'button(', 'this.body.addView('):
            self.assertNotIn(old_layout, host)
        self.assertNotIn('final class TimelineView', MAIN)
        self.assertNotIn('private void renderHistory()', MAIN)
        adapter = body(ADAPTER, 'fun showHome(')
        self.assertIn('SharedHomeState(', adapter)
        self.assertIn('SharedHomeScreen(state, actions)', adapter)
        self.assertIn('safeDrawingPadding().imePadding()', adapter)
        self.assertIn('SharedHistorySection(state.history', SCREEN)

    def test_target_comes_from_full_real_history_not_visible_page_or_labels(self):
        adapter = body(ADAPTER, 'fun showHome(')
        for real in ('selectedId ?: history?.defaultSnapshot', 'history?.cloud?.firstOrNull { it.snapshot.id == targetId }',
                     'history.aliases[snapshot.id]', 'snapshot.directoryMetadata?.displayName',
                     'snapshot.metadata?.device?.label', 'HistoryPresentation.time(snapshot.metadata?.latestFileModifiedAt)',
                     'content.restoreUnnecessary', 'HistoryPresentation.from(it, historyPage)'):
            self.assertIn(real, adapter)
        self.assertIn('history.graph.tips.size > 1', adapter)
        self.assertNotIn('capturedAt', adapter)
        self.assertNotIn('preparedAt', adapter)
        for forbidden in ('.inspect(', 'freshExport(', '.historyPresentation(', 'readComparison(', 'SyncRuntime.',
                          'SaveSummary(', 'DirectorySnapshotMetadata(', 'SnapshotMetadata(', '.copy('):
            self.assertNotIn(forbidden, adapter)

    def test_default_target_and_explicit_selection_are_distinct_and_rotation_retained(self):
        self.assertIn('selectedSnapshotId = explicitSelection?.takeIf { it == selectedId }', body(ADAPTER, 'fun showHome('))
        self.assertIn('this.explicitHomeSelection = bundle.getString("home.explicitSelection")', body(MAIN, 'public void onCreate('))
        self.assertIn('bundle.putString("home.explicitSelection", this.explicitHomeSelection)', body(MAIN, 'protected void onSaveInstanceState('))
        self.assertIn('bundle.putInt("historyPage", this.historyPage)', body(MAIN, 'protected void onSaveInstanceState('))
        home = body(MAIN, 'private void renderHome()')
        select = body(home, 'public void selectSnapshot(')
        for guard in ('isCurrentHomeAction(owner)', 'hasHomeSnapshot(snapshotId)', 'selected = snapshotId', 'explicitHomeSelection = snapshotId'):
            self.assertIn(guard, select)
        paging = body(home, 'public void changeHistoryPage(')
        for guard in ('isCurrentHomeAction(owner)', 'HistoryPresentation.from(history, historyPage)',
                      'requestedPage < 0', 'requestedPage >= visible.getPages()', 'historyPage = requestedPage'):
            self.assertIn(guard, paging)
        self.assertNotIn('selected =', paging)
        self.assertNotIn('explicitHomeSelection =', paging)
        self.assertNotIn('historyPage', body(MAIN, 'private boolean hasHomeSnapshot('))
        for signature in ('private void resetAccessSelection()', 'private void applySharedConnection('):
            # Both game/backend and saved account changes discard old explicit restore intent.
            if signature in MAIN:
                self.assertIn('this.explicitHomeSelection = null', body(MAIN, signature))

    def test_every_home_callback_rechecks_actual_page_account_backend_and_observation(self):
        context = body(MAIN, 'private HomeActionContext currentHomeContext(')
        for field in ('this.connection, this.facade, this.inspection, this.history',
                      'SyncRuntime.currentSelectionToken(this)', 'SyncRuntime.currentNamespace(this)'):
            self.assertIn(field, context)
        foreground = body(MAIN, 'private boolean isForegroundHome()')
        for gate in ('this.resumed', '!isDestroyed()', 'this.page == SyncPage.HOME', '!this.sharedAppreciationOnly'):
            self.assertIn(gate, foreground)
        current = body(MAIN, 'private boolean isCurrentHomeAction(')
        self.assertIn('owner.accepts(currentHomeContext(this.sharedHomeRenderToken), isForegroundHome(), this.busy)', current)
        home = body(MAIN, 'private void renderHome()')
        for signature in ('public void settings()', 'public void upload()', 'public void restore()',
                          'public void launch()', 'public void selectSnapshot(', 'public void rename(',
                          'public void changeHistoryPage('):
            self.assertIn('isCurrentHomeAction(owner)', body(home, signature))
        for signature in ('protected void onPause()', 'protected void onDestroy()', 'private void navigate('):
            self.assertIn('++this.sharedHomeRenderToken', body(MAIN, signature))

    def test_busy_is_published_before_io_without_touching_a_stale_native_status(self):
        work = body(MAIN, 'private <T> void work(DiagnosticAction action, String str, final Task<T> task, final Done<T> done, boolean preserveAutomaticHistory)')
        self.assertLess(work.index('this.busy = true'), work.index('this.activeWorkAction = action'))
        self.assertLess(work.index('this.activeWorkAction = action'), work.index('this.f9io.execute'))
        fallback = body(work, 'else if (this.status == null || this.root == null || this.renderedPage != this.page)')
        self.assertIn('render()', fallback)
        home = body(MAIN, 'private void renderHome()')
        for cleared in ('root', 'status', 'body', 'footer', 'scroll'):
            self.assertIn('this.' + cleared + ' = null', home)
        self.assertIn('this.busy, canUpload, canRestore, canLaunch, homeOperation()', home)
        pending = body(MAIN, 'private SharedHomeOperation homeOperation()')
        self.assertIn('if (!this.busy) return SharedHomeOperation.NONE', pending)
        for action in ('PUSH', 'RESTORE', 'MERGE', 'LAUNCH'):
            self.assertIn('case ' + action + ':', pending)

    def test_platform_gate_and_real_operations_remain_without_new_process_rules(self):
        home = body(MAIN, 'private void renderHome()')
        for capability in ('canRequestSaveAccess()', 'restoreSupported()', 'restoreNeedsAttention()',
                           'automaticObservationSupported()', 'SyncRuntime.isAutoUploadPaused(this)',
                           'automaticStatusBrief()', 'this.inspection.isRetryablePending()'):
            self.assertIn(capability, home)
        for dispatch in ('requestUpload()', 'requestRestore()', 'launchGame()', 'MainActivity.this.rename(snapshotId)',
                         'navigate(SyncPage.SETTINGS)'):
            self.assertIn(dispatch, home)
        for forbidden in ('forceStop', 'force-stop', 'pidof', 'kill(', 'isGameRunning', '.inspect(',
                          'readComparison()', 'freshExport(', 'replaceMirror(', '.historyPresentation(', '.exportTo('):
            self.assertNotIn(forbidden, home)
        self.assertIn('sync().upload(inspection)', body(MAIN, 'AndroidSyncFacade.OperationResult m146lambda$upload$71$'))
        self.assertIn('requireOfficialSyncAccess(account)', body(MAIN, 'String snapshotId, boolean replace, ComparisonProofState.Ticket proof)'))
        self.assertIn('requestRootAuthorization()', MAIN)
        self.assertIn('refreshComparison(SyncPage.HOME)', body(MAIN, 'void m130x445a4530()'))
        self.assertIn('检查未完成恢复', body(MAIN, 'private void showSettingsMaintenance('))

    def test_noop_capabilities_use_shared_full_content_evidence_and_recheck_callbacks(self):
        home = body(MAIN, 'private void renderHome()')
        evidence = body(MAIN, 'private SharedHomeContent homeContent()')
        for required in ('SharedHomeContent.from(this.history, this.selected', 'this.cloudVerified',
                         'this.connection != null', 'this.facade != null', 'this.inspection != null',
                         'this.progress == OnboardingProgress.COMPLETE', '!restoreNeedsAttention()',
                         'kind() != OnboardingKind.BLOCKED', 'this.inspection.isRetryablePending()',
                         'kind() == OnboardingKind.BRANCHED'):
            self.assertIn(required, evidence)
        self.assertIn('!content.getUploadUnnecessary()', home)
        self.assertIn('!content.getRestoreUnnecessary()', home)
        self.assertIn('!homeContent().getUploadUnnecessary()', body(home, 'public void upload()'))
        self.assertIn('!homeContent().getRestoreUnnecessary()', body(home, 'public void restore()'))
        self.assertNotIn('getClean()', home)
        for forbidden in ('.inspect(', 'SyncRuntime.', 'freshExport(', 'getLocalFileCount()',
                          'getClean()', 'getBaselineCommit()', 'getSummary()', '.historyPresentation('):
            self.assertNotIn(forbidden, evidence)

    def test_shared_confirmation_and_naming_keep_real_host_action_boundaries(self):
        restore = body(MAIN, 'public void requestRestore()')
        self.assertIn('showConfirmation(', restore)
        self.assertIn('navigate(SyncPage.CONFLICT)', restore)
        # Protected local bytes still restore directly; UI migration adds no universal prompt.
        protected = body(restore, 'if (syncHistory != null && syncHistory.getCurrentProtected())')
        self.assertIn('operation(DiagnosticAction.RESTORE', protected)
        self.assertIn('return;', protected)
        self.assertNotIn('showConfirmation(', protected)
        rename = body(MAIN, 'public void rename(final String snapshotId)')
        current = body(MAIN, 'public void editCurrent()')
        for naming in (rename, current):
            self.assertIn('AndroidHomeDialogComposition.showNaming(', naming)
            self.assertIn('isCurrentHomeAction(owner)', naming)
            self.assertNotIn('new AlertDialog.Builder', naming)
            self.assertNotIn('EditText', naming)
        self.assertIn('editCurrent()', rename)
        self.assertIn('chooseRunIdentity()', current)
        self.assertIn('sync().renameHistory(snapshotId, name)', rename)
        self.assertIn('sync().renameCurrent(observed, name)', current)
        self.assertIn('readComparison()', rename)
        self.assertIn('readComparison()', current)
        confirmation = body(MAIN, 'private void renderConfirmation()')
        self.assertIn('SharedConfirmationState(', confirmation)
        self.assertIn('AndroidHomeDialogComposition.confirmationView(', confirmation)
        self.assertIn('isCurrentConfirmation(token)', confirmation)
        self.assertIn('this.confirmedAction == confirm', confirmation)
        self.assertIn('this.confirmationMerge == merge', confirmation)
        adapter = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidHomeDialogComposition.kt').read_text()
        self.assertIn('SharedNamingDialog(SharedNamingState(', adapter)
        self.assertIn('SharedConfirmationDialog(state,', adapter)
        self.assertIn('onDismiss = { close() }', adapter)
        self.assertIn('确认单局身份', adapter)
        for forbidden in ('sync()', 'restoreSelected(', 'mergeConflict(', 'renameHistory(', 'renameCurrent('):
            self.assertNotIn(forbidden, adapter)


if __name__ == '__main__':
    unittest.main()
