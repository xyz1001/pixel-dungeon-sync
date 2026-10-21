package pdsync

import kotlin.test.*
import org.junit.jupiter.api.Test

class NoviceCopyTest {
 @Test fun unknownAndBlockedNeverClaimEmptyOrSynchronized() {
  for (kind in listOf(null, OnboardingKind.BLOCKED)) for (busy in listOf(false, true)) {
   val copy = NoviceCopy.comparison(kind, busy)
   assertFalse(copy.contains("没有存档")); assertFalse(copy.contains("相同"))
   assertFalse(copy.contains("已同步"))
  }
  assertContains(NoviceCopy.comparison(OnboardingKind.BOTH_EMPTY, false), "没有存档")
  assertContains(NoviceCopy.comparison(OnboardingKind.DIFFERENT, false), "不同")
  assertContains(NoviceCopy.comparison(OnboardingKind.BRANCHED, false), "多份")
 }
 @Test fun actionableErrorsStayVisibleWithoutTechnicalPaths() {
  assertContains(NoviceCopy.feedback("HTTP 401 at https://fixture.invalid", "失败"), "用户名")
  assertContains(NoviceCopy.feedback("版本不兼容", "失败"), "暂不能恢复")
  assertContains(NoviceCopy.feedback("游戏正在运行", "失败"), "退出游戏")
  assertEquals("检查失败", NoviceCopy.feedback("C:\\private\\save", "检查失败"))
 }
 @Test fun detailsStartCollapsedAndCanBeClosedAgain() {
  val initial = DetailsState()
  assertFalse(initial.expanded)
  assertTrue(initial.toggle().expanded)
  assertFalse(initial.toggle().toggle().expanded)
  assertFalse(DetailsState().expanded)
 }
 @Test fun restoreWarningExplicitlyDescribesReplacementAndRemoval() {
  assertContains(NoviceCopy.restoreWarning, "替换本机存档")
  assertContains(NoviceCopy.restoreWarning, "丢失")
  assertContains(NoviceCopy.restoreWarning, "不创建备份")
  assertFalse(NoviceCopy.restoreWarning.contains("先备份"))
 }
}
