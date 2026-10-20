package pdsync

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes
import kotlin.test.*
import pdsync.i18n.CoreMessages
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SaveVersionsTest {
 @TempDir lateinit var temp: Path
 private val launcher = "com/shatteredpixel/shatteredpixeldungeon/desktop/DesktopLauncher.class"
 private val target = TargetGame("game.jar", "4.0.2", 922, "test", true, 850, "test fixture")
 private fun detect(text: String) = ShatteredVersions.detect(mapOf("game1/game.dat" to text.toByteArray()))
 private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
  GZIPOutputStream(out).use { it.write(bytes) }
 }.toByteArray()
 private fun manifest(extra: String = "") = "Manifest-Version: 1.0\r\n" +
  "Main-Class: com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLaun\r\n cher\r\n" +
  "Specification-Title: Shattered Pixel Dungeon\r\n" +
  "Implementation-Title: com.shatteredpixel.shatteredpixeldungeon\r\n" +
  "Specification-Version: 4.0.2\r\nImplementation-Version: 922\r\n$extra\r\n"
 private fun jar(manifest: String = manifest(), entries: Map<String, ByteArray> = mapOf(launcher to byteArrayOf(1))): Path {
  val path = Files.createTempFile(temp, "target", ".jar")
  ZipOutputStream(Files.newOutputStream(path)).use { zip ->
   (mapOf("META-INF/MANIFEST.MF" to manifest.toByteArray()) + entries).forEach { (name, bytes) ->
    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
   }
  }
  return path
 }

 @Test fun detectsLastWriterInsteadOfInitialVersion() {
  assertEquals(mapOf("game1" to 922), detect("""{"init_ver":800,"version":922,"hero":{"version":1}}""").runs)
  assertEquals(mapOf("game1" to 900), detect("""{"version":900,"init_ver":922}""").runs)
 }

 @Test fun detectsGzipAndPreservesMixedVersions() {
  val found = ShatteredVersions.detect(mapOf(
   "game1/game.dat" to gzip("""{"version":922}""".toByteArray()),
   "game2/game.dat" to """{"version":850}""".toByteArray(),
   "game3/game.dat" to byteArrayOf(1), "game3/depth1.dat" to byteArrayOf(1),
   "game4/game.dat" to byteArrayOf(), "rankings.dat" to """{"version":9999}""".toByteArray()))
  assertEquals(mapOf("game1" to 922, "game2" to 850), found.runs)
  assertEquals(listOf("game3", "game4"), found.inactiveSlots)
  assertNull(found.unknownReason)
  assertContains(found.summary(), "game2: 850")
  ShatteredVersions.requireCompatible(found, target)
 }

 @Test fun recordsAndInactiveSlotsDoNotEstablishVersion() {
  listOf(emptyMap(), mapOf("rankings.dat" to """{"version":922}""".toByteArray()),
   mapOf("game1/game.dat" to byteArrayOf(0), "game2/game.dat" to byteArrayOf()),
   mapOf("game1/game.dat" to "{".toByteArray())).forEach {
   val found = ShatteredVersions.detect(it)
   assertTrue(found.runs.isEmpty()); assertNotNull(found.unknownReason)
   assertFailsWith<IllegalArgumentException> { ShatteredVersions.requireCompatible(found, target) }
  }
 }

 @Test fun rejectsMissingWrongTypesAndNonpositiveVersions() {
  listOf("{}", """{"init_ver":922}""", """{"nested":{"version":922}}""",
   """{"version":"922"}""", """{"version":true}""", """{"version":null}""",
   """{"version":922.0}""", """{"version":9.22e2}""", """{"version":0}""",
   """{"version":-1}""", """{"version":2147483648}""", """{"version":{}}""").forEach { data ->
   val error = assertFailsWith<IllegalArgumentException>(data) { detect(data) }
   assertContains(error.message.orEmpty(), "game1")
  }
 }

 @Test fun rejectsDuplicateTopLevelVersionsIncludingEscapes() {
  listOf("""{"version":922,"version":922}""", """{"version":922,"ver\u0073ion":900}""").forEach {
   assertContains(assertFailsWith<IllegalArgumentException> { detect(it) }.message.orEmpty(), CoreMessages.DUPLICATE_TOP_LEVEL_VERSION.text())
  }
  assertEquals(922, detect("""{"ver\u0073ion":922,"nested":{"version":700}}""").runs["game1"])
 }

 @Test fun validatesWholeDocumentAndDoesNotDeserializeJava() {
  listOf("[]", "not json", "{ ", """{"version":922,}""", """{"version":0922}""",
   """{"version":922}garbage""", """{"version":922,"x":[1,]}""",
   """{"version":922,"x":NaN}""", """{"version":922,"x":"\q"}""",
   """{"version":922,"x":1e}""", """{"version":922,"x":true false}""").forEach {
   assertFailsWith<IllegalArgumentException>(it) { detect(it) }
  }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.detect(mapOf("game1/game.dat" to byteArrayOf(0xac.toByte(), 0xed.toByte(), 0, 5))) }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.detect(mapOf("game1/game.dat" to byteArrayOf(0xc3.toByte(), 0x28))) }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.detect(mapOf("game1/game.dat" to byteArrayOf(0x1f, 0x8b.toByte(), 0))) }
 }

 @Test fun rejectsDeepAndOversizedSaves() {
  assertFailsWith<IllegalArgumentException> { detect("{\"version\":922,\"x\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}") }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.detect(mapOf("game1/game.dat" to ByteArray(8 * 1024 * 1024 + 1) { 32 })) }
  val bomb = gzip(ByteArray(16 * 1024 * 1024 + 1) { 32 })
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.detect(mapOf("game1/game.dat" to bomb)) }
 }

 @Test fun acceptsValidNestedJsonWithoutTreatingStringContentsAsKeys() {
  val data = """{"version":922,"x":[true,false,null,-2.5e+2,{"text":"a\\b\n\"version\":1","emoji":"\uD83D\uDE00"}]}"""
  assertEquals(922, detect(data).runs["game1"])
 }

 @Test fun malformedLiveSlotAndIncompleteSlotCannotHideBehindValidRun() {
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.detect(mapOf("game1/game.dat" to """{"version":922}""".toByteArray(), "game2/game.dat" to "{}".toByteArray())) }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.detect(mapOf("game1/depth1.dat" to byteArrayOf(1))) }
 }

 @Test fun compatibilityChecksEveryRunAndBothBoundaries() {
  ShatteredVersions.requireCompatible(SaveVersions(mapOf("game1" to 850, "game2" to 922)), target)
  listOf(mapOf("game1" to 849, "game2" to 922), mapOf("game1" to 922, "game2" to 923), mapOf("game1" to 0)).forEach {
   assertFailsWith<IllegalArgumentException> { ShatteredVersions.requireCompatible(SaveVersions(it), target) }
  }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.requireCompatible(detect("""{"version":922}"""), target.copy(trusted = false)) }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.requireCompatible(detect("""{"version":922}"""), target.copy(minimumSaveVersion = null)) }
 }

 @Test fun consistentMetadataIsSelectableWithoutAChecksumAllowlist() {
  val found = ShatteredVersions.target(jar())
  assertEquals("4.0.2", found.displayVersion); assertEquals(922, found.versionCode)
  assertTrue(found.trusted); assertEquals(850, found.minimumSaveVersion)
  ShatteredVersions.requireCompatible(detect("""{"version":922}"""), found)
  val changed = ShatteredVersions.target(jar(entries = mapOf(launcher to byteArrayOf(1), "extra-resource" to byteArrayOf(2))))
  assertNotEquals(found.sha256, changed.sha256) // Fingerprint changes, selection does not.
  assertTrue(changed.trusted); assertEquals(found.minimumSaveVersion, changed.minimumSaveVersion)
 }

 @Test fun user338ManifestIsIdentifiedAndRestorationChecksItsOwnRange() {
  val found = ShatteredVersions.target(jar(manifest().replace("4.0.2", "3.3.8").replace("922", "896")))
  assertTrue(found.trusted); assertEquals("3.3.8", found.displayVersion)
  assertEquals(896, found.versionCode); assertEquals(802, found.minimumSaveVersion)
  ShatteredVersions.requireCompatible(SaveVersions(mapOf("game1" to 802, "game2" to 896)), found)
  for (version in listOf(801, 897, 922)) {
   assertFailsWith<IllegalArgumentException> { ShatteredVersions.requireCompatible(SaveVersions(mapOf("game1" to version)), found) }
  }
 }

 @Test fun unknownReleaseIsSelectableButDoesNotAcquireGuessedCompatibility() {
  val found = ShatteredVersions.target(jar(manifest().replace("4.0.2", "5.0-custom").replace("922", "1000")))
  assertTrue(found.trusted); assertNull(found.minimumSaveVersion)
  assertContains(found.reason, "暂不支持恢复")
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.requireCompatible(SaveVersions(mapOf("game1" to 1000)), found) }
 }

 @Test fun contradictoryReleaseAndBuildAreRejectedWithAnExplanation() {
  for (text in listOf(manifest().replace("4.0.2", "3.3.8"), manifest().replace("922", "896"))) {
   assertContains(assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(jar(text)) }.message.orEmpty(), "版本信息不一致")
  }
 }

 @Test fun missingVersionMetadataIsNotInferredFromTheFilename() {
  for (text in listOf(manifest().replace("Specification-Version: 4.0.2\r\n", ""),
   manifest().replace("Implementation-Version: 922\r\n", ""))) {
   val named = temp.resolve("Shattered-4.0.2-official.jar")
   Files.move(jar(text), named, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
   assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(named) }
  }
  val named = temp.resolve("desktop-4.0.2.jar")
  Files.move(jar(manifest().replace("4.0.2", "3.3.8").replace("922", "896")), named)
  assertEquals("3.3.8", ShatteredVersions.target(named).displayVersion)
 }

 @Test fun targetInspectionNeverInitializesOrRunsLauncherClasses() {
  val marker = temp.resolve("must-not-execute")
  val source = temp.resolve("DesktopLauncher.java")
  val quotedPath = marker.toString().replace("\\", "\\\\")
  Files.writeString(source, """
   package com.shatteredpixel.shatteredpixeldungeon.desktop;
   public class DesktopLauncher {
    static { try { java.nio.file.Files.writeString(java.nio.file.Path.of("$quotedPath"), "executed"); }
      catch (Exception e) { throw new RuntimeException(e); } }
    public static void main(String[] args) { throw new AssertionError("must not run"); }
   }
  """.trimIndent())
  val compiler = assertNotNull(javax.tools.ToolProvider.getSystemJavaCompiler())
  assertEquals(0, compiler.run(null, null, null, "-d", temp.toString(), source.toString()))
  val found = ShatteredVersions.target(jar(entries = mapOf(launcher to Files.readAllBytes(temp.resolve(launcher)))))
  assertTrue(found.trusted); assertFalse(Files.exists(marker))
 }

 @Test fun rejectsWrongIdentityMissingLauncherAndInvalidBuildCode() {
  listOf(manifest().replace("Shattered Pixel Dungeon", "Other Game"),
   manifest().replace("Implementation-Title: com.shatteredpixel.shatteredpixeldungeon", "Implementation-Title: other"),
   manifest().replace("desktop.DesktopLaun", "desktop.OtherLaun"),
   manifest().replace("Implementation-Version: 922", "Implementation-Version: 922.0"),
   manifest().replace("Implementation-Version: 922", "Implementation-Version: 0")).forEach {
   assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(jar(it)) }
  }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(jar(entries = emptyMap())) }
 }

 @Test fun rejectsDuplicateManifestKeysAndManifestEntryAliases() {
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(jar(manifest("implementation-version: 922\r\n"))) }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(jar(entries = mapOf(launcher to byteArrayOf(1), "meta-inf/manifest.mf" to manifest().toByteArray()))) }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(jar(" continuation\r\n" + manifest())) }
 }

 @Test fun rejectsDuplicateZipEntriesAndMissingCentralDirectory() {
  val duplicated = jar(entries = mapOf(launcher to byteArrayOf(1), "extra-one" to byteArrayOf(2), "extra-two" to byteArrayOf(3)))
  val bytes = Files.readAllBytes(duplicated)
  val from = "extra-two".toByteArray(); val to = "extra-one".toByteArray()
  for (index in 0..bytes.size - from.size) {
   if (from.indices.all { bytes[index + it] == from[it] }) to.copyInto(bytes, index)
  }
  duplicated.writeBytes(bytes)
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(duplicated) }
  val truncated = jar()
  truncated.writeBytes(Files.readAllBytes(truncated).dropLast(22).toByteArray())
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(truncated) }
 }

 @Test fun rejectsOversizedManifestAndNonJarInput() {
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(jar(manifest("Long-Key: " + "a".repeat(64 * 1024) + "\r\n"))) }
  val text = temp.resolve("plain.jar").also { it.writeBytes("not a zip file".toByteArray()) }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(text) }
 }

 @Test fun rejectsSymlinkTargetAndSparseOversizedJar() {
  val actual = jar()
  val link = temp.resolve("linked.jar")
  Files.createSymbolicLink(link, actual)
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(link) }
  val large = temp.resolve("too-large.jar")
  java.io.RandomAccessFile(large.toFile(), "rw").use { it.setLength(256L * 1024 * 1024 + 1) }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(large) }
 }

 @Test fun inspectionIntegratesWithVerifiedSnapshotBytes() {
  val root = temp.resolve("saves")
  Files.createDirectories(root.resolve("game1"))
  root.resolve("game1/game.dat").writeBytes(gzip("""{"version":922,"init_ver":850}""".toByteArray()))
  val snapshot = Snapshots.capture(root, Shattered, "test", "incorrect-manual-label", null)
  assertEquals(922, ShatteredVersions.detect(Snapshots.verify(snapshot, Shattered)).runs["game1"])
 }
}
