"""Android consumes the shared measured history; geometry is executed in shared-ui JVM tests."""
import unittest
from pathlib import Path
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / 'android-app/src/main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()
ADAPTER = (REPO / 'android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidOnboardingComposition.kt').read_text()
HISTORY = (REPO / 'shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedHistorySection.kt').read_text()


class HistoryRenderingContractTest(unittest.TestCase):
    def test_android_has_no_independent_native_timeline(self):
        self.assertNotIn('class TimelineView', MAIN)
        self.assertNotIn('new TimelineView', MAIN)
        self.assertNotIn('HistoryArrowGeometry', MAIN)
        self.assertIn('SharedHomeScreen(state, actions)', body(ADAPTER, 'fun showHome('))
        self.assertIn('HistoryPresentation.from(it, historyPage)', body(ADAPTER, 'fun showHome('))

    def test_shared_edges_use_measured_cards_and_actual_graph_edges(self):
        self.assertIn('sharedHistoryCanvasLayout(presentation, measured.mapValues', HISTORY)
        self.assertIn('SharedHistoryNodeMeasurement(pair.first.height.toFloat(), pair.second.height.toFloat())', HISTORY)
        self.assertIn('geometry.layout?.arrows?.forEach', HISTORY)
        self.assertIn('arrow.edge.dashed', HISTORY)
        self.assertIn('drawLine(color, arrow.start, arrow.tip', HISTORY)
        self.assertNotIn('cubicTo(', HISTORY)

    def test_only_history_has_horizontal_scroll_and_fixed_readable_lane_widths(self):
        self.assertIn('Column(Modifier.fillMaxWidth().horizontalScroll(scroll)', HISTORY)
        self.assertIn('Row(Modifier.width(560.dp)', HISTORY)
        self.assertIn('Modifier.width(280.dp)', HISTORY)
        self.assertIn('val cardWidth = 200.dp.roundToPx()', HISTORY)
        self.assertIn('public void changeHistoryPage(', body(MAIN, 'private void renderHome()'))
        self.assertNotIn('HorizontalScrollView', MAIN)


if __name__ == '__main__':
    unittest.main()
