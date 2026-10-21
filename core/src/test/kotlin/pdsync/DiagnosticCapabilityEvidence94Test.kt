package pdsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import kotlin.test.*

class DiagnosticCapabilityEvidence94Test {
 @TempDir lateinit var directory:Path
 private fun events():List<JsonObject> = ZipInputStream(DiagnosticLog.snapshotExport().inputStream()).use { zip ->
  var result=""
  while(true) {val entry=zip.nextEntry?:break;if(entry.name=="events.jsonl")result=zip.readBytes().decodeToString()}
  result.lineSequence().filter {it.isNotBlank()}.map {Json.parseToJsonElement(it).jsonObject}.toList()
 }
 @Test fun accumulatesOnlyInMemoryUntilExistingFinishedAndKeepsOtherDetails() {
  DiagnosticLog.configure(directory,"0.5.73-test+94","android")
  val trace=DiagnosticLog.begin(DiagnosticAction.INSPECT,false)
  val file=Files.list(directory.resolve("diagnostics-v1")).use {it.findFirst().orElseThrow()}
  val queued=Files.readAllBytes(file)
  DiagnosticLog.bind(trace).use {
   DiagnosticCapabilities.reply(1,1,1,1)
   DiagnosticCapabilities.record(DiagnosticCapability.READ_SNAPSHOT,DiagnosticCapabilityState.SUPPORTED)
  }
  assertContentEquals(queued,Files.readAllBytes(file),"no per-capability or per-phase log write")
  trace.record(DiagnosticStage.WORK,DiagnosticResult.SUCCEEDED)
  assertNull(events().last().getValue("details").takeUnless {it==JsonNull},"no early capability flush")
  trace.record(DiagnosticStage.FINISHED,DiagnosticResult.SUCCEEDED,DiagnosticFailure.NONE,
   DiagnosticDetails.builder().mode(DiagnosticDetails.Mode.EMBEDDED_BRIDGE).build())
  val records=events();assertEquals(3,records.size)
  val details=records.last().getValue("details").jsonObject
  assertEquals("EMBEDDED_BRIDGE",details.getValue("mode").jsonPrimitive.content)
  assertEquals("SUPPORTED",details.getValue("helperReadSnapshotCapability").jsonPrimitive.content)
  for(key in listOf("helperAccessProtocolVersion","helperSinglePassReadVersion","helperReadSnapshotVersion","helperRestoreResultVersion"))
   assertEquals("1",details.getValue(key).jsonPrimitive.content)
 }
 @Test fun missingVersionsAreOmittedAndOldReplyVersionsAreReplaced() {
  val trace=DiagnosticTrace("test",DiagnosticAction.INSPECT,false)
  DiagnosticLog.bind(trace).use {
   DiagnosticCapabilities.reply(1,1,1,1)
   DiagnosticCapabilities.reply(1,-1,-1,2)
   DiagnosticCapabilities.record(DiagnosticCapability.READ_SNAPSHOT,DiagnosticCapabilityState.VERSION_MISSING)
   DiagnosticCapabilities.record(DiagnosticCapability.RESTORE_RESULT,DiagnosticCapabilityState.VERSION_UNSUPPORTED)
  }
  val values=trace.capabilities.snapshot().values
  assertEquals("1",values[DiagnosticDetails.Field.HELPER_ACCESS_PROTOCOL_VERSION])
  assertEquals("2",values[DiagnosticDetails.Field.HELPER_RESTORE_RESULT_VERSION])
  assertNull(values[DiagnosticDetails.Field.HELPER_READ_SNAPSHOT_VERSION])
  assertNull(values[DiagnosticDetails.Field.HELPER_SINGLE_PASS_READ_VERSION])
  assertEquals("VERSION_MISSING",values[DiagnosticDetails.Field.HELPER_READ_SNAPSHOT_CAPABILITY])
  assertEquals("VERSION_UNSUPPORTED",values[DiagnosticDetails.Field.HELPER_RESTORE_RESULT_CAPABILITY])
 }
 @Test fun capabilityEvidenceCannotIntroduceFreeTextOrOutOfRangeVersions() {
  val details=DiagnosticDetails.builder().helperCapability(DiagnosticCapability.READ_SNAPSHOT,DiagnosticCapabilityState.AUTHENTICATE_UNSUPPORTED)
   .helperCapabilityVersions(-1,0,Int.MAX_VALUE,-1).build()
  assertTrue(details.isValid())
  val json=Json.encodeToString(details)
  assertEquals(details,Json.decodeFromString<DiagnosticDetails>(json))
  assertFails {Json.decodeFromString<DiagnosticDetails>(json.replace("AUTHENTICATE_UNSUPPORTED","/private/password"))}
  assertFails {Json.decodeFromString<DiagnosticDetails>(json.replace("2147483647","2147483648"))}
  assertFails {Json.decodeFromString<DiagnosticDetails>(json.replace("2147483647","001"))}
 }
 @Test fun capabilitiesStayOnTheirBoundTraceAndAreAbsentWithoutAnObservation() {
  val outer=DiagnosticTrace("outer",DiagnosticAction.INSPECT,false)
  val inner=DiagnosticTrace("inner",DiagnosticAction.RESTORE,false)
  DiagnosticLog.bind(outer).use {
   DiagnosticCapabilities.record(DiagnosticCapability.READ_SNAPSHOT,DiagnosticCapabilityState.NOT_EMBEDDED)
   DiagnosticLog.bind(inner).use {DiagnosticCapabilities.record(DiagnosticCapability.RESTORE_RESULT,DiagnosticCapabilityState.FAILED)}
  }
  DiagnosticCapabilities.reply(9,9,9,9)
  assertEquals(setOf(DiagnosticDetails.Field.HELPER_READ_SNAPSHOT_CAPABILITY),outer.capabilities.snapshot().values.keys)
  assertEquals(setOf(DiagnosticDetails.Field.HELPER_RESTORE_RESULT_CAPABILITY),inner.capabilities.snapshot().values.keys)
  assertTrue(DiagnosticTrace("empty",DiagnosticAction.INSPECT,false).capabilities.snapshot().isEmpty())
 }

}
