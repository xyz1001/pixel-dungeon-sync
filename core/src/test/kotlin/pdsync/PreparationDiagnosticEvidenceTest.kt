package pdsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlin.test.*

class PreparationDiagnosticEvidenceTest {
 @TempDir lateinit var directory: Path
 private val sha = "40c9eea94b545704c6a0edbdfd1a3eb01eaffc3a1d62270adf5ef517ee11e167"
 private fun actualFailure() = linkedMapOf(
  "source" to "DOCUMENT_PICKER", "stage" to "SOURCE_SIGNATURE_SET", "api" to "36",
  "app_version_code" to "45", "game_package" to "com.shatteredpixel.shatteredpixeldungeon",
  "game_version_code" to "896", "apk_count" to "1",
  "input.0.bytes" to "29962302", "input.0.sha256" to sha, "input.0.eof" to "true",
  "input.0.copy_ms" to "140", "input.0.cache_bytes" to "29962302", "input.0.cache_sha256" to sha,
  "input.0.cache_match" to "true", "input.0.metadata_status" to "PARSED",
  "input.0.package" to "com.shatteredpixel.shatteredpixeldungeon", "input.0.version_code" to "896",
  "input.0.min_sdk" to "21", "input.0.target_sdk" to "36",
  "verifier.api" to "36", "verifier.elapsed_ms" to "4",
  "verifier.schemes" to "v1=false,v2=false,v3=false,v31=false,v4=false",
  "verifier.issue.0" to "NO_SIG_FOR_TARGET_SANDBOX_VERSION", "verifier.issue.1" to "JAR_SIG_NO_MANIFEST",
  "verifier.parameter.0" to "2", "verifier.parameter.1" to "META-INF/MANIFEST.MF",
  "cause.0.class" to "io.github.xyz1001.pdsync.android.prepare.PreparationDiagnostics\$VerificationFailure",
  "cause.0.reason" to "APK_VERIFIER_REJECTED",
  "cause.0.frame.0" to "io.github.xyz1001.pdsync.android.prepare.LocalApkSigner.sourceCertificates:216"
 )
 @Test fun actualCode45FailureRetainsCopyProofVerifierIssuesAndCodeLocationThroughExport() {
  DiagnosticLog.configureForTests(directory, "0.5.24-test+45", "android",
   Clock.fixed(Instant.parse("2026-10-15T22:52:16Z"), ZoneOffset.UTC))
  val evidence = actualFailure()
  val details = DiagnosticDetails.builder().failureSource("PREPARATION").preparationEvidence(evidence).build()
  assertEquals(evidence, PreparationDiagnosticEvidence.decode(details.values.getValue(DiagnosticDetails.Field.PREPARATION)))
  val trace = DiagnosticLog.begin(DiagnosticAction.PREPARE, false)
  for (stage in listOf(DiagnosticStage.PREPARATION, DiagnosticStage.WORK, DiagnosticStage.FINISHED))
   assertTrue(trace.record(stage, DiagnosticResult.FAILED, DiagnosticFailure.VALIDATION, details))
  val archive = export()
  val text = archive.getValue("应用.log")
  for ((key, value) in evidence) assertContains(text, "prep.$key=$value")
  assertEquals(3, text.lineSequence().count { "error=VALIDATION" in it })
  assertContains(archive.getValue("events.jsonl"), sha)
  assertFalse("MESSAGE_OMITTED" in text)
 }
 @Test fun rawMessagesUrisCredentialsAndUnboundedKeysAreDropped() {
  val bad = mapOf(
   "message" to "password=secret", "source_uri" to "content://private/file",
   "input.0.package" to "https://user:secret@host/path", "input.0.sha256" to "SECRET",
   "input.16.bytes" to "10", "input.00.bytes" to "1", "input.0.bytes" to "-1",
   "cause.0.reason" to "SECRET", "cause.8.reason" to "APK_VERIFIER_REJECTED",
   "cause.0.class" to "secret.credentials", "cause.0.frame.0" to "java.io.File:/private/path",
   "verifier.parameter.0" to "Authorization: Bearer SECRET", "verifier.parameter.1" to "/data/user/0/private",
   "verifier.parameter.2" to "META-INF/../SECRET", "verifier.parameter.3" to "assets/password=secret",
   "verifier.issue.0" to "PRIVATE_SECRET", "verifier.api" to "036", "verifier.schemes" to "secret"
  )
  assertNull(PreparationDiagnosticEvidence.encode(bad))
  assertTrue(DiagnosticDetails.builder().preparationEvidence(bad).build().isEmpty())
  val raw = Json.encodeToString(mapOf("preparation" to Json.encodeToString(bad)))
  assertFails { Json.decodeFromString<DiagnosticDetails>(raw) }
 }
 @Test fun metadataFailurePreservesPartialCopyFactsWithoutPretendingMetadataExists() {
  val data = mapOf("stage" to "EXTRACTION", "input.0.bytes" to "1", "input.0.eof" to "false",
   "input.0.metadata_status" to "FAILED", "input.0.version_code" to "-1")
  val decoded = PreparationDiagnosticEvidence.decode(PreparationDiagnosticEvidence.encode(data)!!)!!
  assertEquals("FAILED", decoded["input.0.metadata_status"])
  assertEquals("false", decoded["input.0.eof"])
  assertNull(decoded["input.0.version_code"])
 }
 @Test fun boundedLargeFailureIsRecordedRatherThanSilentlyDropped() {
  val evidence = actualFailure()
  repeat(16) { i -> actualFailure().filterKeys { it.startsWith("input.0.") }.forEach { (k, v) -> evidence[k.replace("input.0.", "input.$i.")] = v } }
  repeat(8) { c ->
   evidence["cause.$c.class"] = "java.io.IOException"
   evidence["cause.$c.reason"] = "RECEIPT_OR_SOURCE_BINDING_INVALID"
   repeat(6) { f -> evidence["cause.$c.frame.$f"] = "io.github.xyz1001.pdsync.android.prepare.GamePreparationService.loadPending:123" }
  }
  repeat(32) { evidence["verifier.parameter.$it"] = "META-INF/" + "A".repeat(80) }
  val details = DiagnosticDetails.builder().preparationEvidence(evidence).build()
  assertTrue(details.isValid())
  DiagnosticLog.configureForTests(directory, "0.5.25-test+46", "android", Clock.systemUTC())
  assertTrue(DiagnosticLog.begin(DiagnosticAction.PREPARE, false).record(DiagnosticStage.FINISHED,
   DiagnosticResult.FAILED, DiagnosticFailure.VALIDATION, details))
  assertContains(export().getValue("应用.log"), "prep.cause.7.reason=RECEIPT_OR_SOURCE_BINDING_INVALID")
  val before = Files.list(directory.resolve("diagnostics-v1")).use { it.toList() }.sumOf(Files::size)
  assertTrue(before <= DiagnosticLog.MAX_TOTAL_BYTES)
 }
 @Test fun oldClosedDetailsStillDecodeAndNewEvidenceRoundTrips() {
  val old = Json.decodeFromString<DiagnosticDetails>("{\"mode\":\"ROOT_DIRECT\",\"errno\":\"2\"}")
  assertEquals("2", old.values[DiagnosticDetails.Field.ERRNO])
  val current = DiagnosticDetails.builder().preparationEvidence(actualFailure()).build()
  assertEquals(current, Json.decodeFromString<DiagnosticDetails>(Json.encodeToString(current)))
 }
 @Test fun installerTimingAndCacheReasonsAreBoundedAndNotFreeText() {
  val facts = DiagnosticDetails.builder().installationPhase("PREPARE_VALIDATION").installationCache("CACHE_HIT")
   .installationElapsedMs(27).build()
  assertTrue(facts.isValid())
  assertEquals("27", facts.values[DiagnosticDetails.Field.INSTALLATION_ELAPSED_MS])
  assertEquals(facts, Json.decodeFromString<DiagnosticDetails>(Json.encodeToString(facts)))
  val bad = DiagnosticDetails.builder().installationPhase("PRIVATE_PATH").installationCache("password=secret")
   .installationElapsedMs(-1).build()
  assertTrue(bad.values.values.all { it == "UNKNOWN" })
 }
 @Test fun confirmationPhasesAreClosedSafeEvidence() {
  for (phase in listOf("CONFIRMATION_RETAIN", "CONFIRMATION_LAUNCH", "CONFIRMATION_DEPARTURE", "CONFIRMATION_RETURN")) {
   val facts = DiagnosticDetails.builder().installationPhase(phase).build()
   assertTrue(facts.isValid())
   assertEquals(phase, facts.values[DiagnosticDetails.Field.INSTALLATION_PHASE])
   assertEquals(facts, Json.decodeFromString<DiagnosticDetails>(Json.encodeToString(facts)))
  }
 }
 @Test fun allActualPreparationReasonCodesRemainRepresentable() {
  val source = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.map {
   it.resolve("android-app/src/main/java/io/github/xyz1001/pdsync/android/prepare/PreparationDiagnostics.java")
  }.first(Files::isRegularFile)
  val reasonSource = Files.readString(source).substringAfter("private static String reason(").substringBefore("private static void appendCryptoHints")
  val reasons = Regex("\"([A-Z][A-Z0-9_]+)\"").findAll(reasonSource).map { it.groupValues[1] }.toSet()
  for(reason in reasons) assertNotNull(PreparationDiagnosticEvidence.encode(mapOf("cause.0.reason" to reason)),reason)
 }
 private fun export(): Map<String, String> = ZipInputStream(ByteArrayInputStream(DiagnosticLog.snapshotExport())).use { zip ->
  buildMap { while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes().toString(Charsets.UTF_8)) } }
 }
}
