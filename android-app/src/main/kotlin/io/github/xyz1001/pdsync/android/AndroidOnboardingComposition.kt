package io.github.xyz1001.pdsync.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import pdsync.SaveSummary
import pdsync.OnboardingKind
import pdsync.SyncHistory
import pdsync.android.app.AndroidSyncFacade
import pdsync.presentation.HistoryPresentation

/** Thin Android host. Path resolution, read/proof checks and operations stay in MainActivity. */
object AndroidOnboardingComposition {
    @JvmStatic
    fun showSavePath(
        activity: ComponentActivity,
        path: String,
        summary: SaveSummary?,
        busy: Boolean,
        notice: String,
        details: String,
        canContinue: Boolean,
        readStatus: SharedSavePathReadStatus,
        continueAfterRead: Boolean,
        canRequestRead: Boolean,
        game: SharedGameIdentity?,
        canInitializeGame: Boolean,
        retryLabel: String,
        canCopyDiagnostics: Boolean,
        retry: Runnable,
        initializeGame: Runnable,
        back: Runnable,
        next: Runnable,
        copyDiagnostics: Runnable,
    ) {
        val state = SharedSavePathState(
            path = path,
            editable = false,
            summary = summary,
            busy = busy,
            notice = notice,
            details = details,
            canContinue = canContinue,
            readStatus = readStatus,
            continueAfterRead = continueAfterRead,
            canRequestRead = canRequestRead,
            canChooseDirectory = false,
            canUseDefault = false,
            canRetry = true,
            canInitializeGame = canInitializeGame,
            retryLabel = retryLabel,
            canCopyDiagnostics = canCopyDiagnostics,
        )
        val actions = object : SharedSavePathActions {
            // Android's game-private path is resolved by the selected backend, never edited.
            override fun editPath(path: String) = Unit
            override fun chooseDirectory() = Unit
            override fun useDefault() = Unit
            override fun retry() = retry.run()
            override fun initializeGame() = initializeGame.run()
            override fun back() = back.run()
            override fun next() = next.run()
            override fun copyDiagnostics() = copyDiagnostics.run()
        }
        activity.setContent {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                SharedSavePathScreen(state, actions, game)
            }
        }
    }

    /** Map the actual facade observation; presentation counts never prove equality or permission. */
    @JvmStatic
    fun showComparison(
        activity: ComponentActivity,
        observed: AndroidSyncFacade.Inspection?,
        history: SyncHistory?,
        game: SharedGameIdentity?,
        localDevice: String,
        cloudVerified: Boolean,
        restoreSupported: Boolean,
        configured: Boolean,
        pageActive: Boolean,
        busy: Boolean,
        selectedProgress: SharedProgressChoice?,
        selectedId: String?,
        notice: String,
        details: String,
        canCopyDiagnostics: Boolean,
        confirmation: SharedComparisonConfirmation?,
        feedbackError: Boolean,
        actions: SharedComparisonActions,
    ) {
        val kind = observed?.kind?.let { runCatching { OnboardingKind.valueOf(it) }.getOrDefault(OnboardingKind.BLOCKED) }
        val summary = savePathSummary(history?.summary)
        val state = SharedComparisonState(
            kind = kind,
            cloudVerified = cloudVerified,
            needsRestoreRecovery = observed?.needsRestoreRecovery == true,
            retryablePending = observed?.isRetryablePending == true,
            canCompleteWithoutTransfer = observed?.canCompleteWithoutTransfer == true,
            canAdoptRemote = observed?.canAdoptRemote == true,
            canKeepLocalProgress = observed?.canKeepLocalProgress == true,
            restoreSupported = restoreSupported,
            pageActive = pageActive,
            configured = configured,
            busy = busy,
            game = game,
            localLabel = history?.currentName?.takeIf(String::isNotBlank) ?: localDevice,
            localDetails = summary?.let { (listOf(it.headline) + it.slots).joinToString("\n") }.orEmpty(),
            localFileCount = observed?.localFileCount,
            remoteChoices = observed?.remoteVersions.orEmpty().map { remote ->
                SharedComparisonRemoteChoice(remote.snapshotId, remote.displayName,
                    listOfNotNull(remote.deviceLabel, HistoryPresentation.time(remote.capturedAt), remote.versionSummary)
                        .filter(String::isNotBlank).joinToString(" · "))
            },
            selectedProgress = selectedProgress,
            selectedId = selectedId,
            notice = notice,
            details = details,
            canCopyDiagnostics = canCopyDiagnostics,
            confirmation = confirmation,
            feedbackError = feedbackError,
        )
        activity.setContent {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                SharedComparisonScreen(state, actions)
            }
        }
    }

    /** Same physical home/history as desktop; target lookup deliberately uses the full history. */
    @JvmStatic
    fun showHome(
        activity: ComponentActivity,
        history: SyncHistory?,
        historyPage: Int,
        game: SharedGameIdentity?,
        status: String,
        pageActive: Boolean,
        busy: Boolean,
        canUpload: Boolean,
        canRestore: Boolean,
        canLaunch: Boolean,
        pending: SharedHomeOperation,
        selectedId: String?,
        explicitSelection: String?,
        notice: String,
        noticeError: Boolean,
        details: String,
        actions: SharedHomeActions,
    ) {
        val targetId = selectedId ?: history?.defaultSnapshot
        val target = history?.cloud?.firstOrNull { it.snapshot.id == targetId }
        val state = SharedHomeState(
            game = game,
            status = status,
            pageActive = pageActive,
            busy = busy,
            canUpload = canUpload,
            canRestore = canRestore,
            canLaunch = canLaunch,
            pending = pending,
            restoreTarget = if (history == null || target == null) null else target.let { commit ->
                val snapshot = commit.snapshot
                SharedHomeRestoreTarget(
                    snapshotId = snapshot.id,
                    name = history.aliases[snapshot.id]?.takeIf(String::isNotBlank)
                        ?: snapshot.directoryMetadata?.displayName?.takeIf(String::isNotBlank)
                        ?: "存档 ${commit.id.take(6)}",
                    source = snapshot.metadata?.device?.label ?: "来源未知",
                    time = HistoryPresentation.time(snapshot.metadata?.latestFileModifiedAt),
                    notice = if (history.currentFiles.isNotEmpty() && history.currentFiles == snapshot.files)
                        "本机已是此进度，无需恢复。" else "",
                )
            },
            selectedSnapshotId = explicitSelection?.takeIf { it == selectedId },
            emptyTargetMessage = when {
                selectedId != null -> "所选存档不在当前已验证历史中，请重新检查。"
                history == null -> "正在读取恢复目标…"
                history.graph.tips.size > 1 -> "请选择要恢复的云端分支"
                else -> "暂无可恢复的云端存档"
            },
            history = history?.let { HistoryPresentation.from(it, historyPage) },
            notice = notice,
            noticeError = noticeError,
            details = details,
        )
        activity.setContent {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                SharedHomeScreen(state, actions)
            }
        }
    }

}
