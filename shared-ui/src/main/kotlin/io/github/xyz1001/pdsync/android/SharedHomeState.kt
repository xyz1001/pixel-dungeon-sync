package io.github.xyz1001.pdsync.android

import pdsync.presentation.HistoryPresentation

/**
 * Host-owned facts and capabilities. Display names, times and status text are never evidence of
 * synchronization. Hosts retain their operation lock and revalidate the actual save before writing.
 */
data class SharedHomeState(
    val game: SharedGameIdentity?,
    val status: String,
    val pageActive: Boolean = false,
    val busy: Boolean = false,
    val canUpload: Boolean = false,
    val canRestore: Boolean = false,
    val canLaunch: Boolean = false,
    val pending: SharedHomeOperation = SharedHomeOperation.NONE,
    val restoreTarget: SharedHomeRestoreTarget? = null,
    val selectedSnapshotId: String? = null,
    val emptyTargetMessage: String = "暂无可恢复的云端存档",
    val history: HistoryPresentation? = null,
    val notice: String = "",
    val noticeError: Boolean = false,
    val details: String = "",
)

enum class SharedHomeOperation { NONE, UPLOAD, RESTORE, MERGE, LAUNCH }

/** Real selected/default snapshot metadata, independent of the visible history page. */
data class SharedHomeRestoreTarget(
    val snapshotId: String,
    val name: String,
    val source: String = "",
    val time: String = "",
    val notice: String = "",
    val noticeError: Boolean = false,
)

interface SharedHomeActions {
    fun settings()
    fun upload()
    fun restore()
    fun launch()
    fun selectSnapshot(snapshotId: String)
    /** Null identifies the real current local directory, as in SharedHistorySection. */
    fun rename(snapshotId: String?)
    fun changeHistoryPage(page: Int)
}

internal val SharedHomeState.canInteract: Boolean get() = pageActive && !busy
internal val SharedHomeState.uploadEnabled: Boolean get() = canInteract && canUpload
internal val SharedHomeState.restoreEnabled: Boolean get() = canInteract && canRestore
internal val SharedHomeState.launchEnabled: Boolean get() = canInteract && canLaunch
internal val SharedHomeState.targetTitle: String
    get() = if (selectedSnapshotId == null) "默认恢复最新云端存档" else "已选择存档"
internal val SharedHomeRestoreTarget.summary: String
    get() = listOf(name, source, time).filter(String::isNotBlank).joinToString(" · ")

/** Retained callbacks consult the latest capabilities and real IDs, including after navigation. */
internal class SharedHomeInteractions(
    private val state: () -> SharedHomeState,
    private val actions: () -> SharedHomeActions,
) {
    fun settings() { if (state().canInteract) actions().settings() }
    fun upload() { if (state().uploadEnabled) actions().upload() }
    fun restore() { if (state().restoreEnabled) actions().restore() }
    fun launch() { if (state().launchEnabled) actions().launch() }

    fun selectSnapshot(snapshotId: String) {
        val current = state()
        if (current.canInteract && current.history?.nodes?.any { it.selectable && it.snapshotId == snapshotId } == true) {
            actions().selectSnapshot(snapshotId)
        }
    }

    fun rename(snapshotId: String?) {
        val current = state()
        val valid = current.history?.nodes?.any {
            if (snapshotId == null) it.id == "local-current" else it.snapshotId == snapshotId
        } == true
        if (current.canInteract && valid) actions().rename(snapshotId)
    }

    fun changeHistoryPage(page: Int) {
        val current = state()
        val history = current.history ?: return
        if (current.canInteract && page in 0 until history.pages && page != history.page) {
            actions().changeHistoryPage(page)
        }
    }
}
