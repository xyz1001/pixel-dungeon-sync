package pdsync

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopRenewalHistoryReadsTest {
 private val owner = AppConfig(endpoint = DESKTOP_OFFICIAL_ROOT, username = "test",
  connectionProvider = "official", onboardingProgress = OnboardingProgress.COMPLETE)

 @Test fun renewalWaitsThroughSettingsAndBusyUntilHomeThenIsConsumedExactlyOnce() {
  val reads = DesktopRenewalHistoryReads()
  reads.refreshStarted(owner, homeAvailable = false)
  reads.accountAccessChanged(owner, true)
  repeat(3) {
   assertNull(reads.takeForHome(owner, home = false, ready = true), "CONNECTION/SETTINGS never inspect")
   assertNull(reads.takeForHome(owner, home = true, ready = false), "busy/startup/expired/close defer")
  }
  assertTrue(reads.blocksHomeObservation)
  val request = assertNotNull(reads.takeForHome(owner, home = true, ready = true))
  assertTrue(reads.isCurrent(request, owner))
  assertFalse(reads.blocksHomeObservation)
  repeat(3) { assertNull(reads.takeForHome(owner, home = true, ready = true)) }
 }

 @Test fun initialActiveAccountWithUnavailableHomeStillQueuesFreshLocalAndCloudRead() {
  val reads = DesktopRenewalHistoryReads()
  reads.refreshStarted(owner, homeAvailable = false) // failed inspection / null session / no remote verification
  reads.accountAccessChanged(owner, true)
  assertNotNull(reads.takeForHome(owner, home = true, ready = true))
 }

 @Test fun activeToActiveWithVerifiedHomeDoesNotManufactureARecovery() {
  val reads = DesktopRenewalHistoryReads()
  repeat(4) {
   reads.refreshStarted(owner, homeAvailable = true)
   reads.accountAccessChanged(owner, true)
   assertNull(reads.takeForHome(owner, home = true, ready = true))
   assertFalse(reads.blocksHomeObservation)
  }
 }

 @Test fun repeatedSuccessfulRefreshBeforeBackRetainsOnlyOneRecovery() {
  val reads = DesktopRenewalHistoryReads()
  reads.refreshStarted(owner, homeAvailable = false)
  reads.accountAccessChanged(owner, true)
  repeat(4) {
   reads.refreshStarted(owner, homeAvailable = true)
   assertNull(reads.pending)
   assertTrue(reads.blocksHomeObservation)
   reads.accountAccessChanged(owner, true)
  }
  assertNotNull(reads.takeForHome(owner, home = true, ready = true))
  assertNull(reads.takeForHome(owner, home = true, ready = true))
  reads.refreshStarted(owner, homeAvailable = true)
  reads.accountAccessChanged(owner, true)
  assertNull(reads.pending)
 }

 @Test fun laterFailureRevokesQueuedSuccessAndOnlyFreshSuccessCanRecover() {
  val reads = DesktopRenewalHistoryReads()
  reads.refreshStarted(owner, homeAvailable = false)
  reads.accountAccessChanged(owner, true)
  reads.refreshStarted(owner, homeAvailable = true)
  assertNull(reads.pending)
  reads.accountAccessChanged(owner, false) // error / still expired / disabled / unknown
  repeat(3) { assertNull(reads.takeForHome(owner, home = true, ready = true)) }
  assertTrue(reads.blocksHomeObservation, "stale verified session must not start observation or transfer")
  reads.accountAccessChanged(owner, true) // duplicate/stale completion cannot revive a completed refresh
  assertNull(reads.pending)
  reads.refreshStarted(owner, homeAvailable = false)
  reads.accountAccessChanged(owner, true)
  assertNotNull(reads.takeForHome(owner, home = true, ready = true))
 }

 @Test fun missingRefreshStartAndWrongAccountCannotQueueARead() {
  val reads = DesktopRenewalHistoryReads()
  reads.accountAccessChanged(owner, true)
  assertNull(reads.pending)
  reads.refreshStarted(owner, homeAvailable = false)
  reads.accountAccessChanged(owner.copy(username = "other"), true)
  assertNull(reads.pending)
  reads.accountAccessChanged(owner, false)
  assertNull(reads.pending)
 }

 @Test fun accountProfileAndCredentialChangesInvalidateQueuedOrInFlightRequests() {
  for (next in listOf(owner.copy(username = "other"), owner.copy(profile = "other"),
   owner.copy(endpoint = "https://dav.example.test"), owner.copy(password = "changed"),
   owner.copy(selectedSaveRoot = "/another"))) {
   val reads = DesktopRenewalHistoryReads()
   reads.refreshStarted(owner, homeAvailable = false)
   reads.accountAccessChanged(owner, true)
   val request = assertNotNull(reads.pending)
   assertNull(reads.takeForHome(next, home = true, ready = true))
   assertFalse(reads.isCurrent(request, owner))
   reads.accountAccessChanged(owner, true)
   assertNull(reads.pending)
  }
 }

 @Test fun closeResetAndNewRefreshRejectOlderExecutionTickets() {
  val reads = DesktopRenewalHistoryReads()
  reads.refreshStarted(owner, homeAvailable = false)
  reads.accountAccessChanged(owner, true)
  val old = assertNotNull(reads.takeForHome(owner, home = true, ready = true))
  reads.refreshStarted(owner, homeAvailable = true)
  assertFalse(reads.isCurrent(old, owner))
  reads.accountAccessChanged(owner, false)
  reads.reset()
  assertFalse(reads.isCurrent(old, owner))
  assertNull(reads.pending)
  assertFalse(reads.blocksHomeObservation)
 }

 @Test fun manualReadAcceptsOnlyFreshAvailableFactsAndConsumesOnePendingRecovery() {
  val reads = DesktopRenewalHistoryReads()
  assertFalse(reads.allowsManualRefresh(null))
  assertTrue(reads.allowsManualRefresh(owner), "the host separately requires a known active request lease")
  val initial = assertNotNull(reads.manualRequest(owner))
  assertTrue(reads.isManualCurrent(initial, owner))
  reads.refreshStarted(owner, homeAvailable = false)
  assertFalse(reads.isManualCurrent(initial, owner))
  assertFalse(reads.allowsManualRefresh(owner))
  assertNull(reads.manualRequest(owner))
  reads.accountAccessChanged(owner, false)
  assertFalse(reads.allowsManualRefresh(owner))
  reads.accountAccessChanged(owner, true)
  assertFalse(reads.allowsManualRefresh(owner), "late success cannot revive a failed refresh")
  reads.refreshStarted(owner, homeAvailable = false)
  reads.accountAccessChanged(owner, true)
  assertTrue(reads.allowsManualRefresh(owner))
  val current = assertNotNull(reads.manualRequest(owner))
  assertFalse(reads.isManualCurrent(initial, owner), "a newer same-account success cannot revive the older read")
  assertTrue(reads.isManualCurrent(current, owner))
  assertFalse(reads.isManualCurrent(current, owner.copy(profile = "other")))
  assertFalse(reads.allowsManualRefresh(owner.copy(username = "other")))
  val manualRecovery = assertNotNull(reads.takeForHome(owner, home = true, ready = true))
  assertTrue(reads.isCurrent(manualRecovery, owner))
  assertNull(reads.takeForHome(owner, home = true, ready = true), "manual refresh consumes the automatic recovery")
  reads.refreshStarted(owner, homeAvailable = true)
  assertFalse(reads.isManualCurrent(current, owner))
  assertFalse(reads.isCurrent(manualRecovery, owner))
  assertFalse(reads.allowsManualRefresh(owner))
 }

 @Test fun renewalSchedulingSuppressionDoesNotInventAnUploadFromOldBaselineOrPendingPlan() {
  val automatic = AutomaticUpload()
  automatic.establishBaseline(mapOf("save" to "old"))
  automatic.skipped()
  automatic.observe(mapOf("save" to "new"), running = false, stopped = true, now = 0L)
  assertFalse(automatic.shouldUpload(10000L, true, true, true, false, false, false))
  assertFalse(automatic.shouldUpload(10000L, true, true, true, true, false, false))
  automatic.observe(mapOf("save" to "later"), running = false, stopped = true, now = 11000L)
  assertTrue(automatic.shouldUpload(15000L, true, true, true, false, false, false),
   "a subsequent real file change still uses the existing automatic policy")
 }

 @Test fun savedRootWithoutSlashReceivesCanonicalSharedAccountFacts() {
  val saved = owner.copy(endpoint = DESKTOP_OFFICIAL_ROOT.removeSuffix("/"))
  val source = desktopConnectionConfig(saved, "secret")
  assertEquals(DESKTOP_OFFICIAL_ROOT, source.endpoint)
  assertEquals(desktopConnectionConfig(owner, "secret"), source)
  val access = DesktopSyncAccess { _, _, _ -> error("display update cannot authorize DAV") }
  access.observeAccountStatus(saved.endpoint, saved.username, 500L, 1000L)
  assertTrue(access.isExpired(saved))
  access.observeAccountStatus(saved.endpoint, saved.username, 1_000_000L, 1000L)
  assertFalse(access.isExpired(saved))
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val display = main.substringAfter("onAccountStatus = { source, expiry, serverTime ->")
   .substringBefore("onAccountRefreshStarted = {")
  assertContains(display, "source == desktopConnectionConfig(saved, password)")
  assertContains(display, "observeAccountStatus(saved.endpoint, saved.username, expiry, serverTime)")
 }

 @Test fun hostUsesExistingReadOnlyPipelineAndRejectsStalePagesBeforePublishing() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val remote = main.substringAfter("fun readRemote(").substringBefore("fun hasRemoteBranches()")
  assertContains(remote, "refreshProfile(readConfig, canPublish = ::currentPage)")
  assertContains(remote, "if (previousManager == null && !reopenLocal) return")
  assertContains(remote, "if (!localHistoryVerified) return@launch")
  assertContains(remote, "renewalReads.isCurrent(recovery, config)")
  assertContains(remote, "password == secret")
  assertContains(remote, "config == readConfig && session === manager")
  assertContains(remote, "currentPage() && session === readManager")
  assertTrue(remote.indexOf("refreshProfile(readConfig,") < remote.indexOf("manager.readHomeHistory("))
  assertTrue(remote.indexOf("if (!current()) return@launch") < remote.indexOf("remoteTips = loadedGraph.graph.tips"))
  assertEquals(1, Regex("manager\\.readHomeHistory\\(").findAll(remote).count())
  assertContains(remote, "syncAccess.store(manager.config, manager.adapter.id, secret)")
  assertContains(remote, "archiveReads.begin()")
  assertContains(remote, "finally { archiveReads.end(read); busy = false }")
  for (forbidden in listOf("ensureNamespace", "prepareSelectedDirectory = true", "execute(", ".push(", ".restore")) {
   assertFalse(remote.contains(forbidden), forbidden)
  }
  val loop = main.substringAfter("LaunchedEffect(session, connected)").substringBefore("LaunchedEffect(session, selected")
  assertTrue(loop.indexOf("renewalReads.blocksHomeObservation") < loop.indexOf("val manager = session"))
  assertContains(loop, "page == Page.HOME && pageGeneration == generation")
  val callbacks = main.substringAfter("onAccountRefreshStarted = {").substringBefore("onBusy = {")
  assertContains(callbacks, "current() && saved != null && source == desktopConnectionConfig(saved, password)")
  assertContains(callbacks, "localHistoryVerified && remoteInspectionReady")
  for (forbidden in listOf("readRemote(", "refreshProfile(", "execute(", "ensureNamespace")) {
   assertFalse(callbacks.contains(forbidden), "account callbacks only queue: $forbidden")
  }
  val authenticationFailure = main.substringAfter("fun authenticationFailed(").substringBefore("fun cancelConfirmation(")
  assertTrue(authenticationFailure.indexOf("renewalReads.reset()") < authenticationFailure.indexOf("password = \"\""))
  val applied = main.substringAfter("onApplied = { applied, note ->").substringBefore("Page.CONFLICT ->")
  assertContains(applied, "renewalReads.reset()")
 }
}
