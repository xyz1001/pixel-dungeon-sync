package pdsync

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.test.*

class ConnectionProvisioningTest {
 @TempDir lateinit var temp: Path
 private fun collection(path: String) = MockResponse().setResponseCode(207).setBody(
  """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
 )
 private fun config(server: MockWebServer, profile: String = "default") = AppConfig(endpoint = server.url("/dav/").toString(), username = "player", profile = profile)
 private fun success(server: MockWebServer, profile: String = "default") {
  server.enqueue(collection("/dav/")); server.enqueue(collection("/dav/shattered/")); server.enqueue(collection("/dav/shattered/$profile/"))
  server.enqueue(collection("/dav/shattered/$profile/"))
 }
 @Test fun freshConfigUsesAdapterNamespaceAndNeverEnablesPasswordPersistence() {
  val fresh = newConnectionConfig()
  assertEquals("", fresh.endpoint); assertEquals("default", fresh.profile); assertFalse(fresh.rememberPassword)
  val other = object: GameAdapter by Shattered { override val defaultRemoteProfile = "variant-default" }
  assertEquals("variant-default", newConnectionConfig(other).profile)
  assertEquals("default", ConnectionSetup().draft.config.profile)
 }
 @Test fun explicitConnectCreatesOnlyNamespaceThenAuthorizesOneApply(): Unit = MockWebServer().use { server ->
  server.start()
  server.enqueue(collection("/dav/"))
  server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(collection("/dav/shattered/"))
  server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(collection("/dav/shattered/default/"))
  server.enqueue(collection("/dav/shattered/default/"))
  val model = ConnectionSetup(config(server)); model.update(config(server), "session-test-password")
  val file = ConfigFile(temp.resolve("config.json"))
  assertEquals(ConnectionTestResult.Success(false), model.connect()); assertTrue(model.canApply); assertFalse(file.exists())
  val applied = model.apply(file); assertEquals("session-test-password", applied.password); assertFalse(applied.config.rememberPassword)
  val requests = List(server.requestCount) { server.takeRequest() }
  assertEquals(listOf("/dav/shattered/", "/dav/shattered/default/"), requests.filter { it.method == "MKCOL" }.map { it.path })
  assertEquals(8, requests.size); assertTrue(requests.none { it.method in setOf("PUT", "DELETE", "MOVE", "LOCK", "GET") })
  assertEquals("default", file.load()!!.profile); assertFalse(temp.resolve("config.json").readText().contains("session-test-password"))
  assertFails { model.apply(file) }
 }
 @Test fun existingConfiguredAndInjectedProfileIsNeverRedirected(): Unit = MockWebServer().use { server ->
  server.start(); success(server, "isolated-old-profile")
  val configured = config(server, "isolated-old-profile").copy(targetGame = "/retained/game.jar", rememberPassword = true)
  val model = ConnectionSetup(configured); model.update(configured, "test-password")
  assertIs<ConnectionTestResult.Success>(model.connect())
  val applied = model.apply(ConfigFile(temp.resolve("config.json")), rememberPasswordStored = true)
  assertEquals("isolated-old-profile", applied.config.profile); assertEquals("/retained/game.jar", applied.config.targetGame); assertTrue(applied.config.rememberPassword)
  val requests = List(server.requestCount) { server.takeRequest() }
  assertFalse(requests.any { it.path!!.contains("/default/") || it.method == "MKCOL" })
 }
 @Test fun identityChangeDoesNotInheritPasswordOrSavePermission() {
  val original = ConnectionDraft(AppConfig(endpoint = "https://first.example/dav", username = "alice", profile = "existing", rememberPassword = true), "secret-test")
  listOf(original.config.copy(endpoint = "https://second.example/dav"), original.config.copy(username = "bob")).forEach { next ->
   val edited = original.editingConfig(next); assertEquals("", edited.password); assertFalse(edited.config.rememberPassword); assertEquals("existing", edited.config.profile)
  }
  assertTrue(original.editingConfig(original.config.copy(targetGame = "/game.jar")).config.rememberPassword)
 }
 @Test fun authenticationFailurePreservesAllInputsAndAllowsRetry(): Unit = MockWebServer().use { server ->
  server.start(); server.enqueue(MockResponse().setResponseCode(401)); success(server)
  val draft = config(server); val model = ConnectionSetup(draft); model.update(draft, "keep-this-input")
  val failure = assertIs<ConnectionTestResult.Failure>(model.connect()); assertEquals(401, failure.status); assertTrue(failure.message.contains("401"))
  assertEquals(draft, model.draft.config); assertEquals("keep-this-input", model.draft.password); assertFalse(model.canApply)
  assertIs<ConnectionTestResult.Success>(model.connect()); assertTrue(model.canApply)
 }
 @Test fun rootMissingAndForbiddenAreDifferentAndNeverAuthorizeConfigSave(): Unit = MockWebServer().use { server ->
  server.start(); server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(403))
  val model = ConnectionSetup(config(server)); model.update(config(server), "password-test")
  val missing = assertIs<ConnectionTestResult.Failure>(model.connect()); assertEquals(404, missing.status); assertTrue(missing.message.contains("服务地址"))
  val denied = assertIs<ConnectionTestResult.Failure>(model.connect()); assertEquals(403, denied.status); assertTrue(denied.message.contains("权限"))
  assertFalse(model.canApply); assertFails { model.apply(ConfigFile(temp.resolve("config.json"))) }
  assertEquals(listOf("PROPFIND", "PROPFIND"), List(server.requestCount) { server.takeRequest().method })
 }
 @Test fun automaticProbeIsReadOnlyEvenWhenNamespaceMissing(): Unit = MockWebServer().use { server ->
  server.start(); server.enqueue(MockResponse().setResponseCode(404))
  val model = ConnectionSetup(config(server)); model.update(config(server), "password-test")
  assertIs<ConnectionTestResult.Failure>(model.testConnection())
  assertEquals(listOf("PROPFIND"), List(server.requestCount) { server.takeRequest().method }); assertFalse(model.canApply)
 }
 @Test fun cancellationDuringRootProbeStopsFurtherProvisioningAndCannotSave(): Unit = MockWebServer().use { server ->
  val entered = CountDownLatch(1); val release = CountDownLatch(1)
  server.dispatcher = object: Dispatcher() {
   override fun dispatch(request: RecordedRequest): MockResponse {
    entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return collection("/dav/")
   }
  }
  server.start(); val model = ConnectionSetup(config(server)); model.update(config(server), "test-password")
  val worker = thread { model.connect() }
  try {
   assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(model.busy)
   assertFails { model.connect() }; assertFails { model.update(config(server), "replacement") }
   model.cancel(); assertEquals("", model.draft.password)
  } finally { release.countDown(); worker.join(5000) }
  assertFalse(worker.isAlive); assertFalse(model.busy); assertFalse(model.canApply); assertEquals(1, server.requestCount)
  assertFails { model.apply(ConfigFile(temp.resolve("config.json"))) }
 }
 @Test fun unsafeRawEndpointIsRejectedBeforeNormalizationAndAnyRequest(): Unit = MockWebServer().use { server ->
  server.start()
  val authority = server.url("/").toString().removeSuffix("/")
  listOf("/dav/../", "/dav/%2e%2e/", "/dav/./", "/dav/%2fchild/", "/dav/%252e%252e/", "/dav/%255c/", "/dav//child/", "/dav/\\child/").forEach { unsafe ->
   val raw = config(server).copy(endpoint = authority + unsafe)
   val model = ConnectionSetup(raw); model.update(raw, "input-secret-test")
   val result = assertIs<ConnectionTestResult.Failure>(model.connect())
   assertFalse(model.canApply); assertEquals(raw.endpoint, model.draft.config.endpoint)
   assertFalse(result.message.contains("input-secret-test"))
  }
  assertEquals(0, server.requestCount)
 }

}
