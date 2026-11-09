"""Execute production readiness task and callback with a controllable retained owner."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

MAIN = (Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text(encoding='utf-8')


class InitialReadinessRetryExecution(unittest.TestCase):
    def test_failure_retry_and_replacement_owner(self):
        signatures = ('public void refreshReadiness()', 'Object m68x764cc8a4(boolean z)',
                      'void m69xebc6eee5(boolean z, Object obj)')
        methods = []
        for signature in signatures:
            start = MAIN.index(signature)
            methods.append(MAIN[start:MAIN.index('{', start)] + body(MAIN, signature))
        fixture = r'''
class MainActivity {
    enum SyncPage { HOME, CONNECTION, DISCOVERY, SETTINGS }
    enum OnboardingProgress { COMPLETE, PENDING }
    enum DiagnosticAction { ACCESS_CHECK }
    static class Message { String text() { return "checking"; } }
    static Message CHECKING_GAME_COMPONENTS_28 = new Message(), COULD_NOT_READ_CONNECTION_SETTINGS_PLEASE_2 = new Message();
    static class SecureConnectionStore { static class Connection {} }
    static class SyncRuntime {
        static SecureConnectionStore.Connection connection(MainActivity a) { return new SecureConnectionStore.Connection(); }
        static OnboardingProgress onboardingProgress(MainActivity a, SecureConnectionStore.Connection c) { return OnboardingProgress.COMPLETE; }
    }
    static class InstallationReadiness {
        boolean restorePending;
        static InstallationReadiness checking(boolean a, boolean b) { return new InstallationReadiness(); }
        InstallationReadiness withSystemRequest(boolean b) { return this; }
        String statusDescription() { return "status"; }
    }
    static class Installer { boolean hasActiveRequest() { return false; } }
    static class Flow { String target() { return null; } void select(String s) {} }
    static class Choice { Object pending() { return null; } }
    static class Session { Flow flow = new Flow(); Choice modeChoice = new Choice(); }
    static class Outcome { boolean applied; }
    static class Automatic { Outcome outcome; }
    static class RetainedConnectionUi { boolean onboarding; SyncPage page; Object session; }
    static class AndroidUiFlowPolicy { static SyncPage restoredPage(SyncPage page) { return page; } }
    interface Task<T> { T run() throws Exception; }
    interface Done<T> { void accept(MainActivity owner, T value) throws Exception; }
    Task<Object> task; Done<Object> done;
    <T> void setupWork(DiagnosticAction a, String m, Task<T> t, Done<T> d) {
        task = () -> t.run(); done = (owner, value) -> d.accept(owner, (T)value);
    }
    boolean initialLoad = true, busy, readinessRefreshPending, cloudVerified, draftLoaded, onboardingActive, feedbackError;
    boolean fail, root = true; int initialRoutes, subsequentRoutes;
    Object prepared, localPreview, inspection, history, rootGame = new Object();
    Installer installer = new Installer(); Session setupSession = new Session(); Automatic automaticHistoryState = new Automatic();
    InstallationReadiness readinessState = new InstallationReadiness(); SecureConnectionStore.Connection connection;
    OnboardingProgress progress = OnboardingProgress.PENDING; SyncPage page = SyncPage.DISCOVERY;
    String connectionLoadError = "", notice = ""; RetainedConnectionUi retainedConnectionUi;
    boolean isDestroyed() { return false; } void resetPathPreview() {} String selectedGamePackage() { return "game"; }
    void readInstallationReadiness() throws Exception { if (fail) throw new Exception("metadata validation failed"); }
    String safeError(Exception e) { return e.toString(); } void maybeActivateSetupComponent() {} void drainPendingSetup() {}
    boolean canRequestSaveAccess() { return root; } boolean canAdvanceGameSelection() { return false; }
    boolean readyForSaves() { return false; } boolean hasAutomaticHistoryRefresh() { return false; }
    void refreshComparison(SyncPage p, boolean b) {} void readInitialHomeHistory() { initialRoutes++; }
    void finish(MainActivity owner) throws Exception {
        Object value = null; Exception failure = null;
        try { value = task.run(); } catch (Exception e) { failure = e; }
        // setupWork applies the worker source snapshot to the accepted current owner even on error.
        owner.initialLoad = initialLoad; owner.connection = connection; owner.progress = progress;
        owner.readinessState = readinessState; owner.rootGame = rootGame; owner.connectionLoadError = connectionLoadError;
        if (failure == null) done.accept(owner, value);
    }
    /* METHODS */
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        MainActivity source = new MainActivity(); source.fail = true; source.refreshReadiness();
        MainActivity replacement = new MainActivity(); source.finish(replacement);
        check(replacement.initialLoad && replacement.page == SyncPage.DISCOVERY && replacement.initialRoutes == 0, "failure must leave initial routing pending on replacement");
        replacement.fail = false; replacement.refreshReadiness(); replacement.finish(replacement);
        check(!replacement.initialLoad && replacement.page == SyncPage.HOME && replacement.initialRoutes == 1, "successful retry routes configured unprobed Root once");
        replacement.refreshReadiness(); replacement.finish(replacement);
        check(replacement.page == SyncPage.HOME && replacement.initialRoutes == 1, "subsequent refresh must not reroute");
        MainActivity normal = new MainActivity(); normal.refreshReadiness(); normal.finish(normal);
        check(!normal.initialLoad && normal.page == SyncPage.HOME && normal.initialRoutes == 1, "normal initial success");
        System.out.println("initial readiness retry checks passed");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='pdsync-initial-retry-') as tmp:
            source = Path(tmp) / 'MainActivity.java'
            source.write_text(fixture.replace('/* METHODS */', '\n'.join(methods)), encoding='utf-8')
            java = os.environ.get('JAVA_BIN', 'java')
            compile_result = subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', tmp, str(source)], capture_output=True, text=True)
            self.assertEqual(0, compile_result.returncode, compile_result.stderr)
            run = subprocess.run([java, '-cp', tmp, 'MainActivity'], capture_output=True, text=True)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertIn('initial readiness retry checks passed', run.stdout)


if __name__ == '__main__':
    unittest.main()
