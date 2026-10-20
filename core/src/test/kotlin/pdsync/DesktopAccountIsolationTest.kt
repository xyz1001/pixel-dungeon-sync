package pdsync

import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopAccountIsolationTest {
 private class Memory : AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  override fun legacyPresent() = false
  override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
  override fun readCommit(id: String) = commits[id]
  override fun readObject(id: String) = objects[id]
  override fun writeCommit(id: String, bytes: ByteArray) { commits[id] = bytes }
  override fun writeObject(id: String, bytes: ByteArray) { objects[id] = bytes }
 }
 @Test fun accountSwitchCannotImportCloudCacheOrPendingBaselineFromAnotherAccount() {
  val temp = Files.createTempDirectory("account-isolation")
  val root = Files.createDirectory(temp.resolve("save"))
  val state = temp.resolve("state")
  val location = DefaultProfileLocation()
  val a = AppConfig(endpoint = "https://example.invalid/", profile = "default", username = "synthetic-A",
   selectedSaveRoot = root.toString(), connectionProvider = "official")
  val b = a.copy(username = "synthetic-B")
  val first = location.open(a, state)
  val remoteA = Memory()
  val snapshot = Snapshots.fromFiles(mapOf("game1/game.dat" to "{\"version\":896}".encodeToByteArray()),
   Shattered, "default", "game1:896").let { it.copy(manifest = it.manifest.copy(
    gamePackage = GameCloudIdentity.SHATTERED_PACKAGE, gameSource = GameSaveSource("a".repeat(64), "b".repeat(64)))) }
  val commit = AppendCommit(id = UUID.randomUUID().toString(), parents = emptyList(),
   snapshot = snapshot.manifest, objectBytes = snapshot.zip.size)
  remoteA.commits[commit.id] = json.encodeToString(commit).encodeToByteArray()
  remoteA.objects[snapshot.manifest.id] = snapshot.zip
  first.readRemote(remoteA)
  val repositoryA = AppendRepository(first.directory.resolve("append-v2"), Shattered, "default")
  repositoryA.setBaseline(commit.id, repositoryA.discover(remoteA))
  val pendingSnapshot = Snapshots.fromFiles(mapOf("game1/game.dat" to "{\"version\":896,\"turn\":2}".encodeToByteArray()),
   Shattered, "default", "game1:896")
  repositoryA.prepare(pendingSnapshot, listOf(commit.id), "snapshot")
  assertEquals(commit.id, first.baseline())
  assertTrue(first.hasPending())
  val other = location.open(b, state)
  assertNotEquals(first.directory, other.directory)
  assertTrue(other.syncHistory().graph.commits.isEmpty())
  assertTrue(other.history().isEmpty())
  assertNull(other.baseline())
  assertFalse(other.hasPending())
  assertTrue(other.readRemote(Memory()).isEmpty())
  // A late completion owned by A cannot populate the currently selected B account.
  first.readRemote(remoteA)
  assertTrue(other.syncHistory().graph.commits.isEmpty())
  val reopened = location.open(a.copy(password = "synthetic-changed-password"), state)
  assertEquals(first.directory, reopened.directory)
  assertEquals(setOf(commit.id), reopened.syncHistory().graph.commits.keys)
  assertEquals(commit.id, reopened.baseline())
  assertTrue(reopened.hasPending())
  assertEquals(1, reopened.readRemote(remoteA).size)
  assertFalse(first.directory.toString().contains(a.username))
  assertNotEquals(first.directory, location.open(a.copy(connectionProvider = "custom"), state).directory)
 }
 @Test fun oldUnboundCacheIsPreservedButNeverImported() {
  val temp = Files.createTempDirectory("account-old-cache")
  val root = Files.createDirectory(temp.resolve("save"))
  val config = AppConfig(endpoint = "https://example.invalid/", username = "synthetic-A", selectedSaveRoot = root.toString())
  val state = temp.resolve("state")
  val old = AppendProfileSession(config.copy(saveRoot = root.toString()), state)
  Files.createDirectories(old.directory)
  val sentinel = old.directory.resolve("old-cache-preserved.txt")
  Files.writeString(sentinel, "preserve")
  val current = DefaultProfileLocation().open(config, state)
  assertNotEquals(old.directory, current.directory)
  assertEquals("preserve", Files.readString(sentinel))
  assertTrue(current.syncHistory().graph.commits.isEmpty())
 }
}
