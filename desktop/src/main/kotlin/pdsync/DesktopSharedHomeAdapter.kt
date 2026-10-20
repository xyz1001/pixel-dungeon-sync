package pdsync

import pdsync.i18n.DesktopMessages

import io.github.xyz1001.pdsync.android.SharedGameIdentity
import io.github.xyz1001.pdsync.android.SharedHomeContent
import io.github.xyz1001.pdsync.android.SharedHomeArchiveAvailability
import io.github.xyz1001.pdsync.android.SharedHomeOperation
import io.github.xyz1001.pdsync.android.SharedHomeRestoreTarget
import io.github.xyz1001.pdsync.android.SharedHomeStatusKind
import io.github.xyz1001.pdsync.android.SharedHomeState
import io.github.xyz1001.pdsync.android.appreciationEnabled
import io.github.xyz1001.pdsync.android.withoutHomeExpiryNotice
import pdsync.presentation.HistoryPresentation

/** In-memory host facts only. Rendering and paging never inspect, export or write a save. */
internal data class DesktopHomeContext(
 val config: AppConfig?,
 val hasSession: Boolean,
 val pageActive: Boolean,
 val busy: Boolean,
 val connectingAtStartup: Boolean,
 val appClosed: Boolean,
 val connected: Boolean,
 val gameStatus: GameProcessStatus,
 val launchingGame: Boolean,
 val pendingUpload: Boolean,
 val hasConflict: Boolean,
 val remoteTips: Set<String>,
 val selectedId: String?,
 val selectedByUser: Boolean,
 val restoreIssue: String?,
 val history: SyncHistory?,
 val historyPage: Int,
 val game: SharedGameIdentity? = null,
 val operation: SharedHomeOperation = SharedHomeOperation.NONE,
 val automaticStatus: String = "",
 val operationStatus: String = "",
 val connectionNotice: String? = null,
 val locationIssue: String? = null,
 val launchNotice: String? = null,
 val deviceLabel: String? = null,
 val historyOwner: AppConfig? = null,
 val historyVerified: Boolean = false,
 val localHistoryVerified: Boolean = false,
 val pendingUploadSourceAvailable: Boolean = false,
 val archiveLoading: Boolean = false,
 val serviceExpired: Boolean = false,
 val historyRefreshAccessKnown: Boolean = false,
)

internal data class DesktopHomeSelection(val snapshotId: String?, val explicit: Boolean)

/** Keep a real user choice across refreshes, independent of the currently visible history page. */
internal fun desktopHomeSelection(history: SyncHistory?, selectedId: String?, explicit: Boolean): DesktopHomeSelection =
 if (explicit && selectedId != null && history?.graph?.commits?.values?.any { it.snapshot.id == selectedId } == true)
  DesktopHomeSelection(selectedId, true)
 else DesktopHomeSelection(history?.defaultSnapshot, false)

/** An account, profile, game or save-directory change must not carry an old target into a new one. */
internal fun sameDesktopHomeProfile(previous: AppConfig?, current: AppConfig?): Boolean =
 previous != null && current != null &&
  previous.endpoint.trimEnd('/') == current.endpoint.trimEnd('/') &&
  previous.username == current.username && previous.connectionProvider == current.connectionProvider &&
  previous.profile == current.profile && previous.targetGame == current.targetGame &&
  previous.selectedSaveRoot == current.selectedSaveRoot && previous.saveRoot == current.saveRoot

