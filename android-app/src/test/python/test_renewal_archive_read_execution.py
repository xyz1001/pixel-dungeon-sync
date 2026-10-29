"""Execute the production renewal queue and Android callbacks without Android or network I/O."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_embedded_install_contract import body

HERE = Path(__file__).resolve().parents[1]
SRC = HERE.parent / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (SRC / 'MainActivity.java').read_text()
METHODS = (
    'private boolean isForegroundHome()',
    'private boolean currentRenewalAccount(',
    'public void onAccountRefreshStarted(',
    'public void onAccountAccessChanged(',
    'private void drainRenewalArchiveRead()',
    'private boolean isCurrentHomeRead(',
    'private void readHomeArchives(',
)


def declaration(signature):
    start = MAIN.index(signature)
    return MAIN[start:MAIN.index('{', start)] + body(MAIN, signature)


class RenewalArchiveReadExecution(unittest.TestCase):
    def execute(self, mutation=None):
        methods = '\n'.join(map(declaration, METHODS))
        if mutation is not None:
            old, new = mutation
            self.assertIn(old, methods, 'Mutation must still match production source')
            methods = methods.replace(old, new)
        with tempfile.TemporaryDirectory(prefix='pdsync-renewal-read-') as tmp:
            source = Path(tmp) / 'MainActivity.java'
            fixture = (HERE / 'fixtures/renewal-archive-read-host.java.txt').read_text()
            source.write_text(fixture.replace('/* PRODUCTION_METHODS */', methods))
            java = os.environ.get('JAVA_BIN', 'java')
            compiled = subprocess.run(
                [java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-d', tmp,
                 str(source), str(SRC / 'RenewalArchiveReadState.java'),
                 str(HERE / 'java/io/github/xyz1001/pdsync/android/RenewalArchiveReadStateChecks.java')],
                capture_output=True, text=True, check=False)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            if mutation is None:
                queue = subprocess.run(
                    [java, '-cp', tmp, 'io.github.xyz1001.pdsync.android.RenewalArchiveReadStateChecks'],
                    capture_output=True, text=True, check=False)
                self.assertEqual(0, queue.returncode, queue.stderr)
                self.assertIn('Renewal archive read state checks passed', queue.stdout)
            return subprocess.run(
                [java, '-cp', tmp, 'io.github.xyz1001.pdsync.android.MainActivity'],
                capture_output=True, text=True, check=False)

    def test_production_queue_and_home_only_recovery(self):
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Renewal archive read host execution passed', result.stdout)

    def test_regression_detects_namespace_preparation(self):
        result = self.execute(('startDetachedHomeRead(account, false, false, null);', 'startDetachedHomeRead(account, true, false, null);'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('HOME performs one read-only recovery', result.stderr)

    def test_regression_detects_missing_home_gate(self):
        result = self.execute(('this.resumed && !isDestroyed() && this.page == SyncPage.HOME && !this.sharedAppreciationOnly', 'this.resumed && !isDestroyed()'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('account overlay cannot read', result.stderr)

    def test_reader_retains_readiness_and_fresh_facade_boundary(self):
        reader=body(MAIN, 'private boolean startDetachedHomeRead(SecureConnectionStore.Connection account, boolean prepareNamespace,\n                                         boolean checkReadiness, AutoSyncHistoryRefresh.Ticket automatic, boolean fullHistory)')
        self.assertIn('if (!readyForSaves()) outcome.notReady = true;', reader)
        self.assertIn('outcome.facade = SyncRuntime.localFacade(this, account);', reader)
        self.assertNotIn('outcome.facade = this.facade', reader)

    def test_regression_detects_retained_success_during_refresh(self):
        result = self.execute(('this.renewalArchiveRead.started();', '{}'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('pending refresh invalidates queued success', result.stderr)

    def test_regression_detects_unscoped_account_callback(self):
        result = self.execute((
            'if (currentRenewalAccount(source)) this.renewalArchiveRead.result(available);',
            'this.renewalArchiveRead.result(available);'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('foreign account callback dimension', result.stderr)

    def test_callback_scope_and_drain_are_read_only(self):
        callbacks = ''.join(body(MAIN, signature) for signature in METHODS[1:4])
        for forbidden in ('readComparison(', '.inspect(', 'work(', 'refreshComparison(', 'checkRootAccess('):
            self.assertNotIn(forbidden, callbacks)
        drain = body(MAIN, 'private void drainRenewalArchiveRead()')
        self.assertIn('readHomeArchives(account, selection, namespace)', drain)
        read_home = body(MAIN, 'private void readHomeArchives(')
        for forbidden in ('checkRootAccess(', 'readInstallationReadiness(', 'refreshComparison(',
                          'prepareOfficialHomeNamespace(', '.connect(', '.upload(', '.autoUpload(',
                          '.restore', '.mergeConflict(', 'replaceMirror(', 'completeOnboarding(',
                          'setOnboardingProgress(', '.arm(', '.schedule(', '.postDelayed('):
            self.assertNotIn(forbidden, drain + read_home)
        self.assertEqual(1, read_home.count('startDetachedHomeRead(account, false, false, null)'))
        reader=body(MAIN, 'private boolean startDetachedHomeRead(SecureConnectionStore.Connection account, boolean prepareNamespace,\n                                         boolean checkReadiness, AutoSyncHistoryRefresh.Ticket automatic, boolean fullHistory)')
        self.assertLess(reader.index('if (!readyForSaves())'), reader.index('owner.readHomeRemote(context)'))
        self.assertIn('readComparison(true)', body(MAIN, 'private void readComparison()'))
        read = body(MAIN, 'private void readComparison(boolean prepareNamespace, boolean allowHomePreview)')
        self.assertIn('if (prepareNamespace) prepareOfficialHomeNamespace(activeFacade)', read)
        self.assertLess(read.index('if (prepareNamespace)'), read.index('activeFacade.inspect()'))

    def test_existing_navigation_drains_only_after_home_and_retains_queue_on_rotation(self):
        navigate = body(MAIN, 'private void navigate(')
        self.assertIn('if (syncPage == SyncPage.HOME) { drainRenewalArchiveRead();', navigate)
        self.assertLess(navigate.index('this.page = syncPage'), navigate.index('drainRenewalArchiveRead()'))
        back = body(MAIN, 'public void onBack(ConnectionEntryModel.Back destination)')
        self.assertLess(back.index('closeSharedConnection()'), back.index('drainRenewalArchiveRead()'))
        self.assertIn('drainRenewalArchiveRead()', body(MAIN, 'protected void onResume()'))
        for signature in ('void m148lambda$work$0', 'void m149lambda$work$1'):
            completion = body(MAIN, signature)
            self.assertIn('drainRenewalArchiveRead()', completion)
            self.assertLess(completion.index('this.busy = false'), completion.index('drainRenewalArchiveRead()'))
        self.assertIn('renewalRead = activity.renewalArchiveRead', body(MAIN, 'RetainedConnectionUi(MainActivity activity)'))
        self.assertIn('this.renewalArchiveRead = retained.renewalRead', body(MAIN, 'private void restoreConnectionUi('))
        show = body(MAIN, 'private void showManagedAccount(')
        self.assertIn('this.renewalArchiveRead.open(source, SyncRuntime.currentSelectionToken(this), SyncRuntime.currentNamespace(this)', show)
        self.assertIn('homeAccountAccessAvailable() && this.cloudVerified && this.inspection != null && this.history != null', show)


if __name__ == '__main__':
    unittest.main()
