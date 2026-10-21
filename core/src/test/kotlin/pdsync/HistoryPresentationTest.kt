package pdsync

import kotlin.test.*
import org.junit.jupiter.api.Test
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryPresentation

class HistoryPresentationTest {
 private val oldFiles = mapOf("game1/game.dat" to "a".repeat(64))
 private val newFiles = mapOf("game1/game.dat" to "b".repeat(64))
 private fun uniqueFiles(id: String) = mapOf("game1/game.dat" to hash(id.encodeToByteArray()))
 private fun commit(id: String, parents: List<String> = emptyList(), files: Map<String,String> = oldFiles) = AppendCommit(
  id = id, parents = parents, objectBytes = 1,
  snapshot = Manifest(game="shattered", profile="test", version="922", id="snapshot-$id", parent=null,
   sha256="c".repeat(64), files=files))
 private fun state(commits: List<AppendCommit>, baseline: String? = null, current: Map<String,String> = emptyMap(), direction: String? = null) =
  SyncHistory(CommitGraph(commits.associateBy { it.id }, emptyMap()), baseline, current, "",
   SaveSummary(mapOf("game1" to "2026-10-10T00:00:00Z"), 7, current.size), emptyMap(), direction)
 private fun recorded(id: String, time: String, parents: List<String> = emptyList(),
  files: Map<String,String> = oldFiles, capturedAt: String = time, modifiedAt: String = time,
  kind: String = "snapshot") = commit(id, parents, files).let { it.copy(kind = kind,
   operation = CommitOperationMetadata(device = DeviceIdentity("actor", "操作者"), preparedAt = time),
   snapshot = it.snapshot.copy(metadata = SnapshotMetadata(device = DeviceIdentity("source", "来源"),
    capturedAt = capturedAt, fileModifiedAt = files.mapValues { modifiedAt }))) }

 @Test fun emptyLocalHasNoInventedNodesAndAtLeastOneRow() {
  val view=HistoryPresentation.from(state(emptyList()), 0)
  assertTrue(view.localEmpty); assertTrue(view.nodes.isEmpty()); assertTrue(view.edges.isEmpty())
  assertEquals(1,view.rows); assertEquals(1,view.pages)
 }

 @Test fun reinstalledEmptyStatePairsMetadataHashesWithoutInventingVerificationOrBaseline() {
  val remote = recorded("tip", "2026-10-01T00:00:00Z")
  val history = state(listOf(remote), current=oldFiles).let { it.copy(
   graph=it.graph.copy(verification=RemoteVerification.METADATA_ONLY), verifiedCommitIds=emptySet()) }
  val view = HistoryPresentation.from(history,0)
  val local = view.nodes.single {it.id=="local-current"}
  val cloud = view.nodes.single {it.id=="tip"}
  assertEquals(cloud.row,local.row)
  assertEquals(cloud.time,local.time)
  assertEquals(listOf(HistoryEdge("local-current","tip",sameContent=true)),view.edges)
  assertFalse(local.dirty)
  assertEquals("已同步云端",local.status)
  assertFalse(history.currentProtected)
  assertFalse(history.clean)
  assertNull(history.baseline)
  assertNull(history.baselineCommit)
  assertNull(history.baselineDirection)
  assertTrue(history.verifiedCommitIds.isEmpty())
  assertEquals(RemoteVerification.METADATA_ONLY,history.graph.verification)
  assertTrue(remote.parents.isEmpty())

  val verifiedHistory = history.copy(verifiedCommitIds=setOf("tip"))
  val verified = HistoryPresentation.from(verifiedHistory,0)
  assertFalse(verified.nodes.single {it.id=="local-current"}.dirty)
  assertTrue(verifiedHistory.currentProtected)
  assertEquals(view.edges,verified.edges)
  assertNull(verifiedHistory.baseline)
 }

 @Test fun reinstalledStateRequiresTheCompletePathToHashMapForAssociation() {
  val files = oldFiles + ("badges.dat" to "c".repeat(64))
  val remote = recorded("tip", "2026-10-01T00:00:00Z", files=files)
  val differentMaps = listOf(
   files + ("game1/game.dat" to "b".repeat(64)),
   files - "badges.dat",
   files + ("journal.dat" to "d".repeat(64)),
   mapOf("game2/game.dat" to files.getValue("game1/game.dat"), "badges.dat" to files.getValue("badges.dat")),
   mapOf("game1/game.dat" to files.getValue("badges.dat"), "badges.dat" to files.getValue("game1/game.dat"))
  )
  for (current in differentMaps) {
   val history = state(listOf(remote),current=current).let { it.copy(
    graph=it.graph.copy(verification=RemoteVerification.METADATA_ONLY),verifiedCommitIds=emptySet()) }
   val view = HistoryPresentation.from(history,0)
   assertTrue(view.edges.none {it.sameContent},"Different paths or bytes must not associate: $current")
   assertTrue(view.nodes.single {it.id=="local-current"}.dirty)
   assertFalse(history.currentProtected)
   assertNull(history.baseline)
  }
 }

