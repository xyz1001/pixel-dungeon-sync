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
   val (child,zip)=changedHashFixture(parent,objects.getValue(parent.snapshot.id),parents,commits.size)
   objects[child.snapshot.id]=zip
   return child.also {commits[it.id]=json.encodeToString(it).encodeToByteArray()}
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

 @Test fun sameTipIsUnchangedWhileDifferentDescendantRestoresVerifiedContent() {
  for(advance in listOf(false,true)) {
   val store=Store();val bridge=Bridge();val r=publish(store,bridge);val base=store.latest()
   val chosen=if(advance)store.child(base) else base
   val home=r.facade.inspectHome();val sidecar=DirectoryMetadataStore(mirror(r)).read();val writes=store.writes;val exports=bridge.exports
   val result=r.facade.restoreSelected(home,chosen.snapshot.id,false)
   assertEquals(exports+1,bridge.exports);assertEquals(if(advance)1 else 0,bridge.restores);assertEquals(writes,store.writes)
   assertEquals(chosen.id,repository(r).baseline());assertEquals(chosen.id,result.refreshedInspection?.comparison?.baselineId)
   assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)})
   if(!advance)assertEquals(sidecar,DirectoryMetadataStore(mirror(r)).read())
   assertTrue(recoveryFiles(r).isEmpty());assertTrue(result.configurationComplete)
  }
 }

 @Test fun equalBranchChoiceAdoptsSelectionWithoutPublishingOrAbsorbingSibling() {
  val store=Store();publish(store);val chosen=store.latest();val sibling=store.child(chosen,emptyList())
  val bridge=Bridge();val r=rig(bridge,store);val home=r.facade.inspectHome();val writes=store.writes
  val result=r.facade.restoreSelected(home,chosen.snapshot.id,false)
  assertEquals(writes,store.writes);assertEquals(0,bridge.restores)
  assertEquals(2,store.commits.size);assertEquals(setOf(chosen.id,sibling.id),repository(r).discover(store).tips)
  assertEquals(chosen.id,repository(r).baseline());assertNull(repository(r).pending())
  assertFalse(result.configurationComplete);assertTrue(result.isConflict);assertTrue(recoveryFiles(r).isEmpty())
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

 @Test fun finalDiscoveryRejectsModifiedSelectedCommitWithoutInventingRestoreUpload() {
  val store=Store();publish(store);val chosen=store.latest();store.child(chosen,emptyList());val bridge=Bridge(files(9));val r=rig(bridge,store)
  val home=r.facade.inspectHome()
  val writes=store.writes
  bridge.afterRestore={store.commits[chosen.id]=json.encodeToString(chosen.copy(kind="restore")).encodeToByteArray()}
  assertFailsWith<RemoteIntegrityException>{r.facade.restoreSelected(home,chosen.snapshot.id,true)}
  assertEquals(1,bridge.restores);assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)})
  assertNull(repository(r).pending());assertEquals(writes,store.writes)
  assertEquals(chosen.id,repository(r).baseline());assertFalse(r.facade.hasUnresolvedRestore())

 }

 @Test fun loopbackDavBudgetSeparatesDifferentCurrentTipHistoryAndSameContentNoOp() {
  for(mode in listOf("different-current-tip","same-tip-no-op","different-history")) MockWebServer().use { server ->
   val historical=mode=="different-history";val noop=mode=="same-tip-no-op"
   val seed=Store();val seedBridge=Bridge();val seedRig=publish(seed,seedBridge);val first=seed.latest()
   seedBridge.content=files(2);assertEquals("UPLOADED",seedRig.facade.autoUpload().status)
   repeat(5) {seed.child(seed.latest())};val tip=seed.latest();val chosen=if(historical)first else tip
   val folder="/dav/${AndroidGameAdapter(identity).id}/${connection.profile}/"
   val remote=ConcurrentHashMap<String,ByteArray>()
   seed.commits.forEach {(id,bytes)->remote[folder+"pdsync-v${if(validContentHashId(id))3 else 2}-commit-$id.json"]=bytes}
   seed.objects.forEach {(id,bytes)->remote[folder+"pdsync-v${if(validContentHashId(id))3 else 2}-object-$id.zip"]=bytes}
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
   val bridge=Bridge(if(historical)emptyMap() else if(noop)Snapshots.verify(Snapshot(tip.snapshot,seed.objects.getValue(tip.snapshot.id)),AndroidGameAdapter(identity)) else files(9));val root=temp.resolve("dav-${sequence++}")
   val facade=AndroidSyncFacade(root,AndroidConnection(endpoint,connection.username,connection.password,connection.profile),bridge,identity,
    AppendOnlyWebDav(endpoint,AndroidGameAdapter(identity).id,connection.profile,connection.username,connection.password),0)
   if(historical)facade.restoreSelected(facade.inspectHome(),tip.snapshot.id,false)
   val home=facade.inspectHome();val exports=bridge.exports;val restores=bridge.restores;requests.clear();val started=System.nanoTime()
   val result=facade.restoreSelected(home,chosen.snapshot.id,true)
   val elapsedMillis=(System.nanoTime()-started)/1_000_000
   println("RESTORE94_BENCH mode=$mode elapsed_ms=$elapsedMillis http=${requests.size} puts=${requests.count {it.startsWith("PUT ")}} lists=${requests.count {it.startsWith("PROPFIND ")}} exports=${bridge.exports-exports} restores=${bridge.restores-restores}")
   assertEquals(20,requests.size)
   assertEquals(2,requests.count {it.startsWith("PROPFIND ")});assertEquals(0,requests.count {it.startsWith("PUT ")})
   assertEquals(exports+1,bridge.exports);assertEquals(restores+if(noop)0 else 1,bridge.restores)
   assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)});assertFalse(result.isConflict)
   assertEquals(!noop&&!historical,result.configurationComplete)
  }
 }
 @Test fun uploadScopeReusesHistoricalObjectsButKeepsFreshPublicationReads() {
  for(mode in listOf("upload")) MockWebServer().use { server ->
   val historical=mode=="different-history";val noop=mode=="same-tip-no-op"
   val seed=Store();val seedBridge=Bridge();val seedRig=publish(seed,seedBridge);val first=seed.latest()
   seedBridge.content=files(2);assertEquals("UPLOADED",seedRig.facade.autoUpload().status)
   repeat(5) {seed.child(seed.latest())};val tip=seed.latest();val chosen=if(historical)first else tip
   val folder="/dav/${AndroidGameAdapter(identity).id}/${connection.profile}/"
   val remote=ConcurrentHashMap<String,ByteArray>()
   seed.commits.forEach {(id,bytes)->remote[folder+"pdsync-v${if(validContentHashId(id))3 else 2}-commit-$id.json"]=bytes}
   seed.objects.forEach {(id,bytes)->remote[folder+"pdsync-v${if(validContentHashId(id))3 else 2}-object-$id.zip"]=bytes}
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
   val bridge=Bridge(if(historical)emptyMap() else if(noop)Snapshots.verify(Snapshot(tip.snapshot,seed.objects.getValue(tip.snapshot.id)),AndroidGameAdapter(identity)) else files(9));val root=temp.resolve("dav-${sequence++}")
   val facade=AndroidSyncFacade(root,AndroidConnection(endpoint,connection.username,connection.password,connection.profile),bridge,identity,
    AppendOnlyWebDav(endpoint,AndroidGameAdapter(identity).id,connection.profile,connection.username,connection.password),0)
   if(historical)facade.restoreSelected(facade.inspectHome(),tip.snapshot.id,false)
   val home=facade.inspectHome();val exports=bridge.exports;val restores=bridge.restores;requests.clear();val started=System.nanoTime()
   val result=facade.withUploadReadScope(java.util.concurrent.Callable { facade.upload(home) })
   val oldObjects=seed.objects.keys.map { folder+"pdsync-v3-object-$it.zip" }.toSet()
   oldObjects.forEach { path->assertEquals(1,requests.count {it=="GET $path"},"old immutable object read once") }
   assertEquals(2,requests.count {it.startsWith("PUT ")},"one object and one conditional commit")
   assertEquals(2,requests.count {it.startsWith("PROPFIND ")},"one preflight and one delta listing")
   assertEquals(1,requests.takeWhile {!it.startsWith("PUT ")}.count {it.startsWith("PROPFIND ")},"exactly one fresh history before upload")
   assertNotNull(result.refreshedInspection,"UI uses completed operation receipt, no full refresh")
   assertEquals(0,bridge.restores)
   println("UPLOAD104_BENCH http=${requests.size} old_objects=${oldObjects.size} old_object_gets=${requests.count {it.removePrefix("GET ") in oldObjects}}")
  }
 }

 @Test fun uploadFreshRejectsCorruptInitialObjectAndCancellationBeforeWriting() {
  for(cancel in listOf(false,true)) {
   val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge(files(9));val r=rig(bridge,store)
   val home=r.facade.inspectHome();val writes=store.writes
   if(cancel)store.afterReadObject={throw java.util.concurrent.CancellationException()} else store.objects[chosen.snapshot.id]=byteArrayOf(1)
   assertFails {r.facade.upload(home)}
   assertEquals(writes,store.writes);assertEquals(0,bridge.restores)
  }
 }
 @Test fun uploadDeltaKeepsConcurrentBranchAndDetectsCorruptReadback() {
  for(corrupt in listOf(false,true)) {
   val store=Store();publish(store);val parent=store.latest();val bridge=Bridge(files(9));val r=rig(bridge,store)
   val home=r.facade.inspectHome();val listed=store.listings
   var branch:AppendCommit?=null
   store.afterCommit={if(corrupt)store.objects[store.latest().snapshot.id]=byteArrayOf(1) else branch=store.child(parent)}
   if(corrupt) {
    assertFails {r.facade.upload(home)};assertNotNull(repository(r).pending(),"failed readback preserves frozen pending")
   } else {
    val result=r.facade.upload(home);assertTrue(result.isConflict);val receipt=assertNotNull(result.refreshedInspection)
    assertEquals(2,store.listings-listed);assertTrue(receipt.history!!.cloud.any {it.id==branch!!.id})
    assertTrue(store.commits.containsKey(parent.id));assertEquals(3,store.commits.size)
   }
   assertEquals(0,bridge.restores)
  }
 }

 @Test fun uploadDeltaRejectsChangedOrDeletedPreviouslyKnownCommit() {
  for(deleted in listOf(false,true)) {
   val store=Store();publish(store);val parent=store.latest();val r=rig(Bridge(files(9)),store);val home=r.facade.inspectHome()
   store.afterCommit={if(deleted)store.commits.remove(parent.id) else store.commits[parent.id]=json.encodeToString(parent.copy(kind="restore")).encodeToByteArray()}
   assertFails {r.facade.upload(home)}
   assertNotNull(repository(r).pending(),"metadata inconsistency cannot clear frozen pending")
  }
 }

}
