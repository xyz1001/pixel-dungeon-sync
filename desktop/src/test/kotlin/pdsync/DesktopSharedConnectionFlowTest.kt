package pdsync

import io.github.xyz1001.pdsync.android.ConnectionEntryModel
import io.github.xyz1001.pdsync.android.SharedConnectionConfig
import io.github.xyz1001.pdsync.android.SharedConnectionController
import io.github.xyz1001.pdsync.android.SharedConnectionHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.io.path.*
import kotlin.test.*

/** Shared production controller + desktop adapter; every DAV probe is local and injected. */
class DesktopSharedConnectionFlowTest {
 @TempDir lateinit var temp: Path
 private class Queue : Executor {
  val tasks = ArrayDeque<Runnable>()
  override fun execute(command: Runnable) { tasks.add(command) }
  fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
 }
 private class Host : SharedConnectionHost {
  val back = mutableListOf<ConnectionEntryModel.Back>()
  val committed = mutableListOf<SharedConnectionConfig>()
  val busy = mutableListOf<Boolean>()
  override fun onBack(destination: ConnectionEntryModel.Back) { back += destination }
  override fun onCommitted(config: SharedConnectionConfig) { committed += config }
  override fun onBusyChanged(busy: Boolean) { this.busy += busy }
 }
 private data class Page(val controller: SharedConnectionController, val adapter: DesktopConnectionAdapter,
                         val io: Queue, val ui: Queue, val host: Host) {
  fun finish() { io.runAll(); ui.runAll() }
  fun close() { controller.foreground(false); controller.detach(); controller.close(); adapter.close() }
 }
 private fun page(origin: ConnectionEntryModel.Origin, initial: AppConfig, password: String,
                  configFile: ConfigFile, probe: (ConnectionDraft) -> RemoteHead? = { null }): Page {
  val adapter = DesktopConnectionAdapter(initial, password, configFile, temp.resolve("cache")) { ConnectionSetup(it, probe = probe) }
  val io = Queue(); val ui = Queue(); val host = Host()
  val controller = SharedConnectionController(origin, adapter.initialConnection, adapter.profile, adapter, io, ui)
  controller.attach(host)
  controller.foreground(true)
  return Page(controller, adapter, io, ui, host)
 }
 private fun existing() = AppConfig(endpoint = "https://custom.invalid/dav", profile = "existing-profile",
  username = "alice", password = "fixture-secret", targetGame = "/fixture/game.jar", selectedSaveRoot = "/fixture/saves",
  autoUploadPaused = true, onboardingProgress = OnboardingProgress.COMPLETE)

 @Test fun setupChoiceTestsBeforeExplicitCompleteAndDuplicateClicksOnlyCommitOnce() {
  val path = temp.resolve("new.json"); val file = ConfigFile(path)
  var probes = 0
  val fresh = newConnectionConfig().copy(targetGame = "/fixture/game.jar", selectedSaveRoot = "/fixture/saves")
  val page = page(ConnectionEntryModel.Origin.SETUP, fresh, "", file) { probes++; null }
  val controller = page.controller
  assertEquals(ConnectionEntryModel.Mode.CHOICE, controller.state.mode)
  assertFalse(path.exists())
  controller.choose(ConnectionEntryModel.Mode.CUSTOM)
  controller.editEndpoint("https://custom.invalid/dav")
  controller.editUsername("alice")
  controller.editPassword("")
  controller.continueConnection()
  assertTrue(page.io.tasks.isEmpty())
  controller.testConnection(); controller.testConnection()
  assertEquals(1, page.io.tasks.size)
  page.finish()
  assertTrue(controller.state.canContinue)
  assertFalse(path.exists())
  controller.continueConnection(); controller.continueConnection()
  assertEquals(1, page.io.tasks.size)
  page.finish()
  assertEquals(2, probes)
  assertEquals(1, page.host.committed.size)
  assertEquals("", assertNotNull(file.load()).password)
  assertEquals(fresh.profile, file.load()!!.profile)
  assertEquals(OnboardingProgress.COMPLETE, file.load()!!.onboardingProgress)
  assertFalse(temp.resolve("manager").exists(), "Completing connection must not create sync state")
  assertEquals(listOf(false, true, false, true, false), page.host.busy)
  page.close()
 }

 @Test fun setupBackLeavesFormForChoiceThenReturnsToPathWithoutSaving() {
  val path = temp.resolve("setup-back.json")
  val page = page(ConnectionEntryModel.Origin.SETUP, newConnectionConfig(), "", ConfigFile(path))
  page.controller.choose(ConnectionEntryModel.Mode.CUSTOM)
  page.controller.editEndpoint("https://custom.invalid/dav")
  page.controller.back()
  assertEquals(ConnectionEntryModel.Mode.CHOICE, page.controller.state.mode)
  assertTrue(page.host.back.isEmpty())
  page.controller.back()
  assertEquals(listOf(ConnectionEntryModel.Back.PATH), page.host.back)
  assertFalse(path.exists())
  page.close()
 }

