package io.github.xyz1001.pdsync.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pdsync.OnboardingKind

class SharedComparisonStateTest {
    private fun observed(kind: OnboardingKind? = OnboardingKind.DIFFERENT) = SharedComparisonState(
        kind = kind, cloudVerified = true, pageActive = true, configured = true, restoreSupported = true,
        localLabel = "本机 · Desktop", localDetails = "已进行0场游戏", localFileCount = 2,
        remoteChoices = listOf(SharedComparisonRemoteChoice("snapshot-a", "云端 · Android", "修改时间未知")),
        selectedId = "snapshot-a",
    )

    private class Host(initial: SharedComparisonState) : SharedComparisonActions {
        var state = initial
        val calls = mutableListOf<String>()
        var markBusyOnOperation = false
        val interactions = SharedComparisonInteractions({ state }, { this })
        private fun operation(call: String) {
            if (markBusyOnOperation) state = state.copy(busy = true)
            calls += call
        }
        override fun back() { calls += "back" }
        override fun refresh() = operation("refresh")
        override fun selectProgress(choice: SharedProgressChoice) {
            calls += "progress:$choice"
            state = state.copy(selectedProgress = choice,
                selectedId = if (choice == SharedProgressChoice.CLOUD) state.remoteChoices.singleOrNull()?.snapshotId else state.selectedId)
        }
        override fun selectRemote(snapshotId: String) {
            calls += "select:$snapshotId"
            state = state.copy(selectedId = snapshotId, confirmation = state.confirmation?.let { it.copy(token = it.token + 1) })
        }
        override fun finish(mode: SharedComparisonCompletion) = operation("finish:$mode")
        override fun handleConflict() = operation("conflict")
        override fun upload() = operation("upload")
        override fun openRecovery() { calls += "recovery" }
        override fun copyDiagnostics() { calls += "copy" }
        override fun confirmConflict(token: Long) = operation("overwrite:$token")
        override fun previewMerge(token: Long) = operation("preview:$token")
        override fun confirmMerge(token: Long) = operation("merge:$token")
        override fun cancelConflict(token: Long) { calls += "cancel:$token"; state = state.copy(confirmation = null) }
    }

    private fun dialogState(kind: OnboardingKind = OnboardingKind.DIFFERENT) = observed(kind).copy(
        confirmation = SharedComparisonConfirmation(token = 7, canPreviewMerge = true))

    @Test fun allKindsHaveExactlyOnePrimaryAndUnknownIsRefresh() {
        val expected = mapOf(
            OnboardingKind.BOTH_EMPTY to SharedComparisonPrimary.FINISH,
            OnboardingKind.IDENTICAL to SharedComparisonPrimary.REFRESH,
            OnboardingKind.LOCAL_ONLY to SharedComparisonPrimary.FINISH,
            OnboardingKind.REMOTE_ONLY to SharedComparisonPrimary.FINISH,
            OnboardingKind.DIFFERENT to SharedComparisonPrimary.HANDLE_CONFLICT,
            OnboardingKind.BRANCHED to SharedComparisonPrimary.HANDLE_CONFLICT,
            OnboardingKind.BLOCKED to SharedComparisonPrimary.REFRESH,
        )
        OnboardingKind.entries.forEach { assertEquals(expected[it], observed(it).primary) }
        assertEquals(SharedComparisonPrimary.REFRESH, observed(null).primary)
    }

    @Test fun verifiedIdenticalHasOnlyFinishForBothCoreCompletionModes() {
        listOf(false, true).forEach { canConfirm ->
            val state = observed(OnboardingKind.IDENTICAL).copy(
                canCompleteWithoutTransfer = canConfirm, canAdoptRemote = !canConfirm,
                confirmation = SharedComparisonConfirmation(7, canPreviewMerge = true),
            )
            val expected = if (canConfirm) SharedComparisonCompletion.CONFIRM else SharedComparisonCompletion.ADOPT
            assertEquals(expected, state.completion)
            assertEquals(SharedComparisonPrimary.FINISH, state.primary)
            assertFalse(state.showsBack)
            assertNull(state.activeConfirmation)
            assertEquals("两边进度相同", state.headline)
            val host = Host(state)
            SharedComparisonPrimary.entries.forEach(host.interactions::performPrimary)
            host.interactions.back()
            SharedProgressChoice.entries.forEach(host.interactions::selectProgress)
            host.interactions.selectRemote("snapshot-a")
            host.interactions.confirm(7)
            host.interactions.previewMerge(7)
            assertEquals(listOf("finish:$expected"), host.calls)
        }
    }

