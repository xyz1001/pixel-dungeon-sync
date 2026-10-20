"""Compile and execute production MainActivity method bodies with deterministic Android/service ports.

This is host integration, not device/emulator evidence. The entry point, lifecycle gates,
installed/file source dispatch, bridge decision, prepare continuation, pending-flow drain,
cancellation, uninstall gate and IntentSender launch bodies come
from MainActivity verbatim; only Android IO/crypto and background scheduling are simulated.
"""
import os
import re
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

HERE = Path(__file__).resolve().parents[1]
SRC = HERE.parent / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (SRC / 'MainActivity.java').read_text()
METHODS = [
    'private String visibleNotice()',
    'private void renderDiscovery()',
    'private GameModeChoice.Request<Uri> inlineAccessRequest()',
    'private void chooseInlineApkMode()',
    'private void dispatchInlineAccess(',
    'private void chooseApkSetupSource(',
    'private String selectedGamePackage()',
    'private void selectSetupSource(',
    'private void beginModification()',
    'private void cancelInlineAccessSelection()',
    'private void drainPendingSetup()',
    'private void externalInstallerReturned(int requestCode)',
    'private void drainExternalInstallation()',
    'private void checkExternalInstallation()',
    'private void requestPrepare(',
    'private void prepare(',
    'public void continueInstallation()',
    'InstallNext m32xc4841ad1()',
    'void m39x9de049ed(',
    'Object m33x39fe4112(',
    'void m35x24f28d94(',
    'Object m36x9a6cb3d5(',
    'void m38x286623ac(',
    'private void retryInstallConfirmation()',
    'public void openInstallConfirmation()',
    'private void openInstallConfirmation(boolean explicitRetry)',
    'private void offerExternalInstaller()',
    'private void launchExternalInstaller()',
    'private boolean isCurrentGameAction(',
]

def declaration(signature):
    start = MAIN.index(signature)
    opening = MAIN.index('{', start)
    return MAIN[start:opening] + body(MAIN, signature)

