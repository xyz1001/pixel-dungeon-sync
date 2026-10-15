package pdsync
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class SelectedSavePathTest {
 @TempDir lateinit var temp:Path
 @Test fun explicitPathSurvivesFileSettingsWithoutRevivingLegacyRoot() {
  val root=temp.resolve("chosen").createDirectory()
  val file=ConfigFile(temp.resolve("config.json"))
  file.save(AppConfig(selectedSaveRoot=root.toString(),password=""))
  val loaded=file.load()!!
  assertEquals(root.toString(),loaded.selectedSaveRoot);assertEquals("",loaded.password)
  assertEquals(root.toRealPath(),DefaultProfileLocation().open(loaded,temp.resolve("state")).root)
 }
 @Test fun typedPathMustBeDedicatedAbsoluteAndExplicitlyPrepared() {
  val location=DefaultProfileLocation(SavePathContext("Windows",temp.toString(),emptyMap()))
  assertFails{location.requireSelected("relative",true)}
  assertFails{location.requireSelected(temp.toString(),true)}
  val root=temp.resolve("chosen");assertFails{location.requireSelected(root.toString(),false)};assertFalse(root.exists())
  assertEquals(root,location.requireSelected(root.toString(),true));assertTrue(root.isDirectory())
 }
 @Test fun previewReadsActualSlotsTimesAndBoundedRankings() {
  val root=temp.resolve("saves").createDirectory();root.resolve("game1").createDirectory()
  root.resolve("game1/game.dat").writeText("{\"version\":896}")
  root.resolve("rankings.dat").writeText("{\"total\":23}")
  val actual=SaveSummary.read(root);assertEquals(23,actual.plays);assertEquals(setOf("game1"),actual.slots.keys);assertNotNull(actual.slots["game1"])
  root.resolve("rankings.dat").writeText("{\"total\":1,\"total\":999}");assertNull(SaveSummary.read(root).plays)
 }
 @Test fun safelyAbsentNestedDirectoryHasTypedProofAndIsNeverCreatedByInspection() {
  val location=DefaultProfileLocation(SavePathContext("Linux",temp.toString(),emptyMap()))
  val missing=temp.resolve("safe/nested")
  val failure=assertFailsWith<SelectedSaveDirectoryMissing> { location.requireSelected(missing.toString(),false) }
  assertEquals(missing,failure.directory)
  assertFalse(missing.parent.exists())
  assertEquals(missing,location.requireSelected(missing.toString(),true))
  assertTrue(missing.isDirectory())
 }
 @Test fun regularFileAncestorAndDotSegmentsNeverReturnSafeMissingProof() {
  val location=DefaultProfileLocation(SavePathContext("Linux",temp.toString(),emptyMap()))
  val file=temp.resolve("file").apply { writeText("keep") }
  for (path in listOf(file,file.resolve("child"),temp.resolve("a/../b"),temp)) {
   val failure=assertFails { location.requireSelected(path.toString(),false) }
   assertFalse(failure is SelectedSaveDirectoryMissing,"unsafe target: $path")
  }
  assertEquals("keep",file.readText())
 }
 @Test
 @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
 fun symlinkAndDanglingSymlinkAncestorsNeverPermitPreparation() {
  val location=DefaultProfileLocation(SavePathContext("Linux",temp.toString(),emptyMap()))
  val target=temp.resolve("actual").createDirectory()
  val linked=temp.resolve("link")
  val dangling=temp.resolve("dangling")
  java.nio.file.Files.createSymbolicLink(linked,target)
  java.nio.file.Files.createSymbolicLink(dangling,temp.resolve("missing-target"))
  for (path in listOf(linked,linked.resolve("child"),dangling,dangling.resolve("child"))) {
   for (create in listOf(false,true)) {
    val failure=assertFails { location.requireSelected(path.toString(),create) }
    assertFalse(failure is SelectedSaveDirectoryMissing,"symlinks are never safe absent targets")
   }
  }
  assertFalse(target.resolve("child").exists())
  assertFalse(temp.resolve("missing-target").exists())
 }

}
