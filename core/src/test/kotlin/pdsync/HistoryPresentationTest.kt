package pdsync

import kotlin.test.*
import org.junit.jupiter.api.Test
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryPresentation

class HistoryPresentationTest {
 private val oldFiles = mapOf("game1/game.dat" to "a".repeat(64))
 private val newFiles = mapOf("game1/game.dat" to "b".repeat(64))
 private fun commit(id: String, parents: List<String> = emptyList(), files: Map<String,String> = oldFiles) = AppendCommit(
  id = id, parents = parents, objectBytes = 1,
  snapshot = Manifest(game="shattered", profile="test", version="922", id="snapshot-$id", parent=null,
   sha256="c".repeat(64), files=files))
 private fun state(commits: List<AppendCommit>, baseline: String? = null, current: Map<String,String> = emptyMap(), direction: String? = null) =
  SyncHistory(CommitGraph(commits.associateBy { it.id }, emptyMap()), baseline, current, "",
   SaveSummary(mapOf("game1" to "2026-10-10T00:00:00Z"), 7, current.size), emptyMap(), direction)

 @Test fun emptyLocalHasNoInventedNodesAndAtLeastOneRow() {
  val view=HistoryPresentation.from(state(emptyList()), 0)
  assertTrue(view.localEmpty); assertTrue(view.nodes.isEmpty()); assertTrue(view.edges.isEmpty())
  assertEquals(1,view.rows); assertEquals(1,view.pages)
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

 @Test fun restoreDirectionIsCloudToLocalAndUnknownDirectionHasNoCrossColumnArrow() {
  val history=state(listOf(commit("base")),"base",oldFiles,"restore")
  assertEquals(listOf(HistoryEdge("base","local-current")),HistoryPresentation.from(history,0).edges)
  assertTrue(HistoryPresentation.from(history.copy(baselineDirection=null),0).edges.isEmpty())
 }

 @Test fun realDagEdgesPointFromParentsToChildren() {
  val view=HistoryPresentation.from(state(listOf(commit("root"),commit("left",listOf("root")),commit("right",listOf("root")))),0)
  assertEquals(setOf(HistoryEdge("root","left"),HistoryEdge("root","right")),view.edges.toSet())
  assertEquals(setOf("left","right"),view.nodes.filter {it.status=="云端当前分支"}.map {it.id}.toSet())
 }

 @Test fun branchHistoryDoesNotChooseAWinner() {
  val history=state(listOf(commit("left"),commit("right")))
  assertNull(history.defaultSnapshot)
  assertEquals(2,HistoryPresentation.from(history,0).nodes.count {it.selectable})
 }

 @Test fun paginationClampsAndKeepsNodesBounded() {
  val history=state((0..40).map {commit("commit-%03d".format(it))})
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
  val history=state((0..24).map {commit("commit-%03d".format(it))},"commit-000",newFiles,"upload")
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

 @Test fun rowsUseGlobalSaveContentTimeInsteadOfIndependentLaneIndexes() {
  fun at(id: String, time: String, parents: List<String> = emptyList()) = commit(id, parents).copy(
   snapshot = commit(id, parents).snapshot.copy(metadata = SnapshotMetadata(
    device = DeviceIdentity("source-device", "真实来源"), capturedAt = "2026-10-15T00:00:00Z",
    fileModifiedAt = mapOf("game1/game.dat" to time))))
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
  assertEquals(4, view.rows)
  assertEquals(setOf(HistoryEdge("middle", "newest"), HistoryEdge("base", "middle"),
   HistoryEdge("base", "local-baseline"), HistoryEdge("local-baseline", "local-current", true)), view.edges.toSet())
 }

 @Test fun equalTimeNodesShareOnlyOppositeLanesAndOrderIsStableAcrossGraphInsertionOrder() {
  val stamp = "2026-10-10T00:00:00Z"
  fun at(id: String) = commit(id).let { it.copy(snapshot = it.snapshot.copy(metadata = SnapshotMetadata(
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

 @Test fun publicationTimeDoesNotPromoteUnknownSaveContentTime() {
  val known = commit("known").let { it.copy(snapshot = it.snapshot.copy(metadata = SnapshotMetadata(
   device = DeviceIdentity("source-device", "来源"), capturedAt = "2026-01-01T00:00:00Z",
   fileModifiedAt = mapOf("game1/game.dat" to "2026-01-01T00:00:00Z")))) }
  val unknown = commit("unknown").copy(operation = CommitOperationMetadata(
   device = DeviceIdentity("source-device", "来源"), preparedAt = "2026-12-31T00:00:00Z"))
  val view = HistoryPresentation.from(state(listOf(unknown, known)), 0)
  assertEquals(listOf("known", "unknown"), view.nodes.map { it.id })
  assertEquals("时间未知", view.nodes.last().time)
 }

 @Test fun completeRowPagingKeepsAllNodesReachableOnceAndEqualTimePairsTogether() {
  fun at(index: Int) = commit("commit-%03d".format(index)).let { it.copy(snapshot = it.snapshot.copy(
   metadata = SnapshotMetadata(device = DeviceIdentity("source-device", "来源"),
    capturedAt = "2026-10-01T00:00:00Z", fileModifiedAt = mapOf("game1/game.dat" to
     java.time.Instant.parse("2026-10-01T00:00:00Z").plusSeconds(index.toLong() * 3600).toString())))) }
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
  assertEquals(setOf(HistoryEdge("commit-000", "local-baseline"),
   HistoryEdge("local-baseline", "local-current", true)), last.edges.toSet())
  assertEquals(41, allNodes.count { it.selectable && it.snapshotId != null })
 }


 @Test fun capturedAtWithoutSaveModificationTimesRemainsUnknown() {
  val commit = commit("captured-only", files = emptyMap()).let { it.copy(snapshot = it.snapshot.copy(
   metadata = SnapshotMetadata(device = DeviceIdentity("source-device", "来源"),
    capturedAt = "2026-12-31T00:00:00Z", fileModifiedAt = emptyMap()))) }
  val history = state(listOf(commit))
  assertEquals("时间未知", HistoryPresentation.from(history, 0).nodes.single().time)
 }

}
