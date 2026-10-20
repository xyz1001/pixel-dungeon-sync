package pdsync

import pdsync.i18n.DesktopMessages

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
import pdsync.presentation.HistoryPresentation

internal fun historyTime(value:String?):String=value?.let { runCatching { DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT).withLocale(pdsync.i18n.UiLocale.formattingLocale()).withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull() } ?: DesktopMessages.UNKNOWN_TIME.text()

/** Bounded pages keep the complete verified history selectable without an unbounded canvas. */
@Composable internal fun HistoryChart(state:SyncHistory?,selected:String?,busy:Boolean,onSelect:(String)->Unit,onRename:(String?)->Unit) {
 if(state==null){Text(DesktopMessages.READING_SAVE_HISTORY.text());return}
 var page by remember { mutableStateOf(0) }
 val pages=maxOf(1,(state.cloud.size+19)/20);val current=page.coerceIn(0,pages-1)
 val cloud=state.cloud.drop(current*20).take(20)
 val rows=maxOf(1,cloud.size,state.localNodeCount)
 val rowHeight=164.dp
 Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Row(Modifier.fillMaxWidth()){Text(DesktopMessages.CLOUD_SAVES.text(),Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Text(DesktopMessages.LOCAL.text(),Modifier.weight(1f),fontWeight=FontWeight.SemiBold)}
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
        HistoryNode(state.aliases[m.id]?.takeIf(String::isNotBlank) ?: m.directoryMetadata?.displayName?.takeIf(String::isNotBlank) ?: DesktopMessages.SAVE_1_S.text(it.id.take(6)),
         historyTime(m.metadata?.latestFileModifiedAt ?: m.metadata?.capturedAt),DesktopMessages.SOURCE_AND_SLOT_COUNT.text(m.metadata?.device?.label ?: DesktopMessages.UNKNOWN_SOURCE.text(), m.saveVersions?.runs?.size?.toString() ?: "?"),
         if(it.id in state.graph.tips)DesktopMessages.CURRENT_CLOUD_BRANCH.text() else DesktopMessages.CLOUD_HISTORY.text(),false,selected==m.id,busy,{onSelect(m.id)},{onRename(m.id)})
       } }
       Box(Modifier.fillMaxWidth(.724f).padding(vertical=5.dp)) {
        if(row==0 && state.localNodeCount==0)Text(DesktopMessages.NO_SAVES.text(),Modifier.padding(20.dp),color=Color.Gray)
        else if(row==0 && state.localNodeCount>0)HistoryNode(state.currentName.ifBlank{DesktopMessages.CURRENT_LOCAL_SAVE.text()},historyTime(state.summary?.slots?.values?.filterNotNull()?.maxOrNull()),
         DesktopMessages.SLOT_COUNT_AND_PLAYS.text(state.summary?.slots?.size?.toString() ?: "?", HistoryPresentation.plays(state.summary?.plays)),if(state.clean)DesktopMessages.SYNCED_TO_CLOUD.text() else DesktopMessages.NOT_SYNCED_TO_CLOUD.text(),!state.clean,false,busy,{}, {onRename(null)})
        else if(row==1 && state.localNodeCount==2)state.baselineCommit?.snapshot?.let { m ->
         HistoryNode(m.directoryMetadata?.displayName?.ifBlank{DesktopMessages.SYNC_BASELINE.text()} ?: DesktopMessages.SYNC_BASELINE.text(),historyTime(m.metadata?.latestFileModifiedAt),DesktopMessages.SLOT_COUNT.text(m.saveVersions?.runs?.size?.toString() ?: "?"),DesktopMessages.SYNCED_TO_CLOUD.text(),false,false,busy,{}, {onRename(m.id)})
        }
       }
      }
     }
    }
   }
  }
  if(pages>1)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(enabled=current>0,onClick={page=current-1}){Text(DesktopMessages.NEWER.text())};Text("${current+1} / $pages");TextButton(enabled=current+1<pages,onClick={page=current+1}){Text(DesktopMessages.OLDER.text())}}
 }
}

@Composable private fun HistoryNode(name:String,time:String,detail:String,status:String,dirty:Boolean,selected:Boolean,busy:Boolean,onSelect:()->Unit,onRename:()->Unit) {
 Surface(color=when{dirty->Color(0xFFFFE8A8);selected->Color(0xFFD7EBDD);else->Color(0xFFF3F6F1)},shape=MaterialTheme.shapes.small,border=if(selected)BorderStroke(2.dp,Color(0xFF326650)) else null) {
  Column(Modifier.fillMaxWidth().clickable(enabled=!busy,onClick=onSelect).padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
   Text(name,maxLines=1,fontWeight=FontWeight.Medium);Text(time,style=MaterialTheme.typography.caption)
   Text(detail,style=MaterialTheme.typography.caption,maxLines=1);Text(status,style=MaterialTheme.typography.caption)
   TextButton(enabled=!busy,onClick=onRename,contentPadding=PaddingValues(0.dp),modifier=Modifier.height(25.dp)){Text(DesktopMessages.RENAME.text(),style=MaterialTheme.typography.caption)}
  }
 }
}
