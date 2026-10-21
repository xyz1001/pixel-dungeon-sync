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
class ProductionRestoreReadOnly97Test {
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


 private inner class Dav:AutoCloseable {
  val server=MockWebServer();val remote=ConcurrentHashMap<String,ByteArray>();val requests=CopyOnWriteArrayList<String>()
  val folder="/dav/${AndroidGameAdapter(identity).id}/${connection.profile}/"
  var failObjects=false
  init {
   server.dispatcher=object:Dispatcher() {
    override fun dispatch(request:RecordedRequest):MockResponse {
     val path=requireNotNull(request.path);requests+="${request.method} $path"
     return when(request.method) {
      "GET"->if(failObjects&&path.endsWith(".zip"))MockResponse().setResponseCode(503) else remote[path]?.let {MockResponse().setBody(okio.Buffer().write(it))} ?: MockResponse().setResponseCode(404)
      "HEAD"->MockResponse().setResponseCode(if(remote.containsKey(path))200 else 404)
      "PUT"->{remote[path]=request.body.readByteArray();MockResponse().setResponseCode(201)}
      "MKCOL"->MockResponse().setResponseCode(201)
      "PROPFIND"->{
       fun entry(path:String,collection:Boolean=false)="""<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype>${if(collection)"<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
       MockResponse().setResponseCode(207).setHeader("Content-Type","application/xml").setBody("""<d:multistatus xmlns:d="DAV:">${entry(path,true)}${remote.keys.filter {it.startsWith(path)}.sorted().joinToString("") {entry(it)}}</d:multistatus>""")
      }
      else->MockResponse().setResponseCode(405)
     }
    }
   };server.start()
  }
  fun seed(store:Store) {
   store.commits.forEach {(id,bytes)->remote[folder+"pdsync-v2-commit-$id.json"]=bytes.copyOf()}
   store.objects.forEach {(id,bytes)->remote[folder+"pdsync-v2-object-$id.zip"]=bytes.copyOf()}
  }
  fun rig(bridge:Bridge):Rig {
   val endpoint=server.url("/dav/").toString();val root=temp.resolve("http-${sequence++}")
   return Rig(root,AndroidSyncFacade(root,AndroidConnection(endpoint,connection.username,connection.password,connection.profile),bridge,identity,
    AppendOnlyWebDav(endpoint,AndroidGameAdapter(identity).id,connection.profile,connection.username,connection.password),0))
  }
  fun commits()=remote.filterKeys {it.contains("pdsync-v2-commit-")}.values.map {json.decodeFromString<AppendCommit>(it.decodeToString())}
  fun onlyTip():AppendCommit {val all=commits();val parents=all.flatMap {it.parents}.toSet();return all.single {it.id !in parents}}
  fun <T> readOnly(label:String,action:()->T):T {
   val before=remote.mapValues {it.value.copyOf()};requests.clear()
   try {return action()} finally {
    assertTrue(requests.all {it.substringBefore(' ') in setOf("GET","HEAD","PROPFIND")},"$label attempted mutation: $requests")
    assertEquals(before.keys,remote.keys,"$label changed cloud keys")
    before.forEach {(name,bytes)->assertContentEquals(bytes,remote.getValue(name),"$label changed $name")}
   }
  }
  override fun close(){server.close()}
 }
 @Test fun tipHistoricalSameContentAndFailureRetryUseOnlyReadHttpAndPreserveEveryCloudByte() {
  Dav().use {dav->
   val seed=Store();val source=Bridge(files(1));val initial=publish(seed,source);val a=seed.latest()
   source.content=files(2);assertEquals("UPLOADED",initial.facade.autoUpload().status);val b=seed.latest();dav.seed(seed)
   val bridge=Bridge(files(9));val r=dav.rig(bridge)
   for((label,chosen) in listOf("tip" to b,"historical" to a,"newer again" to b,"same content" to b)) {
    dav.readOnly(label) {
     val result=r.facade.restoreSelected(r.facade.inspectHome(),chosen.snapshot.id,true)
     assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)})
     assertEquals(chosen.snapshot.files,r.facade.historyPresentation(assertNotNull(result.refreshedInspection)).currentFiles)
    }
   }
   dav.readOnly("helper failure") {
    bridge.failRestore=true
    assertFails {r.facade.restoreSelected(r.facade.inspectHome(),a.snapshot.id,true)}
    assertEquals(b.snapshot.files,bridge.content.mapValues {hash(it.value)})
   }
   dav.readOnly("helper recovery and retry") {
    bridge.failRestore=false;r.facade.recoverRestore()
    assertEquals(a.snapshot.files,bridge.content.mapValues {hash(it.value)})
    r.facade.restoreSelected(r.facade.inspectHome(),a.snapshot.id,true)
   }
   val second=dav.rig(Bridge(files(9)))
   dav.readOnly("download failure") {dav.failObjects=true;assertFails {second.facade.restoreSelected(second.facade.inspectHome(),b.snapshot.id,true)}}
   dav.readOnly("download retry") {dav.failObjects=false;second.facade.restoreSelected(second.facade.inspectHome(),b.snapshot.id,true)}
  }
 }
 @Test fun explicitBranchResolutionUsesOnlyReadHttpAndLeavesAllBranchesUnchanged() {
  Dav().use {dav->
   val seed=Store();val source=Bridge(files(1));val initial=publish(seed,source);val a=seed.latest()
   source.content=files(2);assertEquals("UPLOADED",initial.facade.autoUpload().status);val b=seed.latest()
   val sibling=seed.child(a);dav.seed(seed)
   val bridge=Bridge(files(9));val r=dav.rig(bridge)
   dav.readOnly("selected branch") {r.facade.restoreSelected(r.facade.inspectHome(),b.snapshot.id,true)}
   dav.readOnly("explicit resolve branch") {
    val result=r.facade.resolveConflict(r.facade.inspect(),sibling.snapshot.id)
    assertTrue(result.isConflict,"unchanged remote branches remain visible")
    assertEquals(sibling.snapshot.files,bridge.content.mapValues {hash(it.value)})
   }
  }
 }
 @Test fun independentMergeOnlyPreparesLocallyAndSeparateUploadPublishesExactPreparedParents() {
  Dav().use {dav->
   val initial=(1..2).flatMap {slot->files(1).map {(name,bytes)->name.replaceFirst("game1/","game$slot/") to bytes}}.toMap()
   val remoteBridge=Bridge(initial);val remote=dav.rig(remoteBridge)
   assertEquals("UPLOADED",remote.facade.autoUpload().status);val base=dav.onlyTip()
   val bridge=Bridge(emptyMap());val local=dav.rig(bridge)
   dav.readOnly("merge setup restore") {local.facade.restoreSelected(local.facade.inspectHome(),base.snapshot.id,true)}
   remoteBridge.content["game1/depth1.dat"]=byteArrayOf(2)
   remote.facade.confirmRunIdentity(remote.facade.inspect(),"game1",true)
   assertEquals("UPLOADED",remote.facade.autoUpload().status);val tip=dav.onlyTip()
   bridge.content["game2/depth1.dat"]=byteArrayOf(3)
   local.facade.confirmRunIdentity(local.facade.inspect(),"game2",true)
   val expected=(bridge.content+("game1/depth1.dat" to remoteBridge.content.getValue("game1/depth1.dat"))).mapValues {hash(it.value)}
   val prepared=dav.readOnly("independent merge") {
    val inspection=local.facade.inspect();assertTrue(local.facade.previewMerge(inspection).isAllowed)
    val result=local.facade.mergeConflict(inspection)
    assertTrue(result.isPending);assertEquals(expected,bridge.content.mapValues {hash(it.value)})
    assertNotNull(repository(local).pending()).commit
   }
   assertEquals(listOf(tip.id),prepared.parents)
   assertEquals(expected,prepared.snapshot.files)
   assertTrue(dav.commits().none {it.id==prepared.id})
   dav.requests.clear()
   val uploaded=local.facade.upload(local.facade.inspect())
   assertFalse(uploaded.isPending);assertFalse(uploaded.isConflict)
   assertTrue(dav.requests.any {it.startsWith("PUT ")},"separate explicit upload publishes")
   assertEquals(prepared,dav.commits().single {it.id==prepared.id})
   assertEquals(prepared.id,dav.onlyTip().id)
  }
 }
}
