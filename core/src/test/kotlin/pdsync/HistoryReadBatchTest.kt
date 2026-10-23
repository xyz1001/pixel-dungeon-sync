package pdsync

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class HistoryReadBatchTest {
 @TempDir lateinit var root:Path
 private val ids=(1..4).map {it.toString(16).padStart(64,'0')}

 @Test fun chunkedBodyCannotExceedValidatedManifestSize()=MockWebServer().use {server->
  server.enqueue(MockResponse().setChunkedBody("oversized",2))
  server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
  val dav=AppendOnlyWebDav(server.url("/dav/").toString(),"synthetic","history","","")
  assertFails {dav.readHistoryObjects(listOf(ids[0]),MetadataReadCancellation(),mapOf(ids[0] to 3))}
  assertEquals(1,server.requestCount)
  assertTrue(Thread.getAllStackTraces().keys.none {it.isAlive && it.name=="pdsync-history-read"})
 }

 @Test fun delayedFreshBodiesRunBoundedAndKeepTraceAndOwnerCache()=MockWebServer().use {server->
  server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=MockResponse().setBody("body").setBodyDelay(200,TimeUnit.MILLISECONDS)}
  server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
  val active=AtomicInteger();val maximum=AtomicInteger()
  val client=OkHttpClient.Builder().eventListener(object:okhttp3.EventListener(){
   override fun callStart(call:okhttp3.Call){val count=active.incrementAndGet();maximum.updateAndGet {maxOf(it,count)}}
   override fun callEnd(call:okhttp3.Call){active.decrementAndGet()}
   override fun callFailed(call:okhttp3.Call,e:java.io.IOException){active.decrementAndGet()}
  }).build()
  val dav=AppendOnlyWebDav(server.url("/dav/").toString(),"synthetic","history","","",client)
  DiagnosticLog.configure(root,"test","android")
  val trace=DiagnosticLog.begin(DiagnosticAction.PUSH,false)
  DiagnosticLog.bind(trace).use {
   dav.withReadScope(root.resolve("cache")) {
    val start=System.nanoTime()
    ids.forEach {dav.readObject(it)}
    val serial=(System.nanoTime()-start)/1_000_000
    val parallelStart=System.nanoTime()
    dav.withFreshReads {assertEquals(ids,dav.readHistoryObjects(ids,MetadataReadCancellation()).keys.toList())}
    val parallel=(System.nanoTime()-parallelStart)/1_000_000
    println("HISTORY108_BODY_LATENCY serial_ms=$serial parallel_ms=$parallel requests=${server.requestCount}")
    assertTrue(parallel<serial*0.8,"fresh parallel body reads must overlap: $serial -> $parallel")
    assertEquals(8,server.requestCount,"fresh scope must not reuse owner cache")
    dav.readHistoryObjects(ids,MetadataReadCancellation())
    assertEquals(8,server.requestCount,"owner cache survives fresh worker reads")
   }
  }
  assertTrue(maximum.get() in 2..4)
  assertEquals(4L,trace.transfers.snapshot().getValue(DiagnosticTransferKind.HISTORY_OBJECT_GET).count)
  assertTrue(trace.timings.snapshot().getValue(DiagnosticPhase.HISTORY_OBJECT_READ).durationMs>=150)
  assertEquals(0,active.get())
  client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
 }

 @Test fun firstFailureCancelsOtherCallsAndJoinsEveryWorkerBeforeReturning() {
  val entered=CountDownLatch(4);val active=AtomicInteger();val exited=AtomicInteger()
  val cancellation=MetadataReadCancellation()
  assertFailsWith<IllegalStateException> {
   historyReadBatch(ids,cancellation) {id->
    active.incrementAndGet();entered.countDown()
    try {
     assertTrue(entered.await(5,TimeUnit.SECONDS))
     if(id==ids[0])throw IllegalStateException("synthetic failure")
     val stop=CountDownLatch(1)
     cancellation.register {stop.countDown()}.use {stop.await();cancellation.checkActive()}
    }finally{active.decrementAndGet();exited.incrementAndGet()}
   }
  }
  assertEquals(0,active.get());assertEquals(4,exited.get())
 }

 @Test fun httpErrorCancelsOtherDelayedBodiesWithoutReplay()=MockWebServer().use {server->
  val entered=CountDownLatch(4)
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
   entered.countDown();check(entered.await(5,TimeUnit.SECONDS))
   return if(request.path!!.contains(ids[0]))MockResponse().setResponseCode(403)
    else MockResponse().setBody("body").setBodyDelay(3,TimeUnit.SECONDS)
  }}
  server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
  val dav=AppendOnlyWebDav(server.url("/dav/").toString(),"synthetic","history","","")
  val start=System.nanoTime()
  assertFailsWith<WebDavException>{dav.readHistoryObjects(ids,MetadataReadCancellation())}
  assertTrue((System.nanoTime()-start)/1_000_000<1500,"failed batch must cancel remaining body reads")
  assertTrue(Thread.getAllStackTraces().keys.none {it.isAlive && it.name=="pdsync-history-read"})
  assertEquals(4,server.requestCount)
 }

 @Test fun interruptedOwnerCancelsRealBodyReadsAndCannotReturnWithLiveWorkers()=MockWebServer().use {server->
  server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=MockResponse().setBody("body").setBodyDelay(3,TimeUnit.SECONDS)}
  server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
  val dav=AppendOnlyWebDav(server.url("/dav/").toString(),"synthetic","history","","")
  val outcome=AtomicReference<Throwable?>()
  val owner=Thread {try {dav.readHistoryObjects(ids,MetadataReadCancellation())}catch(t:Throwable){outcome.set(t)}}
  owner.start()
  repeat(4){assertNotNull(server.takeRequest(5,TimeUnit.SECONDS))}
  owner.interrupt();owner.join(1500)
  assertFalse(owner.isAlive);assertNotNull(outcome.get())
  assertTrue(Thread.getAllStackTraces().keys.none {it.isAlive && it.name=="pdsync-history-read"})
  assertEquals(4,server.requestCount,"cancel never replays GET or writes")
 }
}
