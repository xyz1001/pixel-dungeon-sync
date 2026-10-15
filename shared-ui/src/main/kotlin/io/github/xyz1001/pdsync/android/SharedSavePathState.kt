package io.github.xyz1001.pdsync.android

import pdsync.SaveSummary
import pdsync.presentation.HistoryPresentation

enum class SharedSavePathReadStatus { UNREAD, READING, READY, FAILED, PREPARE_REQUIRED }

/** Values and capabilities supplied by the platform's existing save-access flow. */
data class SharedSavePathState(
    val path: String,
    val editable: Boolean,
    val summary: SaveSummary?,
    /** Only a hard operation, chooser or game initialization blocks Back and editing. */
    val busy: Boolean = false,
    val readStatus: SharedSavePathReadStatus = SharedSavePathReadStatus.UNREAD,
    val continueAfterRead: Boolean = false,
    val canRequestRead: Boolean = false,
    val notice: String = "",
    val details: String = "",
    val canContinue: Boolean = false,
    val canChooseDirectory: Boolean = false,
    val canUseDefault: Boolean = false,
    val canRetry: Boolean = true,
    val canInitializeGame: Boolean = false,
    val retryLabel: String = "重新检查存档",
    val canCopyDiagnostics: Boolean = false,
)

interface SharedSavePathActions {
    fun editPath(path: String)
    fun chooseDirectory()
    fun useDefault()
    fun retry()
    fun initializeGame()
    fun back()
    fun next()
    fun copyDiagnostics() {}
}

internal data class SharedSavePathSlot(val name: String, val time: String)

internal data class SharedSavePathSummary(
    val headline: String,
    val emptyMessage: String?,
    val slots: List<SharedSavePathSlot>,
)

/** A missing preview is not an empty directory; nullable play counts stay untouched. */
internal fun savePathSummary(summary: SaveSummary?): SharedSavePathSummary? = summary?.let {
    SharedSavePathSummary(
        headline = "${HistoryPresentation.plays(it.plays)} · 存档 ${it.slots.size} 个",
        emptyMessage = if (it.slots.isNotEmpty()) null
            else if (it.files > 0 || (it.plays ?: 0) > 0) "有记录，暂无可继续的存档。"
            else "暂无存档。",
        slots = it.slots.map { (slot, time) ->
            SharedSavePathSlot("存档 ${slot.removePrefix("game")}", HistoryPresentation.time(time))
        },
    )
}

internal enum class SharedSavePathControl {
    CHOOSE_DIRECTORY, USE_DEFAULT, RETRY, INITIALIZE_GAME, COPY_DIAGNOSTICS, BACK, NEXT,
}

internal fun SharedSavePathState.shows(control: SharedSavePathControl): Boolean = when (control) {
    SharedSavePathControl.CHOOSE_DIRECTORY -> canChooseDirectory
    SharedSavePathControl.USE_DEFAULT -> canUseDefault
    SharedSavePathControl.RETRY -> canRetry
    SharedSavePathControl.INITIALIZE_GAME -> canInitializeGame
    SharedSavePathControl.COPY_DIAGNOSTICS -> canCopyDiagnostics && details.isNotBlank()
    SharedSavePathControl.BACK, SharedSavePathControl.NEXT -> true
}

internal val SharedSavePathState.primaryLabel: String get() = when {
    continueAfterRead -> "等待检查…"
    readStatus == SharedSavePathReadStatus.FAILED -> "重试"
    else -> "下一步"
}

internal fun SharedSavePathState.enables(control: SharedSavePathControl): Boolean {
    if (busy || !shows(control)) return false
    return when (control) {
        SharedSavePathControl.NEXT -> !continueAfterRead && when (readStatus) {
            SharedSavePathReadStatus.READY, SharedSavePathReadStatus.PREPARE_REQUIRED -> canContinue
            SharedSavePathReadStatus.UNREAD, SharedSavePathReadStatus.READING -> canRequestRead
            SharedSavePathReadStatus.FAILED -> canRequestRead && canRetry
        }
        SharedSavePathControl.CHOOSE_DIRECTORY, SharedSavePathControl.INITIALIZE_GAME,
        SharedSavePathControl.RETRY -> readStatus != SharedSavePathReadStatus.READING
        else -> true
    }
}

/** The screen uses this same dispatch guard, including for callbacks retained across recomposition. */
internal class SharedSavePathInteractions(
    private val state: () -> SharedSavePathState,
    private val actions: () -> SharedSavePathActions,
) {
    fun editPath(path: String) {
        val current = state()
        if (current.editable && !current.busy) actions().editPath(path)
    }

    fun perform(control: SharedSavePathControl) {
        if (!state().enables(control)) return
        val current = actions()
        when (control) {
            SharedSavePathControl.CHOOSE_DIRECTORY -> current.chooseDirectory()
            SharedSavePathControl.USE_DEFAULT -> current.useDefault()
            SharedSavePathControl.RETRY -> current.retry()
            SharedSavePathControl.INITIALIZE_GAME -> current.initializeGame()
            SharedSavePathControl.COPY_DIAGNOSTICS -> current.copyDiagnostics()
            SharedSavePathControl.BACK -> current.back()
            SharedSavePathControl.NEXT -> current.next()
        }
    }
}
