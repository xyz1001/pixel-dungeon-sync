package pdsync

import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*

class DirectoryMetadataTest {
 @TempDir lateinit var temp: Path
 private fun files() = mapOf("game1/game.dat" to hash("run".encodeToByteArray()),"game1/depth1.dat" to hash("floor".encodeToByteArray()),"rankings.dat" to hash("records".encodeToByteArray()))
 private fun root()=temp.resolve(UUID.randomUUID().toString()).createDirectory().also { r -> files().forEach { (p,v) -> r.resolve(p).parent.createDirectories();r.resolve(p).writeText(v) } }
 @Test fun unchangedObservationSkipsReplaceButChangedObservationPersistsAndReportsFailure() {
  val r=root();var writes=0
  val store=DirectoryMetadataStore(r) {a,b->writes++;Files.move(a,b,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit}
  val f=files();val first=store.observe(f);assertEquals(1,writes)
  repeat(3) {assertEquals(first,store.observe(f))};assertEquals(1,writes)
  val changed=f+("game1/depth1.dat" to hash(byteArrayOf(9)))
  store.observe(changed);assertEquals(2,writes)
  val broken=DirectoryMetadataStore(r) {_,_->throw java.io.IOException("test write failure")}
  broken.observe(changed)
  assertFailsWith<java.io.IOException> {broken.observe(f)}
  assertEquals(contentSha256(changed),(store.read() as SidecarRead.Valid).value.currentContentSha256)
 }
 @Test fun canonicalDigestUsesPathsAndActualBytesButNotEnumerationOrder() {
  val original=files(); assertEquals(contentSha256(original),contentSha256(original.entries.reversed().associate { it.toPair() }))
  assertNotEquals(contentSha256(original),contentSha256(original-"rankings.dat"))
  assertNotEquals(contentSha256(original),contentSha256(original+("rankings.dat" to hash(byteArrayOf(1)))))
  assertNotEquals(contentSha256(original),contentSha256(original.mapKeys { if(it.key=="rankings.dat")"badges.dat" else it.key }))
  assertEquals("746c9f41ed4a0ae8c8be771e58c177510d69ed4ababbe02d60565dfa5440978e",contentSha256(emptyMap()))
  assertFails { contentSha256(mapOf("../a" to hash(byteArrayOf()))) };assertFails { contentSha256(mapOf("a" to "bad")) }
  assertFails { contentSha256(mapOf("A" to hash(byteArrayOf()),"a" to hash(byteArrayOf()))) }
 }
 @Test fun directoryNamesAndSidecarNeverEnterGameContentEvenForBroadAdapters() {
  val root=root();val adapter=object:GameAdapter by Shattered { override fun includes(name:String)=name.isNotEmpty() }
  val before=Snapshots.capture(root,adapter,"demo","1",null)
  val store=DirectoryMetadataStore(root);store.observe(before.manifest.files);store.rename("自定义存档",before.manifest.files)
  root.resolve(".pdsync-meta.tmp-${UUID.randomUUID()}").writeText("partial metadata")
  val after=Snapshots.capture(root,adapter,"demo","1",null)
  assertEquals(before.manifest.files,after.manifest.files)
  assertEquals(contentSha256(before.manifest.files),contentSha256(after.manifest.files))
  assertEquals(before.manifest.files,SaveStabilityGuard(root,adapter,{true}).fingerprint().files)
 }
 @Test fun identitiesSurviveRestartAndTransferButChangedSlotsBecomeUnknown() {
  val store=DirectoryMetadataStore(root());val f=files();val initial=store.observe(f);val id=initial.runs.getValue("game1").runUUID
  assertNotNull(id);assertTrue(validCommitId(id));assertEquals(initial,store.observe(f))
  val other=DirectoryMetadataStore(root());other.adopt(initial,f);assertEquals(id,other.observe(f).runs.getValue("game1").runUUID)
  val changed=f+("game1/depth1.dat" to hash(byteArrayOf(2)))
  val unknown=other.observe(changed).runs.getValue("game1");assertNull(unknown.runUUID);assertEquals(id,unknown.previousRunUUID)
  assertEquals(id,other.confirmRun("game1",changed,true).runs.getValue("game1").runUUID)
  other.observe(f);assertNotEquals(id,other.confirmRun("game1",f,false).runs.getValue("game1").runUUID)
  assertEquals(runContentHashes(f).getValue("game1"),runContentHashes(f.mapKeys { it.key.replace("game1/","game3/") }).getValue("game3"))
 }
 @Test fun missingAndDamagedSidecarsNeverInventSynchronizationProof() {
  val root=root();val store=DirectoryMetadataStore(root);val f=files();val proof=DirectorySyncBaseline(hash("scope".encodeToByteArray()),UUID.randomUUID().toString(),contentSha256(f))
  assertFalse(store.isSynced(f,proof));store.observe(f);assertFalse(store.isSynced(f,proof));store.markSynced(f,proof);assertTrue(store.isSynced(f,proof))
  assertFalse(store.isSynced(f,null));assertFalse(store.isSynced(f,proof.copy(scope=hash("other".encodeToByteArray()))))
  assertFalse(store.isSynced(f+("badges.dat" to hash(byteArrayOf())),proof))
  root.resolve(DIRECTORY_METADATA_FILE).writeText("broken");assertIs<SidecarRead.Damaged>(store.read());assertFalse(store.isSynced(f,proof))
  assertNull(store.observe(f).runs.getValue("game1").runUUID);assertEquals("broken",root.resolve(DIRECTORY_METADATA_FILE).readText())
 }
 @Test fun atomicFailureKeepsOldMetadataAndCleansOwnTemporaryFile() {
  val root=root();val store=DirectoryMetadataStore(root);store.observe(files());val before=root.resolve(DIRECTORY_METADATA_FILE).readBytes()
  val fail=DirectoryMetadataStore(root) { a,b -> throw AtomicMoveNotSupportedException(a.toString(),b.toString(),"injected") }
  assertFailsWith<AtomicMoveNotSupportedException> { fail.rename("新名",files()) }
  assertContentEquals(before,root.resolve(DIRECTORY_METADATA_FILE).readBytes())
  assertFalse(Files.list(root).use { it.anyMatch { p->p.fileName.toString().startsWith(".pdsync-meta.tmp-") } })
 }
 @Test fun snapshotExtensionIsOptionalButTamperingIsRejected() {
  val root=root();val old=Snapshots.capture(root,Shattered,"demo","1",null)
  assertNull(json.decodeFromString<Manifest>(old.manifest.toJson()).directoryMetadata)
  val metadata=DirectoryMetadataStore(root).observe(old.manifest.files)
  Snapshots.verify(old.copy(manifest=old.manifest.copy(directoryMetadata=metadata)),Shattered)
  assertFails { Snapshots.verify(old.copy(manifest=old.manifest.copy(directoryMetadata=metadata.copy(contentSha256="0".repeat(64)))),Shattered) }
  assertFails { metadata.copy(schema=2).validated(old.manifest.files) }
  assertFails { metadata.copy(runs=emptyMap()).validated(old.manifest.files) }
 }
 @Test fun sidecarAdoptionFailureRetainsActualNewBytesWithoutRollback() {
  val root=root();val before=Snapshots.capture(root,Shattered,"demo","1",null)
  val target=root();target.resolve("game1/game.dat").writeText("changed")
  val incoming=Snapshots.capture(target,Shattered,"demo","1",null)
  assertFails { Snapshots.restore(incoming,root,Shattered,"1",{true},afterRestore={throw java.io.IOException("sidecar failure")}) }
  assertEquals(incoming.manifest.files,Snapshots.capture(root,Shattered,"demo","1",null).manifest.files)
 }
 @Test fun realSessionOnlyMarksBaselineAfterVerifiedPublicationAndTransfersMetadata() {
  val policy=object:ProfileVersions {
   override fun annotate(snapshot:Snapshot)=snapshot
   override fun target(path:String):TargetGame?=null
   override fun checkRestore(snapshot:Snapshot,targetPath:String)=Unit
  }
  val source=root();val target=temp.resolve("target").createDirectory()
  val a=AppendProfileSession(AppConfig(saveRoot=source.toString()),temp.resolve("state-a"),versions=policy)
  val b=AppendProfileSession(AppConfig(saveRoot=target.toString()),temp.resolve("state-b"),versions=policy)
  val remote=object:AppendStore {
   val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>();var fail=true
   override fun listCommits(cursor:String?)=CommitPage(commits.keys.toList())
   override fun readCommit(id:String)=commits[id]
   override fun readObject(id:String)=objects[id]
   override fun writeCommit(id:String,bytes:ByteArray){commits[id]=bytes}
   override fun writeObject(id:String,bytes:ByteArray){check(!fail);objects[id]=bytes}
   override fun legacyPresent()=false
  }
  a.renameCurrent("冒险存档") {true}
  assertFails { a.push(remote) {true} }
  assertNull((a.directoryStatus() as SidecarRead.Valid).value.lastSynced)
  remote.fail=false
  val published=assertIs<AppendPushResult.Success>(a.push(remote) {true}).commit
  assertEquals(published.id,(a.directoryStatus() as SidecarRead.Valid).value.lastSynced?.commitId)
  val entry=b.readRemote(remote).single().entry
  b.restoreRemote(entry.manifest.id,remote) {true}
  val copied=(b.directoryStatus() as SidecarRead.Valid).value
  assertEquals("冒险存档",copied.displayName)
  assertEquals(published.snapshot.directoryMetadata?.runs,copied.runs)
  assertEquals(published.id,copied.lastSynced?.commitId)
  source.resolve("game1/game.dat").writeText("changed offline")
  val captured=a.createSnapshot {true}
  assertNull(captured.manifest.directoryMetadata?.runs?.get("game1")?.runUUID)
  assertNotNull(captured.manifest.directoryMetadata?.runs?.get("game1")?.previousRunUUID)
  source.resolve(DIRECTORY_METADATA_FILE).deleteExisting()
  assertNull(a.createSnapshot {true}.manifest.directoryMetadata?.runs?.get("game1")?.runUUID)
 }
 @Test fun readOnlyObservationDoesNotCreateOrRewriteSidecar() {
  val root=root();val store=DirectoryMetadataStore(root);val f=files()
  store.observe(f,initialImport=false,persist=false)
  assertFalse(root.resolve(DIRECTORY_METADATA_FILE).exists())
  val initial=store.observe(f);val sidecar=root.resolve(DIRECTORY_METADATA_FILE)
  val bytes=sidecar.readBytes();val modified=Files.getLastModifiedTime(sidecar)
  val changed=f+("badges.dat" to hash("new badge".encodeToByteArray()))
  val preview=store.observe(changed,persist=false)
  assertEquals(initial.runs,preview.runs);assertEquals(contentSha256(changed),preview.contentSha256)
  assertContentEquals(bytes,sidecar.readBytes());assertEquals(modified,Files.getLastModifiedTime(sidecar))
 }

}
