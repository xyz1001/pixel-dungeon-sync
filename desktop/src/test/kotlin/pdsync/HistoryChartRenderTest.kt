@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package pdsync

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class HistoryChartRenderTest {
 @TempDir lateinit var temp:Path
 @Test fun productionChartRendersVerifiedSessionAndSelectsRealCloudNode() {
  val store=object:AppendStore {
   val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>()
   override fun listCommits(cursor:String?)=CommitPage(commits.keys.toList())
   override fun readCommit(id:String)=commits[id]
   override fun readObject(id:String)=objects[id]
   override fun writeCommit(id:String,bytes:ByteArray){commits[id]=bytes}
   override fun writeObject(id:String,bytes:ByteArray){objects[id]=bytes}
   override fun legacyPresent()=false
  }
  val versions=object:ProfileVersions {
   override fun annotate(snapshot:Snapshot)=snapshot.copy(manifest=snapshot.manifest.copy(saveVersions=ShatteredVersions.detect(Snapshots.verify(snapshot,Shattered))))
   override fun target(path:String):TargetGame?=null
   override fun checkRestore(snapshot:Snapshot,targetPath:String){}
  }
  val root=temp.resolve("saves").createDirectory();root.resolve("game1").createDirectory()
  root.resolve("game1/game.dat").writeText("{\"version\":896,\"depth\":1}");root.resolve("rankings.dat").writeText("{\"total\":12}")
  val session=AppendProfileSession(AppConfig(saveRoot=root.toString()),temp.resolve("state"),versions=versions,captureQuietMillis=0)
  session.renameCurrent("初次出发"){true};session.push(store){true}
  root.resolve("game1/game.dat").writeText("{\"version\":896,\"depth\":2}");session.renameCurrent("继续探索"){true};session.push(store){true}
  root.resolve("game1/game.dat").writeText("{\"version\":896,\"depth\":3}")
  val state=session.syncHistory();assertEquals(2,state.localNodeCount);assertEquals(2,state.cloud.size)
  var selected:String?=null
  var renamed:String?=null
  val scene=ImageComposeScene(width=1000,height=650,density=Density(1f)) {
   MaterialTheme { Surface {Column(Modifier.fillMaxSize().padding(20.dp)){HistoryChart(state,null,false,{selected=it},{renamed=it})}} }
  }
  try {
   scene.render(0).close();scene.render(16_000_000).close()
   scene.render(32_000_000).use { image ->
    val report=Path.of("build/reports/history-chart.png");report.parent.createDirectories()
    image.encodeToData(EncodedImageFormat.PNG)!!.use {report.writeBytes(it.bytes)}
   }
   scene.sendPointerEvent(PointerEventType.Press,Offset(80f,80f))
   scene.sendPointerEvent(PointerEventType.Release,Offset(80f,80f))
   assertEquals(state.cloud.first().snapshot.id,selected)
   scene.sendPointerEvent(PointerEventType.Press,Offset(60f,166f))
   scene.sendPointerEvent(PointerEventType.Release,Offset(60f,166f))
   assertEquals(state.cloud.first().snapshot.id,renamed)
  } finally {scene.close()}
 }
}
