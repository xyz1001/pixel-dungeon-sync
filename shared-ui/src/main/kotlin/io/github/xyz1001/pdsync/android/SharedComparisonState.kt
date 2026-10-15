package io.github.xyz1001.pdsync.android

import pdsync.NoviceCopy
import pdsync.OnboardingKind

/** Display values only. The host retains and revalidates the core observation and transaction. */
data class SharedComparisonState(
    val kind: OnboardingKind?,
    val cloudVerified: Boolean = false,
    val needsRestoreRecovery: Boolean = false,
    val retryablePending: Boolean = false,
    val canCompleteWithoutTransfer: Boolean = false,
    val canAdoptRemote: Boolean = false,
    val canKeepLocalProgress: Boolean = false,
    val restoreSupported: Boolean = false,
    val pageActive: Boolean = false,
    val configured: Boolean = false,
    val busy: Boolean = false,
    val game: SharedGameIdentity? = null,
    val localLabel: String = "本机",
    val localDetails: String = "",
    val localFileCount: Int? = null,
    val remoteChoices: List<SharedComparisonRemoteChoice> = emptyList(),
    val selectedProgress: SharedProgressChoice? = null,
    val selectedId: String? = null,
    val notice: String = "",
    val details: String = "",
    val canCopyDiagnostics: Boolean = false,
    val confirmation: SharedComparisonConfirmation? = null,
    val feedbackError: Boolean = false,
)

data class SharedComparisonRemoteChoice(val snapshotId: String, val label: String, val summary: String)

enum class SharedProgressChoice { LOCAL, CLOUD }
enum class SharedComparisonPrimary { FINISH, HANDLE_CONFLICT, REFRESH, RETRY_UPLOAD, OPEN_RECOVERY }
enum class SharedComparisonCompletion { NONE, EMPTY, CONFIRM, ADOPT, KEEP_LOCAL, UPLOAD_LOCAL, RESTORE_REMOTE }
enum class SharedComparisonConfirmationStage { CONFLICT, MERGE_REVIEW }

/** A new selection, observation, preview or cancellation must change/clear the host's token. */
data class SharedComparisonConfirmation(
    val token: Long,
    val stage: SharedComparisonConfirmationStage = SharedComparisonConfirmationStage.CONFLICT,
    val message: String = NoviceCopy.restoreWarning,
    val details: String = "",
    val canPreviewMerge: Boolean = false,
    val canApplyMerge: Boolean = false,
)

interface SharedComparisonActions {
    fun back()
    fun refresh()
    fun selectProgress(choice: SharedProgressChoice)
    fun selectRemote(snapshotId: String)
    fun finish(mode: SharedComparisonCompletion)
    fun handleConflict()
    fun upload()
    fun openRecovery()
    fun copyDiagnostics() {}
    fun confirmConflict(token: Long) {}
    fun previewMerge(token: Long) {}
    fun confirmMerge(token: Long) {}
    fun cancelConflict(token: Long) {}
}

internal val SharedComparisonState.hasObservation: Boolean
    get() = cloudVerified && kind != null && kind != OnboardingKind.BLOCKED

internal val SharedComparisonState.hasUsableObservation: Boolean
    get() = hasObservation && !needsRestoreRecovery && !retryablePending

internal val SharedComparisonState.hasSelectedRemote: Boolean
    get() = selectedId != null && remoteChoices.any { it.snapshotId == selectedId }

/** Capabilities come from the core; display counts, names, times and text never prove equality. */
val SharedComparisonState.completion: SharedComparisonCompletion
    get() = when {
        !hasUsableObservation -> SharedComparisonCompletion.NONE
        kind == OnboardingKind.BOTH_EMPTY -> SharedComparisonCompletion.EMPTY
        kind == OnboardingKind.IDENTICAL && canCompleteWithoutTransfer -> SharedComparisonCompletion.CONFIRM
        kind == OnboardingKind.IDENTICAL && canAdoptRemote -> SharedComparisonCompletion.ADOPT
        kind == OnboardingKind.LOCAL_ONLY && selectedProgress == SharedProgressChoice.LOCAL ->
            SharedComparisonCompletion.UPLOAD_LOCAL
        kind == OnboardingKind.REMOTE_ONLY && selectedProgress == SharedProgressChoice.CLOUD &&
            hasSelectedRemote && restoreSupported -> SharedComparisonCompletion.RESTORE_REMOTE
        kind == OnboardingKind.DIFFERENT && canKeepLocalProgress && selectedProgress == SharedProgressChoice.LOCAL ->
            SharedComparisonCompletion.KEEP_LOCAL
        else -> SharedComparisonCompletion.NONE
    }

