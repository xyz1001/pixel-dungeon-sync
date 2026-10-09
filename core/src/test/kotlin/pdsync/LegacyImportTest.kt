package pdsync

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class LegacyImportTest {
 private fun root() = Files.createTempDirectory("legacy-save").also { it.resolve("game1").createDirectories(); it.resolve("game1/game.dat").writeText("""{"version":922}""") }
 private val endpoint = "http://127.0.0.1:8765"
 private fun legacy(root: Path, state: Path, version: String = "4.0.2", pending: Boolean = false, head: Boolean = true): Snapshot {
  val snapshot = Snapshots.capture(root, Shattered, "demo", version, null)
  val key = hash(listOf("shattered", root.toRealPath().toString(), endpoint, "demo", version).joinToString("\n").encodeToByteArray())
  val dir = state.resolve("profiles/$key"); dir.resolve("history").createDirectories()
  dir.resolve("history/${snapshot.manifest.id}.zip").writeBytes(snapshot.zip)
  dir.resolve("history/${snapshot.manifest.id}.entry.json").writeText(json.encodeToString(HistoryEntry(snapshot.manifest, "2026-10-01T00:00:00Z", "恢复前自动备份")))
  if (head) dir.resolve("head.txt").writeText(snapshot.manifest.id)
  if (pending) PendingSnapshots(dir.resolve("pending")).save(key, snapshot)
  return snapshot
 }
 @Test fun importsHistoryBackupMetadataAndPendingWithoutChangingIdentity() {
  val save = root(); val state = Files.createTempDirectory("legacy-state")
  val old = legacy(save, state, pending = true)
  val manager = ProfileSession(AppConfig(save.toString()), state)
  assertEquals(old.manifest, manager.history().single().manifest)
  assertEquals("2026-10-01T00:00:00Z", manager.history().single().savedAt)
  assertTrue(manager.hasPending()); assertContentEquals(old.zip, manager.loadSnapshot(old.manifest.id).zip)
  val store = object : Store {
   override fun head() = RemoteHead(old.manifest, "\"same\"")
   override fun download(manifest: Manifest) = old
   override fun upload(snapshot: Snapshot) = error("Already published")
   override fun publish(manifest: Manifest, expected: RemoteHead?) = error("Already published")
  }
  assertTrue(manager.push(store) { true } is PushResult.Success)
  val restarted = ProfileSession(AppConfig(save.toString()), state)
  assertFalse(restarted.hasPending()); assertEquals(1, restarted.history().size)
  assertEquals(2, Files.list(state.resolve("profiles")).use { it.count() }.toInt())
 }
 @Test fun neverImportsOtherRootsOrEndpoints() {
  val state = Files.createTempDirectory("legacy-state"); val other = root(); legacy(other, state)
  val selected = root(); assertTrue(ProfileSession(AppConfig(selected.toString()), state).history().isEmpty())
  assertTrue(ProfileSession(AppConfig(other.toString(), "http://127.0.0.1:9999"), state).history().isEmpty())
 }
 @Test fun conflictingPendingOrHeadsAreNotChosen() {
  for (pending in listOf(true, false)) {
   val save = root(); val state = Files.createTempDirectory("legacy-state")
   legacy(save, state, "4.0.1", pending, !pending)
   legacy(save, state, "4.0.2", pending, !pending)
   assertFails { ProfileSession(AppConfig(save.toString()), state) }
   assertEquals(2, Files.list(state.resolve("profiles")).use { it.count() }.toInt())
  }
 }
 @Test fun corruptedLegacyArchiveStopsImportAndOriginalRemains() {
  val save = root(); val state = Files.createTempDirectory("legacy-state"); val old = legacy(save, state)
  val zip = Files.walk(state).use { paths -> paths.filter { it.fileName.toString() == "${old.manifest.id}.zip" }.findFirst().get() }
  zip.writeText("broken")
  assertFails { ProfileSession(AppConfig(save.toString()), state) }
  assertEquals("broken", zip.readText())
 }
}
