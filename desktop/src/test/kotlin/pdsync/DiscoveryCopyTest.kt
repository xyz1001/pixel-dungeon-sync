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
   assertContains(message, "已识别游戏")
   assertFalse(message.contains("未找到"))
   assertFalse(message.contains("请手动选择"))
  }
  assertContains(DiscoveryCopy.game(result(1, incomplete = true), target), "扫描未完成")
 }
 @Test fun incompleteScanIsNotReportedAsNoGameOrAutomaticSelection() {
  for (count in 0..2) {
   val message = DiscoveryCopy.game(result(count, incomplete = true), null)
   assertContains(message, "扫描未完成")
   assertContains(message, "不能自动确认")
   assertFalse(message.contains("未找到"))
  }
 }
 @Test fun completedScanDistinguishesMissingMultipleAndUntrustedCandidates() {
  assertContains(DiscoveryCopy.game(result(), null), "在已检查的位置未找到")
  assertContains(DiscoveryCopy.game(result(2), null), "多个游戏")
  assertContains(DiscoveryCopy.game(result(1), null), "找到游戏候选")
  assertContains(DiscoveryCopy.game(result(1, trusted = false), target.copy(trusted = false)), "无法识别游戏或版本")
  assertContains(DiscoveryCopy.game(null, null), "尚未完成游戏检查")
 }
 @Test fun existingDirectoryDoesNotClaimSaveContentsWereFound() {
  val message = DiscoveryCopy.directory(DefaultSaveDiscovery("fixture", true, null))
  assertContains(message, "目录存在")
  assertContains(message, "尚未检查是否有存档")
  assertContains(message, "第 3 步")
 }
 @Test fun absentAndUnsafeDirectoriesRetainTheirActualReason() {
  for (reason in listOf("未找到默认存档目录。", "无法安全检查默认存档目录：拒绝符号链接")) {
   val message = DiscoveryCopy.directory(DefaultSaveDiscovery("fixture", false, reason))
   assertContains(message, reason)
   assertContains(message, "路径安全检查通过后")
   assertContains(message, "不创建游戏存档或上传内容")
   assertContains(message, "第 3 步")
  }
 }
 @Test fun pendingDirectoryScanDoesNotClaimAbsence() {
  val message = DiscoveryCopy.directory(null)
  assertContains(message, "检查尚未完成")
  assertFalse(message.contains("未找到"))
 }
 @Test fun identifiedUnknownCompatibilityDoesNotClaimRestoreSupportOrOfficialAuthentication() {
  val message = DiscoveryCopy.game(result(1), target.copy(minimumSaveVersion = null))
  assertContains(message, "已识别游戏")
  assertContains(message, "暂不支持恢复")
  assertFalse(message.contains("官方")); assertFalse(message.contains("可信"))
 }
}
