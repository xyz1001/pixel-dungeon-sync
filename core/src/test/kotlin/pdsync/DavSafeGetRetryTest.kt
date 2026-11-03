package pdsync

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DavSafeGetRetryTest {
 private val contentId = "ab".repeat(32)
 private val commitId = "11111111-1111-4111-8111-111111111111"

 private class Probe(private val onFailure: (Call) -> Unit = {}) : EventListener.Factory {
  val calls = Collections.synchronizedList(mutableListOf<Call>())
  val deadlines = Collections.synchronizedList(mutableListOf<Pair<Boolean, Long>>())
  val acquired = Collections.synchronizedList(mutableListOf<Connection>())
  val failures = Collections.synchronizedList(mutableListOf<IOException>())
  var responseBodyStartedWithOpenConnection = false
  override fun create(call: Call): EventListener = object : EventListener() {
   override fun callStart(call: Call) {
    calls.add(call)
    val timeout = call.timeout()
    deadlines.add(timeout.hasDeadline() to if(timeout.hasDeadline()) timeout.deadlineNanoTime() else 0L)
   }
   override fun connectionAcquired(call: Call, connection: Connection) { acquired.add(connection) }
   override fun responseBodyStart(call: Call) {
    responseBodyStartedWithOpenConnection = acquired.lastOrNull()?.socket()?.isClosed == false
   }
   override fun callFailed(call: Call, ioe: IOException) { failures.add(ioe); onFailure(call) }
  }
 }

 private fun fixtureClient(probe: Probe): OkHttpClient {
  val loopback=InetAddress.getByName("127.0.0.1")
  return OkHttpClient.Builder().dns(object:Dns { override fun lookup(hostname:String)=listOf(loopback) })
   .protocols(listOf(Protocol.HTTP_1_1)).eventListenerFactory(probe).build()
 }
 private fun fixtureStore(server:MockWebServer,client:OkHttpClient,gate:WebDavRequestCheck=WebDavRequestCheck {}):AppendOnlyWebDav =
  AppendOnlyWebDav(server.url("/dav/").toString(),"synthetic","probe","test","synthetic-only",client,gate)
 private fun start(server:MockWebServer) {server.start(InetAddress.getByName("127.0.0.1"),0)}
 private fun disconnect() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
 private fun requests(server:MockWebServer,vararg sequences:Int) {
  val requests=sequences.map { assertNotNull(server.takeRequest(1,TimeUnit.SECONDS)) }
  assertEquals(sequences.toList(),requests.map {it.sequenceNumber})
  assertTrue(requests.all {it.method=="GET"})
  assertEquals(null,server.takeRequest(1,TimeUnit.SECONDS))
 }
 private fun close(client:OkHttpClient) {client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
 private fun activeRegistrations(token:MetadataReadCancellation):Int {
  val field=MetadataReadCancellation::class.java.getDeclaredField("active")
  field.isAccessible=true
  return (field.get(token) as Set<*>).size
 }

 @Test fun freshAttemptFailureDoesNotCauseThirdAttempt() {
  val probe=Probe();val client=fixtureClient(probe)
  try {MockWebServer().use {server ->
   server.enqueue(MockResponse().setBody("warm"));server.enqueue(disconnect());server.enqueue(disconnect());start(server)
   val store=fixtureStore(server,client).simpleStore()
   assertContentEquals("warm".toByteArray(),store.objectBytes(contentId))
   assertFailsWith<IOException> {store.objectBytes(contentId)}
   assertEquals(3,probe.calls.size);assertEquals(2,probe.failures.size)
   assertEquals(3,probe.acquired.size);assertSame(probe.acquired[0],probe.acquired[1]);assertNotSame(probe.acquired[1],probe.acquired[2])
   assertEquals(3,server.requestCount);requests(server,0,1,0)
  }} finally {close(client)}
 }

 @Test fun freshResponseBodyTruncationDoesNotReplay() {
  val probe=Probe();val client=fixtureClient(probe)
  try {MockWebServer().use {server ->
   server.enqueue(MockResponse().setBody("warm"));server.enqueue(disconnect())
   server.enqueue(MockResponse().setBody("0123456789".repeat(1000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));start(server)
   val store=fixtureStore(server,client).simpleStore()
   assertContentEquals("warm".toByteArray(),store.objectBytes(contentId))
   probe.responseBodyStartedWithOpenConnection=false
   assertFailsWith<IOException> {store.objectBytes(contentId)}
   assertEquals(3,probe.calls.size);assertEquals(3,server.requestCount)
   assertEquals(3,probe.acquired.size);assertSame(probe.acquired[0],probe.acquired[1]);assertNotSame(probe.acquired[1],probe.acquired[2])
   assertTrue(probe.responseBodyStartedWithOpenConnection,"fresh response body must start on an open connection")
   requests(server,0,1,0)
  }} finally {close(client)}
 }

 @Test fun permissionRevokedAtFailurePreventsFreshRequest() {
  val revoked=AtomicBoolean(false);val probe=Probe {revoked.set(true)};val client=fixtureClient(probe)
  try {MockWebServer().use {server ->
   server.enqueue(MockResponse().setBody("warm"));server.enqueue(disconnect());server.enqueue(MockResponse().setBody("must-not-send"));start(server)
   val dav=fixtureStore(server,client,WebDavRequestCheck {if(revoked.get())throw IOException("synthetic denied")})
   val token=MetadataReadCancellation()
   assertContentEquals("warm".toByteArray(),dav.readCommit(commitId))
   assertFailsWith<IOException> {dav.readCommit(commitId,token)}
   assertEquals(0,activeRegistrations(token),"failed attempt registration must be released")
   assertTrue(revoked.get());assertEquals(2,probe.calls.size);assertEquals(2,server.requestCount);requests(server,0,1)
  }} finally {close(client)}
 }

 @Test fun cancellationAtFailurePreventsFreshRequest() {
  val cancellation=MetadataReadCancellation();val probe=Probe {cancellation.cancel()};val client=fixtureClient(probe)
  try {MockWebServer().use {server ->
   server.enqueue(MockResponse().setBody("warm"));server.enqueue(disconnect());server.enqueue(MockResponse().setBody("must-not-send"));start(server)
   val dav=fixtureStore(server,client)
   assertContentEquals("warm".toByteArray(),dav.readCommit(commitId))
   assertFailsWith<IOException> {dav.readCommit(commitId,cancellation)}
   assertFailsWith<CancellationException> {cancellation.checkActive()}
   assertEquals(2,probe.calls.size);assertEquals(2,server.requestCount);requests(server,0,1)
   assertTrue(probe.calls[1].isCanceled())
  }} finally {close(client)}
 }

 @Test fun interruptionAtFailurePreventsFreshRequestAndIsPreserved() {
  val probe=Probe {Thread.currentThread().interrupt()};val client=fixtureClient(probe)
  try {MockWebServer().use {server ->
   server.enqueue(MockResponse().setBody("warm"));server.enqueue(disconnect());server.enqueue(MockResponse().setBody("must-not-send"));start(server)
   val store=fixtureStore(server,client).simpleStore()
   assertContentEquals("warm".toByteArray(),store.objectBytes(contentId))
   try {
    assertFailsWith<IOException> {store.objectBytes(contentId)}
    assertTrue(Thread.currentThread().isInterrupted)
   } finally {Thread.interrupted()}
   assertEquals(2,probe.calls.size);assertEquals(2,server.requestCount);requests(server,0,1)
  }} finally {Thread.interrupted();close(client)}
 }

 @Test fun freshAttemptSharesAbsoluteDeadline() {
  val probe=Probe();val client=fixtureClient(probe)
  try {MockWebServer().use {server ->
   server.enqueue(MockResponse().setBody("warm"));server.enqueue(disconnect());server.enqueue(MockResponse().setBody("recovered"));start(server)
   val store=fixtureStore(server,client).simpleStore()
   assertContentEquals("warm".toByteArray(),store.objectBytes(contentId))
   assertContentEquals("recovered".toByteArray(),store.objectBytes(contentId))
   assertEquals(3,probe.deadlines.size)
   val first=probe.deadlines[1];val second=probe.deadlines[2]
   assertTrue(first.first);assertTrue(second.first);assertEquals(first.second,second.second)
   assertEquals(3,server.requestCount);requests(server,0,1,0)
  }} finally {close(client)}
 }

 private class Observed {
  var connectStarts = 0
  var requestHeaders = 0
  var responseHeaders = 0
  val connections = mutableListOf<Connection>()
  var failure: IOException? = null
  override fun toString() = "connectStarts=$connectStarts requestHeaders=$requestHeaders " +
   "responseHeaders=$responseHeaders acquisitions=${connections.size} failure=${failure?.javaClass?.simpleName}"
 }

 @Test fun reusedHttp11HeaderEofRetriesOnceOnFreshConnection() {
  val calls = Collections.synchronizedMap(IdentityHashMap<Call, Observed>())
  val order = Collections.synchronizedList(mutableListOf<Call>())
  val factory = EventListener.Factory { call ->
   val observed = Observed()
   calls[call] = observed
   order.add(call)
   object : EventListener() {
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { observed.connectStarts++ }
    override fun connectionAcquired(call: Call, connection: Connection) { observed.connections.add(connection) }
    override fun requestHeadersStart(call: Call) { observed.requestHeaders++ }
    override fun responseHeadersStart(call: Call) { observed.responseHeaders++ }
    override fun callFailed(call: Call, ioe: IOException) { observed.failure = ioe }
   }
  }
  val loopback = InetAddress.getByName("127.0.0.1")
  val client = OkHttpClient.Builder().dns(object : Dns {
   override fun lookup(hostname: String) = listOf(loopback)
  })
   .protocols(listOf(Protocol.HTTP_1_1)).eventListenerFactory(factory).build()
  try {
   MockWebServer().use { server ->
    server.enqueue(MockResponse().setBody("warm"))
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    server.enqueue(MockResponse().setBody("recovered"))
    server.start(loopback, 0)
    val dav = AppendOnlyWebDav(server.url("/dav/").toString(), "synthetic", "probe", "test", "synthetic-only", client)
    val simple = dav.simpleStore()
    val id = "ab".repeat(32)
    assertContentEquals("warm".toByteArray(), simple.objectBytes(id))
    val result = runCatching { simple.objectBytes(id) }

    val warm = assertNotNull(calls[order[0]])
    val failed = assertNotNull(calls[order[1]])
    val premise = "warm=[$warm], failed=[$failed]"
    assertEquals(1, warm.connections.size, premise)
    assertEquals(1, failed.connections.size, premise)
    assertSame(warm.connections.single(), failed.connections.single(), premise)
    assertEquals(0, failed.connectStarts, premise)
    assertEquals(1, failed.requestHeaders, premise)
    assertEquals(0, failed.responseHeaders, premise)
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = failed.failure
    var eof = false
    while (cause != null && seen.add(cause)) {
     if (cause is EOFException) eof = true
     cause = cause.cause
    }
    assertTrue(eof, "$premise; cause chain lacks EOFException")

    assertTrue(result.isSuccess, "$premise; expected recovered object but got ${result.exceptionOrNull()?.javaClass?.simpleName}")
    assertContentEquals("recovered".toByteArray(), result.getOrThrow())
    assertEquals(3, order.size)
    assertEquals(3, server.requestCount)
    val records = List(3) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
    assertEquals(listOf("GET", "GET", "GET"), records.map { it.method })
    assertEquals(listOf(0, 1, 0), records.map { it.sequenceNumber })
    val fresh = assertNotNull(calls[order[2]])
    assertEquals(1, fresh.connections.size)
    assertNotSame(warm.connections.single(), fresh.connections.single())
    assertEquals(1, fresh.connectStarts)
    assertEquals(null, server.takeRequest(1, TimeUnit.SECONDS))
   }
  } finally {
   client.connectionPool.evictAll()
   client.dispatcher.executorService.shutdown()
  }
 }
}