 @Test fun reinstalledSameContentAssociationIgnoresFileMtimeAndMapIterationOrder() {
  val files = linkedMapOf("game1/game.dat" to "a".repeat(64),"badges.dat" to "c".repeat(64))
  val remote = recorded("tip", "2026-10-01T00:00:00Z", files=files,
   modifiedAt="2026-09-01T00:00:00.123Z")
  val history = state(listOf(remote),current=files.entries.reversed().associate {it.toPair()}).let { it.copy(
   graph=it.graph.copy(verification=RemoteVerification.METADATA_ONLY),verifiedCommitIds=emptySet()) }
  val before = HistoryPresentation.from(history,0)
  val changedMtime = remote.copy(snapshot=remote.snapshot.copy(metadata=remote.snapshot.metadata!!.copy(
   fileModifiedAt=files.mapValues {"2026-10-20T12:00:00Z"})))
  val after = HistoryPresentation.from(history.copy(
   graph=history.graph.copy(commits=mapOf(changedMtime.id to changedMtime)),
   summary=history.summary!!.copy(slots=mapOf("game1" to "2026-10-20T12:00:00Z"))),0)
  assertEquals(before,after)
  assertEquals(listOf(HistoryEdge("local-current","tip",sameContent=true)),after.edges)
  assertFalse(history.currentProtected)
  assertNull(history.baseline)
 }

 @Test fun cleanLocalShowsOneSyncedNode() {
  val view=HistoryPresentation.from(state(listOf(commit("base")),"base",oldFiles,"upload"),0)
  val local=view.nodes.single { it.column==1 }
  assertFalse(local.dirty); assertFalse(local.selectable); assertEquals("已同步云端",local.status)
  assertEquals(listOf(HistoryEdge("local-current","base")),view.edges)
 }

 @Test fun normalLocalProgressShowsBaselineAndDirtyCurrentWithoutInventingCloudCommit() {
  val view=HistoryPresentation.from(state(listOf(commit("base")),"base",newFiles,"upload"),0)
  assertEquals(1,view.cloudCount)
  assertEquals(listOf("local-current","local-baseline"),view.nodes.filter {it.column==1}.map {it.id})
  assertTrue(view.nodes.single {it.id=="local-current"}.dirty)
  assertEquals(setOf(HistoryEdge("local-baseline","base"),HistoryEdge("local-baseline","local-current",true)),view.edges.toSet())
 }

 @Test fun unassociatedLocalShowsOneDirtyNodeAndNoFalseBaseline() {
  val view=HistoryPresentation.from(state(listOf(commit("remote")),current=newFiles),0)
  assertEquals(1,view.nodes.count {it.column==1}); assertTrue(view.nodes.single {it.column==1}.dirty)
  assertTrue(view.edges.isEmpty())
 }

 @Test fun restoreAndUnknownDirectionsShowLocalToCloudContentEquivalence() {
  val history=state(listOf(commit("base")),"base",oldFiles,"restore")
  assertEquals(listOf(HistoryEdge("local-current","base",sameContent=true)),HistoryPresentation.from(history,0).edges)
  assertEquals(listOf(HistoryEdge("local-current", "base", sameContent = true)), HistoryPresentation.from(history.copy(baselineDirection=null),0).edges)
 }

 @Test fun realDagEdgesPointFromParentsToChildren() {
  val view=HistoryPresentation.from(state(listOf(commit("root"),commit("left",listOf("root"), newFiles),commit("right",listOf("root"), uniqueFiles("right")))),0)
  assertEquals(setOf(HistoryEdge("root","left"),HistoryEdge("root","right")),view.edges.toSet())
  assertEquals(setOf("left","right"),view.nodes.filter {it.status=="云端当前分支"}.map {it.id}.toSet())
 }

