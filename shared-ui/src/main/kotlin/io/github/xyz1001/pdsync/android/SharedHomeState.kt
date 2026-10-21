package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import pdsync.presentation.HistoryPresentation

/**
 * Host-owned facts and capabilities. Display names, times and status text are never evidence of
 * synchronization. Hosts retain their operation lock and revalidate the actual save before writing.
 */
data class SharedHomeState @JvmOverloads constructor(
    val game: SharedGameIdentity?,
    val status: String,
    val pageActive: Boolean = false,
    val busy: Boolean = false,
    /** Actual home archive reads only, independent of unrelated operations. */
    val archiveLoading: Boolean = false,
    val canUpload: Boolean = false,
    val canRestore: Boolean = false,
    val canLaunch: Boolean = false,
    val pending: SharedHomeOperation = SharedHomeOperation.NONE,
    val restoreTarget: SharedHomeRestoreTarget? = null,
    val selectedSnapshotId: String? = null,
    val emptyTargetMessage: String = SharedMessages.NO_CLOUD_RESTORE.text(),
    val history: HistoryPresentation? = null,
    val notice: String = "",
    val noticeError: Boolean = false,
    val details: String = "",
    val statusKind: SharedHomeStatusKind = SharedHomeStatusKind.NEUTRAL,
    /** Current authenticated official-account identity; this never grants sync permission. */
    val canAppreciate: Boolean = false,
    /** A current home history read is available; this never grants save-write permission. */
    val canRefreshHistory: Boolean = false,
)

enum class SharedHomeStatusKind { NEUTRAL, SYNCED, WARNING, EXPIRED, RUNNING, CONFLICT }

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
    /** Opens the existing appreciation page, without initiating a payment. */
    fun appreciate() {}
    fun upload()
    fun restore()
    fun launch()
    fun selectSnapshot(snapshotId: String)
    /** Null identifies the real current local directory, as in SharedHistorySection. */
    fun rename(snapshotId: String?)
    fun changeHistoryPage(page: Int)
    fun refreshHistory() {}
}

internal val SharedHomeState.navigationAllowed: Boolean get() = pageActive && !busy
internal val SharedHomeState.canInteract: Boolean get() = navigationAllowed && !archiveLoading
internal val SharedHomeState.uploadEnabled: Boolean get() = canInteract && canUpload
internal val SharedHomeState.restoreEnabled: Boolean get() = canInteract && canRestore
internal val SharedHomeState.launchEnabled: Boolean get() = canInteract && canLaunch
internal val SharedHomeState.refreshHistoryEnabled: Boolean get() = canInteract && canRefreshHistory
internal val SharedHomeState.showAppreciation: Boolean
    get() = statusKind == SharedHomeStatusKind.EXPIRED && canAppreciate
internal val SharedHomeState.appreciationEnabled: Boolean get() = navigationAllowed && showAppreciation
internal val SharedHomeState.targetTitle: String
    get() = if (selectedSnapshotId == null) SharedMessages.DEFAULT_RESTORE.text() else SharedMessages.SELECTED_SAVE_TITLE.text()
internal val SharedHomeRestoreTarget.summary: String
    get() = listOf(name, source, time).filter(String::isNotBlank).joinToString(" · ")

/** Retained callbacks consult the latest capabilities and real IDs, including after navigation. */
internal class SharedHomeInteractions(
    private val state: () -> SharedHomeState,
    private val actions: () -> SharedHomeActions,
) {
    fun settings() { if (state().navigationAllowed) actions().settings() }
    fun appreciate() { if (state().appreciationEnabled) actions().appreciate() }
    fun upload() { if (state().uploadEnabled) actions().upload() }
    fun restore() { if (state().restoreEnabled) actions().restore() }
    fun launch() { if (state().launchEnabled) actions().launch() }
    fun refreshHistory() { if (state().refreshHistoryEnabled) actions().refreshHistory() }

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

/** Expiry has one typed status location; remove only its standalone feedback lines. */
internal val SharedHomeState.visibleNotice: String
    get() = withoutHomeExpiryNotice(notice)

internal fun withoutHomeExpiryNotice(notice: String): String = TrustedEntitlementCache.withoutExpiryNotice(notice)
