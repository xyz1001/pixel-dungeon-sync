package pdsync

import io.github.xyz1001.pdsync.android.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopConflictDialogTest {
 @TempDir lateinit var temp: Path

 private fun session(name: String) = AppendProfileSession(
  AppConfig(saveRoot = temp.resolve(name).createDirectory().toString(), username = "account-a"),
  temp.resolve("$name-state"), captureQuietMillis = 0)

 @Test fun observationOwnerRejectsAccountSessionAndNavigationChangesIncludingReopenedSameConflict() {
  val manager = session("first")
  val conflict = AppendConflict(emptySet(), emptyList(), emptyMap())
  val owner = DesktopConflictDialogOwner(4, manager, manager.config, conflict)
  assertTrue(owner.matches(owner.copy(), pageActive = true))
  assertFalse(owner.matches(owner, pageActive = false), "dismissal immediately deactivates retained callbacks")
  for (changed in listOf(
   owner.copy(generation = 5),
   owner.copy(session = session("second")),
   owner.copy(session = null),
   owner.copy(config = manager.config.copy(username = "account-b")),
   owner.copy(conflict = conflict.copy()),
   owner.copy(conflict = null),
  )) assertFalse(owner.matches(changed, pageActive = true), changed.toString())
  assertFalse(owner.copy(session = null).matches(owner, pageActive = true))
  assertFalse(owner.copy(conflict = null).matches(owner, pageActive = true))
  // The chooser can be opened again using the same observation, but its old callbacks cannot.
  val reopened = owner.copy(generation = 6)
  assertTrue(reopened.matches(reopened, pageActive = true))
  assertFalse(owner.matches(reopened, pageActive = true))
 }

 @Test fun retainedConfirmationCannotDispatchAfterHomeRefreshOrAccountChangeEvenBeforeUiDisposal() {
  val manager = session("fixture")
  val observed = AppendConflict(emptySet(), emptyList(), emptyMap())
  val owner = DesktopConflictDialogOwner(1, manager, manager.config, observed)
  for (changed in listOf(
   owner.copy(generation = 2),
   owner.copy(config = manager.config.copy(username = "other")),
   owner.copy(conflict = observed.copy()),
   owner.copy(conflict = null),
  )) {
   var currentOwner = owner
   var active = true
   var writes = 0
   val state = SharedBranchChoiceState(observed,
    listOf(SharedBranchChoice("selected", "fixture", "synthetic")),
    selectedId = "selected", pageActive = true, connected = true, observationCurrent = true,
    overwriteSupported = true)
   val actions = object : SharedBranchChoiceActions {
    override fun select(snapshotId: String) = Unit
    override fun back() { active = false }
    override fun refresh() = Unit
    override fun overwrite(snapshotId: String) {
     if (owner.matches(currentOwner, active)) writes++
    }
    override fun preserveLocal() = Unit
   }
   val interactions = SharedBranchChoiceInteractions({ state }, { actions })
   interactions.primary()
   val overwrite = assertNotNull(interactions.presentation).token
   currentOwner = changed
   interactions.confirm(overwrite)
   assertEquals(0, writes, "host proof must reject a retained callback independently of recomposition")
   interactions.primary()
   assertNotNull(interactions.presentation)
   assertEquals(0, writes)
   interactions.dispose()
  }
 }

 @Test fun cancelAndRepeatedConfirmationPreserveOneShotDispatch() {
  val manager = session("cancel")
  val observed = AppendConflict(emptySet(), emptyList(), emptyMap())
  val owner = DesktopConflictDialogOwner(1, manager, manager.config, observed)
  var current = owner
  var home = false
  var writes = 0
  val state = SharedBranchChoiceState(observed,
   listOf(SharedBranchChoice("selected", "fixture", "synthetic")),
   selectedId = "selected", pageActive = true, connected = true, observationCurrent = true,
   overwriteSupported = true)
  val interactions = SharedBranchChoiceInteractions({ state }, { object : SharedBranchChoiceActions {
   override fun select(snapshotId: String) = Unit
   override fun back() {
    if (owner.matches(current, !home)) { home = true; current = current.copy(generation = current.generation + 1) }
   }
   override fun refresh() = back()
   override fun overwrite(snapshotId: String) {
    if (owner.matches(current, !home)) {
     writes++
     home = true
     current = current.copy(generation = current.generation + 1)
    }
   }
   override fun preserveLocal() = Unit
  } })
  interactions.primary()
  val canceled = assertNotNull(interactions.presentation).token
  interactions.cancel(canceled)
  interactions.confirm(canceled)
  assertEquals(0, writes)
  assertFalse(home, "canceling the existing operation confirmation returns to the chooser")
  interactions.primary()
  val accepted = assertNotNull(interactions.presentation).token
  interactions.confirm(accepted)
  interactions.confirm(accepted)
  interactions.back()
  assertEquals(1, writes)
  assertTrue(home)
  assertEquals(2L, current.generation)
  interactions.dispose()
 }

 @Test fun failedOrCanceledCompatibilityReadNeverBecomesAnExecutableResult() {
  assertNull(desktopConflictCompatibilityIssue {})
  assertEquals("incompatible", desktopConflictCompatibilityIssue { error("incompatible") })
  assertFalse(desktopConflictCompatibilityIssue { throw IllegalStateException() }.isNullOrBlank())
  assertFalse(desktopConflictCompatibilityIssue { error("") }.isNullOrBlank())
  assertFailsWith<CancellationException> {
   desktopConflictCompatibilityIssue { throw CancellationException("stale read") }
  }
 }

 @Test fun ordinaryUploadStillDispatchesDirectlyAndConflictUsesAnInactiveHomeBackdrop() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  val upload = main.substringAfter("override fun upload() {").substringBefore("override fun restore()")
  assertContains(upload, "if (!homeState().uploadEnabled) return")
  assertContains(upload, "else execute(operationEvents.begin(Action.PUSH))")
  assertFalse(upload.contains("requestConfirmation"))
  val modal = main.substringAfter("Page.CONFLICT -> key(pageGeneration)").substringBefore("Page.CONFIRM ->")
  assertContains(modal, "SharedHomeScreen(homeState().copy(pageActive = false)")
  assertContains(modal, "fun current() = !appClosed && !busy && !connectingAtStartup &&")
  assertContains(modal, "owner.matches(conflictOwner(), page == Page.CONFLICT)")
  assertContains(modal, "fun canResolve() = current() && connected && !serviceExpired && !pending")
  assertContains(modal, "onBack = { if (current()) navigate(Page.HOME) }")
  val overwrite = modal.substringAfter("onConfirm = { id ->").substringBefore("onMerge =")
  assertContains(overwrite, "if (canResolve() && overwriteTarget == id &&")
  assertContains(overwrite, "value.branches.any { it.snapshot.manifest.id == id }) {")
  assertContains(overwrite, "conflictSelection = id")
  assertContains(overwrite, "execute(operationEvents.begin(Action.RESOLVE))")
  assertFalse(overwrite.contains("conflictRestoreIssue"), "cloud overwrite must not depend on reverse-restore compatibility")
  assertContains(main, "Action.RESOLVE -> pushed(manager.overwriteCloudConflict(")
  assertTrue(modal.indexOf("SharedHomeScreen") < modal.indexOf("ConflictDialog("))
  val chooser = main.substringAfter("private fun ConflictDialog(").substringBefore("private fun ConfirmationPage(")
  assertContains(chooser, "SharedBranchChoiceDialog(state")
  assertFalse(chooser.contains("SharedBranchChoiceScreen"))
  assertContains(chooser, "if (snapshotId == selected) onConfirm(snapshotId)")
  assertContains(chooser, "selectedId = selected, game = game, pageActive = true, busy = busy, connected = connected")
  assertContains(chooser, "observationCurrent = true, resolutionBlocked = pending")
  assertContains(chooser, "overwriteSupported = true, cloudOverwrite = true")
 }

 @Test fun cloudOverwriteDoesNotRunReverseRestoreChecksOrReviveDismissedModal() {
  val main = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  assertFalse(main.contains("manager.checkResolution(id)"), "cloud deletion does not restore a save into the local game")
  val modal = main.substringAfter("Page.CONFLICT -> key(pageGeneration)").substringBefore("Page.CONFIRM ->")
  assertContains(modal, "val overwriteTarget = value.overwriteTargetSnapshotId")
  assertContains(modal, "onSelect = { }")
  assertFalse(modal.contains("onMerge"))
  val navigation = main.substringAfter("fun navigate(next: Page)").substringBefore("suspend fun reconnectSaved")
  assertContains(navigation, "pageGeneration++; page = next")
  val refresh = main.substringAfter("fun readRemote(").substringBefore("fun hasRemoteBranches()")
  assertFalse(refresh.contains("navigate(Page.CONFLICT)"), "home refresh cannot revive a dismissed chooser")
 }
}