 @Test fun settingsBackReturnsSettingsAndReopeningCannotReuseSetupProof() {
  val path = temp.resolve("existing.json"); val file = ConfigFile(path)
  file.save(existing())
  val before = path.readBytes()
  var probes = 0
  val setup = page(ConnectionEntryModel.Origin.SETUP, existing(), "fixture-secret", file) { probes++; null }
  setup.controller.testConnection(); setup.finish()
  assertTrue(setup.controller.state.canContinue)
  setup.close()
  val settings = page(ConnectionEntryModel.Origin.SETTINGS, assertNotNull(file.load()), "fixture-secret", file) { probes++; null }
  assertEquals(ConnectionEntryModel.Mode.CUSTOM, settings.controller.state.mode)
  assertEquals("alice", settings.controller.state.username)
  assertEquals("fixture-secret", settings.controller.state.password)
  assertTrue(settings.controller.state.showChangeService)
  // Existing durable credentials permit a fresh verification, not the previous page's proof.
  assertTrue(settings.controller.state.canContinue)
  assertEquals(1, probes)
  settings.controller.continueConnection()
  assertEquals(1, settings.io.tasks.size)
  assertContentEquals(before, path.readBytes())
  settings.controller.cancel(); settings.finish()
  assertEquals(1, probes)
  assertFalse(settings.controller.state.canContinue)
  assertTrue(settings.host.committed.isEmpty())
  settings.controller.back()
  assertEquals(listOf(ConnectionEntryModel.Back.SETTINGS), settings.host.back)
  assertContentEquals(before, path.readBytes())
  settings.close()
 }

 @Test fun cancelBeforeQueuedVerificationFinishesCannotSaveOrPublishOnReturn() {
  val path = temp.resolve("cancel.json"); val file = ConfigFile(path)
  file.save(existing())
  val before = path.readBytes()
  var probes = 0
  val page = page(ConnectionEntryModel.Origin.SETTINGS, existing(), "fixture-secret", file) { probes++; null }
  page.controller.testConnection()
  page.controller.cancel()
  page.finish()
  assertEquals(0, probes)
  assertFalse(page.controller.state.canContinue)
  page.controller.continueConnection()
  assertTrue(page.io.tasks.isEmpty())
  page.controller.back()
  assertEquals(listOf(ConnectionEntryModel.Back.SETTINGS), page.host.back)
  assertTrue(page.host.committed.isEmpty())
  assertContentEquals(before, path.readBytes())
  page.close()
 }

 @Test fun cancelAfterExplicitNextBeforeWorkerRunsPreservesExistingConfig() {
  val path = temp.resolve("next-cancel.json"); val file = ConfigFile(path)
  file.save(existing())
  val before = path.readBytes()
  val page = page(ConnectionEntryModel.Origin.SETTINGS, existing(), "fixture-secret", file)
  page.controller.testConnection(); page.finish()
  page.controller.continueConnection()
  page.controller.cancel()
  page.finish()
  assertTrue(page.host.committed.isEmpty())
  assertContentEquals(before, path.readBytes())
  page.close()
 }

 @Test fun changingAccountClearsPasswordAndRequiresNewProofAcrossOrigins() {
  for (origin in ConnectionEntryModel.Origin.values()) {
   val path = temp.resolve("switch-${origin.name}.json"); val file = ConfigFile(path)
   file.save(existing())
   val before = path.readBytes()
   val page = page(origin, existing(), "fixture-secret", file)
   page.controller.testConnection(); page.finish()
   assertTrue(page.controller.state.canContinue)
   page.controller.editUsername("bob")
   assertEquals("", page.controller.state.password)
   assertFalse(page.controller.state.canContinue)
   page.controller.continueConnection()
   assertTrue(page.io.tasks.isEmpty())
   assertContentEquals(before, path.readBytes())
   page.close()
  }
 }

 @Test fun repeatedPageActivationPreservesProofAndDoesNotReplayNetwork() {
  val path = temp.resolve("page-active.json"); val file = ConfigFile(path)
  file.save(existing())
  val before = path.readBytes()
  var probes = 0
  val page = page(ConnectionEntryModel.Origin.SETTINGS, existing(), "fixture-secret", file) { probes++; null }
  page.controller.testConnection(); page.finish()
  assertTrue(page.controller.state.canContinue)
  repeat(3) { page.controller.foreground(true) }
  assertEquals("alice", page.controller.state.username)
  assertEquals("fixture-secret", page.controller.state.password)
  assertTrue(page.controller.state.canContinue)
  assertTrue(page.io.tasks.isEmpty())
  assertEquals(1, probes)
  assertContentEquals(before, path.readBytes())
  page.close(); page.close()
  assertFalse(page.controller.state.canContinue)
  assertEquals("", page.controller.state.password)
  assertTrue(page.io.tasks.isEmpty())
  assertEquals(1, probes)
 }

