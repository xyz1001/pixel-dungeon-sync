"""Step 1 source wiring. The real shared layout/actions run in JVM tests; native dialogs need device QA."""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()
BRIDGE = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidOnboardingComposition.kt').read_text()
DESKTOP = (REPO / 'desktop/src/main/kotlin/pdsync/Main.kt').read_text()
SCREEN = (REPO / 'shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedGameSelectionScreen.kt').read_text()
PICKER = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/InstalledAppPickerDialog.java').read_text()


class SharedGameSelectionAndroidContract(unittest.TestCase):
    def test_one_shared_first_step_is_routed_before_native_views(self):
        route = body(MAIN, 'public void render()')
        game = body(route, 'if (this.page == SyncPage.DISCOVERY)')
        self.assertIn('renderDiscovery();', game)
        self.assertIn('return;', game)
        self.assertLess(route.index(game), route.index('ScrollView scrollView'))
        self.assertIn('SharedGameSelectionScreen(state, actions)', BRIDGE)
        self.assertIn('SharedGameSelectionScreen(discoveryState, object : SharedGameSelectionActions', DESKTOP)
        self.assertIn('SharedAppShell(title = "选择游戏", eyebrow = "1 / 3"', SCREEN)
        self.assertEqual(1, SCREEN.count('SharedActionButton("下一步"'))
        self.assertNotIn('private void renderRootDiscovery()', MAIN)

    def test_callbacks_recheck_current_page_and_live_capabilities(self):
        host = body(MAIN, 'private void renderDiscovery()')
        self.assertIn('this.root = null;', host)
        self.assertIn('this.status = null;', host)
        self.assertIn('isCurrentGameAction(renderToken)', host)
        self.assertIn('gameSelectionState().actionEnabled(control)', host)
        self.assertIn('gameSelectionState().getNextEnabled()', host)
        guard = body(MAIN, 'private boolean isCurrentGameAction(')
        for check in ('renderToken == this.sharedGameRenderToken', 'this.resumed', '!isDestroyed()',
                      'this.page == SyncPage.DISCOVERY', '!this.busy', '!this.gameFilePickerOpen'):
            self.assertIn(check, guard)
        forward = body(MAIN, 'void m103x3c55a874()')
        self.assertIn('this.page != SyncPage.DISCOVERY', forward)
        self.assertIn('if (canRequestSaveAccess())', forward)
        self.assertIn('navigate(SyncPage.PATH)', forward)

    def test_system_picker_cancel_and_installed_filter_keep_selection(self):
        picker = body(MAIN, 'public void pickApks()')
        for capability in ('android.intent.action.OPEN_DOCUMENT', 'android.intent.extra.ALLOW_MULTIPLE',
                           'application/vnd.android.package-archive', 'this.gameFilePickerOpen = true'):
            self.assertIn(capability, picker)
        result = body(MAIN, 'protected void onActivityResult(')
        cancel = body(result, 'if (i == PICK_APK && (i2 != RESULT_OK || intent == null))')
        self.assertIn('render();', cancel)
        for reset in ('prepared = null', 'rootGame = null', 'resetAccessSelection()', 'requestPrepare('):
            self.assertNotIn(reset, cancel)
        for behavior in ('state.setQuery', 'state.setShowAll', 'state.visible(candidates, selectedPackage)',
                         '.setNegativeButton("取消", null)'):
            self.assertIn(behavior, PICKER)

    def test_typed_rows_keep_all_installation_and_root_capabilities(self):
        state = body(MAIN, 'private SharedGameSelectionState gameSelectionState()')
        host = body(MAIN, 'private void renderDiscovery()')
        for action in ('CHOOSE_INSTALLED', 'CHOOSE_FILE', 'CHOOSE_ROOT', 'SWITCH_TO_EMBEDDED',
                       'CHECK_ACCESS', 'LAUNCH_GAME', 'ACTIVATE_BRIDGE', 'INITIALIZE_GAME', 'OPEN_RECOVERY',
                       'CONTINUE_INSTALLATION', 'OPEN_SYSTEM_CONFIRMATION', 'CANCEL_INSTALLATION',
                       'CHECK_EXPIRED_REQUEST', 'CLEAN_LEGACY_REQUEST', 'OPEN_INSTALL_SETTINGS', 'RESCAN'):
            self.assertIn('SharedGameSelectionControl.' + action, state)
            self.assertIn('case ' + action + ':', host)
        for safety in ('ready.canContinueInstallation()', 'this.installer.hasConfirmation() && !obsolete',
                       'activeRequest && !obsolete', 'ready.requiresGameUninstall()', '永久删除全部本地存档和战绩',
                       'ready.identityVerified && !activeRequest && !recovery', 'canRequestSaveAccess()'):
            self.assertIn(safety, state)
        for io in ('.inspectReady(', '.exportTo(', '.requestUninstall(', '.requestInstall('):
            self.assertNotIn(io, state)


if __name__ == '__main__':
    unittest.main()
