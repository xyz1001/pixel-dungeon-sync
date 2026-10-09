package pdsync

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 Window(onCloseRequest = { if (busy) closeBlocked = true else exitApplication() }, title = "像素地牢 · 存档同步", state = rememberWindowState(width = 1120.dp, height = 800.dp)) {
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
  var connectingAtStartup by remember { mutableStateOf(true) }
  var connectionNotice by remember { mutableStateOf<String?>(null) }
  var settings by remember { mutableStateOf<SettingsMode?>(null) }
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
  var showConflict by remember { mutableStateOf(false) }
  var confirm by remember { mutableStateOf<Action?>(null) }
  var restoreIssue by remember { mutableStateOf<String?>("请选择快照") }
  var conflictRestoreIssue by remember { mutableStateOf<String?>("尚未校验") }
  var locationIssue by remember { mutableStateOf<String?>(pathResult.exceptionOrNull()?.message) }
  var status by remember { mutableStateOf("正在检查默认存档位置…") }
  val scope = rememberCoroutineScope()
  val secretStore = remember { SystemSecretStore.create() }
  val selection = history.firstOrNull { it.manifest.id == selected }

  fun loadProfile(value: AppConfig?) {
   if (busy) return
   busy = true
   scope.launch {
    try {
     val data = withContext(Dispatchers.IO) {
      location.requireDirectory()
      if (value == null) null else {
       val manager = location.open(value, workspace.resolve(".local/manager"))
       val target = runCatching { manager.targetInfo() }
       LoadedProfile(manager, manager.history(), manager.hasPending(), manager.currentVersions(), target.getOrNull(), target.exceptionOrNull()?.message)
      }
     }
     locationIssue = null; session = data?.session; history = data?.history ?: emptyList(); pending = data?.pending ?: false
     saveInfo = data?.saves; targetInfo = data?.target; targetError = data?.targetError
     selected = history.firstOrNull()?.manifest?.id; remoteTips = emptySet(); conflictSelection = null; conflict = null; showConflict = false
     status = if (value == null) "默认存档位置已找到。连接云端后即可管理快照。" else "存档与历史已刷新。操作前请退出游戏并暂停其他同步。"
    } catch (e: Exception) {
     session = null; history = emptyList(); saveInfo = null; targetInfo = null; pending = false
     locationIssue = e.message; status = e.message ?: "无法读取默认存档目录"
    } finally { busy = false }
   }
  }
  fun authenticationFailed(e: Exception) {
   if (e is WebDavException && e.statusCode in setOf(401, 403)) {
    connected = false; password = ""; settings = SettingsMode.UNLOCK
    status = "云端验证已失效，请重新输入密码。本地快照与待上传内容已保留。"
   }
  }
  fun execute(action: Action) {
   if (busy) return
   val manager = session ?: return
   val chosen = selected; val branchChoice = conflictSelection; val divergence = conflict; val account = config?.username ?: ""; val secret = password
   busy = true; confirm = null
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
     status = result.message; conflict = result.conflict; conflictSelection = null; showConflict = result.conflict != null
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
   if (busy || !connected) return
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
   try {
   loadProfile(config)
   val saved = config
   if (saved == null) {
    settings = SettingsMode.FULL
    initialResult.exceptionOrNull()?.let { status = "旧设置无法读取，原文件保留。请重新配置：${it.message}" }
   } else if (saved.rememberPassword) {
    // Only this exact endpoint/account's own credential is queried.
    val restored = withContext(Dispatchers.IO) { secretStore.read(saved.endpoint, saved.username) }
    if (restored is SecretReadResult.Found) {
     val checker = ConnectionSetup(saved); checker.update(saved, restored.password)
     val checked = withContext(Dispatchers.IO) { checker.testConnection() }
     if (checked is ConnectionTestResult.Success) { password = restored.password; connected = true }
     else { settings = SettingsMode.UNLOCK; status = "自动连接未成功，请重新验证密码。" }
     checker.cancel()
    } else { settings = SettingsMode.UNLOCK; connectionNotice = if (restored is SecretReadResult.Unavailable) restored.message else "未找到已保存的密码，请重新输入。" }
   } else settings = SettingsMode.UNLOCK
   } finally { connectingAtStartup = false }
  }
  LaunchedEffect(session, selected, busy) {
   restoreIssue = "正在校验实际版本…"
   val manager = session; val id = selected
   if (manager != null && id != null && !busy) restoreIssue = withContext(Dispatchers.IO) { runCatching { manager.checkRestore(id) }.exceptionOrNull()?.message }
  }
  LaunchedEffect(session, conflict, conflictSelection, busy) {
   conflictRestoreIssue = "正在校验实际版本…"
   val manager = session; val value = conflict
   if (manager != null && value != null && conflictSelection != null && !busy) conflictRestoreIssue = withContext(Dispatchers.IO) { runCatching { manager.checkResolution(requireNotNull(conflictSelection)) }.exceptionOrNull()?.message }
  }
  MaterialTheme(colors = lightColors(primary = green, secondary = Color(0xFF8A6B42)), shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp))) {
   Surface(Modifier.fillMaxSize(), color = Color(0xFFF3F5F1)) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
     Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
       Text("像素地牢", style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
       Text("存档同步  /  SHATTERED", color = muted, style = MaterialTheme.typography.caption)
      }
      Text(if (connected) "● 云端已连接" else "○ 云端未连接", color = if (connected) green else muted, style = MaterialTheme.typography.body2)
      Spacer(Modifier.width(18.dp))
      OutlinedButton(enabled = !busy && !connectingAtStartup, onClick = { settings = SettingsMode.FULL }) { Text("设置") }
     }
     Surface(color = Color(0xFFFFECCD)) {
      Text("实验版本 · 操作前退出游戏、暂停 Steam Cloud 及其他同步。尚无可靠进程检测或断电保护，请另留一份备份。", Modifier.padding(12.dp), style = MaterialTheme.typography.caption, color = Color(0xFF75572C))
     }
     Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
      Column(Modifier.width(340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
       Card(elevation = 0.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
         Text("当前存档", style = MaterialTheme.typography.h6)
         Text(if (session != null) saveInfo?.summary() ?: "版本未知" else if (locationIssue != null) "暂未找到可用存档" else "等待连接设置", color = if (locationIssue != null) Color(0xFF986337) else green)
         Text("默认位置 · 由游戏与系统决定", color = muted, style = MaterialTheme.typography.caption)
         SelectionContainer { Text(pathText, style = MaterialTheme.typography.caption) }
         locationIssue?.let { Text(it, color = Color(0xFF986337), style = MaterialTheme.typography.body2) }
         if (session != null) Text("${saveInfo?.runs?.size ?: 0} 个进行中的局 · 全局记录一同保存", style = MaterialTheme.typography.body2)
         Text(when { targetError != null -> "目标游戏暂不可用，请到设置检查。"; targetInfo == null -> "尚未选择目标游戏。备份可用，恢复需在设置中选择 JAR。"; else -> "恢复目标：Shattered ${targetInfo!!.displayVersion}" }, color = muted, style = MaterialTheme.typography.caption)
         TextButton(enabled = !busy && !connectingAtStartup, onClick = { loadProfile(config) }, contentPadding = PaddingValues(0.dp)) { Text("重新检查存档") }
        }
       }
       Card(elevation = 0.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
         Text("同步与备份", style = MaterialTheme.typography.h6)
         Button(enabled = !busy && !connectingAtStartup && session != null, onClick = { if (!connected) settings = if (config == null) SettingsMode.FULL else SettingsMode.UNLOCK else confirm = Action.PUSH }, modifier = Modifier.fillMaxWidth()) {
          Text(if (!connected) "连接云端" else if (pending) "重试待上传快照" else "同步当前存档")
         }
         if (config == null) OutlinedButton(enabled = !busy && !connectingAtStartup, onClick = { settings = SettingsMode.FULL }, modifier = Modifier.fillMaxWidth()) { Text("配置云端连接") }
         OutlinedButton(enabled = session != null && !busy && !connectingAtStartup, onClick = { confirm = Action.CAPTURE }, modifier = Modifier.fillMaxWidth()) { Text("创建本地快照") }
         TextButton(enabled = session != null && connected && !busy && !connectingAtStartup, onClick = { readRemote() }, modifier = Modifier.fillMaxWidth()) { Text("查看云端已观察分支") }
         if (pending) {
          Text("待上传的是上次冻结的快照。重试会继续原内容。", color = Color(0xFF986337), style = MaterialTheme.typography.caption)
          TextButton(enabled = !busy && !connectingAtStartup, onClick = { confirm = Action.ARCHIVE }) { Text("归档待上传，稍后重新采集") }
         }
         if (conflict != null) OutlinedButton(enabled = !busy && !connectingAtStartup, onClick = { showConflict = true }) { Text("查看存档冲突") }
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
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
         Button(enabled = session != null && !busy && !connectingAtStartup && !pending && restoreIssue == null, onClick = { confirm = Action.RESTORE }) { Text("备份后恢复所选快照") }
         if (selected in remoteTips && remoteTips.size == 1) TextButton(enabled = !busy && !connectingAtStartup && connected && !pending && restoreIssue == null, onClick = { confirm = Action.REMOTE_TIP }) { Text("恢复此分支并设为同步基线") }
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
   settings?.let { mode ->
    ConnectionDialog(mode, configFile, config, password, secretStore, window, busy || connectingAtStartup,
     onBusy = { busy = it }, onDismiss = { settings = null }, onFullSettings = { settings = SettingsMode.FULL },
     onApplied = { applied, note ->
      config = applied.config; password = applied.password; connected = true; settings = null
      connectionNotice = note; loadProfile(applied.config)
     })
   }
   if (showConflict && conflict != null) {
    val value = conflict!!
    AlertDialog(onDismissRequest = { if (!busy && !connectingAtStartup) showConflict = false }, title = { Text("${value.branches.size} 个分支均已保留") }, text = {
     Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("选择整份快照。会创建引用本次全部分支的新解决提交；未观察到的后来分支仍会显示冲突，不按时间自动选胜者。")
      value.branches.forEach { branch ->
       val m = branch.snapshot.manifest
       Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { conflictSelection = m.id }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = conflictSelection == m.id, onClick = { conflictSelection = m.id }, enabled = !busy)
        Column {
         Text("分支 ${branch.commit.id.take(8)} · ${m.files.size} 个文件")
         if (m.files == value.observedLocalFiles) Text("与当前本地进度一致", color = green, style = MaterialTheme.typography.caption)
         if (branch.commit.id == value.publishedLocalCommit) Text("本机最近发布的分支", color = muted, style = MaterialTheme.typography.caption)
         if (m.files != value.observedLocalFiles && branch.commit.id != value.publishedLocalCommit) Text("其他已观察分支 · 内容校验 ${m.sha256.take(12)}", color = muted, style = MaterialTheme.typography.caption)
         Text(m.saveVersions?.summary() ?: m.version, style = MaterialTheme.typography.caption)
        }
       }
      }
      conflictRestoreIssue?.let { Text("选择后校验：$it", style = MaterialTheme.typography.caption) }
      Text("需要替换当前档案时会先备份，再恢复所选快照。所有原始分支保留，不合并二进制。")
     }
    }, buttons = {
     Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(enabled = !busy && !connectingAtStartup && connected && conflictSelection != null && conflictRestoreIssue == null, onClick = { showConflict = false; confirm = Action.RESOLVE }) { Text("采用所选快照") }
      TextButton(enabled = !busy && !connectingAtStartup, onClick = { showConflict = false }) { Text("稍后处理") }
     }
    })
   }
   if (closeBlocked) AlertDialog(onDismissRequest = { closeBlocked = false }, title = { Text("请等待当前操作结束") }, text = { Text("操作中暂不关闭，以免中断备份、恢复或连接验证。") }, confirmButton = { TextButton(onClick = { closeBlocked = false }) { Text("继续等待") } })
   confirm?.let { action ->
    var stopped by remember(action) { mutableStateOf(false) }; var paused by remember(action) { mutableStateOf(false) }
    val restoring = action in setOf(Action.RESTORE, Action.RESOLVE, Action.REMOTE_TIP)
    AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (restoring) "备份并恢复整个档案" else if (action == Action.ARCHIVE) "归档待上传快照？" else "操作前确认") }, text = {
     Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("默认目录：${session?.root}", style = MaterialTheme.typography.caption)
      if (action == Action.ARCHIVE) Text("只移除待上传标记，历史保留，不删除云端内容。若此前恢复中断，请先核对当前档案与备份；归档也会解除中断恢复保护，下次同步将重新采集当前档案。")
      else {
       if (restoring) Text("先生成可恢复备份，再替换所有局与全局记录。快照中没有的局将移除。旧档可能在游戏加载时升级；原档备份保留。")
       if (action == Action.PUSH && pending) Text("将重试已冻结的快照，可能不同于当前目录的最新内容。")
       if (action == Action.RESOLVE) Text("将采用所选分支 ${conflictSelection?.take(8)}，并追加多父提交。全部原始快照继续保留。")
       Row { Checkbox(stopped, { stopped = it }); Text("我已完全退出游戏", Modifier.padding(top = 12.dp)) }
       Row { Checkbox(paused, { paused = it }); Text("Steam Cloud 及其他程序不会同时写入", Modifier.padding(top = 12.dp)) }
       Text("这是人工确认。未知兼容性与降级将被阻止，断电及并发写入仍有风险。", style = MaterialTheme.typography.caption)
      }
     }
    }, confirmButton = { Button(enabled = !busy && !connectingAtStartup && (action == Action.ARCHIVE || stopped && paused), onClick = { execute(action) }) { Text(if (restoring) "备份并恢复" else "确认继续") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
   }
  }
 }
}

