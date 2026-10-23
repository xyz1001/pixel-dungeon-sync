package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*

class RunMergeTest {
 @TempDir lateinit var temp: Path
 private val ids=(1..8).map { UUID.randomUUID().toString() }
 private val context=SnapshotCaptureContext(DeviceIdentity(UUID.randomUUID().toString(),"测试电脑"),"2026-10-10T10:00:00Z")
 private val target=TargetGame("isolated-test.jar","3.3.8",896,"a".repeat(64),true,802,"test")
 private fun snapshot(progress: List<String>, global: String="same", runIds: List<String> = ids, slots: List<Int> = progress.indices.map { it+1 }): Snapshot {
  val files=linkedMapOf<String,ByteArray>("rankings.dat" to global.encodeToByteArray())
  progress.forEachIndexed { i,value ->
   files["game${slots[i]}/game.dat"]="""{"version":896,"hero":{"name":"$value"}}""".encodeToByteArray()
   files["game${slots[i]}/depth1.dat"]="floor-$value".encodeToByteArray()
   files["game${slots[i]}/depth2-branch1.dat"]="branch-$value".encodeToByteArray()
  }
  val hashes=files.mapValues { hash(it.value) };val runHashes=runContentHashes(hashes)
  val meta=DirectorySnapshotMetadata(contentSha256=contentSha256(hashes),runs=slots.mapIndexed { i,slot -> "game$slot" to RunIdentity(runIds[i],runHashes.getValue("game$slot")) }.toMap())
  return Snapshots.fromFiles(files,Shattered,"demo","3.3.8",meta)
 }
 private fun plan(base:Snapshot,vararg branches:Snapshot)=RunMergePlanner.plan(base,branches.toList(),target,context)
 @Test fun independentEditsCopyWholeRunUnitsWithoutExtraSlots() {
  val base=snapshot(listOf("A0","B0"));val local=snapshot(listOf("A0","B1"));val remote=snapshot(listOf("A1","B0"))
  val merged=plan(base,local,remote);assertEquals(2,merged.slots);assertEquals(0,merged.addedSlots)
  val files=Snapshots.verify(merged.snapshot,Shattered)
  val byId=merged.snapshot.manifest.directoryMetadata!!.runs.entries.associate { it.value.runUUID to it.key }
  for((id,value) in listOf(ids[0] to "A1",ids[1] to "B1")){
   val slot=byId.getValue(id);assertEquals("floor-$value",files.getValue("$slot/depth1.dat").decodeToString());assertEquals("branch-$value",files.getValue("$slot/depth2-branch1.dat").decodeToString());assertContains(files.getValue("$slot/game.dat").decodeToString(),value)
  }
 }
 @Test fun divergentSameRunForksAndMatchingContentDeduplicates() {
  val base=snapshot(listOf("old"));val left=snapshot(listOf("left"));val right=snapshot(listOf("right"))
  val merged=plan(base,left,right);assertEquals(2,merged.slots);assertEquals(1,merged.addedSlots)
  val runs=merged.snapshot.manifest.directoryMetadata!!.runs.values
  assertEquals(2,runs.map { it.runUUID }.distinct().size);assertTrue(runs.all { it.runUUID!=ids[0]&&it.parentRunUUIDs==listOf(ids[0]) })
  assertEquals(1,plan(base,left,left).slots)
 }
 @Test fun threeBranchesPreserveEveryDistinctRunVersion() {
  val merged=plan(snapshot(listOf("base")),snapshot(listOf("one")),snapshot(listOf("two")),snapshot(listOf("three")))
  assertEquals(3,merged.slots);val files=Snapshots.verify(merged.snapshot,Shattered)
  assertEquals(setOf("floor-one","floor-two","floor-three"),files.filterKeys { it.endsWith("/depth1.dat") }.values.map { it.decodeToString() }.toSet())
 }
 @Test fun slotOverflowAndGlobalConflictFailBeforeAnyApply() {
  val base=snapshot(List(6){"base$it"});val local=snapshot(List(6){if(it==0)"left" else "base$it"});val remote=snapshot(List(6){if(it==0)"right" else "base$it"})
  val before=local.zip.copyOf();val failure=assertFailsWith<RunMergeException>{plan(base,local,remote)};assertContains(failure.message!!,"需要 7");assertContentEquals(before,local.zip)
  assertFailsWith<RunMergeException>{plan(snapshot(listOf("base")),snapshot(listOf("left"),"left-records"),snapshot(listOf("right"),"right-records"))}
 }
 @Test fun missingIdentityOldMetadataAndUnsupportedFormatNeverGuess() {
  val base=snapshot(listOf("base"));val changed=snapshot(listOf("changed"));val old=changed.copy(manifest=changed.manifest.copy(directoryMetadata=null))
  assertFailsWith<RunMergeException>{plan(base,old,base)}
  val meta=changed.manifest.directoryMetadata!!;val unknown=changed.copy(manifest=changed.manifest.copy(directoryMetadata=meta.copy(runs=meta.runs.mapValues { it.value.copy(runUUID=null,previousRunUUID=it.value.runUUID) })))
  assertFailsWith<RunMergeException>{plan(base,unknown,base)}
  assertFailsWith<RunMergeException>{RunMergePlanner.plan(base,listOf(changed),target.copy(versionCode=922,displayVersion="4.0.2"),context)}
 }
 @Test fun movedSlotsKeepIdentityAndUnrelatedIdenticalRunsAreNotCollapsed() {
  val base=snapshot(listOf("A","B"));val moved=snapshot(listOf("A","B"),slots=listOf(3,1))
  val merged=plan(base,moved,base);assertEquals(2,merged.slots);assertEquals(setOf("game3","game1"),merged.snapshot.manifest.directoryMetadata!!.runs.keys)
  val newLeft=snapshot(listOf("same"),runIds=listOf(ids[2]));val newRight=snapshot(listOf("same"),runIds=listOf(ids[3]))
  val empty=snapshot(emptyList());assertEquals(2,plan(empty,newLeft,newRight).slots)
 }
 @Test fun deletionAgainstUnchangedIsAppliedButDeletionAgainstEditsFails() {
  val base=snapshot(listOf("A"));val deleted=snapshot(emptyList())
  assertEquals(0,plan(base,deleted,base).slots)
  assertFailsWith<RunMergeException>{plan(base,deleted,snapshot(listOf("changed")))}
 }

