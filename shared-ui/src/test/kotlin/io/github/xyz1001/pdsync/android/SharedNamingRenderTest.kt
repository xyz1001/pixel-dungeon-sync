@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedNamingRenderTest {
    private var frame = 0L

    @Test fun nameDraftAndFooterFitDesktopNarrowAndKeyboardSizedViewports() {
        for ((width, height) in listOf(1180 to 757, 500 to 757, 320 to 757, 320 to 320)) {
            val state = SharedNamingState(7, "已有存档名称", notice = "保存在此设备，不改写已发布的历史。")
            val scene = ImageComposeScene(width, height, density = Density(1f)) {
                SharedConnectionTheme {
                    BoxWithConstraints(Modifier.fillMaxSize().background(SharedUiColors.background).padding(16.dp),
                        contentAlignment = Alignment.Center) {
                        SharedNamingDialogBody(state, {}, {}, {}, Modifier.widthIn(max = 440.dp)
                            .fillMaxWidth().heightIn(max = maxHeight)) {
                            // Actual identity maintenance can be long. It must not bury Cancel/Save.
                            if (height == 320) repeat(12) { Text("单局身份待确认，请检查真实槽位 $it") }
                        }
                    }
                }
            }
            try {
                val image = render(scene, "naming-$width-$height")
                val buttons = buttonRects(image, 0xf1f5f9) + buttonRects(image, 0x006877)
                assertEquals(2, buttons.size)
                buttons.forEach {
                    assertTrue(it.width >= 88 && it.height >= 40)
                    assertTrue(it.left >= 40 && it.right <= width - 40)
                    assertTrue(it.top >= 16 && it.bottom <= height - 16,
                        "dialog actions remain reachable with a reduced keyboard viewport: $it")
                }
            } finally { scene.close() }
        }
    }

    @Test fun clickingCancelDoesNotWriteAndSaveUsesTheCurrentSharedDraft() {
        for (save in listOf(false, true)) {
            var state by mutableStateOf<SharedNamingState?>(SharedNamingState(9, "原名称"))
            var persisted = "原名称"
            var saves = 0
            var canceled = 0
            val scene = ImageComposeScene(500, 757, density = Density(1f)) {
                SharedConnectionTheme {
                    Box(Modifier.fillMaxSize().background(SharedUiColors.background).padding(16.dp),
                        contentAlignment = Alignment.Center) {
                        state?.let { current ->
                            SharedNamingDialogBody(current, { state = current.copy(name = it) },
                                { persisted = it; saves++; state = null }, { canceled++; state = null },
                                Modifier.widthIn(max = 440.dp).fillMaxWidth())
                        }
                    }
                }
            }
            try {
                render(scene, "naming-open-$save")
                state = state!!.copy(name = "尚未保存的名称")
                val image = render(scene, "naming-draft-$save")
                val target = buttonRects(image, if (save) 0x006877 else 0xf1f5f9).single().center
                click(scene, target)
                render(scene, "naming-closed-$save")
                assertEquals(if (save) "尚未保存的名称" else "原名称", persisted)
                assertEquals(if (save) 1 else 0, saves)
                assertEquals(if (save) 0 else 1, canceled)
                assertEquals(null, state)
            } finally { scene.close() }
        }
    }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }

    private fun render(scene: ImageComposeScene, name: String): BufferedImage {
        repeat(3) { scene.render(++frame * 16_000_000).close() }
        return scene.render(++frame * 16_000_000).use { image ->
            val path = Path.of("build/reports/shared-home-dialogs", "$name.png")
            Files.createDirectories(path.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(path, data.bytes)
                ImageIO.read(ByteArrayInputStream(data.bytes))
            }
        }
    }

    private fun buttonRects(image: BufferedImage, rgb: Int): List<Rect> {
        val seen = BooleanArray(image.width * image.height)
        val queue = IntArray(seen.size)
        val result = mutableListOf<Rect>()
        for (origin in seen.indices) {
            if (seen[origin] || (image.getRGB(origin % image.width, origin / image.width) and 0xffffff) != rgb) continue
            var head = 0; var tail = 1
            queue[0] = origin; seen[origin] = true
            var left = image.width; var top = image.height; var right = 0; var bottom = 0
            while (head < tail) {
                val current = queue[head++]
                val x = current % image.width; val y = current / image.width
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
                for (next in intArrayOf(if (x > 0) current - 1 else -1,
                    if (x + 1 < image.width) current + 1 else -1,
                    if (y > 0) current - image.width else -1,
                    if (y + 1 < image.height) current + image.width else -1)) {
                    if (next >= 0 && !seen[next] && (image.getRGB(next % image.width, next / image.width) and 0xffffff) == rgb) {
                        seen[next] = true; queue[tail++] = next
                    }
                }
            }
            if (tail > 700 && bottom - top >= 40 && tail > (right - left) * (bottom - top) / 2)
                result += Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        }
        return result
    }
}
