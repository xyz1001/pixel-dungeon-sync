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
                     'history.currentFiles == snapshot.files', 'HistoryPresentation.from(it, historyPage)'):
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
        self.assertIn('检查未完成恢复', body(MAIN, 'private void renderSettings()'))

    def test_native_confirmation_and_real_rename_identity_remain_explicit_boundaries(self):
        self.assertIn('showConfirmation(', body(MAIN, 'public void requestRestore()'))
        self.assertIn('navigate(SyncPage.CONFLICT)', body(MAIN, 'public void requestRestore()'))
        self.assertIn('new AlertDialog.Builder', body(MAIN, 'public void rename(final String str)'))
        self.assertIn('editCurrent()', body(MAIN, 'public void rename(final String str)'))
        self.assertIn('确认单局身份', body(MAIN, 'public void editCurrent()'))
        self.assertIn('chooseRunIdentity()', body(MAIN, 'void m40xb1707a89('))
        self.assertIn('sync().renameHistory(str, editText.getText().toString())', MAIN)
        self.assertIn('sync().renameCurrent(inspection, editText.getText().toString())', MAIN)


if __name__ == '__main__':
    unittest.main()
