package pdsync

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import java.nio.file.Path
import io.github.xyz1001.pdsync.android.ConnectionEntryModel
import io.github.xyz1001.pdsync.android.SharedConnectionConfig
import io.github.xyz1001.pdsync.android.SharedConnectionController
import io.github.xyz1001.pdsync.android.SharedConnectionHost
import io.github.xyz1001.pdsync.android.SharedGameIdentity
import io.github.xyz1001.pdsync.android.SharedConnectionScreen
import io.github.xyz1001.pdsync.android.SharedSavePathScreen
import io.github.xyz1001.pdsync.android.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

private val workspace = applicationWorkspace()
private typealias Action = OperationAction
private enum class SettingsMode { FULL, UNLOCK }
private enum class Page { HOME, SETTINGS, DISCOVERY, PATH, CONNECTION, CONFLICT, CONFIRM }
private data class OperationResult(val message: String, val conflict: AppendConflict? = null, val appliedFiles: Map<String, String>? = null, val automaticSkipped: Boolean = false)
private data class LoadedProfile(val session: AppendProfileSession, val history: List<HistoryEntry>, val pending: Boolean, val saves: SaveVersions?, val target: TargetGame?, val targetError: String?, val device: DeviceIdentity, val baselineFiles: Map<String, String>?)
private val green = Color(0xFF326650)
private val muted = Color(0xFF657069)

fun main() = runSyncApplication()

/** Each application keeps only its selected target's process association; every check enumerates afresh. */
private fun productionProcessCheck(): (TargetGame?) -> GameProcessStatus {
 val lock = Any()
 var selected: TargetGame? = null
 var detector = GameProcessDetector(null)
 return { target -> synchronized(lock) {
  if (target != selected) { selected = target; detector = GameProcessDetector(target) }
  detector.status()
 } }
}

