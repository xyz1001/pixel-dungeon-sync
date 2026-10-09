package pdsync

import kotlin.test.*
import org.junit.jupiter.api.Test

class OnboardingGateTest {
 @Test fun newDeferredAndLegacyNeverReleaseAutomatic() {
  for (progress in listOf(null, OnboardingProgress.LEGACY, OnboardingProgress.PENDING, OnboardingProgress.DEFERRED)) {
   for (wizard in listOf(false, true)) for (cloud in listOf(false, true))
    assertFalse(OnboardingGate.allowsAutomatic(progress, wizard, cloud))
  }
 }
 @Test fun completingDoesNotBypassRemoteInspectionOrReentry() {
  assertFalse(OnboardingGate.allowsAutomatic(OnboardingProgress.COMPLETE, false, false))
  assertFalse(OnboardingGate.allowsAutomatic(OnboardingProgress.COMPLETE, true, true))
  assertTrue(OnboardingGate.allowsAutomatic(OnboardingProgress.COMPLETE, false, true))
 }
 @Test fun legacyRequiresTrustedTargetAndVerifiedBaseline() {
  assertEquals(OnboardingProgress.COMPLETE, OnboardingGate.resumedProgress(OnboardingProgress.LEGACY, true, true))
  assertEquals(OnboardingProgress.PENDING, OnboardingGate.resumedProgress(OnboardingProgress.LEGACY, true, false))
  assertEquals(OnboardingProgress.PENDING, OnboardingGate.resumedProgress(OnboardingProgress.COMPLETE, false, true))
 }
 @Test fun postponedDecisionRemainsPostponedAcrossRestart() {
  assertEquals(OnboardingProgress.DEFERRED, OnboardingGate.resumedProgress(OnboardingProgress.DEFERRED, true, true))
  assertEquals(OnboardingProgress.PENDING, OnboardingGate.resumedProgress(OnboardingProgress.PENDING, true, true))
 }
 @Test fun completedEmptyDecisionRechecksAfterRestartUntilRealBaselineExists() {
  assertEquals(OnboardingProgress.PENDING, OnboardingGate.resumedProgress(OnboardingProgress.COMPLETE, true, false))
  assertEquals(OnboardingProgress.COMPLETE, OnboardingGate.resumedProgress(OnboardingProgress.COMPLETE, true, true))
 }
 @Test fun firstSaveImmediatelyAfterEmptyDecisionIsNotMistakenForCleanStartup() {
  val scheduler = AutomaticUpload(quietMillis = 2500)
  scheduler.applied(emptyMap(), 100)
  val firstSave = mapOf("game1/game.dat" to "a".repeat(64))
  scheduler.observe(firstSave, running = false, stopped = true, now = 101)
  assertFalse(scheduler.shouldUpload(2600, true, true, true, false, false, false))
  assertTrue(scheduler.shouldUpload(2601, true, true, true, false, false, false))
 }

}
