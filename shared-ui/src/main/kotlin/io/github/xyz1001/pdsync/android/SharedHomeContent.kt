package io.github.xyz1001.pdsync.android

import pdsync.SyncHistory

/**
 * UI-only content comparison from the host's current verified observation. Every entry is a complete
 * save-relative path and SHA-256 of that file's bytes; names, counts, times and the baseline are not
 * content evidence. This neither reads files nor authorizes a write or advances a sync baseline.
 */
data class SharedHomeContent(
    val uploadUnnecessary: Boolean = false,
    val restoreUnnecessary: Boolean = false,
    /** Display-only manifest equality; never object verification or permission to skip a transfer. */
    val matchesCloudTip: Boolean = false,
) {
    companion object {
        /** HOME button suppression only. Manifest equality never upgrades object proof or ancestry. */
        @JvmStatic
        fun fromHome(
            history: SyncHistory?,
            selectedId: String?,
            verified: Boolean,
            pendingUpload: Boolean,
            hasConflict: Boolean,
        ): SharedHomeContent {
            val content = from(history, selectedId, verified, pendingUpload, hasConflict)
            if (!content.matchesCloudTip || history == null) return content
            val tip = history.graph.tips.singleOrNull()?.let(history.graph.commits::get)
            val targetId = selectedId ?: history.defaultSnapshot
            return content.copy(
                uploadUnnecessary = true,
                restoreUnnecessary = content.restoreUnnecessary || tip?.snapshot?.id == targetId,
            )
        }

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
                uploadUnnecessary = tip != null && tip.id in history.verifiedCommitIds && tip.snapshot.files == history.currentFiles,
                restoreUnnecessary = target != null && target.id in history.verifiedCommitIds && target.snapshot.files == history.currentFiles,
                matchesCloudTip = tip != null && tip.snapshot.files == history.currentFiles,
            )
        }
    }
}
