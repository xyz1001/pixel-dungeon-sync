@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.toComposeEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real Compose dialog, pointer and Escape dispatch; no payment endpoints are used. */
class SharedAppreciationImageTest {
    private var frame = 0L

    @Test fun originalBitmapCentersFitsAndReopensWithoutStackingAtPhoneAndDesktopSizes() {
        for ((width, height) in listOf(320 to 757, 500 to 757, 1165 to 757, 757 to 320)) {
            // Non-square fixture catches accidental square cropping and stretching.
            val bitmap = ImageBitmap(360, 480).also {
                androidx.compose.ui.graphics.Canvas(it).drawRect(
                    androidx.compose.ui.geometry.Rect(0f, 0f, 360f, 480f),
                    androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color(0xff326650) })
            }
            var visible by mutableStateOf(true)
            val scene = ImageComposeScene(width, height, Density(1f)) {
                if (visible) Box(Modifier.fillMaxWidth()) {
                    SharedAppreciationImage(bitmap, Modifier.fillMaxWidth().height(110.dp))
                }
            }
            try {
                render(scene)
                assertEquals(width / 2f, node(scene, "appreciation-thumbnail").boundsInRoot.center.x)
                repeat(3) { cycle ->
                    click(scene, node(scene, "appreciation-thumbnail").boundsInRoot.center)
                    awaitViewer(scene, 1, "viewer-$width-$height-$cycle")
                    assertEquals(1, nodes(scene).count { tag(it) == "appreciation-viewer" },
                        "Viewer after click: width=$width height=$height cycle=$cycle tags=${nodes(scene).map { tag(it) }}")
                    val bounds = node(scene, "appreciation-enlarged-image").boundsInRoot
                    assertEquals(width / 2f, bounds.center.x, 1f)
                    assertEquals(height / 2f, bounds.center.y, 1f)
                    assertTrue(bounds.width >= width - 34 && bounds.height >= height - 34)
                    assertRenderedFit(width, height, "viewer-$width-$height-$cycle")
                    if (cycle == 1) escape(scene) else click(scene, Offset(2f, 2f))
                    awaitViewer(scene, 0, "closed-$width-$height-$cycle")
                    assertEquals(0, nodes(scene).count { tag(it) == "appreciation-viewer" })
                    assertEquals(1, nodes(scene).count { tag(it) == "appreciation-thumbnail" })
                }
                click(scene, node(scene, "appreciation-thumbnail").boundsInRoot.center)
                awaitViewer(scene, 1, "before-hide-$width-$height")
                visible = false
                awaitViewer(scene, 0, "hidden-$width-$height")
                assertEquals(0, nodes(scene).count { tag(it) == "appreciation-viewer" })
                visible = true
                awaitViewer(scene, 0, "returned-$width-$height")
                assertEquals(0, nodes(scene).count { tag(it) == "appreciation-viewer" }, "Returning never resurrects a stale viewer")
            } finally { scene.close() }
        }
    }

    private fun assertRenderedFit(width: Int, height: Int, name: String) {
        val pixels = javax.imageio.ImageIO.read(Path.of("build/reports/appreciation-viewer", "$name.png").toFile())
        val points = buildList {
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels.getRGB(x, y) == 0xff326650.toInt()) add(x to y)
            }
        }
        assertTrue(points.isNotEmpty())
        val left = points.minOf { it.first }; val right = points.maxOf { it.first }
        val top = points.minOf { it.second }; val bottom = points.maxOf { it.second }
        assertEquals(width / 2f, (left + right + 1) / 2f, 1f)
        assertEquals(height / 2f, (top + bottom + 1) / 2f, 1f)
        assertEquals(.75f, (right - left + 1f) / (bottom - top + 1f), .005f)
        assertTrue(left >= 15 && top >= 15 && right < width - 15 && bottom < height - 15)
        assertTrue(right - left > 110 && bottom - top > 110, "The viewer actually enlarges the rendered bitmap")
    }

    private fun tag(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.TestTag)
    private fun node(scene: ImageComposeScene, tag: String) = nodes(scene).single { tag(it) == tag }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val layers = backing.javaClass.getDeclaredField("layers").run { isAccessible = true; get(backing) } as List<*>
        val roots = listOf(root) + layers.map { layer ->
            requireNotNull(layer).javaClass.getDeclaredField("owner").run { isAccessible = true; get(layer) }
        }
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return roots.flatMap {
            val owner = it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner
            descend(owner.unmergedRootSemanticsNode)
        }
    }
    private fun escape(scene: ImageComposeScene) {
        val source = java.awt.Canvas()
        for (id in listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
            scene.sendKeyEvent(KeyEvent(source, id, 1L, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED).toComposeEvent())
        }
    }
    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }
    private fun nextFrame(): Long {
        frame += 16_666_667
        return frame
    }

    // Wait for the observable layer transition, never resend the input that caused it.
    private fun awaitViewer(scene: ImageComposeScene, expected: Int, name: String) {
        val deadline = System.nanoTime() + 2_000_000_000L
        var renderedFrames = 0
        while (true) {
            scene.render(nextFrame()).close()
            renderedFrames++
            val tags = nodes(scene).map { tag(it) }
            val count = tags.count { it == "appreciation-viewer" }
            if (count > 1 || System.nanoTime() >= deadline) {
                saveFrame(scene, "failure-$name")
                assertEquals(expected, count,
                    "Viewer transition $name: frames=$renderedFrames tags=$tags")
                error("Viewer transition timed out: $name frames=$renderedFrames tags=$tags")
            }
            if (count == expected) {
                saveFrame(scene, name)
                assertEquals(expected, nodes(scene).count { tag(it) == "appreciation-viewer" },
                    "Viewer after capture $name: tags=${nodes(scene).map { tag(it) }}")
                return
            }
            Thread.sleep(5)
        }
    }

    private fun render(scene: ImageComposeScene) {
        repeat(3) { scene.render(nextFrame()).close() }
    }

    private fun saveFrame(scene: ImageComposeScene, name: String) {
        scene.render(nextFrame()).use { image ->
            val directory = Path.of("build/reports/appreciation-viewer")
            Files.createDirectories(directory)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(directory.resolve("$name.png"), it.bytes) }
        }
    }
}
