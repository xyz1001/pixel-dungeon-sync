package pdsync

import java.nio.file.Path
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class SimpleSaveSyncTest {
 @TempDir lateinit var root:Path
 private class Store:SimpleSaveStore {
  var tip:String?=null;val nodes=mutableMapOf<String,SaveNode>();val objects=mutableMapOf<String,ByteArray>()
  val calls=mutableListOf<String>();var fail:String?=null
  private fun call(s:String) {calls+=s;if(fail==s)throw java.io.IOException(s)}
  override fun latest():String? {call("latest GET");return tip}
  override fun node(hashId:String):SaveNode? {call("metadata GET");return nodes[hashId]}
  override fun createObject(hashId:String,bytes:ByteArray):Boolean {call("object PUT");if(hashId in objects)return false;objects[hashId]=bytes;return true}
  override fun createNode(node:SaveNode):Boolean {call("metadata PUT");if(node.hashId in nodes)return false;nodes[node.hashId]=node;return true}
  override fun setLatest(hashId:String) {call("latest PUT");tip=hashId}
  override fun objectBytes(hashId:String)=objects.getValue(hashId)
 }
 private fun snapshot(n:Int)=SaveUpload(mapOf("game1/game.dat" to hash("turn$n".encodeToByteArray())),byteArrayOf(n.toByte()),"武僧")
 @Test fun exactlyThreeFieldsAndStableIdentity() {
  val s=snapshot(1);val id=contentSha256(s.files);val node=SaveNode(id,null,s.name)
  assertEquals(setOf("hashId","parentId","name"),json.parseToJsonElement(SimpleSaveSync.encode(node).decodeToString()).jsonObject.keys)
  assertEquals(node,SimpleSaveSync.decode(SimpleSaveSync.encode(node)))
  assertEquals(id,contentSha256(s.copy(zip=byteArrayOf(99)).files))
 }
 @Test fun appendOrdersWritesAndSavesOnlyBaseline() {
  val store=Store();val file=root.resolve("baseline");val sync=SimpleSaveSync(store,file);var captures=0;var permissions=0
  val first=sync.upload({permissions++},{captures++;snapshot(1)}) as SaveUploadResult.Complete
  assertEquals(listOf("latest GET","object PUT","metadata PUT","latest PUT"),store.calls)
  assertEquals(first.node.hashId,Files.readString(file));assertEquals(1,captures);assertEquals(1,permissions)
  store.calls.clear();val second=sync.upload({},{snapshot(2)}) as SaveUploadResult.Complete
  assertEquals(first.node.hashId,second.node.parentId)
  assertEquals(1,Files.list(root).use {it.count()}.toInt())
 }
 @Test fun sameContentSkipsAllWrites() {
  val store=Store();val sync=SimpleSaveSync(store,root.resolve("baseline"));sync.upload({},{snapshot(1)});store.calls.clear()
  assertFalse((sync.upload({},{snapshot(1)}) as SaveUploadResult.Complete).uploaded)
  assertEquals(listOf("latest GET","metadata GET"),store.calls)
 }
 @Test fun conflictRequiresExactConfirmationAndRetainsOldNodes() {
  val store=Store();val a=SimpleSaveSync(store,root.resolve("a"));val b=SimpleSaveSync(store,root.resolve("b"))
  val first=a.upload({},{snapshot(1)}) as SaveUploadResult.Complete;store.calls.clear()
  assertIs<SaveUploadResult.Conflict>(b.upload({},{snapshot(2)}));assertEquals(listOf("latest GET"),store.calls)
  val second=b.upload({},{snapshot(2)},first.node.hashId) as SaveUploadResult.Complete
  assertNull(second.node.parentId);assertEquals(2,store.nodes.size)
 }
 @Test fun oldContentReusesOriginalParentWithoutCycle() {
  val store=Store();val sync=SimpleSaveSync(store,root.resolve("baseline"))
  val first=sync.upload({},{snapshot(1)}) as SaveUploadResult.Complete
  sync.upload({},{snapshot(2)})
  val reused=sync.upload({},{snapshot(1)}) as SaveUploadResult.Complete
  assertEquals(first.node,reused.node);assertNull(reused.node.parentId);assertEquals(2,store.nodes.size)
 }
 @Test fun eachWriteFailureNeverAdvancesBaselineOrClaimsSuccess() {
  for(stage in listOf("object PUT","metadata PUT","latest PUT")) {
   val store=Store();val file=root.resolve(stage.replace(' ','_'));val sync=SimpleSaveSync(store,file)
   val first=sync.upload({},{snapshot(1)}) as SaveUploadResult.Complete;store.fail=stage
   assertFailsWith<java.io.IOException>{sync.upload({},{snapshot(2)})}
   assertEquals(first.node.hashId,Files.readString(file));assertEquals(first.node.hashId,store.tip)
  }
 }
 @Test fun accessFailureAndCancellationDoNotPublish() {
  val store=Store();val sync=SimpleSaveSync(store,root.resolve("baseline"))
  assertFailsWith<IllegalStateException>{sync.upload({error("denied")},{snapshot(1)})};assertTrue(store.calls.isEmpty())
  var checks=0
  assertFailsWith<InterruptedException>{sync.upload({},{snapshot(1)},checkCanceled={if(++checks==3)throw InterruptedException()})}
  assertEquals(listOf("latest GET","object PUT"),store.calls);assertNull(store.tip);assertNull(sync.baseline())
 }

 @Test fun restoredBranchKeepsRealParentAfterConfirmedOverwrite() {
  val store=Store();val sync=SimpleSaveSync(store,root.resolve("branch"))
  fun put(n:Int, confirmed:String?=null) = sync.upload({},{snapshot(n)},confirmed) as SaveUploadResult.Complete
  val a=put(1).node; val b=put(2).node; val c=put(3).node; val d=put(4).node
  sync.acceptBaseline(b.hashId)
  assertIs<SaveUploadResult.Conflict>(sync.upload({},{snapshot(5)}))
  assertEquals(d.hashId,store.tip);assertEquals(b.hashId,sync.baseline())
  val e=put(5,d.hashId).node
  assertEquals(b.hashId,e.parentId);assertEquals(e.hashId,store.tip)
  assertEquals(c,store.nodes[c.hashId]);assertEquals(d,store.nodes[d.hashId])
  val f=put(6).node
  val chain=generateSequence(f){it.parentId?.let(store.nodes::get)}.map{it.hashId}.toList()
  assertEquals(listOf(f.hashId,e.hashId,b.hashId,a.hashId),chain)
 }
}
