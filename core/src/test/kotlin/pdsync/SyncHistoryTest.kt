package pdsync
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class SyncHistoryTest {
 private fun commit(files:Map<String,String>,parents:List<String> = emptyList())=AppendCommit(id=UUID.randomUUID().toString(),parents=parents,snapshot=Manifest(game="shattered",profile="demo",version="v",id=UUID.randomUUID().toString(),parent=null,sha256="0".repeat(64),files=files),objectBytes=0)
 @Test fun localViewHasOnlyZeroOneOrTwoNodesAndNoTimestampBasedProtection() {
  val files=mapOf("game1/game.dat" to "a".repeat(64));val base=commit(files);val graph=CommitGraph(mapOf(base.id to base),emptyMap())
  fun view(current:Map<String,String>,baseline:String?=base.id)=SyncHistory(graph,baseline,current,"",null,emptyMap())
  assertEquals(0,view(emptyMap()).localNodeCount)
  assertEquals(1,view(files).localNodeCount);assertTrue(view(files).clean);assertTrue(view(files).currentProtected)
  val changed=mapOf("game1/game.dat" to "b".repeat(64));assertEquals(2,view(changed).localNodeCount);assertFalse(view(changed).currentProtected)
  assertEquals(1,view(changed,null).localNodeCount)
 }
 @Test fun defaultIsSingleVerifiedTipAndNeverInventsWinnerForBranches() {
  val base=commit(emptyMap());val a=commit(emptyMap(),listOf(base.id));val b=commit(emptyMap(),listOf(base.id))
  fun view(commits:List<AppendCommit>)=SyncHistory(CommitGraph(commits.associateBy {it.id},emptyMap()),null,emptyMap(),"",null,emptyMap())
  assertEquals(a.snapshot.id,view(listOf(base,a)).defaultSnapshot)
  assertNull(view(listOf(base,a,b)).defaultSnapshot)
 }
}
