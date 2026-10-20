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
import kotlin.test.assertTrue

/** Actual shared production screen; all branch identities and timestamps below are test fixtures. */
class SharedBranchChoiceRenderTest {
    private var frame = 0L
    private fun ready() = SharedBranchChoiceState(Any(), listOf(
        SharedBranchChoice("snapshot-a", "手机进度", "手机 · 10-15 09:30 · 4 个文件"),
        SharedBranchChoice("snapshot-b", "电脑进度", "电脑 · 10-14 20:00 · 6 个文件")),
        game = SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"), pageActive = true,
        connected = true, observationCurrent = true, overwriteSupported = true, mergeSupported = true)

    private class Host(initial: SharedBranchChoiceState) : SharedBranchChoiceActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        override fun select(snapshotId: String) { calls += "select:$snapshotId"; state = state.copy(selectedId = snapshotId) }
        override fun back() { calls += "back" }
        override fun refresh() { calls += "refresh" }
        override fun overwrite(snapshotId: String) { calls += "overwrite:$snapshotId" }
        override fun merge() { calls += "merge" }
        override fun preserveLocal() { calls += "preserve" }
    }
    private class Observation : SharedBranchChoiceLayoutObserver {
        val elements = mutableMapOf<String, Rect>()
        override fun element(name: String, bounds: Rect) { elements[name] = bounds }
    }

    @Test fun branchCardsAndSinglePrimaryUseReferenceShellAtDesktop500And320() {
        for (width in listOf(1165, 500, 320)) {
            val host = Host(ready())
            val observed = Observation()
            val scene = scene(width, 757, host, observed)
            try {
                val image = render(scene, "branches-$width")
                val card = cardBounds(image)
                assertEquals(minOf(860, width - if (width <= 600) 16 else 32).toFloat(), card.width, 1f)
                assertNoHorizontalOverflow(image, card)
                val first = observed.elements.getValue("branch:snapshot-a")
                val second = observed.elements.getValue("branch:snapshot-b")
                assertEquals(card.left + 1f + if (width <= 600) 20f else 30f, first.left, .6f)
                assertEquals(8f, second.top - first.bottom, .6f)
                assertTrue(host.calls.isEmpty(), "composition must not perform any host operation")
                val primary = observed.elements.getValue("primary")
                val back = observed.elements.getValue("back")
                assertTrue(primary.height >= 42f && back.height >= 42f)
                assertTrue(back.left < primary.left && primary.right <= first.right + .6f)
                assertTrue(primary.bottom <= 745f && back.bottom <= 745f)
                assertTrue(primary.top > second.bottom)
                click(scene, second.center)
                render(scene, "branches-selected-$width")
                assertEquals("snapshot-b", host.state.selectedId)
                assertEquals(listOf("select:snapshot-b"), host.calls)
                click(scene, back.center)
                assertEquals(listOf("select:snapshot-b", "back"), host.calls)
            } finally { scene.close() }
        }
    }

    @Test fun disabledBusyUnknownAndRecoveryRemainNonWritingAtEveryWidth() {
        for (width in listOf(1165, 500, 320)) {
            val base = ready()
            val variants = listOf(
                "busy" to base.copy(busy = true),
                "inactive" to base.copy(pageActive = false),
                "unsupported" to base.copy(overwriteSupported = false, mergeSupported = false),
                "pending" to base.copy(resolutionBlocked = true, notice = "上次上传尚未完成，请先重试上传。"),
                "unknown-selection" to base.copy(selectedId = "removed", overwriteSupported = false, mergeSupported = false),
            )
            for ((name, current) in variants) {
                val host = Host(current)
                val observed = Observation()
                val scene = scene(width, 757, host, observed)
                try {
                    val image = render(scene, "branches-$name-$width")
                    assertNoHorizontalOverflow(image, cardBounds(image))
                    click(scene, observed.elements.getValue("primary").center)
                    assertTrue(host.calls.isEmpty(), "$name disabled primary must not dispatch")
                    if (current.busy || !current.pageActive) {
                        click(scene, observed.elements.getValue("branch:snapshot-a").center)
                        click(scene, observed.elements.getValue("back").center)
                        assertTrue(host.calls.isEmpty())
                    }
                } finally { scene.close() }
            }
            val host = Host(base.copy(observationCurrent = false, branches = emptyList()))
            val observed = Observation()
            val scene = scene(width, 757, host, observed)
            try {
                render(scene, "branches-refresh-$width")
                click(scene, observed.elements.getValue("primary").center)
                assertEquals(listOf("refresh"), host.calls)
            } finally { scene.close() }
        }
    }

    @Test fun longBranchesScrollWhileBackAndHandleConflictStayVisibleInShortWindows() {
        for (width in listOf(1165, 500, 320)) for (height in listOf(757, 420)) {
            val base = ready()
            val host = Host(base.copy(branches = (1..12).map {
                SharedBranchChoice("branch-$it", "很长的真实设备与存档名称".repeat(3), "实际存档修改时间 · 已进行3场游戏 · 6 个文件")
            }))
            val observed = Observation()
            val scene = scene(width, height, host, observed)
            try {
                val before = render(scene, "branches-scroll-$width-$height-before")
                val card = cardBounds(before)
                assertNoHorizontalOverflow(before, card)
                val back = observed.elements.getValue("back")
                val primary = observed.elements.getValue("primary")
                assertTrue(primary.bottom <= height - if (width <= 600) 15f else 30f)
                assertTrue(primary.right < card.right && back.left > card.left)
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(width / 2f, minOf(220f, back.top - 20f)),
                    scrollDelta = Offset(0f, 20f))
                val after = render(scene, "branches-scroll-$width-$height-after")
                assertEquals(back, observed.elements.getValue("back"))
                assertEquals(primary, observed.elements.getValue("primary"))
                assertTrue((160 until back.top.toInt()).any { y ->
                    (30 until width - 30).any { x -> before.getRGB(x, y) != after.getRGB(x, y) }
                }, "branch list scrolls independently at $width x $height")
                assertTrue(host.calls.isEmpty())
            } finally { scene.close() }
        }
    }

    private fun scene(width: Int, height: Int, host: Host, observed: Observation) =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CompositionLocalProvider(LocalSharedBranchChoiceLayoutObserver provides observed) {
                SharedBranchChoiceScreen(host.state, host)
            }
        }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }

    private fun cardBounds(image: BufferedImage): Rect {
        var left = image.width; var top = image.height; var right = 0; var bottom = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if ((image.getRGB(x, y) and 0xffffff) in setOf(0xffffff, 0xcbd5e1)) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
            }
        }
        assertTrue(right > left && bottom > top)
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun assertNoHorizontalOverflow(image: BufferedImage, card: Rect) {
        val outer = 2 // Exclude the reference ambient shadow; text bounds are checked separately.
        assertTrue(card.left >= outer && card.right <= image.width - outer)
        for (y in 0 until image.height) {
            for (x in 0 until outer) assertEquals(0xf8fafc, image.getRGB(x, y) and 0xffffff)
            for (x in image.width - outer until image.width) assertEquals(0xf8fafc, image.getRGB(x, y) and 0xffffff)
        }
    }

    private fun render(scene: ImageComposeScene, name: String): BufferedImage {
        repeat(3) { scene.render(++frame * 16_000_000).close() }
        return scene.render(++frame * 16_000_000).use { image ->
            val file = Path.of("build/reports/shared-branch-choice", "$name.png")
            Files.createDirectories(file.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(file, data.bytes)
                ImageIO.read(ByteArrayInputStream(data.bytes))
            }
        }
    }
}
