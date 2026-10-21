package pdsync

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*
import kotlin.test.*

/** Newly reconstructed loopback transport checks; no device or external server is used. */
class ReconstructedWebDavReadScopeTest {
 @TempDir lateinit var cacheRoot: Path
 private val id = "ab".repeat(32)
 private fun store(server: MockWebServer, user: String = "fixture",
  check: WebDavRequestCheck = WebDavRequestCheck {}): AppendOnlyWebDav =
  AppendOnlyWebDav(server.url("/dav/").toString(), "shattered", "hero", user, "fixture-value",
   OkHttpClient(), check)
 private fun response(bytes: ByteArray) = MockResponse().setBody(okio.Buffer().write(bytes))

 @Test fun nestedOperationFreezesObjectBytesButNextOperationReadsAgain() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setBody("first")); server.enqueue(MockResponse().setBody("next"))
  server.start(); val dav = store(server)
  dav.withReadScope(cacheRoot) {
   val first = assertNotNull(dav.readObject(id)); first[0] = 0
   assertEquals("first", dav.readObject(id)!!.decodeToString())
   dav.withReadScope(cacheRoot.resolve("ignored-nested")) {
    assertEquals("first", dav.readObject(id)!!.decodeToString())
   }
   assertEquals(1, server.requestCount)
  }
  dav.withReadScope(cacheRoot) { assertEquals("next", dav.readObject(id)!!.decodeToString()) }
  assertEquals(2, server.requestCount)
  assertTrue(Files.list(cacheRoot).use { it.noneMatch { path -> Files.isDirectory(path) } })
 }

 @Test fun freshReadsBypassFrozenBytesAndMissingFreshResultEvicts() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setBody("frozen")); server.enqueue(MockResponse().setBody("fresh"))
  server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setBody("after-missing"))
  server.start(); val dav = store(server)
  dav.withReadScope(cacheRoot) {
   assertEquals("frozen", dav.readObject(id)!!.decodeToString())
   dav.withFreshReads { dav.withFreshReads { assertEquals("fresh", dav.readObject(id)!!.decodeToString()) } }
   assertEquals("frozen", dav.readObject(id)!!.decodeToString())
   dav.withFreshReads { assertNull(dav.readObject(id)) }
   assertEquals("after-missing", dav.readObject(id)!!.decodeToString())
  }
  assertEquals(4, server.requestCount)
 }

 @Test fun commitsListingsAndPermissionChecksRemainFresh() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setBody("object"))
  repeat(2) { server.enqueue(MockResponse().setBody("commit")) }
  repeat(2) { server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"/>""")) }
  server.start(); var permitted = true; var checks = 0
  val dav = store(server, check = WebDavRequestCheck { checks++; if (!permitted) throw IOException("fixture denied") })
  dav.withReadScope(cacheRoot) {
   dav.readObject(id)
   val before = checks; dav.readObject(id); assertEquals(before + 1, checks)
   repeat(2) { assertNotNull(dav.readCommit(id)) }
   repeat(2) { assertTrue(dav.listCommits().ids.isEmpty()) }
   permitted = false
   assertFailsWith<IOException> { dav.readObject(id) }
  }
  assertEquals(5, server.requestCount)
 }

 @Test fun firstReadErrorsAreNotCachedAndCorruptedDiskBytesRefetch() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setResponseCode(401))
  server.enqueue(MockResponse().setBody("first")); server.enqueue(MockResponse().setBody("again"))
  server.start(); val dav = store(server)
  dav.withReadScope(cacheRoot) {
   assertEquals(401, assertFailsWith<WebDavException> { dav.readObject(id) }.statusCode)
   assertEquals("first", dav.readObject(id)!!.decodeToString())
   val bin = Files.walk(cacheRoot).use { it.filter { path -> path.name.endsWith(".bin") }.toList().single() }
   bin.writeText("wrong")
   assertEquals("again", dav.readObject(id)!!.decodeToString())
  }
  assertEquals(3, server.requestCount)
 }

 @Test fun largeObjectUsesDiskOnceWithinScope() = MockWebServer().use { server ->
  val bytes = ByteArray(8 * 1024 * 1024 + 1) { (it % 251).toByte() }
  server.enqueue(response(bytes)); server.start(); val dav = store(server)
  dav.withReadScope(cacheRoot) {
   assertContentEquals(bytes, dav.readObject(id)); assertContentEquals(bytes, dav.readObject(id))
  }
  assertEquals(1, server.requestCount)
 }

 @Test fun putInvalidatesFrozenReadEvenWhenPutFails() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setBody("before")); server.enqueue(MockResponse().setResponseCode(500))
  server.enqueue(MockResponse().setBody("after")); server.start(); val dav = store(server)
  dav.withReadScope(cacheRoot) {
   assertEquals("before", dav.readObject(id)!!.decodeToString())
   assertEquals(500, assertFailsWith<WebDavException> { dav.writeObject(id, byteArrayOf(1)) }.statusCode)
   assertEquals("after", dav.readObject(id)!!.decodeToString())
  }
  assertEquals(3, server.requestCount)
 }

 @Test fun accountInstancesAndWorkerThreadsNeverShareFrozenEntries() = MockWebServer().use { server ->
  val requests = AtomicInteger()
  val authA = Credentials.basic("account-a", "fixture-value", StandardCharsets.UTF_8)
  server.dispatcher = object : Dispatcher() {
   override fun dispatch(request: RecordedRequest): MockResponse {
    requests.incrementAndGet()
    return MockResponse().setBody(if (request.getHeader("Authorization") == authA) "account-a" else "account-b")
   }
  }
  server.start(); val a = store(server, "account-a"); val b = store(server, "account-b")
  val executor = Executors.newSingleThreadExecutor()
  try {
   a.withReadScope(cacheRoot) {
    assertEquals("account-a", a.readObject(id)!!.decodeToString())
    b.withReadScope(cacheRoot) { assertEquals("account-b", b.readObject(id)!!.decodeToString()) }
    assertEquals("account-a", executor.submit(Callable {
     a.withReadScope(cacheRoot) { a.readObject(id)!!.decodeToString() }
    }).get(5, TimeUnit.SECONDS))
    assertEquals("account-a", a.readObject(id)!!.decodeToString())
   }
  } finally { executor.shutdownNow() }
  assertEquals(3, requests.get())
 }

 @Test fun unavailableDiskRootFallsBackToOrdinaryNetworkReads() = MockWebServer().use { server ->
  val badRoot = cacheRoot.resolve("file").also { it.writeText("keep") }
  server.enqueue(MockResponse().setBody("one")); server.enqueue(MockResponse().setBody("two"))
  server.start(); val dav = store(server)
  dav.withReadScope(badRoot) {
   assertEquals("one", dav.readObject(id)!!.decodeToString())
   assertEquals("two", dav.readObject(id)!!.decodeToString())
  }
  assertEquals(2, server.requestCount); assertEquals("keep", badRoot.readText())
 }

 @Test fun metadataCancellationOwnsCallUntilResponseBodyIsConsumed() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setBody("x".repeat(1024)).throttleBody(1, 1, TimeUnit.SECONDS))
  server.start(); val dav = store(server); val cancellation = MetadataReadCancellation()
  val executor = Executors.newSingleThreadExecutor()
  try {
   val result = executor.submit(Callable { runCatching { dav.readCommit(id, cancellation) } })
   assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
   cancellation.cancel()
   assertTrue(result.get(5, TimeUnit.SECONDS).isFailure)
  } finally { executor.shutdownNow() }
 }
}
