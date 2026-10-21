package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*
import kotlin.test.*

class AppendProfileSessionTest {
 @TempDir lateinit var temp: Path
 private var next = 0
 private class Memory: AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  var failWrite = false
  var legacy = false
  var beforeObject: (() -> Unit)? = null
  var afterCommit: (() -> Unit)? = null
  var afterReadObject: (() -> Unit)? = null
  override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
  override fun readCommit(id: String) = commits[id]?.copyOf()
  override fun readObject(id: String): ByteArray? { val bytes=objects[id]?.copyOf(); afterReadObject?.also { afterReadObject=null }?.invoke(); return bytes }
  override fun writeCommit(id: String, bytes: ByteArray) { if (failWrite) error("offline"); commits[id] = bytes.copyOf(); afterCommit?.also { afterCommit = null }?.invoke() }
  override fun writeObject(id: String, bytes: ByteArray) { beforeObject?.also { beforeObject = null }?.invoke(); if (failWrite) error("offline"); objects[id] = bytes.copyOf() }
  override fun legacyPresent() = legacy
 }
 private fun root(value: String? = "alive"): Path = temp.resolve("root-${next++}").createDirectory().also {
  if (value != null) { it.resolve("game1").createDirectory(); it.resolve("game1/game.dat").writeText(value) }
 }
 private fun versions(version: String = "v1") = object: ProfileVersions {
  override fun annotate(snapshot: Snapshot) = snapshot.copy(manifest = snapshot.manifest.copy(version = version))
  override fun target(path: String): TargetGame? = null
  override fun checkRestore(snapshot: Snapshot, targetPath: String) { require(snapshot.manifest.version == version) { "incompatible" } }
 }
 private fun session(root: Path = root(), state: Path = temp.resolve("state-${next++}"), profile: String = "demo", version: String = "v1") =
  AppendProfileSession(AppConfig(saveRoot = root.toString(), profile = profile), state, versions = versions(version), captureQuietMillis = 0)
 private fun content(session: AppendProfileSession) = session.root.resolve("game1/game.dat").readText()
 private fun edit(session: AppendProfileSession, value: String) = session.root.resolve("game1/game.dat").writeText(value)
 private fun published(session: AppendProfileSession, store: Memory) = assertIs<AppendPushResult.Success>(session.push(store) { true }).commit
 private fun graph(session: AppendProfileSession, store: Memory) = AppendRepository(session.directory.resolve("append-v2"), Shattered, "demo").discover(store)

 @Test fun cloudHistorySelectionRequiresApprovalForUniqueBytesAndCreatesNoBackup() {
  val store=Memory();val a=session();val first=published(a,store)
  edit(a,"second");val second=published(a,store)
  val history=a.syncHistory();assertEquals(2,history.cloud.size);assertEquals(1,history.localNodeCount)
  assertEquals("upload",history.baselineDirection);assertEquals(second.snapshot.id,history.defaultSnapshot)
  a.renameHistory(first.snapshot.id,"早期进度");assertEquals("早期进度",a.syncHistory().aliases[first.snapshot.id])
  edit(a,"unique")
  assertFails { a.restoreCloudHistory(first.snapshot.id,store,false){true} }
  assertEquals("unique",content(a));assertFalse(a.hasPending())
  val confirmed=a.observedFiles();edit(a,"changed-after-confirmation")
  assertFails {a.restoreCloudHistory(first.snapshot.id,store,true,confirmed){true}}
  assertEquals("changed-after-confirmation",content(a));assertFalse(a.hasPending())
  a.restoreCloudHistory(first.snapshot.id,store,true){true}
  assertEquals("alive",content(a));assertFalse(a.hasPending())
  assertEquals(first.id,a.baseline());assertEquals(2,store.commits.size)
  assertEquals(2,a.syncHistory().cloud.size)
  assertFalse(a.history().any { it.manifest.files["game1/game.dat"] == hash("changed-after-confirmation".encodeToByteArray()) })
 }

 @Test fun historicalRestoreDoesNotNeedReplacementPermissionForProtectedContent() {
  val store=Memory();val a=session();val first=published(a,store);edit(a,"second");published(a,store)
  a.restoreCloudHistory(first.snapshot.id,store,false){true}
  assertEquals("alive",content(a));assertFalse(a.hasPending())
  assertEquals(first.id,a.baseline());assertEquals(2,store.commits.size)
 }

 @Test fun ordinaryPublicationParentsOnlyItsRealBaselineAndKeepsSiblingBranches() {
  val store = Memory(); val a = session(); val first = published(a, store)
  val b = session(root(null)); b.restoreRemote(b.readRemote(store).single().entry.manifest.id, store) { true }
  edit(a, "a-later"); val second = published(a, store)
  edit(b, "b-later"); val conflict = assertIs<AppendPushResult.Diverged>(b.push(store) { true }).conflict
  assertEquals(2, conflict.branches.size)
  assertEquals(setOf(second.id, b.baseline()), conflict.observedTips)
  val own = conflict.branches.single { it.commit.id == b.baseline() }.commit
  assertEquals(listOf(first.id), own.parents); assertFalse(second.id in own.parents)
  assertEquals("b-later", content(b)); assertEquals(3, store.commits.size)
 }

 @Test fun explicitRemoteApplyAdoptsMatchingUnbaselinedPopulatedDirectory() {
  val store = Memory(); val a = session(); published(a, store)
  val b = session(); val remote = b.readRemote(store).single()
  b.restoreRemote(remote.entry.manifest.id, store) { true }
  assertEquals(remote.commit.id,b.baseline()); assertEquals("alive", content(b)); assertEquals(1, store.commits.size)
  assertFalse(b.hasPending())
 }

 @Test fun explicitRemoteApplyOverwritesDirtyBaselineWithoutInsuranceCopy() {
  val store = Memory(); val a = session(); published(a, store)
  val b = session(root(null)); b.restoreRemote(b.readRemote(store).single().entry.manifest.id, store) { true }
  edit(a, "next"); val next = published(a, store)
  edit(b, "dirty"); val entry = b.readRemote(store).single()
  val restored = b.restoreRemote(entry.entry.manifest.id, store) { true }
  assertEquals("next", content(b)); assertEquals(next.id, b.baseline()); assertEquals(restored.restored.files, Snapshots.fileHashes(b.root, Shattered))
  assertEquals(2,store.commits.size); assertFalse(b.hasPending())
  assertFalse(b.history().any { it.manifest.files["game1/game.dat"] == hash("dirty".encodeToByteArray()) })
 }

 @Test fun explicitRemoteApplyStillRejectsChangesAfterItsFingerprintCheck() {
  val store=Memory();published(session(root("cloud")),store)
  val local=session(root("unique-local"));val remote=local.readRemote(store).single()
  val marker=local.directory.resolve("append-v2/apply-required.txt")
  var changed=false
  assertFails { local.restoreRemote(remote.entry.manifest.id,store) {
   if(marker.exists()&&!changed){changed=true;edit(local,"raced-after-verification")}
   true
  } }
  assertTrue(changed);assertEquals("raced-after-verification",content(local));assertNull(local.baseline())
  assertTrue(local.hasPending());assertEquals(1,store.commits.size)
  assertFalse(local.history().any { it.manifest.files["game1/game.dat"]==hash("unique-local".encodeToByteArray()) })
 }

 @Test fun retryReusesFrozenBytesAndEditsDuringPublicationRemainDirty() {
  val store = Memory(); val local = session(); store.failWrite = true
  assertFails { local.push(store) { true } }; assertTrue(local.hasPending())
  val repo = AppendRepository(local.directory.resolve("append-v2"), Shattered, "demo")
  val frozen = repo.pending()!!
  edit(local, "new-local"); store.failWrite = false
  val result = assertIs<AppendPushResult.Success>(local.push(store) { true })
  assertEquals(frozen.commit.id, result.commit.id); assertTrue(result.localDirty)
  assertContentEquals(frozen.manifestBytes, store.commits.getValue(frozen.commit.id))
  assertContentEquals(frozen.zip, store.objects.getValue(frozen.commit.snapshot.id)); assertEquals("new-local", content(local))
  val next = published(local, store); assertEquals(listOf(result.commit.id), next.parents)
 }

 @Test fun editDuringObjectUploadDoesNotBecomeCleanPublishedState() {
  val store = Memory(); val local = session(); store.beforeObject = { edit(local, "during-upload") }
  val result = assertIs<AppendPushResult.Success>(local.push(store) { true })
  assertTrue(result.localDirty); assertEquals("during-upload", content(local))
  assertNotEquals(hash("during-upload".encodeToByteArray()), result.commit.snapshot.files["game1/game.dat"])
 }

 @Test fun historicalRestoreAdoptsKnownCommitWithoutPendingPublicationAndLaterEditsBranchFromIt() {
  val store = Memory(); val local = session(); val first = published(local, store)
  edit(local, "second"); val second = published(local, store)
  val restored = local.restoreHistory(first.snapshot.id) { true }
  assertEquals("alive", content(local)); assertEquals(restored.restored.files, Snapshots.fileHashes(local.root, Shattered))
  assertEquals(first.id, local.baseline()); assertFalse(local.hasPending()); assertEquals(2,store.commits.size)
  edit(local, "post-restore-edit")
  val result = assertIs<AppendPushResult.Diverged>(local.push(store) { true })
  val own = result.conflict.branches.single { it.commit.id == local.baseline() }.commit
  assertEquals("snapshot",own.kind); assertEquals(listOf(first.id),own.parents)
  assertEquals(setOf(second.id,own.id),result.conflict.observedTips)
  assertEquals(hash("post-restore-edit".encodeToByteArray()),own.snapshot.files["game1/game.dat"])
  assertNotEquals(first.snapshot.files,own.snapshot.files); assertEquals(3,store.commits.size)
 }

 @Test fun failedVersionCheckLeavesTargetAndBaselineUnchangedWithoutRestorePlan() {
  val store = Memory(); val newer = session(version = "v2"); published(newer, store)
  val local = session(); val remote = local.readRemote(store).single()
  assertFails { local.restoreHistory(remote.entry.manifest.id) { true } }
  assertFalse(local.hasPending()); assertEquals("alive", content(local)); assertNull(local.baseline())
  assertTrue(local.history().any { it.manifest.id == remote.entry.manifest.id })
 }

 @Test fun threeBranchesOfferAllChoicesAndRestoreKeepsEveryCloudTip() {
  val store = Memory(); published(session(root("a")), store)
  val b = session(root("b")); assertIs<AppendPushResult.Diverged>(b.push(store) { true })
  val c = session(root("c")); val conflict = assertIs<AppendPushResult.Diverged>(c.push(store) { true }).conflict
  assertEquals(3, conflict.branches.size); val choice = conflict.branches.single { it.commit.id == b.baseline() }
  val commitsBefore=store.commits.mapValues { it.value.copyOf() };val objectsBefore=store.objects.mapValues { it.value.copyOf() }
  val result = assertIs<AppendPushResult.Diverged>(c.resolve(conflict, choice.snapshot.manifest.id, store) { true })
  assertEquals(conflict.observedTips,result.conflict.observedTips);assertEquals(choice.commit.id,c.baseline())
  assertEquals("b", content(c)); assertEquals(3, store.commits.size);assertFalse(c.hasPending())
  commitsBefore.forEach { (id,bytes)->assertContentEquals(bytes,store.commits.getValue(id)) }
  objectsBefore.forEach { (id,bytes)->assertContentEquals(bytes,store.objects.getValue(id)) }
  assertFalse(c.history().any { it.reason == "恢复前自动备份" })
  assertTrue(c.history().any { it.manifest.files["game1/game.dat"] == hash("c".encodeToByteArray()) }) // Already published branch remains readable.
  assertEquals(conflict.observedTips, graph(c, store).tips)
 }

 @Test fun changedLocalFilesInvalidateExplicitConflictChoiceBeforeAnyReplacement() {
  val store = Memory(); published(session(root("remote")), store)
  val local = session(root("local")); val conflict = assertIs<AppendPushResult.Diverged>(local.push(store) { true }).conflict
  edit(local, "changed-during-dialog")
  assertFails { local.resolve(conflict, conflict.branches.first().snapshot.manifest.id, store) { true } }
  assertEquals("changed-during-dialog", content(local)); assertEquals(2, store.commits.size); assertFalse(local.hasPending())
 }

 @Test fun changedRemoteTipsReturnAllNewChoicesWithoutApplyingStaleChoice() {
  val store = Memory(); published(session(root("a")), store)
  val b = session(root("b")); val old = assertIs<AppendPushResult.Diverged>(b.push(store) { true }).conflict
  session(root("c")).push(store) { true }
  val current = assertIs<AppendPushResult.Diverged>(b.resolve(old, old.branches.first().snapshot.manifest.id, store) { true }).conflict
  assertEquals(3, current.branches.size); assertEquals("b", content(b)); assertEquals(3, store.commits.size)
  assertFalse(b.hasPending())
 }

 @Test fun lateSiblingDuringBranchDownloadInvalidatesSelectionWithoutWritingCloud() {
  val store = Memory(); val a = session(root("a")); published(a, store)
  val b = session(root("b")); val conflict = assertIs<AppendPushResult.Diverged>(b.push(store) { true }).conflict
  val thirdStore = Memory(); thirdStore.commits.putAll(store.commits); thirdStore.objects.putAll(store.objects)
  val c = session(root("late")); c.push(thirdStore) { true }
  val late = thirdStore.commits.keys - store.commits.keys
  val baseline=b.baseline()
  store.afterReadObject = { late.forEach { store.commits[it] = thirdStore.commits.getValue(it) }; store.objects.putAll(thirdStore.objects) }
  val result = assertIs<AppendPushResult.Diverged>(b.resolve(conflict, conflict.branches.first().snapshot.manifest.id, store) { true })
  assertEquals(3, result.conflict.observedTips.size); assertTrue(result.conflict.observedTips.containsAll(late))
  assertEquals(3, store.commits.size);assertEquals("b",content(b));assertEquals(baseline,b.baseline());assertFalse(b.hasPending())
 }

 @Test fun conflictRestoreWorksWithWritesDisabledAndOnlyLaterExplicitPushPublishesEdits() {
  val store = Memory(); val a = session(root("a")); published(a, store)
  val b = session(root("b")); val conflict = assertIs<AppendPushResult.Diverged>(b.push(store) { true }).conflict
  val choice = conflict.branches.single { it.commit.id == a.baseline() }
  store.failWrite = true
  val result=assertIs<AppendPushResult.Diverged>(b.resolve(conflict, choice.snapshot.manifest.id, store) { true })
  assertEquals(conflict.observedTips,result.conflict.observedTips)
  assertEquals("a", content(b)); assertFalse(b.hasPending());assertEquals(choice.commit.id,b.baseline());assertEquals(2,store.commits.size)
  edit(b, "newer"); store.failWrite = false
  val done = assertIs<AppendPushResult.Diverged>(b.push(store) { true })
  val published=done.conflict.branches.single { it.commit.id==b.baseline() }.commit
  assertEquals(listOf(choice.commit.id),published.parents);assertEquals("snapshot",published.kind)
  assertEquals(hash("newer".encodeToByteArray()),published.snapshot.files["game1/game.dat"])
  assertEquals("newer", content(b));assertEquals(3,store.commits.size)
 }

 @Test fun interruptedLocalApplyCannotBeReplayedAsPublicationBeforeExplicitArchive() {
  val store = Memory(); val local = session(); val first = published(local, store)
  edit(local, "untouched-newer")
  val marker = local.directory.resolve("append-v2/apply-required.txt")
  marker.writeText("restore")
  val repo = AppendRepository(local.directory.resolve("append-v2"), Shattered, "demo")
  val plan = repo.prepare(local.loadSnapshot(first.snapshot.id), listOf(first.id), "restore")
  assertTrue(local.hasPending())
  assertFails { local.push(store) { true } }
  assertEquals(1, store.commits.size); assertEquals("untouched-newer", content(local))
  local.archivePending(); assertFalse(local.hasPending()); assertFalse(marker.exists())
  assertTrue(local.history().any { it.manifest.id == plan.commit.snapshot.id })
 }

 @Test fun interruptedRemoteApplyGateBlocksBothRestoreAndPushUntilReviewed() {
  val store = Memory(); published(session(), store)
  val local = session(root(null)); val remote = local.readRemote(store).single()
  val marker = local.directory.resolve("append-v2/apply-required.txt")
  marker.parent.createDirectories(); marker.writeText("remote-restore")
  assertTrue(local.hasPending())
  assertFails { local.restoreRemote(remote.entry.manifest.id, store) { true } }
  assertFails { local.push(store) { true } }
  assertNull(local.baseline()); assertFalse(local.root.resolve("game1").exists())
  local.archivePending(); local.restoreRemote(remote.entry.manifest.id, store) { true }
  assertEquals(remote.commit.id, local.baseline()); assertFalse(marker.exists())
 }

 @Test fun failedRemoteApplyKeepsRestartGateAndOriginalHistoryUntilExplicitArchive() {
  val store = Memory(); published(session(), store)
  val local = session(root(null)); val remote = local.readRemote(store).single()
  val marker=local.directory.resolve("append-v2/apply-required.txt")
  assertFails { local.restoreRemote(remote.entry.manifest.id, store) {
   if(marker.exists())throw SecurityException("Synthetic authorization revoked before apply")
   true
  } }
  assertTrue(local.hasPending()); assertNull(local.baseline())
  assertTrue(local.history().any { it.manifest.id == remote.entry.manifest.id })
  assertFails { local.push(store) { true } }
  local.archivePending(); assertFalse(local.hasPending())
 }

 @Test fun missingPreviouslyEstablishedBaselineFailsClosedInsteadOfCreatingRoot() {
  val store = Memory(); val local = session(); published(local, store)
  local.directory.resolve("append-v2/baseline.txt").deleteExisting()
  assertFails { local.push(store) { true } }; assertEquals(1, store.commits.size)
 }

 @Test fun vanishedRemoteBaselineStopsPublicationAndNeverSilentlyStartsNewHistory() {
  val store = Memory(); val local = session(); val first = published(local, store)
  store.commits.remove(first.id); edit(local, "next")
  assertFails { local.push(store) { true } }; assertEquals(0, store.commits.size); assertFalse(local.hasPending())
  assertEquals(first.id,local.baseline());assertEquals("next",content(local));assertEquals(1,store.objects.size)
 }

 @Test fun readRemoteReturnsEveryObservedTipWithoutWritesOrChangingLocalBaseline() {
  val store = Memory(); published(session(root("a")), store); session(root("b")).push(store) { true }
  val local = session(); val remote = local.readRemote(store)
  assertEquals(2, remote.size); assertEquals(2, store.commits.size); assertNull(local.baseline()); assertEquals("alive", content(local))
  assertFails { local.restoreRemote(remote.first().entry.manifest.id, store) { true } }
 }

 @Test fun legacyRemoteAndLegacyPendingAreNeverSilentlyPublishedAsV2() {
  val store = Memory(); val local = session(); store.legacy = true
  assertFailsWith<LegacyProtocolException> { local.push(store) { true } }; assertTrue(store.commits.isEmpty())
  store.legacy = false
  val old = ProfileSession(local.config, temp.resolve("legacy-state"), versions = versions(), captureQuietMillis = 0)
  val oldStore = object: Store {
   override fun head(): RemoteHead? = null
   override fun upload(snapshot: Snapshot) { error("offline") }
   override fun download(manifest: Manifest): Snapshot = error("unused")
   override fun publish(manifest: Manifest, expected: RemoteHead?) = error("unused")
  }
  assertFails { old.push(oldStore) { true } }
  val facade = session(local.root, temp.resolve("legacy-state")); assertTrue(facade.hasPending())
  assertFails { facade.push(store) { true } }; assertTrue(store.commits.isEmpty())
  facade.archivePending(); assertFalse(facade.hasPending()); assertTrue(facade.history().isNotEmpty())
  val first = published(facade, store); assertTrue(first.parents.isEmpty())
 }

 @Test fun oldAndNewSessionsUseIdenticalPhysicalRootLock() {
  val root = root(); val state = temp.resolve("lock-state"); val local = session(root, state)
  val path = state.resolve("locks/${hash(root.toRealPath().toString().encodeToByteArray())}.lock")
  FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.lock().use {
   assertFailsWith<IllegalStateException> { local.createSnapshot { true } }
   assertFailsWith<IllegalStateException> { local.push(Memory()) { true } }
  } }
 }

 @Test fun adapterIdentitySeparatesVariantsWithIdenticalLocalFiles() {
  val root = root(); val state = temp.resolve("variants"); val shattered = session(root, state)
  val variant = object: GameAdapter {
   override val id = "variant"
   override fun includes(name: String) = Shattered.includes(name)
   override fun compatible(source: String, target: String) = source == target
   override fun launchCommand(executable: Path) = listOf(executable.toString())
  }
  val other = AppendProfileSession(AppConfig(saveRoot = root.toString()), state, variant, versions(), captureQuietMillis = 0)
  assertNotEquals(shattered.directory, other.directory)
  val store = Memory(); published(shattered, store)
  assertNull(other.baseline()); assertTrue(other.history().isEmpty())
  assertFails { other.readRemote(store) }
 }

 @Test fun scopeIsolatesProfilesAndBaselineNeverUsesLegacyHeadFile() {
  val root = root(); val state = temp.resolve("scopes"); val a = session(root, state)
  val b = session(root, state, "other"); assertNotEquals(a.directory, b.directory)
  val store = Memory(); val first = published(a, store)
  assertNull(b.baseline()); assertFalse(b.hasPending())
  a.directory.resolve("head.txt").writeText(java.util.UUID.randomUUID().toString())
  edit(a, "next"); assertEquals(listOf(first.id), published(a, store).parents)
 }
}
