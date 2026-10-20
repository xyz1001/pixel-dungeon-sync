package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

/** Real desktop host decisions plus read-only source wiring checks for the composable host. */
class DesktopTwoStepSetupFlowTest {
 @TempDir lateinit var temp: Path
 private fun main() = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
 private fun location() = DefaultProfileLocation(SavePathContext(System.getProperty("os.name"), temp.toString(), mapOf("APPDATA" to temp.resolve("AppData/Roaming").toString())))

 @Test fun selectedGameCarriesConfiguredPathAndAccountWithoutReadingSaveDirectory() {
  val missing = temp.resolve("configured/missing")
  val original = AppConfig(endpoint = "https://custom.invalid/dav", profile = "keep-profile",
   username = "keep-account", password = "fixture-secret", selectedSaveRoot = missing.toString(),
   autoUploadPaused = true, onboardingProgress = OnboardingProgress.COMPLETE)
  val next = desktopSetupDraftForGame(original, "/chosen/game.jar", temp.resolve("default").toString())
  assertEquals(original.copy(targetGame = "/chosen/game.jar", onboardingProgress = OnboardingProgress.PENDING), next)
  assertFalse(missing.exists(), "game selection must not prepare or read the configured save directory")
  assertTrue(Files.list(temp).use { it.findAny().isEmpty })
 }

 @Test fun firstGameSelectionRetainsDefaultAndDoesNotReuseLegacySaveRoot() {
  val default = location().resolve()
  val next = desktopSetupDraftForGame(newConnectionConfig().copy(saveRoot = "/legacy/ignored"),
   "/chosen/game.jar", default.toString())
  assertEquals(default.toString(), next.selectedSaveRoot)
  assertEquals(OnboardingProgress.PENDING, next.onboardingProgress)
  assertFalse(default.exists())
  assertNull(desktopSetupDraftForGame(newConnectionConfig(), "/chosen/game.jar", "").selectedSaveRoot)
 }

 @Test fun completedConfigurationRemainsCompletedWithoutAReadableTargetOrBaseline() {
  val missing = temp.resolve("not-present")
  val saved = AppConfig(targetGame = missing.resolve("game.jar").toString(),
   selectedSaveRoot = missing.resolve("saves").toString(), onboardingProgress = OnboardingProgress.COMPLETE)
  assertTrue(desktopCompletedSetup(saved))
  assertFalse(missing.exists())
  val configFile = ConfigFile(temp.resolve("saved-config.json"))
  configFile.save(saved)
  assertTrue(desktopCompletedSetup(configFile.load()))
  assertFailsWith<SelectedSaveDirectoryMissing> { location().open(saved, temp.resolve("manager")) }
  assertFalse(missing.exists(), "completed startup reports the missing directory without replaying setup")
  assertFalse(temp.resolve("manager").exists())
  assertFalse(desktopCompletedSetup(null))
  listOf(OnboardingProgress.PENDING, OnboardingProgress.DEFERRED, OnboardingProgress.LEGACY).forEach {
   assertFalse(desktopCompletedSetup(saved.copy(onboardingProgress = it)), "$it must not be silently completed")
  }
 }

 @Test fun homeCanPrepareRetainedEmptyDirectoryWithoutAnySaveSnapshotOrBaseline() {
  val selected = temp.resolve("selected/saves")
  val root = desktopPrepareSelectedDirectory(AppConfig(selectedSaveRoot = selected.toString()), location())
  assertEquals(selected, root)
  assertTrue(root.isDirectory())
  assertTrue(Files.list(root).use { it.findAny().isEmpty })
  assertFalse(location().resolve().exists(), "a configured selection must not silently switch to the default")
  assertEquals(listOf(temp.resolve("selected"), selected), Files.walk(temp).use { it.skip(1).toList() })
 }


 @Test fun homeOpeningPreparedEmptyDirectoryStillHasNoSyncBaselineOrSnapshot() {
  val selected = temp.resolve("home-saves")
  val config = AppConfig(selectedSaveRoot = selected.toString(), onboardingProgress = OnboardingProgress.COMPLETE)
  desktopPrepareSelectedDirectory(config, location())
  val session = location().open(config, temp.resolve("manager"))
  assertNull(session.baselineFiles())
  assertTrue(session.history().isEmpty())
  assertTrue(session.observedFiles().isEmpty())
  val history = session.syncHistory()
  assertNull(history.baseline)
  assertTrue(history.graph.commits.isEmpty())
  assertTrue(Files.list(selected).use { it.findAny().isEmpty })
 }

