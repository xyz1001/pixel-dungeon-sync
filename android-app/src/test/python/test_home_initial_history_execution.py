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
    def test_picker_return_consumes_pending_without_io(self):
        resume = body(MAIN, 'protected void onResume()')
        picker = body(resume, 'if (diagnosticPickerReturn || diagnosticExport.busy())')
        self.assertIn('this.homeEntryReadPending = false', picker)
        self.assertIn('return;', picker)
        self.assertNotIn('drainHomeEntryRead()', picker)
        self.assertIn('if (this.gameFilePickerOpen) { this.homeEntryReadPending = false; return; }', resume)

    def test_setup_completion_drains_pending_automatic_history(self):
        completion = body(MAIN, 'private <T> void setupWork(')
        self.assertIn('owner.drainAutomaticHistoryRefresh()', completion)
        self.assertLess(completion.index('done.accept(owner, result)'), completion.index('owner.drainAutomaticHistoryRefresh()'))

    def test_configured_home_starts_one_read_and_other_routes_do_not(self):
        signature = 'void m69xebc6eee5(boolean z, Object obj) throws Exception '
        method = signature + body(MAIN, 'void m69xebc6eee5(') + '\nprivate void readInitialHomeHistory() ' + body(MAIN, 'private void readInitialHomeHistory()') + '\nprivate void drainHomeEntryRead() ' + body(MAIN, 'private void drainHomeEntryRead()')
        for sig in ('public void onBack(ConnectionEntryModel.Back destination)', 'private void drainRenewalArchiveRead()', 'private boolean readHomeArchives('):
            start = MAIN.index(sig)
            method += '\n' + MAIN[start:MAIN.index('{', start)] + body(MAIN, sig)
        fixture = r'''
package io.github.xyz1001.pdsync.android;
class MainActivity {
    enum SyncPage { HOME, CONNECTION, DISCOVERY, SETTINGS }
    enum OnboardingProgress { COMPLETE, PENDING }
    static class Readiness {
        boolean restorePending;
        Readiness withSystemRequest(boolean value) { return this; }
        String statusDescription() { return "not ready"; }
    }
    static class Installer { boolean hasActiveRequest() { return false; } }
    static class SetupSession { boolean running; }
    static class Binding { boolean matches(Binding other) { return true; } }
    static class DetachedRead { boolean checkReadiness, fullHistory; Binding binding = new Binding(); }
    static class SecureConnectionStore { static class Connection {} }
    static class ConnectionEntryModel { enum Back { STAY, CHOICE, HOME } }
    static class SyncRuntime { static String currentSelectionToken(MainActivity a) { return "game"; } static String currentNamespace(MainActivity a) { return "ns"; } }
    static class RetainedConnectionUi { boolean onboarding; SyncPage page; Object session; }
    static class AndroidUiFlowPolicy { static SyncPage restoredPage(SyncPage page) { return page; } }
    static class Message { String text() { return "connection error"; } }
    static final Message COULD_NOT_READ_CONNECTION_SETTINGS_PLEASE_2 = new Message();
    Readiness readinessState = new Readiness(); Installer installer = new Installer();
    Object rootGame; SecureConnectionStore.Connection connection = new SecureConnectionStore.Connection();
    SyncPage page = SyncPage.DISCOVERY;
    OnboardingProgress progress = OnboardingProgress.COMPLETE;
    boolean onboardingActive, feedbackError, ready = true, requestable = true, automatic;
    boolean homeEntryReadPending = true, resumed = true, initialLoad, busy, sharedAppreciationOnly, acceptRead = true;
    DetachedRead detachedHomeRead; SetupSession setupSession = new SetupSession(); RenewalArchiveReadState renewalArchiveRead = new RenewalArchiveReadState();
    Binding officialOperationBinding(Object account) { return new Binding(); }
    String notice = "", connectionLoadError = "";
    RetainedConnectionUi retainedConnectionUi;
    int reads;
    boolean readyForSaves() { return ready; }
    boolean canRequestSaveAccess() { return ready || rootGame != null && requestable; }
    boolean isDestroyed() { return false; }
    boolean isForegroundHome() { return resumed && page == SyncPage.HOME && !sharedAppreciationOnly; }
    boolean isCurrentHomeRead(Object a, String s, String n) { return isForegroundHome() && connection == a; }
    boolean homeAccountAccessAvailable() { return true; }
    void closeSharedConnection() { sharedAppreciationOnly = false; }
    void render() {} void navigate(SyncPage p) { page = p; }
    boolean startDetachedHomeRead(Object account, boolean prepare, boolean check, Object automatic, boolean full) {
        if (!acceptRead || !isForegroundHome()) return false;
        check(!prepare && (!full || check), "read must be read-only and full reads checked"); reads++; return true;
    }
    boolean startDetachedHomeRead(Object account, boolean prepare, boolean check, Object automatic) { return startDetachedHomeRead(account, prepare, check, automatic, false); }
    boolean canAdvanceGameSelection() { return false; }
    boolean hasAutomaticHistoryRefresh() { return automatic; }
    void refreshComparison(SyncPage target, boolean recheck) { check(target == SyncPage.HOME, "read target"); reads++; }
    void refreshComparison(SyncPage target) { refreshComparison(target, true); }
    void drainAutomaticHistoryRefresh() {}
    /* METHOD */
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    boolean renewalPending() throws Exception {
        java.lang.reflect.Field field = RenewalArchiveReadState.class.getDeclaredField("pending"); field.setAccessible(true); return field.getBoolean(renewalArchiveRead);
    }
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
        check(automatic.reads == 1, "configured entry starts one checked read");
        MainActivity root = new MainActivity(); root.rootGame = new Object(); root.ready = false; root.m69xebc6eee5(true, null);
        check(root.reads == 1, "configured Root cold HOME starts one checked read");
        root.drainHomeEntryRead(); check(root.reads == 1, "entry is consumed once");
        MainActivity busy = new MainActivity(); busy.rootGame = new Object(); busy.ready = false; busy.page = SyncPage.HOME; busy.busy = true;
        busy.drainHomeEntryRead(); check(busy.reads == 0 && busy.homeEntryReadPending, "busy keeps entry pending");
        busy.busy = false; busy.drainHomeEntryRead(); busy.drainHomeEntryRead(); check(busy.reads == 1, "busy completion drains once");
        MainActivity away = new MainActivity(); away.page = SyncPage.SETTINGS; away.drainHomeEntryRead();
        check(away.reads == 0, "non-HOME entry never starts a read");
        MainActivity unready = new MainActivity(); unready.ready = false; unready.m69xebc6eee5(true, null);
        check(unready.reads == 0, "unverified bridge never exports saves");
        MainActivity rotated = new MainActivity(); rotated.retainedConnectionUi = new RetainedConnectionUi();
        rotated.retainedConnectionUi.page = SyncPage.HOME; rotated.m69xebc6eee5(true, null);
        check(rotated.reads == 1, "replacement HOME receives a fresh scoped observation");
        MainActivity overlay = new MainActivity(); overlay.page = SyncPage.HOME; overlay.sharedAppreciationOnly = true;
        overlay.renewalArchiveRead.open(overlay.connection, "game", "ns", false); overlay.renewalArchiveRead.started(); overlay.renewalArchiveRead.result(true);
        overlay.drainHomeEntryRead(); overlay.drainRenewalArchiveRead();
        check(overlay.reads == 0 && overlay.homeEntryReadPending && overlay.renewalPending(), "overlay consumed pending work");
        overlay.onBack(ConnectionEntryModel.Back.HOME);
        check(overlay.reads == 1 && !overlay.homeEntryReadPending && !overlay.renewalPending(), "overlay return did not merge pending reads");
        MainActivity rejected = new MainActivity(); rejected.page = SyncPage.HOME; rejected.acceptRead = false;
        rejected.renewalArchiveRead.open(rejected.connection, "game", "ns", false); rejected.renewalArchiveRead.started(); rejected.renewalArchiveRead.result(true);
        rejected.drainHomeEntryRead();
        check(rejected.reads == 0 && rejected.homeEntryReadPending && rejected.renewalPending(), "rejected reader consumed pending work");
        rejected.acceptRead = true; rejected.drainHomeEntryRead();
        check(rejected.reads == 1 && !rejected.homeEntryReadPending && !rejected.renewalPending(), "rejected entry could not retry");
        MainActivity renewal = new MainActivity(); renewal.page = SyncPage.HOME; renewal.homeEntryReadPending = false; renewal.acceptRead = false;
        renewal.renewalArchiveRead.open(renewal.connection, "game", "ns", false); renewal.renewalArchiveRead.started(); renewal.renewalArchiveRead.result(true);
        renewal.drainRenewalArchiveRead(); check(renewal.reads == 0 && renewal.renewalPending(), "rejected renewal was lost");
        renewal.acceptRead = true; renewal.drainRenewalArchiveRead();
        check(renewal.reads == 1 && !renewal.renewalPending(), "rejected renewal could not retry");
        System.out.println("Initial HOME history routing checks passed");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='pdsync-home-initial-') as tmp:
            source = Path(tmp) / 'MainActivity.java'
            source.write_text(fixture.replace('/* METHOD */', method))
            java = os.environ.get('JAVA_BIN', 'java')
            compile = subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', tmp, str(source), str(HERE.parent / 'main/java/io/github/xyz1001/pdsync/android/RenewalArchiveReadState.java')], capture_output=True, text=True)
            self.assertEqual(0, compile.returncode, compile.stderr)
            run = subprocess.run([java, '-cp', tmp, 'io.github.xyz1001.pdsync.android.MainActivity'], capture_output=True, text=True)
            self.assertEqual(0, run.returncode, run.stderr)
