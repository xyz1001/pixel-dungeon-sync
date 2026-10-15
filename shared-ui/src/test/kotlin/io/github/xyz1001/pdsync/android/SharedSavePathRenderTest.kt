@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.runtime.mutableStateOf
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
import pdsync.SaveSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real production page rendering and pointer dispatch, rather than a duplicate test layout. */
class SharedSavePathRenderTest {
    private class Actions : SharedSavePathActions {
        var next = 0
        var back = 0
        var choose = 0
        override fun editPath(path: String) = Unit
        override fun chooseDirectory() { choose++ }
        override fun useDefault() = Unit
        override fun retry() = Unit
        override fun initializeGame() = Unit
        override fun back() { back++ }
        override fun next() { next++ }
    }

    private val longPath = "/home/player/" + "long-selected-game-save-directory/".repeat(8) + "files"
    private val summary = SaveSummary((1..18).associate { "game$it" to "2026-10-14T12:34:00Z" }, 17, 20)
    private val game = SharedGameIdentity("很长的已选游戏名称用于检查真实文字换行和窄屏导航", "3.3.8")

    @Test fun pathStepOmitsAlternativeDirectoryButtonsButKeepsPathPreviewAndNavigation() {
        val source = Files.readString(Path.of("../shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedSavePathScreen.kt"))
        assertFalse(source.contains("选择其他存档路径"))
        assertFalse(source.contains("使用默认路径"))
        assertFalse(source.contains("interactions.perform(SharedSavePathControl.CHOOSE_DIRECTORY)"))
        assertFalse(source.contains("interactions.perform(SharedSavePathControl.USE_DEFAULT)"))
        assertTrue(source.contains("SharedTextField(\"存档路径\""))
        assertTrue(source.contains("savePathSummary(state.summary)"))
        assertTrue(source.contains("SharedSavePathControl.BACK"))
        assertTrue(source.contains("SharedSavePathControl.NEXT"))
    }

    @Test fun actualSavePathPageWrapsLongPathsKeepsSlotTimesAndFooterAtAllReferenceWidths() {
        for (width in listOf(1165, 500, 320)) for (editable in listOf(true, false)) {
            val actions = Actions()
            val state = SharedSavePathState(longPath, editable, summary,
                readStatus = SharedSavePathReadStatus.READY, canContinue = true,
                canChooseDirectory = editable, canUseDefault = editable)
            val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                SharedSavePathScreen(state, actions, game)
            }
            try {
                val name = "path-$width-${if (editable) "desktop" else "android"}"
                val before = render(scene, name)
                assertCardWithinViewport(before)
                val buttonBefore = primaryBounds(before)
                assertTrue(buttonBefore.height >= 44f)
                assertTrue(buttonBefore.right <= width - if (width <= 560) 30 else 50)
                assertTrue(buttonBefore.bottom <= 742f)
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(width / 2f, 340f), scrollDelta = Offset(0f, 15f))
                val after = render(scene, "$name-scrolled")
                assertEquals(buttonBefore, primaryBounds(after), "actual footer must not scroll with long path/slots")
                assertTrue((100 until 450).any { y -> (30 until width - 30).any { x -> before.getRGB(x, y) != after.getRGB(x, y) } },
                    "long body must really scroll at $width")
                click(scene, buttonBefore.center)
                assertEquals(1, actions.next, "primary remains reachable after scrolling at $width")
                val left = if (width <= 560) 31f else (width - minOf(width - 36, 820)) / 2f + 33f
                click(scene, Offset(left + 34f, buttonBefore.center.y))
                assertEquals(1, actions.back, "Back remains in footer at $width")
            } finally { scene.close() }
        }
    }

    @Test fun queuedReadAndHardBusyUseLiveStateGuardsInActualRenderedPage() {
        val actions = Actions()
        val state = mutableStateOf(SharedSavePathState(longPath, false, summary,
            readStatus = SharedSavePathReadStatus.READY, canContinue = true, canRequestRead = true))
        val scene = ImageComposeScene(width = 320, height = 420, density = Density(1f)) {
            SharedSavePathScreen(state.value, actions, game)
        }
        try {
            val next = primaryBounds(render(scene, "path-short-ready"))
            assertTrue(next.bottom <= 405)
            state.value = state.value.copy(summary = null, readStatus = SharedSavePathReadStatus.READING,
                canContinue = false, continueAfterRead = true)
            render(scene, "path-short-queued")
            click(scene, next.center)
            click(scene, Offset(65f, next.center.y))
            assertEquals(0, actions.next)
            assertEquals(1, actions.back, "ordinary queued reads keep Back")
            state.value = state.value.copy(busy = true)
            render(scene, "path-short-busy")
            click(scene, Offset(65f, next.center.y))
            assertEquals(1, actions.back, "hard operations block navigation")
            state.value = state.value.copy(busy = false, continueAfterRead = false,
                readStatus = SharedSavePathReadStatus.FAILED, notice = "读取失败")
            val retry = primaryBounds(render(scene, "path-short-failed"))
            click(scene, retry.center)
            assertEquals(1, actions.next, "Retry uses the same queued primary action")
            assertEquals(0, actions.choose)
        } finally { scene.close() }
    }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }

    private fun assertCardWithinViewport(image: BufferedImage) {
        val margin = if (image.width <= 560) 10 else (image.width - minOf(820, image.width - 36)) / 2
        for (y in 0 until image.height) {
            for (x in 0 until margin) assertEquals(0xeff3ef, image.getRGB(x, y) and 0xffffff, "left overflow")
            for (x in image.width - margin until image.width) assertEquals(0xeff3ef, image.getRGB(x, y) and 0xffffff, "right overflow")
        }
    }

    /** Flood the solid primary fill; label holes and antialiasing do not affect its measured bounds. */
    private fun primaryBounds(image: BufferedImage): Rect {
        fun green(x: Int, y: Int) = (image.getRGB(x, y) and 0xffffff) == 0x326650
        var start = -1
        for (y in image.height - 1 downTo 0) {
            val xs = (0 until image.width).filter { green(it, y) }
            if (xs.size > 35) { start = y * image.width + xs.last(); break }
        }
        assertFalse(start == -1, "actual primary button must render")
        val visited = HashSet<Int>()
        val pending = java.util.ArrayDeque<Int>()
        pending.add(start)
        var left = image.width; var top = image.height; var right = 0; var bottom = 0
        while (pending.isNotEmpty()) {
            val key = pending.removeFirst()
            if (!visited.add(key)) continue
            val x = key % image.width; val y = key / image.width
            if (!green(x, y)) continue
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
            if (x > 0) pending.add(key - 1)
            if (x + 1 < image.width) pending.add(key + 1)
            if (y > 0) pending.add(key - image.width)
            if (y + 1 < image.height) pending.add(key + image.width)
        }
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun render(scene: ImageComposeScene, name: String): BufferedImage {
        scene.render(0).close()
        scene.render(16_000_000).close()
        return scene.render(32_000_000).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                val file = Path.of("build/reports/shared-save-path", "$name.png")
                Files.createDirectories(file.parent)
                Files.write(file, data.bytes)
                ImageIO.read(ByteArrayInputStream(data.bytes))
            }
        }
    }
}
