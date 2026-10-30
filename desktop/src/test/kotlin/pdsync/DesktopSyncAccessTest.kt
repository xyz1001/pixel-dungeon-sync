package pdsync

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopSyncAccessTest {
 private val official = AppConfig(endpoint = DESKTOP_OFFICIAL_ROOT, username = "test", connectionProvider = "official")

 @Test fun appreciationFactsReplaceHomeStatusWithoutGrantingASyncLease() {
  var calls = 0
  val access = DesktopSyncAccess { _, _, _ -> calls++; throw IOException("offline") }
  access.observeAccountStatus(official.endpoint, official.username, 500L, 1000L)
  assertTrue(access.isExpired(official))
  access.observeAccountStatus(official.endpoint, official.username, 1000000L, 1000L)
  assertFalse(access.isExpired(official))
  assertEquals(0, calls)
  assertFailsWith<IOException> { access.store(official, "shattered", "secret") }
  assertEquals(1, calls, "A display refresh cannot skip the actual per-operation authorization")
  access.observeAccountStatus(official.endpoint, official.username, 500L, 1000L)
  assertTrue(access.isExpired(official))
  assertFalse(access.isExpired(official.copy(username = "another")))
  assertFalse(access.isExpired(official.copy(endpoint = "https://dav.example.test/")))
  access.observeAccountStatus(official.endpoint, official.username, null, 1000L)
  assertFalse(access.isExpired(official), "Confirmed unlimited is safe for display")
  access.reset()
  assertFalse(access.isExpired(official))
 }

 @Test fun newerOperationExpiryOverridesAnEarlierActiveDisplayResult() {
  val denied = IOException("expired")
  val access = DesktopSyncAccess(isExpiry = { it === denied }) { _, _, _ -> throw denied }
  access.observeAccountStatus(official.endpoint, official.username, 1000000L, 1000L)
  assertFalse(access.isExpired(official))
  assertFailsWith<IOException> { access.beginOperation(official, "secret") }
  assertTrue(access.isExpired(official))
 }

 @Test fun validLeaseIsReusedButIdentityChangeAndRestartRefresh() {
  var refreshes = 0
  var checks = 0
  val access = DesktopSyncAccess { _, _, _ -> refreshes++; { checks++; Unit } }
  val first = access.beginOperation(official, "secret")
  first(); first()
  assertEquals(1, refreshes)
  assertEquals(2, checks)
  access.beginOperation(official, "secret")()
  assertEquals(1, refreshes)
  assertEquals(4, checks, "cache reuse checks the original lease before use and for the request")
  access.beginOperation(official.copy(connectionProvider = "custom", endpoint = DESKTOP_OFFICIAL_ROOT + "nested"), "secret")()
  assertEquals(2, refreshes)
  assertEquals(5, checks)
  DesktopSyncAccess { _, _, _ -> refreshes++; {} }.beginOperation(official, "secret")
  assertEquals(3, refreshes) // a restarted host must obtain a new server proof
 }

 @Test fun customOriginDoesNotContactOfficialAccountMetadata() {
  val access = DesktopSyncAccess { _, _, _ -> error("Unexpected official request") }
  access.beginOperation(AppConfig(endpoint = "https://dav.example.test/"), "secret")()
  assertFalse(access.isExpired(official))
 }

 @Test fun historyRefreshReusesValidLeaseButExpiredProofCannotSurviveFailedRefresh() {
  var refreshes = 0
  var offline = false
  var elapsed = false
  val expired = IOException("expired")
  val access = DesktopSyncAccess(isExpiry = { it === expired }) { _, _, _ ->
   refreshes++
   if (offline) throw IOException("unavailable")
   val lease: () -> Unit = { if (elapsed) throw expired }
   lease
  }
  assertFalse(access.hasKnownAccess(null))
  assertFalse(access.hasKnownAccess(official), "unknown is not active")
  access.observeAccountStatus(official.endpoint, official.username, null, 1000L)
  assertFalse(access.hasKnownAccess(official), "an active/unlimited display is not a lease")
  access.beginOperation(official, "secret")
  repeat(3) { assertTrue(access.hasKnownAccess(official)) }
  assertEquals(1, refreshes, "rendering never refreshes account metadata")
  assertFalse(access.hasKnownAccess(official.copy(username = "other")))
  offline = true
  access.store(official, "shattered", "secret")
  assertEquals(1, refreshes, "a valid authenticated lease needs no repeated account HTTP")
  elapsed = true
  assertFailsWith<IOException> { access.store(official, "shattered", "secret") }
  assertEquals(2, refreshes, "an expired lease requires a fresh authenticated result")
  assertFalse(access.hasKnownAccess(official), "a failed fresh metadata query invalidates the older UI proof")
  access.observeAccountStatus(official.endpoint, official.username, null, 1000L)
  assertFalse(access.hasKnownAccess(official), "display facts cannot repair failed operation authorization")
  offline = false; elapsed = false
  access.store(official, "shattered", "secret")
  assertTrue(access.hasKnownAccess(official), "fresh operation authorization recovers the button")
  elapsed = true
  assertFalse(access.hasKnownAccess(official))
  access.observeAccountStatus(official.endpoint, official.username, null, 1000L)
  assertFalse(access.hasKnownAccess(official), "display renewal cannot revive an expired lease")
  elapsed = false; offline = false
  access.beginOperation(official, "secret")
  assertTrue(access.hasKnownAccess(official))
  access.reset()
  assertFalse(access.hasKnownAccess(official))
 }

 @Test fun invalidCachedLeaseAndRefreshFailureCannotProduceAnyStore() {
  var permitted = true
  val access = DesktopSyncAccess { _, _, _ ->
   if (!permitted) throw IOException("Metadata unavailable")
   val lease: () -> Unit = { if (!permitted) throw IOException("Cached proof invalid") }
   lease
  }
  access.beginOperation(official, "secret")()
  permitted = false
  assertFailsWith<IOException> { access.store(official, "shattered", "secret") }
 }

 @Test fun leaseIsRecheckedForEveryRequestAndCanExpireDuringOperation() {
  var permitted = true
  val access = DesktopSyncAccess { _, _, _ -> { if (!permitted) throw IOException("expired") } }
  val lease = access.beginOperation(official, "secret")
  lease()
  permitted = false
  assertFailsWith<IOException> { lease() }
 }

 @Test fun confirmedExpirySurvivesOutageAndFreshRenewalRecoversWithoutRestart() {
  val deadline = IOException("confirmed-expiry")
  var crossed = false
  var unavailable = false
  val access = DesktopSyncAccess(isExpiry = { it === deadline }) { _, _, _ ->
   if (unavailable) throw IOException("offline")
   val lease: () -> Unit = { if (crossed) throw deadline }
   lease
  }
  access.beginOperation(official, "secret")
  assertFalse(access.isExpired(official))
  crossed = true
  assertTrue(access.isExpired(official))
  unavailable = true
  assertFailsWith<IOException> { access.beginOperation(official, "secret") }
  assertTrue(access.isExpired(official))
  unavailable = false; crossed = false
  access.beginOperation(official, "secret")
  assertFalse(access.isExpired(official))
  crossed = true
  assertTrue(access.isExpired(official))
  access.reset()
  assertFalse(access.isExpired(official))
 }

 @Test fun expiredIdentityCannotCarryTheHomeEntryToAnotherAccountOrEndpoint() {
  val deadline = IOException("confirmed-expiry")
  val access = DesktopSyncAccess(isExpiry = { it === deadline }) { _, _, _ -> throw deadline }
  assertFailsWith<IOException> { access.beginOperation(official, "secret") }
  assertTrue(access.isExpired(official))
  assertFalse(access.isExpired(null))
  assertFalse(access.isExpired(official.copy(username = "different-account")))
  assertFalse(access.isExpired(official.copy(endpoint = "https://dav.example.test/")))
  access.reset()
  assertFalse(access.isExpired(official))
 }

 @Test fun missingSavedPasswordReturnsBeforeConstructingAnyDavConnection() {
  // No saved credential means NeedsInput even for the official origin; legacy OS credentials
  // are never consulted. A regression would attempt a live connection instead of this return.
  assertIs<SavedConnectionResult.NeedsInput>(restoreSavedConnection(official.copy(password = null)))
  val source = Files.readString(Path.of("../core/src/main/kotlin/pdsync/SavedConnection.kt"))
  assertTrue(source.indexOf("config.password ?: return") < source.indexOf("ConnectionSetup(config)"))
  assertFalse(source.contains("CredentialStore"))
 }

 @Test fun everyDesktopRemoteEntryUsesGuardedFactoryAndStartupNeverUsesDavForOfficialAccount() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt")).replace("\r\n", "\n")
  assertFalse(main.contains("AppendOnlyWebDav("))
  assertEquals(4, Regex("syncAccess\\.store\\(").findAll(main).count())
  val startup = main.substringAfter("suspend fun reconnectSaved(").substringBefore("fun openConnection(")
  assertTrue(startup.indexOf("syncAccess.beginOperation") < startup.indexOf("restoreSavedConnection"))
  assertContains(startup, "connected = true; connectionNotice = null\n     return")
  assertContains(main, "serviceExpired = syncAccess.isExpired(config)")
 }
}
