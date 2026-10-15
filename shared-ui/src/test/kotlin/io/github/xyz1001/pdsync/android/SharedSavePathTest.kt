package io.github.xyz1001.pdsync.android

import pdsync.SaveSummary
import pdsync.presentation.HistoryPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedSavePathTest {
    private class Actions : SharedSavePathActions {
        val calls = mutableListOf<String>()
        override fun editPath(path: String) { calls += "edit:$path" }
        override fun chooseDirectory() { calls += "choose" }
        override fun useDefault() { calls += "default" }
        override fun retry() { calls += "retry" }
        override fun initializeGame() { calls += "initialize" }
        override fun back() { calls += "back" }
        override fun next() { calls += "next" }
        override fun copyDiagnostics() { calls += "copy" }
    }

    private fun allEnabled() = SharedSavePathState(
        path = "/saves", editable = true, summary = null,
        readStatus = SharedSavePathReadStatus.READY,
        canContinue = true, canChooseDirectory = true, canUseDefault = true,
        canRetry = true, canInitializeGame = true,
        canCopyDiagnostics = true, details = "检查结果",
    )

    @Test fun summaryShowsActualSlotsAndSharedLocalTimes() {
        val timestamp = "2026-10-14T12:34:00Z"
        val source = SaveSummary(linkedMapOf("game1" to timestamp, "game4" to null), 17, 9)
        val summary = savePathSummary(source)!!
        assertEquals("已进行17场游戏 · 存档 2 个", summary.headline)
        assertEquals(listOf(SharedSavePathSlot("存档 1", HistoryPresentation.time(timestamp)), SharedSavePathSlot("存档 4", "时间未知")), summary.slots)
        assertNull(summary.emptyMessage)
    }

    @Test fun missingPlayCountDisplaysZeroWithoutMutatingNullableSource() {
        val source = SaveSummary(mapOf("game2" to null), null, 3)
        val state = allEnabled().copy(summary = source)
        assertEquals("已进行0场游戏 · 存档 1 个", savePathSummary(state.summary)!!.headline)
        assertSame(source, state.summary)
        assertNull(source.plays)
    }

    @Test fun recordsWithoutSlotsRemainDistinctFromCompletelyEmptySave() {
        listOf(
            SaveSummary(emptyMap(), null, 1),
            SaveSummary(emptyMap(), 0, 1),
            SaveSummary(emptyMap(), 5, 0),
        ).forEach {
            assertEquals("有记录，暂无可继续的存档。", savePathSummary(it)!!.emptyMessage)
        }
        listOf(null, 0).forEach { count ->
            val summary = savePathSummary(SaveSummary(emptyMap(), count, 0))!!
            assertEquals("暂无存档。", summary.emptyMessage)
            assertEquals("已进行0场游戏 · 存档 0 个", summary.headline)
        }
    }

    @Test fun unreadPreviewNeverCreatesZeroSlotOrZeroPlaySummary() {
        assertNull(savePathSummary(null))
        assertNull(savePathSummary(allEnabled().copy(notice = "暂时无法读取存档，请重新检查。").summary))
    }

    @Test fun unsupportedCapabilitiesAreHiddenAndCannotDispatch() {
        val state = SharedSavePathState("/read-only", editable = false, summary = null, canRetry = false)
        val actions = Actions()
        val interactions = SharedSavePathInteractions({ state }, { actions })
        listOf(
            SharedSavePathControl.CHOOSE_DIRECTORY, SharedSavePathControl.USE_DEFAULT,
            SharedSavePathControl.RETRY, SharedSavePathControl.INITIALIZE_GAME,
            SharedSavePathControl.COPY_DIAGNOSTICS,
        ).forEach {
            assertFalse(state.shows(it))
            assertFalse(state.enables(it))
            interactions.perform(it)
        }
        interactions.editPath("/unexpected")
        interactions.perform(SharedSavePathControl.NEXT)
        assertTrue(actions.calls.isEmpty())
        assertTrue(state.shows(SharedSavePathControl.BACK))
        assertTrue(state.shows(SharedSavePathControl.NEXT))
        assertTrue(state.enables(SharedSavePathControl.BACK))
        assertFalse(state.enables(SharedSavePathControl.NEXT))
    }

    @Test fun busyKeepsSupportedControlsVisibleButDisablesAllExternalActions() {
        val state = allEnabled().copy(busy = true)
        val actions = Actions()
        val interactions = SharedSavePathInteractions({ state }, { actions })
        SharedSavePathControl.entries.forEach {
            assertTrue(state.shows(it))
            assertFalse(state.enables(it))
            interactions.perform(it)
        }
        interactions.editPath("/unexpected")
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun supportedInteractionsDispatchOnlyTheirCallerCallbacks() {
        val state = allEnabled()
        val actions = Actions()
        val interactions = SharedSavePathInteractions({ state }, { actions })
        assertTrue(actions.calls.isEmpty())
        interactions.editPath("/chosen saves")
        SharedSavePathControl.entries.forEach { interactions.perform(it) }
        assertEquals(listOf("edit:/chosen saves", "choose", "default", "retry", "initialize", "copy", "back", "next"), actions.calls)
        assertEquals("/saves", state.path)
        assertNull(state.summary)
    }

    @Test fun callbacksConsultLatestStateAndLatestPlatformActions() {
        var state = allEnabled()
        val oldActions = Actions()
        var actions = oldActions
        val interactions = SharedSavePathInteractions({ state }, { actions })
        state = state.copy(busy = true)
        interactions.perform(SharedSavePathControl.NEXT)
        interactions.editPath("/busy")
        state = state.copy(busy = false, canContinue = false, editable = false, canRetry = false)
        interactions.perform(SharedSavePathControl.NEXT)
        interactions.perform(SharedSavePathControl.RETRY)
        interactions.editPath("/read-only")
        assertTrue(oldActions.calls.isEmpty())
        val replacement = Actions()
        actions = replacement
        state = state.copy(canContinue = true)
        interactions.perform(SharedSavePathControl.NEXT)
        assertEquals(listOf("next"), replacement.calls)
        assertTrue(oldActions.calls.isEmpty())
    }

    @Test fun continuePermissionIsSuppliedByPlatformRatherThanInferredFromPreview() {
        val populated = SaveSummary(mapOf("game1" to null), 20, 4)
        assertFalse(allEnabled().copy(summary = populated, canContinue = false).enables(SharedSavePathControl.NEXT))
        assertTrue(allEnabled().copy(summary = null, canContinue = true).enables(SharedSavePathControl.NEXT))
    }

    @Test fun retryLabelAndPlatformDetailsRemainCallerProvided() {
        val state = SharedSavePathState(
            path = "游戏私有目录", editable = false, summary = null,
            notice = "暂时无法读取存档，请重新检查。", details = "检查结果", retryLabel = "重新检查状态",
        )
        assertEquals("重新检查状态", state.retryLabel)
        assertEquals("检查结果", state.details)
        assertTrue(state.enables(SharedSavePathControl.RETRY))
        assertFalse(state.enables(SharedSavePathControl.INITIALIZE_GAME))
    }

    @Test fun diagnosticCopyRequiresBothCapabilityAndAvailableDetails() {
        val actions = Actions()
        var state = allEnabled().copy(details = "")
        val interactions = SharedSavePathInteractions({ state }, { actions })
        assertFalse(state.shows(SharedSavePathControl.COPY_DIAGNOSTICS))
        interactions.perform(SharedSavePathControl.COPY_DIAGNOSTICS)
        state = state.copy(details = "检查结果", canCopyDiagnostics = false)
        assertFalse(state.shows(SharedSavePathControl.COPY_DIAGNOSTICS))
        interactions.perform(SharedSavePathControl.COPY_DIAGNOSTICS)
        assertTrue(actions.calls.isEmpty())
        state = state.copy(canCopyDiagnostics = true)
        interactions.perform(SharedSavePathControl.COPY_DIAGNOSTICS)
        assertEquals(listOf("copy"), actions.calls)
    }
    @Test fun readingAndQueuedReadKeepBackAndEditingAvailableWithoutAllowingAnotherChooser() {
        var state = allEnabled().copy(readStatus = SharedSavePathReadStatus.READING,
            canContinue = false, canRequestRead = true)
        val actions = Actions()
        val interactions = SharedSavePathInteractions({ state }, { actions })
        assertEquals("下一步", state.primaryLabel)
        assertTrue(state.enables(SharedSavePathControl.NEXT))
        assertFalse(state.enables(SharedSavePathControl.CHOOSE_DIRECTORY))
        assertFalse(state.enables(SharedSavePathControl.RETRY))
        assertFalse(state.enables(SharedSavePathControl.INITIALIZE_GAME))
        interactions.perform(SharedSavePathControl.NEXT)
        state = state.copy(continueAfterRead = true)
        assertEquals("等待检查…", state.primaryLabel)
        assertFalse(state.enables(SharedSavePathControl.NEXT))
        interactions.perform(SharedSavePathControl.NEXT)
        interactions.perform(SharedSavePathControl.CHOOSE_DIRECTORY)
        interactions.perform(SharedSavePathControl.BACK)
        interactions.editPath("/new")
        assertEquals(listOf("next", "back", "edit:/new"), actions.calls)
    }

    @Test fun allReadStatusesUseOnePrimaryActionWithExplicitPlatformReadiness() {
        val actions = Actions()
        var state = allEnabled().copy(canContinue = false, canRequestRead = true)
        val interactions = SharedSavePathInteractions({ state }, { actions })
        for (status in SharedSavePathReadStatus.entries) {
            state = state.copy(readStatus = status)
            assertEquals(status in setOf(SharedSavePathReadStatus.UNREAD, SharedSavePathReadStatus.READING,
                SharedSavePathReadStatus.FAILED), state.enables(SharedSavePathControl.NEXT))
        }
        state = state.copy(readStatus = SharedSavePathReadStatus.FAILED)
        assertEquals("重试", state.primaryLabel)
        interactions.perform(SharedSavePathControl.NEXT)
        assertEquals(listOf("next"), actions.calls, "adapter owns queued retry through the primary action")
        state = state.copy(canRetry = false)
        assertFalse(state.enables(SharedSavePathControl.NEXT))
        state = state.copy(readStatus = SharedSavePathReadStatus.PREPARE_REQUIRED, canContinue = true)
        assertTrue(state.enables(SharedSavePathControl.NEXT))
        assertNull(savePathSummary(state.summary), "safe absence does not fabricate an empty summary")
    }

}