 @Test fun branchHistoryDoesNotChooseAWinner() {
  val history=state(listOf(commit("left"),commit("right", files=newFiles)))
  assertNull(history.defaultSnapshot)
  assertEquals(2,HistoryPresentation.from(history,0).nodes.count {it.selectable})
 }

 @Test fun paginationClampsAndKeepsNodesBounded() {
  val history=state((0..40).map {commit("commit-%03d".format(it), files=uniqueFiles("$it"))})
  val first=HistoryPresentation.from(history,-1); val last=HistoryPresentation.from(history,99)
  assertEquals(0,first.page); assertEquals(20,first.nodes.size)
  assertEquals(2,last.page); assertEquals(1,last.nodes.size); assertEquals(3,last.pages); assertEquals(41,last.cloudCount)
  assertTrue(last.edges.all {edge -> last.nodes.any {it.id==edge.fromId} && last.nodes.any {it.id==edge.toId}})
 }

 @Test fun aliasesAreDisplayOnlyAndBlankAliasFallsBack() {
  val history=state(listOf(commit("abcdefghi")),"abcdefghi",newFiles)
  val view=HistoryPresentation.from(history.copy(aliases=mapOf("snapshot-abcdefghi" to "旅程")),0)
  assertEquals(setOf("旅程"),view.nodes.filter {it.snapshotId=="snapshot-abcdefghi"}.map {it.name}.toSet())
  val fallback=HistoryPresentation.from(history.copy(aliases=mapOf("snapshot-abcdefghi" to " ")),0)
  assertEquals("存档 abcdef",fallback.nodes.single {it.column==0}.name)
  assertEquals("同步基线",fallback.nodes.single {it.id=="local-baseline"}.name)
  assertNull(history.graph.commits.getValue("abcdefghi").snapshot.directoryMetadata)
 }

 @Test fun unknownTimesAndCountsRemainUnknown() {
  assertEquals("时间未知",HistoryPresentation.time(null)); assertEquals("时间未知",HistoryPresentation.time("bad"))
  val view=HistoryPresentation.from(state(listOf(commit("base"))),0)
  assertEquals("来源未知 · ? 个槽位",view.nodes.single().detail)
  assertEquals("时间未知",view.nodes.single().time)
 }

 @Test fun playCountCopyUsesActualCountsAndDisplayOnlyZeroFallback() {
  assertEquals("已进行0场游戏",HistoryPresentation.plays(null))
  assertEquals("已进行0场游戏",HistoryPresentation.plays(0))
  assertEquals("已进行23场游戏",HistoryPresentation.plays(23))
  val history=state(emptyList(),current=newFiles)
  for(count in listOf(null,0,23)) {
   val summary=history.summary!!.copy(plays=count)
   val observed=history.copy(summary=summary)
   val local=HistoryPresentation.from(observed,0).nodes.single()
   assertEquals("1 个槽位 · 已进行${count ?: 0}场游戏",local.detail)
   assertEquals(count,observed.summary!!.plays)
  }
  val unavailable=history.copy(summary=null)
  assertEquals("? 个槽位 · 已进行0场游戏",HistoryPresentation.from(unavailable,0).nodes.single().detail)
  assertNull(unavailable.summary)
 }

 @Test fun repeatedRenderingPagingSelectionAndDismissalArePure() {
  val history=state((0..24).map {commit("commit-%03d".format(it), files=uniqueFiles("$it"))},"commit-000",newFiles,"upload")
  val before=history.copy(graph=history.graph.copy(commits=history.graph.commits.toMap()),currentFiles=history.currentFiles.toMap())
  val first=HistoryPresentation.from(history,0)
  repeat(20) {
   HistoryPresentation.from(history,1).nodes.firstOrNull {it.selectable}?.snapshotId
   HistoryPresentation.from(history,0).nodes.firstOrNull {it.selectable}?.snapshotId
   // Returning or canceling only rebuilds the same in-memory presentation.
   assertEquals(first,HistoryPresentation.from(history,0))
  }
  assertEquals(before,history)
 }

