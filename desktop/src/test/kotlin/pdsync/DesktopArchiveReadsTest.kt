package pdsync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopArchiveReadsTest {
 @Test fun hostTracksRealReadsAndGuardsTheirPublication() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val profile = main.substringAfter("suspend fun refreshProfile(").substringBefore("fun loadProfile(")
  assertContains(profile, "val read = archiveReads.begin()")
  assertContains(profile, "finally { archiveReads.end(read) }")
  assertContains(profile, "page == readPage && pageGeneration == generation && config == value")
  assertTrue(profile.indexOf("if (!current()) return") < profile.indexOf("session = data?.session"))
  val remote = main.substringAfter("fun readRemote(").substringBefore("fun hasRemoteBranches()")
  assertContains(remote, "config == readConfig && session === manager")
  assertTrue(remote.indexOf("if (!current()) return@launch") < remote.indexOf("remoteTips = setOfNotNull(loadedGraph.simple?.latest)"))
  assertContains(remote, "catch (e: CancellationException) { throw e }")
  assertContains(remote, "finally { archiveReads.end(read); busy = false }")
  assertEquals(1, Regex("manager\\.readHomeHistory\\(").findAll(remote).count())
  val navigation = main.substringAfter("fun navigate(next: Page) {").substringBefore("suspend fun reconnectSaved")
  assertContains(navigation, "archiveReads.invalidate()")
 }

 @Test fun transferEntryAndReadFailuresCannotReuseOldArchiveEligibility() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val execute = main.substringAfter("fun execute(").substringBefore("fun editDirectory(")
  assertTrue(execute.indexOf("desktopHomeTransferAllowed(action, homeState())") < execute.indexOf("OPERATION_QUEUED"))
  assertContains(execute, "OperationGuard.NO_SOURCE")
  assertContains(execute, "!isAutomatic && (action == Action.PUSH && !connected ||")
  assertContains(execute, "localHistoryVerified = false; remoteInspectionReady = false; pendingUploadSourceAvailable = false")
  assertContains(main.substringAfter("fun openRestore() {").substringBefore("fun setAutomaticPaused("),
   "if (!homeState().restoreEnabled) return")
  assertContains(main.substringAfter("fun requestConfirmation(").substringBefore("suspend fun refreshProfile("),
   "if (!desktopHomeTransferAllowed(action, homeState())) return")
  val profile = main.substringAfter("suspend fun refreshProfile(").substringBefore("fun loadProfile(")
  assertTrue(profile.indexOf("localHistoryVerified = false") < profile.indexOf("withContext(Dispatchers.IO)"))
  assertContains(profile, "localHistoryVerified = loadedHistory != null")
  assertContains(profile, "pendingUploadSourceAvailable = data?.pendingSourceAvailable == true")
  val observationFailure = main.substringAfter("catch (failure: Exception)").substringBefore("LaunchedEffect(session, selected")
  assertContains(observationFailure, "pageGeneration == observedGeneration && config == observedConfig && session === manager")
  assertContains(observationFailure, "localHistoryVerified = false; pendingUploadSourceAvailable = false")
  val selection = main.substringAfter("LaunchedEffect(session, selected").substringBefore("LaunchedEffect(session, conflict")
  assertContains(selection, "session === manager && selected == id && config == owner")
  assertContains(selection, "pageGeneration == generation && targetInfo == target && graphHistory === observedHistory")
 }

 @Test fun concurrentReadsKeepLoadingUntilLastCompletion() {
  val reads = DesktopArchiveReads()
  assertFalse(reads.loading)
  val local = reads.begin(); val cloud = reads.begin()
  reads.end(local)
  assertTrue(reads.loading)
  reads.end(local) // duplicate completion is harmless
  assertTrue(reads.loading)
  reads.end(cloud)
  assertFalse(reads.loading)
 }
 @Test fun eitherSlowReadKeepsOverlayAfterOtherSideFailsOrFinishes() = runBlocking {
  for (slowLocal in listOf(true, false)) for (failure in listOf(true, false)) {
   val reads = DesktopArchiveReads()
   val localDone = CompletableDeferred<Unit>(); val cloudDone = CompletableDeferred<Unit>()
   val local = launch(start = CoroutineStart.UNDISPATCHED) {
    runCatching { reads.reading { localDone.await(); if (!slowLocal && failure) error("local failed") } }
   }
   val cloud = launch(start = CoroutineStart.UNDISPATCHED) {
    runCatching { reads.reading { cloudDone.await(); if (slowLocal && failure) error("cloud failed") } }
   }
   assertTrue(reads.loading)
   if (slowLocal) { cloudDone.complete(Unit); cloud.join() }
   else { localDone.complete(Unit); local.join() }
   assertTrue(reads.loading, "the slower read must keep the overlay, including after other-side failure")
   localDone.complete(Unit); cloudDone.complete(Unit); local.join(); cloud.join()
   assertFalse(reads.loading)
  }
 }
 @Test fun oldPageCompletionCannotDismissNewPageRead() {
  val reads = DesktopArchiveReads()
  val stale = reads.begin()
  reads.invalidate()
  assertFalse(reads.loading)
  val current = reads.begin()
  reads.end(stale)
  assertTrue(reads.loading)
  reads.end(current)
  assertFalse(reads.loading)
 }
 @Test fun successFailureAndCancellationAlwaysReleaseTheirOwnLease() = runBlocking {
  val reads = DesktopArchiveReads()
  assertEquals(42, reads.reading { assertTrue(reads.loading); 42 })
  assertFalse(reads.loading)
  assertFailsWith<IllegalStateException> { reads.reading { error("unreadable") } }
  assertFalse(reads.loading)
  assertFailsWith<CancellationException> { reads.reading { throw CancellationException("canceled") } }
  assertFalse(reads.loading)
  val waiting = CompletableDeferred<Unit>()
  val job = launch(start = CoroutineStart.UNDISPATCHED) { reads.reading { waiting.await() } }
  assertTrue(reads.loading)
  job.cancelAndJoin()
  assertFalse(reads.loading)
 }
}
