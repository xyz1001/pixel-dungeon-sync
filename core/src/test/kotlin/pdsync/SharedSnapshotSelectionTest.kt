package pdsync

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.*
import kotlin.io.path.*
import kotlin.test.*

/** Exact snapshot duplicates are one content choice, never evidence that two tips are one tip. */
class SharedSnapshotSelectionTest {
 @TempDir lateinit var temp: Path
 private var sequence = 0
 private val firstId = "11111111-1111-4111-8111-111111111111"
 private val secondId = "22222222-2222-4222-8222-222222222222"
 private val profile = "duplicates"
 private fun files(turn: Int = 1) = mapOf(
  "game1/game.dat" to """{"version":896,"init_ver":896,"turn":$turn}""".encodeToByteArray(),
  "game1/depth1.dat" to byteArrayOf(0, 1, 127, -1)
 )
 private class Store: AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  var writes = 0
  override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
  override fun readCommit(id: String) = commits[id]?.copyOf()
  override fun readObject(id: String) = objects[id]?.copyOf()
  override fun writeCommit(id: String, bytes: ByteArray) { writes++; commits[id] = bytes.copyOf() }
  override fun writeObject(id: String, bytes: ByteArray) { writes++; objects[id] = bytes.copyOf() }
  override fun legacyPresent() = false
  fun seed(commit: AppendCommit, zip: ByteArray) {
   commits[commit.id] = json.encodeToString(commit).encodeToByteArray()
   objects[commit.snapshot.id] = zip.copyOf()
  }
 }
 private fun session(): AppendProfileSession {
  val root = temp.resolve("save-${sequence++}").createDirectories()
  val versions = object: ProfileVersions {
   override fun annotate(snapshot: Snapshot) = snapshot
   override fun target(path: String): TargetGame? = null
   override fun checkRestore(snapshot: Snapshot, targetPath: String) = Unit
  }
  return AppendProfileSession(AppConfig(saveRoot = root.toString(), profile = profile),
   temp.resolve("state-${sequence++}"), versions = versions, captureQuietMillis = 0)
 }
 private fun repository(session: AppendProfileSession) = AppendRepository(session.directory.resolve("append-v2"), Shattered, profile)
 private fun seed(store: Store, child: Boolean = false): Pair<AppendCommit, AppendCommit> {
  val snapshot = Snapshots.fromFiles(files(), Shattered, profile, "synthetic")
  val first = AppendCommit(id = firstId, parents = emptyList(), snapshot = snapshot.manifest, objectBytes = snapshot.zip.size)
  val second = first.copy(id = secondId, parents = if (child) listOf(firstId) else emptyList())
  // Reverse insertion order must not change the representative.
  store.seed(second, snapshot.zip); store.seed(first, snapshot.zip)
  return first to second
 }
 private fun assertFiles(expected: Map<String, ByteArray>, root: Path) = expected.forEach { (name, bytes) ->
  assertContentEquals(bytes, root.resolve(name).readBytes())
 }

 @Test fun sessionDeduplicatesSnapshotListsButRetainsTwoTipsAndSkipsAutomaticUpload() {
  val store = Store(); val (first, second) = seed(store); val local = session()
  val remote = local.readRemote(store)
  assertEquals(first.id, remote.single().commit.id)
  assertEquals(first.snapshot, local.history().single().manifest)
  val history = local.syncHistory()
  assertEquals(first.id, history.cloud.single().id)
  assertEquals(setOf(first.id, second.id), history.graph.commits.keys)
  assertEquals(setOf(first.id, second.id), history.graph.tips)
  assertNull(history.defaultSnapshot)
  val comparison = local.compareOnboarding(store) { true }
  assertEquals(OnboardingKind.BRANCHED, comparison.kind)
  assertEquals(history.graph.tips, comparison.observedTips)
  assertEquals(first.id, comparison.remoteEntries.single().commit.id)
  assertIs<AutomaticPushResult.Skipped>(local.automaticPush(store) { true })
  assertFails { local.restoreRemote(first.snapshot.id, store) { true } }
  assertNull(local.baseline()); assertFalse(local.hasPending()); assertEquals(0, store.writes)
  assertFalse(local.root.resolve("game1").exists())
 }

 @Test fun readOnlyDuplicateSelectionUsesVerifiedTipAndKeepsEverySiblingUnresolved() {
  val store = Store(); val (first, second) = seed(store); val local = session()
  local.restoreSelectedReadOnly(local.compareOnboarding(store) { true }, first.snapshot.id, store, false) { true }
  assertFiles(files(), local.root)
  assertEquals(first.id, local.baseline()); assertFalse(local.hasPending())
  assertEquals(setOf(first.id, second.id), local.syncHistory().graph.tips)
  val reused=assertIs<AutomaticPushResult.Skipped>(local.automaticPush(store) { true })
  assertEquals(RemoteVerification.OBJECTS_VERIFIED,reused.comparison.verification)
  assertTrue(local.hasVerifiedUploadContent(reused.comparison))
  assertEquals(first.id,local.baseline());assertFalse(local.hasPending())
  assertEquals(setOf(first.id,second.id),local.syncHistory().graph.tips)
  assertEquals(2,store.commits.size);assertFiles(files(),local.root)
  assertEquals(0, store.writes)
 }

 @Test fun historicalDuplicateSelectionParentsOnlyRealBaselineAndDoesNotAbsorbSibling() {
  val store = Store(); val (first, second) = seed(store); val local = session()
  local.restoreSelectedReadOnly(local.compareOnboarding(store) { true }, first.snapshot.id, store, false) { true }
  local.root.resolve("game1/game.dat").writeText("unique-local")
  local.restoreCloudHistory(first.snapshot.id, store, true) { true }
  assertFiles(files(), local.root)
  assertNull(repository(local).pending())
  assertEquals(first.id,local.baseline())
  assertEquals(setOf(first.id,second.id),local.syncHistory().graph.commits.keys)
  assertEquals(0,store.writes)
 }

 @Test fun duplicateHistoryPrefersTipOverSmallerHistoricalCommitWithoutReparenting() {
  val store = Store(); val (first, second) = seed(store, child = true); val local = session()
  assertEquals(second.id, local.readRemote(store).single().commit.id)
  assertEquals(second.id, local.syncHistory().cloud.single().id)
  local.restoreSelectedReadOnly(local.compareOnboarding(store) { true }, first.snapshot.id, store, false) { true }
  assertEquals(second.id, local.baseline())
  assertEquals(listOf(first.id), local.syncHistory().graph.commits.getValue(second.id).parents)
  assertEquals(2, local.syncHistory().graph.commits.size)
  assertEquals(0, store.writes)
 }

 @Test fun explicitOnboardingDuplicateChoiceResolvesAllOriginalTips() {
  val store = Store(); val (first, second) = seed(store); val local = session()
  val restored = assertIs<OnboardingRestoreResult.Published>(local.onboardingRestore(
   local.compareOnboarding(store) { true }, first.snapshot.id, store) { true })
  val result = assertIs<AppendPushResult.Diverged>(restored.result)
  assertEquals(setOf(first.id,second.id),result.conflict.observedTips)
  assertEquals(first.id,local.baseline());assertFalse(local.hasPending())
  assertFiles(files(),local.root)
  assertEquals(2,local.syncHistory().graph.commits.size)
  assertEquals(setOf(first.id,second.id),local.syncHistory().graph.tips)
  assertEquals(0,store.writes)
 }

 @Test fun explicitConflictDuplicateChoiceResolvesAllTipsAndRejectsDifferentArchiveBytes() {
  val store = Store(); val (first, second) = seed(store); val local = session()
  val conflict = assertNotNull(local.inspectConflict(store) { true })
  assertEquals(2, conflict.branches.size)
  val damaged = conflict.copy(branches = conflict.branches.map {
   if (it.commit.id == second.id) it.copy(snapshot = it.snapshot.copy(zip = it.snapshot.zip + 0)) else it
  })
  assertFailsWith<RemoteIntegrityException> { local.resolve(damaged, first.snapshot.id, store) { true } }
  assertEquals(0, store.writes); assertFalse(local.hasPending())
  val result = assertIs<AppendPushResult.Diverged>(local.resolve(conflict, first.snapshot.id, store) { true })
  assertEquals(setOf(first.id, second.id), result.conflict.observedTips)
  assertEquals(first.id,local.baseline());assertFalse(local.hasPending());assertEquals(0,store.writes)
  assertFiles(files(), local.root)
 }

 @Test fun sameUuidAndArchiveWithDifferentFullManifestBlocksSessionBeforeRestoreOrUpload() {
  val store = Store(); val (first, second) = seed(store)
  val different = second.copy(snapshot = second.snapshot.copy(version = "different-metadata"))
  store.seed(different, store.objects.getValue(first.snapshot.id))
  val local = session()
  assertFailsWith<RemoteIntegrityException> { local.readRemote(store) }
  val comparison = local.compareOnboarding(store) { true }
  assertEquals(OnboardingKind.BLOCKED, comparison.kind)
  assertEquals(SyncBlockReason.REMOTE_INTEGRITY_FAILED, comparison.blockReason)
  assertFailsWith<RemoteIntegrityException> { local.restoreCloudHistory(first.snapshot.id, store, true) { true } }
  assertIs<AutomaticPushResult.Skipped>(local.automaticPush(store) { true })
  assertTrue(local.history().isEmpty()); assertNull(local.baseline()); assertFalse(local.hasPending())
  assertFalse(local.root.resolve("game1").exists()); assertEquals(0, store.writes)
 }

 private val androidIdentity = AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon", "Synthetic game",
  "a".repeat(64), AndroidSaveCapabilities(802, 896, AndroidMergePolicy.SHATTERED_896))
 private val connection = AndroidConnection("https://dav.example.test/synthetic/", "synthetic", "test-only", profile)
 private inner class Bridge: AndroidGameBridge {
  var content = emptyMap<String, ByteArray>()
  override fun targetGame() = AndroidGameTarget(androidIdentity, "synthetic-896", 896, "b".repeat(64), "c".repeat(64))
  override fun exportTo(emptyDestination: Path) {
   content.forEach { (name, bytes) -> emptyDestination.resolve(name).also { it.parent.createDirectories(); it.writeBytes(bytes) } }
  }
  override fun restoreFrom(preparedDirectory: Path, expectedCurrentFiles: Map<String, String>) {
   require(content.mapValues { hash(it.value) } == expectedCurrentFiles)
   content = Files.walk(preparedDirectory).use { paths -> paths.filter { it.isRegularFile() }.toList().associate {
    preparedDirectory.relativize(it).toString().replace('\\', '/') to it.readBytes()
   } }
  }
 }
 private fun androidStore(): Pair<Store, AppendCommit> {
  val store = Store(); val bridge = Bridge().also { it.content = files() }
  val source = AndroidSyncFacade(temp.resolve("android-source-${sequence++}"), connection, bridge, androidIdentity, store, 0)
  assertEquals("UPLOADED", source.autoUpload().status)
  val original = json.decodeFromString<AppendCommit>(store.commits.values.single().decodeToString())
  // Keep the complete original Manifest and object bytes, including metadata and source identity.
  store.commits.clear()
  val zip = store.objects.getValue(original.snapshot.id)
  val first = original.copy(schema=2,id=firstId,snapshot=original.snapshot.copy(id="33333333-3333-4333-8333-333333333333"))
  store.seed(first.copy(id = secondId), zip)
  store.seed(first, zip); store.writes = 0
  return store to first
 }

 @Test fun androidListsOneSnapshotAndReadOnlyRestoreKeepsTwoTipsAndVerifiedBaseline() {
  val (store, selected) = androidStore(); val bridge = Bridge()
  val facade = AndroidSyncFacade(temp.resolve("android-read-only"), connection, bridge, androidIdentity, store, 0)
  val inspection = facade.inspect()
  assertEquals("BRANCHED", inspection.kind)
  assertEquals(firstId, inspection.remoteVersions.single().commitId)
  assertEquals(firstId, facade.listVersions().single().commitId)
  val result = facade.restoreSelectedReadOnly(inspection, selected.snapshot.id, false)
  assertTrue(result.isConflict); assertFalse(result.isPending)
  assertEquals(selected.snapshot.files, bridge.content.mapValues { hash(it.value) })
  val history = facade.historyPresentation()
  assertEquals(firstId, history.baseline)
  assertEquals(setOf(firstId, secondId), history.graph.tips)
  assertEquals("UNCHANGED", facade.autoUpload().status)
  assertEquals(0, store.writes)
 }

 @Test fun androidExplicitRestoreAndConflictResolutionBothAcceptDuplicateSnapshotSelection() {
  for (resolveDirectly in listOf(false, true)) {
   val (store, selected) = androidStore(); val bridge = Bridge()
   val facade = AndroidSyncFacade(temp.resolve("android-choice-$resolveDirectly"), connection, bridge, androidIdentity, store, 0)
   val inspection = facade.inspect()
   val result = if (resolveDirectly) facade.resolveConflict(inspection, selected.snapshot.id)
    else facade.restoreSelected(inspection, selected.snapshot.id, false)
   assertTrue(result.isConflict); assertFalse(result.isPending)
   assertEquals(selected.snapshot.files, bridge.content.mapValues { hash(it.value) })
   val history = facade.historyPresentation()
   assertEquals(2,history.graph.commits.size);assertEquals(setOf(firstId,secondId),history.graph.tips)
   assertEquals(selected.id,history.baseline);assertEquals(0,store.writes)
  }
 }
}
