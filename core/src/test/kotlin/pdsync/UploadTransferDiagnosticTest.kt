package pdsync

import java.nio.file.Path
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class UploadTransferDiagnosticTest {
 @TempDir lateinit var root:Path
 private val id="ab".repeat(32)
 @Test fun delayedBodyBytesCacheHitsAndFailuresSurviveTerminalExport()=MockWebServer().use {server->
  DiagnosticLog.configure(root,"0.5.83-test+104","android")
  server.enqueue(MockResponse().setBody("synthetic-body").setBodyDelay(180,TimeUnit.MILLISECONDS))
  server.enqueue(MockResponse().setResponseCode(201).setBody("ok"))
  server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
  server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
  val dav=AppendOnlyWebDav(server.url("/dav/").toString(),"synthetic","probe","PRIVATE_ACCOUNT","PRIVATE_PASSWORD")
  val trace=DiagnosticLog.begin(DiagnosticAction.PUSH,false)
  DiagnosticLog.bind(trace).use {
   dav.withReadScope(root.resolve("cache")) {assertEquals(14,dav.readObject(id)!!.size);assertEquals(14,dav.readObject(id)!!.size)}
   dav.writeObject("cd".repeat(32),byteArrayOf(1,2,3))
   assertFailsWith<java.io.IOException> {dav.readCommit(id)}
   trace.record(DiagnosticStage.FINISHED,DiagnosticResult.FAILED,DiagnosticFailure.CONNECTION)
  }
  val totals=trace.transfers.snapshot()
  val get=totals.getValue(DiagnosticTransferKind.OBJECT_GET)
  assertEquals(1L,get.count);assertEquals(14L,get.receivedBytes);assertTrue(get.bodyMs>=100);assertTrue(get.totalMs>=get.bodyMs)
  assertEquals(1L,totals.getValue(DiagnosticTransferKind.CACHE_HIT).count)
  assertEquals(1L,totals.getValue(DiagnosticTransferKind.CACHE_MISS).count)
  assertEquals(3L,totals.getValue(DiagnosticTransferKind.OBJECT_PUT).sentBytes)
  assertEquals(1L,totals.getValue(DiagnosticTransferKind.COMMIT_GET).failed)
  assertEquals(3,server.requestCount,"logging must not replay requests")
  val events=ZipInputStream(ByteArrayInputStream(DiagnosticLog.snapshotExport())).use {zip->
   var found="";while(true){val e=zip.nextEntry ?: break;if(e.name=="events.jsonl")found=zip.readBytes().decodeToString()};found
  }
  assertFalse(events.contains("PRIVATE_"));assertFalse(events.contains("synthetic-body"));assertFalse(events.contains(server.url("/").toString()))
  val end=events.lineSequence().filter{it.isNotBlank()}.map{Json.parseToJsonElement(it).jsonObject}.single{it["stage"]!!.jsonPrimitive.content=="FINISHED"}
  assertEquals(trace.id,end["operationId"]!!.jsonPrimitive.content)
  val exported=end["details"]!!.jsonObject["transferTotals"]!!.jsonPrimitive.content
  assertTrue(DiagnosticTransferEvidence.isValid(exported));assertEquals(DiagnosticTransferEvidence.encode(totals),exported)
 }
 @Test fun canceledRequestsAndInvalidInjectedEvidenceAreBounded() {
  val c=DiagnosticTransferCollector()
  c.add(DiagnosticTransferKind.OBJECT_GET,1,2,3,4,5,DiagnosticResult.CANCELED)
  assertEquals(1L,c.snapshot().getValue(DiagnosticTransferKind.OBJECT_GET).canceled)
  assertFalse(DiagnosticTransferEvidence.isValid("{\"url\":\"private\"}"))
  assertFalse(DiagnosticTransferEvidence.isValid("x".repeat(4097)))
 }
 @Test fun productionCanceledCallHasOneCanceledTransferAndTerminal() {
  DiagnosticLog.configure(root,"0.5.83-test+104","android")
  val client=okhttp3.OkHttpClient.Builder().addInterceptor { chain -> chain.call().cancel();throw java.net.SocketException("PRIVATE_SENTINEL") }.build()
  val dav=AppendOnlyWebDav("http://127.0.0.1/dav/","synthetic","probe","test","synthetic",client)
  val trace=DiagnosticLog.begin(DiagnosticAction.PUSH,false)
  DiagnosticLog.bind(trace).use {
   assertFailsWith<java.io.IOException>{dav.readObject(id)}
   trace.record(DiagnosticStage.FINISHED,DiagnosticResult.CANCELED,DiagnosticFailure.CANCELED)
  }
  val value=trace.transfers.snapshot().getValue(DiagnosticTransferKind.OBJECT_GET)
  assertEquals(1L,value.count);assertEquals(1L,value.canceled);assertEquals(0L,value.failed)
 }

}
