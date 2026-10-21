package pdsync

import io.github.xyz1001.pdsync.android.SharedGameIdentity
import io.github.xyz1001.pdsync.android.SharedHomeOperation
import io.github.xyz1001.pdsync.android.SharedHomeStatusKind
import io.github.xyz1001.pdsync.android.showAppreciation
import io.github.xyz1001.pdsync.android.launchEnabled
import io.github.xyz1001.pdsync.android.restoreEnabled
import io.github.xyz1001.pdsync.android.targetTitle
import io.github.xyz1001.pdsync.android.uploadEnabled
import io.github.xyz1001.pdsync.android.refreshHistoryEnabled
import org.junit.jupiter.api.Test
import pdsync.presentation.HistoryPresentation
import kotlin.test.*

class DesktopSharedHomeAdapterTest {
 private val files = mapOf("game1/game.dat" to "a".repeat(64))
 private val config = AppConfig(username = "real-account", profile = "home", onboardingProgress = OnboardingProgress.COMPLETE)
 private fun commit(id: String, parents: List<String> = emptyList(), savedAt: String? = "2026-10-12T01:00:00Z",
  content: Map<String, String> = files) =
  AppendCommit(id = id, parents = parents, objectBytes = 1,
   snapshot = Manifest(game = "shattered", profile = "home", version = "922", id = "snapshot-$id",
    parent = null, sha256 = "b".repeat(64), files = content,
    metadata = SnapshotMetadata(device = DeviceIdentity("device", "真实设备"), capturedAt = "2026-10-15T01:00:00Z",
     fileModifiedAt = savedAt?.let { mapOf("game1/game.dat" to it) }.orEmpty())))
 private fun history(commits: List<AppendCommit> = listOf(commit("tip"))) =
  SyncHistory(CommitGraph(commits.associateBy { it.id }, emptyMap()), null, files, "本机名称",
   SaveSummary(mapOf("game1" to "2026-10-13T01:00:00Z"), null, 1), emptyMap())
 private fun ready(history: SyncHistory = history()) = DesktopHomeContext(
  config = config,
  hasSession = true, pageActive = true, busy = false, connectingAtStartup = false, appClosed = false,
  connected = true, gameStatus = GameProcessStatus.Stopped, launchingGame = false,
  pendingUpload = false, hasConflict = false, remoteTips = setOf("snapshot-tip"),
  selectedId = history.defaultSnapshot, selectedByUser = false, restoreIssue = null,
  history = history, historyPage = 0, game = SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"),
  automaticStatus = "自动上传已开启。",
  historyOwner = config, historyVerified = true, localHistoryVerified = true,
 )

