"""Root UI policy source wiring, complementary to host backend/runner tests."""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (ROOT / 'MainActivity.java').read_text()
SERVICE = (ROOT / 'AutoSyncService.java').read_text()


class RootUiContract(unittest.TestCase):
    def test_automatic_refresh_only_verifies_root_metadata(self):
        readiness = body(MAIN, 'private void readInstallationReadiness()')
        self.assertLess(readiness.index('readRootReadiness()'), readiness.index('this.preparation.reloadPrepared()'))
        root = body(MAIN, 'private void readRootReadiness()')
        self.assertIn('backend.targetGame()', root)
        self.assertIn('backend.capabilities()', root)
        self.assertIn('AccessStatus.NOT_CHECKED', root)
        for operation in ('inspectReady(', 'observeLifecycle(', 'exportTo(', 'probeRootAuthorization('):
            self.assertNotIn(operation, root)
        initial = body(MAIN, 'Object m68x764cc8a4(')
        self.assertIn('z && this.rootGame == null && readyForSaves()', initial)
        resumed = body(MAIN, 'void m69xebc6eee5(')
        self.assertIn('this.rootGame == null && this.page == SyncPage.HOME', resumed)

    def test_authorization_is_explicit_foreground_and_bound_to_selection(self):
        self.assertEqual(MAIN.count('SyncRuntime.probeRootAuthorization(this)'), 1)
        request = body(MAIN, 'private void requestRootAuthorization()')
        self.assertIn('confirm("授权并检查 Root 访问？"', request)
        self.assertIn('!this.resumed', request)
        self.assertIn('chosen.matches(SyncRuntime.rootGame(this))', request)
        self.assertLess(request.index('hasAnyRestoreJournal()'), request.index('SyncRuntime.probeRootAuthorization(this)'))
        self.assertLess(request.index('SyncRuntime.probeRootAuthorization(this)'), request.index('checkRootAccess()'))
        check = body(MAIN, 'private void checkRootAccess()')
        self.assertIn('!this.resumed', check)
        self.assertLess(check.index('inspectReady()'), check.index('AccessStatus.READY'))

    def test_root_rendering_does_not_start_io(self):
        for signature in ('private void renderRootDiscovery()', 'private void renderHome()', 'private void renderSettings()'):
            rendering = body(MAIN, signature)
            for operation in ('.inspectReady(', '.exportTo(', '.observeLifecycle(', 'probeRootAuthorization('):
                self.assertNotIn(operation, rendering)
        path = body(MAIN, 'private void renderPath()')
        self.assertIn('if (this.rootGame != null || this.localPreview != null', path)
        callback = body(MAIN, 'void m111x434ac419()')
        self.assertIn('this.rootGame != null ||', callback)

    def test_root_selection_never_prepares_or_installs_an_apk(self):
        selection = body(MAIN, 'private void selectRootGame(')
        self.assertIn('new RootTargetResolver(this).selectInstalled(packageName)', selection)
        self.assertIn('SyncRuntime.setRootGame(this, chosen)', selection)
        self.assertIn('cancelControlledLaunch()', selection)
        self.assertIn('resetAccessSelection()', selection)
        for operation in ('prepareInstalledGame', 'prepareSelectedApks', 'currentLocalCertificateSha256', 'continueInstallation', 'uninstall'):
            self.assertNotIn(operation, selection)
        switch = body(MAIN, 'private void switchToEmbedded()')
        self.assertIn('SyncRuntime.useEmbeddedBackend(this)', switch)
        self.assertIn('cancelControlledLaunch()', switch)
        reset = body(MAIN, 'private void resetAccessSelection()')
        for field in ('facade', 'history', 'inspection', 'selected', 'confirmedAction', 'confirmationMerge'):
            self.assertIn('this.' + field + ' = null', reset)

    def test_root_game_launch_uses_only_installed_identity(self):
        launch = body(MAIN, 'private void launchSelectedRootGame()')
        self.assertIn('chosen.matches(SyncRuntime.rootGame(this))', launch)
        self.assertIn('SyncRuntime.backend(this).targetGame()', launch)
        self.assertIn('getLaunchIntentForPackage(chosen.gamePackage)', launch)
        self.assertIn('hasAnyRestoreJournal()', launch)
        self.assertIn('cancelControlledLaunch()', launch)
        for operation in ('inspectReady(', 'exportTo(', 'probeRootAuthorization(', 'prepareInstalledGame', 'freshPair('):
            self.assertNotIn(operation, launch)

    def test_restore_and_recovery_capabilities_gate_both_ui_and_actions(self):
        self.assertIn('accessCapabilities.restoreSupported', body(MAIN, 'private boolean restoreSupported()'))
        self.assertIn('accessCapabilities.interruptedBackupRecovery', body(MAIN, 'private boolean backupRecoverySupported()'))
        self.assertIn('restoreSupported() && readyForSaves()', body(MAIN, 'private void renderHome()'))
        self.assertIn('.setEnabled(backupRecoverySupported())', body(MAIN, 'private void renderSettings()'))
        for signature in ('public void requestRestore()', 'public void previewMerge()', 'void m87x12f15700()'):
            self.assertIn('if (!allowRestoreAction()) return;', body(MAIN, signature))
        for signature in ('AndroidSyncFacade.OperationResult m141x92df934d(', 'AndroidSyncFacade.OperationResult m142x859b98e(',
                          'AndroidSyncFacade.OperationResult m85x84f7e729(', 'AndroidSyncFacade.OperationResult m61xf9a4e77a('):
            self.assertIn('requireRestoreSupport()', body(MAIN, signature))
        recovery = body(MAIN, 'String m113x3e2c50ed()')
        self.assertLess(recovery.index('capabilities().interruptedBackupRecovery'), recovery.index('recoverInterruptedBackup()'))

    def test_stale_embedded_callbacks_cannot_prepare_install_or_uninstall_root(self):
        for signature in ('String m138x3dde23b5(', 'GamePreparationService.PreparedPair m58lambda$prepare$33',
                          'InstallNext m32xc4841ad1()', 'Object m33x39fe4112(', 'Object m36x9a6cb3d5('):
            self.assertIn('if (SyncRuntime.rootMode(this)) throw', body(MAIN, signature))

    def test_auto_service_uses_selected_backend_without_enabling_root(self):
        self.assertIn('SyncRuntime.currentSelectionToken(context)', SERVICE)
        self.assertIn('SyncRuntime.currentSelectionToken(this)', SERVICE)
        self.assertIn('this.bridge = SyncRuntime.backend(this)', SERVICE)
        initialization = body(SERVICE, 'void m1x57256bb9()')
        self.assertLess(initialization.index('SyncRuntime.automaticAllowed'), initialization.index('SyncRuntime.backend(this)'))
        self.assertIn('this.rootGame != null || this.connection == null', body(MAIN, 'void m49x6e86027e('))


if __name__ == '__main__':
    unittest.main()
