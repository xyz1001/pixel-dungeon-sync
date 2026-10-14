package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.test.*

class DiagnosticCoverageTest {
 @TempDir lateinit var directory: Path
 private fun events(): String = ZipInputStream(ByteArrayInputStream(DiagnosticLog.snapshotExport())).use { zip ->
  while (true) {
   val entry = zip.nextEntry ?: return@use ""
   if (entry.name == "events.jsonl") return@use zip.readBytes().toString(Charsets.UTF_8)
  }
  @Suppress("UNREACHABLE_CODE") ""
 }
 @Test fun automaticObservationFailuresAreTerminalAndDeduplicatedUntilRecovery() {
  DiagnosticLog.configure(directory, "development", "linux")
  val observer = DiagnosticObservationFailures()
  repeat(10) { observer.failed(AccessDeniedException("PRIVATE_SENTINEL")) }
  var text = events()
  assertEquals(1, text.lineSequence().count { it.contains("\"stage\":\"FINISHED\"") })
  assertContains(text, "\"automatic\":true")
  assertContains(text, "\"result\":\"FAILED\"")
  assertContains(text, "\"failure\":\"STORAGE\"")
  observer.recovered()
  observer.failed(AccessDeniedException("PRIVATE_SENTINEL"))
  text = events()
  assertEquals(2, text.lineSequence().count { it.contains("\"stage\":\"FINISHED\"") })
  assertFalse(text.contains("PRIVATE_SENTINEL"))
 }
 @Test fun conflictRemainsConflictAtWorkAndTerminalStages() {
  DiagnosticLog.configure(directory, "development", "linux")
  val trace = OperationEvents(directory.resolve("legacy.log")).begin(OperationAction.PUSH, automatic = true)
  trace.outcome(DiagnosticResult.CONFLICT)
  trace.record(OperationEvent.WORK_SUCCEEDED)
  trace.record(OperationEvent.OPERATION_FINISHED)
  val text = events()
  for (stage in listOf("VERIFY", "WORK", "FINISHED"))
   assertContains(text, "\"stage\":\"$stage\",\"result\":\"CONFLICT\"")
  assertEquals(DiagnosticResult.SUCCEEDED, diagnosticPublishedResult(Unit))
  val conflict = AppendPushResult.Diverged(AppendConflict(emptySet(), emptyList(), emptyMap()))
  assertEquals(DiagnosticResult.CONFLICT, diagnosticPublishedResult(conflict))
  assertEquals(DiagnosticResult.CONFLICT, diagnosticPublishedResult(OnboardingRestoreResult.Published(conflict)))
 }
 @Test fun previewReadFailureHasOneCorrelatedTerminalFailure() {
  DiagnosticLog.configure(directory, "development", "linux")
  assertFailsWith<AccessDeniedException> {
   diagnosticWork(DiagnosticAction.INSPECT) { throw AccessDeniedException("PRIVATE_SENTINEL") }
  }
  val text = events()
  assertContains(text, "\"action\":\"INSPECT\"")
  assertContains(text, "\"stage\":\"FINISHED\",\"result\":\"FAILED\"")
  assertFalse(text.contains("PRIVATE_SENTINEL"))
 }
}
