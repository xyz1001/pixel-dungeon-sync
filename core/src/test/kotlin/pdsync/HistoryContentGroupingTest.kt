package pdsync

import org.junit.jupiter.api.Test
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryPresentation
import kotlin.test.*

/** Content cards are a projection; the full observed graph and real operation targets survive. */
class HistoryContentGroupingTest {
 private val files = linkedMapOf("game1/game.dat" to "a".repeat(64), "badges.dat" to "b".repeat(64))
 private fun different(id: String) = files + ("game1/game.dat" to hash(id.encodeToByteArray()))
 private fun commit(id: String, time: String = "2026-10-01T00:00:00Z",
  parents: List<String> = emptyList(), content: Map<String, String> = files) = AppendCommit(
   id = id, parents = parents, objectBytes = 1,
   operation = CommitOperationMetadata(device = DeviceIdentity("actor-$id", "Actor $id"), preparedAt = time),
   snapshot = Manifest(game = "shattered", profile = "test", version = "922", id = "snapshot-$id", parent = null,
    sha256 = hash("archive-$id".encodeToByteArray()), files = content,
    metadata = SnapshotMetadata(device = DeviceIdentity("device-$id", "Device $id"), capturedAt = time, fileModifiedAt = content.mapValues { time })))
 private fun state(commits: List<AppendCommit>, baseline: String? = null, current: Map<String, String> = emptyMap(),
  direction: String? = null) = SyncHistory(CommitGraph(commits.associateBy { it.id }, emptyMap()),
   baseline, current, "Current", null, emptyMap(), direction)

 @Test fun completeContentIgnoresSnapshotArchiveMetadataAndMapOrderButRetainsEveryOriginal() {
  val old = commit("old")
  val recent = commit("recent", "2026-10-02T00:00:00Z", listOf(old.id), files.entries.reversed().associate { it.toPair() })
  val history = state(listOf(recent, old), current = files).copy(aliases = mapOf(old.snapshot.id to "Old name", recent.snapshot.id to "Recent name"))
  val before = history.copy(graph = history.graph.copy(commits = history.graph.commits.toMap()), currentFiles = history.currentFiles.toMap())
  val view = HistoryPresentation.from(history, 0)
  assertEquals(1, view.cloudCount)
  assertEquals(listOf(recent.id), view.nodes.filter { it.column == 0 }.map { it.id })
  assertEquals("Recent name", view.nodes.first().name)
  assertEquals(recent.snapshot.id, HistoryPresentation.representativeSnapshotId(history, old.snapshot.id))
  assertEquals(recent.snapshot.id, HistoryPresentation.representativeSnapshotId(history, recent.snapshot.id))
  assertNull(HistoryPresentation.representativeSnapshotId(history, null))
  assertEquals("not-observed", HistoryPresentation.representativeSnapshotId(history, "not-observed"))
  assertEquals(listOf(HistoryEdge("local-current", recent.id, sameContent = true)), view.edges)
  assertEquals(before, history)
  assertEquals(2, history.cloud.size)
  assertEquals(setOf(old.id, recent.id), history.graph.commits.keys)
 }

 @Test fun repeatedSnapshotUuidAndTiedBranchesHaveOneStableDisplayHandle() {
  val parent = commit("a-parent")
  val child = commit("z-child", parents = listOf(parent.id)).copy(snapshot = parent.snapshot)
  val sibling = commit("b-sibling")
  val history = state(listOf(parent, child, sibling))
  val view = HistoryPresentation.from(history, 0)
  assertEquals(listOf(sibling.id), view.nodes.map { it.id })
  assertEquals(sibling.snapshot.id, HistoryPresentation.representativeSnapshotId(history, parent.snapshot.id))
  assertEquals(setOf(child.id, sibling.id), history.graph.tips)
  assertNull(history.defaultSnapshot)
  assertTrue(view.edges.isEmpty())
  assertEquals(view, HistoryPresentation.from(history.copy(graph = history.graph.copy(
   commits = history.graph.commits.entries.reversed().associate { it.toPair() })), 0))
 }

