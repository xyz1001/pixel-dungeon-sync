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
 private val identity=AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon","Synthetic Shattered","a".repeat(64),AndroidSaveCapabilities(802,896))
 private val connection=AndroidConnection("https://dav.example.test/synthetic/","synthetic","test-only","hero")
 private fun files(turn:Int=1)=linkedMapOf("game1/game.dat" to """{"version":896,"init_ver":896,"turn":$turn}""".encodeToByteArray(),"game1/depth1.dat" to byteArrayOf(turn.toByte()))
 private inner class Bridge(initial:Map<String,ByteArray> = files()):AndroidGameBridge {
  var content=initial.toMutableMap();var exports=0;var restores=0;var afterExport:(()->Unit)?=null;var afterRestore:(()->Unit)?=null
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
 private class Store:AppendStore {
  val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>()
  val deletions=linkedMapOf<String,ByteArray>();var failDelete=false;var failDeleteAfter=Int.MAX_VALUE;var failObjectDelete=false;var failWrites=false;val removed=mutableListOf<String>()
  override fun readDeletion(id:String)=deletions[id]?.copyOf()
  override fun writeDeletion(id:String,bytes:ByteArray){deletions.putIfAbsent(id,bytes.copyOf())}
  override fun deleteCommit(id:String){if(failDelete || removed.size>=failDeleteAfter)throw java.io.IOException("synthetic delete failure");commits.remove(id);removed+=id}
  override fun deleteObject(id:String){if(failObjectDelete)throw java.io.IOException("synthetic object delete failure");objects.remove(id)}
  var commitReads=0;var beforeObject:(()->Unit)?=null;var listings=0;var writes=0;var objectReads=0;var failCommitReads=false;var afterReadObject:(()->Unit)?=null;var afterCommit:(()->Unit)?=null
  override fun listCommits(cursor:String?):CommitPage {listings++;return CommitPage(commits.keys.toList())}
  override fun readCommit(id:String):ByteArray? {commitReads++;if(failCommitReads)throw java.io.IOException("synthetic unavailable readback");return commits[id]?.copyOf()}
  override fun readObject(id:String):ByteArray? {
   objectReads++;val bytes=objects[id]?.copyOf();afterReadObject?.also {afterReadObject=null}?.invoke();return bytes
  }
  override fun writeObject(id:String,bytes:ByteArray) {beforeObject?.invoke();if(failWrites)throw java.io.IOException("synthetic write failure");writes++;objects[id]=bytes.copyOf()}
  override fun writeCommit(id:String,bytes:ByteArray) {if(failWrites)throw java.io.IOException("synthetic write failure");writes++;commits[id]=bytes.copyOf();afterCommit?.also {afterCommit=null}?.invoke()}
  override fun legacyPresent():Boolean=error("obsolete head probe must never run")
  fun latest()=json.decodeFromString<AppendCommit>(commits.values.last().decodeToString())
  fun child(parent:AppendCommit,parents:List<String> = listOf(parent.id)):AppendCommit {
   val (child,zip)=changedHashFixture(parent,objects.getValue(parent.snapshot.id),parents,commits.size)
   objects[child.snapshot.id]=zip
   return child.also {commits[it.id]=json.encodeToString(it).encodeToByteArray()}
  }
 }
 private fun legacyPublished(store:Store,bridge:Bridge,parent:AppendCommit):AppendCommit {
  val source=temp.resolve("legacy-${sequence++}");source.createDirectories()
  bridge.content.forEach {(name,bytes)->source.resolve(name).also {it.parent.createDirectories();it.writeBytes(bytes)}}
  val snapshot=Snapshots.capture(source,AndroidGameAdapter(identity),connection.profile,"synthetic",null)
  val id=contentSha256(snapshot.manifest.files)
  val commit=parent.copy(id=id,parents=listOf(parent.id),snapshot=snapshot.manifest.copy(id=id),objectBytes=snapshot.zip.size)
  store.objects[id]=snapshot.zip;store.commits[id]=json.encodeToString(commit).encodeToByteArray()
  return commit
 }
 private data class Rig(val root:Path,val facade:AndroidSyncFacade)
 private fun rig(bridge:Bridge,store:Store)=temp.resolve("workspace-${sequence++}").let {Rig(it,AndroidSyncFacade(it,connection,bridge,identity,store,0))}
 private fun publish(store:Store,bridge:Bridge=Bridge())=rig(bridge,store).also {assertEquals("UPLOADED",it.facade.autoUpload().status)}
 private fun mirror(rig:Rig)=Files.walk(rig.root.resolve("connections")).use {paths->paths.filter {it.fileName.toString()=="mirror"}.findFirst().orElseThrow()}
 private fun repository(rig:Rig)=Files.walk(rig.root.resolve("connections")).use {paths->AppendRepository(paths.filter {it.fileName.toString()=="append-v2"}.findFirst().orElseThrow(),AndroidGameAdapter(identity),connection.profile)}
 private fun recoveryFiles(rig:Rig)=Files.walk(rig.root).use {paths->paths.filter {it.fileName.toString() in setOf("apply-required.txt","active-plan.txt") || it.toString().contains("restore-journals")&&it.fileName.toString().endsWith(".json")}.toList()}

 @Test fun establishedMarkerIsNotRewrittenWhenBaselineAdvances() {
  val store=Store();val bridge=Bridge();val r=publish(store,bridge);val repo=repository(r)
  val marker=repo.overwriteDirectory().parent.resolve("baseline-established.txt")
  assertEquals("established",marker.readText())
  val stamp=java.nio.file.attribute.FileTime.fromMillis(1000000000000L)
  Files.setLastModifiedTime(marker,stamp)
  val before=repo.baseline();bridge.content=files(2).toMutableMap()
  assertEquals("UPLOADED",r.facade.autoUpload().status)
  assertNotEquals(before,repo.baseline());assertNull(repo.pending())
  assertEquals(stamp,Files.getLastModifiedTime(marker))
 }
 @Test fun deletionKeepsObjectsReferencedByRetainedPhysicalAliasesAndChecksReplacementReadback() {
  for(broken in listOf(false,true)) {
   val store=Store();publish(store);val base=store.latest();val victim=store.child(base);val replacement=store.child(base)
   val objectId=UUID.randomUUID().toString();val victimId=UUID.randomUUID().toString();val aliasId=UUID.randomUUID().toString()
   val snapshot=victim.snapshot.copy(id=objectId)
   val selected=victim.copy(schema=2,id=victimId,parents=listOf(base.id),snapshot=snapshot)
   val alias=selected.copy(id=aliasId,parents=emptyList())
   val graph=CommitGraph(mapOf(base.id to base,victimId to selected,aliasId to alias,replacement.id to replacement),emptyMap())
   store.commits.remove(victim.id);store.commits[victimId]=json.encodeToString(selected).encodeToByteArray();store.commits[aliasId]=json.encodeToString(alias).encodeToByteArray()
   store.objects[objectId]=store.objects.getValue(victim.snapshot.id)
   val deletion=ConfirmedBranchOverwrite(temp.resolve("delete-${sequence++}"),AndroidGameAdapter(identity),connection.profile)
   val plan=deletion.prepare(store,graph,base.id,victimId,replacement.id)
   assertTrue(plan.objects.isEmpty())
   if(broken) {
    store.objects[replacement.snapshot.id]=byteArrayOf(1)
    assertFails {deletion.finish(store,plan,graph,{})};assertTrue(store.removed.isEmpty());assertNotNull(deletion.pending())
   } else {
    deletion.finish(store,plan,graph,{})
    assertFalse(victimId in store.commits);assertTrue(aliasId in store.commits);assertTrue(objectId in store.objects);assertNull(deletion.pending())
   }
  }
 }

 @Test fun confirmedReplacementNeverCallsDeletionEvenWhenDeleteWouldFail() {
  val store=Store();val bridge=Bridge();val r=publish(store,bridge);val base=store.latest()
  val child=store.child(base);val tail=store.child(child);val old=store.commits.keys.toSet()
  bridge.content=files(9).toMutableMap();store.failDelete=true;store.failObjectDelete=true
  val done=r.facade.overwriteCloudSuccessors(r.facade.inspectHome(),tail.snapshot.id)
  assertFalse(done.isConflict);assertFalse(done.isPending);assertTrue(store.removed.isEmpty())
  assertTrue(store.commits.keys.containsAll(old));assertTrue(store.deletions.isEmpty())
  assertEquals(listOf(tail.id),store.latest().parents);assertEquals(old.size+1,store.commits.size)
 }

 @Test fun changedProgressAfterConfirmationIsRejectedWithoutWrites() {
  val store=Store();val bridge=Bridge();val r=publish(store,bridge);val child=store.child(store.latest())
  bridge.content=files(9).toMutableMap();val confirmed=r.facade.inspectHome();val writes=store.writes
  bridge.content=files(10).toMutableMap()
  assertFails {r.facade.overwriteCloudSuccessors(confirmed,child.snapshot.id)}
  assertEquals(writes,store.writes);assertTrue(store.removed.isEmpty())
 }

 @Test fun failedUploadAndCanceledConfirmationNeverDeleteOldProgress() {
  for(cancel in listOf(false,true)) {
   val store=Store();val bridge=Bridge();val r=publish(store,bridge);val b=store.latest();val c=store.child(b)
   bridge.content=files(9).toMutableMap();val home=r.facade.inspectHome();store.failWrites=!cancel
   if(cancel)Thread.currentThread().interrupt()
   try {assertFails {r.facade.overwriteCloudSuccessors(home,c.snapshot.id)}} finally {Thread.interrupted()}
   assertTrue(c.id in store.commits);assertTrue(c.snapshot.id in store.objects);assertTrue(store.removed.isEmpty())
  }
 }
 @Test fun anotherClientKeepsItsBaselineAfterConfirmedAppend() {
  val store=Store();val bridge=Bridge();val r=publish(store,bridge);val c=store.child(store.latest())
  val other=rig(Bridge(files(7)),store);other.facade.inspectHome();repository(other).setBaseline(c.id,repository(other).discover(store))
  bridge.content=files(9).toMutableMap();r.facade.overwriteCloudSuccessors(r.facade.inspectHome(),c.snapshot.id)
  other.facade.inspectHome();assertEquals(c.id,repository(other).baseline());assertTrue(store.deletions.isEmpty())
 }

 @Test fun confirmedReplacementPreservesAllHistoryAndMovesOnlyLocalBaseline() {
  val store=Store();val bridge=Bridge();val r=publish(store,bridge);val b=store.latest()
  val c=store.child(b);val tail=store.child(c);val old=store.commits.mapValues {hash(it.value)}
  bridge.content=files(9).toMutableMap();val observed=r.facade.inspectHome()
  val result=r.facade.overwriteCloudSuccessors(observed,tail.snapshot.id)
  assertFalse(result.isPending);assertFalse(result.isConflict);assertEquals(0,bridge.restores)
  old.forEach {(id,digest)->assertEquals(digest,hash(store.commits.getValue(id)))}
  val graph=repository(r).discover(store);val latest=store.latest()
  assertEquals(setOf(latest.id),graph.tips);assertEquals(listOf(tail.id),latest.parents)
  assertEquals(latest.id,repository(r).baseline());assertTrue(store.removed.isEmpty())
 }

 @Test fun uploadConflictBeforeConfirmationDoesNotWriteOrMoveBaseline() {
  val store=Store();val bridge=Bridge();val r=publish(store,bridge);val a=store.latest()
  bridge.content=files(2).toMutableMap();r.facade.upload(r.facade.inspectHome());val b=store.latest()
  val c=store.child(b);bridge.content=files(9).toMutableMap()
  val writes=store.writes;val ids=store.commits.keys.toSet()
  repeat(2) {
   val result=r.facade.upload(r.facade.inspectHome())
   assertTrue(result.isConflict)
   assertEquals(writes,store.writes,"upload must ask before any remote write")
   assertEquals(ids,store.commits.keys);assertTrue(store.removed.isEmpty());assertEquals(b.id,repository(r).baseline())
   val refreshed=r.facade.inspectHome()
   assertEquals(c.snapshot.id,refreshed.overwriteTargetSnapshotId)
  }
 }

 @Test fun acceptedUploadWithLateSiblingCanClearPendingWithoutRepeatingWrites() {
  val store=Store();val bridge=Bridge();val r=publish(store,bridge);val b=store.latest()
  bridge.content=files(9).toMutableMap();val observed=r.facade.inspectHome()
  store.afterCommit={store.child(b);store.failCommitReads=true;throw java.io.IOException("synthetic lost response")}
  assertFailsWith<java.io.IOException>{r.facade.upload(observed)}
  assertNotNull(repository(r).pending());val writes=store.writes;val records=store.commits.mapValues {hash(it.value)}
  store.failCommitReads=false
  val reopened=AndroidSyncFacade(r.root,connection,bridge,identity,store,0)
  reopened.upload(reopened.inspectHome())
  assertNull(repository(r).pending());assertEquals(writes,store.writes)
  assertEquals(records,store.commits.mapValues {hash(it.value)});assertTrue(store.removed.isEmpty())
  assertEquals(2,repository(r).discover(store).tips.size)
 }

 @Test fun manualUploadWithoutVerifiedBaselineNeverPublishesBeforeConfirmation() {
  for(associated in listOf(false,true)) {
   val store=Store();publish(store);val bridge=Bridge(if(associated)files() else files(9));val r=rig(bridge,store)
   r.facade.inspectHome();bridge.content=files(9).toMutableMap()
   val writes=store.writes;val records=store.commits.mapValues {hash(it.value)}
   repeat(2) {
    val facade=AndroidSyncFacade(r.root,connection,bridge,identity,store,0)
    val observed=facade.inspectHome();assertNull(repository(r).baseline())
    val result=facade.upload(observed)
    assertTrue(result.isConflict);assertEquals(writes,store.writes)
    assertEquals(records,store.commits.mapValues {hash(it.value)})
    assertNull(repository(r).baseline());assertTrue(store.removed.isEmpty());assertTrue(store.deletions.isEmpty())
   }
  }
 }

 @Test fun historyKeepsPreviouslyObservedLocalAssociationAfterContentChangesAndRestart() {
  val store=Store();publish(store);val b=store.latest();store.child(b)
  val bridge=Bridge();val r=rig(bridge,store)
  val first=r.facade.historyPresentation(r.facade.inspectHome())
  assertNull(first.baseline)
  assertTrue(pdsync.presentation.HistoryPresentation.from(first,0).edges.any {it.fromId=="local-current"&&it.toId==b.id})
  bridge.content=files(9).toMutableMap()
  val reopened=AndroidSyncFacade(r.root,connection,bridge,identity,store,0)
  val history=reopened.historyPresentation(reopened.inspectHome())
  val shown=pdsync.presentation.HistoryPresentation.from(history,0)
  assertTrue(shown.nodes.any {it.id=="local-baseline"},"refresh must retain the observed local history anchor")
  assertTrue(shown.edges.any {it.fromId=="local-baseline"&&it.toId==b.id})
  assertNull(history.baseline,"display association cannot grant upload ancestry")
  val observed=reopened.inspectHome();val writes=store.writes
  assertNull(observed.overwriteTargetSnapshotId)
  assertFails {reopened.overwriteCloudSuccessors(observed,store.latest().snapshot.id)}
  assertEquals(writes,store.writes);assertTrue(store.removed.isEmpty());assertTrue(store.deletions.isEmpty())
 }

 @Test fun legacyDoubleTipKeepsLocalLineageWhenLocalContentChangesAgain() {
  val store=Store();val bridge=Bridge();val r=publish(store,bridge);val a=store.latest()
  bridge.content=files(2).toMutableMap();r.facade.upload(r.facade.inspectHome());val b=store.latest()
  val c=store.child(b);bridge.content=files(9).toMutableMap()
  val d=legacyPublished(store,bridge,b);repository(r).setBaseline(d.id,repository(r).discover(store))
  bridge.content=files(10).toMutableMap();val writes=store.writes
  val preflight=r.facade.upload(r.facade.inspectHome());assertTrue(preflight.isConflict);assertEquals(writes,store.writes)
  val shown=r.facade.historyPresentation(r.facade.inspectHome())
  assertEquals(0,pdsync.presentation.HistoryPresentation.from(shown,0).nodes.count {it.status==pdsync.i18n.CoreMessages.CURRENT_CLOUD_BRANCH.text()})
  val home=r.facade.inspectHome();assertEquals(c.snapshot.id,home.overwriteTargetSnapshotId)
  val done=r.facade.overwriteCloudSuccessors(home,requireNotNull(home.overwriteTargetSnapshotId))
  assertFalse(done.isConflict);assertFalse(done.isPending)
  val graph=repository(r).discover(store);assertTrue(c.id in graph.commits)
  assertTrue(setOf(a.id,b.id,d.id).all {it in graph.commits});assertEquals(1,graph.tips.size)
  val e=graph.commits.getValue(graph.tips.single());assertEquals(setOf(c.id,d.id),e.parents.toSet())
  assertEquals(bridge.content.mapValues {hash(it.value)},e.snapshot.files)
 }

 @Test fun matchingSiblingIsNotOfferedAsItsOwnOverwriteReplacement() {
  val store=Store();val bridge=Bridge();val r=publish(store,bridge);val b=store.latest()
  val c=store.child(b);bridge.content=files(9).toMutableMap()
  val d=legacyPublished(store,bridge,b);repository(r).setBaseline(d.id,repository(r).discover(store))
  bridge.content=Snapshots.verify(Snapshot(c.snapshot,store.objects.getValue(c.snapshot.id)),AndroidGameAdapter(identity)).toMutableMap()
  val observed=r.facade.inspectHome();val writes=store.writes;val ids=store.commits.keys.toSet()
  assertNull(observed.overwriteTargetSnapshotId)
  assertTrue(r.facade.upload(observed).isConflict)
  assertFails {r.facade.overwriteCloudSuccessors(r.facade.inspectHome(),c.snapshot.id)}
  assertEquals(writes,store.writes);assertEquals(ids,store.commits.keys)
  assertEquals(d.id,repository(r).baseline());assertTrue(store.removed.isEmpty());assertTrue(store.deletions.isEmpty())
 }

 @Test fun automaticOverwriteTargetNeverChoosesBetweenIndependentCloudSuccessors() {
  val store=Store();val bridge=Bridge();val r=publish(store,bridge);val b=store.latest()
  val c=store.child(b);store.child(b);bridge.content=files(9).toMutableMap()
  val before=store.writes
  val observed=r.facade.inspectHome();assertNull(observed.overwriteTargetSnapshotId)
  assertTrue(r.facade.upload(observed).isConflict)
  assertFails {r.facade.overwriteCloudSuccessors(r.facade.inspectHome(),c.snapshot.id)}
  assertEquals(before,store.writes);assertTrue(store.removed.isEmpty());assertTrue(store.deletions.isEmpty())
 }

 @Test fun uploadValidatesRemoteTipFormatWithoutDeclaredSaveVersions() {
  for(valid in listOf(false,true)) {
   val store=Store();publish(store);val original=store.latest()
   val content=files(3).toMutableMap()
   if(!valid)content["game1/game.dat"]="unrecognized synthetic save".encodeToByteArray()
   val source=temp.resolve("raw-${sequence++}");source.createDirectories()
   content.forEach {(name,bytes)->source.resolve(name).also {it.parent.createDirectories();it.writeBytes(bytes)}}
   val snapshot=Snapshots.capture(source,AndroidGameAdapter(identity),connection.profile,"synthetic",null)
   val id=contentSha256(snapshot.manifest.files)
   val manifest=original.snapshot.copy(id=id,files=snapshot.manifest.files,sha256=snapshot.manifest.sha256,saveVersions=null,directoryMetadata=null)
   val tip=original.copy(id=id,parents=emptyList(),snapshot=manifest,objectBytes=snapshot.zip.size)
   store.commits.clear();store.objects.clear();store.commits[id]=json.encodeToString(tip).encodeToByteArray();store.objects[id]=snapshot.zip
   Snapshots.verify(Snapshot(manifest,snapshot.zip),AndroidGameAdapter(identity))
   val r=rig(Bridge(files(9)),store);val home=r.facade.inspectHome();val writes=store.writes;val lists=store.listings
   if(valid) {
    val result=r.facade.upload(home);assertFalse(result.isPending);assertTrue(result.isConflict);assertEquals(writes,store.writes)
    assertEquals(lists+1,store.listings)
   } else {
    assertTrue(r.facade.upload(home).isConflict)
    assertEquals(writes,store.writes);assertEquals(lists+1,store.listings);assertNull(repository(r).pending())
   }
  }
 }

 @Test fun explicitSameTipRestoreRemembersBaselineWithoutCloudOrGameWrites() {
  val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge();val r=rig(bridge,store)
  val home=r.facade.inspectHome();val writes=store.writes;val objects=store.objectReads;val lists=store.listings
  val result=r.facade.restoreSelected(home,chosen.snapshot.id,false)
  assertEquals(1,bridge.exports);assertEquals(0,bridge.restores);assertEquals(writes,store.writes)
  assertEquals(objects+1,store.objectReads);assertEquals(lists+2,store.listings)
  assertFalse(result.isConflict);assertFalse(result.isPending);assertTrue(result.configurationComplete)
  assertEquals("IDENTICAL",result.refreshedInspection?.kind);assertEquals(chosen.id,result.refreshedInspection?.comparison?.baselineId)
  assertEquals(chosen.id,repository(r).baseline());assertTrue(recoveryFiles(r).isEmpty())
  bridge.content=files(9).toMutableMap()
  val reopened=AndroidSyncFacade(r.root,connection,bridge,identity,store,0)
  val shown=pdsync.presentation.HistoryPresentation.from(reopened.historyPresentation(reopened.inspectHome()),0)
  assertTrue(shown.nodes.any {it.id=="local-baseline"})
  assertTrue(shown.edges.any {it.fromId=="local-baseline" && it.toId==chosen.id})
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

 @Test fun lateBranchDuringNoOpKeepsExplicitSelectedBaselineAndReportsConflict() {
  val store=Store();publish(store);val chosen=store.latest();val bridge=Bridge();val r=rig(bridge,store)
  val home=r.facade.inspectHome();val writes=store.writes;var late:AppendCommit?=null
  store.afterReadObject={late=store.child(chosen,emptyList())}
  val result=r.facade.restoreSelected(home,chosen.snapshot.id,false)
  assertEquals(writes,store.writes);assertEquals(0,bridge.restores);assertTrue(result.isConflict);assertFalse(result.configurationComplete)
  assertEquals(setOf(chosen.id,requireNotNull(late).id),result.refreshedInspection?.comparison?.observedTips)
  assertEquals(chosen.id,repository(r).baseline());assertTrue(recoveryFiles(r).isEmpty())
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
   assertEquals(18,requests.size)
   assertFalse(requests.any {it.contains("head.json")})
   assertEquals(2,requests.count {it.startsWith("PROPFIND ")});assertEquals(0,requests.count {it.startsWith("PUT ")})
   assertEquals(exports+1,bridge.exports);assertEquals(restores+if(noop)0 else 1,bridge.restores)
   assertEquals(chosen.snapshot.files,bridge.content.mapValues {hash(it.value)});assertFalse(result.isConflict)
   assertEquals(!historical,result.configurationComplete)
  }
 }
 @Test fun largeDeclaredObjectsUseSingleReadBatchesWithoutExceedingRetentionBudget() {
  val store=Store();publish(store);store.child(store.latest())
  store.commits.replaceAll {_,bytes->json.encodeToString(json.decodeFromString<AppendCommit>(bytes.decodeToString()).copy(objectBytes=MAX_SNAPSHOT_BYTES)).encodeToByteArray()}
  val batches=mutableListOf<Int>()
  val transport=object:AppendStore by store {
   override val historyReadParallelism:Int get()=4
   override fun readHistoryObjects(ids:List<String>,cancellation:MetadataReadCancellation,limits:Map<String,Int>):Map<String,ByteArray?> {
    batches+=ids.size;assertEquals(MAX_SNAPSHOT_BYTES,limits.getValue(ids.single()))
    return ids.associateWith {null}
   }
  }
  val graph=AppendRepository(temp.resolve("large-read-budget"),AndroidGameAdapter(identity),connection.profile).discover(transport)
  assertEquals(listOf(1,1),batches);assertEquals(2,graph.pending.size)
 }

 @Test fun appendReadsOnlyTipObjectAndChecksAllAncestorMetadata() {
  val timings=mutableMapOf<String,Long>()
  for(mode in listOf("serial","parallel","missing")) MockWebServer().use { server ->
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
     if(request.method=="GET")Thread.sleep(90)
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
   val dav=AppendOnlyWebDav(endpoint,AndroidGameAdapter(identity).id,connection.profile,connection.username,connection.password)
   val selectedStore=object:AppendStore by dav {override val historyReadParallelism:Int get()=if(mode=="serial")1 else 4}
   val bridge=Bridge(if(historical)emptyMap() else if(noop)Snapshots.verify(Snapshot(tip.snapshot,seed.objects.getValue(tip.snapshot.id)),AndroidGameAdapter(identity)) else files(9));val root=temp.resolve("dav-${sequence++}")
   val facade=AndroidSyncFacade(root,AndroidConnection(endpoint,connection.username,connection.password,connection.profile),bridge,identity,
    selectedStore,0)
   bridge.content=Snapshots.verify(Snapshot(tip.snapshot,seed.objects.getValue(tip.snapshot.id)),AndroidGameAdapter(identity)).toMutableMap()
   facade.restoreSelected(facade.inspectHome(),tip.snapshot.id,false)
   bridge.content=files(9).toMutableMap()
   val home=facade.inspectHome();val exports=bridge.exports;val restores=bridge.restores;requests.clear();val started=System.nanoTime()
   if(mode=="corrupt" || mode=="missing") {
    val path=folder+"pdsync-v3-object-${tip.snapshot.id}.zip"
    Files.walk(root).use {paths->paths.filter {it.fileName.toString()=="${tip.snapshot.id}.zip"}.forEach {Files.delete(it)}}
    if(mode=="corrupt")remote[path]=byteArrayOf(1) else remote.remove(path)
   }
   val result=facade.withUploadReadScope(java.util.concurrent.Callable { facade.upload(home) })
   val oldObjects=seed.objects.keys.map { folder+"pdsync-v3-object-$it.zip" }.toSet()
   oldObjects.forEach { path->assertEquals(0,requests.count {it=="GET $path"},"metadata-only upload never downloads old ZIPs") }
   assertEquals(2,requests.count {it.startsWith("PUT ")},"one object and one conditional commit")
   assertEquals(1,requests.count {it.startsWith("PROPFIND ")},"one preflight, no post-upload scan")
   assertEquals(exports+1,bridge.exports,"one fixed device export")
   assertEquals(1,requests.takeWhile {!it.startsWith("PUT ")}.count {it.startsWith("PROPFIND ")},"exactly one fresh history before upload")
   assertNotNull(result.refreshedInspection,"UI uses completed operation receipt, no full refresh")
   assertEquals(0,bridge.restores)
   timings[mode]=(System.nanoTime()-started)/1_000_000
   println("UPLOAD108_BENCH mode=$mode elapsed_ms=${timings[mode]} http=${requests.size} old_objects=${oldObjects.size} old_object_gets=${requests.count {it.removePrefix("GET ") in oldObjects}}")
  }
  println("UPLOAD108_LATENCY serial_ms=${timings["serial"]} parallel_ms=${timings["parallel"]}")
  // Known immutable commit metadata is now local in both modes; no timing-ratio assertion.
 }

 @Test fun actualWriteFailureAndCancellationRemainFailuresWithoutSuccessfulBaseline() {
  for(cancel in listOf(false,true)) {
   val store=Store();val bridge=Bridge();val r=publish(store,bridge);val baseline=repository(r).baseline()
   bridge.content=files(9).toMutableMap();val home=r.facade.inspectHome();val writes=store.writes
   if(cancel)store.beforeObject={throw java.util.concurrent.CancellationException()} else store.failWrites=true
   assertFails {r.facade.upload(home)}
   assertEquals(writes,store.writes);assertEquals(baseline,repository(r).baseline());assertNotNull(repository(r).pending())
  }
 }

 @Test fun uploadReceiptAvoidsRereadingMirrorAndExpiresAfterOperation() {
  val store=Store();publish(store);val parent=store.latest();val bridge=Bridge();val r=rig(bridge,store)
  r.facade.restoreSelected(r.facade.inspectHome(),parent.snapshot.id,false)
  bridge.content=files(9).toMutableMap();val home=r.facade.inspectHome()
  store.afterCommit={mirror(r).resolve("game1/game.dat").writeBytes(byteArrayOf(1))}
  val result=r.facade.withUploadReadScope(java.util.concurrent.Callable {
   r.facade.upload(home).also {r.facade.historyPresentation(requireNotNull(it.refreshedInspection))}
  })
  assertTrue(result.configurationComplete);assertNull(repository(r).pending())
  assertEquals(files(9).mapValues {hash(it.value)},result.refreshedInspection?.comparison?.localFiles)
  assertFails {r.facade.historyPresentation(requireNotNull(result.refreshedInspection))}
 }
 @Test fun ancestorObjectIsDeferredButExplicitHistoricalReadStillRejectsCorruption() {
  val store=Store();publish(store);val ancestor=store.latest();val tip=store.child(ancestor)
  val bridge=Bridge(Snapshots.verify(Snapshot(tip.snapshot,store.objects.getValue(tip.snapshot.id)),AndroidGameAdapter(identity)))
  val r=rig(bridge,store);r.facade.restoreSelected(r.facade.inspectHome(),tip.snapshot.id,false)
  bridge.content=files(9).toMutableMap();val home=r.facade.inspectHome();val reads=store.objectReads
  store.objects[ancestor.snapshot.id]=byteArrayOf(1)
  val result=r.facade.upload(home)
  assertTrue(result.configurationComplete);assertEquals(reads,store.objectReads)
  assertFails {repository(r).download(store,ancestor)}
 }
 @Test fun baselineArchiveReuseAndDiskFallbackAreOperationLocal() {
  for(mode in listOf("hit","missing")) {
   val store=Store();val bridge=Bridge();val r=publish(store,bridge);val parent=store.latest()
   bridge.content=files(9).toMutableMap();val home=r.facade.inspectHome();val reads=store.objectReads
   val archive=Files.walk(r.root).use {paths->paths.filter {it.fileName.toString()=="${parent.snapshot.id}.zip" && it.parent.fileName.toString()=="history"}.findFirst().orElseThrow()}
   if(mode=="missing")Files.delete(archive)
   if(mode=="hit")store.objects.remove(parent.snapshot.id) // Reuse proves local bytes, not remote availability.
   val trace=DiagnosticTrace("baseline119-$mode",DiagnosticAction.PUSH,false)
   val result=DiagnosticLog.bind(trace).use {r.facade.upload(home)}
   assertTrue(result.configurationComplete);assertNull(repository(r).pending())
   assertEquals(reads,store.objectReads)
   assertNull(trace.timings.snapshot()[DiagnosticPhase.BASELINE_ARCHIVE_REUSE])
  }
 }
 @Test fun normalUploadUsesKnownMetadataAndNoSuccessfulPendingFiles() {
  val store=Store();publish(store);val parent=store.latest();val bridge=Bridge();val r=rig(bridge,store)
  r.facade.restoreSelected(r.facade.inspectHome(),parent.snapshot.id,false)
  bridge.content=files(19).toMutableMap();val home=r.facade.inspectHome()
  val reads=store.commitReads;val lists=store.listings;val exports=bridge.exports
  val pendingDir=repository(r).overwriteDirectory().parent.resolve("uploads")
  val old=if(Files.exists(pendingDir))Files.list(pendingDir).use {it.map {p->p.fileName.toString()}.toList().toSet()} else emptySet()
  store.beforeObject={assertNull(repository(r).pending());assertFalse(SafePaths.forceWrites())}
  val result=r.facade.upload(home)
  assertFalse(result.isPending);assertEquals(reads,store.commitReads);assertEquals(lists+1,store.listings);assertEquals(exports+1,bridge.exports)
  val now=if(Files.exists(pendingDir))Files.list(pendingDir).use {it.map {p->p.fileName.toString()}.toList().toSet()} else emptySet()
  assertEquals(old,now);assertTrue(SafePaths.forceWrites());assertEquals(store.latest().id,repository(r).baseline())
 }
 @Test fun currentHashEqualSkipsButParentEqualWithNewCurrentAppendsWithoutZipReads() {
  val store=Store();val bridge=Bridge();val r=publish(store,bridge)
  var home=r.facade.inspectHome();var writes=store.writes;var reads=store.objectReads;var exports=bridge.exports
  val same=r.facade.upload(home)
  assertFalse(same.isConflict);assertEquals(writes,store.writes);assertEquals(reads,store.objectReads)
  assertEquals(exports+1,bridge.exports)
  val parent=repository(r).baseline();bridge.content=files(28).toMutableMap()
  home=r.facade.inspectHome();writes=store.writes;reads=store.objectReads;exports=bridge.exports
  val changed=r.facade.upload(home)
  assertFalse(changed.isConflict);assertEquals(writes+2,store.writes);assertEquals(reads,store.objectReads)
  assertEquals(exports+1,bridge.exports);assertEquals(listOf(parent),store.latest().parents)
  assertEquals(contentSha256(bridge.content.mapValues {hash(it.value)}),store.latest().snapshot.id)
 }
 @Test fun optimisticUploadUsesOneFixedExportAndOnePreflight() {
  val store=Store();publish(store);val parent=store.latest();val bridge=Bridge();val r=rig(bridge,store)
  r.facade.restoreSelected(r.facade.inspectHome(),parent.snapshot.id,false)
  bridge.content=files(9).toMutableMap();val home=r.facade.inspectHome()
  val exports=bridge.exports;val lists=store.listings;val targets=bridge.targetReads;val writes=store.writes
  bridge.afterExport={bridge.content=files(10).toMutableMap()}
  val trace=DiagnosticTrace("upload117",DiagnosticAction.PUSH,false)
  val result=DiagnosticLog.bind(trace).use {
   r.facade.withUploadReadScope(java.util.concurrent.Callable {
    r.facade.upload(home).also {r.facade.historyPresentation(requireNotNull(it.refreshedInspection))}
   })
  }
  assertEquals(null,trace.timings.snapshot()[DiagnosticPhase.SNAPSHOT_VERIFY],"normal upload does not audit intact save archives")
  assertFalse(result.isConflict);assertFalse(result.isPending)
  assertEquals(exports+1,bridge.exports);assertEquals(lists+1,store.listings)
  assertEquals(targets+1,bridge.targetReads);assertEquals(writes+2,store.writes)
  val commit=store.latest();assertEquals(files(9).mapValues {hash(it.value)},commit.snapshot.files)
  assertEquals(commit.id,repository(r).baseline());assertNull(repository(r).pending())
  assertNotNull(result.refreshedInspection)
  bridge.targetChanged=true
  assertFails {r.facade.inspectHome()} // Operation-scoped identity must not survive success.
 }
 @Test fun optimisticUploadRetainsPendingOnWriteOrReadbackFailureThenFinishesExplicitRetry() {
  for(readback in listOf(false,true)) {
   val store=Store();publish(store);val parent=store.latest();val bridge=Bridge();val r=rig(bridge,store)
   r.facade.restoreSelected(r.facade.inspectHome(),parent.snapshot.id,false)
   bridge.content=files(9).toMutableMap();val home=r.facade.inspectHome()
   if(readback)store.afterCommit={throw java.io.IOException("lost commit acknowledgement")} else store.failWrites=true
   assertFails {r.facade.upload(home)}
   assertNotNull(repository(r).pending());assertEquals(parent.id,repository(r).baseline())
   store.failWrites=false;store.failCommitReads=false;store.afterCommit=null
   val result=r.facade.upload(r.facade.inspectHome())
   assertFalse(result.isPending);assertNull(repository(r).pending())
   assertEquals(store.latest().id,repository(r).baseline())
  }
 }
 @Test fun optimisticUploadDoesNotReadBackAcknowledgedPublication() {
  val store=Store();publish(store);val parent=store.latest();val bridge=Bridge();val r=rig(bridge,store)
  r.facade.restoreSelected(r.facade.inspectHome(),parent.snapshot.id,false)
  bridge.content=files(9).toMutableMap();val home=r.facade.inspectHome()
  store.afterCommit={store.failCommitReads=true;store.afterReadObject={error("unexpected publication readback")}}
  val result=r.facade.upload(home)
  assertFalse(result.isPending);assertNull(repository(r).pending())
  assertEquals(store.latest().id,repository(r).baseline())
 }
 @Test fun publicationUsesOperationBytesAndRequiresPendingMarker() {
  val adapter=AndroidGameAdapter(identity)
  val source=temp.resolve("verified-source");source.createDirectories()
  files(9).forEach {(name,bytes)->source.resolve(name).also {it.parent.createDirectories();it.writeBytes(bytes)}}
  val verified=VerifiedSnapshot.read(Snapshots.capture(source,adapter,connection.profile,"synthetic",null),adapter)
  val directory=temp.resolve("verified-pending")
  val repository=AppendRepository(directory,adapter,connection.profile)
  val frozen=repository.prepareVerified(verified,emptyList())
  Files.delete(directory.resolve("active-plan.txt"))
  val store=Store()
  assertFails {repository.publishAcknowledged(store,frozen,CommitGraph(emptyMap(),emptyMap()))}
  assertEquals(0,store.writes)
  assertNull(AppendRepository(directory,adapter,connection.profile).pending())
 }
 @Test fun verifiedPublicationCancellationRetainsPendingAndScopeIsReleased() {
  val store=Store();publish(store);val parent=store.latest();val bridge=Bridge();val r=rig(bridge,store)
  r.facade.restoreSelected(r.facade.inspectHome(),parent.snapshot.id,false)
  bridge.content=files(9).toMutableMap();val home=r.facade.inspectHome()
  store.afterCommit={throw java.util.concurrent.CancellationException("lost final response")}
  assertFailsWith<java.util.concurrent.CancellationException> {
   r.facade.withUploadReadScope(java.util.concurrent.Callable {r.facade.upload(home)})
  }
  assertNotNull(repository(r).pending());assertEquals(parent.id,repository(r).baseline())
  bridge.targetChanged=true;assertFails {r.facade.inspectHome()}
  bridge.targetChanged=false
  val result=r.facade.upload(r.facade.inspectHome())
  assertFalse(result.isPending);assertNull(repository(r).pending())
  assertEquals(store.latest().id,repository(r).baseline())
 }
 @Test fun optimisticUploadDoesNotPublishWhenPreflightAlreadyHasBranches() {
  val store=Store();publish(store);val parent=store.latest();val bridge=Bridge();val r=rig(bridge,store)
  r.facade.restoreSelected(r.facade.inspectHome(),parent.snapshot.id,false)
  store.child(parent);store.child(parent);bridge.content=files(9).toMutableMap()
  val home=r.facade.inspectHome();val writes=store.writes
  val result=runCatching {r.facade.upload(home)}
  assertTrue(result.isFailure || result.getOrThrow().isConflict)
  assertEquals(writes,store.writes);assertEquals(parent.id,repository(r).baseline())
 }
}
