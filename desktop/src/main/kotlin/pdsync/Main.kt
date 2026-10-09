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
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val workspace = Path.of(System.getProperty("pdsync.workspace", ".")).toAbsolutePath().normalize()
private typealias Action = OperationAction
private enum class SettingsMode { FULL, UNLOCK }
private enum class Page { HOME, SETTINGS, DISCOVERY, CONNECTION, COMPARISON, CONFLICT, CONFIRM }
private enum class SetupAction { UPLOAD, RESTORE, ADOPT }
private data class OperationResult(val message: String, val conflict: AppendConflict? = null, val appliedFiles: Map<String, String>? = null)
private data class LoadedProfile(val session: AppendProfileSession, val history: List<HistoryEntry>, val pending: Boolean, val saves: SaveVersions?, val target: TargetGame?, val targetError: String?, val device: DeviceIdentity, val baselineFiles: Map<String, String>?)
private val green = Color(0xFF326650)
private val muted = Color(0xFF657069)

private suspend fun chooseGame(parent: java.awt.Component, initial: String): String? = suspendCoroutine { continuation ->
 SwingUtilities.invokeLater {
  try {
   val chooser = JFileChooser().apply {
    dialogTitle = "选择目标游戏 JAR（只读取，不运行）"; fileSelectionMode = JFileChooser.FILES_ONLY
    if (initial.isNotBlank()) { val file = java.io.File(initial); currentDirectory = file.parentFile; if (file.isFile) selectedFile = file }
   }
   continuation.resume(if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null)
  } catch (_: Exception) { continuation.resume(null) }
 }
}

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
  val initialResult = remember { runCatching { configFile.load() } }
  var config by remember { mutableStateOf(initialResult.getOrNull()) }
  val location = remember { DefaultProfileLocation() }
  val pathResult = remember { runCatching { location.resolve() } }
  val pathText = pathResult.getOrNull()?.toString() ?: "无法解析此系统的默认目录"
  var password by remember { mutableStateOf("") }
  var connected by remember { mutableStateOf(false) }
  var connectionNotice by remember { mutableStateOf<String?>(null) }
  var page by remember { mutableStateOf(if (config == null) Page.DISCOVERY else Page.HOME) }
  var pageGeneration by remember { mutableStateOf(0L) }
  var onboardingActive by remember { mutableStateOf(config == null || config?.onboardingProgress in setOf(OnboardingProgress.PENDING, OnboardingProgress.DEFERRED)) }
  var setupDraft by remember { mutableStateOf(config ?: newConnectionConfig()) }
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
  val secretStore = remember { SystemSecretStore.create() }
  val selection = history.firstOrNull { it.manifest.id == selected }
  val homeScroll = rememberScrollState()
  val historyScroll = rememberLazyListState()

  fun navigate(next: Page) {
   pageGeneration++; page = next; closeBlocked = false
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
     location.requireDirectory()
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
    status = if (value == null) "默认存档位置已找到。连接云端后即可管理快照。" else "已开始观察存档。已有同步基线会自动补传离线变化；新档案可先点击上传，后续自动处理。"
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
     val chosen = chooseGame(window, saved.targetGame)
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
     status = "操作未完成：${e.message}。已保存的历史与备份保留。"; authenticationFailed(e)
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
     status = if (entries.isEmpty()) "此次未观察到云端提交。可先同步当前存档。" else "已观察到 ${entries.size} 个云端分支，均已加入历史，本地未改动。普通恢复只允许单一分支及干净本地基线。已有本地进度可先同步，再明确选择；空目录可先从历史选定快照恢复，之后同步解决分支。"
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
   } else requestConfirmation(if (selected in remoteTips) Action.REMOTE_TIP else Action.RESTORE)
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
   discoveryNotice = when {
    discoveredTarget != null -> "已识别可信游戏版本，未运行游戏。"
    found.trustedTargets.size > 1 -> "找到多个游戏，请明确选择要使用的一份。"
    else -> "未能唯一确认可信游戏，请手动选择官方游戏 JAR。"
   }
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
     val chosen = chooseGame(window, discoveredTarget?.path ?: setupDraft.targetGame)
     if (chosen != null) {
      val verified = withContext(Dispatchers.IO) {
       val candidate = ShatteredInstallationDiscovery().validateJar(Path.of(chosen))
       require(candidate.trusted) { candidate.reason }; requireNotNull(candidate.target)
      }
      require(verified.trusted) { "尚不能信任所选游戏版本：${verified.reason}" }
      discoveredTarget = verified; discoveryNotice = "已选择 Shattered ${verified.displayVersion}，仅静态校验，未运行。"
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
      location.prepareDefaultDirectory()
     }
     val next = setupDraft.copy(targetGame = target.path, onboardingProgress = OnboardingProgress.PENDING)
     if (config != null) { withContext(Dispatchers.IO) { configFile.save(next) }; config = next }
     setupDraft = next; onboardingActive = true; proceed = true
    } catch (e: Exception) { discoveryNotice = "尚不能继续：${e.message}" }
    finally { busy = false }
    if (proceed) {
     if (connected) inspectOnboarding() else { settingsMode = SettingsMode.FULL; navigate(Page.CONNECTION) }
    }
   }
  }
  fun postponeOnboarding(emptyDecision: Boolean = false) {
   if (busy || connectingAtStartup || config == null) return
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
      fun requestUnlock(notice: String? = null) {
       settingsMode = SettingsMode.UNLOCK; connectionNotice = notice; page = Page.CONNECTION
      }
      if (resumed.rememberPassword) {
       val restored = withContext(Dispatchers.IO) { secretStore.read(resumed.endpoint, resumed.username) }
       if (restored is SecretReadResult.Found) {
        val checker = ConnectionSetup(resumed)
        try {
         checker.update(resumed, restored.password)
         when (val checked = withContext(Dispatchers.IO) { checker.testConnection() }) {
          is ConnectionTestResult.Success -> { password = restored.password; connected = true }
          is ConnectionTestResult.Failure -> requestUnlock(checked.message)
         }
        } finally { checker.cancel() }
       } else requestUnlock(if (restored is SecretReadResult.Unavailable) restored.message else "未找到已保存的密码，请重新输入。")
      } else requestUnlock()
     }
    }
   } catch (e: CancellationException) { throw e }
   catch (e: Exception) {
    onboardingActive = true; page = Page.DISCOVERY
    discoveryNotice = "启动检查未完成：${e.message}。请重新扫描；没有上传或覆盖存档。"
   } finally { connectingAtStartup = false }
   if (connected && onboardingActive && page != Page.DISCOVERY) inspectOnboarding()
  }
  LaunchedEffect(session, connected) {
   while (isActive && !appClosed) {
    delay(1000)
    if (busy || connectingAtStartup || page != Page.HOME || onboardingActive || config?.onboardingProgress != OnboardingProgress.COMPLETE) continue
    val manager = session ?: continue
    if (connected && !remoteInspectionReady) {
     automaticStatus = "完整云端核验尚未完成，自动上传暂停。"
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
      !connected -> "等待登录，已有快照与待上传操作保留。"
      state !is GameProcessStatus.Stopped -> processMessage(state)
      pending && !retryable -> "有待检查的中断操作，请在设置中处理。"
      conflict != null || remoteTips.size > 1 -> "多个分支均已保留。点击恢复，明确选择要继续的存档。"
      automatic.needsAttention -> "自动上传已停止重试。请查看下方原因，修正后点击上传。"
      automatic.waitingForRetry -> "上次上传未完成，稍后自动重试；已有冻结快照会原样保留。"
      retryable -> "待上传快照已保存在本机，等待静默期后自动重试。"
      else -> "自动上传已开启：检测存档变化或游戏退出，稳定后上传。"
     }
     if (automatic.shouldUpload(now, config?.autoUploadPaused != true && OnboardingGate.allowsAutomatic(config?.onboardingProgress, onboardingActive, remoteInspectionReady), connected, state is GameProcessStatus.Stopped, retryable, conflict != null || remoteTips.size > 1, busy)) {
      val baseline = withContext(Dispatchers.IO) { manager.baselineFiles() }
      if (busy || connectingAtStartup || page != Page.HOME || onboardingActive || config?.onboardingProgress != OnboardingProgress.COMPLETE || !remoteInspectionReady || session !== manager || !connected || appClosed) continue
      if (!retryable && files == baseline) automatic.succeeded(files)
      else execute(operationEvents.begin(Action.PUSH), isAutomatic = true)
     } else if (connected && !busy && (remoteReadAt == 0L || now - remoteReadAt >= 60000)) readRemote()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { automaticStatus = "存档正在变化或暂不可读，继续等待；没有采集或覆盖存档。" }
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
        title = "1 / 3  找到游戏与本机存档", subtitle = "只检查默认存档位置和有限的游戏安装位置，不运行游戏。",
        footer = {
         discoveryNotice?.let { Text(it, color = muted, style = MaterialTheme.typography.body2) }
         if (busy || connectingAtStartup) LinearProgressIndicator(Modifier.fillMaxWidth())
         Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(enabled = !busy && !connectingAtStartup, onClick = { rescanDiscovery() }) { Text("重新扫描") }
          Spacer(Modifier.weight(1f))
          Button(enabled = !busy && !connectingAtStartup && discoveredTarget?.trusted == true && pathResult.isSuccess, onClick = { continueDiscovery() }) { Text("继续") }
         }
        }
       ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
         Text("默认存档位置", fontWeight = FontWeight.Medium)
         SelectionContainer { Text(pathText, color = muted, style = MaterialTheme.typography.body2) }
         Text(if (discovery?.defaultSave?.available == true) "已找到默认目录。下一步连接后再比较云端与本机进度。"
          else "尚未找到默认目录。继续会仅准备这个空目录，供之后恢复；不会创建游戏存档或上传内容。", color = muted)
         discovery?.defaultSave?.reason?.let { Text(it, color = Color(0xFF986337), style = MaterialTheme.typography.caption) }
         Divider()
         Text("目标游戏", fontWeight = FontWeight.Medium)
         discoveredTarget?.let { Text("Shattered ${it.displayVersion} · 内部版本 ${it.versionCode}", color = green); SelectionContainer { Text(it.path, style = MaterialTheme.typography.caption) } }
         discovery?.trustedTargets?.takeIf { discovery?.requiresSelection == true && it.isNotEmpty() }?.forEach { game ->
          Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { discoveredTarget = game; discoveryNotice = "已选择此游戏。" }, verticalAlignment = Alignment.CenterVertically) {
           RadioButton(selected = discoveredTarget?.path == game.path, onClick = { discoveredTarget = game }, enabled = !busy)
           Column { Text("Shattered ${game.displayVersion}"); Text(game.path, color = muted, style = MaterialTheme.typography.caption) }
          }
         }
         OutlinedButton(enabled = !busy && !connectingAtStartup, onClick = { chooseSetupGame() }) { Text("手动选择游戏 JAR…") }
         Text("未找到游戏、存在多个版本或当前安装方式无法识别时，请手动选择。当前只信任已核实的官方 4.0.2 JAR；不会根据存档猜测安装版本。", color = muted, style = MaterialTheme.typography.caption)
         discovery?.diagnostics?.forEach { Text(it, color = muted, style = MaterialTheme.typography.caption) }
        }
       }
       Page.COMPARISON -> GuidedPage(
        title = "3 / 3  选择要继续的存档", subtitle = "先核验完整云端提交与祖先，再比较内容。此步骤完成前自动上传暂停。",
        footer = {
         comparisonNotice?.let { Text(it, Modifier.heightIn(max = 72.dp).verticalScroll(rememberScrollState()), color = muted, style = MaterialTheme.typography.body2) }
         comparison?.blocker?.let { Text(it, color = Color(0xFF986337), style = MaterialTheme.typography.body2) }
         if (busy || connectingAtStartup) LinearProgressIndicator(Modifier.fillMaxWidth())
         if (setupConfirm != null) {
          Text(if (setupConfirm == SetupAction.RESTORE) "确认后会先保留本机进度并生成备份，再恢复所选整份存档。没有的局会移除，原始分支保留。" else "只关联这份已核验的云端提交，不改写存档内容，也不上传新根提交。", color = muted)
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
           TextButton(enabled = !busy, onClick = { setupConfirm = null; comparisonNotice = "已取消选择，双方存档未改变。" }) { Text("取消") }
           Spacer(Modifier.weight(1f))
           Button(enabled = !busy, onClick = { setupConfirm?.let { performOnboarding(it) } }) { Text(if (setupConfirm == SetupAction.RESTORE) "保留并恢复所选存档" else "确认采用云端关联") }
          }
         } else {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
           TextButton(enabled = !busy, onClick = { onboardingActive = true; setupDraft = config ?: setupDraft; settingsMode = SettingsMode.FULL; navigate(Page.CONNECTION) }) { Text("返回连接") }
           TextButton(enabled = !busy, onClick = { inspectOnboarding() }) { Text("重新比较") }
           Spacer(Modifier.weight(1f))
           TextButton(enabled = !busy, onClick = { postponeOnboarding() }) { Text("稍后处理") }
          }
          comparison?.let { value ->
           if (value.kind == OnboardingKind.BOTH_EMPTY) Button(enabled = !busy, onClick = { postponeOnboarding(emptyDecision = true) }, modifier = Modifier.fillMaxWidth()) { Text("进入主页，稍后创建存档") }
           else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (value.localFiles.isNotEmpty() || value.retryablePending) Button(enabled = !busy && (value.kind != OnboardingKind.BLOCKED || value.retryablePending), onClick = { performOnboarding(SetupAction.UPLOAD) }, modifier = Modifier.weight(1f)) { Text(if (value.retryablePending) "重试已冻结的上传" else if (value.remoteEntries.isEmpty()) "上传本机存档" else "上传本机，保留云端分支") }
            if (value.canAdoptRemote) OutlinedButton(enabled = !busy, onClick = { setupConfirm = SetupAction.ADOPT }, modifier = Modifier.weight(1f)) { Text("采用云端关联") }
            else if (value.remoteEntries.isNotEmpty()) OutlinedButton(enabled = !busy && comparisonSelection != null && value.kind != OnboardingKind.BLOCKED, onClick = { setupConfirm = SetupAction.RESTORE }, modifier = Modifier.weight(1f)) { Text("恢复所选云端存档") }
           }
          }
         }
        }
       ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
         Text(when (comparison?.kind) {
          null -> if (busy) "正在核验云端与本地…" else "尚未完成比较，请重试。"
          OnboardingKind.BOTH_EMPTY -> "双方暂无存档。保持同步器开启，去游戏里创建进度并退出后会自动上传；首次上传前重启同步器会重新比较。"
          OnboardingKind.LOCAL_ONLY -> "仅本机有存档。确认上传后，才会启用后续自动上传。"
          OnboardingKind.REMOTE_ONLY -> "仅云端有存档。选择下方快照并恢复到本机。"
          OnboardingKind.IDENTICAL -> "存档内容相同，但同步关联还需确认。不会仅凭相同内容猜测提交历史。"
          OnboardingKind.DIFFERENT -> "本机与云端内容不同。上传会保留双方分支；恢复也会先保留本机进度。"
          OnboardingKind.BRANCHED -> "发现多个云端分支，请查看设备、时间和版本，明确选择完整进度。"
          OnboardingKind.BLOCKED -> "暂不能判断同步方向。未将异常或不完整的云端当作空存档。"
         }, fontWeight = FontWeight.Medium)
         comparison?.let { value ->
          Surface(color = Color(0xFFE3EFE6)) {
           Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("本机 · ${device?.displayLabel() ?: "设备未知"}")
            Text("${OnboardingGate.localFileCountLabel(value.kind, value.localFiles.size, value.retryablePending)} · ${saveInfo?.summary() ?: "无可识别存档"}", color = muted, style = MaterialTheme.typography.caption)
            value.localLatestModifiedAt?.let { Text("存档修改 ${localTime(it)}", color = muted, style = MaterialTheme.typography.caption) }
            comparisonCheckedAt?.let { Text("本次核验 ${localTime(it)}", color = muted, style = MaterialTheme.typography.caption) }
           }
          }
          value.remoteEntries.forEach { remote ->
           val m = remote.entry.manifest
           Surface(color = if (comparisonSelection == m.id) Color(0xFFE3EFE6) else Color.White) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { comparisonSelection = m.id }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
             RadioButton(selected = comparisonSelection == m.id, onClick = { comparisonSelection = m.id }, enabled = !busy)
             Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("云端 · ${m.metadata?.device?.displayLabel() ?: "旧快照 · 设备未知"}")
              Text(snapshotTimes(m, null, remote.entry.remoteObservedAt), color = muted, style = MaterialTheme.typography.caption)
              Text("${m.files.size} 个文件 · ${m.saveVersions?.summary() ?: m.version}", style = MaterialTheme.typography.caption)
              Text("分支 ${remote.commit.id.take(8)} · ${if (m.files == value.localFiles) "与本机内容相同" else "与本机内容不同"}", color = muted, style = MaterialTheme.typography.caption)
             }
            }
           }
          }
         }
         Text("时间只供判断，不会自动选最新。恢复会继续检查可信游戏版本、游戏退出状态、存档静默和备份。", color = muted, style = MaterialTheme.typography.caption)
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
           Text(if (config?.onboardingProgress == OnboardingProgress.COMPLETE) automaticStatus else "首次存档选择尚未完成，自动上传暂停。点击上传或恢复继续检查。", color = green, style = MaterialTheme.typography.body2)
           Text("设备：${device?.displayLabel() ?: "正在读取"}  ·  ${saveInfo?.summary() ?: "尚未识别本地存档"}", color = muted, style = MaterialTheme.typography.caption)
           Text("设备名与时间随快照上传；原始游戏存档不改写。", color = muted, style = MaterialTheme.typography.caption)
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
         if (locationIssue != null) Text(locationIssue!!, color = Color(0xFF986337), style = MaterialTheme.typography.caption)
         if (selected != null && restoreIssue != null && remoteTips.size <= 1 && conflict == null) Text("恢复暂不可用：$restoreIssue", color = Color(0xFF986337), style = MaterialTheme.typography.caption)
         if (pending) Text("待上传操作已冻结，重试仍使用原内容。恢复暂不可用。", color = Color(0xFF986337), style = MaterialTheme.typography.caption)
         Card(Modifier.weight(1f).fillMaxWidth(), elevation = 0.dp) {
          Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
           Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("选择要恢复的存档", style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
            Text("${history.size} 份", color = muted, style = MaterialTheme.typography.body2)
           }
           Text("自动读取云端分支，保留本机历史及恢复前备份。点击一项，再点击恢复。", color = muted, style = MaterialTheme.typography.caption)
           if (history.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("还没有快照。可点击上传，或等待云端读取完成。", color = muted)
           } else LazyColumn(Modifier.weight(1f), state = historyScroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history, key = { it.manifest.id }) { entry ->
             val m = entry.manifest
             Surface(color = if (selected == m.id) Color(0xFFE3EFE6) else Color(0xFFF7F8F5)) {
              Column(Modifier.fillMaxWidth().clickable(enabled = !busy && !connectingAtStartup) { selected = m.id }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
               Text(m.metadata?.device?.displayLabel() ?: "旧快照 · 设备未知", fontWeight = FontWeight.Medium)
               Text(snapshotTimes(m, entry.savedAt, remoteObserved[m.id] ?: entry.remoteObservedAt), color = muted, style = MaterialTheme.typography.caption)
               Text("${if (m.id in remoteTips) "云端分支 · " else ""}${if (entry.remoteObservedAt != null) entry.reason.replace("待追加发布", "已上传").replace("待上传快照", "同步快照") else entry.reason}  ·  ${m.files.size} 个文件  ·  ${m.id.take(8)}", color = muted, style = MaterialTheme.typography.caption)
              }
             }
            }
           }
          }
         }
         if (busy || connectingAtStartup) LinearProgressIndicator(Modifier.fillMaxWidth())
         connectionNotice?.let { Text(it, color = Color(0xFF986337), style = MaterialTheme.typography.caption) }
         Text(status, Modifier.fillMaxWidth().heightIn(max = 76.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.body2, color = muted)
        }
       }
       Page.SETTINGS -> GuidedPage(
        title = "设置", subtitle = "自动上传在同步器打开且云端已登录时工作。",
        footer = { TextButton(enabled = !busy && !connectingAtStartup, onClick = { navigate(Page.HOME) }) { Text("返回存档") } }
       ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
         Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) { Text("自动上传"); Text("关闭后保留手动上传与所有待上传快照。", color = muted, style = MaterialTheme.typography.caption) }
          Switch(checked = config?.autoUploadPaused != true, enabled = config != null && !busy, onCheckedChange = { setAutomaticPaused(!it) })
         }
         Divider()
         Text("游戏检测与恢复校验", style = MaterialTheme.typography.subtitle1)
         Text(when { targetError != null -> "所选目标暂不可用：$targetError"; targetInfo == null -> "请选择官方游戏 JAR，才能核对游戏进程与恢复版本。"; else -> "Shattered ${targetInfo!!.displayVersion}  ·  ${processMessage(gameStatus)}" }, color = muted)
         OutlinedButton(enabled = config != null && !busy, onClick = { selectTargetGame() }) { Text("选择游戏 JAR…") }
         Text("按游戏以普通权限运行检测当前账户；无游戏线索的受限进程不拦截。已识别游戏状态不明时暂停操作。请避免 Steam Cloud 等同时写入。", color = muted, style = MaterialTheme.typography.caption)
         if (gameStatus is GameProcessStatus.Unknown) Text("无法确认游戏状态，请关闭游戏后重试。检测失败时不会上传或恢复。", color = Color(0xFF986337), style = MaterialTheme.typography.caption)
         Text("默认存档位置", style = MaterialTheme.typography.subtitle1)
         SelectionContainer { Text(pathText, style = MaterialTheme.typography.caption) }
         TextButton(enabled = !busy && !connectingAtStartup, onClick = { loadProfile(config) }) { Text("重新检查存档") }
         OutlinedButton(enabled = !busy && !connectingAtStartup, onClick = { openConnection(SettingsMode.FULL) }) { Text("云端连接设置") }
         TextButton(enabled = !busy && !connectingAtStartup, onClick = { onboardingActive = true; setupDraft = config ?: newConnectionConfig(); navigate(Page.DISCOVERY); rescanDiscovery() }) { Text("重新检查三步引导") }
         if (pending) TextButton(enabled = !busy && !connectingAtStartup, onClick = { requestConfirmation(Action.ARCHIVE) }) { Text("检查并归档中断的待上传操作") }
         Text("实验版本：请另留备份。断电事务、其他写入程序及跨平台原生运行仍有验证限制。", color = Color(0xFF986337), style = MaterialTheme.typography.caption)
        }
       }
       Page.CONNECTION -> key(pageGeneration) {
        val generation = pageGeneration
        fun current() = !appClosed && page == Page.CONNECTION && pageGeneration == generation
        ConnectionPage(settingsMode, configFile, if (onboardingActive) setupDraft else config, password, secretStore, connectionNotice, busy || connectingAtStartup, onboardingActive,
         onBusy = { if (current()) busy = it },
         onDismiss = { if (current() && !busy && !connectingAtStartup) navigate(if (onboardingActive) Page.DISCOVERY else Page.HOME) },
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
         onConfirm = { requestConfirmation(Action.RESOLVE) })
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
   Text(subtitle, color = muted, style = MaterialTheme.typography.body2)
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
private fun ConnectionPage(mode: SettingsMode, configFile: ConfigFile, initial: AppConfig?, currentPassword: String, secretStore: SecureCredentialStore, initialNotice: String?, busy: Boolean, onboarding: Boolean, onBusy: (Boolean) -> Unit, onDismiss: () -> Unit, onApplied: (AppliedConnection, String?) -> Unit) {
 val controller = remember { ConnectionSetup(initial) }
 val lifetime = remember { ConnectionPageLifetime() }
 var draft by remember { mutableStateOf(initial ?: newConnectionConfig()) }
 var secret by remember { mutableStateOf(currentPassword) }
 var message by remember { mutableStateOf(initialNotice) }
 var failed by remember { mutableStateOf(false) }
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
  // Keep the last failure visible while correcting inputs, until the next explicit attempt.
 }
 fun cancel() {
  if (busy || inFlight || closed) return
  closed = true; lifetime.close { controller.cancel() }; secret = ""; onDismiss()
 }
 fun connect() {
  if (busy || inFlight || closed) return
  val ticket = lifetime.begin() ?: return
  inFlight = true; onBusy(true); failed = false; message = "正在验证连接并准备应用目录…"
  val submitted = ConnectionDraft(draft, secret)
  scope.launch {
   var applied: AppliedConnection? = null
   var note: String? = null
   var secretChanged = false
   var verified = false
   var finished = false
   try {
    controller.update(submitted.config, submitted.password)
    when (val checked = withContext(Dispatchers.IO) { controller.connect() }) {
     is ConnectionTestResult.Failure -> if (lifetime.isCurrent(ticket)) { message = checked.message; failed = true }
     is ConnectionTestResult.Success -> {
      verified = true
      applied = withContext(Dispatchers.IO) {
       lifetime.persist(ticket) {
        // Only a previously opted-in, unchanged identity can retain secure password storage.
        // Disposal cannot interleave between this guard, credential storage and config apply.
        var stored = false
        if (submitted.config.rememberPassword) {
         val written = secretStore.save(submitted.config.endpoint, submitted.config.username, submitted.password)
         stored = written is SecretWriteResult.Success
         secretChanged = stored
         if (!stored) note = "安全存储不可用，密码仅用于本次会话。${(written as SecretWriteResult.Unavailable).message}"
        }
        controller.apply(configFile, stored)
       }
      }
     }
    }
   } catch (e: CancellationException) { throw e }
   catch (e: Exception) {
    if (lifetime.isCurrent(ticket)) {
     message = if (verified) "连接已通过，但本机设置未保存：${e.message ?: "请检查配置文件写入权限后重试"}" else "连接未完成，请检查地址、网络与服务状态后重试。"
     if (secretChanged) message += " 系统安全存储中的密码已更新，其他设置尚未保存。"
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
  title = if (onboarding) "2 / 3  连接云端" else if (mode == SettingsMode.UNLOCK) "欢迎回来，连接云端" else if (initial == null) "让存档跟着你" else "连接设置",
  subtitle = if (onboarding) "配置 WebDAV。连接成功后比较云端与本机存档，再选择上传或恢复。" else "输入 WebDAV 地址、用户名和密码。",
  footer = {
   // Feedback and the single submit action remain visible independently of form scrolling.
   message?.let {
    val feedbackScroll = rememberScrollState()
    LaunchedEffect(it) { feedbackScroll.scrollTo(0) }
    Box(Modifier.fillMaxWidth().heightIn(max = 80.dp)) {
     Text(it, Modifier.fillMaxWidth().verticalScroll(feedbackScroll).padding(end = 12.dp), color = if (failed) Color(0xFFAD4634) else muted, style = MaterialTheme.typography.body2)
     Box(Modifier.matchParentSize()) { VerticalScrollbar(rememberScrollbarAdapter(feedbackScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight()) }
    }
   }
   if (controlsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    TextButton(enabled = !controlsBusy, onClick = { cancel() }) { Text(if (onboarding) "返回游戏检查" else "取消，返回主页") }
    Spacer(Modifier.weight(1f))
    Button(enabled = !controlsBusy, onClick = { connect() }) { Text(if (inFlight) "正在连接…" else "连接") }
   }
  }
 ) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   OutlinedTextField(draft.endpoint, { edit(draft.copy(endpoint = it)) }, Modifier.fillMaxWidth(), enabled = !controlsBusy, label = { Text("WebDAV 地址") }, placeholder = { Text("https://example.com/dav") }, singleLine = true)
   OutlinedTextField(draft.username, { edit(draft.copy(username = it)) }, Modifier.fillMaxWidth(), enabled = !controlsBusy, label = { Text("用户名") }, singleLine = true)
   OutlinedTextField(secret, { edit(password = it) }, Modifier.fillMaxWidth(), enabled = !controlsBusy, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
   Text(if (draft.rememberPassword) "沿用此连接的系统安全密码存储。" else "密码仅用于本次会话，不保存到磁盘。", color = muted, style = MaterialTheme.typography.caption)
   Text("无需手动建目录。连接会按需创建应用空文件夹；取消不会删除已创建的文件夹。", color = muted, style = MaterialTheme.typography.caption)
  }
 }
}

@Composable
private fun ConflictPage(value: AppendConflict, selected: String?, restoreIssue: String?, busy: Boolean, connected: Boolean, onSelect: (String) -> Unit, onBack: () -> Unit, onConfirm: () -> Unit) {
 GuidedPage(
  title = "选择要继续的存档",
  subtitle = "${value.branches.size} 个进度分支均已保留。请明确选择一份完整快照。",
  footer = {
   restoreIssue?.let { Text("选择后校验：$it", color = Color(0xFF986337), style = MaterialTheme.typography.caption) }
   Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    TextButton(enabled = !busy, onClick = onBack) { Text("稍后处理，返回主页") }
    Spacer(Modifier.weight(1f))
    Button(enabled = !busy && connected && selected != null && restoreIssue == null, onClick = onConfirm) { Text("采用所选快照") }
   }
  }
 ) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   Text("会创建引用本次全部分支的新解决提交；未观察到的后来分支仍会显示冲突，不按时间自动选胜者。", color = muted, style = MaterialTheme.typography.body2)
   value.branches.forEach { branch ->
    val m = branch.snapshot.manifest
    Surface(color = if (selected == m.id) Color(0xFFE3EFE6) else Color.White, shape = MaterialTheme.shapes.small) {
     Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { onSelect(m.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      RadioButton(selected = selected == m.id, onClick = { onSelect(m.id) }, enabled = !busy)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
       Text(m.metadata?.device?.displayLabel() ?: "旧快照 · 设备未知")
       Text(snapshotTimes(m, null, null), color = muted, style = MaterialTheme.typography.caption)
       Text("分支 ${branch.commit.id.take(8)} · ${m.files.size} 个文件")
       if (m.files == value.observedLocalFiles) Text("与当前本地进度一致", color = green, style = MaterialTheme.typography.caption)
       if (branch.commit.id == value.publishedLocalCommit) Text("本机最近发布的分支", color = muted, style = MaterialTheme.typography.caption)
       if (m.files != value.observedLocalFiles && branch.commit.id != value.publishedLocalCommit) Text("其他已观察分支 · 内容校验 ${m.sha256.take(12)}", color = muted, style = MaterialTheme.typography.caption)
       Text(m.saveVersions?.summary() ?: m.version, style = MaterialTheme.typography.caption)
      }
     }
    }
   }
   Text("需要替换当前档案时会先备份，再恢复所选快照。所有原始分支保留，不合并二进制。", color = muted, style = MaterialTheme.typography.body2)
  }
 }
}