 @Test fun rowsAndDisplayedTimesUseRecordGenerationInsteadOfSaveContentOrCaptureTime() {
  fun at(id: String, time: String, parents: List<String> = emptyList()) = recorded(id, time, parents, files=if(id == "base") oldFiles else uniqueFiles(id),
   capturedAt = "2026-10-15T00:00:00Z", modifiedAt = "2026-09-01T00:00:00Z")
  val history = state(listOf(
   at("newest", "2026-10-12T00:00:00Z", listOf("middle")),
   at("middle", "2026-10-09T00:00:00Z", listOf("base")),
   at("base", "2026-10-08T00:00:00Z")), "base", newFiles, "restore")
  val view = HistoryPresentation.from(history, 0)
  val nodes = view.nodes.associateBy { it.id }
  assertEquals(0, nodes.getValue("newest").row)
  assertEquals(1, nodes.getValue("local-current").row)
  assertEquals(2, nodes.getValue("middle").row)
  assertEquals(3, nodes.getValue("base").row)
  assertEquals(nodes.getValue("base").row, nodes.getValue("local-baseline").row)
  assertEquals(HistoryPresentation.time("2026-10-12T00:00:00Z"), nodes.getValue("newest").time)
  assertEquals(HistoryPresentation.time("2026-10-10T00:00:00Z"), nodes.getValue("local-current").time)
  assertEquals(HistoryPresentation.time("2026-10-08T00:00:00Z"), nodes.getValue("base").time)
  assertEquals(nodes.getValue("base").time, nodes.getValue("local-baseline").time)
  assertEquals(4, view.rows)
  assertEquals(setOf(HistoryEdge("middle", "newest"), HistoryEdge("base", "middle"),
   HistoryEdge("local-baseline", "base", sameContent = true), HistoryEdge("local-baseline", "local-current", true)), view.edges.toSet())
 }

 @Test fun equalTimeNodesShareOnlyOppositeLanesAndOrderIsStableAcrossGraphInsertionOrder() {
  val stamp = "2026-10-10T00:00:00Z"
  fun at(id: String) = commit(id, files=if(id == "zeta") oldFiles else uniqueFiles(id)).let { it.copy(snapshot = it.snapshot.copy(metadata = SnapshotMetadata(
   device = DeviceIdentity("source-device", "来源"), capturedAt = stamp,
   fileModifiedAt = mapOf("game1/game.dat" to stamp)))) }
  val commits = listOf(at("zeta"), at("alpha"), at("omega"))
  val history = state(commits, "zeta", newFiles, "upload")
  val first = HistoryPresentation.from(history, 0)
  val reversed = HistoryPresentation.from(state(commits.reversed(), "zeta", newFiles, "upload"), 0)
  assertEquals(first.nodes, reversed.nodes)
  assertEquals(3, first.rows)
  first.nodes.groupBy { it.row }.values.forEach { row -> assertEquals(row.size, row.map { it.column }.distinct().size) }
  assertEquals(listOf("alpha", "omega", "zeta"), first.nodes.filter { it.column == 0 }.map { it.id })
  assertEquals(2, first.nodes.filter { it.column == 1 }.map { it.row }.distinct().size)
 }

 @Test fun operationTimeOrdersAndDisplaysARecordEvenWithoutSnapshotMetadata() {
  val known = commit("known").let { it.copy(snapshot = it.snapshot.copy(metadata = SnapshotMetadata(
   device = DeviceIdentity("source-device", "来源"), capturedAt = "2026-01-01T00:00:00Z",
   fileModifiedAt = mapOf("game1/game.dat" to "2026-01-01T00:00:00Z")))) }
  val unknown = commit("unknown", files=newFiles).copy(operation = CommitOperationMetadata(
   device = DeviceIdentity("source-device", "来源"), preparedAt = "2026-12-31T00:00:00Z"))
  val view = HistoryPresentation.from(state(listOf(unknown, known)), 0)
  assertEquals(listOf("unknown", "known"), view.nodes.map { it.id })
  assertEquals(HistoryPresentation.time("2026-12-31T00:00:00Z"), view.nodes.first().time)
  assertEquals(HistoryPresentation.time("2026-01-01T00:00:00Z"), view.nodes.last().time)
 }

 @Test fun completeRowPagingKeepsAllNodesReachableOnceAndEqualTimePairsTogether() {
  fun at(index: Int) = recorded("commit-%03d".format(index),
   java.time.Instant.parse("2026-10-01T00:00:00Z").plusSeconds(index.toLong() * 3600).toString(),
   files=if(index == 0) oldFiles else uniqueFiles("$index"), capturedAt = "2026-09-01T00:00:00Z", modifiedAt = "2026-08-01T00:00:00Z")
  val history = state((0..40).map(::at), "commit-000", newFiles, "restore").copy(
   summary = SaveSummary(mapOf("game1" to "2026-09-01T00:00:00Z"), 7, 1))
  val pages = (0 until HistoryPresentation.from(history, 0).pages).map { HistoryPresentation.from(history, it) }
  val allNodes = pages.flatMap { it.nodes }
  assertEquals(43, allNodes.size)
  assertEquals(43, allNodes.map { it.id }.toSet().size)
  assertEquals(1, allNodes.count { it.id == "local-current" })
  assertTrue(pages.first().nodes.none { it.column == 1 })
  val last = pages.last()
  assertEquals(last.nodes.single { it.id == "commit-000" }.row,
   last.nodes.single { it.id == "local-baseline" }.row)
  assertEquals(setOf(HistoryEdge("local-baseline", "commit-000", sameContent = true),
   HistoryEdge("local-baseline", "local-current", true)), last.edges.toSet())
  assertEquals(41, allNodes.count { it.selectable && it.snapshotId != null })
 }


