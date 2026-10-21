package pdsync

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class WebDavNetworkDiagnosticsTest {
 @TempDir lateinit var temp: Path
 private val id = "ab".repeat(32)
 private val secret = "https://private.invalid/path?password=sentinel-secret Authorization: private-token"
 private fun store(base: String, client: OkHttpClient) = AppendOnlyWebDav(base, "shattered", "hero", "synthetic", "test-only", client)

 @Test fun diagnosticCategoriesNeverRetainTransportTextOrChangeExceptionClassification() {
  val errors = listOf(
   SocketTimeoutException(secret) to "TIMEOUT", UnknownHostException(secret) to "DNS",
   ConnectException(secret) to "CONNECT", SSLHandshakeException(secret) to "TLS",
   SSLException(secret) to "TLS", EOFException(secret) to "EOF", SocketException(secret) to "CONNECTION",
   InterruptedIOException(secret) to "INTERRUPTED", IOException(secret) to "IO",
   // Do not inspect causes or infer meaning from arbitrary transport messages.
   IOException(secret, SocketTimeoutException(secret)) to "IO", IOException("timeout $secret") to "IO"
  )
  for ((input, category) in errors) {
   input.addSuppressed(IOException(secret))
   var calls = 0
   val client = OkHttpClient.Builder().addInterceptor { calls++; throw input }.build()
   val output = assertFailsWith<IOException> { store("https://example.invalid/", client).readCommit(id) }
   assertEquals(IOException::class.java, output.javaClass) // Desktop's retry policy still sees OTHER.
   assertEquals("WebDAV network request failed [$category]", output.message)
   assertNull(output.cause); assertTrue(output.suppressed.isEmpty()); assertEquals(1, calls)
   val diagnostic = output.stackTraceToString()
   for (marker in listOf("private.invalid", "sentinel-secret", "Authorization", "private-token"))
    assertFalse(diagnostic.contains(marker))
   assertEquals(if (category == "TIMEOUT") "连接超时，请重试。" else "网络连接失败，请重试。",
    NoviceCopy.feedback(output.message!!, "fallback"))
  }
 }

 @Test fun httpAndNamespaceFailuresRemainTheOriginalExceptions() {
  for (status in listOf(401, 403, 429, 503, 507)) {
   val input = WebDavException(status)
   val client = OkHttpClient.Builder().addInterceptor { throw input }.build()
   assertSame(input, assertFailsWith<WebDavException> { store("https://example.invalid/", client).readCommit(id) })
  }
  val input = NamespaceProvisioningException(NamespaceFailureReason.FORBIDDEN, 403)
  val client = OkHttpClient.Builder().addInterceptor { throw input }.build()
  assertSame(input, assertFailsWith<NamespaceProvisioningException> { store("https://example.invalid/", client).ensureNamespace() })
 }

 @Test fun acceptedPutDisconnectDoesNotEnableTransportRetry() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)); server.start()
  val bytes = "frozen-test-object".encodeToByteArray()
  val client = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
  val error = assertFailsWith<IOException> { store(server.url("/").toString(), client).writeObject(id, bytes) }
  assertEquals(IOException::class.java, error.javaClass)
  assertEquals(1, server.requestCount)
  val request = server.takeRequest(); assertEquals("PUT", request.method)
  assertEquals("/shattered/hero/pdsync-v3-object-$id.zip", request.path)
  assertContentEquals(bytes, request.body.readByteArray())
 }

 @Test fun sanitizedTransportFailureRetainsFrozenPlanAcrossExplicitRestartAndRetry() = MockWebServer().use { server ->
  val remote = ConcurrentHashMap<String, ByteArray>(); val puts = CopyOnWriteArrayList<String>()
  val failFirstObject = AtomicBoolean(true); val failReadback = AtomicBoolean(false)
  server.dispatcher = object : Dispatcher() {
   override fun dispatch(request: RecordedRequest): MockResponse {
    val path = request.path!!
    return when (request.method) {
     "GET" -> remote[path]?.let { MockResponse().setBody(okio.Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
     "PUT" -> {
      puts += path; remote[path] = request.body.readByteArray()
      if (path.endsWith(".zip") && failFirstObject.getAndSet(false)) {
       failReadback.set(true); MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
      } else MockResponse().setResponseCode(201)
     }
     "PROPFIND" -> MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:">${remote.keys.filter { it.endsWith(".json") }.joinToString("") { "<d:response><d:href>$it</d:href><d:status>HTTP/1.1 200 OK</d:status></d:response>" }}</d:multistatus>""")
     else -> MockResponse().setResponseCode(405)
    }
   }
  }
  server.start()
  val client = OkHttpClient.Builder().addInterceptor { chain ->
   if (chain.request().method == "GET" && chain.request().url.encodedPath.endsWith(".zip") && failReadback.getAndSet(false))
    throw SocketTimeoutException(secret)
   chain.proceed(chain.request())
  }.build()
  val dav = store(server.url("/").toString(), client)
  val root = temp.resolve("save").createDirectories(); root.resolve("game1").createDirectories()
  root.resolve("game1/game.dat").writeText("""{"version":922,"init_ver":922,"marker":"original"}""")
  val source = Snapshots.capture(root, Shattered, "hero", "4.0.2", null)
  val state = temp.resolve("state"); val repo = AppendRepository(state, Shattered, "hero")
  val frozen = repo.prepare(source, emptyList())
  assertFailsWith<IOException> { repo.publish(dav, frozen) }
  assertEquals(frozen.commit, repo.pending()!!.commit); assertContentEquals(frozen.zip, repo.pending()!!.zip)
  assertEquals(1, puts.size); assertTrue(remote.keys.none { it.endsWith(".json") })
  source.zip.fill(0); root.resolve("game1/game.dat").writeText("later-local-change")
  val restarted = AppendRepository(state, Shattered, "hero")
  assertEquals(frozen.commit, restarted.publish(dav, restarted.pending()!!))
  assertContentEquals(frozen.zip, remote["/shattered/hero/pdsync-v3-object-${frozen.commit.snapshot.id}.zip"])
  assertContentEquals(frozen.manifestBytes, remote["/shattered/hero/pdsync-v3-commit-${frozen.commit.id}.json"])
  assertEquals(2, puts.size) // Accepted object was read back, not uploaded a second time.
  assertEquals(frozen.commit, restarted.publish(dav, restarted.pending()!!)); assertEquals(2, puts.size)
 }
}
