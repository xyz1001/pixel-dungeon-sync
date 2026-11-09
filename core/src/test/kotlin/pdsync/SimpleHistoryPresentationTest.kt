package pdsync

import org.junit.jupiter.api.Test
import kotlin.test.*
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryPresentation

class SimpleHistoryPresentationTest {
 private fun id(n: Int) = n.toString(16).padStart(64, '0')
 private fun node(n: Int, parent: Int? = null) = SaveNode(id(n), parent?.let(::id), "save $n")
 private fun view(nodes: List<SaveNode>, current: Int?, baseline: Int?, page: Int = 0) =
  HistoryPresentation.from(SyncHistory(CommitGraph(emptyMap(), emptyMap()), baseline?.let(::id),
   if (current == null) emptyMap() else mapOf("game.dat" to id(current)), "", null, emptyMap(),
   simple = SimpleHistory(nodes, nodes.firstOrNull()?.hashId, current?.let(::id))), page)
 private fun localEdges(v: HistoryPresentation) = v.edges.filter { it.fromId.startsWith("local-") || it.toId.startsWith("local-") }
 private fun visibleEdges(v: HistoryPresentation) = assertTrue(v.edges.all { e -> v.nodes.any { it.id == e.fromId } && v.nodes.any { it.id == e.toId } })

 @Test fun restoredThenChangedHasTwoLocalCardsAndCorrectEdges() {
  val v = view(listOf(node(3, 2), node(2, 1), node(1)), 4, 2)
  val baseline = v.nodes.single { it.id == "local-baseline" }
  val current = v.nodes.single { it.id == "local-current" }
  assertEquals(v.nodes.single { it.id == id(2) }.row, baseline.row)
  assertNotEquals(baseline.row, current.row)
  assertFalse(baseline.dirty); assertTrue(current.dirty)
  assertEquals(setOf(HistoryEdge("local-baseline", id(2), sameContent = true), HistoryEdge("local-baseline", "local-current", dashed = true)), localEdges(v).toSet())
  visibleEdges(v)
 }

 @Test fun unchangedOrMatchingOlderCloudUsesOnlyCurrentCard() {
  for (baseline in listOf(2, 3, null)) {
   val v = view(listOf(node(3, 2), node(2)), 2, baseline)
   assertNull(v.nodes.find { it.id == "local-baseline" })
   assertFalse(v.nodes.single { it.id == "local-current" }.dirty)
   assertEquals(listOf(HistoryEdge("local-current", id(2), sameContent = true)), localEdges(v))
  }
 }

 @Test fun missingBaselineAndEmptyLocalNeverInventCardsOrEdges() {
  val missing = view(listOf(node(3)), 4, 2)
  assertNull(missing.nodes.find { it.id == "local-baseline" }); assertTrue(localEdges(missing).isEmpty())
  val empty = view(listOf(node(3)), null, 3)
  assertTrue(empty.localEmpty); assertTrue(empty.nodes.none { it.id.startsWith("local-") }); assertTrue(localEdges(empty).isEmpty())
  val noCloud = view(emptyList(), 4, 2)
  assertEquals(1, noCloud.rows); assertTrue(localEdges(noCloud).isEmpty())
 }

 @Test fun pageBoundaryCountsExtraRowAndFiltersBothEndpoints() {
  val nodes = (20 downTo 1).map { node(it, if (it > 1) it - 1 else null) }
  val first = view(nodes, 21, 1)
  val second = view(nodes, 21, 1, 1)
  assertEquals(2, first.pages); assertEquals(20, first.rows); assertEquals(1, second.rows)
  assertNotNull(first.nodes.find { it.id == "local-baseline" }); assertNotNull(second.nodes.find { it.id == "local-current" })
  assertEquals(listOf(HistoryEdge("local-baseline", id(1), sameContent = true)), localEdges(first))
  assertTrue(localEdges(second).isEmpty()); visibleEdges(first); visibleEdges(second)
  assertEquals(1, view(nodes, 21, 1, 99).page)
 }

 @Test fun duplicateHashesRemainSingleCloudCardAndDoNotDistortRows() {
  val v = view(listOf(node(3, 2), node(2, 1), node(2, 1), node(1)), 4, 2)
  assertEquals(3, v.cloudCount); assertEquals(1, v.nodes.count { it.id == id(2) })
  assertEquals(4, v.rows); assertEquals(v.edges.distinct(), v.edges); visibleEdges(v)
 }
}
