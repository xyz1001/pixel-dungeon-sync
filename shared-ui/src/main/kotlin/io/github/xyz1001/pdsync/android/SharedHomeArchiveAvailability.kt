package io.github.xyz1001.pdsync.android

import pdsync.SyncHistory

/**
 * Source availability from a current, verified archive observation. This does not authorize writes,
 * replace host locks, or decide whether available content is already synchronized.
 */
data class SharedHomeArchiveAvailability(
    val uploadSourceAvailable: Boolean = false,
    val restoreSourceAvailable: Boolean = false,
) {
    companion object {
        @JvmStatic
        fun from(history: SyncHistory?, selectedId: String?, verified: Boolean): SharedHomeArchiveAvailability {
            if (!verified || history == null || history.graph.pending.isNotEmpty()) {
                return SharedHomeArchiveAvailability()
            }

            val targetId = selectedId ?: history.defaultSnapshot
            val restoreAvailable = if (targetId != null) {
                history.graph.commits.values.any { it.snapshot.id == targetId && it.snapshot.files.isNotEmpty() }
            } else {
                // Multiple verified branches need the existing chooser, never an invented winner.
                history.graph.tips.size > 1 && history.graph.tips.any {
                    history.graph.commits[it]?.snapshot?.files?.isNotEmpty() == true
                }
            }
            return SharedHomeArchiveAvailability(
                uploadSourceAvailable = history.currentFiles.isNotEmpty(),
                restoreSourceAvailable = restoreAvailable,
            )
        }
    }
}
