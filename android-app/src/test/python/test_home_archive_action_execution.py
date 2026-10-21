"""Execute Android HOME source guards and callbacks without Android or service I/O.

MainActivity methods are extracted verbatim and compiled with real HomeActionContext and
HomeArchiveReadState. Shared Kotlin archive/content policy, platform views, capabilities,
and service operations are deterministic ports. The real shared policy is tested separately
by SharedHomeArchiveAvailabilityTest/SharedHomeContentTest; this suite tests host wiring,
entry points, stale callbacks, and confirmation ownership, not device/emulator behavior.
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
    'private void renderHome()',
    'private String homeStatus(',
    'private SharedHomeContent homeContent()',
    'private boolean homeDisplayMatchesCloudTip(',
    'private SharedHomeStatusKind homeStatusKind(',
    'private SharedHomeArchiveAvailability homeArchiveAvailability()',
    'private boolean canHomeUpload()',
    'private boolean canHomeRestore()',
    'private boolean hasHomeSnapshot(',
    'private boolean canRefreshHomeHistory()',
    'private boolean isForegroundHome()',
    'private HomeActionContext currentHomeContext(',
    'private boolean isCurrentHomeAction(',
    'private boolean isCurrentHomeNavigation(',
    'private boolean isHomeArchiveLoading()',
    'public void requestUpload()',
    'public void upload()',
    'AndroidSyncFacade.OperationResult m146lambda$upload$71$iogithubxyz1001pdsyncandroidMainActivity(',
    'private boolean mayRestoreWithoutReplacementConfirmation(',
    'public void requestRestore()',
    'AndroidSyncFacade.OperationResult m141x92df934d(',
    'AndroidSyncFacade.OperationResult m142x859b98e(',
    'void m143x7dd3dfcf(',
    'private void renderConfirmation()',
    'private boolean isCurrentConfirmation(',
)


def declaration(signature):
    # A public upload() also exists inside the anonymous SharedHomeActions callback.
    # Prefer the Activity's four-space-indented declaration rather than that earlier match.
    signature = '\n    ' + signature if '\n    ' + signature in MAIN else signature
    start = MAIN.index(signature)
    opening = MAIN.index('{', start)
    return MAIN[start:opening] + body(MAIN, signature)


class HomeArchiveActionExecution(unittest.TestCase):
    def execute(self, mutation=None):
        methods = '\n'.join(map(declaration, METHODS))
        if mutation is not None:
            old, new = mutation
            self.assertIn(old, methods, 'Mutation must still match the production source')
            methods = methods.replace(old, new)
        with tempfile.TemporaryDirectory(prefix='pdsync-home-archive-host-') as tmp:
            root = Path(tmp)
            fixture = (HERE / 'fixtures/home-archive-action-host.java.txt').read_text()
            (root / 'MainActivity.java').write_text(fixture.replace('/* PRODUCTION_METHODS */', methods))
            java = os.environ.get('JAVA_BIN', 'java')
            compiled = subprocess.run(
                [java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-sourcepath', str(HERE.parents[2] / 'core/src/main/java'), '-encoding', 'UTF-8', '-d', tmp,
                 str(SRC / 'HomeActionContext.java'), str(SRC / 'HomeArchiveReadState.java'),
                 str(root / 'MainActivity.java')], capture_output=True, text=True, check=False)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            return subprocess.run([java, '-Duser.language=zh', '-Duser.country=CN', '-cp', tmp, 'io.github.xyz1001.pdsync.android.MainActivity'],
                                  capture_output=True, text=True, check=False)

    def test_real_host_guards_entry_points_and_retained_callbacks(self):
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('home archive action execution passed', result.stdout)

    def test_regression_detects_home_metadata_suppression_removed(self):
        result = self.execute(('SharedHomeContent.fromHome(', 'SharedHomeContent.from('))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('metadata identical HOME blocks existing transfer buttons', result.stderr)

    def test_regression_detects_removed_expiry_and_refresh_guard(self):
        result = self.execute(("homeAccountAccessAvailable() && ", ""))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("expired with local and cloud saves: upload enabled", result.stderr)

    def test_regression_detects_missing_source_guards(self):
        result = self.execute(('homeArchiveAvailability().getUploadSourceAvailable()', 'true'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('both empty: upload enabled', result.stderr)

    def test_regression_detects_missing_restore_source_guard(self):
        result = self.execute(('homeArchiveAvailability().getRestoreSourceAvailable()', 'true'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('both empty: restore enabled', result.stderr)

    def test_regression_detects_missing_observation_owner_guard(self):
        result = self.execute(('current && this.cloudVerified', 'this.cloudVerified'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('stale facade: upload enabled', result.stderr)

    def test_regression_detects_direct_restore_bypass(self):
        result = self.execute(('if (!canHomeRestore()) return;', ''))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('both empty: direct restore bypass', result.stderr)

    def test_regression_detects_direct_upload_bypass(self):
        result = self.execute((
            'if (this.page == SyncPage.HOME && !canHomeUpload()) return;', ''))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('both empty: direct upload bypass', result.stderr)

    def test_regression_detects_stale_manual_refresh_callback(self):
        result = self.execute(('if (isCurrentHomeAction(owner)) refreshHomeHistory();', 'refreshHomeHistory();'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('stale refresh callback', result.stderr)

    def test_regression_detects_explicit_history_rerouted_to_branch_choice(self):
        result = self.execute(('this.selected.equals(this.explicitHomeSelection)', 'false'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('confirmation must be required', result.stderr)

    def test_regression_detects_second_choice_for_explicit_history(self):
        result = self.execute(('() -> m143x7dd3dfcf(observed, target), null)',
                               '() -> m143x7dd3dfcf(observed, target), this::previewMerge)'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('explicit history must not ask for another source or merge', result.stderr)

    def test_regression_detects_stale_confirmation_bypass(self):
        result = self.execute((
            'this.inspection != inspection || !java.util.Objects.equals(this.selected, str)', 'false'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('confirmation changed selection: stale confirmation dispatched', result.stderr)


if __name__ == '__main__':
    unittest.main()
