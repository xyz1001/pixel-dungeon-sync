package io.github.xyz1001.pdsync.android

import pdsync.i18n.AndroidHostMessages.*
import pdsync.SyncHistory
import pdsync.android.app.AndroidSyncFacade
import pdsync.presentation.HistoryPresentation

/** Platform-free production mapping, shared with end-to-end rendered upload tests. */
fun androidCloudOverwriteState(observed: AndroidSyncFacade.Inspection?, history: SyncHistory?,
    game: SharedGameIdentity?, pageActive: Boolean, busy: Boolean, connected: Boolean,
    cloudVerified: Boolean, recovery: Boolean, notice: String, details: String): SharedBranchChoiceState {
        val branched = observed?.isOverwriteConflict == true
        val pending = observed?.isRetryablePending == true
        return SharedBranchChoiceState(
            observation = observed,
            branches = if (!branched) emptyList() else observed?.remoteVersions.orEmpty().map { branch ->
                val manifest = history?.cloud?.firstOrNull { it.snapshot.id == branch.snapshotId }?.snapshot
                SharedBranchChoice(branch.snapshotId, branch.displayName,
                    listOf(branch.deviceLabel ?: UNKNOWN_SOURCE.text(),
                        HistoryPresentation.time(manifest?.metadata?.latestFileModifiedAt),
                        FILE_COUNT.text(branch.fileCount)).joinToString(" · "))
            },
            selectedId = observed?.overwriteTargetSnapshotId, game = game, pageActive = pageActive, busy = busy,
            connected = connected, observationCurrent = cloudVerified && branched,
            resolutionBlocked = recovery || pending,
            overwriteSupported = true, cloudOverwrite = true,
            canPreserveLocal = false,
            notice = listOf(notice, when {
                recovery -> AN_UNFINISHED_RESTORE_EXISTS_RETURN_TO_SETTINGS.text()
                pending -> THE_PREVIOUS_UPLOAD_IS_UNFINISHED_RETRY_UPLOADING.text()
                else -> ""
            }).filter(String::isNotBlank).distinct().joinToString("\n"),
            details = details,
        )
}
