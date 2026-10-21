"""Execute real Android manual-refresh methods with deterministic read-only ports.

This covers host wiring, worker timing, callback ownership and read publication. It is
not an Android device/emulator, Root-permission, or live-account/network integration test.
"""
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
    'private boolean canRefreshHomeHistory()',
    'private void refreshHomeHistory()',
    'private boolean isForegroundHome()',
    'private boolean isHomeArchiveLoading()',
    'private void drainRenewalArchiveRead()',
    'private boolean isCurrentHomeRead(',
    'private void readHomeArchives(',
    'private void requireCurrentArchiveRead(',
    'private void readComparison(boolean prepareNamespace)', 'private void readComparison(boolean prepareNamespace, boolean allowHomePreview)',
)


def declaration(signature):
    start = MAIN.index(signature)
    return MAIN[start:MAIN.index('{', start)] + body(MAIN, signature)


class ManualHomeHistoryRefreshExecution(unittest.TestCase):
    def execute(self, mutation=None):
        methods = '\n'.join(map(declaration, METHODS))
        if mutation is not None:
            old, new = mutation
            self.assertIn(old, methods, 'Mutation must still match production source')
            methods = methods.replace(old, new)
        with tempfile.TemporaryDirectory(prefix='pdsync-manual-home-read-') as tmp:
            source = Path(tmp) / 'MainActivity.java'
            fixture = (HERE / 'fixtures/manual-home-history-refresh-host.java.txt').read_text()
            source.write_text(fixture.replace('/* PRODUCTION_METHODS */', methods))
            java = os.environ.get('JAVA_BIN', 'java')
            compiled = subprocess.run(
                [java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-d', tmp,
                 str(source), str(SRC / 'HomeArchiveReadState.java'), str(SRC / 'RenewalArchiveReadState.java')],
                capture_output=True, text=True, check=False)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            return subprocess.run([java, '-cp', tmp, 'io.github.xyz1001.pdsync.android.MainActivity'],
                                  capture_output=True, text=True, check=False)

    def test_real_manual_guards_empty_history_repeat_failure_and_stale_scopes(self):
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Manual HOME history refresh execution passed', result.stdout)

    def test_regression_detects_missing_entitlement_guard(self):
        result = self.execute(('&& readyForSaves() && homeAccountAccessAvailable();', '&& readyForSaves();'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('manual refresh guard missing: 11', result.stderr)

    def test_regression_detects_missing_loading_guard(self):
        result = self.execute(('&& !isHomeArchiveLoading()', ''))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('manual refresh guard missing: 7', result.stderr)

    def test_regression_detects_required_existing_history(self):
        result = self.execute(('return this.connection != null && this.progress',
                               'return this.history != null && this.connection != null && this.progress'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('no previous observation must remain refreshable', result.stderr)

    def test_regression_detects_namespace_preparation(self):
        result = self.execute(('startDetachedHomeRead(account, false, false, null);', 'startDetachedHomeRead(account, true, false, null);'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('manual read provisioned the remote namespace', result.stderr)

    def test_regression_detects_missing_manual_renewal_dedupe(self):
        result = self.execute(('this.renewalArchiveRead.take(account, selection, namespace);', ''))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('manual refresh must consume same-scope pending renewal', result.stderr)

    def test_stale_failure_and_completion_use_scoped_reader_acceptance(self):
        # Full cross-thread execution, including old-scope errors, lives in the detached queue fixture.
        finish = body(MAIN, 'private void finishDetachedHomeRead(')
        self.assertLess(finish.index('requireDetachedHomeRead(read)'), finish.index('reportError(outcome.error)'))
        self.assertIn('if (discarded)', finish)

    def test_manual_and_renewal_share_only_read_only_inspection_work(self):
        manual = body(MAIN, 'private void refreshHomeHistory()')
        self.assertIn('if (!canRefreshHomeHistory()) return;', manual)
        self.assertIn('readHomeArchives(account, selection, namespace)', manual)
        helper = body(MAIN, 'private void readHomeArchives(')
        self.assertEqual(1, helper.count('startDetachedHomeRead(account, false, false, null)'))
        self.assertNotIn('work(', helper)
        for forbidden in ('checkRootAccess(', 'requestRootAuthorization(', 'readInstallationReadiness(',
                          'refreshComparison(', 'prepareOfficialHomeNamespace(', '.connect(', '.upload(',
                          '.autoUpload(', '.restore', '.mergeConflict(', 'replaceMirror(', 'completeOnboarding(',
                          'setOnboardingProgress(', '.arm(', '.schedule(', '.postDelayed('):
            self.assertNotIn(forbidden, manual + helper)
        # Mutation scheduling remains independent; the separate reader never sets write-busy.
        work = body(MAIN, 'private <T> void work(DiagnosticAction action, String str, final Task<T> task, final Done<T> done, boolean preserveAutomaticHistory)')
        self.assertLess(work.index('this.busy = true'), work.index('this.f9io.execute'))
        reader = body(MAIN, 'private boolean startDetachedHomeRead(')
        self.assertIn('this.homeReader.submit', reader)
        self.assertNotIn('this.busy = true', reader)
        failure = body(MAIN, 'void m149lambda$work$1$iogithubxyz1001pdsyncandroidMainActivity(')
        self.assertIn('this.busy = false', failure)
        self.assertIn('if (!(exc instanceof StaleArchiveReadException)) reportError(exc)', failure)
        gate = body(MAIN, 'private boolean canRefreshHomeHistory()')
        for forbidden in ('this.history', 'this.inspection', 'this.cloudVerified', 'SourceAvailable', 'getLocalNodeCount'):
            self.assertNotIn(forbidden, gate)

    def test_android_adapter_and_real_callback_publish_refresh_eligibility(self):
        home = body(MAIN, 'private void renderHome()')
        callback = body(home, 'public void refreshHistory()')
        self.assertIn('if (isCurrentHomeAction(owner)) refreshHomeHistory();', callback)
        self.assertIn('canRefreshHomeHistory()', home)
        adapter = (HERE.parent / 'main/kotlin/io/github/xyz1001/pdsync/android/AndroidOnboardingComposition.kt').read_text()
        self.assertIn('canRefreshHistory: Boolean', adapter)
        self.assertIn('canRefreshHistory = canRefreshHistory', body(adapter, 'fun showHome('))


if __name__ == '__main__':
    unittest.main()