 @Test fun metadataEqualityDisablesHomeTransfersWithoutUpgradingObjectProof() {
  val observed = history().let { it.copy(
   graph = it.graph.copy(verification = RemoteVerification.METADATA_ONLY), verifiedCommitIds = emptySet()) }
  val before = observed.copy(currentFiles = observed.currentFiles.toMap())
  val state = DesktopSharedHomeAdapter.state(ready(observed))
  assertEquals("进度已同步 · 本机与云端档案完全一致", state.status)
  assertEquals(SharedHomeStatusKind.SYNCED, state.statusKind)
  val local = state.history!!.nodes.single { it.id == "local-current" }
  assertEquals("已同步云端", local.status)
  assertFalse(local.dirty)
  assertTrue(state.history.edges.single().sameContent)
  // Fresh unique-tip equality suppresses HOME actions without fabricating object proof.
  assertFalse(state.canUpload); assertFalse(state.canRestore)
  assertFalse(desktopHomeTransferAllowed(OperationAction.PUSH, state))
  assertFalse(desktopHomeTransferAllowed(OperationAction.RESTORE, state))
  assertFalse(observed.currentProtected); assertFalse(observed.clean)
  assertNull(observed.baseline); assertNull(observed.baselineCommit); assertNull(observed.baselineDirection)
  assertTrue(observed.verifiedCommitIds.isEmpty())
  assertEquals(RemoteVerification.METADATA_ONLY, observed.graph.verification)
  assertEquals(before, observed)
  val base = ready(observed)
  for (blocked in listOf(base.copy(historyVerified = false), base.copy(localHistoryVerified = false),
   base.copy(historyOwner = config.copy(username = "previous-account")), base.copy(historyOwner = null),
   base.copy(locationIssue = "failed local read"), base.copy(remoteTips = emptySet()),
   base.copy(pendingUpload = true), base.copy(hasConflict = true), base.copy(serviceExpired = true),
   base.copy(history = observed.copy(graph = observed.graph.copy(pending = mapOf("missing" to "unverified")))))) {
   val invalid = DesktopSharedHomeAdapter.state(blocked)
   assertNotEquals(state.status, invalid.status)
   assertNotEquals(SharedHomeStatusKind.SYNCED, invalid.statusKind)
  }
  val branches = history(listOf(commit("left"), commit("right"))).let { it.copy(
   graph = it.graph.copy(verification = RemoteVerification.METADATA_ONLY), verifiedCommitIds = emptySet()) }
  val branched = DesktopSharedHomeAdapter.state(ready(branches).copy(
   remoteTips = setOf("snapshot-left", "snapshot-right"), selectedId = "snapshot-left"))
  assertEquals(SharedHomeStatusKind.CONFLICT, branched.statusKind)
  assertNotEquals(state.status, branched.status)
  val changed = observed.copy(currentFiles = mapOf("game1/game.dat" to "e".repeat(64)))
  val different = DesktopSharedHomeAdapter.state(ready(changed))
  assertNotEquals(state.status, different.status)
  assertNotEquals(SharedHomeStatusKind.SYNCED, different.statusKind)
  assertTrue(different.history!!.nodes.single { it.id == "local-current" }.dirty)
  assertTrue(different.canUpload); assertTrue(different.canRestore)
  val old = commit("old", content = mapOf("game1/game.dat" to "e".repeat(64)))
  val tip = commit("tip", parents = listOf("old"))
  val historical = history(listOf(old, tip)).copy(verifiedCommitIds = emptySet())
  val selected = DesktopSharedHomeAdapter.state(ready(historical).copy(selectedId = "snapshot-old"))
  assertFalse(selected.canUpload)
  assertTrue(selected.canRestore, "A genuinely different historical restore remains actionable")
 }

 @Test fun expiredAuthenticatedAccountDisablesTransfersButKeepsLaunchAndHistory() {
  val base = ready()
  val state = DesktopSharedHomeAdapter.state(base.copy(serviceExpired = true))
  assertFalse(state.uploadEnabled)
  assertFalse(state.restoreEnabled)
  assertTrue(state.launchEnabled)
  assertEquals(pdsync.i18n.CoreSessionMessages.SERVICE_EXPIRED.text(), state.status)
  assertEquals(SharedHomeStatusKind.EXPIRED, state.statusKind)
  assertNotNull(state.restoreTarget)
 }

 @Test fun historyRefreshRequiresKnownCurrentAccessButNeverRequiresAnUploadSourceOrOldHistory() {
  val base = ready().copy(historyRefreshAccessKnown = true)
  for (available in listOf(base, base.copy(history = null, hasSession = false, selectedId = null),
   base.copy(history = history(emptyList()).copy(currentFiles = emptyMap()), remoteTips = emptySet()),
   base.copy(localHistoryVerified = false, historyVerified = false, historyOwner = null,
    locationIssue = "previous local read failed", game = null),
   base.copy(pendingUpload = true, pendingUploadSourceAvailable = false),
   base.copy(gameStatus = GameProcessStatus.Running(setOf(1L))))) {
   assertTrue(DesktopSharedHomeAdapter.state(available).refreshHistoryEnabled)
  }
  for (blocked in listOf(base.copy(historyRefreshAccessKnown = false), base.copy(connected = false),
   base.copy(serviceExpired = true), base.copy(config = null),
   base.copy(config = config.copy(onboardingProgress = OnboardingProgress.PENDING)),
   base.copy(busy = true), base.copy(archiveLoading = true), base.copy(pageActive = false),
   base.copy(connectingAtStartup = true), base.copy(appClosed = true))) {
   assertFalse(DesktopSharedHomeAdapter.state(blocked).refreshHistoryEnabled)
  }
 }

