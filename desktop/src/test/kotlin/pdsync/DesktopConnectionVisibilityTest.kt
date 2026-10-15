package pdsync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DesktopConnectionVisibilityTest {
 @Test fun minimizeHideAndFocusLossInvalidateWithoutRepeatedCallbacks() {
  val changes = mutableListOf<Boolean>()
  val lifecycle = DesktopConnectionVisibility(changes::add)
  lifecycle.update(showing = true, focused = true, iconified = false)
  lifecycle.update(showing = true, focused = true, iconified = false)
  lifecycle.update(showing = true, focused = true, iconified = true)
  lifecycle.update(showing = false, focused = false, iconified = true)
  lifecycle.update(showing = true, focused = true, iconified = false)
  lifecycle.update(showing = true, focused = false, iconified = false)
  lifecycle.update(showing = true, focused = true, iconified = false)
  lifecycle.update(showing = false, focused = true, iconified = false)
  assertEquals(listOf(true, false, true, false, true, false), changes)
 }

 @Test fun disposalInvalidatesOnceAndIgnoresLateNativeEvents() {
  val changes = mutableListOf<Boolean>()
  val lifecycle = DesktopConnectionVisibility(changes::add)
  lifecycle.update(showing = true, focused = true, iconified = false)
  lifecycle.close(); lifecycle.close()
  lifecycle.update(showing = true, focused = true, iconified = false)
  assertEquals(listOf(true, false), changes)
 }

 @Test fun hiddenInitialWindowDoesNotBecomeForegroundOnFocusAlone() {
  val changes = mutableListOf<Boolean>()
  val lifecycle = DesktopConnectionVisibility(changes::add)
  lifecycle.update(showing = false, focused = true, iconified = false)
  lifecycle.update(showing = true, focused = true, iconified = true)
  lifecycle.update(showing = true, focused = false, iconified = false)
  assertEquals(listOf(false), changes)
 }
}