class SelectedApkActionExecution(unittest.TestCase):
    def execute(self, inert_apk_choice=False, generic_preparation_notice=False, missing_launch_options=False,
                old_disabled_only_route=False, inert_installed_modification=False, reinject_valid_bridge=False):
        with tempfile.TemporaryDirectory(prefix='pdsync-action-host-') as tmp:
            root = Path(tmp)
            harness = (HERE / 'fixtures/selected-apk-action-host.java.txt').read_text()
            methods = '\n'.join(map(declaration, METHODS))
            # Execute the real external-result branch, not a fixture-only cancellation shortcut.
            methods += '\nprivate void externalInstallResult(int i, int i2) ' + body(
                body(MAIN, 'protected void onActivityResult('), 'if (i == EXTERNAL_INSTALL')
            if inert_apk_choice:
                original = declaration('private void chooseInlineApkMode()')
                methods = methods.replace(original, original.replace(
                    'dispatchInlineAccess(request, GameModeChoice.Mode.APK);', 'render();'))
            if inert_installed_modification:
                original = declaration('private void beginModification()')
                methods = methods.replace(original, 'private void beginModification() { bridgeChecks++; }')
            if reinject_valid_bridge:
                original = declaration('private void beginModification()')
                modified = original.replace(
                    'binding != null && !"PURE_HANDSHAKE_REQUIRED".equals(owner.bridgeAccessCode)', 'false')
                self.assertNotEqual(original, modified)
                methods = methods.replace(original, modified)
            if missing_launch_options:
                methods = methods.replace("RuntimePlatformCompat.systemConfirmationLaunchOptions()", "null")
            if generic_preparation_notice:
                methods = methods.replace('PreparationDiagnostics.isSafeUserMessage(this.notice)', 'false')
            if old_disabled_only_route:
                pattern = r'(installNext\.externalInstaller\s*=\s*)([^;]+);'
                match = re.search(pattern, methods)
                self.assertIsNotNone(match)
                self.assertIn('embeddedBridge()', match.group(2))
                self.assertIn('helperOnlyUpgrade', match.group(2))
                self.assertIn('gameApks.size()', match.group(2))
                methods = re.sub(pattern, r'\1SystemConfirmationToken.installerPositivelyDisabled(this);', methods, count=1)
            diagnostics = (SRC / 'prepare/PreparationDiagnostics.java').read_text()
            def diagnostic_declaration(signature):
                start = diagnostics.index(signature)
                opening = diagnostics.index('{', start)
                return diagnostics[start:opening] + body(diagnostics, signature)
            diagnostic_methods = '\n'.join(diagnostic_declaration(s) for s in (
                'public enum Stage', 'public static final class Failure',
                'private static String safeUserMessage(', 'public static boolean isSafeUserMessage('))
            harness = harness.replace('/* DIAGNOSTIC_METHODS */', diagnostic_methods)
            harness = harness.replace('/* PRODUCTION_METHODS */', methods)
            (root / 'MainActivity.java').write_text(harness)
            java = os.environ.get('JAVA_BIN', 'java')
            sources = [str(SRC / (name + '.java')) for name in
                       ('GameModeChoice', 'GameModeChoiceDialog', 'GameSetupFlow', 'InstallUiCopy')]
            sources += [str(p) for p in (HERE / 'fixtures/game-mode-dialog-stubs').rglob('*.java')]
            clock = root / 'android/os/SystemClock.java'
            clock.parent.mkdir(parents=True, exist_ok=True)
            clock.write_text('package android.os; public final class SystemClock { public static long elapsedRealtime() { return 10000L; } }')
            sources.append(str(clock))
            log = root / 'android/util/Log.java'
            log.parent.mkdir(parents=True, exist_ok=True)
            log.write_text('package android.util; public final class Log { public static int i(String tag, String text) { return 0; } }')
            sources.append(str(log))
            sources.append(str(root / 'MainActivity.java'))
            compiled = subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-sourcepath', str(HERE.parents[2] / 'core/src/main/java'), '-encoding', 'UTF-8',
                                       '-d', str(root), *sources], check=False, capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            result = subprocess.run([java, '-Duser.language=zh', '-Duser.country=CN', '-cp', str(root), 'io.github.xyz1001.pdsync.android.MainActivity'],
                                    check=False, capture_output=True, text=True)
            return result

    def test_real_activity_action_reaches_installer_and_obeys_lifecycle(self):
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('selected APK action integration passed', result.stdout)
        self.assertIn('installed APK action integration passed', result.stdout)

    def test_regression_detects_inert_installed_modification(self):
        result = self.execute(inert_installed_modification=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Installed APK card reaches the production bridge decision once', result.stderr)

    def test_regression_detects_unnecessary_installed_bridge_reinjection(self):
        result = self.execute(reinject_valid_bridge=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('An existing installed bridge is authenticated for the selected package', result.stderr)

    def test_regression_detects_missing_foreground_launch_options(self):
        result = self.execute(missing_launch_options=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Foreground options passed to platform launch', result.stderr)

    def test_regression_detects_previous_generic_preparation_notice(self):
        result = self.execute(generic_preparation_notice=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Preserve fixed safe preparation message', result.stderr)

    def test_regression_detects_previous_inert_card(self):
        result = self.execute(inert_apk_choice=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('APK card must process exact selected base/splits immediately', result.stderr)

    def test_regression_detects_old_disabled_only_external_route(self):
        result = self.execute(old_disabled_only_route=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Single APK uses default ACTION_VIEW regardless of system installer enablement', result.stderr)

    def test_ordinary_handoff_does_not_wrap_default_installer_in_chooser(self):
        launch = declaration('private void launchExternalInstaller()')
        self.assertIn('ExternalInstallerHandoff.installIntent(ticket)', launch)
        self.assertNotIn('ExternalInstallerHandoff.chooser(', launch)
        self.assertNotIn('Intent.createChooser(', launch)

if __name__ == '__main__':
    unittest.main()
