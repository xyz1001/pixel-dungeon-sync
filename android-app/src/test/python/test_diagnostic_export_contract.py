"""Source wiring paired with executed DiagnosticExportChecks; not Android runtime tests."""
import unittest
from pathlib import Path
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (ROOT / 'MainActivity.java').read_text()
EXPORT = (ROOT / 'AndroidDiagnosticExport.java').read_text()
WRITER = (ROOT / 'DiagnosticExportWriter.java').read_text()
STATE = (ROOT / 'DiagnosticExportState.java').read_text()
SERVICE = (ROOT / 'AutoSyncService.java').read_text()
LOGGING = (ROOT / 'AndroidDiagnosticLogging.java').read_text()
SHARED = Path(__file__).resolve().parents[4] / 'core/src/main/java/io/github/xyz1001/pdsync/android'
AUTH = (SHARED / 'OfficialServiceRegistrationClient.java').read_text()

class DiagnosticExportContract(unittest.TestCase):
    def test_settings_contains_only_one_export_button(self):
        settings = body(MAIN, 'private void renderSettings()')
        self.assertEqual(MAIN.count('button("导出日志"'), 1)
        self.assertIn('button("导出日志", this::chooseDiagnosticDirectory).setEnabled(!diagnosticExport.busy())', settings)
        start = body(MAIN, 'private void chooseDiagnosticDirectory()')
        for forbidden in ('work(', 'sync(', 'SyncRuntime.', 'requestReadinessRefresh(', 'Root', 'Network'):
            self.assertNotIn(forbidden, start)

    def test_system_picker_temporary_read_write_grants_no_custom_browser(self):
        choose = body(EXPORT, 'void chooseDirectory(')
        self.assertIn('new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)', choose)
        self.assertIn('picker.addFlags(TEMPORARY_GRANTS)', choose)
        self.assertIn('Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION', EXPORT)
        for forbidden in ('takePersistableUriPermission', 'FLAG_GRANT_PERSISTABLE_URI_PERMISSION', 'ACTION_GET_CONTENT', 'ACTION_CREATE_DOCUMENT', 'WRITE_EXTERNAL_STORAGE', 'MANAGE_EXTERNAL_STORAGE'):
            self.assertNotIn(forbidden, EXPORT)

    def test_request_code_routes_before_existing_apk_installer_and_returns(self):
        self.assertIn('REQUEST_DIRECTORY = 8104', EXPORT)
        result = body(MAIN, 'protected void onActivityResult(')
        selected = body(result, 'if (i == AndroidDiagnosticExport.REQUEST_DIRECTORY)')
        self.assertIn('diagnosticExport.onDirectoryResult(this, i2, intent)', selected)
        self.assertIn('return;', selected)
        self.assertLess(result.index(selected), result.index('if (i == 8103)'))
        self.assertIn('i == 8101 && i2 == -1', result)
        self.assertIn('i != 8102 || this.busy', result)

    def test_cancellation_is_silent_and_stale_results_do_not_write(self):
        result = body(EXPORT, 'void onDirectoryResult(')
        self.assertLess(result.index('if (!state.selecting()) return'), result.index('state.selected()'))
        cancel = body(result, 'if (resultCode != Activity.RESULT_OK)')
        self.assertIn('state.cancelSelection()', cancel)
        self.assertIn('DiagnosticResult.CANCELED', cancel)
        for forbidden in ('complete(', 'snapshotExport(', 'createNew(', 'Toast', 'work('): self.assertNotIn(forbidden, cancel)

    def test_export_is_bounded_local_snapshot_with_unique_create_and_readback(self):
        self.assertLess(EXPORT.index('byte[] snapshot = DiagnosticLog.snapshotExport()'), EXPORT.index('DiagnosticExportWriter.write(snapshot'))
        self.assertIn('UUID.randomUUID()', EXPORT)
        self.assertIn('DocumentsContract.createDocument(resolver, directory, "application/zip", filename)', EXPORT)
        self.assertIn('existing.contains(DocumentsContract.getDocumentId(candidate))', EXPORT)
        self.assertIn('FLAG_DIR_SUPPORTS_CREATE', EXPORT)
        self.assertIn('filename.equals(cursor.getString(1))', EXPORT)
        self.assertIn('MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024', WRITER)
        self.assertIn('input.read() != -1', WRITER)
        self.assertIn('buffer[i] != snapshot[offset + i]', WRITER)
        for forbidden in ('SyncRuntime.', 'AutoSyncService.', 'RootGameBridge', 'okhttp', 'WebDav', 'getExternalStorage', 'logcat', 'getMessage()', 'printStackTrace'):
            self.assertNotIn(forbidden, EXPORT + WRITER)

    def test_only_new_failed_document_removed_never_source_logs(self):
        self.assertIn('if (created && !verified)', WRITER)
        self.assertIn('destination.removeCreated()', WRITER)
        self.assertEqual(EXPORT.count('DocumentsContract.deleteDocument('), 1)
        self.assertIn('DocumentsContract.deleteDocument(resolver, created)', EXPORT)
        self.assertNotIn('delete(', WRITER)
        self.assertNotIn('deleteIfExists', EXPORT)
        self.assertIn('日志导出失败，请检查目录权限和剩余空间后重试。', EXPORT)

    def test_rotation_uses_one_process_state_weak_owner_and_no_persisted_uri(self):
        self.assertIn('private static final AndroidDiagnosticExport INSTANCE', EXPORT)
        self.assertIn('WeakReference<MainActivity>', EXPORT)
        self.assertIn('owner.get() == activity', EXPORT)
        save = body(MAIN, 'protected void onSaveInstanceState(')
        self.assertIn('bundle.putBoolean("diagnostics.picker", diagnosticExport.selecting())', save)
        self.assertIn('bundle.putBoolean("diagnostics.return", diagnosticPickerReturn)', save)
        self.assertNotIn('putParcelable', save)
        self.assertIn('restorePicker(bundle.getBoolean("diagnostics.picker", false))', MAIN)
        self.assertIn('ticket != generation', STATE)
        self.assertIn('if (phase != Phase.SELECTING) return 0', STATE)

    def test_picker_lifecycle_does_not_trigger_root_network_or_auto_evidence(self):
        resume = body(MAIN, 'protected void onResume()')
        guard = body(resume, 'if (diagnosticPickerReturn || diagnosticExport.busy())')
        self.assertIn('return;', guard)
        self.assertLess(resume.index(guard), resume.index('AutoSyncService.knownReturn(this)'))
        self.assertLess(resume.index(guard), resume.index('requestReadinessRefresh()'))
        pause = body(MAIN, 'protected void onPause()')
        self.assertIn('if (!diagnosticExport.selecting()) AutoSyncService.recordDeparture(this)', pause)

    def test_manual_and_auto_diagnostics_are_closed_and_thread_scoped(self):
        for action in ('CONNECT','PREPARE','INSTALL','INSPECT','PUSH','RESTORE','RECOVERY'):
            self.assertIn('DiagnosticAction.' + action, MAIN)
        worker = body(MAIN, 'void m150lambda$work$2')
        self.assertIn('AndroidDiagnosticLogging.bind(trace)', worker)
        self.assertIn('AndroidDiagnosticLogging.failure(trace, DiagnosticStage.FINISHED, e)', worker)
        self.assertIn('DiagnosticResult.CONFLICT', MAIN)
        self.assertIn('AndroidDiagnosticLogging.readinessFailure(this.readinessState, this.bridgeAccessCode)', MAIN)
        self.assertIn('case "ROOT_BUSY"', LOGGING)
        self.assertIn('DiagnosticFailure.BUSY', LOGGING)
        self.assertIn('DiagnosticResult.PENDING', MAIN)
        poll = body(SERVICE, 'public void poll()')
        self.assertIn('AndroidDiagnosticLogging.bind(automaticTrace())', poll)
        self.assertEqual(poll.count('.record('), 3)  # Two terminal guards and one first PLAYING transition.
        self.assertIn('if (!"PLAYING".equals(this.prefs.getString("state", "")))', poll)
        finish = body(SERVICE, 'private synchronized void finish(')
        self.assertIn('AndroidDiagnosticLogging.automaticResult(str)', finish)
        self.assertIn('case "SKIPPED_UNCERTAIN": case "PAUSED": return DiagnosticResult.SKIPPED', LOGGING)
        self.assertIn('case "SKIPPED_CONFLICT": return DiagnosticResult.CONFLICT', LOGGING)

    def test_real_outcomes_and_readiness_reason_reach_terminal_event(self):
        worker = body(MAIN, 'void m150lambda$work$2')
        self.assertIn('diagnosticWorkFailure(trace.getAction(), objRun, result)', worker)
        self.assertIn('trace.record(DiagnosticStage.WORK, result, failure, details)', worker)
        completion = body(MAIN, 'void m148lambda$work$0')
        self.assertEqual(completion.count('trace.record(DiagnosticStage.FINISHED, result, failure, details)'), 2)
        refresh = body(MAIN, 'private void drainAutomaticHistoryRefresh()')
        self.assertIn('return DiagnosticResult.SKIPPED', refresh)
        self.assertIn('DiagnosticLog.failure(DiagnosticStage.REFRESH, error)', refresh)
        self.assertIn('return result;', refresh)
        self.assertIn('result instanceof AutomaticHistoryOutcome', MAIN)
        self.assertIn('work(DiagnosticAction.LAUNCH, "正在核对首次启动', MAIN)
        self.assertIn('failDiagnostics(failure)', body(SERVICE, 'public void poll()'))
        self.assertIn('DiagnosticStage.FINISHED', body(SERVICE, 'private synchronized void failDiagnostics('))

    def test_installer_and_auth_status_logging_has_no_payloads(self):
        receiver = (ROOT / 'InstallResultReceiver.java').read_text()
        self.assertIn('AndroidDiagnosticLogging.installerResult(intExtra)', receiver)
        self.assertIn('DiagnosticLog.http(DiagnosticHttpMethod.POST, status, networkContext(requestStarted))', AUTH)
        self.assertIn('AndroidDiagnosticLogging.configure(this)', (ROOT / 'DiagnosticApplication.java').read_text())
        self.assertIn('DiagnosticLog.networkFailure(timeout, networkContext(requestStarted))', AUTH)
        self.assertIn('DiagnosticLog.networkFailure(tls, networkContext(requestStarted))', AUTH)
        self.assertIn('DiagnosticLog.networkFailure(network, networkContext(requestStarted))', AUTH)
        self.assertIn('context.getFilesDir().getCanonicalFile().toPath()', LOGGING)
        self.assertIn('info.versionName + "+"', LOGGING)
        for forbidden in ('getMessage()', 'toString()', 'username', 'password', 'getStackTrace', 'logcat'):
            self.assertNotIn(forbidden, LOGGING)
        self.assertNotRegex(LOGGING, r'\.gamePackage\b')  # gamePackageStatus is an allowed closed enum.
