package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.*
import kotlin.test.*

class OnboardingReviewTest {
 @TempDir lateinit var temp: Path
 private var next = 0
 private class Memory: AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  var failWrites = false
  var afterCommit: (() -> Unit)? = null
  override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
  override fun readCommit(id: String) = commits[id]?.copyOf()
  override fun readObject(id: String) = objects[id]?.copyOf()
  override fun writeCommit(id: String, bytes: ByteArray) { check(!failWrites); commits[id] = bytes.copyOf(); afterCommit?.also { afterCommit = null }?.invoke() }
  override fun writeObject(id: String, bytes: ByteArray) { check(!failWrites); objects[id] = bytes.copyOf() }
  override fun legacyPresent() = false
 }
 private val versions = object: ProfileVersions {
  override fun annotate(snapshot: Snapshot) = snapshot
  override fun target(path: String): TargetGame? = null
  override fun checkRestore(snapshot: Snapshot, targetPath: String) { Snapshots.verify(snapshot, Shattered) }
 }
 private fun session(value: String? = "a"): AppendProfileSession {
  val root = temp.resolve("root-${next++}").createDirectory()
  if (value != null) root.resolve("game1").createDirectory().resolve("game.dat").writeText(value)
  return AppendProfileSession(AppConfig(saveRoot = root.toString(), profile = "review"), temp.resolve("state-${next++}"), versions = versions, captureQuietMillis = 0)
 }
 private fun edit(s: AppendProfileSession, value: String) = s.root.resolve("game1/game.dat").writeText(value)
 private fun value(s: AppendProfileSession) = s.root.resolve("game1/game.dat").readText()
 private fun publish(s: AppendProfileSession, store: Memory) = assertIs<AppendPushResult.Success>(s.push(store) { true }).commit
 private fun graph(s: AppendProfileSession, store: Memory) = AppendRepository(s.directory.resolve("append-v2"), Shattered, "review").discover(store)

 @Test fun equalContentRequiresExplicitAssociationAndFutureUploadDescendsFromIt() {
  val cloud = Memory(); val remote = session(); val first = publish(remote, cloud); val local = session()
  val comparison = local.compareOnboarding(cloud) { true }
  assertEquals(OnboardingKind.IDENTICAL, comparison.kind); assertTrue(comparison.canAdoptRemote); assertNull(local.baseline())
  assertEquals(first.id, local.adoptOnboardingRemote(comparison, cloud) { true }.id)
  assertEquals(1, cloud.commits.size); edit(local, "later")
  val next = publish(local, cloud); assertEquals(listOf(first.id), next.parents); assertEquals(setOf(next.id), graph(local, cloud).tips)
 }
 @Test fun equalDescendantContentCanAdvanceLineageWithoutPublishingNewRoot() {
  val cloud = Memory(); val remote = session(); publish(remote, cloud); val local = session(null)
  local.restoreRemote(local.readRemote(cloud).single().entry.manifest.id, cloud) { true }
  val old = local.baseline(); edit(remote, "b"); publish(remote, cloud); edit(remote, "a"); val latest = publish(remote, cloud)
  val c = local.compareOnboarding(cloud) { true }
  assertEquals(OnboardingKind.IDENTICAL, c.kind); assertEquals(OnboardingLineage.ANCESTOR_OF_TIP, c.baselineLineage)
  assertEquals(old, local.baseline()); local.adoptOnboardingRemote(c, cloud) { true }
  assertEquals(latest.id, local.baseline()); assertEquals(3, cloud.commits.size)
 }
 @Test fun equalUnrelatedBranchesCannotBeSilentlyAdopted() {
  val cloud = Memory(); val local = session(); publish(local, cloud); session().push(cloud) { true }
  val old = local.baseline(); val c = local.compareOnboarding(cloud) { true }
  assertEquals(OnboardingKind.BRANCHED, c.kind); assertFalse(c.canAdoptRemote)
  assertFails { local.adoptOnboardingRemote(c, cloud) { true } }; assertEquals(old, local.baseline()); assertEquals(2, cloud.commits.size)
 }
 @Test fun missingRememberedCommitIsBlockedEvenWhenListingTurnsEmpty() {
  val cloud = Memory(); publish(session(), cloud); val local = session(); assertEquals(OnboardingKind.IDENTICAL, local.compareOnboarding(cloud) { true }.kind)
  cloud.commits.clear(); val c = local.compareOnboarding(cloud) { true }
  assertEquals(OnboardingKind.BLOCKED, c.kind); assertNotNull(c.blocker)
  assertFails { local.onboardingUpload(c, cloud) { true } }; assertTrue(cloud.commits.isEmpty())
 }
 @Test fun incompleteRemoteObjectCannotBeClassifiedEmpty() {
  val cloud = Memory(); publish(session(), cloud); cloud.objects.clear(); val local = session(null)
  val c = local.compareOnboarding(cloud) { true }; assertEquals(OnboardingKind.BLOCKED, c.kind)
  assertFails { local.onboardingUpload(c, cloud) { true } }; assertNull(local.baseline())
 }
 @Test fun changedLocalBytesInvalidateUploadChoice() {
  val cloud = Memory(); val local = session(); val c = local.compareOnboarding(cloud) { true }; edit(local, "changed")
  assertFails { local.onboardingUpload(c, cloud) { true } }; assertTrue(cloud.objects.isEmpty()); assertTrue(cloud.commits.isEmpty()); assertFalse(local.hasPending())
 }
 @Test fun changedLocalMtimeInvalidatesChoiceEvenWhenContentMatches() {
  val cloud = Memory(); val local = session(); val c = local.compareOnboarding(cloud) { true }
  val file = local.root.resolve("game1/game.dat"); Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 10000))
  assertFails { local.onboardingUpload(c, cloud) { true } }; assertTrue(cloud.commits.isEmpty())
 }
 @Test fun changedCloudInvalidatesRestoreBeforeLocalPreservationOrMutation() {
  val cloud = Memory(); val remote = session("cloud"); publish(remote, cloud); val local = session("local")
  val c = local.compareOnboarding(cloud) { true }; edit(remote, "new cloud"); publish(remote, cloud)
  assertFails { local.onboardingRestore(c, c.remoteEntries.single().entry.manifest.id, cloud) { true } }
  assertEquals("local", value(local)); assertEquals(2, cloud.commits.size); assertFalse(local.hasPending())
 }
 @Test fun comparisonIsBoundToItsOriginalSession() {
  val cloud = Memory(); val a = session(); val b = session(); val c = a.compareOnboarding(cloud) { true }
  assertFails { b.onboardingUpload(c, cloud) { true } }; assertTrue(cloud.commits.isEmpty())
 }
 @Test fun frozenPendingRetryKeepsOriginalBytesAfterFurtherLocalEdits() {
  val cloud = Memory(); val local = session(); val c = local.compareOnboarding(cloud) { true }; cloud.failWrites = true
  assertFails { local.onboardingUpload(c, cloud) { true } }; assertTrue(local.hasPending())
  val frozen = AppendRepository(local.directory.resolve("append-v2"), Shattered, "review").pending()!!; edit(local, "after failure"); cloud.failWrites = false
  val retry = local.compareOnboarding(cloud) { true }; assertTrue(retry.retryablePending)
  val result = assertIs<AppendPushResult.Success>(local.onboardingUpload(retry, cloud) { true })
  assertEquals(frozen.commit, result.commit); assertTrue(result.localDirty); assertEquals("after failure", value(local)); assertEquals(1, cloud.commits.size)
 }
 @Test fun lateSiblingDuringLocalPreservationNeedsNewChoiceAndDoesNotRestore() {
  val cloud = Memory(); val remote = session("cloud"); publish(remote, cloud); val local = session("local")
  val c = local.compareOnboarding(cloud) { true }
  val fork = Memory(); fork.commits.putAll(cloud.commits); fork.objects.putAll(cloud.objects); session("late").push(fork) { true }
  cloud.afterCommit = { cloud.commits.putAll(fork.commits); cloud.objects.putAll(fork.objects) }
  runCatching { local.onboardingRestore(c, c.remoteEntries.single().entry.manifest.id, cloud) { true } }
  assertEquals("local", value(local)); assertEquals(3, graph(local, cloud).tips.size); assertEquals(3, cloud.commits.size)
 }
 @Test fun emptyLocalCanExplicitlyChooseAmongCloudBranches() {
  val cloud = Memory(); publish(session("one"), cloud); session("two").push(cloud) { true }; val local = session(null)
  val c = local.compareOnboarding(cloud) { true }; assertEquals(OnboardingKind.BRANCHED, c.kind)
  val chosen = c.remoteEntries.first(); local.onboardingRestore(c, chosen.entry.manifest.id, cloud) { true }
  assertEquals(chosen.entry.manifest.files, local.observedFiles()); assertEquals(1, graph(local, cloud).tips.size)
 }
 @Test fun bothEmptyCannotCreateAnEmptySnapshot() {
  val cloud = Memory(); val local = session(null); val c = local.compareOnboarding(cloud) { true }
  assertEquals(OnboardingKind.BOTH_EMPTY, c.kind); assertFails { local.onboardingUpload(c, cloud) { true } }; assertTrue(cloud.objects.isEmpty()); assertTrue(cloud.commits.isEmpty())
 }
 @Test fun choosingUnlistedSnapshotCannotPublishLocalOrOverwriteIt() {
  val cloud = Memory(); publish(session("cloud"), cloud); val local = session("local"); val c = local.compareOnboarding(cloud) { true }
  assertFails { local.onboardingRestore(c, java.util.UUID.randomUUID().toString(), cloud) { true } }
  assertEquals("local", value(local)); assertEquals(1, cloud.commits.size); assertFalse(local.hasPending())
 }
 @Test fun missingPreviouslyEstablishedBaselineIsBlocked() {
  val cloud = Memory(); val local = session(); publish(local, cloud); local.directory.resolve("append-v2/baseline.txt").deleteExisting()
  val c = local.compareOnboarding(cloud) { true }; assertEquals(OnboardingKind.BLOCKED, c.kind)
  assertFails { local.onboardingUpload(c, cloud) { true } }; assertEquals(1, cloud.commits.size)
 }
 @Test fun immutableComparisonDefendsAgainstCollectionTampering() {
  val cloud = Memory(); publish(session(), cloud); val local = session(); val c = local.compareOnboarding(cloud) { true }
  assertFails { (c.localFiles as MutableMap<String, String>).clear() }
  assertFails { (c.observedTips as MutableSet<String>).clear() }
  assertFails { (c.remoteEntries.single().commit.snapshot.files as MutableMap<String, String>).clear() }
 }
 @Test fun identicalGlobalOnlySaveCanAssociateWithoutRestoreCompatibility() {
  fun globalSession(): AppendProfileSession {
   val root = temp.resolve("global-${next++}").createDirectory(); root.resolve("journal.dat").writeText("global record fixture")
   return AppendProfileSession(AppConfig(saveRoot = root.toString(), profile = "review", targetGame = "/workspace/shared/pds-append-gameplay/ShatteredPD-v4.0.2-Java.jar"), temp.resolve("global-state-${next++}"), captureQuietMillis = 0)
  }
  val cloud = Memory(); val remote = globalSession(); val original = publish(remote, cloud); val local = globalSession()
  val c = local.compareOnboarding(cloud) { true }; assertEquals(OnboardingKind.IDENTICAL, c.kind)
  assertEquals(original.id, local.adoptOnboardingRemote(c, cloud) { true }.id)
  assertEquals(original.id, local.baseline()); assertEquals(1, cloud.commits.size)
 }

}
