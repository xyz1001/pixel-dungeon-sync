@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedGameSelectionRenderTest {
    private class Observation : SharedGameSelectionLayoutObserver {
        val bounds = mutableMapOf<String, Rect>()
        val lines = mutableMapOf<String, Int>()
        val overflow = mutableSetOf<String>()
        override fun element(name: String, bounds: Rect) { this.bounds[name] = bounds }
        override fun text(name: String, lineCount: Int, overflow: Boolean) {
            lines[name] = lineCount
            if (overflow) this.overflow += name else this.overflow -= name
        }
    }
    private class Host(initial: SharedGameSelectionState) : SharedGameSelectionActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        override fun perform(control: SharedGameSelectionControl) { calls += control.name }
        override fun chooseCandidate(id: String) {
            val candidate = state.candidates.single { it.id == id }
            calls += id
            state = state.copy(game = candidate.game, selectionId = id, location = candidate.location, canContinue = true)
        }
        override fun next() { calls += "next"; state = state.copy(busy = true) }
    }
    private fun ready() = SharedGameSelectionState(
        game = SharedGameIdentity("Shattered Pixel Dungeon", "4.0.2"), selectionId = "/games/selected.jar",
        location = "/games/selected.jar", pageActive = true, canContinue = true,
        rows = listOf(SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_FILE, "重新选择")),
    )
    private fun scene(width: Int, height: Int, host: Host, observed: Observation) =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CompositionLocalProvider(LocalSharedGameSelectionLayoutObserver provides observed) {
                SharedGameSelectionScreen(host.state, host)
            }
        }

    @Test fun realSharedCardAndSingleNextFitDesktopAndNarrowScreens() {
        for (width in listOf(1180, 560, 500, 320)) {
            val host = Host(ready())
            val observed = Observation()
            val scene = scene(width, 757, host, observed)
            try {
                val image = render(scene, "game-selection-$width")
                assertCardInViewport(image)
                val card = observed.bounds.getValue("identity")
                val next = observed.bounds.getValue("next")
                assertTrue(card.left >= 30f && card.right <= width - 30f)
                assertTrue(next.height >= 46f && next.bottom < 742f)
                assertTrue(card.bottom < next.top)
                assertTrue(observed.overflow.isEmpty())
                assertTrue(host.state.selectionStatus.isEmpty(), "selected desktop game needs no found-status label")
                assertEquals("Shattered Pixel Dungeon", host.state.game?.name)
                assertEquals("4.0.2", host.state.game?.version)
                click(scene, observed.bounds.getValue("action:CHOOSE_FILE").center)
                render(scene, "game-selection-cancel-$width")
                assertEquals(listOf("CHOOSE_FILE"), host.calls)
                assertEquals("/games/selected.jar", host.state.selectionId)
                click(scene, next.center); click(scene, next.center)
                render(scene, "game-selection-next-$width")
                assertEquals(listOf("CHOOSE_FILE", "next"), host.calls)
            } finally { scene.close() }
        }
    }

    @Test fun emptyAndUnverifiedSelectionsNeverAdvanceAndCandidateSelectionIsReal() {
        val observed = Observation()
        val host = Host(ready().copy(game = null, selectionId = null, canContinue = false, candidates = listOf(
            SharedGameCandidate("one", SharedGameIdentity("Game one", "1"), "/games/one.jar"),
            SharedGameCandidate("two", SharedGameIdentity("Game two", "2"), "/games/two.jar"),
        )))
        val scene = scene(500, 757, host, observed)
        try {
            render(scene, "game-selection-candidates")
            click(scene, observed.bounds.getValue("next").center)
            assertTrue(host.calls.isEmpty())
            click(scene, observed.bounds.getValue("candidate:two").center)
            render(scene, "game-selection-candidate-chosen")
            assertEquals("two", host.state.selectionId)
            assertEquals("Game two", host.state.game?.name)
            click(scene, observed.bounds.getValue("next").center)
            render(scene, "game-selection-candidate-next")
            assertEquals(listOf("two", "next"), host.calls)
        } finally { scene.close() }
    }

    @Test fun longIdentityPathAndManyAndroidActionsScrollWithoutMovingFooter() {
        val observed = Observation()
        val host = Host(ready().copy(
            game = SharedGameIdentity("一个很长的已选择游戏名称，用于确认窄屏可以换行并保持操作可达", "2026.10.15"),
            location = "/storage/emulated/0/很长的真实目录/" + "selected-game-path-".repeat(12),
            rows = listOf(SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_INSTALLED, "从已安装应用中选择")),
        ))
        val scene = scene(320, 600, host, observed)
        try {
            render(scene, "game-selection-long-320")
            assertTrue(observed.lines.getValue("game-name") > 1)
            assertFalse("details" in observed.bounds)
            assertFalse("location" in observed.bounds)
            assertTrue(observed.overflow.isEmpty())
            val footer = observed.bounds.getValue("next")
            host.state = host.state.copy(rows = SharedGameSelectionControl.entries.filter {
                it != SharedGameSelectionControl.COPY_DIAGNOSTICS
            }.map { SharedGameSelectionRow(it, "实际平台操作：${it.name}") })
            render(scene, "game-selection-many-actions-320")
            val first = observed.bounds.getValue("identity")
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(160f, 320f), scrollDelta = Offset(0f, 12f))
            assertCardInViewport(render(scene, "game-selection-scrolled-320"))
            assertEquals(footer, observed.bounds.getValue("next"))
            assertTrue(observed.bounds.getValue("identity").top < first.top)
            assertFalse(footer.bottom > 585f)
        } finally { scene.close() }
    }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }
    private fun render(scene: ImageComposeScene, name: String): BufferedImage {
        scene.render(0).close(); scene.render(16_000_000).close()
        return scene.render(32_000_000).use { image ->
            val file = Path.of("build/reports/shared-game-selection", "$name.png")
            Files.createDirectories(file.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(file, data.bytes)
                ImageIO.read(ByteArrayInputStream(data.bytes))
            }
        }
    }
    private fun assertCardInViewport(image: BufferedImage) {
        val margin = if (image.width <= 560) 10 else 18
        for (x in 0 until margin) for (y in 0 until image.height) {
            assertEquals(0xeff3ef, image.getRGB(x, y) and 0xffffff)
            assertEquals(0xeff3ef, image.getRGB(image.width - x - 1, y) and 0xffffff)
        }
    }
}
