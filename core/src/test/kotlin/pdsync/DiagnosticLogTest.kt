package pdsync

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*

class DiagnosticLogTest {
 @TempDir lateinit var directory: Path
 private val fixed = Instant.parse("2026-10-14T10:20:30Z")
 private fun configure(now: Instant = fixed, version: String = "0.5.15-root-test+36", platform: String = "android", zone: ZoneId = ZoneOffset.UTC) {
  DiagnosticLog.configureForTests(directory, version, platform, Clock.fixed(now, zone))
 }
 private fun files() = Files.list(directory.resolve("diagnostics-v1")).use { it.toList() }
 private fun unzip(bytes: ByteArray = DiagnosticLog.snapshotExport()): Map<String, String> =
  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
   val result = linkedMapOf<String, String>()
   while (true) {
    val entry = zip.nextEntry ?: break
    result[entry.name] = zip.readBytes().decodeToString()
   }
   result
  }
 private fun records(archive: Map<String, String> = unzip()): List<JsonObject> =
  archive.getValue("events.jsonl").lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
 private fun field(record: JsonObject, key: String) = record.getValue(key).jsonPrimitive.content

 @Test fun persistsVersionedClosedRecordsAcrossReconfigurationWithLocalZoneAndOpaqueId() {
  configure(zone = ZoneId.of("Asia/Shanghai"))
  val trace = DiagnosticLog.begin(DiagnosticAction.PUSH, true)
  DiagnosticLog.bind(trace).use {
   assertTrue(DiagnosticLog.http(DiagnosticHttpMethod.PUT, 201))
   assertTrue(trace.record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED))
  }
  configure(zone = ZoneId.of("Asia/Shanghai"))
  val archive = unzip(); val events = records(archive)
  assertEquals(setOf("metadata.json", "events.jsonl"), archive.keys)
  assertEquals(3, events.size)
  assertTrue(events.all { field(it, "operationId") == trace.id })
  assertEquals(trace.id, UUID.fromString(trace.id).toString())
  assertTrue(events.all { field(it, "timestampUtc") == fixed.toString() && field(it, "zone") == "Asia/Shanghai" && field(it, "offsetMinutes") == "480" })
  assertTrue(events.all { field(it, "schema") == "1" && field(it, "appVersion") == "0.5.15-root-test+36" && field(it, "platform") == "android" })
  assertEquals("3", field(Json.parseToJsonElement(archive.getValue("metadata.json")).jsonObject, "recordCount"))
 }

 @Test fun labelsAndThrowablesCannotLeakFreeTextAndUnknownMetadataIsReplaced() {
  val secret = "SECRET https://private.invalid/a user@example.com password=private game.dat\nmalformed"
  configure(version = secret, platform = secret)
  val trace = DiagnosticLog.begin(DiagnosticAction.RESTORE, false)
  val hostile = object : RuntimeException() {
   override val message: String get() = error("must not inspect arbitrary messages")
   override fun toString(): String = error("must not stringify errors")
  }
  for (error in listOf(IOException(secret), IllegalArgumentException(secret), SocketTimeoutException(secret), hostile))
   assertTrue(trace.failure(DiagnosticStage.WORK, error))
  val text = unzip().values.joinToString()
  listOf("SECRET", "private", "user@example.com", "game.dat", "malformed").forEach { assertFalse(it in text) }
  assertTrue(records().all { field(it, "appVersion") == "unknown" && field(it, "platform") == "unknown" })
 }

 @Test fun exactSanitizedTransportLabelsClassifyWithoutChangingOriginalExceptions() {
  val cases = mapOf("TIMEOUT" to DiagnosticFailure.TIMEOUT, "DNS" to DiagnosticFailure.DNS,
   "CONNECT" to DiagnosticFailure.CONNECT, "TLS" to DiagnosticFailure.TLS, "EOF" to DiagnosticFailure.EOF,
   "CONNECTION" to DiagnosticFailure.CONNECTION, "INTERRUPTED" to DiagnosticFailure.INTERRUPTED, "IO" to DiagnosticFailure.NETWORK_IO)
  for ((label, expected) in cases) {
   val original = IOException("WebDAV network request failed [$label]")
   assertEquals(expected, DiagnosticLog.classify(original))
   assertEquals(IOException::class.java, original.javaClass); assertNull(original.cause)
   assertEquals(DiagnosticFailure.OTHER, DiagnosticLog.classify(IOException(original.message + " secret")))
   assertEquals(DiagnosticFailure.OTHER, DiagnosticLog.classify(IOException(original.message, IOException("secret"))))
  }
  assertEquals(DiagnosticFailure.AUTHENTICATION, DiagnosticLog.classify(WebDavException(401)))
  assertEquals(DiagnosticFailure.AUTHORIZATION, DiagnosticLog.classify(WebDavException(403)))
  assertEquals(DiagnosticFailure.HTTP, DiagnosticLog.classify(WebDavException(507)))
  assertEquals(DiagnosticFailure.CANCELED, DiagnosticLog.classify(CancellationException("secret")))
  assertEquals(DiagnosticFailure.STORAGE, DiagnosticLog.classify(java.nio.file.AccessDeniedException("secret")))
 }

 @Test fun boundNestedContextsRestoreAndDoNotLeakAcrossThreads() {
  configure()
  val outer = DiagnosticLog.begin(DiagnosticAction.PUSH, false)
  val inner = DiagnosticLog.begin(DiagnosticAction.HISTORY, false)
  DiagnosticLog.bind(outer).use {
   DiagnosticLog.bind(inner).use { DiagnosticLog.record(DiagnosticStage.WORK, DiagnosticResult.STARTED) }
   DiagnosticLog.record(DiagnosticStage.WORK, DiagnosticResult.SUCCEEDED)
   val pool = Executors.newSingleThreadExecutor()
   try { assertFalse(pool.submit<Boolean> { DiagnosticLog.record(DiagnosticStage.WORK, DiagnosticResult.STARTED) }.get()) }
   finally { pool.shutdownNow() }
  }
  assertFalse(DiagnosticLog.record(DiagnosticStage.WORK, DiagnosticResult.STARTED))
  val events = records().filter { field(it, "stage") == "WORK" }
  assertEquals(listOf(inner.id, outer.id), events.map { field(it, "operationId") })
 }

 @Test fun closedReadinessOutcomesRoundTripWithoutRawRootCodesOrMessages() {
  configure()
  val trace = DiagnosticLog.begin(DiagnosticAction.ACCESS_CHECK, false)
  val categories = listOf(DiagnosticFailure.BUSY, DiagnosticFailure.NOT_READY, DiagnosticFailure.RECOVERY_REQUIRED,
   DiagnosticFailure.IDENTITY_MISMATCH, DiagnosticFailure.UNAVAILABLE)
  categories.forEach { assertTrue(trace.record(DiagnosticStage.READINESS, DiagnosticResult.REJECTED, it)) }
  val archive = unzip(); val events = records(archive).drop(1)
  assertEquals(categories.map { it.name }, events.map { field(it, "failure") })
  assertTrue(events.all { field(it, "stage") == "READINESS" && field(it, "result") == "REJECTED" && field(it, "operationId") == trace.id })
  assertFalse("ROOT_BUSY" in archive.values.joinToString())
 }

 @Test fun cancellationsRemainExplicitAndStatusDoesNotDecideProtocolSuccess() {
  configure()
  val trace = DiagnosticLog.begin(DiagnosticAction.CONNECT, false)
  DiagnosticLog.bind(trace).use {
   assertFalse(DiagnosticLog.http(DiagnosticHttpMethod.GET, -1)); assertFalse(DiagnosticLog.http(DiagnosticHttpMethod.GET, 1000))
   assertTrue(DiagnosticLog.http(DiagnosticHttpMethod.GET, 404))
   trace.failure(DiagnosticStage.FINISHED, CancellationException("secret"))
  }
  val events = records()
  assertEquals("404", field(events[1], "httpStatus")); assertEquals("SUCCEEDED", field(events[1], "result"))
  assertEquals("CANCELED", field(events.last(), "result")); assertEquals("CANCELED", field(events.last(), "failure"))
 }

 @Test fun rolloverPrunesSevenDateBucketsAndCompactionStaysBounded() {
  for (day in 0L..9L) {
   configure(now = fixed.plusSeconds(day * 86400))
   DiagnosticLog.begin(DiagnosticAction.APP_START, false)
  }
  assertEquals(7, files().size)
  assertEquals(7, records().size)
  val trace = DiagnosticLog.begin(DiagnosticAction.PUSH, false)
  repeat(600) { assertTrue(trace.record(DiagnosticStage.WORK, DiagnosticResult.SUCCEEDED)) }
  assertTrue(files().all { Files.size(it) <= DiagnosticLog.MAX_FILE_BYTES })
  assertTrue(files().sumOf { Files.size(it) } <= DiagnosticLog.MAX_TOTAL_BYTES)
  assertTrue(records().size < 608); assertEquals(trace.id, field(records().last(), "operationId"))
  // Seven retained UTC dates is conservatively less than seven full elapsed days.
  configure(now = fixed.plusSeconds(20 * 86400))
  assertEquals(0, files().size); assertTrue(records().isEmpty())
 }

 @Test fun exportRejectsTamperedFieldsDuplicatesUnknownEnumsAndIncompleteTail() {
  configure()
  DiagnosticLog.begin(DiagnosticAction.PUSH, false)
  val file = files().single(); val valid = file.readText().trimEnd()
  val malicious = listOf(valid.replace("\"PUSH\"", "\"SECRET\""),
   valid.dropLast(1) + ",\"password\":\"SECRET\"}",
   valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
   valid.replace("\"android\"", "\"SECRET\""),
   valid.replace("\"0.5.15-root-test+36\"", "\"SECRET\""),
   valid.replace("\"offsetMinutes\":0", "\"offsetMinutes\":999999"))
  file.writeText(valid + "\n" + malicious.joinToString("\n", postfix = "\n") + "SECRET incomplete tail")
  val archive = unzip()
  assertEquals(1, records(archive).size); assertFalse("SECRET" in archive.values.joinToString())
  assertTrue(DiagnosticLog.begin(DiagnosticAction.HISTORY, false).record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED))
  assertEquals(3, records().size)
 }

 @Test fun snapshotAndConcurrentWritersRemainCompleteWithConsistentMetadata() {
  configure(); val pool = Executors.newFixedThreadPool(4)
  try {
   val jobs = (1..4).map { pool.submit {
    val trace = DiagnosticLog.begin(DiagnosticAction.INSPECT, false)
    repeat(40) { assertTrue(trace.record(DiagnosticStage.WORK, DiagnosticResult.SUCCEEDED)) }
   } }
   repeat(12) {
    val archive = unzip(); val metadata = Json.parseToJsonElement(archive.getValue("metadata.json")).jsonObject
    assertEquals(records(archive).size.toString(), field(metadata, "recordCount"))
    assertTrue(archive.getValue("events.jsonl").isEmpty() || archive.getValue("events.jsonl").endsWith('\n'))
   }
   jobs.forEach { it.get(20, TimeUnit.SECONDS) }
   assertEquals(164, records().size)
  } finally { pool.shutdownNow() }
 }

 @Test fun exportCreatesCollisionSafeNamesWithoutTruncatingExistingFiles() {
  configure(); DiagnosticLog.begin(DiagnosticAction.EXPORT_LOGS, false)
  val target = Files.createDirectory(directory.resolve("out"))
  val occupied = target.resolve(DiagnosticLog.suggestedFilename()); occupied.writeText("existing-private-file")
  val first = DiagnosticLog.exportToDirectory(target); val second = DiagnosticLog.exportToDirectory(target)
  assertNotEquals(first, second); assertNotEquals(occupied, first)
  assertEquals("existing-private-file", occupied.readText())
  assertEquals(setOf("metadata.json", "events.jsonl"), unzip(Files.readAllBytes(first)).keys)
 }

 @Test fun storageFailuresAreBestEffortAndExportsNeverIncludeLegacyFiles() {
  val blocker = directory.resolve("blocked"); blocker.writeText("private-existing")
  DiagnosticLog.configure(blocker, "development", "linux")
  val trace = DiagnosticLog.begin(DiagnosticAction.PUSH, false)
  assertFalse(trace.record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED))
  assertEquals("private-existing", blocker.readText())
  assertEquals("false", field(Json.parseToJsonElement(unzip().getValue("metadata.json")).jsonObject, "storageAvailable"))
  configure(); directory.resolve("operation-events.log").writeText("legacy private payload")
  directory.resolve("config.json").writeText("password private")
  directory.resolve("diagnostics-v1/unrecognized.log").writeText("raw private log")
  assertFalse("private" in unzip().values.joinToString())
  assertFailsWith<IOException> { DiagnosticLog.exportToDirectory(blocker) }
 }

 @Test fun interruptedCompactionLeavesOnlyOneBoundedTemporaryAndRecoversSafely() {
  configure(); DiagnosticLog.begin(DiagnosticAction.APP_START, false)
  val rotation = directory.resolve("diagnostics-v1/rotation.tmp")
  Files.write(rotation, ByteArray(DiagnosticLog.MAX_FILE_BYTES / 2) { 'X'.code.toByte() })
  configure()
  assertFalse(Files.exists(rotation)); assertEquals(1, records().size)
  assertTrue(DiagnosticLog.begin(DiagnosticAction.PUSH, false).record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED))
  val target = directory.resolve("private-rotation-target"); target.writeText("PRIVATE")
  Files.createSymbolicLink(rotation, target)
  assertFalse(DiagnosticLog.begin(DiagnosticAction.PUSH, false).record(DiagnosticStage.WORK, DiagnosticResult.STARTED))
  assertEquals("PRIVATE", target.readText()); assertTrue(Files.isSymbolicLink(rotation))
 }

 @Test fun oversizedStoredFileIsRejectedWithoutReadingOrExportingIt() {
  configure(); DiagnosticLog.begin(DiagnosticAction.APP_START, false)
  val file = files().single(); file.writeText("SECRET".repeat(DiagnosticLog.MAX_FILE_BYTES / 6 + 1))
  assertFalse(DiagnosticLog.begin(DiagnosticAction.PUSH, false).record(DiagnosticStage.WORK, DiagnosticResult.STARTED))
  val exported = unzip(); assertTrue(records(exported).isEmpty()); assertFalse("SECRET" in exported.values.joinToString())
 }

 @Test fun destinationSymlinkCannotOverwriteItsTarget() {
  configure(); val output = Files.createDirectory(directory.resolve("output"))
  val target = directory.resolve("private-target"); target.writeText("PRIVATE-ORIGINAL")
  Files.createSymbolicLink(output.resolve(DiagnosticLog.suggestedFilename()), target)
  assertFailsWith<IOException> { DiagnosticLog.exportToDirectory(output) }
  assertEquals("PRIVATE-ORIGINAL", target.readText())
 }

 @Test fun symlinkLogOrExportDestinationCannotTouchTargets() {
  configure()
  val secret = directory.resolve("private"); secret.writeText("private-original")
  Files.createSymbolicLink(directory.resolve("diagnostics-v1/events-2026-10-14.jsonl"), secret)
  assertFalse(DiagnosticLog.begin(DiagnosticAction.PUSH, false).record(DiagnosticStage.WORK, DiagnosticResult.STARTED))
  assertFalse("private-original" in unzip().values.joinToString())
  assertEquals("private-original", secret.readText())
  val out = Files.createDirectory(directory.resolve("out")); val link = directory.resolve("linked-out")
  Files.createSymbolicLink(link, out)
  assertFailsWith<IOException> { DiagnosticLog.exportToDirectory(link) }
  assertEquals(0L, Files.list(out).use { it.count() })
 }
}
