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
        self.assertIn('safeDrawingPadding().imePadding()', adapter)
        for operation in ('SyncRuntime.', 'File(', 'read(', 'inspect(', 'getPackageManager', 'entitlementCache', 'showConfirmation'):
            self.assertNotIn(operation, adapter)

    def test_real_values_and_available_actions_are_projected_without_fixtures(self):
        host = body(MAIN, 'private void renderSettings()')
        for key in ('GAME', 'CLOUD_CONNECTION', 'ACCOUNT_APPRECIATION', 'SAVE_PATH', 'AUTOMATIC_UPLOAD',
                    'RECHECK_SAVES', 'REVIEW_SETUP', 'LINK_PROFILE', 'SYNC_NOTIFICATIONS', 'EXPORT_LOGS', 'MAINTENANCE'):
            self.assertEqual(host.count('new SharedSettingsRow(SharedSettingsKey.' + key + ','), 1)
        for value in ('selectedGameLabel()', 'settingsAccessStatus()', 'settingsConnectionValue()', 'savePathLabel()',
                      'automaticObservationSupported()', 'SyncRuntime.isAutoUploadPaused(this)',
                      'officialAppreciationLabel(this.connection.username)', 'isOfficialAccount(this.connection)',
                      'this.selectedNamespace', 'this.connection.profile', 'this.inspection.isRetryablePending()',
                      'Build.VERSION.SDK_INT >= 33', 'diagnosticExport.busy()'):
            self.assertIn(value, host)
        self.assertIn('savePathLabel(), null, false', host)
        self.assertIn('automaticSupported ? automaticPaused ? "开启" : "暂停" : null', host)
        self.assertIn('notificationsAllowed ? null : "允许"', host)
        for forbidden in ('example.invalid', '模拟', 'sample', '.inspectReady(', '.exportTo(', 'probeRootAuthorization('):
            self.assertNotIn(forbidden, host)
        status = body(MAIN, 'private String settingsAccessStatus()')
        self.assertIn('readyForSaves()', status)
        self.assertIn('Root', status)
        self.assertIn('内置存档桥', status)

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
                      'm132x2f4e91b2()', 'editNamespace()', 'm133xa4c8b7f3()', 'chooseDiagnosticDirectory()',
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
            self.assertIn('showConfirmation(', body(MAIN, signature))
        self.assertIn('guardSettingsConfirmation(settingsOwner, settingsTicket, runnable)', body(MAIN, 'private void showConfirmation('))
        guard = body(MAIN, 'private Runnable guardSettingsConfirmation(')
        for value in ('!this.resumed', 'isDestroyed()', 'this.busy', 'this.page != SyncPage.CONFIRM',
                      'this.confirmationReturn != SyncPage.SETTINGS', 'ticket != this.settingsConfirmationToken',
                      'owner.sameTarget(currentSettingsContext(this.sharedSettingsRenderToken))'):
            self.assertIn(value, guard)
        self.assertLess(guard.index('++this.settingsConfirmationToken'), guard.index('action.run()'))
        self.assertIn('++this.settingsConfirmationToken', body(MAIN, 'public void cancelConfirmation()'))

    def test_namespace_dialog_is_same_host_flow_and_stale_callbacks_stop(self):
        edit = body(MAIN, 'public void editNamespace()')
        self.assertIn('showSettingsDialog(new AlertDialog.Builder', edit)
        self.assertIn('MainActivity.this.settingsDialog != dialogInterface || !isCurrentSettingsContext(owner)', edit)
        self.assertIn('!settingsCapabilities().canChangeGame', edit)
        self.assertIn('showConfirmation("确认云端档案关联"', body(MAIN, 'void m47x6b6b32d0('))

    def test_diagnostics_are_on_demand_and_export_keeps_existing_saf_boundary(self):
        host = body(MAIN, 'private void renderSettings()')
        self.assertNotIn('this.detailsExpanded', host)
        self.assertNotIn('safeText(this.details)', host)
        self.assertIn('showSettingsDiagnostics(owner)', body(MAIN, 'private void showSettingsMaintenance('))
        diagnostic = body(MAIN, 'private void showSettingsDiagnostics(')
        self.assertIn('safeText(this.details)', diagnostic)
        self.assertIn('copyDiagnostic()', diagnostic)
        self.assertIn('isCurrentSettingsContext(owner)', diagnostic)
        export = body(MAIN, 'private void chooseDiagnosticDirectory()')
        self.assertIn('diagnosticExport.chooseDirectory(this)', export)
        for forbidden in ('SyncRuntime.', 'Root', 'Network', 'sync(', 'work('):
            self.assertNotIn(forbidden, export)


if __name__ == '__main__':
    unittest.main()
