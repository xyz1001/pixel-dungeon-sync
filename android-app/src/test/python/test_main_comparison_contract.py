"""Source contracts complement the facade/JVM tests; these are not device UI tests."""
import re
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[2] / "main/java/io/github/xyz1001/pdsync/android/MainActivity.java").read_text()


def method(name, parameters=None):
    arguments = r"[^;{}]*" if parameters is None else re.escape(parameters)
    match = re.search(r"\b" + re.escape(name) + r"\(" + arguments + r"\)(?: throws [^{]+)? \{", SOURCE)
    if not match:
        raise AssertionError("Missing method: " + name)
    start = match.end()
    depth = 1
    for index in range(start, len(SOURCE)):
        depth += (SOURCE[index] == "{") - (SOURCE[index] == "}")
        if depth == 0:
            return SOURCE[start:index]
    raise AssertionError("Unbalanced method: " + name)


class MainComparisonContractTest(unittest.TestCase):
    def test_timeline_uses_exact_same_facade_and_inspection(self):
        body = method("readComparison", "boolean prepareNamespace, boolean allowHomePreview")
        self.assertIn("AndroidSyncFacade activeFacade = sync();", body)
        self.assertIn("inspectionInspect = homePreview ? activeFacade.inspectHome() : activeFacade.inspect();", body)
        self.assertIn("activeFacade.historyPresentation(inspectionInspect)", body)
        self.assertEqual(1, body.count("sync()"))
        self.assertEqual(1, body.count(".inspect()"))

    def test_no_standalone_history_refresh_can_invalidate_an_active_choice(self):
        self.assertNotRegex(SOURCE, r"\.historyPresentation\(\)")

    def test_failed_or_blocked_compare_cannot_keep_previous_cloud_proof(self):
        body = method("readComparison", "boolean prepareNamespace, boolean allowHomePreview")
        self.assertLess(body.index("this.cloudVerified = false"), body.index("activeFacade.inspect()"))
        self.assertLess(body.index("this.inspection = null"), body.index("activeFacade.inspect()"))
        self.assertLess(body.index('"BLOCKED"'), body.index("this.cloudVerified = true"))

    def test_render_and_choices_do_not_read_or_write_mirror(self):
        for name in ("renderHome", "renderConfirmation", "cancelConfirmation"):
            body = method(name)
            for operation in ("sync()", ".inspect()", ".historyPresentation(", ".exportTo(", "readComparison()", "replaceMirror("):
                self.assertNotIn(operation, body, name)

    def test_cancel_and_back_clear_action_without_running_it(self):
        body = method("cancelConfirmation")
        self.assertIn("this.confirmedAction = null", body)
        self.assertIn("this.confirmationMerge = null", body)
        self.assertNotIn(".run()", body)
        self.assertIn("cancelConfirmation();", method("onBackPressed"))




    def test_return_and_launch_preserve_existing_monitoring_gates(self):
        self.assertIn("AutoSyncService.knownReturn(this)", method("onResume"))
        self.assertIn("AutoSyncService.recordDeparture(this)", method("onPause"))
        self.assertIn("OnboardingGate.allowsAutomatic", method("launchGame"))
        self.assertIn("SyncRuntime.isAutoUploadPaused(this)", method("m49x6e86027e"))


if __name__ == "__main__":
    unittest.main()
