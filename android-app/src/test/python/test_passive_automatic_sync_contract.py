"""Existing return event and timer trigger automatic sync without querying game processes."""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

BASE = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
SERVICE = (BASE / 'AutoSyncService.java').read_text()


class PassiveAutomaticSyncContract(unittest.TestCase):
    def test_return_is_passive_and_requires_departure(self):
        known = body(SERVICE, 'public static void knownReturn(')
        self.assertIn('getBoolean("departed", false)', known)
        self.assertIn('setAction("pdsync.RETURN")', known)
        start = body(SERVICE, 'public int onStartCommand(')
        self.assertIn('this.returned = true;', start)
        activity = (BASE / 'MainActivity.java').read_text()
        self.assertIn('AutoSyncService.knownReturn(this)', activity)
        self.assertIn('AutoSyncService.recordDeparture(this)', activity)

    def test_no_process_observation_or_synthetic_session_evidence(self):
        for forbidden in ('observeLifecycle(', 'lifecycleObservation',
                          'beginControlledObservation(', 'observeControlledSession(',
                          'this.policy.observe(', 'this.policy.knownReturn('):
            self.assertNotIn(forbidden, SERVICE)
        poll = body(SERVICE, 'public void poll(')
        self.assertIn('if (this.returned)', poll)
        self.assertIn('attemptUpload();', poll)
        self.assertNotIn('this.returned = false', poll)
        self.assertIn('schedule(1500L)', poll)
        self.assertIn('21600000L', poll)

    def test_gates_lock_scope_and_retry_remain(self):
        poll = body(SERVICE, 'public void poll(')
        self.assertIn('AutoSyncPolicy.mayPublish(', poll)
        self.assertIn('currentGameAndPolicy()', poll)
        upload = body(SERVICE, 'private void attemptUpload(')
        for required in ('synchronized (SyncRuntime.OPERATION_LOCK)',
                         'currentGameAndPolicy()', 'requireOfficialSyncAccess(',
                         'this.facade.autoUpload()', 'AutoSyncPolicy.mayPublish('):
            self.assertIn(required, upload)
        current = body(SERVICE, 'private boolean currentGameAndPolicy(')
        for required in ('currentSelectionToken(', 'currentNamespace(', 'connectionScope(', 'automaticAllowed('):
            self.assertIn(required, current)
        retry = body(SERVICE, 'private void retryTransient(')
        self.assertIn('nextTransientRetryMillis()', retry)
        self.assertIn('schedule(jNextTransientRetryMillis)', retry)


if __name__ == '__main__':
    unittest.main()
