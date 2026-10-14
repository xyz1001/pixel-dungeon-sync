package pdsync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.test.*

class DiagnosticEnvironmentTest {
 @TempDir lateinit var directory: Path
 private val codec = Json { encodeDefaults = true }
 @AfterEach fun reset() { DiagnosticEnvironment.resetForTests() }

 @Test fun androidExportsOnlyTheExplicitEnvironmentSchema() {
  DiagnosticEnvironment.configureAndroid("16", 36, "Google", "Pixel 9 Pro", arrayOf("arm64-v8a", "armeabi-v7a"), "zh-Hans-CN", "Asia/Shanghai")
  val value = DiagnosticEnvironment.snapshot()
  assertEquals("16", value.osVersion)
  assertEquals(36, value.androidApiLevel)
  assertEquals("Google", value.brand)
  assertEquals("Pixel 9 Pro", value.model)
  assertEquals(listOf("arm64-v8a", "armeabi-v7a"), value.cpuArchitectures)
  assertEquals("zh-Hans-CN", value.locale)
  assertEquals("Asia/Shanghai", value.timeZone)
  assertEquals(setOf("osVersion", "androidApiLevel", "brand", "model", "cpuArchitectures", "locale", "timeZone"),
   Json.parseToJsonElement(codec.encodeToString(value)).jsonObject.keys)
 }

 @Test fun malformedOversizedAndFreeTextValuesAreDroppedRatherThanTruncated() {
  for (unsafe in listOf("SECRET https://private.invalid/a", "password=SECRET", "user@example.com", "/private/save", "line\nSECRET", "tab\tSECRET", "a".repeat(100), "bad\u202evalue")) {
   DiagnosticEnvironment.configureAndroid(unsafe, -1, unsafe, unsafe, arrayOf(unsafe), unsafe, unsafe)
   val value = DiagnosticEnvironment.snapshot()
   assertNull(value.osVersion); assertNull(value.androidApiLevel); assertNull(value.brand); assertNull(value.model)
   assertTrue(value.cpuArchitectures.isEmpty()); assertNull(value.locale); assertNull(value.timeZone)
   val encoded = codec.encodeToString(value)
   assertFalse("SECRET" in encoded); assertFalse("private" in encoded)
  }
 }

 @Test fun supportedArchitecturesAreCanonicalBoundedCopiedAndImmutable() {
  val input = arrayOf("amd64", "aarch64", "i686", "armv7l", "x86_64", "unknown", "riscv64", null)
  DiagnosticEnvironment.configureAndroid("15", 35, null, null, input, null, null)
  input[0] = "SECRET"
  val architectures = DiagnosticEnvironment.snapshot().cpuArchitectures
  assertEquals(listOf("x86_64", "arm64-v8a", "x86", "armeabi-v7a", "riscv64"), architectures)
  assertFailsWith<UnsupportedOperationException> { (architectures as MutableList).add("SECRET") }
  DiagnosticEnvironment.configureAndroid(null, 1000, null, null, Array(100) { if (it < 16) "amd64" else "riscv64" }, null, null)
  assertEquals(listOf("x86_64"), DiagnosticEnvironment.snapshot().cpuArchitectures)
  assertNull(DiagnosticEnvironment.snapshot().androidApiLevel)
 }

 @Test fun desktopReconfigurationClearsAndroidFieldsAndLeavesExistingSnapshotsStable() {
  DiagnosticEnvironment.configureAndroid("16", 36, "Google", "Pixel 9", arrayOf("arm64-v8a"), "en-US", "UTC")
  val android = DiagnosticEnvironment.snapshot()
  DiagnosticEnvironment.configureDesktop("10.0", "amd64", "en-GB", "Europe/London")
  val desktop = DiagnosticEnvironment.snapshot()
  assertEquals("10.0", desktop.osVersion); assertEquals(listOf("x86_64"), desktop.cpuArchitectures)
  assertNull(desktop.androidApiLevel); assertNull(desktop.brand); assertNull(desktop.model)
  assertEquals("en-GB", desktop.locale); assertEquals("Europe/London", desktop.timeZone)
  assertEquals("Pixel 9", android.model); assertEquals(36, android.androidApiLevel)
 }

 @Test fun localeExcludesCustomVariantsAndPrivateExtensions() {
  for ((input, expected) in mapOf("en-US-x-secret" to "en-US", "zh-Hant-TW-u-nu-hanidec" to "zh-Hant-TW", "de-DE-1996" to "de-DE", "es-419" to "es-419")) {
   DiagnosticEnvironment.configureDesktop(null, null, input, null)
   assertEquals(expected, DiagnosticEnvironment.snapshot().locale)
  }
  for (bad in listOf("en_US", "en--US", "x-secret", "root", "", "en-US-123456789")) {
   DiagnosticEnvironment.configureDesktop(null, null, bad, null)
   assertNull(DiagnosticEnvironment.snapshot().locale)
  }
 }

 @Test fun timezoneRequiresRealZoneOrValidOffset() {
  for (valid in listOf("UTC", "GMT", "Etc/UTC", "America/Los_Angeles", "GMT+08:00", "+05:30", "Z")) {
   DiagnosticEnvironment.configureDesktop(null, null, null, valid)
   assertNotNull(DiagnosticEnvironment.snapshot().timeZone)
  }
  for (bad in listOf("Private/SECRET", "GMT+99:00", "Europe/NotAZone", "GMT+8", "../UTC")) {
   DiagnosticEnvironment.configureDesktop(null, null, null, bad)
   assertNull(DiagnosticEnvironment.snapshot().timeZone)
  }
 }

 @Test fun missingMetadataIsExplicitAndResetDoesNotInventADevice() {
  DiagnosticEnvironment.resetForTests()
  val value = DiagnosticEnvironment.snapshot()
  assertNull(value.osVersion); assertNull(value.androidApiLevel); assertNull(value.brand); assertNull(value.model)
  assertTrue(value.cpuArchitectures.isEmpty()); assertNull(value.locale); assertNull(value.timeZone)
 }

 @Test fun exportEmbedsTheSanitizedSnapshotAndExistingAppVersion() {
  DiagnosticEnvironment.configureAndroid("16", 36, "Google", "Pixel 9 Pro", arrayOf("arm64-v8a"), "zh-CN-x-secret", "Asia/Shanghai")
  DiagnosticLog.configure(directory, "0.5.16-root-test+37", "android")
  val entries = linkedMapOf<String, String>()
  ZipInputStream(ByteArrayInputStream(DiagnosticLog.snapshotExport())).use { zip ->
   while (true) {
    val entry = zip.nextEntry ?: break
    entries[entry.name] = zip.readBytes().decodeToString()
   }
  }
  val metadata = Json.parseToJsonElement(entries.getValue("metadata.json")).jsonObject
  assertEquals("0.5.16-root-test+37", metadata.getValue("appVersion").jsonPrimitive.content)
  val environment = metadata.getValue("environment").jsonObject
  assertEquals("36", environment.getValue("androidApiLevel").jsonPrimitive.content)
  assertEquals("Pixel 9 Pro", environment.getValue("model").jsonPrimitive.content)
  assertEquals("zh-CN", environment.getValue("locale").jsonPrimitive.content)
  assertEquals("Asia/Shanghai", environment.getValue("timeZone").jsonPrimitive.content)
  assertContains(entries.getValue("应用.log"), "osVersion=16 api=36 brand=Google model=Pixel 9 Pro")
  assertFalse("secret" in entries.values.joinToString())
 }
}