@Composable
private fun ConfirmationPage(action: Action, root: String?, pending: Boolean, branch: String?, snapshot: Manifest?, busy: Boolean, notice: String?, onBack: () -> Unit, onConfirm: () -> Unit) {
 val restoring = action in setOf(Action.RESTORE, Action.RESOLVE, Action.REMOTE_TIP)
 GuidedPage(
  title = if (restoring) "备份并恢复整个档案" else if (action == Action.ARCHIVE) "归档待上传快照" else "操作前确认",
  subtitle = "请核对操作范围。取消会返回主页，当前存档不会改变。",
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
   Text("默认目录：$root", color = muted, style = MaterialTheme.typography.caption)
   if (action == Action.ARCHIVE) Text("只移除待上传标记，历史保留，不删除云端内容。若此前恢复中断，请先核对当前档案与备份；归档也会解除中断恢复保护，下次同步将重新采集当前档案。")
   else {
    if (restoring && snapshot != null) {
     Text("所选存档：${snapshot.metadata?.device?.displayLabel() ?: "旧快照 · 设备未知"}  ·  ${snapshot.id.take(8)}", fontWeight = FontWeight.Medium)
     Text(snapshotTimes(snapshot, null, null), color = muted, style = MaterialTheme.typography.caption)
    }
    if (restoring) Text("先生成可恢复备份，再替换所有局与全局记录。快照中没有的局将移除。旧档可能在游戏加载时升级；原档备份保留。")
    if (action == Action.PUSH && pending) Text("将重试已冻结的快照，可能不同于当前目录的最新内容。")
    if (action == Action.RESOLVE) Text("将采用所选分支 ${branch?.take(8)}，并追加多父提交。全部原始快照继续保留。")
    Text("将自动检测游戏是否退出，并等待存档稳定。检测未知、版本不兼容或检测到文件变化时会停止，不需要勾选。", color = muted, style = MaterialTheme.typography.body2)
    Text("请避免 Steam Cloud 或其他程序同时写入。进程检测无法锁住其他写入者，断电及并发写入仍有风险。", color = muted, style = MaterialTheme.typography.caption)
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
private fun snapshotTimes(manifest: Manifest, savedAt: String?, observedAt: String?): String = buildList {
 manifest.metadata?.capturedAt?.let { add("采集 ${localTime(it)}") } ?: add("原始采集时间未知")
 manifest.metadata?.latestFileModifiedAt?.let { add("存档修改 ${localTime(it)}") }
 if (observedAt != null) add("本机云端核验 ${localTime(observedAt)}")
 else savedAt?.let { add("本机入库 ${localTime(it)}") }
}.joinToString("  ·  ")
