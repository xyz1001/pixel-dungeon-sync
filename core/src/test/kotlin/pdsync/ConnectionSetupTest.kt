package pdsync

import kotlinx.serialization.encodeToString
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.test.*

class ConnectionSetupTest {
 @TempDir lateinit var temp: Path
 private val secret = "session-only-test-secret"
 private fun file() = ConfigFile(temp.resolve("config.json"))
 private fun config(server: MockWebServer) = AppConfig(
  endpoint = server.url("/dav/").toString(), profile = "hero", username = "player", targetGame = "/chosen/game.jar"
 )
 private fun setup(config: AppConfig, probe: ((ConnectionDraft) -> RemoteHead?)? = null) =
  ConnectionSetup(config, probe = probe).also { it.update(config, secret) }

 @Test fun absentConfigurationStartsBlankAndDoesNotPersistOrAutoConnect() {
  val model = ConnectionSetup()
  assertFalse(file().exists()); assertNull(file().load())
  assertEquals("", model.draft.config.endpoint); assertEquals("default", model.draft.config.profile)
  assertEquals("", model.draft.password); assertFalse(model.canApply)
  assertFailsWith<IllegalStateException> { model.apply(file()) }
  assertFalse(file().exists())
 }

 @Test fun emptyRemoteTestIsOnlyOneGetAndApplyPersistsOnlySafeFields() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setResponseCode(404)); server.start()
  val initial = config(server).copy(saveRoot = "/arbitrary/manual/root", version = "forged", rememberPassword = true)
  val model = setup(initial)
  assertEquals(ConnectionTestResult.Success(false), model.testConnection())
  assertTrue(model.canApply); assertFalse(model.busy); assertFalse(file().exists())
  val request = server.takeRequest(1, TimeUnit.SECONDS)!!
  assertEquals("GET", request.method); assertEquals("/dav/shattered/hero/head.json", request.path)
  assertEquals(Credentials.basic("player", secret), request.getHeader("Authorization"))
  assertEquals(0L, request.bodySize); assertEquals(1, server.requestCount)
  assertFalse(model.draft.toString().contains(secret))
  val applied = model.apply(file())
  assertEquals(secret, applied.password); assertEquals("", applied.config.saveRoot); assertNull(applied.config.version)
  assertTrue(applied.config.rememberPassword); assertFalse(applied.toString().contains(secret))
  assertEquals(applied.config, file().load())
  val written = temp.resolve("config.json").readText()
  assertTrue(written.contains("player")); assertTrue(written.contains("rememberPassword"))
  listOf(secret, "saveRoot", "forged", "arbitrary", "\"password\"").forEach { assertFalse(written.contains(it), it) }
  assertEquals("", model.draft.password); assertFalse(model.canApply)
  assertFailsWith<IllegalStateException> { model.apply(file()) }
  assertEquals(1, server.requestCount)
  val restarted = ConnectionSetup(file().load())
  assertEquals("player", restarted.draft.config.username); assertEquals("", restarted.draft.password)
  assertFalse(restarted.canApply); assertEquals(1, server.requestCount)
 }

 @Test fun populatedHeadTestReadsOnlyManifest() = MockWebServer().use { server ->
  val head = Manifest(game = "shattered", profile = "hero", version = "v1", id = UUID.randomUUID().toString(), parent = null, sha256 = "0".repeat(64), files = emptyMap())
  server.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(head)).setHeader("ETag", "\"test\"")); server.start()
  val model = setup(config(server))
  assertEquals(ConnectionTestResult.Success(true), model.testConnection())
  assertEquals(1, server.requestCount); assertEquals("GET", server.takeRequest().method)
  assertFalse(file().exists())
 }

 @Test fun authFailureHasTypedStatusAndRetryDoesNotPersist() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setResponseCode(404)); server.start()
  val model = setup(config(server))
  val failed = assertIs<ConnectionTestResult.Failure>(model.testConnection())
  assertEquals(401, failed.status); assertTrue(failed.message.contains("401")); assertFalse(failed.message.contains(secret))
  assertFalse(model.canApply); assertFalse(file().exists())
  assertFailsWith<IllegalStateException> { model.apply(file()) }
  model.update(model.draft.config, "corrected-secret")
  assertIs<ConnectionTestResult.Success>(model.testConnection())
  assertTrue(model.canApply); assertFalse(file().exists()); assertEquals(2, server.requestCount)
  repeat(2) { assertEquals("GET", server.takeRequest().method) }
 }

 @Test fun forbiddenAndRedirectsAreFailuresWithoutFollowingRedirect() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setResponseCode(403)); server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/other")); server.start()
  val model = setup(config(server))
  assertEquals(403, assertIs<ConnectionTestResult.Failure>(model.testConnection()).status)
  assertEquals(307, assertIs<ConnectionTestResult.Failure>(model.testConnection()).status)
  assertEquals(2, server.requestCount); assertFalse(file().exists()); assertFalse(model.canApply)
 }

 @Test fun networkFailureIsClearAndRetainsNoSuccessfulProof() {
  val server = MockWebServer(); server.start()
  val config = config(server); server.shutdown()
  val model = setup(config)
  val result = assertIs<ConnectionTestResult.Failure>(model.testConnection())
  assertNull(result.status); assertTrue(result.message.contains("网络")); assertFalse(result.message.contains(secret))
  assertFalse(model.canApply); assertFalse(model.busy); assertFalse(file().exists())
 }

 @Test fun invalidInputOrCorruptRemoteNeverAuthorizesApply() = MockWebServer().use { server ->
  server.start()
  val good = config(server)
  listOf(good.copy(endpoint = "http://remote.invalid"), good.copy(endpoint = "https://user:secret@example.com/"),
   good.copy(endpoint = "https://example.com/?token=secret"), good.copy(profile = "../bad"), good.copy(username = "bad:user")).forEach {
   val model = setup(it)
   assertIs<ConnectionTestResult.Failure>(model.testConnection()); assertFalse(model.canApply)
  }
  assertEquals(0, server.requestCount)
  server.enqueue(MockResponse().setResponseCode(200).setBody("invalid-json"))
  val model = setup(good)
  assertIs<ConnectionTestResult.Failure>(model.testConnection()); assertFalse(model.canApply)
  assertFalse(file().exists()); assertEquals(1, server.requestCount)
 }

 @Test fun everyDraftEditInvalidatesProofEvenWhenChangedBack() {
  val original = AppConfig(endpoint = "https://example.com/dav", profile = "hero", username = "player")
  val changes = listOf(
   ConnectionDraft(original.copy(endpoint = "https://elsewhere.example/dav"), secret),
   ConnectionDraft(original.copy(profile = "other"), secret), ConnectionDraft(original.copy(username = "other"), secret),
   ConnectionDraft(original.copy(targetGame = "/different.jar"), secret), ConnectionDraft(original.copy(rememberPassword = true), secret),
   ConnectionDraft(original, "different-password")
  )
  changes.forEach { changed ->
   val model = setup(original) { null }
   assertIs<ConnectionTestResult.Success>(model.testConnection()); assertTrue(model.canApply)
   model.update(changed.config, changed.password)
   assertFalse(model.canApply); assertNull(model.lastTest)
   assertFailsWith<IllegalStateException> { model.apply(file()) }
   model.update(original, secret)
   assertFalse(model.canApply)
  }
  assertFalse(file().exists())
 }

 @Test fun cancelAfterSuccessDropsDraftAndKeepsPriorFileExactly() {
  val original = AppConfig(endpoint = "https://existing.example/dav", username = "existing")
  file().save(original)
  val bytes = temp.resolve("config.json").readBytes()
  val model = setup(original) { null }
  model.update(original.copy(endpoint = "https://new.example/dav", username = "new"), secret)
  assertIs<ConnectionTestResult.Success>(model.testConnection())
  model.cancel()
  assertEquals(original, model.draft.config); assertEquals("", model.draft.password)
  assertFalse(model.canApply); assertNull(model.lastTest)
  assertContentEquals(bytes, temp.resolve("config.json").readBytes())
  assertFailsWith<IllegalStateException> { model.apply(file()) }
  assertFailsWith<IllegalStateException> { model.testConnection() }
 }

 @Test fun cancelDuringTestCannotResurrectProofAndConcurrentWorkIsGuarded() {
  val entered = CountDownLatch(1); val release = CountDownLatch(1)
  val failure = AtomicReference<Throwable?>()
  val initial = AppConfig(endpoint = "https://example.com/dav")
  val model = setup(initial) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); null }
  val worker = thread { try { model.testConnection() } catch (e: Throwable) { failure.set(e) } }
  try {
   assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(model.busy)
   assertFailsWith<IllegalStateException> { model.testConnection() }
   assertFailsWith<IllegalStateException> { model.update(initial, "another") }
   assertFailsWith<IllegalStateException> { model.apply(file()) }
   model.cancel(); assertEquals("", model.draft.password)
  } finally { release.countDown(); worker.join(5_000) }
  assertFalse(worker.isAlive); assertNull(failure.get()); assertFalse(model.busy)
  assertFalse(model.canApply); assertNull(model.lastTest); assertFalse(file().exists())
 }

 @Test fun failedRetestClearsPreviousProof() {
  var fail = false
  val model = setup(AppConfig(endpoint = "https://example.com")) { if (fail) throw WebDavException(503) else null }
  assertIs<ConnectionTestResult.Success>(model.testConnection()); assertTrue(model.canApply)
  fail = true
  assertEquals(503, assertIs<ConnectionTestResult.Failure>(model.testConnection()).status)
  assertFalse(model.canApply); assertFailsWith<IllegalStateException> { model.apply(file()) }; assertFalse(file().exists())
 }

 @Test fun rememberedSecretFailureMayDowngradeFlagButCannotSilentlyEnableIt() {
  val original = AppConfig(endpoint = "https://example.com", rememberPassword = true)
  val model = setup(original) { null }; model.testConnection()
  val applied = model.apply(file(), rememberPasswordStored = false)
  assertFalse(applied.config.rememberPassword); assertFalse(file().load()!!.rememberPassword); assertEquals(secret, applied.password)
  val unchecked = setup(original.copy(rememberPassword = false)) { null }; unchecked.testConnection()
  assertFailsWith<IllegalArgumentException> { unchecked.apply(file(), rememberPasswordStored = true) }
  assertTrue(unchecked.canApply)
 }

 @Test fun failedConfigWriteDoesNotConsumeTestOrLoseSessionPassword() {
  val model = setup(AppConfig(endpoint = "https://example.com")) { null }; model.testConnection()
  val directory = temp.resolve("not-a-file").createDirectory()
  assertFails { model.apply(ConfigFile(directory)) }
  assertTrue(model.canApply); assertEquals(secret, model.draft.password)
  assertEquals(secret, model.apply(file()).password)
 }

 @Test fun legacyRootIsIgnoredWithoutRewritingAndOriginalJsonIsBackedUpOnce() {
  val path = temp.resolve("config.json")
  val old = """{"saveRoot":"/legacy/arbitrary/root","endpoint":"https://example.com/dav/","profile":"hero","version":"manually-entered","targetGame":"/game.jar"}"""
  path.writeText(old)
  val loaded = file().load()!!
  assertEquals("", loaded.saveRoot); assertNull(loaded.version); assertEquals("/game.jar", loaded.targetGame)
  assertEquals(old, path.readText()); assertFalse(temp.resolve("config.json.legacy").exists())
  file().save(loaded.copy(username = "player"))
  val backup = temp.resolve("config.json.legacy")
  assertEquals(old, backup.readText()); assertFalse(path.readText().contains("saveRoot"))
  file().save(loaded.copy(username = "later"))
  assertEquals(old, backup.readText()); assertEquals("later", file().load()!!.username)
 }

 @Test fun existingDifferentLegacyBackupIsNeverOverwrittenOrUsedToDiscardOriginal() {
  val path = temp.resolve("config.json")
  val old = """{"saveRoot":"/legacy/root","endpoint":"https://example.com"}"""
  path.writeText(old)
  temp.resolve("config.json.legacy").writeText("earlier-original")
  file().save(file().load()!!)
  assertEquals("earlier-original", temp.resolve("config.json.legacy").readText())
  assertEquals(old, temp.resolve("config.json.legacy." + hash(old.encodeToByteArray())).readText())
 }

 @Test fun webDavFailureCodeRemainsAvailableToOrdinarySyncCallers() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setResponseCode(401)); server.start()
  val config = config(server)
  val error = assertFailsWith<WebDavException> { WebDav(config.endpoint, "shattered", config.profile, config.username, secret).head() }
  assertEquals(401, error.statusCode); assertFalse(error.toString().contains(secret))
 }
 @Test fun identityEditDropsPreviousPasswordButProfileEditRetainsIt() {
  val draft = ConnectionDraft(AppConfig(endpoint = "https://example.com/dav", username = "alice"), "private")
  assertEquals("", draft.editingConfig(draft.config.copy(endpoint = "https://other.example.com")).password)
  assertEquals("", draft.editingConfig(draft.config.copy(username = "bob")).password)
  assertEquals("private", draft.editingConfig(draft.config.copy(profile = "other")).password)
  assertEquals("private", draft.editingConfig(draft.config.copy(targetGame = "/tmp/game.jar")).password)
 }

}
