package pdsync

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlin.test.*
import pdsync.HomeRestoreDiagnosticEvidence.Gate

class HomeRestoreDiagnosticEvidenceTest {
 @TempDir lateinit var directory: Path
 private val commitId = "12345678-1234-4567-8123-123456789abc"
 private val a = "a".repeat(64)
 private val b = "b".repeat(64)
 private fun passing() = Gate.entries.associateWith { true }
 private fun capture(current: Map<String, String>? = mapOf("game1/game.dat" to a),
                     target: Map<String, String>? = mapOf("game1/game.dat" to b),
                     gates: Map<Gate, Boolean> = passing(), tip: Boolean = false,
                     id: String? = commitId) = HomeRestoreDiagnosticEvidence.capture(current, target, id, tip, gates)
 private fun details(evidence: HomeRestoreDiagnosticEvidence) = DiagnosticDetails.builder().homeRestoreEvidence(evidence).build()

 @Test fun hashesCompleteCanonicalMapsIndependentlyOfInsertionOrderWithoutRawPathsOrIds() {
  val current = linkedMapOf("game2/game.dat" to a, "game1/game.dat" to b)
  val target = linkedMapOf("game1/game.dat" to b, "game2/game.dat" to a)
  val evidence = capture(current, target)
  val facts = evidence.values
  assertEquals(contentSha256(current), facts["current.filemap_sha256"])
  assertEquals(contentSha256(target), facts["target.filemap_sha256"])
  assertEquals("2", facts["current.file_count"])
  assertEquals("2", facts["target.file_count"])
  assertEquals("true", facts["filemaps_equal"])
  assertEquals(hash(commitId.toByteArray()), facts["selected.commit_sha256"])
  assertEquals("false", facts["selected.tip"])
  val encoded = Json.encodeToString(details(evidence))
  for (raw in listOf("game1/game.dat", "game2/game.dat", commitId, a, b)) assertFalse(raw in encoded)
  assertTrue(details(evidence).isValid())
  assertEquals(details(evidence), Json.decodeFromString<DiagnosticDetails>(encoded))
 }

 @Test fun sameCountDifferentLastFileChangesFullMapHashAndEquality() {
  val current = (1..MAX_SNAPSHOT_FILES).associate { "part-$it.dat" to a }
  val target = current + ("part-${MAX_SNAPSHOT_FILES}.dat" to b)
  val facts = capture(current, target).values
  assertEquals(MAX_SNAPSHOT_FILES.toString(), facts["current.file_count"])
  assertEquals(contentSha256(current), facts["current.filemap_sha256"])
  assertEquals(contentSha256(target), facts["target.filemap_sha256"])
  assertNotEquals(facts["current.filemap_sha256"], facts["target.filemap_sha256"])
  assertEquals("false", facts["filemaps_equal"])
  assertTrue(HomeRestoreDiagnosticEvidence.encode(capture(current, target)).toByteArray().size < HomeRestoreDiagnosticEvidence.MAX_BYTES)
 }

 @Test fun emptyMapsAreRealCompleteMapsWhileMissingOrInvalidMapsRemainUnknown() {
  val empty = capture(emptyMap(), emptyMap()).values
  assertEquals("AVAILABLE", empty["current.state"])
  assertEquals("0", empty["current.file_count"])
  assertEquals(contentSha256(emptyMap()), empty["current.filemap_sha256"])
  assertEquals("true", empty["filemaps_equal"])
  val missing = capture(null, emptyMap()).values
  assertEquals("ABSENT", missing["current.state"])
  assertNull(missing["current.filemap_sha256"])
  assertNull(missing["current.file_count"])
  assertNull(missing["filemaps_equal"])
  val badMaps = listOf(mapOf("../secret" to a), mapOf("game.dat" to "PRIVATE_SECRET"),
   mapOf("game.dat" to a, "GAME.DAT" to b), mapOf(DIRECTORY_METADATA_FILE to a),
   (0..MAX_SNAPSHOT_FILES).associate { "part-$it.dat" to a })
  for (bad in badMaps) {
   val evidence = capture(bad, bad, id = "https://user:password@private.invalid")
   assertEquals("INVALID", evidence.values["current.state"])
   assertEquals("INVALID", evidence.values["target.state"])
   assertNull(evidence.values["current.filemap_sha256"])
   assertNull(evidence.values["filemaps_equal"])
   assertNull(evidence.values["selected.commit_sha256"])
   assertFalse("PRIVATE_SECRET" in Json.encodeToString(details(evidence)))
   assertTrue(details(evidence).isValid())
  }
 }