 @Test fun legacyOrdinarySnapshotUsesCaptureTimeWhenOperationIsAbsent() {
  val commit = commit("captured-only", files = emptyMap()).let { it.copy(snapshot = it.snapshot.copy(
   metadata = SnapshotMetadata(device = DeviceIdentity("source-device", "来源"),
    capturedAt = "2026-12-31T00:00:00Z", fileModifiedAt = emptyMap()))) }
  val history = state(listOf(commit))
  assertEquals(HistoryPresentation.time("2026-12-31T00:00:00Z"), HistoryPresentation.from(history, 0).nodes.single().time)
 }

 @Test fun identicalCloudContentWithoutBaselineIsSyncedAndPairedDespiteTimestampPrecision() {
  val remote = commit("remote").let { it.copy(snapshot = it.snapshot.copy(metadata = SnapshotMetadata(
   device = DeviceIdentity("source", "来源"), capturedAt = "2026-10-10T00:00:00Z",
   fileModifiedAt = mapOf("game1/game.dat" to "2026-10-10T00:00:00.123Z")))) }
  val history = state(listOf(remote), current = oldFiles)
  val view = HistoryPresentation.from(history, 0)
  val local = view.nodes.single { it.id == "local-current" }
  assertFalse(history.clean); assertTrue(history.currentProtected)
  assertFalse(local.dirty); assertEquals("已同步云端", local.status)
  assertEquals(view.nodes.single { it.id == "remote" }.row, local.row)
  assertEquals(view.nodes.single { it.id == "remote" }.time, local.time)
  assertEquals(1, view.rows); assertEquals(listOf(HistoryEdge("local-current", "remote", sameContent = true)), view.edges); assertNull(history.baseline)
 }

 @Test fun differentContentsWithinSameDisplayedMinuteAreNotPairedOrMarkedSynced() {
  val remote = recorded("remote", "2026-10-10T00:00:00.123Z",
   capturedAt = "2026-10-10T00:00:00Z", modifiedAt = "2026-10-10T00:00:00Z")
  val view = HistoryPresentation.from(state(listOf(remote), current = newFiles), 0)
  val local = view.nodes.single { it.id == "local-current" }
  assertTrue(local.dirty); assertEquals("未同步云端", local.status)
  assertEquals(2, view.rows); assertNotEquals(view.nodes.single { it.id == "remote" }.row, local.row)
 }

 @Test fun sameTimeDifferentContentsPairWithTheirOwnCloudCommit() {
  fun at(id: String, files: Map<String,String>) = commit(id, files = files).let {
   it.copy(snapshot = it.snapshot.copy(metadata = SnapshotMetadata(device = DeviceIdentity("source", "来源"),
    capturedAt = "2026-10-10T00:00:00Z", fileModifiedAt = mapOf("game1/game.dat" to "2026-10-10T00:00:00Z")))) }
  val view = HistoryPresentation.from(state(listOf(at("alpha", newFiles), at("zeta", oldFiles)),
   "zeta", newFiles, "restore"), 0)
  val nodes = view.nodes.associateBy { it.id }
  assertEquals(nodes.getValue("alpha").row, nodes.getValue("local-current").row)
  assertEquals(nodes.getValue("zeta").row, nodes.getValue("local-baseline").row)
  assertFalse(nodes.getValue("local-current").dirty)
  assertTrue(HistoryEdge("local-current", "alpha", sameContent = true) in view.edges)
 }

 @Test fun matchingContentWithUnknownCloudTimestampStillSharesRowWithoutInventingTime() {
  val history = state(listOf(commit("remote")), current = oldFiles)
  val view = HistoryPresentation.from(history, 0)
  assertEquals(1, view.rows)
  assertEquals("时间未知", view.nodes.single { it.id == "remote" }.time)
  assertEquals("时间未知", view.nodes.single { it.id == "local-current" }.time)
  assertEquals(listOf(HistoryEdge("local-current", "remote", sameContent = true)), view.edges)
 }

