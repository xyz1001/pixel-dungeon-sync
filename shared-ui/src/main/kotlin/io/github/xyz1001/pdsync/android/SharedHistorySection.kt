package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    fun headerElement(name: String, boundsInRoot: Rect) {}
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
    naming: SharedHistoryNaming? = null,
    loading: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = false,
) {
    val observer = LocalSharedHistoryLayoutObserver.current
    var requestedRenameNodeId by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onRefresh == null) {
            Text(SharedMessages.SAVE_HISTORY.text(), fontSize = 15.sp, lineHeight = 22.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 24.dp))
        } else Row(Modifier.fillMaxWidth().padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(SharedMessages.SAVE_HISTORY.text(), fontSize = 15.sp, lineHeight = 22.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).onGloballyPositioned { observer?.headerElement("title", it.boundsInRoot()) })
            SharedActionButton(SharedMessages.REFRESH.text(), onRefresh,
                enabled = refreshEnabled && !busy && !loading, style = SharedButtonStyle.TEXT,
                modifier = Modifier.onGloballyPositioned { observer?.headerElement("refresh", it.boundsInRoot()) })
        }
        if (presentation == null) {
            if (loading) Text(SharedMessages.READING_HISTORY.text(), color = SharedUiColors.muted, fontSize = 13.sp)
            return@Column
        }
        val scroll = rememberScrollState()
        BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(1.dp, SharedUiColors.fieldBorder, RoundedCornerShape(12.dp))
            .background(Color(0xFFFFFFFF))
            .onGloballyPositioned { observer?.viewport(it.boundsInRoot(), scroll) }) {
            val canvasWidth = maxOf(440.dp, maxWidth)
            Column(Modifier.fillMaxWidth().horizontalScroll(scroll)
                .semantics { contentDescription = SharedMessages.HISTORY_ACCESSIBILITY.text() }) {
                Row(Modifier.width(canvasWidth).background(Color(0xFFF1F5F9))
                    .drawBehind { drawLine(SharedUiColors.fieldBorder,
                        Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }) {
                    for (label in listOf(SharedMessages.CLOUD_SAVE.text(), SharedMessages.LOCAL.text())) {
                        Box(Modifier.width(canvasWidth / 2).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(label, color = SharedUiColors.label, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                val editingNode = naming?.let { draft ->
                    presentation.nodes.firstOrNull { it.id == requestedRenameNodeId && it.matchesNaming(draft) }
                        ?: presentation.nodes.firstOrNull { it.matchesNaming(draft) }
                }?.id
                SharedHistoryCanvas(presentation, selectedSnapshotId, busy, onSelect, { node ->
                    requestedRenameNodeId = node.id
                    onRename(node.snapshotId)
                }, canvasWidth, naming, editingNode)
            }
        }
        if (presentation.nodes.isEmpty()) Text(SharedMessages.NO_HISTORY.text(), color = SharedUiColors.muted, fontSize = 13.sp)
        if (presentation.pages > 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                SharedActionButton(SharedMessages.NEWER.text(), { onPageChange(presentation.page - 1) },
                    enabled = !busy && presentation.page > 0, style = SharedButtonStyle.TEXT,
                    modifier = Modifier.onGloballyPositioned { observer?.pageAction("newer", it.boundsInRoot()) })
                Text(SharedMessages.PAGE_NUMBER.text(presentation.page + 1, presentation.pages), fontSize = 13.sp, color = SharedUiColors.muted)
                SharedActionButton(SharedMessages.OLDER.text(), { onPageChange(presentation.page + 1) },
                    enabled = !busy && presentation.page + 1 < presentation.pages, style = SharedButtonStyle.TEXT,
                    modifier = Modifier.onGloballyPositioned { observer?.pageAction("older", it.boundsInRoot()) })
            }
        }
    }
}

private fun HistoryNode.matchesNaming(naming: SharedHistoryNaming): Boolean =
    if (naming.snapshotId == null) id == "local-current" else snapshotId == naming.snapshotId

private class HistoryGeometryHolder { var layout: SharedHistoryCanvasLayout? = null }

@Composable
private fun SharedHistoryCanvas(
    presentation: HistoryPresentation,
    selectedSnapshotId: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onRename: (HistoryNode) -> Unit,
    canvasWidth: Dp,
    naming: SharedHistoryNaming?,
    editingNodeId: String?,
) {
    val observer = LocalSharedHistoryLayoutObserver.current
    val geometry = remember(presentation) { HistoryGeometryHolder() }
    Layout(modifier = Modifier.width(canvasWidth)
        .drawBehind {
            drawLine(Color(0xFFE2E8F0), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height),
                1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            val nodes = presentation.nodes.associateBy { it.id }
            geometry.layout?.arrows?.forEach { arrow ->
                val color = when {
                    arrow.edge.dashed -> Color(0xFF855300)
                    nodes[arrow.edge.fromId]?.column != nodes[arrow.edge.toId]?.column -> Color(0xFF006877)
                    else -> Color(0xFF94A3B8)
                }
                drawLine(color, arrow.start, arrow.tip,
                    if (nodes[arrow.edge.fromId]?.column != nodes[arrow.edge.toId]?.column) 2.5.dp.toPx() else 2.2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = if (arrow.edge.dashed)
                        PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null)
                drawPath(Path().apply {
                    moveTo(arrow.tip.x, arrow.tip.y)
                    lineTo(arrow.headLeft.x, arrow.headLeft.y)
                    lineTo(arrow.headRight.x, arrow.headRight.y)
                    close()
                }, color)
                if (!arrow.edge.sameContent) drawCircle(Color(0xFFFFFFFF), 3.dp.toPx(), arrow.start)
                if (!arrow.edge.sameContent) drawCircle(Color(0xFF789685), 3.dp.toPx(), arrow.start, style = Stroke(1.5.dp.toPx()))
            }
        }
        .onGloballyPositioned { coordinates ->
            geometry.layout?.let { observer?.canvas(coordinates.positionInRoot(), it) }
        }, content = {
            presentation.nodes.forEach { node -> key(node.id) {
                SharedHistoryCard(node, node.selectable && node.snapshotId == selectedSnapshotId, busy, onSelect)
                Box(contentAlignment = Alignment.CenterEnd) {
                    val editing = naming != null && node.id == editingNodeId
                    val renameFocus = remember { FocusRequester() }
                    var wasEditing by remember { mutableStateOf(false) }
                    LaunchedEffect(editing, busy) {
                        if (wasEditing && !editing && !busy) renameFocus.requestFocus()
                        wasEditing = editing
                    }
                    if (naming != null && editing) {
                        SharedInlineNaming(naming)
                    } else {
                        TextButton(onClick = { onRename(node) }, enabled = !busy,
                            modifier = Modifier.heightIn(min = 22.dp).focusRequester(renameFocus),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(SharedMessages.RENAME.text(), fontSize = 11.sp, lineHeight = 16.sp,
                                fontWeight = FontWeight.SemiBold, color = SharedUiColors.muted)
                        }
                    }
                }
            } }
            if (presentation.localEmpty) Text(SharedMessages.NO_LOCAL_SAVES.text(), color = SharedUiColors.muted, fontSize = 14.sp,
                modifier = Modifier.padding(22.dp))
        }) { measurables, constraints ->
        val cardWidth = (canvasWidth / 2 - 32.dp).roundToPx()
        val nodeConstraints = Constraints.fixedWidth(cardWidth)
        val measured = presentation.nodes.mapIndexed { index, node ->
            node.id to (measurables[index * 2].measure(nodeConstraints) to
                measurables[index * 2 + 1].measure(nodeConstraints))
        }.toMap()
        val empty = if (presentation.localEmpty) measurables.last().measure(nodeConstraints) else null
        val actualLayout = sharedHistoryCanvasLayout(presentation, measured.mapValues { (_, pair) ->
            SharedHistoryNodeMeasurement(pair.first.height.toFloat(), pair.second.height.toFloat())
        }, density, canvasWidth.toPx())
        geometry.layout = actualLayout
        layout(actualLayout.width.roundToInt(), actualLayout.height.roundToInt()) {
            measured.forEach { (id, pair) ->
                val card = actualLayout.cards.getValue(id)
                val rename = actualLayout.renameActions.getValue(id)
                pair.first.place(card.left.roundToInt(), card.top.roundToInt())
                pair.second.place(rename.left.roundToInt(), rename.top.roundToInt())
            }
            empty?.place((canvasWidth / 2 + 16.dp).roundToPx(), 12.dp.roundToPx())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedHistoryCard(node: HistoryNode, selected: Boolean, busy: Boolean, onSelect: (String) -> Unit) {
    val background = when {
        node.dirty -> Color(0xFFFFFDF5)
        selected -> Color(0xFFDFF6FB)
        node.column == 0 -> Color(0xFFFBFDFF)
        else -> Color.White
    }
    val border = when {
        node.dirty -> Color(0xFF855300)
        selected -> Color(0xFF006877)
        node.column == 0 -> Color(0xFFCBD5E1)
        else -> Color(0xFFE2E8F0)
    }
    var modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(background).drawBehind {
        val stroke = if (selected) 2.dp.toPx() else 1.dp.toPx()
        drawRoundRect(border, topLeft = Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(stroke, pathEffect = if (node.dirty)
                PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null))
        if (node.id == "local-current") drawLine(if (node.dirty) border else Color(0xFF006877),
            Offset(2.5.dp.toPx(), 0f), Offset(2.5.dp.toPx(), size.height), 5.dp.toPx())
    }
    val selectableSnapshotId = node.snapshotId
    if (node.selectable && selectableSnapshotId != null) {
        modifier = modifier.selectable(selected, enabled = !busy, role = Role.Button,
            onClick = { onSelect(selectableSnapshotId) })
    }
    Column(modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(node.name, Modifier.widthIn(min = 120.dp), color = SharedUiColors.text, fontSize = 14.sp,
                lineHeight = 19.6.sp, fontWeight = FontWeight.Bold)
            val cloudHead = node.cloudHead
            val badgeBackground = if (node.dirty) Color(0xFFFEF3C7) else if (cloudHead) Color(0xFFE0F2FE)
                else if (node.column == 0) SharedUiColors.secondary else Color(0xFFDCFCE7)
            val badgeText = if (node.dirty) Color(0xFF92400E) else if (cloudHead) Color(0xFF0369A1)
                else if (node.column == 0) SharedUiColors.label else Color(0xFF15803D)
            val status = if (node.id == "local-current") SharedMessages.LOCAL_CURRENT_STATUS.text(node.status) else node.status
            Text(status, Modifier.widthIn(max = 150.dp).background(badgeBackground, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                color = badgeText, fontSize = 11.sp, lineHeight = 15.4.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(node.time, color = SharedUiColors.label, fontSize = 12.sp, lineHeight = 16.8.sp,
            fontWeight = FontWeight.SemiBold)
        Text(node.detail, modifier = Modifier.padding(top = 2.dp), color = SharedUiColors.muted,
            fontSize = 12.sp, lineHeight = 16.8.sp)
    }
}
