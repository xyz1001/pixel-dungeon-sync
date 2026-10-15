"""The retired Step 4 account panel is unreachable from desktop navigation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
MAIN = (ROOT / 'desktop/src/main/kotlin/pdsync/Main.kt').read_text()
PANEL = (ROOT / 'desktop/src/main/kotlin/pdsync/DesktopComparisonAppreciation.kt').read_text()

class InlineAppreciationContract(unittest.TestCase):
    def test_removed_fourth_step_never_renders_its_old_panel(self):
        self.assertNotIn('Page.COMPARISON', MAIN)
        self.assertNotIn('SharedComparisonScreen(', MAIN)
        self.assertNotIn('DesktopComparisonAppreciation(', MAIN)
        self.assertIn('SharedConnectionScreen(controller, game)', MAIN)

    def test_controller_identity_is_connection_not_comparison_or_window(self):
        self.assertIn('key(config.endpoint, config.username, password, config.profile, config.connectionProvider)', PANEL)
        self.assertIn('val controller = remember', PANEL)
        for forbidden in ('isFocused', 'addWindow', 'isShowing', 'LaunchedEffect', 'delay(', 'comparisonState'):
            self.assertNotIn(forbidden, PANEL)
        self.assertEqual(1, PANEL.count('controller.foreground(true)'))
        for cleanup in ('controller.close()', 'adapter.close()', 'io.shutdownNow()'):
            self.assertIn(cleanup, PANEL)

    def test_panel_cannot_write_configuration_or_block_completion(self):
        for name in ('testConnection', 'saveAndActivate', 'saveAuthenticatedOfficial'):
            self.assertRegex(PANEL, rf'override fun {name}[^\n]+error\("Read-only panel"\)')
        self.assertIn('override fun onBusyChanged(busy: Boolean) = Unit', PANEL)
        self.assertIn('SharedInlineAppreciation(controller)', PANEL)

if __name__ == '__main__':
    unittest.main()
