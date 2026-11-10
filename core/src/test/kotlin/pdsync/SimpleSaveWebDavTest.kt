package pdsync

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class SimpleSaveWebDavTest {
 @TempDir lateinit var root:Path
 @Test fun renamePutAndFreshGetPreserveIdentityAndFailureThrows() {
  MockWebServer().use {server->
   server.start()
   val store=AppendOnlyWebDav(server.url("/").toString(),"game","profile","user","test").simpleStore()
   val ancestor=SaveNode("a".repeat(64),null,"Old")
   val latest=SaveNode("b".repeat(64),ancestor.hashId,"Old latest")
   for(node in listOf(ancestor,latest)) {
    val renamed=node.copy(name="New ${node.hashId.first()}")
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(MockResponse().setResponseCode(200).setBody(SimpleSaveSync.encode(renamed).decodeToString()))
    store.rename(renamed);assertEquals(renamed,store.node(node.hashId))
    val put=server.takeRequest();val get=server.takeRequest()
    assertEquals("PUT",put.method);assertEquals("GET",get.method)
    assertEquals("/game/profile/saves/${node.hashId}.json",put.path);assertEquals(put.path,get.path)
    assertEquals(renamed,SimpleSaveSync.decode(put.body.readByteArray()))
   }
   server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After","0"))
   assertFailsWith<WebDavException> {store.rename(latest.copy(name="Failure"))}
   assertEquals("PUT",server.takeRequest().method);assertEquals(5,server.requestCount)
  }
 }
 @Test fun normalUploadUsesLatestGetAndThreePutsWithoutReadback() {
  MockWebServer().use {server->
   server.start();server.enqueue(MockResponse().setResponseCode(404))
   repeat(3){server.enqueue(MockResponse().setResponseCode(201))}
   val store=AppendOnlyWebDav(server.url("/").toString(),"game","profile","user","test").simpleStore()
   val snapshot=SaveUpload(mapOf("game1/game.dat" to hash(byteArrayOf(1))),byteArrayOf(4,5),"name")
   val result=SimpleSaveSync(store,root.resolve("baseline")).upload({},{snapshot}) as SaveUploadResult.Complete
   val requests=List(4){server.takeRequest()};val id=result.node.hashId
   assertEquals(listOf("GET","PUT","PUT","PUT"),requests.map {it.method})
   assertEquals(listOf("/game/profile/latest","/game/profile/saves/$id.zip","/game/profile/saves/$id.json","/game/profile/latest"),requests.map {it.path})
   assertEquals("*",requests[1].getHeader("If-None-Match"));assertEquals("*",requests[2].getHeader("If-None-Match"))
   assertNull(requests[3].getHeader("If-None-Match"));assertEquals(id,requests[3].body.readUtf8())
   assertEquals(4,server.requestCount)
  }
 }
 @Test fun failedLatestIsNotAutomaticallyReplayed() {
  MockWebServer().use {server->
   server.start();server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After","0"))
   val store=AppendOnlyWebDav(server.url("/").toString(),"game","profile","user","test").simpleStore()
   assertFailsWith<WebDavException>{store.setLatest("a".repeat(64))}
   assertEquals(1,server.requestCount)
  }
 }
}
