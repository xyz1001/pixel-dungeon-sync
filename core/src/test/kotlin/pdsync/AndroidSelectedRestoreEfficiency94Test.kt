package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.*
import kotlin.io.path.*
import kotlin.test.*

/** Real restore, same-content no-op, and concurrent branches have distinct assertions. */
class AndroidSelectedRestoreEfficiency94Test {
 @TempDir lateinit var temp:Path
 private var sequence=0
 private val identity=AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon","Synthetic Shattered","a".repeat(64),AndroidSaveCapabilities(802,896,AndroidMergePolicy.SHATTERED_896))
 private val connection=AndroidConnection("https://dav.example.test/synthetic/","synthetic","test-only","hero")
 private fun files(turn:Int=1)=linkedMapOf("game1/game.dat" to """{"version":896,"init_ver":896,"turn":$turn}""".encodeToByteArray(),"game1/depth1.dat" to byteArrayOf(turn.toByte()))
 private inner class Bridge(initial:Map<String,ByteArray> = files()):AndroidGameBridge {
  var content=initial.toMutableMap();var exports=0;var restores=0;var afterExport:(()->Unit)?=null;var afterRestore:(()->Unit)?=null
  var targetChanged=false;var failRestore=false
  val target=AndroidGameTarget(identity,"synthetic-896",896,"b".repeat(64),"c".repeat(64))
  override fun targetGame()=if(targetChanged)target.copy(apkSha256="d".repeat(64)) else target
  override fun exportTo(emptyDestination:Path) {
   exports++;content.forEach {(name,bytes)->val p=emptyDestination.resolve(name);p.parent.createDirectories();p.writeBytes(bytes)}
   afterExport?.also {afterExport=null}?.invoke()
  }
  override fun restoreFrom(preparedDirectory:Path,expectedCurrentFiles:Map<String,String>)=error("unexpected fallback restore")
  override fun restoreVerified(snapshot:Snapshot,expectedCurrentFiles:Map<String,String>):AndroidRestoreReceipt {
   require(content.mapValues {hash(it.value)}==expectedCurrentFiles)
   restores++;check(!failRestore) {"Synthetic helper unavailable"};content=Snapshots.verify(snapshot,AndroidGameAdapter(identity)).toMutableMap()
   afterRestore?.also {afterRestore=null}?.invoke()
   return AndroidRestoreReceipt(targetGame(),snapshot.manifest.files,content.values.sumOf {it.size.toLong()})
  }
  override fun readSnapshot()=AndroidSaveObservation(targetGame(),content.mapValues {(_,bytes)->AndroidSaveFile(hash(bytes),bytes.size.toLong(),1700000000000)},content.filterKeys {it.endsWith("/game.dat")})
 }
 private class Store:AppendStore {
  val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>()
  var listings=0;var writes=0;var objectReads=0;var afterReadObject:(()->Unit)?=null;var afterCommit:(()->Unit)?=null
  override fun listCommits(cursor:String?):CommitPage {listings++;return CommitPage(commits.keys.toList())}
  override fun readCommit(id:String)=commits[id]?.copyOf()
  override fun readObject(id:String):ByteArray? {
   objectReads++;val bytes=objects[id]?.copyOf();afterReadObject?.also {afterReadObject=null}?.invoke();return bytes
  }
  override fun writeObject(id:String,bytes:ByteArray) {writes++;objects[id]=bytes.copyOf()}
  override fun writeCommit(id:String,bytes:ByteArray) {writes++;commits[id]=bytes.copyOf();afterCommit?.also {afterCommit=null}?.invoke()}
  override fun legacyPresent()=false
  fun latest()=json.decodeFromString<AppendCommit>(commits.values.last().decodeToString())
  fun child(parent:AppendCommit,parents:List<String> = listOf(parent.id)):AppendCommit {
   val snapshot=parent.snapshot.copy(id=UUID.randomUUID().toString())
   objects[snapshot.id]=objects.getValue(parent.snapshot.id).copyOf()
   return parent.copy(id=UUID.randomUUID().toString(),parents=parents,snapshot=snapshot).also {commits[it.id]=json.encodeToString(it).encodeToByteArray()}
  }
 }
 private data class Rig(val root:Path,val facade:AndroidSyncFacade)
 private fun rig(bridge:Bridge,store:Store)=temp.resolve("workspace-${sequence++}").let {Rig(it,AndroidSyncFacade(it,connection,bridge,identity,store,0))}
 private fun publish(store:Store,bridge:Bridge=Bridge())=rig(bridge,store).also {assertEquals("UPLOADED",it.facade.autoUpload().status)}
 private fun mirror(rig:Rig)=Files.walk(rig.root.resolve("connections")).use {paths->paths.filter {it.fileName.toString()=="mirror"}.findFirst().orElseThrow()}
 private fun repository(rig:Rig)=Files.walk(rig.root.resolve("connections")).use {paths->AppendRepository(paths.filter {it.fileName.toString()=="append-v2"}.findFirst().orElseThrow(),AndroidGameAdapter(identity),connection.profile)}
 private fun recoveryFiles(rig:Rig)=Files.walk(rig.root).use {paths->paths.filter {it.fileName.toString() in setOf("apply-required.txt","active-plan.txt") || it.toString().contains("restore-journals")&&it.fileName.toString().endsWith(".json")}.toList()}

