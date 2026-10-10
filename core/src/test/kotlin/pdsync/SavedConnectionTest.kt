package pdsync

import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class SavedConnectionTest {
 @TempDir lateinit var temp: Path
 private val secret = "synthetic-file-password"
 private fun listing() = MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/dav/shattered/default/</d:href><d:status>HTTP/1.1 200 OK</d:status></d:response></d:multistatus>")
 @Test fun restartAndEditsUseFilePasswordWithReadOnlyLoopbackChecks() = MockWebServer().use { server ->
  server.start()
  val config = newConnectionConfig().copy(endpoint = server.url("/dav").toString(), username = "synthetic-user", targetGame = temp.resolve("game.jar").toString())
  val file = ConfigFile(temp.resolve("config.json"))
  for ((index, progress) in listOf(OnboardingProgress.PENDING, OnboardingProgress.COMPLETE).withIndex()) {
   val password = "$secret-$index"
   val setup = ConnectionSetup(file.load() ?: config, probe = { null })
   setup.update(config.copy(onboardingProgress = progress), password)
   assertIs<ConnectionTestResult.Success>(setup.testConnection())
   setup.applyFilePassword(file)
   val restarted = ConfigFile(temp.resolve("config.json")).load()!!
   assertEquals(config.targetGame, restarted.targetGame); assertEquals(progress, restarted.onboardingProgress)
   assertEquals(password, restarted.password)
   server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(listing())
   val restored = assertIs<SavedConnectionResult.Connected>(restoreSavedConnection(restarted))
   assertEquals(password, restored.connection.password)
   assertFalse(restarted.toString().contains(password)); assertFalse(restored.connection.toString().contains(password))
   assertEquals("GET", server.takeRequest().method)
   val request = server.takeRequest()
   assertEquals("PROPFIND", request.method)
   assertEquals(Credentials.basic(config.username, password), request.getHeader("Authorization"))
  }
  val previous = file.load()!!
  server.enqueue(MockResponse().setResponseCode(401))
  assertContains(assertIs<SavedConnectionResult.NeedsInput>(restoreSavedConnection(previous)).message, "401")
  assertEquals(previous, file.load())
  server.enqueue(MockResponse().setResponseCode(500))
  val retry = assertIs<SavedConnectionResult.Retry>(restoreSavedConnection(previous))
  assertEquals(previous.password, retry.connection.password)
  assertEquals(6, server.requestCount)
 }
 @Test fun failedSaveRetainsEditableDraftAndDoesNotClaimSuccess() {
  val parent = temp.resolve("blocked"); parent.writeText("occupied")
  val setup = ConnectionSetup(newConnectionConfig().copy(endpoint = "https://fixture.invalid"), probe = { null })
  setup.update(setup.draft.config, secret); setup.testConnection()
  assertFails { setup.applyFilePassword(ConfigFile(parent.resolve("config.json"))) }
  assertEquals(secret, setup.draft.password); assertTrue(setup.canApply)
  assertEquals("occupied", parent.readText())
 }
 @Test fun oldConfigNeverImportsSystemPasswordAndPromptsForMissingField() {
  val old = newConnectionConfig().copy(endpoint = "https://unused.invalid", rememberPassword = true)
  val file = ConfigFile(temp.resolve("config.json")); file.save(old)
  assertNull(file.load()!!.password)
  assertIs<SavedConnectionResult.NeedsInput>(restoreSavedConnection(file.load()!!))
 }
 @Test fun corruptFileDoesNotLeakSecretAndOriginalIsPreservedOnRecovery() {
  val path = temp.resolve("config.json")
  val corrupt = "{\"password\":\"$secret\", broken"
  path.writeText(corrupt)
  val file = ConfigFile(path)
  val failure = assertFails { file.load() }
  assertFalse(failure.toString().contains(secret)); assertNull(failure.cause)
  assertEquals(corrupt, path.readText())
  file.save(newConnectionConfig().copy(endpoint = "https://fixture.invalid", password = "replacement-synthetic"))
  assertEquals(corrupt, temp.resolve("config.json.legacy").readText())
 }
 @Test fun unverifiedDraftCannotReplacePreviousConfiguration() {
  val config = newConnectionConfig().copy(endpoint = "https://unused.invalid", password = secret)
  val file = ConfigFile(temp.resolve("config.json")); file.save(config)
  val setup = ConnectionSetup(config)
  setup.update(config.copy(username = "edited"), "new-synthetic")
  assertFailsWith<IllegalStateException> { setup.applyFilePassword(file) }
  assertEquals(config, file.load())
  assertNull(ConnectionDraft(config, secret).editingConfig(config.copy(username = "changed")).config.password)
 }
}
