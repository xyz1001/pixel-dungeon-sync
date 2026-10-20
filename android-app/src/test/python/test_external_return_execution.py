"""Execute production external-installer Activity return and deferred-observation methods.

Framework scheduling and coordinator outcomes are deterministic ports. The independent
InstallComponentCoordinatorChecks suite executes production verification/persistence.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

HERE = Path(__file__).resolve().parents[1]
SRC = HERE.parent / 'main/java/io/github/xyz1001/pdsync/android'


class ExternalReturnExecution(unittest.TestCase):
    def execute(self, mutation=None):
        source = (SRC / 'MainActivity.java').read_text()
        signatures = ('private void externalInstallerReturned(int requestCode)',
                      'private void drainExternalInstallation()',
                      'private void checkExternalInstallation()')
        methods = '\n'.join(signature + ' ' + body(source, signature) for signature in signatures)
        branch = body(body(source, 'protected void onActivityResult('), 'if (i == EXTERNAL_INSTALL')
        methods += '\nprivate void externalInstallResult(int i, int i2) ' + branch
        resume = body(source, 'protected void onResume()')
        queue = 'if (this.installer.externalInstallObservation()) this.setupSession.externalCheckPending = true;'
        self.assertIn(queue, resume)
        self.assertNotIn('externalInstallerReturned(', resume)
        self.assertNotIn('externalActivityReturned(', resume)
        completion = 'owner.drainExternalInstallation();'
        self.assertIn(completion, body(source, 'private <T> void setupWork('))
        if mutation == 'old_check_only_callback':
            original = '\nprivate void externalInstallResult(int i, int i2) ' + branch
            methods = methods.replace(original, '\nprivate void externalInstallResult(int i, int i2) { checkExternalInstallation(); }')
        elif mutation == 'drop_paused_return':
            methods = methods.replace('private void externalInstallerReturned(int requestCode) {',
                                      'private void externalInstallerReturned(int requestCode) { if (!this.resumed || this.busy || this.setupSession.running) return;')
        elif mutation == 'ignore_request_identity':
            guard = 'if (requestCode != this.installer.externalActivityRequestCode()) return;'
            self.assertIn(guard, methods)
            methods = methods.replace(guard, '')
        elif mutation == 'poll_on_plain_resume':
            queue = 'this.setupSession.externalCheckPending = true;'
        elif mutation == 'drop_completion_drain':
            completion = ''
        elif mutation == 'ignore_observation_guard':
            condition = '|| (!this.installer.externalInstallPending() && !this.installer.externalInstallObservation())'
            self.assertIn(condition, methods)
            methods = methods.replace(condition, '')
        harness = (HERE / 'fixtures/external-return-host.java.txt').read_text()
        harness = harness.replace('/* PRODUCTION_METHODS */', methods)
        harness = harness.replace('/* PRODUCTION_COMPLETION_DRAIN */', completion)
        harness = harness.replace('/* PRODUCTION_RESUME_QUEUE */', queue)
        with tempfile.TemporaryDirectory(prefix='pdsync-external-return-') as tmp:
            root = Path(tmp)
            (root / 'MainActivity.java').write_text(harness)
            log = root / 'android/util/Log.java'
            log.parent.mkdir(parents=True)
            log.write_text('package android.util; public final class Log { public static int i(String tag, String text) { return 0; } }')
            java = os.environ.get('JAVA_BIN', 'java')
            compiled = subprocess.run(
                [java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-sourcepath', str(HERE.parents[2] / 'core/src/main/java'), '-encoding', 'UTF-8', '-d', str(root),
                 str(log), str(root / 'MainActivity.java')], capture_output=True, text=True, check=False)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            return subprocess.run([java, '-Duser.language=zh', '-Duser.country=CN', '-cp', str(root), 'io.github.xyz1001.pdsync.android.MainActivity'],
                                  capture_output=True, text=True, check=False)

    def test_production_external_return_and_deferred_observation(self):
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('external return execution passed: 13 scenarios', result.stdout)

    def test_detects_previous_check_only_callback(self):
        result = self.execute('old_check_only_callback')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('True result releases only the business wait', result.stderr)

    def test_detects_dropped_busy_or_paused_return(self):
        result = self.execute('drop_paused_return')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('True result releases only the business wait', result.stderr)

    def test_detects_old_result_unlocking_new_request(self):
        result = self.execute('ignore_request_identity')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('A stale old callback cannot release a newer external request', result.stderr)

    def test_detects_polling_on_plain_resume(self):
        result = self.execute('poll_on_plain_resume')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Plain resume does not poll or revoke', result.stderr)

    def test_detects_missing_setup_completion_drain(self):
        result = self.execute('drop_completion_drain')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Setup completion drains the queued external return', result.stderr)

    def test_detects_stale_observation_processing(self):
        result = self.execute('ignore_observation_guard')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Stale queued observation cannot verify or bind', result.stderr)

    def test_return_state_does_not_change_default_installer_contract(self):
        source = (SRC / 'MainActivity.java').read_text()
        launch = body(source, 'private void launchExternalInstaller()')
        self.assertIn('ExternalInstallerHandoff.installIntent(ticket)', launch)
        self.assertNotIn('Intent.createChooser(', launch)
        dispatch = body(source, 'private void dispatchInlineAccess(')
        self.assertIn('this.installer.supersedeExternalObservation();', dispatch)
        self.assertIn('this.setupSession.externalCheckPending = false;', dispatch)
        self.assertLess(dispatch.index('supersedeExternalObservation()'), dispatch.index('modeChoice.dispatch('))


if __name__ == '__main__':
    unittest.main()
