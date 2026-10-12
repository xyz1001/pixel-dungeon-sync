package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.*
import kotlin.test.*

class UnchangedBaselineLineageTest {
 @TempDir lateinit var temp: Path
 private class Memory : AppendStore {
  val commits = linkedMapOf<String, ByteArray>(); val objects = linkedMapOf<String, ByteArray>(); var writes = 0
  var failWrites = false
  override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
  override fun readCommit(id: String) = commits[id]?.copyOf()
  override fun readObject(id: String) = objects[id]?.copyOf()
  override fun writeCommit(id: String, bytes: ByteArray) { if(failWrites) error("offline"); writes++; commits[id] = bytes.copyOf() }
  override fun writeObject(id: String, bytes: ByteArray) { if(failWrites) error("offline"); writes++; objects[id] = bytes.copyOf() }
  override fun legacyPresent() = false
  fun child(parent: AppendCommit, manifest: Manifest = parent.snapshot, kind: String = "snapshot", parents: List<String> = listOf(parent.id)): AppendCommit {
   val snapshot = manifest.copy(id = UUID.randomUUID().toString())
   objects[snapshot.id] = objects.getValue(parent.snapshot.id).copyOf()
   val commit = parent.copy(id = UUID.randomUUID().toString(), parents = parents, snapshot = snapshot, kind = kind)
   commits[commit.id] = json.encodeToString(commit).encodeToByteArray()
   return commit
  }
 }
 private fun session(name: String = "local") = AppendProfileSession(AppConfig(saveRoot = temp.resolve(name).createDirectories().also {
  it.resolve("game1").createDirectories(); it.resolve("game1/game.dat").writeText("{\"version\":896,\"turn\":1}")
 }.toString()), temp.resolve("$name-state"), captureQuietMillis = 0)
 private fun setup(): Triple<AppendProfileSession, Memory, AppendCommit> {
  val local = session(); val store = Memory(); val first = assertIs<AppendPushResult.Success>(local.push(store) { true }).commit
  return Triple(local, store, first)
 }
 private fun edit(local: AppendProfileSession) = local.root.resolve("game1/game.dat").writeText("{\"version\":896,\"turn\":2}")
 private fun compare(local: AppendProfileSession, store: Memory) = local.compareOnboarding(store) { true }
 private fun fingerprint(local: AppendProfileSession) = SaveStabilityGuard(local.root, local.adapter, { true }, 0).fingerprint()
 private fun graph(vararg commits: AppendCommit) = CommitGraph(commits.associateBy { it.id }, emptyMap())