/** Exact desktop action policy, kept separate from the shared layout and its interaction lock. */
internal object DesktopSharedHomeAdapter {
 fun state(current: DesktopHomeContext): SharedHomeState {
  val history = current.history
  val hasRemoteBranches = current.remoteTips.size > 1 || (history?.graph?.tips?.size ?: 0) > 1
  val target = history?.graph?.commits?.values?.firstOrNull { it.snapshot.id == current.selectedId }
  val complete = current.config?.onboardingProgress == OnboardingProgress.COMPLETE
  val locked = current.busy || current.connectingAtStartup || current.appClosed
  val transferLocked = locked || current.archiveLoading
  val gameStopped = current.gameStatus is GameProcessStatus.Stopped
  val localVerified = current.hasSession && current.localHistoryVerified && current.game != null &&
   current.locationIssue == null && sameDesktopHomeProfile(current.historyOwner, current.config)
  val remoteVerified = current.historyVerified &&
   history?.graph?.tips?.map { history.graph.commits.getValue(it).snapshot.id }?.toSet() == current.remoteTips
  val archiveVerified = localVerified && (!current.connected || remoteVerified)
  val sources = SharedHomeArchiveAvailability.from(history, current.selectedId, archiveVerified)
  val frozenUploadAvailable = archiveVerified && history != null && history.graph.pending.isEmpty() &&
   current.pendingUpload && current.pendingUploadSourceAvailable
  val content = SharedHomeContent.from(history, current.selectedId,
   complete && current.connected && archiveVerified,
   current.pendingUpload, current.hasConflict)
  val restoreProblem = current.restoreIssue?.takeIf {
   current.selectedId != null && !hasRemoteBranches && !current.hasConflict
  }
  val notice = listOfNotNull(
   current.launchNotice?.let(::withoutHomeExpiryNotice)?.takeIf(String::isNotBlank)?.let { desktopHomeFeedback(it, DesktopMessages.PLEASE_CHECK_THE_LAUNCH_RESULT.text()) },
   current.locationIssue?.let(::withoutHomeExpiryNotice)?.takeIf(String::isNotBlank)?.let { desktopHomeFeedback(it, DesktopMessages.CANNOT_READ_SAVES_CHECK_AGAIN_IN_SETTINGS.text(), allowPlainText = false) },
   current.connectionNotice?.let(::withoutHomeExpiryNotice)?.takeIf(String::isNotBlank)?.let { desktopHomeFeedback(it, DesktopMessages.THE_CONNECTION_NEEDS_ATTENTION_OPEN_SETTINGS.text()) },
   withoutHomeExpiryNotice(current.operationStatus).takeIf { !current.busy && it.isNotBlank() }?.let { desktopHomeFeedback(it, DesktopMessages.PLEASE_CHECK_THE_OPERATION_RESULT.text()) },
  ).distinct().joinToString("\n")
  val automaticStatus = withoutHomeExpiryNotice(current.automaticStatus)
  return SharedHomeState(
   game = current.game,
   status = when {
    current.serviceExpired -> pdsync.i18n.CoreSessionMessages.SERVICE_EXPIRED.text()
    current.connectingAtStartup -> DesktopMessages.READING_SAVES_AND_CONNECTION_STATUS.text()
    current.launchingGame -> DesktopMessages.STARTING_GAME.text()
    current.busy -> when (current.operation) {
     SharedHomeOperation.UPLOAD -> DesktopMessages.UPLOADING_SAVES.text()
     SharedHomeOperation.RESTORE -> DesktopMessages.BACKING_UP_AND_RESTORING_SAVES.text()
     SharedHomeOperation.MERGE -> DesktopMessages.CHECKING_AND_MERGING_SAVES.text()
     else -> DesktopMessages.PROCESSING_PLEASE_WAIT.text()
    }
    current.gameStatus is GameProcessStatus.Running -> DesktopMessages.THE_GAME_IS_RUNNING_EXIT_IT_BEFORE_SYNCING.text()
    !current.connected -> DesktopMessages.NOT_CONNECTED_TO_THE_CLOUD_CLICK_UPLOAD_TO_SIGN_IN.text()
    !complete -> DesktopMessages.AUTOMATIC_UPLOAD_PAUSED_CHOOSE_UPLOAD_OR_RESTORE_FIRST.text()
    current.pendingUpload -> DesktopMessages.THE_LAST_UPLOAD_IS_INCOMPLETE_RETRY_THE_UPLOAD_FIRST.text()
    current.hasConflict || hasRemoteBranches -> DesktopMessages.THERE_ARE_MULTIPLE_CLOUD_BRANCHES_CLICK_RESTORE_TO_CHOOSE_ONE.text()
    content.uploadUnnecessary -> DesktopMessages.PROGRESS_SYNCED_LOCAL_AND_CLOUD_PROFILES_ARE_IDENTICAL.text()
    automaticStatus.isNotBlank() -> desktopHomeFeedback(automaticStatus, DesktopMessages.PLEASE_CHECK_SYNC_STATUS.text())
    else -> DesktopMessages.CONNECTED_TO_CLOUD.text()
   },
   statusKind = when {
    current.serviceExpired -> SharedHomeStatusKind.EXPIRED
    current.connectingAtStartup || current.launchingGame || current.busy || current.gameStatus is GameProcessStatus.Running -> SharedHomeStatusKind.RUNNING
    !current.connected || !complete || current.pendingUpload -> SharedHomeStatusKind.WARNING
    current.hasConflict || hasRemoteBranches -> SharedHomeStatusKind.CONFLICT
    content.uploadUnnecessary -> SharedHomeStatusKind.SYNCED
    current.config?.autoUploadPaused == true || current.gameStatus is GameProcessStatus.Unknown -> SharedHomeStatusKind.WARNING
    else -> SharedHomeStatusKind.NEUTRAL
   },
   pageActive = current.pageActive && !current.appClosed,
   busy = locked,
   archiveLoading = current.archiveLoading && current.pageActive && !current.appClosed,
   // Login remains reachable through Upload only when this profile has a verified source.
   // A genuine frozen retry is a source even when the live save has subsequently disappeared.
   canUpload = !current.serviceExpired && !transferLocked && gameStopped && !content.uploadUnnecessary &&
    (if (current.pendingUpload) frozenUploadAvailable else sources.uploadSourceAvailable),
   canRestore = !current.serviceExpired && current.hasSession && !transferLocked && gameStopped && current.connected &&
    sources.restoreSourceAvailable && !content.restoreUnnecessary &&
    (!complete || !current.pendingUpload && (current.hasConflict || hasRemoteBranches ||
     current.selectedId != null && current.restoreIssue == null)),
   canLaunch = current.config != null && !locked && current.gameStatus !is GameProcessStatus.Running,
   canAppreciate = current.serviceExpired && desktopHomeOfficialIdentity(current.config),
   // Reading is useful even with an empty/unavailable local source or an earlier failed session.
   // The account fact gates entry only; syncAccess.store still obtains a new request lease.
   canRefreshHistory = complete && current.connected && !current.serviceExpired &&
    current.historyRefreshAccessKnown,
   pending = if (current.launchingGame) SharedHomeOperation.LAUNCH else current.operation,
   restoreTarget = target?.let { commit ->
    val snapshot = commit.snapshot
    SharedHomeRestoreTarget(snapshot.id,
     history?.aliases?.get(snapshot.id)?.takeIf(String::isNotBlank)
      ?: snapshot.directoryMetadata?.displayName?.takeIf(String::isNotBlank)
      ?: DesktopMessages.SAVE_1_S.text(commit.id.take(6)),
     snapshot.metadata?.device?.label ?: DesktopMessages.UNKNOWN_SOURCE.text(),
     HistoryPresentation.time(snapshot.metadata?.latestFileModifiedAt),
     notice = restoreProblem?.let { desktopHomeFeedback(it, DesktopMessages.CANNOT_RESTORE_YET_CHECK_THE_SELECTED_SAVE_AGAIN.text()) }
      ?: if (content.restoreUnnecessary) DesktopMessages.THIS_PROGRESS_IS_ALREADY_ON_THIS_DEVICE_NO_RESTORE_NEEDED.text() else "",
     noticeError = restoreProblem != null)
   },
   // The actual restore target can be the unique default without becoming a user selection.
   selectedSnapshotId = current.selectedId.takeIf { current.selectedByUser },
   emptyTargetMessage = when {
    history == null -> DesktopMessages.READING_CLOUD_SAVES.text()
    history.graph.tips.size > 1 -> DesktopMessages.THERE_ARE_MULTIPLE_CLOUD_BRANCHES_SELECT_A_SAVE_FIRST.text()
    else -> DesktopMessages.NO_CLOUD_SAVES_AVAILABLE_TO_RESTORE.text()
   },
   history = history?.let { HistoryPresentation.from(it, current.historyPage) },
   notice = notice,
   noticeError = current.locationIssue != null || current.connectionNotice != null,
   details = listOfNotNull(current.deviceLabel?.let { DesktopMessages.THIS_DEVICE_1_S.text(it) }, current.automaticStatus,
    current.operationStatus, current.connectionNotice, current.locationIssue, current.launchNotice,
    restoreProblem).filter(String::isNotBlank).distinct().joinToString("\n"),
  )
 }
}

