package pdsync

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test
import kotlin.test.*

class ConfirmedDeletionWebDavTest {
 @Test fun deletionIsScopedIdempotentAndNeverReplayed() = MockWebServer().use { server ->
  server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
  val id="a".repeat(64)
  val one=AppendOnlyWebDav(server.url("/root/").toString(),Shattered.id,"one","synthetic","test")
  val two=AppendOnlyWebDav(server.url("/root/").toString(),Shattered.id,"two","synthetic","test")
  server.enqueue(MockResponse().setResponseCode(204));one.deleteCommit(id)
  val first=server.takeRequest();assertEquals("DELETE",first.method);assertTrue(first.path!!.contains("/one/"));assertTrue(first.path!!.endsWith("pdsync-v3-commit-$id.json"))
  server.enqueue(MockResponse().setResponseCode(404));two.deleteObject(id)
  val second=server.takeRequest();assertEquals("DELETE",second.method);assertTrue(second.path!!.contains("/two/"));assertFalse(second.path!!.contains("/one/"))
  server.enqueue(MockResponse().setResponseCode(503));assertFails {one.deleteCommit(id)}
  assertEquals(3,server.requestCount)
  for(bad in listOf("../outside","/absolute",id+"/child",""))assertFails {one.deleteObject(bad)}
  assertEquals(3,server.requestCount)
 }
}