 @Test fun sameCurrentTipWithoutBaselineVerifiesButDoesNotPublishRestoreOrAdopt() {
  val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge();val r=rig(bridge,store)
  val home=r.facade.inspectHome();val writes=store.writes;val objects=store.objectReads;val lists=store.listings
  val result=r.facade.restoreSelected(home,chosen.snapshot.id,false)
  assertEquals(1,bridge.exports);assertEquals(0,bridge.restores);assertEquals(writes,store.writes)
  assertEquals(objects+1,store.objectReads);assertEquals(lists+2,store.listings)
  assertFalse(result.isConflict);assertFalse(result.isPending);assertFalse(result.configurationComplete)
  assertEquals("IDENTICAL",result.refreshedInspection?.kind);assertNull(result.refreshedInspection?.comparison?.baselineId)
  assertNull(repository(r).baseline());assertTrue(recoveryFiles(r).isEmpty())
 }

 @Test fun sameTipAndAncestorBaselinesRemainExactlyUnchanged() {
  for(advance in listOf(false,true)) {
   val store=Store();val bridge=Bridge();val r=publish(store,bridge);val base=store.latest()
   val chosen=if(advance)store.child(base) else base
   val home=r.facade.inspectHome();val sidecar=DirectoryMetadataStore(mirror(r)).read();val writes=store.writes;val exports=bridge.exports
   val result=r.facade.restoreSelected(home,chosen.snapshot.id,false)
   assertEquals(exports+1,bridge.exports);assertEquals(0,bridge.restores);assertEquals(writes,store.writes)
   assertEquals(base.id,repository(r).baseline());assertEquals(base.id,result.refreshedInspection?.comparison?.baselineId)
   assertEquals(sidecar,DirectoryMetadataStore(mirror(r)).read());assertTrue(recoveryFiles(r).isEmpty())
   assertEquals(!advance,result.configurationComplete)
  }
 }

 @Test fun equalBranchChoiceStillPublishesResolutionWithoutRedundantPreservation() {
  val store=Store();publish(store);val chosen=store.latest();val sibling=store.child(chosen,emptyList())
  val bridge=Bridge();val r=rig(bridge,store);val home=r.facade.inspectHome();val writes=store.writes
  val result=r.facade.restoreSelected(home,chosen.snapshot.id,false)
  assertEquals(writes+2,store.writes);assertEquals(0,bridge.restores)
  assertEquals("resolution",store.latest().kind);assertEquals(setOf(chosen.id,sibling.id),store.latest().parents.toSet())
  assertTrue(result.configurationComplete);assertFalse(result.isConflict);assertTrue(recoveryFiles(r).isEmpty())
 }

