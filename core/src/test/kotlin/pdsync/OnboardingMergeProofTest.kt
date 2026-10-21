package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*
import kotlin.test.*

/** The setup dialog must apply the same observation it previewed, including its owner and graph. */
class OnboardingMergeProofTest {
 @TempDir lateinit var temp: Path
 private var next = 0
 private val target = TargetGame("fixture.jar", "3.3.8", 896, "a".repeat(64), true, 802, "test")
 private val policy = object : ProfileVersions {
  override fun annotate(snapshot: Snapshot) = snapshot.copy(manifest = snapshot.manifest.copy(
   saveVersions = ShatteredVersions.detect(Snapshots.verify(snapshot, Shattered))))
  override fun target(path: String) = target
  override fun checkRestore(snapshot: Snapshot, targetPath: String) {
   ShatteredVersions.requireCompatible(ShatteredVersions.detect(Snapshots.verify(snapshot, Shattered)), target)
  }
 }

 private class Memory : AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  var writes = 0
  var failWrites = false
  var failListing = false
  var afterListing: (() -> Unit)? = null
  var afterCommitWrite: ((String) -> Unit)? = null
  override fun listCommits(cursor: String?): CommitPage {
   check(!failListing) { "offline" }
   val page = CommitPage(commits.keys.toList())
   afterListing?.also { afterListing = null }?.invoke()
   return page
  }
  override fun readCommit(id: String) = commits[id]?.copyOf()
  override fun readObject(id: String) = objects[id]?.copyOf()
  override fun writeCommit(id: String, bytes: ByteArray) {
   check(!failWrites) { "offline" }
   writes++; commits[id] = bytes.copyOf()
   afterCommitWrite?.also { afterCommitWrite = null }?.invoke(id)
  }
  override fun writeObject(id: String, bytes: ByteArray) {
   check(!failWrites) { "offline" }
   writes++; objects[id] = bytes.copyOf()
  }
  override fun legacyPresent() = false
 }

 private data class Fixture(val store: Memory, val remote: AppendProfileSession, val local: AppendProfileSession)

 private fun session(populated: Boolean): AppendProfileSession {
  val id = next++
  val root = temp.resolve("game-$id").createDirectory()
  if (populated) (1..2).forEach { slot ->
   root.resolve("game$slot").createDirectory()
   root.resolve("game$slot/game.dat").writeText("""{"version":896,"hero":{"name":"hero-$slot"}}""")
   root.resolve("game$slot/depth1.dat").writeText("base-$slot")
  }
  return AppendProfileSession(AppConfig(saveRoot = root.toString(), profile = "demo"),
   temp.resolve("state-$id"), versions = policy, captureQuietMillis = 0)
 }

 private fun edit(session: AppendProfileSession, slot: Int, value: String) {
  session.root.resolve("game$slot/depth1.dat").writeText(value)
  session.confirmRunIdentity("game$slot", session.observedFiles(), true) { true }
 }

 private fun fixture(): Fixture {
  val store = Memory()
  val remote = session(true)
  val local = session(false)
  val base = assertIs<AppendPushResult.Success>(remote.push(store) { true }).commit
  local.restoreRemote(base.snapshot.id, store) { true }
  edit(remote, 1, "remote-1")
  assertIs<AppendPushResult.Success>(remote.push(store) { true })
  edit(local, 2, "local-2")
  return Fixture(store, remote, local)
 }

 private fun repository(session: AppendProfileSession) =
  AppendRepository(session.directory.resolve("append-v2"), Shattered, "demo")

 private fun files(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
  paths.filter { Files.isRegularFile(it) }.toList().associate {
   root.relativize(it).toString() to it.readBytes().toList()
  }
 }

 private fun compare(fixture: Fixture) = fixture.local.compareOnboarding(fixture.store) { true }

 private fun assertRejectedWithoutWrites(
  fixture: Fixture,
  observed: OnboardingComparison,
  owner: AppendProfileSession = fixture.local,
 ) {
  val beforeFiles = files(owner.root)
  val beforeWrites = fixture.store.writes
  // Read persisted proof directly: this helper also runs while another operation owns the lock.
  val repository = repository(owner)
  val beforeBaseline = repository.baseline()
  val beforePending = repository.pending()?.commit
  listOf<() -> Unit>(
   { owner.previewOnboardingMerge(observed, fixture.store) { true }; Unit },
   { owner.mergeCurrent(observed, fixture.store) { true }; Unit },
  ).forEach { action ->
   assertFails { action() }
   assertEquals(beforeWrites, fixture.store.writes)
   assertEquals(beforeFiles, files(owner.root))
   assertEquals(beforeBaseline, repository.baseline())
   assertEquals(beforePending, repository.pending()?.commit)
  }
 }

 @Test fun currentObservationPreviewsWithoutWritesAndAppliesTheSameRunMerge() {
  val fixture = fixture()
  val observed = compare(fixture)
  assertEquals(OnboardingKind.DIFFERENT, observed.kind)
  val beforeFiles = files(fixture.local.root)
  val beforeWrites = fixture.store.writes
  val beforeBaseline = fixture.local.baseline()
  val preview = fixture.local.previewOnboardingMerge(observed, fixture.store) { true }
  assertEquals(2, preview.slots)
  assertEquals(0, preview.addedSlots)
  assertEquals(beforeFiles, files(fixture.local.root))
  assertEquals(beforeWrites, fixture.store.writes)
  assertEquals(beforeBaseline, fixture.local.baseline())
  assertFalse(fixture.local.hasPending())

  val result = assertIs<AppendPushResult.Prepared>(fixture.local.mergeCurrent(observed, fixture.store) { true })
  assertEquals("snapshot", result.commit.kind)
  assertEquals(preview.snapshot.manifest.files, fixture.local.observedFiles())
  assertEquals("remote-1", fixture.local.root.resolve("game1/depth1.dat").readText())
  assertEquals("local-2", fixture.local.root.resolve("game2/depth1.dat").readText())
  assertEquals(beforeBaseline, fixture.local.baseline())
  assertTrue(fixture.local.hasPending()); assertEquals(result.commit, repository(fixture.local).pending()?.commit)
  assertEquals(beforeWrites, fixture.store.writes)
  val graph = repository(fixture.local).discover(fixture.store)
  assertEquals(observed.observedTips, graph.tips)
  assertFalse(result.commit.id in graph.commits)
  assertTrue(result.commit.parents.containsAll(observed.observedTips))
 }

 @Test fun observedMultiheadMergeRetainsEveryRealBranchAsAnAncestor() {
  val fixture = fixture()
  assertIs<AppendPushResult.Diverged>(fixture.local.push(fixture.store) { true })
  val observed = compare(fixture)
  assertEquals(OnboardingKind.BRANCHED, observed.kind)
  assertEquals(2, observed.observedTips.size)
  val preview = fixture.local.previewOnboardingMerge(observed, fixture.store) { true }
  val beforeWrites = fixture.store.writes
  val beforeBaseline = fixture.local.baseline()
  val result = assertIs<AppendPushResult.Prepared>(fixture.local.mergeCurrent(observed, fixture.store) { true })
  assertEquals("resolution", result.commit.kind)
  assertEquals(2, result.commit.parents.size)
  assertEquals(beforeWrites, fixture.store.writes); assertEquals(beforeBaseline, fixture.local.baseline())
  assertEquals(observed.observedTips, repository(fixture.local).discover(fixture.store).tips)
  assertTrue(fixture.local.hasPending()); assertEquals(result.commit, repository(fixture.local).pending()?.commit)
  val uploaded = assertIs<AppendPushResult.Success>(fixture.local.push(fixture.store) { true })
  assertEquals(result.commit, uploaded.commit)
  val graph = repository(fixture.local).discover(fixture.store)
  assertTrue(observed.observedTips.all { graph.descendsFrom(result.commit.id, it) })
  assertEquals(setOf(result.commit.id), graph.tips)
  assertEquals(preview.snapshot.manifest.files, fixture.local.observedFiles())
  assertFalse(fixture.local.hasPending())
 }

 @Test fun anotherSessionOrAccountCannotUseTheDisplayedObservation() {
  val fixture = fixture()
  val observed = compare(fixture)
  val other = AppendProfileSession(fixture.local.config.copy(username = "other-account"),
   temp.resolve("other-account-state"), versions = policy, captureQuietMillis = 0)
  assertRejectedWithoutWrites(fixture, observed, other)
 }

 @Test fun localEditAfterPreviewInvalidatesBothPreviewAndApply() {
  val fixture = fixture()
  val observed = compare(fixture)
  fixture.local.previewOnboardingMerge(observed, fixture.store) { true }
  edit(fixture.local, 2, "newer-local-2")
  assertRejectedWithoutWrites(fixture, observed)
 }

 @Test fun newCloudTipAfterPreviewCannotBeSilentlyAddedToTheMerge() {
  val fixture = fixture()
  val observed = compare(fixture)
  fixture.local.previewOnboardingMerge(observed, fixture.store) { true }
  edit(fixture.remote, 1, "newer-remote-1")
  assertIs<AppendPushResult.Success>(fixture.remote.push(fixture.store) { true })
  assertRejectedWithoutWrites(fixture, observed)
 }

 @Test fun cloudChangeBetweenRevalidationAndPreflightCannotReplaceTheOriginalProof() {
  listOf(false, true).forEach { apply ->
   val fixture = fixture()
   val observed = compare(fixture)
   val beforeFiles = files(fixture.local.root)
   val beforeBaseline = fixture.local.baseline()
   var writesAfterOtherDevice = -1
   // The first discovery returns the old listing; the preflight's second discovery sees the
   // later tip. It must reject, rather than recapture that newer graph as the user's selection.
   fixture.store.afterListing = {
    edit(fixture.remote, 1, "arrived-during-validation")
    assertIs<AppendPushResult.Success>(fixture.remote.push(fixture.store) { true })
    writesAfterOtherDevice = fixture.store.writes
   }
   val failure = assertFailsWith<IllegalArgumentException> {
    if (apply) fixture.local.mergeCurrent(observed, fixture.store) { true }
    else fixture.local.previewOnboardingMerge(observed, fixture.store) { true }
   }
   assertContains(failure.message.orEmpty(), "冲突分支已变化")
   assertTrue(writesAfterOtherDevice >= 0)
   assertEquals(writesAfterOtherDevice, fixture.store.writes)
   assertEquals(beforeFiles, files(fixture.local.root))
   assertEquals(beforeBaseline, fixture.local.baseline())
   assertFalse(fixture.local.hasPending())
  }
 }

 @Test fun changedBaselineAfterPreviewInvalidatesTheDisplayedObservation() {
  val fixture = fixture()
  val observed = compare(fixture)
  fixture.local.previewOnboardingMerge(observed, fixture.store) { true }
  val repository = repository(fixture.local)
  val graph = repository.discover(fixture.store)
  val otherBaseline = observed.observedTips.single()
  assertNotEquals(observed.baselineId, otherBaseline)
  repository.setBaseline(otherBaseline, graph)
  assertRejectedWithoutWrites(fixture, observed)
 }

 @Test fun siblingArrivingDuringExplicitMergedUploadRemainsIndependent() {
  val store = Memory()
  val remote = session(true)
  val local = session(false)
  val lateDevice = session(false)
  val base = assertIs<AppendPushResult.Success>(remote.push(store) { true }).commit
  local.restoreRemote(base.snapshot.id, store) { true }
  lateDevice.restoreRemote(base.snapshot.id, store) { true }
  edit(remote, 1, "remote-1")
  assertIs<AppendPushResult.Success>(remote.push(store) { true })
  edit(local, 2, "local-2")
  edit(lateDevice, 1, "late-third-device")
  val observed = local.compareOnboarding(store) { true }
  val preview = local.previewOnboardingMerge(observed, store) { true }
  assertEquals(2, preview.slots)
  val beforeBaseline = local.baseline()
  val beforeWrites = store.writes
  val beforeCommits = store.commits.keys.toSet()
  var localCommitId: String? = null
  var lateCommitId: String? = null
  store.afterCommitWrite = { id ->
   localCommitId = id
   assertIs<AppendPushResult.Diverged>(lateDevice.push(store) { true })
   lateCommitId = lateDevice.baseline()
  }

  val prepared = assertIs<AppendPushResult.Prepared>(local.mergeCurrent(observed, store) { true })
  assertEquals(beforeWrites, store.writes); assertEquals(beforeCommits, store.commits.keys)
  assertEquals(beforeBaseline, local.baseline()); assertTrue(local.hasPending())
  assertNull(localCommitId); assertNull(lateCommitId)
  assertEquals(preview.snapshot.manifest.files, local.observedFiles())
  val result = assertIs<AppendPushResult.Diverged>(local.push(store) { true })
  assertEquals(prepared.commit.id, localCommitId)
  val newLocal = assertNotNull(localCommitId)
  val newLate = assertNotNull(lateCommitId)
  assertEquals(setOf(newLocal,newLate), result.conflict.observedTips)
  assertEquals(beforeCommits + newLocal + newLate, store.commits.keys)
  assertEquals(preview.snapshot.manifest.files, local.observedFiles())
  assertEquals("remote-1", local.root.resolve("game1/depth1.dat").readText())
  assertEquals("local-2", local.root.resolve("game2/depth1.dat").readText())
  assertEquals(newLocal, local.baseline())
  assertFalse(local.hasPending())
  val graph = repository(local).discover(store)
  assertTrue(graph.commits.values.none { it.kind == "resolution" })
  assertEquals(result.conflict.observedTips, graph.tips)

  // Only a new, explicit observation may include that third source in its reviewed result.
  val refreshed = local.compareOnboarding(store) { true }
  assertEquals(OnboardingKind.BRANCHED, refreshed.kind)
  assertEquals(3, local.previewOnboardingMerge(refreshed, store) { true }.slots)
 }

 @Test fun failedUnknownComparisonCannotBecomeMergePermission() {
  val fixture = fixture()
  fixture.store.failListing = true
  val unknown = compare(fixture)
  assertEquals(OnboardingKind.BLOCKED, unknown.kind)
  assertFalse(unknown.retryablePending)
  fixture.store.failListing = false
  assertRejectedWithoutWrites(fixture, unknown)
 }

 @Test fun frozenPendingUploadRemainsUnchangedAndCannotBeMergedAgain() {
  val fixture = fixture()
  val observed = compare(fixture)
  fixture.store.failWrites = true
  assertFails { fixture.local.onboardingUpload(observed, fixture.store) { true } }
  fixture.store.failWrites = false
  val pending = assertNotNull(repository(fixture.local).pending())
  val retry = compare(fixture)
  assertEquals(OnboardingKind.BLOCKED, retry.kind)
  assertTrue(retry.retryablePending)
  assertRejectedWithoutWrites(fixture, retry)
  val retained = assertNotNull(repository(fixture.local).pending())
  assertEquals(pending.commit, retained.commit)
  assertContentEquals(pending.zip, retained.zip)
 }

 @Test fun interruptedApplyAndHeldPhysicalRootLockRemainHardGuards() {
  val fixture = fixture()
  val observed = compare(fixture)
  val marker = fixture.local.directory.resolve("append-v2/apply-required.txt")
  marker.writeText("restore")
  assertRejectedWithoutWrites(fixture, observed)
  assertEquals("restore", marker.readText())
  marker.deleteExisting()
  val lock = fixture.local.directory.parent.parent.resolve("locks/${hash(fixture.local.root.toString().encodeToByteArray())}.lock")
  FileChannel.open(lock, StandardOpenOption.WRITE).use { channel -> channel.lock().use {
   assertRejectedWithoutWrites(fixture, observed)
  } }
 }
}