val SharedComparisonState.primary: SharedComparisonPrimary
    get() = when {
        needsRestoreRecovery -> SharedComparisonPrimary.OPEN_RECOVERY
        retryablePending -> SharedComparisonPrimary.RETRY_UPLOAD
        !hasObservation -> SharedComparisonPrimary.REFRESH
        kind == OnboardingKind.IDENTICAL && completion == SharedComparisonCompletion.NONE -> SharedComparisonPrimary.REFRESH
        kind == OnboardingKind.BOTH_EMPTY || kind == OnboardingKind.IDENTICAL ||
            kind == OnboardingKind.LOCAL_ONLY || kind == OnboardingKind.REMOTE_ONLY -> SharedComparisonPrimary.FINISH
        completion == SharedComparisonCompletion.KEEP_LOCAL -> SharedComparisonPrimary.FINISH
        else -> SharedComparisonPrimary.HANDLE_CONFLICT
    }

internal val SharedComparisonState.isSimpleCompletion: Boolean
    get() = completion in setOf(SharedComparisonCompletion.EMPTY, SharedComparisonCompletion.CONFIRM,
        SharedComparisonCompletion.ADOPT)

internal val SharedComparisonState.showsBack: Boolean
    get() = !(isSimpleCompletion && kind == OnboardingKind.IDENTICAL)

internal val SharedComparisonState.activeConfirmation: SharedComparisonConfirmation?
    get() = confirmation.takeIf { primary == SharedComparisonPrimary.HANDLE_CONFLICT && hasUsableObservation }

internal val SharedComparisonState.canInteract: Boolean
    get() = pageActive && !busy

internal val SharedComparisonState.primaryEnabled: Boolean
    get() = canInteract && configured && activeConfirmation == null && when (primary) {
        SharedComparisonPrimary.FINISH -> completion != SharedComparisonCompletion.NONE
        SharedComparisonPrimary.HANDLE_CONFLICT -> hasUsableObservation
        SharedComparisonPrimary.REFRESH, SharedComparisonPrimary.OPEN_RECOVERY -> true
        SharedComparisonPrimary.RETRY_UPLOAD -> !needsRestoreRecovery
    }

internal fun SharedComparisonState.offers(choice: SharedProgressChoice): Boolean = hasUsableObservation && when (choice) {
    SharedProgressChoice.LOCAL -> kind == OnboardingKind.LOCAL_ONLY ||
        (kind == OnboardingKind.DIFFERENT && canKeepLocalProgress)
    SharedProgressChoice.CLOUD -> kind == OnboardingKind.REMOTE_ONLY ||
        (kind == OnboardingKind.DIFFERENT && canKeepLocalProgress)
}

internal val SharedComparisonState.headline: String
    get() = when {
        needsRestoreRecovery -> "上次恢复尚未完成，请先检查。"
        retryablePending -> "上次上传尚未完成，请重试上传。"
        !hasObservation -> if (busy) "正在检查存档…" else "尚未完成检查，请重试。"
        completion == SharedComparisonCompletion.EMPTY -> if (feedbackError) "配置尚未完成" else "配置完成"
        isSimpleCompletion && kind == OnboardingKind.IDENTICAL -> "两边进度相同"
        kind == OnboardingKind.IDENTICAL -> "尚未完成检查，请重试。"
        kind == OnboardingKind.LOCAL_ONLY -> "本机有存档"
        kind == OnboardingKind.REMOTE_ONLY -> "云端有存档"
        kind == OnboardingKind.DIFFERENT && canKeepLocalProgress -> "选择要继续的进度"
        else -> "本机与云端进度不同"
    }

