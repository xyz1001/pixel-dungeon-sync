"""Execute production Android identity/scope selection against host-only metadata doubles.

The real package resolver and facade persistence are exercised by AndroidPackageNamespaceTest.
No provider, device, Root, real account, or network is used here.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
BASE = REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android'
RUNTIME = (BASE / 'SyncRuntime.java').read_text()


class PackageNamespaceScopeContract(unittest.TestCase):
    def test_package_namespace_ignores_old_manual_overrides_and_uses_current_generation(self):
        methods = [
            'static AndroidGameIdentity gameIdentity(Context context, InstalledBridgeBinding binding)',
            'private static AndroidGameIdentity rootIdentity(Context context, SelectedRootGame root)',
            'static AndroidGameIdentity gameIdentity(Context context, GamePreparationService.PreparedPair preparedPair)',
            'static AndroidGameIdentity currentGameIdentity(Context context)',
            'private static String currentOrigin(Context context)',
            'static String currentNamespace(Context context)',
            'static String currentCloudNamespace(Context context)',
            'private static String scope(Context context, SecureConnectionStore.Connection connection2)',
        ]
        declarations = '\n'.join(signature + ' throws Exception ' + body(RUNTIME, signature) for signature in methods)
        java = r'''
import java.io.IOException;
import pdsync.i18n.AndroidMessages;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
public class NamespaceScopeChecks {
 static final String KNOWN = "32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89";
 static class Context { String mode, source; Prefs preferences = new Prefs(); }
 static class Prefs {
  Map<String,String> values = new HashMap<>();
  String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
 }
 static class AndroidGameIdentity {
  static final String LOCAL_STATE_GENERATION = "package-current-v1";
  String namespace, source;
  static AndroidGameIdentity forPackage(String pkg, String label, String source) {
   AndroidGameIdentity result = new AndroidGameIdentity(); result.namespace = "canonical:" + pkg;
   result.source = source; return result;
  }
  String getNamespace() { return namespace; }
 }
 static class InstalledBridgeBinding {
  String gamePackage = "org.example.game", gameLabel = "Synthetic", originIdentity, namespaceId;
  InstalledBridgeBinding(String origin) { originIdentity = origin; namespaceId = "android-" + origin; }
 }
 static class SelectedRootGame {
  String gamePackage = "org.example.game", gameLabel = "Synthetic", originIdentity;
  SelectedRootGame(String origin) { originIdentity = origin; }
 }
 static class GamePreparationService {
  static class PreparedPair {
   String gamePackage = "org.example.game", gameLabel = "Synthetic", originIdentity;
   PreparedPair(String origin) { originIdentity = origin; }
  }
 }
 static class SecureConnectionStore {
  static class Connection {
   String username = "existing-user", profile = "custom-profile";
   String stateEndpoint() { return "https://example.test/retained-endpoint/"; }
  }
 }
 static class PackageTrust {
  static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }
 }
 static Prefs prefs(Context context) { return context.preferences; }
 static SelectedRootGame rootGame(Context context) { return context.mode.equals("root") ? new SelectedRootGame(context.source) : null; }
 static InstalledBridgeBinding installedBridge(Context context) { return context.mode.equals("installed") ? new InstalledBridgeBinding(context.source) : null; }
 static GamePreparationService.PreparedPair requirePreparedGame(Context context) { return new GamePreparationService.PreparedPair(context.source); }
''' + declarations + r'''
 static void check(boolean value) { if (!value) throw new AssertionError(); }
 public static void main(String[] args) throws Exception {
  SecureConnectionStore.Connection connection = new SecureConnectionStore.Connection();
  int checked = 0;
  for (String mode : List.of("root", "installed", "prepared")) {
   for (String source : List.of(KNOWN, "a".repeat(64))) {
    for (String override : Arrays.asList(null, "existing-manual")) {
     Context context = new Context(); context.mode = mode; context.source = source;
     if (override != null) context.preferences.values.put("namespace." + source, override);
     String oldNamespace = override == null ? "android-" + source : override;
     String oldScope = PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest((source + "\n" + oldNamespace + "\n" + connection.stateEndpoint() + "\n" + connection.username + "\n" + connection.profile).getBytes(StandardCharsets.UTF_8)));
     String expectedScope = PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest(("package-current-v1\n" + source + "\ncanonical:org.example.game\n" + connection.stateEndpoint() + "\n" + connection.username + "\n" + connection.profile).getBytes(StandardCharsets.UTF_8)));
     check(currentCloudNamespace(context).equals("canonical:org.example.game"));
     check(currentNamespace(context).equals("canonical:org.example.game"));
     check(currentGameIdentity(context).source.equals(source));
     check(scope(context, connection).equals(expectedScope));
     check(!scope(context, connection).equals(oldScope));
     check(Objects.equals(context.preferences.values.get("namespace." + source), override));
     check(connection.profile.equals("custom-profile"));
     checked++;
    }
   }
  }
  System.out.println("NamespaceScopeChecks: " + checked + " backend/source/override combinations passed");
 }
}
'''
        with tempfile.TemporaryDirectory(prefix='pdsync-namespace-scope-') as temporary:
            source = Path(temporary) / 'NamespaceScopeChecks.java'
            source.write_text(java)
            compiled = subprocess.run(['javac', '-sourcepath', str(REPO / 'core/src/main/java'), '-d', temporary, str(source)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(['java', '-cp', temporary, 'NamespaceScopeChecks'], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('12 backend/source/override combinations passed', result.stdout)

    def test_listener_and_status_use_canonical_control_token_and_fresh_connection_scope(self):
        auto = (BASE / 'AutoSyncService.java').read_text()
        main = (BASE / 'MainActivity.java').read_text()
        self.assertIn('return currentGameIdentity(context).getNamespace()', body(RUNTIME, 'static String currentNamespace('))
        self.assertIn('AndroidGameIdentity.LOCAL_STATE_GENERATION', body(RUNTIME, 'private static String scope('))
        self.assertNotIn('\"namespace.\"', RUNTIME)
        self.assertIn('strCurrentNamespace.equals(sharedPreferences.getString("gameNamespace", ""))', auto)
        self.assertIn('String strCurrentNamespace = SyncRuntime.currentNamespace(context)', auto)
        self.assertNotIn('currentCloudNamespace(', auto)
        self.assertIn('sb.append(SyncRuntime.gameIdentity(this, preparedPair).getNamespace())', main)

    def test_fallbacks_use_canonical_cloud_identity_without_rewriting_trusted_bridge_binding(self):
        client = (BASE / 'BridgeClient.java').read_text()
        root = (BASE / 'RootGameBridge.java').read_text()
        self.assertIn('identity == null ? SyncRuntime.gameIdentity(context, binding) : identity', client)
        self.assertIn('identity==null?AndroidGameIdentity.forPackage(', root)
        self.assertIn('selected.originIdentity):identity', root)
        self.assertIn('selected.namespaceId : installedSelection.namespaceId).equals(reply.getString("namespaceId"))', client)
        for name in ['InstalledBridgeBinding.java', 'SelectedRootGame.java']:
            self.assertNotIn('GameCloudIdentity', (BASE / name).read_text())


if __name__ == '__main__':
    unittest.main()
