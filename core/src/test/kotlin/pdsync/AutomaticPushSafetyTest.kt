package pdsync

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Exercises the desktop's actual automatic entry with immutable store writes counted separately. */
class AutomaticPushSafetyTest {
 @TempDir lateinit var temp: Path
 private var sequence = 0
 private val versions = object: ProfileVersions {
  override fun annotate(snapshot: Snapshot) = snapshot
  override fun target(path: String): TargetGame? = null
  override fun checkRestore(snapshot: Snapshot, targetPath: String) = Unit
 }
 private class Memory: AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  var writes = 0
  var lists = 0
  var failWrites = false
  var failListing = false
  var failAfterCommit = false
  var onList: ((Int) -> Unit)? = null
  override fun legacyPresent() = false
  override fun listCommits(cursor: String?): CommitPage {
   if (failListing) throw IOException("synthetic unavailable history")
   onList?.invoke(++lists)
   return CommitPage(commits.keys.toList())
  }
  override fun readCommit(id: String) = commits[id]?.copyOf()
  override fun readObject(id: String) = objects[id]?.copyOf()
  override fun writeObject(id: String, bytes: ByteArray) {
   if (failWrites) throw IOException("synthetic upload unavailable")
   writes++; objects[id] = bytes.copyOf()
  }
  override fun writeCommit(id: String, bytes: ByteArray) {
   if (failWrites) throw IOException("synthetic upload unavailable")
   writes++; commits[id] = bytes.copyOf()
   if (failAfterCommit) failListing = true
  }
 }
 private fun session(content: String? = "original"): AppendProfileSession {
  val root = temp.resolve("save-${sequence++}").createDirectory()
  if (content != null) { root.resolve("game1").createDirectory(); root.resolve("game1/game.dat").writeText(content) }
  return AppendProfileSession(AppConfig(saveRoot = root.toString()), temp.resolve("state-${sequence++}"), versions = versions, captureQuietMillis = 0)
 }
 private fun edit(local: AppendProfileSession, content: String) {
  local.root.resolve("game1").createDirectories(); local.root.resolve("game1/game.dat").writeText(content)
 }
 private fun publish(local: AppendProfileSession, store: Memory) = assertIs<AppendPushResult.Success>(local.push(store) { true }).commit
 private fun repository(local: AppendProfileSession) = AppendRepository(local.directory.resolve("append-v2"), Shattered, local.config.profile)
 private fun skippedWithoutWrites(local: AppendProfileSession, store: Memory): OnboardingComparison {
  val writes = store.writes; val objects = store.objects.keys.toSet(); val commits = store.commits.keys.toSet()
  val baseline = local.baseline(); val pending = repository(local).pending()
  val result = assertIs<AutomaticPushResult.Skipped>(local.automaticPush(store) { true })
  assertEquals(writes, store.writes); assertEquals(objects, store.objects.keys); assertEquals(commits, store.commits.keys)
  assertEquals(baseline, local.baseline()); assertEquals(pending?.commit, repository(local).pending()?.commit)
  return result.comparison
 }

 @Test fun emptyCompletionSurvivesConfigurationAndSessionRestartWithoutSyncBaseline() {
  val local = session(null); val store = Memory()
  local.confirmOnboardingReady(local.compareOnboarding(store) { true }, store) { true }
  val file = ConfigFile(temp.resolve("config.json"))
  file.save(local.config.copy(selectedSaveRoot = local.root.toString(), onboardingProgress = OnboardingProgress.COMPLETE))
  val saved = assertNotNull(file.load())
  // Config reload restores the selected root, not a manufactured empty sync ancestry.
  val restarted = AppendProfileSession(saved.copy(saveRoot = saved.selectedSaveRoot!!),
   local.directory.parent.parent, versions = versions, captureQuietMillis = 0)
  assertEquals(OnboardingProgress.COMPLETE, OnboardingGate.resumedProgress(saved.onboardingProgress, true, restarted.baselineFiles() != null))
  assertNull(restarted.baseline()); assertFalse(restarted.hasPending())
  assertEquals(OnboardingKind.BOTH_EMPTY, skippedWithoutWrites(restarted, store).kind)
  assertEquals(0, store.writes); assertTrue(local.root.listDirectoryEntries().isEmpty())
 }

 @Test fun legacyOrImportedSettingsNeverBecomeCompletedFromEmptyHistory() {
  val path = temp.resolve("imported.json")
  path.writeText("""{"endpoint":"https://example.test/dav","profile":"demo","targetGame":"/synthetic.jar"}""")
  val legacy = assertNotNull(ConfigFile(path).load())
  assertEquals(OnboardingProgress.LEGACY, legacy.onboardingProgress)
  assertEquals(OnboardingProgress.PENDING, OnboardingGate.resumedProgress(legacy.onboardingProgress, true, false))
  assertEquals(OnboardingProgress.COMPLETE, OnboardingGate.resumedProgress(legacy.onboardingProgress, true, true))
  for (progress in OnboardingProgress.entries) {
   assertEquals(OnboardingProgress.PENDING, OnboardingGate.resumedProgress(progress, false, true))
  }
  for (progress in listOf(OnboardingProgress.PENDING, OnboardingProgress.DEFERRED)) {
   for (baseline in listOf(false, true)) assertEquals(progress, OnboardingGate.resumedProgress(progress, true, baseline))
  }
 }

 @Test fun threeStepConfigurationCompletionCannotInventAncestryOrPublishOverExistingRemote() {
  val store = Memory(); val remote = publish(session("remote progress"), store)
  for (content in listOf<String?>(null, "independent local progress", "remote progress")) {
   val local = session(content)
   val before = content?.let { local.root.resolve("game1/game.dat").readBytes() }
   val writes = store.writes
   val file = ConfigFile(temp.resolve("completed-${sequence++}.json"))
   // The third step saves only configuration. It has made no comparison or progress choice.
   file.save(local.config.copy(selectedSaveRoot = local.root.toString(), onboardingProgress = OnboardingProgress.COMPLETE))
   val saved = assertNotNull(file.load())
   val restarted = AppendProfileSession(saved.copy(saveRoot = saved.selectedSaveRoot!!),
    local.directory.parent.parent, versions = versions, captureQuietMillis = 0)
   assertEquals(OnboardingProgress.COMPLETE, OnboardingGate.resumedProgress(saved.onboardingProgress, true, false))
   assertNull(restarted.baseline()); assertFalse(restarted.hasPending()); assertEquals(writes, store.writes)
   // A later genuine exit event still runs the unchanged conservative automatic gate.
   val comparison = skippedWithoutWrites(restarted, store)
   assertEquals(OnboardingLineage.NONE, comparison.baselineLineage)
   assertEquals(setOf(remote.id), comparison.observedTips)
   assertNull(restarted.baseline()); assertFalse(restarted.hasPending())
   if (before == null) assertFalse(local.root.resolve("game1/game.dat").exists())
   else assertContentEquals(before, local.root.resolve("game1/game.dat").readBytes())
  }
 }

 @Test fun verifiedLocalOnlyEventCanCreateTheFirstRealCommit() {
  val local = session(); val store = Memory()
  val result = assertIs<AutomaticPushResult.Published>(local.automaticPush(store) { true })
  val commit = assertIs<AppendPushResult.Success>(result.result).commit
  assertTrue(commit.parents.isEmpty()); assertEquals(commit.id, local.baseline()); assertEquals(2, store.writes)
 }

 @Test fun sameTipLocalProgressPublishesWithTheRealParent() {
  val local = session(); val store = Memory(); val first = publish(local, store)
  edit(local, "later local progress")
  val result = assertIs<AutomaticPushResult.Published>(local.automaticPush(store) { true })
  val commit = assertIs<AppendPushResult.Success>(result.result).commit
  assertEquals(listOf(first.id), commit.parents); assertEquals(commit.id, local.baseline()); assertEquals(4, store.writes)
 }

 @Test fun oneUnknownRemoteTipDoesNotAuthorizeAnUnbasedRootOrEqualByteAssociation() {
  val store = Memory(); publish(session("remote"), store)
  for (content in listOf("independent local", "remote")) {
   val local = session(content); val result = skippedWithoutWrites(local, store)
   assertEquals(OnboardingLineage.NONE, result.baselineLineage)
   assertEquals(if (content == "remote") OnboardingKind.IDENTICAL else OnboardingKind.DIFFERENT, result.kind)
   assertNull(local.baseline()); assertFalse(local.hasPending())
  }
 }

 @Test fun remoteOnlyAndMultipleHeadsNeverPublishAutomatically() {
  val store = Memory(); publish(session("remote"), store)
  assertEquals(OnboardingKind.REMOTE_ONLY, skippedWithoutWrites(session(null), store).kind)
  assertIs<AppendPushResult.Diverged>(session("independent branch").push(store) { true })
  val local = session(); assertEquals(OnboardingKind.BRANCHED, skippedWithoutWrites(local, store).kind)
  assertNull(local.baseline()); assertFalse(local.hasPending())
 }

 @Test fun realRemoteAdvanceRequiresAChoiceEvenWithOneTip() {
  val store = Memory(); val local = session(); val first = publish(local, store)
  val remote = session(); remote.adoptOnboardingRemote(remote.compareOnboarding(store) { true }, store) { true }
  edit(remote, "remote advance"); publish(remote, store); edit(local, "local advance")
  assertEquals(OnboardingLineage.ANCESTOR_OF_TIP, skippedWithoutWrites(local, store).baselineLineage)
  assertEquals(first.id, local.baseline()); assertFalse(local.hasPending())
 }

 @Test fun failedOrIncompleteRemoteInspectionIsSkippedNeverTreatedAsEmpty() {
  val unavailable = Memory().apply { failListing = true }
  assertEquals(OnboardingKind.BLOCKED, skippedWithoutWrites(session(), unavailable).kind)
  val incomplete = Memory(); val remote = publish(session(), incomplete)
  incomplete.objects.remove(remote.snapshot.id)
  assertEquals(OnboardingKind.BLOCKED, skippedWithoutWrites(session(), incomplete).kind)
 }

 @Test fun frozenPendingRetriesOnlyItsExactOriginalBytesWithMatchingParents() {
  for (withBaseline in listOf(false, true)) {
   val store = Memory(); val local = session()
   val parent = if (withBaseline) publish(local, store).id else null
   edit(local, "frozen"); store.failWrites = true
   assertFailsWith<IOException> { local.push(store) { true } }
   val frozen = assertNotNull(repository(local).pending()); assertEquals(listOfNotNull(parent), frozen.commit.parents)
   edit(local, "later still-local progress"); store.failWrites = false
   val result = assertIs<AutomaticPushResult.Published>(local.automaticPush(store) { true })
   assertEquals(frozen.commit, assertIs<AppendPushResult.Success>(result.result).commit)
   assertContentEquals(frozen.zip, store.objects[frozen.commit.snapshot.id])
   assertContentEquals(frozen.manifestBytes, store.commits[frozen.commit.id]); assertFalse(local.hasPending())
   assertEquals("later still-local progress", local.root.resolve("game1/game.dat").readText())
  }
 }

 @Test fun pendingWithAdvancedRemoteParentsIsSkippedAndPreserved() {
  val store = Memory(); val local = session(); publish(local, store)
  val remote = session(); remote.adoptOnboardingRemote(remote.compareOnboarding(store) { true }, store) { true }
  edit(local, "frozen local"); store.failWrites = true
  assertFailsWith<IOException> { local.push(store) { true } }; store.failWrites = false
  val frozen = assertNotNull(repository(local).pending())
  edit(remote, "remote advance"); publish(remote, store)
  val comparison = skippedWithoutWrites(local, store)
  assertTrue(comparison.retryablePending); assertFalse(AutomaticUploadEligibility.allows(comparison))
  assertEquals(frozen.commit, repository(local).pending()?.commit)
 }

 @Test fun pendingAlreadyPublishedAsTheSoleTipCanFinishWithoutAnyNewWrite() {
  val store = Memory().apply { failAfterCommit = true }; val local = session()
  assertFailsWith<IOException> { local.push(store) { true } }
  val frozen = assertNotNull(repository(local).pending()); val writes = store.writes
  store.failAfterCommit = false; store.failListing = false
  val result = assertIs<AutomaticPushResult.Published>(local.automaticPush(store) { true })
  assertEquals(frozen.commit, assertIs<AppendPushResult.Success>(result.result).commit)
  assertEquals(writes, store.writes); assertEquals(frozen.commit.id, local.baseline()); assertFalse(local.hasPending())
 }

 @Test fun localChangeDuringProofRevalidationRejectsBeforePlanOrRemoteWrite() {
  val local = session(); val store = Memory()
  store.onList = { if (it == 2) edit(local, "changed after comparison") }
  assertFails { local.automaticPush(store) { true } }
  assertEquals(0, store.writes); assertTrue(store.commits.isEmpty()); assertTrue(store.objects.isEmpty())
  assertNull(local.baseline()); assertFalse(local.hasPending())
 }

 @Test fun remoteChangeDuringProofRevalidationRejectsBeforePlanOrRemoteWrite() {
  val remoteStore = Memory(); publish(session("new remote"), remoteStore)
  val store = Memory(); val local = session()
  store.onList = { if (it == 2) { store.commits.putAll(remoteStore.commits); store.objects.putAll(remoteStore.objects) } }
  assertFails { local.automaticPush(store) { true } }
  assertEquals(0, store.writes); assertEquals(remoteStore.commits.keys, store.commits.keys)
  assertNull(local.baseline()); assertFalse(local.hasPending())
 }

 @Test fun aRunningBooleanDoesNotBlockAnOtherwiseEligibleAutomaticUpload() {
  val local = session(); val store = Memory()
  assertIs<AutomaticPushResult.Published>(local.automaticPush(store) { false })
  assertTrue(store.writes > 0); assertFalse(local.hasPending()); assertNotNull(local.baseline())
 }
}
