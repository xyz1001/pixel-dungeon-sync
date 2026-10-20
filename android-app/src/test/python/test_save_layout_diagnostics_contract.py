"""Source wiring checks complement executable hostile-bundle and filesystem tests."""
from pathlib import Path
import unittest
from test_embedded_install_contract import body, MAIN, CLIENT

ROOT = Path(__file__).resolve().parents[4]
PROVIDER = (ROOT / 'android-helper/src/main/java/io/github/xyz1001/pdsync/bridge/SaveBridgeProvider.java').read_text()

class SaveLayoutDiagnosticsContract(unittest.TestCase):
    def test_inspect_metadata_is_authenticated_and_retained_on_readiness_failure(self):
        call = body(PROVIDER, 'public Bundle call(')
        self.assertLess(call.index('authenticate()'), call.index('return inspect()'))
        inspect = body(PROVIDER, 'private Bundle inspect()')
        self.assertIn('describeContent(bundleOk, content)', inspect)
        self.assertIn('putBoolean("transferReady", !z || content.transferable())', inspect)
        self.assertNotIn('requireTransferable()', inspect)
        client = body(CLIENT, 'public String inspectReady()')
        self.assertLess(client.index('call("inspect", null)'), client.index('BridgeReadiness.failureCode('))
        self.assertIn('BridgeSaveLayout.fromReply(bundleCall)', client)
        self.assertIn('throw new Failure(readiness.stage, readiness.code,', client)

    def test_main_keeps_readiness_diagnostic_for_global_export(self):
        readiness = body(MAIN, 'private void authenticateInstalledBridge(')
        self.assertIn('.authenticateAccess()', readiness)
        self.assertIn('captureDiagnostic(error)', readiness)
        capture = body(MAIN, 'private void captureDiagnostic(')
        self.assertIn('BridgeDiagnostics.report(', capture)
        self.assertIn('BridgeDiagnostics.find(exc).saveLayout != null', capture)
        self.assertIn('DIAGNOSTIC_PRIVACY.text()', capture)
        self.assertIn('AndroidDiagnosticLogging.failureDetails(error)', readiness)

    def test_provider_sends_bounded_diagnostic_fields_only(self):
        describe = body(PROVIDER, 'private static void describeContent(')
        for field in ('saveLayoutDiagnosticVersion', 'rootEntryNames', 'rootEntryTypes', 'rootEntryClassifications', 'rootEntriesTruncated'):
            self.assertIn('"' + field + '"', describe)
        self.assertNotIn('getAbsolutePath', describe)
        self.assertNotIn('readAllBytes', describe)

if __name__ == '__main__':
    unittest.main()
