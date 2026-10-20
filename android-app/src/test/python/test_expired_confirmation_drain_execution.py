"""Execute production Activity polling body; ports do not emulate Android installation."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

HERE = Path(__file__).resolve().parents[1]
SRC = HERE.parent / 'main/java/io/github/xyz1001/pdsync/android'


class ExpiredConfirmationDrainExecution(unittest.TestCase):
    def execute(self, mutation=None):
        source = (SRC / 'MainActivity.java').read_text()
        signatures = ('private void drainPendingSetup()', 'public void openInstallConfirmation()',
                      'private void openInstallConfirmation(boolean explicitRetry)', 'private void retryInstallConfirmation()')
        method = '\n'.join(signature + ' ' + body(source, signature) for signature in signatures)
        result_branch = body(body(source, 'protected void onActivityResult('), 'if (i == SYSTEM_INSTALL)')
        method += '\nprivate void systemInstallResult(int i) ' + result_branch
        if mutation == 'old_boundary_guard':
            method = method.replace('if (this.installer.confirmationUnavailable()) {',
                                    'if (this.installer.confirmationUnavailable() && !this.installer.installationResultBoundary()) {')
        elif mutation == 'allow_last_poll_cleanup':
            method = method.replace('&& !this.installer.confirmationUnavailable()', '')
        elif mutation == 'keep_rescheduling':
            start = method.index('} else if (owner.installer.confirmationUnavailable()) {')
            end = method.index('} else if (owner.pendingSystemChecks < 12)', start)
            method = method[:start] + method[end:]
        elif mutation == 'missing_recovery_render':
            start = method.index('if (this.installer.confirmationUnavailable()) {')
            stop = method.index('return;', start)
            method = method[:start] + method[start:stop].replace('render();', '') + method[stop:]
        elif mutation == 'missing_durable_replay_gate':
            self.assertIn('this.installer.automaticConfirmationAttempted()', method)
            method = method.replace('this.installer.automaticConfirmationAttempted()', 'false')
        elif mutation == 'missing_external_wait_gate':
            self.assertIn('this.installer.externalInstallPending()', method)
            method = method.replace('this.installer.externalInstallPending()', 'false')
        harness = (HERE / 'fixtures/expired-confirmation-drain-host.java.txt').read_text()
        with tempfile.TemporaryDirectory(prefix='pdsync-expired-drain-') as tmp:
            root = Path(tmp)
            (root / 'MainActivity.java').write_text(harness.replace('/* PRODUCTION_METHODS */', method))
            clock = root / 'android/os/SystemClock.java'
            clock.parent.mkdir(parents=True)
            clock.write_text('package android.os; public final class SystemClock { public static long elapsedRealtime() { return 10000L; } }')
            java = os.environ.get('JAVA_BIN', 'java')
            compile_result = subprocess.run(
                [java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-sourcepath', str(HERE.parents[2] / 'core/src/main/java'), '-encoding', 'UTF-8', '-d', str(root),
                 str(SRC / 'SelectedApkInstallPolicy.java'), str(SRC / 'GameSetupFlow.java'),
                 str(clock), str(root / 'MainActivity.java')],
                check=False, capture_output=True, text=True)
            self.assertEqual(0, compile_result.returncode, compile_result.stderr)
            return subprocess.run([java, '-Duser.language=zh', '-Duser.country=CN', '-cp', str(root), 'io.github.xyz1001.pdsync.android.MainActivity'],
                                  check=False, capture_output=True, text=True)

    def test_real_drain_preserves_unavailable_and_renders_recovery(self):
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('expired confirmation drain passed: 10 scenarios', result.stdout)

    def test_detects_previous_boundary_only_guard(self):
        result = self.execute('old_boundary_guard')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('known unavailable live request does no repeated work', result.stderr)

    def test_detects_cleanup_after_new_failure_at_last_poll(self):
        result = self.execute('allow_last_poll_cleanup')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('newly unavailable at last poll: checkpoint retained', result.stderr)

    def test_detects_reschedule_after_new_failure(self):
        result = self.execute('keep_rescheduling')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('newly unavailable before budget exhausted: unavailable request does not reschedule', result.stderr)

    def test_detects_missing_render_on_receiver_failure(self):
        result = self.execute('missing_recovery_render')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('unavailable state is rendered immediately', result.stderr)

    def test_detects_immediate_return_automatic_replay_after_recreation(self):
        result = self.execute('missing_durable_replay_gate')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('immediate returned request never automatically relaunches after recreation', result.stderr)

    def test_detects_external_wait_entering_legacy_settle_loop(self):
        result = self.execute('missing_external_wait_gate')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('external installer never enters ordinary result polling or cleanup', result.stderr)


if __name__ == '__main__':
    unittest.main()
