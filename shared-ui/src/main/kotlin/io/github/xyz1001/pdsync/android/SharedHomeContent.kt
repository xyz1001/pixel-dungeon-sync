package io.github.xyz1001.pdsync.android

import pdsync.SyncHistory

/**
 * UI-only no-op detection from the host's current verified observation. Every entry is a complete
 * save-relative path and SHA-256 of that file's bytes; names, counts, times and the baseline are not
 * content evidence. This neither reads files nor authorizes a write or advances a sync baseline.
 */
data class SharedHomeContent(
    val uploadUnnecessary: Boolean = false,
    val restoreUnnecessary: Boolean = false,
) {
    companion object {
        /** Unknown/failed observations and recovery/branch-selection actions retain host policy. */
        @JvmStatic
        fun from(
            history: SyncHistory?,
            selectedId: String?,
            verified: Boolean,
            pendingUpload: Boolean,
            hasConflict: Boolean,
        ): SharedHomeContent {
            if (!verified || history == null || pendingUpload || hasConflict ||
                history.graph.pending.isNotEmpty() || history.graph.tips.size > 1 ||
                history.currentFiles.isEmpty()) return SharedHomeContent()

            val tip = history.graph.tips.singleOrNull()?.let(history.graph.commits::get)
            val targetId = selectedId ?: history.defaultSnapshot
            val target = history.graph.commits.values.singleOrNull { it.snapshot.id == targetId }
            return SharedHomeContent(
                uploadUnnecessary = tip?.snapshot?.files == history.currentFiles,
                restoreUnnecessary = target?.snapshot?.files == history.currentFiles,
            )
        }
    }
}
