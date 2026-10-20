package pdsync

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.presentation.HistoryPresentation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.io.path.*
import kotlin.test.*

class SnapshotMetadataTest {
 @TempDir lateinit var temp: Path
 private var next = 0
 private val captureTime = "2026-10-09T03:04:05Z"
 private val writeTime = "2026-10-08T01:02:03Z"
 private val publishTime = "2026-10-09T04:05:06Z"
 private fun clock(time: String) = Clock.fixed(Instant.parse(time), ZoneOffset.UTC)
 private fun directory(label: String) = temp.resolve("$label-${next++}").createDirectory()
 private fun metadata(state: Path = directory("state"), time: String = captureTime, label: String = "电脑 🎮") = DeviceMetadata(state, clock(time)) { label }
 private fun root(value: String = "original") = directory("save").also {
  it.resolve("game1").createDirectory(); it.resolve("game1/game.dat").writeText(value)
  it.resolve("rankings.dat").writeText("ranks")
  it.resolve("settings.xml").writeText("excluded")
  Files.setLastModifiedTime(it.resolve("game1/game.dat"), FileTime.from(Instant.parse(writeTime)))
  Files.setLastModifiedTime(it.resolve("rankings.dat"), FileTime.from(Instant.parse(writeTime)))
 }
 private fun snapshot(device: DeviceMetadata? = metadata(), save: Path = root()) =
  Snapshots.capture(save, Shattered, "demo", "v1", null, device?.captureContext())
 private val policy = object: ProfileVersions {
  override fun annotate(snapshot: Snapshot) = snapshot.copy(manifest = snapshot.manifest.copy(version = "v1"))
  override fun target(path: String): TargetGame? = null
  override fun checkRestore(snapshot: Snapshot, targetPath: String) = Unit
 }
 private fun session(save: Path = root(), state: Path = directory("state")) =
  ProfileSession(AppConfig(saveRoot = save.toString()), state, versions = policy, captureQuietMillis = 0)
 private class Memory: AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  var writes = 0
  override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
  override fun readCommit(id: String) = commits[id]?.copyOf()
  override fun readObject(id: String) = objects[id]?.copyOf()
  override fun writeCommit(id: String, bytes: ByteArray) { writes++; commits[id] = bytes.copyOf() }
  override fun writeObject(id: String, bytes: ByteArray) { writes++; objects[id] = bytes.copyOf() }
  override fun legacyPresent() = false
 }

 @Test fun identitySurvivesRestartAndRenameButDifferentWorkspacesRemainDistinct() {
  val state = directory("state"); val first = metadata(state).currentDevice()
  assertTrue(validCommitId(first.id))
  assertEquals(first, metadata(state).currentDevice())
  val renamed = metadata(state, label = "新电脑").currentDevice()
  assertEquals(first.id, renamed.id); assertNotEquals(first.label, renamed.label)
  val second = metadata(label = first.label).currentDevice()
  assertNotEquals(first.id, second.id); assertNotEquals(first.displayName, second.displayName)
  val stored = state.resolve("device.json").readText()
  assertFalse(stored.contains(first.label)); assertFalse(stored.contains("hostname")); assertFalse(stored.contains("macAddress"))
 }

 @Test fun concurrentProvidersCreateOnlyOnePersistedIdentity() {
  val state = directory("state"); val pool = Executors.newFixedThreadPool(6)
  try {
   val futures = (1..20).map { pool.submit<String> { metadata(state).currentDevice().id } }
   assertEquals(1, futures.map { it.get() }.toSet().size)
  } finally { pool.shutdownNow() }
 }

 @Test fun corruptIdentityIsNotSilentlyReplacedOnRestart() {
  val state = directory("state"); metadata(state).currentDevice()
  state.resolve("device.json").writeText("corrupt identity")
  assertFails { metadata(state).currentDevice() }
  assertEquals("corrupt identity", state.resolve("device.json").readText())
 }

 @Test fun labelsAreUnicodeSafeBoundedAndFreeOfControlsAndDirectionalFormatting() {
  val label = sanitizeDeviceLabel("  电\u0000脑\u202e\n  🎮  ")
  assertEquals("电脑 🎮", label)
  val long = sanitizeDeviceLabel("🎮".repeat(100))
  assertEquals(MAX_DEVICE_LABEL_CODE_POINTS, long.codePointCount(0, long.length))
  assertEquals("🎮".repeat(MAX_DEVICE_LABEL_CODE_POINTS), long)
  assertEquals("此设备", sanitizeDeviceLabel("\u0000\u202e "))
  assertEquals("é", sanitizeDeviceLabel("e\u0301"))
  assertEquals("此设备", sanitizeDeviceLabel("\uD800"))
  assertFails { DeviceIdentity(UUID.randomUUID().toString(), "Bad\nlabel").validated() }
  assertFails { DeviceIdentity(UUID.randomUUID().toString(), "a".repeat(65)).validated() }
 }

 @Test fun captureAndFileModificationTimesAreDistinctAndRawSaveBytesDoNotChange() {
  val source = root(); val device = metadata()
  val before = Files.walk(source).use { files -> files.filter { Files.isRegularFile(it) }.toList().associate { source.relativize(it).toString() to it.readBytes().toList() } }
  val captured = snapshot(device, source); val provenance = assertNotNull(captured.manifest.metadata)
  assertEquals(device.currentDevice(), provenance.device); assertEquals(captureTime, provenance.capturedAt)
  assertEquals(captured.manifest.files.keys, provenance.fileModifiedAt.keys)
  assertEquals(setOf(writeTime), provenance.fileModifiedAt.values.toSet()); assertEquals(writeTime, provenance.latestFileModifiedAt)
  assertNotEquals(provenance.capturedAt, provenance.latestFileModifiedAt)
  assertEquals("original", Snapshots.verify(captured, Shattered).getValue("game1/game.dat").decodeToString())
  before.forEach { (name, bytes) -> assertEquals(bytes, source.resolve(name).readBytes().toList()) }
  assertEquals(Instant.parse(writeTime), Files.getLastModifiedTime(source.resolve("game1/game.dat")).toInstant())
  assertTrue(captured.manifest.toJson().contains("\"version\": 1"))
 }

 @Test fun newReaderPreservesOldUnknownProvenanceAndOldBytes() {
  val old = snapshot(null); val manifestBytes = old.manifest.toJson()
  assertFalse(manifestBytes.contains("metadata"))
  assertNull(json.decodeFromString<Manifest>(manifestBytes).metadata)
  assertEquals(manifestBytes, json.decodeFromString<Manifest>(manifestBytes).toJson())
  val oldCommit = AppendCommit(id = UUID.randomUUID().toString(), parents = emptyList(), snapshot = old.manifest, objectBytes = old.zip.size)
  val commitBytes = json.encodeToString(oldCommit).encodeToByteArray()
  assertFalse(commitBytes.decodeToString().contains("operation"))
  val remote = Memory(); remote.commits[oldCommit.id] = commitBytes; remote.objects[old.manifest.id] = old.zip
  val repo = AppendRepository(directory("repo"), Shattered, "demo", clock(publishTime))
  val downloaded = repo.download(remote, repo.discover(remote).commits.getValue(oldCommit.id))
  assertNull(downloaded.manifest.metadata); assertNull(oldCommit.operation)
  val plan = repo.prepare(downloaded, listOf(oldCommit.id), "restore", metadata().operation())
  assertNull(plan.commit.snapshot.metadata); assertNotNull(plan.commit.operation)
  assertContentEquals(commitBytes, remote.commits.getValue(oldCommit.id)); assertEquals(0, remote.writes)
 }

 @Test fun restoreAndResolutionPreserveSourceProvenanceWhileRecordingNewActorAndTime() {
  val origin = metadata(); val source = snapshot(origin)
  val actor = metadata(time = publishTime, label = "另一台电脑")
  for (kind in listOf("restore", "resolution")) {
   val parents = if (kind == "resolution") List(2) { UUID.randomUUID().toString() } else emptyList()
   val plan = AppendRepository(directory("repo"), Shattered, "demo").prepare(source, parents, kind, actor.operation())
   assertEquals(source.manifest.metadata, plan.commit.snapshot.metadata)
   assertEquals(origin.currentDevice(), plan.commit.snapshot.metadata!!.device)
   assertEquals(actor.currentDevice(), plan.commit.operation!!.device)
   assertEquals(publishTime, plan.commit.operation!!.preparedAt)
   assertNotEquals(plan.commit.snapshot.metadata!!.device.id, plan.commit.operation!!.device.id)
   assertContentEquals(source.zip, plan.zip); assertEquals(source.manifest.sha256, plan.commit.snapshot.sha256)
   val history = SyncHistory(CommitGraph(mapOf(plan.commit.id to plan.commit), emptyMap()),
    plan.commit.id, source.manifest.files, "", null, emptyMap())
   val presentation = HistoryPresentation.from(history, 0)
   assertEquals(setOf(HistoryPresentation.time(publishTime)), presentation.nodes.map { it.time }.toSet())
   assertEquals(1, presentation.rows)
   assertEquals(source.manifest.metadata, plan.commit.snapshot.metadata)
  }
 }

 @Test fun retriesAndObservationsNeverRewriteImmutableMetadata() {
  val source = snapshot(); val state = directory("repo"); val remote = Memory()
  val repo = AppendRepository(state, Shattered, "demo", clock(publishTime))
  val plan = repo.prepare(source, emptyList(), operation = metadata(time = publishTime).operation())
  assertNull(repo.observedAt(plan.commit.id))
  val restarted = AppendRepository(state, Shattered, "demo", clock(publishTime))
  assertContentEquals(plan.manifestBytes, restarted.pending()!!.manifestBytes)
  restarted.publish(remote, restarted.pending()!!)
  val writes = remote.writes
  val later = AppendRepository(state, Shattered, "demo", clock("2026-10-10T05:06:07Z"))
  later.publish(remote, later.pending()!!); later.discover(remote)
  assertEquals(writes, remote.writes)
  assertEquals(publishTime, later.observedAt(plan.commit.id))
  assertContentEquals(plan.manifestBytes, remote.commits.getValue(plan.commit.id))
  assertEquals(plan.commit.operation, later.pending()!!.commit.operation)
  assertFalse(plan.manifestBytes.decodeToString().contains("observedAt"))
 }

 @Test fun malformedProvenanceCannotBecomeVerifiedHistoryOrRemoteCommit() {
  val source = snapshot(); val original = source.manifest.metadata!!
  val invalid = listOf(
   original.copy(version = 2),
   original.copy(device = original.device.copy(id = "00:11:22:33:44:55")),
   original.copy(device = original.device.copy(label = "evil\u202e")),
   original.copy(capturedAt = "2026-10-09T04:04:05+01:00"),
   original.copy(capturedAt = "yesterday"),
   original.copy(fileModifiedAt = mapOf("wrong.dat" to writeTime)),
   original.copy(fileModifiedAt = original.fileModifiedAt.mapValues { "not-time" })
  )
  for (provenance in invalid) {
   val changed = source.copy(manifest = source.manifest.copy(metadata = provenance))
   assertFails { Snapshots.verify(changed, Shattered) }
   val commit = AppendCommit(id = UUID.randomUUID().toString(), parents = emptyList(), snapshot = changed.manifest, objectBytes = changed.zip.size)
   val remote = Memory(); remote.commits[commit.id] = json.encodeToString(commit).encodeToByteArray(); remote.objects[changed.manifest.id] = changed.zip
   val repo = AppendRepository(directory("invalid"), Shattered, "demo")
   assertTrue(commit.id in repo.discover(remote).pending); assertNull(repo.observedAt(commit.id))
  }
  assertFails { AppendRepository(directory("invalid-operation"), Shattered, "demo").prepare(source, emptyList(), operation = metadata().operation().copy(version = 9)) }
 }

 @Test fun profileCreationAndPreRestoreBackupCarryCurrentDeviceButChosenHistoryKeepsOrigin() {
  val source = session(); val saved = source.createSnapshot { true }
  val sourceSnapshot = source.loadSnapshot(saved.manifest.id)
  val target = session(root("target current")); target.remember(sourceSnapshot, "downloaded")
  val restored = target.restoreHistory(saved.manifest.id) { true }
  assertEquals(source.deviceIdentity(), restored.restored.metadata!!.device)
  assertEquals(target.deviceIdentity(), restored.backup.manifest.metadata!!.device)
  assertNotEquals(source.deviceIdentity().id, target.deviceIdentity().id)
  assertEquals("target current", Snapshots.verify(target.loadSnapshot(restored.backup.manifest.id), Shattered).getValue("game1/game.dat").decodeToString())
  assertEquals(sourceSnapshot.manifest.metadata, target.loadSnapshot(saved.manifest.id).manifest.metadata)
 }

 @Test fun historyObservationCanBeAddedLocallyWithoutChangingSavedAtOrProvenance() {
  val target = session(); val source = snapshot(); val first = target.remember(source, "local")
  val observed = target.remember(source, "remote", publishTime)
  assertEquals(first.savedAt, observed.savedAt); assertEquals(first.manifest, observed.manifest)
  assertEquals(publishTime, observed.remoteObservedAt)
  assertEquals(observed, target.remember(source, "again", "2026-10-10T05:06:07Z"))
  assertEquals(observed, target.history().single())
 }

 @Test fun baselineFilesReadsOnlyEstablishedValidatedLocalState() {
  val remote = Memory(); val state = directory("repo"); val repo = AppendRepository(state, Shattered, "demo")
  assertNull(repo.baselineFiles())
  val source = snapshot(); val plan = repo.prepare(source, emptyList())
  repo.publish(remote, plan); repo.setBaseline(plan.commit.id, repo.discover(remote)); repo.clearPending()
  assertEquals(source.manifest.files, AppendRepository(state, Shattered, "demo").baselineFiles())
  state.resolve("known/${plan.commit.id}.json").writeText("corrupt")
  assertFails { AppendRepository(state, Shattered, "demo").baselineFiles() }
 }
 @Test fun appendHistoryRestorePublishesOriginalProvenanceAndCurrentRestoringActor() {
  val remote = Memory()
  val a = AppendProfileSession(AppConfig(saveRoot = root("source A").toString()), directory("state-a"), versions = policy, captureQuietMillis = 0)
  val initial = assertIs<AppendPushResult.Success>(a.push(remote) { true }).commit
  val initialBytes = remote.commits.getValue(initial.id).copyOf()
  val b = AppendProfileSession(AppConfig(saveRoot = directory("empty").toString()), directory("state-b"), versions = policy, captureQuietMillis = 0)
  b.restoreRemote(b.readRemote(remote).single().entry.manifest.id, remote) { true }
  b.root.resolve("game1/game.dat").writeText("newer B")
  val later = assertIs<AppendPushResult.Success>(b.push(remote) { true }).commit
  val restored = b.restoreHistory(initial.snapshot.id) { true }
  assertEquals(a.deviceIdentity(), restored.restored.metadata!!.device)
  assertEquals(b.deviceIdentity(), restored.backup.manifest.metadata!!.device)
  val plan = AppendRepository(b.directory.resolve("append-v2"), Shattered, "demo").pending()!!
  assertEquals("restore", plan.commit.kind); assertEquals(listOf(later.id), plan.commit.parents)
  assertEquals(initial.snapshot.metadata, plan.commit.snapshot.metadata)
  assertEquals(b.deviceIdentity(), plan.commit.operation!!.device)
  val published = assertIs<AppendPushResult.Success>(b.push(remote) { true }).commit
  assertEquals(plan.commit, published)
  assertEquals(initial.snapshot.metadata, published.snapshot.metadata)
  assertEquals("source A", b.root.resolve("game1/game.dat").readText())
  assertContentEquals(initialBytes, remote.commits.getValue(initial.id))
  assertNotNull(b.history().single { it.manifest.id == published.snapshot.id }.remoteObservedAt)
 }

 @Test fun appendResolutionChoosingAnotherDeviceKeepsItsSnapshotOriginAndCurrentActor() {
  val remote = Memory()
  val a = AppendProfileSession(AppConfig(saveRoot = root("initial").toString()), directory("state-a"), versions = policy, captureQuietMillis = 0)
  val initial = assertIs<AppendPushResult.Success>(a.push(remote) { true }).commit
  val b = AppendProfileSession(AppConfig(saveRoot = directory("empty").toString()), directory("state-b"), versions = policy, captureQuietMillis = 0)
  b.restoreRemote(b.readRemote(remote).single().entry.manifest.id, remote) { true }
  a.root.resolve("game1/game.dat").writeText("chosen A")
  val aBranch = assertIs<AppendPushResult.Success>(a.push(remote) { true }).commit
  b.root.resolve("game1/game.dat").writeText("other B")
  val conflict = assertIs<AppendPushResult.Diverged>(b.push(remote) { true }).conflict
  val originalCommits = remote.commits.mapValues { it.value.copyOf() }
  val resolved = assertIs<AppendPushResult.Success>(b.resolve(conflict, aBranch.snapshot.id, remote) { true }).commit
  assertEquals("resolution", resolved.kind); assertEquals(conflict.observedTips, resolved.parents.toSet())
  assertEquals(aBranch.snapshot.metadata, resolved.snapshot.metadata)
  assertEquals(a.deviceIdentity(), resolved.snapshot.metadata!!.device)
  assertEquals(b.deviceIdentity(), resolved.operation!!.device)
  assertEquals("chosen A", b.root.resolve("game1/game.dat").readText())
  originalCommits.forEach { (id, bytes) -> assertContentEquals(bytes, remote.commits.getValue(id)) }
  assertTrue(initial.id in originalCommits)
  assertNotNull(b.history().single { it.manifest.id == resolved.snapshot.id }.remoteObservedAt)
 }

 @Test fun metadataOnlyWriteAfterStableBackupCancelsBeforeReplacementAndNeverRollsBack() {
  val source = root("old source"); val target = root("current target")
  val backup = directory("backup"); val guard = SaveStabilityGuard(target, Shattered, { true }, quietMillis = 0)
  val replacement = snapshot(save = source)
  val externalTime = FileTime.from(Instant.parse("2026-10-10T10:11:12Z"))
  var expected: SaveFingerprint? = null; var attemptedReplacement = false; var retainedBackup: Path? = null
  assertFailsWith<SaveNotStableException> {
   Snapshots.restore(replacement, target, backup, Shattered, "v1", { true },
    beforeWrite = { attemptedReplacement = true },
    onBackupReady = { _, path ->
     retainedBackup = path
     // Same bytes defeat a content-only recheck; the changed mtime must still cancel replacement.
     target.resolve("game1/game.dat").writeText("current target")
     Files.setLastModifiedTime(target.resolve("game1/game.dat"), externalTime)
    },
    captureBackup = {
     val captured = guard.captureStable("demo", "v1", null)
     expected = captured.fingerprint; captured.snapshot
    },
    beforeReplacement = { guard.requireUnchanged(requireNotNull(expected)) })
  }
  assertFalse(attemptedReplacement)
  assertTrue(requireNotNull(retainedBackup).exists())
  assertEquals("current target", target.resolve("game1/game.dat").readText())
  assertEquals(externalTime, Files.getLastModifiedTime(target.resolve("game1/game.dat")))
  assertEquals("excluded", target.resolve("settings.xml").readText())
 }

 @Test fun profileRestoreWiresFinalBackupFingerprintCheckBeforeAnyReplacement() {
  val manager = session(root("old")); val original = manager.createSnapshot { true }
  manager.root.resolve("game1/game.dat").writeText("current")
  val externalTime = FileTime.from(Instant.parse("2026-10-10T11:12:13Z"))
  var injected = false
  assertFailsWith<SaveNotStableException> {
   manager.restoreHistory(original.manifest.id) {
    val backups = manager.directory.resolve("backups")
    if (!injected && backups.exists() && Files.list(backups).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".zip") } }) {
     injected = true
     manager.root.resolve("game1/game.dat").writeText("current")
     Files.setLastModifiedTime(manager.root.resolve("game1/game.dat"), externalTime)
    }
    true
   }
  }
  assertTrue(injected)
  assertEquals("current", manager.root.resolve("game1/game.dat").readText())
  assertEquals(externalTime, Files.getLastModifiedTime(manager.root.resolve("game1/game.dat")))
  assertEquals(2, manager.history().size)
  assertEquals("current", Snapshots.verify(manager.loadSnapshot(manager.history().first { it.manifest.id != original.manifest.id }.manifest.id), Shattered).getValue("game1/game.dat").decodeToString())
 }

}
