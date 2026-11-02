package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.*
import kotlin.io.path.*
import kotlin.test.*

/** End-to-end facade receipt regressions using actual in-memory game bytes and immutable cloud objects. */
class AndroidRestoreReceiptRoundTrip97Test {
 @TempDir lateinit var temp:Path
 private var sequence=0
 private val identity=AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon","Synthetic Shattered","a".repeat(64),AndroidSaveCapabilities(802,896))
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
 private fun rig(bridge:Bridge,store:Store)=temp.resolve("workspace-${sequence++}").let {Rig(it,AndroidSyncFacade(it,connection,bridge,identity,store))}
 private fun publish(store:Store,bridge:Bridge=Bridge())=rig(bridge,store).also {assertEquals("UPLOADED",it.facade.autoUpload().status)}
 private fun mirror(rig:Rig)=Files.walk(rig.root.resolve("connections")).use {paths->paths.filter {it.fileName.toString()=="mirror"}.findFirst().orElseThrow()}
 private fun repository(rig:Rig)=Files.walk(rig.root.resolve("connections")).use {paths->AppendRepository(paths.filter {it.fileName.toString()=="append-v2"}.findFirst().orElseThrow(),AndroidGameAdapter(identity),connection.profile)}
 private fun recoveryFiles(rig:Rig)=Files.walk(rig.root).use {paths->paths.filter {it.fileName.toString() in setOf("apply-required.txt","active-plan.txt") || it.toString().contains("restore-journals")&&it.fileName.toString().endsWith(".json")}.toList()}

 @Test fun selectedAThenBThenAReallyWritesAndFreshReceiptTracksExactBytes() {
  val store=Store();val bridge=Bridge(files(1));val r=publish(store,bridge);val a=store.latest()
  bridge.content=files(2).toMutableMap()
  assertEquals("UPLOADED",r.facade.autoUpload().status);val b=store.latest()
  assertNotEquals(a.snapshot.files,b.snapshot.files)
  var inspection=r.facade.inspectHome()
  listOf(a,b,a).forEachIndexed { index,chosen ->
   val result=r.facade.restoreSelected(inspection,chosen.snapshot.id,true)
   inspection=assertNotNull(result.refreshedInspection)
   val history=r.facade.historyPresentation(inspection)
   assertEquals(index+1,bridge.restores,"each different-content choice must write actual bridge")
   assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)},"actual game hashes")
   assertEquals(chosen.snapshot.files,Snapshots.fileHashes(mirror(r),AndroidGameAdapter(identity)),"mirror hashes")
   assertEquals(chosen.snapshot.files,history.currentFiles,"receipt history hashes")
   assertEquals(2,store.commits.size,"restore does not publish same-content records")
   assertEquals(chosen.id,history.baseline,"local association is the selected original commit")
   assertTrue(chosen.id in history.verifiedCommitIds)
   assertTrue(history.graph.commits.values.single {it.snapshot.id==chosen.snapshot.id}.snapshot.files==history.currentFiles,
    "chosen original card is correctly same-content and restore unnecessary")
   val other=if(chosen.id==a.id)b else a
   assertFalse(history.graph.commits.values.single {it.snapshot.id==other.snapshot.id}.snapshot.files==history.currentFiles,
    "other different-content card must not be suppressed as restore unnecessary")
   assertFalse(result.isConflict);assertFalse(result.isPending)
   assertTrue(recoveryFiles(r).isEmpty())
  }
  bridge.content=files(3).toMutableMap()
  val uploaded=r.facade.upload(r.facade.inspect())
  val next=store.latest()
  assertEquals(b.id,next.id,"preflight must not publish new gameplay before confirmation")
  assertEquals(2,store.commits.size)
  assertEquals(a.id,r.facade.historyPresentation(r.facade.inspectHome()).baseline)
  assertTrue(uploaded.isConflict,"restored A versus cloud B requires confirmation before writes")
 }
 @Test fun restoredOneObjectDoesNotVerifyUnrelatedCloudObjects() {
  val store=Store();val source=Bridge(files(1));val sourceRig=publish(store,source);val a=store.latest()
  source.content=files(2).toMutableMap();assertEquals("UPLOADED",sourceRig.facade.autoUpload().status);val b=store.latest()
  val bridge=Bridge(files(9));val target=rig(bridge,store)
  val restored=target.facade.restoreSelected(target.facade.inspectHome(),a.snapshot.id,true)
  val history=target.facade.historyPresentation(assertNotNull(restored.refreshedInspection))
  assertEquals(a.snapshot.files,bridge.content.mapValues {hash(it.value)})
  assertEquals(a.id,history.baseline)
  assertTrue(a.id in history.verifiedCommitIds)
  assertFalse(b.id in history.verifiedCommitIds,"metadata-only B must not inherit A object proof")
  assertEquals(2,store.commits.size)
 }
}
