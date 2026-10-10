package pdsync

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import java.nio.file.Path

private val workspace = applicationWorkspace()
private typealias Action = OperationAction
private enum class SettingsMode { FULL, UNLOCK }
private enum class Page { HOME, SETTINGS, DISCOVERY, PATH, CONNECTION, COMPARISON, CONFLICT, CONFIRM }
private enum class SetupAction { UPLOAD, RESTORE, ADOPT }
private data class OperationResult(val message: String, val conflict: AppendConflict? = null, val appliedFiles: Map<String, String>? = null)
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
  var pathText by remember { mutableStateOf(config?.selectedSaveRoot ?: pathResult.getOrNull()?.toString().orEmpty()) }
  var pathPreview by remember { mutableStateOf<SaveSummary?>(null) }
  var pathNotice by remember { mutableStateOf<String?>(null) }
  var password by remember { mutableStateOf("") }
  var connected by remember { mutableStateOf(false) }
  var connectionNotice by remember { mutableStateOf<String?>(null) }
  var page by remember { mutableStateOf(if (config == null) Page.DISCOVERY else Page.HOME) }
  var pageGeneration by remember { mutableStateOf(0L) }
  var onboardingActive by remember { mutableStateOf(config == null || config?.onboardingProgress in setOf(OnboardingProgress.PENDING, OnboardingProgress.DEFERRED)) }
  var setupDraft by remember { mutableStateOf(config ?: newConnectionConfig().copy(targetGame = runCatching { setupSelection.load() }.getOrNull().orEmpty())) }
  var discovery by remember { mutableStateOf<InstallationDiscoveryResult?>(null) }
  var discoveredTarget by remember { mutableStateOf<TargetGame?>(null) }
  var discoveryNotice by remember { mutableStateOf<String?>(null) }
  var comparison by remember { mutableStateOf<OnboardingComparison?>(null) }
  var comparisonNotice by remember { mutableStateOf<String?>(null) }
  var comparisonSelection by remember { mutableStateOf<String?>(null) }
  var comparisonCheckedAt by remember { mutableStateOf<String?>(null) }
  var setupConfirm by remember { mutableStateOf<SetupAction?>(null) }
  var remoteInspectionReady by remember { mutableStateOf(false) }
  var settingsMode by remember { mutableStateOf(SettingsMode.FULL) }
  var session by remember { mutableStateOf<AppendProfileSession?>(null) }
  var saveInfo by remember { mutableStateOf<SaveVersions?>(null) }
  var targetInfo by remember { mutableStateOf<TargetGame?>(null) }
  var targetError by remember { mutableStateOf<String?>(null) }
  var history by remember { mutableStateOf(emptyList<HistoryEntry>()) }
  var selected by remember { mutableStateOf<String?>(null) }
  var remoteTips by remember { mutableStateOf(emptySet<String>()) }
  var conflictSelection by remember { mutableStateOf<String?>(null) }
  var pending by remember { mutableStateOf(false) }
  var conflict by remember { mutableStateOf<AppendConflict?>(null) }
  var directoryEditor by remember { mutableStateOf<DirectorySnapshotMetadata?>(null) }
  var directoryName by remember { mutableStateOf("") }
  var directoryNotice by remember { mutableStateOf<String?>(null) }
  var confirm by remember { mutableStateOf<OperationTrace?>(null) }
  val operationEvents = remember { OperationEvents(workspace.resolve(".local/operation-events.log")) }
  var confirmationNotice by remember { mutableStateOf<String?>(null) }
  var restoreIssue by remember { mutableStateOf<String?>("请选择快照") }
  var conflictRestoreIssue by remember { mutableStateOf<String?>("尚未校验") }
  var locationIssue by remember { mutableStateOf<String?>(pathResult.exceptionOrNull()?.message) }
  var status by remember { mutableStateOf("正在检查默认存档位置…") }
  var gameStatus by remember { mutableStateOf<GameProcessStatus>(GameProcessStatus.Unknown("请在设置中选择官方游戏 JAR")) }
  var automaticStatus by remember { mutableStateOf("正在建立本地观察基线…") }
  var automatic by remember { mutableStateOf(AutomaticUpload()) }
  var remoteReadAt by remember { mutableStateOf(0L) }
  var device by remember { mutableStateOf<DeviceIdentity?>(null) }
  var remoteObserved by remember { mutableStateOf(emptyMap<String, String>()) }
  val scope = rememberCoroutineScope()
  val selection = history.firstOrNull { it.manifest.id == selected }
  val homeScroll = rememberScrollState()
  val historyScroll = rememberLazyListState()

  fun navigate(next: Page) {
   pageGeneration++; page = next; closeBlocked = false
  }
  suspend fun reconnectSaved(saved: AppConfig) {
   when (val restored = withContext(Dispatchers.IO) { restoreSavedConnection(saved) }) {
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
   confirm = operationEvents.begin(action); confirmationNotice = null; navigate(Page.CONFIRM)
  }
  suspend fun refreshProfile(value: AppConfig?) {
   try {
    val data = withContext(Dispatchers.IO) {
     if(value==null)location.requireDirectory() else location.selected(value)
     if (value == null) null else {
      val manager = location.open(value, workspace.resolve(".local/manager"))
      val target = runCatching { manager.targetInfo() }
      LoadedProfile(manager, manager.history(), manager.hasPending(), manager.currentVersions(), target.getOrNull(), target.exceptionOrNull()?.message, manager.deviceIdentity(), manager.baselineFiles())
     }
    }
    if (appClosed) return
    locationIssue = null; session = data?.session; history = data?.history ?: emptyList(); pending = data?.pending ?: false
    saveInfo = data?.saves; targetInfo = data?.target; targetError = data?.targetError
    // A newest history entry can be an empty pre-restore backup. Start unselected;
    // a sole verified remote tip (or the user's click) supplies the normal selection.
    selected = null; remoteTips = emptySet(); conflictSelection = null; conflict = null
    device = data?.device; remoteInspectionReady = false; automatic = AutomaticUpload().apply { data?.baselineFiles?.let(::establishBaseline) }; remoteReadAt = 0L
    status = if (value == null) "存档内容待检查。" else "正在检查存档变化。"
   } catch (e: CancellationException) { throw e }
   catch (e: Exception) {
    if (appClosed) return
    session = null; history = emptyList(); saveInfo = null; targetInfo = null; pending = false
    locationIssue = e.message; status = e.message ?: "无法读取默认存档目录"
   }
  }
  fun loadProfile(value: AppConfig?) {
   if (busy || connectingAtStartup || appClosed) return
   busy = true
   scope.launch { try { refreshProfile(value) } finally { busy = false } }
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
   val chosen = selected; val branchChoice = conflictSelection; val divergence = conflict; val account = config?.username ?: ""; val secret = password
   busy = true; confirm = null; confirmationNotice = null; navigate(Page.HOME)
   if (action == Action.PUSH) { if (!isAutomatic) automatic.retryAllowed(); automatic.started() }
   status = when (action) {
    Action.RESTORE, Action.RESOLVE, Action.REMOTE_TIP -> "正在校验并备份当前档案，然后恢复…"
    Action.MERGE -> "正在检查单局身份和槽位，合并完整存档…"
    Action.PUSH -> "正在保留快照并校验云端…"
    else -> "正在处理…"
   }
   var terminalFailure: OperationFailure? = null
   trace.record(OperationEvent.OPERATION_QUEUED)
   scope.launch {
    trace.record(OperationEvent.OPERATION_STARTED)
    try {
     val result = withContext(Dispatchers.IO) {
      trace.record(OperationEvent.WORK_STARTED)
      val target = manager.targetInfo()
      val stopped = {
       val fresh = processStatus(target)
       if (fresh !is GameProcessStatus.Stopped) throw SaveNotStableException(processMessage(fresh))
       fresh == GameProcessStatus.Stopped
      }
      if (action != Action.ARCHIVE) {
       val state = processStatus(target)
       if (state !is GameProcessStatus.Stopped) throw SaveNotStableException(processMessage(state))
       SaveStabilityGuard(manager.root, manager.adapter, stopped).awaitStable()
      }
      fun store() = AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, account, secret)
      fun pushed(result: AppendPushResult): OperationResult = when (result) {
       is AppendPushResult.Success -> OperationResult("追加提交已回读校验。" + (if (result.localDirty) "当前本地还有未上传变化；下一次同步会另建提交。" else "所选进度已成为本机同步基线。") + (if (result.remoteAhead) "另有已观察后续分支，可读取查看。" else ""))
       is AppendPushResult.Diverged -> OperationResult("发现 ${result.conflict.branches.size} 个进度分支，全部快照均已保留。请明确选择。", result.conflict)
      }
      val completed = when (action) {
       Action.CAPTURE -> manager.createSnapshot(stopped).let { OperationResult("本地快照已保存，可随时从历史中恢复。") }
       Action.PUSH -> pushed(manager.push(store(), stopped))
       Action.REMOTE_TIP -> manager.restoreRemote(requireNotNull(chosen), store(), stopped).let { OperationResult("云端存档已恢复。恢复前备份已加入历史。", appliedFiles = it.restored.files) }
       Action.RESTORE -> manager.restoreHistory(requireNotNull(chosen), stopped).let { OperationResult("恢复完成，备份已加入历史。恢复提交已冻结，将自动上传；暂停时可点击上传。", appliedFiles = it.restored.files) }
       Action.RESOLVE -> pushed(manager.resolve(requireNotNull(divergence), requireNotNull(branchChoice), store(), stopped)).copy(appliedFiles = divergence.branches.single { it.snapshot.manifest.id == branchChoice }.snapshot.manifest.files)
       Action.MERGE -> pushed(manager.merge(requireNotNull(divergence),store(),stopped)).let { result ->
        if(result.conflict==null) result.copy(appliedFiles=manager.observedFiles()) else result
       }
       Action.ARCHIVE -> { manager.archivePending(); OperationResult("待上传快照已归档。下次同步将重新采集当前存档。") }
      }
      trace.record(OperationEvent.WORK_SUCCEEDED)
      completed
     }
     status = result.message; conflict = result.conflict; conflictSelection = null; remoteReadAt = 0L
     val now = monotonicMillis()
     if (action == Action.PUSH) {
      val baseline = withContext(Dispatchers.IO) { manager.baselineFiles() }
      if (baseline != null) automatic.succeeded(baseline) else automatic.failed(now, false)
     } else if (result.appliedFiles != null) automatic.applied(result.appliedFiles, now)
     else if (action == Action.ARCHIVE) {
      val files = withContext(Dispatchers.IO) { runCatching { manager.observedFiles() }.getOrNull() }
      if (files != null) automatic.applied(files, now) else automatic.retryAllowed()
     }
     if (result.conflict != null) automaticStatus = "存在多个存档分支，自动上传已暂停，点击恢复选择。"
     if (result.conflict != null) navigate(Page.CONFLICT)
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
      val data = withContext(Dispatchers.IO) { Triple(manager.history(), manager.hasPending(), runCatching { manager.currentVersions() }.getOrNull()) }
      history = data.first; pending = data.second; saveInfo = data.third
      if (selected == null) selected = history.firstOrNull()?.manifest?.id
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
     busy = false
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
      val stopped={ processStatus(target) is GameProcessStatus.Stopped }
      if(change==null)manager.inspectDirectory(stopped) else change(manager,stopped)
     }
     directoryEditor=metadata;directoryName=metadata.displayName
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
     val entries = withContext(Dispatchers.IO) { manager.readRemote(AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, account, secret)) }
     remoteTips = entries.map { it.entry.manifest.id }.toSet(); remoteInspectionReady = true
     remoteObserved = entries.mapNotNull { remote -> manager.observedAt(remote.commit.id)?.let { remote.entry.manifest.id to it } }.toMap()
     if (selected == null && entries.size == 1) selected = entries.single().entry.manifest.id
     history = withContext(Dispatchers.IO) { manager.history() }
     status = if (entries.isEmpty()) "此次未发现云端记录。" else "已读取云端记录。"
    } catch (e: Exception) { status = "读取云端失败：${e.message}"; authenticationFailed(e) }
    finally { busy = false }
   }
  }
  fun openRestore() {
   if (busy || connectingAtStartup || appClosed) return
   if (conflict != null) { navigate(Page.CONFLICT); return }
   if (remoteTips.size > 1) {
    val manager = session ?: return
    busy = true
    scope.launch {
     try {
      val found = withContext(Dispatchers.IO) {
       val target = manager.targetInfo()
       val stopped = { processStatus(target) is GameProcessStatus.Stopped }
       require(stopped()) { processMessage(processStatus(target)) }
       SaveStabilityGuard(manager.root, manager.adapter, stopped).awaitStable()
       manager.inspectConflict(AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, config?.username ?: "", password), stopped)
      }
      conflict = found; conflictSelection = null
      if (found != null) navigate(Page.CONFLICT) else status = "云端分支已变化，请等待刷新后重新选择。"
     } catch (e: Exception) { status = "暂不能选择恢复：${e.message}"; authenticationFailed(e) }
     finally { busy = false }
    }
   } else if(selected in remoteTips) execute(operationEvents.begin(Action.REMOTE_TIP))
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
  suspend fun finishOnboarding(progress: OnboardingProgress, message: String) {
   val next = requireNotNull(config).copy(onboardingProgress = progress)
   withContext(Dispatchers.IO) { configFile.save(next) }
   config = next; setupDraft = next; onboardingActive = false; setupConfirm = null
   status = message
   automaticStatus = if (progress == OnboardingProgress.COMPLETE) "首次检查已完成，正在观察存档变化。" else "首次存档选择尚未完成，自动上传保持暂停。点击上传或恢复继续检查。"
   navigate(Page.HOME)
  }
  fun inspectOnboarding() {
   if (busy || connectingAtStartup || appClosed) return
   if (!connected) { onboardingActive = true; openConnection(SettingsMode.UNLOCK); return }
   onboardingActive = true; comparison = null; comparisonNotice = null; setupConfirm = null
   navigate(Page.COMPARISON); busy = true
   scope.launch {
    try {
     refreshProfile(config)
     val manager = session ?: error(locationIssue ?: "默认存档尚未就绪")
     val result = withContext(Dispatchers.IO) {
      val target = manager.targetInfo()
      manager.compareOnboarding(AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, manager.config.username, password)) {
       processStatus(target) is GameProcessStatus.Stopped
      }
     }
     if (appClosed) return@launch
     comparison = result; comparisonCheckedAt = java.time.Instant.now().toString(); comparisonSelection = result.remoteEntries.singleOrNull()?.entry?.manifest?.id
     remoteTips = result.remoteEntries.map { it.entry.manifest.id }.toSet()
     history = withContext(Dispatchers.IO) { manager.history() }
     if (result.kind == OnboardingKind.IDENTICAL && result.baselineLineage == OnboardingLineage.SAME_TIP) {
      withContext(Dispatchers.IO) {
       val target = manager.targetInfo()
       manager.confirmOnboardingReady(result, AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, manager.config.username, password)) { processStatus(target) is GameProcessStatus.Stopped }
      }
      remoteInspectionReady = true
      selected = OnboardingGate.homeSelection(result.baselineId, result.remoteEntries.associate { it.commit.id to it.entry.manifest.id })
      finishOnboarding(OnboardingProgress.COMPLETE, "本地与已关联云端分支一致，无需上传或恢复。")
     }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { comparisonNotice = "暂不能比较：${e.message}。未上传或覆盖存档。"; authenticationFailed(e) }
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
     if (!connected && config?.password != null) reconnectSaved(next)
    } catch (e: Exception) { discoveryNotice = "尚不能继续：${e.message}" }
    finally { busy = false }
    if (proceed) {
     navigate(Page.PATH)
    }
   }
  }
  fun continuePath() {
   if(busy || connectingAtStartup || appClosed)return
   busy=true
   scope.launch {
    var proceed=false
    try {
     val root=withContext(Dispatchers.IO) { location.requireSelected(pathText,true) }
     val next=setupDraft.copy(selectedSaveRoot=root.toString(),onboardingProgress=OnboardingProgress.PENDING)
     withContext(Dispatchers.IO) {
      // Validate the selected directory before persisting it or advancing.
      val preview=SaveSummary.read(root)
      require(preview.files>0 || java.nio.file.Files.list(root).use { it.allMatch { p -> isDirectoryMetadata(p.fileName.toString()) } }) { "此目录没有可识别存档，请选择档案根目录或空目录" }
      if(config!=null)configFile.save(next)
     }
     setupDraft=next;if(config!=null)config=next;proceed=true
    } catch(e:Exception) { pathNotice=e.message }
    finally { busy=false }
    if(proceed){settingsMode=SettingsMode.FULL;navigate(Page.CONNECTION)}
   }
  }
  fun postponeOnboarding(emptyDecision: Boolean = false) {
   val pageActive = !appClosed && page == Page.COMPARISON && onboardingActive
   if (!EmptySetupCompletion.canNavigate(pageActive, busy || connectingAtStartup) || config == null) return
   if (emptyDecision && !EmptySetupCompletion.canFinish(comparison?.kind, comparison?.blocker, pageActive, busy || connectingAtStartup, config != null)) return
   busy = true
   scope.launch {
    try {
     if (emptyDecision) {
      // The explicit empty/empty decision releases observation, never creates a baseline or upload.
      require(comparison?.kind == OnboardingKind.BOTH_EMPTY)
      val manager = requireNotNull(session)
      val fresh = withContext(Dispatchers.IO) {
       val target = manager.targetInfo()
       manager.compareOnboarding(AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, manager.config.username, password)) { processStatus(target) is GameProcessStatus.Stopped }
      }
      comparison = fresh
      require(fresh.kind == OnboardingKind.BOTH_EMPTY) { "存档状态已变化，请重新查看并选择" }
      withContext(Dispatchers.IO) {
       val target = manager.targetInfo()
       manager.confirmOnboardingReady(fresh, AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, manager.config.username, password)) { processStatus(target) is GameProcessStatus.Stopped }
      }
      remoteInspectionReady = true
      automatic.applied(emptyMap(), monotonicMillis())
      finishOnboarding(OnboardingProgress.COMPLETE, "双方暂无存档。同步器保持开启时，新建游戏进度并退出后会自动上传；首次上传前重启同步器会重新比较。")
     } else finishOnboarding(OnboardingProgress.DEFERRED, "已保留双方进度。稍后点击上传或恢复，继续首次存档比较。")
    } catch (e: Exception) { comparisonNotice = "选择未保存：${e.message}" }
    finally { busy = false }
   }
  }
  fun performOnboarding(action: SetupAction) {
   if (busy || connectingAtStartup || !connected || appClosed) return
   val manager = session ?: return
   val observed = comparison ?: return
   val chosen = comparisonSelection
   busy = true; setupConfirm = null; comparisonNotice = "正在重新校验并处理所选进度…"
   scope.launch {
    try {
     withContext(Dispatchers.IO) {
      val target = manager.targetInfo()
      val stopped = {
       val state = processStatus(target)
       if (state !is GameProcessStatus.Stopped) throw SaveNotStableException(processMessage(state))
       true
      }
      SaveStabilityGuard(manager.root, manager.adapter, stopped).awaitStable()
      val store = AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, manager.config.username, password)
      when (action) {
       SetupAction.UPLOAD -> manager.onboardingUpload(observed, store, stopped)
       SetupAction.RESTORE -> manager.onboardingRestore(observed, requireNotNull(chosen), store, stopped)
       SetupAction.ADOPT -> manager.adoptOnboardingRemote(observed, store, stopped)
      }
     }
     comparisonNotice = "操作已完成，正在重新比较。"
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { comparisonNotice = "操作未完成：${e.message}。已有快照与备份保留。"; authenticationFailed(e) }
    finally { busy = false }
    // Every successful or interrupted action is observed again; pending/forks cannot release autosync.
    if (page == Page.COMPARISON && connected) {
     val notice = comparisonNotice
     inspectOnboarding()
     comparisonNotice = notice
    }
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
      // A verified v9 baseline is a returning setup, not a fabricated first-run decision.
      val hasBaseline = withContext(Dispatchers.IO) { session?.baselineFiles() != null }
      val progress = OnboardingGate.resumedProgress(saved.onboardingProgress, targetInfo?.trusted == true, hasBaseline)
      val resumed = saved.copy(onboardingProgress = progress)
      if (resumed != saved) withContext(Dispatchers.IO) { configFile.save(resumed) }
      config = resumed; setupDraft = resumed; onboardingActive = progress != OnboardingProgress.COMPLETE
      reconnectSaved(resumed)
      if (!connected) page = Page.CONNECTION
     }
    }
   } catch (e: CancellationException) { throw e }
   catch (e: Exception) {
    onboardingActive = true; page = Page.DISCOVERY
    discoveryNotice = "启动检查未完成：${e.message}。请重新扫描；没有上传或覆盖存档。"
   } finally { connectingAtStartup = false }
   if (connected && onboardingActive && page != Page.DISCOVERY) inspectOnboarding()
  }
  LaunchedEffect(page,pathText) {
   if(page==Page.PATH) {
    pathPreview=null;pathNotice="正在读取存档…";delay(350)
    try {
     val result=withContext(Dispatchers.IO){SaveSummary.read(location.requireSelected(pathText,false))}
     pathPreview=result;pathNotice=if(result.files==0)"目录存在，尚无游戏存档或记录。" else null
    } catch(e:CancellationException){throw e}
    catch(e:Exception){pathNotice=e.message}
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
      val state = processStatus(target)
      state to manager.observedFiles()
     }
     if (busy || connectingAtStartup || page != Page.HOME || onboardingActive || config?.onboardingProgress != OnboardingProgress.COMPLETE || !remoteInspectionReady || session !== manager) continue
     val state = observation.first; val files = observation.second; val now = monotonicMillis()
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
      else execute(operationEvents.begin(Action.PUSH), isAutomatic = true)
     } else if (connected && !busy && (remoteReadAt == 0L || now - remoteReadAt >= 60000)) readRemote()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { automaticStatus = "存档正在变化或暂不可读，自动上传等待中。" }
   }
  }
  LaunchedEffect(session, selected, busy) {
   restoreIssue = "正在校验实际版本…"
   val manager = session; val id = selected
   if (manager != null && id != null && !busy) restoreIssue = withContext(Dispatchers.IO) { runCatching { manager.checkRestore(id) }.exceptionOrNull()?.message }
  }
  LaunchedEffect(session, conflict, conflictSelection, busy) {
   conflictRestoreIssue = "正在校验实际版本…"
   val manager = session; val value = conflict; val id = conflictSelection
   if (manager != null && value != null && id != null && !busy) conflictRestoreIssue = withContext(Dispatchers.IO) { runCatching { manager.checkResolution(id) }.exceptionOrNull()?.message }
  }
  MaterialTheme(colors = lightColors(primary = green, secondary = Color(0xFF8A6B42)), shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp))) {
   directoryEditor?.let { metadata ->
    AlertDialog(onDismissRequest={ if(!busy)directoryEditor=null }, title={ Text("存档名称与单局身份") },
     text={ Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
      OutlinedTextField(directoryName,{directoryName=it},enabled=!busy,label={Text("存档名称")},singleLine=true)
      Text("存档在同步器未观察期间发生变化时，需要确认是否仍为原来的一局。不能确定时请保留待确认。",style=MaterialTheme.typography.caption)
      metadata.runs.forEach { (slot,run) ->
       Text("$slot · "+if(run.runUUID==null)"身份待确认" else "身份已确认",fontWeight=FontWeight.Medium)
       if(run.runUUID==null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
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
      directoryNotice?.let { Text(it,style=MaterialTheme.typography.caption) }
     } }, buttons={ Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.End) {
      TextButton(enabled=!busy,onClick={directoryEditor=null}) {Text("关闭")}
      Button(enabled=!busy,onClick={val name=directoryName;editDirectory {manager,stopped->manager.renameCurrent(name,stopped)}}) {Text("保存名称")}
     } })
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
       Page.DISCOVERY -> GuidedPage(
        title = "1 / 4  选择游戏", subtitle = "",
        footer = {
         discoveryNotice?.let { CompactFeedback(it, "检查未完成，请重新选择游戏或重试。") }
         if (busy || connectingAtStartup) LinearProgressIndicator(Modifier.fillMaxWidth())
         Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(enabled = !busy && !connectingAtStartup, onClick = { rescanDiscovery() }) { Text("重新扫描") }
          Spacer(Modifier.weight(1f))
          Button(enabled = !busy && !connectingAtStartup && discoveredTarget?.trusted == true && pathResult.isSuccess, onClick = { continueDiscovery() }) { Text("继续") }
         }
        }
       ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
         discoveredTarget?.let { Text("Shattered Pixel Dungeon ${it.displayVersion}", fontWeight = FontWeight.Medium) }
         DiscoveryCopy.game(discovery, discoveredTarget).takeIf { it.isNotEmpty() }?.let { Text(it, color = muted) }
         discovery?.trustedTargets?.takeIf { discovery?.requiresSelection == true && it.isNotEmpty() }?.forEach { game ->
          Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { discoveredTarget = game; discoveryNotice = null }, verticalAlignment = Alignment.CenterVertically) {
           RadioButton(selected = discoveredTarget?.path == game.path, onClick = { discoveredTarget = game }, enabled = !busy)
           Column { Text("Shattered ${game.displayVersion}"); Details { SelectionContainer { Text(game.path) } } }
          }
         }
         OutlinedButton(enabled = !busy && !connectingAtStartup, onClick = { chooseSetupGame() }) { Text(if (discoveredTarget == null) "选择游戏…" else "更换游戏…") }
         Details {
          Text("存档位置：$pathText")
          discoveredTarget?.let { Text("游戏文件：${it.path}") }
          discovery?.defaultSave?.reason?.let { Text(it) }
          discovery?.diagnostics?.forEach { Text(it) }
         }
        }
       }
       Page.PATH -> GuidedPage(title="2 / 4  设置存档路径",subtitle="",footer={
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth()) {
         TextButton(enabled=!busy,onClick={navigate(Page.DISCOVERY)}){Text("上一步")}
         Spacer(Modifier.weight(1f))
         Button(enabled=!busy&&pathText.isNotBlank(),onClick={continuePath()}){Text("下一步")}
        }
       }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
         OutlinedTextField(pathText,{pathText=it},Modifier.fillMaxWidth(),enabled=!busy,label={Text("存档目录")},singleLine=true)
         Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
          OutlinedButton(enabled=!busy,onClick={busy=true;scope.launch {try{NativeFolderChooser.choose(pathText)?.let{pathText=it}}catch(e:Exception){pathNotice=e.message}finally{busy=false}}}){Text("选择目录…")}
          TextButton(enabled=!busy&&pathResult.isSuccess,onClick={pathText=pathResult.getOrThrow().toString()}){Text("使用默认路径")}
         }
         pathPreview?.let { summary ->
          Text("游戏槽位 ${summary.slots.size} 个 · 游玩 ${summary.plays?.toString() ?: "未知"} 次")
          summary.slots.forEach { (slot,time) -> Text("$slot · ${time?.let(::localTime) ?: "时间未知"}",color=muted) }
         }
         pathNotice?.let { Text(it,color=muted) }
         Text("目录不存在时，下一步会创建空目录用于恢复。不会运行游戏。",style=MaterialTheme.typography.caption,color=muted)
        }
       }
       Page.COMPARISON -> GuidedPage(
        title = if (EmptySetupCompletion.isEmpty(comparison?.kind, comparison?.blocker)) "4 / 4  配置完成" else "4 / 4  选择存档", subtitle = "",
        footer = {
         comparisonNotice?.let { CompactFeedback(it, "操作未完成，请重试。") }
         comparison?.blocker?.let { CompactFeedback(it, "暂不能继续，请检查存档或连接后重试。") }
         if (busy || connectingAtStartup) LinearProgressIndicator(Modifier.fillMaxWidth())
         if (EmptySetupCompletion.isEmpty(comparison?.kind, comparison?.blocker)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
           TextButton(enabled = EmptySetupCompletion.canNavigate(onboardingActive, busy || connectingAtStartup), onClick = {
            if (EmptySetupCompletion.canNavigate(page == Page.COMPARISON && onboardingActive && !appClosed, busy || connectingAtStartup)) {
             setupDraft = config ?: setupDraft; settingsMode = SettingsMode.FULL; navigate(Page.CONNECTION)
            }
           }) { Text("上一步") }
           Spacer(Modifier.weight(1f))
           Button(enabled = EmptySetupCompletion.canFinish(comparison?.kind, comparison?.blocker, onboardingActive, busy || connectingAtStartup, config != null), onClick = { postponeOnboarding(emptyDecision = true) }) { Text("完成") }
          }
         } else if (setupConfirm != null) {
          Text(if (setupConfirm == SetupAction.RESTORE) NoviceCopy.restoreWarning else "使用云端记录，不改动本机存档。", color = Color(0xFF986337))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
           TextButton(enabled = !busy, onClick = { setupConfirm = null; comparisonNotice = "已取消选择，双方存档未改变。" }) { Text("取消") }
           Spacer(Modifier.weight(1f))
           Button(enabled = !busy, onClick = { setupConfirm?.let { performOnboarding(it) } }) { Text(if (setupConfirm == SetupAction.RESTORE) "备份并恢复" else "确认继续") }
          }
         } else {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
           TextButton(enabled = !busy, onClick = { onboardingActive = true; setupDraft = config ?: setupDraft; settingsMode = SettingsMode.FULL; navigate(Page.CONNECTION) }) { Text("返回连接") }
           TextButton(enabled = !busy, onClick = { inspectOnboarding() }) { Text("重新比较") }
           Spacer(Modifier.weight(1f))
           TextButton(enabled = !busy, onClick = { postponeOnboarding() }) { Text("稍后处理") }
          }
          comparison?.let { value ->
           Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (value.localFiles.isNotEmpty() || value.retryablePending) Button(enabled = !busy && (value.kind != OnboardingKind.BLOCKED || value.retryablePending), onClick = { performOnboarding(SetupAction.UPLOAD) }, modifier = Modifier.weight(1f)) { Text(if (value.retryablePending) "重试上传" else if (value.remoteEntries.isEmpty()) "上传本机存档" else "上传本机，保留两份") }
            if (value.canAdoptRemote) OutlinedButton(enabled = !busy, onClick = { setupConfirm = SetupAction.ADOPT }, modifier = Modifier.weight(1f)) { Text("确认使用") }
            else if (value.remoteEntries.isNotEmpty()) OutlinedButton(enabled = !busy && comparisonSelection != null && value.kind != OnboardingKind.BLOCKED, onClick = { setupConfirm = SetupAction.RESTORE }, modifier = Modifier.weight(1f)) { Text("恢复所选云端存档") }
           }
          }
         }
        }
       ) {
        if (EmptySetupCompletion.isEmpty(comparison?.kind, comparison?.blocker)) {
         Text(if (comparisonNotice == null) EmptySetupCompletion.message else "配置尚未完成", fontWeight = FontWeight.Medium)
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
         Text(NoviceCopy.comparison(comparison?.kind, busy), fontWeight = FontWeight.Medium)
         comparison?.let { value ->
          Surface(color = Color(0xFFE3EFE6)) {
           Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("本机 · ${device?.label ?: "设备未知"}")
            value.localLatestModifiedAt?.let { Text("存档修改 ${localTime(it)}", color = muted, style = MaterialTheme.typography.caption) }
           }
          }
          value.remoteEntries.forEach { remote ->
           val m = remote.entry.manifest
           Surface(color = if (comparisonSelection == m.id) Color(0xFFE3EFE6) else Color.White) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { comparisonSelection = m.id }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
             RadioButton(selected = comparisonSelection == m.id, onClick = { comparisonSelection = m.id }, enabled = !busy)
             Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("云端 · ${m.metadata?.device?.label ?: "设备未知"}")
              Text(snapshotBriefTime(m), color = muted, style = MaterialTheme.typography.caption)
             }
            }
           }
          }
          Details {
           Text("本机：${OnboardingGate.localFileCountLabel(value.kind, value.localFiles.size, value.retryablePending)}")
           saveInfo?.let { Text(it.summary()) }
           value.remoteEntries.forEach { remote -> Text("${remote.entry.manifest.metadata?.device?.label ?: "云端"}：${remote.entry.manifest.files.size} 个文件 · ${remote.entry.manifest.saveVersions?.summary() ?: remote.entry.manifest.version}") }
          }
         }
        }
       }
       Page.HOME -> {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
         Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
           Text("像素地牢", style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
           Text("存档自动同步  /  SHATTERED", color = muted, style = MaterialTheme.typography.caption)
          }
          Text(if (connected) "● 云端已连接" else "○ 等待登录", color = if (connected) green else muted, style = MaterialTheme.typography.body2)
          Spacer(Modifier.width(20.dp))
          Text("设置", Modifier.clickable(enabled = !busy && !connectingAtStartup) { navigate(Page.SETTINGS) }.padding(8.dp), color = green)
         }
         Surface(color = Color(0xFFE3EFE6)) {
          Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
           Text(if (config?.onboardingProgress == OnboardingProgress.COMPLETE) automaticStatus else "自动上传暂停，请先选择上传或恢复。", color = green, style = MaterialTheme.typography.body2)
           Text("本机：${device?.label ?: "正在读取"}", color = muted, style = MaterialTheme.typography.caption)
          }
         }
         Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Button(enabled = !busy && !connectingAtStartup && (!connected || session != null), onClick = {
           if (!connected) openConnection(if (config == null) SettingsMode.FULL else SettingsMode.UNLOCK)
           else if (config?.onboardingProgress != OnboardingProgress.COMPLETE) inspectOnboarding()
           else execute(operationEvents.begin(Action.PUSH))
          }, modifier = Modifier.weight(1f)) { Text("上传") }
          OutlinedButton(enabled = session != null && !busy && !connectingAtStartup && (config?.onboardingProgress != OnboardingProgress.COMPLETE || !pending && (selected !in remoteTips || connected) &&
           (conflict != null || remoteTips.size > 1 || selected != null && restoreIssue == null)), onClick = { if (config?.onboardingProgress != OnboardingProgress.COMPLETE) inspectOnboarding() else openRestore() }, modifier = Modifier.weight(1f)) { Text("恢复") }
         }
         locationIssue?.let { CompactFeedback(it, "无法读取存档，请在设置中重新检查。") }
         TextButton(enabled=session!=null&&!busy&&!connectingAtStartup,onClick={editDirectory()}) { Text("存档名称与单局身份") }
         if (selected != null && restoreIssue != null && remoteTips.size <= 1 && conflict == null) CompactFeedback(restoreIssue!!, "暂不能恢复，请重新检查所选存档。")
         if (pending) Text("上次上传尚未完成，请先重试上传。", color = Color(0xFF986337), style = MaterialTheme.typography.caption)
         Card(Modifier.weight(1f).fillMaxWidth(), elevation = 0.dp) {
          Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
           Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("选择要恢复的存档", style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
            Text("${history.size} 份", color = muted, style = MaterialTheme.typography.body2)
           }
           if (history.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("尚无可选记录，请上传或等待读取。", color = muted)
           } else LazyColumn(Modifier.weight(1f), state = historyScroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history, key = { it.manifest.id }) { entry ->
             val m = entry.manifest
             Surface(color = if (selected == m.id) Color(0xFFE3EFE6) else Color(0xFFF7F8F5)) {
              Column(Modifier.fillMaxWidth().clickable(enabled = !busy && !connectingAtStartup) { selected = m.id }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
               Text(m.directoryMetadata?.displayName?.takeIf { it.isNotBlank() } ?: m.metadata?.device?.label ?: "设备未知", fontWeight = FontWeight.Medium)
               Text(snapshotBriefTime(m), color = muted, style = MaterialTheme.typography.caption)
              }
             }
            }
           }
          }
         }
         if (busy || connectingAtStartup) LinearProgressIndicator(Modifier.fillMaxWidth())
         connectionNotice?.let { CompactFeedback(it, "连接需要处理，请打开设置。") }
         CompactFeedback(status, "请查看操作结果。")
        }
       }
       Page.SETTINGS -> GuidedPage(
        title = "设置", subtitle = "",
        footer = { TextButton(enabled = !busy && !connectingAtStartup, onClick = { navigate(Page.HOME) }) { Text("返回存档") } }
       ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
         Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("自动上传", Modifier.weight(1f))
          Switch(checked = config?.autoUploadPaused != true, enabled = config != null && !busy, onCheckedChange = { setAutomaticPaused(!it) })
         }
         Divider()
         Text(targetInfo?.let { "Shattered ${it.displayVersion}" } ?: "尚未选择游戏")
         targetError?.let { CompactFeedback(it, "游戏文件不可用，请重新选择。") }
         OutlinedButton(enabled = config != null && !busy, onClick = { selectTargetGame() }) { Text("更换游戏…") }
         Details { Text("存档位置：$pathText"); Text(processMessage(gameStatus)) }
         TextButton(enabled = !busy && !connectingAtStartup, onClick = { loadProfile(config) }) { Text("重新检查存档") }
         OutlinedButton(enabled = !busy && !connectingAtStartup, onClick = { openConnection(SettingsMode.FULL) }) { Text("云端连接设置") }
         TextButton(enabled = !busy && !connectingAtStartup, onClick = { onboardingActive = true; setupDraft = config ?: newConnectionConfig(); navigate(Page.PATH) }) { Text("修改存档路径") }
         TextButton(enabled = !busy && !connectingAtStartup, onClick = { onboardingActive = true; setupDraft = config ?: newConnectionConfig(); navigate(Page.DISCOVERY); rescanDiscovery() }) { Text("重新检查四步引导") }
         if (pending) TextButton(enabled = !busy && !connectingAtStartup, onClick = { requestConfirmation(Action.ARCHIVE) }) { Text("检查并归档中断的待上传操作") }
        }
       }
       Page.CONNECTION -> key(pageGeneration) {
        val generation = pageGeneration
        fun current() = !appClosed && page == Page.CONNECTION && pageGeneration == generation
        ConnectionPage(settingsMode, configFile, if (onboardingActive) setupDraft else config, password, connectionNotice, busy || connectingAtStartup, onboardingActive,
         onBusy = { if (current()) busy = it },
         onDismiss = { if (current() && !busy && !connectingAtStartup) navigate(if (onboardingActive) Page.PATH else Page.HOME) },
         onApplied = { applied, note ->
          if (current()) {
           config = applied.config; setupDraft = applied.config; password = applied.password; connected = true
           connectionNotice = note
           onboardingActive = applied.config.onboardingProgress != OnboardingProgress.COMPLETE || onboardingActive
           if (onboardingActive) inspectOnboarding() else { navigate(Page.HOME); loadProfile(applied.config) }
          }
         })
       }
       Page.CONFLICT -> conflict?.let { value ->
        ConflictPage(value, conflictSelection, conflictRestoreIssue, busy || connectingAtStartup, connected,
         onSelect = { if (!busy && !connectingAtStartup) conflictSelection = it },
         onBack = { if (!busy && !connectingAtStartup) navigate(Page.HOME) },
         onConfirm = { execute(operationEvents.begin(Action.RESOLVE)) },
         onMerge = { execute(operationEvents.begin(Action.MERGE)) })
       }
       Page.CONFIRM -> confirm?.let { trace ->
        ConfirmationPage(trace.action, session?.root?.toString(), pending, conflictSelection, if (trace.action == Action.RESOLVE) conflict?.branches?.firstOrNull { it.snapshot.manifest.id == conflictSelection }?.snapshot?.manifest else selection?.manifest, busy || connectingAtStartup, confirmationNotice,
         onBack = { cancelConfirmation(trace) },
         onConfirm = { execute(trace) })
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

@Composable
private fun ConnectionPage(mode: SettingsMode, configFile: ConfigFile, initial: AppConfig?, currentPassword: String, initialNotice: String?, busy: Boolean, onboarding: Boolean, onBusy: (Boolean) -> Unit, onDismiss: () -> Unit, onApplied: (AppliedConnection, String?) -> Unit) {
 val controller = remember { ConnectionSetup(initial) }
 val lifetime = remember { ConnectionPageLifetime() }
 var draft by remember { mutableStateOf(initial ?: newConnectionConfig()) }
 var secret by remember { mutableStateOf(currentPassword) }
 var message by remember { mutableStateOf(initialNotice) }
 var failed by remember { mutableStateOf(false) }
 var connectionVerified by remember { mutableStateOf(false) }
 // Both the UI state and lifetime reservation change synchronously before recomposition.
 var inFlight by remember { mutableStateOf(false) }
 var closed by remember { mutableStateOf(false) }
 val scope = rememberCoroutineScope()
 val controlsBusy = busy || inFlight
 DisposableEffect(controller, lifetime) {
  onDispose {
   closed = true
   lifetime.close { controller.cancel() }
   secret = ""
  }
 }
 LaunchedEffect(initialNotice) { if (!inFlight && !failed) message = initialNotice }
 fun edit(value: AppConfig = draft, password: String = secret) {
  if (busy || inFlight || closed) return
  val next = ConnectionDraft(draft, password).editingConfig(value)
  draft = next.config; secret = next.password
  connectionVerified=false
  controller.update(draft,secret)
  // Keep the last failure visible while correcting inputs, until the next explicit attempt.
 }
 fun cancel() {
  if (busy || inFlight || closed) return
  closed = true; lifetime.close { controller.cancel() }; secret = ""; onDismiss()
 }
 fun connect(apply: Boolean = false) {
  if (busy || inFlight || closed) return
  if(apply && !connectionVerified)return
  val ticket = lifetime.begin() ?: return
  inFlight = true; onBusy(true); failed = false; message = null
  val submitted = ConnectionDraft(draft, secret)
  scope.launch {
   var applied: AppliedConnection? = null
   var note: String? = null
   var verified = false
   var finished = false
   try {
    controller.update(submitted.config, submitted.password)
    when (val checked = if(apply)ConnectionTestResult.Success(false) else withContext(Dispatchers.IO) { controller.connect() }) {
     is ConnectionTestResult.Failure -> if (lifetime.isCurrent(ticket)) { message = checked.message; failed = true }
     is ConnectionTestResult.Success -> {
      verified = true
      connectionVerified=true;message="连接测试通过。"
      if(apply)applied = withContext(Dispatchers.IO) {
       lifetime.persist(ticket) {
        controller.applyFilePassword(configFile)
       }
      }
     }
    }
   } catch (e: CancellationException) { throw e }
   catch (e: Exception) {
    if (lifetime.isCurrent(ticket)) {
     message = if (verified) "连接已通过，但本机设置未保存：${e.message ?: "请检查配置文件写入权限后重试"}" else "连接未完成，请检查地址、网络与服务状态后重试。"
     failed = true
    }
   } finally {
    finished = lifetime.finish(ticket)
    if (finished) { inFlight = false; onBusy(false) }
   }
   // There is no suspension between finish and publication. The parent also checks page generation.
   if (finished && !closed) applied?.let {
    closed = true
    onApplied(it, note)
    secret = ""
   }
  }
 }
 GuidedPage(
  title = if (onboarding) "3 / 4  连接云端" else if (mode == SettingsMode.UNLOCK) "欢迎回来，连接云端" else if (initial == null) "让存档跟着你" else "连接设置",
  subtitle = "",
  footer = {
   // Feedback and the single submit action remain visible independently of form scrolling.
   message?.let { CompactFeedback(it, "连接未完成，请检查填写内容后重试。") }
   if (controlsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    TextButton(enabled = !controlsBusy, onClick = { cancel() }) { Text("返回") }
    Spacer(Modifier.weight(1f))
    Button(enabled = !controlsBusy && connectionVerified, onClick = { connect(true) }) { Text(if(onboarding)"下一步" else "保存") }
   }
  }
 ) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   OutlinedTextField(draft.endpoint, { edit(draft.copy(endpoint = it)) }, Modifier.fillMaxWidth(), enabled = !controlsBusy, label = { Text("WebDAV 地址") }, placeholder = { Text("https://example.com/dav") }, singleLine = true)
   OutlinedTextField(draft.username, { edit(draft.copy(username = it)) }, Modifier.fillMaxWidth(), enabled = !controlsBusy, label = { Text("用户名") }, singleLine = true)
   OutlinedTextField(secret, { edit(password = it) }, Modifier.fillMaxWidth(), enabled = !controlsBusy, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
   OutlinedButton(enabled=!controlsBusy,onClick={connect()}) { Text(if(inFlight)"正在测试…" else "测试连接") }
   Text("密码可留空。测试会检查并准备本应用的同步目录。",style=MaterialTheme.typography.caption,color=muted)
  }
 }
}

