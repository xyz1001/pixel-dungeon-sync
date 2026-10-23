package pdsync

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.*
import kotlin.io.path.*
import kotlin.test.*

/** Newly reconstructed coverage for APK-evidenced 90–92 behavior; these are not recovered original tests. */
class AndroidHomeReconstruction92Test {
 @TempDir lateinit var temp:Path
 private var sequence=0
 private val identity=AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon","Synthetic Shattered","a".repeat(64),AndroidSaveCapabilities(802,896,AndroidMergePolicy.SHATTERED_896))
 private val connection=AndroidConnection("https://dav.example.test/synthetic/","synthetic","test-only","hero")
 private fun gzip(text:String)=ByteArrayOutputStream().also {out->GZIPOutputStream(out).use {it.write(text.encodeToByteArray())}}.toByteArray()
 private fun files(turn:Int=1)=linkedMapOf("game1/game.dat" to gzip("""{"version":896,"init_ver":896,"turn":$turn}"""),"game1/depth1.dat" to gzip("""{"version":896,"depth":1,"turn":$turn}"""),"rankings.dat" to gzip("""{"total":23}"""))
 private inner class Bridge(initial:Map<String,ByteArray> = files()):AndroidGameBridge {
  var content=initial.toMutableMap();var exports=0;var restores=0;var targets=0;var identityScopes=0;var closedScopes=0
  var lastExport:Path?=null;var onTarget:((Int)->Unit)?=null;var afterExport:((Path)->Unit)?=null
  var directRestore=false;var wrongReceiptBytes=false;var afterRestore:(()->Unit)?=null
  val target=AndroidGameTarget(identity,"synthetic-896",896,"b".repeat(64),"c".repeat(64))
  override fun targetGame():AndroidGameTarget {targets++;onTarget?.invoke(targets);return target}
  override fun exportTo(emptyDestination:Path) {
   exports++;lastExport=emptyDestination
   content.forEach {(name,bytes)->val path=emptyDestination.resolve(name);path.parent.createDirectories();path.writeBytes(bytes);Files.setLastModifiedTime(path,FileTime.fromMillis(1_700_000_000_000L+exports*1_000))}
   afterExport?.invoke(emptyDestination)
  }
  override fun restoreFrom(preparedDirectory:Path,expectedCurrentFiles:Map<String,String>) {
   restores++;require(content.mapValues {hash(it.value)}==expectedCurrentFiles)
   content=Files.walk(preparedDirectory).use {paths->paths.filter(Files::isRegularFile).toList().associate {preparedDirectory.relativize(it).toString().replace('\\','/') to it.readBytes()}}.toMutableMap()
  }
  override fun beginHomeReadIdentityScope():AutoCloseable {identityScopes++;return AutoCloseable {closedScopes++}}
  override fun restoreVerified(snapshot:Snapshot,expectedCurrentFiles:Map<String,String>):AndroidRestoreReceipt? {
   if(!directRestore)return null
   restores++;require(content.mapValues {hash(it.value)}==expectedCurrentFiles)
   content=Snapshots.verify(snapshot,AndroidGameAdapter(identity)).toMutableMap();afterRestore?.invoke()
   return AndroidRestoreReceipt(target,snapshot.manifest.files,content.values.sumOf {it.size.toLong()}+if(wrongReceiptBytes)1 else 0)
  }
 }
 private class Store:AppendStore {
  val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>()
  val objectReads=AtomicInteger();val commitReads=AtomicInteger();var writes=0;var scopes=0;var closedScopes=0;var scopeDepth=0;var outerScopes=0;val scopePaths=mutableListOf<Path?>()
  val objectReadIds=mutableListOf<String>()
  var afterCommit:((AppendCommit)->Unit)?=null
  override fun listCommits(cursor:String?)=CommitPage(commits.keys.toList())
  override fun readCommit(id:String):ByteArray? {commitReads.incrementAndGet();return commits[id]?.copyOf()}
  override fun readObject(id:String):ByteArray? {objectReads.incrementAndGet();objectReadIds+=id;return objects[id]?.copyOf()}
  override fun writeCommit(id:String,bytes:ByteArray) {writes++;commits[id]=bytes.copyOf();afterCommit?.invoke(json.decodeFromString(bytes.decodeToString()))}
  override fun writeObject(id:String,bytes:ByteArray) {writes++;objects[id]=bytes.copyOf()}
  override fun legacyPresent()=false
  override fun <T> withReadScope(cacheDirectory:Path?,block:()->T):T {scopes++;if(scopeDepth++==0){outerScopes++;scopePaths+=cacheDirectory};try{return block()}finally{scopeDepth--;closedScopes++}}
  fun latest()=json.decodeFromString<AppendCommit>(commits.values.last().decodeToString())
  fun child(parent:AppendCommit,parents:List<String> = listOf(parent.id)):AppendCommit {
   val (child,zip)=changedHashFixture(parent,objects.getValue(parent.snapshot.id),parents,commits.size)
   objects[child.snapshot.id]=zip
   return child.also {commits[it.id]=json.encodeToString(it).encodeToByteArray()}
  }
 }
 private data class Rig(val root:Path,val facade:AndroidSyncFacade)
 private fun rig(bridge:Bridge,store:Store)=temp.resolve("workspace-${sequence++}").let {Rig(it,AndroidSyncFacade(it,connection,bridge,identity,store,0))}
 private fun publish(store:Store,bridge:Bridge=Bridge()):Rig=rig(bridge,store).also {assertEquals("UPLOADED",it.facade.autoUpload().status)}
 private fun mirror(root:Path)=Files.walk(root.resolve("connections")).use {paths->paths.filter {it.fileName.toString()=="mirror" && Files.isDirectory(it)}.findFirst().orElseThrow()}
 private fun treeFingerprint(root:Path)=Files.walk(root).use {paths->paths.toList().associate {path->val a=Files.readAttributes(path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS);root.relativize(path).toString() to "${a.fileKey()}:${a.lastModifiedTime()}:${a.size()}:${if(a.isRegularFile)hash(path.readBytes()) else "directory"}"}}
 private fun assertExportsClean(root:Path) {assertFalse(Files.walk(root).use {paths->paths.anyMatch {it.fileName.toString().startsWith("export-")}})}

