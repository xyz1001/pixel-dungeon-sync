package pdsync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class KnownAncestorChainTest {
 private fun node(id:String,parent:String?)=SaveNode(id,parent,id)

 @Test fun missingParentTruncatesWithoutInventingNodes() {
  val published=node("new","missing")
  assertEquals(listOf(published),knownAncestorChain(published,listOf(node("other",null))))
 }

 @Test fun cyclesTerminateBeforeRepeatingAnyNode() {
  val published=node("new","a");val a=node("a","b");val b=node("b","a")
  assertEquals(listOf(published,a,b),knownAncestorChain(published,listOf(a,b)))
  val backToPublished=a.copy(parentId="new")
  assertEquals(listOf(published,backToPublished),knownAncestorChain(published,listOf(backToPublished)))
 }

 @Test fun overwrittenSuccessorsAreExcludedWhileKnownAncestorsRemain() {
  val a=node("a",null);val b=node("b","a");val c=node("c","b");val d=node("d","c")
  val published=node("new","b")
  assertEquals(listOf(published,b,a),knownAncestorChain(published,listOf(d,c,b,a)))
 }
}
