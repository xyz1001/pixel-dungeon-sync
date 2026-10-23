package pdsync

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.io.path.*

/** A confirmed local lineage, not current-byte inequality, identifies the replaceable cloud line. */
data class CloudOverwriteSelection(val base:String,val target:String)
fun cloudOverwriteSelection(graph:CommitGraph,baseline:String?,files:Map<String,String>):CloudOverwriteSelection? {
 val anchor=baseline?.takeIf {it in graph.commits} ?: return null
 val tips=graph.tips.mapNotNull(graph.commits::get)
 val matching=tips.filter {it.snapshot.files==files}.map {it.id}.toSet()
 val other=tips.filter { !graph.descendsFrom(it.id,anchor) }
 val candidates=if(other.isNotEmpty())other else tips.filter {it.id!=anchor && it.id !in matching}
 val target=candidates.singleOrNull()?.id ?: return null
 // The selected cloud content cannot also be the replacement. Do not infer permission
 // to delete the other local line merely because bytes now match this sibling.
 if(target in matching)return null
 fun line(id:String):List<String>? {
  val result=mutableListOf<String>();var at=id
  while(true) {
   if(at in result)return null
   result+=at
   val parents=graph.parentIds(graph.commits[at] ?: return null)
   if(parents.isEmpty())return result
   if(parents.size!=1)return null
   at=parents.single()
  }
 }
 val localLine=line(anchor) ?: return null
 val remoteLine=line(target) ?: return null
 val base=localLine.firstOrNull {it in remoteLine} ?: return null
 if(base==target)return null
 return CloudOverwriteSelection(base,target)
}
internal fun uploadNeedsOverwrite(graph:CommitGraph,baseline:String?,files:Map<String,String>):Boolean =
 graph.tips.size>1 || (graph.tips.isNotEmpty() && graph.tips.singleOrNull()!=baseline &&
  graph.commits.values.none {it.snapshot.files==files})