@Composable
private fun ConflictPage(value: AppendConflict, selected: String?, restoreIssue: String?, busy: Boolean, connected: Boolean, onSelect: (String) -> Unit, onBack: () -> Unit, onConfirm: () -> Unit, onMerge: () -> Unit) {
 GuidedPage(
  title = "存档发生冲突",
  subtitle = "覆盖：所选快照成为本机和云端的当前版本。合并：保留各局改动，同一局的不同进度各占一个槽位。原始历史保留。",
  footer = {
   restoreIssue?.let { Text("选择后校验：$it", color = Color(0xFF986337), style = MaterialTheme.typography.caption) }
   Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    TextButton(enabled = !busy, onClick = onBack) { Text("取消") }
    Spacer(Modifier.weight(1f))
    OutlinedButton(enabled = !busy && connected && selected != null && restoreIssue == null, onClick = onConfirm) { Text("覆盖为所选存档") }
    Spacer(Modifier.width(8.dp))
    Button(enabled = !busy && connected, onClick = onMerge) { Text("合并") }
   }
  }
 ) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   value.branches.forEach { branch ->
    val m = branch.snapshot.manifest
    Surface(color = if (selected == m.id) Color(0xFFE3EFE6) else Color.White, shape = MaterialTheme.shapes.small) {
     Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { onSelect(m.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      RadioButton(selected = selected == m.id, onClick = { onSelect(m.id) }, enabled = !busy)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
       Text(m.metadata?.device?.label ?: "设备未知")
       Text(snapshotBriefTime(m), color = muted, style = MaterialTheme.typography.caption)
       if (m.files == value.observedLocalFiles) Text("与当前本地进度一致", color = green, style = MaterialTheme.typography.caption)
       Details { Text("${m.files.size} 个文件 · ${m.saveVersions?.summary() ?: m.version}") }
      }
     }
    }
   }
  }
 }
}

