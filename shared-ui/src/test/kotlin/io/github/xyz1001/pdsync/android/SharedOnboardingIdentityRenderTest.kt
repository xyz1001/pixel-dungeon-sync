@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.Density
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import pdsync.OnboardingKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

/** Compare actual rendered pixels, including the icon and any space left by the identity row. */
class SharedOnboardingIdentityRenderTest {
    private fun identity() = SharedGameIdentity("Selected game identity", "4.0.2", ImageBitmap(24, 24).also {
        Canvas(it).drawRect(Rect(0f, 0f, 24f, 24f), Paint().apply { color = Color.Magenta })
    })

    @Test fun shellOmitsTheWholeIdentityAfterFirstStep() {
        val game = identity()
        for (width in listOf(1165, 500, 320)) for (step in 2..4) {
            fun pixels(selected: SharedGameIdentity?) = render(width, "shell-$step-$width-${selected != null}") {
                SharedConnectionTheme {
                    SharedAppShell("Setup", eyebrow = "$step / 4", game = selected, onboardingStep = step) {
                        Text("Page content")
                    }
                }
            }
            assertContentEquals(pixels(null), pixels(game), "step $step must omit icon, name, version and spacing at $width")
        }
    }

    @Test fun shellKeepsIdentityWithoutASetupStepAndOnFirstStep() {
        val game = identity()
        for (step in listOf(null, 1)) {
            fun pixels(selected: SharedGameIdentity?) = render(500, "shell-preserve-$step-${selected != null}") {
                SharedConnectionTheme {
                    SharedAppShell("Page", game = selected, onboardingStep = step) { Text("Page content") }
                }
            }
            assertFalse(pixels(null).contentEquals(pixels(game)), "non-setup pages and step 1 retain identity")
        }
    }

    @Test fun actualSavePathAndComparisonScreensOmitIdentity() {
        val game = identity()
        val save = SharedSavePathState("/game/files", false, null)
        val comparison = SharedComparisonState(OnboardingKind.BOTH_EMPTY,
            cloudVerified = true, pageActive = true, configured = true)
        for (width in listOf(1165, 500, 320)) {
            assertContentEquals(
                render(width, "path-$width-empty") { SharedSavePathScreen(save, saveActions) },
                render(width, "path-$width-identity") { SharedSavePathScreen(save, saveActions, game) },
                "step 2 must not show the selected game at $width",
            )
            assertContentEquals(
                render(width, "comparison-$width-empty") { SharedComparisonScreen(comparison, comparisonActions) },
                render(width, "comparison-$width-identity") {
                    SharedComparisonScreen(comparison.copy(game = game), comparisonActions)
                },
                "step 4 must not show the selected game at $width",
            )
        }
    }

    @Test fun actualConnectionSetupOmitsIdentityWhileSettingsKeepsIt() {
        val game = identity()
        for (origin in ConnectionEntryModel.Origin.values()) {
            val controller = SharedConnectionController(origin, null, "slot", connectionPorts,
                Executor { error("Rendering must not schedule I/O") }, Executor { it.run() })
            try {
                for (width in listOf(500, 320)) {
                    val empty = render(width, "connection-$origin-$width-empty") { SharedConnectionScreen(controller) }
                    val selected = render(width, "connection-$origin-$width-identity") {
                        SharedConnectionScreen(controller, game)
                    }
                    if (origin == ConnectionEntryModel.Origin.SETUP) {
                        assertContentEquals(empty, selected, "step 3 must omit identity at $width")
                    } else {
                        assertFalse(empty.contentEquals(selected), "connection settings keeps identity at $width")
                    }
                }
            } finally { controller.close() }
        }
    }

    @Test fun actualFirstStepAndHomeKeepNameVersionAndIcon() {
        val game = identity()
        val variants = linkedMapOf(
            "name" to game.copy(name = "Another selected game"),
            "version" to game.copy(version = "9.9.9"),
            "icon" to game.copy(icon = null),
        )
        for (width in listOf(500, 320)) {
            val first = render(width, "first-$width-identity") {
                SharedGameSelectionScreen(SharedGameSelectionState(game = game), selectionActions)
            }
            val home = render(width, "home-$width-identity") {
                SharedHomeScreen(SharedHomeState(game, ""), homeActions)
            }
            for ((part, variant) in variants) {
                assertFalse(first.contentEquals(render(width, "first-$width-$part") {
                    SharedGameSelectionScreen(SharedGameSelectionState(game = variant), selectionActions)
                }), "step 1 must render the game $part at $width")
                assertFalse(home.contentEquals(render(width, "home-$width-$part") {
                    SharedHomeScreen(SharedHomeState(variant, ""), homeActions)
                }), "home must render the game $part at $width")
            }
        }
    }

    private fun render(width: Int, name: String, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) { content() }
        return try {
            scene.render(0).close()
            scene.render(16_000_000).close()
            scene.render(32_000_000).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    val file = Path.of("build/reports/shared-onboarding-identity", "$name.png")
                    Files.createDirectories(file.parent)
                    Files.write(file, data.bytes)
                    ImageIO.read(ByteArrayInputStream(data.bytes)).getRGB(0, 0, width, 757, null, 0, width)
                }
            }
        } finally { scene.close() }
    }

    private val saveActions = object : SharedSavePathActions {
        override fun editPath(path: String) = Unit
        override fun chooseDirectory() = Unit
        override fun useDefault() = Unit
        override fun retry() = Unit
        override fun initializeGame() = Unit
        override fun back() = Unit
        override fun next() = Unit
    }
    private val comparisonActions = object : SharedComparisonActions {
        override fun back() = Unit
        override fun refresh() = Unit
        override fun selectProgress(choice: SharedProgressChoice) = Unit
        override fun selectRemote(snapshotId: String) = Unit
        override fun finish(mode: SharedComparisonCompletion) = Unit
        override fun handleConflict() = Unit
        override fun upload() = Unit
        override fun openRecovery() = Unit
    }
    private val selectionActions = object : SharedGameSelectionActions {
        override fun perform(control: SharedGameSelectionControl) = Unit
        override fun chooseCandidate(id: String) = Unit
        override fun next() = Unit
    }
    private val homeActions = object : SharedHomeActions {
        override fun settings() = Unit
        override fun upload() = Unit
        override fun restore() = Unit
        override fun launch() = Unit
        override fun selectSnapshot(snapshotId: String) = Unit
        override fun rename(snapshotId: String?) = Unit
        override fun changeHistoryPage(page: Int) = Unit
    }
    private val connectionPorts = object : SharedConnectionPorts {
        override fun testConnection(config: SharedConnectionConfig) = error("No network in a render test")
        override fun saveAndActivate(config: SharedConnectionConfig) = error("No save in a render test")
        override fun cacheDirectory(): Path? = null
        override fun elapsedRealtimeMs() = 0L
        override fun bootCount() = 1
        override fun copyUsername(username: String) = Unit
        override fun decodeImage(bytes: ByteArray) = null
    }
}