 @Test fun appreciationOnlyAppearsForTheCurrentExpiredBuiltinIdentity() {
  val official = config.copy(endpoint = DESKTOP_OFFICIAL_ROOT, connectionProvider = "official")
  val base = ready().copy(config = official, serviceExpired = true)
  val expired = DesktopSharedHomeAdapter.state(base)
  assertTrue(expired.showAppreciation)
  assertTrue(desktopHomeAppreciationAllowed(official, expired))
  for (changed in listOf(null, official.copy(connectionProvider = "custom"),
   official.copy(endpoint = "https://dav.example.test/"), official.copy(username = ""))) {
   val unknown = DesktopSharedHomeAdapter.state(base.copy(config = changed))
   assertFalse(unknown.showAppreciation)
   assertFalse(desktopHomeAppreciationAllowed(changed, expired))
  }
  for (blocked in listOf(base.copy(busy = true), base.copy(busy = true, archiveLoading = true),
   base.copy(pageActive = false), base.copy(appClosed = true), base.copy(connectingAtStartup = true))) {
   assertFalse(desktopHomeAppreciationAllowed(official, DesktopSharedHomeAdapter.state(blocked)))
  }
  assertTrue(desktopHomeAppreciationAllowed(official, DesktopSharedHomeAdapter.state(base.copy(archiveLoading = true))),
   "A read-only HOME refresh does not block the existing appreciation navigation")
  assertFalse(DesktopSharedHomeAdapter.state(base.copy(serviceExpired = false)).showAppreciation)
 }

 @Test fun expiryReplacesTheExistingStatusAndNeverDuplicatesIndependentNotice() {
  val expiredText = pdsync.i18n.CoreSessionMessages.SERVICE_EXPIRED.text()
  val base = ready().copy(serviceExpired = true, operationStatus = expiredText,
   connectionNotice = "$expiredText\n请重新检查连接。", automaticStatus = expiredText)
  val expired = DesktopSharedHomeAdapter.state(base.copy(busy = true))
  assertEquals(expiredText, expired.status)
  assertEquals(SharedHomeStatusKind.EXPIRED, expired.statusKind)
  assertEquals("请重新检查连接。", expired.notice)
  val renewed = DesktopSharedHomeAdapter.state(base.copy(serviceExpired = false, connectionNotice = expiredText,
   historyVerified = false))
  assertNotEquals(expiredText, renewed.status)
  assertEquals("", renewed.notice)
  assertFalse(renewed.showAppreciation)
 }

 @Test fun archiveLoadingIsIndependentOfUnrelatedBusyWorkAndHiddenOffHome() {
  val base = ready()
  assertFalse(DesktopSharedHomeAdapter.state(base.copy(busy = true)).archiveLoading)
  assertFalse(DesktopSharedHomeAdapter.state(base.copy(connectingAtStartup = true)).archiveLoading)
  val reading = DesktopSharedHomeAdapter.state(base.copy(archiveLoading = true))
  assertTrue(reading.archiveLoading)
  assertFalse(reading.uploadEnabled)
  assertFalse(reading.restoreEnabled)
  assertFalse(reading.launchEnabled)
  assertFalse(DesktopSharedHomeAdapter.state(base.copy(archiveLoading = true, pageActive = false)).archiveLoading)
  assertFalse(DesktopSharedHomeAdapter.state(base.copy(archiveLoading = true, appClosed = true)).archiveLoading)
 }

