package pdsync

import io.github.xyz1001.pdsync.android.SharedGameIdentity
import io.github.xyz1001.pdsync.android.SharedSettingsKey
import io.github.xyz1001.pdsync.android.actionEnabled
import io.github.xyz1001.pdsync.android.backEnabled
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopSharedSettingsAdapterTest {
 private fun ready() = DesktopSettingsContext(
  config = AppConfig(username = "实际账号", targetGame = "/actual/game.jar", profile = "real-profile",
   endpoint = "https://actual.invalid/dav", password = "never-display-this",
   selectedSaveRoot = "/actual/save/path", onboardingProgress = OnboardingProgress.COMPLETE),
  game = SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"), savePath = "/actual/save/path",
  hasSession = true, connected = true, pendingUpload = false, currentName = "实际存档名称",
  pageActive = true, busy = false, connectingAtStartup = false, appClosed = false,
 )
 private fun state(context: DesktopSettingsContext = ready()) = DesktopSharedSettingsAdapter.state(context)

 @Test fun rowsUseRealValuesAndAdvertiseOnlyExistingDesktopCapabilities() {
  val current = ready()
  val result = state(current)
  val rows = result.rows.associateBy { it.key }
  assertEquals(setOf(SharedSettingsKey.GAME, SharedSettingsKey.SAVE_PATH, SharedSettingsKey.CLOUD_CONNECTION,
   SharedSettingsKey.AUTOMATIC_UPLOAD, SharedSettingsKey.RECHECK_SAVES, SharedSettingsKey.REVIEW_SETUP,
   SharedSettingsKey.MAINTENANCE, SharedSettingsKey.EXPORT_LOGS), rows.keys)
  assertEquals(current.game, result.game)
  assertEquals("Shattered Pixel Dungeon · 3.3.8", rows.getValue(SharedSettingsKey.GAME).currentValue)
  assertEquals(current.savePath, rows.getValue(SharedSettingsKey.SAVE_PATH).currentValue)
  assertEquals("自定义服务 · 实际账号 · 已连接", rows.getValue(SharedSettingsKey.CLOUD_CONNECTION).currentValue)
  assertEquals("实际存档名称", rows.getValue(SharedSettingsKey.MAINTENANCE).currentValue)
  assertEquals("已完成", rows.getValue(SharedSettingsKey.REVIEW_SETUP).currentValue)
  assertTrue(result.notice.isEmpty())
  val visible = rows.values.joinToString { "${it.title} ${it.currentValue}" } + result.notice
  assertFalse(visible.contains("never-display-this"))
  assertFalse(visible.contains("https://actual.invalid"))
 }

 @Test fun absentConfigurationDoesNotInventASelectedGameAccountOrEnabledAutomaticUpload() {
  val result = state(ready().copy(config = null, game = null, savePath = "", hasSession = false,
   connected = false, currentName = ""))
  assertNull(result.game)
  assertEquals("尚未选择游戏", result.rows.single { it.key == SharedSettingsKey.GAME }.currentValue)
  assertEquals("尚未配置", result.rows.single { it.key == SharedSettingsKey.AUTOMATIC_UPLOAD }.currentValue)
  assertEquals("尚未配置", result.rows.single { it.key == SharedSettingsKey.CLOUD_CONNECTION }.currentValue)
  assertFalse(result.actionEnabled(SharedSettingsKey.GAME))
  assertFalse(result.actionEnabled(SharedSettingsKey.AUTOMATIC_UPLOAD))
  assertFalse(result.actionEnabled(SharedSettingsKey.MAINTENANCE))
  assertTrue(result.actionEnabled(SharedSettingsKey.EXPORT_LOGS))
  assertTrue(result.actionEnabled(SharedSettingsKey.CLOUD_CONNECTION))
  assertTrue(result.actionEnabled(SharedSettingsKey.SAVE_PATH))
 }

 @Test fun automaticUploadReflectsSavedPauseRatherThanConnectionOrProcessStatus() {
  val original = ready()
  for (paused in listOf(false, true)) for (connected in listOf(false, true)) {
   val current = original.copy(config = original.config!!.copy(autoUploadPaused = paused), connected = connected)
   val row = state(current).rows.single { it.key == SharedSettingsKey.AUTOMATIC_UPLOAD }
   assertEquals(if (paused) "已暂停" else "已开启", row.currentValue)
   assertEquals(if (paused) "开启" else "暂停", row.actionLabel)
   assertTrue(row.actionEnabled)
  }
  assertFalse(original.config!!.autoUploadPaused)
 }

 @Test fun officialAccountAndDisconnectedStateAreShownWithoutInventingEntitlement() {
  val original = ready()
  val current = original.copy(config = original.config!!.copy(connectionProvider = "official"), connected = false)
  val result = state(current)
  assertEquals("官方服务 · 实际账号 · 未连接", result.rows.single { it.key == SharedSettingsKey.CLOUD_CONNECTION }.currentValue)
  assertFalse(result.rows.any { it.key == SharedSettingsKey.ACCOUNT_APPRECIATION })
  assertTrue(result.actionEnabled(SharedSettingsKey.CLOUD_CONNECTION))
 }

 @Test fun pendingUploadUsesOneManagementEntryAndNeverExpandsDestructiveActionsOnThePage() {
  val result = state(ready().copy(pendingUpload = true))
  val row = result.rows.single { it.key == SharedSettingsKey.MAINTENANCE }
  assertEquals("有未完成的上传", row.currentValue)
  assertEquals("管理", row.actionLabel)
  assertFalse(result.rows.any { it.title.contains("归档") || it.actionLabel?.contains("归档") == true })
  assertTrue(result.actionEnabled(SharedSettingsKey.MAINTENANCE))
 }

 @Test fun busyStartupClosedInactiveAndCoveredPagesBlockEveryEntryAndReturn() {
  val original = ready()
  for (current in listOf(original.copy(busy = true), original.copy(connectingAtStartup = true),
   original.copy(appClosed = true), original.copy(pageActive = false),
   original.copy(managementOpen = true), original.copy(editorOpen = true))) {
   val result = state(current)
   SharedSettingsKey.entries.forEach { assertFalse(result.actionEnabled(it), "$it: $current") }
   assertFalse(result.backEnabled)
  }
 }

 @Test fun retainedCallbacksReadCurrentCapabilitiesAndRejectUnsupportedAndRemovedEntries() {
  var current = ready()
  val calls = mutableListOf<SharedSettingsKey>()
  var backs = 0
  val actions = DesktopSharedSettingsActions({ state(current) }, { true }, { calls += it }, { backs++ })
  actions.perform(SharedSettingsKey.LINK_PROFILE)
  actions.perform(SharedSettingsKey.ACCOUNT_APPRECIATION)
  actions.perform(SharedSettingsKey.SYNC_NOTIFICATIONS)
  assertTrue(calls.isEmpty())
  current = current.copy(config = null, hasSession = false)
  actions.perform(SharedSettingsKey.GAME)
  actions.perform(SharedSettingsKey.AUTOMATIC_UPLOAD)
  actions.perform(SharedSettingsKey.MAINTENANCE)
  assertTrue(calls.isEmpty())
  actions.perform(SharedSettingsKey.EXPORT_LOGS)
  assertEquals(listOf(SharedSettingsKey.EXPORT_LOGS), calls)
  actions.back(); assertEquals(1, backs)
  current = current.copy(busy = true)
  actions.perform(SharedSettingsKey.EXPORT_LOGS); actions.back()
  assertEquals(1, calls.size); assertEquals(1, backs)
 }

 @Test fun duplicateClicksStopWhenHostReservesWorkAndOldPageCallbacksCannotRevive() {
  var current = ready()
  var pageGeneration = 4L
  val originalGeneration = pageGeneration
  var calls = 0
  val actions = DesktopSharedSettingsActions({ state(current) }, { pageGeneration == originalGeneration },
   { calls++; current = current.copy(busy = true) }, { calls++ })
  repeat(2) { actions.perform(SharedSettingsKey.AUTOMATIC_UPLOAD) }
  assertEquals(1, calls)
  current = current.copy(busy = false)
  pageGeneration++ // Leave and reopen settings, which otherwise has the same capabilities.
  actions.perform(SharedSettingsKey.EXPORT_LOGS); actions.back()
  assertEquals(1, calls)
 }

 @Test fun maintenanceRequiresCurrentDialogSessionAndPendingStateBeforeDispatch() {
  var current = ready().copy(managementOpen = true, pendingUpload = true)
  var dialogGeneration = 1
  val originalGeneration = dialogGeneration
  val calls = mutableListOf<DesktopSettingsMaintenance>()
  val actions = DesktopSettingsMaintenanceActions({ current }, { dialogGeneration == originalGeneration }) { calls += it }
  actions.perform(DesktopSettingsMaintenance.EDIT_DIRECTORY)
  actions.perform(DesktopSettingsMaintenance.ARCHIVE_PENDING)
  assertEquals(DesktopSettingsMaintenance.entries.toList(), calls)
  calls.clear()
  for (unavailable in listOf(current.copy(hasSession = false), current.copy(pageActive = false),
   current.copy(appClosed = true), current.copy(busy = true), current.copy(connectingAtStartup = true),
   current.copy(managementOpen = false), current.copy(editorOpen = true))) {
   current = unavailable
   DesktopSettingsMaintenance.entries.forEach(actions::perform)
   assertTrue(calls.isEmpty())
  }
  current = ready().copy(managementOpen = true, pendingUpload = false)
  actions.perform(DesktopSettingsMaintenance.ARCHIVE_PENDING)
  assertTrue(calls.isEmpty())
  actions.perform(DesktopSettingsMaintenance.EDIT_DIRECTORY)
  assertEquals(listOf(DesktopSettingsMaintenance.EDIT_DIRECTORY), calls)
  calls.clear()
  current = current.copy(pendingUpload = true)
  dialogGeneration++ // An old dialog callback cannot authorize the newly opened dialog.
  DesktopSettingsMaintenance.entries.forEach(actions::perform)
  assertTrue(calls.isEmpty())
 }

 @Test fun noticesAreActionableAndTechnicalDetailsDoNotBecomePermanentPanels() {
  val raw = "java.io.IOException: https://private.invalid/dav/path C:\\private\\save"
  val failures = listOf(ready().copy(targetError = raw), ready().copy(locationIssue = raw),
   ready().copy(operationNotice = "设置未保存：$raw"),
   ready().copy(operationNotice = "目标游戏未保存：$raw"),
   ready().copy(operationNotice = "无法更新存档信息：$raw"), ready().copy(diagnosticExportNotice = raw))
  for (current in failures) {
   val result = state(current)
   assertTrue(result.noticeError)
   assertTrue(result.notice.isNotBlank())
   assertTrue(result.notice.length <= 48)
   assertFalse(result.notice.contains("private"))
   assertFalse(result.notice.contains("IOException"))
  }
  val exported = state(ready().copy(diagnosticExportNotice = "日志已导出：actual-diagnostic-file.zip"))
  assertEquals("日志已导出。", exported.notice)
  assertFalse(exported.noticeError)
  assertTrue(state(ready().copy(operationNotice = "正在建立本地观察基线…")).notice.isEmpty())
 }

 @Test fun longRealNamesAndPathsAreKeptIntactAndRepeatedRenderingDoesNotMutateConfiguration() {
  val original = ready().copy(savePath = "/actual/" + "long-directory/".repeat(40),
   currentName = "真实存档名称".repeat(30), game = SharedGameIdentity("实际游戏名称".repeat(30), "3.3.8"))
  val expected = state(original)
  repeat(20) { assertEquals(expected, state(original)) }
  assertEquals(original.savePath, expected.rows.single { it.key == SharedSettingsKey.SAVE_PATH }.currentValue)
  assertEquals(original.currentName, expected.rows.single { it.key == SharedSettingsKey.MAINTENANCE }.currentValue)
  assertEquals(original.game?.summary, expected.rows.single { it.key == SharedSettingsKey.GAME }.currentValue)
  assertEquals("never-display-this", original.config?.password)
  assertEquals(OnboardingProgress.COMPLETE, original.config?.onboardingProgress)
 }
}
