package pdsync

import io.github.xyz1001.pdsync.android.SharedGameIdentity
import io.github.xyz1001.pdsync.android.SharedHomeOperation
import io.github.xyz1001.pdsync.android.launchEnabled
import io.github.xyz1001.pdsync.android.restoreEnabled
import io.github.xyz1001.pdsync.android.targetTitle
import io.github.xyz1001.pdsync.android.uploadEnabled
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
  historyOwner = config, historyVerified = true,
 )

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
   if (index > 0) listOf("commit-%03d".format(index - 1)) else emptyList()) }
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

 @Test fun multiHeadHistoryNeverInventsADefaultOrHidesBranchSelection() {
  val history = history(listOf(commit("left"), commit("right")))
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

 @Test fun unverifiedUploadRestoreAndLaunchRetainThePreviousHostCapabilityMatrix() {
  val base = ready().copy(historyVerified = false)
  for (connected in listOf(false, true)) for (session in listOf(false, true))
   for (pending in listOf(false, true)) for (complete in listOf(false, true))
    for (conflict in listOf(false, true)) for (tips in listOf(emptySet(), setOf("a"), setOf("a", "b")))
     for (selected in listOf(null, "snapshot-tip")) for (issue in listOf(null, "不兼容")) {
      val current = base.copy(connected = connected, hasSession = session, pendingUpload = pending,
       config = base.config!!.copy(onboardingProgress = if (complete) OnboardingProgress.COMPLETE else OnboardingProgress.PENDING),
       hasConflict = conflict, remoteTips = tips, selectedId = selected, restoreIssue = issue)
      val state = DesktopSharedHomeAdapter.state(current)
      assertEquals(!connected || session && (pending || current.history!!.currentFiles.isNotEmpty()), state.canUpload)
      assertEquals(session && connected && (!complete || !pending &&
       (conflict || tips.size > 1 || selected != null && issue == null)), state.canRestore)
      assertTrue(state.canLaunch)
     }
 }

 @Test fun verifiedFullContentEqualityDisablesOnlyTheUnnecessaryTransfers() {
  val same = DesktopSharedHomeAdapter.state(ready())
  assertFalse(same.canUpload); assertFalse(same.canRestore); assertTrue(same.canLaunch)
  assertEquals("已同步", same.status)
  assertEquals("本机已是此进度，无需恢复。", same.restoreTarget?.notice)

  // A changed global record is still a different save even when slot/play counts and names match.
  val different = history().copy(currentFiles = files + ("badges.dat" to "c".repeat(64)))
  val changed = DesktopSharedHomeAdapter.state(ready(different))
  assertTrue(changed.canUpload); assertTrue(changed.canRestore)
  assertNotEquals("已同步", changed.status)
  assertEquals("", changed.restoreTarget?.notice)
 }

 @Test fun selectedIdenticalHistoryDoesNotMistakeTheDifferentLatestTipForSynced() {
  val history = history(listOf(commit("old"), commit("tip", listOf("old"),
   content = mapOf("game1/game.dat" to "c".repeat(64)))))
  val selected = DesktopSharedHomeAdapter.state(ready(history).copy(selectedId = "snapshot-old", selectedByUser = true))
  assertTrue(selected.canUpload); assertFalse(selected.canRestore)
  assertEquals("snapshot-old", selected.restoreTarget?.snapshotId)
  assertNotEquals("已同步", selected.status)
  val latest = DesktopSharedHomeAdapter.state(ready(history))
  assertTrue(latest.canUpload); assertTrue(latest.canRestore)
 }

 @Test fun equalityNeverHidesPendingBranchRecoveryOrUnfinishedSetup() {
  val pending = DesktopSharedHomeAdapter.state(ready().copy(pendingUpload = true))
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
  for (unverified in listOf(base.copy(historyVerified = false), base.copy(historyOwner = null),
   base.copy(historyOwner = config.copy(username = "previous-account")),
   base.copy(historyOwner = config.copy(profile = "previous-profile")),
   base.copy(locationIssue = "Unable to read save"), base.copy(remoteTips = emptySet()),
   base.copy(history = base.history!!.copy(graph = base.history.graph.copy(pending = mapOf("missing" to "unverified")))))) {
   val state = DesktopSharedHomeAdapter.state(unverified)
   assertTrue(state.canUpload); assertTrue(state.canRestore)
   assertNotEquals("已同步", state.status)
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
  assertFalse(DesktopSharedHomeAdapter.state(base.copy(config = null)).canLaunch)
  // The existing disconnected Upload remains an intentional route into login.
  assertTrue(DesktopSharedHomeAdapter.state(base.copy(connected = false, hasSession = false)).canUpload)
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
  val pending = DesktopSharedHomeAdapter.state(base.copy(history = empty, selectedId = null, pendingUpload = true))
  assertTrue(pending.canUpload); assertFalse(pending.canRestore)
  assertContains(pending.status, "重试上传")
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
   assertEquals(1, state.history!!.nodes.single { it.id == "local-current" }.row)
   assertContains(state.history.nodes.single { it.id == "local-current" }.detail, "已进行0场游戏")
  }
  assertEquals(original, history)
  assertNull(history.summary!!.plays)
 }
}