 @Test fun everyDifferentFullFileMapRemainsSelectableEvenWithIdenticalMetadata() {
  val variants = listOf(files, different("changed"), files - "badges.dat", files + ("journal.dat" to "c".repeat(64)),
   mapOf("game2/game.dat" to files.getValue("game1/game.dat"), "badges.dat" to files.getValue("badges.dat")),
   mapOf("game1/game.dat" to files.getValue("badges.dat"), "badges.dat" to files.getValue("game1/game.dat")))
  val commits = variants.mapIndexed { index, content -> commit("$index", content = content) }
  val history = state(commits)
  val view = HistoryPresentation.from(history, 0)
  assertEquals(variants.size, view.cloudCount)
  assertEquals(commits.map { it.snapshot.id }.toSet(), view.nodes.filter { it.selectable }.map { it.snapshotId }.toSet())
 }

 @Test fun emptyInvalidAndPartialMapsStayIndividualAndCannotMatchLocalContent() {
  val invalid = listOf(emptyMap(), mapOf("game1/depth1.dat" to "a".repeat(64)),
   mapOf("../game1/game.dat" to "a".repeat(64)), mapOf("game1\\game.dat" to "a".repeat(64)),
   mapOf("game1//game.dat" to "a".repeat(64)), mapOf("unknown.dat" to "a".repeat(64)),
   mapOf("game1/game.dat" to "invalid"), files + ("GAME1/GAME.DAT" to "a".repeat(64)),
   files + (DIRECTORY_METADATA_FILE to "c".repeat(64)))
  for (content in invalid) {
   val history = state(listOf(commit("first", content = content), commit("second", content = content)), current = content)
   val view = HistoryPresentation.from(history, 0)
   assertEquals(2, view.cloudCount, "$content")
   assertTrue(view.edges.none { it.sameContent }, "$content")
   assertEquals("snapshot-first", HistoryPresentation.representativeSnapshotId(history, "snapshot-first"))
  }
 }

 @Test fun equalBytesAcrossDifferentGameOrProfileNamespacesDoNotCollapse() {
  val original = commit("one")
  val game = commit("two").let { it.copy(snapshot = it.snapshot.copy(game = "another-game")) }
  val profile = commit("three").let { it.copy(snapshot = it.snapshot.copy(profile = "another-profile")) }
  assertEquals(3, HistoryPresentation.from(state(listOf(original, game, profile)), 0).cloudCount)
 }

 @Test fun duplicateTipsStayUnresolvedAndPreferNewestTipDeterministically() {
  val left = commit("left")
  val right = commit("right", "2026-10-02T00:00:00Z")
  val history = state(listOf(left, right), left.id, files, "restore")
  val view = HistoryPresentation.from(history, 0)
  assertEquals(listOf(right.id), view.nodes.filter { it.column == 0 }.map { it.id })
  assertTrue(view.nodes.single { it.column == 0 }.cloudHead)
  assertEquals(setOf(left.id, right.id), history.graph.tips)
  assertNull(history.defaultSnapshot)
  assertEquals(left.id, history.baseline)
  assertEquals(listOf(HistoryEdge("local-current", right.id, sameContent = true)), view.edges)
  assertEquals(view, HistoryPresentation.from(history.copy(graph = history.graph.copy(commits = history.graph.commits.entries.reversed().associate { it.toPair() })), 0))
 }

 @Test fun tipHandleWinsWhileGroupTimeUsesNewestActualMemberEvenWithClockSkew() {
  val futureParent = commit("parent", "2026-10-20T00:00:00Z")
  val tip = commit("tip", "2026-10-02T00:00:00Z", listOf(futureParent.id))
  val other = commit("other", "2026-10-10T00:00:00Z", content = different("other"))
  val history = state(listOf(other, tip, futureParent), futureParent.id, files, "restore")
  val view = HistoryPresentation.from(history, 0)
  assertEquals(tip.id, view.nodes.first().id)
  assertEquals(HistoryPresentation.time("2026-10-20T00:00:00Z"), view.nodes.first().time)
  assertEquals(view.nodes.first().time, view.nodes.single { it.id == "local-current" }.time)
  assertEquals(tip.snapshot.id, HistoryPresentation.representativeSnapshotId(history, futureParent.snapshot.id))
 }

