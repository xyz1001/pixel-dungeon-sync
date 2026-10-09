package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FileNotFoundException
import java.io.IOException
import java.io.UncheckedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.test.*

class OperationEventsTest {
 @TempDir lateinit var directory: Path
 private val path: Path get() = directory.resolve("diagnostics/action-events.log")
 private val previous: Path get() = path.resolveSibling("action-events.log.1")

 @Test fun persistsAcrossRecorderInstancesAndKeepsOneOpaqueIdForEachAction() {
  val trace = OperationEvents(path).begin(OperationAction.PUSH)
  assertTrue(trace.record(OperationEvent.CONFIRMATION_SUBMITTED))
  assertTrue(trace.record(OperationEvent.WORK_SUCCEEDED))
  val next = OperationEvents(path).begin(OperationAction.PUSH)
  assertEquals(OperationAction.PUSH, trace.action)
  assertEquals(trace.id, UUID.fromString(trace.id).toString())
  assertNotEquals(trace.id, next.id)
  val lines = readLines(path)
  assertEquals(4, lines.size)
  assertEquals(listOf(trace.id, trace.id, trace.id, next.id), lines.map { it.getValue("id") })
  assertEquals(listOf("CONFIRMATION_ENTERED", "CONFIRMATION_SUBMITTED", "WORK_SUCCEEDED", "CONFIRMATION_ENTERED"), lines.map { it["event"] })
 }

 @Test fun acceptsOnlyTheFixedSchemaAndOptionalEnumFields() {
  val recorder = OperationEvents(path)
  OperationAction.entries.forEach { action ->
   val trace = recorder.begin(action)
   OperationEvent.entries.forEach { assertTrue(trace.record(it)) }
   OperationGuard.entries.forEach { assertTrue(trace.record(OperationEvent.GUARD_REJECTED, guard = it)) }
   OperationFailure.entries.forEach { assertTrue(trace.record(OperationEvent.WORK_FAILED, failure = it)) }
  }
  val lines = readLines(path)
  assertEquals(OperationAction.entries.toSet(), lines.map { OperationAction.valueOf(it.getValue("action")) }.toSet())
  assertEquals(OperationEvent.entries.toSet(), lines.map { OperationEvent.valueOf(it.getValue("event")) }.toSet())
  assertEquals(OperationGuard.entries.toSet(), lines.mapNotNull { it["guard"]?.let(OperationGuard::valueOf) }.toSet())
  assertEquals(OperationFailure.entries.toSet(), lines.mapNotNull { it["failure"]?.let(OperationFailure::valueOf) }.toSet())
 }

 @Test fun classifiesTypedErrorsWithoutGuessingFromTheirMessages() {
  val cases = listOf(
   CancellationException("secret") to OperationFailure.CANCELED,
   InterruptedException("secret") to OperationFailure.CANCELED,
   ClosedByInterruptException() to OperationFailure.CANCELED,
   WebDavException(401) to OperationFailure.AUTHENTICATION,
   WebDavException(403) to OperationFailure.AUTHORIZATION,
   WebDavException(503) to OperationFailure.NETWORK,
   NamespaceProvisioningException(NamespaceFailureReason.AUTHENTICATION) to OperationFailure.AUTHENTICATION,
   NamespaceProvisioningException(NamespaceFailureReason.FORBIDDEN) to OperationFailure.AUTHORIZATION,
   NamespaceProvisioningException(NamespaceFailureReason.CANCELLED) to OperationFailure.CANCELED,
   NamespaceProvisioningException(NamespaceFailureReason.NETWORK) to OperationFailure.NETWORK,
   NamespaceProvisioningException(NamespaceFailureReason.HTTP_ERROR, 500) to OperationFailure.NETWORK,
   NamespaceProvisioningException(NamespaceFailureReason.UNSAFE_RESPONSE) to OperationFailure.VALIDATION,
   SocketTimeoutException("secret") to OperationFailure.NETWORK,
   ConnectException("secret") to OperationFailure.NETWORK,
   UnknownHostException("secret") to OperationFailure.NETWORK,
   SSLHandshakeException("secret") to OperationFailure.NETWORK,
   AccessDeniedException("secret") to OperationFailure.STORAGE,
   FileNotFoundException("secret") to OperationFailure.STORAGE,
   SecurityException("secret") to OperationFailure.AUTHORIZATION,
   IllegalArgumentException("secret") to OperationFailure.VALIDATION,
   RemoteIntegrityException("secret") to OperationFailure.VALIDATION,
   UnsettledRemoteException("secret") to OperationFailure.VALIDATION,
   LegacyProtocolException() to OperationFailure.VALIDATION,
   IOException("HTTP 401 password=secret") to OperationFailure.OTHER,
   RuntimeException("NETWORK cancellation authentication secret") to OperationFailure.OTHER,
   ExecutionException(WebDavException(401)) to OperationFailure.AUTHENTICATION,
   CompletionException(ExecutionException(AccessDeniedException("secret"))) to OperationFailure.STORAGE,
   UncheckedIOException(FileNotFoundException("secret")) to OperationFailure.STORAGE
  )
  cases.forEach { (failure, expected) -> assertEquals(expected, operationFailure(failure), failure.javaClass.simpleName) }
 }

