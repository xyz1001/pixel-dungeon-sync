package pdsync

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopSelectedRestoreConfirmationTest {
 private val first = AppendCommit(id = "first", parents = emptyList(), objectBytes = 1,
  snapshot = Manifest(game = "shattered", profile = "home", version = "896", id = "old-save",
   parent = null, sha256 = "b".repeat(64), files = mapOf("game1/game.dat" to "a".repeat(64))))
 private val second = first.copy(id = "second", snapshot = first.snapshot.copy(id = "other-save"))
 private fun history() = SyncHistory(CommitGraph(listOf(first, second).associateBy { it.id }, emptyMap()),
  null, first.snapshot.files, "local", null, emptyMap())

 @Test fun explicitExistingHistoryIsAlreadyAChoiceEvenWithMultipleTipsAndProtectedLocal() {
  val observed = history()
  assertEquals(2, observed.graph.tips.size)
  assertTrue(desktopHasExplicitCloudRestoreSelection(observed, "old-save", true))
  assertFalse(desktopHasExplicitCloudRestoreSelection(observed, "old-save", false))
  assertFalse(desktopHasExplicitCloudRestoreSelection(observed, "missing-save", true))
  assertFalse(desktopHasExplicitCloudRestoreSelection(observed, null, true))
  assertFalse(desktopHasExplicitCloudRestoreSelection(null, "old-save", true))
 }

 @Test fun consentRetainsExactSourceAndRejectsChangedSelectionObservationSessionAndAccount() {
  val observed = history()
  val session = Any()
  val account = AppConfig(onboardingProgress = OnboardingProgress.COMPLETE)
  val consent = DesktopSelectedRestoreConfirmation("old-save", session, account, observed)
  assertEquals("old-save", consent.snapshotId)
  assertTrue(consent.isCurrent(session, account, observed, "old-save", true))
  assertFalse(consent.isCurrent(session, account, observed, "other-save", true))
  assertFalse(consent.isCurrent(session, account, observed, "old-save", false))
  assertFalse(consent.isCurrent(session, account, history(), "old-save", true))
  assertFalse(consent.isCurrent(Any(), account, observed, "old-save", true))
  assertFalse(consent.isCurrent(session, account.copy(username = "other-account"), observed, "old-save", true))
 }

 @Test fun productionFlowShowsOnlyOverwriteAndKeepsCancellationAndRepeatGuards() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val restore = main.substringAfter("fun openRestore() {").substringBefore("fun setAutomaticPaused(")
  val explicit = restore.substringAfter("if (desktopHasExplicitCloudRestoreSelection(").substringBefore("if (conflict")
  assertContains(explicit, "requestConfirmation(Action.CLOUD_HISTORY)")
  assertContains(explicit, "return")
  assertFalse(explicit.contains("execute("))
  assertTrue(restore.indexOf("desktopHasExplicitCloudRestoreSelection") < restore.indexOf("hasRemoteBranches()"))
  val modal = main.substringAfter("Page.CONFIRM -> confirm?.let { trace ->")
  assertContains(modal, "onBack = { if (confirm === trace && page == Page.CONFIRM) cancelConfirmation(trace) }")
  assertContains(modal, "confirm === trace && page == Page.CONFIRM && confirmationOwner?.isCurrent(session, config, graphHistory, selected, selectedByUser) != false")
  assertContains(modal, "onMerge = if(trace.action==Action.CLOUD_HISTORY && confirmationSelected == null)")
  assertContains(modal, "else confirmationManifest")
  assertContains(main, "val chosen = if (confirm === trace && confirmationSelected != null) confirmationSelected else selected")
  assertContains(main.substringAfter("fun cancelConfirmation(trace:"), "confirm = null; confirmationNotice = null; navigate(Page.HOME)")
  assertContains(main.substringAfter("fun execute(trace:"), "busy = true; homeOperation = desktopHomeOperation(action); confirm = null;")
 }
}
