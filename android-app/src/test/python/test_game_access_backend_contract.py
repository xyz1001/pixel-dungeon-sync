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
        self.assertIn('embedded() ? Mode.EMBEDDED_BRIDGE : Mode.LEGACY_HELPER_BRIDGE',
                      body(CLIENT, 'public Mode mode()'))
        self.assertIn('new Capabilities(true, true, false, true)', CLIENT)
        self.assertIn('return CAPABILITIES;', body(CLIENT, 'public Capabilities capabilities()'))
        self.assertNotIn('ROOT_DIRECT', CLIENT)

    def test_lifecycle_observation_is_unsupported_without_any_exchange(self):
        observer = body(CLIENT, 'public Observation observeLifecycle()')
        self.assertIn('return Observation.unknown();', observer)
        self.assertNotIn('exchange(', observer)
        self.assertNotIn('call(', observer)

    def test_factory_keeps_prepared_selection_and_identity_without_readiness_claims(self):
        factory = body(FACTORY, 'public static GameAccessBackend forPreparedGame(')
        self.assertIn('return new BridgeClient(context, prepared, identity);', factory)
        self.assertNotIn('inspectReady(', factory)
        self.assertNotIn('ROOT_DIRECT', factory)
        self.assertNotIn('System.', factory)
        self.assertNotIn('ProcessBuilder', factory)

    def test_facade_and_observer_depend_on_the_factory_boundary(self):
        facade = body(RUNTIME, 'private static AndroidSyncFacade createFacade(')
        self.assertIn('backend(context)', facade)
        self.assertIn('GameAccessBackends.forPreparedGame(context, prepared, gameIdentity(context, prepared))', body(RUNTIME, 'GameAccessBackend backend('))
        self.assertNotIn('new BridgeClient(', facade)
        self.assertNotIn('observeLifecycle(', SERVICE)
        self.assertNotIn('lifecycleObservation', SERVICE)
        self.assertIn('this.facade = SyncRuntime.facade(this, this.connection)', SERVICE)
        self.assertNotIn('new BridgeClient(', SERVICE)

    def test_metadata_does_not_replace_existing_readiness_or_automatic_gates(self):
        readiness = body(CLIENT, 'public String inspectReady()')
        self.assertIn('call("inspect", null)', readiness)
        self.assertIn('BridgeReadiness.failureCode(', readiness)
        self.assertNotIn('capabilities()', readiness)
        automatic = body(RUNTIME, 'static boolean automaticAllowed(')
        self.assertIn('backendSupportsAutomatic(', automatic)
        self.assertNotIn('rootMode(context) ||', automatic)
        support = body(RUNTIME, 'static boolean backendSupportsAutomatic(')
        self.assertIn('exportSupported', support)
        self.assertNotIn('lifecycleObservation', support)
        self.assertIn('LEGACY_HELPER_BRIDGE', support)
        for operation in ('inspectReady(', 'observeLifecycle(', 'probeRootAuthorization(', 'exportTo('):
            self.assertNotIn(operation, automatic)
        self.assertIn('OnboardingGate.allowsAutomatic(', automatic)
        self.assertIn('capabilities()', automatic)
        self.assertIn('call("recover", null)', body(CLIENT, 'public String recoverInterruptedBackup()'))


if __name__ == '__main__':
    unittest.main()
