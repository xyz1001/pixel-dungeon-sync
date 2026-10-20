"""Two-step configuration and HOME-only save-operation source contracts.

These complement shared controller/core runtime tests; no Android device claims.
"""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()
ADAPTER = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidConnectionComposition.kt').read_text()
UI = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidOnboardingComposition.kt').read_text()
RUNTIME = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/SyncRuntime.java').read_text()


class TwoStepConfigurationAndroidContract(unittest.TestCase):
    def test_removed_comparison_wizard_has_no_route_or_finish_actions(self):
        for old in ('renderSharedComparison', 'SharedComparisonActions', 'finishSharedComparison',
                    'completeEmpty()', 'completeLocalProgress()', 'completeReadyComparison()', 'showComparison('):
            self.assertNotIn(old, MAIN + UI)
        self.assertNotIn('SyncPage.COMPARISON', body(MAIN, 'public void render()'))
        for action in ('navigate(SyncPage.COMPARISON)', 'refreshComparison(SyncPage.COMPARISON)',
                       'this.page = SyncPage.COMPARISON'):
            self.assertNotIn(action, MAIN)
        self.assertIn('AndroidUiFlowPolicy.restoredPage(this.page)', body(MAIN, 'public void onCreate('))

    def test_complete_marker_is_persisted_after_credentials_before_runtime_activation(self):
        save = body(ADAPTER, 'private fun persistAndActivate(')
        sequence = ('requireEntryContext()', 'OnboardingProgress.PENDING', 'SecureConnectionStore(app).save(candidate)',
                    'OnboardingProgress.COMPLETE', 'SyncRuntime.setConnection(candidate)', 'completed = candidate')
        for before, after in zip(sequence, sequence[1:]):
            self.assertLess(save.index(before), save.index(after))
        self.assertGreaterEqual(save.count('requireEntryContext()'), 3)
        for forbidden in ('.connect()', '.inspect()', '.upload(', '.restore', '.adopt', 'completeOnboarding', 'Baseline'):
            self.assertNotIn(forbidden, save)
        marker = body(RUNTIME, 'static void setOnboardingProgress(')
        self.assertIn('.commit()', marker)
        self.assertIn('throw new IOException', marker)
        self.assertIn('throw new IllegalStateException', marker)

    def test_async_commit_is_bound_to_game_namespace_account_and_durable_marker(self):
        context = body(ADAPTER, 'private fun currentSelection()')
        for required in ('entrySelection != null', 'entryNamespace != null', 'SyncRuntime.currentSelectionToken(app)',
                         'SyncRuntime.currentNamespace(app)'):
            self.assertIn(required, context)
        self.assertIn('SyncRuntime.currentConnection() === entryConnection', body(ADAPTER, 'private fun requireEntryContext()'))
        committed = body(ADAPTER, 'fun completedConnection(config: SharedConnectionConfig): SecureConnectionStore.Connection? = runCatching')
        for required in ('currentSelection()', 'SyncRuntime.currentConnection() === candidate',
                         'AndroidConnectionComposition.fromStored(candidate) == config', 'OnboardingProgress.COMPLETE'):
            self.assertIn(required, committed)
        host = body(MAIN, 'private boolean drainSharedCommit()')
        self.assertLess(host.index('if (committed == null)'), host.index('this.progress = OnboardingProgress.COMPLETE'))
        self.assertLess(host.index('this.page = SyncPage.HOME'), host.index('refreshComparison(SyncPage.HOME)'))
        self.assertIn('this.onboardingActive = false', host)
        for forbidden in ('.upload(', '.restore', '.adopt', 'completeOnboarding', 'setOnboardingProgress'):
            self.assertNotIn(forbidden, host)

    def test_restart_reads_configuration_marker_without_inspection_or_baseline_inference(self):
        load = body(MAIN, 'Object m68x764cc8a4(')
        self.assertIn('SyncRuntime.onboardingProgress(this, connection)', load)
        for forbidden in ('readComparison()', 'resumedProgress', 'getBaselineCommit', 'setOnboardingProgress'):
            self.assertNotIn(forbidden, load)
        route = body(MAIN, 'void m69xebc6eee5(')
        self.assertIn('this.progress != OnboardingProgress.COMPLETE', route)
        self.assertIn('this.onboardingActive ? SyncPage.CONNECTION : SyncPage.HOME', route)
        self.assertNotIn('setOnboardingProgress', route)

    def test_initial_home_reads_once_but_ordinary_resume_does_not_read_save_contents(self):
        route = body(MAIN, 'void m69xebc6eee5(')
        initial = body(route, 'if (this.connectionLoadError.isEmpty())')
        self.assertIn('readInitialHomeHistory()', initial)
        read = body(MAIN, 'private void readInitialHomeHistory()')
        self.assertIn('this.rootGame == null', read)
        self.assertIn('readyForSaves() && !hasAutomaticHistoryRefresh()', read)
        self.assertIn('refreshComparison(SyncPage.HOME, false)', read)
        resume = body(MAIN, 'protected void onResume()')
        self.assertNotIn('requestReadinessRefresh()', resume)
        self.assertIn('if (this.initialLoad && !this.setupSession.running) refreshReadiness()', resume)
        refresh = body(MAIN, 'Boolean m66x99ceaa21(')
        self.assertIn('if (this.rootGame != null) checkRootAccess()', refresh)
        self.assertLess(refresh.index('if (!readyForSaves())'), refresh.index('readComparison()'))

    def test_home_manual_operations_refresh_home_without_new_wizard(self):
        for signature in ('public void requestUpload()', 'public void requestRestore()'):
            action = body(MAIN, signature)
            self.assertIn('OnboardingProgress.COMPLETE', action)
            self.assertIn('navigate(SyncPage.CONNECTION)', action)
            self.assertIn('refreshComparison(SyncPage.HOME)', action)
            self.assertNotIn('SyncPage.COMPARISON', action)
        self.assertIn('sync().upload(inspection)', body(MAIN, 'AndroidSyncFacade.OperationResult m146lambda$upload$71$'))
        restore = body(MAIN, 'private AndroidSyncFacade.OperationResult restoreForCurrentAccount(')
        self.assertLess(restore.index('owner = sync()'), restore.index('owner.restoreSelected('))
        self.assertIn('requireOfficialSyncAccess(this.connection)', body(MAIN, 'private AndroidSyncFacade sync()'))
        result = body(MAIN, 'OperationOutcome m54xc977a20(')
        conflict = 'if (operationOutcome.result.isConflict() || kind() == OnboardingKind.BRANCHED)'
        self.assertNotIn(conflict, result)  # Save conflict cannot erase completed connection setup.

    def test_home_namespace_preparation_is_scoped_and_has_no_save_side_effects(self):
        prepare = body(MAIN, 'private void prepareOfficialHomeNamespace(')
        for guard in ('this.page != SyncPage.HOME', 'this.progress != OnboardingProgress.COMPLETE', '!isOfficialAccount(account)',
                      'currentOfficialHomeNamespace(owner, account, selection, namespace)'):
            self.assertIn(guard, prepare)
        self.assertEqual(2, prepare.count('currentOfficialHomeNamespace(owner, account, selection, namespace)'))
        self.assertIn('owner.connect()', prepare)
        self.assertIn('if (!preparedNamespace.isSuccess()) throw', prepare)
        for forbidden in ('.upload(', '.restore', '.adopt', 'completeOnboarding', 'setOnboardingProgress'):
            self.assertNotIn(forbidden, prepare)
        check = body(MAIN, 'private boolean currentOfficialHomeNamespace(')
        for guard in ('this.resumed', '!isDestroyed()', 'this.facade == owner', 'this.connection == account',
                      'SyncRuntime.currentConnection() == account', 'selection.equals(', 'namespace.equals('):
            self.assertIn(guard, check)
        read = body(MAIN, 'private void readComparison(boolean prepareNamespace)')
        self.assertLess(read.index('prepareOfficialHomeNamespace(activeFacade)'), read.index('activeFacade.inspect()'))
        self.assertNotIn('.connect()', body(MAIN, 'private void drainAutomaticHistoryRefresh()'))


if __name__ == '__main__':
    unittest.main()
