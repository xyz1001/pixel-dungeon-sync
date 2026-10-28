package pdsync

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class NoHeadProbeTest {
 @TempDir lateinit var temp:Path
 @Test fun localScopeMaintenanceCostWithoutNetwork()=MockWebServer().use { server ->
  server.start()
  val store=AppendOnlyWebDav(server.url("/").toString(),"shattered","hero","fixture","fixture")
  val eager=mutableListOf<Long>();val lazy=mutableListOf<Long>()
  repeat(21) {round->
   var start=System.nanoTime()
   OperationObjectReadCache.open(temp.resolve("eager-$round")).close()
   eager+=(System.nanoTime()-start)/1000
   start=System.nanoTime()
   store.withReadScope(temp.resolve("lazy-$round")) {}
   lazy+=(System.nanoTime()-start)/1000
  }
  println("SCOPE_LOCAL_ONLY eager_median_us=${eager.drop(1).sorted()[10]} lazy_median_us=${lazy.drop(1).sorted()[10]} samples=20 network_requests=${server.requestCount}")
  assertEquals(0,server.requestCount)
 }
 @Test fun unusedReadScopeCreatesNoCacheFiles()=MockWebServer().use { server ->
  server.start()
  val store=AppendOnlyWebDav(server.url("/").toString(),"shattered","hero","fixture","fixture")
  val cache=temp.resolve("unused-cache")
  store.withReadScope(cache) {store.withReadScope(cache) {assertFalse(java.nio.file.Files.exists(cache))}}
  assertFalse(java.nio.file.Files.exists(cache));assertEquals(0,server.requestCount)
 }
 private fun listing()=MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>")
 @Test fun setupUsesOnlyCurrentListing()=MockWebServer().use { server ->
  server.enqueue(listing());server.start()
  val config=AppConfig(endpoint=server.url("/").toString(),profile="hero",username="fixture")
  val setup=ConnectionSetup(config).also {it.update(config,"fixture")}
  assertEquals(ConnectionTestResult.Success(false),setup.testConnection())
  assertEquals(1,server.requestCount)
  val request=server.takeRequest();assertEquals("PROPFIND",request.method)
  assertFalse(request.path!!.contains("head.json"))
 }
 @Test fun discoveryUsesOnlyCurrentListing()=MockWebServer().use { server ->
  server.enqueue(listing());server.start()
  val store=AppendOnlyWebDav(server.url("/").toString(),"shattered","hero","fixture","fixture")
  val repo=AppendRepository(temp,Shattered,"hero")
  assertTrue(repo.discoverMetadata(store).commits.isEmpty())
  assertEquals(1,server.requestCount);assertEquals("PROPFIND",server.takeRequest().method)
 }
 @Test fun currentListingFailureStillFailsSetup()=MockWebServer().use { server ->
  server.enqueue(MockResponse().setResponseCode(403));server.start()
  val config=AppConfig(endpoint=server.url("/").toString(),profile="hero",username="fixture")
  val setup=ConnectionSetup(config).also {it.update(config,"fixture")}
  assertEquals(403,assertIs<ConnectionTestResult.Failure>(setup.testConnection()).status)
  assertFalse(setup.canApply);assertEquals(1,server.requestCount)
 }
}
