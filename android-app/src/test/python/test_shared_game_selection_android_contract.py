"""Step 1 source wiring. The real shared layout/actions run in JVM tests; native dialogs need device QA."""
from pathlib import Path
import re
import unittest
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()
BRIDGE = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidOnboardingComposition.kt').read_text()
DESKTOP = (REPO / 'desktop/src/main/kotlin/pdsync/Main.kt').read_text()
SCREEN = (REPO / 'shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedGameSelectionScreen.kt').read_text()
PICKER = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidInstalledAppPickerComposition.kt').read_text()
MODE_DIALOG = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/GameModeChoiceDialog.java').read_text()


class SharedGameSelectionAndroidContract(unittest.TestCase):
    def test_one_shared_first_step_is_routed_before_native_views(self):
        route = body(MAIN, 'public void render()')
        game = body(route, 'if (this.page == SyncPage.DISCOVERY)')
        self.assertIn('renderDiscovery();', game)
        self.assertIn('return;', game)
        self.assertLess(route.index(game), route.index('ScrollView scrollView'))
        self.assertIn('SharedGameSelectionScreen(state, actions)', BRIDGE)
        self.assertIn('SharedGameSelectionScreen(discoveryState, object : SharedGameSelectionActions', DESKTOP)
        self.assertIn('SharedAppShell(title = SharedMessages.CHOOSE_GAME.text(), eyebrow = SharedMessages.STEP_ONE.text()', SCREEN)
        self.assertIn('if (recoveryRows.isEmpty())', SCREEN)
        self.assertEqual(2, SCREEN.count('SharedActionButton(SharedMessages.NEXT_CONNECT.text()'))
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
        self.assertIn('if (!canAdvanceGameSelection()) return', forward)
        self.assertIn('navigate(SyncPage.CONNECTION)', forward)
        self.assertNotIn('SyncPage.PATH', forward)
        self.assertNotIn('previewPath(', forward)

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
                         'SharedGamePickerDialog(', 'Lifecycle.State.RESUMED', 'onDismiss = { close() }'):
            self.assertIn(behavior, PICKER)

    def test_typed_rows_expose_choices_without_internal_system_actions(self):
        state = body(MAIN, 'private SharedGameSelectionState gameSelectionState()')
        host = body(MAIN, 'private void renderDiscovery()')
        for action in ('CHOOSE_INSTALLED', 'CHOOSE_FILE', 'OPEN_RECOVERY', 'CANCEL_INSTALLATION'):
            self.assertIn('SharedGameSelectionControl.' + action, state)
            self.assertIn('case ' + action + ':', host)
        for action in ('OPEN_INSTALL_SETTINGS', 'RESCAN', 'ACTIVATE_BRIDGE',
                       'CHECK_ACCESS'):
            self.assertNotIn('SharedGameSelectionControl.' + action, state)
        self.assertIn('canAdvanceGameSelection()', state)
        self.assertIn('!active && !recovery', state)
        for io in ('.inspectReady(', '.exportTo(', '.requestUninstall(', '.requestInstall('):
            self.assertNotIn(io, state)

    def test_inline_apk_choice_dispatches_selected_source_immediately(self):
        state = body(MAIN, 'private SharedGameSelectionState gameSelectionState()')
        for action in ('CHOOSE_ROOT', 'SWITCH_TO_EMBEDDED', 'CANCEL_ACCESS_SELECTION'):
            self.assertIn('SharedGameSelectionControl.' + action, state)
        self.assertNotIn('SharedGameSelectionControl.CONTINUE_INSTALLATION', state)
        self.assertNotIn('已选择修改 APK 模式', state)
        self.assertNotIn('Root 授权成功。', state)
        self.assertIn('new SharedGameAccessState(', state)
        self.assertIn('pending == null && canAdvanceGameSelection()', state)
        self.assertNotIn('visibleNotice()', state)
        self.assertIn('offerGameModeChoice(GameModeChoice.Source.installed(packageName))',
                      body(MAIN, 'private void selectInstalledForSetup('))
        self.assertIn('offerGameModeChoice(GameModeChoice.Source.files(', body(MAIN, 'protected void onActivityResult('))
        apk_card = body(MAIN, 'private void chooseInlineApkMode()')
        self.assertIn('accessMode = GameModeChoice.Mode.APK', apk_card)
        self.assertIn('dispatchInlineAccess(request, GameModeChoice.Mode.APK)', apk_card)
        install = body(MAIN, 'private void beginInlineInstallation()')
        self.assertIn('dispatchInlineAccess(request, GameModeChoice.Mode.APK)', install)
        dispatch = body(MAIN, 'private void dispatchInlineAccess(')
        for guard in ('this.setupSession.owner != this', '!this.resumed', 'this.page != SyncPage.DISCOVERY',
                      'this.installer.hasActiveRequest()', 'restoreNeedsAttention()', 'this.setupSession.modeChoice.awaiting(request)'):
            self.assertIn(guard, dispatch)
        self.assertIn('resolveRootSetupSource(selected)', dispatch)
        self.assertIn('chooseApkSetupSource(selected)', dispatch)
        self.assertNotIn('GameModeChoiceDialog.show', MAIN)
        back = body(MAIN, 'public void onBackPressed()')
        self.assertIn('modeChoice.awaiting(this.setupSession.modeChoice.pending())', back)
        self.assertIn('cancelInlineAccessSelection()', back)
        cancel = body(MAIN, 'private void cancelInlineAccessSelection()')
        self.assertIn('modeChoice.cancel(request)', cancel)
        for mutation in ('resetAccessSelection(', 'flow.select(', 'requestPrepare(', 'rootGame = null'):
            self.assertNotIn(mutation, cancel)

    def test_verified_apk_has_no_redundant_success_or_other_installer_entry(self):
        state = body(MAIN, 'private SharedGameSelectionState gameSelectionState()')
        self.assertNotIn('SharedGameSelectionControl.USE_OTHER_INSTALLER', state)
        for source in ('visibleNotice()', 'this.installer.summary()', 'this.setupOutcome', 'statusDescription()'):
            self.assertNotIn(source, state)
        self.assertIn('canAdvanceGameSelection(), "", false,', state)
        self.assertIn('state.access == null && state.notice.isNotBlank()', SCREEN)
        self.assertIn('state.access == null && state.warning.isNotBlank()', SCREEN)
        for action in ('CHECK_EXTERNAL_INSTALL', 'CANCEL_INSTALLATION', 'OPEN_SYSTEM_CONFIRMATION', 'CHECK_EXPIRED_REQUEST'):
            self.assertIn('SharedGameSelectionControl.' + action, state)


if __name__ == '__main__':
    unittest.main()
