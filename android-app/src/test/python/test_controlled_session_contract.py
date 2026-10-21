"""Real Android source wiring; complementary to production facade/JVM state tests."""
import unittest
from pathlib import Path
from test_main_comparison_contract import method
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2]
SERVICE = (ROOT / "main/java/io/github/xyz1001/pdsync/android/AutoSyncService.java").read_text()


class ControlledSessionContractTest(unittest.TestCase):
    def test_before_launch_capture_runs_on_existing_background_worker(self):
        preflight = method("m48xf90bdc3d")
        self.assertLess(preflight.index(".targetGame()"), preflight.index(".beginControlledSession()"))
        self.assertIn("SyncRuntime.automaticAllowed", preflight)
        self.assertIn("synchronized (SyncRuntime.OPERATION_LOCK)", preflight)
        self.assertIn("catch (Exception unavailableEvidence)", preflight)
        self.assertIn("cancelControlledLaunch();", preflight)

    def test_launch_and_monitor_share_token_and_failed_launch_cancels_it(self):
        launch = method("m49x6e86027e")
        self.assertIn("AutoSyncService.arm(this, this.controlledLaunchToken)", launch)
        self.assertGreaterEqual(launch.count("cancelControlledLaunch();"), 3)
        self.assertIn("catch (RuntimeException failure)", launch)
        self.assertNotIn("beginControlledSession", launch)

    def test_service_does_not_refresh_an_active_token_with_new_evidence(self):
        self.assertIn("controlledToken != null && !controlledToken.equals(string)", SERVICE)
        self.assertIn("controlledToken == null ? UUID.randomUUID().toString() : controlledToken", SERVICE)

    def test_passive_return_does_not_fabricate_helper_observation_before_upload(self):
        returned = body(SERVICE, 'public static void knownReturn(')
        self.assertIn('isActive(sharedPreferences) && sharedPreferences.getBoolean("departed", false)', returned)
        self.assertIn('.setAction("pdsync.RETURN").putExtra("token", sharedPreferences.getString("token", ""))', returned)
        poll = body(SERVICE, 'public void poll()')
        trigger = body(poll, 'if (this.returned)')
        self.assertIn('attemptUpload();', trigger)
        self.assertIn('return;', trigger)
        self.assertEqual(1, poll.count('attemptUpload();'))
        self.assertLess(poll.index('AutoSyncPolicy.mayPublish('), poll.index('if (!currentGameAndPolicy())'))
        self.assertLess(poll.index('if (!currentGameAndPolicy())'), poll.index('if (this.returned)'))
        for fabricated in ('observeLifecycle(', 'beginControlledObservation(', 'observeControlledSession(',
                           'this.policy.observe(', 'putBoolean("foregroundSeen", true)'):
            self.assertNotIn(fabricated, SERVICE)
        upload = body(SERVICE, 'private void attemptUpload()')
        self.assertIn('synchronized (SyncRuntime.OPERATION_LOCK)', upload)
        self.assertLess(upload.index('AutoSyncPolicy.mayPublish('), upload.index('this.facade.autoUpload()'))
        self.assertLess(upload.index('if (!currentGameAndPolicy())'), upload.index('this.facade.autoUpload()'))
        self.assertLess(upload.index('SyncRuntime.requireOfficialSyncAccess('), upload.index('this.facade.autoUpload()'))

    def test_service_cancellation_and_destruction_drop_evidence_by_exact_token(self):
        self.assertIn('cancelEvidence(context, sharedPreferences.getString("token", ""))', SERVICE)
        self.assertEqual(2, SERVICE.count("cancelEvidence(this, this.token);"))
        self.assertIn('"sync").toPath(), token)', SERVICE)


if __name__ == "__main__":
    unittest.main()
