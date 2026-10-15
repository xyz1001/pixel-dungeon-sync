package pdsync

import io.github.xyz1001.pdsync.android.SharedConnectionConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.test.*

/** Local, injected probes only: no HTTP, real credentials, game files or cloud writes. */
class DesktopConnectionAdapterTest {
 @TempDir lateinit var temp: Path
 private val path get() = temp.resolve("config.json")
 private val file get() = ConfigFile(path)
 private fun original() = AppConfig(
  endpoint = "https://custom.invalid/dav", profile = "keep-existing-profile", targetGame = "/fixture/game.jar",
  username = "alice", password = "fixture-secret", autoUploadPaused = true,
  onboardingProgress = OnboardingProgress.COMPLETE, selectedSaveRoot = "/fixture/saves"
 )
 private fun adapter(initial: AppConfig? = original(), password: String = "fixture-secret",
                     probe: (ConnectionDraft) -> RemoteHead? = { null }) =
  DesktopConnectionAdapter(initial, password, file, temp.resolve("cache")) { ConnectionSetup(it, probe = probe) }
 private fun connection(endpoint: String = "https://custom.invalid/dav", username: String = "alice",
                        password: String = "fixture-secret", official: Boolean = false) =
  SharedConnectionConfig(endpoint, username, password, "keep-existing-profile", official)

 @Test fun newSetupStartsAtServiceChoiceWithoutLosingInternalProfile() {
  val port = adapter(newConnectionConfig())
  assertNull(port.initialConnection)
  assertEquals(Shattered.defaultRemoteProfile, port.profile)
  assertFalse(path.exists())
 }

 @Test fun legacyConfigurationDefaultsToCustomEvenAtOfficialRoot() {
  path.writeText("""{"endpoint":"https://webdav.xyz1001.top:8443/","profile":"preserved","username":"alice","password":"fixture-secret"}""")
  val before = path.readBytes()
  val loaded = assertNotNull(file.load())
  assertEquals("custom", loaded.connectionProvider)
  val port = adapter(loaded)
  assertFalse(assertNotNull(port.initialConnection).official)
  assertEquals("https://webdav.xyz1001.top:8443", port.initialConnection!!.endpoint)
  assertContentEquals(before, path.readBytes())
 }

 @Test fun onlyExplicitProviderAndExactOfficialRootOpenOfficialForm() {
  for (endpoint in listOf(DESKTOP_OFFICIAL_ROOT, DESKTOP_OFFICIAL_ROOT.removeSuffix("/"))) {
   val dto = desktopConnectionConfig(original().copy(endpoint = endpoint, connectionProvider = "official"), "fixture")
   assertTrue(dto.official)
   assertEquals(DESKTOP_OFFICIAL_ROOT, dto.endpoint)
  }
  for (endpoint in listOf(
   "https://webdav.xyz1001.top:8443/dav/", "https://webdav.xyz1001.top:8443/another",
   "https://webdav.xyz1001.top:8443.evil.invalid/", "https://webdav.xyz1001.top/",
   "https://webdav.xyz1001.top:8443/?x=1", "https://webdav.xyz1001.top:8443//"
  )) {
   val dto = desktopConnectionConfig(original().copy(endpoint = endpoint, connectionProvider = "official"), "fixture")
   assertFalse(dto.official, endpoint)
   assertEquals(endpoint, dto.endpoint)
  }
  assertFalse(desktopConnectionConfig(original().copy(endpoint = DESKTOP_OFFICIAL_ROOT), "fixture").official)
 }

 @Test fun explicitOfficialProviderSurvivesConfigRoundTripAndSlashNormalization() {
  file.save(original().copy(endpoint = DESKTOP_OFFICIAL_ROOT, connectionProvider = "official"))
  val loaded = assertNotNull(file.load())
  assertEquals("official", loaded.connectionProvider)
  assertEquals(DESKTOP_OFFICIAL_ROOT.removeSuffix("/"), loaded.endpoint)
  assertEquals(DESKTOP_OFFICIAL_ROOT, desktopConnectionConfig(loaded, "fixture").endpoint)
 }

