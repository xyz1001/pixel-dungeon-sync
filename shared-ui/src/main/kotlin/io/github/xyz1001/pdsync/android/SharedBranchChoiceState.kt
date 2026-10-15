package io.github.xyz1001.pdsync.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Already-observed branch metadata only. A label or timestamp never identifies a branch. */
data class SharedBranchChoice(val snapshotId: String, val name: String, val summary: String)

data class SharedBranchChoiceState(
    val observation: Any?,
    val branches: List<SharedBranchChoice>,
    val selectedId: String? = null,
    val game: SharedGameIdentity? = null,
    val pageActive: Boolean = false,
    val busy: Boolean = false,
    val connected: Boolean = false,
    val observationCurrent: Boolean = false,
    val resolutionBlocked: Boolean = false,
    val overwriteSupported: Boolean = false,
    val mergeSupported: Boolean = false,
    val canPreserveLocal: Boolean = false,
    val notice: String = "",
    val details: String = "",
)

interface SharedBranchChoiceActions {
    fun select(snapshotId: String)
    fun back()
    fun refresh()
    /** Revalidate the original core observation and selected ID before any write. */
    fun overwrite(snapshotId: String)
    fun merge()
    fun preserveLocal()
}

internal val SharedBranchChoiceState.canInteract: Boolean get() = pageActive && !busy
internal val SharedBranchChoiceState.hasBranches: Boolean get() = observation != null && observationCurrent &&
    branches.isNotEmpty() && branches.all { it.snapshotId.isNotBlank() } &&
    branches.map { it.snapshotId }.distinct().size == branches.size
internal val SharedBranchChoiceState.hasSelection: Boolean get() = selectedId != null &&
    branches.any { it.snapshotId == selectedId }
internal val SharedBranchChoiceState.canResolve: Boolean get() = canInteract && connected && hasBranches && !resolutionBlocked
internal val SharedBranchChoiceState.primaryEnabled: Boolean get() = canInteract && connected &&
    (!hasBranches || canResolve && (overwriteSupported || mergeSupported))
internal val SharedBranchChoiceState.preserveEnabled: Boolean get() = canInteract && connected && hasBranches && canPreserveLocal

/**
 * A local confirmation is bound to the exact host observation and displayed selection/capabilities.
 * Opening, selecting, canceling and Back do not dispatch a write. Consuming a token is one-shot.
 * The hosts still own their operation lock, frozen snapshot proof and final transaction checks.
 */
internal class SharedBranchChoiceInteractions(
    private val state: () -> SharedBranchChoiceState,
    private val actions: () -> SharedBranchChoiceActions,
) {
    private data class Confirmation(val token: Long, val source: SharedBranchChoiceState)
    private var confirmation by mutableStateOf<Confirmation?>(null)
    private var serial = 0L
    private var attached = true

    private fun currentConfirmation(): Confirmation? {
        val current = state()
        val proof = confirmation ?: return null
        if (!attached || !current.pageActive || !sameEvidence(proof.source, current)) {
            confirmation = null
            return null
        }
        return proof
    }

    private fun sameEvidence(a: SharedBranchChoiceState, b: SharedBranchChoiceState): Boolean =
        a.observation === b.observation && a.branches == b.branches && a.selectedId == b.selectedId &&
            a.game == b.game && a.connected == b.connected && a.observationCurrent == b.observationCurrent &&
            a.resolutionBlocked == b.resolutionBlocked && a.overwriteSupported == b.overwriteSupported &&
            a.mergeSupported == b.mergeSupported

    val presentation: SharedConfirmationState? get() = currentConfirmation()?.let { proof ->
        val current = state()
        val selected = current.branches.singleOrNull { it.snapshotId == current.selectedId }
        SharedConfirmationState(proof.token, "如何处理冲突？",
            listOfNotNull(selected?.let { "所选存档：${it.name}" },
                "将先备份，再将所选存档设为本机和云端当前进度。所选存档中没有的进度会移除。请退出游戏，并暂停其他存档同步。")
                .joinToString("\n\n"),
            confirmLabel = "使用所选进度", confirmEnabled = current.canResolve && current.overwriteSupported && current.hasSelection,
            secondaryLabel = if (current.mergeSupported) "合并" else null,
            secondaryEnabled = current.canResolve && current.mergeSupported, busy = current.busy)
    }

    fun select(snapshotId: String) {
        val current = state()
        if (attached && current.canInteract && current.connected && current.hasBranches && currentConfirmation() == null &&
            current.branches.any { it.snapshotId == snapshotId } && current.selectedId != snapshotId) {
            confirmation = null
            actions().select(snapshotId)
        }
    }

    fun primary() {
        val current = state()
        if (!attached || !current.primaryEnabled || currentConfirmation() != null) return
        if (!current.hasBranches) actions().refresh()
        else confirmation = Confirmation(++serial, current)
    }

    fun back() {
        if (attached && state().canInteract && currentConfirmation() == null) {
            confirmation = null
            actions().back()
        }
    }

    fun preserveLocal() {
        if (attached && state().preserveEnabled && currentConfirmation() == null) actions().preserveLocal()
    }

    fun confirm(token: Long) {
        val proof = currentConfirmation() ?: return
        if (proof.token != token || presentation?.confirmEnabled != true) return
        val id = proof.source.selectedId ?: return
        confirmation = null
        actions().overwrite(id)
    }

    fun merge(token: Long) {
        val proof = currentConfirmation() ?: return
        if (proof.token != token || presentation?.secondaryEnabled != true) return
        confirmation = null
        actions().merge()
    }

    fun cancel(token: Long) {
        if (currentConfirmation()?.token == token && state().canInteract) confirmation = null
    }

    fun dispose() { attached = false; confirmation = null }
}
