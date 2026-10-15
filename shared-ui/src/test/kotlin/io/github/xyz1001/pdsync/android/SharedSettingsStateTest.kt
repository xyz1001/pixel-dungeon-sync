package io.github.xyz1001.pdsync.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedSettingsStateTest {
    private fun row(key: SharedSettingsKey = SharedSettingsKey.EXPORT_LOGS) =
        SharedSettingsRow(key, "日志", "尚未导出", "导出", actionEnabled = true)
    private fun ready(rows: List<SharedSettingsRow> = listOf(row())) =
        SharedSettingsState(SharedGameIdentity("真实游戏", "3.3.8"), rows, pageActive = true)

    private class Actions : SharedSettingsActions {
        val calls = mutableListOf<String>()
        var onPerform: () -> Unit = {}
        override fun perform(key: SharedSettingsKey) { calls += key.name; onPerform() }
        override fun back() { calls += "back" }
    }

    @Test fun noRowsOrLabelsInventActionsAndCapabilitiesStartDisabled() {
        val empty = SharedSettingsState(null, emptyList())
        assertFalse(empty.backEnabled)
        SharedSettingsKey.entries.forEach { assertFalse(empty.actionEnabled(it)) }
        for (label in listOf(null, "", " ")) {
            val state = ready(listOf(row().copy(actionLabel = label)))
            assertFalse(state.actionEnabled(SharedSettingsKey.EXPORT_LOGS))
        }
        val passive = SharedSettingsRow(SharedSettingsKey.SAVE_PATH, "存档位置", "/real/path", "修改")
        assertFalse(ready(listOf(passive)).actionEnabled(SharedSettingsKey.SAVE_PATH))
    }

    @Test fun onlySuppliedKeysReachExistingHostCapabilities() {
        val actions = Actions()
        val rows = SharedSettingsKey.entries.map { row(it) }
        var state = ready(rows)
        val interactions = SharedSettingsInteractions({ state }, { actions })
        SharedSettingsKey.entries.forEach(interactions::perform)
        interactions.back()
        assertEquals(SharedSettingsKey.entries.map { it.name } + "back", actions.calls)
        actions.calls.clear()
        state = state.copy(rows = listOf(row(SharedSettingsKey.GAME)))
        SharedSettingsKey.entries.forEach(interactions::perform)
        assertEquals(listOf("GAME"), actions.calls, "omitted platform capabilities never dispatch")
    }

    @Test fun retainedActionsUseLatestAvailabilityAndCallbackObject() {
        var state = ready()
        val original = Actions()
        var actions = original
        val interactions = SharedSettingsInteractions({ state }, { actions })
        val retained = { interactions.perform(SharedSettingsKey.EXPORT_LOGS) }
        state = state.copy(rows = listOf(row().copy(actionEnabled = false)))
        retained()
        state = state.copy(rows = emptyList())
        retained()
        assertTrue(original.calls.isEmpty())
        actions = Actions()
        state = ready()
        retained()
        assertTrue(original.calls.isEmpty())
        assertEquals(listOf("EXPORT_LOGS"), actions.calls)
    }

    @Test fun busyInactiveAndDisposedPagesRejectAllRetainedCallbacks() {
        for (blocked in listOf(ready().copy(busy = true), ready().copy(pageActive = false))) {
            val actions = Actions()
            var state = ready()
            val interactions = SharedSettingsInteractions({ state }, { actions })
            val retained = { interactions.perform(SharedSettingsKey.EXPORT_LOGS) }
            val back = interactions::back
            state = blocked
            retained(); back()
            assertTrue(actions.calls.isEmpty())
        }
        val actions = Actions()
        val interactions = SharedSettingsInteractions({ ready() }, { actions })
        val retained = { interactions.perform(SharedSettingsKey.EXPORT_LOGS) }
        interactions.dispose()
        retained(); interactions.back()
        assertTrue(actions.calls.isEmpty(), "removal from composition blocks even an old active snapshot")
    }

    @Test fun returnAvailabilityIsIndependentAndHostBusyBoundaryStopsDoubleDispatch() {
        var state = ready().copy(canReturn = false)
        val actions = Actions()
        val interactions = SharedSettingsInteractions({ state }, { actions })
        interactions.back()
        assertTrue(actions.calls.isEmpty())
        actions.onPerform = { state = state.copy(busy = true) }
        repeat(2) { interactions.perform(SharedSettingsKey.EXPORT_LOGS) }
        interactions.back()
        assertEquals(listOf("EXPORT_LOGS"), actions.calls)
    }

    @Test fun valuesStayUnmodifiedAndMaintenanceIsOneOptionalHostEntry() {
        val path = "/real/game data/存档/" + "long-directory/".repeat(20)
        val rows = listOf(
            SharedSettingsRow(SharedSettingsKey.SAVE_PATH, "存档位置", path),
            SharedSettingsRow(SharedSettingsKey.MAINTENANCE, "恢复与维护", "有未完成恢复", "检查", true),
        )
        val state = ready(rows)
        assertEquals(path, state.rows.first().currentValue)
        assertEquals(2, state.rows.size)
        assertFalse(state.actionEnabled(SharedSettingsKey.SAVE_PATH))
        assertTrue(state.actionEnabled(SharedSettingsKey.MAINTENANCE))
        assertFailsWith<IllegalArgumentException> { ready(listOf(row(), row())) }
    }
}
