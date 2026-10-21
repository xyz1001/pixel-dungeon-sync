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
        assertEquals(158f + 158f + 298f, layout.height)
        assertEquals(248f, layout.cards.getValue("new").width)
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

    @Test fun inlineEditorExpandsOnlyItsMeasuredRowWithoutMovingAnyEdgeIntoACard() {
        val nodes = listOf(node("cloud", 0, 0), node("copy", 1, 0), node("older", 0, 1))
        val view = HistoryPresentation(nodes, listOf(HistoryEdge("older", "cloud")), 0, 1, 2, 2, false)
        val closed = nodes.associate { it.id to SharedHistoryNodeMeasurement(92f, 22f) }
        val before = sharedHistoryCanvasLayout(view, closed)
        val after = sharedHistoryCanvasLayout(view, closed + ("copy" to SharedHistoryNodeMeasurement(92f, 88f)))
        assertEquals(before.cards.getValue("cloud"), after.cards.getValue("cloud"))
        assertEquals(before.cards.getValue("copy"), after.cards.getValue("copy"))
        assertEquals(66f, after.cards.getValue("older").top - before.cards.getValue("older").top)
        assertEquals(66f, after.height - before.height)
        assertTrue(after.renameActions.getValue("copy").bottom < after.cards.getValue("older").top)
        assertFalse(after.cards.getValue("cloud").contains(after.arrows.single().tip))
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

    @Test fun contentMatchHasOneLeftFacingHeadAndTransferDirectionsRemainUnchanged() {
        val cards = mapOf("cloud" to Rect(20f, 12f, 260f, 112f), "local" to Rect(300f, 12f, 540f, 112f))
        val match = assertNotNull(sharedHistoryArrow(HistoryEdge("local", "cloud", sameContent = true), cards))
        assertEquals(Offset(300f, 62f), match.start)
        assertEquals(Offset(265f, 62f), match.tip)
        assertTrue(match.headLeft.x > match.tip.x && match.headRight.x > match.tip.x)
        val restore = assertNotNull(sharedHistoryArrow(HistoryEdge("cloud", "local"), cards))
        assertEquals(Offset(260f, 62f), restore.start); assertEquals(Offset(295f, 62f), restore.tip)
        assertTrue(restore.headLeft.x < restore.tip.x && restore.headRight.x < restore.tip.x)
        val upload = assertNotNull(sharedHistoryArrow(HistoryEdge("local", "cloud"), cards))
        assertTrue(upload.tip.x < upload.start.x)
        assertTrue(upload.headLeft.x > upload.tip.x && upload.headRight.x > upload.tip.x)
    }

    @Test fun shaftStopsAtHeadBaseInEveryDirectionAndDensity() {
        val cards = mapOf("a" to Rect(20f, 20f, 260f, 120f),
            "b" to Rect(300f, 20f, 540f, 120f),
            "c" to Rect(20f, 220f, 260f, 320f),
            "d" to Rect(300f, 440f, 540f, 540f))
        for (density in listOf(1f, 2f, 3f)) {
            for ((from, to) in listOf("a" to "b", "b" to "a", "a" to "c", "c" to "a", "a" to "d", "d" to "a")) {
                val arrow = assertNotNull(sharedHistoryArrow(HistoryEdge(from, to), cards, density))
                assertEquals((arrow.headLeft + arrow.headRight) / 2f, arrow.shaftEnd)
                assertEquals(8f * density, (arrow.tip - arrow.shaftEnd).getDistance(), 0.001f)
                assertTrue((arrow.shaftEnd - arrow.start).getDistance() < (arrow.tip - arrow.start).getDistance())
            }
        }
    }

    private fun node(id: String, column: Int, row: Int) =
        HistoryNode(id, null, id, "time", "detail", "status", column, row)
}