/** Production always uses fresh process detection. Dependency injection is only used by test code. */
internal fun runSyncApplication(
 processStatus: (TargetGame?) -> GameProcessStatus = productionProcessCheck(),
 title: String = "像素地牢 · 存档同步"
) = application {
 remember { configureDesktopDiagnostics(workspace.resolve(".local")) }
 var busy by remember { mutableStateOf(false) }
 var closeBlocked by remember { mutableStateOf(false) }
 var connectingAtStartup by remember { mutableStateOf(true) }
 var appClosed by remember { mutableStateOf(false) }
 Window(onCloseRequest = { if (busy || connectingAtStartup) closeBlocked = true else { appClosed = true; exitApplication() } }, title = title, state = rememberWindowState(width = 1120.dp, height = 800.dp)) {
  LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(760, 640) }
  DisposableEffect(Unit) { onDispose { appClosed = true } }
  if (System.getenv("PDSYNC_SMOKE") == "1") LaunchedEffect(Unit) {
   delay(3000); check(window.isDisplayable && window.isShowing); println("PDSYNC_UI_SMOKE_OK ${window.width}x${window.height}"); exitApplication()
  }
  val configFile = remember { ConfigFile(workspace.resolve(".local/config.json")) }
  val setupSelection = remember { SetupSelection(workspace.resolve(".local/setup-game.txt")) }
  val initialResult = remember { runCatching { configFile.load() } }
  var config by remember { mutableStateOf(initialResult.getOrNull()) }
  val location = remember { DefaultProfileLocation() }
  val pathResult = remember { runCatching { location.resolve() } }
  val pathAdapter = remember {
   DesktopSavePathAdapter(config?.selectedSaveRoot ?: pathResult.getOrNull()?.toString().orEmpty(), pathResult.getOrNull()?.toString(), location)
  }
  val pathNavigation = remember { DesktopSavePathNavigation() }
  var password by remember { mutableStateOf("") }
  var connected by remember { mutableStateOf(false) }
  var connectionNotice by remember { mutableStateOf<String?>(null) }
  var page by remember { mutableStateOf(if (config == null) Page.DISCOVERY else Page.HOME) }
  var pageGeneration by remember { mutableStateOf(0L) }
  var connectionReturnPage by remember { mutableStateOf(Page.HOME) }
  var onboardingActive by remember { mutableStateOf(config == null || config?.onboardingProgress in setOf(OnboardingProgress.PENDING, OnboardingProgress.DEFERRED)) }
  var setupDraft by remember { mutableStateOf(config ?: newConnectionConfig().copy(targetGame = runCatching { setupSelection.load() }.getOrNull().orEmpty())) }
  var discovery by remember { mutableStateOf<InstallationDiscoveryResult?>(null) }
  var discoveredTarget by remember { mutableStateOf<TargetGame?>(null) }
  var discoveryNotice by remember { mutableStateOf<String?>(null) }
  var remoteInspectionReady by remember { mutableStateOf(false) }
  var settingsMode by remember { mutableStateOf(SettingsMode.FULL) }
  var session by remember { mutableStateOf<AppendProfileSession?>(null) }
  var saveInfo by remember { mutableStateOf<SaveVersions?>(null) }
  var targetInfo by remember { mutableStateOf<TargetGame?>(null) }
  var targetError by remember { mutableStateOf<String?>(null) }
  var history by remember { mutableStateOf(emptyList<HistoryEntry>()) }
  var graphHistory by remember { mutableStateOf<SyncHistory?>(null) }
  var renameSnapshot by remember { mutableStateOf<String?>(null) }
  var historyName by remember { mutableStateOf("") }
  var selected by remember { mutableStateOf<String?>(null) }
  var selectedByUser by remember { mutableStateOf(false) }
  var homeHistoryPage by remember { mutableStateOf(0) }
  var homeProfile by remember { mutableStateOf<AppConfig?>(null) }
  var homeOperation by remember { mutableStateOf(SharedHomeOperation.NONE) }
  var remoteTips by remember { mutableStateOf(emptySet<String>()) }
  var conflictSelection by remember { mutableStateOf<String?>(null) }
  var pending by remember { mutableStateOf(false) }
  var conflict by remember { mutableStateOf<AppendConflict?>(null) }
  var directoryEditor by remember { mutableStateOf<DirectorySnapshotMetadata?>(null) }
  var directoryName by remember { mutableStateOf("") }
  var directoryNotice by remember { mutableStateOf<String?>(null) }
  var confirm by remember { mutableStateOf<OperationTrace?>(null) }
  var confirmationFiles by remember { mutableStateOf<Map<String,String>?>(null) }
  val operationEvents = remember { OperationEvents(workspace.resolve(".local/operation-events.log")) }
  val diagnosticExport = remember { DesktopDiagnosticExport() }
  var diagnosticExportNotice by remember { mutableStateOf<String?>(null) }
  var confirmationNotice by remember { mutableStateOf<String?>(null) }
  var restoreIssue by remember { mutableStateOf<String?>("请选择快照") }
  var conflictRestoreIssue by remember { mutableStateOf<String?>("尚未校验") }
  var locationIssue by remember { mutableStateOf<String?>(pathResult.exceptionOrNull()?.message) }
  var status by remember { mutableStateOf("正在检查默认存档位置…") }
  var gameStatus by remember { mutableStateOf<GameProcessStatus>(GameProcessStatus.Unknown("请在设置中选择官方游戏 JAR")) }
  val gameLauncher = remember { GameLauncher(processStatus) }
  var launchingGame by remember { mutableStateOf(false) }
  var launchNotice by remember { mutableStateOf<String?>(null) }
  var automaticStatus by remember { mutableStateOf("正在建立本地观察基线…") }
  var automatic by remember { mutableStateOf(AutomaticUpload()) }
  val observationDiagnostics = remember { DiagnosticObservationFailures() }
  var remoteReadAt by remember { mutableStateOf(0L) }
  var device by remember { mutableStateOf<DeviceIdentity?>(null) }
  var remoteObserved by remember { mutableStateOf(emptyMap<String, String>()) }
  val scope = rememberCoroutineScope()
  val selection = history.firstOrNull { it.manifest.id == selected } ?: graphHistory?.graph?.commits?.values?.firstOrNull { it.snapshot.id==selected }?.let { HistoryEntry(it.snapshot,"","云端历史") }

  val iconTarget = (if (onboardingActive) discoveredTarget ?: targetInfo else targetInfo)?.takeIf { it.trusted }
  val selectedGameIcon = rememberDesktopGameIcon(iconTarget)
  fun gameIdentity(target: TargetGame) = SharedGameIdentity("Shattered Pixel Dungeon", target.displayVersion,
   selectedGameIcon.takeIf { target == iconTarget })

  fun homeState() = DesktopSharedHomeAdapter.state(DesktopHomeContext(
   config = config, hasSession = session != null,
   pageActive = page == Page.HOME && directoryEditor == null && renameSnapshot == null,
   busy = busy, connectingAtStartup = connectingAtStartup, appClosed = appClosed,
   connected = connected, gameStatus = gameStatus, launchingGame = launchingGame,
   pendingUpload = pending, hasConflict = conflict != null, remoteTips = remoteTips,
   selectedId = selected, selectedByUser = selectedByUser, restoreIssue = restoreIssue,
   history = graphHistory, historyPage = homeHistoryPage,
   game = targetInfo?.takeIf { it.trusted }?.let { gameIdentity(it) },
   operation = homeOperation, automaticStatus = automaticStatus, operationStatus = status,
   connectionNotice = connectionNotice, locationIssue = locationIssue,
   launchNotice = launchNotice, deviceLabel = device?.label,
   historyOwner = homeProfile, historyVerified = remoteInspectionReady,
  ))
  fun reconcileHomeSelection(retainExplicit: Boolean = true) {
   val next = desktopHomeSelection(graphHistory, selected, selectedByUser && retainExplicit)
   if (next.snapshotId != selected) restoreIssue = "正在校验实际版本…"
   selected = next.snapshotId; selectedByUser = next.explicit
  }

  fun navigate(next: Page) {
   if (next == Page.CONNECTION && page != Page.CONNECTION) {
    connectionReturnPage = if (page == Page.SETTINGS) Page.SETTINGS else if (onboardingActive) Page.PATH else Page.HOME
   }
   pathAdapter.pageChanged(next == Page.PATH)
   pageGeneration++; page = next; closeBlocked = false
  }
  suspend fun reconnectSaved(saved: AppConfig) {
   when (val restored = withContext(Dispatchers.IO) { diagnosticWork(DiagnosticAction.CONNECT, result = { if (it is SavedConnectionResult.Connected) DiagnosticResult.SUCCEEDED else DiagnosticResult.FAILED }) { restoreSavedConnection(saved) } }) {
    is SavedConnectionResult.Connected -> { password = restored.connection.password; connected = true; connectionNotice = null }
    is SavedConnectionResult.Retry -> { password = restored.connection.password; settingsMode = SettingsMode.UNLOCK; connectionNotice = restored.message }
    is SavedConnectionResult.NeedsInput -> { settingsMode = SettingsMode.UNLOCK; connectionNotice = restored.message }
   }
  }
  fun openConnection(mode: SettingsMode) {
   if (busy || connectingAtStartup || appClosed) return
   settingsMode = mode; if (config?.onboardingProgress != OnboardingProgress.COMPLETE) { onboardingActive = true; setupDraft = config ?: setupDraft }; navigate(Page.CONNECTION)
  }
  fun requestConfirmation(action: Action) {
   if (busy || connectingAtStartup || appClosed) return
   confirmationFiles=graphHistory?.currentFiles
   confirm = operationEvents.begin(action); confirmationNotice = null; navigate(Page.CONFIRM)
  }
  suspend fun refreshProfile(value: AppConfig?) {
   val sameProfile = sameDesktopHomeProfile(homeProfile, value)
   try {
    val data = withContext(Dispatchers.IO) { diagnosticWork(DiagnosticAction.INSPECT) {
     if(value==null)location.requireDirectory() else location.selected(value)
     if (value == null) null else {
      val manager = location.open(value, workspace.resolve(".local/manager"))
      val target = runCatching { manager.targetInfo() }
      LoadedProfile(manager, manager.history(), manager.hasPending(), manager.currentVersions(), target.getOrNull(), target.exceptionOrNull()?.message, manager.deviceIdentity(), manager.baselineFiles())
     }
    } }
    if (appClosed) return
    locationIssue = null; session = data?.session; history = data?.history ?: emptyList(); pending = data?.pending ?: false
    graphHistory=withContext(Dispatchers.IO){diagnosticWork(DiagnosticAction.HISTORY) { data?.session?.syncHistory() }}
    saveInfo = data?.saves; targetInfo = data?.target; targetError = data?.targetError
    // Preserve an explicit still-valid target on ordinary refresh. A different account/profile
    // starts from its sole verified tip; publication order never chooses a branch.
    reconcileHomeSelection(retainExplicit = sameProfile)
    if (!sameProfile) homeHistoryPage = 0
    homeProfile = value; remoteTips = emptySet(); conflictSelection = null; conflict = null
    device = data?.device; remoteInspectionReady = false; automatic = AutomaticUpload().apply { data?.baselineFiles?.let(::establishBaseline) }; remoteReadAt = 0L
    status = if (value == null) "存档内容待检查。" else "正在检查存档变化。"
   } catch (e: CancellationException) { throw e }
   catch (e: Exception) {
    if (appClosed) return
    session = null; history = emptyList(); graphHistory = null; saveInfo = null; targetInfo = null; pending = false
    if (!sameProfile) { selected = null; selectedByUser = false; homeHistoryPage = 0; homeProfile = null }
    locationIssue = e.message; status = e.message ?: "无法读取默认存档目录"
   }
  }
  fun loadProfile(value: AppConfig?, prepareOfficialNamespace: Boolean = false) {
   if (busy || connectingAtStartup || appClosed) return
   val generation = pageGeneration
   val secret = password
   fun current() = !appClosed && page == Page.HOME && pageGeneration == generation && config == value
   busy = true
   scope.launch {
    try {
     refreshProfile(value)
     // Step 3 has already returned HOME. Prepare only this app's empty DAV folders before
     // HOME's first remote-history read; this is neither a DAV test nor a sync decision.
     val manager = session
     if (prepareOfficialNamespace && current() && manager != null && value != null &&
         value.connectionProvider == "official" && isDesktopOfficialRoot(value.endpoint)) {
      withContext(Dispatchers.IO) {
       AppendOnlyWebDav(value.endpoint, manager.adapter.id, value.profile, value.username, secret)
        .ensureNamespace { current() && session === manager }
      }
     }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
     if (current()) status = "云端暂时无法连接。"
    } finally { busy = false }
   }
  }
  fun selectTargetGame() {
   if (busy || connectingAtStartup) return
   val saved = config ?: return
   busy = true
   scope.launch {
    var updated: AppConfig? = null
    try {
     val chosen = NativeGameChooser.choose(window, saved.targetGame)
     if (chosen != null) {
      val checked = withContext(Dispatchers.IO) {
       val candidate = ShatteredInstallationDiscovery().validateJar(Path.of(chosen))
       require(candidate.trusted) { candidate.reason }; requireNotNull(candidate.target)
      }
      val next = saved.copy(targetGame = checked.path, onboardingProgress = OnboardingProgress.PENDING)
      withContext(Dispatchers.IO) { configFile.save(next) }
      config = next; setupDraft = next; discoveredTarget = checked; onboardingActive = true; updated = next
     }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { status = "目标游戏未保存：${e.message ?: "请检查配置文件写入权限"}" }
    finally { busy = false }
    // Reload only after releasing the shared guard. This never reopens connection settings.
    updated?.let { loadProfile(it); navigate(Page.DISCOVERY) }
   }
  }
  fun launchGame() {
   if (busy || connectingAtStartup || appClosed || launchingGame) return
   val saved = config ?: return
   busy = true; launchingGame = true; launchNotice = "正在启动游戏…"
   scope.launch {
    try {
     val result = withContext(Dispatchers.IO) { diagnosticWork(DiagnosticAction.LAUNCH, result = {
      when (it) { is GameLaunchResult.Started, is GameLaunchResult.AlreadyRunning -> DiagnosticResult.SUCCEEDED; else -> DiagnosticResult.FAILED }
     }) { gameLauncher.launch(saved.targetGame, saved.selectedSaveRoot) } }
     if (!appClosed) {
      launchNotice = result.message
      when (result) {
       is GameLaunchResult.Started -> { targetInfo = result.target; gameStatus = GameProcessStatus.Running(setOf(result.pid)) }
       is GameLaunchResult.AlreadyRunning -> gameStatus = result.status
       else -> Unit
      }
     }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { launchNotice = "启动失败，请重试。" }
    finally { launchingGame = false; busy = false }
   }
  }
  fun exportDiagnosticLogs() {
   if (busy || connectingAtStartup || appClosed) return
   busy = true; diagnosticExportNotice = null
   scope.launch {
    try {
     when (val result = diagnosticExport.export(System.getProperty("user.home"))) {
      is DiagnosticExportResult.Saved -> diagnosticExportNotice = "日志已导出：${result.filename}"
      is DiagnosticExportResult.Failed -> diagnosticExportNotice = result.message
      DiagnosticExportResult.Canceled, DiagnosticExportResult.Ignored -> Unit
     }
    } finally { busy = false }
   }
  }
  fun authenticationFailed(e: Exception) {
   if (e is WebDavException && e.statusCode in setOf(401, 403)) {
    connected = false; password = ""; settingsMode = SettingsMode.UNLOCK; navigate(Page.CONNECTION)
    connectionNotice = if (e.statusCode == 401) "认证失败（HTTP 401），请检查用户名和密码。" else "服务拒绝访问（HTTP 403），请检查账号权限或重新输入密码。"
    status = "云端验证已失效，请重新输入密码。本地快照与待上传内容已保留。"
   }
  }
  fun cancelConfirmation(trace: OperationTrace) {
   if (busy || connectingAtStartup) return
   trace.record(OperationEvent.CONFIRMATION_CANCELED)
   status = if (trace.action == Action.PUSH && pending) "已取消上传，待上传快照仍保留。" else "已取消本次操作。"
   confirm = null; confirmationNotice = null; navigate(Page.HOME)
  }
  fun execute(trace: OperationTrace, isAutomatic: Boolean = false) {
   val action = trace.action
   trace.record(OperationEvent.CONFIRMATION_SUBMITTED)
   val rejected = when {
    appClosed -> OperationGuard.CLOSED
    connectingAtStartup -> OperationGuard.STARTUP
    busy -> OperationGuard.BUSY
    session == null -> OperationGuard.NO_SESSION
    else -> null
   }
   if (rejected != null) {
    trace.record(OperationEvent.GUARD_REJECTED, guard = rejected)
    confirmationNotice = when (rejected) {
     OperationGuard.CLOSED -> "操作未开始：窗口已关闭，请重新打开。"
     OperationGuard.STARTUP -> "操作未开始：正在读取存档与连接状态，请稍后重试。"
     OperationGuard.BUSY -> "操作未开始：请等待当前操作完成。"
     OperationGuard.NO_SESSION -> "操作未开始：存档尚未就绪，请返回主页重新检查存档。"
    }
    return
   }
   val manager = requireNotNull(session)
   val operationConfig = config
   val chosen = selected; val branchChoice = conflictSelection; val divergence = conflict; val account = config?.username ?: ""; val secret = password
   val allowUniqueReplacement=confirm===trace
   val expectedBefore=if(allowUniqueReplacement)confirmationFiles else null
   busy = true; homeOperation = desktopHomeOperation(action); confirm = null; confirmationNotice = null; navigate(Page.HOME)
   if (action == Action.PUSH) { if (!isAutomatic) automatic.retryAllowed(); automatic.started() }
   status = when (action) {
    Action.RESTORE, Action.RESOLVE, Action.REMOTE_TIP -> "正在校验并备份当前档案，然后恢复…"
    Action.MERGE -> "正在检查单局身份、徽章、历史和图鉴…"
    Action.PUSH -> "正在保留快照并校验云端…"
    else -> "正在处理…"
   }
   var terminalFailure: OperationFailure? = null
   trace.record(OperationEvent.OPERATION_QUEUED)
   scope.launch {
    trace.record(OperationEvent.OPERATION_STARTED)
    try {
     val result = withContext(Dispatchers.IO) { trace.withDiagnostics {
      trace.record(OperationEvent.WORK_STARTED)
      val target = manager.targetInfo()
      val stopped = {
       val fresh = gameLauncher.status(target)
       if (fresh !is GameProcessStatus.Stopped) throw SaveNotStableException(processMessage(fresh))
       fresh == GameProcessStatus.Stopped
      }
      if (action != Action.ARCHIVE) {
       val state = gameLauncher.status(target)
       if (state !is GameProcessStatus.Stopped) throw SaveNotStableException(processMessage(state))
       SaveStabilityGuard(manager.root, manager.adapter, stopped).awaitStable()
      }
      fun store() = AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, account, secret)
      // Explicit manual upload may retry failed initial app-folder preparation.
      // Background history reads and automatic upload never provision folders.
      if (action == Action.PUSH && !isAutomatic && manager.config.connectionProvider == "official" &&
          isDesktopOfficialRoot(manager.config.endpoint)) {
       store().ensureNamespace { !appClosed && session === manager && config == operationConfig }
      }
      fun pushed(result: AppendPushResult): OperationResult = when (result) {
       is AppendPushResult.Success -> OperationResult("追加提交已回读校验。" + (if (result.localDirty) "当前本地还有未上传变化；下一次同步会另建提交。" else "所选进度已成为本机同步基线。") + (if (result.remoteAhead) "另有已观察后续分支，可读取查看。" else ""))
       is AppendPushResult.Diverged -> OperationResult("发现 ${result.conflict.branches.size} 个进度分支，全部快照均已保留。请明确选择。", result.conflict)
      }
      val completed = when (action) {
       Action.CAPTURE -> manager.createSnapshot(stopped).let { OperationResult("本地快照已保存，可随时从历史中恢复。") }
       Action.PUSH -> if (!isAutomatic) pushed(manager.push(store(), stopped)) else when (val automaticResult = manager.automaticPush(store(), stopped)) {
        is AutomaticPushResult.Published -> pushed(automaticResult.result)
        is AutomaticPushResult.Skipped -> OperationResult(
         if (automaticResult.comparison.kind in setOf(OnboardingKind.BOTH_EMPTY, OnboardingKind.IDENTICAL)) "本次没有需要自动上传的变化。"
         else "自动上传已跳过：${automaticResult.comparison.blocker ?: "请检查本机与云端进度后明确选择。"}",
         automaticSkipped = true)
       }
       Action.REMOTE_TIP -> manager.restoreRemote(requireNotNull(chosen), store(), stopped).let { OperationResult("云端存档已恢复。恢复前备份已加入历史。", appliedFiles = it.restored.files) }
       Action.RESTORE -> manager.restoreHistory(requireNotNull(chosen), stopped).let { OperationResult("恢复完成，备份已加入历史。恢复提交已冻结，将自动上传；暂停时可点击上传。", appliedFiles = it.restored.files) }
       Action.CLOUD_HISTORY -> manager.restoreCloudHistory(requireNotNull(chosen),store(),allowUniqueReplacement,expectedBefore,stopped).let {OperationResult("所选云端历史已恢复，原始历史保留。恢复结果待上传。",appliedFiles=it.restored.files)}
       Action.RESOLVE -> pushed(manager.resolve(requireNotNull(divergence), requireNotNull(branchChoice), store(), stopped)).copy(appliedFiles = divergence.branches.single { it.snapshot.manifest.id == branchChoice }.snapshot.manifest.files)
       Action.MERGE -> pushed(manager.merge(requireNotNull(divergence),store(),stopped)).let { result ->
        if(result.conflict==null) result.copy(appliedFiles=manager.observedFiles()) else result
       }
       Action.MERGE_CURRENT -> pushed(manager.mergeCurrent(store(),stopped)).let {result->if(result.conflict==null)result.copy(appliedFiles=manager.observedFiles()) else result}
       Action.ARCHIVE -> { manager.archivePending(); OperationResult("待上传快照已归档。下次同步将重新采集当前存档。") }
      }
      trace.outcome(when { completed.automaticSkipped -> DiagnosticResult.SKIPPED; completed.conflict != null -> DiagnosticResult.CONFLICT; else -> DiagnosticResult.SUCCEEDED })
      trace.record(OperationEvent.WORK_SUCCEEDED)
      completed
     } }
     status = result.message; conflict = result.conflict
     conflictSelection=if(action==Action.PUSH)result.conflict?.let {value->value.branches.firstOrNull {it.snapshot.manifest.files==value.observedLocalFiles}?.snapshot?.manifest?.id} else null
     remoteReadAt = 0L
     if(action in setOf(Action.CLOUD_HISTORY,Action.REMOTE_TIP,Action.RESOLVE,Action.MERGE,Action.MERGE_CURRENT)){selectedByUser=false;selected=null}
     val now = monotonicMillis()
     if (result.automaticSkipped) {
      automatic.skipped(); automaticStatus = result.message
     } else if (action == Action.PUSH) {
      val baseline = withContext(Dispatchers.IO) { manager.baselineFiles() }
      if (baseline != null) automatic.succeeded(baseline) else automatic.failed(now, false)
     } else if (result.appliedFiles != null) automatic.applied(result.appliedFiles, now)
     else if (action == Action.ARCHIVE) {
      val files = withContext(Dispatchers.IO) { runCatching { manager.observedFiles() }.getOrNull() }
      if (files != null) automatic.applied(files, now) else automatic.retryAllowed()
     }
     if (result.conflict != null) automaticStatus = "存在多个存档分支，自动上传已暂停，点击恢复选择。"
     if (result.conflict != null && !isAutomatic) navigate(Page.CONFLICT)
    } catch (e: CancellationException) {
     terminalFailure = OperationFailure.CANCELED
     if (action == Action.PUSH) automatic.failed(monotonicMillis(), false)
     trace.record(OperationEvent.OPERATION_CANCELED, failure = terminalFailure)
     status = "本次操作已中断，已保存的历史与备份保留。请核对待上传状态后重试。"
     throw e
    } catch (e: Exception) {
     terminalFailure = operationFailure(e)
     if (action == Action.PUSH) automatic.failed(monotonicMillis(), terminalFailure == OperationFailure.NETWORK || e is SaveNotStableException)
     trace.record(OperationEvent.WORK_FAILED, failure = terminalFailure)
     status = if(action==Action.MERGE && withContext(Dispatchers.IO) { manager.retryablePending() })
      "本机合并已完成，云端上传未完成。请点击上传重试同一份合并结果；原始历史与备份保留。"
     else "操作未完成：${e.message}。已保存的历史与备份保留。"
     authenticationFailed(e)
    }
    finally {
     try {
      trace.record(OperationEvent.HISTORY_REFRESH_STARTED)
      val data = withContext(Dispatchers.IO) { trace.withDiagnostics { Triple(manager.history(), manager.hasPending(), runCatching { manager.currentVersions() }.getOrNull()) } }
      history = data.first; pending = data.second; saveInfo = data.third
      graphHistory=withContext(Dispatchers.IO){manager.syncHistory()}
      reconcileHomeSelection()
      trace.record(OperationEvent.HISTORY_REFRESH_SUCCEEDED)
     } catch (e: CancellationException) {
      if (terminalFailure == null) terminalFailure = OperationFailure.CANCELED
      trace.record(OperationEvent.HISTORY_REFRESH_FAILED, failure = OperationFailure.CANCELED)
     } catch (e: Exception) {
      val refreshFailure = operationFailure(e)
      if (terminalFailure == null) terminalFailure = refreshFailure
      trace.record(OperationEvent.HISTORY_REFRESH_FAILED, failure = refreshFailure)
      status += " 历史刷新失败：${e.message}"
     }
     homeOperation = SharedHomeOperation.NONE; busy = false
    }
   }.invokeOnCompletion { cause ->
    trace.record(OperationEvent.OPERATION_FINISHED, failure = terminalFailure ?: cause?.let(::operationFailure))
   }
  }
  fun editDirectory(change: ((AppendProfileSession, () -> Boolean) -> DirectorySnapshotMetadata)? = null) {
   if(busy || connectingAtStartup || appClosed) return
   val manager=session ?: return
   busy=true;directoryNotice=null
   scope.launch {
    try {
     val metadata=withContext(Dispatchers.IO) {
      val target=manager.targetInfo()
      val stopped={ gameLauncher.status(target) is GameProcessStatus.Stopped }
      if(change==null)manager.inspectDirectory(stopped) else change(manager,stopped)
     }
     directoryEditor=metadata;directoryName=metadata.displayName
     graphHistory=withContext(Dispatchers.IO){manager.syncHistory()}
     directoryNotice=if(change==null)null else "已保存。点击上传可同步名称和已确认身份。"
    } catch(e:Exception) { directoryNotice=e.message;status="无法更新存档信息：${e.message}" }
    finally { busy=false }
   }
  }
  fun readRemote() {
   if (busy || connectingAtStartup || appClosed || !connected) return
   val manager = session ?: return; val account = config?.username ?: ""; val secret = password
   busy = true; remoteReadAt = monotonicMillis(); remoteInspectionReady = false; status = "正在读取并校验云端快照…"
   scope.launch {
    try {
     val entries = withContext(Dispatchers.IO) { diagnosticWork(DiagnosticAction.HISTORY) { manager.readRemote(AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, account, secret)) } }
     remoteTips = entries.map { it.entry.manifest.id }.toSet(); remoteInspectionReady = true
     remoteObserved = entries.mapNotNull { remote -> manager.observedAt(remote.commit.id)?.let { remote.entry.manifest.id to it } }.toMap()
     history = withContext(Dispatchers.IO) { manager.history() }
     graphHistory=withContext(Dispatchers.IO){manager.syncHistory()}
     reconcileHomeSelection()
     status = if (entries.isEmpty()) "此次未发现云端记录。" else "已读取云端记录。"
    } catch (e: Exception) { status = "读取云端失败：${e.message}"; authenticationFailed(e) }
    finally { busy = false }
   }
  }
  fun openRestore() {
   if (busy || connectingAtStartup || appClosed) return
   if (conflict != null && (selected==null || conflict!!.branches.any {it.snapshot.manifest.id==selected})) { navigate(Page.CONFLICT); return }
   if (remoteTips.size > 1 && (selected==null || selected in remoteTips)) {
    val manager = session ?: return
    busy = true
    scope.launch {
     try {
      val found = withContext(Dispatchers.IO) {
       val target = manager.targetInfo()
       val stopped = { gameLauncher.status(target) is GameProcessStatus.Stopped }
       require(stopped()) { processMessage(gameLauncher.status(target)) }
       SaveStabilityGuard(manager.root, manager.adapter, stopped).awaitStable()
       manager.inspectConflict(AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, config?.username ?: "", password), stopped)
      }
      conflict = found; conflictSelection = null
      if (found != null) navigate(Page.CONFLICT) else status = "云端分支已变化，请等待刷新后重新选择。"
     } catch (e: Exception) { status = "暂不能选择恢复：${e.message}"; authenticationFailed(e) }
     finally { busy = false }
    }
   } else if(graphHistory?.graph?.commits?.values?.any {it.snapshot.id==selected}==true) {
    if(graphHistory?.currentProtected==true) {
     if(selected in remoteTips && (graphHistory?.clean==true || graphHistory?.currentFiles?.isEmpty()==true))execute(operationEvents.begin(Action.REMOTE_TIP))
     else execute(operationEvents.begin(Action.CLOUD_HISTORY))
    } else requestConfirmation(Action.CLOUD_HISTORY)
   }
   else requestConfirmation(Action.RESTORE)
  }
  fun setAutomaticPaused(paused: Boolean) {
   if (busy || connectingAtStartup) return
   val value = config ?: return
   busy = true
   scope.launch {
    try {
     val next = value.copy(autoUploadPaused = paused)
     withContext(Dispatchers.IO) { configFile.save(next) }
     config = next; automatic.retryAllowed()
    } catch (e: Exception) { status = "设置未保存：${e.message}" }
    finally { busy = false }
   }
  }
  suspend fun scanDiscovery() {
   val found = withContext(Dispatchers.IO) { ShatteredInstallationDiscovery().discover(setupDraft.targetGame) }
   if (appClosed) return
   discovery = found
   discoveredTarget = found.suggestedTarget
   discoveryNotice = null
  }
  fun rescanDiscovery() {
   if (busy || connectingAtStartup || appClosed) return
   busy = true
   scope.launch {
    try { scanDiscovery() }
    catch (e: Exception) { discoveryNotice = "扫描未完成：${e.message}" }
    finally { busy = false }
   }
  }
  fun chooseSetupGame() {
   if (busy || connectingAtStartup || appClosed) return
   busy = true
   scope.launch {
    try {
     val chosen = NativeGameChooser.choose(window, discoveredTarget?.path ?: setupDraft.targetGame)
     if (chosen != null) {
      val verified = withContext(Dispatchers.IO) {
       val candidate = ShatteredInstallationDiscovery().validateJar(Path.of(chosen))
       require(candidate.trusted) { candidate.reason }; requireNotNull(candidate.target)
      }
      require(verified.trusted) { "无法识别所选游戏版本：${verified.reason}" }
      withContext(Dispatchers.IO) { setupSelection.save(verified.path) }
      discoveredTarget = verified; discoveryNotice = null
     }
    } catch (e: Exception) { discoveryNotice = "未更改游戏选择：${e.message}" }
    finally { busy = false }
   }
  }
  fun continueDiscovery() {
   if (busy || connectingAtStartup || appClosed) return
   val target = discoveredTarget ?: return
   busy = true
   scope.launch {
    var proceed = false
    try {
     withContext(Dispatchers.IO) {
      require(ShatteredInstallationDiscovery().validateJar(Path.of(target.path)).target == target && target.trusted) { "游戏文件已变化，请重新选择" }
     }
     val next = setupDraft.copy(targetGame = target.path, onboardingProgress = OnboardingProgress.PENDING)
     withContext(Dispatchers.IO) { setupSelection.save(target.path) }
     if (config != null) { withContext(Dispatchers.IO) { configFile.save(next) }; config = next }
     setupDraft = next; onboardingActive = true; proceed = true
    } catch (e: Exception) { discoveryNotice = "尚不能继续：${e.message}" }
    finally { busy = false }
    if (proceed) {
     pathNavigation.beginSetup()
     navigate(Page.PATH)
    }
   }
  }
  fun continuePath() {
   if(busy || connectingAtStartup || appClosed || page != Page.PATH)return
   val selectedPath = pathAdapter.state.path
   val selectedRevision = pathAdapter.revision
   val generation = pageGeneration
   fun current() = !appClosed && page == Page.PATH && pageGeneration == generation &&
    pathAdapter.revision == selectedRevision && pathAdapter.state.path == selectedPath
   if (selectedPath.isBlank() || !pathAdapter.state.canContinue) return
   busy=true
   scope.launch {
    var proceed=false
    try {
     val root=withContext(Dispatchers.IO) { location.requireSelected(selectedPath,true) }
     val next=setupDraft.copy(selectedSaveRoot=root.toString(),onboardingProgress=OnboardingProgress.PENDING)
     withContext(Dispatchers.IO) {
      // Validate the selected directory before persisting it or advancing.
      inspectSelectedSaveDirectory(root)
      if(config!=null)configFile.save(next)
     }
     if (current()) {
      setupDraft=next;if(config!=null)config=next
      onboardingActive=true;pathNavigation.beginSetup();proceed=true
     }
    } catch(e:CancellationException) { throw e }
    catch(e:Exception) { if (current()) pathAdapter.showError(e.message) }
    finally { busy=false }
    if(proceed && current()){settingsMode=SettingsMode.FULL;navigate(Page.CONNECTION)}
   }
  }
  LaunchedEffect(Unit) {
   // No observation/upload engine is released until target and onboarding progress are known.
   try {
    val saved = config
    if (saved == null) {
     onboardingActive = true; settingsMode = SettingsMode.FULL
     scanDiscovery(); page = Page.DISCOVERY
     initialResult.exceptionOrNull()?.let { discoveryNotice = "旧设置无法读取，原文件保留。请重新检查游戏并配置连接。" }
     if (initialResult.isSuccess && System.getProperty("pdsync.workspace") == null && java.nio.file.Files.exists(Path.of(".local/config.json"))) {
      discoveryNotice = "发现旧启动目录中的设置，原文件已保留。本次使用固定用户目录，请重新填写连接。"
     }
    } else {
     refreshProfile(saved)
     if (session == null || targetInfo?.trusted != true) {
      onboardingActive = true; setupDraft = saved.copy(onboardingProgress = OnboardingProgress.PENDING)
      scanDiscovery(); page = Page.DISCOVERY
     } else {
      // Explicit configuration completion survives restart without claiming sync ancestry.
      // Only legacy settings use a verified baseline to infer a completed configuration.
      val hasBaseline = withContext(Dispatchers.IO) { session?.baselineFiles() != null }
      val progress = OnboardingGate.resumedProgress(saved.onboardingProgress, targetInfo?.trusted == true, hasBaseline)
      val resumed = saved.copy(onboardingProgress = progress)
      if (resumed != saved) withContext(Dispatchers.IO) { configFile.save(resumed) }
      config = resumed; setupDraft = resumed; onboardingActive = progress != OnboardingProgress.COMPLETE
      reconnectSaved(resumed)
      // Incomplete setup resumes its connection step. Completed setup can stay on HOME
      // even while offline; reconnecting remains an explicit HOME/settings action.
      if (onboardingActive) navigate(Page.CONNECTION) else navigate(Page.HOME)
     }
    }
   } catch (e: CancellationException) { throw e }
   catch (e: Exception) {
    onboardingActive = true; page = Page.DISCOVERY
    discoveryNotice = "启动检查未完成：${e.message}。请重新扫描；没有上传或覆盖存档。"
   } finally { connectingAtStartup = false }
  }
  LaunchedEffect(page, pathAdapter.revision, busy, connectingAtStartup) {
   if (page == Page.PATH && !busy && !connectingAtStartup && !appClosed) {
    val generation = pageGeneration
    val preview = pathAdapter.beginPreview() ?: return@LaunchedEffect
    delay(350)
    val result = try {
     Result.success(withContext(Dispatchers.IO) { diagnosticWork(DiagnosticAction.INSPECT) { pathAdapter.inspect(preview) } })
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }
    val continueRequested = pathAdapter.completePreview(preview, result)
    if (continueRequested && !appClosed && page == Page.PATH && pageGeneration == generation &&
     pathAdapter.state.path == preview.path && pathAdapter.revision == preview.revision &&
     !busy && !connectingAtStartup) continuePath()
   }
  }
  // Keep launch availability fresh even when disconnected or automatic upload is paused.
  LaunchedEffect(targetInfo) {
   while (isActive && !appClosed) {
    if (!busy && !connectingAtStartup) {
     val target = targetInfo
     val observed = withContext(Dispatchers.IO) { gameLauncher.status(target) }
     if (!busy && !connectingAtStartup && targetInfo == target && !appClosed) {
      if (gameStatus is GameProcessStatus.Running && observed is GameProcessStatus.Stopped) launchNotice = null
      gameStatus = observed
     }
    }
    delay(1000)
   }
  }
  LaunchedEffect(session, connected) {
   while (isActive && !appClosed) {
    delay(1000)
    if (busy || connectingAtStartup || page != Page.HOME || onboardingActive || config?.onboardingProgress != OnboardingProgress.COMPLETE) continue
    val manager = session ?: continue
    if (connected && !remoteInspectionReady) {
     automaticStatus = "云端检查未完成，自动上传暂停。"
     if (remoteReadAt == 0L || monotonicMillis() - remoteReadAt >= 60000) readRemote()
     continue
    }
    try {
     val target = targetInfo
     val observation = withContext(Dispatchers.IO) {
      val state = gameLauncher.status(target)
      state to manager.observedFiles()
     }
     if (busy || connectingAtStartup || page != Page.HOME || onboardingActive || config?.onboardingProgress != OnboardingProgress.COMPLETE || !remoteInspectionReady || session !== manager) continue
     val state = observation.first; val files = observation.second; val now = monotonicMillis()
     if(graphHistory?.currentFiles!=files)graphHistory=withContext(Dispatchers.IO){manager.syncHistory()}
     gameStatus = state
     automatic.observe(files, state is GameProcessStatus.Running, state is GameProcessStatus.Stopped, now)
     val retryable = withContext(Dispatchers.IO) { manager.retryablePending() }
     if (busy || connectingAtStartup || page != Page.HOME || onboardingActive || config?.onboardingProgress != OnboardingProgress.COMPLETE || !remoteInspectionReady || session !== manager || appClosed) continue
     automaticStatus = when {
      config?.autoUploadPaused == true -> "自动上传已暂停；仍可手动上传。"
      !connected -> "请先连接云端。"
      state !is GameProcessStatus.Stopped -> processMessage(state)
      pending && !retryable -> "有待检查的中断操作，请在设置中处理。"
      conflict != null || remoteTips.size > 1 -> "云端有多份进度，请点击恢复后选择。"
      automatic.needsAttention -> "自动上传暂停，请处理下方问题后重试。"
      automatic.waitingForRetry -> "上次上传未完成，稍后重试。"
      retryable -> "等待存档稳定后重试上传。"
      else -> "自动上传已开启。"
     }
     if (automatic.shouldUpload(now, config?.autoUploadPaused != true && OnboardingGate.allowsAutomatic(config?.onboardingProgress, onboardingActive, remoteInspectionReady), connected, state is GameProcessStatus.Stopped, retryable, conflict != null || remoteTips.size > 1, busy)) {
      val baseline = withContext(Dispatchers.IO) { manager.baselineFiles() }
      if (busy || connectingAtStartup || page != Page.HOME || onboardingActive || config?.onboardingProgress != OnboardingProgress.COMPLETE || !remoteInspectionReady || session !== manager || !connected || appClosed) continue
      if (!retryable && files == baseline) automatic.succeeded(files)
      else execute(operationEvents.begin(Action.PUSH, automatic = true), isAutomatic = true)
     } else if (connected && !busy && (remoteReadAt == 0L || now - remoteReadAt >= 60000)) readRemote()
     observationDiagnostics.recovered()
    } catch (e: CancellationException) { throw e }
    catch (failure: Exception) {
     observationDiagnostics.failed(failure)
     automaticStatus = "存档正在变化或暂不可读，自动上传等待中。"
    }
   }
  }
  LaunchedEffect(session, selected, busy) {
   restoreIssue = "正在校验实际版本…"
   val manager = session; val id = selected
   if (manager != null && id != null && !busy) restoreIssue = withContext(Dispatchers.IO) {
    val cloud=graphHistory?.graph?.commits?.values?.firstOrNull {it.snapshot.id==id}
    runCatching { if(cloud==null)manager.checkRestore(id) else cloud.snapshot.saveVersions?.let {ShatteredVersions.requireCompatible(it,requireNotNull(targetInfo){"请选择游戏"})} }.exceptionOrNull()?.message
   }
  }
  LaunchedEffect(session, conflict, conflictSelection, busy) {
   conflictRestoreIssue = "正在校验实际版本…"
   val manager = session; val value = conflict; val id = conflictSelection
   if (manager != null && value != null && id != null && !busy) conflictRestoreIssue = withContext(Dispatchers.IO) { runCatching { manager.checkResolution(id) }.exceptionOrNull()?.message }
  }
  MaterialTheme(colors = lightColors(primary = green, secondary = Color(0xFF8A6B42)), shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp))) {
   renameSnapshot?.let { id ->
    SharedNamingDialog(SharedNamingState(id.hashCode().toLong(), historyName, busy = busy,
     notice = "保存在此设备，不改写已发布的历史。"),
     onNameChange = { historyName = it },
     onSave = { name -> busy=true;scope.launch {try{withContext(Dispatchers.IO){session!!.renameHistory(id,name)};graphHistory=withContext(Dispatchers.IO){session!!.syncHistory()};renameSnapshot=null}catch(e:Exception){status=e.message.orEmpty()}finally{busy=false}} },
     onDismiss = { renameSnapshot=null })
   }
   directoryEditor?.let { metadata ->
    SharedNamingDialog(SharedNamingState(System.identityHashCode(metadata).toLong(), directoryName,
     busy = busy, notice = directoryNotice.orEmpty()),
     onNameChange = { directoryName=it },
     onSave = { name -> editDirectory { manager,stopped -> manager.renameCurrent(name,stopped) } },
     onDismiss = { directoryEditor=null }) {
      // Identity decisions remain the existing host maintenance actions, with their original proof.
      if (metadata.runs.values.any { it.runUUID == null }) {
       Text("不能确定是否仍为原来的一局时，请保留待确认。", style=MaterialTheme.typography.caption)
       metadata.runs.forEach { (slot,run) ->
        if(run.runUUID==null) {
         Text("$slot · 身份待确认", fontWeight=FontWeight.Medium)
         Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
          if(run.previousRunUUID!=null) TextButton(enabled=!busy,onClick={
           editDirectory { manager,stopped ->
            val files=manager.observedFiles();require(contentSha256(files)==metadata.contentSha256){"存档已变化，请关闭后重新检查"}
            manager.confirmRunIdentity(slot,files,true,stopped)
           }
          }) { Text("同一局继续") }
          TextButton(enabled=!busy,onClick={
           editDirectory { manager,stopped ->
            val files=manager.observedFiles();require(contentSha256(files)==metadata.contentSha256){"存档已变化，请关闭后重新检查"}
            manager.confirmRunIdentity(slot,files,false,stopped)
           }
          }) { Text("这是新的一局") }
         }
        }
       }
      }
    }
   }
   Surface(Modifier.fillMaxSize(), color = Color(0xFFF3F5F1)) {
    Column(Modifier.fillMaxSize()) {
     if (closeBlocked) Surface(color = Color(0xFFFFECCD)) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
       Text(if (busy || connectingAtStartup) "请等待当前操作结束，再关闭窗口。备份、恢复或连接验证仍在进行。" else "操作已结束，现在可以关闭窗口。", Modifier.weight(1f), color = Color(0xFF75572C), style = MaterialTheme.typography.body2)
       TextButton(onClick = { closeBlocked = false }) { Text("知道了") }
      }
     }
     Box(Modifier.weight(1f).fillMaxWidth()) {
      when (page) {
       Page.DISCOVERY -> {
        val gamePageGeneration = pageGeneration
        val selectedGame = discoveredTarget?.takeIf { it.trusted }
        val discoveryState = SharedGameSelectionState(
         game = selectedGame?.let { gameIdentity(it) },
         selectionId = selectedGame?.path,
         location = selectedGame?.path.orEmpty(),
         rows = listOf(
          SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_FILE, if (selectedGame == null) "选择游戏 JAR…" else "重新选择"),
         ),
         candidates = discovery?.trustedTargets?.takeIf { discovery?.requiresSelection == true }.orEmpty().map {
          SharedGameCandidate(it.path, gameIdentity(it), it.path)
         },
         pageActive = !appClosed && page == Page.DISCOVERY,
         busy = busy || connectingAtStartup,
         canContinue = selectedGame != null,
         notice = discoveryNotice?.let { NoviceCopy.feedback(it, "检查未完成，请重新选择游戏或重试。") }
          ?: DiscoveryCopy.game(discovery, discoveredTarget),
         noticeError = discoveryNotice != null,
         details = buildList {
          add("存档位置：${pathAdapter.state.path}")
          discovery?.defaultSave?.reason?.let { add(it) }
          discovery?.diagnostics?.let { addAll(it) }
          discoveryNotice?.let { add(it) }
         }.joinToString("\n"),
        )
        SharedGameSelectionScreen(discoveryState, object : SharedGameSelectionActions {
         private fun canAct() = !busy && !connectingAtStartup && !appClosed && page == Page.DISCOVERY && pageGeneration == gamePageGeneration
         override fun perform(control: SharedGameSelectionControl) {
          if (!canAct()) return
          when (control) {
           SharedGameSelectionControl.CHOOSE_FILE -> chooseSetupGame()
           SharedGameSelectionControl.RESCAN -> rescanDiscovery()
           else -> Unit
          }
         }
         override fun chooseCandidate(id: String) {
          if (!canAct()) return
          val candidate = discovery?.trustedTargets?.singleOrNull { it.path == id && it.trusted } ?: return
          discoveredTarget = candidate; discoveryNotice = null
         }
         override fun next() { if (canAct()) continueDiscovery() }
        })
       }
       Page.PATH -> key(pageGeneration) {
        val generation = pageGeneration
        fun current() = !appClosed && page == Page.PATH && pageGeneration == generation
        SharedSavePathScreen(pathAdapter.state.copy(busy = busy || connectingAtStartup), DesktopSavePathActions(
        adapter = pathAdapter,
        canAct = { !busy && !connectingAtStartup && current() },
        choose = {
         busy = true
         scope.launch {
          pathAdapter.chooseDirectoryUsing(
           choose = { NativeFolderChooser.choose(it) },
           canPublish = ::current,
           onBusyChanged = { busy = it }
          )
         }
        },
        goBack = {
         val previous = pathNavigation.cancel(pathAdapter)
         if (previous == null) navigate(Page.DISCOVERY) else {
          setupDraft = previous.setupDraft; onboardingActive = previous.onboardingActive
          navigate(Page.SETTINGS)
         }
        },
        continuePath = { continuePath() }
       ), game = (discoveredTarget ?: targetInfo)?.takeIf { it.trusted }?.let {
        gameIdentity(it)
       })
       }
       Page.HOME -> SharedHomeScreen(homeState(), object : SharedHomeActions {
        override fun settings() { if (homeState().canInteract) navigate(Page.SETTINGS) }
        override fun upload() {
         if (!homeState().uploadEnabled) return
         if (!connected) openConnection(if (config == null) SettingsMode.FULL else SettingsMode.UNLOCK)
         else execute(operationEvents.begin(Action.PUSH))
        }
        override fun restore() {
         if (!homeState().restoreEnabled) return
         openRestore()
        }
        override fun launch() { if (homeState().launchEnabled) launchGame() }
        override fun selectSnapshot(snapshotId: String) {
         val current = homeState()
         if (current.canInteract && current.history?.nodes?.any { it.selectable && it.snapshotId == snapshotId } == true) {
          if (selected != snapshotId) restoreIssue = "正在校验实际版本…"
          selected = snapshotId; selectedByUser = true
         }
        }
        override fun rename(snapshotId: String?) {
         val current = homeState()
         if (!current.canInteract || session == null || current.history?.nodes?.any {
           if (snapshotId == null) it.id == "local-current" else it.snapshotId == snapshotId
          } != true) return
         if (snapshotId == null) editDirectory()
         else { renameSnapshot = snapshotId; historyName = graphHistory?.aliases?.get(snapshotId).orEmpty() }
        }
        override fun changeHistoryPage(page: Int) {
         val current = homeState()
         val presentation = current.history ?: return
         if (current.canInteract && page in 0 until presentation.pages) homeHistoryPage = page
        }
       })
       Page.SETTINGS -> key(pageGeneration) {
        val generation = pageGeneration
        val initialStatus = remember { status }
        var managementOpen by remember { mutableStateOf(false) }
        var managementGeneration by remember { mutableStateOf(0L) }
        fun current() = !appClosed && page == Page.SETTINGS && pageGeneration == generation
        fun settingsContext() = DesktopSettingsContext(
         config = config,
         game = targetInfo?.let { gameIdentity(it) },
         savePath = pathAdapter.state.path, hasSession = session != null, connected = connected,
         pendingUpload = pending, currentName = graphHistory?.currentName.orEmpty(),
         pageActive = current(), busy = busy, connectingAtStartup = connectingAtStartup, appClosed = appClosed,
         managementOpen = managementOpen, editorOpen = directoryEditor != null || renameSnapshot != null,
         targetError = targetError, locationIssue = locationIssue,
         operationNotice = status.takeIf { it != initialStatus }, diagnosticExportNotice = diagnosticExportNotice,
        )
        fun settingsState() = DesktopSharedSettingsAdapter.state(settingsContext())
        SharedSettingsScreen(settingsState(), DesktopSharedSettingsActions(
         state = ::settingsState, pageCurrent = ::current,
         performAction = { action ->
          diagnosticExportNotice = null
          when (action) {
           SharedSettingsKey.GAME -> selectTargetGame()
           SharedSettingsKey.SAVE_PATH -> {
            val savedPath = config?.selectedSaveRoot?.takeIf { it.isNotBlank() } ?: pathAdapter.state.path
            pathNavigation.beginSettings(pathAdapter, savedPath, setupDraft, onboardingActive)
            setupDraft = config ?: newConnectionConfig(); navigate(Page.PATH)
           }
           SharedSettingsKey.CLOUD_CONNECTION -> openConnection(SettingsMode.FULL)
           SharedSettingsKey.AUTOMATIC_UPLOAD -> setAutomaticPaused(config?.autoUploadPaused != true)
           SharedSettingsKey.RECHECK_SAVES -> loadProfile(config)
           SharedSettingsKey.REVIEW_SETUP -> {
            onboardingActive = true; setupDraft = config ?: newConnectionConfig(); navigate(Page.DISCOVERY); rescanDiscovery()
           }
           SharedSettingsKey.EXPORT_LOGS -> exportDiagnosticLogs()
           SharedSettingsKey.MAINTENANCE -> { managementGeneration++; managementOpen = true }
           else -> Unit
          }
         },
         goBack = { navigate(Page.HOME) },
        ))
        if (managementOpen) {
         val dialogGeneration = managementGeneration
         fun dialogCurrent() = current() && managementOpen && managementGeneration == dialogGeneration
         fun dismissManagement() { if (dialogCurrent() && !busy && !connectingAtStartup) managementOpen = false }
         val maintenance = DesktopSettingsMaintenanceActions(::settingsContext, ::dialogCurrent) { action ->
          managementOpen = false
          when (action) {
           DesktopSettingsMaintenance.EDIT_DIRECTORY -> editDirectory()
           DesktopSettingsMaintenance.ARCHIVE_PENDING -> requestConfirmation(Action.ARCHIVE)
          }
         }
         AlertDialog(onDismissRequest = ::dismissManagement, title = { Text("存档管理") },
          text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {
           TextButton(enabled = settingsContext().maintenanceEnabled(DesktopSettingsMaintenance.EDIT_DIRECTORY),
            onClick = { maintenance.perform(DesktopSettingsMaintenance.EDIT_DIRECTORY) }) { Text("存档名称与单局身份") }
           if (pending) TextButton(enabled = settingsContext().maintenanceEnabled(DesktopSettingsMaintenance.ARCHIVE_PENDING),
            onClick = { maintenance.perform(DesktopSettingsMaintenance.ARCHIVE_PENDING) }) { Text("检查未完成的上传") }
           Details {
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
             Text(processMessage(gameStatus))
             listOfNotNull(targetError, locationIssue, directoryNotice, diagnosticExportNotice,
              status.takeIf { it != initialStatus }).filter(String::isNotBlank).distinct().forEach { Text(it) }
            } }
           }
          } },
          confirmButton = { TextButton(enabled = !busy && !connectingAtStartup,
           onClick = ::dismissManagement) { Text("关闭") } })
        }
       }
       Page.CONNECTION -> key(pageGeneration) {
        val generation = pageGeneration
        fun current() = !appClosed && page == Page.CONNECTION && pageGeneration == generation
        DesktopSharedConnectionPage(configFile, if (onboardingActive) setupDraft else config, password,
         if (connectionReturnPage == Page.PATH) ConnectionEntryModel.Origin.SETUP else ConnectionEntryModel.Origin.SETTINGS,
         game = (discoveredTarget ?: targetInfo)?.takeIf { it.trusted }?.let {
          gameIdentity(it)
         },
         onBusy = { if (current()) busy = it },
         onDismiss = { if (current() && !busy && !connectingAtStartup) navigate(connectionReturnPage) },
         onApplied = { applied, note ->
          if (current()) {
           val prepareNamespace = onboardingActive || !sameDesktopHomeProfile(config, applied.config)
           config = applied.config; setupDraft = applied.config; password = applied.password; connected = true
           connectionNotice = note
           // Connection completion only changes configuration. HOME owns history and save actions.
           onboardingActive = false
           status = "配置完成。"
           automaticStatus = "正在读取存档历史；手动上传或恢复可在主页操作。"
           navigate(Page.HOME)
           loadProfile(applied.config, prepareOfficialNamespace = prepareNamespace)
          }
         })
       }
       Page.CONFLICT -> conflict?.let { value ->
        ConflictPage(value, conflictSelection, conflictRestoreIssue, busy || connectingAtStartup, connected,
         game = (discoveredTarget ?: targetInfo)?.takeIf { it.trusted }?.let {
          gameIdentity(it)
         }, pending = pending,
         onSelect = { if (!busy && !connectingAtStartup) conflictSelection = it },
         onBack = { if (!busy && !connectingAtStartup) navigate(Page.HOME) },
         onConfirm = { execute(operationEvents.begin(Action.RESOLVE)) },
         onMerge = { execute(operationEvents.begin(Action.MERGE)) })
       }
       Page.CONFIRM -> confirm?.let { trace ->
        ConfirmationPage(trace.id.hashCode().toLong(), trace.action, session?.root?.toString(), pending, conflictSelection, if (trace.action == Action.RESOLVE) conflict?.branches?.firstOrNull { it.snapshot.manifest.id == conflictSelection }?.snapshot?.manifest else selection?.manifest, busy || connectingAtStartup, confirmationNotice,
         onBack = { cancelConfirmation(trace) },
         onConfirm = { execute(trace) },
         onMerge = if(trace.action==Action.CLOUD_HISTORY)({execute(operationEvents.begin(Action.MERGE_CURRENT))}) else null)
       }
      }
     }
    }
   }
  }
 }
}

