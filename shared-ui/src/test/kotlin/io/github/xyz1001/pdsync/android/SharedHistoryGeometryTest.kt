package io.github.xyz1001.pdsync.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryNode
import pdsync.presentation.HistoryPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedHistoryGeometryTest {
    @Test fun measuredRowsGrowForWrappedCardsAndKeepTrueEdgesAtFacingCenters() {
        val nodes = listOf(node("new", 0, 0), node("local-current", 1, 1),
            node("base", 0, 2), node("local-baseline", 1, 2))
        val edges = listOf(HistoryEdge("base", "new"), HistoryEdge("base", "local-baseline"),
            HistoryEdge("local-baseline", "local-current", true))
        val view = HistoryPresentation(nodes, edges, 0, 1, 3, 2, false)
        val layout = sharedHistoryCanvasLayout(view, nodes.associate { it.id to
            SharedHistoryNodeMeasurement(if (it.id == "base") 240f else 100f, 24f) })
        assertEquals(560f, layout.width)
        assertEquals(208f + 208f + 336f, layout.height)
        assertEquals(200f, layout.cards.getValue("new").width)
        val base = layout.cards.getValue("base")
        val local = layout.cards.getValue("local-baseline")
        assertEquals(base.center.y, local.center.y, "equal-time unequal-height cards share a measured center line")
        assertTrue(base.top < local.top)
        assertEquals(layout.renameActions.getValue("base").top, layout.renameActions.getValue("local-baseline").top)
        val cross = layout.arrows.single { it.edge.toId == "local-baseline" }
        assertEquals(Offset(base.right, base.center.y), cross.start)
        assertTrue(cross.tip.x < local.left)
        val same = layout.arrows.single { it.edge.toId == "new" }
        assertEquals(base.center.x, same.start.x)
        assertEquals(base.center.x, same.tip.x)
        val dirty = layout.arrows.single { it.edge.dashed }
        assertEquals(local.center.x, dirty.start.x)
        assertEquals(local.center.x, dirty.tip.x)
        layout.arrows.forEach { arrow ->
            val target = layout.cards.getValue(arrow.edge.toId)
            assertFalse(target.contains(arrow.tip))
            assertFalse(target.contains(arrow.headLeft))
            assertFalse(target.contains(arrow.headRight))
        }
    }

    @Test fun steepCrossLaneArrowsKeepEntireHeadOutsideTargetAndNeverInventMissingEndpoints() {
        val cards = mapOf("a" to Rect(40f, 24f, 240f, 124f), "b" to Rect(320f, 1500f, 520f, 1700f))
        for (edge in listOf(HistoryEdge("a", "b"), HistoryEdge("b", "a"))) {
            val arrow = assertNotNull(sharedHistoryArrow(edge, cards))
            val target = cards.getValue(edge.toId)
            assertFalse(target.contains(arrow.tip)); assertFalse(target.contains(arrow.headLeft))
            assertFalse(target.contains(arrow.headRight))
        }
        assertNull(sharedHistoryArrow(HistoryEdge("a", "missing"), cards))
    }

    private fun node(id: String, column: Int, row: Int) =
        HistoryNode(id, null, id, "time", "detail", "status", column, row)
}
