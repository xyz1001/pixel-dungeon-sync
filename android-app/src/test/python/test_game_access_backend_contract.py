"""Source wiring checks; executable value/interface checks use the current core jar."""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
CLIENT = (ROOT / 'BridgeClient.java').read_text()
FACTORY = (ROOT / 'GameAccessBackends.java').read_text()
RUNTIME = (ROOT / 'SyncRuntime.java').read_text()
SERVICE = (ROOT / 'AutoSyncService.java').read_text()


class GameAccessBackendContract(unittest.TestCase):
    def test_bridge_retains_direct_core_operations_and_explicit_modes(self):
        self.assertIn('class BridgeClient implements GameAccessBackend', CLIENT)
        self.assertIn('selected.embeddedBridge() ? Mode.EMBEDDED_BRIDGE : Mode.LEGACY_HELPER_BRIDGE',
                      body(CLIENT, 'public Mode mode()'))
        self.assertIn('new Capabilities(true, true, true, true)', CLIENT)
        self.assertIn('return CAPABILITIES;', body(CLIENT, 'public Capabilities capabilities()'))
        self.assertNotIn('ROOT_DIRECT', CLIENT)

    def test_lifecycle_evidence_still_comes_from_authenticated_exchange(self):
        observer = body(CLIENT, 'public Observation observeLifecycle()')
        self.assertIn('exchange("inspect", null)', observer)
        for field in ('gameForeground', 'gameProcessPresent', 'ready', 'observationReliable'):
            self.assertIn('reply.getBoolean("' + field + '", false)', observer)
        self.assertIn('catch (Exception unused)', observer)
        self.assertIn('return Observation.unknown();', observer)
        self.assertNotIn('capabilities()', observer)
        self.assertNotIn('mode()', observer)

    def test_factory_keeps_prepared_selection_and_identity_without_readiness_claims(self):
        factory = body(FACTORY, 'public static GameAccessBackend forPreparedGame(')
        self.assertIn('return new BridgeClient(context, prepared, identity);', factory)
        self.assertNotIn('inspectReady(', factory)
        self.assertNotIn('ROOT_DIRECT', factory)
        self.assertNotIn('System.', factory)
        self.assertNotIn('ProcessBuilder', factory)

    def test_facade_and_observer_depend_on_the_factory_boundary(self):
        facade = body(RUNTIME, 'static AndroidSyncFacade facade(')
        self.assertIn('backend(context)', facade)
        self.assertIn('GameAccessBackends.forPreparedGame(context, prepared, gameIdentity(context, prepared))', body(RUNTIME, 'GameAccessBackend backend('))
        self.assertNotIn('new BridgeClient(', facade)
        self.assertIn('private GameAccessBackend bridge;', SERVICE)
        self.assertIn('GameAccessBackend.Observation observationObserveLifecycle', SERVICE)
        self.assertIn('this.bridge = SyncRuntime.backend(this)', SERVICE)
        self.assertNotIn('new BridgeClient(', SERVICE)

    def test_metadata_does_not_replace_existing_readiness_or_automatic_gates(self):
        readiness = body(CLIENT, 'public String inspectReady()')
        self.assertIn('call("inspect", null)', readiness)
        self.assertIn('BridgeReadiness.failureCode(', readiness)
        self.assertNotIn('capabilities()', readiness)
        automatic = body(RUNTIME, 'static boolean automaticAllowed(')
        self.assertIn('if (rootMode(context) || !requirePreparedGame(context).embeddedBridge()) return false;', automatic)
        self.assertIn('OnboardingGate.allowsAutomatic(', automatic)
        self.assertNotIn('capabilities()', automatic)
        self.assertIn('call("recover", null)', body(CLIENT, 'public String recoverInterruptedBackup()'))


if __name__ == '__main__':
    unittest.main()
