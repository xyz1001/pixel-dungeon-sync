package pdsync

import io.github.xyz1001.pdsync.android.SharedGameIdentity
import io.github.xyz1001.pdsync.android.SharedHomeOperation
import io.github.xyz1001.pdsync.android.SharedHomeRestoreTarget
import io.github.xyz1001.pdsync.android.SharedHomeState
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
  val target = history?.graph?.commits?.values?.firstOrNull { it.snapshot.id == current.selectedId }
  val complete = current.config?.onboardingProgress == OnboardingProgress.COMPLETE
  val locked = current.busy || current.connectingAtStartup || current.appClosed
  val restoreProblem = current.restoreIssue?.takeIf {
   current.selectedId != null && current.remoteTips.size <= 1 && !current.hasConflict
  }
  val notice = listOfNotNull(
   current.launchNotice?.takeIf(String::isNotBlank)?.let { desktopHomeFeedback(it, "请查看启动结果。") },
   current.locationIssue?.takeIf(String::isNotBlank)?.let { desktopHomeFeedback(it, "无法读取存档，请在设置中重新检查。", allowPlainText = false) },
   current.connectionNotice?.takeIf(String::isNotBlank)?.let { desktopHomeFeedback(it, "连接需要处理，请打开设置。") },
   current.operationStatus.takeIf { !current.busy && it.isNotBlank() }?.let { desktopHomeFeedback(it, "请查看操作结果。") },
  ).distinct().joinToString("\n")
  return SharedHomeState(
   game = current.game,
   status = when {
    current.connectingAtStartup -> "正在读取存档与连接状态…"
    current.launchingGame -> "正在启动游戏…"
    current.busy -> when (current.operation) {
     SharedHomeOperation.UPLOAD -> "正在上传存档…"
     SharedHomeOperation.RESTORE -> "正在备份并恢复存档…"
     SharedHomeOperation.MERGE -> "正在检查并合并存档…"
     else -> "正在处理，请稍候…"
    }
    current.gameStatus is GameProcessStatus.Running -> "游戏运行中，请退出后再同步。"
    !current.connected -> "尚未连接云端，点击上传登录。"
    !complete -> "自动上传暂停，请先选择上传或恢复。"
    current.pendingUpload -> "上次上传尚未完成，请先重试上传。"
    current.hasConflict || current.remoteTips.size > 1 -> "云端有多份进度，请点击恢复后选择。"
    current.automaticStatus.isNotBlank() -> desktopHomeFeedback(current.automaticStatus, "请查看同步状态。")
    else -> "云端已连接"
   },
   pageActive = current.pageActive && !current.appClosed,
   busy = locked,
   // A disconnected Upload intentionally opens the existing login flow. Do not invent an
   // entitlement result here: real authentication and expiry failures remain owned by the host.
   canUpload = !locked && (!current.connected || current.hasSession &&
    (current.pendingUpload || history?.currentFiles?.isNotEmpty() == true)),
   canRestore = current.hasSession && !locked && current.connected &&
    (!complete || !current.pendingUpload && (current.hasConflict || current.remoteTips.size > 1 ||
     current.selectedId != null && current.restoreIssue == null)),
   canLaunch = current.config != null && !locked && current.gameStatus !is GameProcessStatus.Running,
   pending = if (current.launchingGame) SharedHomeOperation.LAUNCH else current.operation,
   restoreTarget = target?.let { commit ->
    val snapshot = commit.snapshot
    SharedHomeRestoreTarget(snapshot.id,
     history?.aliases?.get(snapshot.id)?.takeIf(String::isNotBlank)
      ?: snapshot.directoryMetadata?.displayName?.takeIf(String::isNotBlank)
      ?: "存档 ${commit.id.take(6)}",
     snapshot.metadata?.device?.label ?: "来源未知",
     HistoryPresentation.time(snapshot.metadata?.latestFileModifiedAt),
     notice = restoreProblem?.let { desktopHomeFeedback(it, "暂不能恢复，请重新检查所选存档。") }.orEmpty(),
     noticeError = restoreProblem != null)
   },
   // The actual restore target can be the unique default without becoming a user selection.
   selectedSnapshotId = current.selectedId.takeIf { current.selectedByUser },
   emptyTargetMessage = when {
    history == null -> "正在读取云端存档…"
    history.graph.tips.size > 1 -> "云端有多份进度，请先选择存档。"
    else -> "暂无可恢复的云端存档"
   },
   history = history?.let { HistoryPresentation.from(it, current.historyPage) },
   notice = notice,
   noticeError = current.locationIssue != null || current.connectionNotice != null,
   details = listOfNotNull(current.deviceLabel?.let { "本机：$it" }, current.automaticStatus,
    current.operationStatus, current.connectionNotice, current.locationIssue, current.launchNotice,
    restoreProblem).filter(String::isNotBlank).distinct().joinToString("\n"),
  )
 }
}

/** Known actionable translations may be shown; exception text and paths stay in collapsed details. */
private fun desktopHomeFeedback(raw: String, fallback: String, allowPlainText: Boolean = true): String {
 val message = NoviceCopy.feedback(raw, fallback)
 if (message != raw) return message
 return if (!allowPlainText || raw.contains(Regex("https?://|[A-Za-z]:[\\\\/]|(^|\\s)/|Exception|Error|failed|denied", RegexOption.IGNORE_CASE)) ||
  raw.startsWith("操作未完成：") || raw.startsWith("读取云端失败：") || raw.startsWith("无法更新存档信息：") || raw.startsWith("暂不能选择恢复：")) fallback
 else message
}

internal fun desktopHomeOperation(action: OperationAction): SharedHomeOperation = when (action) {
 OperationAction.PUSH -> SharedHomeOperation.UPLOAD
 OperationAction.RESTORE, OperationAction.RESOLVE, OperationAction.CLOUD_HISTORY, OperationAction.REMOTE_TIP -> SharedHomeOperation.RESTORE
 OperationAction.MERGE, OperationAction.MERGE_CURRENT -> SharedHomeOperation.MERGE
 OperationAction.CAPTURE, OperationAction.ARCHIVE -> SharedHomeOperation.NONE
}