 @Test fun verifiedMatchingBaselineIsPreferredWhenItsContentGroupHasNoTip() {
  val base = commit("base")
  val duplicate = commit("duplicate", "2026-10-02T00:00:00Z", listOf(base.id))
  val tip = commit("tip", "2026-10-03T00:00:00Z", listOf(duplicate.id), different("tip"))
  val history = state(listOf(tip, duplicate, base), base.id, files, "restore")
  val view = HistoryPresentation.from(history, 0)
  assertEquals(listOf(tip.id, base.id), view.nodes.filter { it.column == 0 }.map { it.id })
  assertEquals(HistoryPresentation.time("2026-10-02T00:00:00Z"), view.nodes.single { it.id == base.id }.time)
  assertEquals(base.snapshot.id, HistoryPresentation.representativeSnapshotId(history, duplicate.snapshot.id))
  assertEquals(setOf(HistoryEdge(base.id, tip.id), HistoryEdge("local-current", base.id, sameContent = true)), view.edges.toSet())
  val unmatched = history.copy(currentFiles = different("local"))
  assertEquals(duplicate.snapshot.id, HistoryPresentation.representativeSnapshotId(unmatched, base.snapshot.id))
  val dirty = HistoryPresentation.from(unmatched, 0)
  assertTrue(HistoryEdge("local-baseline", duplicate.id, sameContent = true) in dirty.edges)
  assertTrue(HistoryEdge(duplicate.id, tip.id) in dirty.edges)
  assertEquals(base.id, unmatched.baseline)
 }

 @Test fun legacyRepeatedContentRetainsBothRecordedCrossGroupRelationships() {
  val a = commit("a")
  val b = commit("b", "2026-10-02T00:00:00Z", listOf(a.id), different("b"))
  val c = commit("c", "2026-10-03T00:00:00Z", listOf(b.id))
  val history = state(listOf(a, b, c))
  val view = HistoryPresentation.from(history, 0)
  assertEquals(listOf(c.id, b.id), view.nodes.map { it.id })
  assertEquals(setOf(HistoryEdge(b.id, c.id), HistoryEdge(c.id, b.id)), view.edges.toSet())
  assertTrue(view.edges.none { it.fromId == it.toId })
  assertEquals(listOf(a.id), history.graph.commits.getValue(b.id).parents)
  assertEquals(listOf(b.id), history.graph.commits.getValue(c.id).parents)
 }

 @Test fun groupingBeforePagingKeepsOneCardPerContentAndLocalAssociationOnItsPage() {
  val commits = (0..40).flatMap { index ->
   val content = different("content-$index")
   val old = commit("old-$index", content = content)
   val recent = commit("recent-$index", "2026-10-02T00:00:00Z", listOf(old.id), content)
   listOf(old, recent)
  }
  val history = state(commits, "old-9", different("content-9"), "restore")
  val pages = (0..2).map { HistoryPresentation.from(history, it) }
  assertTrue(pages.all { it.cloudCount == 41 && it.pages == 3 && it.rows <= 20 })
  val all = pages.flatMap { it.nodes }
  assertEquals(41, all.count { it.column == 0 })
  assertEquals(42, all.map { it.id }.toSet().size)
  val containingLocal = pages.single { page -> page.nodes.any { it.id == "local-current" } }
  assertEquals(containingLocal.nodes.single { it.id == "recent-9" }.row,
   containingLocal.nodes.single { it.id == "local-current" }.row)
  assertEquals(listOf(HistoryEdge("local-current", "recent-9", sameContent = true)), containingLocal.edges)
  pages.forEach { page ->
   val visible = page.nodes.map { it.id }.toSet()
   assertTrue(page.edges.all { it.fromId in visible && it.toId in visible })
  }
  assertEquals(0, HistoryPresentation.from(history, -1).page)
  assertEquals(2, HistoryPresentation.from(history, 99).page)
  assertEquals("snapshot-recent-9", HistoryPresentation.representativeSnapshotId(history, "snapshot-old-9"))
 }

