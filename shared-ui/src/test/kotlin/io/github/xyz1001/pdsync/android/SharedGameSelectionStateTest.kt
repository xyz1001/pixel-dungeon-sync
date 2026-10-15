package io.github.xyz1001.pdsync.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedGameSelectionStateTest {
    private val game = SharedGameIdentity("Real selected game", "4.0.2")
    private fun ready() = SharedGameSelectionState(game = game, selectionId = "/games/selected.jar",
        pageActive = true, canContinue = true,
        rows = listOf(SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_FILE, "选择游戏 JAR…")),
        candidates = listOf(SharedGameCandidate("/games/selected.jar", game, "/games/selected.jar")))
    private class Actions : SharedGameSelectionActions {
        val calls = mutableListOf<String>()
        override fun perform(control: SharedGameSelectionControl) { calls += control.name }
        override fun chooseCandidate(id: String) { calls += id }
        override fun next() { calls += "next" }
    }

    @Test fun selectionAndRealPrerequisitesAreBothRequired() {
        for (state in listOf(SharedGameSelectionState(), ready().copy(game = null),
            ready().copy(selectionId = ""), ready().copy(canContinue = false),
            ready().copy(busy = true), ready().copy(pageActive = false))) assertFalse(state.nextEnabled)
        assertTrue(ready().nextEnabled)
    }

    @Test fun onlyEnabledPlatformCapabilitiesAndKnownCandidatesDispatch() {
        val actions = Actions()
        var state = ready()
        val events = SharedGameSelectionInteractions({ state }, { actions })
        SharedGameSelectionControl.entries.forEach(events::perform)
        events.chooseCandidate("unknown")
        assertEquals(listOf("CHOOSE_FILE"), actions.calls)
        events.chooseCandidate("/games/selected.jar")
        assertEquals("/games/selected.jar", actions.calls.last())
        state = state.copy(rows = state.rows.map { it.copy(enabled = false) }, candidates = emptyList())
        events.perform(SharedGameSelectionControl.CHOOSE_FILE)
        events.chooseCandidate("/games/selected.jar")
        assertEquals(2, actions.calls.size)
    }

    @Test fun oneNextPerObservedSnapshotAndFailureAllowsRetry() {
        val actions = Actions()
        var state = ready()
        val events = SharedGameSelectionInteractions({ state }, { actions })
        events.next(); events.next()
        assertEquals(listOf("next"), actions.calls)
        state = state.copy(busy = true)
        events.next()
        assertEquals(1, actions.calls.size)
        state = state.copy(busy = false, notice = "游戏已变化，请重试", noticeError = true)
        events.next(); events.next()
        assertEquals(listOf("next", "next"), actions.calls)
    }

    @Test fun retainedCallbacksCannotActAfterBusyNavigationOrDisposal() {
        for (blocked in listOf(ready().copy(busy = true), ready().copy(pageActive = false))) {
            val actions = Actions()
            var state = ready()
            val events = SharedGameSelectionInteractions({ state }, { actions })
            state = blocked
            events.perform(SharedGameSelectionControl.CHOOSE_FILE); events.chooseCandidate("/games/selected.jar"); events.next()
            assertTrue(actions.calls.isEmpty())
        }
        val actions = Actions()
        val state = ready()
        val events = SharedGameSelectionInteractions({ state }, { actions })
        events.dispose()
        events.perform(SharedGameSelectionControl.CHOOSE_FILE); events.chooseCandidate("/games/selected.jar"); events.next()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun cancelAndBackDoNotMutatePlatformSelection() {
        val actions = Actions()
        var state = ready()
        val events = SharedGameSelectionInteractions({ state }, { actions })
        events.perform(SharedGameSelectionControl.CHOOSE_FILE)
        // Cancel means the platform reports the same selection; no reset or invented identity.
        assertEquals("/games/selected.jar", state.selectionId)
        state = state.copy(pageActive = false)
        events.next()
        state = state.copy(pageActive = true)
        events.next()
        assertEquals(listOf("CHOOSE_FILE", "next"), actions.calls)
        assertEquals(game, state.game)
    }

    @Test fun duplicateRowsAndCandidatesAreRejectedAndDiagnosticsStayExplicit() {
        val original = ready()
        assertFailsWith<IllegalArgumentException> { original.copy(rows = original.rows + original.rows) }
        assertFailsWith<IllegalArgumentException> { original.copy(candidates = original.candidates + original.candidates) }
        assertFalse(original.actionEnabled(SharedGameSelectionControl.COPY_DIAGNOSTICS))
        assertTrue(original.copy(canCopyDiagnostics = true).actionEnabled(SharedGameSelectionControl.COPY_DIAGNOSTICS))
    }
}