@Composable
private fun ConnectionDialog(mode: SettingsMode, configFile: ConfigFile, initial: AppConfig?, currentPassword: String, secretStore: SecureCredentialStore, parent: java.awt.Component, busy: Boolean, onBusy: (Boolean) -> Unit, onDismiss: () -> Unit, onFullSettings: () -> Unit, onApplied: (AppliedConnection, String?) -> Unit) {
 val controller = remember(mode, initial) { ConnectionSetup(initial) }
 var draft by remember(mode, initial) { mutableStateOf(initial ?: AppConfig(endpoint = "", profile = "default")) }
 var secret by remember(mode, initial) { mutableStateOf(currentPassword) }
 var step by remember(mode, initial) { mutableStateOf(0) }
 var tested by remember(mode, initial) { mutableStateOf(false) }
 var message by remember(mode, initial) { mutableStateOf<String?>(null) }
 var testFailed by remember(mode, initial) { mutableStateOf(false) }
 val scope = rememberCoroutineScope()
 fun edit(value: AppConfig = draft, password: String = secret) { secret = ConnectionDraft(draft, password).editingConfig(value).password; draft = value; tested = false; message = null; testFailed = false }
 fun cancel() { if (!busy) { controller.cancel(); secret = ""; onDismiss() } }
 DialogWindow(onCloseRequest = { cancel() }, title = if (mode == SettingsMode.UNLOCK) "连接云端" else "连接设置", state = rememberDialogState(width = 550.dp, height = if (mode == SettingsMode.UNLOCK) 490.dp else 660.dp), resizable = false) {
  Surface(Modifier.fillMaxSize(), color = Color.White) {
   Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(if (mode == SettingsMode.UNLOCK) "欢迎回来" else if (initial == null) "让存档跟着你" else "连接设置", style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
    Text(if (mode == SettingsMode.UNLOCK) "输入密码即可连接已保存的云端。" else if (step == 0) "1 / 2   连接你的 WebDAV" else "2 / 2   确认游戏与连接", color = muted)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
     if (mode == SettingsMode.UNLOCK) {
      Text(draft.endpoint, style = MaterialTheme.typography.body2)
      Text("账号：${draft.username.ifBlank { "未设置用户名" }}", color = muted, style = MaterialTheme.typography.body2)
     } else if (step == 0) {
      OutlinedTextField(draft.endpoint, { edit(draft.copy(endpoint = it)) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("WebDAV 地址") }, placeholder = { Text("https://example.com/dav") }, singleLine = true)
      OutlinedTextField(draft.username, { edit(draft.copy(username = it)) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("用户名") }, singleLine = true)
     }
     if (mode == SettingsMode.UNLOCK || step == 0) {
      OutlinedTextField(secret, { edit(password = it) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
      Row(verticalAlignment = Alignment.CenterVertically) {
       Checkbox(draft.rememberPassword, { edit(draft.copy(rememberPassword = it)) }, enabled = !busy)
       Text("使用系统安全存储保存密码", style = MaterialTheme.typography.body2)
      }
      Text("只保存此地址与账号的密码。安全存储不可用时，仅在本次会话使用。取消不会保存更改。", color = muted, style = MaterialTheme.typography.caption)
      if (initial?.rememberPassword == true) Text("取消勾选并保存，将忘记此连接已保存的密码。", color = muted, style = MaterialTheme.typography.caption)
      if (mode == SettingsMode.FULL) {
       OutlinedTextField(draft.profile, { edit(draft.copy(profile = it)) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("远端档案名") }, singleLine = true)
       Text("服务器需预先创建 shattered/${draft.profile}/。测试读取完整 PROPFIND 目录列表，不上传存档。", color = muted, style = MaterialTheme.typography.caption)
      }
      OutlinedButton(enabled = !busy, onClick = {
       onBusy(true); message = "正在验证连接…"; testFailed = false
       scope.launch {
        try {
         controller.update(draft, secret)
         when (val result = withContext(Dispatchers.IO) { controller.testConnection() }) {
          is ConnectionTestResult.Success -> { tested = true; message = if (result.hasRemote) "连接成功，完整目录列表中发现新版提交。" else "完整目录列表读取成功，此次未观察到新版提交；写入权限会在同步时验证。" }
          is ConnectionTestResult.Failure -> { tested = false; testFailed = true; message = result.message }
         }
        } catch (e: Exception) { tested = false; testFailed = true; message = e.message ?: "连接未成功" }
        finally { onBusy(false) }
       }
      }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在连接…" else "测试连接") }
     } else {
      Text("Shattered 的存档位置由系统自动确定，无需选择文件夹。", style = MaterialTheme.typography.body2)
      OutlinedTextField(draft.targetGame, { edit(draft.copy(targetGame = it)); step = 1 }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("目标游戏 JAR（恢复时需要）") }, maxLines = 2)
      OutlinedButton(enabled = !busy, onClick = {
       onBusy(true)
       scope.launch {
        val chosen = chooseGame(parent, draft.targetGame)
        if (chosen != null) { draft = draft.copy(targetGame = chosen); controller.update(draft, secret); tested = false; message = "保存前会重新验证连接。" }
        onBusy(false)
       }
      }) { Text("选择游戏 JAR…") }
      Text("只读取游戏版本，不运行文件。暂时跳过也能备份与上传；恢复前可在设置中补充。", color = muted, style = MaterialTheme.typography.caption)
      Divider()
      Text("连接：${draft.endpoint}\n账号：${draft.username}\n档案：${draft.profile}", style = MaterialTheme.typography.body2)
      Text("旧版手选目录与历史不会被删除，也不会混入默认位置。", color = muted, style = MaterialTheme.typography.caption)
     }
     message?.let { Text(it, color = if (testFailed) Color(0xFFAD4634) else green, style = MaterialTheme.typography.body2) }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
     TextButton(enabled = !busy, onClick = { cancel() }) { Text("取消") }
     if (mode == SettingsMode.UNLOCK) TextButton(enabled = !busy, onClick = { controller.cancel(); secret = ""; onFullSettings() }) { Text("修改设置") }
     else if (step == 1) TextButton(enabled = !busy, onClick = { step = 0 }) { Text("上一步") }
     Spacer(Modifier.weight(1f))
     if (mode == SettingsMode.FULL && step == 0) Button(enabled = !busy && tested, onClick = { step = 1 }) { Text("下一步") }
     else Button(enabled = !busy && (tested || mode == SettingsMode.FULL && step == 1), onClick = {
      onBusy(true)
      scope.launch {
       var secretChanged = false
       try {
        var note: String? = null
        val result = withContext(Dispatchers.IO) {
         if (!tested) {
          controller.update(draft, secret)
          val rechecked = controller.testConnection()
          require(rechecked is ConnectionTestResult.Success) { (rechecked as ConnectionTestResult.Failure).message }
         }
         var stored = false
         if (draft.rememberPassword) {
          val written = secretStore.save(draft.endpoint, draft.username, secret)
          stored = written is SecretWriteResult.Success
          secretChanged = stored
          if (!stored) note = "安全存储不可用，密码仅用于本次会话。${(written as SecretWriteResult.Unavailable).message}"
         } else if (initial?.rememberPassword == true && initial.validated().endpoint == draft.validated().endpoint && initial.username == draft.username) {
          val forgotten = secretStore.delete(initial.endpoint, initial.username)
          require(forgotten is SecretWriteResult.Success) { "无法忘记已保存密码：${(forgotten as SecretWriteResult.Unavailable).message}。设置未保存。" }
          secretChanged = true
         }
         controller.apply(configFile, stored)
        }
        secret = ""; onBusy(false); onApplied(result, note)
       } catch (e: Exception) { message = "设置未保存：${e.message}" + if (secretChanged) " 系统安全存储中的密码已更新；其他设置尚未保存，可重试。" else ""; testFailed = true; onBusy(false) }
      }
     }) { Text(if (mode == SettingsMode.UNLOCK) "连接" else "保存并开始") }
    }
   }
  }
 }
}