 @Test fun equivalenceUsesCanonicalContentRegardlessOfMapOrderAndMetadata() {
  val files = linkedMapOf("game1/game.dat" to "a".repeat(64), "game2/game.dat" to "b".repeat(64))
  val reversed = files.entries.reversed().associate { it.toPair() }
  assertEquals(contentSha256(files), contentSha256(reversed))
  val remote = commit("remote", files = files)
  val history = state(listOf(remote), current = reversed)
  val view = HistoryPresentation.from(history, 0)
  assertEquals(listOf(HistoryEdge("local-current", "remote", sameContent = true)), view.edges)
  assertNull(history.baseline); assertTrue(remote.parents.isEmpty())
  assertTrue(HistoryPresentation.from(history.copy(currentFiles = files + ("game1/game.dat" to "c".repeat(64))), 0).edges.isEmpty())
 }

 @Test fun resolvingAnOlderSaveCreatesTheNewestRecordWithoutRewritingItsSourceOrParents() {
  val olderSave = recorded("5cf2d7", "2026-10-20T06:49:45.808592200Z",
   capturedAt = "2026-10-20T14:49:00Z", modifiedAt = "2026-10-03T00:00:00Z")
  val otherBranch = recorded("6c50d1", "2026-10-19T11:10:16.784323Z", files = newFiles)
  val resolution = recorded("92cf9d", "2026-10-20T06:50:19.830460100Z",
   parents = listOf(olderSave.id, otherBranch.id), kind = "resolution").let {
   it.copy(snapshot = it.snapshot.copy(metadata = olderSave.snapshot.metadata))
  }
  val history = state(listOf(otherBranch, olderSave, resolution), resolution.id, oldFiles, "upload")
  val before = history.copy(graph = history.graph.copy(commits = history.graph.commits.toMap()))
  val view = HistoryPresentation.from(history, 0)
  assertEquals(listOf("92cf9d", "6c50d1"), view.nodes.filter { it.column == 0 }.map { it.id })
  val nodes = view.nodes.associateBy { it.id }
  assertEquals(HistoryPresentation.time("2026-10-20T06:50:19.830460100Z"), nodes.getValue("92cf9d").time)
  assertEquals(nodes.getValue("92cf9d").time, nodes.getValue("local-current").time)
  assertEquals(nodes.getValue("92cf9d").row, nodes.getValue("local-current").row)
  assertEquals(setOf(HistoryEdge("6c50d1", "92cf9d"),
   HistoryEdge("local-current", "92cf9d")), view.edges.toSet())
  assertEquals(setOf("92cf9d"), view.nodes.filter { it.cloudHead }.map { it.id }.toSet())
  assertEquals(before, history)
  assertEquals("2026-10-03T00:00:00Z", resolution.snapshot.metadata!!.latestFileModifiedAt)
  assertEquals(olderSave.snapshot.metadata, resolution.snapshot.metadata)
 }

 @Test fun equalTimeChildrenPrecedeParentsAndBranchOrderingDoesNotDependOnDiscoveryOrder() {
  val stamp = "2026-10-20T14:49:00Z"
  val commits = listOf(
   recorded("a-root", stamp, files=uniqueFiles("a-root")),
   recorded("b-left", stamp, listOf("a-root"), files=uniqueFiles("b-left")),
   recorded("c-right", stamp, listOf("a-root"), files=uniqueFiles("c-right")),
   recorded("z-resolution", stamp, listOf("b-left", "c-right"), files=uniqueFiles("z-resolution"), kind = "resolution"))
  val expected = HistoryPresentation.from(state(commits), 0)
  assertEquals(listOf("z-resolution", "b-left", "c-right", "a-root"), expected.nodes.map { it.id })
  assertEquals(setOf(HistoryEdge("a-root", "b-left"), HistoryEdge("a-root", "c-right"),
   HistoryEdge("b-left", "z-resolution"), HistoryEdge("c-right", "z-resolution")), expected.edges.toSet())
  repeat(10) { seed ->
   assertEquals(expected, HistoryPresentation.from(state(commits.shuffled(kotlin.random.Random(seed))), 0))
  }
 }

