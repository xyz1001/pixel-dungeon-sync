package pdsync

import io.github.xyz1001.pdsync.android.SharedHomeContent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Newly reconstructed host coverage. These tests are not recovered original code91/92 tests. */
class DesktopHomeMetadataTest {
 @TempDir lateinit var temp: Path
 private class Store : AppendStore {
  val commits = linkedMapOf<String, ByteArray>()
  val objects = linkedMapOf<String, ByteArray>()
  var puts = 0
  var objectReads = 0
  override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
  override fun readCommit(id: String) = commits[id]?.copyOf()
  override fun readObject(id: String): ByteArray? { objectReads++; return objects[id]?.copyOf() }
  override fun writeCommit(id: String, bytes: ByteArray) { puts++; commits[id] = bytes.copyOf() }
  override fun writeObject(id: String, bytes: ByteArray) { puts++; objects[id] = bytes.copyOf() }
  override fun legacyPresent() = false
 }
 private val versions = object : ProfileVersions {
  override fun annotate(snapshot: Snapshot) = snapshot.copy(manifest = snapshot.manifest.copy(version = "v1"))
  override fun target(path: String): TargetGame? = null
  override fun checkRestore(snapshot: Snapshot, targetPath: String) = Unit
 }
 private fun session(name: String, content: String): AppendProfileSession {
  val root = temp.resolve(name).createDirectories()
  root.resolve("game1").createDirectory().resolve("game.dat").writeText(content)
  return AppendProfileSession(AppConfig(saveRoot=root.toString(), username="account"), temp.resolve("state-$name"),
   versions=versions, captureQuietMillis=0)
 }
 private fun seeded(): Pair<Store, AppendProfileSession> {
  val store=Store(); val publisher=session("publisher", "same progress")
  assertIs<AppendPushResult.Success>(publisher.push(store) { true })
  store.puts=0; store.objectReads=0
  return store to session("local", "same progress")
 }

 @Test fun metadataPreviewIsZeroZipAndIdenticalManualUploadVerifiesAndAssociatesWithoutPut() {
  val (store, manager)=seeded()
  val preview=manager.readHomeHistory(store)
  assertEquals(0,store.objectReads)
  assertEquals(RemoteVerification.METADATA_ONLY,preview.graph.verification)
  assertFalse(preview.currentProtected)
  assertEquals(SharedHomeContent(matchesCloudTip=true), SharedHomeContent.from(preview,null,true,false,false))
  assertTrue(manager.history().isEmpty())
  val result=assertIs<HomePushResult.Unchanged>(manager.pushFromHome(preview,store) { true })
  assertTrue(store.objectReads>0,"zero PUT still requires full object verification")
  assertEquals(0,store.puts)
  assertEquals(preview.graph.tips.single(),manager.baseline(),"verified no-transfer upload associates the existing content record")
  assertFalse(manager.hasPending())
  assertEquals(RemoteVerification.OBJECTS_VERIFIED,result.history.graph.verification)
  assertTrue(result.history.currentProtected)
  assertEquals(SharedHomeContent(true,true,matchesCloudTip=true),SharedHomeContent.from(result.history,null,true,false,false))
  assertEquals(preview.currentFiles,result.history.currentFiles)
 }

 @Test fun changedContentKeepsOriginalBranchPublishingSemantics() {
  val (store,manager)=seeded()
  manager.root.resolve("game1/game.dat").writeText("different local progress")
  val preview=manager.readHomeHistory(store)
  val result=assertIs<HomePushResult.Published>(manager.pushFromHome(preview,store) { true })
  assertIs<AppendPushResult.Diverged>(result.result)
  assertTrue(store.puts>0)
  assertEquals(2,manager.syncHistory().graph.tips.size)
 }

 @Test fun wrongOwnerAndCorruptedObjectsCannotProduceVerifiedNoTransferResult() {
  val (store,manager)=seeded()
  val preview=manager.readHomeHistory(store)
  val other=session("other", "same progress")
  assertFailsWith<IllegalArgumentException> { other.pushFromHome(preview,store) { true } }
  assertEquals(0,store.puts)
  val objectId=store.objects.keys.single()
  store.objects[objectId]=byteArrayOf(0,1,2)
  assertFails { manager.pushFromHome(preview,store) { true } }
  assertNull(manager.baseline()); assertFalse(manager.hasPending()); assertEquals(0,store.puts)
  assertFalse(preview.currentProtected)
 }

 @Test fun metadataReadyOnlySchedulesFreshFullAutomaticCheckThatAssociatesVerifiedContent() {
  val (store,manager)=seeded()
  val preview=manager.readHomeHistory(store)
  assertEquals(0,store.objectReads)
  val unchanged=assertIs<AutomaticPushResult.Skipped>(manager.automaticPush(store) { true })
  assertEquals(OnboardingKind.IDENTICAL,unchanged.comparison.kind)
  assertEquals(RemoteVerification.OBJECTS_VERIFIED,unchanged.comparison.verification)
  assertTrue(store.objectReads>0,"automatic path must upgrade by its own full comparison")
  val associated=preview.graph.tips.single()
  assertEquals(0,store.puts);assertEquals(associated,manager.baseline());assertFalse(manager.hasPending())
  assertEquals(RemoteVerification.METADATA_ONLY,preview.graph.verification)
  store.objects[store.objects.keys.single()]=byteArrayOf(1,2,3)
  val blocked=assertIs<AutomaticPushResult.Skipped>(manager.automaticPush(store) { true })
  assertEquals(OnboardingKind.BLOCKED,blocked.comparison.kind)
  assertEquals(0,store.puts);assertEquals(associated,manager.baseline());assertFalse(manager.hasPending())
 }

 @Test fun branchAppearingAfterMetadataCannotAuthorizeAutomaticPublish() {
  val (store,manager)=seeded()
  val preview=manager.readHomeHistory(store)
  val sibling=session("sibling","independent branch")
  assertIs<AppendPushResult.Diverged>(sibling.push(store) { true })
  store.puts=0;store.objectReads=0
  val skipped=assertIs<AutomaticPushResult.Skipped>(manager.automaticPush(store) { true })
  assertEquals(OnboardingKind.BRANCHED,skipped.comparison.kind)
  assertTrue(store.objectReads>0);assertEquals(0,store.puts);assertEquals(preview.graph.tips.single(),manager.baseline())
  assertEquals(1,preview.graph.tips.size,"original metadata must stay immutable")
 }

 @Test fun actualDesktopRoutesKeepMetadataReadsAndObjectProofSeparate() {
  val main=Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val remote=main.substringAfter("fun readRemote(").substringBefore("fun hasRemoteBranches()")
  assertContains(remote,"manager.readHomeHistory(")
  assertContains(remote,"manager.syncHistory(observed)")
  assertFalse(remote.contains("manager.readRemote("))
  assertContains(main,"manager.pushFromHome(operationHistory, store(), stopped)")
  assertContains(main,"is HomePushResult.Unchanged -> OperationResult(")
  assertContains(main,"verifiedHistory = upload.history")
  assertContains(main,"OnboardingGate.allowsAutomatic(config?.onboardingProgress, onboardingActive, remoteInspectionReady)")
  assertContains(main,"manager.automaticPush(store(), stopped)")
 }
}
