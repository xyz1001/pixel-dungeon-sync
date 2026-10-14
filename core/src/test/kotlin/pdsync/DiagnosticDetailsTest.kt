package pdsync

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.nio.file.StandardOpenOption
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import pdsync.DiagnosticDetails.Field

class DiagnosticDetailsTest {
 @TempDir lateinit var directory: Path
 private val codec = Json { encodeDefaults = true }
 private val secret = "SECRET https://private.invalid/path user@example.invalid package.name save.dat password=private\nraw exception"
 private fun roundTrip(details: DiagnosticDetails): DiagnosticDetails = codec.decodeFromString(codec.encodeToString(details))

 @Test fun JavaFriendlyBuilderKeepsClosedEvidenceAndSerializesWithoutAWrapper() {
  val details = DiagnosticDetails.builder().mode("ROOT_DIRECT").readinessCode("ROOT_SAVE_NOT_READY")
   .access("FIRST_LAUNCH_NEEDED").gamePackageStatus("VERIFIED").helperPackageStatus("NOT_APPLICABLE")
   .identityVerified(true).systemRequestActive(false).restorePending(false)
   .saveLayout("EMPTY_DIRECTORY", 0, 2, 0, false, true)
   .rootCommand("INSPECT").rootStage("LAYOUT_SCAN").rootCategory("MISSING").errno(2).uidState(20)
   .observationState("ABSENT").observationReliable(true).fileCount(0).contentBytes(0)
   .networkTarget(DiagnosticDetails.NetworkTarget.DAV).requestDurationMs(123)
   .bridgeStage("SAVE_READINESS").failureSource(DiagnosticDetails.FailureSource.ROOT)
   .syncCompare("LOCAL_ONLY").syncDecision("LOCAL_ONLY").syncBlockReason("FIRST_LAUNCH_NEEDED")
   .localFileCount(0).remoteFileCount(4).gameVersionCode(50).saveVersion(902).build()
  assertTrue(details.isValid())
  assertEquals(details, roundTrip(details))
  val encoded = codec.encodeToString(details)
  assertTrue(encoded.startsWith("{\"mode\":\"ROOT_DIRECT\""))
  assertTrue("\"readinessCode\":\"ROOT_SAVE_NOT_READY\"" in encoded)
  assertFalse("values" in encoded)
  assertTrue(encoded.toByteArray().size < 1400, "Closed details must fit the bounded record alongside its envelope")
 }

 @Test fun unknownTokensNeverBecomeExportableFreeTextAndAbsentEvidenceStaysAbsent() {
  val details = DiagnosticDetails.builder().mode(secret).readinessCode(secret).access(secret)
   .gamePackageStatus(secret).helperPackageStatus(secret).saveLayoutState(secret)
   .rootCommand(secret).rootStage(secret).rootCategory(secret).bridgeStage(secret).failureSource(secret)
   .observationState(secret).syncCompare(secret).syncDecision(secret).syncBlockReason(secret).build()
  assertTrue(details.values.values.all { it == "UNKNOWN" })
  assertFalse("SECRET" in codec.encodeToString(details))
  assertFalse("SECRET" in details.toString())
  assertEquals(details, roundTrip(details))
  val absent = DiagnosticDetails.builder().mode(null as String?).readinessCode(null).access(null).build()
  assertTrue(absent.isEmpty())
  assertEquals("{}", codec.encodeToString(absent))
 }

 @Test fun buildersCannotMutateAlreadyBuiltEvidenceAndExposedValuesAreUnmodifiable() {
  val builder = DiagnosticDetails.builder().readinessCode("OK")
  val first = builder.build()
  builder.readinessCode("ROOT_UNAVAILABLE")
  assertEquals("OK", first.values[Field.READINESS_CODE])
  assertEquals("ROOT_UNAVAILABLE", builder.build().values[Field.READINESS_CODE])
  assertFailsWith<UnsupportedOperationException> {
   @Suppress("UNCHECKED_CAST")
   (first.values as MutableMap<Field, String>)[Field.READINESS_CODE] = secret
  }
  assertEquals("OK", first.values[Field.READINESS_CODE])
 }