internal val SharedComparisonState.primaryLabel: String
    get() = when (primary) {
        SharedComparisonPrimary.FINISH -> "完成"
        SharedComparisonPrimary.HANDLE_CONFLICT -> "处理冲突"
        SharedComparisonPrimary.REFRESH -> "重新检查"
        SharedComparisonPrimary.RETRY_UPLOAD -> "重试上传"
        SharedComparisonPrimary.OPEN_RECOVERY -> "检查恢复"
    }

internal val SharedComparisonState.confirmationPresentation: SharedConfirmationState?
    get() = activeConfirmation?.let { confirmation ->
        val review = confirmation.stage == SharedComparisonConfirmationStage.MERGE_REVIEW
        val writesAllowed = canInteract && configured && restoreSupported && hasUsableObservation
        SharedConfirmationState(
            token = confirmation.token,
            title = if (review) "确认合并" else "如何处理冲突？",
            message = confirmation.message,
            confirmLabel = if (review) "合并并上传" else "覆盖本机",
            confirmEnabled = writesAllowed && if (review) confirmation.canApplyMerge else hasSelectedRemote,
            secondaryLabel = if (!review && confirmation.canPreviewMerge) "合并" else null,
            secondaryEnabled = writesAllowed && confirmation.canPreviewMerge,
            busy = busy || !pageActive,
            details = confirmation.details,
        )
    }

/**
 * Retained callbacks consult current state. Hosts synchronously acquire their busy/operation lock
 * before work, revalidate core proof, and invalidate confirmation tokens when evidence changes.
 */
internal class SharedComparisonInteractions(
    private val state: () -> SharedComparisonState,
    private val actions: () -> SharedComparisonActions,
) {
    fun back() {
        val current = state()
        if (current.canInteract && current.showsBack && current.activeConfirmation == null) actions().back()
    }

    fun selectProgress(choice: SharedProgressChoice) {
        val current = state()
        if (current.canInteract && current.configured && current.activeConfirmation == null && current.offers(choice)) {
            actions().selectProgress(choice)
        }
    }

    fun selectRemote(snapshotId: String) {
        val current = state()
        if (!current.canInteract || !current.configured || !current.hasUsableObservation ||
            current.activeConfirmation?.stage != SharedComparisonConfirmationStage.CONFLICT ||
            current.remoteChoices.none { it.snapshotId == snapshotId }) return
        actions().selectRemote(snapshotId)
    }

    fun performPrimary(expected: SharedComparisonPrimary) {
        val current = state()
        if (current.primary != expected || !current.primaryEnabled) return
        when (expected) {
            SharedComparisonPrimary.FINISH -> actions().finish(current.completion)
            SharedComparisonPrimary.HANDLE_CONFLICT -> actions().handleConflict()
            SharedComparisonPrimary.REFRESH -> actions().refresh()
            SharedComparisonPrimary.RETRY_UPLOAD -> actions().upload()
            SharedComparisonPrimary.OPEN_RECOVERY -> actions().openRecovery()
        }
    }

    fun copyDiagnostics() {
        val current = state()
        if (current.canInteract && current.activeConfirmation == null && current.canCopyDiagnostics && current.details.isNotBlank()) {
            actions().copyDiagnostics()
        }
    }

    fun confirm(token: Long) {
        val current = state()
        val confirmation = current.activeConfirmation ?: return
        if (confirmation.token != token || current.confirmationPresentation?.confirmEnabled != true) return
        if (confirmation.stage == SharedComparisonConfirmationStage.MERGE_REVIEW) actions().confirmMerge(token)
        else actions().confirmConflict(token)
    }

    fun previewMerge(token: Long) {
        val current = state()
        val confirmation = current.activeConfirmation ?: return
        if (confirmation.token != token || confirmation.stage != SharedComparisonConfirmationStage.CONFLICT ||
            current.confirmationPresentation?.secondaryEnabled != true) return
        actions().previewMerge(token)
    }

    fun cancel(token: Long) {
        val current = state()
        if (current.canInteract && current.activeConfirmation?.token == token) actions().cancelConflict(token)
    }
}