 @Test fun metadataOnlyGroupingNeverEstablishesProtectionOrBaseline() {
  val original = commit("one")
  val duplicate = commit("two", "2026-10-02T00:00:00Z", listOf(original.id))
  val history = state(listOf(original, duplicate), current = files).let { it.copy(
   graph = it.graph.copy(verification = RemoteVerification.METADATA_ONLY), verifiedCommitIds = emptySet()) }
  assertEquals(1, HistoryPresentation.from(history, 0).cloudCount)
  assertFalse(history.currentProtected)
  assertFalse(history.clean)
  assertNull(history.baseline)
  assertEquals(setOf(original.id, duplicate.id), history.graph.commits.keys)
 }

 @Test fun hiddenDuplicateParentProjectsItsRealBranchEdgesWithoutConnectingUnrelatedCards() {
  val base = commit("base")
  val hidden = commit("hidden", "2026-10-02T00:00:00Z", listOf(base.id))
  val left = commit("left", "2026-10-03T00:00:00Z", listOf(hidden.id), different("left"))
  val right = commit("right", "2026-10-04T00:00:00Z", listOf(hidden.id), different("right"))
  val unrelated = commit("unrelated", content = different("unrelated"))
  val history = state(listOf(base, hidden, left, right, unrelated), base.id, files, "restore")
  val before = history.copy(graph = history.graph.copy(commits = history.graph.commits.toMap()))
  val view = HistoryPresentation.from(history, 0)
  assertEquals(setOf(HistoryEdge(base.id, left.id), HistoryEdge(base.id, right.id)),
   view.edges.filter { !it.sameContent }.toSet())
  assertTrue(view.edges.none { it.fromId == unrelated.id || it.toId == unrelated.id })
  assertEquals(before, history)
  repeat(10) { seed ->
   assertEquals(view, HistoryPresentation.from(history.copy(graph = history.graph.copy(
    commits = history.graph.commits.values.shuffled(kotlin.random.Random(seed)).associateBy { it.id })), 0))
  }
 }

 @Test fun sameContentAncestorChainsRetainTransitionsAcrossEveryCollapsedGroup() {
  val a = commit("a")
  val aa = commit("aa", parents = listOf(a.id))
  val b = commit("b", parents = listOf(aa.id), content = different("b"))
  val bb = commit("bb", parents = listOf(b.id), content = different("b"))
  val c = commit("c", parents = listOf(bb.id), content = different("c"))
  val history = state(listOf(a, aa, b, bb, c), a.id, files)
  val view = HistoryPresentation.from(history, 0)
  assertEquals(setOf(HistoryEdge(a.id, bb.id), HistoryEdge(bb.id, c.id)),
   view.edges.filter { !it.sameContent }.toSet())
  assertEquals(listOf(aa.id), history.graph.commits.getValue(b.id).parents)
 }

 @Test fun legacyRepeatedContentKeepsRecordedDirectionsDespiteClockSkew() {
  val a = commit("a", "2030-01-01T00:00:00Z")
  val b = commit("b", "2029-01-01T00:00:00Z", listOf(a.id), different("b"))
  val c = commit("c", "2020-01-01T00:00:00Z", listOf(b.id))
  val view = HistoryPresentation.from(state(listOf(a, b, c)), 0)
  assertEquals(listOf(c.id, b.id), view.nodes.map { it.id })
  assertEquals(HistoryPresentation.time("2030-01-01T00:00:00Z"), view.nodes.first().time)
  assertEquals(setOf(HistoryEdge(b.id, c.id), HistoryEdge(c.id, b.id)), view.edges.toSet())
 }

