"""Execute the production cold-start routing, not a duplicate policy or source-only assertion."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

HERE = Path(__file__).resolve().parents[1]
MAIN = (HERE.parent / 'main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()


class HomeInitialHistoryExecution(unittest.TestCase):
    def test_setup_completion_drains_pending_automatic_history(self):
        completion = body(MAIN, 'private <T> void setupWork(')
        self.assertIn('owner.drainAutomaticHistoryRefresh()', completion)
        self.assertLess(completion.index('done.accept(owner, result)'), completion.index('owner.drainAutomaticHistoryRefresh()'))

    def test_configured_home_starts_one_read_and_other_routes_do_not(self):
        signature = 'void m69xebc6eee5(boolean z, Object obj) throws Exception '
        method = signature + body(MAIN, 'void m69xebc6eee5(') + '\nprivate void readInitialHomeHistory() ' + body(MAIN, 'private void readInitialHomeHistory()')
        fixture = r'''
class MainActivity {
    enum SyncPage { HOME, CONNECTION, DISCOVERY, SETTINGS }
    enum OnboardingProgress { COMPLETE, PENDING }
    static class Readiness {
        boolean restorePending;
        Readiness withSystemRequest(boolean value) { return this; }
        String statusDescription() { return "not ready"; }
    }
    static class Installer { boolean hasActiveRequest() { return false; } }
    static class RetainedConnectionUi { boolean onboarding; SyncPage page; Object session; }
    static class AndroidUiFlowPolicy { static SyncPage restoredPage(SyncPage page) { return page; } }
    static class Message { String text() { return "connection error"; } }
    static final Message COULD_NOT_READ_CONNECTION_SETTINGS_PLEASE_2 = new Message();
    Readiness readinessState = new Readiness(); Installer installer = new Installer();
    Object rootGame, connection = new Object();
    SyncPage page = SyncPage.DISCOVERY;
    OnboardingProgress progress = OnboardingProgress.COMPLETE;
    boolean onboardingActive, feedbackError, ready = true, requestable = true, automatic;
    String notice = "", connectionLoadError = "";
    RetainedConnectionUi retainedConnectionUi;
    int reads;
    boolean readyForSaves() { return ready; }
    boolean canRequestSaveAccess() { return requestable; }
    boolean canAdvanceGameSelection() { return false; }
    boolean hasAutomaticHistoryRefresh() { return automatic; }
    void refreshComparison(SyncPage target, boolean recheck) { check(target == SyncPage.HOME, "read target"); reads++; }
    void refreshComparison(SyncPage target) { refreshComparison(target, true); }
    void drainAutomaticHistoryRefresh() {}
    /* METHOD */
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        MainActivity cold = new MainActivity(); cold.m69xebc6eee5(true, null);
        check(cold.page == SyncPage.HOME, "configured cold start routes home");
        check(cold.reads == 1, "configured cold HOME must start exactly one history read");
        MainActivity setup = new MainActivity(); setup.progress = OnboardingProgress.PENDING; setup.m69xebc6eee5(true, null);
        check(setup.page == SyncPage.CONNECTION && setup.reads == 0, "unfinished setup does not read");
        MainActivity missing = new MainActivity(); missing.connection = null; missing.m69xebc6eee5(true, null);
        check(missing.reads == 0, "missing connection does not read");
        MainActivity error = new MainActivity(); error.connectionLoadError = "failed"; error.m69xebc6eee5(true, null);
        check(error.reads == 0 && error.feedbackError, "connection failure uses existing error");
        MainActivity automatic = new MainActivity(); automatic.automatic = true; automatic.m69xebc6eee5(true, null);
        check(automatic.reads == 0, "automatic result is not duplicated");
        MainActivity root = new MainActivity(); root.rootGame = new Object(); root.m69xebc6eee5(true, null);
        check(root.reads == 0, "Root cold start never requests permissions or exports saves");
        MainActivity unready = new MainActivity(); unready.ready = false; unready.m69xebc6eee5(true, null);
        check(unready.reads == 0, "unverified bridge never exports saves");
        MainActivity rotated = new MainActivity(); rotated.retainedConnectionUi = new RetainedConnectionUi();
        rotated.retainedConnectionUi.page = SyncPage.HOME; rotated.m69xebc6eee5(true, null);
        check(rotated.reads == 1, "replacement HOME receives a fresh scoped observation");
        System.out.println("Initial HOME history routing checks passed");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='pdsync-home-initial-') as tmp:
            source = Path(tmp) / 'MainActivity.java'
            source.write_text(fixture.replace('/* METHOD */', method))
            java = os.environ.get('JAVA_BIN', 'java')
            compile = subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', tmp, str(source)], capture_output=True, text=True)
            self.assertEqual(0, compile.returncode, compile.stderr)
            run = subprocess.run([java, '-cp', tmp, 'MainActivity'], capture_output=True, text=True)
            self.assertEqual(0, run.returncode, run.stderr)
