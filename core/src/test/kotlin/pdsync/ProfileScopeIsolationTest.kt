package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProfileScopeIsolationTest {
 @TempDir lateinit var temp: Path
 private val endpoint = "http://127.0.0.1:8765"
 private fun root() = temp.resolve(UUID.randomUUID().toString()).createDirectories().also {
  it.resolve("game1").createDirectories(); it.resolve("game1/game.dat").writeText("""{"version":922}""")
 }
 private fun priorScope(root: Path, state: Path, version: String = "4.0.2", automatic: Boolean = false): Pair<Path, Snapshot> {
  val snapshot = ShatteredProfileVersions.annotate(Snapshots.capture(root, Shattered, "demo", version, null))
  val binding = if (automatic) listOf("auto-version-v2", Shattered.id, root.toRealPath().toString(), endpoint, "demo")
   else listOf(Shattered.id, root.toRealPath().toString(), endpoint, "demo", version)
  val key = hash(binding.joinToString("\n").encodeToByteArray())
  val dir = state.resolve("profiles/$key"); dir.resolve("history").createDirectories()
  dir.resolve("history/${snapshot.manifest.id}.zip").writeBytes(snapshot.zip)
  dir.resolve("history/${snapshot.manifest.id}.entry.json").writeText(json.encodeToString(HistoryEntry(snapshot.manifest, "2026-10-01T00:00:00Z", "恢复前自动备份")))
  dir.resolve("head.txt").writeText(snapshot.manifest.id)
  PendingSnapshots(dir.resolve("pending")).save(key, snapshot)
  val plan = AppendRepository(dir.resolve("append-v2"), Shattered, "demo").prepare(snapshot, emptyList())
  dir.resolve("append-v2/known").createDirectories().resolve("${plan.commit.id}.json").writeBytes(plan.manifestBytes)
  dir.resolve("append-v2/baseline.txt").writeText(plan.commit.id)
  dir.resolve("append-v2/baseline-established.txt").writeText("established")
  dir.resolve("append-v2/apply-required.txt").writeText("old interrupted restore")
  return dir to snapshot
 }
 private fun storedBytes(directory: Path): Map<String, String> = Files.walk(directory).use { paths ->
  paths.filter { Files.isRegularFile(it) }.iterator().asSequence().associate { directory.relativize(it).toString() to hash(it.readBytes()) }
 }
 @Test fun oldManualAndAutomaticScopesAreNeverAdoptedAndTheirFilesRemainUntouched() {
  val save = root(); val state = temp.resolve("state")
  val oldScopes = listOf(priorScope(save, state), priorScope(save, state, automatic = true))
  val before = storedBytes(state.resolve("profiles"))
  val manager = ProfileSession(AppConfig(save.toString()), state, captureQuietMillis = 0)
  val append = AppendProfileSession(AppConfig(save.toString()), state, captureQuietMillis = 0)
  assertTrue(manager.history().isEmpty()); assertFalse(manager.hasPending())
  assertTrue(append.history().isEmpty()); assertFalse(append.hasPending()); assertNull(append.baseline())
  oldScopes.forEach { (directory, snapshot) ->
   assertNotEquals(directory, manager.directory); assertFails { manager.loadSnapshot(snapshot.manifest.id) }
  }
  assertEquals(before, storedBytes(state.resolve("profiles")))
  val current = manager.createSnapshot { true }
  assertNull(current.manifest.parent)
  val restarted = ProfileSession(AppConfig(save.toString()), state, captureQuietMillis = 0)
  assertEquals(current.manifest.id, restarted.history().single().manifest.id)
  before.forEach { (path, digest) -> assertEquals(digest, hash(state.resolve("profiles").resolve(path).readBytes())) }
 }
 @Test fun conflictingPriorQueuesAndBaselinesDoNotBlockAFreshCurrentScope() {
  val save = root(); val state = temp.resolve("state")
  priorScope(save, state, "4.0.1"); priorScope(save, state, "4.0.2")
  val before = storedBytes(state.resolve("profiles"))
  val manager = AppendProfileSession(AppConfig(save.toString()), state, captureQuietMillis = 0)
  assertNull(manager.baseline()); assertFalse(manager.hasPending()); assertTrue(manager.history().isEmpty())
  assertEquals(before, storedBytes(state.resolve("profiles")))
 }
 @Test fun corruptPriorStateIsNeitherReadNorChanged() {
  val save = root(); val state = temp.resolve("state")
  val (directory, snapshot) = priorScope(save, state, automatic = true)
  directory.resolve("history/${snapshot.manifest.id}.zip").writeText("broken")
  directory.resolve("history/${snapshot.manifest.id}.entry.json").writeText("invalid json")
  directory.resolve("append-v2/active-plan.txt").writeText("broken queue")
  val before = storedBytes(state.resolve("profiles"))
  val manager = AppendProfileSession(AppConfig(save.toString()), state, captureQuietMillis = 0)
  assertTrue(manager.history().isEmpty()); assertFalse(manager.hasPending()); assertNull(manager.baseline())
  assertEquals(before, storedBytes(state.resolve("profiles")))
 }
}
