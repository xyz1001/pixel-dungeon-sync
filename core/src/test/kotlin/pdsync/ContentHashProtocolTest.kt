package pdsync

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.*
import kotlin.test.*

class ContentHashProtocolTest {
 @TempDir lateinit var root:Path
 private fun repo(label:String)=AppendRepository(root.resolve(label),Shattered,"hero")
 private fun snapshot(value:String):Snapshot {
  val dir=root.resolve(UUID.randomUUID().toString()).createDirectories()
  dir.resolve("game1").createDirectories()
  dir.resolve("game1/game.dat").writeText("""{"version":922,"init_ver":922,"marker":"$value"}""")
  return Snapshots.capture(dir,Shattered,"hero","4.0.2",null)
 }
 private fun bytes(c:AppendCommit)=json.encodeToString(c).encodeToByteArray()
 private class Store:AppendStore {
  val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>()
  var writes=0
  var onCommit:((String,ByteArray)->Unit)?=null
  override fun legacyPresent()=false
  override fun listCommits(cursor:String?)=CommitPage(commits.keys.toList())
  override fun readCommit(id:String)=commits[id]
  override fun readObject(id:String)=objects[id]
  override fun writeCommit(id:String,bytes:ByteArray) {
   writes++;onCommit?.invoke(id,bytes)
   check(id !in commits);commits[id]=bytes
  }
  override fun writeObject(id:String,bytes:ByteArray) { writes++;check(id !in objects);objects[id]=bytes }
  fun seed(c:AppendCommit,s:Snapshot) { commits[c.id]=json.encodeToString(c).encodeToByteArray();objects[c.snapshot.id]=s.zip }
 }
 @Test fun newRecordsUseCompleteContentHashAndUuidValidatorRemainsStrict() {
  val s=snapshot("a");val p=repo("r").prepare(s,emptyList())
  assertEquals(3,p.commit.schema)
  assertEquals(contentSha256(s.manifest.files),p.commit.id)
  assertEquals(p.commit.id,p.commit.snapshot.id)
  assertTrue(validRecordId(p.commit.id));assertFalse(validCommitId(p.commit.id))
  assertTrue(validCommitId(UUID.randomUUID().toString()))
 }
 @Test fun restoringAnyHistoricalContentReusesWithoutAddingAnEdgeOrWriting() {
  val r=repo("r");val store=Store()
  val a=r.prepare(snapshot("a"),emptyList());r.publish(store,a);r.clearPending()
  val b=r.prepare(snapshot("b"),listOf(a.commit.id));r.publish(store,b);r.clearPending()
  val prior=store.commits.mapValues {it.value.copyOf()};val writes=store.writes
  val restored=r.prepare(snapshot("a"),listOf(b.commit.id),"restore")
  val result=r.publishResult(store,restored)
  assertTrue(result.reused);assertEquals(a.commit,result.commit);assertEquals(writes,store.writes)
  prior.forEach {(id,data)->assertContentEquals(data,store.commits[id])}
  assertEquals(setOf(b.commit.id),r.discover(store).tips)
 }
 @Test fun legacyUuidMetadataAndParentsStayUnchangedWhileNewParentsUseHash() {
  val s=snapshot("old");val old=AppendCommit(id=UUID.randomUUID().toString(),parents=emptyList(),snapshot=s.manifest,objectBytes=s.zip.size)
  val store=Store();store.seed(old,s);val original=bytes(old);val r=repo("r")
  r.discover(store)
  val p=r.prepare(snapshot("new"),listOf(old.id));r.publish(store,p)
  assertEquals(listOf(contentSha256(s.manifest.files)),p.commit.parents)
  val graph=r.discover(store)
  assertEquals(listOf(old.id),graph.parentIds(p.commit));assertTrue(graph.descendsFrom(p.commit.id,old.id))
  assertEquals(setOf(p.commit.id),graph.tips);assertContentEquals(original,store.commits[old.id])
  assertFalse(contentSha256(s.manifest.files) in store.commits)
 }
 @Test fun legacySameContentReusesTheOldPhysicalRecordWithZeroWrites() {
  val s=snapshot("old");val c=AppendCommit(id=UUID.randomUUID().toString(),parents=emptyList(),snapshot=s.manifest,objectBytes=s.zip.size)
  val store=Store();store.seed(c,s);val r=repo("r")
  val result=r.publishResult(store,r.prepare(snapshot("old"),emptyList()))
  assertTrue(result.reused);assertEquals(c,result.commit);assertEquals(0,store.writes)
 }
 @Test fun commitCollisionAdoptsFirstWriterMetadataAndDoesNotRewriteIt() {
  val r=repo("r");val store=Store();val p=r.prepare(snapshot("a"),emptyList())
  val winner=p.commit.copy(kind="restore")
  store.onCommit={id,_->store.commits[id]=bytes(winner)}
  val result=r.publishResult(store,p)
  assertTrue(result.reused);assertEquals(winner,result.commit);assertContentEquals(bytes(winner),store.commits[winner.id])
 }
 @Test fun existingZipEncodingWithSameFilesIsVerifiedAndAdopted() {
  val r=repo("r");val store=Store();val p=r.prepare(snapshot("a"),emptyList())
  val files=Snapshots.verify(Snapshot(p.commit.snapshot,p.zip),Shattered)
  val out=ByteArrayOutputStream()
  ZipOutputStream(out).use { zip->files.forEach {(name,data)->zip.putNextEntry(ZipEntry(name).apply {time=123456789000L});zip.write(data);zip.closeEntry()} }
  val priorZip=out.toByteArray();store.objects[p.commit.id]=priorZip
  val result=r.publishResult(store,p)
  assertFalse(result.reused);assertEquals(hash(priorZip),result.commit.snapshot.sha256)
  assertEquals(priorZip.size,result.commit.objectBytes);assertContentEquals(priorZip,store.objects[p.commit.id])
  assertEquals(1,store.writes)
 }
 @Test fun legacyPendingIsConvertedDurablyAndItsOriginalFilesArePreserved() {
  val s=snapshot("old-pending");val c=AppendCommit(id=UUID.randomUUID().toString(),parents=emptyList(),snapshot=s.manifest,objectBytes=s.zip.size)
  val dir=root.resolve("r");dir.resolve("uploads").createDirectories()
  dir.resolve("uploads/${c.id}.json").writeBytes(bytes(c));dir.resolve("uploads/${c.id}.zip").writeBytes(s.zip)
  dir.resolve("active-plan.txt").writeText(c.id)
  val r=repo("r");val store=Store();val result=r.publishResult(store,r.pending()!!)
  assertEquals(3,result.commit.schema);assertTrue(validContentHashId(result.commit.id))
  assertEquals(result.commit.id,r.pending()!!.commit.id)
  assertContentEquals(bytes(c),dir.resolve("uploads/${c.id}.json").readBytes())
  assertContentEquals(s.zip,dir.resolve("uploads/${c.id}.zip").readBytes())
  assertFalse(c.id in store.commits)
 }
 @Test fun missingAndCorruptExistingObjectsNeverPermitAnotherPublication() {
  for(corrupt in listOf(false,true)) {
   val r=repo("r-$corrupt");val store=Store();val p=r.prepare(snapshot("a"),emptyList())
   store.commits[p.commit.id]=p.manifestBytes
   if(corrupt)store.objects[p.commit.id]=byteArrayOf(1,2,3)
   assertFailsWith<UnsettledRemoteException> {r.publishResult(store,p)}
   assertEquals(0,store.writes)
  }
 }
 @Test fun legacyAliasCannotHideACycleBetweenHashRecords() {
  val sa=snapshot("a");val sb=snapshot("b")
  val a=contentSha256(sa.manifest.files);val b=contentSha256(sb.manifest.files)
  val legacy=AppendCommit(id=UUID.randomUUID().toString(),parents=emptyList(),snapshot=sa.manifest,objectBytes=sa.zip.size)
  val ca=AppendCommit(schema=3,id=a,parents=listOf(b),snapshot=sa.manifest.copy(id=a),objectBytes=sa.zip.size)
  val cb=AppendCommit(schema=3,id=b,parents=listOf(a),snapshot=sb.manifest.copy(id=b),objectBytes=sb.zip.size)
  val store=Store();store.seed(legacy,sa);store.seed(ca,sa);store.seed(cb,sb)
  val graph=repo("r").discover(store)
  assertEquals(setOf(legacy.id),graph.commits.keys)
  assertEquals(setOf(a,b),graph.pending.keys)
 }

