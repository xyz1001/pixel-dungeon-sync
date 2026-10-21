package pdsync

import io.github.xyz1001.pdsync.android.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

/** Host proof policy exercised against real core comparisons, hashes and in-memory append stores. */
class DesktopSharedComparisonFlowTest {
 @TempDir lateinit var temp: Path
 private var serial = 0
 private class Memory : AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  var failWrites = false
  var failListing = false
  override fun listCommits(cursor: String?): CommitPage {
   check(!failListing) { "unavailable" }; return CommitPage(commits.keys.toList())
  }
  override fun readCommit(id: String) = commits[id]?.copyOf()
  override fun readObject(id: String) = objects[id]?.copyOf()
  override fun writeCommit(id: String, bytes: ByteArray) { check(!failWrites) { "offline" }; commits[id] = bytes.copyOf() }
  override fun writeObject(id: String, bytes: ByteArray) { check(!failWrites) { "offline" }; objects[id] = bytes.copyOf() }
  override fun legacyPresent() = false
 }
 private fun session(content: String? = "alive"): AppendProfileSession {
  val root = temp.resolve("saves-${serial++}").createDirectory()
  if (content != null) { root.resolve("game1").createDirectory(); root.resolve("game1/game.dat").writeText(content) }
  val versions = object : ProfileVersions {
   override fun annotate(snapshot: Snapshot) = snapshot.copy(manifest = snapshot.manifest.copy(version = "v1"))
   override fun target(path: String): TargetGame? = null
   override fun checkRestore(snapshot: Snapshot, targetPath: String) { require(snapshot.manifest.version == "v1") }
  }
  return AppendProfileSession(AppConfig(saveRoot = root.toString(), username = "account-a"),
   temp.resolve("state-${serial++}"), versions = versions, captureQuietMillis = 0)
 }
 private fun publish(manager: AppendProfileSession, store: Memory) = assertIs<AppendPushResult.Success>(manager.push(store) { true })
 private fun context(manager: AppendProfileSession, observed: OnboardingComparison) =
  DesktopComparisonContext(1, manager, observed, manager.config, pageActive = true, busy = false, connected = true)

 @Test fun realEmptyAndIdenticalObservationsExposeOnlyExplicitFinishWithoutPerformingIt() {
  val store = Memory(); val manager = session(null)
  val empty = manager.compareOnboarding(store) { true }
  val adapter = DesktopSharedComparisonAdapter().apply { observed(empty) }
  assertEquals(SharedComparisonCompletion.EMPTY, adapter.state(context(manager, empty)).completion)
  assertEquals(SharedComparisonPrimary.FINISH, adapter.state(context(manager, empty)).primary)
  assertFalse(desktopCanFinishAfterOperation(false, true, empty), "inspection alone cannot complete setup")
  assertTrue(store.commits.isEmpty())
  val populated = session(); publish(populated, store)
  val identical = populated.compareOnboarding(store) { true }
  adapter.observed(identical)
  val state = adapter.state(context(populated, identical))
  assertEquals(SharedComparisonCompletion.CONFIRM, state.completion)
  assertEquals(SharedComparisonPrimary.FINISH, state.primary)
  assertFalse(state.showsBack)
  assertNull(state.confirmation)
 }

 @Test fun unbasedIdenticalUsesAdoptAndLocalOnlyNeedsExplicitProgressChoice() {
  val store = Memory(); val remote = session(); publish(remote, store)
  val unbased = session(); val identical = unbased.compareOnboarding(store) { true }
  val adapter = DesktopSharedComparisonAdapter().apply { observed(identical) }
  assertEquals(SharedComparisonCompletion.ADOPT, adapter.state(context(unbased, identical)).completion)
  assertNull(unbased.baseline())
  val local = session("local-only"); val localOnly = local.compareOnboarding(Memory()) { true }
  adapter.observed(localOnly)
  val current = context(local, localOnly)
  assertEquals(SharedComparisonCompletion.NONE, adapter.state(current).completion)
  adapter.selectProgress(current, SharedProgressChoice.LOCAL)
  assertEquals(SharedComparisonCompletion.UPLOAD_LOCAL, adapter.state(current).completion)
  assertFalse(local.hasPending())
 }

 @Test fun keepLocalRequiresExactUniqueVerifiedBaselineRatherThanEqualLookingLabels() {
  val store = Memory(); val manager = session(); publish(manager, store)
  manager.root.resolve("game1/game.dat").writeText("new local progress")
  val localProgress = manager.compareOnboarding(store) { true }
  assertTrue(canKeepDesktopLocalProgress(localProgress))
  val adapter = DesktopSharedComparisonAdapter().apply { observed(localProgress) }
  val current = context(manager, localProgress)
  adapter.selectProgress(current, SharedProgressChoice.LOCAL)
  assertEquals(SharedComparisonCompletion.KEEP_LOCAL, adapter.state(current).completion)
  manager.confirmLocalProgressReady(localProgress, store) { true }
  val unbased = session("new local progress")
  val different = unbased.compareOnboarding(store) { true }
  assertFalse(canKeepDesktopLocalProgress(different))
  adapter.observed(different)
  adapter.selectProgress(context(unbased, different), SharedProgressChoice.LOCAL)
  assertEquals(SharedComparisonPrimary.HANDLE_CONFLICT, adapter.state(context(unbased, different)).primary)
 }

 @Test fun retainedConfirmationCannotCrossAccountSessionPageObservationOrBusyState() {
  val store = Memory(); publish(session("cloud"), store)
  val manager = session("local")
  val observed = manager.compareOnboarding(store) { true }
  val adapter = DesktopSharedComparisonAdapter().apply { observed(observed) }
  val current = context(manager, observed)
  val proof = assertNotNull(adapter.openConflict(current))
  val token = proof.token
  assertNotNull(adapter.confirmation(current, token, SharedComparisonConfirmationStage.CONFLICT))
  val changedObservation = manager.compareOnboarding(store) { true }
  for (changed in listOf(
   current.copy(generation = 2), current.copy(pageActive = false), current.copy(busy = true),
   current.copy(connected = false), current.copy(config = manager.config.copy(username = "account-b")),
   current.copy(session = session("local")), current.copy(observed = changedObservation),
  )) {
   assertNull(adapter.confirmation(changed, token, SharedComparisonConfirmationStage.CONFLICT))
  }
  adapter.cancel(current, token)
  assertNull(adapter.confirmation(current, token, SharedComparisonConfirmationStage.CONFLICT))
  assertNull(adapter.dialog)
 }

 @Test fun multiHeadSelectionChangesTokenAndNeverSilentlyPicksABranch() {
  val store = Memory(); publish(session("one"), store); session("two").push(store) { true }
  val manager = session("local")
  val observed = manager.compareOnboarding(store) { true }
  assertEquals(OnboardingKind.BRANCHED, observed.kind)
  val adapter = DesktopSharedComparisonAdapter().apply { observed(observed) }
  val current = context(manager, observed)
  assertNull(adapter.selectedId)
  val before = assertNotNull(adapter.openConflict(current))
  assertFalse(adapter.state(current).confirmationPresentation!!.confirmEnabled)
  val chosen = observed.remoteEntries.last().entry.manifest.id
  adapter.selectRemote(current, chosen)
  val after = assertNotNull(adapter.dialog)
  assertEquals(chosen, after.proof.selectedId)
  assertTrue(after.proof.token > before.token)
  assertNull(adapter.confirmation(current, before.token, SharedComparisonConfirmationStage.CONFLICT))
  assertNotNull(adapter.confirmation(current, after.proof.token, SharedComparisonConfirmationStage.CONFLICT))
  adapter.observed(manager.compareOnboarding(store) { true })
  assertNull(adapter.dialog)
  assertNull(adapter.selectedId)
 }

 @Test fun realUploadFinishCompletesOnceOnlyAfterSuccessAndFreshCoreConfirmation() {
  val store = Memory(); val manager = session()
  val before = manager.compareOnboarding(store) { true }
  val result = manager.onboardingUpload(before, store) { true }
  assertTrue(desktopOnboardingOperationSucceeded(result))
  val fresh = manager.compareOnboarding(store) { true }
  assertTrue(desktopCanFinishAfterOperation(true, desktopOnboardingOperationSucceeded(result), fresh))
  assertFalse(desktopCanFinishAfterOperation(false, true, fresh))
  assertFalse(desktopCanFinishAfterOperation(true, false, fresh))
  manager.confirmOnboardingReady(fresh, store) { true }
  assertEquals(OnboardingKind.IDENTICAL, fresh.kind)
  assertFalse(manager.hasPending())
  assertEquals(1, store.commits.size)
 }

 @Test fun failedFrozenUploadAndUnverifiedCloudNeverBecomeCompletedOrEmpty() {
  val store = Memory(); val manager = session()
  val before = manager.compareOnboarding(store) { true }
  store.failWrites = true
  assertFails { manager.onboardingUpload(before, store) { true } }
  val frozen = manager.compareOnboarding(store) { true }
  assertTrue(frozen.retryablePending)
  assertFalse(desktopCanFinishAfterOperation(true, false, frozen))
  assertFalse(desktopCanFinishAfterOperation(true, true, frozen))
  val adapter = DesktopSharedComparisonAdapter().apply { observed(frozen) }
  assertEquals(SharedComparisonPrimary.RETRY_UPLOAD, adapter.state(context(manager, frozen)).primary)
  store.failListing = true
  val failed = manager.compareOnboarding(store) { true }
  adapter.observed(failed)
  val state = adapter.state(context(manager, failed))
  assertEquals(OnboardingKind.BLOCKED, state.kind)
  assertEquals(SharedComparisonCompletion.NONE, state.completion)
  assertEquals(SharedComparisonPrimary.REFRESH, state.primary)
  assertFalse(state.cloudVerified)
 }

 @Test fun failedOrDivergedReturnValuesCannotAuthorizeAutomaticCompletion() {
  val diverged = AppendPushResult.Diverged(AppendConflict(emptySet(), emptyList(), emptyMap()))
  val prepared = AppendPushResult.Prepared(publish(session("prepared"), Memory()).commit)
  assertFalse(desktopOnboardingOperationSucceeded(prepared))
  assertFalse(desktopOnboardingOperationSucceeded(OnboardingRestoreResult.Published(prepared)))
  assertFalse(desktopOnboardingOperationSucceeded(diverged))
  assertFalse(desktopOnboardingOperationSucceeded(OnboardingRestoreResult.Published(diverged)))
  assertFalse(desktopOnboardingOperationSucceeded(null))
  assertFalse(desktopOnboardingOperationSucceeded(Unit))
 }

 @Test fun proofKeepsTheCapturedObservationAndCoreRejectsLaterLocalMutation() {
  val store = Memory(); publish(session("cloud"), store)
  val manager = session("local")
  val observed = manager.compareOnboarding(store) { true }
  val adapter = DesktopSharedComparisonAdapter().apply { observed(observed) }
  val current = context(manager, observed)
  val proof = assertNotNull(adapter.openConflict(current))
  manager.root.resolve("game1/game.dat").writeText("changed after opening dialog")
  // UI identity alone is not a filesystem proof. The production callback supplies this exact
  // observed object to the core, whose lock/fingerprint gate rejects the changed bytes.
  assertTrue(adapter.matches(current, proof))
  assertFails { proof.session.onboardingRestore(proof.observed, assertNotNull(proof.selectedId), store) { true } }
  assertEquals("changed after opening dialog", manager.root.resolve("game1/game.dat").readText())
 }
 @Test fun realMergePreviewChangesTokenAndConfirmedApplyUsesTheExactObservedProof() {
  val target = TargetGame("fixture.jar", "3.3.8", 896, "a".repeat(64), true, 802, "test")
  val versions = object : ProfileVersions {
   override fun annotate(snapshot: Snapshot) = snapshot.copy(manifest = snapshot.manifest.copy(
    saveVersions = ShatteredVersions.detect(Snapshots.verify(snapshot, Shattered))))
   override fun target(path: String) = target
   override fun checkRestore(snapshot: Snapshot, targetPath: String) {
    ShatteredVersions.requireCompatible(ShatteredVersions.detect(Snapshots.verify(snapshot, Shattered)), target)
   }
  }
  fun mergeSession(populated: Boolean): AppendProfileSession {
   val id = serial++
   val root = temp.resolve("merge-$id").createDirectory()
   if (populated) (1..2).forEach { slot ->
    root.resolve("game$slot").createDirectory()
    root.resolve("game$slot/game.dat").writeText("""{"version":896,"hero":{"name":"hero-$slot"}}""")
    root.resolve("game$slot/depth1.dat").writeText("base-$slot")
   }
   return AppendProfileSession(AppConfig(saveRoot = root.toString()), temp.resolve("merge-state-$id"),
    versions = versions, captureQuietMillis = 0)
  }
  val store = Memory(); val remote = mergeSession(true); val manager = mergeSession(false)
  val base = publish(remote, store).commit
  manager.restoreRemote(base.snapshot.id, store) { true }
  remote.root.resolve("game1/depth1.dat").writeText("remote-progress")
  remote.confirmRunIdentity("game1", remote.observedFiles(), true) { true }
  publish(remote, store)
  manager.root.resolve("game2/depth1.dat").writeText("local-progress")
  manager.confirmRunIdentity("game2", manager.observedFiles(), true) { true }
  val observed = manager.compareOnboarding(store) { true }
  val adapter = DesktopSharedComparisonAdapter().apply { observed(observed) }
  val current = context(manager, observed)
  val initial = assertNotNull(adapter.openConflict(current))
  assertTrue(adapter.dialog!!.presentation.canPreviewMerge)
  val commitsBefore = store.commits.size
  val result = manager.previewOnboardingMerge(initial.observed, store) { true }
  assertEquals(commitsBefore, store.commits.size, "preview cannot publish")
  assertTrue(adapter.acceptMergePreview(current.copy(busy = true), initial, result))
  val reviewed = assertNotNull(adapter.dialog)
  assertEquals(SharedComparisonConfirmationStage.MERGE_REVIEW, reviewed.presentation.stage)
  assertTrue(reviewed.presentation.canApplyMerge)
  assertSame(observed, reviewed.proof.observed)
  assertTrue(reviewed.proof.token > initial.token)
  assertNull(adapter.confirmation(current, initial.token, SharedComparisonConfirmationStage.CONFLICT))
  val confirmed = assertNotNull(adapter.confirmation(current, reviewed.proof.token, SharedComparisonConfirmationStage.MERGE_REVIEW))
  val applied = manager.mergeCurrent(confirmed.observed, store) { true }
  assertTrue(desktopOnboardingOperationSucceeded(applied))
  val fresh = manager.compareOnboarding(store) { true }
  assertTrue(desktopCanFinishAfterOperation(true, true, fresh))
  manager.confirmOnboardingReady(fresh, store) { true }
  adapter.observed(fresh)
  assertNull(adapter.confirmation(current, reviewed.proof.token, SharedComparisonConfirmationStage.MERGE_REVIEW))
 }

 @Test fun staleLocalFinishDropsOldIdenticalProofAndCanCompareAgain() {
  val store = Memory(); val manager = session(); publish(manager, store)
  val old = manager.compareOnboarding(store) { true }
  val adapter = DesktopSharedComparisonAdapter().apply { observed(old) }
  var current = context(manager, old)
  assertEquals(SharedComparisonCompletion.CONFIRM, adapter.state(current).completion)
  assertFalse(adapter.state(current).showsBack)
  val proof = assertNotNull(adapter.capture(current))
  manager.root.resolve("game1/game.dat").writeText("changed local progress")
  assertFails { manager.confirmOnboardingReady(proof.observed, store) { true } }
  assertTrue(adapter.invalidateFailedCompletion(current.copy(busy = true), proof))
  current = current.copy(observed = null)
  assertEquals(SharedComparisonPrimary.REFRESH, adapter.state(current).primary)
  assertTrue(adapter.state(current).primaryEnabled)
  assertTrue(adapter.state(current).showsBack)
  val fresh = manager.compareOnboarding(store) { true }
  adapter.observed(fresh); current = current.copy(observed = fresh)
  assertEquals(OnboardingKind.DIFFERENT, fresh.kind)
  adapter.selectProgress(current, SharedProgressChoice.LOCAL)
  assertEquals(SharedComparisonCompletion.KEEP_LOCAL, adapter.state(current).completion)
  val freshProof = assertNotNull(adapter.capture(current))
  manager.confirmLocalProgressReady(freshProof.observed, store) { true }
  assertFalse(adapter.invalidateFailedCompletion(current, proof), "late old failures cannot clear the new observation")
 }

 @Test fun staleRemoteFinishDropsOldIdenticalProofAndCanChooseFreshCloudProgress() {
  val store = Memory(); val manager = session(); val base = publish(manager, store).commit
  val old = manager.compareOnboarding(store) { true }
  val adapter = DesktopSharedComparisonAdapter().apply { observed(old) }
  var current = context(manager, old)
  val proof = assertNotNull(adapter.capture(current))
  val remote = session(null)
  remote.restoreRemote(base.snapshot.id, store) { true }
  remote.root.resolve("game1/game.dat").writeText("new cloud progress")
  publish(remote, store)
  assertFails { manager.confirmOnboardingReady(proof.observed, store) { true } }
  assertTrue(adapter.invalidateFailedCompletion(current.copy(busy = true), proof))
  current = current.copy(observed = null)
  assertEquals(SharedComparisonPrimary.REFRESH, adapter.state(current).primary)
  assertTrue(adapter.state(current).primaryEnabled)
  val fresh = manager.compareOnboarding(store) { true }
  adapter.observed(fresh); current = current.copy(observed = fresh)
  assertEquals(SharedComparisonPrimary.HANDLE_CONFLICT, adapter.state(current).primary)
  val freshProof = assertNotNull(adapter.openConflict(current))
  val result = manager.onboardingRestore(freshProof.observed, assertNotNull(freshProof.selectedId), store) { true }
  assertTrue(desktopOnboardingOperationSucceeded(result))
  val ready = manager.compareOnboarding(store) { true }
  assertTrue(desktopCanFinishAfterOperation(true, true, ready))
  manager.confirmOnboardingReady(ready, store) { true }
  assertEquals("new cloud progress", manager.root.resolve("game1/game.dat").readText())
 }

}
