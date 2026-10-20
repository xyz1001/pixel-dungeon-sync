package io.github.xyz1001.pdsync.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pdsync.presentation.HistoryNode
import pdsync.presentation.HistoryPresentation

class SharedHomeStateTest {
    private val cloud = HistoryNode("commit-real", "snapshot-real", "已同步", "10-15 09:30", "", "", 0, 0, selectable = true)
    private val local = HistoryNode("local-current", null, "本机", "10-15 09:30", "", "", 1, 0)
    private fun ready() = SharedHomeState(SharedGameIdentity("Actual Game", "1.2"), "云端已连接",
        pageActive = true, canUpload = true, canRestore = true, canLaunch = true, canRefreshHistory = true,
        restoreTarget = SharedHomeRestoreTarget("snapshot-real", "已同步", "本机", "10-15 09:30"),
        history = HistoryPresentation(listOf(cloud, local), emptyList(), 0, 2, 1, 21, false))

    private class Host(var state: SharedHomeState) : SharedHomeActions {
        val calls = mutableListOf<String>()
        var lockOnOperation = false
        val interactions = SharedHomeInteractions({ state }, { this })
        private fun operation(name: String) { calls += name; if (lockOnOperation) state = state.copy(busy = true) }
        override fun settings() { calls += "settings" }
        override fun appreciate() { calls += "appreciate" }
        override fun upload() = operation("upload")
        override fun restore() = operation("restore")
        override fun launch() = operation("launch")
        override fun selectSnapshot(snapshotId: String) { calls += "select:$snapshotId" }
        override fun rename(snapshotId: String?) { calls += "rename:$snapshotId" }
        override fun changeHistoryPage(page: Int) { calls += "page:$page" }
        override fun refreshHistory() = operation("refresh")
        fun all() {
            interactions.settings(); interactions.appreciate(); interactions.upload(); interactions.restore(); interactions.launch()
            interactions.selectSnapshot("snapshot-real"); interactions.rename("snapshot-real")
            interactions.rename(null); interactions.changeHistoryPage(1); interactions.refreshHistory()
        }
    }

    @Test fun hostNoticesAreNeverInterpretedAsBusinessState() {
        assertEquals("已读取云端记录。", ready().copy(notice = "已读取云端记录。").visibleNotice)
        assertEquals("已读取云端记录。\n恢复失败，请重试。", ready().copy(notice = "已读取云端记录。\n恢复失败，请重试。").visibleNotice)
        assertEquals("已读取云端记录。", ready().copy(notice = "已读取云端记录。", noticeError = true).visibleNotice)
        assertEquals("此次未发现云端记录。", ready().copy(notice = "此次未发现云端记录。").visibleNotice)
    }

    @Test fun expiryHasOneStatusLocationAndPreservesOtherFeedback() {
        val expired = pdsync.i18n.CoreSessionMessages.SERVICE_EXPIRED.text()
        val state = ready().copy(status = expired, statusKind = SharedHomeStatusKind.EXPIRED,
            canAppreciate = true, notice = "$expired\n恢复失败，请重试。\n$expired")
        assertEquals("恢复失败，请重试。", state.visibleNotice)
        assertEquals("", state.copy(notice = "$expired\n$expired").visibleNotice)
        val embedded = "本次反馈：$expired，稍后再试。"
        assertEquals(embedded, state.copy(notice = embedded).visibleNotice)
        assertEquals("", ready().copy(notice = expired).visibleNotice, "stale feedback cannot invent an expired status")
    }

    @Test fun appreciationRequiresTypedExpiryAndCurrentIdentityAndRechecksRetainedCallbacks() {
        val expired = ready().copy(status = pdsync.i18n.CoreSessionMessages.SERVICE_EXPIRED.text(),
            statusKind = SharedHomeStatusKind.EXPIRED, canAppreciate = true)
        val host = Host(expired)
        val retained = host.interactions::appreciate
        for (blocked in listOf(expired.copy(canAppreciate = false), expired.copy(statusKind = SharedHomeStatusKind.WARNING),
            expired.copy(statusKind = SharedHomeStatusKind.NEUTRAL), expired.copy(busy = true),
            expired.copy(archiveLoading = true), expired.copy(pageActive = false))) {
            host.state = blocked
            retained()
            assertTrue(host.calls.isEmpty())
        }
        host.state = expired
        assertTrue(host.state.appreciationEnabled)
        retained()
        assertEquals(listOf("appreciate"), host.calls)
        host.state = expired.copy(pageActive = false)
        repeat(3) { retained() }
        assertEquals(listOf("appreciate"), host.calls, "navigation suppresses repeated clicks")
        host.state = expired.copy(canAppreciate = false, statusKind = SharedHomeStatusKind.SYNCED)
        retained()
        assertFalse(host.state.showAppreciation, "renewal removes the entry")
        assertEquals(listOf("appreciate"), host.calls)
    }

