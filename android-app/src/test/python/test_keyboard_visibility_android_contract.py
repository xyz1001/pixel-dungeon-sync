"""Android host ownership seams; actual focus/scroll runs in SharedInputVisibilityRenderTest.

No Android keyboard, device, window animation or OEM behavior is claimed by these checks.
"""
from pathlib import Path
import unittest
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
ANDROID = REPO / "android-app/src/main"
JAVA = ANDROID / "java/io/github/xyz1001/pdsync/android"
KOTLIN = ANDROID / "kotlin/io/github/xyz1001/pdsync/android"
SHARED = REPO / "shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android"


class KeyboardVisibilityAndroidContract(unittest.TestCase):
    def test_resize_and_edge_to_edge_have_one_explicit_inset_owner(self):
        manifest = (REPO / "android-app/AndroidManifest.xml").read_text()
        main = (JAVA / "MainActivity.java").read_text()
        self.assertIn('android:windowSoftInputMode="adjustResize"', manifest)
        self.assertIn("WindowCompat.setDecorFitsSystemWindows(getWindow(), false)", main)
        self.assertNotIn("withDiagnosticToolbar", main)
        self.assertNotIn('label("存档同步", 14, INK)', main)

    def test_android_root_owns_remaining_safe_area_and_measures_after_ime(self):
        viewport = (KOTLIN / "AndroidAppViewport.kt").read_text()
        self.assertNotIn("consumeWindowInsets", viewport)
        self.assertIn(".safeDrawingPadding().imePadding()", viewport)
        self.assertLess(viewport.index(".imePadding()"), viewport.index(".onSizeChanged {"))
        self.assertIn("LocalSharedInputViewport provides viewport", viewport)
        for name in ("AndroidConnectionComposition.kt", "AndroidOnboardingComposition.kt"):
            adapter = (KOTLIN / name).read_text()
            self.assertIn("AndroidAppViewport {", adapter)
            self.assertNotIn("imePadding", adapter)
            self.assertNotIn("safeDrawingPadding", adapter)
        connection = (KOTLIN / "AndroidConnectionComposition.kt").read_text()
        self.assertIn("SharedConnectionScreen(session.controller, game", connection)

    def test_relocation_is_opt_in_and_retriggers_on_effective_viewport(self):
        visibility = (SHARED / "SharedInputVisibility.kt").read_text()
        self.assertIn("staticCompositionLocalOf<IntSize?> { null }", visibility)
        self.assertIn("LocalSharedInputViewport.current ?: return this", visibility)
        self.assertIn("LaunchedEffect(focused, viewport)", visibility)
        self.assertLess(visibility.index("withFrameNanos { }"), visibility.index("requester.bringIntoView()"))
        shell = (SHARED / "SharedAppShell.kt").read_text()
        self.assertIn("keepFocusedFieldVisible(focused)", shell)
        self.assertIn("LocalSharedInputViewport.current != null", shell)
        # No Android API or platform insets enter the physically shared screen.
        for name in ("SharedInputVisibility.kt", "SharedAppShell.kt", "SharedConnectionScreen.kt"):
            shared = (SHARED / name).read_text()
            self.assertNotIn("import android.", shared)
            self.assertNotIn("imePadding", shared)


if __name__ == "__main__":
    unittest.main()
