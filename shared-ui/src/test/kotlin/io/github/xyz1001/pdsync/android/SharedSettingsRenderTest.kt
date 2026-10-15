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

/** Render and click the actual shared screen. Fixture metadata exists only in these tests. */
class SharedSettingsRenderTest {
    private var frame = 0L
    private val game = SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8")
    private fun ready() = SharedSettingsState(game, listOf(
        SharedSettingsRow(SharedSettingsKey.GAME, "游戏", game.summary, "更换", true),
        SharedSettingsRow(SharedSettingsKey.CLOUD_CONNECTION, "云端连接", "player@example.invalid", "修改", true),
    ), pageActive = true)

    private class Host(initial: SharedSettingsState) : SharedSettingsActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        override fun perform(key: SharedSettingsKey) { calls += key.name }
        override fun back() { calls += "back" }
    }

    private class Observation : SharedSettingsLayoutObserver {
        val elements = mutableMapOf<String, Rect>()
        val lines = mutableMapOf<SharedSettingsKey, Int>()
        val overflowing = mutableSetOf<SharedSettingsKey>()
        override fun element(name: String, boundsInRoot: Rect) { elements[name] = boundsInRoot }
        override fun value(key: SharedSettingsKey, lineCount: Int, hasVisualOverflow: Boolean) {
            lines[key] = lineCount
            if (hasVisualOverflow) overflowing += key else overflowing -= key
        }
    }

    @Test fun referenceRowsKeepValuesLeftActionsRightAndOnlyReturnInTheFooter() {
        for (width in listOf(1165, 500, 320)) {
            val observed = Observation()
            val host = Host(ready())
            val scene = scene(width, 757, host, observed)
            try {
                val image = render(scene, "settings-$width")
                val card = cardBounds(image)
                assertEquals(minOf(820, width - if (width <= 560) 20 else 36).toFloat(), card.width, 1f)
                assertNoHorizontalOverflow(image, card)
                val first = observed.elements.getValue("row:GAME")
                val second = observed.elements.getValue("row:CLOUD_CONNECTION")
                val back = observed.elements.getValue("back")
                val innerPadding = if (width <= 560) 20f else 32f
                assertEquals(card.left + 1f + innerPadding, first.left, .6f)
                assertEquals(card.right - 1f - innerPadding, first.right, .6f)
                assertEquals(12f, second.top - first.bottom, .6f)
                for (key in listOf(SharedSettingsKey.GAME, SharedSettingsKey.CLOUD_CONNECTION)) {
                    val row = observed.elements.getValue("row:$key")
                    val value = observed.elements.getValue("value:$key")
                    val action = observed.elements.getValue("action:$key")
                    assertTrue(value.right + (if (width <= 560) 8f else 16f) <= action.left + .6f)
                    assertTrue(action.width >= 46f && action.height >= 46f)
                    assertTrue(value.left >= row.left + 18f && action.right <= row.right - 18f + .6f)
                    assertTrue(row.top < action.bottom && action.bottom <= row.bottom)
                    if (width <= 560) assertEquals(row.top + 18f, action.top, .6f)
                }
                assertTrue(back.top > second.bottom && back.bottom < card.bottom)
                assertTrue(back.bottom <= 742f)
                assertEquals(first.left, back.left, .6f)
                assertFalse("notice" in observed.elements)
                assertTrue(host.calls.isEmpty(), "composition never triggers a platform operation")
                click(scene, observed.elements.getValue("action:GAME").center)
                click(scene, observed.elements.getValue("action:CLOUD_CONNECTION").center)
                click(scene, back.center)
                assertEquals(listOf("GAME", "CLOUD_CONNECTION", "back"), host.calls)
            } finally { scene.close() }
        }
    }

    @Test fun longUnbrokenCurrentValuesWrapWithoutHidingTheActionAtAllReferenceWidths() {
        val path = "/home/player/" + "veryLongUnbrokenSaveDirectoryName".repeat(5) + "/files"
        for (width in listOf(1165, 500, 320)) {
            val observed = Observation()
            val host = Host(ready().copy(rows = listOf(
                SharedSettingsRow(SharedSettingsKey.SAVE_PATH, "存档位置", path, "修改", true),
            )))
            val scene = scene(width, 757, host, observed)
            try {
                val image = render(scene, "settings-long-path-$width")
                val card = cardBounds(image)
                assertNoHorizontalOverflow(image, card)
                assertTrue(observed.lines.getValue(SharedSettingsKey.SAVE_PATH) > 1)
                assertTrue(observed.overflowing.isEmpty(), "full value wraps instead of truncation or ellipsis")
                val value = observed.elements.getValue("value:SAVE_PATH")
                val action = observed.elements.getValue("action:SAVE_PATH")
                assertTrue(value.width > 50f && value.right < action.left)
                assertTrue(action.width >= 46f && action.height >= 46f && action.right < card.right)
                click(scene, action.center)
                assertEquals(listOf("SAVE_PATH"), host.calls)
            } finally { scene.close() }
        }
    }

    @Test fun longActionLabelsAlsoWrapWhileTheValueColumnRemainsReadable() {
        val observed = Observation()
        val host = Host(ready().copy(rows = listOf(
            SharedSettingsRow(SharedSettingsKey.ACCOUNT_APPRECIATION, "官方账号", "player@example.invalid",
                "查看账号有效期与赞赏", true),
        )))
        val scene = scene(320, 757, host, observed)
        try {
            val image = render(scene, "settings-long-action-320")
            assertNoHorizontalOverflow(image, cardBounds(image))
            val value = observed.elements.getValue("value:ACCOUNT_APPRECIATION")
            val action = observed.elements.getValue("action:ACCOUNT_APPRECIATION")
            assertTrue(value.width > 80f && value.right < action.left)
            assertTrue(action.width <= 100f && action.height > 46f)
            click(scene, action.center)
            assertEquals(listOf("ACCOUNT_APPRECIATION"), host.calls)
        } finally { scene.close() }
    }

    @Test fun longSettingsScrollButReturnStaysReachableInShortAndRegularWindows() {
        for (width in listOf(1165, 500, 320)) for (height in listOf(757, 420)) {
            val observed = Observation()
            val host = Host(ready().copy(rows = SharedSettingsKey.entries.map { key ->
                SharedSettingsRow(key, "已有功能 ${key.ordinal + 1}", "调用者提供的真实当前值", "查看", true)
            }))
            val scene = scene(width, height, host, observed)
            try {
                val before = render(scene, "settings-scroll-$width-$height-before")
                val back = observed.elements.getValue("back")
                assertTrue(back.bottom <= height - if (width <= 560) 15f else 30f)
                val bodyPoint = Offset(width / 2f, minOf(240f, back.top - 30f))
                scene.sendPointerEvent(PointerEventType.Scroll, bodyPoint, scrollDelta = Offset(0f, 20f))
                val after = render(scene, "settings-scroll-$width-$height-after")
                assertEquals(back, observed.elements.getValue("back"), "footer does not scroll with rows")
                assertTrue((160 until back.top.toInt()).any { y ->
                    (30 until width - 30).any { x -> before.getRGB(x, y) != after.getRGB(x, y) }
                }, "actual settings body scrolls at $width x $height")
                click(scene, back.center)
                assertEquals(listOf("back"), host.calls)
                host.state = host.state.copy(canReturn = false)
                render(scene, "settings-return-disabled-$width-$height")
                click(scene, back.center)
                assertEquals(listOf("back"), host.calls)
            } finally { scene.close() }
        }
    }

    @Test fun missingReadOnlyDisabledBusyAndInactiveActionsNeverDispatch() {
        val observed = Observation()
        val host = Host(ready().copy(rows = listOf(
            SharedSettingsRow(SharedSettingsKey.SAVE_PATH, "存档位置", "/trusted/private/path"),
            SharedSettingsRow(SharedSettingsKey.EXPORT_LOGS, "日志", "正在导出…", "导出", false),
        )))
        val scene = scene(500, 757, host, observed)
        try {
            render(scene, "settings-readonly-disabled")
            assertFalse("action:SAVE_PATH" in observed.elements)
            assertFalse("row:MAINTENANCE" in observed.elements)
            click(scene, observed.elements.getValue("action:EXPORT_LOGS").center)
            assertTrue(host.calls.isEmpty())
            host.state = host.state.copy(rows = host.state.rows.map { it.copy(actionEnabled = true) }, busy = true)
            render(scene, "settings-busy")
            click(scene, observed.elements.getValue("action:EXPORT_LOGS").center)
            click(scene, observed.elements.getValue("back").center)
            host.state = host.state.copy(busy = false, pageActive = false)
            render(scene, "settings-inactive")
            click(scene, observed.elements.getValue("action:EXPORT_LOGS").center)
            click(scene, observed.elements.getValue("back").center)
            assertTrue(host.calls.isEmpty())
            host.state = host.state.copy(pageActive = true)
            render(scene, "settings-export-ready")
            click(scene, observed.elements.getValue("action:EXPORT_LOGS").center)
            assertEquals(listOf("EXPORT_LOGS"), host.calls)
        } finally { scene.close() }
    }

    private fun scene(width: Int, height: Int, host: Host, observed: Observation) =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CompositionLocalProvider(LocalSharedSettingsLayoutObserver provides observed) {
                SharedSettingsScreen(host.state, host)
            }
        }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }

    private fun cardBounds(image: BufferedImage): Rect {
        var left = image.width; var top = image.height; var right = 0; var bottom = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if ((image.getRGB(x, y) and 0xffffff) != 0xeff3ef) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
            }
        }
        assertTrue(right > left && bottom > top)
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun assertNoHorizontalOverflow(image: BufferedImage, card: Rect) {
        val outer = if (image.width <= 560) 10 else (image.width - minOf(820, image.width - 36)) / 2
        assertTrue(card.left >= outer && card.right <= image.width - outer)
        for (y in 0 until image.height) {
            for (x in 0 until outer) assertEquals(0xeff3ef, image.getRGB(x, y) and 0xffffff)
            for (x in image.width - outer until image.width) assertEquals(0xeff3ef, image.getRGB(x, y) and 0xffffff)
        }
    }

    private fun render(scene: ImageComposeScene, name: String): BufferedImage {
        repeat(3) { scene.render(++frame * 16_000_000).close() }
        return scene.render(++frame * 16_000_000).use { image ->
            val file = Path.of("build/reports/shared-settings", "$name.png")
            Files.createDirectories(file.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(file, data.bytes)
                ImageIO.read(ByteArrayInputStream(data.bytes))
            }
        }
    }
}
