"""Android settings source seams plus executed production host policy in SettingsHostChecks.

No device, lifecycle, native dialog/picker, real account or Android pixel claims are made here.
"""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()
ADAPTER = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidOnboardingComposition.kt').read_text()


class SharedSettingsAndroidContract(unittest.TestCase):
    def test_review_setup_label_does_not_embed_an_obsolete_step_count(self):
        host = body(MAIN, 'private void renderSettings()')
        self.assertIn('SharedSettingsKey.REVIEW_SETUP, SETUP_GUIDE_202.text()', host)
        self.assertNotIn('三步引导', host)
        self.assertNotIn('四步引导', host)
        self.assertIn('m132x2f4e91b2()', body(host, 'public void perform('))

    def test_settings_uses_shared_page_before_native_hierarchy(self):
        render = body(MAIN, 'public void render()')
        settings = body(render, 'if (this.page == SyncPage.SETTINGS)')
        self.assertIn('renderSettings()', settings)
        self.assertIn('return;', settings)
        self.assertLess(render.index(settings), render.index('ScrollView scrollView'))
        host = body(MAIN, 'private void renderSettings()')
        self.assertIn('new SharedSettingsState(selectedGameIdentity(), rows, isForegroundSettings()', host)
        self.assertIn('AndroidOnboardingComposition.showSettings(this, state, new SharedSettingsActions()', host)
        for old in ('heading(', 'button(', 'foot(', 'new Switch(', 'this.body.addView('):
            self.assertNotIn(old, host)
        for field in ('root', 'status', 'body', 'footer', 'scroll'):
            self.assertIn('this.' + field + ' = null', host)
        adapter = body(ADAPTER, 'fun showSettings(')
        self.assertIn('SharedSettingsScreen(state, actions)', adapter)
        self.assertIn('AndroidAppViewport {', adapter)
        for operation in ('SyncRuntime.', 'File(', 'read(', 'inspect(', 'getPackageManager', 'entitlementCache', 'showConfirmation'):
            self.assertNotIn(operation, adapter)

    def test_real_values_and_available_actions_are_projected_without_fixtures(self):
        host = body(MAIN, 'private void renderSettings()')
        for key in ('GAME', 'CLOUD_CONNECTION', 'ACCOUNT_APPRECIATION', 'SAVE_PATH', 'AUTOMATIC_UPLOAD',
                    'RECHECK_SAVES', 'REVIEW_SETUP', 'SYNC_NOTIFICATIONS', 'EXPORT_LOGS', 'MAINTENANCE'):
            self.assertEqual(host.count('new SharedSettingsRow(SharedSettingsKey.' + key + ','), 1)
        for value in ('selectedGameLabel()', 'settingsAccessStatus()', 'settingsConnectionValue()', 'savePathLabel()',
                      'automaticObservationSupported()', 'SyncRuntime.isAutoUploadPaused(this)',
                      'officialAppreciationLabel(this.connection.username)', 'isOfficialAccount(this.connection)',
                      'this.inspection.isRetryablePending()',
                      'Build.VERSION.SDK_INT >= 33', 'diagnosticExport.busy()'):
            self.assertIn(value, host)
        self.assertIn('savePathLabel(), null, false', host)
        self.assertIn('automaticSupported ? automaticPaused ? ENABLE_195.text() : PAUSE_196.text() : null', host)
        self.assertIn('notificationsAllowed ? null : ALLOW_208.text()', host)
        for forbidden in ('example.invalid', '模拟', 'sample', '.inspectReady(', '.exportTo(', 'probeRootAuthorization('):
            self.assertNotIn(forbidden, host)
        status = body(MAIN, 'private String settingsAccessStatus()')
        self.assertIn('readyForSaves()', status)
        self.assertIn('Root', status)
        self.assertIn('BUILT_IN_SAVE_BRIDGE_219.text()', status)

    def test_retained_actions_recheck_current_page_account_backend_and_capabilities(self):
        foreground = body(MAIN, 'private boolean isForegroundSettings()')
        for guard in ('this.resumed', '!isDestroyed()', 'this.page == SyncPage.SETTINGS', '!this.sharedAppreciationOnly'):
            self.assertIn(guard, foreground)
        context = body(MAIN, 'private SettingsActionContext currentSettingsContext(')
        for identity in ('this.connection, this.facade, this.inspection', 'SyncRuntime.currentSelectionToken(this)',
                         'SyncRuntime.currentNamespace(this)'):
            self.assertIn(identity, context)
        host = body(MAIN, 'private void renderSettings()')
        perform = body(host, 'public void perform(')
        self.assertLess(perform.index('if (!isCurrentSettingsAction(owner)) return'), perform.index('switch (key)'))
        self.assertIn('SettingsCapabilities current = settingsCapabilities()', perform)
        for route in ('m129xcee01eef()', 'm131xb9d46b71()', 'showManagedAccount(MainActivity.this.connection)',
                      'm128x5965f8ae(null, SyncRuntime.isAutoUploadPaused(MainActivity.this))', 'm130x445a4530()',
                      'm132x2f4e91b2()', 'm133xa4c8b7f3()', 'chooseDiagnosticDirectory()',
                      'showSettingsMaintenance(owner)'):
            self.assertIn(route, perform)
        self.assertIn('isCurrentSettingsAction(owner)', body(host, 'public void back()'))
        for signature in ('protected void onPause()', 'protected void onDestroy()', 'private void navigate('):
            self.assertIn('++this.sharedSettingsRenderToken', body(MAIN, signature))
            self.assertIn('dismissSettingsDialog()', body(MAIN, signature))

    def test_single_maintenance_entry_preserves_original_confirmations(self):
        host = body(MAIN, 'private void renderSettings()')
        self.assertNotIn('检查未完成恢复', host)
        menu = body(MAIN, 'private void showSettingsMaintenance(')
        for capability, route in (('canRecoverRestore', 'm135x8fbd0475()'), ('canDiscardRestore', 'm112xc8b22aac()'),
                                  ('canRecoverBackup', 'm116x9e9ac3b0()'), ('canArchiveUpload', 'm120x74835cb4()'),
                                  ('canForgetConnection', 'm124xed71190d()')):
            self.assertIn('if (capabilities.' + capability + ')', menu)
            self.assertIn('if (settingsCapabilities().' + capability + ') ' + route, menu)
        self.assertIn('this.settingsDialog != dialog || !isCurrentSettingsContext(owner)', menu)
        for signature in ('void m112xc8b22aac()', 'void m116x9e9ac3b0()', 'void m120x74835cb4()', 'void m124xed71190d()'):
            expected = 'showDestructiveConfirmation(' if signature == 'void m116x9e9ac3b0()' else 'showConfirmation('
            self.assertIn(expected, body(MAIN, signature))
        self.assertIn('guardSettingsConfirmation(settingsOwner, settingsTicket, runnable)', body(MAIN, 'private void showConfirmation(String str, String str2, String str3, Runnable runnable, Runnable runnable2, boolean destructive)'))
        guard = body(MAIN, 'private Runnable guardSettingsConfirmation(')
        for value in ('!this.resumed', 'isDestroyed()', 'this.busy', 'this.page != SyncPage.CONFIRM',
                      'this.confirmationReturn != SyncPage.SETTINGS', 'ticket != this.settingsConfirmationToken',
                      'owner.sameTarget(currentSettingsContext(this.sharedSettingsRenderToken))'):
            self.assertIn(value, guard)
        self.assertLess(guard.index('++this.settingsConfirmationToken'), guard.index('action.run()'))
        self.assertIn('++this.settingsConfirmationToken', body(MAIN, 'public void cancelConfirmation()'))

    def test_package_cloud_identity_has_no_manual_pc_association_entry_or_dialog(self):
        host = body(MAIN, 'private void renderSettings()')
        for removed in ('SharedSettingsKey.LINK_PROFILE', 'case LINK_PROFILE:', 'PC 档案关联',
                        'editNamespace()', '关联同一游戏的 PC 档案', '确认云端档案关联',
                        'SyncRuntime.setNamespace(', 'm47x6b6b32d0(', 'm44xafcc00d('):
            self.assertNotIn(removed, MAIN)
        self.assertNotIn('this.connection.profile', host)
        self.assertNotIn('this.selectedNamespace', host)
        runtime = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/SyncRuntime.java').read_text()
        self.assertNotIn('static void setNamespace(', runtime)
        self.assertNotIn('remove("namespace."', runtime)
        self.assertNotIn('putString("namespace."', runtime)
        self.assertNotIn('getString("namespace."', runtime)

    def test_diagnostics_are_global_export_with_existing_saf_boundary(self):
        host = body(MAIN, 'private void renderSettings()')
        self.assertNotIn('this.detailsExpanded', host)
        self.assertNotIn('safeText(this.details)', host)
        self.assertNotIn('showSettingsDiagnostics', MAIN)
        export = body(MAIN, 'private void chooseDiagnosticDirectory()')
        self.assertIn('diagnosticExport.chooseDirectory(this)', export)
        for forbidden in ('SyncRuntime.', 'Root', 'Network', 'sync(', 'work('):
            self.assertNotIn(forbidden, export)


if __name__ == '__main__':
    unittest.main()