 @Test fun differentRecordTimesRemainPrimaryEvenWhenAParentClockIsAhead() {
  val parent = recorded("parent", "2026-10-21T00:00:00Z")
  val child = recorded("child", "2026-10-20T00:00:00Z", listOf(parent.id), files=newFiles)
  val view = HistoryPresentation.from(state(listOf(child, parent)), 0)
  assertEquals(listOf("parent", "child"), view.nodes.map { it.id })
  assertEquals(listOf(HistoryEdge("parent", "child")), view.edges)
 }

 @Test fun legacyOperationsCannotBorrowReusedSnapshotOrFileTimes() {
  val source = recorded("a-source", "2026-10-19T00:00:00Z",
   capturedAt = "2026-10-18T00:00:00Z", modifiedAt = "2030-01-01T00:00:00Z").copy(operation = null)
  val other = commit("b-other", files = newFiles)
  val resolution = source.copy(id = "z-resolution", kind = "resolution", parents = listOf(source.id, other.id),
   snapshot = source.snapshot.copy(id="snapshot-z-resolution", files=uniqueFiles("resolution")))
  val restore = source.copy(id = "y-restore", kind = "restore", parents = listOf(resolution.id),
   snapshot = source.snapshot.copy(id="snapshot-y-restore", files=uniqueFiles("restore")))
  val imported = source.copy(id = "x-import", kind = "legacy-import",
   snapshot = source.snapshot.copy(id="snapshot-x-import", files=uniqueFiles("import")))
  val view = HistoryPresentation.from(state(listOf(other, resolution, restore, imported, source)), 0)
  assertEquals(listOf("a-source", "x-import", "y-restore", "z-resolution", "b-other"), view.nodes.map { it.id })
  assertEquals(HistoryPresentation.time("2026-10-18T00:00:00Z"), view.nodes.first().time)
  assertTrue(view.nodes.drop(1).all { it.time == "时间未知" })
  assertEquals(setOf(HistoryEdge("a-source", "z-resolution"), HistoryEdge("b-other", "z-resolution"),
   HistoryEdge("z-resolution", "y-restore")), view.edges.toSet())
 }

 @Test fun malformedOperationOrLegacyCaptureStaysUnknownWithoutUsingFileTime() {
  val invalidOperation = recorded("invalid-operation", "bad-operation", files=uniqueFiles("invalid-operation"),
   capturedAt = "2026-10-19T00:00:00Z", modifiedAt = "2026-10-20T00:00:00Z")
  val invalidCapture = recorded("invalid-capture", "2026-10-20T00:00:00Z", files=uniqueFiles("invalid-capture"),
   capturedAt = "bad-capture", modifiedAt = "2026-10-21T00:00:00Z").copy(operation = null)
  val missing = commit("missing", files=uniqueFiles("missing"))
  val valid = recorded("valid", "2026-10-01T00:00:00Z", capturedAt = "bad-capture", modifiedAt = "bad-mtime")
  val view = HistoryPresentation.from(state(listOf(missing, invalidOperation, valid, invalidCapture)), 0)
  assertEquals(listOf("valid", "invalid-capture", "invalid-operation", "missing"), view.nodes.map { it.id })
  assertTrue(view.nodes.drop(1).all { it.time == "时间未知" })
  assertEquals(HistoryPresentation.time("2026-10-01T00:00:00Z"), view.nodes.first().time)
 }

 @Test fun identicalCurrentContentUsesNewestGroupTimeWithoutRewritingEstablishedBaseline() {
  val baseline = recorded("baseline", "2026-10-01T00:00:00Z")
  val duplicate = recorded("newer-copy", "2026-10-20T00:00:00Z", listOf(baseline.id))
  for (direction in listOf("upload", "restore", null)) {
   val view = HistoryPresentation.from(state(listOf(duplicate, baseline), baseline.id, oldFiles, direction), 0)
   val nodes = view.nodes.associateBy { it.id }
   assertEquals(nodes.getValue("newer-copy").row, nodes.getValue("local-current").row)
   assertEquals(nodes.getValue("newer-copy").time, nodes.getValue("local-current").time)
   assertEquals(HistoryPresentation.time("2026-10-20T00:00:00Z"), nodes.getValue("newer-copy").time)
   assertEquals(setOf(HistoryEdge("local-current", "newer-copy", sameContent = true)), view.edges.toSet())
  }
 }

