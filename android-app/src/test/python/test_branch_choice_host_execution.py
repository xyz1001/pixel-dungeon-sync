"""Execute real conflict callback guards and upload entry points in deterministic JVM ports.

No Android lifecycle/device, rendered dialog or cloud-I/O claim: production Activity methods
and HomeActionContext are compiled verbatim; platform rendering and operation scheduling are
ports. Shared modal rendering/confirmation tests cover Compose separately.
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
    'private void renderConflict()',
    'private HomeActionContext currentHomeContext(',
    'void m83x9a039aa7(',
    'void m84xf7dc0e8(',
    'AndroidSyncFacade.OperationResult m85x84f7e729(',
    'void m86xfa720d6a(',
    'public void requestUpload()',
    'public void upload()',
    'AndroidSyncFacade.OperationResult m146lambda$upload$71$iogithubxyz1001pdsyncandroidMainActivity(',
)


def declaration(signature):
    # Do not select the anonymous HOME callback's upload() method.
    signature = '\n    ' + signature if '\n    ' + signature in MAIN else signature
    start = MAIN.index(signature)
    opening = MAIN.index('{', start)
    return MAIN[start:opening] + body(MAIN, signature)


class BranchChoiceHostExecution(unittest.TestCase):
    def execute(self, mutation=None):
        methods = '\n'.join(map(declaration, METHODS))
        if mutation:
            old, new = mutation
            self.assertIn(old, methods, 'Mutation must still match production source')
            methods = methods.replace(old, new)
        fixture = (HERE / 'fixtures/branch-choice-host.java.txt').read_text()
        with tempfile.TemporaryDirectory(prefix='pdsync-branch-choice-host-') as tmp:
            source = Path(tmp) / 'MainActivity.java'
            source.write_text(fixture.replace('/* PRODUCTION_METHODS */', methods))
            java = os.environ.get('JAVA_BIN', 'java')
            compile_result = subprocess.run(
                [java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-sourcepath',
                 str(HERE.parents[2] / 'core/src/main/java'), '-encoding', 'UTF-8', '-d', tmp,
                 str(SRC / 'HomeActionContext.java'), str(source)],
                capture_output=True, text=True, check=False)
            self.assertEqual(0, compile_result.returncode, compile_result.stderr)
            return subprocess.run(
                [java, '-Duser.language=zh', '-Duser.country=CN', '-cp', tmp,
                 'io.github.xyz1001.pdsync.android.MainActivity'],
                capture_output=True, text=True, check=False)

    def test_real_conflict_guards_repeated_callbacks_gates_and_ordinary_upload(self):
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('branch choice host execution passed:', result.stdout)

    def assert_detected(self, old, new, reason):
        result = self.execute((old, new))
        self.assertNotEqual(0, result.returncode)
        self.assertIn(reason, result.stderr)

    def test_detects_stale_render_callback(self):
        self.assert_detected('root == ownerRoot && ', '', 'replaced root: stale callback dispatched')

    def test_detects_callback_after_cancel(self):
        self.assert_detected('page == SyncPage.CONFLICT && ', '', 'returned home: stale callback dispatched')

    def test_detects_paused_activity_callback(self):
        self.assert_detected('&& resumed && !isDestroyed()', '&& !isDestroyed()',
                             'paused activity: stale callback dispatched')

    def test_detects_missing_history_backend_context(self):
        self.assert_detected('&& ownerContext.accepts(currentHomeContext(sharedHomeRenderToken), true, busy)', '',
                             'replaced history: stale callback dispatched')

    def test_detects_pending_resolution_bypass(self):
        self.assert_detected('&& !observed.isRetryablePending()', '', 'pending upload: resolution dispatched')

    def test_detects_unsupported_resolution_bypass(self):
        self.assert_detected('&& restoreSupported() && !restoreNeedsAttention()', '&& !restoreNeedsAttention()',
                             'Unsupported restore reached I/O')

    def test_detects_selected_branch_mismatch(self):
        self.assert_detected('&& snapshotId.equals(selected)', '', 'overwrite must match selected observed branch')

    def test_detects_protected_local_preserve_bypass(self):
        self.assert_detected('&& !history.getCurrentProtected()', '', 'preserve already protected: preserve dispatched')

    def test_detects_ordinary_upload_guard_bypass(self):
        self.assert_detected('if (this.page == SyncPage.HOME && !canHomeUpload()) return;', '',
                             'ordinary upload preserves its existing source gate')


if __name__ == '__main__':
    unittest.main()
