package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.io.IOException
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.net.ssl.*
import java.security.cert.*
import okhttp3.*
import okio.*
import kotlin.test.*

class TlsDiagnostic110Test {
 @TempDir lateinit var temp:Path
 private val secret="PRIVATE_SECRET https://private.invalid/password"
 private fun events():String=ZipInputStream(ByteArrayInputStream(DiagnosticLog.snapshotExport())).use { z ->
  var entry=z.nextEntry
  while(entry!=null && entry.name!="events.jsonl") entry=z.nextEntry
  check(entry!=null);z.readBytes().decodeToString()
 }
 @Test fun closedCategoriesAndBoundedCyclesNeverReadMessages() {
  val cases=listOf(SSLHandshakeException(secret) to "TLS_HANDSHAKE",SSLProtocolException(secret) to "TLS_PROTOCOL",
   SSLPeerUnverifiedException(secret) to "TLS_PEER_VERIFY",SSLException(secret) to "TLS_GENERIC",
   CertPathValidatorException(secret) to "CERT_PATH",CertificateExpiredException(secret) to "CERT_EXPIRED",
   CertificateNotYetValidException(secret) to "CERT_NOT_YET_VALID",CertificateException(secret) to "CERT_OTHER")
  for((error,expected) in cases) assertEquals(expected,TransportFailureKind.detail(error).name)
  val a=SSLHandshakeException(secret);val b=IOException(secret);a.initCause(b);b.initCause(a)
  DiagnosticLog.configure(temp,"110","android")
  DiagnosticLog.networkFailure(a,DiagnosticDetails.builder().transportFailure(a,false,false).build())
  val text=events()
  assertContains(text,"\"transportChainTruncated\":\"true\"")
  assertContains(text,"TLS_HANDSHAKE");assertContains(text,"IO")
  assertFalse(text.contains("PRIVATE_SECRET"));assertFalse(text.contains("private.invalid"))
 }
 @Test fun namespaceAndAndroidConnectionWrappersRetainOnlyClosedFailureCategory() {
  val http=OkHttpClient.Builder().addInterceptor {throw SSLException(secret)}.build()
  try {
   val dav=AppendOnlyWebDav("https://example.invalid/","synthetic","test","","",http)
   val wrapped=assertFailsWith<NamespaceProvisioningException>{dav.ensureNamespace()}
   assertEquals(NamespaceFailureReason.NETWORK,wrapped.reason)
   assertEquals(DiagnosticFailure.TLS,DiagnosticLog.classify(wrapped))
   assertNull(wrapped.cause);assertFalse(wrapped.toString().contains(secret))
   val setup=ConnectionSetup(AppConfig(endpoint="https://example.invalid/",profile="test",username="synthetic"),"synthetic",probe={throw wrapped})
   val result=assertIs<ConnectionTestResult.Failure>(setup.connect())
   assertEquals(DiagnosticFailure.TLS,result.diagnosticFailure)
   val status=pdsync.android.app.AndroidSyncFacade.ConnectionStatus(false,result.message,false,result.diagnosticFailure)
   val outer=assertFailsWith<ConnectionDiagnosticException>{status.requireSuccess("unchanged UI message")}
   assertEquals("unchanged UI message",outer.message)
   assertEquals(DiagnosticFailure.TLS,DiagnosticLog.classify(outer));assertNull(outer.cause)
   pdsync.android.app.AndroidSyncFacade.ConnectionStatus(true,"ready",false).requireSuccess("unused")
  } finally {http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()}
 }

 @Test fun productionHeaderAndBodyFailuresKeepRequestIdentityAndCancellationEvidence() {
  DiagnosticLog.configure(temp,"110","android")
  val trace=DiagnosticLog.begin(DiagnosticAction.INSPECT,false)
  val counts=mutableListOf<Int>()
  DiagnosticLog.bind(trace).use {
   for(body in listOf(false,true)) for(cancel in listOf(false,true)) {
    var attempts=0
    val http=OkHttpClient.Builder().addInterceptor { chain ->
     attempts++
     val failure=SSLHandshakeException(secret).apply {initCause(CertPathValidatorException(secret))}
     if(!body) {if(cancel)chain.call().cancel();throw failure}
     val responseBody=object:ResponseBody() {
      private val input=object:Source {
       override fun timeout()=Timeout.NONE
       override fun close() {}
       override fun read(sink:Buffer,byteCount:Long):Long {if(cancel)chain.call().cancel();throw failure}
      }.buffer()
      override fun contentType():MediaType?=null
      override fun contentLength()=-1L
      override fun source()=input
     }
     Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(responseBody).build()
    }.build()
    try {
     val dav=AppendOnlyWebDav("https://example.invalid/","synthetic","test","","",http)
     val error=assertFailsWith<IOException>{dav.readObject("ab".repeat(32))}
     assertEquals("WebDAV network request failed [TLS]",error.message)
     assertNull(error.cause);assertEquals(1,attempts);counts+=attempts
    } finally {http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()}
   }
   trace.record(DiagnosticStage.FINISHED,DiagnosticResult.FAILED)
  }
  val text=events()
  assertContains(text,"AWAITING_HEADERS");assertContains(text,"RESPONSE_BODY")
  assertContains(text,"TLS_HANDSHAKE");assertContains(text,"CERT_PATH")
  assertContains(text,"\"transportCanceled\":\"false\"");assertContains(text,"\"transportCanceled\":\"true\"")
  assertContains(text,"transportHeadersMs");assertContains(text,"transportBodyReadMs")
  val ids=Regex("\"transportRequestId\":\"([0-9]+)\"").findAll(text).map{it.groupValues[1]}.toSet()
  assertEquals(4,ids.size)
  assertFalse(text.contains("PRIVATE_SECRET"));assertFalse(text.contains("example.invalid"));assertFalse(text.contains("private.invalid"))
 }
}
