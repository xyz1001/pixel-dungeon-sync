"""Source wiring checks supplement host state tests; no Android installer executes."""
import unittest
from pathlib import Path
from test_main_comparison_contract import method

ROOT = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (ROOT / 'MainActivity.java').read_text()
INSTALL = (ROOT / 'InstallCoordinator.java').read_text()
CLIENT = (ROOT / 'BridgeClient.java').read_text()


def body(source, signature):
    start = source.index(signature)
    start = source.index('{', start)
    depth = 1
    end = start + 1
    while depth:
        if source[end] == '{': depth += 1
        if source[end] == '}': depth -= 1
        end += 1
    return source[start:end]


class EmbeddedInstallContractTest(unittest.TestCase):
    def test_main_prepares_runtime_dex_and_no_helper_apk_or_update_action(self):
        self.assertIn('getAssets().open("bridge-runtime.dex")', MAIN)
        for removed in ('helper-unsigned.apk', 'prepareHelperUpdate(', 'this.prepared.helperApk', 'this.prepared.helperPackage'):
            self.assertNotIn(removed, MAIN)
        discovery = body(MAIN, 'private SharedGameSelectionState gameSelectionState()')
        prepare = body(MAIN, 'GamePreparationService.PreparedPair m58lambda$prepare$33')
        self.assertIn('getAssets().open("bridge-runtime.dex")', prepare)
        self.assertIn('prepareSelectedApks(list, z, runtimeDex)', prepare)
        self.assertIn('SELECT_AN_INSTALLED_APP_41.text()', discovery)
        self.assertIn('SELECT_AN_APK_FILE_42.text()', discovery)
        self.assertNotIn('只修改共享 UID', discovery)
        self.assertNotIn('更新存档助手', discovery)

    def test_installer_only_accepts_game_and_embedded_receipts(self):
        target = body(INSTALL, 'private static boolean isGame(')
        self.assertIn('requireEmbedded(pair)', target)
        self.assertIn('!pair.gamePackage.equals(packageName)', target)
        self.assertIn('throw new IOException', target)
        request = body(INSTALL, 'public void requestInstall(')
        self.assertIn('List<File> apks=pair.gameApks;', request)
        self.assertNotIn('helperApk', INSTALL)
        self.assertNotIn('helperPackage', request)
        self.assertNotIn('helperPackage', body(INSTALL, 'public void requestUninstall('))
        self.assertNotIn('helperStrategy', INSTALL)
        self.assertIn('InstallSessionWriter.requireNames(session.getNames(),apks.size())', request)

    def test_installer_uses_accepted_writes_without_reading_session_storage(self):
        request = body(INSTALL, 'public void requestInstall(')
        self.assertNotIn('.openRead(', request)
        self.assertIn('InstallSessionWriter.Snapshot source=validatedOutputs', request)
        self.assertIn('InstallSessionWriter.inspect(pair.gameApks)', body(INSTALL, 'private void rememberOutputs('))
        self.assertIn('InstallSessionWriter.write(source,', request)
        self.assertIn('session.openWrite(name,0,length)', request)
        self.assertIn('session.fsync(output)', request)
        self.assertNotIn('InstallSessionWriter.verifySources(source)', request)
        self.assertEqual(2, request.count('ValidatedPreparationCache.requireUnchanged(pair)'))
        self.assertLess(request.index('freshPair()'), request.index('InstallSessionWriter.write(source,'))
        self.assertLess(request.index('InstallSessionWriter.write(source,'), request.rindex('ValidatedPreparationCache.requireUnchanged(pair)'))
        self.assertLess(request.rindex('ValidatedPreparationCache.requireUnchanged(pair)'), request.index('session.commit('))
        self.assertLess(request.index('InstallSessionWriter.requireNames('), request.index('session.commit('))
        self.assertIn('installer!=null && sessionId>=0', request)
        self.assertIn('installer.abandonSession(sessionId)', request)
        self.assertNotIn('getMySessions', request)
        result = body(INSTALL, 'private void reconcileRequest()')
        self.assertIn('InstalledBridgeResolver.resolve(context, strPendingPackage)', result)
        self.assertNotIn('PackageTrust.apkSetSha256(', result)

    def test_installer_diagnostics_are_copyable_plain_text_and_stage_specific(self):
        request = body(INSTALL, 'public void requestInstall(')
        for stage in ('CREATE', 'OPEN', 'READ_SOURCE_VERIFY', 'GET_NAMES', 'CHECKPOINT', 'CALLBACK', 'COMMIT', 'CLOSE'):
            self.assertIn('InstallDiagnostics.Stage.' + stage, request)
        self.assertIn('InstallDiagnostics.report(failure)', request)
        self.assertIn('Log.e("PDInstall",report.substring(', request)
        self.assertNotIn('Log.e("PDInstall",report,failure)', request)
        self.assertLess(request.index('callback.getIntentSender()'), request.index('commitAttempted=true'))
        capture = body(MAIN, 'private void captureDiagnostic(')
        self.assertIn('InstallDiagnostics.find(exc)', capture)
        self.assertIn('this.diagnosticReport = InstallDiagnostics.report(installFailure)', capture)
        self.assertIn('installFailure.userMessage()', body(MAIN, 'private String safeError('))

    def test_single_real_readiness_state_uses_pure_installed_handshake(self):
        readiness = body(MAIN, 'private void readInstallationReadiness()')
        self.assertIn('InstalledBridgeResolver.resolve', readiness)
        self.assertIn('authenticateInstalledBridge(this.installedBridge)', readiness)
        handshake = body(MAIN, 'private void authenticateInstalledBridge(')
        self.assertIn('.authenticateAccess()', handshake)
        self.assertNotIn('.inspectReady()', handshake)
        self.assertIn('this.readinessState.withAccessCode(this.bridgeAccessCode)', handshake)

    def test_uninstall_consent_uses_prevalidated_session_and_actual_reason(self):
        request = body(INSTALL, 'public void requestUninstall(')
        for guard in ('ValidatedPreparationCache.requireUnchanged', 'approvedUninstallReceipt.equals(str2)',
                      'targetStamp(str).equals(approvedTargetStamp)', 'hasUnresolvedRestore('):
            self.assertLess(request.index(guard), request.index('.uninstall('))
        for expensive in ('freshPair()', 'validatePrepared(', 'apkSetSha256(', 'identity(str)'):
            self.assertNotIn(expensive, request)
        consent = body(INSTALL, 'public String uninstallConsentReceipt(')
        self.assertIn('identity(packageName)', consent)
        self.assertIn('rememberOutputs(pair)', consent)
        self.assertIn('InstallUiCopy.uninstallWarning(next.pair.gameStrategyReason)', method('m39x9de049ed'))

    def test_resumed_uninstall_token_rechecks_original_and_prepared_contents(self):
        resume = body(INSTALL, 'public PendingIntent confirmationToken()')
        for guard in ('freshPair()', 'isGame(', 'targetStamp(pendingPackage())', 'uninstallPreparedApkSet', 'uninstallOrigin', 'hasUnresolvedRestore('):
            self.assertIn(guard, resume)
        self.assertLess(resume.index('uninstallPreparedApkSet'), resume.index('return pendingIntentFind'))

    def test_uncertain_commit_and_uninstall_cancellation_are_not_replayed(self):
        request = body(INSTALL, 'public void requestInstall(')
        self.assertLess(request.index('commitAttempted=true'), request.index('session.commit('))
        self.assertIn('if(commitAttempted)', request)
        cancellation = body(INSTALL, 'public void cancelSession()')
        self.assertLess(cancellation.index('"uninstall"'), cancellation.index('abandonSession('))
        self.assertIn('throw new IOException', cancellation)
        self.assertIn('AndroidMessages.INSTALL_COORDINATOR_72.text()', cancellation)

    def test_expired_legacy_cleanup_does_not_require_installable_embedded_pair(self):
        cleanup = body(INSTALL, 'public void acknowledgeExpiredRequest(')
        self.assertIn('mayResetExpiredConfirmation(z, z2)', cleanup)
        self.assertIn('retainedCleanupPair()', cleanup)
        self.assertNotIn('freshPair()', cleanup)
        self.assertIn('identity(strPendingPackage)', cleanup)
        self.assertIn('clearCleanupCheckpoint(', cleanup)
        self.assertIn('freshPair()', body(INSTALL, 'public PendingIntent confirmationToken()'))

    def test_legacy_live_token_cleanup_requires_explicit_closure_and_never_replays(self):
        cleanup = body(INSTALL, 'public void cancelObsoleteLegacyRequest(')
        self.assertIn('if (!dialogClosedByUser)', cleanup)
        self.assertIn('retainedCleanupPair()', cleanup)
        self.assertIn('identity(pendingPackage())', cleanup)
        self.assertIn('mayCancelObsoleteLegacy(', cleanup)
        self.assertIn('info.getAppPackageName()', cleanup)
        self.assertIn('installer.abandonSession(session)', cleanup)
        self.assertIn('SystemConfirmationToken.cancel(', cleanup)
        for forbidden in ('.uninstall(', 'requestInstall(', 'confirmationToken()', '.send(', 'freshPair()'):
            self.assertNotIn(forbidden, cleanup)
        self.assertLess(cleanup.index('mayCancelObsoleteLegacy('), cleanup.index('SystemConfirmationToken.cancel('))
        reset = body(INSTALL, 'private void clearCleanupCheckpoint(')
        self.assertIn('.remove("nonce")', reset)
        self.assertIn('.remove("operation")', reset)
        self.assertNotIn('gameRemoved', reset)
        ui = body(MAIN, 'private void confirmObsoleteLegacyCleanup()')
        self.assertIn('FIRST_TAP_CANCEL_AND_CLOSE_THE_62.text()', ui)
        self.assertIn('cancelObsoleteLegacyRequest(true)', ui)
        discovery = body(MAIN, 'private SharedGameSelectionState gameSelectionState()')
        self.assertIn('if (active && this.installer.hasConfirmation())', discovery)
        self.assertIn('OPEN_SYSTEM_CONFIRMATION', discovery)

    def test_runtime_binding_rechecked_after_successful_reply(self):
        exchange = body(CLIENT, 'private Bundle exchange(')
        self.assertEqual(2, exchange.count('requireTrust("authenticate".equals(operation))'))
        gate = body(CLIENT, 'private boolean requireTrust(')
        for required in ('InstalledBridgeResolver.requireUnchanged', 'PackageTrust.requireBridgeForExchange', 'PackageTrust.requireBridgeForAccess'):
            self.assertIn(required, gate)
        for field in ('bridgeMode', 'bridgeRuntimeVersion', 'providerPackage'):
            self.assertIn(field, exchange)
        self.assertIn('REPLY_IDENTITY_MISMATCH', exchange)
        self.assertIn('closeRejectedReply(reply,error)', exchange)


if __name__ == '__main__':
    unittest.main()
