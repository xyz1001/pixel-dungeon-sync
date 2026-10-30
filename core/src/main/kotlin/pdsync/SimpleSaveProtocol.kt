package pdsync

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Common protocol operations. Platform adapters own file access, not cloud semantics. */
object SimpleSaveProtocol {
 fun history(store:SimpleSaveStore,latest:String?,known:List<SaveNode> = emptyList()):List<SaveNode> {
  val result=mutableListOf<SaveNode>();val seen=hashSetOf<String>();var next=latest
  val available=known.associateBy {it.hashId}
  while(next!=null) {
   require(seen.add(next) && seen.size<=MAX_COMMITS)
   val node=available[next] ?: requireNotNull(store.node(next)){"Save metadata is missing"}
   require(node.hashId==next)
   result+=node;next=node.parentId
  }
  return result
 }

 fun kind(files:Map<String,String>,baseline:String?,latest:String?):String {
  val id=files.takeIf {it.isNotEmpty()}?.let(::contentSha256)
  return when {id==null && latest==null->"BOTH_EMPTY";id==null->"REMOTE_ONLY";latest==null->"LOCAL_ONLY";id==latest->"IDENTICAL";baseline==latest->"DIFFERENT";else->"BRANCHED"}
 }

 /** Decode once, retaining the input/path/size boundary for both desktop and Root restore. */
 fun restoreEntries(zip:ByteArray,adapter:GameAdapter):Map<String,ByteArray> {
  require(zip.size<=MAX_SNAPSHOT_BYTES)
  val entries=linkedMapOf<String,ByteArray>();var total=0L
  ZipInputStream(zip.inputStream()).use {input->while(true) {
   val entry=input.nextEntry ?: break
   if(entry.isDirectory)continue
   require(safePath(entry.name) && adapter.includes(entry.name) && !isDirectoryMetadata(entry.name) && entry.name !in entries)
   val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
   while(true) {val count=input.read(buffer);if(count<0)break;total+=count;require(total<=MAX_SNAPSHOT_BYTES);output.write(buffer,0,count)}
   entries[entry.name]=output.toByteArray();require(entries.size<=MAX_SNAPSHOT_FILES)
  }}
  return entries
 }
}