/** A normal page in the root window: content may scroll; navigation and actions never do. */
@Composable
private fun GuidedPage(title: String, subtitle: String, footer: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
 Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Text("像素地牢  /  存档同步", color = muted, style = MaterialTheme.typography.caption)
   Text(title, style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
   if (subtitle.isNotBlank()) Text(subtitle, color = muted, style = MaterialTheme.typography.body2)
  }
  Divider()
  Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
   Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(), content = content)
  }
  Divider()
  Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp), content = footer)
 }
}

@Composable
private fun Details(label: String = "详情", resetKey: Any? = Unit, content: @Composable ColumnScope.() -> Unit) {
 var state by remember(resetKey) { mutableStateOf(DetailsState()) }
 Column {
  TextButton(onClick = { state = state.toggle() }) { Text(if (state.expanded) "收起$label" else label) }
  if (state.expanded) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
 }
}

@Composable
private fun CompactFeedback(raw: String, fallback: String) {
 val summary = NoviceCopy.feedback(raw, fallback)
 if (summary.isNotBlank()) Text(summary, color = muted, style = MaterialTheme.typography.body2)
 if (summary != raw) Details("问题详情", raw) { SelectionContainer { Text(raw, style = MaterialTheme.typography.caption) } }
}

/** The same controller and composable are used by Android; this host only supplies desktop I/O. */
@Composable
private fun DesktopSharedConnectionPage(
 configFile: ConfigFile,
 initial: AppConfig?,
 currentPassword: String,
 origin: ConnectionEntryModel.Origin,
 game: SharedGameIdentity?,
 onBusy: (Boolean) -> Unit,
 onDismiss: () -> Unit,
 onApplied: (AppliedConnection, String?) -> Unit
) {
 val adapter = remember { DesktopConnectionAdapter(initial, currentPassword, configFile, workspace.resolve(".local/official-account-cache")) }
 val io = remember { Executors.newSingleThreadExecutor { task -> Thread(task, "pdsync-connection").apply { isDaemon = true } } }
 val ui = remember { Executor { task -> if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeLater(task) } }
 val controller = remember { SharedConnectionController(origin, adapter.initialConnection, adapter.profile, adapter, io, ui) }
 val latestDismiss by rememberUpdatedState(onDismiss)
 val latestApplied by rememberUpdatedState(onApplied)
 val latestBusy by rememberUpdatedState(onBusy)
 DisposableEffect(controller) {
  controller.attach(object : SharedConnectionHost {
   override fun onBusyChanged(busy: Boolean) { latestBusy(busy) }
   override fun onBack(destination: ConnectionEntryModel.Back) { latestBusy(false); latestDismiss() }
   override fun onCommitted(config: SharedConnectionConfig) {
    latestBusy(false)
    adapter.appliedConnection?.let { latestApplied(it, null) }
   }
  })
  // Account/appreciation state belongs to this page, independent of native window focus.
  controller.foreground(true)
  onDispose {
   controller.foreground(false)
   controller.detach()
   controller.close()
   adapter.close()
   io.shutdownNow()
   latestBusy(false)
  }
 }
 SharedConnectionScreen(controller, game)
}

