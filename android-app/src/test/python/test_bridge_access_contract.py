"""Structural guards supplement production JVM provider/client policy tests; no Android execution."""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[4]
APP = ROOT / 'android-app/src/main/java/io/github/xyz1001/pdsync/android'
HELPER = ROOT / 'android-helper/src/main/java/io/github/xyz1001/pdsync/bridge'

class BridgeAccessContract(unittest.TestCase):
    def test_provider_on_create_and_authentication_are_inert(self):
        source=(HELPER/'SaveBridgeProvider.java').read_text()
        for section in (body(source,'public boolean onCreate()'), body(source,'private Bundle authenticateAccess()')):
            for forbidden in ('getFilesDir(', 'getNoBackupFilesDir(', 'prepareArea(', '.root(', '.observe(', 'SaveDirectoryPolicy.', 'Files.', 'mkdir'):
                self.assertNotIn(forbidden,section)
        dispatch=body(source,'public Bundle call(')
        self.assertLess(dispatch.index('return authenticateAccess();'),dispatch.index('initializeWorkspace();'))
        self.assertLess(dispatch.index('Unknown bridge method'),dispatch.index('initializeWorkspace();'))
        self.assertIn('"authenticate".equals(str)',dispatch)
        self.assertIn('!accessOnly && pendingPresent()',body(source,'private Bundle failure('))

    def test_old_runtime_is_proved_before_canonical_handshake(self):
        source=(APP/'BridgeClient.java').read_text()
        handshake=body(source,'public String authenticateAccess()')
        self.assertLess(handshake.index('requirePureAccessProtocol('),handshake.index('call("authenticate", null)'))
        self.assertNotIn('inspect',handshake)
        for field in ('accessProtocolVersion','authenticated','metadataOnly'):
            self.assertIn(field,handshake)
        self.assertIn('finally { closeReply(reply); }',handshake)
        self.assertIn('PURE_HANDSHAKE_REQUIRED',source)
        proof=body(source,'static void requirePureAccessProtocol(')
        self.assertIn('"1".equals(binding.accessProtocol)',proof)
        self.assertIn('InstalledBridgeProtocol.requireKnownRuntime',proof)
        self.assertNotIn('apkSetSha256',proof)
        self.assertNotIn('inspect',proof)

    def test_access_does_not_validate_receipts_or_read_source_archives(self):
        source=(APP/'PackageTrust.java').read_text()
        access=body(source,'static boolean requireBridgeForAccess(')
        self.assertIn('InstalledBridgeResolver.resolve',access)
        for forbidden in ('validatePrepared(', 'apkSetSha256(', 'sha256(', 'currentLocalCertificateSha256('):
            self.assertNotIn(forbidden,access)
        self.assertNotIn('requirePinnedSigner',(HELPER/'MainCallerAuthenticator.java').read_text())
        self.assertNotIn('signer(main)',source)

    def test_real_file_inspection_follows_parent_capability_check(self):
        source=(HELPER/'SaveBridgeProvider.java').read_text()
        inspect=body(source,'private Bundle inspect()')
        self.assertLess(inspect.index('verifySaveParent(fileRoot)'),inspect.index('SaveDirectoryPolicy.content(fileRoot)'))
        self.assertIn('parentIdentity.equals(verifySaveParent(fileRoot))',inspect)
        self.assertNotIn('mkdir',inspect)
        parent=body(source,'private String verifySaveParent(')
        for required in ('SafeSaveTree.checkDirectory(parent)','Os.open(', 'OsConstants.O_NOFOLLOW','Os.fstat(', 'Os.close('):
            self.assertIn(required,parent)
        policy=(HELPER/'SaveDirectoryPolicy.java').read_text()
        missing=body(policy,'public static boolean present(')
        self.assertIn('catch (NoSuchFileException',missing)
        self.assertNotIn('catch (IOException',missing)

if __name__=='__main__':unittest.main()