 @Test fun homePreparationUsesDefaultOnlyWhenNoExplicitSelectionExists() {
  val default = location().resolve()
  val root = desktopPrepareSelectedDirectory(AppConfig(saveRoot = temp.resolve("legacy").toString()), location())
  assertEquals(default, root)
  assertTrue(Files.list(root).use { it.findAny().isEmpty })
  assertFalse(temp.resolve("legacy").exists())
 }

 @Test fun preparationRejectsUnrelatedFilesAndUnsafePathsWithoutChangingTheirContents() {
  val unrelated = temp.resolve("unrelated").createDirectory()
  val note = unrelated.resolve("important.txt").apply { writeText("keep") }
  assertFails { desktopPrepareSelectedDirectory(AppConfig(selectedSaveRoot = unrelated.toString()), location()) }
  assertEquals("keep", note.readText())
  assertEquals(listOf(note), Files.list(unrelated).use { it.toList() })
  assertFails { desktopPrepareSelectedDirectory(AppConfig(selectedSaveRoot = temp.resolve("../escape").toString()), location()) }
 }

 @Test fun hostRoutesGameDirectlyToServiceAndServiceBackToGame() {
  val main = main()
  val next = main.substringAfter("fun continueDiscovery() {").substringBefore("fun continuePath() {")
  assertTrue(next.contains("desktopSetupDraftForGame(setupDraft, target.path, pathAdapter.state.path)"))
  assertTrue(next.contains("navigate(Page.CONNECTION)"))
  listOf("Page.PATH", "requireSelected", "inspectSelectedSaveDirectory", "refreshProfile", "baseline").forEach {
   assertFalse(next.contains(it), "game selection must not gate on $it")
  }
  val reselect = main.substringAfter("fun selectTargetGame() {").substringBefore("fun launchGame() {")
  assertTrue(reselect.contains("navigate(Page.DISCOVERY)"))
  assertFalse(reselect.contains("loadProfile("))
  assertTrue(main.contains("else if (onboardingActive) Page.DISCOVERY else Page.HOME"))
  assertTrue(main.contains("if (connectionReturnPage == Page.DISCOVERY) ConnectionEntryModel.Origin.SETUP"))
  assertEquals(1, Regex("navigate\\(Page\\.PATH\\)").findAll(main).count(), "only the existing settings entry can open PATH")
  val settings = main.substringAfter("SharedSettingsKey.SAVE_PATH -> {").substringBefore("SharedSettingsKey.CLOUD_CONNECTION")
  assertTrue(settings.contains("navigate(Page.PATH)"))
  val path = main.substringAfter("Page.PATH -> key(pageGeneration) {").substringBefore("Page.HOME -> SharedHomeScreen")
  assertTrue(path.contains("SharedSavePathDialog("))
  assertTrue(path.contains("SharedSettingsScreen("), "settings stays behind its path modal")
  assertFalse(path.contains("SharedSavePathScreen("), "path edit is not a new setup step")
 }

 @Test fun homeOwnsDirectoryPreparationAfterServiceCompletionAndCompletedRestart() {
  val main = main()
  val completion = main.substringAfter("// Connection completion only changes configuration.").substringBefore("Page.CONFLICT ->")
  assertTrue(completion.indexOf("navigate(Page.HOME)") in 0 until completion.indexOf("loadProfile(applied.config"))
  assertTrue(completion.contains("prepareSelectedDirectory = true"))
  assertTrue(main.contains("refreshProfile(value, prepareSelectedDirectory && current())"))
  assertFalse(completion.contains("establishBaseline"))
  val completed = main.substringAfter("} else if (desktopCompletedSetup(saved)) {").substringBefore("} else {")
  assertTrue(completed.indexOf("navigate(Page.HOME)") in 0 until completed.indexOf("refreshProfile(saved)"))
  assertFalse(completed.contains("Page.DISCOVERY"))
  assertFalse(completed.contains("baselineFiles()"))
  val resume = main.substringAfter("// Resume an older unfinished PATH/connection flow").substringBefore("if (saved.onboardingProgress == OnboardingProgress.LEGACY)")
  assertTrue(resume.contains("validateJar"))
  assertFalse(resume.contains("refreshProfile("))
  assertFalse(resume.contains("requireSelected"))
 }
}