@Composable
private fun ConflictPage(value: AppendConflict, selected: String?, restoreIssue: String?, busy: Boolean, connected: Boolean,
 game: SharedGameIdentity?, pending: Boolean, onSelect: (String) -> Unit, onBack: () -> Unit,
 onConfirm: () -> Unit, onMerge: () -> Unit) {
 val state = SharedBranchChoiceState(
  observation = value,
  branches = value.branches.map { branch ->
   val manifest = branch.snapshot.manifest
   SharedBranchChoice(manifest.id,
    manifest.directoryMetadata?.displayName?.takeIf(String::isNotBlank) ?: manifest.metadata?.device?.label ?: "设备未知",
    listOfNotNull(manifest.metadata?.device?.label,
     pdsync.presentation.HistoryPresentation.time(manifest.metadata?.latestFileModifiedAt),
     "${manifest.files.size} 个文件", "与当前本机进度一致".takeIf { manifest.files == value.observedLocalFiles })
     .joinToString(" · "))
  },
  selectedId = selected, game = game, pageActive = true, busy = busy, connected = connected,
  observationCurrent = true, resolutionBlocked = pending,
  overwriteSupported = restoreIssue == null, mergeSupported = true,
  notice = if (pending) "有未完成操作，请返回主页处理。" else if (selected != null) restoreIssue.orEmpty() else "",
 )
 SharedBranchChoiceScreen(state, object : SharedBranchChoiceActions {
  override fun select(snapshotId: String) = onSelect(snapshotId)
  override fun back() = onBack()
  override fun refresh() = onBack()
  override fun overwrite(snapshotId: String) { if (snapshotId == selected) onConfirm() }
  override fun merge() = onMerge()
  override fun preserveLocal() = Unit
 })
}

