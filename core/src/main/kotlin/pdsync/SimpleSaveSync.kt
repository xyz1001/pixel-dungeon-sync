package pdsync

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** The complete wire metadata. Content identity uses the existing PDSYNC-CONTENT-V1 algorithm. */
@Serializable data class SaveNode(val hashId:String,val parentId:String?,val name:String)

interface SimpleSaveStore {
 fun latest():String?
 fun node(hashId:String):SaveNode?
 /** False means the content-addressed file already exists; never overwrite it. */
 fun createObject(hashId:String,bytes:ByteArray):Boolean
 fun createNode(node:SaveNode):Boolean
 fun setLatest(hashId:String)
 fun objectBytes(hashId:String):ByteArray
 fun rename(node:SaveNode) {error("Rename is unsupported")}
}

data class SaveUpload(val files:Map<String,String>,val zip:ByteArray,val name:String)
sealed interface SaveUploadResult {
 data class Complete(val node:SaveNode,val uploaded:Boolean):SaveUploadResult
 data class Conflict(val localHash:String,val latest:String):SaveUploadResult
}

/** One snapshot, one latest read, three ordered writes. No local save archive or verification markers. */
class SimpleSaveSync(private val store:SimpleSaveStore,private val baselineFile:Path) {
 fun baseline():String? = if(Files.exists(baselineFile))
  Files.readAllBytes(baselineFile).decodeToString().also {require(validContentHashId(it))} else null

 fun upload(checkAccess:()->Unit,capture:()->SaveUpload,confirmedLatest:String?=null,
            checkCanceled:()->Unit={}):SaveUploadResult {
  checkAccess();checkCanceled()
  val snapshot=capture()
  val id=contentSha256(snapshot.files)
  val parent=baseline()
  val latest=store.latest()?.also {require(validContentHashId(it))}
  if(id==latest) {
   val node=requireNotNull(store.node(id)) {"Current save metadata is missing"}
   saveBaseline(id)
   return SaveUploadResult.Complete(node,false)
  }
  if(parent!=latest && latest!=null && confirmedLatest!=latest)
   return SaveUploadResult.Conflict(id,latest)
  checkCanceled()
  val proposed=SaveNode(id,parent,snapshot.name)
  store.createObject(id,snapshot.zip)
  checkCanceled()
  val node=if(store.createNode(proposed))proposed else
   requireNotNull(store.node(id)) {"Existing save metadata is missing"}
  // Reusing an older content node preserves its original parent, preventing a cycle.
  require(node.hashId==id)
  checkCanceled();store.setLatest(id)
  saveBaseline(id)
  return SaveUploadResult.Complete(node,true)
 }
 fun acceptBaseline(id:String) {require(validContentHashId(id));saveBaseline(id)}
 private fun saveBaseline(id:String) {
  Files.createDirectories(baselineFile.parent)
  Files.write(baselineFile,id.encodeToByteArray())
 }
 companion object {
  fun encode(node:SaveNode):ByteArray = json.encodeToString(node).encodeToByteArray()
  fun decode(bytes:ByteArray):SaveNode = json.decodeFromString<SaveNode>(bytes.decodeToString()).also {
   require(validContentHashId(it.hashId) && (it.parentId==null || validContentHashId(it.parentId)))
   require(it.hashId!=it.parentId)
  }
 }
}
