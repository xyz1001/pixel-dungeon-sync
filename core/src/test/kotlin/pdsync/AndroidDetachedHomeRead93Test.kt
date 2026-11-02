package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.*
import kotlin.io.path.*
import kotlin.test.*

class AndroidDetachedHomeRead93Test {
 @TempDir lateinit var temp:Path
 private var sequence=0
 private val identity=AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon","Synthetic Shattered","a".repeat(64),AndroidSaveCapabilities(802,896))
 private val connection=AndroidConnection("https://dav.example.test/synthetic/","synthetic","test-only","hero")
 private fun files(turn:Int=1)=linkedMapOf("game1/game.dat" to """{"version":896,"init_ver":896,"turn":$turn}""".encodeToByteArray(),"game1/depth1.dat" to byteArrayOf(turn.toByte()),"rankings.dat" to """{"total":23}""".encodeToByteArray())
 private inner class Bridge(initial:Map<String,ByteArray> = files()):AndroidGameBridge {
  var content=initial.toMutableMap();var exports=0;var reads=0;var supported=true;var targetChange=false
  val target=AndroidGameTarget(identity,"synthetic-896",896,"b".repeat(64),"c".repeat(64))
  override fun targetGame()=if(targetChange)target.copy(apkSha256="d".repeat(64)) else target
  override fun exportTo(emptyDestination:Path) {exports++;content.forEach {(name,bytes)->val p=emptyDestination.resolve(name);p.parent.createDirectories();p.writeBytes(bytes);Files.setLastModifiedTime(p,FileTime.fromMillis(1700000000000))}}
  override fun restoreFrom(preparedDirectory:Path,expectedCurrentFiles:Map<String,String>)=error("unexpected restore")
  override fun restoreVerified(snapshot:Snapshot,expectedCurrentFiles:Map<String,String>):AndroidRestoreReceipt {
   require(content.mapValues {hash(it.value)}==expectedCurrentFiles)
   content=Snapshots.verify(snapshot,AndroidGameAdapter(identity)).toMutableMap()
   return AndroidRestoreReceipt(targetGame(),snapshot.manifest.files,content.values.sumOf {it.size.toLong()})
  }
  override fun readSnapshot():AndroidSaveObservation? {
   reads++;if(!supported)return null
   return AndroidSaveObservation(targetGame(),content.mapValues {(name,bytes)->AndroidSaveFile(hash(bytes),bytes.size.toLong(),if(name.endsWith("depth1.dat"))1700000020000 else 1700000000000)},content.filterKeys {it=="rankings.dat"||it.endsWith("/game.dat")})
  }
 }
 private class Store:AppendStore {
  val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>();var objectReads=0;var writes=0;var listed:CountDownLatch?=null;var proceed:CountDownLatch?=null
  override fun listCommits(cursor:String?):CommitPage {listed?.countDown();proceed?.let {assertTrue(it.await(5,TimeUnit.SECONDS))};return CommitPage(commits.keys.toList())}
  override fun readCommit(id:String)=commits[id]?.copyOf()
  override fun readObject(id:String):ByteArray? {objectReads++;return objects[id]?.copyOf()}
  override fun writeCommit(id:String,bytes:ByteArray) {writes++;commits[id]=bytes.copyOf()}
  override fun writeObject(id:String,bytes:ByteArray) {writes++;objects[id]=bytes.copyOf()}
  override fun legacyPresent()=false
 }
 private data class Rig(val root:Path,val facade:AndroidSyncFacade)
 private fun rig(bridge:Bridge,store:Store,root:Path=temp.resolve("workspace-${sequence++}"))=Rig(root,AndroidSyncFacade(root,connection,bridge,identity,store))
 private fun mirror(root:Path)=Files.walk(root.resolve("connections")).use {s->s.filter {it.fileName.toString()=="mirror"}.findFirst().orElseThrow()}
 private fun content(root:Path)=Files.walk(root).use {s->s.filter(Files::isRegularFile).toList().associate {root.relativize(it).toString() to hash(it.readBytes())}}
 private fun publish(store:Store)=rig(Bridge(),store).also {assertEquals("UPLOADED",it.facade.autoUpload().status)}
 @Test fun supportedHomeUsesInventoryWithoutExportMirrorCaptureOrObjectAuthority() {
  val store=Store();publish(store);val bridge=Bridge();val r=rig(bridge,store);val before=content(mirror(r.root));val objects=store.objectReads;val writes=store.writes
  val home=r.facade.inspectHome();val history=r.facade.historyPresentation(home)
  assertEquals(1,bridge.reads);assertEquals(0,bridge.exports);assertEquals(before,content(mirror(r.root)))
  assertEquals(objects,store.objectReads);assertEquals(writes,store.writes);assertFalse(home.isObjectVerified);assertFalse(home.canAutoUpload)
  assertEquals(LocalObservationSource.BRIDGE_INVENTORY,home.comparison?.localSource);assertNull(home.comparison?.fingerprint)
  assertEquals(3,history.summary?.files);assertEquals(23,history.summary?.plays);assertEquals("2023-11-14T22:13:40Z",history.summary?.slots?.get("game1"))
  assertEquals(bridge.content.mapValues {hash(it.value)},history.currentFiles)
 }
 @Test fun networkPhaseWritesNothingAndWriterCanRunWhileItWaits() {
  val store=Store();publish(store);val bridge=Bridge();val r=rig(bridge,store)
  val context=assertNotNull(r.facade.captureHomeReadContext());val before=content(r.root)
  store.listed=CountDownLatch(1);store.proceed=CountDownLatch(1)
  val pool=Executors.newSingleThreadExecutor()
  try {
   val read=pool.submit<AndroidSyncFacade.HomeReadResult> {r.facade.readHomeRemote(context)}
   assertTrue(store.listed!!.await(5,TimeUnit.SECONDS));assertEquals(before,content(r.root))
   // A second facade's ordinary operation remains serialized, but does not wait for network read.
   rig(bridge,store,r.root).facade.archivePending()
   store.proceed!!.countDown();val remote=read.get(5,TimeUnit.SECONDS)
   val afterWriter=content(r.root);assertNull(r.facade.acceptHomeObservation(remote));assertEquals(afterWriter,content(r.root))
  } finally {store.proceed!!.countDown();context.close();pool.shutdownNow()}
 }
 @Test fun cancellationAndReplayedAcceptanceNeverMergeEvidence() {
  val store=Store();publish(store);val r=rig(Bridge(),store)
  val context=assertNotNull(r.facade.captureHomeReadContext());val result=r.facade.readHomeRemote(context);val before=content(r.root)
  context.close();assertFailsWith<CancellationException>{r.facade.acceptHomeObservation(result)};assertEquals(before,content(r.root))
  val again=assertNotNull(r.facade.captureHomeReadContext());val remote=r.facade.readHomeRemote(again)
  assertNotNull(r.facade.acceptHomeObservation(remote));assertNull(r.facade.acceptHomeObservation(remote));again.close()
 }
 @Test fun actualActionMaterializesFreshBytesAndChangedGameCannotUseOldMirror() {
  val store=Store();val bridge=Bridge();val r=rig(bridge,store)
  val home=r.facade.inspectHome();assertEquals(0,bridge.exports);bridge.content=files(2).toMutableMap()
  assertFailsWith<IllegalArgumentException>{r.facade.upload(home)}
  assertEquals(1,bridge.exports);assertEquals(0,store.writes);assertTrue(content(mirror(r.root)).isEmpty())
 }
 @Test fun matchingActualActionCanUploadButOldObservationCannotBeReplayed() {
  val store=Store();val bridge=Bridge();val r=rig(bridge,store);val home=r.facade.inspectHome()
  assertFalse(r.facade.upload(home).isPending);assertTrue(store.writes>0)
  assertFailsWith<IllegalArgumentException>{r.facade.upload(home)}
 }
 @Test fun targetOrMirrorChangesInvalidateWithoutPublishing() {
  for(target in listOf(false,true)) {
   val store=Store();val bridge=Bridge();val r=rig(bridge,store);val context=assertNotNull(r.facade.captureHomeReadContext());val remote=r.facade.readHomeRemote(context)
   if(target){bridge.targetChange=true;assertFailsWith<AndroidTargetChangedException>{r.facade.acceptHomeObservation(remote)}}
   else {val p=mirror(r.root).resolve("rankings.dat");p.writeBytes("{}".encodeToByteArray());assertNull(r.facade.acceptHomeObservation(remote))}
   context.close()
  }
 }
 @Test fun unsupportedBackendKeepsSerializedExportFallback() {
  val store=Store();val bridge=Bridge().also {it.supported=false};val r=rig(bridge,store)
  val home=r.facade.inspectHome();assertEquals(1,bridge.reads);assertEquals(1,bridge.exports)
  assertEquals(LocalObservationSource.FILESYSTEM,home.comparison?.localSource);assertNotNull(r.facade.historyPresentation(home))
 }
 @Test fun inventoryDirtyAndHistoricalRestoreMaterializesOnlyOneFullExport() {
  for(historical in listOf(false,true)) {
   val store=Store();val bridge=Bridge();val r=rig(bridge,store)
   assertEquals("UPLOADED",r.facade.autoUpload().status)
   val first=json.decodeFromString<AppendCommit>(store.commits.values.last().decodeToString())
   bridge.content=files(2).toMutableMap();assertEquals("UPLOADED",r.facade.autoUpload().status)
   val tip=json.decodeFromString<AppendCommit>(store.commits.values.last().decodeToString())
   bridge.content=files(3).toMutableMap();val home=r.facade.inspectHome();val exports=bridge.exports
   val selected=if(historical)first else tip
   val result=r.facade.restoreSelected(home,selected.snapshot.id,true)
   assertEquals(exports+1,bridge.exports,"inventory restore repeated full export")
   assertEquals(selected.snapshot.files,bridge.content.mapValues {hash(it.value)})
   assertNotNull(result.refreshedInspection);assertFalse(r.facade.hasUnresolvedRestore())
  }
 }
 @Test fun cancelledHomeRetainsBoundOneUseContinuityWithoutPersistingSidecar() {
  val store=Store();val bridge=Bridge(mapOf("game1/game.dat" to controlledRun(),"game1/depth1.dat" to byteArrayOf(2)))
  val r=rig(bridge,store);assertEquals("UPLOADED",r.facade.autoUpload().status)
  val original=json.decodeFromString<AppendCommit>(store.commits.values.last().decodeToString()).snapshot.directoryMetadata!!.runs.getValue("game1").runUUID
  val token=r.facade.beginControlledSession();r.facade.observeControlledSession(token,true,true)
  bridge.content["game1/game.dat"]=controlledRun(3.0)
  val sidecarBefore=DirectoryMetadataStore(mirror(r.root)).read()
  val cancelled=assertNotNull(r.facade.captureHomeReadContext());cancelled.close()
  assertEquals(sidecarBefore,DirectoryMetadataStore(mirror(r.root)).read())
  val second=rig(bridge,store,r.root);val home=second.facade.inspectHome()
  assertEquals(sidecarBefore,DirectoryMetadataStore(mirror(r.root)).read())
  second.facade.upload(home)
  val current=json.decodeFromString<AppendCommit>(store.commits.values.last().decodeToString()).snapshot.directoryMetadata!!.runs.getValue("game1").runUUID
  assertNotNull(original);assertEquals(original,current)
 }
 @Test fun unsupportedInventoryDoesNotConsumeControlledFallbackWindow() {
  val store=Store();val bridge=Bridge(mapOf("game1/game.dat" to controlledRun(),"game1/depth1.dat" to byteArrayOf(2))).also {it.supported=false}
  val r=rig(bridge,store);assertEquals("UPLOADED",r.facade.autoUpload().status)
  val original=json.decodeFromString<AppendCommit>(store.commits.values.last().decodeToString()).snapshot.directoryMetadata!!.runs.getValue("game1").runUUID
  val token=r.facade.beginControlledSession();r.facade.observeControlledSession(token,true,true);bridge.content["game1/game.dat"]=controlledRun(3.0)
  r.facade.inspectHome()
  val current=(DirectoryMetadataStore(mirror(r.root)).read() as SidecarRead.Valid).value.runs.getValue("game1").runUUID
  assertNotNull(original);assertEquals(original,current)
 }
 @Test fun summaryRejectsIncompleteSlotAndNeverUsesPayloadAsFullInventory() {
  assertFailsWith<IllegalArgumentException>{SaveSummary.fromObservation(setOf("game1/depth1.dat"),emptyMap()){0}}
  val bytes=files();val summary=SaveSummary.fromObservation(bytes.keys,bytes.filterKeys {it!="game1/depth1.dat"}) {if(it.endsWith("depth1.dat"))20 else 10}
  assertEquals(3,summary.files);assertEquals("1970-01-01T00:00:00.020Z",summary.slots["game1"])
 }
}
