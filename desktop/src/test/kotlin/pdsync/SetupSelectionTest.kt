package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class SetupSelectionTest {
 @TempDir lateinit var temp: Path
 @Test fun incompleteSetupKeepsSelectionButNeverMakesMissingJarTrusted() {
  val file = temp.resolve("setup-game.txt")
  assertNull(SetupSelection(file).load())
  val game = temp.resolve("missing.jar").toString()
  SetupSelection(file).save(game)
  assertEquals(game, SetupSelection(file).load())
  assertFalse(ShatteredInstallationDiscovery().validateJar(Path.of(SetupSelection(file).load()!!)).trusted)
 }
 @Test fun relativeSelectionCannotBePersisted() {
  assertFailsWith<IllegalArgumentException> { SetupSelection(temp.resolve("setup-game.txt")).save("relative.jar") }
 }
 @Test fun defaultStateIsUnderFixedUserHomeAndOverrideKeepsTestIsolation() {
  assertEquals(temp.resolve(".pixel-dungeon-sync"), applicationWorkspace(null, temp.toString()))
  val isolated = temp.resolve("fixed-test-state")
  assertEquals(isolated, applicationWorkspace(isolated.toString(), temp.resolve("other-home").toString()))
 }
}
