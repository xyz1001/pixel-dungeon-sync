package pdsync

import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLException
import kotlin.test.*

class ConnectionDiagnosticsTest {
 @TempDir lateinit var temp:Path
 private fun events()=ZipInputStream(ByteArrayInputStream(DiagnosticLog.snapshotExport())).use { z ->
  var e=z.nextEntry;while(e!=null&&e.name!="events.jsonl")e=z.nextEntry
  check(e!=null);z.readBytes().decodeToString()
 }
 @Test fun whitelistNeverExportsInputAndUnknownStaysUnknown() {
  val patterns=mapOf("peer closed" to "PEER_CLOSED","ECONNRESET" to "RESET","SSL_read" to "READ_ERROR",
   "handshake failed" to "HANDSHAKE","wrong version number" to "PROTOCOL","PRIVATE_SECRET" to "UNKNOWN")
  for((message,expected) in patterns) {
   val kind=TransportFailureKind.messagePattern(SSLException("$message https://secret.invalid/token PRIVATE_SECRET"))
   assertEquals(expected,kind.name)
   val details=DiagnosticDetails.builder().messagePattern(kind).build()
   assertTrue(details.isValid());assertFalse(details.toString().contains("PRIVATE_SECRET"));assertFalse(details.toString().contains("secret.invalid"))
  }
  assertEquals(TransportFailureKind.MessagePattern.UNKNOWN,TransportFailureKind.messagePattern(SSLException("x".repeat(2048)+"handshake")))
  assertEquals(TransportFailureKind.MessagePattern.UNKNOWN,TransportFailureKind.messagePattern(object:SSLException("unused"){override val message:String get()=error("unavailable")}))
 }
 @Test fun handshakeFailureRemainsFirstEvidenceAfterCancellationWithoutInventingCompletedHandshake() {
  val client=OkHttpClient();val call=client.newCall(Request.Builder().url("https://secret.invalid/").build())
  var now=0L;val rows=mutableListOf<DiagnosticDetails>();var delegated=0
  val observer=ConnectionDiagnostics.Observer(object:EventListener(){
   override fun connectFailed(call:Call,address:InetSocketAddress,proxy:Proxy,protocol:Protocol?,ioe:IOException){delegated++}
  },DiagnosticDetails.NetworkTarget.DAV,1,{_,_,d->rows+=d},{now})
  observer.callStart(call);observer.connectStart(call,InetSocketAddress("127.0.0.1",1),Proxy.NO_PROXY)
  now=2_000_000;observer.secureConnectStart(call);now=12_000_000
  observer.connectFailed(call,InetSocketAddress("127.0.0.1",1),Proxy.NO_PROXY,null,SSLException("SSL_read PRIVATE_SECRET"))
  call.cancel();now=40_000_000;observer.callFailed(call,IOException("Canceled"))
  assertEquals(1,rows.size);assertEquals(1,delegated)
  val values=rows.single().values
  assertEquals("SECURE_CONNECT",values[DiagnosticDetails.Field.CONNECTION_STAGE])
  assertEquals("0",values[DiagnosticDetails.Field.SECURE_ENDS])
  assertEquals("10",values[DiagnosticDetails.Field.SECURE_ELAPSED_MS])
  assertEquals("false",values[DiagnosticDetails.Field.TRANSPORT_CANCELED])
  assertEquals("READ_ERROR",values[DiagnosticDetails.Field.TRANSPORT_MESSAGE_PATTERN])
  assertFalse(rows.toString().contains("PRIVATE_SECRET"))
  client.dispatcher.executorService.shutdown()
 }
 @Test fun actualCallsReportNewThenReusedConnectionAndRetainDelegateAndTrace() {
  DiagnosticLog.configure(temp,"diagnostic","desktop")
  MockWebServer().use { server ->
   server.start(InetAddress.getByName("127.0.0.1"),0)
   repeat(2){server.enqueue(MockResponse().setBody("fixture"))}
   var started=0;var ended=0
   val base=OkHttpClient.Builder().retryOnConnectionFailure(false).eventListener(object:EventListener(){
    override fun callStart(call:Call){started++}
    override fun callEnd(call:Call){ended++}
   }).build()
   val client=ConnectionDiagnostics.attach(base,DiagnosticDetails.NetworkTarget.DAV)
   val trace=DiagnosticLog.begin(DiagnosticAction.INSPECT,false)
   try {
    DiagnosticLog.bind(trace).use {
     repeat(2){client.newCall(Request.Builder().url(server.url("/PRIVATE_SECRET")).build()).execute().use {assertEquals("fixture",it.body!!.string())}}
    }
    assertEquals(2,started);assertEquals(2,ended);assertEquals(2,server.requestCount)
    val text=events()
    assertContains(text,"\"connectionRoute\":\"CONNECT_ATTEMPTED\"")
    assertContains(text,"\"connectionRoute\":\"REUSED\"")
    assertContains(text,"\"responseHeaderEvents\":\"1\"")
    assertContains(text,trace.id);assertFalse(text.contains("PRIVATE_SECRET"));assertFalse(text.contains("127.0.0.1"))
    assertFalse(text.contains("secureElapsedMs")) // HTTP has no measured TLS phase.
    assertEquals(base.retryOnConnectionFailure,client.retryOnConnectionFailure)
    assertSame(base.connectionPool,client.connectionPool);assertSame(base.dispatcher,client.dispatcher)
   } finally {client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
  }
 }
 @Test fun asynchronousCallsKeepTheirOwnOperationTraceAndUniqueCallId() {
  DiagnosticLog.configure(temp,"diagnostic","desktop")
  MockWebServer().use { server ->
   server.start(InetAddress.getByName("127.0.0.1"),0)
   repeat(4){server.enqueue(MockResponse().setBody("fixture"))}
   val client=ConnectionDiagnostics.attach(OkHttpClient.Builder().retryOnConnectionFailure(false).build(),DiagnosticDetails.NetworkTarget.DAV)
   val latch=java.util.concurrent.CountDownLatch(4)
   val errors=java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
   val traces=(1..4).map { DiagnosticLog.begin(DiagnosticAction.INSPECT,false) }
   try {
    for(trace in traces)DiagnosticLog.bind(trace).use {
     client.newCall(Request.Builder().url(server.url("/private")).build()).enqueue(object:Callback {
      override fun onFailure(call:Call,e:IOException){errors+=e;latch.countDown()}
      override fun onResponse(call:Call,response:Response){try {response.use{it.body!!.string()}}catch(t:Throwable){errors+=t}finally{latch.countDown()}}
     })
    }
    assertTrue(latch.await(10,java.util.concurrent.TimeUnit.SECONDS));assertTrue(errors.isEmpty())
    val rows=events().lineSequence().filter{it.contains("connectionCallId")}.toList()
    assertEquals(4,rows.size)
    for(trace in traces)assertEquals(1,rows.count{it.contains(trace.id)})
    assertEquals(4,rows.map{Regex("\"connectionCallId\":\"([0-9]+)\"").find(it)!!.groupValues[1]}.toSet().size)
   } finally {client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
  }
 }
 @Test fun negotiatedHttp2IsRecordedAsClosedProtocolToken() {
  val client=OkHttpClient();val call=client.newCall(Request.Builder().url("https://secret.invalid/").build())
  val rows=mutableListOf<DiagnosticDetails>()
  val observer=ConnectionDiagnostics.Observer(EventListener.NONE,DiagnosticDetails.NetworkTarget.DAV,1,{_,_,d->rows+=d})
  observer.callStart(call)
  observer.connectEnd(call,InetSocketAddress("127.0.0.1",1),Proxy.NO_PROXY,Protocol.HTTP_2)
  observer.callEnd(call)
  assertEquals("H2",rows.single().values[DiagnosticDetails.Field.CONNECTION_PROTOCOL])
  client.dispatcher.executorService.shutdown()
 }
}