    @Test fun identicalRequiresCorePermissionAndConfirmTakesPriorityOverAdopt() {
        val unavailable = observed(OnboardingKind.IDENTICAL).copy(canKeepLocalProgress = true,
            selectedProgress = SharedProgressChoice.LOCAL)
        assertEquals(SharedComparisonCompletion.NONE, unavailable.completion)
        assertEquals(SharedComparisonPrimary.REFRESH, unavailable.primary)
        val ready = unavailable.copy(canCompleteWithoutTransfer = true, canAdoptRemote = true)
        assertEquals(SharedComparisonCompletion.CONFIRM, ready.completion)
        assertEquals(SharedComparisonPrimary.FINISH, ready.primary)
    }

    @Test fun verifiedEmptyHasBackAndFinishAndErrorIsNotSuccessCopy() {
        val state = observed(OnboardingKind.BOTH_EMPTY).copy(localFileCount = 0, remoteChoices = emptyList(), selectedId = null)
        assertEquals(SharedComparisonCompletion.EMPTY, state.completion)
        assertEquals(SharedComparisonPrimary.FINISH, state.primary)
        assertTrue(state.showsBack)
        assertEquals("配置完成", state.headline)
        assertEquals("配置尚未完成", state.copy(feedbackError = true).headline)
        val host = Host(state)
        host.interactions.back()
        host.interactions.performPrimary(SharedComparisonPrimary.FINISH)
        assertEquals(listOf("back", "finish:EMPTY"), host.calls)
        assertFalse(state.copy(configured = false).primaryEnabled)
    }

    @Test fun unverifiedAndUnfinishedTransactionsNeverFinishEmptyOrIdentical() {
        listOf(OnboardingKind.BOTH_EMPTY, OnboardingKind.IDENTICAL).forEach { kind ->
            val ready = observed(kind).copy(canCompleteWithoutTransfer = true, canAdoptRemote = true)
            listOf(ready.copy(cloudVerified = false), ready.copy(needsRestoreRecovery = true),
                ready.copy(retryablePending = true)).forEach { state ->
                assertEquals(SharedComparisonCompletion.NONE, state.completion)
                assertFalse(state.primary == SharedComparisonPrimary.FINISH)
                assertFalse(state.headline == "配置完成")
                val host = Host(state)
                host.interactions.performPrimary(SharedComparisonPrimary.FINISH)
                assertTrue(host.calls.isEmpty())
            }
        }
    }

    @Test fun missingObservationAndDisplayTextNeverProveEqualityOrEmpty() {
        val state = observed(null).copy(cloudVerified = false, localFileCount = null,
            canAdoptRemote = true, canCompleteWithoutTransfer = true, canKeepLocalProgress = true,
            selectedProgress = SharedProgressChoice.LOCAL)
        assertNull(state.kind)
        assertNull(state.localFileCount)
        assertEquals(SharedComparisonCompletion.NONE, state.completion)
        assertEquals(SharedComparisonPrimary.REFRESH, state.primary)
        assertEquals("尚未完成检查，请重试。", state.headline)
        assertFalse(state.offers(SharedProgressChoice.LOCAL))
    }

    @Test fun localOnlyRequiresExplicitLocalChoiceThenUsesUploadCompletion() {
        val host = Host(observed(OnboardingKind.LOCAL_ONLY).copy(remoteChoices = emptyList(), selectedId = null))
        assertFalse(host.state.primaryEnabled)
        host.interactions.performPrimary(SharedComparisonPrimary.FINISH)
        host.interactions.selectProgress(SharedProgressChoice.CLOUD)
        assertTrue(host.calls.isEmpty())
        host.interactions.selectProgress(SharedProgressChoice.LOCAL)
        assertTrue(host.state.primaryEnabled)
        host.interactions.performPrimary(SharedComparisonPrimary.FINISH)
        assertEquals(listOf("progress:LOCAL", "finish:UPLOAD_LOCAL"), host.calls)
    }

    @Test fun cloudOnlyRequiresExplicitCloudChoiceAndCurrentRealId() {
        val host = Host(observed(OnboardingKind.REMOTE_ONLY))
        assertFalse(host.state.primaryEnabled, "preselected real ID must not imply progress choice")
        host.interactions.performPrimary(SharedComparisonPrimary.FINISH)
        host.interactions.selectProgress(SharedProgressChoice.LOCAL)
        assertTrue(host.calls.isEmpty())
        host.interactions.selectProgress(SharedProgressChoice.CLOUD)
        assertTrue(host.state.primaryEnabled)
        host.interactions.performPrimary(SharedComparisonPrimary.FINISH)
        assertEquals(listOf("progress:CLOUD", "finish:RESTORE_REMOTE"), host.calls)
        assertEquals(SharedComparisonCompletion.NONE, host.state.copy(selectedId = null).completion)
        assertEquals(SharedComparisonCompletion.NONE, host.state.copy(selectedId = "removed").completion)
        assertEquals(SharedComparisonCompletion.NONE, host.state.copy(restoreSupported = false).completion)
    }

