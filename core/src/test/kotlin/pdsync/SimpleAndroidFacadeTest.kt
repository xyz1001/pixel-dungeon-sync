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
import pdsync.i18n.CoreMessages
import kotlin.io.path.*
import kotlin.test.*

/** Real restore, same-content no-op, and concurrent branches have distinct assertions. */
class SimpleAndroidFacadeTest {
 @TempDir lateinit var temp:Path
 private var sequence=0
 private val identity=AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon","Synthetic Shattered","a".repeat(64),AndroidSaveCapabilities(802,896))
 private val connection=AndroidConnection("https://dav.example.test/synthetic/","synthetic","test-only","hero")
 private fun files(turn:Int=1)=linkedMapOf("game1/game.dat" to """{"version":896,"init_ver":896,"turn":$turn}""".encodeToByteArray(),"game1/depth1.dat" to byteArrayOf(turn.toByte()))
 private inner class Bridge(initial:Map<String,ByteArray> = files()):AndroidGameBridge {
  var content=initial.toMutableMap();var exports=0;var restores=0;var afterExport:(()->Unit)?=null;var afterRestore:(()->Unit)?=null
  var archiveMode=false;var archiveFailure:Exception?=null;var archiveExports=0;var sentArchive:ByteArray?=null
  override fun exportArchive():ByteArray? {
   if(!archiveMode)return null
   archiveFailure?.let {throw it}
   archiveExports++;return Snapshots.fromFiles(content,AndroidGameAdapter(identity),"hero","fixture").zip.also {sentArchive=it}
  }
  var targetChanged=false;var failRestore=false;var targetReads=0
  val target=AndroidGameTarget(identity,"synthetic-896",896,"b".repeat(64),"c".repeat(64))
  override fun targetGame():AndroidGameTarget {targetReads++;return if(targetChanged)target.copy(apkSha256="d".repeat(64)) else target}
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
 private class Store:SimpleSaveStore {
  var tip:String?=null;val nodes=linkedMapOf<String,SaveNode>();val objects=linkedMapOf<String,ByteArray>();val calls=mutableListOf<String>()
  override fun latest():String? {calls+="GET latest";return tip}
  override fun node(hashId:String):SaveNode? {calls+="GET metadata";return nodes[hashId]}
  override fun createObject(hashId:String,bytes:ByteArray):Boolean {calls+="PUT zip";return objects.putIfAbsent(hashId,bytes)==null}
  override fun createNode(node:SaveNode):Boolean {calls+="PUT metadata";return nodes.putIfAbsent(node.hashId,node)==null}
  override fun setLatest(hashId:String){calls+="PUT latest";tip=hashId}
  override fun objectBytes(hashId:String):ByteArray {calls+="GET zip";return objects.getValue(hashId)}
 }
 private fun facade(bridge:Bridge,store:Store)=AndroidSyncFacade(temp.resolve("work-${sequence++}"),connection,bridge,identity,null,suppliedSimpleStore=store)
 @Test fun changedUploadsUseTheirOwnDefaultNamesAndPreserveExplicitCurrentName() {
  val store=Store();val bridge=Bridge();val facade=facade(bridge,store)
  val ids=(1..3).map {turn->
   bridge.content=files(turn).toMutableMap()
   val expected=contentSha256(bridge.content.mapValues {hash(it.value)})
   val id=requireNotNull(facade.upload(facade.inspectHome()).snapshotId)
   assertEquals(expected,id);assertEquals(CoreMessages.SAVE_1_S.text(id.take(6)),store.nodes.getValue(id).name)
   id
  }
  assertEquals(3,ids.toSet().size);assertEquals(3,store.nodes.size)
  store.calls.clear();assertEquals(ids.last(),facade.upload(facade.inspectHome()).snapshotId)
  assertEquals(3,store.nodes.size);assertEquals(3,store.objects.size)
  assertFalse(store.calls.any {it.startsWith("PUT ")})
  bridge.content=files(4).toMutableMap();val view=facade.inspectHome()
  facade.renameCurrent(view,"Custom name")
  val named=requireNotNull(facade.upload(view).snapshotId)
  assertEquals(contentSha256(bridge.content.mapValues {hash(it.value)}),named)
  assertEquals("Custom name",store.nodes.getValue(named).name)
 }
 @Test fun uploadHistoryRestoreUsesOnlyNewLayout() {
  val store=Store();val bridge=Bridge();val facade=facade(bridge,store)
  val initial=facade.inspectHome();store.calls.clear();val exports=bridge.exports
  val first=facade.upload(initial);assertFalse(first.isConflict);assertTrue(first.configurationComplete)
  assertEquals(exports+1,bridge.exports)
  assertEquals(listOf("GET latest","PUT zip","PUT metadata","PUT latest"),store.calls)
  val firstId=requireNotNull(first.snapshotId)
  bridge.content=files(2).toMutableMap();val view=facade.inspectHome();store.calls.clear()
  val second=facade.upload(view);assertEquals(firstId,store.nodes.getValue(requireNotNull(second.snapshotId)).parentId)
  val history=facade.historyPresentation(requireNotNull(second.refreshedInspection));assertEquals(2,history.simple!!.nodes.size)
  store.calls.clear();val restored=facade.restoreSelected(requireNotNull(second.refreshedInspection),firstId,true)
  assertFalse(restored.isConflict,"successful historical restore is not an upload conflict")
  assertTrue(requireNotNull(restored.refreshedInspection).isOverwriteConflict,"explicit later upload still needs confirmation")
  assertEquals(second.snapshotId,store.tip,"restore must not publish or switch latest")
  assertEquals(listOf("GET zip"),store.calls);assertEquals(1,bridge.restores)
  assertEquals(files().keys,bridge.content.keys);files().forEach {(name,bytes)->assertContentEquals(bytes,bridge.content.getValue(name))}
  val forbidden=Files.walk(temp).use {paths->paths.filter {it.fileName.toString() in setOf("known","observations","history")}.toList()}
  assertTrue(forbidden.isEmpty(),"no old history database or local archive")
 }
 @Test fun directArchiveUploadsSameBytesWithoutFilesystemExportAndRestores() {
  val store=Store();val bridge=Bridge().also {it.archiveMode=true};val facade=facade(bridge,store)
  val first=facade.upload(facade.inspectHome());val id=requireNotNull(first.snapshotId)
  assertEquals(0,bridge.exports);assertEquals(1,bridge.archiveExports)
  assertSame(bridge.sentArchive,store.objects.getValue(id))
  assertEquals(contentSha256(bridge.content.mapValues {hash(it.value)}),id)
  bridge.content=files(2).toMutableMap()
  facade.restoreSelected(requireNotNull(first.refreshedInspection),id,true)
  files().forEach {(name,bytes)->assertContentEquals(bytes,bridge.content.getValue(name))}
 }
 @Test fun archiveIoFailureAndCancellationDoNotPublish() {
  for(failure in listOf(java.io.IOException("synthetic read failure"),InterruptedException())) {
   val store=Store();val bridge=Bridge().also {it.archiveMode=true;it.archiveFailure=failure};val facade=facade(bridge,store)
   val view=facade.inspectHome();store.calls.clear()
   assertFails {facade.upload(view)}
   assertTrue(store.calls.isEmpty());assertNull(store.tip)
  }
 }
 @Test fun homeRequestsStayConstantAndHistoryLoadsOnDemand() {
  val store=Store();val bridge=Bridge();val facade=facade(bridge,store)
  var parent:String?=null
  repeat(100) {i->val id=hash("node-$i".encodeToByteArray());store.nodes[id]=SaveNode(id,parent,"node");parent=id}
  store.tip=parent
  facade.inspectHome();assertEquals(listOf("GET latest","GET metadata"),store.calls)
  store.calls.clear();val full=facade.inspectHomeHistory()
  assertEquals(100,facade.historyPresentation(full).simple!!.nodes.size)
  assertEquals(1,store.calls.count {it=="GET latest"});assertEquals(100,store.calls.count {it=="GET metadata"})
 }
 @Test fun conflictAndExplicitConfirmationUseExistingFacade() {
  val store=Store();val a=facade(Bridge(),store);a.upload(a.inspectHome())
  val bridge=Bridge(files(3));val b=facade(bridge,store);val view=b.inspectHome()
  assertTrue(view.isOverwriteConflict);val rejected=b.upload(view);assertTrue(rejected.isConflict)
  val refreshed=requireNotNull(rejected.refreshedInspection);val old=store.tip
  val accepted=b.overwriteCloudSuccessors(refreshed,requireNotNull(refreshed.overwriteTargetSnapshotId))
  assertFalse(accepted.isConflict);assertNull(store.nodes.getValue(requireNotNull(accepted.snapshotId)).parentId);assertTrue(store.nodes.containsKey(old))
  assertEquals(listOf(accepted.snapshotId),b.historyPresentation(requireNotNull(accepted.refreshedInspection)).simple!!.nodes.map {it.hashId})
 }
 @Test fun overwriteReceiptExcludesReplacedBranchWithoutReadingHistory() {
  val store=Store();val bridge=Bridge();val facade=facade(bridge,store)
  val a=facade.upload(facade.inspectHome())
  bridge.content=files(2).toMutableMap();val b=facade.upload(facade.inspectHome())
  bridge.content=files(3).toMutableMap();val c=facade.upload(facade.inspectHome())
  facade.restoreSelected(facade.inspectHomeHistory(),requireNotNull(b.snapshotId),true)
  bridge.content=files(4).toMutableMap()
  val conflict=facade.upload(facade.inspectHome())
  val view=requireNotNull(conflict.refreshedInspection);store.calls.clear()
  val e=facade.overwriteCloudSuccessors(view,requireNotNull(c.snapshotId))
  assertEquals(b.snapshotId,store.nodes.getValue(requireNotNull(e.snapshotId)).parentId)
  assertEquals(listOf(e.snapshotId),facade.historyPresentation(requireNotNull(e.refreshedInspection)).simple!!.nodes.map {it.hashId})
  assertEquals(listOf("GET latest","PUT zip","PUT metadata","PUT latest"),store.calls)
  assertTrue(store.nodes.containsKey(c.snapshotId));assertTrue(store.objects.containsKey(c.snapshotId))
  assertEquals(listOf(e.snapshotId,b.snapshotId,a.snapshotId),facade.historyPresentation(facade.inspectHomeHistory()).simple!!.nodes.map {it.hashId})
 }
}
