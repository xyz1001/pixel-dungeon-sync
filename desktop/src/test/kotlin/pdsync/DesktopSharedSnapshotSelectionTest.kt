package pdsync

import io.github.xyz1001.pdsync.android.SharedBranchChoice
import io.github.xyz1001.pdsync.android.SharedBranchChoiceState
import io.github.xyz1001.pdsync.android.canResolve
import io.github.xyz1001.pdsync.android.hasBranches
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopSharedSnapshotSelectionTest {
 private val files = mapOf("game1/game.dat" to "a".repeat(64))
 private val first = AppendCommit(id = "first", parents = emptyList(), objectBytes = 1,
  snapshot = Manifest(game = "shattered", profile = "home", version = "896", id = "same-snapshot",
   parent = null, sha256 = "b".repeat(64), files = files))
 private val second = first.copy(id = "second")

 @Test fun identicalSnapshotTipsKeepDesktopRestoreAvailableAndConflictVisible() {
  val config = AppConfig(onboardingProgress = OnboardingProgress.COMPLETE)
  val history = SyncHistory(CommitGraph(listOf(first, second).associateBy { it.id }, emptyMap()),
   null, files, "local", null, emptyMap())
  val context = DesktopHomeContext(config = config, hasSession = true, connected = true,
   pageActive = true, busy = false, connectingAtStartup = false, appClosed = false,
   gameStatus = GameProcessStatus.Stopped, launchingGame = false, pendingUpload = false,
   hasConflict = false, remoteTips = setOf(first.snapshot.id), selectedId = null, selectedByUser = false,
   restoreIssue = null, history = history, historyOwner = config, historyVerified = true, localHistoryVerified = true,
   historyPage = 0, game = io.github.xyz1001.pdsync.android.SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"))
  val state = DesktopSharedHomeAdapter.state(context)
  assertNull(history.defaultSnapshot)
  assertTrue(state.canRestore)
  assertEquals("云端有多份进度，请点击恢复后选择。", state.status)
  assertEquals(2, history.graph.tips.size)
 }

 @Test fun desktopConflictChoicesDeduplicateContentWhileRoutingByFullGraphTips() {
  val source = Files.readString(Path.of("src/main/kotlin/pdsync/Main.kt"))
  assertContains(source, "branches = value.branches.sortedBy { it.commit.id }.distinctBy { it.snapshot.manifest.id }.map")
  assertContains(source, "fun hasRemoteBranches() = remoteTips.size > 1 || (graphHistory?.graph?.tips?.size ?: 0) > 1")
  assertContains(source.substringAfter("fun openRestore()").substringBefore("fun setAutomaticPaused("),
   "if (hasRemoteBranches() &&")
  assertContains(source, "retryable, conflict != null || hasRemoteBranches(), busy")
  val branches = listOf(second, first).sortedBy { it.id }.distinctBy { it.snapshot.id }.map {
   SharedBranchChoice(it.snapshot.id, it.id, "verified duplicate")
  }
  val state = SharedBranchChoiceState(Any(), branches, first.snapshot.id, null,
   pageActive = true, busy = false, connected = true, observationCurrent = true,
   resolutionBlocked = false, overwriteSupported = true, mergeSupported = false)
  assertTrue(state.hasBranches); assertTrue(state.canResolve)
  assertEquals(first.id, branches.single().name)
 }
}