 @Test fun selectedTipRestoreReadsOnlyChosenObjectAndUsesFinalReceiptWithoutAnotherExport() {
  val store=Store();publish(store);val first=store.latest();val second=store.child(first);val chosen=store.child(second)
  val bridge=Bridge(emptyMap()).also {it.directRestore=true};val rig=rig(bridge,store)
  val home=rig.facade.inspectHome();store.objectReadIds.clear();val exports=bridge.exports;val writes=store.writes
  val result=rig.facade.restoreSelected(home,chosen.snapshot.id,false)
  assertEquals(listOf(chosen.snapshot.id),store.objectReadIds)
  assertEquals(exports+1,bridge.exports);assertEquals(1,bridge.restores);assertEquals(writes,store.writes)
  assertTrue(result.configurationComplete);val refreshed=assertNotNull(result.refreshedInspection)
  assertFalse(refreshed.isObjectVerified);assertEquals(chosen.id,refreshed.comparison?.baselineId)
  assertTrue(rig.facade.historyPresentation(refreshed).currentProtected)
  assertEquals(exports+1,bridge.exports)
  assertFalse(rig.facade.hasUnresolvedRestore())
 }

 @Test fun selectedTipRestoreRejectsBadReceiptAndRetainsRecovery() {
  val store=Store();publish(store);val bridge=Bridge(emptyMap()).also {it.directRestore=true;it.wrongReceiptBytes=true};val rig=rig(bridge,store)
  val home=rig.facade.inspectHome()
  assertFailsWith<IllegalArgumentException> {rig.facade.restoreSelected(home,store.latest().snapshot.id,false)}
  assertTrue(rig.facade.hasUnresolvedRestore())
 }

 @Test fun selectedTipRestoreDoesNotUseMetadataAsObjectProof() {
  val store=Store();publish(store);val chosen=store.latest()
  val bridge=Bridge(emptyMap()).also {it.directRestore=true};val rig=rig(bridge,store);val home=rig.facade.inspectHome()
  store.objects[chosen.snapshot.id]=byteArrayOf(1,2,3)
  assertFailsWith<IllegalArgumentException> {rig.facade.restoreSelected(home,chosen.snapshot.id,false)}
  assertEquals(0,bridge.restores);assertFalse(rig.facade.hasUnresolvedRestore())
 }

