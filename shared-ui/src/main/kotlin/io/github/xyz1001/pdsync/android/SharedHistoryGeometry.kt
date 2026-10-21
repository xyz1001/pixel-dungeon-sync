package io.github.xyz1001.pdsync.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryPresentation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Immutable, side-effect-free geometry derived only from actual Compose measurements. */
internal data class SharedHistoryNodeMeasurement(val cardHeight: Float, val renameHeight: Float)

internal data class SharedHistoryArrow(
    val edge: HistoryEdge,
    val start: Offset,
    val tip: Offset,
    val headLeft: Offset,
    val headRight: Offset,
) {
    // Stop the shaft at the head base: a round cap at the tip protrudes past the triangle.
    val shaftEnd: Offset get() = (headLeft + headRight) / 2f
}

internal data class SharedHistoryCanvasLayout(
    val width: Float,
    val height: Float,
    val cards: Map<String, Rect>,
    val renameActions: Map<String, Rect>,
    val arrows: List<SharedHistoryArrow>,
)

/**
 * Rows hug their tallest measured card and rename action. Both lanes fill the available width;
 * a minimum readable canvas scrolls only on very narrow viewports.
 */
internal fun sharedHistoryCanvasLayout(
    presentation: HistoryPresentation,
    measurements: Map<String, SharedHistoryNodeMeasurement>,
    density: Float = 1f,
    canvasWidth: Float = 560 * density,
): SharedHistoryCanvasLayout {
    require(density > 0 && density.isFinite())
    val lane = canvasWidth / 2
    val inset = 16 * density
    val cardWidth = lane - inset * 2
    val top = 14 * density
    val gap = 6 * density
    val bottom = 14 * density
    val rowCount = presentation.rows.coerceAtLeast(1)
    val cardHeights = FloatArray(rowCount)
    val renameHeights = FloatArray(rowCount)
    presentation.nodes.forEach { node ->
        require(node.column in 0..1 && node.row in 0 until rowCount)
        val measured = measurements.getValue(node.id)
        cardHeights[node.row] = max(cardHeights[node.row], measured.cardHeight)
        renameHeights[node.row] = max(renameHeights[node.row], measured.renameHeight)
    }
    val rows = FloatArray(rowCount) { row ->
        max(126 * density, top + cardHeights[row] + gap + renameHeights[row] + bottom)
    }
    val tops = FloatArray(rows.size)
    for (index in 1 until rows.size) tops[index] = tops[index - 1] + rows[index - 1]
    val cards = linkedMapOf<String, Rect>()
    val rename = linkedMapOf<String, Rect>()
    presentation.nodes.forEach { node ->
        val measured = measurements.getValue(node.id)
        val left = node.column * lane + inset
        // Equal save times share an actual center line even when one card wraps to more lines.
        val rowTop = tops[node.row] + top
        val y = rowTop + (cardHeights[node.row] - measured.cardHeight) / 2f
        val renameTop = rowTop + cardHeights[node.row] + gap
        cards[node.id] = Rect(left, y, left + cardWidth, y + measured.cardHeight)
        rename[node.id] = Rect(left, renameTop, left + cardWidth, renameTop + measured.renameHeight)
    }
    return SharedHistoryCanvasLayout(lane * 2, rows.sum(), cards, rename,
        presentation.edges.mapNotNull { edge -> sharedHistoryArrow(edge, cards, density) })
}

/**
 * Match the reference: cross-lane edges join the facing side centers; same-lane edges join the
 * top/bottom centers. Arrow tips stop outside the target's opaque face, never halfway along an
 * estimated row. The additional head clearance also handles unusually tall wrapped cards.
 */
internal fun sharedHistoryArrow(
    edge: HistoryEdge,
    cards: Map<String, Rect>,
    density: Float = 1f,
): SharedHistoryArrow? {
    val from = cards[edge.fromId] ?: return null
    val to = cards[edge.toId] ?: return null
    val cross = abs(from.center.x - to.center.x) > density
    val right = to.center.x > from.center.x
    val down = to.center.y > from.center.y
    val start = if (cross) Offset(if (right) from.right else from.left, from.center.y)
        else Offset(from.center.x, if (down) from.bottom else from.top)
    val boundary = if (cross) Offset(if (right) to.left else to.right, to.center.y)
        else Offset(to.center.x, if (down) to.top else to.bottom)
    val delta = boundary - start
    val length = sqrt(delta.x * delta.x + delta.y * delta.y)
    if (length <= 0f) return null
    val unit = delta / length
    val normal = Offset(-unit.y, unit.x)
    val headLength = 8 * density
    val halfHead = 4 * density
    val approach = if (cross) abs(unit.x) else abs(unit.y)
    val lateral = if (cross) abs(normal.x) else abs(normal.y)
    val gap = max(5 * density, halfHead * lateral / approach - headLength + density)
    if (length <= gap + headLength) return null
    val tip = boundary - unit * gap
    val back = tip - unit * headLength
    return SharedHistoryArrow(edge, start, tip, back + normal * halfHead, back - normal * halfHead)
}