 @Test fun groupedProjectionIsGlobalBeforePagingAndNeverBridgesOffPageCards() {
  val start = java.time.Instant.parse("2026-10-01T00:00:00Z")
  val commits = (0..40).flatMap { index ->
   val content = different("group-$index")
   val old = commit("old-%02d".format(index), start.plusSeconds(index.toLong()).toString(),
    if (index == 0) emptyList() else listOf("old-%02d".format(index - 1)), content)
   // A separate newer member is the display handle; the old chain still proves every edge.
   val recent = commit("recent-%02d".format(index), start.plusSeconds(100L + index).toString(),
    listOf(old.id), content)
   listOf(old, recent)
  }
  val history = state(commits)
  val pages = (0..2).map { HistoryPresentation.from(history, it) }
  for (page in pages) {
   val visible = page.nodes.map { it.id }.toSet()
   val expected = (1..40).map { index -> HistoryEdge("recent-%02d".format(index - 1), "recent-%02d".format(index)) }
    .filter { it.fromId in visible && it.toId in visible }.toSet()
   assertEquals(expected, page.edges.toSet())
   assertTrue(page.edges.all { it.fromId in visible && it.toId in visible })
  }
  assertEquals(38, pages.sumOf { it.edges.size })
 }

 @Test fun legacyMultiGroupRepetitionRetainsAllRecordedTransitionsDeterministically() {
  val contents = listOf(files, different("b"), different("c"), files, different("b"))
  val commits = contents.mapIndexed { index, content -> commit("node-$index",
   parents = if (index == 0) emptyList() else listOf("node-${index - 1}"), content = content) }
  val history = state(commits)
  val expected = HistoryPresentation.from(history, 0)
  assertEquals(setOf(HistoryEdge("node-3", "node-4"), HistoryEdge("node-2", "node-3"), HistoryEdge("node-4", "node-2")), expected.edges.toSet())
  repeat(10) { seed ->
   assertEquals(expected, HistoryPresentation.from(state(commits.shuffled(kotlin.random.Random(seed))), 0))
  }
 }


 @Test fun offPageCardsDoNotSuppressVisibleRecordedRelationships() {
  val a = commit("a", "2026-10-01T00:00:00Z")
  val b = commit("b", "2026-10-20T00:00:00Z", listOf(a.id), different("b"))
  val c = commit("c", "2026-10-02T00:00:00Z", listOf(b.id), different("c"))
  val d = commit("d", "2026-10-21T00:00:00Z", listOf(c.id))
  val fillers = (0..19).map { commit("filler-$it", "2026-10-10T00:00:00Z", content = different("filler-$it")) }
  val history = state(listOf(a, b, c, d) + fillers)
  val first = HistoryPresentation.from(history, 0)
  assertTrue(first.nodes.any { it.id == d.id })
  assertTrue(first.nodes.any { it.id == b.id })
  assertTrue(first.nodes.none { it.id == c.id })
  assertEquals(listOf(HistoryEdge(d.id, b.id)), first.edges)
 }


 @Test fun contentHashParentResolvesLegacyCardWithoutChangingItsUuidMetadata() {
  val legacy = commit("00000000-0000-4000-8000-000000000001")
  val childFiles = different("hash-child")
  val childId = contentSha256(childFiles)
  val child = commit(childId, parents = listOf(contentSha256(files)), content = childFiles).let {
   it.copy(schema = 3, snapshot = it.snapshot.copy(id = childId))
  }
  val history = state(listOf(legacy, child))
  val before = history.copy(graph = history.graph.copy(commits = history.graph.commits.toMap()))
  val view = HistoryPresentation.from(history, 0)
  assertEquals(listOf(childId, legacy.id), view.nodes.map { it.id })
  assertEquals(listOf(HistoryEdge(legacy.id, childId)), view.edges)
  assertEquals(listOf(legacy.id), history.graph.parentIds(child))
  assertEquals(listOf(contentSha256(files)), child.parents)
  assertEquals(before, history)
 }