    @Test fun localProgressPermissionRequiresDifferentAndExplicitLocalChoice() {
        val host = Host(observed().copy(canKeepLocalProgress = true, canCompleteWithoutTransfer = true))
        assertEquals(SharedComparisonPrimary.HANDLE_CONFLICT, host.state.primary)
        assertTrue(host.state.offers(SharedProgressChoice.LOCAL))
        assertTrue(host.state.offers(SharedProgressChoice.CLOUD))
        host.interactions.selectProgress(SharedProgressChoice.LOCAL)
        assertEquals(SharedComparisonCompletion.KEEP_LOCAL, host.state.completion)
        host.interactions.performPrimary(SharedComparisonPrimary.FINISH)
        host.interactions.selectProgress(SharedProgressChoice.CLOUD)
        assertEquals(SharedComparisonPrimary.HANDLE_CONFLICT, host.state.primary)
        assertEquals(SharedComparisonCompletion.NONE, host.state.completion)
        host.interactions.performPrimary(SharedComparisonPrimary.HANDLE_CONFLICT)
        assertEquals(listOf("progress:LOCAL", "finish:KEEP_LOCAL", "progress:CLOUD", "conflict"), host.calls)
        assertEquals(SharedComparisonCompletion.NONE, host.state.copy(kind = OnboardingKind.BRANCHED,
            selectedProgress = SharedProgressChoice.LOCAL).completion)
        assertEquals(SharedComparisonCompletion.NONE, host.state.copy(canKeepLocalProgress = false,
            selectedProgress = SharedProgressChoice.LOCAL).completion)
    }

    @Test fun recoveryTakesPriorityOverRetryAndBothTakePriorityOverUnknown() {
        val state = observed(OnboardingKind.BLOCKED).copy(cloudVerified = false, retryablePending = true)
        assertEquals(SharedComparisonPrimary.RETRY_UPLOAD, state.primary)
        assertTrue(state.primaryEnabled)
        val host = Host(state)
        SharedComparisonPrimary.entries.forEach(host.interactions::performPrimary)
        assertEquals(listOf("upload"), host.calls)
        host.calls.clear()
        host.state = state.copy(needsRestoreRecovery = true)
        assertEquals(SharedComparisonPrimary.OPEN_RECOVERY, host.state.primary)
        SharedComparisonPrimary.entries.forEach(host.interactions::performPrimary)
        assertEquals(listOf("recovery"), host.calls)
    }

    @Test fun everyActionAndSelectionIsBlockedWhenBusyOrInactive() {
        val ready = dialogState().copy(details = "diagnostics", canCopyDiagnostics = true)
        listOf(ready.copy(busy = true), ready.copy(pageActive = false)).forEach { state ->
            val host = Host(state)
            SharedComparisonPrimary.entries.forEach(host.interactions::performPrimary)
            SharedProgressChoice.entries.forEach(host.interactions::selectProgress)
            host.interactions.back()
            host.interactions.selectRemote("snapshot-a")
            host.interactions.copyDiagnostics()
            host.interactions.confirm(7)
            host.interactions.previewMerge(7)
            host.interactions.cancel(7)
            assertTrue(host.calls.isEmpty())
        }
    }

    @Test fun missingConfigurationBlocksOperationsButNotBack() {
        OnboardingKind.entries.forEach { kind ->
            val host = Host(observed(kind).copy(configured = false))
            SharedComparisonPrimary.entries.forEach(host.interactions::performPrimary)
            SharedProgressChoice.entries.forEach(host.interactions::selectProgress)
            assertTrue(host.calls.isEmpty())
            if (host.state.showsBack) { host.interactions.back(); assertEquals(listOf("back"), host.calls) }
        }
    }

    @Test fun retainedPrimaryCallbackRejectsChangedPrimaryAndUsesCurrentProof() {
        val host = Host(observed(OnboardingKind.IDENTICAL).copy(canCompleteWithoutTransfer = true))
        val finish = { host.interactions.performPrimary(SharedComparisonPrimary.FINISH) }
        host.state = host.state.copy(cloudVerified = false)
        finish()
        assertTrue(host.calls.isEmpty())
        host.state = host.state.copy(cloudVerified = true, canCompleteWithoutTransfer = false)
        finish()
        assertTrue(host.calls.isEmpty())
    }

    @Test fun synchronousHostBusyBoundarySuppressesDoubleClick() {
        val host = Host(observed(OnboardingKind.IDENTICAL).copy(canCompleteWithoutTransfer = true))
        host.markBusyOnOperation = true
        repeat(2) { host.interactions.performPrimary(SharedComparisonPrimary.FINISH) }
        assertEquals(listOf("finish:CONFIRM"), host.calls)
        host.state = host.state.copy(busy = false, cloudVerified = false)
        host.interactions.performPrimary(SharedComparisonPrimary.FINISH)
        assertEquals(listOf("finish:CONFIRM"), host.calls)
    }

