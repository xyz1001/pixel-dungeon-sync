package pdsync

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DesktopSimpleSessionTest {
 @TempDir lateinit var temp:Path
 private class Memory:SimpleSaveStore {
  var tip:String?=null
  val nodes=linkedMapOf<String,SaveNode>();val objects=linkedMapOf<String,ByteArray>();val calls=mutableListOf<String>()
  var fail:String?=null
  private fun call(value:String) {calls+=value;if(fail==value)throw java.io.IOException(value)}
  override fun latest():String? {call("latest");return tip}
  override fun node(hashId:String):SaveNode? {call("node");return nodes[hashId]}
  override fun createObject(hashId:String,bytes:ByteArray):Boolean {call("object");if(hashId in objects)return false;objects[hashId]=bytes;return true}
  override fun createNode(node:SaveNode):Boolean {call("metadata");if(node.hashId in nodes)return false;nodes[node.hashId]=node;return true}
  override fun setLatest(hashId:String) {call("publish");tip=hashId}
  override fun objectBytes(hashId:String)=objects.getValue(hashId)
  override fun rename(node:SaveNode) {nodes[node.hashId]=node}
 }
 private val versions=object:ProfileVersions {
  override fun annotate(snapshot:Snapshot)=snapshot
  override fun target(path:String):TargetGame?=null
  override fun checkRestore(snapshot:Snapshot,targetPath:String)=Unit
 }
 private fun session(store:Memory):DesktopSimpleSession {
  val root=Files.createDirectory(temp.resolve("game"));Files.createDirectories(root.resolve("game1"))
  Files.writeString(root.resolve("game1/game.dat"),"A")
  return DesktopSimpleSession(AppConfig(saveRoot=root.toString()),temp.resolve("state"),versions=versions,suppliedStore=store)
 }
 private fun transport()=AppendOnlyWebDav("http://127.0.0.1:1","shattered","demo","","")
 private fun edit(session:DesktopSimpleSession,value:String)=Files.writeString(session.root.resolve("game1/game.dat"),value)
 private fun put(session:DesktopSimpleSession)=assertIs<AppendPushResult.Success>(assertIs<HomePushResult.Published>(session.pushFromHome(null,transport()){true}).result)

 @Test fun localHistoryUsesNewestIncludedFileTimeAndLocalDevice() {
  val store=Memory();val desktop=session(store)
  val game=desktop.root.resolve("game1/game.dat");val rankings=desktop.root.resolve("rankings.dat")
  Files.writeString(rankings,"ranking")
  Files.setLastModifiedTime(game,java.nio.file.attribute.FileTime.fromMillis(1700000000000))
  Files.setLastModifiedTime(rankings,java.nio.file.attribute.FileTime.fromMillis(1700000100000))
  put(desktop)
  val history=desktop.readHomeHistory(transport(),true).simple!!
  assertEquals("2023-11-14T22:15:00Z",history.localModifiedAt)
  assertEquals(desktop.deviceIdentity(),history.localDevice)
  Files.delete(game);Files.delete(rankings)
  assertNull(desktop.syncHistory().simple!!.localModifiedAt)
 }

 @Test fun namesAreExplicitAndScopedToContent() {
  val store=Memory();val desktop=session(store)
   val first=put(desktop).commit.id;assertEquals("",store.nodes.getValue(first).name)
   val operation=assertNotNull(store.nodes.getValue(first).operation)
   assertEquals(desktop.deviceIdentity(),operation.device)
   desktop.renameCurrent("Named ancestor"){true}
   assertEquals("Named ancestor",store.nodes.getValue(first).name)
   assertEquals(operation,store.nodes.getValue(first).operation)
  edit(desktop,"B");val second=put(desktop).commit.id
  assertEquals(first,store.nodes.getValue(second).parentId);assertEquals("",store.nodes.getValue(second).name)
  store.calls.clear();assertTrue(put(desktop).reused);assertEquals(2,store.nodes.size)
  desktop.renameHistory(second,"Named second",desktop.syncHistory())
  val reopened=DesktopSimpleSession(desktop.config,temp.resolve("state"),versions=versions,suppliedStore=store)
  val history=reopened.readHomeHistory(transport(),true).simple!!.nodes
  assertEquals(listOf("Named second","Named ancestor"),history.map {it.name})
 }

 @Test fun desktopAndAndroidProtocolUseSameObjectsParentsAndRestoreBytes() {
  val store=Memory();val desktop=session(store)
  val a=put(desktop).commit.id
  val mobile=SimpleSaveSync(store,temp.resolve("android/baseline"))
  mobile.acceptBaseline(a)
  val bBytes="B".encodeToByteArray()
  val root=Files.createDirectories(temp.resolve("mobile/game1"));Files.write(root.resolve("game.dat"),bBytes)
  val snapshot=Snapshots.capture(root.parent,Shattered,"demo","",null)
  val b=assertIs<SaveUploadResult.Complete>(mobile.upload({},{SaveUpload(snapshot.manifest.files,snapshot.zip,"B")})).node
  assertEquals(a,b.parentId)
  desktop.readHomeHistory(transport(),fullHistory=true)
  desktop.restoreCloudHistory(b.hashId,transport(),true,null){true}
  assertEquals("B",Files.readString(desktop.root.resolve("game1/game.dat")))
  edit(desktop,"C");val c=put(desktop).commit.id
  assertEquals(b.hashId,store.nodes.getValue(c).parentId)
  assertEquals(listOf(c,b.hashId,a),SimpleSaveProtocol.history(store,c).map {it.hashId})
  assertEquals("C",SimpleSaveProtocol.restoreEntries(store.objects.getValue(c),Shattered).getValue("game1/game.dat").decodeToString())
 }

 @Test fun restoredBranchConfirmationKeepsBParentAndRetainsOtherObjects() {
  val store=Memory();val desktop=session(store)
  val a=put(desktop).commit.id;edit(desktop,"B");val b=put(desktop).commit.id
  edit(desktop,"C");val c=put(desktop).commit.id;edit(desktop,"D");val d=put(desktop).commit.id
  desktop.readHomeHistory(transport(),true);desktop.restoreCloudHistory(b,transport(),true,null){true};edit(desktop,"E")
  val conflict=assertIs<AppendPushResult.Diverged>(assertIs<HomePushResult.Published>(desktop.pushFromHome(null,transport()){true}).result).conflict
  assertEquals(d,store.tip)
  store.calls.clear()
  val e=assertIs<AppendPushResult.Success>(desktop.overwriteCloudConflict(conflict,d,transport()){true}).commit.id
  assertEquals(listOf("latest","object","metadata","publish"),store.calls)
  assertEquals(listOf(e,b,a),desktop.syncHistory().simple!!.nodes.map {it.hashId})
  assertEquals(b,store.nodes.getValue(e).parentId)
  assertEquals(listOf(e,b,a),SimpleSaveProtocol.history(store,e).map {it.hashId})
  assertTrue(c in store.objects && d in store.objects)
 }

 @Test fun homeRequestCountIsConstantAndHistoryIsExplicit() {
  val store=Memory();val desktop=session(store)
  repeat(12) {edit(desktop,"""{"version":896,"init_ver":896,"turn":$it}""");put(desktop)}
  val reopened=DesktopSimpleSession(desktop.config,temp.resolve("state"),versions=versions,suppliedStore=store)
  store.calls.clear();assertEquals(1,reopened.readHomeHistory(transport()).simple!!.nodes.size)
  assertEquals(listOf("latest","node"),store.calls)
  assertEquals(12,reopened.readHomeHistory(transport(),true).simple!!.nodes.size)
  store.calls.clear();assertEquals(12,reopened.readHomeHistory(transport()).simple!!.nodes.size)
  assertEquals(listOf("latest","node"),store.calls)
 }

 @Test fun publicationFailureDoesNotAdvanceBaselineAndIdenticalDoesNotWrite() {
  val store=Memory();val desktop=session(store);val a=put(desktop).commit.id
  store.calls.clear();assertTrue(put(desktop).reused);assertEquals(listOf("latest","node"),store.calls)
  edit(desktop,"B");store.fail="publish"
  assertFailsWith<java.io.IOException>{put(desktop)}
  assertEquals(a,store.tip);assertEquals(a,desktop.syncHistory().baseline)
 }

 @Test fun sharedRestoreRejectsPathTraversal() {
  val out=java.io.ByteArrayOutputStream()
  java.util.zip.ZipOutputStream(out).use {it.putNextEntry(java.util.zip.ZipEntry("../game.dat"));it.write(1);it.closeEntry()}
  assertFailsWith<IllegalArgumentException>{SimpleSaveProtocol.restoreEntries(out.toByteArray(),Shattered)}
 }
 @Test fun latest404IsKnownEmptyButAuthenticationAndServiceFailureAreNotEmpty() {
  okhttp3.mockwebserver.MockWebServer().use {server->
   server.start()
   val root=Files.createDirectories(temp.resolve("http-game/game1"))
   Files.writeString(root.resolve("game.dat"),"""{"version":896,"init_ver":896,"turn":1}""")
   val desktop=DesktopSimpleSession(AppConfig(saveRoot=root.parent.toString()),temp.resolve("http-state"),versions=versions)
   val dav=AppendOnlyWebDav(server.url("/").toString(),"shattered","demo","user","fixture")
   server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))
   val empty=desktop.readHomeHistory(dav)
   assertNotNull(empty.simple);assertNull(empty.simple!!.latest);assertTrue(empty.simple!!.nodes.isEmpty())
   assertEquals("/shattered/demo/latest",server.takeRequest().path)
   for(status in listOf(401,503)) {
    server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(status))
    assertEquals(status,assertFailsWith<WebDavException>{desktop.readHomeHistory(dav)}.statusCode)
    assertEquals("GET",server.takeRequest().method)
   }
   assertEquals(3,server.requestCount)
  }
 }
}