/** Only the configured built-in account can open its existing appreciation page. */
internal fun desktopHomeOfficialIdentity(config: AppConfig?): Boolean = config != null &&
 config.connectionProvider == "official" && isDesktopOfficialRoot(config.endpoint) && config.username.isNotBlank()

/** Retained navigation callbacks recheck both the current identity and the shared interaction lock. */
internal fun desktopHomeAppreciationAllowed(config: AppConfig?, state: SharedHomeState): Boolean =
 desktopHomeOfficialIdentity(config) && state.appreciationEnabled

/** Rechecked by the host before direct transfer entry and confirmation submission. */
internal fun desktopHomeTransferAllowed(action: OperationAction, state: SharedHomeState): Boolean = when (action) {
 OperationAction.PUSH -> state.canUpload
 OperationAction.RESTORE, OperationAction.CLOUD_HISTORY, OperationAction.REMOTE_TIP -> state.canRestore
 else -> true
}

/** Known actionable translations may be shown; exception text and paths stay in collapsed details. */
private fun desktopHomeFeedback(raw: String, fallback: String, allowPlainText: Boolean = true): String {
 val message = NoviceCopy.feedback(raw, fallback)
 if (message != raw) return message
 return if (!allowPlainText || raw.contains(Regex("https?://|[A-Za-z]:[\\\\/]|(^|\\s)/|Exception|Error|failed|denied", RegexOption.IGNORE_CASE)) ||
  raw.startsWith(DesktopMessages.OPERATION_INCOMPLETE.text()) || raw.startsWith(DesktopMessages.FAILED_TO_READ_CLOUD.text()) || raw.startsWith(DesktopMessages.CANNOT_UPDATE_SAVE_INFORMATION.text()) || raw.startsWith(DesktopMessages.CANNOT_SELECT_RESTORE_YET.text())) fallback
 else message
}

internal fun desktopHomeOperation(action: OperationAction): SharedHomeOperation = when (action) {
 OperationAction.PUSH -> SharedHomeOperation.UPLOAD
 OperationAction.RESTORE, OperationAction.RESOLVE, OperationAction.CLOUD_HISTORY, OperationAction.REMOTE_TIP -> SharedHomeOperation.RESTORE
 OperationAction.MERGE, OperationAction.MERGE_CURRENT -> SharedHomeOperation.MERGE
 OperationAction.CAPTURE, OperationAction.ARCHIVE -> SharedHomeOperation.NONE
}