 @Test fun dirtyLocalProgressCanAdvanceOnlyTheVerifiedNoopAncestry() {
  val (local, store, first) = setup(); val second = store.child(first); val third = store.child(second, kind = "restore")
  edit(local)
  val comparison = compare(local, store)
  assertEquals(OnboardingKind.DIFFERENT, comparison.kind); assertEquals(OnboardingLineage.ANCESTOR_OF_TIP, comparison.baselineLineage)
  val before = fingerprint(local); val sidecar = local.root.resolve(DIRECTORY_METADATA_FILE).readBytes(); val writes = store.writes
  val transfer = local.directory.resolve("baseline-transfer.txt").readBytes()
  assertTrue(local.advanceUnchangedBaseline(comparison, store) { true })
  assertEquals(third.id, local.baseline()); assertEquals(before, fingerprint(local)); assertContentEquals(sidecar, local.root.resolve(DIRECTORY_METADATA_FILE).readBytes())
  assertContentEquals(transfer, local.directory.resolve("baseline-transfer.txt").readBytes())
  assertFalse(local.hasPending()); assertEquals(writes, store.writes)
  assertFails { local.onboardingUpload(comparison, store) { true } }
  val fresh = compare(local, store)
  assertEquals(OnboardingLineage.SAME_TIP, fresh.baselineLineage)
  val uploaded = assertIs<AppendPushResult.Success>(local.onboardingUpload(fresh, store) { true }).commit
  assertEquals(listOf(third.id), uploaded.parents)
 }
 @Test fun equalLocalBytesCanAdvanceWithoutSidecarAdoption() {
  val (local, store, first) = setup(); val second = store.child(first)
  val observation = compare(local, store); val sidecar = local.root.resolve(DIRECTORY_METADATA_FILE).readBytes()
  assertEquals(OnboardingKind.IDENTICAL, observation.kind); assertTrue(local.advanceUnchangedBaseline(observation, store) { true })
  assertEquals(second.id, local.baseline()); assertContentEquals(sidecar, local.root.resolve(DIRECTORY_METADATA_FILE).readBytes())
 }
 @Test fun uniqueTipAlreadyAtBaselineDoesNotAdvance() {
  val (local, store, first) = setup(); assertFalse(local.advanceUnchangedBaseline(compare(local, store), store) { true }); assertEquals(first.id, local.baseline())
 }
 @Test fun metadataOnlyChangesAndChangedThenRevertedChainsAreNotNoops() {
  val (_, store, base) = setup()
  val metadata = base.snapshot.directoryMetadata!!
  val variants = listOf(
   base.snapshot.copy(version = "another"), base.snapshot.copy(saveVersions = SaveVersions(mapOf("game1" to 922))),
   base.snapshot.copy(gamePackage = "org.example.game"), base.snapshot.copy(gameSource = GameSaveSource("a".repeat(64), "b".repeat(64))),
   base.snapshot.copy(directoryMetadata = metadata.copy(displayName = "renamed")),
   base.snapshot.copy(directoryMetadata = metadata.copy(runs = metadata.runs.mapValues { it.value.copy(runUUID = null, previousRunUUID = it.value.runUUID) })),
   base.snapshot.copy(directoryMetadata = metadata.copy(runs = metadata.runs.mapValues { it.value.copy(parentRunUUIDs = listOf(UUID.randomUUID().toString())) })),
   base.snapshot.copy(files = base.snapshot.files.mapValues { "f".repeat(64) })
  )
  for (changed in variants) {
   val intermediate = store.child(base, changed); val reverted = store.child(intermediate, base.snapshot)
   assertNull(unchangedDescendantTip(graph(base, intermediate), base.id, false))
   assertNull(unchangedDescendantTip(graph(base, intermediate, reverted), base.id, false))
  }
 }
 @Test fun presentationProvenanceAndArchiveIdsAreNotSaveSemantics() {
  val (_, store, base) = setup()
  val candidate = store.child(base, base.snapshot.copy(metadata = null))
  assertEquals(candidate, unchangedDescendantTip(graph(base, candidate), base.id, false))
 }
 @Test fun resolutionMultiTipUnbasedPendingAndIncompleteGraphsNeverAdvance() {
  val (_, store, base) = setup(); val tip = store.child(base); val sibling = store.child(base)
  assertNull(unchangedDescendantTip(graph(base, tip, sibling), base.id, false))
  assertNull(unchangedDescendantTip(graph(base, tip), null, false))
  assertNull(unchangedDescendantTip(graph(base, tip), base.id, true))
  assertNull(unchangedDescendantTip(graph(base, tip).copy(pending = mapOf(UUID.randomUUID().toString() to "missing")), base.id, false))
  assertNull(unchangedDescendantTip(graph(tip), base.id, false))
  val resolution = store.child(tip, kind = "resolution", parents = listOf(tip.id, sibling.id))
  assertNull(unchangedDescendantTip(graph(base, tip, sibling, resolution), base.id, false))
  assertNull(unchangedDescendantTip(graph(base, tip.copy(kind = "legacy-import")), base.id, false))
  assertNull(unchangedDescendantTip(CommitGraph(mapOf(base.id to base, "wrong-key" to tip), emptyMap()), base.id, false))
 }
 @Test fun changedLocalBytesAndMetadataInvalidateOldAdvance() {
  for (metadataOnly in listOf(false, true)) {
   val local = session("case-$metadataOnly"); val store = Memory(); val base = assertIs<AppendPushResult.Success>(local.push(store) { true }).commit
   store.child(base); val comparison = compare(local, store)
   if(metadataOnly) { val path = local.root.resolve("game1/game.dat"); Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 2000)) } else edit(local)
   assertFails { local.advanceUnchangedBaseline(comparison, store) { true } }; assertEquals(base.id, local.baseline()); assertEquals(2, store.writes)
  }
 }
 @Test fun remoteGraphAdvanceForeignComparisonAndMissingObjectsInvalidateAdvance() {
  val (local, store, base) = setup(); val child = store.child(base); val old = compare(local, store); store.child(child)
  assertFails { local.advanceUnchangedBaseline(old, store) { true } }
  assertFails { session("foreign").advanceUnchangedBaseline(compare(local, store), store) { true } }
  store.objects.remove(child.snapshot.id)
  val blocked = compare(local, store); assertEquals(OnboardingKind.BLOCKED, blocked.kind); assertEquals(SyncBlockReason.REMOTE_INCOMPLETE, blocked.blockReason)
  assertFails { local.advanceUnchangedBaseline(blocked, store) { true } }; assertEquals(base.id, local.baseline())
 }
 @Test fun pendingAndRecoveryNeverPermitAutomaticBaselineAdvance() {
  val (local, store, base) = setup(); store.child(base); edit(local); store.failWrites = true
  assertFails { local.push(store) { true } }; val pending = compare(local, store)
  assertEquals(SyncBlockReason.PENDING_UPLOAD, pending.blockReason); assertFails { local.advanceUnchangedBaseline(pending, store) { true } }
  local.directory.resolve("append-v2/apply-required.txt").writeText("interrupted")
  assertEquals(SyncBlockReason.RESTORE_RECOVERY_REQUIRED, compare(local, store).blockReason)
  assertEquals(base.id, local.baseline())
 }
}