    @Test fun conflictDisablesUnderlyingPageActionsAndRequiresCurrentSelectedIdForOverwrite() {
        val host = Host(dialogState().copy(selectedId = null))
        assertFalse(host.state.primaryEnabled)
        host.interactions.back()
        host.interactions.performPrimary(SharedComparisonPrimary.HANDLE_CONFLICT)
        host.interactions.confirm(7)
        assertTrue(host.calls.isEmpty())
        host.state = host.state.copy(selectedId = "removed")
        host.interactions.confirm(7)
        assertTrue(host.calls.isEmpty())
        host.state = host.state.copy(selectedId = "snapshot-a")
        host.interactions.confirm(7)
        assertEquals(listOf("overwrite:7"), host.calls)
    }

    @Test fun multiheadSelectionInvalidatesOldTokenAndOnlyAcceptsRealCurrentIds() {
        val second = SharedComparisonRemoteChoice("snapshot-b", "另一设备", "已进行5场游戏")
        val host = Host(dialogState(OnboardingKind.BRANCHED).copy(remoteChoices = observed().remoteChoices + second,
            selectedId = null))
        val staleOverwrite = { host.interactions.confirm(7) }
        host.interactions.selectRemote("removed")
        assertTrue(host.calls.isEmpty())
        host.interactions.selectRemote("snapshot-b")
        staleOverwrite()
        host.interactions.previewMerge(7)
        host.interactions.cancel(7)
        assertEquals(listOf("select:snapshot-b"), host.calls)
        assertEquals("snapshot-b", host.state.selectedId)
        host.interactions.confirm(8)
        assertEquals(listOf("select:snapshot-b", "overwrite:8"), host.calls)
        host.state = host.state.copy(remoteChoices = emptyList())
        host.interactions.confirm(8)
        assertEquals(2, host.calls.size)
    }

    @Test fun mergePreviewDoesNotNeedSingleSelectedHeadButDoesNeedRealCapabilityAndWriteSupport() {
        val host = Host(dialogState(OnboardingKind.BRANCHED).copy(selectedId = null))
        host.interactions.previewMerge(7)
        assertEquals(listOf("preview:7"), host.calls)
        host.calls.clear()
        listOf(host.state.copy(restoreSupported = false), host.state.copy(cloudVerified = false),
            host.state.copy(confirmation = SharedComparisonConfirmation(7))).forEach { blocked ->
            host.state = blocked
            host.interactions.previewMerge(7)
        }
        assertTrue(host.calls.isEmpty())
        assertFalse(SharedComparisonConfirmation(1).canPreviewMerge)
        assertFalse(SharedComparisonConfirmation(1).canApplyMerge)
    }

    @Test fun mergeReviewOnlyAppliesCurrentRealPlanAndDoesNotReturnToOverwrite() {
        val host = Host(dialogState(OnboardingKind.BRANCHED).copy(selectedId = null,
            confirmation = SharedComparisonConfirmation(9, SharedComparisonConfirmationStage.MERGE_REVIEW,
                canPreviewMerge = true, canApplyMerge = true)))
        assertEquals("合并并上传", host.state.confirmationPresentation?.confirmLabel)
        assertNull(host.state.confirmationPresentation?.secondaryLabel)
        host.interactions.confirm(7)
        host.interactions.previewMerge(9)
        host.interactions.selectRemote("snapshot-a")
        assertTrue(host.calls.isEmpty())
        host.interactions.confirm(9)
        assertEquals(listOf("merge:9"), host.calls)
        host.state = host.state.copy(confirmation = host.state.confirmation?.copy(token = 10, canApplyMerge = false))
        host.interactions.confirm(9)
        host.interactions.confirm(10)
        assertEquals(listOf("merge:9"), host.calls)
    }

    @Test fun cancellationClearsTokenAndLateActionsCannotUseIt() {
        val host = Host(dialogState())
        host.interactions.cancel(7)
        host.interactions.confirm(7)
        host.interactions.previewMerge(7)
        host.interactions.cancel(7)
        assertEquals(listOf("cancel:7"), host.calls)
    }

    @Test fun diagnosticsAreOptionalAndDoNotChangeSinglePrimary() {
        val state = observed(OnboardingKind.IDENTICAL).copy(canCompleteWithoutTransfer = true,
            details = "diagnostics", canCopyDiagnostics = true)
        val host = Host(state)
        host.interactions.copyDiagnostics()
        assertEquals(listOf("copy"), host.calls)
        assertEquals(SharedComparisonPrimary.FINISH, state.primary)
        host.state = state.copy(details = "")
        host.interactions.copyDiagnostics()
        assertEquals(listOf("copy"), host.calls)
    }
}
