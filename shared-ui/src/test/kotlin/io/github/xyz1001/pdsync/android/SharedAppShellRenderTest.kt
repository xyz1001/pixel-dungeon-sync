@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Offscreen production Compose layout, with actual font measurement and pointer dispatch. */
class SharedAppShellRenderTest {
    @Test fun cardFormAndFooterMeasureAtDesktopBreakpointAndNarrowWidths() {
        for (width in listOf(1165, 561, 560, 500, 320)) {
            var form: Rect? = null
            var field: Rect? = null
            var back: Rect? = null
            var next: Rect? = null
            val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                SharedConnectionTheme {
                    SharedAppShell(title = "连接云端", eyebrow = "3 / 4",
                        game = SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"),
                        footer = {
                            SharedActionButton("上一步", {}, style = SharedButtonStyle.TEXT,
                                modifier = Modifier.onGloballyPositioned { back = it.boundsInRoot() })
                            Spacer(Modifier.weight(1f))
                            SharedActionButton("下一步", {},
                                modifier = Modifier.onGloballyPositioned { next = it.boundsInRoot() })
                        }) {
                        SharedForm(Modifier.onGloballyPositioned { form = it.boundsInRoot() }) {
                            SharedTextField("WebDAV 地址", "", {},
                                modifier = Modifier.onGloballyPositioned { field = it.boundsInRoot() })
                        }
                    }
                }
            }
            try {
                val pixels = render(scene, "shell-$width")
                val narrow = width <= 560
                val outer = if (narrow) 10 else 18
                val inner = if (narrow) 20 else 32
                val cardWidth = minOf(820, width - outer * 2)
                val measuredCard = cardPixelBounds(pixels)
                assertTrue(measuredCard.width <= 820f, "card maximum at $width")
                assertEquals(cardWidth.toFloat(), measuredCard.width, 1f, "actual card pixels at $width")
                val expectedLeft = (width - cardWidth) / 2f + 1 + inner
                val expectedFormWidth = minOf(510, cardWidth - 2 - inner * 2).toFloat()
                val measuredForm = assertNotNull(form)
                val measuredField = assertNotNull(field)
                val measuredBack = assertNotNull(back)
                val measuredNext = assertNotNull(next)
                assertEquals(expectedLeft, measuredForm.left, .6f, "form left at $width")
                assertEquals(expectedFormWidth, measuredForm.width, .6f, "form max-width at $width")
                assertEquals(measuredForm.left, measuredField.left, .1f)
                assertEquals(measuredForm.width, measuredField.width, .1f)
                assertEquals(expectedLeft, measuredBack.left, .6f, "Back left at $width")
                assertEquals(width - expectedLeft, measuredNext.right, .6f, "primary right at $width")
                assertTrue(measuredBack.height >= 46, "Back target at $width")
                assertTrue(measuredNext.height >= 46, "primary target at $width")
                val expectedFooterBottom = (if (narrow) 15 else 30) + 570 - 1 - (if (narrow) 16 else 20)
                assertEquals(expectedFooterBottom.toFloat(), measuredNext.bottom, .6f, "570px minimum card at $width")
                assertTrue(measuredField.bottom < measuredNext.top)
                assertTrue(measuredBack.left >= 0 && measuredNext.right <= width)
                assertTrue(measuredField.left >= measuredCard.left && measuredField.right <= measuredCard.right)
            } finally { scene.close() }
        }
    }

    @Test fun overflowingContentScrollsWithoutMovingFooterAtNarrowAndShortHeight() {
        var next: Rect? = null
        var first: Rect? = null
        val scene = ImageComposeScene(width = 320, height = 420, density = Density(1f)) {
            SharedConnectionTheme {
                SharedAppShell(title = "连接云端", eyebrow = "3 / 4",
                    game = SharedGameIdentity("A long selected game title that wraps instead of clipping", "3.3.8"),
                    footer = {
                        SharedActionButton("上一步", {}, style = SharedButtonStyle.TEXT)
                        Spacer(Modifier.weight(1f))
                        SharedActionButton("下一步", {},
                            modifier = Modifier.onGloballyPositioned { next = it.boundsInRoot() })
                    }) {
                    SharedForm {
                        Text("first", modifier = Modifier.onGloballyPositioned { first = it.boundsInRoot() })
                        repeat(15) { SharedTextField("字段 $it", "", {}) }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
        try {
            render(scene, "shell-short-before")
            val footerBefore = assertNotNull(next)
            val firstBefore = assertNotNull(first)
            assertTrue(footerBefore.bottom <= 404)
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(160f, 250f), scrollDelta = Offset(0f, 10f))
            render(scene, "shell-short-after")
            assertEquals(footerBefore, next, "footer must not scroll with the form")
            assertTrue(assertNotNull(first).top < firstBefore.top, "content should actually scroll")
        } finally { scene.close() }
    }

    @Test fun realConnectionScreenRendersSavedCustomFormAndTestDoesNotNavigate() {
        var probes = 0
        var commits = 0
        val controller = SharedConnectionController(
            ConnectionEntryModel.Origin.SETTINGS,
            SharedConnectionConfig("https://dav.example.test/", "layout-user", "", "slot", false),
            "slot",
            object : SharedConnectionPorts {
                override fun testConnection(config: SharedConnectionConfig): Boolean { probes++; return true }
                override fun saveAndActivate(config: SharedConnectionConfig) { commits++ }
                override fun cacheDirectory(): Path? = null
                override fun elapsedRealtimeMs() = 0L
                override fun bootCount() = 1
                override fun copyUsername(username: String) {}
                override fun decodeImage(bytes: ByteArray) = null
            }, Executor { it.run() }, Executor { it.run() },
        )
        controller.attach(object : SharedConnectionHost {
            override fun onBack(destination: ConnectionEntryModel.Back) {}
            override fun onCommitted(config: SharedConnectionConfig) {}
            override fun onBusyChanged(busy: Boolean) {}
        })
        controller.foreground(true)
        for (width in listOf(1165, 500, 320)) {
            val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                SharedConnectionScreen(controller, SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"))
            }
            try { render(scene, "connection-$width") } finally { scene.close() }
        }
        assertFalse(controller.state.canContinue)
        controller.testConnection()
        assertEquals(1, probes)
        assertEquals(0, commits)
        assertTrue(controller.state.canContinue)
        controller.close()
    }

    @Test fun chooserOfficialFormAndAppreciationShareTheNarrowShell() {
        // Work is deliberately queued: rendering must not authenticate or contact an external service.
        val queued = mutableListOf<Runnable>()
        val controller = SharedConnectionController(
            ConnectionEntryModel.Origin.SETUP, null, "slot",
            object : SharedConnectionPorts {
                override fun testConnection(config: SharedConnectionConfig) = error("No I/O in a layout test")
                override fun saveAndActivate(config: SharedConnectionConfig) = error("No save in a layout test")
                override fun cacheDirectory(): Path? = null
                override fun elapsedRealtimeMs() = 0L
                override fun bootCount() = 1
                override fun copyUsername(username: String) {}
                override fun decodeImage(bytes: ByteArray) = null
            }, Executor { queued += it }, Executor { it.run() },
        )
        controller.attach(object : SharedConnectionHost {
            override fun onBack(destination: ConnectionEntryModel.Back) {}
            override fun onCommitted(config: SharedConnectionConfig) {}
            override fun onBusyChanged(busy: Boolean) {}
        })
        controller.foreground(true)
        fun renderState(name: String) {
            for (width in listOf(500, 320)) {
                val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                    SharedConnectionScreen(controller,
                        SharedGameIdentity("一个很长的真实游戏名称用于检查标题换行以及窄屏导航是否仍然可见", "2026.10.15-long-version"))
                }
                try {
                    val card = cardPixelBounds(render(scene, "$name-$width"))
                    assertTrue(card.left >= 10f && card.right <= width - 10f)
                    assertTrue(card.bottom <= 742f, "$name footer stays in viewport")
                } finally { scene.close() }
            }
        }
        renderState("chooser")
        controller.choose(ConnectionEntryModel.Mode.OFFICIAL)
        controller.editUsername("long_username_" + "x".repeat(49))
        controller.editPassword("long-password-kept-private")
        renderState("official")
        controller.openAppreciation()
        assertNotNull(controller.state.appreciation)
        renderState("appreciation")
        controller.back()
        assertEquals(ConnectionEntryModel.Mode.OFFICIAL, controller.state.mode)
        assertEquals("long_username_" + "x".repeat(49), controller.state.username)
        assertTrue(queued.isNotEmpty())
        controller.close()
    }

    /** Surface has no shadow, so every non-background pixel belongs to the rendered card. */
    private fun cardPixelBounds(image: BufferedImage): Rect {
        var left = image.width
        var top = image.height
        var right = 0
        var bottom = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if ((image.getRGB(x, y) and 0xffffff) != 0xeff3ef) {
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
            }
        }
        assertTrue(right > left && bottom > top, "card must actually render")
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun render(scene: ImageComposeScene, name: String): BufferedImage {
        scene.render(0).close()
        scene.render(16_000_000).close()
        return scene.render(32_000_000).use { image ->
            val file = Path.of("build/reports/shared-connection", "$name.png")
            Files.createDirectories(file.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(file, data.bytes)
                ImageIO.read(ByteArrayInputStream(data.bytes))
            }
        }
    }
}
