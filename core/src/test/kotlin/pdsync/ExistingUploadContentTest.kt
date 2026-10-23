package pdsync

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Content reuse must happen before capture, independently of baseline and display metadata. */
class ExistingUploadContentTest {
 @TempDir lateinit var temp:Path
 private var sequence=0
 private class Store:AppendStore {
  val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>()
  var writes=0;var listings=0;var failWrites=false
  override fun listCommits(cursor:String?):CommitPage {listings++;return CommitPage(commits.keys.toList())}
  override fun readCommit(id:String)=commits[id]?.copyOf()
  override fun readObject(id:String)=objects[id]?.copyOf()
  override fun writeCommit(id:String,bytes:ByteArray) {writes++;check(!failWrites);commits[id]=bytes.copyOf()}
  override fun writeObject(id:String,bytes:ByteArray) {writes++;check(!failWrites);objects[id]=bytes.copyOf()}
  override fun legacyPresent()=false
 }
 private val versions=object:ProfileVersions {
  override fun annotate(snapshot:Snapshot)=snapshot.copy(manifest=snapshot.manifest.copy(version="v1"))
  override fun target(path:String):TargetGame?=null
  override fun checkRestore(snapshot:Snapshot,targetPath:String)=Unit
 }
 private fun session(content:String="A"):AppendProfileSession {
  val id=sequence++;val root=temp.resolve("root-$id").createDirectory()
  root.resolve("game1").createDirectory().resolve("game.dat").writeText(content)
  return AppendProfileSession(AppConfig(saveRoot=root.toString(),profile="reuse"),temp.resolve("state-$id"),versions=versions,captureQuietMillis=0)
 }
 private fun edit(session:AppendProfileSession,content:String)=session.root.resolve("game1/game.dat").writeText(content)
 private fun push(session:AppendProfileSession,store:Store)=assertIs<AppendPushResult.Success>(session.push(store){true})
 private fun seeded():Triple<AppendProfileSession,Store,Pair<AppendCommit,AppendCommit>> {
  val session=session();val store=Store();val a=push(session,store).commit
  edit(session,"B");val b=push(session,store).commit
  return Triple(session,store,a to b)
 }
 @Test fun localSnapshotsUseContentIdentityWithoutGeneratingDuplicateHistory() {
  val session=session()
  val first=session.createSnapshot {true}
  assertEquals(contentSha256(first.manifest.files),first.manifest.id);assertNull(first.manifest.parent)
  session.renameCurrent("display name does not change identity"){true}
  val second=session.createSnapshot {true}
  assertEquals(first.manifest.id,second.manifest.id)
  assertEquals(1,session.history().size)
  edit(session,"new local content")
  val changed=session.createSnapshot {true}
  assertEquals(contentSha256(changed.manifest.files),changed.manifest.id)
  assertNotEquals(first.manifest.id,changed.manifest.id);assertNull(changed.manifest.parent)
  assertEquals(2,session.history().size)
 }
 @Test fun hashHistoryRenameSurvivesRestartAndRepeatedRenaming() {
  val session=session();val store=Store();val commit=push(session,store).commit
  assertTrue(validContentHashId(commit.id))
  session.renameHistory(commit.snapshot.id,"first display name")
  assertEquals("first display name",session.syncHistory().aliases[commit.snapshot.id])
  val restarted=AppendProfileSession(session.config,temp.resolve("state-0"),versions=versions,captureQuietMillis=0)
  assertEquals("first display name",restarted.syncHistory().aliases[commit.snapshot.id])
  restarted.renameHistory(commit.snapshot.id,"second display name",restarted.readHomeHistory(store))
  assertEquals("second display name",restarted.syncHistory().aliases[commit.snapshot.id])
  val writes=store.writes
  assertTrue(push(restarted,store).reused)
  assertEquals(writes,store.writes);assertEquals(1,store.commits.size)
 }
 @Test fun restoreHistoricalAThenUploadReusesRealRecordWithoutRemoteMutation() {
  val (session,store,records)=seeded();val (a,b)=records
  session.restoreCloudHistory(a.snapshot.id,store,false){true}
  session.renameCurrent("renamed after restore"){true}
  val writes=store.writes;val localHistory=session.history().map {it.manifest.id};val listings=store.listings
  val result=push(session,store)
  assertTrue(result.reused);assertEquals(a,result.commit)
  assertEquals(writes,store.writes);assertEquals(listings+1,store.listings)
  assertEquals(setOf(a.id,b.id),store.commits.keys);assertEquals(2,store.objects.size)
  assertEquals(a.id,session.baseline());assertFalse(session.hasPending())
  assertEquals(localHistory,session.history().map {it.manifest.id})
  assertEquals(setOf(b.id),session.syncHistory().graph.tips)
 }
 @Test fun contentReturningToAWhileBaselineBReassociatesAndNextChangeParentsA() {
  val (session,store,records)=seeded();val (a,b)=records
  edit(session,"A");session.renameCurrent("same bytes, different metadata"){true}
  val writes=store.writes
  val result=push(session,store)
  assertTrue(result.reused);assertEquals(a.id,result.commit.id);assertEquals(writes,store.writes)
  assertEquals(a.id,session.baseline());assertFalse(session.hasPending())
  assertEquals("same bytes, different metadata",(session.directoryStatus() as SidecarRead.Valid).value.displayName)
  assertEquals(setOf(b.id),session.syncHistory().graph.tips)
  edit(session,"C")
  assertIs<AppendPushResult.Diverged>(session.push(store){true})
  assertEquals(a.id,session.baseline())
  assertEquals(writes,store.writes)
  assertEquals(2,store.commits.size);assertEquals(2,store.objects.size)
 }
 @Test fun homeAndAutomaticUseAnyVerifiedHistoryWithoutExtraScanOrCapture() {
  val (session,store,records)=seeded();val (a,b)=records;edit(session,"A")
  val preview=session.readHomeHistory(store);val writes=store.writes;val listings=store.listings
  val result=assertIs<HomePushResult.Unchanged>(session.pushFromHome(preview,store){true})
  assertTrue(result.history.currentProtected);assertEquals(writes,store.writes)
  assertEquals(listings+1,store.listings);assertEquals(a.id,session.baseline())
  val automatic=assertIs<AutomaticPushResult.Skipped>(session.automaticPush(store){true})
  assertEquals(a.snapshot.files,automatic.comparison.localFiles)
  assertEquals(writes,store.writes);assertFalse(session.hasPending());assertEquals(2,store.commits.size)
 }
 @Test fun onboardingReuseConsumesItsExistingRevalidatedGraph() {
  val (session,store,records)=seeded();val (a,b)=records;edit(session,"A")
  val comparison=session.compareOnboarding(store){true};val writes=store.writes;val listings=store.listings
  val result=assertIs<AppendPushResult.Success>(session.onboardingUpload(comparison,store){true})
  assertTrue(result.reused);assertEquals(a.id,result.commit.id);assertEquals(a.id,session.baseline())
  assertEquals(writes,store.writes);assertEquals(listings+1,store.listings);assertFalse(session.hasPending())
 }
 @Test fun reinstalledManualAndAutomaticClientsAssociateExistingContentWithoutCloudMutation() {
  val original=session();val store=Store();val a=push(original,store).commit
  val manual=session();manual.renameCurrent("my local name"){true};val writes=store.writes
  assertNull(manual.baseline());assertTrue(push(manual,store).reused)
  assertEquals(a.id,manual.baseline())
  assertEquals("my local name",(manual.directoryStatus() as SidecarRead.Valid).value.displayName)
  val automatic=session();assertNull(automatic.baseline())
  assertIs<AutomaticPushResult.Skipped>(automatic.automaticPush(store){true})
  assertEquals(a.id,automatic.baseline());assertEquals(writes,store.writes)
  assertEquals(1,store.commits.size);assertEquals(setOf(a.id),automatic.syncHistory().graph.tips)
 }
 @Test fun corruptHistoricalObjectCannotAuthorizeNoWriteSuccess() {
  val (session,store,records)=seeded();val (a,_)=records;edit(session,"A")
  store.objects[a.snapshot.id]=byteArrayOf(1,2,3);val writes=store.writes
  assertFails {session.push(store){true}}
  assertEquals(writes,store.writes);assertFalse(session.hasPending())
 }
 @Test fun matchingSubsetDoesNotCountAsCompleteContentIdentity() {
  val (session,store,records)=seeded();val (_,b)=records;edit(session,"A")
  session.root.resolve("game1/depth1.dat").writeText("additional file")
  val result=push(session,store)
  assertFalse(result.reused);assertEquals(2,result.commit.snapshot.files.size)
  assertEquals(listOf(b.id),result.commit.parents);assertEquals(3,store.commits.size)
 }
 @Test fun pendingRetryAlreadyPublishedByAnotherDeviceReusesCloudRecord() {
  val local=session();val store=Store();store.failWrites=true
  assertFails {local.push(store){true}};assertTrue(local.hasPending())
  store.failWrites=false
  val remote=session();val existing=push(remote,store).commit
  val comparison=local.compareOnboarding(store){true};val writes=store.writes
  val result=assertIs<AppendPushResult.Success>(local.onboardingUpload(comparison,store){true})
  assertTrue(result.reused);assertEquals(existing.id,result.commit.id);assertEquals(writes,store.writes)
  assertFalse(local.hasPending());assertEquals(existing.id,local.baseline());assertEquals(1,store.commits.size);assertEquals(1,store.objects.size)
  assertTrue(local.history().any {it.manifest.files==existing.snapshot.files})
 }
}
