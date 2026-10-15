package io.github.xyz1001.pdsync.android

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
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
             recovery: Boolean, notice: String, details: String, actions: SharedBranchChoiceActions): View {
        val branched = observed?.kind == "BRANCHED"
        val pending = observed?.isRetryablePending == true
        val state = SharedBranchChoiceState(
            observation = observed,
            branches = if (!branched) emptyList() else observed?.remoteVersions.orEmpty().map { branch ->
                val manifest = history?.cloud?.firstOrNull { it.snapshot.id == branch.snapshotId }?.snapshot
                SharedBranchChoice(branch.snapshotId, branch.displayName,
                    listOf(branch.deviceLabel ?: "来源未知",
                        HistoryPresentation.time(manifest?.metadata?.latestFileModifiedAt),
                        "${branch.fileCount} 个文件").joinToString(" · "))
            },
            selectedId = selectedId, game = game, pageActive = pageActive, busy = busy,
            connected = connected, observationCurrent = cloudVerified && branched,
            resolutionBlocked = recovery || pending,
            overwriteSupported = restoreSupported, mergeSupported = restoreSupported,
            canPreserveLocal = branched && !recovery && history?.currentProtected == false,
            notice = listOf(notice, when {
                recovery -> "有未完成恢复，请返回设置检查。"
                pending -> "上次上传尚未完成，请先重试上传。"
                !restoreSupported -> "当前访问方式不支持恢复与合并写入。"
                else -> ""
            }).filter(String::isNotBlank).distinct().joinToString("\n"),
            details = details,
        )
        return ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    SharedBranchChoiceScreen(state, actions)
                }
            }
        }
    }
}
