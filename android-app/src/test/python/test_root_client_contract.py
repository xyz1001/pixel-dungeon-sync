"""Source-level safety contracts alongside injectable JVM root-client tests."""
from pathlib import Path
import re
import unittest

BASE = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'

class RootClientContract(unittest.TestCase):
    def test_root_paths_do_not_prepare_install_or_generate_keys(self):
        for name in ('SelectedRootGame', 'RootSelectionStore', 'RootTargetResolver', 'RootGameBridge', 'RootProcessTransport'):
            text = (BASE / (name + '.java')).read_text()
            for forbidden in ('.prepareInstalledGame(', '.prepare(', '.install(', '.uninstall(',
                              'obtainIdentity(', 'LocalApkSigner.obtain', 'KeyPairGenerator', 'PackageInstaller'):
                self.assertNotIn(forbidden, text, (name, forbidden))

    def test_root_provenance_is_validated_not_manifest_accepted(self):
        text = (BASE / 'RootTargetResolver.java').read_text()
        self.assertIn('PackageTrust.currentSigners(info)', text)
        self.assertIn('PackageTrust.installedApks(info)', text)
        self.assertIn('preparation.reloadPrepared()', text)
        self.assertIn('preparation.validatePrepared(retained)', text)
        self.assertIn('PackageTrust.apkSetSha256(retained.gameApks)', text)
        self.assertIn('retained.sourceSignerCertificateSha256', text)
        self.assertNotIn('metadata.getString(', text)

    def test_backend_switch_is_blocked_and_cancels_live_root(self):
        text = (BASE / 'SyncRuntime.java').read_text()
        for name in ('setRootGame', 'useEmbeddedBackend'):
            body = text.split('static void ' + name, 1)[1].split('\n    }', 1)[0]
            self.assertIn('requireBackendSwitchAllowed(context)', body)
            self.assertIn('AutoSyncService.cancelForConnectionChange(context)', body)
            self.assertIn('closeRootBackend()', body)
            self.assertIn('requireFreshBackendComparison(context)', body)
        self.assertIn('hasUnresolvedRestore', text)
        self.assertIn('new InstallCoordinator(context).hasActiveRequest()', text)
        self.assertIn('AndroidGameIdentity.forPackage(root.gamePackage, root.gameLabel, root.originIdentity)', text)
        self.assertNotIn('"namespace."', text)
        self.assertIn('backendSupportsAutomatic(access.mode(), access.capabilities())', text)
        self.assertIn('capabilities.exportSupported && capabilities.lifecycleObservation', text)
        self.assertIn('mode != GameAccessBackend.Mode.LEGACY_HELPER_BRIDGE', text)

    def test_root_opt_in_never_probes_from_lookup(self):
        runtime = (BASE / 'SyncRuntime.java').read_text()
        for method in ('rootGame', 'rootAuthorized', 'rootSelectionMatches'):
            body = re.search(r'static .*? ' + method + r'\(Context context[^)]*\).*?\n    }', runtime, re.S).group()
            self.assertNotIn('.exchange(', body)
            self.assertNotIn('.probeAuthorization(', body)
        store = (BASE / 'RootSelectionStore.java').read_text()
        self.assertNotIn('rootAuthorizationToken', store)
        self.assertNotIn('rootAuthorizationToken', runtime)
        probe = runtime.split('static String probeRootAuthorization', 1)[1].split('\n    }', 1)[0]
        self.assertNotIn('requireBackendSwitchAllowed', probe)
        self.assertNotIn('hasUnresolvedRestore', probe)
        self.assertIn('current.matches(game)', probe)
        self.assertNotIn('password', store)
        transport = (BASE / 'RootProcessTransport.java').read_text()
        self.assertIn('cancelled.set(true)', transport)
        self.assertIn('destroyForcibly()', transport)
        self.assertNotIn('request.binding.packageName +', transport)
        self.assertNotIn('request.binding.dataDir +', transport)

if __name__ == '__main__':
    unittest.main()
