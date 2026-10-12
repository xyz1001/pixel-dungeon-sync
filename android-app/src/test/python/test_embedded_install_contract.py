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
        discovery = body(MAIN, 'private void renderDiscovery()')
        self.assertIn('追加存档桥 DEX', discovery)
        self.assertIn('原游戏 DEX 和资源逐项保留', discovery)
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
        self.assertIn('session.getNames().length!=apks.size()', request)

    def test_single_real_readiness_state_never_fakes_helper_verification(self):
        readiness = body(MAIN, 'private void readInstallationReadiness()')
        self.assertIn('InstallationReadiness.embedded(gameStatus, targetVerified,', readiness)
        self.assertNotIn('helperStatus', readiness)
        self.assertIn('bridge.targetGame()', readiness)
        self.assertLess(readiness.index('bridge.targetGame()'), readiness.index('targetVerified = true'))
        self.assertIn('bridge.inspectReady()', readiness)

    def test_uninstall_requires_fresh_preparation_identity_and_explicit_loss_warning(self):
        request = body(INSTALL, 'public void requestUninstall(')
        self.assertLess(request.index('freshPair()'), request.index('.uninstall('))
        self.assertLess(request.index('uninstallConsentReceipt('), request.index('.uninstall('))
        self.assertLess(request.index('hasUnresolvedRestore('), request.index('.uninstall('))
        self.assertIn('不可逆地永久删除全部本地存档和战绩', INSTALL)
        self.assertIn('APK 备份不是存档备份', INSTALL)
        consent = body(INSTALL, 'public String uninstallConsentReceipt(')
        self.assertIn('validatePrepared(pair)', consent)
        self.assertIn('PackageTrust.apkSetSha256(pair.gameApks)', consent)
        self.assertIn('identity(packageName)', consent)
        self.assertIn('InstallCoordinator.LOSS_WARNING', method('m39x9de049ed'))

    def test_resumed_uninstall_token_rechecks_original_and_prepared_contents(self):
        resume = body(INSTALL, 'public PendingIntent confirmationToken()')
        for guard in ('freshPair()', 'isGame(', 'identity(pendingPackage())', 'uninstallPreparedApkSet', 'uninstallOrigin', 'hasUnresolvedRestore('):
            self.assertIn(guard, resume)
        self.assertLess(resume.index('uninstallPreparedApkSet'), resume.index('SystemConfirmationToken.find'))

    def test_uncertain_commit_and_uninstall_cancellation_are_not_replayed(self):
        request = body(INSTALL, 'public void requestInstall(')
        self.assertLess(request.index('commitAttempted=true'), request.index('session.commit('))
        self.assertIn('if(commitAttempted)', request)
        cancellation = body(INSTALL, 'public void cancelSession()')
        self.assertLess(cancellation.index('"uninstall"'), cancellation.index('abandonSession('))
        self.assertIn('throw new IOException', cancellation)
        self.assertIn('系统确认尚未结束，不能当作原应用已保留', cancellation)

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
        self.assertIn('先在 Android 旧安装／卸载对话框点击取消并关闭', ui)
        self.assertIn('cancelObsoleteLegacyRequest(true)', ui)
        discovery = body(MAIN, 'private void renderDiscovery()')
        self.assertIn('this.installer.hasConfirmation() && !obsoleteLegacyRequest', discovery)

    def test_runtime_binding_rechecked_after_successful_reply(self):
        exchange = body(CLIENT, 'private Bundle exchange(')
        self.assertEqual(2, exchange.count('PackageTrust.requireBridgeForExchange('))
        for field in ('bridgeMode', 'bridgeRuntimeVersion', 'providerPackage'):
            self.assertIn(field, exchange)
        self.assertIn('REPLY_IDENTITY_MISMATCH', exchange)
        self.assertIn('closeRejectedReply(reply,error)', exchange)


if __name__ == '__main__':
    unittest.main()
