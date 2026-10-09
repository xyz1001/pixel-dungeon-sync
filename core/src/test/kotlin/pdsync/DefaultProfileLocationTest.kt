package pdsync

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class DefaultProfileLocationTest {
 private fun context() = SavePathContext("Linux", Files.createTempDirectory("fixed-home").toString(), emptyMap())
 @Test fun missingDefaultDoesNotCreateOrFallBackToManualConfig() {
  val context = context(); val location = DefaultProfileLocation(context)
  val manual = Files.createTempDirectory("old-manual"); manual.resolve("rankings.dat").writeText("old")
  val state = Files.createTempDirectory("fixed-state")
  val error = assertFailsWith<IllegalArgumentException> { location.open(AppConfig(saveRoot = manual.toString()), state) }
  assertTrue(error.message!!.contains("未找到默认存档目录"))
  assertFalse(location.resolve().exists()); assertEquals("old", manual.resolve("rankings.dat").readText())
  assertFalse(state.resolve("profiles").exists())
 }
 @Test fun fixedRootWinsOverLegacyConfigAndRetainsSameRootHistory() {
  val location = DefaultProfileLocation(context()); val root = location.resolve(); root.resolve("game1").createDirectories()
  root.resolve("game1/game.dat").writeText("{\"version\":922}")
  val old = Files.createTempDirectory("old-fixed-other"); old.resolve("rankings.dat").writeText("never read")
  val state = Files.createTempDirectory("fixed-state")
  val config = AppConfig(saveRoot = root.toString())
  val previous = ProfileSession(config, state, captureQuietMillis = 0); val saved = previous.createSnapshot { true }
  val current = location.open(config.copy(saveRoot = old.toString()), state)
  assertEquals(root.toRealPath(), current.root); assertEquals(previous.directory, current.directory)
  assertEquals(saved.manifest.id, current.history().single().manifest.id)
  assertEquals("never read", old.resolve("rankings.dat").readText())
 }
 @Test fun unrelatedManualScopeStaysSeparateAndUntouched() {
  val location = DefaultProfileLocation(context()); val root = location.resolve(); root.resolve("game1").createDirectories()
  root.resolve("game1/game.dat").writeText("{\"version\":922}")
  val manual = Files.createTempDirectory("unrelated-manual"); manual.resolve("game1").createDirectories(); manual.resolve("game1/game.dat").writeText("{\"version\":900}")
  val state = Files.createTempDirectory("fixed-state")
  val old = ProfileSession(AppConfig(saveRoot = manual.toString()), state, captureQuietMillis = 0); val snapshot = old.createSnapshot { true }
  val current = location.open(AppConfig(saveRoot = manual.toString()), state)
  assertTrue(current.history().isEmpty()); assertNotEquals(old.directory, current.directory)
  assertEquals(snapshot.manifest.id, old.history().single().manifest.id)
 }
 @Test fun explicitXdgAndFutureAdapterAreInjectableWithoutHostReads() {
  val home = Files.createTempDirectory("fixed-home"); val data = Files.createTempDirectory("fixed-xdg")
  val location = DefaultProfileLocation(SavePathContext("Linux", home.toString(), mapOf("XDG_DATA_HOME" to data.toString())))
  assertEquals(data.resolve(".shatteredpixel/shattered-pixel-dungeon"), location.resolve())
  val other = Files.createTempDirectory("adapter-root")
  val resolver = object : GameSavePaths { override fun resolve(context: SavePathContext) = other.toString() }
  assertEquals(other, DefaultProfileLocation(context(), resolver).requireDirectory())
 }
 @Test fun settingsOnlyDirectoryHasClearMessageAndNeverCreatesFakeSave() {
  val location = DefaultProfileLocation(context()); val root = location.resolve(); root.createDirectories(); root.resolve("settings.xml").writeText("device")
  assertFails { location.open(AppConfig(), Files.createTempDirectory("fixed-state")) }
  assertFalse(root.resolve("game1").exists()); assertEquals("device", root.resolve("settings.xml").readText())
 }
 @Test fun dotSegmentsCannotHideSymlinkOrChangeGamesDirectory() {
  val home = Files.createTempDirectory("dot-home")
  val outside = Files.createTempDirectory("dot-outside"); outside.resolve("child").createDirectories()
  Files.createSymbolicLink(home.resolve("link"), outside.resolve("child"))
  listOf(home.resolve("link/../data").toString(), home.resolve("./data").toString()).forEach { xdg ->
   val location = DefaultProfileLocation(SavePathContext("Linux", home.toString(), mapOf("XDG_DATA_HOME" to xdg)))
   assertFailsWith<IllegalArgumentException> { location.resolve() }
  }
 }

}