 @Test fun uniqueDefaultTargetIsRealButDoesNotPretendTheUserSelectedIt() {
  val history = history().copy(aliases = mapOf("snapshot-tip" to "远征记录"))
  val state = DesktopSharedHomeAdapter.state(ready(history))
  assertEquals("默认恢复最新云端存档", state.targetTitle)
  assertNull(state.selectedSnapshotId)
  val target = assertNotNull(state.restoreTarget)
  assertEquals("snapshot-tip", target.snapshotId)
  assertEquals("远征记录", target.name)
  assertEquals("真实设备", target.source)
  assertEquals(HistoryPresentation.time("2026-10-12T01:00:00Z"), target.time)
  assertEquals(SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"), state.game)
 }

 @Test fun explicitChoiceSurvivesPagingRefreshAndAChangedUniqueTip() {
  val commits = (0..40).map { index -> commit("commit-%03d".format(index),
   if (index > 0) listOf("commit-%03d".format(index - 1)) else emptyList(),
   content = mapOf("game1/game.dat" to hash("content-$index".encodeToByteArray()))) }
  val history = history(commits)
  val selected = "snapshot-commit-040"
  for (page in 0..2) {
   val state = DesktopSharedHomeAdapter.state(ready(history).copy(
    selectedId = selected, selectedByUser = true, historyPage = page))
   assertEquals("已选择存档", state.targetTitle)
   assertEquals(selected, state.selectedSnapshotId)
   assertEquals(selected, state.restoreTarget?.snapshotId)
   assertEquals(page, state.history?.page)
  }
  val refreshed = history.copy(graph = CommitGraph((commits + commit("new-tip", listOf("commit-040"))).associateBy { it.id }, emptyMap()))
  assertEquals(DesktopHomeSelection(selected, true), desktopHomeSelection(refreshed, selected, true))
  assertEquals(DesktopHomeSelection("snapshot-new-tip", false), desktopHomeSelection(refreshed, selected, false))
  assertEquals(DesktopHomeSelection("snapshot-new-tip", false), desktopHomeSelection(refreshed, "missing", true))
 }

 @Test fun duplicateContentMapsOnlyTheVisibleSelectionAndPreservesTheActualRestoreTarget() {
  val old = commit("old")
  val tip = commit("tip", listOf(old.id))
  val observed = history(listOf(old, tip))
  val context = ready(observed).copy(selectedId = old.snapshot.id, selectedByUser = true)
  val state = DesktopSharedHomeAdapter.state(context)
  assertEquals(1, state.history!!.cloudCount)
  assertEquals(tip.snapshot.id, state.selectedSnapshotId)
  assertEquals(old.snapshot.id, state.restoreTarget!!.snapshotId)
  assertEquals(old.snapshot.id, context.selectedId)
  assertFalse(state.restoreEnabled)
  assertFalse(state.uploadEnabled)
  assertEquals(2, observed.graph.commits.size)
  assertNull(observed.baseline)
  val different = commit("different", parents = listOf(old.id), content = mapOf("game1/game.dat" to "e".repeat(64)))
  val refreshed = history(listOf(old, different, tip.copy(parents = listOf(different.id))))
  val selectedDifferent = DesktopSharedHomeAdapter.state(ready(refreshed).copy(
   selectedId = different.snapshot.id, selectedByUser = true))
  assertEquals(different.snapshot.id, selectedDifferent.selectedSnapshotId)
  assertTrue(selectedDifferent.restoreEnabled)
 }

 @Test fun multiHeadHistoryNeverInventsADefaultOrHidesBranchSelection() {
  val history = history(listOf(commit("left"), commit("right", content = mapOf("game1/game.dat" to "e".repeat(64)))))
  val state = DesktopSharedHomeAdapter.state(ready(history).copy(remoteTips = setOf("snapshot-left", "snapshot-right")))
  assertNull(state.restoreTarget)
  assertNull(state.selectedSnapshotId)
  assertContains(state.emptyTargetMessage, "多份进度")
  assertTrue(state.canRestore)
  assertEquals(setOf("snapshot-left", "snapshot-right"), state.history!!.nodes.filter { it.selectable }.map { it.snapshotId }.toSet())
  assertEquals(DesktopHomeSelection(null, false), desktopHomeSelection(history, "missing", true))
 }

 @Test fun identityChangesDiscardSelectionWhileRoutineSettingsChangesDoNot() {
  val config = ready().config!!.copy(selectedSaveRoot = "/saves", targetGame = "/game.jar")
  assertTrue(sameDesktopHomeProfile(config, config.copy(autoUploadPaused = true, password = "changed",
   onboardingProgress = OnboardingProgress.PENDING)))
  assertTrue(sameDesktopHomeProfile(config, config.copy(endpoint = config.endpoint + "/")))
  for (changed in listOf(config.copy(username = "other"), config.copy(profile = "other"),
   config.copy(endpoint = "https://other.invalid"), config.copy(connectionProvider = "official"),
   config.copy(targetGame = "/other.jar"), config.copy(selectedSaveRoot = "/other-saves"))) {
   assertFalse(sameDesktopHomeProfile(config, changed))
  }
  assertFalse(sameDesktopHomeProfile(null, config))
 }

 @Test fun unverifiedReadsCannotEnableEitherTransferEvenDuringUnfinishedSetup() {
  val base = ready().copy(historyVerified = false)
  for (pending in listOf(false, true)) for (complete in listOf(false, true))
   for (conflict in listOf(false, true)) for (tips in listOf(emptySet(), setOf("a"), setOf("a", "b")))
    for (selected in listOf(null, "snapshot-tip")) for (issue in listOf(null, "不兼容")) {
     val current = base.copy(pendingUpload = pending, pendingUploadSourceAvailable = pending,
      config = base.config!!.copy(onboardingProgress = if (complete) OnboardingProgress.COMPLETE else OnboardingProgress.PENDING),
      hasConflict = conflict, remoteTips = tips, selectedId = selected, restoreIssue = issue)
     val state = DesktopSharedHomeAdapter.state(current)
     assertFalse(state.canUpload); assertFalse(state.canRestore)
     assertTrue(state.canLaunch)
    }
 }

 @Test fun verifiedFullContentEqualityDisablesOnlyTheUnnecessaryTransfers() {
  val same = DesktopSharedHomeAdapter.state(ready())
  assertFalse(same.canUpload); assertFalse(same.canRestore); assertTrue(same.canLaunch)
  assertEquals("进度已同步 · 本机与云端档案完全一致", same.status)
  assertEquals("本机已是此进度，无需恢复。", same.restoreTarget?.notice)

  // A changed global record is still a different save even when slot/play counts and names match.
  val different = history().copy(currentFiles = files + ("badges.dat" to "c".repeat(64)))
  val changed = DesktopSharedHomeAdapter.state(ready(different))
  assertTrue(changed.canUpload); assertTrue(changed.canRestore)
  assertNotEquals("进度已同步 · 本机与云端档案完全一致", changed.status)
  assertEquals("", changed.restoreTarget?.notice)
 }

 @Test fun selectedIdenticalHistoryDoesNotMistakeTheDifferentLatestTipForSynced() {
  val history = history(listOf(commit("old"), commit("tip", listOf("old"),
   content = mapOf("game1/game.dat" to "c".repeat(64)))))
  val selected = DesktopSharedHomeAdapter.state(ready(history).copy(selectedId = "snapshot-old", selectedByUser = true))
  assertTrue(selected.canUpload); assertFalse(selected.canRestore)
  assertEquals("snapshot-old", selected.restoreTarget?.snapshotId)
  assertNotEquals("进度已同步 · 本机与云端档案完全一致", selected.status)
  val latest = DesktopSharedHomeAdapter.state(ready(history))
  assertTrue(latest.canUpload); assertTrue(latest.canRestore)
 }

 @Test fun equalityNeverHidesPendingBranchRecoveryOrUnfinishedSetup() {
  val pending = DesktopSharedHomeAdapter.state(ready().copy(pendingUpload = true, pendingUploadSourceAvailable = true))
  assertTrue(pending.canUpload); assertFalse(pending.canRestore)
  assertContains(pending.status, "重试上传")
  val conflict = DesktopSharedHomeAdapter.state(ready().copy(hasConflict = true))
  assertTrue(conflict.canUpload); assertTrue(conflict.canRestore)
  val branched = history(listOf(commit("left"), commit("right")))
  val branch = DesktopSharedHomeAdapter.state(ready(branched).copy(
   remoteTips = setOf("snapshot-left", "snapshot-right"), selectedId = "snapshot-left"))
  assertTrue(branch.canUpload); assertTrue(branch.canRestore)
  val setup = DesktopSharedHomeAdapter.state(ready().copy(config = config.copy(onboardingProgress = OnboardingProgress.PENDING)))
  assertTrue(setup.canUpload); assertTrue(setup.canRestore)
 }

 @Test fun unknownFailedOrOtherAccountHistoryCannotClaimIdenticalContent() {
  val base = ready()
  for (unverified in listOf(base.copy(historyVerified = false), base.copy(localHistoryVerified = false), base.copy(historyOwner = null),
   base.copy(historyOwner = config.copy(username = "previous-account")),
   base.copy(historyOwner = config.copy(profile = "previous-profile")),
   base.copy(locationIssue = "Unable to read save"), base.copy(remoteTips = emptySet()),
   base.copy(history = base.history!!.copy(graph = base.history.graph.copy(pending = mapOf("missing" to "unverified")))))) {
   val state = DesktopSharedHomeAdapter.state(unverified)
   assertFalse(state.canUpload); assertFalse(state.canRestore)
   assertNotEquals("进度已同步 · 本机与云端档案完全一致", state.status)
   assertEquals("", state.restoreTarget?.notice)
  }
 }

 @Test fun startupBusyClosedAndInactivePagesCannotAct() {
  val base = ready()
  for (current in listOf(base.copy(busy = true), base.copy(connectingAtStartup = true),
   base.copy(appClosed = true), base.copy(pageActive = false))) {
   val state = DesktopSharedHomeAdapter.state(current)
   assertFalse(state.uploadEnabled); assertFalse(state.restoreEnabled); assertFalse(state.launchEnabled)
  }
  assertFalse(DesktopSharedHomeAdapter.state(base.copy(gameStatus = GameProcessStatus.Running(setOf(123L)))).canLaunch)
  val changed = base.copy(history = history().copy(currentFiles = files + ("badges.dat" to "c".repeat(64))))
  for (process in listOf(GameProcessStatus.Stopped, GameProcessStatus.Running(setOf(123L)), GameProcessStatus.Unknown("unavailable"))) {
   val state = DesktopSharedHomeAdapter.state(changed.copy(gameStatus = process))
   assertTrue(state.canUpload)
   assertTrue(state.canRestore)
   assertEquals(DesktopSharedHomeAdapter.state(changed).status, state.status)
   assertEquals(DesktopSharedHomeAdapter.state(changed).statusKind, state.statusKind)
  }
  assertFalse(DesktopSharedHomeAdapter.state(base.copy(config = null)).canLaunch)
  // The existing disconnected Upload remains an intentional route into login.
  assertTrue(DesktopSharedHomeAdapter.state(base.copy(connected = false)).canUpload)
  assertFalse(DesktopSharedHomeAdapter.state(base.copy(hasSession = false)).canRestore)
 }

 @Test fun emptyUnknownAndFrozenPendingStatesRemainDistinct() {
  val base = ready()
  val empty = history(emptyList()).copy(currentFiles = emptyMap(), summary = null)
  val emptyState = DesktopSharedHomeAdapter.state(base.copy(history = empty, selectedId = null, remoteTips = emptySet()))
  assertFalse(emptyState.canUpload); assertFalse(emptyState.canRestore)
  assertTrue(emptyState.history!!.localEmpty)
  assertEquals("暂无可恢复的云端存档", emptyState.emptyTargetMessage)
  val unknown = DesktopSharedHomeAdapter.state(base.copy(history = null, hasSession = false, selectedId = null))
  assertNull(unknown.history)
  assertContains(unknown.emptyTargetMessage, "正在读取")
  val unverifiedPending = DesktopSharedHomeAdapter.state(base.copy(history = empty, selectedId = null,
   remoteTips = emptySet(), pendingUpload = true))
  assertFalse(unverifiedPending.canUpload); assertFalse(unverifiedPending.canRestore)
  val pending = DesktopSharedHomeAdapter.state(base.copy(history = empty, selectedId = null, remoteTips = emptySet(),
   pendingUpload = true, pendingUploadSourceAvailable = true))
  assertTrue(pending.canUpload); assertFalse(pending.canRestore)
  assertContains(pending.status, "重试上传")
 }

 @Test fun eachDirectionRequiresItsOwnNonemptySourceRegardlessOfSetupCompletion() {
  for (complete in listOf(false, true)) for (localPresent in listOf(false, true))
   for (remotePresent in listOf(false, true)) {
    val observed = history(if (remotePresent) listOf(commit("tip")) else emptyList()).copy(
     currentFiles = if (localPresent) mapOf("game1/game.dat" to "c".repeat(64)) else emptyMap())
    val current = ready(observed).copy(
     config = config.copy(onboardingProgress = if (complete) OnboardingProgress.COMPLETE else OnboardingProgress.PENDING),
     remoteTips = if (remotePresent) setOf("snapshot-tip") else emptySet())
    val state = DesktopSharedHomeAdapter.state(current)
    assertEquals(localPresent, state.uploadEnabled, "complete=$complete local=$localPresent cloud=$remotePresent")
    assertEquals(remotePresent, state.restoreEnabled, "complete=$complete local=$localPresent cloud=$remotePresent")
   }
 }

 @Test fun selectedEmptyOrMissingSnapshotCannotFallBackToAnotherNonemptyCloudSource() {
  val observed = history(listOf(commit("empty", content = emptyMap()), commit("tip")))
   .copy(currentFiles = mapOf("game1/game.dat" to "c".repeat(64)))
  val base = ready(observed).copy(remoteTips = setOf("snapshot-empty", "snapshot-tip"))
  assertTrue(DesktopSharedHomeAdapter.state(base.copy(selectedId = null)).canRestore)
  for (selected in listOf("snapshot-empty", "missing")) {
   val state = DesktopSharedHomeAdapter.state(base.copy(selectedId = selected, selectedByUser = true))
   assertTrue(state.canUpload)
   assertFalse(state.canRestore, "must not substitute another snapshot for $selected")
   assertFalse(desktopHomeTransferAllowed(OperationAction.CLOUD_HISTORY, state))
  }
 }

 @Test fun noGameStaleOwnerAndInterruptedReadsBlockHostEntry() {
  val base = ready(history().copy(currentFiles = mapOf("game1/game.dat" to "c".repeat(64))))
  val states = listOf(base.copy(game = null), base.copy(config = null),
   base.copy(archiveLoading = true), base.copy(localHistoryVerified = false),
   base.copy(historyVerified = false), base.copy(history = null),
   base.copy(historyOwner = config.copy(username = "old-account")),
   base.copy(config = config.copy(targetGame = "/other-game.jar")),
   base.copy(config = config.copy(selectedSaveRoot = "/other-saves")),
   base.copy(locationIssue = "unreadable"), base.copy(hasSession = false))
  for (current in states) {
   val state = DesktopSharedHomeAdapter.state(current)
   assertFalse(state.uploadEnabled); assertFalse(state.restoreEnabled)
   for (action in listOf(OperationAction.PUSH, OperationAction.RESTORE,
    OperationAction.REMOTE_TIP, OperationAction.CLOUD_HISTORY)) {
    assertFalse(desktopHomeTransferAllowed(action, state), "direct entry $action must recheck current sources")
   }
   assertTrue(desktopHomeTransferAllowed(OperationAction.ARCHIVE, state), "recovery maintenance stays separate")
  }
  // Confirmation is a different page, but must still use the same current capability checks.
  val confirmation = DesktopSharedHomeAdapter.state(base.copy(pageActive = false))
  assertFalse(confirmation.restoreEnabled)
  assertTrue(desktopHomeTransferAllowed(OperationAction.CLOUD_HISTORY, confirmation))
 }

 @Test fun disconnectedUploadOpensLoginOnlyForKnownCurrentNonemptySource() {
  val local = history(emptyList()).copy(currentFiles = files)
  val base = ready(local).copy(connected = false, remoteTips = emptySet(), historyVerified = false)
  assertTrue(DesktopSharedHomeAdapter.state(base).uploadEnabled)
  for (blocked in listOf(base.copy(history = local.copy(currentFiles = emptyMap())),
   base.copy(localHistoryVerified = false), base.copy(historyOwner = null), base.copy(game = null))) {
   val state = DesktopSharedHomeAdapter.state(blocked)
   assertFalse(state.uploadEnabled); assertFalse(state.restoreEnabled)
  }
 }

 @Test fun technicalMessagesStayInDetailsAndUnknownSaveTimesStayUnknown() {
  val raw = "读取失败：https://private.invalid/dav/path/with/a/long/technical/explanation"
  val state = DesktopSharedHomeAdapter.state(ready(history(listOf(commit("tip", savedAt = null)))).copy(
   operationStatus = raw, connectionNotice = "HTTP 403 at https://private.invalid",
   restoreIssue = "存档写入版本不兼容：922", deviceLabel = "真实本机"))
  assertFalse(state.notice.contains("https://"))
  assertFalse(state.status.contains("https://"))
  assertContains(state.details, raw)
  assertContains(state.notice, "访问权限")
  assertEquals("时间未知", state.restoreTarget?.time)
  assertContains(state.restoreTarget!!.notice, "暂不能恢复")
  assertFalse(state.canRestore)
 }

 @Test fun operationLabelsReflectTheRealOperationAndClearWhenTheHostClearsIt() {
  assertEquals(SharedHomeOperation.UPLOAD, desktopHomeOperation(OperationAction.PUSH))
  for (action in listOf(OperationAction.RESTORE, OperationAction.RESOLVE, OperationAction.CLOUD_HISTORY, OperationAction.REMOTE_TIP))
   assertEquals(SharedHomeOperation.RESTORE, desktopHomeOperation(action))
  for (action in listOf(OperationAction.MERGE, OperationAction.MERGE_CURRENT))
   assertEquals(SharedHomeOperation.MERGE, desktopHomeOperation(action))
  assertEquals(SharedHomeOperation.LAUNCH, DesktopSharedHomeAdapter.state(ready().copy(launchingGame = true)).pending)
  assertEquals(SharedHomeOperation.NONE, DesktopSharedHomeAdapter.state(ready()).pending)
 }

 @Test fun repeatedRenderingAndPageChangesArePureAndUseTheGlobalTimeRows() {
  val history = history(listOf(commit("new", listOf("base"), "2026-10-14T01:00:00Z"),
   commit("base", savedAt = "2026-10-12T01:00:00Z")))
  val original = history.copy(currentFiles = history.currentFiles.toMap(), aliases = history.aliases.toMap())
  repeat(20) { page ->
   val state = DesktopSharedHomeAdapter.state(ready(history).copy(historyPage = page))
   assertEquals(HistoryPresentation.from(history, page), state.history)
   assertEquals(state.history!!.nodes.single { it.id == "new" }.row,
    state.history.nodes.single { it.id == "local-current" }.row)
   assertContains(state.history.nodes.single { it.id == "local-current" }.detail, "已进行0场游戏")
  }
  assertEquals(original, history)
  assertNull(history.summary!!.plays)
 }
}
