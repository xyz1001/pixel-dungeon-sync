package pdsync

import org.junit.jupiter.api.Test
import kotlin.test.*
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryPresentation

class SimpleHistoryPresentationTest {
 @Test fun localCardUsesObservedLocalTimeAndDeviceEvenWhenMatchingCloudContent() {
  val upload = CommitOperationMetadata(
   device = DeviceIdentity("12345678-1234-1234-1234-123456789abc", "上传电脑"),
   preparedAt = "2026-11-10T08:09:10Z")
  val localDevice = DeviceIdentity("abcdef12-1234-1234-1234-123456789abc", "本机手机")
  val localTime = "2026-11-11T10:11:12Z"
  val recorded = node(3).copy(operation = upload)
  val simple = SimpleHistory(listOf(recorded), recorded.hashId, recorded.hashId,
   localModifiedAt = localTime, localDevice = localDevice)
  fun cards(value: SimpleHistory) = HistoryPresentation.from(SyncHistory(
   CommitGraph(emptyMap(), emptyMap()), recorded.hashId, mapOf("game.dat" to id(3)),
   "本机名称", null, emptyMap(), simple = value), 0).nodes
  val shown = cards(simple)
  val current = shown.single { it.id == "local-current" }
  assertEquals("本机名称", current.name)
  assertEquals(recorded.hashId, current.contentId)
  assertEquals(HistoryPresentation.time(localTime), current.time)
   assertEquals("设备名：本机手机", current.detail)
  val cloud = shown.single { it.column == 0 }
  assertEquals(HistoryPresentation.time(upload.preparedAt), cloud.time)
   assertEquals("设备名：${upload.device.label}", cloud.detail)
  assertNotEquals(cloud.time, current.time)
  assertNotEquals(cloud.detail, current.detail)
  val unknownTime = cards(simple.copy(localModifiedAt = null)).single { it.id == "local-current" }
  assertEquals("", unknownTime.time)
   assertEquals("设备名：${localDevice.label}", unknownTime.detail)
  val unknownDevice = cards(simple.copy(localDevice = null)).single { it.id == "local-current" }
  assertEquals(HistoryPresentation.time(localTime), unknownDevice.time)
  assertEquals("", unknownDevice.detail)
 }

 @Test fun recordedUploadTimeAndDeviceAreShownOnlyForTheirCloudContentAndBaseline() {
  val operation = CommitOperationMetadata(
   device = DeviceIdentity("12345678-1234-1234-1234-123456789abc", "上传电脑"),
   preparedAt = "2026-11-10T08:09:10Z")
  val recorded = node(3).copy(name = "手动名称", operation = operation)
  val old = node(2).copy(name = "")
  val v = view(listOf(recorded, old), 4, 3)
  val cloud = v.nodes.single { it.id == recorded.hashId }
  val baseline = v.nodes.single { it.id == "local-baseline" }
  for (card in listOf(cloud, baseline)) {
   assertEquals(HistoryPresentation.time(operation.preparedAt), card.time)
   assertEquals("设备名：上传电脑", card.detail)
   assertEquals("手动名称", card.name)
   assertEquals(recorded.hashId, card.contentId)
  }
  for (card in listOf(v.nodes.single { it.id == old.hashId }, v.nodes.single { it.id == "local-current" })) {
   assertEquals("", card.time)
   assertEquals("", card.detail)
  }
  // A local content match is not evidence that this machine uploaded it at that time.
  val matchingLocal = view(listOf(recorded), 3, 3).nodes.single { it.id == "local-current" }
  assertEquals("", matchingLocal.time)
  assertEquals("", matchingLocal.detail)
 }

 @Test fun namesNeverStandInForContentIdentity() {
  val hashes = listOf("d04de083", "8e3ac18f", "4894b709", "4075a98f", "8d8ebb61").map { it.padEnd(64, '0') }
  val nodes = hashes.map { SaveNode(it, null, "存档 8d8ebb") }
  val state = SyncHistory(CommitGraph(emptyMap(), emptyMap()), hashes.last(), emptyMap(), "", null,
   emptyMap(), simple = SimpleHistory(nodes + nodes.last(), hashes.first(), "f".repeat(64)))
  val v = HistoryPresentation.from(state, 0)
  val cloud = v.nodes.filter { it.column == 0 }
  assertEquals(5, v.cloudCount)
  assertEquals(setOf("存档 8d8ebb"), cloud.map { it.name }.toSet())
  assertEquals(hashes, cloud.map { it.contentId })
  assertEquals(5, cloud.map { it.contentId!!.take(6) }.distinct().size)
  assertEquals(hashes.last(), v.nodes.single { it.id == "local-baseline" }.contentId)
  assertEquals("f".repeat(64), v.nodes.single { it.id == "local-current" }.contentId)
  assertEquals("", v.nodes.single { it.id == "local-current" }.name)
 }

 @Test fun unnamedCloudAndLocalCardsStayUnnamedWhileCustomNamesSurvive() {
  val v = view(listOf(node(3).copy(name = ""), node(2).copy(name = "手动名称")), 4, 3)
  assertEquals("", v.nodes.single { it.id == id(3) }.name)
  assertEquals("", v.nodes.single { it.id == "local-baseline" }.name)
  assertEquals("", v.nodes.single { it.id == "local-current" }.name)
  assertEquals("手动名称", v.nodes.single { it.id == id(2) }.name)
  assertTrue(v.nodes.all { it.contentId != null })
 }

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
