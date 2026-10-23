package io.github.xyz1001.pdsync.android

import pdsync.i18n.AndroidHostMessages.*

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import pdsync.SyncHistory
import pdsync.android.app.AndroidSyncFacade
import pdsync.presentation.HistoryPresentation

/** Legacy Activity attachment only; the complete shell, chooser and confirmation are shared. */
object AndroidBranchChoiceComposition {
    @JvmStatic
    fun view(activity: ComponentActivity, observed: AndroidSyncFacade.Inspection?, history: SyncHistory?,
             game: SharedGameIdentity?, selectedId: String?, pageActive: Boolean, busy: Boolean,
             connected: Boolean, cloudVerified: Boolean, restoreSupported: Boolean,
             recovery: Boolean, notice: String, details: String, historyPage: Int,
             homeSelection: String?, homeStatus: String, homeStatusKind: SharedHomeStatusKind,
             actions: SharedBranchChoiceActions): View {
        val branched = observed?.kind == "BRANCHED"
        val pending = observed?.isRetryablePending == true
        val state = SharedBranchChoiceState(
            observation = observed,
            branches = if (!branched) emptyList() else observed?.remoteVersions.orEmpty().map { branch ->
                val manifest = history?.cloud?.firstOrNull { it.snapshot.id == branch.snapshotId }?.snapshot
                SharedBranchChoice(branch.snapshotId, branch.displayName,
                    listOf(branch.deviceLabel ?: UNKNOWN_SOURCE.text(),
                        HistoryPresentation.time(manifest?.metadata?.latestFileModifiedAt),
                        FILE_COUNT.text(branch.fileCount)).joinToString(" · "))
            },
            selectedId = selectedId, game = game, pageActive = pageActive, busy = busy,
            connected = connected, observationCurrent = cloudVerified && branched,
            resolutionBlocked = recovery || pending,
            overwriteSupported = true, mergeSupported = false, cloudOverwrite = true,
            canPreserveLocal = false,
            notice = listOf(notice, when {
                recovery -> AN_UNFINISHED_RESTORE_EXISTS_RETURN_TO_SETTINGS.text()
                pending -> THE_PREVIOUS_UPLOAD_IS_UNFINISHED_RETRY_UPLOADING.text()
                else -> ""
            }).filter(String::isNotBlank).distinct().joinToString("\n"),
            details = details,
        )
        return ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AndroidAppViewport {
                    Box(Modifier.fillMaxSize()) {
                        SharedHomeScreen(SharedHomeState(game = game, status = homeStatus, statusKind = homeStatusKind,
                            pageActive = false, selectedSnapshotId = homeSelection,
                            history = history?.let { HistoryPresentation.from(it, historyPage) }),
                            object : SharedHomeActions {
                                override fun settings() = Unit
                                override fun upload() = Unit
                                override fun restore() = Unit
                                override fun launch() = Unit
                                override fun selectSnapshot(snapshotId: String) = Unit
                                override fun rename(snapshotId: String?) = Unit
                                override fun changeHistoryPage(page: Int) = Unit
                            })
                        SharedBranchChoiceDialog(state, actions)
                    }
                }
            }
        }
    }
}