 @Test fun neverInspectsOrWritesExceptionMessagesAndPrivateSentinels() {
  val secret = "PRIVATE_SENTINEL password=top-secret https://private.example/save user@example.com Authorization: Bearer private-token game.dat\n event=INJECTED"
  val trace = OperationEvents(path).begin(OperationAction.RESTORE)
  listOf(IllegalArgumentException(secret), IOException(secret), AccessDeniedException(secret), RuntimeException(secret)).forEach {
   assertTrue(trace.record(OperationEvent.WORK_FAILED, failure = operationFailure(it)))
  }
  val unreadableMessage = object : RuntimeException() {
   override val message: String get() = error("Exception text must never be inspected")
   override fun toString(): String = error("Exception must never be stringified")
  }
  assertEquals(OperationFailure.OTHER, operationFailure(unreadableMessage))
  val text = Files.readString(path)
  listOf("PRIVATE_SENTINEL", "top-secret", "private.example", "user@example.com", "Authorization", "private-token", "game.dat", "INJECTED").forEach {
   assertFalse(text.contains(it), "Unexpected private value in diagnostics")
  }
  assertEquals(5, readLines(path).size)
 }

 @Test fun concurrentRecordersProduceCompleteNonInterleavedLines() {
  val count = 8
  val pool = Executors.newFixedThreadPool(count)
  val ready = CountDownLatch(count); val release = CountDownLatch(1)
  try {
   val jobs = (1..count).map {
    pool.submit<String> {
     val trace = OperationEvents(path).begin(OperationAction.CAPTURE)
     ready.countDown(); check(release.await(10, TimeUnit.SECONDS))
     repeat(40) { check(trace.record(OperationEvent.WORK_STARTED)) }
     trace.id
    }
   }
   assertTrue(ready.await(10, TimeUnit.SECONDS)); release.countDown()
   val ids = jobs.map { it.get(15, TimeUnit.SECONDS) }
   assertEquals(count, ids.toSet().size)
   val lines = readLines(path)
   assertEquals(count * 41, lines.size)
   ids.forEach { id -> assertEquals(41, lines.count { it["id"] == id }) }
  } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
 }

 @Test fun rotatesAtTheBoundAndRetainsOnlyOnePreviousGeneration() {
  val trace = OperationEvents(path).begin(OperationAction.PUSH)
  val firstLine = Files.readString(path)
  val full = firstLine.repeat(OperationEvents.MAX_FILE_BYTES / firstLine.toByteArray().size)
  Files.writeString(path, full)
  assertTrue(trace.record(OperationEvent.HISTORY_REFRESH_SUCCEEDED))
  assertEquals(full, Files.readString(previous))
  assertEquals("HISTORY_REFRESH_SUCCEEDED", readLines(path).single()["event"])
  val secondLine = Files.readString(path)
  val nextFull = secondLine.repeat(OperationEvents.MAX_FILE_BYTES / secondLine.toByteArray().size)
  Files.writeString(path, nextFull)
  assertTrue(trace.record(OperationEvent.HISTORY_REFRESH_SUCCEEDED))
  assertEquals(nextFull, Files.readString(previous))
  assertTrue(Files.size(path) <= OperationEvents.MAX_FILE_BYTES)
  assertTrue(Files.size(previous) <= OperationEvents.MAX_FILE_BYTES)
  assertEquals(2, Files.list(path.parent).use { it.count() }.toInt())
  readLines(path); readLines(previous)
 }

