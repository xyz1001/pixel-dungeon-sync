package io.github.xyz1001.pdsync.android

import pdsync.i18n.AndroidHostMessages.*

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import pdsync.SaveSummary
import pdsync.SyncHistory
import pdsync.presentation.HistoryPresentation

/** Thin Android host. Path resolution, read/proof checks and operations stay in MainActivity. */
object AndroidOnboardingComposition {
    @JvmStatic
    fun showGameSelection(activity: ComponentActivity, state: SharedGameSelectionState, actions: SharedGameSelectionActions) {
        activity.setContent {
            AndroidAppViewport {
                SharedGameSelectionScreen(state, actions)
            }
        }
    }

    /** The host supplies a snapshot and real callbacks; recomposition never reads platform state. */
    @JvmStatic
    fun showSettings(activity: ComponentActivity, state: SharedSettingsState, actions: SharedSettingsActions) {
        activity.setContent {
            AndroidAppViewport {
                SharedSettingsScreen(state, actions)
            }
        }
    }

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
            AndroidAppViewport {
                SharedSavePathScreen(state, actions, game)
            }
        }
    }

    /** Same physical home/history as desktop; target lookup deliberately uses the full history. */
    @JvmStatic
    @JvmOverloads
    fun showHome(
        activity: ComponentActivity,
        history: SyncHistory?,
        historyPage: Int,
        game: SharedGameIdentity?,
        status: String,
        statusKind: SharedHomeStatusKind,
        pageActive: Boolean,
        busy: Boolean,
        canUpload: Boolean,
        canRestore: Boolean,
        canLaunch: Boolean,
        pending: SharedHomeOperation,
        archiveLoading: Boolean,
        selectedId: String?,
        explicitSelection: String?,
        content: SharedHomeContent,
        notice: String,
        noticeError: Boolean,
        details: String,
        actions: SharedHomeActions,
        naming: SharedHistoryNamingController,
        canAppreciate: Boolean = false,
        canRefreshHistory: Boolean = false,
    ) {
        val targetId = selectedId ?: history?.defaultSnapshot
        val target = history?.cloud?.firstOrNull { it.snapshot.id == targetId }
        val state = SharedHomeState(
            game = game,
            status = status,
            statusKind = statusKind,
            canAppreciate = canAppreciate,
            canRefreshHistory = canRefreshHistory,
            pageActive = pageActive,
            busy = busy,
            archiveLoading = archiveLoading,
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
                        ?: SAVE_SHORT_ID.text(commit.id.take(6)),
                    source = snapshot.metadata?.device?.label ?: UNKNOWN_SOURCE.text(),
                    time = HistoryPresentation.time(snapshot.metadata?.latestFileModifiedAt),
                    notice = if (content.restoreUnnecessary)
                        THIS_DEVICE_ALREADY_HAS_THIS_PROGRESS_NO.text() else "",
                )
            },
            selectedSnapshotId = explicitSelection?.takeIf { it == selectedId },
            emptyTargetMessage = when {
                selectedId != null -> THE_SELECTED_SAVE_IS_NOT_IN_THE.text()
                history == null -> READING_RESTORE_TARGETS.text()
                history.graph.tips.size > 1 -> SELECT_THE_CLOUD_BRANCH_TO_RESTORE.text()
                else -> NO_CLOUD_SAVES_ARE_AVAILABLE_TO_RESTORE.text()
            },
            history = history?.let { HistoryPresentation.from(it, historyPage) },
            notice = notice,
            noticeError = noticeError,
            details = details,
        )
        activity.setContent {
            AndroidAppViewport {
                SharedHomeScreen(state, actions, naming = naming.naming)
            }
        }
    }

}
