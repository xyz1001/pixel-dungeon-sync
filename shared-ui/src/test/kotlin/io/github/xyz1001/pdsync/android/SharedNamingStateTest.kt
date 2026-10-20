package io.github.xyz1001.pdsync.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedNamingStateTest {
    @Test fun cancelLeavesPersistedCurrentNameAndHistoryAliasUntouched() {
        for (target in listOf("current", "snapshot-real")) {
            var persisted = "已保存的名称"
            var state: SharedNamingState? = SharedNamingState(4, persisted)
            val calls = mutableListOf<String>()
            val interactions = SharedNamingInteractions({ state }, { state = state!!.copy(name = it) },
                { persisted = it; calls += "save:$target" }, { state = null; calls += "cancel" })
            interactions.edit(4, "尚未保存的名称")
            assertEquals("尚未保存的名称", state!!.name)
            assertEquals("已保存的名称", persisted)
            interactions.dismiss(4)
            interactions.save(4)
            assertEquals("已保存的名称", persisted)
            assertEquals(listOf("cancel"), calls)
        }
    }

    @Test fun saveUsesLatestDraftAndAllowsBlankToRestoreDefaultName() {
        var state: SharedNamingState? = SharedNamingState(4, "原名称")
        val saved = mutableListOf<String>()
        val interactions = SharedNamingInteractions({ state }, { state = state!!.copy(name = it) },
            { saved += it }, { state = null })
        interactions.edit(4, "当前草稿")
        interactions.save(4)
        interactions.edit(4, "")
        interactions.save(4)
        assertEquals(listOf("当前草稿", ""), saved)
    }

    @Test fun draftLimitPreservesUnicodeCharactersAndLeavesPersistenceToTheHost() {
        var state = SharedNamingState(4, "")
        val saved = mutableListOf<String>()
        val interactions = SharedNamingInteractions({ state }, { state = state.copy(name = it) },
            { saved += it }, {})
        interactions.edit(4, "🙂".repeat(41))
        assertEquals("🙂".repeat(40), state.name)
        assertTrue(saved.isEmpty())
        interactions.edit(4, "  待保存  ")
        assertEquals("  待保存  ", state.name, "typing does not trim or persist a draft")
        interactions.save(4)
        assertEquals(listOf("  待保存  "), saved)
    }

    @Test fun identityMaintenanceIsExplicitAndRejectsStaleBusyAndDisposedCallbacks() {
        var state = SharedNamingState(4, "名称")
        val calls = mutableListOf<String>()
        val interactions = SharedNamingInteractions({ state }, {}, { calls += "save" },
            { calls += "dismiss" }, { calls += "identity" })
        interactions.maintenance(3)
        state = state.copy(busy = true)
        interactions.maintenance(4)
        assertTrue(calls.isEmpty())
        state = state.copy(busy = false)
        interactions.maintenance(4)
        assertEquals(listOf("identity"), calls)
        interactions.dispose()
        interactions.maintenance(4)
        assertEquals(listOf("identity"), calls)
    }

    @Test fun staleAndBusyNamingCallbacksCannotWriteOrDismissAnotherTarget() {
        var state: SharedNamingState? = SharedNamingState(4, "原名称")
        val calls = mutableListOf<String>()
        val interactions = SharedNamingInteractions({ state }, { calls += "edit" },
            { calls += "save" }, { calls += "cancel" })
        for (blocked in listOf(state!!.copy(token = 5), state!!.copy(busy = true), null)) {
            state = blocked
            interactions.edit(4, "过期草稿")
            interactions.save(4)
            interactions.dismiss(4)
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun disposedNamingCallbacksCannotReadOrChangeRetainedOrReplacementState() {
        var state = SharedNamingState(4, "仍被闭包保留的草稿")
        var stateReads = 0
        val calls = mutableListOf<String>()
        val interactions = SharedNamingInteractions({ stateReads++; state }, { calls += "edit" },
            { calls += "save" }, { calls += "cancel" })
        interactions.dispose()
        interactions.dispose() // Leaving the composition permanently invalidates this controller.
        repeat(2) {
            interactions.edit(4, "过期草稿")
            interactions.save(4)
            interactions.dismiss(4)
            // Reusing a presentation token is not authorization for an old UI callback.
            state = state.copy(name = "另一个目标的草稿")
        }
        assertEquals(0, stateReads)
        assertTrue(calls.isEmpty())
    }

    @Test fun confirmationKeepsHostPermissionsAndCannotTurnUnknownIntoMergePermission() {
        var state: SharedConfirmationState? = SharedConfirmationState(11, "如何处理冲突？", "", "覆盖本机")
        val calls = mutableListOf<String>()
        val interactions = SharedConfirmationInteractions({ state }, { calls += "overwrite:$it" },
            { calls += "merge:$it" }, { calls += "cancel:$it" })
        interactions.confirm(11); interactions.secondary(11)
        assertTrue(calls.isEmpty())
        state = state!!.copy(confirmEnabled = true, secondaryLabel = "合并", secondaryEnabled = false)
        interactions.secondary(11); interactions.confirm(10)
        assertTrue(calls.isEmpty())
        interactions.confirm(11)
        assertEquals(listOf("overwrite:11"), calls)
        state = state!!.copy(secondaryEnabled = true, busy = true)
        interactions.confirm(11); interactions.secondary(11); interactions.dismiss(11)
        assertEquals(listOf("overwrite:11"), calls)
        state = state!!.copy(busy = false)
        interactions.secondary(11); interactions.dismiss(11)
        assertEquals(listOf("overwrite:11", "merge:11", "cancel:11"), calls)
    }
}
