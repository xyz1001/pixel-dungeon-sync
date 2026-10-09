package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class InstalledGameDiscoveryTest {
 @TempDir lateinit var temp: Path
 private fun context() = SavePathContext("Linux", temp.resolve("home").toString(), emptyMap())
 private fun saveRoot() = Path.of(ShatteredDefaultSavePaths.resolve(context()))
 private fun root(path: Path) = InstallationSearchRoot(path.toString(), relativeDirectories = listOf(""))
 private fun file(name: String): Path = temp.resolve(name).also { Files.createDirectories(it.parent); Files.write(it, byteArrayOf(1, 2)) }
 private fun trusted(path: Path) = TargetGame(path.toString(), "4.0.2", 922, "synthetic-test-only", true, 850, "test fixture inspector")
 private fun fakeJar(path: Path): Path {
  Files.createDirectories(path.parent)
  ZipOutputStream(Files.newOutputStream(path)).use { zip ->
   val manifest = "Manifest-Version: 1.0\r\nMain-Class: com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher\r\n" +
    "Specification-Title: Shattered Pixel Dungeon\r\nImplementation-Title: com.shatteredpixel.shatteredpixeldungeon\r\n" +
    "Specification-Version: 4.0.2\r\nImplementation-Version: 922\r\n\r\n"
   mapOf("META-INF/MANIFEST.MF" to manifest.toByteArray(), "com/shatteredpixel/shatteredpixeldungeon/desktop/DesktopLauncher.class" to byteArrayOf(1, 2)).forEach { (name, bytes) ->
    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
   }
  }
  return path
 }

 @Test fun noHitsDoesNotCreateDirectoriesOrReadHostHome() {
  val before = Files.list(temp).use { it.count() }
  val result = ShatteredInstallationDiscovery(context(), listOf(root(temp.resolve("absent")))).discover()
  assertFalse(result.defaultSave.available); assertEquals(saveRoot().toString(), result.defaultSave.path)
  assertTrue(result.candidates.isEmpty()); assertNull(result.suggestedTarget); assertFalse(result.incomplete)
  assertEquals(before, Files.list(temp).use { it.count() })
 }

 @Test fun defaultSaveIsDetectedIndependentlyOfGameJar() {
  Files.createDirectories(saveRoot())
  val result = ShatteredInstallationDiscovery(context(), emptyList()).discover()
  assertTrue(result.defaultSave.available); assertTrue(result.candidates.isEmpty())
 }

 @Test fun invalidDefaultEnvironmentDoesNotFallBack() {
  val result = ShatteredInstallationDiscovery(context().copy(environment = mapOf("XDG_DATA_HOME" to "")), emptyList()).discover()
  assertFalse(result.defaultSave.available); assertNull(result.defaultSave.path)
  assertContains(result.defaultSave.reason.orEmpty(), "XDG_DATA_HOME")
 }

 @Test fun defaultSaveRejectsFileAndSymlink() {
  Files.createDirectories(saveRoot().parent); Files.write(saveRoot(), byteArrayOf(1))
  assertFalse(ShatteredInstallationDiscovery(context(), emptyList()).discover().defaultSave.available)
  Files.delete(saveRoot()); Files.createSymbolicLink(saveRoot(), Files.createDirectory(temp.resolve("elsewhere")))
  assertFalse(ShatteredInstallationDiscovery(context(), emptyList()).discover().defaultSave.available)
 }

 @Test fun oneTrustedCandidateSuggestsItButTwoRequireExplicitChoice() {
  val first = file("install/first.jar")
  val scanner = ShatteredInstallationDiscovery(context(), listOf(root(first.parent)), ::trusted)
  val single = scanner.discover()
  assertEquals(first.toString(), single.suggestedTarget?.path); assertFalse(single.requiresSelection)
  val second = file("install/second.jar")
  val multiple = scanner.discover()
  assertEquals(setOf(first.toString(), second.toString()), multiple.trustedTargets.map { it.path }.toSet())
  assertTrue(multiple.requiresSelection); assertNull(multiple.suggestedTarget)
 }

 @Test fun configuredJarIsProbedFirstAndDeduplicatedButDoesNotHideAmbiguity() {
  val first = file("game/one.jar"); val second = file("game/two.jar")
  val visited = mutableListOf<Path>()
  val result = ShatteredInstallationDiscovery(context(), listOf(root(first.parent))) { visited.add(it); trusted(it) }.discover(second.toString())
  assertEquals(second, visited.first()); assertEquals(2, visited.size)
  assertEquals(2, result.candidates.size); assertTrue(result.requiresSelection)
 }

 @Test fun staleConfiguredJarDoesNotSilentlyBecomeAnAutomaticDifferentTarget() {
  val found = file("game/new.jar")
  val result = ShatteredInstallationDiscovery(context(), listOf(root(found.parent)), ::trusted).discover(temp.resolve("old.jar").toString())
  assertTrue(result.incomplete); assertNull(result.suggestedTarget)
  assertEquals(1, result.trustedTargets.size)
 }

 @Test fun copiedManifestCannotBecomeTrustedAutomaticallyOrManually() {
  val fake = fakeJar(temp.resolve("game/ShatteredPD-v4.0.2-Java.jar"))
  val scanner = ShatteredInstallationDiscovery(context(), listOf(root(fake.parent)))
  val result = scanner.discover()
  assertEquals(922, result.candidates.single().target?.versionCode)
  assertFalse(result.candidates.single().trusted); assertTrue(result.trustedTargets.isEmpty())
  assertFalse(scanner.validateJar(fake).trusted)
 }

 @Test fun malformedJarAndNativeLauncherDoNotExecuteOrBecomeTrusted() {
  val malformed = file("game/not-a-jar.jar")
  val native = temp.resolve("game/Shattered Pixel Dungeon")
  val marker = temp.resolve("executed")
  Files.writeString(native, "#!/bin/sh\ntouch '$marker'\n"); native.toFile().setExecutable(true)
  val scanner = ShatteredInstallationDiscovery(context(), listOf(root(native.parent)))
  assertFalse(scanner.validateJar(native).trusted)
  assertFalse(scanner.validateJar(malformed).trusted)
  assertTrue(scanner.discover().trustedTargets.isEmpty()); assertFalse(Files.exists(marker))
 }

 @Test fun scannerNeverRecursesBeyondExplicitSlotsOrReadsRuntimeJars() {
  val direct = file("package/app/desktop.jar")
  file("package/runtime/lib/hidden.jar"); file("package/app/nested/other.jar"); file("package/unrelated/another.jar")
  val visited = mutableListOf<Path>()
  val result = ShatteredInstallationDiscovery(context(), listOf(InstallationSearchRoot(temp.resolve("package").toString()))) {
   visited.add(it); trusted(it)
  }.discover()
  assertEquals(listOf(direct), visited); assertEquals(direct.toString(), result.suggestedTarget?.path)
 }

 @Test fun symlinkJarAndAncestorAreRejectedBeforeInspector() {
  val actual = file("outside/game.jar")
  val directory = Files.createDirectory(temp.resolve("install"))
  Files.createSymbolicLink(directory.resolve("linked.jar"), actual)
  val linkedRoot = temp.resolve("linked-install"); Files.createSymbolicLink(linkedRoot, actual.parent)
  var calls = 0
  val result = ShatteredInstallationDiscovery(context(), listOf(root(directory), root(linkedRoot))) { calls++; trusted(it) }.discover()
  assertEquals(0, calls); assertTrue(result.incomplete); assertTrue(result.trustedTargets.isEmpty())
 }

 @Test fun dotSegmentsCannotConcealSymbolicLinkAncestor() {
  val actual = file("outside/game.jar")
  Files.createSymbolicLink(temp.resolve("alias"), actual.parent)
  val scanner = ShatteredInstallationDiscovery(context(), emptyList())
  val result = scanner.validateJar(temp.resolve("alias/../outside/game.jar"))
  assertNull(result.target); assertContains(result.reason, ". 或 ..")
 }

 @Test fun candidateLimitAlsoBoundsAggregateInputAndExpandedWork() {
  repeat(ShatteredInstallationDiscovery.MAX_CANDIDATES + 2) { file("game/game$it.jar") }
  var calls = 0
  val result = ShatteredInstallationDiscovery(context(), listOf(root(temp.resolve("game")))) { calls++; trusted(it) }.discover()
  assertEquals(4, calls); assertTrue(result.incomplete); assertNull(result.suggestedTarget)
  assertEquals(256L * 1024 * 1024, ShatteredInstallationDiscovery.MAX_CANDIDATES.toLong() * ShatteredInstallationDiscovery.MAX_INPUT_BYTES)
  assertEquals(512L * 1024 * 1024, ShatteredInstallationDiscovery.MAX_CANDIDATES * ShatteredInstallationDiscovery.MAX_EXPANDED_BYTES)
 }

 @Test fun entryLimitPreventsUnboundedDirectoryEnumeration() {
  repeat(ShatteredInstallationDiscovery.MAX_ENTRIES + 1) { file("crowded/$it.txt") }
  val result = ShatteredInstallationDiscovery(context(), listOf(root(temp.resolve("crowded")))).discover()
  assertTrue(result.incomplete); assertTrue(result.diagnostics.any { "512" in it })
 }

 @Test fun rootAndSlotLimitsAreReportedAndTraversalSlotsAreRejected() {
  val roots = List(ShatteredInstallationDiscovery.MAX_ROOTS + 1) { root(temp.resolve("absent$it")) }
  assertTrue(ShatteredInstallationDiscovery(context(), roots).discover().incomplete)
  val manySlots = InstallationSearchRoot(temp.toString(), relativeDirectories = List(49) { "sub$it" })
  assertTrue(ShatteredInstallationDiscovery(context(), listOf(manySlots)).discover().incomplete)
  val badSlot = InstallationSearchRoot(temp.toString(), relativeDirectories = listOf("../outside"))
  assertTrue(ShatteredInstallationDiscovery(context(), listOf(badSlot)).discover().incomplete)
 }

 @Test fun unreadableDirectoryIsReportedAsIncompleteRatherThanNotFound() {
  val directory = Files.createDirectory(temp.resolve("unreadable"))
  val permissions = Files.getPosixFilePermissions(directory)
  try {
   Files.setPosixFilePermissions(directory, emptySet<PosixFilePermission>())
   val result = ShatteredInstallationDiscovery(context(), listOf(root(directory))).discover()
   assertTrue(result.incomplete); assertTrue(result.diagnostics.isNotEmpty())
  } finally { Files.setPosixFilePermissions(directory, permissions) }
 }

 @Test fun oversizedAutomaticCandidateIsRejectedBeforeReading() {
  val file = temp.resolve("huge.jar")
  java.io.RandomAccessFile(file.toFile(), "rw").use { it.setLength(ShatteredInstallationDiscovery.MAX_INPUT_BYTES.toLong() + 1) }
  val result = ShatteredInstallationDiscovery(context(), listOf(root(temp))).discover()
  assertNull(result.candidates.single().target); assertTrue(result.incomplete)
  assertContains(result.candidates.single().reason, "input limit")
 }

 @Test fun stricterTargetBudgetChecksDeclaredExpandedSizeBeforeDecompression() {
  val jar = fakeJar(temp.resolve("fake.jar"))
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(jar, 1024 * 1024, 1) }
  assertFailsWith<IllegalArgumentException> { ShatteredVersions.target(jar, 1, 1024 * 1024) }
 }

 @Test fun defaultHintPolicyIsPureAndUsesOnlyExplicitContext() {
  val linux = ShatteredInstallationPaths.roots(SavePathContext("Linux", "/test-home"))
  assertTrue(linux.any { it.path == "/test-home/Downloads" && it.relativeDirectories == listOf("") && it.requireGameName })
  assertTrue(linux.none { it.path == "/test-home" || it.path == "/" })
  assertTrue(linux.all { it.path.startsWith("/test-home/") })
  val mac = ShatteredInstallationPaths.roots(SavePathContext("Mac OS X", "/Users/test"))
  assertTrue(mac.any { it.path == "/Applications/Shattered Pixel Dungeon.app" })
  val windows = ShatteredInstallationPaths.roots(SavePathContext("Windows 11", "C:\\Users\\test", mapOf("ProgramFiles(x86)" to "D:\\Programs")))
  assertTrue(windows.any { it.path == "D:/Programs/Steam/steamapps/common/Shattered Pixel Dungeon" })
  assertTrue(ShatteredInstallationPaths.roots(SavePathContext("Darwin", "/test")).isEmpty())
 }
}
