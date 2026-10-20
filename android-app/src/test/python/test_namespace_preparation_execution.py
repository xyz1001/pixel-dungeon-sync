"""Execute production namespace preparation against deterministic, non-network ports."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / "main/java/io/github/xyz1001/pdsync/android"
MAIN = (ROOT / "MainActivity.java").read_text()


class NamespacePreparationExecution(unittest.TestCase):
    def test_same_configuration_facade_rebuild_failure_and_stale_completion(self):
        methods = []
        for signature in ("private void prepareOfficialHomeNamespace(",
                          "private boolean currentOfficialHomeNamespace(",
                          "private void invalidatePreparedOfficialNamespace("):
            start = MAIN.index(signature)
            methods.append(MAIN[start:MAIN.index("{", start)] + body(MAIN, signature))
        fixture = r"""
package io.github.xyz1001.pdsync.android;
import java.io.IOException;
public class MainActivity {
    enum SyncPage { HOME, SETTINGS }
    enum OnboardingProgress { COMPLETE, PENDING }
    static class Text { String text() { return "existing failure"; } }
    static final Text SETTINGS_HAVE_CHANGED_PLEASE_RETRY_137 = new Text();
    static final Text THE_CLOUD_IS_TEMPORARILY_UNAVAILABLE_PLEASE_145 = new Text();
    static class SecureConnectionStore {
        static class Connection {
            String endpoint = "endpoint", username = "user", profile = "profile";
        }
    }
    static class SyncRuntime {
        static SecureConnectionStore.Connection account;
        static long generation = 1;
        static String selection = "selection", namespace = "namespace";
        static long connectionGeneration() { return generation; }
        static SecureConnectionStore.Connection currentConnection() { return account; }
        static String currentSelectionToken(MainActivity owner) { return selection; }
        static String currentNamespace(MainActivity owner) { return namespace; }
    }
    interface Action { void run() throws Exception; }
    static class AndroidSyncFacade {
        static class ConnectionStatus {
            final boolean success; ConnectionStatus(boolean value) { success = value; }
            boolean isSuccess() { return success; }
        }
        int connects; boolean success = true; Action hook = () -> {};
        ConnectionStatus connect() throws Exception { connects++; hook.run(); return new ConnectionStatus(success); }
    }
    OfficialNamespacePreparation preparedOfficialNamespace = new OfficialNamespacePreparation();
    SecureConnectionStore.Connection connection = new SecureConnectionStore.Connection();
    AndroidSyncFacade facade = new AndroidSyncFacade();
    SyncPage page = SyncPage.HOME;
    OnboardingProgress progress = OnboardingProgress.COMPLETE;
    boolean resumed = true, destroyed;
    MainActivity() { SyncRuntime.account = connection; SyncRuntime.selection = "selection"; SyncRuntime.namespace = "namespace"; SyncRuntime.generation++; }
    boolean isDestroyed() { return destroyed; }
    boolean isOfficialAccount(SecureConnectionStore.Connection candidate) { return candidate != null; }
    /* METHODS */
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void blocked(Action action) throws Exception {
        try { action.run(); throw new AssertionError("failure was swallowed"); } catch (IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        MainActivity a = new MainActivity(); a.prepareOfficialHomeNamespace(a.facade);
        check(a.facade.connects == 1, "first preparation must be real");
        AndroidSyncFacade original = a.facade;
        a.facade = new AndroidSyncFacade(); a.prepareOfficialHomeNamespace(a.facade);
        check(a.facade.connects == 0 && original.connects == 1, "new facade must reuse same configuration preparation");
        // A retained rotation holder has no Activity/facade ownership and remains valid.
        OfficialNamespacePreparation retained = a.preparedOfficialNamespace;
        a.preparedOfficialNamespace = retained; a.facade = new AndroidSyncFacade();
        a.prepareOfficialHomeNamespace(a.facade); check(a.facade.connects == 0, "rotation must retain successful key");
        for (int field = 0; field < 7; field++) {
            MainActivity changed = new MainActivity(); changed.prepareOfficialHomeNamespace(changed.facade);
            changed.facade = new AndroidSyncFacade();
            switch (field) {
                case 0: changed.connection = new SecureConnectionStore.Connection(); SyncRuntime.account = changed.connection; break;
                case 1: SyncRuntime.generation++; break;
                case 2: changed.connection.endpoint = "new"; break;
                case 3: changed.connection.username = "new"; break;
                case 4: changed.connection.profile = "new"; break;
                case 5: SyncRuntime.selection = "new"; break;
                case 6: SyncRuntime.namespace = "new"; break;
            }
            changed.prepareOfficialHomeNamespace(changed.facade);
            check(changed.facade.connects == 1, "changed binding retained preparation: " + field);
        }
        for (int error : new int[] {401, 403, 404, 409}) {
            MainActivity failed = new MainActivity(); failed.prepareOfficialHomeNamespace(failed.facade);
            failed.invalidatePreparedOfficialNamespace(); failed.facade = new AndroidSyncFacade();
            failed.facade.hook = () -> { throw new IOException("HTTP " + error); };
            blocked(() -> failed.prepareOfficialHomeNamespace(failed.facade));
            check(failed.facade.connects == 1, "failure skipped real preparation");
            failed.facade.hook = () -> {}; failed.prepareOfficialHomeNamespace(failed.facade);
            check(failed.facade.connects == 2, "failed preparation cached success");
        }
        MainActivity rejected = new MainActivity(); rejected.facade.success = false;
        blocked(() -> rejected.prepareOfficialHomeNamespace(rejected.facade));
        rejected.facade.success = true; rejected.prepareOfficialHomeNamespace(rejected.facade);
        check(rejected.facade.connects == 2, "unsuccessful status cached success");
        MainActivity stale = new MainActivity();
        stale.facade.hook = () -> { SyncRuntime.generation++; };
        blocked(() -> stale.prepareOfficialHomeNamespace(stale.facade));
        stale.facade.hook = () -> {}; stale.prepareOfficialHomeNamespace(stale.facade);
        check(stale.facade.connects == 2, "late response cached success for another generation");
        System.out.println("Namespace preparation checks passed");
    }
}
"""
        with tempfile.TemporaryDirectory(prefix="pdsync-namespace-key-") as tmp:
            source = Path(tmp) / "MainActivity.java"
            source.write_text(fixture.replace("/* METHODS */", "\n".join(methods)))
            java = os.environ.get("JAVA_BIN", "java")
            compiled = subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", tmp,
                                       str(source), str(ROOT / "OfficialOperationLease.java"),
                                       str(ROOT / "OfficialNamespacePreparation.java")],
                                      capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            result = subprocess.run([java, "-cp", tmp, "io.github.xyz1001.pdsync.android.MainActivity"],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_history_remains_live_and_failures_revoke_only_preparation(self):
        read = body(MAIN, "private void readComparison(boolean prepareNamespace, boolean allowHomePreview)")
        self.assertIn("activeFacade.inspectHome()", read)
        self.assertIn("activeFacade.inspect()", read)
        self.assertIn("invalidatePreparedOfficialNamespace()", read)
        manual = body(MAIN, "private void readHomeArchives(")
        self.assertIn("readComparison(false)", manual)
        self.assertNotIn("preparedOfficialNamespace", manual)
        prepare = body(MAIN, "private void prepareOfficialHomeNamespace(")
        self.assertIn("this.preparedOfficialNamespace.contains(key)", prepare)
        self.assertNotIn("history =", prepare)
        self.assertNotIn("inspection =", prepare)
