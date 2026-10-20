package pdsync

import org.junit.jupiter.api.Test
import kotlin.test.*

class AutomaticUploadTest {
 private val a = mapOf("game1/game.dat" to "a")
 private val b = mapOf("game1/game.dat" to "b")
 private fun AutomaticUpload.ready(t: Long, pending: Boolean = false, enabled: Boolean = true, connected: Boolean = true, stopped: Boolean = true, conflict: Boolean = false, busy: Boolean = false) = shouldUpload(t, enabled, connected, stopped, pending, conflict, busy)
 @Test fun `startup does not upload existing or empty files`() {
  for (files in listOf(a, emptyMap())) { val c=AutomaticUpload(); c.observe(files,false,true,0); assertFalse(c.ready(100000)) }
 }
 @Test fun `changes debounce while running or unknown and retain pause network conflict and busy gates`() {
  for (running in listOf(true, false)) {
   val c=AutomaticUpload(); c.observe(a,false,true,0); c.observe(b,running,false,100)
   assertFalse(c.ready(2599,stopped=false)); assertTrue(c.ready(2600,stopped=false))
   assertFalse(c.ready(2600,enabled=false)); assertFalse(c.ready(2600,connected=false))
   assertFalse(c.ready(2600,busy=true)); assertFalse(c.ready(2600,conflict=true))
  }
 }
 @Test fun `game exit does not delay a queued content change`() {
  val c=AutomaticUpload(); c.observe(a,false,true,0); c.observe(b,true,false,100)
  c.observe(b,false,true,2600); assertTrue(c.ready(2600))
 }
 @Test fun `pending retry resumes after restart without recapture`() { val c=AutomaticUpload(); c.observe(a,false,true,0); assertTrue(c.ready(2500,pending=true)) }
 @Test fun `singleflight and bounded backoff`() {
  val c=AutomaticUpload(); c.observe(a,false,true,0); c.observe(b,false,true,1); c.started(); assertFalse(c.ready(9999)); c.failed(10000,true); assertFalse(c.ready(14999)); assertTrue(c.ready(15000))
  repeat(20) { c.started(); c.failed(20000,true) }; assertFalse(c.ready(139999)); assertTrue(c.ready(140000))
 }
 @Test fun `nonnetwork failure needs explicit retry`() { val c=AutomaticUpload(); c.observe(a,false,true,0); c.started(); c.failed(0,false); assertFalse(c.ready(999999,pending=true)); c.retryAllowed(); assertTrue(c.ready(999999,pending=true)) }
 @Test fun `restore produces one frozen operation and no self write upload loop`() {
  val c=AutomaticUpload(); c.observe(a,false,true,0); c.applied(b,10); assertFalse(c.ready(2510)); assertTrue(c.ready(2510,pending=true)); c.started(); c.succeeded(b); c.observe(b,false,true,3000); assertFalse(c.ready(999999))
 }
 @Test fun `dirty change during upload remains queued`() { val c=AutomaticUpload(); c.observe(a,false,true,0); c.started(); c.observe(b,false,true,100); c.succeeded(a); assertTrue(c.ready(2600)) }
 @Test fun `return to settled content cancels uncommitted work`() { val c=AutomaticUpload(); c.observe(a,false,true,0); c.observe(b,false,true,10); c.observe(a,false,true,20); assertFalse(c.ready(999999)) }
 @Test fun `game exit is an eligible event even unchanged observation`() { val c=AutomaticUpload(); c.observe(a,true,false,0); c.observe(a,false,true,10); assertFalse(c.ready(2509)); assertTrue(c.ready(2510)) }
 @Test fun `changed empty content never publishes deletion snapshot`() { val c=AutomaticUpload(); c.observe(a,false,true,0); c.observe(emptyMap(),false,true,10); assertFalse(c.ready(999999)) }
 @Test fun `persisted baseline detects changes made while application was closed`() {
  val c=AutomaticUpload(); c.establishBaseline(a); c.observe(b,false,true,100); assertFalse(c.ready(2599)); assertTrue(c.ready(2600))
 }
 @Test fun `unchanged persisted baseline does not produce startup duplicate`() {
  val c=AutomaticUpload(); c.establishBaseline(a); c.observe(a,false,true,100); assertFalse(c.ready(999999))
 }
 @Test fun `skipped comparison does not loop even for a frozen pending upload`() {
  val c=AutomaticUpload(); c.observe(a,false,true,0); c.started(); c.skipped()
  c.observe(a,false,true,10000)
  assertFalse(c.ready(999999,pending=true)); assertTrue(c.needsAttention); assertFalse(c.waitingForRetry)
  c.retryAllowed(); assertTrue(c.ready(999999,pending=true))
 }
 @Test fun `new content or observed game exit can request a fresh comparison after skip`() {
  val changed=AutomaticUpload(); changed.observe(a,false,true,0); changed.started(); changed.skipped()
  changed.observe(b,false,true,100); assertFalse(changed.ready(2599)); assertTrue(changed.ready(2600))
  val exit=AutomaticUpload(); exit.observe(a,false,true,0); exit.started(); exit.skipped()
  exit.observe(a,true,false,100); exit.observe(a,false,true,200)
  assertFalse(exit.ready(2699)); assertTrue(exit.ready(2700))
 }
 @Test fun `skip never marks unpublished content as the settled baseline`() {
  val c=AutomaticUpload(); c.establishBaseline(a); c.observe(b,false,true,0); c.started(); c.skipped()
  c.observe(a,false,true,100)
  assertFalse(c.ready(999999)); assertFalse(c.needsAttention)
 }
}