 @Test fun historicalRestoreReusesOnlySelectedArchiveAndPublishesAfterGameReceipt() {
  val store=Store();val remote=Bridge();val published=publish(store,remote);val old=store.latest()
  remote.content=files(2);assertEquals("UPLOADED",published.facade.autoUpload().status)
  remote.content=files(3);assertEquals("UPLOADED",published.facade.autoUpload().status)
  val bridge=Bridge(emptyMap()).also {it.directRestore=true};val rig=rig(bridge,store)
  rig.facade.restoreSelected(rig.facade.inspectHome(),store.latest().snapshot.id,false)
  val home=rig.facade.inspectHome();store.objectReadIds.clear();val exports=bridge.exports;val restores=bridge.restores
  var publishedAfterReceipt=false
  store.afterCommit={publishedAfterReceipt=bridge.restores==restores+1}
  val result=rig.facade.restoreSelected(home,old.snapshot.id,false)
  assertEquals(1,store.objectReadIds.count {it==old.snapshot.id})
  assertEquals(1,store.objectReadIds.filter {it in listOf(old.snapshot.id)}.size)
  assertEquals(exports+1,bridge.exports);assertFalse(publishedAfterReceipt)
  assertEquals(3,store.commits.size);assertEquals(old.id,result.refreshedInspection?.comparison?.baselineId);assertFalse(result.configurationComplete)
  assertNotNull(result.refreshedInspection);assertFalse(result.isPending)
  assertEquals(old.snapshot.files,bridge.content.mapValues {hash(it.value)})
 }

