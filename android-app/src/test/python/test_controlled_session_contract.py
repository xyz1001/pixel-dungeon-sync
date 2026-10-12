"""Real Android source wiring; complementary to production facade/JVM state tests."""
import unittest
from pathlib import Path
from test_main_comparison_contract import method

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

    def test_only_actual_helper_observation_is_forwarded_before_upload_decision(self):
        observation = "this.facade.observeControlledSession(this.token, observationObserveLifecycle.reliable, observationObserveLifecycle.foreground);"
        self.assertIn(observation, SERVICE)
        self.assertLess(SERVICE.index(observation), SERVICE.index("this.policy.observe("))
        self.assertNotIn("observeControlledSession(this.token, true", SERVICE)

    def test_service_cancellation_and_destruction_drop_evidence_by_exact_token(self):
        self.assertIn('cancelEvidence(context, sharedPreferences.getString("token", ""))', SERVICE)
        self.assertEqual(2, SERVICE.count("cancelEvidence(this, this.token);"))
        self.assertIn('"sync").toPath(), token)', SERVICE)


if __name__ == "__main__":
    unittest.main()
