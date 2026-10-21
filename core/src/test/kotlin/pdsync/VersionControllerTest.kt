package pdsync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test

/** Controller tests use a known-target policy fixture; executable trust is tested separately. */
class VersionControllerTest {
 private val known = TargetGame("test-target", "4.0.2", 922, "unit-fixture", true, 850, "unit fixture")
 private val policy = object : ProfileVersions {
  override fun annotate(snapshot: Snapshot) = ShatteredProfileVersions.annotate(snapshot)
  override fun target(path: String) = known
  override fun checkRestore(snapshot: Snapshot, targetPath: String) {
   val actual = ShatteredVersions.detect(Snapshots.verify(snapshot, Shattered))
   snapshot.manifest.saveVersions?.let { require(actual == it) }
   ShatteredVersions.requireCompatible(actual, known)
  }
 }
 private fun root(writer: Int): Path = Files.createTempDirectory("version-profile").also {
  it.resolve("game1").createDirectories(); it.resolve("game1/game.dat").writeText("""{"version":$writer,"init_ver":700}""")
  it.resolve("settings.xml").writeText("local-settings")
 }
 private fun session(root: Path, selectedPolicy: ProfileVersions = policy, manual: String? = null) = ProfileSession(
  AppConfig(root.toString(), version = manual), Files.createTempDirectory("version-state"), Shattered, selectedPolicy, captureQuietMillis = 0)
 @Test fun manualLabelCannotOverrideActualWriter() {
  val manager = session(root(923), ShatteredProfileVersions, "4.0.2")
  val entry = manager.createSnapshot { true }
  assertNull(manager.config.version)
  assertEquals(mapOf("game1" to 923), entry.manifest.saveVersions!!.runs)
  assertNotEquals("4.0.2", entry.manifest.version)
 }
 @Test fun missingTargetBlocksBeforeBackupOrMutation() {
  val save = root(922); val manager = session(save, ShatteredProfileVersions)
  val snapshot = manager.createSnapshot { true }
  assertFails { manager.restoreHistory(snapshot.manifest.id) { true } }
  assertFalse(manager.directory.resolve("backups").exists())
  assertEquals("""{"version":922,"init_ver":700}""", save.resolve("game1/game.dat").readText())
 }
 @Test fun allowedUpgradePreservesOldArchiveAndActualBackupWriter() {
  val save = root(850); val manager = session(save)
  val older = manager.createSnapshot { true }
  save.resolve("game1/game.dat").writeText("""{"version":922,"init_ver":850}""")
  val restored = manager.restoreHistory(older.manifest.id) { true }
  assertFalse(manager.directory.resolve("backups").exists())
  assertEquals(850, manager.loadSnapshot(older.manifest.id).manifest.saveVersions!!.runs["game1"])
  assertEquals(850, manager.currentVersions()!!.runs["game1"])
  assertEquals("local-settings", save.resolve("settings.xml").readText())
 }
 @Test fun newerAndMixedTooOldArchivesAreBlockedBeforeBackup() {
  listOf(923, 849).forEach { incompatible ->
   val save = root(922); save.resolve("game2").createDirectories()
   save.resolve("game2/game.dat").writeText("""{"version":$incompatible}""")
   val manager = session(save); val entry = manager.createSnapshot { true }
   assertEquals(2, entry.manifest.saveVersions!!.runs.size)
   assertFails { manager.restoreHistory(entry.manifest.id) { true } }
   assertFalse(manager.directory.resolve("backups").exists())
  }
 }
 @Test fun malformedAndRecordsOnlyCanBeArchivedButNotRestored() {
  listOf(true, false).forEach { malformed ->
   val save = if (malformed) root(922).also { it.resolve("game1/game.dat").writeText("not a save") }
    else Files.createTempDirectory("records-only").also { it.resolve("rankings.dat").writeText("""{"version":922}""") }
   val manager = session(save); val entry = manager.createSnapshot { true }
   assertNotNull(entry.manifest.saveVersions!!.unknownReason)
   assertFails { manager.restoreHistory(entry.manifest.id) { true } }
   assertFalse(manager.directory.resolve("backups").exists())
  }
 }
 @Test fun forgedStoredVersionEvidenceIsRejected() {
  val save = root(923); val manager = session(save)
  val snapshot = ShatteredProfileVersions.annotate(Snapshots.capture(save, Shattered, "demo", "ignored", null))
  val forged = snapshot.copy(manifest = snapshot.manifest.copy(saveVersions = SaveVersions(mapOf("game1" to 922))))
  val store = object : Store {
   override fun head() = RemoteHead(forged.manifest, "\"test\"")
   override fun download(manifest: Manifest) = forged
   override fun upload(snapshot: Snapshot) = error("not used")
   override fun publish(manifest: Manifest, expected: RemoteHead?) = error("not used")
  }
  assertFails { manager.readRemote(store) }
  assertTrue(manager.history().isEmpty())
 }
}
