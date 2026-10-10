package pdsync

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

internal fun historyTime(value:String?):String=value?.let { runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull() } ?: "时间未知"

/** Bounded pages keep the complete verified history selectable without an unbounded canvas. */
@Composable internal fun HistoryChart(state:SyncHistory?,selected:String?,busy:Boolean,onSelect:(String)->Unit,onRename:(String?)->Unit) {
 if(state==null){Text("正在读取存档历史…");return}
 var page by remember { mutableStateOf(0) }
 val pages=maxOf(1,(state.cloud.size+19)/20);val current=page.coerceIn(0,pages-1)
 val cloud=state.cloud.drop(current*20).take(20)
 val rows=maxOf(1,cloud.size,state.localNodeCount)
 val rowHeight=164.dp
 Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Row(Modifier.fillMaxWidth()){Text("云端存档",Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Text("本机",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)}
  BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
   val width=maxWidth;val height=rowHeight*rows
   Box(Modifier.width(width).height(height)) {
    Canvas(Modifier.fillMaxSize()) {
     val step=rowHeight.toPx();val left=size.width*.42f;val right=size.width*.58f
     fun edge(from:Offset,to:Offset,dashed:Boolean=false) {
      val color=if(dashed)Color(0xFFB58D39) else Color(0xFF769887)
      drawLine(color,from,to,2.dp.toPx(),pathEffect=if(dashed)PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(),5.dp.toPx())) else null)
      val delta=to-from;val length=sqrt(delta.x*delta.x+delta.y*delta.y);if(length<1)return
      val unit=delta/length;val middle=(from+to)/2f;val back=middle-unit*8.dp.toPx();val normal=Offset(-unit.y,unit.x)*4.dp.toPx()
      drawLine(color,back+normal,middle,2.dp.toPx());drawLine(color,back-normal,middle,2.dp.toPx())
     }
     val indices=cloud.mapIndexed { i,c->c.id to i }.toMap()
     cloud.forEachIndexed { childIndex,commit -> commit.parents.forEach { parent -> indices[parent]?.let { parentIndex ->
      edge(Offset(left,(parentIndex+.5f)*step),Offset(left,(childIndex+.5f)*step))
     } } }
     val base=state.baselineCommit
     if(state.localNodeCount>0 && base!=null)indices[base.id]?.let { index ->
      val localRow=if(state.localNodeCount==2)1 else 0
      if(state.clean || state.localNodeCount==2) {
       val a=Offset(left,(index+.5f)*step);val b=Offset(right,(localRow+.5f)*step)
       when(state.baselineDirection){"upload"->edge(b,a);"restore"->edge(a,b)}
      }
     }
     if(state.localNodeCount==2)edge(Offset(right,1.5f*step),Offset(right,.5f*step),true)
    }
    Column {
     repeat(rows){row ->
      Row(Modifier.fillMaxWidth().height(rowHeight),horizontalArrangement=Arrangement.SpaceBetween) {
       val commit=cloud.getOrNull(row)
       Box(Modifier.fillMaxWidth(.42f).padding(vertical=5.dp)) { commit?.let {
        val m=it.snapshot
        HistoryNode(state.aliases[m.id]?.takeIf(String::isNotBlank) ?: m.directoryMetadata?.displayName?.takeIf(String::isNotBlank) ?: "存档 ${it.id.take(6)}",
         historyTime(m.metadata?.latestFileModifiedAt ?: m.metadata?.capturedAt),"${m.metadata?.device?.label ?: "来源未知"} · ${m.saveVersions?.runs?.size?.toString() ?: "?"} 个槽位",
         if(it.id in state.graph.tips)"云端当前分支" else "云端历史",false,selected==m.id,busy,{onSelect(m.id)},{onRename(m.id)})
       } }
       Box(Modifier.fillMaxWidth(.724f).padding(vertical=5.dp)) {
        if(row==0 && state.localNodeCount==0)Text("无存档",Modifier.padding(20.dp),color=Color.Gray)
        else if(row==0 && state.localNodeCount>0)HistoryNode(state.currentName.ifBlank{"本机当前"},historyTime(state.summary?.slots?.values?.filterNotNull()?.maxOrNull()),
         "${state.summary?.slots?.size?.toString() ?: "?"} 个槽位 · 游玩 ${state.summary?.plays?.toString() ?: "未知"} 次",if(state.clean)"已同步云端" else "未同步云端",!state.clean,false,busy,{}, {onRename(null)})
        else if(row==1 && state.localNodeCount==2)state.baselineCommit?.snapshot?.let { m ->
         HistoryNode(m.directoryMetadata?.displayName?.ifBlank{"同步基线"} ?: "同步基线",historyTime(m.metadata?.latestFileModifiedAt),"${m.saveVersions?.runs?.size?.toString() ?: "?"} 个槽位","已同步云端",false,false,busy,{}, {onRename(m.id)})
        }
       }
      }
     }
    }
   }
  }
  if(pages>1)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(enabled=current>0,onClick={page=current-1}){Text("较新")};Text("${current+1} / $pages");TextButton(enabled=current+1<pages,onClick={page=current+1}){Text("更早")}}
 }
}

@Composable private fun HistoryNode(name:String,time:String,detail:String,status:String,dirty:Boolean,selected:Boolean,busy:Boolean,onSelect:()->Unit,onRename:()->Unit) {
 Surface(color=when{dirty->Color(0xFFFFE8A8);selected->Color(0xFFD7EBDD);else->Color(0xFFF3F6F1)},shape=MaterialTheme.shapes.small,border=if(selected)BorderStroke(2.dp,Color(0xFF326650)) else null) {
  Column(Modifier.fillMaxWidth().clickable(enabled=!busy,onClick=onSelect).padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
   Text(name,maxLines=1,fontWeight=FontWeight.Medium);Text(time,style=MaterialTheme.typography.caption)
   Text(detail,style=MaterialTheme.typography.caption,maxLines=1);Text(status,style=MaterialTheme.typography.caption)
   TextButton(enabled=!busy,onClick=onRename,contentPadding=PaddingValues(0.dp),modifier=Modifier.height(25.dp)){Text("命名",style=MaterialTheme.typography.caption)}
  }
 }
}
