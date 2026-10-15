package pdsync

import io.github.xyz1001.pdsync.android.SharedGameIdentity
import io.github.xyz1001.pdsync.android.SharedSettingsActions
import io.github.xyz1001.pdsync.android.SharedSettingsKey
import io.github.xyz1001.pdsync.android.SharedSettingsRow
import io.github.xyz1001.pdsync.android.SharedSettingsState
import io.github.xyz1001.pdsync.android.actionEnabled
import io.github.xyz1001.pdsync.android.backEnabled

/** Settings are a projection of real host facts. Rendering never reads or writes configuration. */
internal data class DesktopSettingsContext(
 val config: AppConfig?,
 val game: SharedGameIdentity?,
 val savePath: String,
 val hasSession: Boolean,
 val connected: Boolean,
 val pendingUpload: Boolean,
 val currentName: String,
 val pageActive: Boolean,
 val busy: Boolean,
 val connectingAtStartup: Boolean,
 val appClosed: Boolean,
 val managementOpen: Boolean = false,
 val editorOpen: Boolean = false,
 val targetError: String? = null,
 val locationIssue: String? = null,
 val operationNotice: String? = null,
 val diagnosticExportNotice: String? = null,
)

internal enum class DesktopSettingsMaintenance { EDIT_DIRECTORY, ARCHIVE_PENDING }

internal fun DesktopSettingsContext.maintenanceEnabled(action: DesktopSettingsMaintenance): Boolean =
 pageActive && managementOpen && !editorOpen && !busy && !connectingAtStartup && !appClosed &&
  when (action) {
   DesktopSettingsMaintenance.EDIT_DIRECTORY -> hasSession
   DesktopSettingsMaintenance.ARCHIVE_PENDING -> hasSession && pendingUpload
  }

internal object DesktopSharedSettingsAdapter {
 fun state(current: DesktopSettingsContext): SharedSettingsState {
  val config = current.config
  val notice = notice(current)
  val rows = buildList {
   add(SharedSettingsRow(SharedSettingsKey.GAME, "游戏", current.game?.summary ?: "尚未选择游戏",
    "更换", config != null))
   add(SharedSettingsRow(SharedSettingsKey.SAVE_PATH, "存档位置", current.savePath.ifBlank { "尚未选择" },
    "修改", true))
   add(SharedSettingsRow(SharedSettingsKey.CLOUD_CONNECTION, "云端连接", if (config == null) "尚未配置" else
    listOf(if (config.connectionProvider == "official") "内置服务" else "自定义服务",
     config.username, if (current.connected) "已连接" else "未连接").filter(String::isNotBlank).joinToString(" · "),
    "修改", true))
   add(SharedSettingsRow(SharedSettingsKey.AUTOMATIC_UPLOAD, "自动上传", when {
    config == null -> "尚未配置"
    config.autoUploadPaused -> "已暂停"
    else -> "已开启"
   }, if (config?.autoUploadPaused == true) "开启" else "暂停", config != null))
   add(SharedSettingsRow(SharedSettingsKey.RECHECK_SAVES, "存档检查",
    if (current.hasSession) "已读取" else "尚未就绪", "重新检查", true))
   add(SharedSettingsRow(SharedSettingsKey.REVIEW_SETUP, "四步引导",
    if (config?.onboardingProgress == OnboardingProgress.COMPLETE) "已完成" else "尚未完成", "重新检查", true))
   if (current.hasSession || current.pendingUpload) {
    add(SharedSettingsRow(SharedSettingsKey.MAINTENANCE, "存档管理",
     if (current.pendingUpload) "有未完成的上传" else current.currentName.ifBlank { "名称与单局身份" },
     "管理", true))
   }
   add(SharedSettingsRow(SharedSettingsKey.EXPORT_LOGS, "日志", "", "导出", true))
  }
  return SharedSettingsState(current.game, rows,
   pageActive = current.pageActive && !current.appClosed && !current.managementOpen && !current.editorOpen,
   busy = current.busy || current.connectingAtStartup || current.appClosed,
   notice = notice?.first.orEmpty(), noticeError = notice?.second == true)
 }

 /** Only short, actionable results belong on the page. Raw diagnostics remain available on demand. */
 private fun notice(current: DesktopSettingsContext): Pair<String, Boolean>? = when {
  !current.diagnosticExportNotice.isNullOrBlank() ->
   if (current.diagnosticExportNotice.startsWith("日志已导出：")) "日志已导出。" to false
   else concise(current.diagnosticExportNotice, "日志导出失败，请重试。") to true
  current.operationNotice?.startsWith("设置未保存：") == true -> "设置未保存，请重试。" to true
  current.operationNotice?.startsWith("目标游戏未保存：") == true -> "游戏未更换，请重新选择。" to true
  current.operationNotice?.startsWith("无法更新存档信息：") == true ->
   concise(current.operationNotice, "存档信息未更新，请重试。") to true
  !current.targetError.isNullOrBlank() -> "游戏文件不可用，请重新选择。" to true
  !current.locationIssue.isNullOrBlank() -> "无法读取存档，请重新检查。" to true
  else -> null
 }

 private fun concise(raw: String, fallback: String): String {
  val translated = NoviceCopy.feedback(raw, fallback)
  return if (translated == raw && (raw.startsWith("无法更新存档信息：") ||
    raw.contains(Regex("https?://|[A-Za-z]:[\\\\/]|(^|\\s)/|Exception|Error|failed|denied", RegexOption.IGNORE_CASE)))) fallback
   else translated
 }
}

/** Each callback rechecks current capabilities and its original page lifetime before dispatching. */
internal class DesktopSharedSettingsActions(
 private val state: () -> SharedSettingsState,
 private val pageCurrent: () -> Boolean,
 private val performAction: (SharedSettingsKey) -> Unit,
 private val goBack: () -> Unit,
) : SharedSettingsActions {
 override fun perform(key: SharedSettingsKey) {
  if (pageCurrent() && state().actionEnabled(key)) performAction(key)
 }
 override fun back() {
  if (pageCurrent() && state().backEnabled) goBack()
 }
}

/** Opening management does not authorize a later action after the dialog or session changes. */
internal class DesktopSettingsMaintenanceActions(
 private val context: () -> DesktopSettingsContext,
 private val dialogCurrent: () -> Boolean,
 private val performAction: (DesktopSettingsMaintenance) -> Unit,
) {
 fun perform(action: DesktopSettingsMaintenance) {
  if (dialogCurrent() && context().maintenanceEnabled(action)) performAction(action)
 }
}