@Serializable internal data class DeletionReceipt(val id:String,val recordHash:String,val replacement:String,val game:String,val profile:String) {
 companion object {
  fun decode(bytes:ByteArray,id:String,game:String,profile:String):DeletionReceipt=json.decodeFromString<DeletionReceipt>(bytes.decodeToString()).also {
   require(it.game==game && it.profile==profile && it.id==id && validRecordId(id) && validContentHashId(it.recordHash) && validRecordId(it.replacement) && it.replacement!=id)
  }
 }
}
@Serializable internal data class OverwriteVictim(val id:String,val recordHash:String,val objectId:String)
@Serializable internal data class ConfirmedOverwritePlan(val base:String,val replacement:String,val victims:List<OverwriteVictim>,val objects:List<String>)
internal class ConfirmedBranchOverwrite(private val directory:Path,private val adapter:GameAdapter,private val profile:String) {
 private val marker=directory.resolve("pending.json")
 fun pending():ConfirmedOverwritePlan?=if(SafePaths.checked(marker).exists())json.decodeFromString<ConfirmedOverwritePlan>(SafePaths.boundedRead(marker,MAX_MANIFEST_BYTES).decodeToString()).also(::validate) else null
 private fun validate(p:ConfirmedOverwritePlan) {
  require(validRecordId(p.base)&&validRecordId(p.replacement)&&p.base!=p.replacement)
  require(p.victims.isNotEmpty()&&p.victims.size<=MAX_COMMITS&&p.victims.map{it.id}.distinct().size==p.victims.size)
  require(p.victims.all {validRecordId(it.id)&&it.id!=p.base&&it.id!=p.replacement&&validContentHashId(it.recordHash)&&validRecordId(it.objectId)})
  require(p.objects.distinct().size==p.objects.size&&p.objects.all {id->p.victims.any {it.objectId==id}})
 }
 fun prepare(store:AppendStore,graph:CommitGraph,base:String,selected:String,replacement:String):ConfirmedOverwritePlan {
  pending()?.let {require(it.base==base&&it.replacement==replacement);return it}
  graph.requireObjectsVerified();graph.requireSettled()
  require(base in graph.commits && replacement in graph.commits && selected in graph.commits && selected!=replacement)
  require(graph.descendsFrom(selected,base)&&graph.descendsFrom(replacement,base))
  // Only the confirmed selected line, never all descendants of B or unrelated branches.
  val ids=linkedSetOf<String>();var at=selected
  while(at!=base) {
   require(ids.add(at)&&at!=replacement)
   val parents=graph.parentIds(graph.commits.getValue(at));require(parents.size==1) {"Selected history is not a single replaceable line"};at=parents.single()
  }
  require(graph.commits.filterKeys {it !in ids}.values.none {graph.parentIds(it).any(ids::contains)}) {"A retained branch still depends on the selected history"}
  val retainedObjects=graph.commits.filterKeys {it !in ids}.values.map {it.snapshot.id}.toSet()
  val victims=ids.map {id->val bytes=requireNotNull(store.readCommit(id));require(json.decodeFromString<AppendCommit>(bytes.decodeToString())==graph.commits[id]);OverwriteVictim(id,hash(bytes),graph.commits.getValue(id).snapshot.id)}
  return ConfirmedOverwritePlan(base,replacement,victims,victims.map {it.objectId}.distinct().filter {it !in retainedObjects}).also {validate(it);SafePaths.atomicWrite(marker,json.encodeToString(it).encodeToByteArray())}
 }
 fun finish(store:AppendStore,plan:ConfirmedOverwritePlan,retained:CommitGraph,onDeleted:(String)->Unit,onObjectDeleted:(String)->Unit = {}) = store.withFreshReads {
  validate(plan);require(pending()==plan);retained.requireSettled()
  val winnerBytes=requireNotNull(store.readCommit(plan.replacement)) {"Replacement commit is not durable"}
  val winner=json.decodeFromString<AppendCommit>(winnerBytes.decodeToString());require(winner.id==plan.replacement && winner==retained.commits[plan.replacement] && winner.snapshot.profile==profile)
  val zip=requireNotNull(store.readObject(winner.snapshot.id));require(zip.size==winner.objectBytes);Snapshots.verify(Snapshot(winner.snapshot,zip),adapter)
  val ids=plan.victims.map {it.id}.toSet()
  require(retained.commits.filterKeys {it !in ids}.values.none {retained.parentIds(it).any(ids::contains)}) {"A retained branch depends on the confirmed deletion"}
  for(v in plan.victims) {
   if(Thread.currentThread().isInterrupted)throw InterruptedException()
   val bytes=store.readCommit(v.id)
   if(bytes!=null)require(hash(bytes)==v.recordHash) {"Confirmed record changed; deletion stopped"}
   val receipt=DeletionReceipt(v.id,v.recordHash,plan.replacement,adapter.id,profile)
   val existing=store.readDeletion(v.id)
   if(existing==null)store.writeDeletion(v.id,json.encodeToString(receipt).encodeToByteArray())
   require(DeletionReceipt.decode(requireNotNull(store.readDeletion(v.id)),v.id,adapter.id,profile)==receipt)
   if(bytes!=null)store.deleteCommit(v.id)
   require(store.readCommit(v.id)==null) {"Deletion is not confirmed"};onDeleted(v.id)
  }
  val referenced=retained.commits.filterKeys {it !in ids}.values.map {it.snapshot.id}.toSet()+winner.snapshot.id
  for(id in plan.objects.filter {it !in referenced}) {
   if(Thread.currentThread().isInterrupted)throw InterruptedException()
   store.deleteObject(id);require(store.readObject(id)==null) {"Object deletion is not confirmed"};onObjectDeleted(id)
  }
  SafePaths.checked(marker).deleteExisting()
 }
}