 @Test fun everyConditionHasSpecificReasonAndFixedOrderRegardlessOfCallerMapOrder() {
  for (gate in Gate.entries.filter { it != Gate.RESTORE_ALLOWED }) {
   val gates = passing() + (gate to false) + (Gate.RESTORE_ALLOWED to false)
   val facts = capture(gates = gates.entries.reversed().associate { it.toPair() }).values
   assertEquals(gate.name, facts["disabled_reason"])
   assertEquals("false", facts["gate.${gate.name}"])
   assertEquals("false", facts["gate.RESTORE_ALLOWED"])
  }
  val facts = capture(gates = passing() + (Gate.CONTENT_NEEDED to false) + (Gate.ACCOUNT_ACCESS to false)).values
  assertEquals("ACCOUNT_ACCESS", facts["disabled_reason"])
  assertEquals("NONE", capture(gates = passing() + (Gate.RESTORE_ALLOWED to false)).values["disabled_reason"])
  assertEquals("UNKNOWN", capture(gates = passing() - Gate.ACCOUNT_ACCESS).values["disabled_reason"])
  assertNull(capture(gates = emptyMap()).values["gate.ACCOUNT_ACCESS"])
 }

 @Test fun captureIsImmutableAndCannotBeUsedToAlterSuppliedGatesOrMaps() {
  val current = mutableMapOf("game.dat" to a)
  val gates = passing().toMutableMap()
  val evidence = capture(current, current, gates)
  val before = HomeRestoreDiagnosticEvidence.encode(evidence)
  current["game.dat"] = b
  gates[Gate.ACCOUNT_ACCESS] = false
  assertEquals(before, HomeRestoreDiagnosticEvidence.encode(evidence))
  assertFailsWith<UnsupportedOperationException> {
   (evidence.values as MutableMap<String, String>)["disabled_reason"] = "PRIVATE_SECRET"
  }
  assertEquals("NONE", evidence.values["disabled_reason"])
 }

 @Test fun decoderRejectsArbitraryKeysUnboundedValuesAndNoncanonicalFacts() {
  val good = capture().values
  val attacks = listOf(
   good + ("path" to "/PRIVATE_SECRET/save.dat"),
   good + ("current.filemap_sha256" to "PRIVATE_SECRET"),
   good + ("selected.commit_sha256" to commitId),
   good + ("current.file_count" to "01"),
   good + ("current.file_count" to "${MAX_SNAPSHOT_FILES + 1}"),
   good + ("current.file_count" to "-1"),
   good + ("gate.PRIVATE_SECRET" to "true"),
   good + ("gate.ACCOUNT_ACCESS" to "TRUE"),
   good + ("disabled_reason" to "PRIVATE_SECRET"),
   good + ("disabled_reason" to "ACCOUNT_ACCESS"),
   good + ("current.state" to "ABSENT"),
   good - "target.filemap_sha256",
   good - "filemaps_equal",
   good + ("selected.commit_sha256" to "a".repeat(HomeRestoreDiagnosticEvidence.MAX_BYTES))
  )
  for (bad in attacks) {
   val encoded = Json.encodeToString(bad)
   assertNull(HomeRestoreDiagnosticEvidence.decode(encoded))
   assertFails { Json.decodeFromString<DiagnosticDetails>(Json.encodeToString(mapOf("homeRestore" to encoded))) }
  }
  val encoded = HomeRestoreDiagnosticEvidence.encode(capture())
  assertNull(HomeRestoreDiagnosticEvidence.decode(encoded.replace("\"selected.tip\":\"false\"", "\"selected.tip\":\"true\",\"selected.tip\":\"false\"")))
  assertNull(HomeRestoreDiagnosticEvidence.decode(" $encoded"))
  assertTrue(DiagnosticDetails.builder().homeRestoreEvidence(capture()).homeRestoreEvidence(null).build().isEmpty())
  assertEquals("2", Json.decodeFromString<DiagnosticDetails>("{\"errno\":\"2\"}").values[DiagnosticDetails.Field.ERRNO])
 }

