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
}