 @Test fun boundedIntegersPreserveZeroAndUpperBoundsWhileOmittingInvalidNumbers() {
  val bounds = mapOf(
   Field.ERRNO to 4095L, Field.UID_STATE to 20L,
   Field.SAVE_LAYOUT_INCLUDED to DiagnosticDetails.MAX_FILE_COUNT,
   Field.SAVE_LAYOUT_EXCLUDED to DiagnosticDetails.MAX_FILE_COUNT,
   Field.SAVE_LAYOUT_UNRECOGNIZED to DiagnosticDetails.MAX_FILE_COUNT,
   Field.FILE_COUNT to DiagnosticDetails.MAX_FILE_COUNT,
   Field.LOCAL_FILE_COUNT to DiagnosticDetails.MAX_FILE_COUNT,
   Field.REMOTE_FILE_COUNT to DiagnosticDetails.MAX_FILE_COUNT,
   Field.CONTENT_BYTES to DiagnosticDetails.MAX_CONTENT_BYTES,
   Field.REQUEST_DURATION_MS to DiagnosticDetails.MAX_REQUEST_DURATION_MS,
   Field.GAME_VERSION_CODE to Long.MAX_VALUE, Field.SAVE_VERSION to Int.MAX_VALUE.toLong()
  )
  for ((field, max) in bounds) {
   for (value in listOf(0L, max)) {
    val details = DiagnosticDetails.builder().number(field, value).build()
    assertEquals(value.toString(), details.values[field], "$field=$value")
    assertEquals(details, roundTrip(details))
   }
   assertTrue(DiagnosticDetails.builder().number(field, -1).build().isEmpty(), field.name)
   if (max < Long.MAX_VALUE) assertTrue(DiagnosticDetails.builder().number(field, max + 1).build().isEmpty(), field.name)
  }
  assertTrue(DiagnosticDetails.builder().number(Field.READINESS_CODE, 123).flag(Field.READINESS_CODE, true).build().isEmpty())
  assertTrue(DiagnosticDetails.builder().flag(Field.UID_STATE, true).build().isEmpty())
 }

 @Test fun excessiveLayoutTotalsCannotBeRepresentedAsValidCounts() {
  val details = DiagnosticDetails.builder().saveLayout("ALLOWLISTED_FILES_PRESENT", 16384, 1, 0, true, true).build()
  assertEquals("false", details.values[Field.SAVE_LAYOUT_VALID])
  assertNull(details.values[Field.SAVE_LAYOUT_INCLUDED])
  assertNull(details.values[Field.SAVE_LAYOUT_EXCLUDED])
  assertTrue(details.isValid())
  val invalid = DiagnosticDetails.builder().saveLayout("UNKNOWN", -1, -1, -1, true, false).build()
  assertEquals("false", invalid.values[Field.SAVE_LAYOUT_VALID])
  assertNull(invalid.values[Field.SAVE_LAYOUT_INCLUDED])
  assertEquals(invalid, roundTrip(invalid))
 }

 @Test fun decoderRejectsUnknownFieldsUntrustedTokensNonCanonicalNumbersAndOutOfRangeValues() {
  val malicious = listOf(
   "{\"path\":\"$secret\"}", "{\"readinessCode\":\"PRIVATE_CODE\"}",
   "{\"networkTarget\":\"https://private.invalid\"}", "{\"rootStage\":\"PRIVATE_STAGE\"}",
   "{\"access\":\"PRIVATE_STATE\"}", "{\"gamePackageStatus\":\"package.name\"}",
   "{\"syncCompare\":\"SAVE_CONTENTS\"}", "{\"syncBlockReason\":\"private message\"}",
   "{\"identityVerified\":\"TRUE\"}", "{\"restorePending\":\"1\"}",
   "{\"errno\":\"4096\"}", "{\"errno\":\"-1\"}", "{\"uidState\":\"21\"}",
   "{\"fileCount\":\"16385\"}", "{\"fileCount\":\"01\"}", "{\"fileCount\":\"+1\"}",
   "{\"fileCount\":\"1e2\"}", "{\"fileCount\":\"1.0\"}",
   "{\"requestDurationMs\":\"604800001\"}", "{\"saveVersion\":\"2147483648\"}",
   "{\"gameVersionCode\":\"9223372036854775808\"}",
   "{\"saveLayoutIncluded\":\"16384\",\"saveLayoutExcluded\":\"1\"}"
  )
  for (json in malicious) assertFails("Decoder must reject: $json") { codec.decodeFromString<DiagnosticDetails>(json) }
 }

