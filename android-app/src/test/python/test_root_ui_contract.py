"""Root UI policy source wiring, complementary to host backend/runner tests."""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (ROOT / 'MainActivity.java').read_text()
SERVICE = (ROOT / 'AutoSyncService.java').read_text()


class RootUiContract(unittest.TestCase):
    def test_setup_next_requires_proven_root_access(self):
        advance = body(MAIN, 'private boolean canAdvanceGameSelection()')
        self.assertIn('readyForSaves() && !restoreNeedsAttention()', advance)
        self.assertIn('canAdvanceGameSelection()', body(MAIN, 'private SharedGameSelectionState gameSelectionState()'))
        self.assertIn('canAdvanceGameSelection()', body(MAIN, 'void m103x3c55a874()'))
        request = body(MAIN, 'private void requestRootAuthorization()')
        self.assertNotIn('SyncPage.PATH', request)
        self.assertNotIn('previewPath()', request)

    def test_automatic_refresh_only_verifies_root_metadata(self):
        readiness = body(MAIN, 'private void readInstallationReadiness()')
        self.assertLess(readiness.index('readRootReadiness()'), readiness.index('this.preparation.reloadPreparedMetadata()'))
        root = body(MAIN, 'private void readRootReadiness()')
        self.assertNotIn('backend.targetGame()', root)
        self.assertIn('PackageTrust.installed(this, this.rootGame.gamePackage)', root)
        self.assertIn('backend.capabilities()', root)
        self.assertIn('AccessStatus.NOT_CHECKED', root)
        for operation in ('inspectReady(', 'observeLifecycle(', 'exportTo(', 'probeRootAuthorization('):
            self.assertNotIn(operation, root)
        initial = body(MAIN, 'Object m68x764cc8a4(')
        self.assertNotIn('readComparison()', initial)
        self.assertNotIn('checkRootAccess()', initial)
        resumed = body(MAIN, 'void m69xebc6eee5(')
        self.assertIn('this.rootGame == null && this.page == SyncPage.HOME', resumed)

    def test_explicit_check_has_no_repeated_custom_authorization_gate(self):
        request = body(MAIN, 'private void requestRootAuthorization()')
        self.assertNotIn('confirm(', request)
        self.assertIn('!this.resumed', request)
        self.assertIn('RootSetupAuthorization.probe(getApplicationContext(), packageName)', request)
        self.assertIn('authorizeRoot(generation, packageName)', request)
        self.assertNotIn('checkRootAccess(', request)
        self.assertNotIn('SyncPage.PATH', request)
        check = body(MAIN, 'private void checkRootAccess(boolean setupOnly)')
        self.assertIn('if (hasAnyRestoreJournal()) {', check)
        self.assertIn('SyncRuntime.probeRootAuthorization(this)', check)
        self.assertIn('AccessStatus.RECOVERY_NEEDED', check)
        self.assertLess(check.index('inspectReady()'), check.index('AccessStatus.READY'))
        discovery = body(MAIN, 'private SharedGameSelectionState gameSelectionState()')
        self.assertIn('!active && !recovery', discovery)
        self.assertNotIn('SharedGameSelectionControl.CHECK_ACCESS', discovery)

    def test_restart_allows_deliberate_operations_without_claiming_ready(self):
        requestable = body(MAIN, 'private boolean canRequestSaveAccess()')
        self.assertIn('canRequestRootAccess()', requestable)
        self.assertNotIn('AccessStatus.READY', requestable)
        startup = body(MAIN, 'void m69xebc6eee5(')
        self.assertIn('if (!canRequestSaveAccess() && !canAdvanceGameSelection())', startup)
        self.assertNotIn('checkRootAccess()', startup)
        preview = body(MAIN, 'private SaveSummary readPathPreview(')
        self.assertLess(preview.index('checkRootAccess(request, backend)'), preview.index('exportTo('))
        explicit_compare = body(MAIN, 'Boolean m66x99ceaa21(')
        self.assertIn('if (this.rootGame != null) checkRootAccess()', explicit_compare)

    def test_root_rendering_does_not_start_io(self):
        for signature in ('private SharedGameSelectionState gameSelectionState()', 'private void renderHome()', 'private void renderSettings()'):
            rendering = body(MAIN, signature)
            for operation in ('.inspectReady(', '.exportTo(', '.observeLifecycle(', 'probeRootAuthorization('):
                self.assertNotIn(operation, rendering)
        path = body(MAIN, 'private void schedulePathPreviewEntry()')
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

    def test_root_first_launch_fallback_uses_only_installed_identity(self):
        launch = body(MAIN, 'private void launchSelectedRootGame()')
        self.assertIn('AccessStatus.FIRST_LAUNCH_NEEDED', launch)
        self.assertIn('launchGame()', launch)
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
        self.assertIn('canHomeRestore()', body(MAIN, 'private void renderHome()'))
        self.assertIn('restoreSupported() && canRequestSaveAccess()', body(MAIN, 'private boolean canHomeRestore()'))
        self.assertIn('backupRecoverySupported()', body(MAIN, 'private SettingsCapabilities settingsCapabilities()'))
        self.assertIn('if (capabilities.canRecoverBackup)', body(MAIN, 'private void showSettingsMaintenance('))
        for signature in ('public void requestRestore()', 'public void previewMerge()', 'void m87x12f15700()'):
            self.assertIn('if (!allowRestoreAction()) return;', body(MAIN, signature))
        for signature in ('AndroidSyncFacade.OperationResult m141x92df934d(', 'AndroidSyncFacade.OperationResult m142x859b98e(',
                          'AndroidSyncFacade.OperationResult m61xf9a4e77a('):
            self.assertIn('requireRestoreSupport()', body(MAIN, signature))
        recovery = body(MAIN, 'String m113x3e2c50ed()')
        self.assertLess(recovery.index('capabilities().interruptedBackupRecovery'), recovery.index('recoverInterruptedBackup()'))

    def test_stale_embedded_callbacks_cannot_prepare_install_or_uninstall_root(self):
        for signature in ('String m138x3dde23b5(', 'GamePreparationService.PreparedPair m58lambda$prepare$33',
                          'InstallNext m32xc4841ad1()', 'Object m33x39fe4112(', 'Object m36x9a6cb3d5('):
            self.assertIn('if (SyncRuntime.rootMode(this)) throw', body(MAIN, signature))

    def test_auto_service_uses_actual_capabilities_and_passive_return(self):
        self.assertIn('SyncRuntime.currentSelectionToken(context)', SERVICE)
        self.assertIn('SyncRuntime.currentSelectionToken(this)', SERVICE)
        self.assertNotIn('observeLifecycle(', SERVICE)
        initialization = body(SERVICE, 'void m1x57256bb9()')
        self.assertLess(initialization.index('SyncRuntime.automaticAllowed'), initialization.index('SyncRuntime.facade(this, this.connection)'))
        launch = body(MAIN, 'void m49x6e86027e(')
        self.assertIn('!automaticObservationSupported()', launch)
        self.assertNotIn('if (this.rootGame != null ||', launch)
        capture = body(MAIN, 'Intent m48xf90bdc3d()')
        self.assertIn('if (this.rootGame != null && !this.cloudVerified) readComparison()', capture)
        self.assertLess(capture.index('readComparison()'), capture.index('beginControlledSession()'))
        self.assertNotIn('observeControlledSession(', SERVICE)
        self.assertIn('if (this.returned)', body(SERVICE, 'public void poll()'))
        settings = body(MAIN, 'private void renderSettings()')
        self.assertIn('selectedGame && automaticObservationSupported()', settings)
        self.assertIn('capabilities.canToggleAutomatic', settings)
        self.assertIn('if (current.canToggleAutomatic)', settings)


if __name__ == '__main__':
    unittest.main()
