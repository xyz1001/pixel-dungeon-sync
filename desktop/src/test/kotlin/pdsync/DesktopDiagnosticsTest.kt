package pdsync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.test.*

class DesktopDiagnosticsTest {
 @TempDir lateinit var directory: Path
 private val privateDirectory get() = directory.resolve("private")
 private val outputDirectory get() = directory.resolve("exports")

 @BeforeEach fun setup() {
  DiagnosticLog.configure(privateDirectory, "development", "linux")
  Files.createDirectory(outputDirectory)
 }

 @Test fun platformAndDevelopmentMetadataAreHonestAndLegacyFilesRemainUntouched() {
  val legacy = privateDirectory.resolve("operation-events.log")
  val previous = privateDirectory.resolve("operation-events.log.1")
  val history = privateDirectory.resolve("manager/history/keep.bin")
  Files.createDirectories(history.parent)
  Files.writeString(legacy, "legacy events")
  Files.writeString(previous, "previous events")
  Files.writeString(history, "history sentinel")
  configureDesktopDiagnostics(privateDirectory)
  val archive = entries(DiagnosticLog.snapshotExport())
  assertContains(archive.getValue("metadata.json"), "\"appVersion\":\"development\"")
  assertContains(archive.getValue("metadata.json"), "\"zone\":")
  assertContains(archive.getValue("events.jsonl"), "\"action\":\"APP_START\"")
  assertEquals("legacy events", Files.readString(legacy))
  assertEquals("previous events", Files.readString(previous))
  assertEquals("history sentinel", Files.readString(history))
  assertEquals("windows", desktopDiagnosticPlatform("Windows 11"))
  assertEquals("macos", desktopDiagnosticPlatform("Mac OS X"))
  assertEquals("macos", desktopDiagnosticPlatform("Darwin"))
  assertEquals("linux", desktopDiagnosticPlatform("Linux"))
  assertNull(desktopDiagnosticPlatform("Unrecognized"))
 }

 @Test fun chooserCancellationIsSilentAndNeverWritesAnExport() = runBlocking {
  var writes = 0
  val exporter = DesktopDiagnosticExport(choose = { initial, title ->
   assertEquals("/initial folder", initial); assertEquals("导出日志", title); null
  }, write = { writes++; error("must not write") })
  assertEquals(DiagnosticExportResult.Canceled, exporter.export("/initial folder"))
  assertEquals(0, writes)
  assertEquals(0L, Files.list(outputDirectory).use { it.count() })
 }

 @Test fun unavailableChooserReturnsOnlySafeShortText() = runBlocking {
  val exporter = DesktopDiagnosticExport(choose = { _, _ -> throw IOException("PRIVATE_SENTINEL /account secret") })
  val result = assertIs<DiagnosticExportResult.Failed>(exporter.export("initial"))
  assertEquals("无法打开系统目录选择器，请稍后重试。", result.message)
  assertFalse(entries(DiagnosticLog.snapshotExport()).values.joinToString().contains("PRIVATE_SENTINEL"))
 }

 @Test fun readOnlyAndDiskFullFailuresAreSafeAndCanBeRetried() = runBlocking {
  for (failure in listOf(AccessDeniedException("PRIVATE_SENTINEL"), FileSystemException("PRIVATE_SENTINEL", null, "No space left on device"))) {
   var fail = true
   val exporter = DesktopDiagnosticExport(choose = { _, _ -> outputDirectory.toString() }, write = { target ->
    if (fail) { fail = false; throw failure }
    DiagnosticLog.exportToDirectory(target)
   })
   assertEquals("导出失败，请检查目录权限和剩余空间。", assertIs<DiagnosticExportResult.Failed>(exporter.export("initial")).message)
   assertIs<DiagnosticExportResult.Saved>(exporter.export("initial"))
  }
  assertFalse(entries(DiagnosticLog.snapshotExport()).values.joinToString().contains("PRIVATE_SENTINEL"))
 }

 @Test fun duplicateExportDoesNotOpenAnotherChooserAndCancellationReleasesGuard() = runBlocking {
  val opened = CompletableDeferred<Unit>(); val answer = CompletableDeferred<String?>()
  var openings = 0
  val exporter = DesktopDiagnosticExport(choose = { _, _ -> openings++; opened.complete(Unit); answer.await() })
  val first = async { exporter.export("initial") }
  opened.await()
  assertEquals(DiagnosticExportResult.Ignored, exporter.export("initial"))
  assertEquals(1, openings)
  answer.complete(null)
  assertEquals(DiagnosticExportResult.Canceled, first.await())
  assertEquals(DiagnosticExportResult.Canceled, exporter.export("initial"))
  assertEquals(2, openings)
 }

 @Test fun coroutineCancellationReleasesGuardAndDoesNotWrite() = runBlocking {
  val opened = CompletableDeferred<Unit>(); val answer = CompletableDeferred<String?>()
  val exporter = DesktopDiagnosticExport(choose = { _, _ -> opened.complete(Unit); answer.await() })
  val pending = async { exporter.export("initial") }
  opened.await(); pending.cancelAndJoin()
  answer.complete(null)
  assertEquals(DiagnosticExportResult.Canceled, exporter.export("initial"))
  assertEquals(0L, Files.list(outputDirectory).use { it.count() })
 }

