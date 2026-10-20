"""Source wiring paired with executed AutoSyncStatusChecks; not Android runtime tests."""
import unittest
from pathlib import Path
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (ROOT / 'MainActivity.java').read_text()
SERVICE = (ROOT / 'AutoSyncService.java').read_text()

class AutoStatusContract(unittest.TestCase):
    def test_evidence_is_captured_before_actual_export_and_acknowledged_after_full_comparison(self):
        read = body(MAIN, 'private void readComparison(boolean prepareNamespace, boolean allowHomePreview)')
        self.assertLess(read.index('AutoSyncService.beginManualInspection(this)'), read.index('activeFacade.inspect()'))
        blocked = body(read[read.index('requireCurrentArchiveRead(archiveRead, archiveAccount, archiveSelection, archiveNamespace, archivePage);', read.index('final SyncHistory observedHistory;')):], 'if ("BLOCKED".equals(inspectionInspect.getKind()))')
        self.assertIn('return;', blocked)
        self.assertLess(read.index(blocked), read.index('AutoSyncService.verifiedManualInspection('))
        self.assertLess(read.index('activeFacade.historyPresentation(inspectionInspect)'), read.index('AutoSyncService.verifiedManualInspection('))
        self.assertLess(read.index('this.cloudVerified = true'), read.index('AutoSyncService.verifiedManualInspection('))
        self.assertEqual(MAIN.count('AutoSyncService.verifiedManualInspection('), 1)
        self.assertIn('if (inspectionInspect.isObjectVerified())', read)

    def test_acknowledgment_never_changes_listener_or_upload_state(self):
        mark = body(SERVICE, 'static void verifiedManualInspection(')
        self.assertIn('inspection.getKind(), inspection.getNeedsRestoreRecovery(), inspection.isRetryablePending()', mark)
        self.assertIn('SyncRuntime.currentSelectionToken(context), SyncRuntime.currentNamespace(context)', mark)
        self.assertIn('putString(CHECKED_IDLE_FAILURE, acknowledgment)', mark)
        for mutation in ('putString("state"', 'putString("result"', 'putString("token"', 'startMonitoring(', 'stopService(', '.arm(', '.upload(', '.autoUpload('):
            self.assertNotIn(mutation, mark)
        self.assertIn('catch (Exception unavailable)', mark)

    def test_display_requires_exact_current_record_and_scope(self):
        status = body(SERVICE, 'public static String status(')
        self.assertIn('AutoSyncStatusPolicy.visibleResult(current, preferences.getString(CHECKED_IDLE_FAILURE, "")', status)
        self.assertIn('SyncRuntime.currentSelectionToken(context), SyncRuntime.currentNamespace(context)', status)
        self.assertIn('return StoredAndroidText.render(current.result)', status)
        record = body(SERVICE, 'private static AutoSyncStatusPolicy.Record statusRecord(')
        self.assertIn('preferences.getAll()', record)
        for field in ('"token"', '"state"', '"result"', '"gameSelection"', '"gameNamespace"', '"startedAt"'):
            self.assertIn(field, record)

    def test_unknown_observation_remains_terminal_and_no_new_status_text(self):
        poll = body(SERVICE, 'public void poll()')
        stop = body(poll, 'if (stepObserve == AutoSyncPolicy.Step.STOP_UNKNOWN)')
        self.assertIn('finish("BLOCKED", AutoSyncStatusPolicy.IDLE_FAILURE)', stop)
        self.assertIn('return;', stop)
        brief = body(MAIN, 'private String automaticStatusBrief()')
        self.assertIn('AutoSyncService.status(this)', brief)
        self.assertNotIn('已重新检查', MAIN)

    def test_root_only_scope_is_rechecked_before_acknowledgment_and_display(self):
        capture = body(SERVICE, 'static AutoSyncStatusPolicy.Record beginManualInspection(')
        mark = body(SERVICE, 'static void verifiedManualInspection(')
        status = body(SERVICE, 'public static String status(')
        self.assertLess(capture.index('if (!SyncRuntime.rootMode(context)) return null'), capture.index('AutoSyncStatusPolicy.capture('))
        self.assertLess(mark.index('if (!SyncRuntime.rootMode(context)) return;'), mark.index('AutoSyncStatusPolicy.acknowledgment('))
        self.assertLess(status.index('if (!SyncRuntime.rootMode(context)) return StoredAndroidText.render(current.result)'), status.index('AutoSyncStatusPolicy.visibleResult('))