 @Test fun differentDirtySoleTipUsesTwoDiscoveriesNoWritesAndCreatesNoBackup() {
  val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge(files(9));val r=rig(bridge,store)
  val before=bridge.content.mapValues {hash(it.value)};val home=r.facade.inspectHome();val lists=store.listings;val writes=store.writes
  val result=r.facade.restoreSelected(home,chosen.snapshot.id,true)
  assertEquals(lists+2,store.listings);assertEquals(writes,store.writes);assertEquals(1,bridge.restores);assertEquals(1,bridge.exports)
  assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)});assertTrue(result.configurationComplete)
  assertEquals(chosen,store.latest());assertEquals(chosen.id,repository(r).baseline())
  val backups=Files.walk(r.root).use {paths->paths.filter {it.parent?.fileName.toString()=="backups"&&it.fileName.toString().endsWith(".json")}.toList()}
  assertTrue(backups.isEmpty());assertNotEquals(before,bridge.content.mapValues {hash(it.value)})
  assertTrue(recoveryFiles(r).isEmpty())
 }

 @Test fun freshExportThatObservesNewCloudBranchRejectsBeforeAnyWriteOrRestore() {
  val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge(files(9));val r=rig(bridge,store)
  val home=r.facade.inspectHome();val writes=store.writes
  bridge.afterExport={store.child(chosen)}
  assertFailsWith<IllegalArgumentException>{r.facade.restoreSelected(home,chosen.snapshot.id,true)}
  assertEquals(writes,store.writes);assertEquals(0,bridge.restores);assertNull(repository(r).pending());assertTrue(recoveryFiles(r).isEmpty())
 }

 @Test fun dirtySoleTipStillRequiresOverwriteApprovalAndKeepsRecoveryGateWithoutBackup() {
  val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge(files(9));val r=rig(bridge,store)
  val before=bridge.content.mapValues {hash(it.value)};val writes=store.writes
  assertFailsWith<IllegalArgumentException>{r.facade.restoreSelected(r.facade.inspectHome(),chosen.snapshot.id,false)}
  assertEquals(0,bridge.restores);assertEquals(writes,store.writes);assertTrue(recoveryFiles(r).isEmpty())
  bridge.failRestore=true
  assertFailsWith<IllegalStateException>{r.facade.restoreSelected(r.facade.inspectHome(),chosen.snapshot.id,true)}
  assertEquals(1,bridge.restores);assertEquals(before,bridge.content.mapValues {hash(it.value)});assertEquals(writes,store.writes)
  assertTrue(r.facade.hasUnresolvedRestore());assertNull(repository(r).pending())
  val backups=Files.walk(r.root).use {paths->paths.filter {it.parent?.fileName.toString()=="backups"&&it.fileName.toString().endsWith(".json")}.toList()}
  assertTrue(backups.isEmpty())
  bridge.failRestore=false;r.facade.recoverRestore()
  assertEquals(2,bridge.restores);assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)})
  assertEquals(writes,store.writes);assertFalse(r.facade.hasUnresolvedRestore());assertTrue(recoveryFiles(r).isEmpty())
 }

 @Test fun lateBranchAfterFrozenTargetRemainsUnabsorbedAndReturnsConflict() {
  val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge(files(9));val r=rig(bridge,store)
  val home=r.facade.inspectHome();var late:AppendCommit?=null;val writes=store.writes
  store.afterReadObject={late=store.child(chosen,emptyList())}
  val result=r.facade.restoreSelected(home,chosen.snapshot.id,true)
  assertEquals(1,bridge.restores);assertTrue(result.isConflict);assertFalse(result.configurationComplete)
  assertEquals(writes,store.writes);assertEquals(chosen.id,repository(r).baseline())
  assertEquals(setOf(chosen.id,requireNotNull(late).id),result.refreshedInspection?.comparison?.observedTips)
  assertTrue(recoveryFiles(r).isEmpty())
 }

 @Test fun lateBranchDuringNoOpIsReportedByFinalDiscoveryWithoutBaselineChange() {
  val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge();val r=rig(bridge,store)
  val home=r.facade.inspectHome();val writes=store.writes;var late:AppendCommit?=null
  store.afterReadObject={late=store.child(chosen,emptyList())}
  val result=r.facade.restoreSelected(home,chosen.snapshot.id,false)
  assertEquals(writes,store.writes);assertEquals(0,bridge.restores);assertTrue(result.isConflict);assertFalse(result.configurationComplete)
  assertEquals(setOf(chosen.id,requireNotNull(late).id),result.refreshedInspection?.comparison?.observedTips)
  assertNull(repository(r).baseline());assertTrue(recoveryFiles(r).isEmpty())
 }

 @Test fun missingOrCorruptInitialObjectCannotRestoreOrPublishEvenWhenFilesMatch() {
  for(missing in listOf(false,true)) {
   val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge();val r=rig(bridge,store)
   val home=r.facade.inspectHome();val writes=store.writes
   if(missing)store.objects.remove(chosen.snapshot.id) else store.objects[chosen.snapshot.id]=byteArrayOf(1,2,3)
   assertFails{r.facade.restoreSelected(home,chosen.snapshot.id,false)}
   assertEquals(writes,store.writes);assertEquals(0,bridge.restores);assertNull(repository(r).baseline());assertTrue(recoveryFiles(r).isEmpty())
  }
 }

 @Test fun changedActualSaveOrTargetCannotUseTheNoOpShortcut() {
  for(changeTarget in listOf(false,true)) {
   val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge();val r=rig(bridge,store)
   val home=r.facade.inspectHome();val writes=store.writes
   if(changeTarget)bridge.targetChanged=true else bridge.content=files(2)
   assertFails{r.facade.restoreSelected(home,chosen.snapshot.id,false)}
   assertEquals(writes,store.writes);assertEquals(0,bridge.restores);assertNull(repository(r).baseline());assertTrue(recoveryFiles(r).isEmpty())
  }
 }

 @Test fun alreadyFrozenTargetIsNotDownloadedAgainWhenItsRemoteBlobChanges() {
  val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge(files(9));val r=rig(bridge,store)
  val home=r.facade.inspectHome();val reads=store.objectReads
  store.afterReadObject={store.objects[chosen.snapshot.id]=byteArrayOf(1,2,3)}
  val result=r.facade.restoreSelected(home,chosen.snapshot.id,true)
  assertTrue(result.configurationComplete);assertEquals(1,bridge.restores);assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)})
  assertEquals(reads+1,store.objectReads);assertTrue(recoveryFiles(r).isEmpty())
 }

 @Test fun finalDiscoveryFailureCannotReturnSuccessfulRestore() {
  val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge(emptyMap());val r=rig(bridge,store)
  val home=r.facade.inspectHome();bridge.afterRestore={store.commits.remove(chosen.id)}
  assertFails{r.facade.restoreSelected(home,chosen.snapshot.id,false)}
  assertEquals(1,bridge.restores);assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)})
  // The verified game receipt is real even though the final remote observation failed.
  assertFalse(r.facade.hasUnresolvedRestore())
 }

 @Test fun finalDiscoveryRejectsModifiedParentAndRetainsFrozenRestorePlan() {
  val store=Store();publish(store);val chosen=store.latest();store.child(chosen,emptyList());val bridge=Bridge(files(9));val r=rig(bridge,store)
  val home=r.facade.inspectHome()
  store.afterCommit={store.commits[chosen.id]=json.encodeToString(chosen.copy(kind="restore")).encodeToByteArray()}
  assertFailsWith<RemoteIntegrityException>{r.facade.restoreSelected(home,chosen.snapshot.id,true)}
  assertEquals(1,bridge.restores);assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)})
  val frozen=assertNotNull(repository(r).pending())
  assertEquals("resolution",frozen.commit.kind);assertEquals(chosen.snapshot.files,frozen.commit.snapshot.files)
  assertNotEquals(frozen.commit.id,repository(r).baseline());assertFalse(r.facade.hasUnresolvedRestore())
 }

 @Test fun loopbackDavBudgetSeparatesDifferentCurrentTipHistoryAndSameContentNoOp() {
  for(mode in listOf("different-current-tip","same-tip-no-op","different-history")) MockWebServer().use { server ->
   val historical=mode=="different-history";val noop=mode=="same-tip-no-op"
   val seed=Store();val seedBridge=Bridge();val seedRig=publish(seed,seedBridge);val first=seed.latest()
   seedBridge.content=files(2);assertEquals("UPLOADED",seedRig.facade.autoUpload().status)
   repeat(5) {seed.child(seed.latest())};val tip=seed.latest();val chosen=if(historical)first else tip
   val folder="/dav/${AndroidGameAdapter(identity).id}/${connection.profile}/"
   val remote=ConcurrentHashMap<String,ByteArray>()
   seed.commits.forEach {(id,bytes)->remote[folder+"pdsync-v2-commit-$id.json"]=bytes}
   seed.objects.forEach {(id,bytes)->remote[folder+"pdsync-v2-object-$id.zip"]=bytes}
   val requests=CopyOnWriteArrayList<String>()
   server.dispatcher=object:Dispatcher() {
    override fun dispatch(request:RecordedRequest):MockResponse {
     requests+="${request.method} ${request.path}"
     return when(request.method) {
      "GET"->remote[request.path]?.let {MockResponse().setBody(okio.Buffer().write(it))} ?: MockResponse().setResponseCode(404)
      "PUT"->{remote[requireNotNull(request.path)]=request.body.readByteArray();MockResponse().setResponseCode(201)}
      "PROPFIND"->{
       fun entry(path:String,collection:Boolean=false)="""<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype>${if(collection)"<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
       MockResponse().setResponseCode(207).setHeader("Content-Type","application/xml").setBody("""<d:multistatus xmlns:d="DAV:">${entry(folder,true)}${remote.keys.sorted().joinToString("") {entry(it)}}</d:multistatus>""")
      }
      else->MockResponse().setResponseCode(405)
     }
    }
   }
   server.start()
   val endpoint=server.url("/dav/").toString()
   val bridge=Bridge(if(historical)emptyMap() else files(if(noop)2 else 9));val root=temp.resolve("dav-${sequence++}")
   val facade=AndroidSyncFacade(root,AndroidConnection(endpoint,connection.username,connection.password,connection.profile),bridge,identity,
    AppendOnlyWebDav(endpoint,AndroidGameAdapter(identity).id,connection.profile,connection.username,connection.password),0)
   if(historical)facade.restoreSelected(facade.inspectHome(),tip.snapshot.id,false)
   val home=facade.inspectHome();val exports=bridge.exports;val restores=bridge.restores;requests.clear();val started=System.nanoTime()
   val result=facade.restoreSelected(home,chosen.snapshot.id,true)
   val elapsedMillis=(System.nanoTime()-started)/1_000_000
   println("RESTORE94_BENCH mode=$mode elapsed_ms=$elapsedMillis http=${requests.size} puts=${requests.count {it.startsWith("PUT ")}} lists=${requests.count {it.startsWith("PROPFIND ")}} exports=${bridge.exports-exports} restores=${bridge.restores-restores}")
   assertEquals(if(historical)27 else 20,requests.size)
   assertEquals(2,requests.count {it.startsWith("PROPFIND ")});assertEquals(if(historical)2 else 0,requests.count {it.startsWith("PUT ")})
   assertEquals(exports+1,bridge.exports);assertEquals(restores+if(noop)0 else 1,bridge.restores)
   assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)});assertFalse(result.isConflict)
   assertEquals(!noop,result.configurationComplete)
  }
 }
}