 @Test fun hashRecordsConnectOnlyTheirRecordedParentHashesAndKeepBranchDirections() {
  fun hashed(content: Map<String, String>, parents: List<String> = emptyList()): AppendCommit {
   val id = contentSha256(content)
   return commit(id, parents = parents, content = content).let { it.copy(schema = 3, snapshot = it.snapshot.copy(id = id)) }
  }
  val root = hashed(files)
  val left = hashed(different("hash-left"), listOf(root.id))
  val right = hashed(different("hash-right"), listOf(root.id))
  val unrelated = hashed(different("hash-unrelated"))
  val history = state(listOf(root, left, right, unrelated))
  val view = HistoryPresentation.from(history, 0)
  assertEquals(setOf(HistoryEdge(root.id, left.id), HistoryEdge(root.id, right.id)), view.edges.toSet())
  assertTrue(view.edges.none { it.fromId == unrelated.id || it.toId == unrelated.id })
  assertEquals(setOf(left.id, right.id, unrelated.id), history.graph.tips)
  repeat(10) { seed ->
   assertEquals(view, HistoryPresentation.from(state(history.graph.commits.values.shuffled(kotlin.random.Random(seed))), 0))
  }
 }

 @Test fun unresolvedParentHashDoesNotInventAnArrowToAnotherContentCard() {
  val unrelated = commit("unrelated")
  val childFiles = different("child")
  val childId = contentSha256(childFiles)
  val child = commit(childId, parents = listOf(contentSha256(different("missing"))), content = childFiles).let {
   it.copy(schema = 3, snapshot = it.snapshot.copy(id = childId))
  }
  assertTrue(HistoryPresentation.from(state(listOf(unrelated, child)), 0).edges.isEmpty())
 }


 @Test fun defaultCardNameUsesContentHashWhileSelectionRetainsTheRealLegacyTarget() {
  val original = commit("00000000-0000-4000-8000-000000000001")
  val duplicate = commit("00000000-0000-4000-8000-000000000002", "2026-10-02T00:00:00Z", listOf(original.id))
  val history = state(listOf(original, duplicate))
  val card = HistoryPresentation.from(history, 0).nodes.single()
  assertEquals(pdsync.i18n.CoreMessages.SAVE_1_S.text(contentSha256(files).take(6)), card.name)
  assertEquals(duplicate.id, card.id)
  assertEquals(duplicate.snapshot.id, card.snapshotId)
  assertEquals(duplicate.snapshot.id, HistoryPresentation.representativeSnapshotId(history, original.snapshot.id))
  assertEquals("My save", HistoryPresentation.from(history.copy(
   aliases = mapOf(duplicate.snapshot.id to "My save")), 0).nodes.single().name)
  val named = duplicate.copy(snapshot = duplicate.snapshot.copy(directoryMetadata = DirectorySnapshotMetadata(
   displayName = "Named in save", contentSha256 = contentSha256(files))))
  assertEquals("Named in save", HistoryPresentation.from(state(listOf(original, named)), 0).nodes.single().name)
  assertEquals("My save", HistoryPresentation.from(state(listOf(original, named)).copy(
   aliases = mapOf(named.snapshot.id to "My save")), 0).nodes.single().name)
 }

 @Test fun defaultHashRecordCardNameMatchesItsContentIdentity() {
  val id = contentSha256(files)
  val record = commit(id).let { it.copy(schema = 3, snapshot = it.snapshot.copy(id = id)) }
  val card = HistoryPresentation.from(state(listOf(record)), 0).nodes.single()
  assertEquals(pdsync.i18n.CoreMessages.SAVE_1_S.text(id.take(6)), card.name)
  assertEquals(id, card.id)
  assertEquals(id, card.snapshotId)
 }

}