 @Test fun testingNeverPersistsAndExplicitSaveKeepsUnrelatedSettings() {
  val initial = original()
  file.save(initial)
  val before = path.readBytes()
  val port = adapter(initial)
  val dto = connection()
  assertTrue(port.testConnection(dto))
  assertNull(port.appliedConnection)
  assertContentEquals(before, path.readBytes())
  port.saveAndActivate(dto)
  val saved = assertNotNull(file.load())
  assertEquals(initial, saved)
  assertEquals("fixture-secret", assertNotNull(port.appliedConnection).password)
  assertFailsWith<IllegalStateException> { port.saveAndActivate(dto) }
 }

 @Test fun emptyCustomPasswordIsARealVerifiedAndPersistedValue() {
  var testedPassword: String? = null
  val port = adapter(password = "") { testedPassword = it.password; null }
  val dto = connection(password = "")
  assertTrue(port.testConnection(dto))
  assertEquals("", testedPassword)
  assertFalse(path.exists())
  port.saveAndActivate(dto)
  assertEquals("", assertNotNull(file.load()).password)
  assertEquals("", assertNotNull(port.appliedConnection).password)
 }

 @Test fun failedTestKeepsOriginalConfigAndCannotSave() {
  file.save(original())
  val before = path.readBytes()
  val port = adapter { throw IOException("fixture transport failure") }
  assertFailsWith<IOException> { port.testConnection(connection()) }
  assertFailsWith<IllegalStateException> { port.saveAndActivate(connection()) }
  assertContentEquals(before, path.readBytes())
 }

 @Test fun switchingAccountCannotReuseOldProofOrCarryPasswordIntoDifferentConfig() {
  file.save(original())
  val before = path.readBytes()
  val port = adapter()
  assertTrue(port.testConnection(connection()))
  val switched = connection(username = "bob", password = "")
  assertFailsWith<IllegalStateException> { port.saveAndActivate(switched) }
  assertContentEquals(before, path.readBytes())
  assertTrue(port.testConnection(switched))
  port.saveAndActivate(switched)
  val saved = assertNotNull(file.load())
  assertEquals("bob", saved.username)
  assertEquals("", saved.password)
  assertEquals("keep-existing-profile", saved.profile)
  assertEquals(OnboardingProgress.PENDING, saved.onboardingProgress)
 }

 @Test fun failedNewAttemptInvalidatesPreviouslySuccessfulProof() {
  var fail = false
  val port = adapter { if (fail) throw IOException("fixture failure"); null }
  val dto = connection()
  assertTrue(port.testConnection(dto))
  fail = true
  assertFailsWith<IOException> { port.testConnection(dto) }
  assertFailsWith<IllegalStateException> { port.saveAndActivate(dto) }
  assertFalse(path.exists())
 }

 @Test fun cancelAndReturnDiscardProofAndPreserveStoredConfig() {
  file.save(original())
  val before = path.readBytes()
  val first = adapter()
  assertTrue(first.testConnection(connection()))
  first.close()
  first.close()
  assertFailsWith<IllegalStateException> { first.saveAndActivate(connection()) }
  assertFailsWith<IllegalStateException> { first.testConnection(connection()) }
  val returned = adapter()
  assertEquals("alice", assertNotNull(returned.initialConnection).username)
  assertFailsWith<IllegalStateException> { returned.saveAndActivate(connection()) }
  assertContentEquals(before, path.readBytes())
 }

 @Test fun duplicateTestAndCloseDuringTestCannotProduceLateSave() {
  file.save(original())
  val before = path.readBytes()
  val entered = CountDownLatch(1)
  val release = CountDownLatch(1)
  val failure = AtomicReference<Throwable?>()
  val port = adapter {
   entered.countDown()
   check(release.await(5, TimeUnit.SECONDS))
   null
  }
  val worker = thread { try { port.testConnection(connection()) } catch (e: Throwable) { failure.set(e) } }
  try {
   assertTrue(entered.await(5, TimeUnit.SECONDS))
   assertFailsWith<IllegalStateException> { port.testConnection(connection()) }
   assertFailsWith<IllegalStateException> { port.saveAndActivate(connection()) }
   port.close()
  } finally { release.countDown(); worker.join(5000) }
  assertFalse(worker.isAlive)
  assertIs<IllegalStateException>(failure.get())
  assertFailsWith<IllegalStateException> { port.saveAndActivate(connection()) }
  assertContentEquals(before, path.readBytes())
 }

 @Test fun rejectedOfficialEndpointInvalidatesPriorCustomProof() {
  val port = adapter()
  assertTrue(port.testConnection(connection()))
  assertFailsWith<IllegalArgumentException> { port.testConnection(connection(endpoint = "https://other.invalid/", official = true)) }
  assertFailsWith<IllegalStateException> { port.saveAndActivate(connection()) }
  assertFalse(path.exists())
 }

