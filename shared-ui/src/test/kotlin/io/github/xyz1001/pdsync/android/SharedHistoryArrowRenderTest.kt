@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryNode
import pdsync.presentation.HistoryPresentation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pixel regression of the actual shared history renderer, independent of history grouping. */
class SharedHistoryArrowRenderTest {
    @Test fun shaftNeverProtrudesBeyondHeadAtNarrowAndDesktopWidths() {
        val cases = listOf(
            Triple("right", listOf(node("a", 0, 0), node("b", 1, 0)), HistoryEdge("a", "b")),
            Triple("left", listOf(node("a", 0, 0), node("b", 1, 0)), HistoryEdge("b", "a", sameContent = true)),
            Triple("up", listOf(node("a", 0, 0), node("b", 0, 1)), HistoryEdge("b", "a")),
            Triple("down", listOf(node("a", 0, 0), node("b", 0, 1)), HistoryEdge("a", "b")),
            Triple("dashed", listOf(node("a", 0, 0), node("b", 0, 1)), HistoryEdge("b", "a", dashed = true)),
        )
        // High-density software rendering exposes the old round cap as several real pixels.
        val density = 3f
        for (width in listOf(320, 1165)) for ((name, nodes, edge) in cases) {
            var origin = Offset.Zero
            var layout: SharedHistoryCanvasLayout? = null
            val observer = object : SharedHistoryLayoutObserver {
                override fun canvas(positionInRoot: Offset, layoutValue: SharedHistoryCanvasLayout) {
                    origin = positionInRoot
                    layout = layoutValue
                }
            }
            val presentation = HistoryPresentation(nodes, listOf(edge), 0, 1,
                nodes.maxOf { it.row } + 1, nodes.count { it.column == 0 }, false)
            val scene = ImageComposeScene(width = (width * density).toInt(), height = 1500, density = Density(density)) {
                SharedConnectionTheme {
                    CompositionLocalProvider(LocalSharedHistoryLayoutObserver provides observer) {
                        Column(Modifier.fillMaxSize().background(Color.White)) {
                            SharedHistorySection(presentation, null, false, {}, {}, {})
                        }
                    }
                }
            }
            try {
                repeat(3) { scene.render(it * 16_000_000L).close() }
                val pixels = scene.render(48_000_000L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                        val file = Path.of("build/reports/shared-history", "arrow-tip-$name-$width.png")
                        Files.createDirectories(file.parent)
                        Files.write(file, data.bytes)
                        ImageIO.read(ByteArrayInputStream(data.bytes))
                    }
                }
                val arrow = assertNotNull(layout).arrows.single()
                val unit = (arrow.tip - arrow.start) / (arrow.tip - arrow.start).getDistance()
                val normal = Offset(-unit.y, unit.x)
                val tip = origin + arrow.tip
                val color = when {
                    edge.dashed -> 0x855300
                    name in listOf("left", "right") -> 0x006877
                    else -> 0x94A3B8
                }
                assertTrue(hasColor(pixels, tip - unit * (4f * density), color), "real head painted: $name/$width")
                // A round shaft drawn to tip used to paint this region outside the triangle.
                // Inspect pixel centers rather than truncating a fractional geometric tip.
                val outside = mutableListOf<Int>()
                for (x in tip.x.toInt() - 5..tip.x.toInt() + 5) {
                    for (y in tip.y.toInt() - 5..tip.y.toInt() + 5) {
                        val delta = Offset(x + 0.5f, y + 0.5f) - tip
                        val ahead = delta.x * unit.x + delta.y * unit.y
                        val sideways = abs(delta.x * normal.x + delta.y * normal.y)
                        if (ahead in 1.25f..2.75f && sideways <= 0.8f) outside += pixels.getRGB(x, y) and 0xffffff
                    }
                }
                assertTrue(outside.isNotEmpty(), "sampled beyond actual tip: $name/$width")
                assertTrue(outside.all { isNear(it, 0xffffff) }, "shaft must not pierce arrow tip: $name/$width; $outside")
            } finally { scene.close() }
        }
    }

    private fun node(id: String, column: Int, row: Int) =
        HistoryNode(id, null, "Save $id", "12:00", "Level 1", "", column, row)

    private fun hasColor(image: BufferedImage, point: Offset, color: Int) =
        (-1..1).any { dx -> (-1..1).any { dy ->
            isNear(image.getRGB(point.x.toInt() + dx, point.y.toInt() + dy) and 0xffffff, color)
        } }

    private fun isNear(actual: Int, expected: Int) = listOf(0, 8, 16).all { shift ->
        abs(((actual shr shift) and 0xff) - ((expected shr shift) and 0xff)) <= 18
    }
}