 @Test fun pageDisposalCancelsQueuedVerificationAndIgnoresLateActivation() {
  val path = temp.resolve("page-disposal.json"); val file = ConfigFile(path)
  file.save(existing())
  val before = path.readBytes()
  var probes = 0
  val page = page(ConnectionEntryModel.Origin.SETTINGS, existing(), "fixture-secret", file) { probes++; null }
  page.controller.testConnection()
  page.close(); page.close()
  page.controller.foreground(true)
  page.finish()
  assertEquals(0, probes)
  assertFalse(page.controller.state.canContinue)
  assertEquals("", page.controller.state.password)
  assertTrue(page.host.committed.isEmpty())
  assertContentEquals(before, path.readBytes())
 }

 @Test fun changingServiceAndReturningKeepsOriginalFormButDiscardsProof() {
  val file = ConfigFile(temp.resolve("change-service.json"))
  file.save(existing())
  val page = page(ConnectionEntryModel.Origin.SETTINGS, existing(), "fixture-secret", file)
  page.controller.testConnection(); page.finish()
  page.controller.showChooser()
  assertEquals(ConnectionEntryModel.Mode.CHOICE, page.controller.state.mode)
  page.controller.choose(ConnectionEntryModel.Mode.OFFICIAL)
  assertEquals("", page.controller.state.password)
  assertEquals("", page.controller.state.username)
  page.controller.showChooser()
  page.controller.choose(ConnectionEntryModel.Mode.CUSTOM)
  assertEquals("https://custom.invalid/dav", page.controller.state.endpoint)
  assertEquals("alice", page.controller.state.username)
  assertEquals("fixture-secret", page.controller.state.password)
  assertFalse(page.controller.state.canContinue)
  page.close()
 }
 @Test fun committedWizardConnectionCanReturnAndReverifyWithoutSeparateTestClick() {
  val path = temp.resolve("saved-reentry.json"); val file = ConfigFile(path)
  var probes = 0
  val first = page(ConnectionEntryModel.Origin.SETUP, newConnectionConfig(), "", file) { probes++; null }
  first.controller.choose(ConnectionEntryModel.Mode.CUSTOM)
  first.controller.editEndpoint("https://custom.invalid/dav/")
  first.controller.editUsername("alice")
  first.controller.editPassword("fixture-secret")
  first.controller.testConnection(); first.finish()
  first.controller.continueConnection(); first.finish()
  assertEquals(2, probes)
  val saved = assertNotNull(file.load())
  assertEquals("https://custom.invalid/dav", saved.endpoint)
  first.close()
  // Hosts can retain the entered slash even though ConfigFile canonicalizes it on disk.
  val returned = page(ConnectionEntryModel.Origin.SETUP, saved.copy(endpoint = saved.endpoint + "/"),
   "fixture-secret", file) { probes++; null }
  assertTrue(returned.controller.state.canContinue)
  assertEquals(2, probes)
  returned.controller.continueConnection(); returned.finish()
  assertEquals(3, probes)
  assertEquals(1, returned.host.committed.size)
  assertNotNull(returned.adapter.appliedConnection)
  returned.close()
 }

 @Test fun returningToUntestedDurableConnectionCannotSaveWhenFreshDavFails() {
  val path = temp.resolve("saved-reentry-failure.json"); val file = ConfigFile(path)
  file.save(existing())
  val before = path.readBytes()
  var probes = 0
  val returned = page(ConnectionEntryModel.Origin.SETUP, existing(), "fixture-secret", file) {
   probes++; throw java.io.IOException("fixture failure")
  }
  assertTrue(returned.controller.state.canContinue)
  returned.controller.continueConnection(); returned.finish()
  assertEquals(1, probes)
  assertFalse(returned.controller.state.canContinue)
  assertTrue(returned.controller.state.error)
  assertTrue(returned.host.committed.isEmpty())
  assertNull(returned.adapter.appliedConnection)
  assertContentEquals(before, path.readBytes())
  returned.close()
 }

 @Test fun successfulUnsavedDraftCannotGrantNextToNewPage() {
  val path = temp.resolve("unsaved-reentry.json"); val file = ConfigFile(path)
  val draft = existing()
  val first = page(ConnectionEntryModel.Origin.SETUP, draft, "fixture-secret", file)
  first.controller.testConnection(); first.finish()
  assertTrue(first.controller.state.canContinue)
  assertFalse(path.exists())
  first.close()
  val returned = page(ConnectionEntryModel.Origin.SETUP, draft, "fixture-secret", file)
  assertFalse(returned.controller.state.canContinue)
  returned.controller.continueConnection()
  assertTrue(returned.io.tasks.isEmpty())
  assertFalse(path.exists())
  returned.close()
 }

}
