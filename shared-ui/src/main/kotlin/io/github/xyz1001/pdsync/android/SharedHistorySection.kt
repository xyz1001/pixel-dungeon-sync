package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pdsync.presentation.HistoryNode
import pdsync.presentation.HistoryPresentation
import kotlin.math.roundToInt

/** Optional internal observer for real offscreen layout/pointer verification, absent in production. */
internal interface SharedHistoryLayoutObserver {
    fun canvas(positionInRoot: Offset, layout: SharedHistoryCanvasLayout) {}
    fun viewport(boundsInRoot: Rect, scrollState: ScrollState) {}
    fun pageAction(name: String, boundsInRoot: Rect) {}
}
internal val LocalSharedHistoryLayoutObserver = staticCompositionLocalOf<SharedHistoryLayoutObserver?> { null }

/**
 * One physical history view for both platforms. This component performs no I/O and owns no save
 * data. The host retains its selected snapshot and page, then rebuilds the presentation from the
 * verified SyncHistory. Rename uses a real snapshot ID, or null for the current local directory.
 * There is no nested vertical scroller: the shared page shell scrolls its content normally.
 */
@Composable
fun SharedHistorySection(
    presentation: HistoryPresentation?,
    selectedSnapshotId: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onRename: (String?) -> Unit,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val observer = LocalSharedHistoryLayoutObserver.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("完整档案历史", fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        if (presentation == null) {
            Text("正在读取存档历史…", color = SharedUiColors.muted, fontSize = 13.sp)
            return@Column
        }
        Text("按存档时间从新到旧 · 选择云端存档恢复", color = SharedUiColors.muted,
            fontSize = 12.sp, lineHeight = 18.sp)
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(1.dp, SharedUiColors.line, RoundedCornerShape(12.dp))
            .background(Color(0xFFF8FAF8))
            .onGloballyPositioned { observer?.viewport(it.boundsInRoot(), scroll) }) {
            Column(Modifier.fillMaxWidth().horizontalScroll(scroll)
                .semantics { contentDescription = "云端与本机存档历史，可左右滚动" }) {
                Row(Modifier.width(560.dp).background(Color(0xFFEDF3EE))) {
                    for (label in listOf("云端存档", "本机")) {
                        Box(Modifier.width(280.dp).padding(15.dp), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                SharedHistoryCanvas(presentation, selectedSnapshotId, busy, onSelect, onRename)
            }
        }
        if (presentation.nodes.isEmpty()) Text("暂无存档历史", color = SharedUiColors.muted, fontSize = 13.sp)
        if (presentation.pages > 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                SharedActionButton("较新", { onPageChange(presentation.page - 1) },
                    enabled = !busy && presentation.page > 0, style = SharedButtonStyle.TEXT,
                    modifier = Modifier.onGloballyPositioned { observer?.pageAction("newer", it.boundsInRoot()) })
                Text("${presentation.page + 1} / ${presentation.pages}", fontSize = 13.sp, color = SharedUiColors.muted)
                SharedActionButton("更早", { onPageChange(presentation.page + 1) },
                    enabled = !busy && presentation.page + 1 < presentation.pages, style = SharedButtonStyle.TEXT,
                    modifier = Modifier.onGloballyPositioned { observer?.pageAction("older", it.boundsInRoot()) })
            }
        }
    }
}

private class HistoryGeometryHolder { var layout: SharedHistoryCanvasLayout? = null }

@Composable
private fun SharedHistoryCanvas(
    presentation: HistoryPresentation,
    selectedSnapshotId: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onRename: (String?) -> Unit,
) {
    val observer = LocalSharedHistoryLayoutObserver.current
    val geometry = remember(presentation) { HistoryGeometryHolder() }
    Layout(modifier = Modifier.width(560.dp)
        .drawBehind {
            drawLine(Color(0xFFDCE6DE), Offset(280.dp.toPx(), 0f), Offset(280.dp.toPx(), size.height),
                1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            val nodes = presentation.nodes.associateBy { it.id }
            geometry.layout?.arrows?.forEach { arrow ->
                val color = when {
                    arrow.edge.dashed -> Color(0xFF9A8053)
                    nodes[arrow.edge.fromId]?.column != nodes[arrow.edge.toId]?.column -> Color(0xFF326650)
                    else -> Color(0xFF9AAA9F)
                }
                drawLine(color, arrow.start, arrow.tip, 1.75.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = if (arrow.edge.dashed)
                        PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null)
                drawPath(Path().apply {
                    moveTo(arrow.tip.x, arrow.tip.y)
                    lineTo(arrow.headLeft.x, arrow.headLeft.y)
                    lineTo(arrow.headRight.x, arrow.headRight.y)
                    close()
                }, color)
                drawCircle(Color(0xFFF8FAF8), 3.dp.toPx(), arrow.start)
                drawCircle(Color(0xFF789685), 3.dp.toPx(), arrow.start, style = Stroke(1.5.dp.toPx()))
            }
        }
        .onGloballyPositioned { coordinates ->
            geometry.layout?.let { observer?.canvas(coordinates.positionInRoot(), it) }
        }, content = {
            presentation.nodes.forEach { node -> key(node.id) {
                SharedHistoryCard(node, node.selectable && node.snapshotId == selectedSnapshotId, busy, onSelect)
                Box(contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { onRename(node.snapshotId) }, enabled = !busy,
                        modifier = Modifier.height(24.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                        Text("命名", fontSize = 11.sp, lineHeight = 16.sp, color = SharedUiColors.muted)
                    }
                }
            } }
            if (presentation.localEmpty) Text("无存档", color = SharedUiColors.muted, fontSize = 14.sp,
                modifier = Modifier.padding(22.dp))
        }) { measurables, constraints ->
        val cardWidth = 200.dp.roundToPx()
        val nodeConstraints = Constraints.fixedWidth(cardWidth)
        val measured = presentation.nodes.mapIndexed { index, node ->
            node.id to (measurables[index * 2].measure(nodeConstraints) to
                measurables[index * 2 + 1].measure(nodeConstraints))
        }.toMap()
        val empty = if (presentation.localEmpty) measurables.last().measure(nodeConstraints) else null
        val actualLayout = sharedHistoryCanvasLayout(presentation, measured.mapValues { (_, pair) ->
            SharedHistoryNodeMeasurement(pair.first.height.toFloat(), pair.second.height.toFloat())
        }, density)
        geometry.layout = actualLayout
        layout(actualLayout.width.roundToInt(), actualLayout.height.roundToInt()) {
            measured.forEach { (id, pair) ->
                val card = actualLayout.cards.getValue(id)
                val rename = actualLayout.renameActions.getValue(id)
                pair.first.place(card.left.roundToInt(), card.top.roundToInt())
                pair.second.place(rename.left.roundToInt(), rename.top.roundToInt())
            }
            empty?.place(320.dp.roundToPx(), 24.dp.roundToPx())
        }
    }
}

@Composable
private fun SharedHistoryCard(node: HistoryNode, selected: Boolean, busy: Boolean, onSelect: (String) -> Unit) {
    val background = when {
        node.dirty -> Color(0xFFFFF3C4)
        selected -> Color(0xFFE7F3EB)
        node.column == 0 -> Color(0xFFEFF7F0)
        else -> Color.White
    }
    val border = when {
        node.dirty -> Color(0xFFB58B22)
        selected -> Color(0xFF326650)
        node.column == 0 -> Color(0xFF72A384)
        else -> Color(0xFFBFD1C4)
    }
    val foreground = if (node.dirty) Color(0xFF655018) else Color(0xFF294C3A)
    var modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(background).drawBehind {
        val stroke = if (selected) 2.dp.toPx() else 1.dp.toPx()
        drawRoundRect(border, topLeft = Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(stroke, pathEffect = if (node.dirty)
                PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null))
        if (node.id == "local-current") drawLine(if (node.dirty) border else Color(0xFF326650),
            Offset(2.dp.toPx(), 0f), Offset(2.dp.toPx(), size.height), 4.dp.toPx())
    }
    val selectableSnapshotId = node.snapshotId
    if (node.selectable && selectableSnapshotId != null) {
        modifier = modifier.selectable(selected, enabled = !busy, role = Role.Button,
            onClick = { onSelect(selectableSnapshotId) })
    }
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(node.name, color = foreground, fontSize = 13.sp, lineHeight = 18.85.sp, fontWeight = FontWeight.SemiBold)
        Text(node.time, color = foreground, fontSize = 13.sp, lineHeight = 18.85.sp, fontWeight = FontWeight.SemiBold)
        Text(node.detail, color = foreground, fontSize = 11.sp, lineHeight = 15.95.sp)
        Text((if (node.id == "local-current") "本机当前 · " else "") + node.status,
            color = SharedUiColors.muted, fontSize = 11.sp, lineHeight = 15.95.sp)
    }
}