 @Test fun hashParentResolvesAllLegacyAliasesWithoutChangingTheirRawAncestry() {
  val s=snapshot("same");val store=Store();val r=repo("r")
  val ids=listOf("11111111-1111-4111-8111-111111111111","33333333-3333-4333-8333-333333333333","22222222-2222-4222-8222-222222222222")
  val legacy=ids.mapIndexed { index,id -> AppendCommit(id=id,parents=if(index==0) emptyList() else listOf(ids[index-1]),snapshot=s.manifest,objectBytes=s.zip.size) }
  legacy.forEach { store.seed(it,s) }
  val before=store.commits.mapValues { it.value.copyOf() }
  r.setBaseline(ids.last(),r.discover(store))
  val plan=r.prepare(snapshot("new"),listOf(ids.last()))
  val result=r.publishResult(store,plan)
  assertFalse(result.reused)
  val graph=r.discover(store);graph.requireSettled()
  assertEquals(ids.toSet(),graph.parentIds(result.commit).toSet())
  assertEquals(setOf(result.commit.id),graph.tips)
  assertTrue(graph.descendsFrom(result.commit.id,ids.last()))
  assertTrue(graph.descendsFrom(result.commit.id,ids.first()))
  before.forEach { (id,bytes)->assertContentEquals(bytes,store.commits[id]) }
  assertEquals(graph.parentIds(result.commit),r.cachedGraph().parentIds(result.commit))
 }
 @Test fun equalContentLegacyRootsAllowOneHashParentWithoutRewritingOldRecords() {
  val s=snapshot("same");val store=Store();val r=repo("r")
  val legacy=List(2) { AppendCommit(id=UUID.randomUUID().toString(),parents=emptyList(),snapshot=s.manifest,objectBytes=s.zip.size) }
  legacy.forEach { store.seed(it,s) }
  val before=store.commits.mapValues { it.value.copyOf() }
  val graph=r.discover(store);graph.requireSettled()
  assertEquals(legacy.map { it.id }.toSet(),graph.resolveParentIds(contentSha256(s.manifest.files)))
  val plan=r.prepare(snapshot("new"),listOf(legacy.first().id))
  val published=r.publishResult(store,plan,graph)
  assertFalse(published.reused)
  assertEquals(listOf(contentSha256(s.manifest.files)),published.commit.parents)
  val complete=r.discover(store);complete.requireSettled()
  assertEquals(setOf(published.commit.id),complete.tips)
  legacy.forEach { assertTrue(complete.descendsFrom(published.commit.id,it.id));assertContentEquals(before[it.id],store.commits[it.id]);assertTrue(complete.commits.getValue(it.id).parents.isEmpty()) }
  assertEquals(2,store.writes)
 }
 @Test fun hashParentRequiresAllEquivalentPhysicalEvidenceToBeComplete() {
  val same=snapshot("same");val changed=snapshot("changed");val store=Store()
  val valid=AppendCommit(id=UUID.randomUUID().toString(),parents=emptyList(),snapshot=same.manifest,objectBytes=same.zip.size)
  val broken=valid.copy(id=UUID.randomUUID().toString(),parents=listOf(UUID.randomUUID().toString()))
  val id=contentSha256(changed.manifest.files)
  val child=AppendCommit(schema=3,id=id,parents=listOf(contentSha256(same.manifest.files)),snapshot=changed.manifest.copy(id=id),objectBytes=changed.zip.size)
  store.seed(valid,same);store.seed(broken,same);store.seed(child,changed)
  val graph=repo("r").discover(store)
  assertTrue(valid.id in graph.commits)
  assertTrue(broken.id in graph.pending);assertTrue(child.id in graph.pending)
 }


}
