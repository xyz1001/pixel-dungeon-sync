"""Production activity/service wiring for the host-tested single-read gate."""
from pathlib import Path
import unittest
from test_embedded_install_contract import body
ROOT = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (ROOT / 'MainActivity.java').read_text()
SERVICE = (ROOT / 'AutoSyncService.java').read_text()

class AutoHistoryRefreshContract(unittest.TestCase):
    def test_terminal_notifications_are_separate_from_install_changes(self):
        terminal = body(SERVICE, 'private synchronized void finish(')
        self.assertIn('if (published) notifyHistoryChanged(this)', terminal)
        self.assertNotIn('INSTALL_CHANGED', terminal)
        receiver = body(MAIN, 'public void onReceive(')
        self.assertIn('AutoSyncService.HISTORY_CHANGED.equals(intent.getAction())', receiver)
        self.assertIn('drainAutomaticHistoryRefresh()', receiver)
        self.assertIn('requestReadinessRefresh()', receiver)

    def test_session_is_bound_at_arm_and_in_flight(self):
        arm = body(SERVICE, 'public static String arm(Context context, String controlledToken)')
        self.assertIn('.putString("connectionScope", connectionScope)', arm)
        self.assertIn('SyncRuntime.connectionScope(context, SyncRuntime.connection(context))', arm)
        self.assertIn('connectionScope', body(SERVICE, 'private boolean currentGameAndPolicy()'))
        current = body(MAIN, 'private boolean currentAutomaticHistory(')
        for proof in ('this.connection == connection', 'SyncRuntime.connection(this) == connection',
                      'historyRefreshRecord(this)', 'currentSelectionToken(this)',
                      'currentNamespace(this)', 'connectionScope(this, connection)'):
            self.assertIn(proof, current)

    def test_single_read_is_staged_and_has_no_mutating_action(self):
        refresh = body(MAIN, 'private void drainAutomaticHistoryRefresh()')
        for gate in ('!this.resumed', 'this.busy', 'isDestroyed()', 'this.page != SyncPage.HOME',
                     'this.readinessState.restorePending', 'this.installer.hasActiveRequest()'):
            self.assertIn(gate, refresh)
        self.assertEqual(refresh.count('.inspect()'), 1)
        self.assertEqual(refresh.count('.historyPresentation(result.inspection)'), 1)
        self.assertLess(refresh.index('!this.resumed || !currentAutomaticHistory(ticket, captured)'),
                        refresh.index('result.facade = SyncRuntime.facade'))
        self.assertIn('state.outcome = result', refresh)
        self.assertLess(refresh.index('claimHistoryRefresh(this, ticket)'), refresh.index('result.facade = SyncRuntime.facade'))
        self.assertIn('finally { state.inFlight = false; }', refresh)
        self.assertIn('deferBeforeRead(ticket)', refresh)
        for mutation in ('.autoUpload(', '.upload(', '.restoreSelected(', '.mergeConflict(',
                         '.completeOnboarding(', '.arm(', '.schedule(', '.postDelayed('):
            self.assertNotIn(mutation, refresh)
        self.assertNotIn('readComparison()', refresh)
        self.assertNotIn('checkRootAccess()', refresh)

    def test_persisted_consumption_and_rotation_do_not_repeat_root_reads(self):
        claim = body(SERVICE, 'static synchronized boolean claimHistoryRefresh(')
        self.assertIn('ticket.recordKey.equals(current.key())', claim)
        self.assertIn('historyRefreshPending(context, current)', claim)
        self.assertIn('.putString(READ_HISTORY, ticket.recordKey).commit()', claim)
        available = body(MAIN, 'private boolean hasAutomaticHistoryRefresh()')
        self.assertIn('AutoSyncService.historyRefreshPending(this, record)', available)
        retention = body(MAIN, 'public Object onRetainCustomNonConfigurationInstance()')
        self.assertIn('this.automaticHistoryState.outcome == null && !this.automaticHistoryState.inFlight', ' '.join(retention.split()))
        restore = body(MAIN, 'private void restoreConnectionUi(')
        self.assertIn('this.automaticHistoryState = retained.automaticHistory', restore)
        self.assertIn('retained.page == SyncPage.CONFIRM ? SyncPage.HOME', restore)
        self.assertIn('retained.page == SyncPage.COMPARISON ? SyncPage.CONNECTION : retained.page', restore)
        remember = body(MAIN, 'private void rememberCurrentAutomaticHistory()')
        self.assertIn('this.busy ||', remember)
        self.assertIn('currentAutomaticHistory(state.ticket, state.connection)', remember)
        for field in ('facade', 'inspection', 'history'):
            self.assertIn('current.' + field + ' = this.' + field, remember)
        complete = body(MAIN, 'void m148lambda$work$0')
        self.assertLess(complete.index('done.accept(obj)'), complete.index('rememberCurrentAutomaticHistory()'))
        self.assertLess(complete.index('rememberCurrentAutomaticHistory()'), complete.index('drainReadinessRefresh()'))

    def test_home_navigation_and_work_drain_without_polling(self):
        navigation = body(MAIN, 'private void navigate(SyncPage syncPage)')
        self.assertIn('if (syncPage == SyncPage.HOME) drainAutomaticHistoryRefresh()', navigation)
        for signature in ('void m148lambda$work$0', 'void m149lambda$work$1'):
            self.assertIn('drainAutomaticHistoryRefresh()', body(MAIN, signature))
        self.assertIn('requestReadinessRefresh()', body(MAIN, 'protected void onResume()'))
        readiness = body(MAIN, 'public void refreshReadiness()')
        self.assertIn('this.automaticHistoryState.outcome.applied = false', readiness)
        self.assertIn('}, true)', readiness)
        for signature in ('private void renderHome()', 'private void renderSettings()'):
            self.assertNotIn('drainAutomaticHistoryRefresh()', body(MAIN, signature))

if __name__ == '__main__':
    unittest.main()
