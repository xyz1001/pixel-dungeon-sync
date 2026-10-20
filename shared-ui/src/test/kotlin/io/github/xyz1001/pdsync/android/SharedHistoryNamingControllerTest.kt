package io.github.xyz1001.pdsync.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Executes the controller used by Android without requiring an Activity or service. */
class SharedHistoryNamingControllerTest {
    @Test fun cancelAndReopenDoNotSaveAndStaleCallbacksCannotTargetTheNewDraft() {
        val controller = SharedHistoryNamingController()
        val calls = mutableListOf<String>()
        controller.open("snapshot-one", "旧名称", "", { calls += "one:$it" }, null)
        val old = assertNotNull(controller.naming)
        old.onNameChange("未保存")
        assertEquals("未保存", controller.naming!!.state.name)
        old.onDismiss()
        assertNull(controller.naming)
        controller.open(null, "本机", "", { calls += "current:$it" }, { calls += "identity" })
        val current = assertNotNull(controller.naming)
        old.onNameChange("过期修改"); old.onSave("过期名称"); old.onDismiss()
        assertEquals(current, controller.naming)
        current.onSave("新名称")
        current.onSave("重复保存")
        assertNull(controller.naming)
        assertEquals(listOf("current:新名称"), calls)
    }

    @Test fun replacedTargetAndClosedForegroundSessionRejectEveryRetainedAction() {
        val controller = SharedHistoryNamingController()
        val calls = mutableListOf<String>()
        controller.open(null, "本机", "", { calls += "save:$it" }, { calls += "identity" })
        val old = assertNotNull(controller.naming)
        controller.open("real-snapshot", "历史", "", { calls += "history:$it" }, null)
        old.onMaintenance!!.invoke()
        old.onSave("过期保存")
        assertEquals("real-snapshot", controller.naming!!.snapshotId)
        val history = assertNotNull(controller.naming)
        controller.close()
        old.onMaintenance.invoke()
        history.onNameChange("过期修改"); history.onSave("过期保存"); history.onDismiss()
        controller.open(null, "新页面", "", { calls += "reopened:$it" }, null)
        assertNull(controller.naming)
        assertEquals(emptyList(), calls)
    }

    @Test fun identityMaintenanceIsAnExplicitSingleActionAndNeverSavesTheName() {
        val controller = SharedHistoryNamingController()
        val calls = mutableListOf<String>()
        controller.open(null, "本机", "", { calls += "save:$it" }, { calls += "identity" })
        val draft = assertNotNull(controller.naming)
        draft.onNameChange("未保存的草稿")
        assertEquals(emptyList(), calls)
        draft.onMaintenance!!.invoke()
        draft.onMaintenance.invoke()
        draft.onSave("未保存的草稿")
        assertNull(controller.naming)
        assertEquals(listOf("identity"), calls)
    }
}