 @Test fun productionExportUsesChosenDirectoryPreservesCollisionsAndContainsNoLegacyData() = runBlocking {
  Files.writeString(privateDirectory.resolve("operation-events.log"), "PRIVATE_SENTINEL legacy")
  val trace = OperationEvents(privateDirectory.resolve("operation-events-new.log")).begin(OperationAction.PUSH, automatic = true)
  trace.record(OperationEvent.WORK_STARTED)
  trace.withDiagnostics { DiagnosticLog.http(DiagnosticHttpMethod.PUT, 503) }
  trace.record(OperationEvent.WORK_FAILED, failure = OperationFailure.NETWORK)
  trace.record(OperationEvent.OPERATION_FINISHED, failure = OperationFailure.NETWORK)
  var collision: Path? = null
  val exporter = DesktopDiagnosticExport(choose = { _, _ -> outputDirectory.toString() }, write = { destination ->
   collision = destination.resolve(DiagnosticLog.suggestedFilename())
   Files.writeString(collision, "existing export must survive")
   DiagnosticLog.exportToDirectory(destination)
  })
  val saved = assertIs<DiagnosticExportResult.Saved>(exporter.export("initial"))
  val savedPath = outputDirectory.resolve(saved.filename)
  assertTrue(Files.isRegularFile(savedPath))
  assertNotEquals(collision, savedPath)
  assertEquals("existing export must survive", Files.readString(collision))
  val archive = entries(Files.readAllBytes(savedPath))
  assertEquals(setOf("metadata.json", "events.jsonl", "应用.log"), archive.keys)
  val events = archive.getValue("events.jsonl")
  assertContains(events, "\"automatic\":true")
  assertContains(events, "\"httpStatus\":503")
  assertContains(events, "\"failure\":\"NETWORK_IO\"")
  assertFalse(archive.values.joinToString().contains("PRIVATE_SENTINEL"))
  assertEquals("PRIVATE_SENTINEL legacy", Files.readString(privateDirectory.resolve("operation-events.log")))
 }

 @Test fun operationLifecycleMirrorsClosedStagesWithoutChangingLegacySchema() {
  val legacy = privateDirectory.resolve("operation-events.log")
  val trace = OperationEvents(legacy).begin(OperationAction.RESTORE)
  OperationEvent.entries.forEach { trace.record(it) }
  OperationFailure.entries.forEach { trace.record(OperationEvent.OPERATION_FINISHED, failure = it) }
  val text = entries(DiagnosticLog.snapshotExport()).getValue("events.jsonl")
  listOf("CONFIRMATION", "GUARD", "QUEUED", "STARTED", "WORK", "REFRESH", "FINISHED").forEach { assertContains(text, "\"stage\":\"$it\"") }
  assertContains(text, "\"result\":\"CANCELED\"")
  assertContains(text, "\"failure\":\"AUTHENTICATION\"")
  assertFalse(Files.readString(legacy).contains("appVersion"))
  assertEquals(OperationEvent.entries.size + OperationFailure.entries.size + 1, Files.readAllLines(legacy).size)
 }

 @Test fun actualIoBindingCorrelatesHttpAndClearsContextOnFailure() {
  val secret = "PRIVATE_SENTINEL https://private.invalid secret"
  val failure = object : RuntimeException() {
   override val message: String get() = error(secret)
   override fun toString(): String = error(secret)
  }
  assertSame(failure, assertFailsWith<RuntimeException> {
   diagnosticWork(DiagnosticAction.CONNECT) {
    DiagnosticLog.http(DiagnosticHttpMethod.PROPFIND, 401)
    throw failure
   }
  })
  assertFalse(DiagnosticLog.record(DiagnosticStage.WORK, DiagnosticResult.STARTED))
  val events = entries(DiagnosticLog.snapshotExport()).getValue("events.jsonl")
  assertFalse(events.contains("PRIVATE_SENTINEL"))
  assertContains(events, "\"httpStatus\":401")
  assertContains(events, "\"stage\":\"FINISHED\",\"result\":\"FAILED\"")
  val ids = Regex("\\\"operationId\\\":\\\"([^\\\"]+)\\\"").findAll(events).map { it.groupValues[1] }.toSet()
  assertEquals(1, ids.size)
 }

 @Test fun resultBasedOperationsLogRejectedOutcomesAndReturnTheOriginalValue() {
  val value = diagnosticWork(DiagnosticAction.INSPECT, result = { DiagnosticResult.REJECTED }) { 42 }
  assertEquals(42, value)
  assertContains(entries(DiagnosticLog.snapshotExport()).getValue("events.jsonl"), "\"stage\":\"FINISHED\",\"result\":\"REJECTED\"")
 }

 @Test fun unavailableDiagnosticStorageNeverChangesBusinessResultsOrTheLegacyRecorder() {
  val blocker = directory.resolve("storage-blocker")
  Files.writeString(blocker, "keep original")
  DiagnosticLog.configure(blocker, "development", "linux")
  assertEquals(42, diagnosticWork(DiagnosticAction.INSPECT) { 42 })
  val legacy = directory.resolve("legacy-events.log")
  val trace = OperationEvents(legacy).begin(OperationAction.PUSH)
  assertTrue(trace.record(OperationEvent.WORK_SUCCEEDED))
  assertTrue(Files.readString(legacy).contains("event=WORK_SUCCEEDED"))
  assertEquals("keep original", Files.readString(blocker))
 }

 @Test fun invalidExportDestinationFailsSafelyWithoutOverwritingTheSelection() = runBlocking {
  val selected = directory.resolve("existing-file")
  Files.writeString(selected, "keep original")
  val exporter = DesktopDiagnosticExport(choose = { _, _ -> selected.toString() })
  assertEquals("导出失败，请检查目录权限和剩余空间。", assertIs<DiagnosticExportResult.Failed>(exporter.export("initial")).message)
  assertEquals("keep original", Files.readString(selected))
 }

 private fun entries(bytes: ByteArray): Map<String, String> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
  buildMap {
   while (true) {
    val entry = zip.nextEntry ?: break
    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
    zip.closeEntry()
   }
  }
 }
}