 @Test fun dirtySoleTipKeepsIndependentLocalBackupWithoutPublishing() {
  val store=Store();publish(store);val chosen=store.latest()
  val bridge=Bridge(files(9)).also {it.directRestore=true};val rig=rig(bridge,store)
  val before=bridge.content.mapValues {hash(it.value)};val home=rig.facade.inspectHome();store.objectReadIds.clear();val writes=store.writes
  val result=rig.facade.restoreSelected(home,chosen.snapshot.id,true)
  assertEquals(1,store.objectReadIds.count {it==chosen.snapshot.id})
  assertEquals(1,bridge.restores);assertTrue(result.configurationComplete)
  assertEquals(writes,store.writes);assertEquals(chosen,store.latest())
  val backups=Files.walk(rig.root).use {paths->paths.filter {it.parent?.fileName.toString()=="backups"&&it.fileName.toString().endsWith(".json")}.toList()}
  assertTrue(backups.isEmpty())
  assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)})
 }

 @Test fun lateBranchDuringDirtyPreservationRemainsSeparateAndReportsConflictAfterRestore() {
  val store=Store();publish(store);val chosen=store.latest();store.child(chosen,emptyList())
  val bridge=Bridge(files(9)).also {it.directRestore=true};val rig=rig(bridge,store)
  val before=bridge.content.mapValues {hash(it.value)};val home=rig.facade.inspectHome()
  var late:AppendCommit?=null
  bridge.afterRestore={bridge.afterRestore=null;late=store.child(chosen)}
  val result=rig.facade.restoreSelected(home,chosen.snapshot.id,true)
  assertTrue(result.isConflict);assertFalse(result.configurationComplete)
  assertEquals(1,bridge.restores);assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)})
  assertFalse(store.commits.values.map {json.decodeFromString<AppendCommit>(it.decodeToString())}.any {it.snapshot.files==before})
  assertFalse(requireNotNull(late).id in store.latest().parents)
  assertEquals(2,result.refreshedInspection?.comparison?.observedTips?.size)
  assertFalse(rig.facade.hasUnresolvedRestore())
 }

 @Test fun homeReadsMetadataWithoutObjectAuthorityOrTransferAndReusesOneExport() {
  val store=Store();publish(store);val bridge=Bridge();val rig=rig(bridge,store);val reads=store.objectReads.get();val writes=store.writes
  val home=rig.facade.inspectHome()
  assertEquals(1,bridge.exports);assertEquals(reads,store.objectReads.get());assertEquals(writes,store.writes)
  assertFalse(home.isObjectVerified);assertFalse(home.canAutoUpload);assertFalse(home.canCompleteWithoutTransfer);assertFalse(home.canKeepLocalProgress)
  assertEquals(1,home.remoteVersions.size);assertEquals(3,home.localFileCount)
  val history=rig.facade.historyPresentation(home)
  assertEquals(23,history.summary?.plays);assertEquals(setOf("game1"),history.summary?.slots?.keys)
  assertFalse(history.currentProtected);assertEquals(reads,store.objectReads.get());assertEquals(1,bridge.exports)
  assertEquals(bridge.content.mapValues {hash(it.value)},history.currentFiles)
  assertExportsClean(rig.root)
 }

 @Test fun sameContentMetadataUploadVerifiesAndAssociatesWithoutWriting() {
  val store=Store();publish(store);val bridge=Bridge();val rig=rig(bridge,store);val home=rig.facade.inspectHome();val writes=store.writes;val commits=store.commits.keys.toSet();val reads=store.objectReads.get()
  val result=rig.facade.upload(home)
  assertFalse(result.isConflict);assertFalse(result.isPending);assertEquals(store.latest().snapshot.id,result.snapshotId)
  assertEquals(writes,store.writes);assertEquals(commits,store.commits.keys);assertTrue(store.objectReads.get()>reads)
  val verified=rig.facade.inspect();assertTrue(verified.isObjectVerified);assertEquals(store.latest().id,verified.comparison?.baselineId)
  assertTrue(verified.canCompleteWithoutTransfer);assertFalse(verified.canAutoUpload)
 }

 @Test fun differentMetadataContentWaitsForConfirmationWithoutPublishingUnrelatedBranch() {
  val store=Store();publish(store);val oldTip=store.latest();val bridge=Bridge(files(2));val rig=rig(bridge,store);val home=rig.facade.inspectHome();val writes=store.writes
  val result=rig.facade.upload(home)
  assertEquals(writes,store.writes);assertTrue(result.isConflict)
  assertEquals(setOf(oldTip.id),store.commits.keys);assertEquals(setOf(oldTip.snapshot.id),store.objects.keys)
  assertNull(rig.facade.historyPresentation(rig.facade.inspectHome()).baseline)
 }

 @Test fun changedRemoteGraphRejectsMetadataActionBeforeWrites() {
  val store=Store();publish(store);val bridge=Bridge();val rig=rig(bridge,store);val home=rig.facade.inspectHome();store.child(store.latest());val writes=store.writes
  assertFailsWith<IllegalArgumentException> {rig.facade.upload(home)}
  assertEquals(writes,store.writes);assertEquals(0,bridge.restores)
 }

 @Test fun anotherFacadeAndChangedActualOrMirrorCannotReuseMetadataObservation() {
  val store=Store();publish(store);val bridge=Bridge();val rig=rig(bridge,store);val home=rig.facade.inspectHome();val writes=store.writes
  assertFailsWith<IllegalArgumentException> {rig(Bridge(),store).facade.upload(home)}
  bridge.content=files(3)
  assertFailsWith<IllegalArgumentException> {rig.facade.upload(home)}
  bridge.content=files();mirror(rig.root).resolve("game1/game.dat").writeBytes(files(4).getValue("game1/game.dat"))
  assertFailsWith<IllegalArgumentException> {rig.facade.upload(home)}
  assertEquals(writes,store.writes);assertEquals(0,bridge.restores)
 }

 @Test fun explicitVerificationUpgradesFreshObjectsAndEnablesVerifiedHistory() {
  val store=Store();publish(store);val bridge=Bridge();val rig=rig(bridge,store);val home=rig.facade.inspectHome();val reads=store.objectReads.get()
  val verified=rig.facade.verifyHomeInspection(home)
  assertTrue(verified.isObjectVerified);assertTrue(store.objectReads.get()>reads);assertTrue(rig.facade.historyPresentation(verified).currentProtected)
  assertFalse(home.isObjectVerified)
 }

 @Test fun repeatedHomePreservesMirrorIdentityAndOriginalTimes() {
  val store=Store();val bridge=Bridge();val rig=rig(bridge,store);val first=rig.facade.inspectHome();val root=mirror(rig.root);val before=treeFingerprint(root);val summary=rig.facade.historyPresentation(first).summary
  assertEquals("2023-11-14T22:13:21Z",summary?.slots?.get("game1"))
  val second=rig.facade.inspectHome()
  assertEquals(2,bridge.exports);assertEquals(before,treeFingerprint(root));assertEquals(summary,rig.facade.historyPresentation(second).summary)
  bridge.content=files(2);val changed=rig.facade.inspectHome()
  assertEquals("2023-11-14T22:13:23Z",rig.facade.historyPresentation(changed).summary?.slots?.get("game1"))
  assertEquals(bridge.content.mapValues {hash(it.value)},Snapshots.fileHashes(root,AndroidGameAdapter(identity)))
 }

 @Test fun mutationBetweenVerifiedCaptureAndMirrorUseIsRejectedWithoutMirrorReplacement() {
  val store=Store();val bridge=Bridge();val rig=rig(bridge,store);rig.facade.inspectHome();val root=mirror(rig.root);val before=treeFingerprint(root)
  bridge.content=files(2)
  bridge.afterExport={exported->
   var targetChecks=0
   bridge.onTarget={if(++targetChecks==2)exported.resolve("game1/game.dat").writeBytes(files(3).getValue("game1/game.dat"))}
  }
  val failure=assertFailsWith<IllegalArgumentException> {rig.facade.inspectHome()}
  assertEquals(SyncBlockReason.SAVE_NOT_STABLE,(failure as ReasonedSyncFailure).syncBlockReason)
  assertEquals(before,treeFingerprint(root));assertExportsClean(rig.root);assertEquals(0,store.writes)
 }

 @Test fun invalidFormatAndUnrecognizedExportPathsNeverReplaceMirror() {
  for(invalid in listOf("format","path","symlink")) {
   val store=Store();val bridge=Bridge();val rig=rig(bridge,store);rig.facade.inspectHome();val root=mirror(rig.root);val before=treeFingerprint(root)
   bridge.afterExport={exported->when(invalid) {
    "format"->exported.resolve("game1/game.dat").writeText("not JSON")
    "path"->exported.resolve("settings.xml").writeText("forbidden")
    else->Files.createSymbolicLink(exported.resolve("game1/link"),exported.resolve("game1/game.dat"))
   }}
   assertFailsWith<IllegalArgumentException> {rig.facade.inspectHome()}
   assertEquals(before,treeFingerprint(root));assertExportsClean(rig.root)
  }
 }

 @Test fun homeTimesOneVerifiedLocalPassAndPreservesHelperStableWait() {
  val store=Store();val bridge=Bridge();val rig=rig(bridge,store)
  bridge.afterExport={Thread.sleep(2_000)} // Synthetic representation of the retained installed-helper wait, not device timing.
  val trace=DiagnosticLog.begin(DiagnosticAction.INSPECT,false)
  val home=DiagnosticLog.bind(trace).use {rig.facade.inspectHome()}
  assertEquals(3,home.localFileCount);assertEquals(1,bridge.exports)
  val phases=trace.timings.snapshot()
  for(phase in listOf(DiagnosticPhase.LOCAL_EXPORT_VERIFY,DiagnosticPhase.MIRROR_COPY,DiagnosticPhase.LOCAL_SUMMARY,DiagnosticPhase.COMPARISON))assertEquals(1,phases[phase]?.count,"$phase count")
  val exports=requireNotNull(phases[DiagnosticPhase.SAVE_EXPORT])
  assertEquals(2,exports.count);assertEquals(1,exports.skipped) // unsupported inventory probe, then one real export
  assertTrue(exports.durationMs>=1_900)
  assertEquals(2,phases[DiagnosticPhase.OPERATION_LOCK_WAIT]?.count) // short inventory attempt plus serialized fallback
 }

 @Test fun facadeDelegatesIdentityScopeAndRestoreDiskReadScopeWithFailureCleanup() {
  val store=Store();publish(store);val bridge=Bridge(emptyMap());val rig=rig(bridge,store)
  rig.facade.beginHomeReadIdentityScope().use {assertEquals(1,bridge.identityScopes);assertEquals(0,bridge.closedScopes)}
  assertEquals(1,bridge.closedScopes)
  val inspected=rig.facade.inspect();val before=store.outerScopes
  rig.facade.restoreSelectedReadOnly(inspected,store.latest().snapshot.id,false)
  assertEquals(before+1,store.outerScopes);assertEquals(store.scopes,store.closedScopes);assertEquals(1,bridge.restores)
  assertEquals("read-cache",store.scopePaths.last()?.fileName.toString())
  assertEquals(7,rig.facade.withRestoreReadScope(Callable {7}))
  assertFailsWith<IllegalStateException> {rig.facade.withRestoreReadScope(Callable<Int> {error("synthetic")})}
  assertEquals(store.scopes,store.closedScopes)
 }
}

/** New history fixtures use real changed bytes; schema3 cannot encode a duplicate-content child. */
internal fun changedHashFixture(parent:AppendCommit, archive:ByteArray, parents:List<String>, salt:Int):Pair<AppendCommit,ByteArray> {
 val adapter=object:GameAdapter by Shattered {
  override val id=parent.snapshot.game
  override fun acceptsManifest(manifest:Manifest)=manifest.game==id
 }
 val files=Snapshots.verify(Snapshot(parent.snapshot,archive),adapter).toMutableMap()
 val path=files.keys.first {it.endsWith("/depth1.dat")}
 files[path]=files.getValue(path)+salt.toString().encodeToByteArray()
 val fresh=Snapshots.fromFiles(files,adapter,parent.snapshot.profile,parent.snapshot.version)
 val id=contentSha256(fresh.manifest.files)
 val manifest=parent.snapshot.copy(id=id,files=fresh.manifest.files,sha256=fresh.manifest.sha256,directoryMetadata=null)
 return parent.copy(schema=3,id=id,parents=parents,snapshot=manifest,objectBytes=fresh.zip.size) to fresh.zip
}
