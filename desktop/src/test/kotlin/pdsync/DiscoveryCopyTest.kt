package pdsync

import kotlin.test.*
import org.junit.jupiter.api.Test

class DiscoveryCopyTest {
 private val target = TargetGame("fixture.jar", "4.0.2", 922, "test-only", true, 850, "fixture")
 private fun result(count: Int = 0, incomplete: Boolean = false, trusted: Boolean = true) =
  InstallationDiscoveryResult(DefaultSaveDiscovery("fixture", true, null),
   List(count) { InstalledTargetCandidate("fixture-$it.jar", "fixture", target.copy(trusted = trusted), "fixture") },
   incomplete, emptyList())

 @Test fun trustedSelectionNeverShowsMissingOrSelectionInstructions() {
  for (scan in listOf(null, result(), result(1), result(2), result(1, incomplete = true))) {
   val message = DiscoveryCopy.game(scan, target)
   assertFalse(message.contains("未找到"))
   assertFalse(message.contains("请手动选择"))
  }
  assertEquals("", DiscoveryCopy.game(result(1, incomplete = true), target))
 }
 @Test fun incompleteScanIsNotReportedAsNoGameOrAutomaticSelection() {
  for (count in 0..2) {
   val message = DiscoveryCopy.game(result(count, incomplete = true), null)
   assertContains(message, "查找未完成")
   assertContains(message, "请手动选择")
   assertFalse(message.contains("未找到"))
  }
 }
 @Test fun completedScanDistinguishesMissingMultipleAndUntrustedCandidates() {
  assertContains(DiscoveryCopy.game(result(), null), "未找到游戏")
  assertContains(DiscoveryCopy.game(result(2), null), "请选择要使用的游戏")
  assertContains(DiscoveryCopy.game(result(1), null), "请确认游戏")
  assertContains(DiscoveryCopy.game(result(1, trusted = false), target.copy(trusted = false)), "无法识别游戏")
  assertContains(DiscoveryCopy.game(null, null), "正在查找游戏")
 }
 @Test fun existingDirectoryDoesNotClaimSaveContentsWereFound() {
  val message = DiscoveryCopy.directory(DefaultSaveDiscovery("fixture", true, null))
  assertContains(message, "目录已找到")
  assertContains(message, "内容待检查")

 }
 @Test fun absentAndUnsafeDirectoriesRemainDistinct() {
  for (reason in listOf("未找到默认存档目录。", "无法安全检查默认存档目录：拒绝符号链接")) {
   val message = DiscoveryCopy.directory(DefaultSaveDiscovery("fixture", false, reason, if (reason == "未找到默认存档目录。") DefaultSaveDiscoveryReason.MISSING else DefaultSaveDiscoveryReason.UNAVAILABLE))
   assertEquals(if (reason == "未找到默认存档目录。") "本机存档：目录尚未创建" else "本机存档：暂无法检查", message)
  }
 }
 @Test fun pendingDirectoryScanDoesNotClaimAbsence() {
  val message = DiscoveryCopy.directory(null)
  assertContains(message, "尚未检查")
  assertFalse(message.contains("未找到"))
 }
 @Test fun identifiedUnknownCompatibilityDoesNotClaimRestoreSupportOrOfficialAuthentication() {
  val message = DiscoveryCopy.game(result(1), target.copy(minimumSaveVersion = null))
  assertFalse(message.contains("未找到"))
  assertContains(message, "暂不支持恢复")
  assertFalse(message.contains("官方")); assertFalse(message.contains("可信"))
 }
}