 @Test fun standaloneSelectionWritesOneEventAndReadableExportContainsAllClosedEvidence() {
  configure()
  val evidence = capture(gates = passing() + (Gate.CONTENT_NEEDED to false) + (Gate.RESTORE_ALLOWED to false))
  assertTrue(DiagnosticLog.recordStandalone(DiagnosticAction.HISTORY, DiagnosticStage.GUARD,
   DiagnosticResult.SUCCEEDED, DiagnosticFailure.NONE, details(evidence)))
  val archive = export()
  val lines = archive.getValue("events.jsonl").lineSequence().filter { it.isNotBlank() }.toList()
  assertEquals(1, lines.size)
  assertFalse("QUEUED" in lines.single())
  val parsed = Json.parseToJsonElement(lines.single()).jsonObject
  assertEquals("false", parsed.getValue("automatic").jsonPrimitive.content)
  assertEquals("HISTORY", parsed.getValue("action").jsonPrimitive.content)
  val readable = archive.getValue("应用.log")
  for ((key, fact) in evidence.values) assertContains(readable, "homeRestore.$key=$fact")
  assertFalse(commitId in archive.values.joinToString())
  assertFalse("game1/game.dat" in archive.values.joinToString())
 }

 @Test fun standaloneDoesNotStealBoundTraceAndExistingBeginStillQueues() {
  configure()
  val trace = DiagnosticLog.begin(DiagnosticAction.INSPECT, true)
  DiagnosticLog.bind(trace).use {
   assertTrue(DiagnosticLog.recordStandalone(DiagnosticAction.HISTORY, DiagnosticStage.GUARD,
    DiagnosticResult.SUCCEEDED, DiagnosticFailure.NONE, details(capture())))
   assertSame(trace, DiagnosticLog.currentTrace())
   assertTrue(DiagnosticLog.record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED))
  }
  val records = export().getValue("events.jsonl").lineSequence().filter { it.isNotBlank() }
   .map { Json.parseToJsonElement(it).jsonObject }.toList()
  assertEquals(3, records.size)
  assertEquals("QUEUED", records[0].getValue("stage").jsonPrimitive.content)
  assertEquals(trace.id, records[0].getValue("operationId").jsonPrimitive.content)
  assertNotEquals(trace.id, records[1].getValue("operationId").jsonPrimitive.content)
  assertEquals(trace.id, records[2].getValue("operationId").jsonPrimitive.content)
 }

 @Test fun exportRevalidatesAndRejectsTamperedHomeRestorePayload() {
  configure()
  assertTrue(DiagnosticLog.recordStandalone(DiagnosticAction.HISTORY, DiagnosticStage.GUARD,
   DiagnosticResult.SUCCEEDED, DiagnosticFailure.NONE, details(capture())))
  val log = Files.list(directory.resolve("diagnostics-v1")).use { it.toList().single() }
  val original = Files.readAllLines(log).single()
  val tampered = original.replace("filemaps_equal", "PRIVATE_SECRET")
  assertNotEquals(original, tampered)
  Files.writeString(log, "$tampered\n", StandardOpenOption.APPEND)
  val archive = export()
  assertEquals(1, archive.getValue("events.jsonl").lineSequence().count { it.isNotBlank() })
  assertFalse("PRIVATE_SECRET" in archive.values.joinToString())
 }

 private fun configure() = DiagnosticLog.configureForTests(directory, "0.5.77-test+98", "android",
  Clock.fixed(Instant.parse("2026-10-21T05:00:00Z"), ZoneOffset.UTC))
 private fun export(): Map<String, String> = ZipInputStream(ByteArrayInputStream(DiagnosticLog.snapshotExport())).use { zip ->
  buildMap { while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes().toString(Charsets.UTF_8)) } }
 }
}
