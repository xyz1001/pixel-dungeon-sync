"""Comparison source wiring plus JVM diagnostic tests; not an Android device test."""
import unittest
from test_main_comparison_contract import method
from test_embedded_install_contract import body as block


class SyncInspectionDiagnosticsContract(unittest.TestCase):
    def test_comparison_phase_matches_failing_call_and_preserves_flow(self):
        body = method("readComparison", "boolean prepareNamespace")
        calls = ("Stage.RUNTIME", "AndroidSyncFacade activeFacade = sync();", "Stage.INSPECT",
                 "inspectionInspect = activeFacade.inspect();", '"BLOCKED"', "Stage.HISTORY",
                 "activeFacade.historyPresentation(inspectionInspect)", "this.cloudVerified = true")
        for earlier, later in zip(calls, calls[1:]):
            self.assertLess(body.index(earlier), body.index(later))
        self.assertEqual(1, body.count("sync()"))
        self.assertEqual(1, body.count(".inspect()"))
        self.assertEqual(1, body.count(".historyPresentation("))
        catch = block(body, "catch (Exception error)")
        for preferred in ("InstallDiagnostics", "PreparationDiagnostics", "BridgeDiagnostics"):
            self.assertIn(preferred + ".find(error) != null", catch)
        self.assertLess(catch.index("throw error;"), catch.index("throw SyncInspectionDiagnostics.at(stage, error);"))
        for changed in ("this.", "setOnboardingProgress", ".upload(", ".restore(", "readComparison()", "return;"):
            self.assertNotIn(changed, catch)
        self.assertNotIn("finishReinspection", body)

    def test_onboarding_next_reaches_home_before_wrapped_history_inspection(self):
        self.assertIn("refreshComparison(SyncPage.HOME)", method("drainSharedCommit"))
        next_step = method("m66x99ceaa21")
        self.assertLess(next_step.index("readInstallationReadiness()"), next_step.index("readComparison()"))
        self.assertLess(next_step.index("if (!readyForSaves())"), next_step.index("readComparison()"))
        self.assertEqual(1, next_step.count("readComparison()"))
        self.assertNotIn("catch", next_step)

    def test_preferred_diagnostics_and_copy_button_remain_authoritative(self):
        capture = method("captureDiagnostic")
        for preferred in ("InstallDiagnostics.report(", "PreparationDiagnostics.report(", "BridgeDiagnostics.report("):
            self.assertLess(capture.index(preferred), capture.index("SyncInspectionDiagnostics.report("))
        self.assertIn("this.diagnosticReport = SyncInspectionDiagnostics.report(syncFailure", capture)
        self.assertIn("this.diagnosticReport.isEmpty() ? safeText(exc2.getMessage()) : this.diagnosticReport", capture)
        copy = method("copyDiagnostic")
        self.assertIn('ClipData.newPlainText(DIAGNOSTIC_CLIPBOARD_LABEL.text(), this.diagnosticReport)', copy)
        self.assertIn('"android.content.extra.IS_SENSITIVE", true', copy)
        self.assertNotIn("safeText(", copy)
        safe_error = method("safeError")
        self.assertLess(safe_error.index("syncFailure.userMessage()"), safe_error.index("safeText(exc.getMessage())"))


if __name__ == "__main__":
    unittest.main()
