package pdsync

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.io.path.writeText
import kotlin.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WebDavPersistentDiagnosticsTest {
 @TempDir lateinit var temp: Path
 private val id = "11111111-1111-4111-8111-111111111111"
 private val username = "private-test-account"
 private val password = "private-test-password"
 private fun dav(base:String,client:OkHttpClient=OkHttpClient()) =
  AppendOnlyWebDav(base,"private_game","private_profile",username,password,client)
 private fun exported():String = ZipInputStream(ByteArrayInputStream(DiagnosticLog.snapshotExport())).use { zip ->
  buildString { while (zip.nextEntry != null) append(zip.readBytes().decodeToString()) }
 }
 private fun assertPrivate(text:String, vararg extra:String) {
  for (value in listOf(username,password,"private_game","private_profile",id,
    Base64.getEncoder().encodeToString("$username:$password".encodeToByteArray())) + extra)
   assertFalse(value in text,"Diagnostic export leaked a private marker")
 }

 @Test fun actualHttpStatusesShareOperationAndNeverRecordRequestOrSaveBytes() = MockWebServer().use { server ->
  DiagnosticLog.configure(temp,"9.8.7-test","linux")
  server.enqueue(MockResponse().setResponseCode(201))
  server.enqueue(MockResponse().setResponseCode(200).setBody("private-save-content"))
  server.start()
  val trace=DiagnosticLog.begin(DiagnosticAction.PUSH,false)
  DiagnosticLog.bind(trace).use {
   dav(server.url("/private-base/").toString()).apply {
    writeObject(id,"private-save-content".encodeToByteArray())
    assertEquals("private-save-content",readObject(id)!!.decodeToString())
   }
   trace.record(DiagnosticStage.FINISHED,DiagnosticResult.SUCCEEDED)
  }
  val text=exported()
  for(value in listOf("PUT","GET","201","200","PUSH","SUCCEEDED")) assertTrue(value in text,value)
  assertContains(text, "\"networkTarget\":\"DAV\"")
  assertContains(text, "\"requestDurationMs\":")
  assertEquals(2,server.requestCount)
  assertPrivate(text,"private-base","private-save-content",server.url("/").toString())
 }

 @Test fun namespaceInterceptorStatusIsLoggedWithoutChangingTheThrownFailure() = MockWebServer().use { server ->
  DiagnosticLog.configure(temp,"9.8.7-test","linux")
  server.enqueue(MockResponse().setResponseCode(403));server.start()
  val error=assertFailsWith<NamespaceProvisioningException> { dav(server.url("/").toString()).ensureNamespace() }
  assertEquals(NamespaceFailureReason.FORBIDDEN,error.reason)
  assertEquals(403,error.statusCode)
  val text=exported();assertTrue("PROPFIND" in text);assertTrue("403" in text)
  assertPrivate(text);assertEquals(1,server.requestCount)
 }

 @Test fun transportCategoryDoesNotLeakCauseAndDoesNotRetry() {
  DiagnosticLog.configure(temp,"9.8.7-test","linux")
  var attempts=0
  val client=OkHttpClient.Builder().addInterceptor {
   attempts++;throw SocketTimeoutException("https://private.invalid/?token=secret $password")
  }.build()
  val error=assertFailsWith<IOException> { dav("https://example.invalid/",client).readCommit(id) }
  assertEquals("WebDAV network request failed [TIMEOUT]",error.message)
  assertEquals(IOException::class.java,error.javaClass)
  assertEquals(1,attempts)
  val text=exported();assertTrue("TIMEOUT" in text)
  assertContains(text, "NETWORK_TARGET=DAV")
  assertContains(text, "REQUEST_DURATION_MS=")
  assertPrivate(text,"private.invalid","secret","https://example.invalid")
 }

 @Test fun unavailableLoggingStorageCannotChangeSuccessfulHttpResult() = MockWebServer().use { server ->
  val notDirectory=temp.resolve("not-directory");notDirectory.writeText("private-existing-data")
  DiagnosticLog.configure(notDirectory,"9.8.7-test","linux")
  server.enqueue(MockResponse().setResponseCode(200).setBody("result"));server.start()
  assertEquals("result",dav(server.url("/").toString()).readObject(id)!!.decodeToString())
  assertEquals(1,server.requestCount)
 }

 @Test fun legacyHttpLoggingPreserves404BehaviorAndNoFilePath() = MockWebServer().use { server ->
  DiagnosticLog.configure(temp,"9.8.7-test","linux")
  server.enqueue(MockResponse().setResponseCode(404));server.start()
  assertNull(WebDav(server.url("/private-base/").toString(),"private_game","private_profile",username,password).head())
  val text=exported();assertTrue("GET" in text);assertTrue("404" in text)
  assertPrivate(text,"private-base","head.json");assertEquals(1,server.requestCount)
 }
}