 @Test fun invalidOrUnwritableDestinationCannotChangeTheOperationOutcome() {
  val blocker = directory.resolve("not-a-directory")
  Files.writeString(blocker, "keep this")
  val trace = OperationEvents(blocker.resolve("action-events.log")).begin(OperationAction.ARCHIVE)
  assertFalse(trace.record(OperationEvent.WORK_SUCCEEDED))
  assertEquals("keep this", Files.readString(blocker))
  val directoryTrace = OperationEvents(directory).begin(OperationAction.ARCHIVE)
  assertFalse(directoryTrace.record(OperationEvent.OPERATION_FINISHED))
 }

 @Test fun refusesSymbolicLinkLogAndAncestorWithoutTouchingTheirTargets() {
  val target = directory.resolve("private-target")
  Files.writeString(target, "private original")
  Files.createDirectories(path.parent)
  Files.createSymbolicLink(path, target)
  assertFalse(OperationEvents(path).begin(OperationAction.PUSH).record(OperationEvent.WORK_STARTED))
  assertEquals("private original", Files.readString(target))
  val realDirectory = Files.createDirectory(directory.resolve("private-directory"))
  val linkedDirectory = Files.createSymbolicLink(directory.resolve("linked-directory"), realDirectory)
  assertFalse(OperationEvents(linkedDirectory.resolve("events.log")).begin(OperationAction.PUSH).record(OperationEvent.WORK_STARTED))
  assertEquals(0L, Files.list(realDirectory).use { it.count() })
 }

 @Test fun refusesSymbolicLinkPreviousGenerationBeforeRotation() {
  val trace = OperationEvents(path).begin(OperationAction.PUSH)
  val original = Files.readString(path)
  val target = directory.resolve("private-target")
  Files.writeString(target, "private original")
  Files.createSymbolicLink(previous, target)
  assertFalse(trace.record(OperationEvent.WORK_STARTED))
  assertEquals(original, Files.readString(path))
  assertEquals("private original", Files.readString(target))
  assertTrue(Files.isSymbolicLink(previous))
 }

 @Test fun unsafeDestinationFailureIsBestEffortAndDoesNotPermanentlyDisableRecorder() {
  Files.createDirectories(path.parent)
  Files.createDirectory(path)
  val trace = OperationEvents(path).begin(OperationAction.ARCHIVE)
  assertFalse(trace.record(OperationEvent.WORK_STARTED))
  Files.delete(path)
  assertTrue(trace.record(OperationEvent.WORK_SUCCEEDED))
  assertEquals(trace.id, readLines(path).single()["id"])
 }

 private fun readLines(file: Path): List<Map<String, String>> {
  val text = Files.readString(file)
  assertTrue(text.endsWith('\n'))
  return text.trimEnd('\n').split('\n').map { line ->
   val fields = line.split(' ').associate { field ->
    val pair = field.split('=', limit = 2); assertEquals(2, pair.size); pair[0] to pair[1]
   }
   assertTrue(fields.keys.all { it in setOf("timestamp", "id", "action", "event", "guard", "failure") })
   assertTrue(fields.keys.containsAll(listOf("timestamp", "id", "action", "event")))
   Instant.parse(fields.getValue("timestamp")); UUID.fromString(fields.getValue("id"))
   OperationAction.valueOf(fields.getValue("action")); OperationEvent.valueOf(fields.getValue("event"))
   fields["guard"]?.let(OperationGuard::valueOf); fields["failure"]?.let(OperationFailure::valueOf)
   assertEquals(fields.size, line.split(' ').size)
   fields
  }
 }
}
