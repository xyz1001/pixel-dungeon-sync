package io.github.xyz1001.pdsync.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedConfirmationStateTest {
    private fun ready() = SharedConfirmationState(12, "确认", "操作说明", "覆盖本机",
        confirmEnabled = true, secondaryLabel = "合并", secondaryEnabled = true)

    @Test fun retainedCallbacksRejectOldTokensAcrossNewResultAndDismissal() {
        var state: SharedConfirmationState? = ready()
        val calls = mutableListOf<String>()
        val interactions = SharedConfirmationInteractions({ state }, { calls += "confirm:$it" },
            { calls += "secondary:$it" }, { calls += "dismiss:$it" })
        state = state?.copy(token = 13)
        interactions.confirm(12); interactions.secondary(12); interactions.dismiss(12)
        assertTrue(calls.isEmpty())
        interactions.confirm(13); interactions.secondary(13); interactions.dismiss(13)
        assertEquals(listOf("confirm:13", "secondary:13", "dismiss:13"), calls)
        state = null
        interactions.confirm(13); interactions.secondary(13); interactions.dismiss(13)
        assertEquals(3, calls.size)
    }

    @Test fun everyCallbackUsesCurrentCapabilitiesAndBusyState() {
        var state = ready().copy(busy = true)
        val calls = mutableListOf<String>()
        val interactions = SharedConfirmationInteractions({ state }, { calls += "confirm" },
            { calls += "secondary" }, { calls += "dismiss" })
        interactions.confirm(12); interactions.secondary(12); interactions.dismiss(12)
        assertTrue(calls.isEmpty())
        state = ready().copy(confirmEnabled = false, secondaryEnabled = false)
        interactions.confirm(12); interactions.secondary(12); interactions.dismiss(12)
        assertEquals(listOf("dismiss"), calls)
        state = ready().copy(secondaryLabel = null)
        interactions.secondary(12)
        assertEquals(listOf("dismiss"), calls)
        assertFalse(SharedConfirmationState(1, "", "", "").confirmEnabled)
        assertFalse(SharedConfirmationState(1, "", "", "").secondaryEnabled)
    }

    @Test fun synchronousBusyBoundaryPreventsDuplicateConfirm() {
        var state = ready()
        var calls = 0
        val interactions = SharedConfirmationInteractions({ state }, { calls++; state = state.copy(busy = true) }, {}, {})
        repeat(2) { interactions.confirm(12) }
        assertEquals(1, calls)
    }
}
