package pdsync

import org.junit.jupiter.api.Test
import kotlin.test.*

class EmptySetupCompletionTest {
 @Test fun verifiedEmptyScreenContainsOnlyCompletionCopy() {
  assertTrue(EmptySetupCompletion.isEmpty(OnboardingKind.BOTH_EMPTY, null))
  assertEquals("配置完成", EmptySetupCompletion.message)
  assertTrue(EmptySetupCompletion.canFinish(OnboardingKind.BOTH_EMPTY, null, true, false, true))
 }
 @Test fun identicalDifferentAndUnknownNeverBecomeEmptyCompletion() {
  for (kind in OnboardingKind.entries.filter { it != OnboardingKind.BOTH_EMPTY } + listOf(null)) {
   assertFalse(EmptySetupCompletion.isEmpty(kind, null))
   assertFalse(EmptySetupCompletion.canFinish(kind, null, true, false, true))
  }
  assertFalse(EmptySetupCompletion.isEmpty(OnboardingKind.BOTH_EMPTY, "检查失败"))
 }
 @Test fun backLeavesWithoutCompletingAndBusyPreventsNavigation() {
  assertTrue(EmptySetupCompletion.canNavigate(true, false))
  assertFalse(EmptySetupCompletion.canNavigate(true, true))
  // Once Back changes the page, a queued Finish click cannot initialize anything.
  assertFalse(EmptySetupCompletion.canFinish(OnboardingKind.BOTH_EMPTY, null, false, false, true))
 }
 @Test fun repeatedFinishIsBlockedDuringSaveAndAfterLeavingPage() {
  assertFalse(EmptySetupCompletion.canFinish(OnboardingKind.BOTH_EMPTY, null, true, true, true))
  assertFalse(EmptySetupCompletion.canFinish(OnboardingKind.BOTH_EMPTY, null, false, false, true))
  assertFalse(EmptySetupCompletion.canFinish(OnboardingKind.BOTH_EMPTY, null, true, false, false))
 }
}
