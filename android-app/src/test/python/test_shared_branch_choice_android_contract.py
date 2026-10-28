"""Android modal composition seam; executed host guards and shared Compose tests complement it."""
from pathlib import Path
import unittest

from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()
ADAPTER = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidBranchChoiceComposition.kt').read_text()


MODEL = (REPO / 'shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidCloudOverwriteState.kt').read_text()

class SharedBranchChoiceAndroidContract(unittest.TestCase):
    def test_modal_replaces_legacy_chrome_and_uses_real_home_history(self):
        host = body(MAIN, 'private void renderConflict()')
        for required in ('ownerRoot.removeAllViews()', 'ownerRoot.setPadding(0, 0, 0, 0)',
                         'ownerRoot.setOnApplyWindowInsetsListener(null)',
                         'AndroidBranchChoiceComposition.view(this, observed, this.history,',
                         'this.historyPage', 'this.explicitHomeSelection',
                         'homeStatus(homeContent())',
                         'homeAccountExpired() ? SharedHomeStatusKind.EXPIRED : homeStatusKind(homeContent())',
                         'new LinearLayout.LayoutParams(-1, -1)'):
            self.assertIn(required, host)
        for forbidden in ('this.body.addView(', 'heading(', 'showConfirmation(', 'showDestructiveConfirmation('):
            self.assertNotIn(forbidden, host)
        for required in ('SharedHomeScreen(SharedHomeState(', 'pageActive = false',
                         'status = homeStatus, statusKind = homeStatusKind',
                         'selectedSnapshotId = homeSelection', 'HistoryPresentation.from(it, historyPage)',
                         'SharedBranchChoiceDialog(state, actions)', 'AndroidAppViewport {',
                         'ViewCompositionStrategy.DisposeOnDetachedFromWindow'):
            self.assertIn(required, ADAPTER)
        self.assertNotIn('SharedBranchChoiceScreen(', ADAPTER)
        self.assertLess(ADAPTER.index('SharedHomeScreen('), ADAPTER.index('SharedBranchChoiceDialog('))

    def test_inactive_backdrop_has_no_action_or_io_dispatch(self):
        actions = body(ADAPTER, 'object : SharedHomeActions')
        for action in ('settings()', 'upload()', 'restore()', 'launch()', 'selectSnapshot(snapshotId: String)',
                       'rename(snapshotId: String?)', 'changeHistoryPage(page: Int)'):
            self.assertIn('override fun ' + action + ' = Unit', actions)
        for forbidden in ('.inspect(', '.upload(', '.restore', '.connect(', 'SyncRuntime.',
                          'readComparison(', 'freshExport(', 'SaveSummary(', '.entitlement('):
            self.assertNotIn(forbidden, ADAPTER)

    def test_adapter_preserves_existing_branch_capabilities_and_metadata(self):
        for required in ('observation = observed', 'observed?.isOverwriteConflict == true',
                         'branch.snapshotId, branch.displayName', 'selectedId = observed?.overwriteTargetSnapshotId',
                         'pageActive = pageActive, busy = busy',
                         'connected = connected, observationCurrent = cloudVerified && branched',
                         'resolutionBlocked = recovery || pending',
                         'overwriteSupported = true, cloudOverwrite = true',
                         'canPreserveLocal = false'):
            self.assertIn(required, MODEL)
        self.assertNotIn('capturedAt', MODEL)
        self.assertNotIn('preparedAt', MODEL)

    def test_ordinary_upload_has_no_new_chooser_or_confirmation(self):
        request = body(MAIN, '\n    public void requestUpload()')
        upload = body(MAIN, '\n    public void upload()')
        self.assertIn('upload();', request)
        self.assertIn('operation(DiagnosticAction.PUSH', upload)
        self.assertIn('sync().upload(inspection)', body(MAIN, 'AndroidSyncFacade.OperationResult m146lambda$upload$71$'))
        for forbidden in ('showConfirmation(', 'showDestructiveConfirmation(', 'SyncPage.CONFIRM',
                          'SyncPage.CONFLICT', 'SharedBranchChoice', 'showUploadConfirmation('):
            self.assertNotIn(forbidden, request + upload)
        restore = body(MAIN, '\n    public void requestRestore()')
        self.assertNotIn('SyncPage.CONFLICT', restore)
        self.assertNotIn('overwriteCloudSuccessors', restore)

    def test_conflict_callbacks_keep_platform_guard_and_original_operation_routes(self):
        host = body(MAIN, 'private void renderConflict()')
        for required in ('root == ownerRoot', 'page == SyncPage.CONFLICT', 'resumed && !isDestroyed()',
                         '!busy && !sharedAppreciationOnly', 'inspection == observed',
                         'facade == ownerFacade', 'connection == ownerConnection',
                         'ownerContext.accepts(currentHomeContext(sharedHomeRenderToken), true, busy)',
                         'm86xfa720d6a(observed, snapshotId)',
                         '@Override public void preserveLocal() { }'):
            self.assertIn(required, host)
        work = body(MAIN, 'private <T> void work(DiagnosticAction action, String str, final Task<T> task, final Done<T> done, boolean preserveAutomaticHistory)')
        self.assertLess(work.index('this.busy = true'), work.index('this.f9io.execute'))
        self.assertIn('requireOfficialSyncAccess(this.connection)', body(MAIN, 'private AndroidSyncFacade sync()'))
        resolve = body(MAIN, 'AndroidSyncFacade.OperationResult m85x84f7e729(')
        self.assertIn('sync().overwriteCloudSuccessors(inspection, str)', resolve)
        dispatch = body(MAIN, 'void m86xfa720d6a(')
        self.assertLess(dispatch.index('this.page = SyncPage.HOME'), dispatch.index('work(DiagnosticAction.PUSH'))
        self.assertIn('m85x84f7e729(inspection, str)', dispatch)


if __name__ == '__main__':
    unittest.main()
