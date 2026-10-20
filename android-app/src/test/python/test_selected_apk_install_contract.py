"""Production handoff and persistence contracts; not Android device execution."""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (ROOT / 'MainActivity.java').read_text()
INSTALL = (ROOT / 'InstallCoordinator.java').read_text()
RECEIVER = (ROOT / 'InstallResultReceiver.java').read_text()
PREPARATION = (ROOT / 'prepare/GamePreparationService.java').read_text()


class SelectedApkInstallationContract(unittest.TestCase):
    def test_selected_files_prepare_without_installed_bridge_substitution(self):
        choose = body(MAIN, 'private void chooseApkSetupSource(')
        files = body(choose, 'if (source.installedPackage == null)')
        self.assertIn('requestPrepare(null, source.apks)', files)
        for forbidden in ('InstalledBridgeResolver', 'authenticateInstalledBridge', 'selectInstalledSource'):
            self.assertNotIn(forbidden, files)
        prepare = body(MAIN, 'GamePreparationService.PreparedPair m58lambda$prepare$33')
        self.assertIn('this.preparation.prepareSelectedApks(list, z, runtimeDex)', prepare)
        self.assertIn('this.installer.registerPrepared(pair, list != null)', prepare)

    def test_explicit_file_install_intent_is_saved_and_overrides_exact_byte_reuse(self):
        register = body(INSTALL, 'public void registerPrepared(')
        self.assertIn('selectedFile ? pair.installationReceiptId() : ""', register)
        self.assertIn('.remove("systemStatus")', register)
        next_package = body(INSTALL, 'public String nextPackage(')
        self.assertLess(next_package.index('requiresSelectedFileInstallation(pair)'), next_package.index('installedMatches('))

    def test_unfinished_file_blocks_both_stored_and_discovered_bridges_without_hashing(self):
        readiness = body(MAIN, 'private void readInstallationReadiness()')
        pending = body(MAIN, 'private boolean keepSelectedFileInstallationPending()')
        self.assertIn('this.installedBridge = null', pending)
        self.assertLess(readiness.index('reloadPreparedMetadata()'), readiness.index('keepSelectedFileInstallationPending()'))
        self.assertIn('return true;', pending)
        self.assertLess(readiness.index('keepSelectedFileInstallationPending()'), readiness.index('SyncRuntime.installedBridge'))
        self.assertLess(readiness.index('keepSelectedFileInstallationPending()'), readiness.index('InstalledBridgeResolver.resolve'))
        self.assertEqual(2, readiness.count('keepSelectedFileInstallationPending()'))
        fallback = body(readiness, 'if (this.installedBridge == null && this.prepared == null)')
        self.assertIn('reloadPreparedMetadata()', fallback)
        self.assertIn('keepSelectedFileInstallationPending()', fallback)
        for forbidden in ('installedMatches', 'sha256', 'freshPair', 'authenticateInstalledBridge'):
            self.assertNotIn(forbidden, pending)

    def test_new_install_clears_old_status_and_binds_its_receipt(self):
        request = body(INSTALL, 'public void requestInstall(')
        self.assertIn('.putString("installRequestReceipt", pair.installationReceiptId()).remove("systemStatus")', request)
        self.assertLess(request.index('"installRequestReceipt"'), request.index('session.commit(sender)'))
        self.assertIn('InstallProgressPolicy.acceptsCallback(', RECEIVER)
        self.assertIn('string.equals(intent.getStringExtra("requestNonce"))', RECEIVER)

    def test_component_check_after_return_consumes_intent_without_signature_hash_or_status_gate(self):
        reconcile = body(INSTALL, 'private void reconcileRequest()')
        boundary = body(INSTALL, 'public boolean installationResultBoundary()')
        self.assertIn('SelectedApkInstallPolicy.mayCheckInstalledComponent(', boundary)
        self.assertIn('getSessionInfo(', boundary)
        self.assertLess(reconcile.index('if (!installationResultBoundary()) return'), reconcile.index('InstalledBridgeResolver.resolve'))
        self.assertIn('InstalledBridgeResolver.resolve(context, strPendingPackage)', reconcile)
        self.assertLess(reconcile.index('acceptsInstalledComponent('), reconcile.index('.remove("selectedFileInstallReceipt")'))
        self.assertIn('SyncRuntime.setInstalledBridge(context, component)', reconcile)
        self.assertLess(reconcile.index('.putBoolean("installedVerified", true).putString("state", "READY")'),
                        reconcile.index('SyncRuntime.setInstalledBridge(context, component)'))
        self.assertIn('completedFileInstallReceipt', reconcile)
        for forbidden in ('apkSetSha256', 'currentLocalCertificateSha256', 'getInt("systemStatus"', 'freshPair(', 'getVersionCode'):
            self.assertNotIn(forbidden, reconcile)
        for signature in ('public void cancelSession()', 'public void settleClosedSystemRequest()',
                          'private void clearCleanupCheckpoint(', 'public void requestUninstall('):
            self.assertNotIn('.remove("selectedFileInstallReceipt")', body(INSTALL, signature))

    def test_result_boundary_is_bounded_and_precedes_reopening_old_confirmation(self):
        drain = body(MAIN, 'private void drainPendingSetup()')
        self.assertLess(drain.index('installationResultBoundary()'), drain.index('openInstallConfirmation()'))
        self.assertIn('!this.installer.confirmationWasLaunched()', drain)
        self.assertIn('this.installer.reconcile(returned)', drain)
        self.assertIn('this.pendingSystemChecks >= 12 && this.installer.hasPendingResult()', drain)
        self.assertIn('postDelayed(owner::drainPendingSetup, 500L)', drain)
        self.assertIn('if (!hasPendingResult()) return', body(INSTALL, 'public void settleClosedSystemRequest()'))
        self.assertIn('putBoolean("installationReturned", true)', body(INSTALL, 'public void reconcile(boolean'))
        result = body(MAIN, 'protected void onActivityResult(')
        install = body(result, 'if (i == SYSTEM_INSTALL)')
        self.assertNotIn('RESULT_OK', install)
        self.assertNotIn('i2', install)

    def test_first_step_uses_component_metadata_without_claiming_save_access(self):
        advance = body(MAIN, 'private boolean canAdvanceGameSelection()')
        self.assertIn('this.installer.acceptedInstalledComponent(this.installedBridge)', advance)
        readiness = body(MAIN, 'private void readInstallationReadiness()')
        component = body(readiness, 'if (this.page == SyncPage.DISCOVERY')
        self.assertIn('InstallationReadiness.AccessStatus.NOT_CHECKED', component)
        for forbidden in ('authenticateAccess', 'authenticateInstalledBridge', 'inspectReady', 'exportTo'):
            self.assertNotIn(forbidden, component)
        activation = body(MAIN, 'private void maybeActivateSetupComponent()')
        self.assertIn('this.page == SyncPage.DISCOVERY && this.installer.acceptedInstalledComponent', activation)
        next_step = body(MAIN, 'void m103x3c55a874()')
        self.assertIn('this.onboardingActive = true; navigate(SyncPage.CONNECTION)', next_step)
        for forbidden in ('SyncPage.PATH', 'previewPath(', 'authenticateInstalledBridge(',
                          '.inspectReady(', '.exportTo(', 'readComparison(', 'refreshComparison('):
            self.assertNotIn(forbidden, next_step)

    def test_only_accepted_installed_apk_choice_can_replace_file_intent(self):
        choose = body(MAIN, 'private void chooseApkSetupSource(')
        self.assertLess(choose.index('modeChoice.complete(request)'), choose.index('owner.installer.selectInstalledSource()'))
        self.assertLess(choose.index('owner.installer.selectInstalledSource()'), choose.index('owner.beginModification()'))
        installed = body(INSTALL, 'public void selectInstalledSource()')
        self.assertIn('preparation.currentReceiptId()', installed)
        self.assertNotIn('reloadPreparedMetadata', installed)
        for signature in ('private void offerGameModeChoice(', 'private void chooseInlineApkMode()',
                          'private void resolveRootSetupSource(', 'protected void onResume()', 'protected void onPause()'):
            self.assertNotIn('selectInstalledSource()', body(MAIN, signature))

    def test_file_intent_is_part_of_the_atomic_preparation_before_publication(self):
        prepare = body(PREPARATION, 'private PreparedPair prepare(')
        self.assertLess(prepare.index('receipt.setProperty("selectedFileInstallation",'),
                        prepare.index('writeProperties(new File(directory, "receipt.properties"), receipt)'))
        self.assertLess(prepare.index('receipt.setProperty("selectedFileInstallation",'),
                        prepare.index('writeBytes(new File(root, "current"),'))
        require = body(INSTALL, 'public boolean requiresSelectedFileInstallation(')
        self.assertIn('SelectedApkInstallPolicy.recoverIntent(', require)
        self.assertIn('completedFileInstallReceipt', require)
        self.assertIn('replacedFileInstallReceipt', require)
        self.assertIn('persist(prefs.edit().putString("selectedFileInstallReceipt", required))', require)


if __name__ == '__main__':
    unittest.main()