 @Test fun sessionsMergeVerifiedBranchesAndKeepNetworkRetryFrozen() {
  val policy=object:ProfileVersions {
   override fun annotate(snapshot:Snapshot)=snapshot.copy(manifest=snapshot.manifest.copy(saveVersions=ShatteredVersions.detect(Snapshots.verify(snapshot,Shattered))))
   override fun target(path:String)=target
   override fun checkRestore(snapshot:Snapshot,targetPath:String){ShatteredVersions.requireCompatible(ShatteredVersions.detect(Snapshots.verify(snapshot,Shattered)),target)}
  }
  val remote=object:AppendStore {
   val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>();var fail=false
   override fun listCommits(cursor:String?)=CommitPage(commits.keys.toList())
   override fun readCommit(id:String)=commits[id]
   override fun readObject(id:String)=objects[id]
   override fun writeCommit(id:String,bytes:ByteArray){check(!fail);commits[id]=bytes}
   override fun writeObject(id:String,bytes:ByteArray){check(!fail);objects[id]=bytes}
   override fun legacyPresent()=false
  }
  val aRoot=temp.resolve("a").createDirectory();val bRoot=temp.resolve("b").createDirectory()
  Snapshots.verify(snapshot(listOf("A0","B0")),Shattered).forEach { (name,bytes)->aRoot.resolve(name).parent.createDirectories();aRoot.resolve(name).writeBytes(bytes) }
  val a=AppendProfileSession(AppConfig(saveRoot=aRoot.toString(),profile="demo"),temp.resolve("state-a"),versions=policy,captureQuietMillis=0)
  val b=AppendProfileSession(AppConfig(saveRoot=bRoot.toString(),profile="demo"),temp.resolve("state-b"),versions=policy,captureQuietMillis=0)
  assertIs<AppendPushResult.Success>(a.push(remote){true});b.restoreRemote(b.readRemote(remote).single().entry.manifest.id,remote){true}
  aRoot.resolve("game1/depth1.dat").writeText("remote A1");a.confirmRunIdentity("game1",a.observedFiles(),true){true};a.push(remote){true}
  bRoot.resolve("game2/depth1.dat").writeText("local B1");b.confirmRunIdentity("game2",b.observedFiles(),true){true}
  // Existing old-client branches remain mergeable; new upload preflight never creates one.
  fun legacyPublish(session:AppendProfileSession) {
   val repo=AppendRepository(session.directory.resolve("append-v2"),Shattered,"demo")
   val plan=repo.prepare(session.loadSnapshot(session.createSnapshot {true}.manifest.id),listOfNotNull(session.baseline()))
   val commit=repo.publish(remote,plan);repo.setBaseline(commit.id,repo.discover(remote));repo.clearPending()
  }
  legacyPublish(b)
  val conflict=assertIs<AppendPushResult.Diverged>(b.push(remote){true}).conflict
  val preview=b.previewMerge(conflict,remote){true};assertEquals(2,preview.slots);assertEquals(0,preview.addedSlots)
  val originalCommits=remote.commits.mapValues { it.value.copyOf() }
  val originalObjects=remote.objects.mapValues { it.value.copyOf() };val previousBaseline=b.baseline()
  remote.fail=true;val prepared=assertIs<AppendPushResult.Prepared>(b.merge(conflict,remote){true});assertTrue(b.hasPending())
  assertEquals(previousBaseline,b.baseline());assertEquals(originalCommits.keys,remote.commits.keys)
  originalCommits.forEach { (id,bytes)->assertContentEquals(bytes,remote.commits.getValue(id)) }
  originalObjects.forEach { (id,bytes)->assertContentEquals(bytes,remote.objects.getValue(id)) }
  assertFails { b.push(remote){true} };assertTrue(b.hasPending())
  val mergedFiles=b.observedFiles();assertEquals(preview.snapshot.manifest.files,mergedFiles)
  remote.fail=false;val result=assertIs<AppendPushResult.Success>(b.push(remote){true});assertEquals(prepared.commit,result.commit);assertEquals(mergedFiles,result.commit.snapshot.files);assertEquals(conflict.observedTips,result.commit.parents.toSet());assertFalse(b.hasPending())
  assertEquals("remote A1",bRoot.resolve("game1/depth1.dat").readText());assertEquals("local B1",bRoot.resolve("game2/depth1.dat").readText())
  aRoot.resolve("game1/depth1.dat").writeText("remote A2");a.confirmRunIdentity("game1",a.observedFiles(),true){true};legacyPublish(a)
  bRoot.resolve("game2/depth1.dat").writeText("local B2");b.confirmRunIdentity("game2",b.observedFiles(),true){true}
  assertEquals(2,b.previewMerge(b.inspectConflict(remote){true}!!,remote){true}.slots)
  val beforeMergeCommits=remote.commits.keys.toSet();val beforeMergeBaseline=b.baseline()
  val nextPrepared=assertIs<AppendPushResult.Prepared>(b.mergeCurrent(remote){true})
  assertEquals(beforeMergeCommits,remote.commits.keys);assertEquals(beforeMergeBaseline,b.baseline());assertTrue(b.hasPending())
  val nextUpload=assertIs<AppendPushResult.Success>(b.push(remote){true})
  assertEquals(nextPrepared.commit,nextUpload.commit);assertFalse(b.hasPending())
  assertEquals("remote A2",bRoot.resolve("game1/depth1.dat").readText());assertEquals("local B2",bRoot.resolve("game2/depth1.dat").readText())
  bRoot.resolve("game1/game.dat").writeBytes(byteArrayOf());bRoot.resolve("game2/game.dat").writeBytes(byteArrayOf())
  val beforeCount=remote.commits.size;val beforeFiles=b.observedFiles()
  assertFails {b.mergeCurrent(remote){true}}
  assertEquals(beforeCount,remote.commits.size);assertEquals(beforeFiles,b.observedFiles());assertFalse(b.hasPending())
 }
}
