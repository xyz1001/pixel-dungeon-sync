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
private enum class Action { CAPTURE, PUSH, RESTORE, RESOLVE, REMOTE_TIP, ARCHIVE }
private enum class SettingsMode { FULL, UNLOCK }
private enum class Page { HOME, CONNECTION, CONFLICT, CONFIRM }
private data class OperationResult(val message: String, val conflict: AppendConflict? = null)
private data class LoadedProfile(val session: AppendProfileSession, val history: List<HistoryEntry>, val pending: Boolean, val saves: SaveVersions?, val target: TargetGame?, val targetError: String?)
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

fun main() = application {
 var busy by remember { mutableStateOf(false) }
 var closeBlocked by remember { mutableStateOf(false) }
 var connectingAtStartup by remember { mutableStateOf(true) }
 var appClosed by remember { mutableStateOf(false) }
 Window(onCloseRequest = { if (busy || connectingAtStartup) closeBlocked = true else { appClosed = true; exitApplication() } }, title = "像素地牢 · 存档同步", state = rememberWindowState(width = 1120.dp, height = 800.dp)) {
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
  var confirm by remember { mutableStateOf<Action?>(null) }
  var restoreIssue by remember { mutableStateOf<String?>("请选择快照") }
  var conflictRestoreIssue by remember { mutableStateOf<String?>("尚未校验") }
  var locationIssue by remember { mutableStateOf<String?>(pathResult.exceptionOrNull()?.message) }
  var status by remember { mutableStateOf("正在检查默认存档位置…") }
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
   confirm = action; navigate(Page.CONFIRM)
  }
  suspend fun refreshProfile(value: AppConfig?) {
   try {
    val data = withContext(Dispatchers.IO) {
     location.requireDirectory()
     if (value == null) null else {
      val manager = location.open(value, workspace.resolve(".local/manager"))
      val target = runCatching { manager.targetInfo() }
      LoadedProfile(manager, manager.history(), manager.hasPending(), manager.currentVersions(), target.getOrNull(), target.exceptionOrNull()?.message)
     }
    }
    if (appClosed) return
    locationIssue = null; session = data?.session; history = data?.history ?: emptyList(); pending = data?.pending ?: false
    saveInfo = data?.saves; targetInfo = data?.target; targetError = data?.targetError
    selected = history.firstOrNull()?.manifest?.id; remoteTips = emptySet(); conflictSelection = null; conflict = null
    status = if (value == null) "默认存档位置已找到。连接云端后即可管理快照。" else "存档与历史已刷新。操作前请退出游戏并暂停其他同步。"
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
  fun execute(action: Action) {
   if (busy || connectingAtStartup || appClosed) return
   val manager = session ?: return
   val chosen = selected; val branchChoice = conflictSelection; val divergence = conflict; val account = config?.username ?: ""; val secret = password
   busy = true; confirm = null; navigate(Page.HOME)
   status = when (action) {
    Action.RESTORE, Action.RESOLVE, Action.REMOTE_TIP -> "正在校验并备份当前档案，然后恢复…"
    Action.PUSH -> "正在保留快照并校验云端…"
    else -> "正在处理…"
   }
   scope.launch {
    try {
     val result = withContext(Dispatchers.IO) {
      val stopped = { true }
      fun store() = AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, account, secret)
      fun pushed(result: AppendPushResult): OperationResult = when (result) {
       is AppendPushResult.Success -> OperationResult("追加提交已回读校验。" + (if (result.localDirty) "当前本地还有未上传变化；下一次同步会另建提交。" else "所选进度已成为本机同步基线。") + (if (result.remoteAhead) "另有已观察后续分支，可读取查看。" else ""))
       is AppendPushResult.Diverged -> OperationResult("发现 ${result.conflict.branches.size} 个进度分支，全部快照均已保留。请明确选择。", result.conflict)
      }
      when (action) {
       Action.CAPTURE -> manager.createSnapshot(stopped).let { OperationResult("本地快照已保存，可随时从历史中恢复。") }
       Action.PUSH -> pushed(manager.push(store(), stopped))
       Action.REMOTE_TIP -> manager.restoreRemote(requireNotNull(chosen), store(), stopped).let { OperationResult("云端存档已恢复。恢复前备份已加入历史。") }
       Action.RESTORE -> manager.restoreHistory(requireNotNull(chosen), stopped).let { OperationResult("恢复完成，备份已加入历史。新的恢复提交已冻结，点击重试待上传快照即可发布。") }
       Action.RESOLVE -> pushed(manager.resolve(requireNotNull(divergence), requireNotNull(branchChoice), store(), stopped))
       Action.ARCHIVE -> { manager.archivePending(); OperationResult("待上传快照已归档。下次同步将重新采集当前存档。") }
      }
     }
     status = result.message; conflict = result.conflict; conflictSelection = null
     if (result.conflict != null) navigate(Page.CONFLICT)
    } catch (e: Exception) { status = "操作未完成：${e.message}。已保存的历史与备份保留。"; authenticationFailed(e) }
    finally {
     try {
      val data = withContext(Dispatchers.IO) { Triple(manager.history(), manager.hasPending(), manager.currentVersions()) }
      history = data.first; pending = data.second; saveInfo = data.third
      if (selected == null) selected = history.firstOrNull()?.manifest?.id
     } catch (e: Exception) { status += " 历史刷新失败：${e.message}" }
     busy = false
    }
   }
  }
  fun readRemote() {
   if (busy || connectingAtStartup || appClosed || !connected) return
   val manager = session ?: return; val account = config?.username ?: ""; val secret = password
   busy = true; status = "正在读取并校验云端快照…"
   scope.launch {
    try {
     val entries = withContext(Dispatchers.IO) { manager.readRemote(AppendOnlyWebDav(manager.config.endpoint, manager.adapter.id, manager.config.profile, account, secret)) }
     remoteTips = entries.map { it.entry.manifest.id }.toSet(); if (entries.size == 1) selected = entries.single().entry.manifest.id
     history = withContext(Dispatchers.IO) { manager.history() }
     status = if (entries.isEmpty()) "此次未观察到云端提交。可先同步当前存档。" else "已观察到 ${entries.size} 个云端分支，均已加入历史，本地未改动。普通恢复只允许单一分支及干净本地基线。已有本地进度可先同步，再明确选择；空目录可先从历史选定快照恢复，之后同步解决分支。"
    } catch (e: Exception) { status = "读取云端失败：${e.message}"; authenticationFailed(e) }
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
           Text("存档同步  /  SHATTERED", color = muted, style = MaterialTheme.typography.caption)
          }
          Text(if (connected) "● 云端已连接" else "○ 云端未连接", color = if (connected) green else muted, style = MaterialTheme.typography.body2)
          Spacer(Modifier.width(18.dp))
          OutlinedButton(enabled = !busy && !connectingAtStartup, onClick = { openConnection(SettingsMode.FULL) }) { Text("设置") }
         }
         Surface(color = Color(0xFFFFECCD)) {
          Text("实验版本 · 操作前退出游戏、暂停 Steam Cloud 及其他同步。尚无可靠进程检测或断电保护，请另留一份备份。", Modifier.padding(12.dp), style = MaterialTheme.typography.caption, color = Color(0xFF75572C))
         }
         Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
          Column(Modifier.width(340.dp).verticalScroll(homeScroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
           Card(elevation = 0.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
             Text("当前存档", style = MaterialTheme.typography.h6)
             Text(if (session != null) saveInfo?.summary() ?: "版本未知" else if (locationIssue != null) "暂未找到可用存档" else "等待连接设置", color = if (locationIssue != null) Color(0xFF986337) else green)
             Text("默认位置 · 由游戏与系统决定", color = muted, style = MaterialTheme.typography.caption)
             SelectionContainer { Text(pathText, style = MaterialTheme.typography.caption) }
             locationIssue?.let { Text(it, color = Color(0xFF986337), style = MaterialTheme.typography.body2) }
             if (session != null) Text("${saveInfo?.runs?.size ?: 0} 个进行中的局 · 全局记录一同保存", style = MaterialTheme.typography.body2)
             Text(when { targetError != null -> "目标游戏暂不可用，请重新选择 JAR。"; targetInfo == null -> "尚未选择目标游戏。备份与上传可用；恢复前需选择 JAR。"; else -> "恢复目标：Shattered ${targetInfo!!.displayVersion}" }, color = muted, style = MaterialTheme.typography.caption)
             OutlinedButton(enabled = config != null && !busy && !connectingAtStartup, onClick = { selectTargetGame() }) { Text(if (config?.targetGame.isNullOrBlank()) "选择目标游戏 JAR…" else "更换目标游戏 JAR…") }
             Text("只读取游戏版本，不运行 JAR。", color = muted, style = MaterialTheme.typography.caption)
             TextButton(enabled = !busy && !connectingAtStartup, onClick = { loadProfile(config) }, contentPadding = PaddingValues(0.dp)) { Text("重新检查存档") }
            }
           }
           Card(elevation = 0.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
             Text("同步与备份", style = MaterialTheme.typography.h6)
             Button(enabled = !busy && !connectingAtStartup && (!connected || session != null), onClick = { if (!connected) openConnection(if (config == null) SettingsMode.FULL else SettingsMode.UNLOCK) else requestConfirmation(Action.PUSH) }, modifier = Modifier.fillMaxWidth()) {
              Text(if (!connected) "连接云端" else if (pending) "重试待上传快照" else "同步当前存档")
             }
             OutlinedButton(enabled = session != null && !busy && !connectingAtStartup, onClick = { requestConfirmation(Action.CAPTURE) }, modifier = Modifier.fillMaxWidth()) { Text("创建本地快照") }
             TextButton(enabled = session != null && connected && !busy && !connectingAtStartup, onClick = { readRemote() }, modifier = Modifier.fillMaxWidth()) { Text("查看云端已观察分支") }
             if (pending) {
              Text("待上传的是上次冻结的快照。重试会继续原内容。", color = Color(0xFF986337), style = MaterialTheme.typography.caption)
              TextButton(enabled = !busy && !connectingAtStartup, onClick = { requestConfirmation(Action.ARCHIVE) }) { Text("归档待上传，稍后重新采集") }
             }
             if (conflict != null) OutlinedButton(enabled = !busy && !connectingAtStartup, onClick = { navigate(Page.CONFLICT) }) { Text("查看存档冲突") }
            }
           }
           Text("同步包含所有 gameN 局目录，以及排行榜、徽章和日志。设备设置不参与。", Modifier.padding(horizontal = 4.dp), color = muted, style = MaterialTheme.typography.caption)
          }
          Card(Modifier.weight(1f).fillMaxHeight(), elevation = 0.dp) {
           Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
             Text("历史快照", style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
             Text("${history.size} 份", color = muted, style = MaterialTheme.typography.body2)
            }
            Text("本地保存的同步版本与恢复前备份", color = muted, style = MaterialTheme.typography.caption)
            if (history.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
             Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("还没有快照", color = muted, style = MaterialTheme.typography.subtitle1)
              Text(if (session == null) "完成连接设置并检查默认存档位置" else "创建一份本地快照，或读取云端存档", color = muted, style = MaterialTheme.typography.caption)
             }
            } else LazyColumn(Modifier.weight(1f), state = historyScroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
             items(history, key = { it.manifest.id }) { entry ->
              val m = entry.manifest
              Surface(color = if (selected == m.id) Color(0xFFE3EFE6) else Color(0xFFF7F8F5)) {
               Column(Modifier.fillMaxWidth().clickable(enabled = !busy && !connectingAtStartup) { selected = m.id }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (entry.reason == "待上传快照") "同步快照" else entry.reason, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.body2)
                Text("${entry.savedAt.substringBefore('.').replace('T', ' ')} UTC", color = muted, style = MaterialTheme.typography.caption)
                Text("${m.saveVersions?.summary() ?: "恢复前自动校验"}  ·  ${m.files.size} 个文件  ·  ${m.id.take(8)}", color = muted, style = MaterialTheme.typography.caption)
               }
              }
             }
            }
            if (selection != null) {
             Divider()
             Text("恢复将替换整个档案；系统会先保存当前进度。", style = MaterialTheme.typography.caption, color = muted)
             Button(enabled = session != null && !busy && !connectingAtStartup && !pending && restoreIssue == null, onClick = { requestConfirmation(Action.RESTORE) }) { Text("备份后恢复所选快照") }
             if (selected in remoteTips && remoteTips.size == 1) TextButton(enabled = !busy && !connectingAtStartup && connected && !pending && restoreIssue == null, onClick = { requestConfirmation(Action.REMOTE_TIP) }) { Text("恢复此分支并设为同步基线") }
             if (restoreIssue != null) Text("恢复暂不可用：$restoreIssue", color = Color(0xFF986337), style = MaterialTheme.typography.caption)
             if (pending) Text("请先处理或归档待上传快照。", color = Color(0xFF986337), style = MaterialTheme.typography.caption)
            }
           }
          }
         }
         if (busy || connectingAtStartup) LinearProgressIndicator(Modifier.fillMaxWidth())
         connectionNotice?.let { Text(it, color = Color(0xFF986337), style = MaterialTheme.typography.caption) }
         Text(status, Modifier.fillMaxWidth(), style = MaterialTheme.typography.body2, color = muted)
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
       Page.CONFIRM -> confirm?.let { action ->
        ConfirmationPage(action, session?.root?.toString(), pending, conflictSelection, busy || connectingAtStartup,
         onBack = { if (!busy && !connectingAtStartup) { confirm = null; navigate(Page.HOME) } },
         onConfirm = { execute(action) })
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
private fun ConfirmationPage(action: Action, root: String?, pending: Boolean, branch: String?, busy: Boolean, onBack: () -> Unit, onConfirm: () -> Unit) {
 var stopped by remember(action) { mutableStateOf(false) }
 var paused by remember(action) { mutableStateOf(false) }
 val restoring = action in setOf(Action.RESTORE, Action.RESOLVE, Action.REMOTE_TIP)
 GuidedPage(
  title = if (restoring) "备份并恢复整个档案" else if (action == Action.ARCHIVE) "归档待上传快照" else "操作前确认",
  subtitle = "请核对操作范围。取消会返回主页，当前存档不会改变。",
  footer = {
   Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    TextButton(enabled = !busy, onClick = onBack) { Text("取消，返回主页") }
    Spacer(Modifier.weight(1f))
    Button(enabled = !busy && (action == Action.ARCHIVE || stopped && paused), onClick = onConfirm) { Text(if (restoring) "备份并恢复" else "确认继续") }
   }
  }
 ) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
   Text("默认目录：$root", color = muted, style = MaterialTheme.typography.caption)
   if (action == Action.ARCHIVE) Text("只移除待上传标记，历史保留，不删除云端内容。若此前恢复中断，请先核对当前档案与备份；归档也会解除中断恢复保护，下次同步将重新采集当前档案。")
   else {
    if (restoring) Text("先生成可恢复备份，再替换所有局与全局记录。快照中没有的局将移除。旧档可能在游戏加载时升级；原档备份保留。")
    if (action == Action.PUSH && pending) Text("将重试已冻结的快照，可能不同于当前目录的最新内容。")
    if (action == Action.RESOLVE) Text("将采用所选分支 ${branch?.take(8)}，并追加多父提交。全部原始快照继续保留。")
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(stopped, { stopped = it }, enabled = !busy); Text("我已完全退出游戏") }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(paused, { paused = it }, enabled = !busy); Text("Steam Cloud 及其他程序不会同时写入") }
    Text("这是人工确认。未知兼容性与降级将被阻止，断电及并发写入仍有风险。", color = muted, style = MaterialTheme.typography.caption)
   }
  }
 }
}