 @Test fun androidReadinessRootAndPackageAllowlistsStayInSyncWithTheirSourceOfTruth() {
  val bridge = source("android-app/src/main/java/io/github/xyz1001/pdsync/android/BridgeDiagnostics.java")
  val codes = Regex("CODES = (.*?);").find(bridge)!!.groupValues[1]
  for (match in Regex("\"([A-Z_]+)\"").findAll(codes)) {
   val code = match.groupValues[1]
   assertEquals(code, DiagnosticDetails.builder().readinessCode(code).build().values[Field.READINESS_CODE])
  }
  fun enumValues(text: String, name: String): List<String> = Regex("enum $name \\{([^}]+)}")
   .find(text)!!.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
  for (stage in enumValues(bridge, "RootStage"))
   assertEquals(stage, DiagnosticDetails.builder().rootStage(stage).build().values[Field.ROOT_STAGE])
  for (category in enumValues(bridge, "RootCategory"))
   assertEquals(category, DiagnosticDetails.builder().rootCategory(category).build().values[Field.ROOT_CATEGORY])
  for (stage in enumValues(bridge, "Stage"))
   assertEquals(stage, DiagnosticDetails.builder().bridgeStage(stage).build().values[Field.BRIDGE_STAGE])
  val rootProtocol = source("android-root/src/main/java/io/github/xyz1001/pdsync/root/RootProtocol.java")
  for (command in Regex("enum Command \\{([^;]+);").find(rootProtocol)!!.groupValues[1]
   .split(',').map { it.trim().substringBefore('(') })
   assertEquals(command, DiagnosticDetails.builder().rootCommand(command).build().values[Field.ROOT_COMMAND])
  val readiness = source("android-app/src/main/java/io/github/xyz1001/pdsync/android/InstallationReadiness.java")
  for (state in enumValues(readiness, "AccessStatus"))
   assertEquals(state, DiagnosticDetails.builder().access(state).build().values[Field.ACCESS])
  for (state in enumValues(readiness, "PackageStatus"))
   assertEquals(state, DiagnosticDetails.builder().gamePackageStatus(state).build().values[Field.GAME_PACKAGE_STATUS])
 }

 @Test fun everyComparisonAndBlockReasonRetainsItsSpecificClosedValue() {
  for (kind in OnboardingKind.entries) {
   val details = DiagnosticDetails.builder().syncCompare(kind.name).syncDecision(kind.name).build()
   assertEquals(kind.name, details.values[Field.SYNC_COMPARE])
   assertEquals(kind.name, details.values[Field.SYNC_DECISION])
  }
  for (reason in SyncBlockReason.entries)
   assertEquals(reason.name, DiagnosticDetails.builder().syncBlockReason(reason.name).build().values[Field.SYNC_BLOCK_REASON])
  assertEquals("ROOT_DIRECT", DiagnosticDetails.builder().mode("ROOT").build().values[Field.MODE])
  assertEquals("EMBEDDED_BRIDGE", DiagnosticDetails.builder().mode("EMBEDDED").build().values[Field.MODE])
  assertEquals("LEGACY_HELPER_BRIDGE", DiagnosticDetails.builder().mode("LEGACY_HELPER").build().values[Field.MODE])
 }

