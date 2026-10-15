"""Android Step 4 seam checks, paired with executed ComparisonProofState and core JVM tests.

These assertions do not claim Android device, pixel, lifecycle or real-account execution.
"""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()
ADAPTER = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidOnboardingComposition.kt').read_text()
CORE = (REPO / 'core/src/main/kotlin/pdsync/android/app/AndroidSyncFacade.kt').read_text()


class SharedComparisonAndroidContract(unittest.TestCase):
    def test_shared_comparison_routes_before_native_view_creation(self):
        render = body(MAIN, 'public void render()')
        route = body(render, 'if (this.page == SyncPage.COMPARISON)')
        self.assertIn('renderSharedComparison()', route)
        self.assertIn('return;', route)
        self.assertLess(render.index(route), render.index('ScrollView scrollView'))
        self.assertNotIn('private void renderComparison()', MAIN)
        adapter = body(ADAPTER, 'fun showComparison(')
        self.assertIn('SharedComparisonScreen(state, actions, appreciation = appreciation)', adapter)
        self.assertIn('safeDrawingPadding().imePadding()', adapter)
        for native in ('LinearLayout', 'RadioButton', 'foot(', 'heading(', 'showConfirmation('):
            self.assertNotIn(native, body(MAIN, 'private void renderSharedComparison()'))

    def test_platform_state_uses_actual_observation_capabilities_and_game(self):
        adapter = body(ADAPTER, 'fun showComparison(')
        for proof in ('observed?.kind', 'observed?.needsRestoreRecovery', 'observed?.isRetryablePending',
                      'observed?.canCompleteWithoutTransfer', 'observed?.canAdoptRemote',
                      'observed?.canKeepLocalProgress', 'observed?.localFileCount', 'observed?.remoteVersions'):
            self.assertIn(proof, adapter)
        self.assertIn('savePathSummary(history?.summary)', adapter)
        render = body(MAIN, 'private void renderSharedComparison()')
        self.assertIn('this.inspection, this.history, selectedGameIdentity()', render)
        self.assertIn('this.cloudVerified, restoreSupported(), this.connection != null', render)
        for fabricated in ('new Inspection(', 'new SaveSummary(', 'SaveSummary(', 'SyncRuntime.', '.inspect('):
            self.assertNotIn(fabricated, adapter)

    def test_readiness_and_backend_proof_are_bound_independently_of_render(self):
        context = body(MAIN, 'private ComparisonProofState.Context currentComparisonContext()')
        for proof in ('this.facade, this.connection, this.inspection', 'SyncRuntime.currentSelectionToken(this)',
                      'SyncRuntime.currentNamespace(this)'):
            self.assertIn(proof, context)
        self.assertNotIn('sharedComparisonRenderToken', context)
        foreground = body(MAIN, 'private boolean isForegroundComparison()')
        for guard in ('this.resumed', '!isDestroyed()', 'this.page == SyncPage.COMPARISON', '!this.sharedAppreciationOnly'):
            self.assertIn(guard, foreground)
        for signature in ('protected void onPause()', 'protected void onDestroy()', 'private void navigate(',
                          'private void readComparison()', 'private void resetAccessSelection()', 'public void refreshReadiness()'):
            self.assertIn('this.comparisonProofState.reset()', body(MAIN, signature))

    def test_explicit_progress_does_not_inherit_native_default_snapshot(self):
        render = body(MAIN, 'private void renderSharedComparison()')
        self.assertIn('this.comparisonProofState.selectedId()', render)
        self.assertNotIn('this.selected', render)
        choice = body(MAIN, 'private void selectSharedProgress(')
        self.assertIn('getRemoteVersions().size() == 1', choice)
        self.assertIn('this.comparisonProofState.selectProgress(', choice)
        for mode in ('OnboardingKind.LOCAL_ONLY', 'OnboardingKind.REMOTE_ONLY', 'getCanKeepLocalProgress()'):
            self.assertIn(mode, choice)
        self.assertNotIn('getDefaultSnapshot()', choice)
        selection = body(MAIN, 'private void selectSharedRemote(')
        self.assertIn('hasSharedRemote(snapshotId)', selection)
        self.assertIn('this.comparisonProofState.selectRemote(snapshotId)', selection)

    def test_finish_dispatch_revalidates_the_requested_mode(self):
        dispatch = body(MAIN, 'private void finishSharedComparison(')
        self.assertIn('if (!usableSharedComparison()', dispatch)
        for action in ('completeEmpty()', 'completeReadyComparison()', 'adopt()', 'completeLocalProgress()',
                       'upload()', 'restoreSharedRemote()'):
            self.assertIn(action, dispatch)
        for evidence in ('OnboardingKind.BOTH_EMPTY', 'OnboardingKind.IDENTICAL', 'getCanCompleteWithoutTransfer()',
                         'getCanAdoptRemote()', 'getCanKeepLocalProgress()', 'Progress.LOCAL', 'Progress.CLOUD'):
            self.assertIn(evidence, dispatch)
        usable = body(MAIN, 'private boolean usableSharedComparison()')
        for guard in ('matches(currentComparisonContext())', 'this.cloudVerified', 'OnboardingKind.BLOCKED',
                      '!this.inspection.getNeedsRestoreRecovery()', '!this.inspection.isRetryablePending()'):
            self.assertIn(guard, usable)

    def test_finish_core_baseline_revalidation_is_retained(self):
        for signature in ('private void completeReadyComparison()', 'private void completeLocalProgress()'):
            finish = body(MAIN, signature)
            self.assertIn('captureSharedFinish()', finish)
            self.assertLess(finish.index('requireSharedComparisonProof(proof)'), finish.index('owner.completeOnboarding(observed)'))
            self.assertLess(finish.index('owner.completeOnboarding(observed)'), finish.index('SyncRuntime.setOnboardingProgress'))
        empty = body(MAIN, 'public void completeEmpty()')
        self.assertIn('requireSharedComparisonProof(proof)', empty)
        empty_core = body(MAIN, 'Object m29xb53dc5d7()')
        self.assertLess(empty_core.index('readComparison()'), empty_core.index('.completeOnboarding(this.inspection)'))
        self.assertIn('EmptySetupCompletion.canFinish', empty_core)

    def test_remote_only_finish_uses_empty_local_proof_without_second_native_dialog(self):
        restore = body(MAIN, 'private void restoreSharedRemote()')
        for guard in ('OnboardingKind.REMOTE_ONLY', 'getLocalFileCount() != 0', '!restoreSupported()',
                      'hasSharedRemote(', 'captureSharedFinish()', 'requireSharedComparisonProof(proof)',
                      'requireRestoreSupport()'):
            self.assertIn(guard, restore)
        self.assertIn('restoreForCurrentAccount(observed, proof.snapshotId, false, proof)', restore)
        for forbidden in ('showConfirmation(', 'requestRestore()', 'this.selected', 'SyncPage.CONFIRM'):
            self.assertNotIn(forbidden, restore)

    def test_handle_conflict_only_creates_dialog_and_confirmation_claim_is_single_use(self):
        open_dialog = body(MAIN, 'private void openSharedConflict()')
        self.assertIn('this.comparisonProofState.openConflict()', open_dialog)
        for forbidden in ('requestRestore(', 'operation(', 'showConfirmation(', '.resolveConflict(', '.restoreSelected('):
            self.assertNotIn(forbidden, open_dialog)
        claim = body(MAIN, 'private ComparisonProofState.Ticket claimSharedConflict(')
        self.assertIn('this.comparisonProofState.claim(token, currentComparisonContext()', claim)
        self.assertIn('!restoreSupported()', claim)
        overwrite = body(MAIN, 'private void confirmSharedConflict(')
        self.assertIn('hasSharedRemote(this.comparisonProofState.selectedId())', overwrite)
        self.assertIn('ComparisonProofState.Stage.CONFLICT', overwrite)
        self.assertIn('owner.resolveConflict(observed, proof.snapshotId)', overwrite)
        self.assertIn('restoreForCurrentAccount(observed, proof.snapshotId, true, proof)', overwrite)
        self.assertNotIn('requestRestore()', overwrite)
        self.assertNotIn('showConfirmation(', overwrite)

    def test_branch_restore_and_merge_check_real_official_entitlement_before_write(self):
        entitlement = body(MAIN, 'private void requireOfficialSyncAccess(')
        for proof in ('this.connection != account', 'new ManagedAccountClient(', '.entitlement()', 'entitlement.requireSyncAccess()'):
            self.assertIn(proof, entitlement)
        for signature, write in (('private void confirmSharedConflict(', 'owner.resolveConflict('),
                                 ('private void confirmSharedMerge(', 'owner.mergeConflict(')):
            action = body(MAIN, signature)
            self.assertLess(action.index('requireRestoreSupport()'), action.index(write))
            self.assertLess(action.index('requireOfficialSyncAccess('), action.index(write))
            self.assertGreaterEqual(action.count('requireSharedComparisonProof(proof)'), 2)
        account_restore = body(MAIN, 'String snapshotId, boolean replace, ComparisonProofState.Ticket proof)')
        self.assertLess(account_restore.index('requireOfficialSyncAccess(account)'), account_restore.index('requireSharedComparisonProof(proof)'))
        self.assertLess(account_restore.index('requireSharedComparisonProof(proof)'), account_restore.index('owner.restoreSelected('))

    def test_merge_preview_stays_in_shared_dialog_and_stale_completion_cannot_publish(self):
        preview = body(MAIN, 'private void previewSharedMerge(')
        self.assertIn('owner.previewMerge(observed)', preview)
        self.assertIn('result.preview.isAllowed()', preview)
        self.assertIn('this.comparisonProofState.review(proof,', preview)
        self.assertIn('getSlotCount()', preview)
        self.assertIn('getDecisions()', preview)
        self.assertLess(preview.index('if (!this.comparisonProofState.current(proof.token'),
                        preview.index('this.comparisonDialogMessage ='))
        for forbidden in ('m63xe49933fc', 'showConfirmation(', 'SyncPage.CONFIRM', 'mergeConflict('):
            self.assertNotIn(forbidden, preview)
        merge = body(MAIN, 'private void confirmSharedMerge(')
        self.assertIn('ComparisonProofState.Stage.MERGE_REVIEW', merge)
        self.assertIn('owner.mergeConflict(observed)', merge)
        for proof in ('checkActualUnchanged(comparison.localFiles)', 'prepareGameRestore(', 'session.previewOnboardingMerge(comparison,store,mirrorStopped)', 'session.mergeCurrent(comparison,store,mirrorStopped)'):
            self.assertIn(proof, body(CORE, 'fun mergeConflict('))
        self.assertIn('session.previewOnboardingMerge(comparison,store,mirrorStopped)', body(CORE, 'fun previewMerge('))
        for unbound in ('mergeObservation(', 'session.previewMerge(', 'session.merge(conflict,', 'session.mergeCurrent(store,'):
            self.assertNotIn(unbound, body(CORE, 'fun mergeConflict('))

    def test_back_cancels_modal_first_and_never_implicitly_postpones_setup(self):
        back = body(MAIN, 'public void onBackPressed()')
        self.assertIn('cancelSharedConflict(this.comparisonProofState.dialog().token)', back)
        self.assertLess(back.index('cancelSharedConflict('), back.index('if (this.busy)'))
        comparison = body(back, 'else if (this.page == SyncPage.COMPARISON)')
        self.assertIn('navigate(SyncPage.CONNECTION)', comparison)
        self.assertNotIn('postpone()', comparison)
        cancel = body(MAIN, 'private void cancelSharedConflict(')
        self.assertIn('this.comparisonProofState.cancel(token,', cancel)
        for mutation in ('operation(', '.run()', '.restore', '.mergeConflict', 'setOnboardingProgress'):
            self.assertNotIn(mutation, cancel)

    def test_retry_upload_preserves_pending_but_never_bypasses_restore_recovery(self):
        upload = body(MAIN, '\n    public void upload()')
        self.assertIn('requireSharedComparisonProof(proof, true)', upload)
        proof = body(MAIN, 'private void requireSharedComparisonProof(ComparisonProofState.Ticket proof, boolean allowPendingUpload)')
        self.assertIn('this.inspection.getNeedsRestoreRecovery()', proof)
        self.assertIn('allowPendingUpload && this.inspection.isRetryablePending()', proof)
        self.assertIn('claimedCurrent(proof, currentComparisonContext(), isForegroundComparison())', proof)
        self.assertIn('sync().upload(inspection)', body(MAIN, 'AndroidSyncFacade.OperationResult m146lambda$upload$71$'))
        self.assertIn('checked(inspection,true)', body(CORE, 'fun upload('))

    def test_failed_finish_or_frozen_upload_revokes_only_its_captured_observation(self):
        work = body(MAIN, "private <T> void work(DiagnosticAction action, String str, final Task<T> task, final Done<T> done, boolean preserveAutomaticHistory)")
        self.assertIn("final ComparisonProofState.Ticket comparisonClaim = this.activeSharedComparisonClaim", work)
        self.assertIn("task, done, trace, comparisonClaim", work)
        worker = body(MAIN, "void m150lambda$work$2")
        self.assertIn("e, comparisonClaim", worker)
        failed = body(MAIN, "void m149lambda$work$1")
        self.assertIn("reportWorkFailure(exc, comparisonClaim)", failed)
        callback = body(MAIN, "void m148lambda$work$0")
        self.assertIn("reportWorkFailure(e, comparisonClaim)", callback)
        handler = body(MAIN, "private void reportWorkFailure(")
        self.assertIn("invalidateFailure(comparisonClaim, currentComparisonContext()", handler)
        self.assertIn("this.page == SyncPage.COMPARISON", handler)
        self.assertLess(handler.index("invalidateFailure("), handler.index("this.cloudVerified = false"))
        self.assertLess(handler.index("invalidateFailure("), handler.index("reportError(failure)"))
        for reset in ("this.cloudVerified = false", "this.inspection = null", "this.history = null"):
            self.assertIn(reset, handler)
        self.assertNotIn("SyncRuntime.setOnboardingProgress", handler)
        self.assertIn('!invalidateUninspectedComparisonFailure(comparisonClaim, pageActive)', handler)
        incomplete = body(MAIN, 'private boolean invalidateUninspectedComparisonFailure(')
        for guard in ('if (this.inspection != null) return false', 'invalidateUninspectedFailure(comparisonClaim, this.facade, this.connection,',
                      'SyncRuntime.currentSelectionToken(this)', 'SyncRuntime.currentNamespace(this)', 'catch (Exception unavailable)'):
            self.assertIn(guard, incomplete)
        self.assertIn("requireSharedComparisonProof(proof, true)", body(MAIN, "\n    public void upload()"))
        self.assertIn("captureSharedFinish()", body(MAIN, "private void completeReadyComparison()"))
        reinspection = body(MAIN, 'private void readComparison()')
        self.assertIn('beginReinspection(reinspectionOwner,', reinspection)
        self.assertIn('if (!ownedReinspection) this.comparisonProofState.reset()', reinspection)
        self.assertIn('finally', reinspection)
        self.assertIn('finishReinspection(reinspectionOwner,', reinspection)
        # Only a new core inspection can reveal frozen retryable pending or recovery after a failure.
        refresh = body(MAIN, "private void refreshComparison(final SyncPage syncPage,")
        self.assertIn("this.inspection = null", refresh)
        self.assertIn("m66x99ceaa21", refresh)
        self.assertIn("readComparison()", body(MAIN, "Boolean m66x99ceaa21("))

    def test_failed_claim_releases_only_its_owner_and_unresolved_setup_stays_shared(self):
        render = body(MAIN, 'public void render()')
        self.assertIn('if (!this.busy && this.activeSharedComparisonClaim != null)', render)
        self.assertIn('this.comparisonProofState.releaseClaim(this.activeSharedComparisonClaim)', render)
        outcome = body(MAIN, 'void m55x8211a061(')
        self.assertIn('this.onboardingActive ? SyncPage.COMPARISON : SyncPage.CONFLICT', outcome)
        pending = body(outcome, 'else if (operationOutcome.result.isPending())')
        self.assertIn('this.onboardingActive ? SyncPage.COMPARISON : SyncPage.HOME', pending)
        operation = body(MAIN, 'OperationOutcome m54xc977a20(')
        for proof in ('readComparison()', 'kind() == OnboardingKind.IDENTICAL', 'getBaselineCommit() != null',
                      '!operationOutcome.result.isPending()', '!operationOutcome.result.isConflict()', '.completeOnboarding(this.inspection)'):
            self.assertIn(proof, operation)


if __name__ == '__main__':
    unittest.main()
