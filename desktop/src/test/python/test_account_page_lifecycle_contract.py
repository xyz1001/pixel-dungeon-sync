"""Desktop account activation is page-scoped; native window changes have no callback."""
from pathlib import Path
import unittest

REPO = Path(__file__).resolve().parents[4]
MAIN = (REPO / "desktop/src/main/kotlin/pdsync/Main.kt").read_text()
ADAPTER = (REPO / "desktop/src/main/kotlin/pdsync/DesktopConnectionAdapter.kt").read_text()


def body(source, marker):
    start = source.index("{", source.index(marker))
    depth = 1
    for end in range(start + 1, len(source)):
        depth += (source[end] == "{") - (source[end] == "}")
        if depth == 0:
            return source[start + 1:end]
    raise AssertionError("unclosed body: " + marker)


class AccountPageLifecycleContract(unittest.TestCase):
    def test_account_host_has_no_native_window_observer(self):
        page = body(MAIN, "private fun DesktopSharedConnectionPage(")
        declaration = MAIN.split("private fun DesktopSharedConnectionPage(", 1)[1].split(") {", 1)[0]
        self.assertNotIn("window", declaration)
        for source in (page, ADAPTER):
            for token in (
                "observeDesktopConnectionWindow", "DesktopConnectionVisibility",
                "addWindow", "addComponentListener", "isFocused", "isShowing",
                "ICONIFIED", "windowGainedFocus", "windowLostFocus",
                "componentShown", "componentHidden",
            ):
                self.assertNotIn(token, source)

    def test_account_activation_is_once_per_controller_page_effect(self):
        page = body(MAIN, "private fun DesktopSharedConnectionPage(")
        effect = body(page, "DisposableEffect(controller)")
        entry = effect.split("onDispose {", 1)[0]
        self.assertEqual(1, page.count("controller.foreground(true)"))
        self.assertIn("controller.foreground(true)", entry)
        self.assertLess(entry.index("controller.attach("), entry.index("controller.foreground(true)"))
        self.assertIn("val controller = remember { SharedConnectionController(", page)
        self.assertIn("DesktopSharedConnectionPage(configFile,", body(MAIN, "Page.CONNECTION -> key(pageGeneration)"))

    def test_only_page_disposal_deactivates_and_releases_account_resources(self):
        page = body(MAIN, "private fun DesktopSharedConnectionPage(")
        disposal = body(page, "onDispose")
        self.assertEqual(1, page.count("controller.foreground(false)"))
        self.assertIn("controller.foreground(false)", disposal)
        for call in ("controller.detach()", "controller.close()", "adapter.close()", "io.shutdownNow()"):
            self.assertIn(call, disposal)
        self.assertLess(disposal.index("controller.foreground(false)"), disposal.index("controller.close()"))


if __name__ == "__main__":
    unittest.main()