 @Test fun actualLogExportPreservesAllDetailsWithinTheExistingRecordBudget() {
  DiagnosticLog.configureForTests(directory,
   "999999.999999.999999.999999-release.999999-debug.999999-alpha.999999-beta.999999+999999999",
   "android", Clock.fixed(Instant.parse("2026-10-14T23:59:59Z"), ZoneId.of("America/Argentina/ComodRivadavia")))
  val details = DiagnosticDetails.builder().mode("LEGACY_HELPER_BRIDGE")
   .readinessCode("ROOT_RECOVERY_COMMITTED_CLEANUP_PENDING").access("CAPABILITY_UNAVAILABLE")
   .gamePackageStatus("COMPATIBLE_UPDATE").helperPackageStatus("REQUIRES_UNINSTALL")
   .identityVerified(false).systemRequestActive(false).restorePending(false)
   .saveLayout("NO_TRANSFERABLE_FILES", 16384, 0, 0, false, false)
   .rootCommand("RESTORE").rootStage("KERNEL_LSTAT_DATA_PACKAGE").rootCategory("CROSS_DEVICE").errno(4095).uidState(20)
   .observationState("FOREGROUND").observationReliable(false).fileCount(16384)
   .contentBytes(DiagnosticDetails.MAX_CONTENT_BYTES)
   .networkTarget(DiagnosticDetails.NetworkTarget.OFFICIAL_AUTH)
   .requestDurationMs(DiagnosticDetails.MAX_REQUEST_DURATION_MS)
   .bridgeStage("HELPER_ACTIVATION").failureSource("HELPER_ACTIVATION")
   .syncCompare("REMOTE_ONLY").syncDecision("REMOTE_ONLY").syncBlockReason("RESTORE_RECOVERY_REQUIRED")
   .localFileCount(16384).remoteFileCount(16384).gameVersionCode(Long.MAX_VALUE).saveVersion(Int.MAX_VALUE).build()
  val trace = DiagnosticLog.begin(DiagnosticAction.AUTOMATIC_SYNC, true)
  assertTrue(trace.record(DiagnosticStage.CONFIRMATION, DiagnosticResult.REJECTED,
   DiagnosticFailure.CAPABILITY_UNAVAILABLE, details), "Largest realistic details must not silently drop the record")
  val archive = exported()
  val line = archive.getValue("events.jsonl").lineSequence().filter { it.isNotBlank() }.last()
  assertTrue(line.toByteArray().size + 1 <= 2048, "Existing 2048-byte record budget should remain sufficient")
  val restored = codec.decodeFromJsonElement<DiagnosticDetails>(Json.parseToJsonElement(line).jsonObject.getValue("details"))
  assertEquals(details, restored)
  val readable = archive.getValue("应用.log")
  assertTrue("READINESS_CODE=ROOT_RECOVERY_COMMITTED_CLEANUP_PENDING" in readable)
  assertTrue("GAME_VERSION_CODE=${Long.MAX_VALUE}" in readable)
  assertTrue("SAVE_LAYOUT_INCLUDED=16384" in readable)
 }

 @Test fun exportRejectsTamperedDetailValuesUnknownFieldsAndDuplicateNestedKeys() {
  DiagnosticLog.configureForTests(directory, "0.5.15-root-test+36", "android",
   Clock.fixed(Instant.parse("2026-10-14T23:59:59Z"), ZoneId.of("UTC")))
  val trace = DiagnosticLog.begin(DiagnosticAction.ACCESS_CHECK, false)
  assertTrue(trace.record(DiagnosticStage.READINESS, DiagnosticResult.PENDING, DiagnosticFailure.NOT_CHECKED,
   DiagnosticDetails.builder().readinessCode("NOT_CHECKED").build()))
  val log = Files.list(directory.resolve("diagnostics-v1")).use { it.toList().single() }
  val line = Files.readAllLines(log).last()
  val parsed = Json.parseToJsonElement(line).jsonObject
  val attacks = listOf(
   buildJsonObject { put("readinessCode", "SECRET_PRIVATE_FAILURE") },
   buildJsonObject { put("privatePath", "/SECRET/private/save.dat") },
   buildJsonObject { put("uidState", "99999") },
   buildJsonObject { put("saveLayoutIncluded", "16385") }
  ).map { badDetails -> JsonObject(parsed + ("details" to badDetails)).toString() } +
   line.replace("\"readinessCode\":\"NOT_CHECKED\"", "\"readinessCode\":\"SECRET\",\"readinessCode\":\"NOT_CHECKED\"")
  Files.writeString(log, attacks.joinToString("\n", postfix = "\n"), StandardOpenOption.APPEND)
  val archive = exported()
  assertEquals(2, archive.getValue("events.jsonl").lineSequence().count { it.isNotBlank() })
  assertFalse("SECRET" in archive.values.joinToString())
 }

 private fun exported(): Map<String, String> = ZipInputStream(ByteArrayInputStream(DiagnosticLog.snapshotExport())).use { zip ->
  buildMap {
   while (true) {
    val entry = zip.nextEntry ?: break
    put(entry.name, zip.readBytes().decodeToString())
   }
  }
 }

 private fun source(relative: String): String {
  val file = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
   .map { it.resolve(relative) }.firstOrNull { Files.isRegularFile(it) }
  assertNotNull(file, "Repository source needed for diagnostic allowlist parity: $relative")
  return Files.readString(file)
 }
}
