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
    def test_selection_diagnostics_are_single_event_and_observed_memory_only(self):
        home = body(MAIN, 'private void renderHome()')
        selection = body(home, 'public void selectSnapshot(String snapshotId)')
        self.assertEqual(1, selection.count('recordHomeRestoreSelection()'))
        self.assertLess(selection.index('explicitHomeSelection = snapshotId'), selection.index('recordHomeRestoreSelection()'))
        diagnostics = body(MAIN, 'private void recordHomeRestoreSelection()')
        self.assertEqual(1, diagnostics.count('DiagnosticLog.recordStandalone('))
        for forbidden in ('DiagnosticLog.begin(', 'Thread.sleep', '.inspect(', '.readComparison(', '.restore(', '.upload(', 'Snapshots.capture(', 'Files.', 'MessageDigest', '.getPassword('):
            self.assertNotIn(forbidden, diagnostics)
        for required in ('getCurrentFiles()', 'getSnapshot().getFiles()', 'canHomeRestore()', 'catch (Exception ignored)'):
            self.assertIn(required, diagnostics)

    def test_official_account_button_eligibility_uses_current_trusted_metadata(self):
        gate = body(MAIN, 'private boolean homeAccountAccessAvailable()')
        self.assertIn('!isOfficialAccount(this.connection)', gate)
        self.assertIn('TrustedEntitlementCache.currentAccessAvailable', gate)
        self.assertIn('this.connection.username', gate)
        self.assertIn('homeAccountAccessAvailable()', body(MAIN, 'private boolean canHomeUpload()'))
        self.assertIn('homeAccountAccessAvailable()', body(MAIN, 'private boolean canHomeRestore()'))

    def test_home_expiry_uses_current_account_state_and_existing_appreciation_navigation(self):
        home = body(MAIN, 'private void renderHome()')
        self.assertIn('final boolean serviceExpired = homeAccountExpired()', home)
        self.assertIn('homeStatus(content)', home)
        self.assertIn('serviceExpired ? pdsync.i18n.CoreSessionMessages.SERVICE_EXPIRED.text()',
                      body(MAIN, 'private String homeStatus('))
        self.assertIn('TrustedEntitlementCache.withoutExpiryNotice(visibleNotice())', home)
        self.assertIn('serviceExpired ? SharedHomeStatusKind.EXPIRED', home)
        self.assertIn('isCurrentHomeNavigation(owner) && homeAccountExpired() && !installer.hasActiveRequest()', home)
        self.assertIn('showManagedAccount(MainActivity.this.connection)', home)
        state = body(MAIN, 'private boolean homeAccountExpired()')
        for expected in ('isOfficialAccount(this.connection)', '.read(', 'this.connection.username', 'elapsedRealtime()', 'bootCount(', 'TrustedEntitlementCache.currentExpired('):
            self.assertIn(expected, state)
        for forbidden in ('.entitlement(', '.requireSyncAccess(', '.save(', '.write(', 'lease ='):
            self.assertNotIn(forbidden, state)

    def test_confirmation_replaces_legacy_chrome_with_inactive_real_shared_backdrop(self):
        confirmation = body(MAIN, 'private void renderConfirmation()')
        for actual in ('this.root.removeAllViews()', 'this.root.setPadding(0, 0, 0, 0)',
                       'this.root.setOnApplyWindowInsetsListener(null)', 'this.root.addView(',
                       'this.history, this.historyPage, selectedGameIdentity()',
                       'this.confirmationSettingsBackdrop', 'new LinearLayout.LayoutParams(-1, -1)'):
            self.assertIn(actual, confirmation)
        self.assertNotIn('this.body.addView(', confirmation)
        self.assertNotIn('new LinearLayout.LayoutParams(0, 0)', confirmation)
        adapter = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidHomeDialogComposition.kt').read_text()
        actual = body(adapter, 'fun confirmationView(')
        for guard in ('AndroidAppViewport {', 'SharedHomeScreen(SharedHomeState(', 'pageActive = false',
                      'HistoryPresentation.from(it, historyPage)', 'SharedSettingsScreen(settingsBackdrop.copy(pageActive = false)',
                      'SharedConfirmationDialog(state,'):
            self.assertIn(guard, actual)
        for forbidden in ('.inspect(', 'freshExport(', 'SaveSummary(', '.restore(', '.upload(', '.entitlement('):
            self.assertNotIn(forbidden, actual)

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
        self.assertIn('SharedHomeScreen(state, actions, naming = naming.naming)', adapter)
        self.assertIn('AndroidAppViewport {', adapter)
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

    def test_content_grouping_maps_display_selection_without_replacing_real_action_target(self):
        adapter = body(ADAPTER, 'fun showHome(')
        self.assertIn('HistoryPresentation.representativeSnapshotId(it, selected)', adapter)
        self.assertIn('val targetId = selectedId ?: history?.defaultSnapshot', adapter)
        self.assertIn('snapshotId = snapshot.id', adapter)
        self.assertNotIn('selectedId = HistoryPresentation', adapter)
        selection = body(body(MAIN, 'private void renderHome()'), 'public void selectSnapshot(')
        self.assertIn('selected = snapshotId', selection)
        self.assertIn('hasHomeSnapshot(snapshotId)', selection)

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
            self.assertIn('isCurrentHomeNavigation(owner)' if signature in ('public void settings()', 'public void appreciate()') else 'isCurrentHomeAction(owner)', body(home, signature))
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
        for action in ('PUSH', 'RESTORE', 'LAUNCH'):
            self.assertIn('case ' + action + ':', pending)

    def test_platform_gate_and_real_operations_remain_without_new_process_rules(self):
        home = body(MAIN, 'private void renderHome()')
        for capability in ('canRequestSaveAccess()', 'canHomeUpload()', 'canHomeRestore()', 'restoreNeedsAttention()',
                           'homeStatus(content)', 'this.inspection.isRetryablePending()'):
            self.assertIn(capability, home)
        for capability in ('automaticObservationSupported()', 'SyncRuntime.isAutoUploadPaused(this)', 'automaticStatusBrief()'):
            self.assertIn(capability, body(MAIN, 'private String homeStatus('))
        for dispatch in ('requestUpload()', 'requestRestore()', 'launchGame()', 'MainActivity.this.rename(snapshotId)',
                         'navigate(SyncPage.SETTINGS)'):
            self.assertIn(dispatch, home)
        for forbidden in ('forceStop', 'force-stop', 'pidof', 'kill(', 'isGameRunning', '.inspect(',
                          'readComparison()', 'freshExport(', 'replaceMirror(', '.historyPresentation(', '.exportTo('):
            self.assertNotIn(forbidden, home)
        self.assertIn('sync().upload(inspection)', body(MAIN, 'AndroidSyncFacade.OperationResult m146lambda$upload$71$'))
        self.assertIn('owner = sync()', body(MAIN, 'private AndroidSyncFacade.OperationResult restoreForCurrentAccount('))
        self.assertIn('requireOfficialSyncAccess(this.connection)', body(MAIN, 'private AndroidSyncFacade sync()'))
        self.assertIn('requestRootAuthorization()', MAIN)
        self.assertIn('refreshComparison(SyncPage.HOME)', body(MAIN, 'void m130x445a4530()'))
        self.assertIn('CHECK_UNFINISHED_RESTORE_226.text()', body(MAIN, 'private void showSettingsMaintenance('))

    def test_archive_sources_guard_display_retained_callbacks_and_direct_entries(self):
        home = body(MAIN, 'private void renderHome()')
        for name in ('Upload', 'Restore'):
            gate = 'canHome' + name + '()'
            self.assertIn('final boolean can' + name + ' = ' + gate, home)
            self.assertIn(gate, body(home, 'public void ' + name.lower() + '()'))
            entry = body(MAIN, 'public void request' + name + '()')
            self.assertTrue(entry.lstrip('{').strip().startswith('if (!' + gate + ') return;'))
            check = body(MAIN, 'private boolean ' + gate)
            self.assertIn('isForegroundHome()', check)
            self.assertIn('!this.busy', check)
            self.assertIn('homeArchiveAvailability().get' + name + 'SourceAvailable()', check)
        source = body(MAIN, 'private SharedHomeArchiveAvailability homeArchiveAvailability()')
        for guard in ('SharedHomeArchiveAvailability.from(this.history, this.selected',
                      'this.homeObservationOwner.accepts(currentHomeContext(0)',
                      'this.cloudVerified', 'this.connection != null', 'this.facade != null',
                      'this.inspection != null', 'selectedGamePackage() != null', 'readyForSaves()',
                      '!this.archiveReads.loading()', '!isHomeArchiveLoading()',
                      'kind() != OnboardingKind.BLOCKED'):
            self.assertIn(guard, source)
        self.assertIn('this.homeObservationOwner = currentHomeContext(0)', body(MAIN, 'private void readComparison(boolean prepareNamespace, boolean allowHomePreview)'))
        self.assertIn('this.homeObservationOwner = this.cloudVerified ? currentHomeContext(0) : null',
                      body(MAIN, 'private void drainAutomaticHistoryRefresh()'))
        confirmation = body(MAIN, 'void m143x7dd3dfcf(')
        for guard in ('this.inspection != inspection', 'java.util.Objects.equals(this.selected, str)',
                      'homeArchiveAvailability().getRestoreSourceAvailable()'):
            self.assertIn(guard, confirmation)
        self.assertLess(confirmation.index('return;'), confirmation.index('operation('))

    def test_archive_loading_tracks_reads_without_starting_io_from_render(self):
        adapter = body(ADAPTER, 'fun showHome(')
        self.assertIn('archiveLoading: Boolean', ADAPTER)
        self.assertIn('archiveLoading = archiveLoading', adapter)
        home = body(MAIN, 'private void renderHome()')
        self.assertIn('isHomeArchiveLoading()', home)
        state = body(MAIN, 'private boolean isHomeArchiveLoading()')
        for guard in ('isForegroundHome()', 'this.archiveReads.loading()',
                      'this.automaticHistoryState.inFlight', 'currentAutomaticHistory('):
            self.assertIn(guard, state)
        for forbidden in ('refreshComparison(', 'readComparison(', 'this.busy', 'activeWorkAction'):
            self.assertNotIn(forbidden, state)
        read = body(MAIN, 'private void readComparison(boolean prepareNamespace, boolean allowHomePreview)')
        self.assertLess(read.index('this.archiveReads.begin()'), read.index('activeFacade.inspect()'))
        self.assertIn('finally {', read)
        self.assertIn('this.archiveReads.finish(archiveRead)', read)
        self.assertIn('publishArchiveReadState()', read)
        self.assertLess(read.index('final SyncHistory observedHistory;'), read.index('this.history = observedHistory'))
        self.assertIn('requireCurrentArchiveRead(archiveRead, archiveAccount, archiveSelection, archiveNamespace, archivePage)', read)
        scope = body(MAIN, 'private void requireCurrentArchiveRead(')
        for guard in ('this.archiveReads.accepts(ticket)', '!isDestroyed()', 'this.connection == account',
                      'SyncRuntime.currentConnection() == account', 'this.page == origin',
                      'SyncRuntime.currentSelectionToken(this)', 'SyncRuntime.currentNamespace(this)'):
            self.assertIn(guard, scope)
        worker = body(MAIN, 'void m150lambda$work$2$iogithubxyz1001pdsyncandroidMainActivity(')
        self.assertIn('synchronized (SyncRuntime.OPERATION_LOCK)', worker)
        self.assertIn('objRun = task.run()', worker)
        self.assertIn('this.archiveReads.close()', body(MAIN, 'protected void onDestroy()'))
        self.assertIn('this.archiveReads.invalidate()', body(MAIN, 'private void navigate('))
        automatic = body(MAIN, 'private void drainAutomaticHistoryRefresh()')
        self.assertLess(automatic.index('state.inFlight = true'), automatic.index('startDetachedHomeRead('))
        self.assertIn('state.refresh.deferBeforeRead(ticket)', automatic)
        self.assertIn('this.automaticHistoryState.inFlight = false', body(MAIN, 'private void finishDetachedHomeRead('))

    def test_home_status_uses_display_equality_without_granting_transfer_or_object_proof(self):
        for signature in ('private String homeStatus(', 'private SharedHomeStatusKind homeStatusKind('):
            status = body(MAIN, signature)
            self.assertIn('homeDisplayMatchesCloudTip(content)', status)
            self.assertNotIn('content.getUploadUnnecessary()', status)
        current = body(MAIN, 'private boolean homeDisplayMatchesCloudTip(')
        for guard in ('content.getMatchesCloudTip()', 'this.cloudVerified', '!this.feedbackError',
                      'this.homeObservationOwner.accepts(currentHomeContext(0), true, false)',
                      'this.connection == SyncRuntime.currentConnection()',
                      '!this.archiveReads.loading()', '!isHomeArchiveLoading()'):
            self.assertIn(guard, current)
        for signature in ('private boolean canHomeUpload()', 'private boolean canHomeRestore()',
                          'private boolean mayRestoreWithoutReplacementConfirmation('):
            self.assertNotIn('getMatchesCloudTip()', body(MAIN, signature))

    def test_noop_capabilities_use_shared_full_content_evidence_and_recheck_callbacks(self):
        home = body(MAIN, 'private void renderHome()')
        evidence = body(MAIN, 'private SharedHomeContent homeContent()')
        for required in ('SharedHomeContent.fromHome(this.history, this.selected', 'this.cloudVerified',
                         'this.connection != null', 'this.facade != null', 'this.inspection != null',
                         'this.progress == OnboardingProgress.COMPLETE', '!restoreNeedsAttention()',
                         'kind() != OnboardingKind.BLOCKED', 'this.inspection.isRetryablePending()',
                         'kind() == OnboardingKind.BRANCHED'):
            self.assertIn(required, evidence)
        self.assertIn('!homeContent().getUploadUnnecessary()', body(MAIN, 'private boolean canHomeUpload()'))
        self.assertIn('!homeContent().getRestoreUnnecessary()', body(MAIN, 'private boolean canHomeRestore()'))
        self.assertIn('canHomeUpload()', body(home, 'public void upload()'))
        self.assertIn('canHomeRestore()', body(home, 'public void restore()'))
        self.assertNotIn('getClean()', home)
        for forbidden in ('.inspect(', 'SyncRuntime.', 'freshExport(', 'getLocalFileCount()',
                          'getClean()', 'getBaselineCommit()', 'getSummary()', '.historyPresentation('):
            self.assertNotIn(forbidden, evidence)

    def test_shared_confirmation_and_naming_keep_real_host_action_boundaries(self):
        restore = body(MAIN, 'public void requestRestore()')
        self.assertIn('showDestructiveConfirmation(', restore)
        destructive = body(MAIN, 'private void showDestructiveConfirmation(')
        self.assertIn('showConfirmation(title, message, label, action, true)', destructive)
        confirmation = body(MAIN, 'private void showConfirmation(String str, String str2, String str3, Runnable runnable, boolean destructive)')
        self.assertIn('this.confirmationDestructive = destructive', confirmation)
        self.assertIn('this.confirmationDestructive', body(MAIN, 'private void renderConfirmation()'))
        self.assertNotIn('navigate(SyncPage.CONFLICT)', restore)
        self.assertNotIn('overwriteCloudSuccessors', restore)
        # Protected local bytes still restore directly; UI migration adds no universal prompt.
        protected = body(restore, 'if (mayRestoreWithoutReplacementConfirmation(syncHistory))')
        self.assertIn('operation(DiagnosticAction.RESTORE', protected)
        self.assertIn('return;', protected)
        self.assertNotIn('showConfirmation(', protected)
        self.assertNotIn('showDestructiveConfirmation(', protected)
        rename = body(MAIN, 'public void rename(final String snapshotId)')
        current = body(MAIN, 'public void editCurrent()')
        inline_current = body(MAIN, 'private void renameCurrentInline()')
        for naming in (rename, current, inline_current):
            self.assertIn('isCurrentHomeAction(owner)', naming)
            self.assertNotIn('new AlertDialog.Builder', naming)
            self.assertNotIn('EditText', naming)
        self.assertIn('this.homeNaming.open(', rename)
        self.assertIn('this.homeNaming.open(', inline_current)
        self.assertIn('renameCurrentInline()', rename)
        self.assertIn('AndroidHomeDialogComposition.showNaming(', current)
        self.assertIn('localSync().renameCurrent(observed, name)', inline_current)
        self.assertIn('readComparison()', inline_current)
        self.assertIn('chooseRunIdentity()', inline_current)
        for signature in ('protected void onPause()', 'protected void onDestroy()', 'private void navigate(', 'private void renderHome()'):
            self.assertIn('this.homeNaming.close()', body(MAIN, signature))
        self.assertIn('chooseRunIdentity()', current)
        self.assertIn('localSync().renameHistory(observed, snapshotId, name)', rename)
        self.assertIn('localSync().renameCurrent(observed, name)', current)
        self.assertIn('readComparison()', rename)
        self.assertIn('readComparison()', current)
        confirmation = body(MAIN, 'private void renderConfirmation()')
        self.assertIn('SharedConfirmationState(', confirmation)
        self.assertIn('AndroidHomeDialogComposition.confirmationView(', confirmation)
        self.assertIn('isCurrentConfirmation(token)', confirmation)
        self.assertIn('this.confirmedAction == confirm', confirmation)
        self.assertNotIn('confirmationMerge', confirmation)
        adapter = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidHomeDialogComposition.kt').read_text()
        self.assertIn('SharedNamingDialog(SharedNamingState(', adapter)
        self.assertIn('SharedConfirmationDialog(state,', adapter)
        self.assertIn('onDismiss = { close() }', adapter)
        self.assertIn('CONFIRM_RUN_IDENTITY.text()', adapter)
        for forbidden in ('sync()', 'restoreSelected(', 'mergeConflict(', 'renameHistory(', 'renameCurrent('):
            self.assertNotIn(forbidden, adapter)


if __name__ == '__main__':
    unittest.main()
