package pdsync

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class ConnectionPageLifetimeTest {
 @Test fun oneAttemptAtATimeAndRetryGetsNewGeneration() {
  val page = ConnectionPageLifetime(); val first = assertNotNull(page.begin())
  assertNull(page.begin()); assertTrue(page.isCurrent(first)); assertTrue(page.finish(first))
  assertFalse(page.isCurrent(first)); assertFalse(page.finish(first))
  val next = assertNotNull(page.begin()); assertTrue(next > first); assertTrue(page.isCurrent(next))
 }
 @Test fun disposedPageCannotBeginOrPersistOrCompleteLateResult() {
  val page = ConnectionPageLifetime(); val ticket = assertNotNull(page.begin()); var cancellations = 0; var writes = 0
  page.close { cancellations++ }
  assertEquals(1, cancellations); assertNull(page.begin()); assertFalse(page.isCurrent(ticket))
  assertNull(page.persist(ticket) { writes++; "saved" }); assertEquals(0, writes); assertFalse(page.finish(ticket))
 }
 @Test fun closingIdlePageStillPreventsFutureActions() {
  val page = ConnectionPageLifetime(); page.close { }
  assertNull(page.begin()); assertNull(page.persist(1) { error("must not run") }); assertFalse(page.finish(1))
 }
 @Test fun oldCompletionCannotReleaseOrWriteForNewAttempt() {
  val page = ConnectionPageLifetime(); val old = assertNotNull(page.begin()); assertTrue(page.finish(old))
  val current = assertNotNull(page.begin()); var writes = 0
  assertFalse(page.finish(old)); assertNull(page.persist(old) { writes++ })
  assertTrue(page.isCurrent(current)); assertNull(page.begin())
  assertEquals("ok", page.persist(current) { writes++; "ok" }); assertEquals(1, writes)
  assertTrue(page.finish(current))
 }
 @Test fun onlyExactCurrentTicketCanPersist() {
  val page = ConnectionPageLifetime(); val ticket = assertNotNull(page.begin()); var writes = 0
  assertNull(page.persist(ticket + 1) { writes++ }); assertFalse(page.finish(ticket + 1))
  assertEquals(0, writes); assertTrue(page.isCurrent(ticket))
  assertEquals(42, page.persist(ticket) { writes++; 42 }); assertEquals(1, writes)
 }
 @Test fun failingPersistenceKeepsAttemptOwnedUntilItsFinallyFinishes() {
  val page = ConnectionPageLifetime(); val ticket = assertNotNull(page.begin())
  assertFailsWith<IllegalStateException> { page.persist(ticket) { error("synthetic file failure") } }
  assertTrue(page.isCurrent(ticket)); assertNull(page.begin()); assertTrue(page.finish(ticket)); assertNotNull(page.begin())
 }
 @Test fun concurrentDuplicateClicksReserveExactlyOneAttempt() {
  val page = ConnectionPageLifetime(); val ready = CountDownLatch(12); val release = CountDownLatch(1)
  val tickets = CopyOnWriteArrayList<Long>()
  val workers = List(12) { thread { ready.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); page.begin()?.let(tickets::add) } }
  try { assertTrue(ready.await(5, TimeUnit.SECONDS)) } finally { release.countDown(); workers.forEach { it.join(5000) } }
  assertTrue(workers.none { it.isAlive }); assertEquals(1, tickets.size); assertTrue(page.finish(tickets.single()))
 }
 @Test fun persistenceAlreadyStartedFinishesBeforeDisposalAndNothingStartsAfter() {
  val page = ConnectionPageLifetime(); val ticket = assertNotNull(page.begin())
  val entered = CountDownLatch(1); val release = CountDownLatch(1); val closing = CountDownLatch(1); val closed = CountDownLatch(1)
  val events = CopyOnWriteArrayList<String>()
  val writer = thread { page.persist(ticket) { events += "write-start"; entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); events += "write-finish" } }
  assertTrue(entered.await(5, TimeUnit.SECONDS))
  val disposer = thread { closing.countDown(); page.close { events += "cancel" }; closed.countDown() }
  try {
   assertTrue(closing.await(5, TimeUnit.SECONDS)); assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
  } finally { release.countDown(); writer.join(5000); disposer.join(5000) }
  assertEquals(listOf("write-start", "write-finish", "cancel"), events.toList())
  assertTrue(closed.await(1, TimeUnit.SECONDS)); assertNull(page.persist(ticket) { error("late write") }); assertFalse(page.finish(ticket))
 }
 @Test fun cancellationFailureCannotReactivatePage() {
  val page = ConnectionPageLifetime(); val ticket = assertNotNull(page.begin())
  assertFailsWith<IllegalStateException> { page.close { error("synthetic cancellation failure") } }
  assertFalse(page.isCurrent(ticket)); assertNull(page.begin()); assertFalse(page.finish(ticket))
  assertNull(page.persist(ticket) { error("late write") })
 }
 @Test fun explicitCancelAndCompositionDisposalCancelControllerOnlyOnce() {
  val page = ConnectionPageLifetime(); val ticket = assertNotNull(page.begin()); var cancellations = 0
  page.close { cancellations++ }; page.close { error("duplicate cancel callback") }
  assertEquals(1, cancellations); assertFalse(page.isCurrent(ticket)); assertNull(page.begin())
 }

}