@Composable
private fun ConfirmationPage(token: Long, action: Action, root: String?, pending: Boolean, branch: String?, snapshot: Manifest?, busy: Boolean, notice: String?, onBack: () -> Unit, onConfirm: () -> Unit,onMerge:(()->Unit)?=null) {
 val restoring = action in setOf(Action.RESTORE, Action.RESOLVE, Action.REMOTE_TIP,Action.CLOUD_HISTORY)
 val message = when {
  action == Action.ARCHIVE -> "只移除待上传标记，历史保留，不删除云端内容。若此前恢复中断，请先核对当前档案与备份；归档也会解除中断恢复保护，下次同步将重新采集当前档案。"
  restoring -> listOfNotNull(snapshot?.let { "所选存档：${it.metadata?.device?.label ?: "设备未知"} · ${snapshotBriefTime(it)}" },
   NoviceCopy.restoreWarning, "请退出游戏，并暂停其他存档同步。").joinToString("\n\n")
  action == Action.PUSH && pending -> "将重试上次保存的内容，可能不是当前最新进度。"
  else -> "确认继续此操作？"
 }
 SharedConnectionTheme {
  SharedConfirmationDialog(SharedConfirmationState(token,
   title = if (onMerge != null) "如何处理冲突？" else if (restoring) "恢复存档" else if (action == Action.ARCHIVE) "结束待上传操作" else "操作前确认",
   message = message, confirmLabel = if (restoring) "覆盖本机" else "确认继续",
   confirmEnabled = true, secondaryLabel = if (onMerge != null) "合并" else null,
   secondaryEnabled = onMerge != null, busy = busy, details = notice.orEmpty()),
   onConfirm = { onConfirm() }, onSecondary = { onMerge?.invoke() }, onDismiss = { onBack() })
 }
}

private fun monotonicMillis() = System.nanoTime() / 1_000_000
private fun processMessage(status: GameProcessStatus): String = when (status) {
 is GameProcessStatus.Stopped -> "当前账户未检测到游戏，等待存档稳定。"
 is GameProcessStatus.Running -> "检测到游戏正在运行，退出后自动上传。"
 is GameProcessStatus.Unknown -> if (status.reason.startsWith("无法确认游戏已退出")) status.reason else "无法确认游戏已退出：${status.reason}"
}
private fun DeviceIdentity.displayLabel() = "$label · ${id.take(8)}"
private fun localTime(value: String): String = runCatching {
 java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(value))
}.getOrElse { "时间未知" }
private fun snapshotBriefTime(manifest: Manifest): String =
 manifest.metadata?.latestFileModifiedAt?.let { "存档修改 ${localTime(it)}" }
  ?: manifest.metadata?.capturedAt?.let { "保存时间 ${localTime(it)}" } ?: "时间未知"
