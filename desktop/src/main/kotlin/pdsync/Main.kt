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
private enum class Page { HOME, SETTINGS, CONNECTION, CONFLICT, CONFIRM }
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

/** Production always uses fresh process detection. Dependency injection is only used by test code. */
internal fun runSyncApplication(
 processStatus: (TargetGame?) -> GameProcessStatus = { GameProcessDetector(it).status() },
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
  var page by remember { mutableStateOf(if (config == null) Page.CONNECTION else Page.HOME) }
  var pageGeneration by remember { mutableStateOf(0L) }
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
   settingsMode = mode; navigate(Page.CONNECTION)
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
    selected = history.firstOrNull()?.manifest?.id; remoteTips = emptySet(); conflictSelection = null; conflict = null
    device = data?.device; automatic = AutomaticUpload().apply { data?.baselineFiles?.let(::establishBaseline) }; remoteReadAt = 0L
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
      val next = saved.copy(targetGame = chosen)
      withContext(Dispatchers.IO) { configFile.save(next) }
      config = next; updated = next
     }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { status = "目标游戏未保存：${e.message ?: "请检查配置文件写入权限"}" }
    finally { busy = false }
    // Reload only after releasing the shared guard. This never reopens connection settings.
    updated?.let { loadProfile(it) }
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
   busy = true; remoteReadAt = monotonicMillis(); status = "正在读取并校验云端快照…"
   scope.launch {
    try {
     val entries = withContext(Dispatchers.IO) { manager.readRemote(AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, account, secret)) }
     remoteTips = entries.map { it.entry.manifest.id }.toSet()
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
  LaunchedEffect(Unit) {
   // Startup is one operation: profile loading cannot release the credential probe's guard.
   val startupGeneration = pageGeneration
   fun current() = !appClosed && pageGeneration == startupGeneration
   fun requestUnlock(notice: String? = null) {
    if (!current()) return
    settingsMode = SettingsMode.UNLOCK; connectionNotice = notice; page = Page.CONNECTION
   }
   try {
    refreshProfile(config)
    val saved = config
    if (saved == null) {
     if (current()) {
      settingsMode = SettingsMode.FULL; page = Page.CONNECTION
      initialResult.exceptionOrNull()?.let { connectionNotice = "旧设置无法读取，原文件保留。请重新配置。" }
     }
    } else if (saved.rememberPassword) {
     // Only this exact endpoint/account's own credential is queried; startup never provisions.
     val restored = withContext(Dispatchers.IO) { secretStore.read(saved.endpoint, saved.username) }
     if (restored is SecretReadResult.Found) {
      val checker = ConnectionSetup(saved)
      try {
       checker.update(saved, restored.password)
       val checked = withContext(Dispatchers.IO) { checker.testConnection() }
       if (current()) {
        if (checked is ConnectionTestResult.Success) { password = restored.password; connected = true }
        else { requestUnlock((checked as ConnectionTestResult.Failure).message); status = "自动连接未成功，请检查连接信息。" }
       }
      } finally { checker.cancel() }
     } else requestUnlock(if (restored is SecretReadResult.Unavailable) restored.message else "未找到已保存的密码，请重新输入。")
    } else requestUnlock()
   } catch (e: CancellationException) { throw e }
   catch (_: Exception) { requestUnlock("自动连接未完成，请检查连接信息后重试。") }
   finally { connectingAtStartup = false }
  }
  LaunchedEffect(session, connected) {
   while (isActive && !appClosed) {
    delay(1000)
    if (busy || connectingAtStartup || page != Page.HOME) continue
    val manager = session ?: continue
    try {
     val target = targetInfo
     val observation = withContext(Dispatchers.IO) {
      val state = processStatus(target)
      state to manager.observedFiles()
     }
     if (busy || connectingAtStartup || page != Page.HOME || session !== manager) continue
     val state = observation.first; val files = observation.second; val now = monotonicMillis()
     gameStatus = state
     automatic.observe(files, state is GameProcessStatus.Running, state is GameProcessStatus.Stopped, now)
     val retryable = withContext(Dispatchers.IO) { manager.retryablePending() }
     if (busy || connectingAtStartup || page != Page.HOME || session !== manager || appClosed) continue
     automaticStatus = when {
      config?.autoUploadPaused == true -> "自动上传已暂停；仍可手动上传。"
      !connected -> "等待登录，已有快照与待上传操作保留。"
      state !is GameProcessStatus.Stopped -> processMessage(state)
      pending && !retryable -> "有待检查的中断操作，请在设置中处理。"
      conflict != null || remoteTips.size > 1 -> "多个分支均已保留。点击恢复，明确选择要继续的存档。"
      automatic.needsAttention -> "自动上传已停止重试。请查看下方原因，修正后点击上传。"
      automatic.waitingForRetry -> "网络未完成，稍后自动重试同一份冻结快照。"
      retryable -> "待上传快照已保存在本机，等待静默期后自动重试。"
      else -> "自动上传已开启：检测存档变化或游戏退出，稳定后上传。"
     }
     if (automatic.shouldUpload(now, config?.autoUploadPaused != true, connected, state is GameProcessStatus.Stopped, retryable, conflict != null || remoteTips.size > 1, busy)) {
      val baseline = withContext(Dispatchers.IO) { manager.baselineFiles() }
      if (busy || connectingAtStartup || page != Page.HOME || session !== manager || !connected || appClosed) continue
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
           Text(automaticStatus, color = green, style = MaterialTheme.typography.body2)
           Text("设备：${device?.displayLabel() ?: "正在读取"}  ·  ${saveInfo?.summary() ?: "尚未识别本地存档"}", color = muted, style = MaterialTheme.typography.caption)
           Text("设备名与时间随快照上传；原始游戏存档不改写。", color = muted, style = MaterialTheme.typography.caption)
          }
         }
         Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Button(enabled = !busy && !connectingAtStartup && (!connected || session != null), onClick = {
           if (!connected) openConnection(if (config == null) SettingsMode.FULL else SettingsMode.UNLOCK)
           else execute(operationEvents.begin(Action.PUSH))
          }, modifier = Modifier.weight(1f)) { Text("上传") }
          OutlinedButton(enabled = session != null && !busy && !connectingAtStartup && !pending && (selected !in remoteTips || connected) &&
           (conflict != null || remoteTips.size > 1 || selected != null && restoreIssue == null), onClick = { openRestore() }, modifier = Modifier.weight(1f)) { Text("恢复") }
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
         Text("只读取，不启动游戏。检测当前账户的游戏进程；相关进程信息不可见时停止操作。其他账户、Steam Cloud 或其他程序仍可能写入，请避免同时同步；这不是全局互斥锁。", color = muted, style = MaterialTheme.typography.caption)
         if (gameStatus is GameProcessStatus.Unknown) Text("游戏已退出仍无法检测时，请在普通桌面会话检查进程可见性或运行只读诊断。不要强行结束不认识的系统进程，也不要绕过权限限制。", color = Color(0xFF986337), style = MaterialTheme.typography.caption)
         Text("默认存档位置", style = MaterialTheme.typography.subtitle1)
         SelectionContainer { Text(pathText, style = MaterialTheme.typography.caption) }
         TextButton(enabled = !busy && !connectingAtStartup, onClick = { loadProfile(config) }) { Text("重新检查存档") }
         OutlinedButton(enabled = !busy && !connectingAtStartup, onClick = { openConnection(SettingsMode.FULL) }) { Text("云端连接设置") }
         if (pending) TextButton(enabled = !busy && !connectingAtStartup, onClick = { requestConfirmation(Action.ARCHIVE) }) { Text("检查并归档中断的待上传操作") }
         Text("实验版本：请另留备份。断电事务、其他写入程序及跨平台原生运行仍有验证限制。", color = Color(0xFF986337), style = MaterialTheme.typography.caption)
        }
       }
       Page.CONNECTION -> key(pageGeneration) {
        val generation = pageGeneration
        fun current() = !appClosed && page == Page.CONNECTION && pageGeneration == generation
        ConnectionPage(settingsMode, configFile, config, password, secretStore, connectionNotice, busy || connectingAtStartup,
         onBusy = { if (current()) busy = it },
         onDismiss = { if (current() && !busy && !connectingAtStartup) navigate(Page.HOME) },
         onApplied = { applied, note ->
          if (current()) {
           config = applied.config; password = applied.password; connected = true
           connectionNotice = note; navigate(Page.HOME); loadProfile(applied.config)
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
private fun ConnectionPage(mode: SettingsMode, configFile: ConfigFile, initial: AppConfig?, currentPassword: String, secretStore: SecureCredentialStore, initialNotice: String?, busy: Boolean, onBusy: (Boolean) -> Unit, onDismiss: () -> Unit, onApplied: (AppliedConnection, String?) -> Unit) {
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
  title = if (mode == SettingsMode.UNLOCK) "欢迎回来，连接云端" else if (initial == null) "让存档跟着你" else "连接设置",
  subtitle = "输入 WebDAV 地址、用户名和密码。连接成功后进入存档主页。",
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
    TextButton(enabled = !controlsBusy, onClick = { cancel() }) { Text("取消，返回主页") }
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
