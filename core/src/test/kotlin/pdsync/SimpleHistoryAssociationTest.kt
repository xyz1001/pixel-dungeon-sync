package pdsync
import org.junit.jupiter.api.Test
import kotlin.test.*
import pdsync.presentation.HistoryPresentation
import pdsync.presentation.HistoryEdge

class SimpleHistoryAssociationTest {
 private fun id(n:Int)=n.toString(16).padStart(64,'0')
 private fun node(n:Int,parent:Int?=null)=SaveNode(id(n),parent?.let(::id),"save $n")
 private fun view(nodes:List<SaveNode>,latest:Int?,current:Int?,baseline:Int?,page:Int=0)=
  HistoryPresentation.from(SyncHistory(CommitGraph(emptyMap(),emptyMap()),baseline?.let(::id),
   if(current==null)emptyMap() else mapOf("game.dat" to id(current)),"",null,emptyMap(),
   simple=SimpleHistory(nodes,latest?.let(::id),current?.let(::id))),page)
 private fun localEdges(v:HistoryPresentation)=v.edges.filter {it.fromId=="local-current"||it.toId=="local-current"}
 @Test fun sameContentPairsLocalWithCloudOnceEvenWithoutBaseline() {
  val v=view(listOf(node(2,1),node(1),node(2,1)),2,2,null)
  assertEquals(listOf(HistoryEdge("local-current",id(2),sameContent=true)),localEdges(v))
  assertFalse(v.nodes.single {it.id=="local-current"}.dirty)
  assertTrue(v.edges.all {it.fromId!=it.toId});assertEquals(v.edges.distinct(),v.edges)
 }
 @Test fun editedLocalContinuesActualBaselineNotOtherBranchLatest() {
  val v=view(listOf(node(4,3),node(3,2),node(2,1),node(1)),4,5,2)
  assertEquals(listOf(HistoryEdge(id(2),"local-current",dashed=true)),localEdges(v))
  assertEquals(v.nodes.single {it.id==id(2)}.row,v.nodes.single {it.id=="local-current"}.row)
  assertTrue(v.nodes.single {it.id=="local-current"}.dirty)
 }
 @Test fun restoredHistoricalContentPairsWithRestoredNodeNotLatest() {
  val v=view(listOf(node(4,3),node(3,2),node(2,1),node(1)),4,2,2)
  assertEquals(listOf(HistoryEdge("local-current",id(2),sameContent=true)),localEdges(v))
  assertFalse(v.nodes.single {it.id=="local-current"}.dirty)
 }
 @Test fun contentMatchWinsOverUnrelatedBaseline() {
  assertEquals(listOf(HistoryEdge("local-current",id(2),sameContent=true)),localEdges(view(listOf(node(4,2),node(2)),4,2,4)))
 }
 @Test fun absentBaselineAndEmptyLocalDoNotInventLatestAssociation() {
  assertTrue(localEdges(view(listOf(node(4)),4,5,2)).isEmpty())
  assertTrue(localEdges(view(listOf(node(4)),4,null,4)).isEmpty())
  assertTrue(localEdges(view(emptyList(),null,5,null)).isEmpty())
 }
 @Test fun associationStaysWithVisibleHistoricalEndpointAcrossPages() {
  val nodes=(25 downTo 1).map {node(it,if(it>1)it-1 else null)}
  assertTrue(localEdges(view(nodes,25,1,1,0)).isEmpty())
  val v=view(nodes,25,1,1,1)
  assertEquals(listOf(HistoryEdge("local-current",id(1),sameContent=true)),localEdges(v))
  assertTrue(v.edges.all {e->v.nodes.any {it.id==e.fromId}&&v.nodes.any {it.id==e.toId}})
 }
}
