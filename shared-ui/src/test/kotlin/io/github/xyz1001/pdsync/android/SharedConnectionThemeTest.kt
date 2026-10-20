package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedConnectionThemeTest {
    @Test fun paletteMatchesReferenceSurfaceAndText() {
        val colors = sharedConnectionColors()
        assertEquals(Color(0xFF006877), colors.primary)
        assertEquals(Color(0xFFF1F5F9), colors.secondary)
        assertEquals(Color(0xFFF8FAFC), colors.background)
        assertEquals(Color(0xFF0F172A), colors.onSurface)
        assertEquals(Color.White, colors.surface)
        assertTrue(colors.isLight)
    }

    @Test fun shapesMatchReferenceButtonsCardsAndShell() {
        assertEquals(RoundedCornerShape(8.dp), sharedConnectionShapes.small)
        assertEquals(RoundedCornerShape(12.dp), sharedConnectionShapes.medium)
        assertEquals(RoundedCornerShape(24.dp), sharedConnectionShapes.large)
    }

    @Test fun publicScreenOwnsThemeAndAndroidHasNoCompetingDefaultTheme() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("shared-ui/src/main/kotlin")) }
        val shared = root.resolve("shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android")
        val screen = Files.readString(shared.resolve("SharedConnectionScreen.kt"))
        val entry = screen.substringAfter("fun SharedConnectionScreen(")
            .substringBefore("private fun SharedConnectionContent(")
        assertTrue(entry.contains("SharedConnectionTheme {"))
        assertTrue(entry.contains("SharedConnectionContent(controller, game, onSaveImage, imageSaveBusy, imageSaveMessage)"))
        val android = Files.readString(root.resolve(
            "android-app/src/main/kotlin/io/github/xyz1001/pdsync/android/AndroidConnectionComposition.kt"))
        assertTrue(android.contains("SharedConnectionScreen(session.controller,"))
        assertFalse(android.contains("import androidx.compose.material.MaterialTheme"))
        assertFalse(android.contains("MaterialTheme {"))
        val desktop = Files.readString(root.resolve("desktop/src/main/kotlin/pdsync/Main.kt"))
        assertTrue(desktop.contains("SharedConnectionScreen(controller,"))
    }
}
