package pdsync

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.*
import kotlin.test.*

class LocalProgressOnboardingTest {
 @TempDir lateinit var temp: Path
 private var index = 0
 private class Memory : AppendStore {
  val commits = linkedMapOf<String, ByteArray>(); val objects = linkedMapOf<String, ByteArray>(); var writes = 0
  override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
  override fun readCommit(id: String) = commits[id]?.copyOf()
  override fun readObject(id: String) = objects[id]?.copyOf()
  override fun writeCommit(id: String, bytes: ByteArray) { writes++; commits[id] = bytes.copyOf() }
  override fun writeObject(id: String, bytes: ByteArray) { writes++; objects[id] = bytes.copyOf() }
  override fun legacyPresent() = false
 }
 private fun session(): AppendProfileSession {
  val name = "local-${index++}"; val root = temp.resolve(name).createDirectory()
  root.resolve("game1").createDirectory(); root.resolve("game1/game.dat").writeText("{\"version\":896,\"turn\":1}")
  return AppendProfileSession(AppConfig(saveRoot = root.toString()), temp.resolve("$name-state"))
 }
 private fun edit(local: AppendProfileSession, turn: Int = 2) = local.root.resolve("game1/game.dat").writeText("{\"version\":896,\"turn\":$turn}")
 private fun compare(local: AppendProfileSession, store: Memory) = local.compareOnboarding(store) { true }
 private fun setup(): Triple<AppendProfileSession, Memory, AppendCommit> {
  val local = session(); val store = Memory(); val base = assertIs<AppendPushResult.Success>(local.push(store) { true }).commit
  edit(local); return Triple(local, store, base)
 }
 @Test fun ordinaryLocalProgressCanCompleteOnboardingWithoutWritingAnything() {
  val (local, store, base) = setup(); val comparison = compare(local, store)
  val before = SaveStabilityGuard(local.root, local.adapter, { true }).fingerprint()
  val sidecar = local.root.resolve(DIRECTORY_METADATA_FILE).readBytes()
  assertEquals(OnboardingKind.DIFFERENT, comparison.kind); assertEquals(OnboardingLineage.SAME_TIP, comparison.baselineLineage)
  local.confirmLocalProgressReady(comparison, store) { true }
  assertEquals(base.id, local.baseline()); assertEquals(2, store.writes); assertFalse(local.hasPending())
  assertEquals(before, SaveStabilityGuard(local.root, local.adapter, { true }).fingerprint()); assertContentEquals(sidecar, local.root.resolve(DIRECTORY_METADATA_FILE).readBytes())
 }
 @Test fun changedLocalBytesRejectAnOldDecision() {
  val (local, store, base) = setup(); val comparison = compare(local, store); edit(local, 3)
  assertFails { local.confirmLocalProgressReady(comparison, store) { true } }; assertEquals(base.id, local.baseline()); assertEquals(2, store.writes)
 }
 @Test fun sameBytesButChangedFilesystemMetadataStillReject() {
  val (local, store, _) = setup(); val comparison = compare(local, store); val path = local.root.resolve("game1/game.dat")
  Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 2000))
  assertFails { local.confirmLocalProgressReady(comparison, store) { true } }; assertEquals(2, store.writes)
 }
 @Test fun remoteAdvanceRejectsDecisionAndDoesNotWriteLocalProgress() {
  val (local, store, base) = setup(); val comparison = compare(local, store)
  val remote = session(); remote.adoptOnboardingRemote(compare(remote, store), store) { true }; edit(remote, 4); remote.push(store) { true }
  assertFails { local.confirmLocalProgressReady(comparison, store) { true } }; assertEquals(base.id, local.baseline()); assertEquals(4, store.writes)
  assertFails { local.confirmLocalProgressReady(compare(local, store), store) { true } }
 }
 @Test fun foreignObservationAndUnbasedLocalProgressNeverComplete() {
  val (local, store, _) = setup(); val comparison = compare(local, store); val other = session(); edit(other, 5)
  assertFails { other.confirmLocalProgressReady(comparison, store) { true } }
  assertFails { other.confirmLocalProgressReady(compare(other, store), store) { true } }; assertNull(other.baseline()); assertEquals(2, store.writes)
 }
 @Test fun runningStatusAllowsDecisionButPendingOperationsStillRejectIt() {
  val (local, store, _) = setup(); val comparison = compare(local, store)
  local.confirmLocalProgressReady(comparison, store) { false }
  val repository = AppendRepository(local.directory.resolve("append-v2"), local.adapter, local.config.profile)
  val snapshot = local.loadSnapshot(local.history().first().manifest.id)
  repository.prepare(snapshot, listOf(local.baseline()!!))
  assertFails { local.confirmLocalProgressReady(comparison, store) { true } }; assertEquals(2, store.writes)
 }
 @Test fun identicalAndLocalOnlyStatesUseTheirExistingDecisions() {
  val local = session(); val store = Memory()
  assertFails { local.confirmLocalProgressReady(compare(local, store), store) { true } }
  local.push(store) { true }
  assertFails { local.confirmLocalProgressReady(compare(local, store), store) { true } }
 }
}
