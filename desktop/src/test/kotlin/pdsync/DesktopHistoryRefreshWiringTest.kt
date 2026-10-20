package pdsync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DesktopHistoryRefreshWiringTest {
 @TempDir lateinit var temp: Path

 private class ReadCheckedStore : AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  var writes = 0
  var failReads = false
  override fun listCommits(cursor: String?): CommitPage {
   check(!failReads) { "history unavailable" }
   return CommitPage(commits.keys.toList())
  }
  override fun readCommit(id: String) = commits[id]?.copyOf()
  override fun readObject(id: String) = objects[id]?.copyOf()
  override fun writeCommit(id: String, bytes: ByteArray) { writes++; commits[id] = bytes.copyOf() }
  override fun writeObject(id: String, bytes: ByteArray) { writes++; objects[id] = bytes.copyOf() }
  override fun legacyPresent() = false
 }
 private val versions = object : ProfileVersions {
  override fun annotate(snapshot: Snapshot) = snapshot.copy(manifest = snapshot.manifest.copy(version = "v1"))
  override fun target(path: String): TargetGame? = null
  override fun checkRestore(snapshot: Snapshot, targetPath: String) = Unit
 }
 private fun bytes(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
  paths.filter { Files.isRegularFile(it) }.toList().associate {
   root.relativize(it).toString() to Files.readAllBytes(it).toList()
  }
 }

 @Test fun explicitHostCallbackUsesGuardedLocalAndCloudReadWithoutAnyTransferOrProvisioning() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val callback = main.substringAfter("override fun refreshHistory() {").substringBefore("override fun upload()")
  assertContains(callback, "if (!homeState().refreshHistoryEnabled) return")
  assertContains(callback, "readRemote(refreshLocal = true)")
  val state = main.substringAfter("fun homeState()").substringBefore("fun reconcileHomeSelection")
  assertContains(state, "syncAccess.hasKnownAccess(config) && renewalReads.allowsManualRefresh(config)")
  val remote = main.substringAfter("fun readRemote(").substringBefore("fun hasRemoteBranches()")
  assertContains(remote, "if (refreshLocal && !homeState().refreshHistoryEnabled) return")
  assertContains(remote, "renewalReads.manualRequest(config) ?: return")
  assertContains(remote, "renewalReads.takeForHome(config, home = true, ready = true)")
  assertContains(remote, "if (previousManager == null && !reopenLocal) return")
  assertContains(remote, "homeSelectionGeneration == selectionGeneration")
  assertContains(remote, "renewalReads.isManualCurrent(manualRefresh, config)")
  assertContains(remote, "refreshProfile(readConfig, canPublish = ::currentPage)")
  assertContains(remote, "manager.readHomeHistory(syncAccess.store(manager.config, manager.adapter.id, secret))")
  assertContains(remote, "if (!localHistoryVerified) return@launch")
  assertTrue(remote.indexOf("busy = true") < remote.indexOf("scope.launch"))
  assertTrue(remote.indexOf("refreshProfile(readConfig,") < remote.indexOf("manager.readHomeHistory("))
  assertEquals(2, Regex("automatic\\.skipped\\(\\)").findAll(remote).count(),
   "both the retained and reopened automatic policies must suppress old upload work")
  assertContains(remote, "catch (e: CancellationException) { throw e }")
  assertContains(remote, "currentPage() && session === readManager")
  assertContains(remote, "finally { archiveReads.end(read); busy = false }")
  for (forbidden in listOf("ensureNamespace", "prepareSelectedDirectory = true", "execute(", ".push(",
   ".restore", "hasPending()", "uploadSourceAvailable")) assertFalse(remote.contains(forbidden), forbidden)
  val profile = main.substringAfter("suspend fun refreshProfile(").substringBefore("fun loadProfile(")
  assertContains(profile, "config == value && canPublish()")
  assertTrue(profile.indexOf("if (!current()) return") < profile.indexOf("archiveReads.begin()"))
 }

 @Test fun reopenedEmptyAndPopulatedDirectoriesReadCloudHistoryWithoutWritingSavesOrPublishing() {
  val remoteRoot = temp.resolve("remote-fixture").createDirectory()
  remoteRoot.resolve("game1").createDirectory()
  remoteRoot.resolve("game1/game.dat").writeText("cloud progress")
  val store = ReadCheckedStore()
  val remote = AppendProfileSession(AppConfig(saveRoot = remoteRoot.toString(), username = "account"),
   temp.resolve("remote-state"), versions = versions, captureQuietMillis = 0)
  assertIs<AppendPushResult.Success>(remote.push(store) { true })
  val cloudBefore = store.commits.mapValues { it.value.toList() } to store.objects.mapValues { it.value.toList() }
  store.writes = 0

  for (populated in listOf(false, true)) {
   val root = temp.resolve("local-$populated").createDirectory()
   if (populated) { root.resolve("game1").createDirectory(); root.resolve("game1/game.dat").writeText("local progress") }
   val owner = AppConfig(selectedSaveRoot = root.toString(), username = "account",
    onboardingProgress = OnboardingProgress.COMPLETE)
   val location = DefaultProfileLocation(versions = versions)
   val before = bytes(root)
   repeat(2) {
    // These are the existing refreshProfile/readRemote operations selected by the actual callback.
    assertEquals(root, location.selected(owner))
    val manager = location.open(owner, temp.resolve("local-state-$populated"))
    manager.history(); manager.currentVersions(); manager.syncHistory()
    assertNull(manager.baseline())
    assertFalse(manager.hasPending())
    val preview = manager.readHomeHistory(store)
    assertEquals(1, preview.graph.commits.size)
    assertEquals(RemoteVerification.METADATA_ONLY, preview.graph.verification)
    assertTrue(preview.verifiedCommitIds.isEmpty())
    assertEquals(1, manager.syncHistory(preview).graph.commits.size)
    assertTrue(manager.history().isEmpty(), "metadata read must not remember a ZIP")
    assertEquals(before, bytes(root), "reading must not restore or create any local save")
    assertNull(manager.baseline(), "reading never establishes sync ancestry")
    assertFalse(manager.hasPending(), "reading never creates an upload plan")
    store.failReads = true
    assertFailsWith<IllegalStateException> { manager.readHomeHistory(store) }
    store.failReads = false
    assertEquals(before, bytes(root))
   }
  }
  assertEquals(0, store.writes)
  assertEquals(cloudBefore, store.commits.mapValues { it.value.toList() } to store.objects.mapValues { it.value.toList() })
 }

 @Test fun missingDirectoryReadNeverPreparesIt() {
  val root = temp.resolve("not-created")
  val owner = AppConfig(selectedSaveRoot = root.toString(), onboardingProgress = OnboardingProgress.COMPLETE)
  assertFailsWith<SelectedSaveDirectoryMissing> { DefaultProfileLocation().selected(owner) }
  assertFalse(root.exists())
 }
}
