package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.encodeToString
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*

class OnboardingComparisonTest {
 @TempDir lateinit var temp: Path
 private var next = 0
 private class Memory: AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  var writes = 0
  var failWrites = false
  var failCommitWrites = false
  var failListing = false
  var hidden = false
  var hiddenObject: String? = null
  var hiddenCommit: String? = null
  var beforeObject: (() -> Unit)? = null
  var afterObject: ((String) -> Unit)? = null
  var afterCommit: (() -> Unit)? = null
  var afterReadObject: ((String) -> Unit)? = null
  override fun listCommits(cursor: String?): CommitPage {
   if (failListing) error("https://secret.invalid/?password=never-display")
   return CommitPage(if (hidden) emptyList() else commits.keys.toList())
  }
  override fun readCommit(id: String) = if (id == hiddenCommit) null else commits[id]?.copyOf()
  override fun readObject(id: String): ByteArray? {
   val bytes = if (id == hiddenObject) null else objects[id]?.copyOf()
   afterReadObject?.invoke(id)
   return bytes
  }
  override fun writeCommit(id: String, bytes: ByteArray) {
   if (failWrites || failCommitWrites) error("offline")
   writes++; commits[id] = bytes.copyOf(); afterCommit?.also { afterCommit = null }?.invoke()
  }
  override fun writeObject(id: String, bytes: ByteArray) {
   beforeObject?.also { beforeObject = null }?.invoke()
   if (failWrites) error("offline")
   writes++; objects[id] = bytes.copyOf(); afterObject?.also { afterObject = null }?.invoke(id)
  }
  override fun legacyPresent() = false
 }
 private fun root(value: String? = "alive") = temp.resolve("root-${next++}").createDirectory().also {
  if (value != null) { it.resolve("game1").createDirectory(); it.resolve("game1/game.dat").writeText(value) }
 }
 private fun versions(version: String) = object: ProfileVersions {
  override fun annotate(snapshot: Snapshot) = snapshot.copy(manifest = snapshot.manifest.copy(version = version))
  override fun target(path: String): TargetGame? = null
  override fun checkRestore(snapshot: Snapshot, targetPath: String) { require(snapshot.manifest.version == version) { "incompatible" } }
 }
 private fun session(value: String? = "alive", version: String = "v1") = AppendProfileSession(
  AppConfig(saveRoot = root(value).toString()), temp.resolve("state-${next++}"), versions = versions(version), captureQuietMillis = 0)
 private fun edit(session: AppendProfileSession, value: String) = session.root.resolve("game1/game.dat").writeText(value)
 private fun content(session: AppendProfileSession) = session.root.resolve("game1/game.dat").readText()
 private fun publish(session: AppendProfileSession, store: Memory) = assertIs<AppendPushResult.Success>(session.push(store) { true }).commit
 private fun compare(session: AppendProfileSession, store: Memory) = session.compareOnboarding(store) { true }
 private fun repository(session: AppendProfileSession) = AppendRepository(session.directory.resolve("append-v2"), Shattered, "demo")
 private fun dirtyBranchedBaseline(): Triple<Memory, AppendProfileSession, AppendCommit> {
  val store = Memory(); val local = session("base"); val base = publish(local, store)
  assertIs<AppendPushResult.Diverged>(session("sibling").push(store) { true }); edit(local, "dirty")
  return Triple(store, local, base)
 }

 @Test fun bothEmptyIsReadOnlyAndNeverUploads() {
  val local = session(null); val store = Memory(); val result = compare(local, store)
  assertEquals(OnboardingKind.BOTH_EMPTY, result.kind); assertTrue(result.localFiles.isEmpty()); assertNull(local.baseline())
  assertFails { local.onboardingUpload(result, store) { true } }
  assertEquals(0, store.writes); assertFalse(local.hasPending()); assertTrue(local.root.listDirectoryEntries().isEmpty())
 }

 @Test fun completingEmptyOrLinkedStateRevalidatesBeforeEnablingAnything() {
  val store = Memory(); val local = session(null); val empty = compare(local, store)
  local.confirmOnboardingReady(empty, store) { true }
  assertEquals(0, store.writes)
  assertNull(local.baseline()); assertFalse(local.hasPending())
  assertTrue(local.root.listDirectoryEntries().isEmpty())
  val appearing = session(null); val beforeAppearance = compare(appearing, store)
  appearing.root.resolve("game1").createDirectory(); appearing.root.resolve("game1/game.dat").writeText("new-save")
  assertFails { appearing.confirmOnboardingReady(beforeAppearance, store) { true } }
  assertEquals(0, store.writes)
  val remote = publish(session(), store)
  assertFails { local.confirmOnboardingReady(empty, store) { true } }
  val remoteOnly = compare(local, store)
  assertFails { local.confirmOnboardingReady(remoteOnly, store) { true } }
  local.onboardingRestore(remoteOnly, remote.snapshot.id, store) { true }
  val linked = compare(local, store); local.confirmOnboardingReady(linked, store) { true }
  edit(local, "changed")
  assertFails { local.confirmOnboardingReady(linked, store) { true } }
  assertEquals(2, store.writes)
 }

 @Test fun localOnlyWaitsForExplicitUpload() {
  val local = session(); val store = Memory(); val result = compare(local, store)
  assertEquals(OnboardingKind.LOCAL_ONLY, result.kind); assertEquals(0, store.writes); assertNull(local.baseline())
  assertEquals(java.nio.file.Files.getLastModifiedTime(local.root.resolve("game1/game.dat")).toInstant().toString(), result.localLatestModifiedAt)
  val done = assertIs<AppendPushResult.Success>(local.onboardingUpload(result, store) { true })
  assertEquals(done.commit.id, local.baseline()); assertEquals(2, store.writes)
  assertEquals(OnboardingLineage.SAME_TIP, compare(local, store).baselineLineage)
 }

 @Test fun remoteOnlyRestoresWithoutEmptyUpload() {
  val store = Memory(); val commit = publish(session(), store); val local = session(null)
  val result = compare(local, store); assertEquals(OnboardingKind.REMOTE_ONLY, result.kind)
  assertEquals(2, store.writes); assertTrue(local.root.listDirectoryEntries().isEmpty())
  assertIs<OnboardingRestoreResult.Restored>(local.onboardingRestore(result, commit.snapshot.id, store) { true })
  assertEquals("alive", content(local)); assertEquals(commit.id, local.baseline()); assertEquals(2, store.writes)
 }

 @Test fun equalUnbasedContentNeedsExplicitVerifiedAssociation() {
  val store = Memory(); val commit = publish(session(), store); val local = session()
  val result = compare(local, store)
  assertEquals(OnboardingKind.IDENTICAL, result.kind); assertEquals(OnboardingLineage.NONE, result.baselineLineage)
  assertTrue(result.canAdoptRemote); assertNull(local.baseline()); assertEquals(2, store.writes)
  assertEquals(commit.id, local.adoptOnboardingRemote(result, store) { true }.id)
  assertEquals(commit.id, local.baseline()); assertEquals("alive", content(local)); assertEquals(2, store.writes)
  assertFalse(local.hasPending()); assertEquals(OnboardingLineage.SAME_TIP, compare(local, store).baselineLineage)
 }

 @Test fun equalDescendantDoesNotSilentlyAdvanceBaseline() {
  val store = Memory(); val remote = session(); val first = publish(remote, store)
  val local = session(); local.adoptOnboardingRemote(compare(local, store), store) { true }
  remote.renameCurrent("Same game bytes, new metadata") { true }
  val second = publish(remote, store)
  val result = compare(local, store)
  assertEquals(OnboardingKind.IDENTICAL, result.kind); assertEquals(OnboardingLineage.ANCESTOR_OF_TIP, result.baselineLineage)
  assertEquals(first.id, local.baseline())
  local.adoptOnboardingRemote(result, store) { true }; assertEquals(second.id, local.baseline()); assertEquals(4, store.writes)
 }

 @Test fun associationOfIdenticalBytesDoesNotRequireRestoreCompatibility() {
  val store = Memory(); publish(session(version = "v2"), store); val local = session()
  val result = compare(local, store); assertEquals(OnboardingKind.IDENTICAL, result.kind)
  val associated = local.adoptOnboardingRemote(result, store) { true }
  assertEquals(associated.id, local.baseline()); assertEquals(2, store.writes); assertEquals("alive", content(local))
 }

 @Test fun comparisonObjectsAreDeeplyImmutableAndBoundToSession() {
  val store = Memory(); publish(session(), store); val local = session(); val result = compare(local, store)
  assertFails { (result.localFiles as MutableMap)["game1/game.dat"] = "fake" }
  assertFails { (result.observedTips as MutableSet).clear() }
  assertFails { (result.remoteEntries as MutableList).clear() }
  assertFails { (result.remoteEntries.single().commit.snapshot.files as MutableMap).clear() }
  assertFails { session().adoptOnboardingRemote(result, store) { true } }; assertNull(local.baseline())
 }

 @Test fun localEditInvalidatesEveryChoiceBeforeWrites() {
  val store = Memory(); val commit = publish(session(), store); val local = session(); val result = compare(local, store)
  edit(local, "edited")
  assertFails { local.adoptOnboardingRemote(result, store) { true } }
  assertFails { local.onboardingUpload(result, store) { true } }
  assertFails { local.onboardingRestore(result, commit.snapshot.id, store) { true } }
  assertEquals(2, store.writes); assertEquals("edited", content(local)); assertFalse(local.hasPending())
 }

 @Test fun remoteOrBaselineChangeInvalidatesOldComparison() {
  val store = Memory(); val remote = session(); val first = publish(remote, store); val local = session()
  val old = compare(local, store); edit(remote, "new"); publish(remote, store)
  assertFails { local.adoptOnboardingRemote(old, store) { true } }
  assertFails { local.onboardingRestore(old, first.snapshot.id, store) { true } }
  val current = compare(local, store)
  local.onboardingUpload(current, store) { true }
  assertFails { local.onboardingUpload(current, store) { true } }
  assertEquals("alive", content(local))
 }

 @Test fun unknownAndIncompleteRemoteAreBlockedNeverEmpty() {
  val cases: List<(Memory, AppendCommit) -> Unit> = listOf(
   { store, commit -> store.commits[commit.id] = """{"schema":999}""".encodeToByteArray() },
   { store, commit -> store.objects.remove(commit.snapshot.id) },
   { store, commit -> store.objects[commit.snapshot.id] = byteArrayOf(1, 2, 3) },
   { store, _ -> store.commits[UUID.randomUUID().toString()] = "{}".encodeToByteArray() }
  )
  cases.forEach { corrupt ->
   val store = Memory(); val commit = publish(session(), store); corrupt(store, commit)
   val local = session(null); val result = compare(local, store)
   assertEquals(OnboardingKind.BLOCKED, result.kind); assertFalse(result.retryablePending); assertNotNull(result.blocker)
   assertFails { local.onboardingUpload(result, store) { true } }; assertNull(local.baseline())
  }
 }

 @Test fun missingRememberedCommitCannotTurnIntoEmptyCloud() {
  val store = Memory(); val commit = publish(session(), store); val local = session(null)
  assertEquals(OnboardingKind.REMOTE_ONLY, compare(local, store).kind)
  store.hidden = true; store.commits.remove(commit.id)
  assertEquals(OnboardingKind.BLOCKED, compare(local, store).kind); assertNull(local.baseline()); assertEquals(2, store.writes)
 }

 @Test fun missingAncestorBlocksEvenWhenLatestObjectIsComplete() {
  val store = Memory(); val remote = session(); val ancestor = publish(remote, store)
  edit(remote, "next"); publish(remote, store); store.objects.remove(ancestor.snapshot.id)
  assertEquals(OnboardingKind.BLOCKED, compare(session(null), store).kind)
 }

 @Test fun errorsRemainBlockedButRunningStatusDoesNotGateAnEmptyComparison() {
  val store = Memory(); store.failListing = true; val local = session(null)
  val error = compare(local, store)
  assertEquals(OnboardingKind.BLOCKED, error.kind); assertFalse(error.blocker!!.contains("secret")); assertFalse(error.blocker.contains("password"))
  val running = local.compareOnboarding(Memory()) { false }
  assertEquals(OnboardingKind.BOTH_EMPTY, running.kind); assertEquals(0, store.writes)
 }

 @Test fun comparisonUsesSamePhysicalRootLock() {
  val local = session(); val lock = local.directory.parent.parent.resolve("locks/${hash(local.root.toString().encodeToByteArray())}.lock")
  FileChannel.open(lock, StandardOpenOption.WRITE).use { channel -> channel.lock().use {
   assertEquals(OnboardingKind.BLOCKED, compare(local, Memory()).kind)
  } }
 }

 @Test fun differentFirstRunOverwritesWithoutPublishingLocalInsurance() {
  val store = Memory(); val remote = publish(session("remote"), store); val local = session("local")
  val comparison = compare(local, store); assertEquals(OnboardingKind.DIFFERENT, comparison.kind); assertNull(local.baseline())
  assertIs<OnboardingRestoreResult.Restored>(local.onboardingRestore(comparison, remote.snapshot.id, store) { true })
  assertEquals("remote", content(local)); assertEquals(remote.id, local.baseline())
  assertEquals(1, store.commits.size); assertEquals(1, store.objects.size); assertEquals(2, store.writes)
  assertTrue(local.history().none { it.manifest.files["game1/game.dat"] == hash("local".encodeToByteArray()) })
 }

 @Test fun emptyLocalCanChooseAmongAllBranchesWithoutEmptyPublication() {
  val store = Memory(); val chosen = publish(session("a"), store); session("b").push(store) { true }
  val local = session(null); val comparison = compare(local, store)
  assertEquals(OnboardingKind.BRANCHED, comparison.kind); assertEquals(2, comparison.remoteEntries.size)
  val result = assertIs<OnboardingRestoreResult.Published>(local.onboardingRestore(comparison, chosen.snapshot.id, store) { true })
  val done = assertIs<AppendPushResult.Success>(result.result)
  assertEquals(comparison.observedTips, done.commit.parents.toSet()); assertEquals(3, store.commits.size); assertEquals("a", content(local))
 }

 @Test fun equalBytesWithUnrelatedBranchesRequireExplicitChoiceNotAdoption() {
  val store = Memory(); val first = publish(session(), store); val local = session()
  local.adoptOnboardingRemote(compare(local, store), store) { true }
  session().push(store) { true }
  val comparison = compare(local, store)
  assertEquals(OnboardingKind.BRANCHED, comparison.kind); assertEquals(OnboardingLineage.BRANCHED, comparison.baselineLineage)
  assertFalse(comparison.canAdoptRemote); assertFails { local.adoptOnboardingRemote(comparison, store) { true } }
  assertEquals(first.id, local.baseline()); assertEquals(2, store.commits.size)
  val result = assertIs<OnboardingRestoreResult.Published>(local.onboardingRestore(comparison, first.snapshot.id, store) { true })
  assertIs<AppendPushResult.Success>(result.result); assertEquals(3, store.commits.size)
 }

 @Test fun dirtyDivergenceAdoptsRemoteWithoutPublishingDirtyBytes() {
  val store = Memory(); val remote = session("base"); publish(remote, store); val local = session("base")
  local.adoptOnboardingRemote(compare(local, store), store) { true }
  edit(remote, "remote"); val chosen = publish(remote, store); edit(local, "dirty")
  assertIs<OnboardingRestoreResult.Restored>(local.onboardingRestore(compare(local, store), chosen.snapshot.id, store) { true })
  val graph = repository(local).discover(store)
  assertEquals(chosen.id, local.baseline()); assertEquals("remote", content(local)); assertEquals(2, graph.commits.size)
  assertEquals(4, store.writes)
  assertTrue(graph.commits.values.none { it.snapshot.files["game1/game.dat"] == hash("dirty".encodeToByteArray()) })
 }

 @Test fun dirtySameTipRestoreCreatesNoExtraCommitOrLocalBackup() {
  val store = Memory(); val local = session("base"); val base = publish(local, store); edit(local, "dirty")
  assertIs<OnboardingRestoreResult.Restored>(local.onboardingRestore(compare(local, store), base.snapshot.id, store) { true })
  assertEquals("base", content(local)); assertEquals(base.id, local.baseline()); assertEquals(1, store.commits.size)
  assertEquals(2, store.writes)
  assertTrue(local.history().none { it.manifest.files["game1/game.dat"] == hash("dirty".encodeToByteArray()) })
 }

 @Test fun dirtySelectedBaselineWithSiblingResolvesOnlyOriginalCloudParents() {
  val (store, local, base) = dirtyBranchedBaseline(); val comparison = compare(local, store)
  val originals = store.commits.mapValues { it.value.copyOf() }
  val originalObjects = store.objects.mapValues { it.value.copyOf() }
  val result = assertIs<OnboardingRestoreResult.Published>(local.onboardingRestore(comparison, base.snapshot.id, store) { true })
  val done = assertIs<AppendPushResult.Success>(result.result); val graph = repository(local).discover(store)
  assertEquals(comparison.observedTips, done.commit.parents.toSet())
  assertEquals("resolution", done.commit.kind); assertEquals(base.snapshot.files, done.commit.snapshot.files)
  assertEquals("base", content(local)); assertEquals(done.commit.id, local.baseline()); assertFalse(local.hasPending())
  assertEquals(setOf(done.commit.id), graph.tips); assertEquals(3, graph.commits.size); assertEquals(3, store.objects.size)
  originals.forEach { (id, bytes) -> assertContentEquals(bytes, store.commits.getValue(id)) }
  originalObjects.forEach { (id, bytes) -> assertContentEquals(bytes, store.objects.getValue(id)) }
  assertContentEquals(store.objects.getValue(base.snapshot.id), store.objects.getValue(done.commit.snapshot.id))
  assertTrue(local.history().none { it.manifest.files["game1/game.dat"] == hash("dirty".encodeToByteArray()) })
  assertTrue(graph.commits.values.none { it.snapshot.files["game1/game.dat"] == hash("dirty".encodeToByteArray()) })
 }

 @Test fun resolutionWriteAndReadbackFailuresRetryExactSelectedPlanWithoutInsurance() {
  val failures: List<(Memory, AppendProfileSession) -> Unit> = listOf(
   { store, _ -> store.failWrites = true },
   { store, _ -> store.failCommitWrites = true },
   { store, _ -> store.afterObject = { id -> store.hiddenObject = id } },
   { store, local -> store.afterCommit = { store.hiddenCommit = requireNotNull(repository(local).pending()).commit.id } }
  )
  failures.forEach { fail ->
   val (store, local, base) = dirtyBranchedBaseline(); val comparison = compare(local, store)
   val originalIds = store.commits.keys.toSet(); fail(store, local)
   assertFails { local.onboardingRestore(comparison, base.snapshot.id, store) { true } }
   val frozen = requireNotNull(repository(local).pending())
   assertEquals("resolution", frozen.commit.kind); assertEquals(comparison.observedTips, frozen.commit.parents.toSet())
   assertEquals(base.snapshot.files, frozen.commit.snapshot.files); assertEquals(base.id, local.baseline())
   assertEquals("base", content(local)); assertTrue(local.retryablePending())
   assertFalse(local.directory.resolve("append-v2/apply-required.txt").exists())
   assertTrue(store.commits.keys.all { it in originalIds || it == frozen.commit.id })
   edit(local, "newer"); store.failWrites = false; store.failCommitWrites = false
   store.hiddenObject = null; store.hiddenCommit = null
   val retry = compare(local, store); assertTrue(retry.retryablePending)
   val done = assertIs<AppendPushResult.Success>(local.onboardingUpload(retry, store) { true })
   assertEquals(frozen.commit, done.commit); assertTrue(done.localDirty)
   assertEquals(originalIds + frozen.commit.id, store.commits.keys)
   assertContentEquals(frozen.manifestBytes, store.commits.getValue(frozen.commit.id))
   assertContentEquals(frozen.zip, store.objects.getValue(frozen.commit.snapshot.id))
   assertEquals("newer", content(local)); assertEquals(frozen.commit.id, local.baseline()); assertFalse(local.hasPending())
  }
 }

 @Test fun selectedBaselineResolutionUploadFailureRetriesFrozenChoiceAfterLaterLocalEdit() {
  val (store, local, base) = dirtyBranchedBaseline(); val comparison = compare(local, store)
  store.failCommitWrites = true
  assertFails { local.onboardingRestore(comparison, base.snapshot.id, store) { true } }
  val frozen = requireNotNull(repository(local).pending())
  assertEquals("resolution", frozen.commit.kind); assertEquals(base.snapshot.files, frozen.commit.snapshot.files)
  assertEquals(comparison.observedTips, frozen.commit.parents.toSet())
  assertEquals("base", content(local)); assertTrue(local.retryablePending()); assertEquals(2, store.commits.size)
  assertFalse(local.directory.resolve("append-v2/apply-required.txt").exists())
  edit(local, "newer"); store.failCommitWrites = false
  val done = assertIs<AppendPushResult.Success>(local.onboardingUpload(compare(local, store), store) { true })
  assertEquals(frozen.commit, done.commit); assertTrue(done.localDirty); assertEquals("newer", content(local))
  assertEquals(3, store.commits.size); assertFalse(local.hasPending())
  assertContentEquals(frozen.manifestBytes, store.commits.getValue(done.commit.id))
  assertContentEquals(frozen.zip, store.objects.getValue(done.commit.snapshot.id))
 }

 @Test fun selectedBaselineLocalEditDuringVerificationNeverRestoresOverNewerBytes() {
  val (store, local, base) = dirtyBranchedBaseline(); val comparison = compare(local, store)
  store.afterReadObject = { store.afterReadObject = null; edit(local, "newer") }
  assertFails { local.onboardingRestore(comparison, base.snapshot.id, store) { true } }
  assertEquals("newer", content(local)); assertEquals(base.id, local.baseline())
  assertEquals(2, store.commits.size); assertEquals(4, store.writes); assertFalse(local.hasPending())
 }

 @Test fun selectedBaselineLateTipDuringVerificationNeverGetsAbsorbed() {
  listOf(1, 2).forEach { readToChange ->
   val (store, local, base) = dirtyBranchedBaseline(); val comparison = compare(local, store)
   val other = Memory(); val late = publish(session("late"), other); var selectedReads = 0
   store.afterReadObject = { id ->
    if (id == base.snapshot.id && ++selectedReads == readToChange) {
     store.afterReadObject = null; store.commits.putAll(other.commits); store.objects.putAll(other.objects)
    }
   }
   assertFails { local.onboardingRestore(comparison, base.snapshot.id, store) { true } }
   assertEquals(readToChange, selectedReads); assertEquals("dirty", content(local))
   assertEquals(3, store.commits.size); assertEquals(4, store.writes); assertFalse(local.hasPending())
   val graph = repository(local).discover(store)
   assertTrue(late.id in graph.tips); assertTrue(graph.commits.values.none { it.kind == "resolution" })
  }
 }

 @Test fun lateSiblingDuringResolutionPublicationRemainsAnIndependentBranch() {
  val (store, local, base) = dirtyBranchedBaseline(); val comparison = compare(local, store)
  val other = Memory(); val late = publish(session("late"), other)
  store.afterCommit = { store.commits.putAll(other.commits); store.objects.putAll(other.objects) }
  val result = assertIs<OnboardingRestoreResult.Published>(local.onboardingRestore(comparison, base.snapshot.id, store) { true })
  val conflict = assertIs<AppendPushResult.Diverged>(result.result).conflict
  val resolution = repository(local).discover(store).commits.getValue(requireNotNull(local.baseline()))
  assertEquals("resolution", resolution.kind); assertEquals(comparison.observedTips, resolution.parents.toSet())
  assertEquals(setOf(resolution.id, late.id), conflict.observedTips)
  assertEquals("base", content(local)); assertEquals(4, store.commits.size); assertEquals(6, store.writes)
  assertFalse(local.hasPending())
 }

 @Test fun selectedBaselineImmutableCommitOrObjectChangeBeforeApplyIsRejected() {
  val changes: List<(Memory, AppendCommit) -> Unit> = listOf(
   { store, base -> store.commits[base.id] = json.encodeToString(base.copy(kind = "restore")).encodeToByteArray() },
   { store, base -> store.objects[base.snapshot.id] = byteArrayOf(1, 2, 3) }
  )
  changes.forEach { change ->
   val (store, local, base) = dirtyBranchedBaseline(); val comparison = compare(local, store)
   val original = store.objects.getValue(base.snapshot.id).copyOf(); var selectedReads = 0
   store.afterReadObject = { id ->
    if (id == base.snapshot.id) { selectedReads++; store.afterReadObject = null; change(store, base) }
   }
   assertFails { local.onboardingRestore(comparison, base.snapshot.id, store) { true } }
   assertEquals(1, selectedReads); assertEquals("dirty", content(local)); assertEquals(2, store.commits.size)
   assertEquals(4, store.writes); assertFalse(local.hasPending())
   assertFalse(local.directory.resolve("append-v2/apply-required.txt").exists())
   assertContentEquals(original, local.loadSnapshot(base.snapshot.id).zip)
  }
 }

 @Test fun ordinaryResolveAndFreshComparisonStillRejectHistoricalBaselineAfterPreservation() {
  val (store, local, base) = dirtyBranchedBaseline(); val original = compare(local, store)
  val conflict = assertIs<AppendPushResult.Diverged>(local.push(store) { true }).conflict
  val historical = AppendBranch(base, local.loadSnapshot(base.snapshot.id))
  assertFails { local.resolve(conflict, base.snapshot.id, store) { true } }
  assertFails { local.resolve(conflict.copy(branches = conflict.branches + historical), base.snapshot.id, store) { true } }
  assertFails { local.onboardingRestore(compare(local, store), base.snapshot.id, store) { true } }
  assertFails { local.onboardingRestore(original, base.snapshot.id, store) { true } }
  assertEquals("dirty", content(local)); assertEquals(3, store.commits.size); assertFalse(local.hasPending())
 }

 @Test fun lateSiblingDuringVerificationRejectsSoleTipRestoreWithoutPublication() {
  val store = Memory(); val chosen = publish(session("remote"), store)
  val other = Memory(); val late = publish(session("late"), other)
  val local = session("local"); val comparison = compare(local, store)
  store.afterReadObject = {
   store.afterReadObject = null; store.commits.putAll(other.commits); store.objects.putAll(other.objects)
  }
  assertFails { local.onboardingRestore(comparison, chosen.snapshot.id, store) { true } }
  assertEquals("local", content(local)); assertEquals(2, store.commits.size); assertEquals(2, store.writes)
  assertNull(local.baseline()); assertFalse(local.hasPending()); assertTrue(late.id in repository(local).discover(store).tips)
 }

 @Test fun editDuringRemoteVerificationNeverOverwritesNewerLocalBytes() {
  val store = Memory(); val chosen = publish(session("remote"), store); val local = session("local")
  val comparison = compare(local, store)
  store.afterReadObject = { store.afterReadObject = null; edit(local, "newer") }
  assertFails { local.onboardingRestore(comparison, chosen.snapshot.id, store) { true } }
  assertEquals("newer", content(local)); assertEquals(1, store.commits.size); assertEquals(2, store.writes)
 }

 @Test fun frozenPendingRetryIsExplicitAndRetainsOriginalBytesAfterLaterEdits() {
  val store = Memory(); val local = session("frozen"); val comparison = compare(local, store)
  store.failWrites = true; assertFails { local.onboardingUpload(comparison, store) { true } }
  val frozen = repository(local).pending()!!; edit(local, "newer"); store.failWrites = false
  val retry = compare(local, store)
  assertEquals(OnboardingKind.BLOCKED, retry.kind); assertTrue(retry.retryablePending); assertEquals(frozen.commit.id, retry.pendingCommitId)
  assertFails { local.onboardingRestore(retry, frozen.commit.snapshot.id, store) { true } }
  val done = assertIs<AppendPushResult.Success>(local.onboardingUpload(retry, store) { true })
  assertEquals(frozen.commit, done.commit); assertTrue(done.localDirty); assertEquals("newer", content(local))
  assertContentEquals(frozen.zip, store.objects.getValue(frozen.commit.snapshot.id))
 }

 @Test fun frozenPendingDoesNotBypassBrokenCloudOrInterruptedApplyGate() {
  val store = Memory(); val local = session(); store.failWrites = true
  assertFails { local.onboardingUpload(compare(local, store), store) { true } }
  store.failListing = true; val unavailable = compare(local, store)
  assertEquals(OnboardingKind.BLOCKED, unavailable.kind); assertFalse(unavailable.retryablePending)
  store.failListing = false; local.directory.resolve("append-v2/apply-required.txt").writeText("restore")
  val interrupted = compare(local, store); assertEquals(OnboardingKind.BLOCKED, interrupted.kind); assertFalse(interrupted.retryablePending)
  assertFails { local.onboardingUpload(interrupted, store) { true } }; assertEquals(0, store.writes)
 }

 @Test fun incompatibleRemoteChoiceDoesNotUploadOrOverwriteLocalFirst() {
  val store = Memory(); val chosen = publish(session("remote", "v2"), store); val local = session("local")
  assertFails { local.onboardingRestore(compare(local, store), chosen.snapshot.id, store) { true } }
  assertEquals("local", content(local)); assertEquals(1, store.commits.size); assertFalse(local.hasPending())
 }
}
