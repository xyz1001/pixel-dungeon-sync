"""Production-view wiring contracts; geometry is executed by the JVM host checks."""
import re
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[2] / "main/java/io/github/xyz1001/pdsync/android/MainActivity.java").read_text()
TIMELINE = SOURCE[SOURCE.index("final class TimelineView"):SOURCE.index("private void renderDiscovery()")]
DRAW = TIMELINE[TIMELINE.index("protected void onDraw(Canvas canvas)"):]


class HistoryRenderingContractTest(unittest.TestCase):
    def test_edges_use_laid_out_card_bounds(self):
        self.assertIn("HistoryArrowGeometry.between(from, to, cards", DRAW)
        for getter in ("getLeft", "getTop", "getRight", "getBottom"):
            self.assertIn("card." + getter + "()", DRAW)
        self.assertNotRegex(DRAW, r"getWidth\(\)\s*\*")
        self.assertNotIn("this.heights[", DRAW)
        self.assertNotIn("historyNode.getColumn()", DRAW)

    def test_same_verified_edges_and_dashes_are_preserved(self):
        self.assertIn("this.model.getEdges()", DRAW)
        self.assertIn("bounds.get(historyEdge.getFromId())", DRAW)
        self.assertIn("bounds.get(historyEdge.getToId())", DRAW)
        self.assertIn("historyEdge.getDashed() ? new DashPathEffect", DRAW)
        self.assertLess(DRAW.index("this.paint.setPathEffect(null)"), DRAW.index("if (arrow.hasHead)"))
        self.assertEqual(3, DRAW.count("canvas.drawLine("))

    def test_edges_are_drawn_behind_cards_without_an_axis_or_outside_route(self):
        self.assertNotIn("dispatchDraw", TIMELINE)
        self.assertNotIn("drawPath", DRAW)
        self.assertNotIn("drawText", DRAW)
        self.assertNotIn("cubicTo", DRAW)
        self.assertIn("canvas.drawLine(arrow.startX, arrow.startY, arrow.endX, arrow.endY", DRAW)


if __name__ == "__main__":
    unittest.main()