 @Test fun officialFormCannotSendCredentialsToDifferentPathOrHost() {
  var probes = 0
  val port = adapter { probes++; null }
  for (endpoint in listOf("https://webdav.xyz1001.top:8443/dav/", "https://other.invalid/")) {
   assertFailsWith<IllegalArgumentException> { port.testConnection(connection(endpoint = endpoint, official = true)) }
  }
  assertEquals(0, probes)
  assertFalse(path.exists())
 }

 @Test fun profileCannotBeChangedByConnectionAdapter() {
  val port = adapter()
  val dto = SharedConnectionConfig("https://custom.invalid/dav", "alice", "fixture", "different", false)
  assertFailsWith<IllegalArgumentException> { port.testConnection(dto) }
  assertFalse(path.exists())
 }

 @Test fun officialSelectionChangesOnlyExplicitConnectionFields() {
  val initial = original()
  val port = adapter(initial)
  val dto = connection(endpoint = DESKTOP_OFFICIAL_ROOT, official = true)
  assertTrue(port.testConnection(dto))
  assertFalse(path.exists())
  port.saveAndActivate(dto)
  val saved = assertNotNull(file.load())
  assertEquals("official", saved.connectionProvider)
  assertEquals(DESKTOP_OFFICIAL_ROOT.removeSuffix("/"), saved.endpoint)
  assertEquals(initial.profile, saved.profile)
  assertEquals(initial.selectedSaveRoot, saved.selectedSaveRoot)
  assertEquals(initial.targetGame, saved.targetGame)
  assertEquals(initial.autoUploadPaused, saved.autoUploadPaused)
  assertEquals(OnboardingProgress.PENDING, saved.onboardingProgress)
 }

 @Test fun persistedPasswordDoesNotResurrectAnExplicitlyClearedSessionPassword() {
  val port = adapter(password = "")
  assertEquals("", assertNotNull(port.initialConnection).password)
 }
 @Test fun savedCredentialMatchEnablesOnlyRevalidationAndHonorsSlashNormalization() {
  file.save(original())
  val port = adapter()
  val dto = connection(endpoint = "https://custom.invalid/dav/")
  assertTrue(port.isSavedConnection(dto))
  assertFailsWith<IllegalStateException> { port.saveAndActivate(dto) }
  assertNull(port.appliedConnection)
  port.close()
  assertFalse(port.isSavedConnection(dto))
 }

 @Test fun savedCredentialMatchRejectsMissingUnreadableOrChangedCredentials() {
  assertFalse(adapter().isSavedConnection(connection()))
  file.save(original().copy(password = null))
  assertFalse(adapter(password = "").isSavedConnection(connection(password = "")))
  file.save(original())
  val port = adapter()
  listOf(connection(endpoint = "https://different.invalid/dav"), connection(username = "bob"),
   connection(password = ""), connection().copy(profile = "different-profile"),
   connection(endpoint = DESKTOP_OFFICIAL_ROOT, official = true)).forEach {
   assertFalse(port.isSavedConnection(it))
  }
  path.writeText("broken")
  assertFalse(port.isSavedConnection(connection()))
 }

 @Test fun persistedEmptyPasswordRemainsAnExactSavedCredentialValue() {
  file.save(original().copy(password = ""))
  assertTrue(adapter(password = "").isSavedConnection(connection(password = "")))
  assertFalse(adapter(password = "different").isSavedConnection(connection(password = "different")))
 }

 @Test fun savedOfficialRevalidationKeepsExplicitProviderAndExactRootRules() {
  file.save(original().copy(endpoint = DESKTOP_OFFICIAL_ROOT, connectionProvider = "official"))
  val official = adapter(assertNotNull(file.load()))
  assertTrue(official.isSavedConnection(connection(endpoint = DESKTOP_OFFICIAL_ROOT, official = true)))
  assertFalse(official.isSavedConnection(connection(endpoint = DESKTOP_OFFICIAL_ROOT, official = false)))
  file.save(original().copy(endpoint = "https://webdav.xyz1001.top:8443/dav/", connectionProvider = "official"))
  val legacy = adapter(assertNotNull(file.load()))
  assertFalse(legacy.initialConnection!!.official)
  assertFalse(legacy.isSavedConnection(legacy.initialConnection!!))
 }

}