@Composable
private fun ConfirmationPage(action: Action, root: String?, pending: Boolean, branch: String?, snapshot: Manifest?, busy: Boolean, notice: String?, onBack: () -> Unit, onConfirm: () -> Unit) {
 val restoring = action in setOf(Action.RESTORE, Action.RESOLVE, Action.REMOTE_TIP)
 GuidedPage(
  title = if (restoring) "恢复存档" else if (action == Action.ARCHIVE) "结束待上传操作" else "操作前确认",
  subtitle = "",
  footer = {
   notice?.let { Text(it, color = Color(0xFFAD4634), style = MaterialTheme.typography.body2) }
   Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    TextButton(enabled = !busy, onClick = onBack) { Text("取消，返回主页") }
    Spacer(Modifier.weight(1f))
    Button(enabled = !busy, onClick = onConfirm) { Text(if (restoring) "备份并恢复" else "确认继续") }
   }
  }
 ) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
   if (action == Action.ARCHIVE) Text("只移除待上传标记，历史保留，不删除云端内容。若此前恢复中断，请先核对当前档案与备份；归档也会解除中断恢复保护，下次同步将重新采集当前档案。")
   else {
    if (restoring && snapshot != null) {
     Text("所选存档：${snapshot.metadata?.device?.label ?: "设备未知"}", fontWeight = FontWeight.Medium)
     Text(snapshotBriefTime(snapshot), color = muted, style = MaterialTheme.typography.caption)
    }
    if (restoring) Text(NoviceCopy.restoreWarning, color = Color(0xFF986337))
    if (action == Action.PUSH && pending) Text("将重试上次保存的内容，可能不是当前最新进度。")
    if (restoring) Text("请退出游戏，并暂停其他存档同步。", color = muted)
    Details { Text("存档目录：$root") }
   }
  }
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
