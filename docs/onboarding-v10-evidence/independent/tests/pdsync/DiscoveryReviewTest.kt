package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class DiscoveryReviewTest {
 @TempDir lateinit var temp: Path
 private fun context() = SavePathContext("Linux", temp.toString())
 private fun target(path: Path) = TargetGame(path.toString(), "4.0.2", 922, "a".repeat(64), true, 850, "fixture inspector")
 @Test fun downloadsIgnoresUnrelatedNamesAndNeverRecursesIntoOtherFolders() {
  val downloads = temp.resolve("Downloads").createDirectory(); downloads.resolve("unrelated.jar").writeText("not a game")
  downloads.resolve("another-folder").createDirectory().resolve("Shattered.jar").writeText("not scanned")
  val inspected = mutableListOf<Path>()
  val scanner = ShatteredInstallationDiscovery(context(), ShatteredInstallationPaths.roots(context())) { inspected += listOf(it); target(it) }
  val result = scanner.discover(); assertTrue(inspected.isEmpty()); assertTrue(result.candidates.isEmpty()); assertNull(result.suggestedTarget)
 }
 @Test fun symbolicDirectoryCannotExposeOutsideFiles() {
  val real = temp.resolve("outside").createDirectory(); real.resolve("Shattered.jar").writeText("x")
  val link = temp.resolve("link"); Files.createSymbolicLink(link, real)
  var inspected = 0
  val result = ShatteredInstallationDiscovery(context(), listOf(InstallationSearchRoot(link.toString(), relativeDirectories = listOf("")))) { inspected++; target(it) }.discover()
  assertEquals(0, inspected); assertTrue(result.incomplete); assertNull(result.suggestedTarget)
 }
 @Test fun symbolicJarNeverReachesInspector() {
  val folder = temp.resolve("game").createDirectory(); val file = temp.resolve("actual.jar").apply { writeText("x") }
  Files.createSymbolicLink(folder.resolve("Shattered.jar"), file); var inspected = 0
  val result = ShatteredInstallationDiscovery(context(), listOf(InstallationSearchRoot(folder.toString(), relativeDirectories = listOf("")))) { inspected++; target(it) }.discover()
  assertEquals(0, inspected); assertNull(result.suggestedTarget); assertFalse(result.candidates.single().trusted)
 }
 @Test fun inspectedCandidatesNeverExceedFourAndIncompleteNeverSelectsFirst() {
  val folder = temp.resolve("game").createDirectory(); repeat(20) { folder.resolve("Shattered-$it.jar").writeText("x") }; var inspected = 0
  val result = ShatteredInstallationDiscovery(context(), listOf(InstallationSearchRoot(folder.toString(), relativeDirectories = listOf("")))) { inspected++; target(it) }.discover()
  assertEquals(4, inspected); assertEquals(4, result.candidates.size); assertTrue(result.incomplete); assertNull(result.suggestedTarget)
 }
 @Test fun twoTrustedCandidatesRequireExplicitSelection() {
  val folder = temp.resolve("game").createDirectory(); repeat(2) { folder.resolve("Shattered-$it.jar").writeText("x") }
  val result = ShatteredInstallationDiscovery(context(), listOf(InstallationSearchRoot(folder.toString(), relativeDirectories = listOf(""))), ::target).discover()
  assertEquals(2, result.trustedTargets.size); assertTrue(result.requiresSelection); assertNull(result.suggestedTarget)
 }
 @Test fun lexicalParentTraversalIsRejectedBeforeNormalization() {
  val folder = temp.resolve("game").createDirectory(); folder.resolve("Shattered.jar").writeText("x"); var inspected = 0
  val result = ShatteredInstallationDiscovery(context(), listOf(InstallationSearchRoot(folder.resolve("../game").toString(), relativeDirectories = listOf("")))) { inspected++; target(it) }.discover()
  assertEquals(0, inspected); assertTrue(result.incomplete); assertNull(result.suggestedTarget)
 }
 @Test fun scanDoesNotCreateMissingDefaultSaveOrGameDirectories() {
  val result = ShatteredInstallationDiscovery(context()).discover()
  assertFalse(result.defaultSave.available); assertFalse(temp.resolve(".local").exists()); assertFalse(temp.resolve("Downloads").exists()); assertTrue(temp.listDirectoryEntries().isEmpty())
 }
 @Test fun productionDiscoveryRecognizesExactOfficialJarStatically() {
  val folder = temp.resolve("game").createDirectory()
  Files.copy(Path.of("/workspace/shared/pds-append-gameplay/ShatteredPD-v4.0.2-Java.jar"), folder.resolve("Shattered.jar"))
  val result = ShatteredInstallationDiscovery(context(), listOf(InstallationSearchRoot(folder.toString(), relativeDirectories = listOf("")))).discover()
  val actual = assertNotNull(result.suggestedTarget); assertTrue(actual.trusted); assertEquals(922, actual.versionCode)
  assertEquals("bcefd52a9c14f69c69d3ccc08a212cdd3830b2dc60d78d80895cd803fa1ec602", actual.sha256)
 }
 @Test fun forgedManifestAndLauncherNameNeverGrantTrust() {
  val folder = temp.resolve("game").createDirectory(); val jar = folder.resolve("Shattered.jar")
  ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
   zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); zip.write(("Manifest-Version: 1.0\r\nSpecification-Title: Shattered Pixel Dungeon\r\nImplementation-Title: com.shatteredpixel.shatteredpixeldungeon\r\nMain-Class: com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher\r\nSpecification-Version: 4.0.2\r\nImplementation-Version: 922\r\n\r\n").toByteArray()); zip.closeEntry()
   zip.putNextEntry(ZipEntry("com/shatteredpixel/shatteredpixeldungeon/desktop/DesktopLauncher.class")); zip.write(byteArrayOf(1,2,3)); zip.closeEntry()
  }
  val result = ShatteredInstallationDiscovery(context(), listOf(InstallationSearchRoot(folder.toString(), relativeDirectories = listOf("")))).discover()
  assertEquals(922, result.candidates.single().target?.versionCode); assertFalse(result.candidates.single().trusted); assertNull(result.suggestedTarget)
 }
}