 @Test fun identicalContentWithoutMatchingBaselineUsesNewestRecordAndStableChildTieChoice() {
  val old = recorded("old", "2026-10-01T00:00:00Z", modifiedAt = "2030-01-01T00:00:00Z")
  val newer = recorded("a-parent", "2026-10-20T00:00:00Z", listOf(old.id),
   capturedAt = "2026-09-01T00:00:00Z", modifiedAt = "2026-08-01T00:00:00Z")
  val duplicate = recorded("z-child", "2026-10-20T00:00:00Z", listOf(newer.id))
  val baseline = recorded("different-baseline", "2026-10-02T00:00:00Z", files = newFiles)
  for (baselineId in listOf(null, baseline.id)) {
   val history = state(listOf(old, newer, duplicate, baseline), baselineId, oldFiles, "restore")
   val view = HistoryPresentation.from(history, 0)
   val nodes = view.nodes.associateBy { it.id }
   assertEquals(nodes.getValue("z-child").row, nodes.getValue("local-current").row)
   assertEquals(nodes.getValue("z-child").time, nodes.getValue("local-current").time)
   assertFalse(nodes.getValue("local-current").dirty)
   assertTrue(HistoryEdge("local-current", "z-child", sameContent = true) in view.edges)
   assertEquals(view, HistoryPresentation.from(history.copy(graph = history.graph.copy(
    commits = history.graph.commits.entries.reversed().associate { it.toPair() })), 0))
  }
 }

 @Test fun equalTimeAncestryPagingKeepsMatchedBaselineAtBoundaryAndOmitsOnlyOffPageEdges() {
  val commits = (0..40).map { index ->
   recorded("node-%03d".format(index), "2026-10-20T00:00:00Z",
    if (index == 0) emptyList() else listOf("node-%03d".format(index - 1)),
    files=if(index == 21) oldFiles else uniqueFiles("$index"))
  }
  val history = state(commits, "node-021", oldFiles, "restore")
  val pages = (0..2).map { HistoryPresentation.from(history, it) }
  val nodes = pages.flatMap { it.nodes }
  assertEquals(42, nodes.size)
  assertEquals(42, nodes.map { it.id }.toSet().size)
  assertEquals((40 downTo 0).map { "node-%03d".format(it) }, nodes.filter { it.column == 0 }.map { it.id })
  assertEquals(19, pages.first().nodes.single { it.id == "node-021" }.row)
  assertEquals(19, pages.first().nodes.single { it.id == "local-current" }.row)
  assertTrue(HistoryEdge("local-current", "node-021", sameContent = true) in pages.first().edges)
  assertTrue(pages.flatMap { it.edges }.none { it.fromId == "node-020" && it.toId == "node-021" })
  pages.forEach { page ->
   val visible = page.nodes.map { it.id }.toSet()
   assertTrue(page.edges.all { it.fromId in visible && it.toId in visible })
   assertTrue(page.rows <= HistoryPresentation.PAGE_SIZE)
  }
  assertEquals(0, HistoryPresentation.from(history, -1).page)
  assertEquals(2, HistoryPresentation.from(history, Int.MAX_VALUE).page)
 }

 @Test fun readOnlyRefreshReordersNewRecordsWithoutChangingSavedDataOrHashRelationships() {
  val initial = recorded("initial", "2026-10-01T00:00:00Z")
  val before = state(listOf(initial), current = oldFiles)
  val first = HistoryPresentation.from(before, 0)
  val published = recorded("published", "2026-10-20T00:00:00Z", listOf(initial.id),
   capturedAt = "2026-10-01T00:00:00Z", modifiedAt = "2026-09-01T00:00:00Z")
  val refreshed = before.copy(graph = CommitGraph(linkedMapOf(initial.id to initial, published.id to published), emptyMap()),
   verifiedCommitIds = setOf(initial.id, published.id))
  val immutable = refreshed.copy(graph = refreshed.graph.copy(commits = refreshed.graph.commits.toMap()),
   currentFiles = refreshed.currentFiles.toMap())
  val view = HistoryPresentation.from(refreshed, 99)
  assertEquals("published", view.nodes.first().id)
  assertEquals(setOf(HistoryEdge("local-current", "published", sameContent = true)), view.edges.toSet())
  assertEquals(view.nodes.single { it.id == "published" }.time, view.nodes.single { it.id == "local-current" }.time)
  repeat(20) {
   assertEquals(view, HistoryPresentation.from(refreshed, 0))
   assertEquals(first, HistoryPresentation.from(before, 0))
  }
  assertEquals(immutable, refreshed)
  assertNull(refreshed.baseline)
 }

}
