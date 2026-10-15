package io.github.xyz1001.pdsync.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedBranchChoiceStateTest {
    private fun ready() = SharedBranchChoiceState(Any(), listOf(
        SharedBranchChoice("real-a", "手机", "10-15 09:30"),
        SharedBranchChoice("real-b", "电脑", "10-14 20:00")),
        pageActive = true, connected = true, observationCurrent = true,
        overwriteSupported = true, mergeSupported = true)

    private class Host(initial: SharedBranchChoiceState) : SharedBranchChoiceActions {
        var state = initial
        val calls = mutableListOf<String>()
        val interactions = SharedBranchChoiceInteractions({ state }, { this })
        override fun select(snapshotId: String) { calls += "select:$snapshotId"; state = state.copy(selectedId = snapshotId) }
        override fun back() { calls += "back"; state = state.copy(pageActive = false) }
        override fun refresh() { calls += "refresh"; state = state.copy(busy = true) }
        override fun overwrite(snapshotId: String) { calls += "overwrite:$snapshotId"; state = state.copy(busy = true) }
        override fun merge() { calls += "merge"; state = state.copy(busy = true) }
        override fun preserveLocal() { calls += "preserve"; state = state.copy(busy = true) }
    }

    @Test fun compositionAndSelectionNeverWriteAndUnknownIdsNeverSelect() {
        val host = Host(ready())
        assertTrue(host.calls.isEmpty())
        assertNull(host.interactions.presentation)
        host.interactions.select("missing")
        assertTrue(host.calls.isEmpty())
        host.interactions.select("real-b")
        host.interactions.select("real-b")
        assertEquals(listOf("select:real-b"), host.calls)
        host.interactions.primary()
        assertNotNull(host.interactions.presentation)
        assertEquals(listOf("select:real-b"), host.calls)
    }

    @Test fun overwriteAndMergeRequireOneShotCurrentConfirmation() {
        val host = Host(ready().copy(selectedId = "real-b"))
        host.interactions.confirm(1)
        host.interactions.merge(1)
        assertTrue(host.calls.isEmpty())
        host.interactions.primary()
        val token = host.interactions.presentation!!.token
        host.interactions.confirm(token + 1)
        assertTrue(host.calls.isEmpty())
        host.interactions.confirm(token)
        host.interactions.confirm(token)
        host.interactions.merge(token)
        assertEquals(listOf("overwrite:real-b"), host.calls)
        val merge = Host(ready())
        merge.interactions.primary()
        val mergeToken = merge.interactions.presentation!!.token
        merge.interactions.merge(mergeToken)
        merge.interactions.merge(mergeToken)
        assertEquals(listOf("merge"), merge.calls)
    }

    @Test fun missingOrUnknownSelectionDisablesOnlyOverwriteWithoutChoosingAnId() {
        for (id in listOf(null, "removed")) {
            val host = Host(ready().copy(selectedId = id))
            host.interactions.primary()
            val dialog = host.interactions.presentation!!
            assertFalse(dialog.confirmEnabled)
            assertTrue(dialog.secondaryEnabled)
            host.interactions.confirm(dialog.token)
            assertTrue(host.calls.isEmpty())
            assertEquals(id, host.state.selectedId)
        }
    }

    @Test fun cancelAndBackDoNotWriteAndOldTokenCannotReturn() {
        val host = Host(ready().copy(selectedId = "real-a"))
        host.interactions.primary()
        val old = host.interactions.presentation!!.token
        host.interactions.back()
        host.interactions.select("real-b")
        assertTrue(host.calls.isEmpty(), "underlying page is inert during the dialog")
        host.interactions.cancel(old)
        assertNull(host.interactions.presentation)
        host.interactions.primary()
        val fresh = host.interactions.presentation!!.token
        assertTrue(fresh > old)
        host.interactions.confirm(old)
        host.interactions.cancel(fresh)
        host.interactions.back()
        host.interactions.confirm(fresh)
        host.interactions.primary()
        assertEquals(listOf("back"), host.calls)
    }

    @Test fun changedObservationSelectionCapabilitiesAndPageInvalidateRetainedDialog() {
        val changes: List<(SharedBranchChoiceState) -> SharedBranchChoiceState> = listOf(
            { it.copy(observation = Any()) }, { it.copy(selectedId = "real-b") },
            { it.copy(branches = it.branches.take(1)) }, { it.copy(connected = false) },
            { it.copy(observationCurrent = false) }, { it.copy(resolutionBlocked = true) },
            { it.copy(overwriteSupported = false) }, { it.copy(mergeSupported = false) },
            { it.copy(pageActive = false) }, { it.copy(game = SharedGameIdentity("另一个游戏")) },
        )
        for (change in changes) {
            val host = Host(ready().copy(selectedId = "real-a"))
            val original = host.state
            host.interactions.primary()
            val token = host.interactions.presentation!!.token
            host.state = change(host.state)
            assertNull(host.interactions.presentation)
            host.state = original
            host.interactions.confirm(token)
            host.interactions.merge(token)
            assertTrue(host.calls.isEmpty())
        }
    }

    @Test fun busyInactiveDisconnectedBlockedAndUnsupportedCannotResolve() {
        val base = ready().copy(selectedId = "real-a")
        for (state in listOf(base.copy(busy = true), base.copy(pageActive = false), base.copy(connected = false),
            base.copy(resolutionBlocked = true), base.copy(overwriteSupported = false, mergeSupported = false))) {
            val host = Host(state)
            host.interactions.primary()
            assertNull(host.interactions.presentation)
            assertTrue(host.calls.isEmpty())
        }
        val host = Host(base)
        host.interactions.primary()
        val token = host.interactions.presentation!!.token
        host.state = base.copy(busy = true)
        host.interactions.confirm(token); host.interactions.merge(token); host.interactions.cancel(token)
        host.interactions.back(); host.interactions.select("real-b")
        assertTrue(host.calls.isEmpty())
        assertNotNull(host.interactions.presentation, "busy locks the current confirmation")
    }

    @Test fun invalidObservationRefreshesAndPendingUploadRemainsAnIndependentRecoveryAction() {
        val base = ready()
        for (state in listOf(base.copy(observation = null), base.copy(observationCurrent = false),
            base.copy(branches = emptyList()), base.copy(branches = base.branches + base.branches.first()))) {
            val host = Host(state)
            host.interactions.select("real-a")
            host.interactions.primary(); host.interactions.primary()
            assertEquals(listOf("refresh"), host.calls)
        }
        val host = Host(base.copy(resolutionBlocked = true, canPreserveLocal = true))
        host.interactions.primary()
        assertNull(host.interactions.presentation)
        host.interactions.preserveLocal(); host.interactions.preserveLocal()
        assertEquals(listOf("preserve"), host.calls)
    }

    @Test fun disposedCallbacksAndUnsupportedPreservationNeverDispatch() {
        val host = Host(ready().copy(selectedId = "real-a"))
        host.interactions.preserveLocal()
        host.interactions.primary()
        val token = host.interactions.presentation!!.token
        host.interactions.dispose()
        host.interactions.confirm(token); host.interactions.merge(token); host.interactions.cancel(token)
        host.interactions.back(); host.interactions.select("real-b"); host.interactions.primary()
        assertTrue(host.calls.isEmpty())
    }
}