    @Test fun capabilitiesFailClosedAndDisplayTextNeverProvesSynchronization() {
        val state = SharedHomeState(SharedGameIdentity("Game"), "已同步")
        assertFalse(state.uploadEnabled); assertFalse(state.restoreEnabled); assertFalse(state.launchEnabled)
        assertFalse(state.canRefreshHistory); assertFalse(state.refreshHistoryEnabled)
        val host = Host(ready().copy(status = "已同步", canUpload = false, canRestore = false, canLaunch = false))
        host.interactions.upload(); host.interactions.restore(); host.interactions.launch()
        assertTrue(host.calls.isEmpty())
        host.state = host.state.copy(status = "已同步", canUpload = true, canRestore = true, canLaunch = true)
        host.interactions.upload(); host.interactions.restore(); host.interactions.launch()
        assertEquals(listOf("upload", "restore", "launch"), host.calls)
    }

    @Test fun busyAndInactiveBlockEveryHostActionAndRetainedCallbacks() {
        for (blocked in listOf(ready().copy(busy = true), ready().copy(archiveLoading = true), ready().copy(pageActive = false))) {
            val host = Host(ready())
            val delayed = host.interactions::restore
            host.state = blocked
            host.all(); delayed()
            assertTrue(host.calls.isEmpty())
        }
    }

    @Test fun allActionsUseOnlyRealSnapshotIdsAndInRangeHistoryPages() {
        val host = Host(ready())
        host.all()
        assertEquals(listOf("settings", "upload", "restore", "launch", "select:snapshot-real",
            "rename:snapshot-real", "rename:null", "page:1", "refresh"), host.calls)
        host.calls.clear()
        host.interactions.selectSnapshot("commit-real")
        host.interactions.selectSnapshot("removed-snapshot")
        host.interactions.rename("commit-real")
        host.interactions.rename("removed-snapshot")
        listOf(-1, 0, 2).forEach(host.interactions::changeHistoryPage)
        assertTrue(host.calls.isEmpty(), "commit/chart IDs must not be sent as snapshot IDs")
        host.state = host.state.copy(history = host.state.history!!.copy(nodes = listOf(cloud.copy(selectable = false))))
        host.interactions.selectSnapshot("snapshot-real"); host.interactions.rename(null)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun latestCapabilitiesAndHostSynchronousBusyBoundarySuppressRepeatedWrites() {
        val host = Host(ready())
        val retained = host.interactions::upload
        host.state = host.state.copy(canUpload = false)
        retained()
        assertTrue(host.calls.isEmpty())
        host.state = ready()
        host.lockOnOperation = true
        repeat(2) { retained() }
        host.interactions.restore(); host.interactions.launch()
        assertEquals(listOf("upload"), host.calls)
    }

    @Test fun refreshRechecksLatestCapabilityLoadingNavigationAndOperationLock() {
        val host = Host(ready())
        val retained = host.interactions::refreshHistory
        for (blocked in listOf(ready().copy(canRefreshHistory = false), ready().copy(busy = true),
            ready().copy(archiveLoading = true), ready().copy(pageActive = false))) {
            host.state = blocked
            repeat(3) { retained() }
            assertFalse(host.state.refreshHistoryEnabled)
            assertTrue(host.calls.isEmpty())
        }
        host.state = ready().copy(history = null, canUpload = false, canRestore = false, canLaunch = false)
        host.lockOnOperation = true
        repeat(3) { retained() }
        assertEquals(listOf("refresh"), host.calls, "refresh can retry an absent history, and the host lock suppresses repeats")
        host.state = ready().copy(pageActive = false)
        retained()
        assertEquals(listOf("refresh"), host.calls, "a retained callback cannot read after leaving home")
    }

    @Test fun retainedRefreshCallbackUsesTheCurrentHost() {
        val first = Host(ready())
        val second = Host(ready())
        var current = first
        val interactions = SharedHomeInteractions({ current.state }, { current })
        val retained = interactions::refreshHistory
        current = second
        retained()
        assertTrue(first.calls.isEmpty())
        assertEquals(listOf("refresh"), second.calls)
    }

    @Test fun restoreCapabilityDoesNotInferADefaultBranchFromVisibleHistory() {
        val host = Host(ready().copy(restoreTarget = null, selectedSnapshotId = null,
            emptyTargetMessage = "请选择云端存档"))
        host.interactions.restore()
        assertEquals(listOf("restore"), host.calls, "the host may open the real multi-head selector")
        assertEquals(null, host.state.restoreTarget)
        assertEquals(null, host.state.selectedSnapshotId)
    }

    @Test fun targetMetadataRemainsIndependentOfVisiblePagesAndMissingFieldsStayMissing() {
        val state = ready().copy(selectedSnapshotId = "snapshot-offscreen",
            restoreTarget = SharedHomeRestoreTarget("snapshot-offscreen", "真实名称", time = "时间未知"))
        assertEquals("已选择存档", state.targetTitle)
        assertEquals("真实名称 · 时间未知", state.restoreTarget!!.summary)
        assertEquals("snapshot-offscreen", state.copy(history = null).restoreTarget!!.snapshotId)
        assertEquals("默认恢复最新云端存档", state.copy(selectedSnapshotId = null).targetTitle)
    }

    @Test fun loadingAndEmptyHistoryDoNotInventSelectableOrRenameableNodes() {
        for (history in listOf(null, HistoryPresentation(emptyList(), emptyList(), 0, 1, 1, 0, true))) {
            val host = Host(ready().copy(history = history))
            host.interactions.selectSnapshot("snapshot-real"); host.interactions.rename(null)
            host.interactions.rename("snapshot-real"); host.interactions.changeHistoryPage(1)
            assertTrue(host.calls.isEmpty())
        }
    }
}
