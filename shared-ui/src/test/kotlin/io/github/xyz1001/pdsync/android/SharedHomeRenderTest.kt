@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import pdsync.presentation.HistoryNode
import pdsync.presentation.HistoryPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual production Compose rendering and pointer dispatch; all sample data stays in tests. */
class SharedHomeRenderTest {
    private var frame = 0L
    private val cloud = HistoryNode("commit-a", "snapshot-a", "远征记录", "10-15 09:30",
        "Desktop · 2 个槽位", "云端当前分支", 0, 1, selectable = true)
    private val local = HistoryNode("local-current", null, "本机进度", "10-15 10:30",
        "2 个槽位 · 已进行28场游戏", "未同步云端", 1, 0, dirty = true)
    private fun history(page: Int = 0) = HistoryPresentation(
        if (page == 0) listOf(cloud, local) else listOf(cloud.copy(id = "commit-older", snapshotId = "snapshot-older", row = 0)),
        emptyList(), page, 2, if (page == 0) 2 else 1, 21, false)
    private fun ready() = SharedHomeState(SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"), "云端已连接",
        pageActive = true, canUpload = true, canRestore = true, canLaunch = true,
        restoreTarget = SharedHomeRestoreTarget("snapshot-a", "远征记录", "Desktop", "10-15 09:30"), history = history())

    private class Host(initial: SharedHomeState) : SharedHomeActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        var onPage: (Int) -> Unit = {}
        override fun settings() { calls += "settings" }
        override fun upload() { calls += "upload" }
        override fun restore() { calls += "restore" }
        override fun launch() { calls += "launch" }
        override fun selectSnapshot(snapshotId: String) {
            calls += "select:$snapshotId"
            val node = state.history!!.nodes.first { it.snapshotId == snapshotId }
            state = state.copy(selectedSnapshotId = snapshotId,
                restoreTarget = SharedHomeRestoreTarget(snapshotId, node.name, time = node.time))
        }
        override fun rename(snapshotId: String?) { calls += "rename:$snapshotId" }
        override fun changeHistoryPage(page: Int) { calls += "page:$page"; onPage(page) }
    }

    @Test fun homeMatchesReferenceOrderAndTwoActionColumnsWithoutPageOverflow() {
        for (width in listOf(1165, 561, 560, 500, 320)) {
            val observed = Observation()
            val host = Host(ready())
            val scene = scene(width, 757, host, observed)
            try {
                val pixels = render(scene, "home-$width")
                val bounds = cardBounds(pixels)
                assertEquals(minOf(820, width - if (width <= 560) 20 else 36).toFloat(), bounds.width, 1f)
                assertTrue(bounds.bottom <= 742f)
                val status = observed.elements.getValue("status")
                val upload = observed.elements.getValue("upload")
                val restore = observed.elements.getValue("restore")
                val launch = observed.elements.getValue("launch")
                val target = observed.elements.getValue("target")
                val settings = observed.elements.getValue("settings")
                val gap = if (width <= 560) 8f else 12f
                assertTrue(settings.bottom <= status.top, "settings stays in the compact header")
                assertEquals(upload.top, restore.top)
                assertEquals(upload.width, restore.width, 1f)
                assertEquals(gap, restore.left - upload.right, .6f)
                assertTrue(upload.height >= 64f && restore.height >= 64f)
                assertTrue(status.bottom < upload.top && upload.bottom <= launch.top && launch.bottom <= target.top)
                for (element in listOf(status, upload, restore, launch, target, settings)) {
                    assertTrue(element.left >= bounds.left && element.right <= bounds.right, "content fits at $width: $element")
                }
                assertEquals(status.left, target.left, .6f)
                assertEquals(status.right, target.right, .6f)
                assertFalse("details" in observed.elements)
                assertTrue(host.calls.isEmpty(), "composition must not invoke a platform operation")
            } finally { scene.close() }
        }
    }

    @Test fun realButtonsSelectionRenamePagingAndBusyStateUseTheSameHomeImplementation() {
        val observed = Observation()
        val host = Host(ready())
        host.onPage = { host.state = host.state.copy(history = history(it)) }
        val scene = scene(1165, 1600, host, observed)
        try {
            render(scene, "home-actions")
            for (name in listOf("settings", "upload", "restore", "launch")) click(scene, observed.elements.getValue(name).center)
            click(scene, observed.rootPoint(observed.layout!!.cards.getValue("commit-a").center))
            render(scene, "home-selected")
            click(scene, observed.renamePoint("commit-a"))
            click(scene, observed.renamePoint("local-current"))
            assertEquals(listOf("settings", "upload", "restore", "launch", "select:snapshot-a",
                "rename:snapshot-a", "rename:null"), host.calls)
            click(scene, observed.pageActions.getValue("older").center)
            render(scene, "home-older-page")
            assertEquals(1, host.state.history!!.page)
            assertEquals("snapshot-a", host.state.selectedSnapshotId)
            assertEquals("snapshot-a", host.state.restoreTarget!!.snapshotId, "paging never silently replaces the restore target")
            click(scene, observed.rootPoint(observed.layout!!.cards.getValue("commit-older").center))
            render(scene, "home-older-selected")
            assertEquals("snapshot-older", host.state.selectedSnapshotId)
            val count = host.calls.size
            host.state = host.state.copy(busy = true, pending = SharedHomeOperation.UPLOAD)
            render(scene, "home-busy")
            for (name in listOf("settings", "upload", "restore", "launch")) click(scene, observed.elements.getValue(name).center)
            click(scene, observed.rootPoint(observed.layout!!.cards.getValue("commit-older").center))
            click(scene, observed.renamePoint("commit-older"))
            click(scene, observed.pageActions.getValue("newer").center)
            assertEquals(count, host.calls.size)
            host.state = host.state.copy(busy = false, pageActive = false)
            render(scene, "home-inactive")
            click(scene, observed.elements.getValue("upload").center)
            assertEquals(count, host.calls.size)
        } finally { scene.close() }
    }

    @Test fun narrowHistoryScrollsOnItsOwnAndTechnicalDetailsStartCollapsed() {
        val observed = Observation()
        val host = Host(ready().copy(details = "Retained watcher diagnostic\nA long diagnostic line " + "detail ".repeat(40)))
        val scene = scene(320, 1600, host, observed)
        try {
            render(scene, "home-narrow-details-collapsed")
            assertFalse("details" in observed.elements)
            val statusBefore = observed.elements.getValue("status")
            val targetBefore = observed.elements.getValue("target")
            val viewport = assertNotNull(observed.viewport)
            val origin = observed.origin
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(viewport.center.x, viewport.top + 80),
                scrollDelta = Offset(8f, 0f))
            render(scene, "home-narrow-history-scrolled")
            assertTrue(observed.scroll!!.value > 0)
            assertTrue(observed.origin.x < origin.x)
            assertEquals(viewport, observed.viewport)
            assertEquals(statusBefore, observed.elements.getValue("status"))
            assertEquals(targetBefore, observed.elements.getValue("target"))
            assertTrue(observed.layout!!.cards.values.all { it.width == 200f })
            click(scene, observed.elements.getValue("details-toggle").center)
            render(scene, "home-narrow-details-open")
            assertTrue(observed.elements.getValue("details").height > 0)
            observed.elements.remove("details")
            click(scene, observed.elements.getValue("details-toggle").center)
            render(scene, "home-narrow-details-closed")
            assertFalse("details" in observed.elements)
            assertTrue(host.calls.isEmpty(), "details and horizontal scroll never invoke business operations")
        } finally { scene.close() }
    }

    @Test fun loadingEmptyAndLongMetadataStatesStayWithinNarrowViewport() {
        val empty = HistoryPresentation(emptyList(), emptyList(), 0, 1, 1, 0, true)
        val states = mapOf(
            "loading" to ready().copy(status = "正在检查存档…", history = null, restoreTarget = null,
                canUpload = false, canRestore = false, busy = true),
            "empty" to ready().copy(status = "暂无存档", history = empty, restoreTarget = null,
                canUpload = false, canRestore = false),
            "long" to ready().copy(game = SharedGameIdentity("一个完整显示的很长游戏名称用于窄屏检查", "2026.10.15-long-version"),
                restoreTarget = SharedHomeRestoreTarget("snapshot-a", "需要完整换行保留的真实存档名称".repeat(4),
                    "很长的设备标签".repeat(3), "时间未知", "本机已是此进度，无需恢复。"), canRestore = false),
            "error" to ready().copy(notice = "存档暂不可用，请重试。", noticeError = true, details = "Underlying diagnostic"),
        )
        for ((name, state) in states) {
            val observed = Observation()
            val host = Host(state)
            val scene = scene(320, 757, host, observed)
            try {
                val bounds = cardBounds(render(scene, "home-$name-narrow"))
                assertEquals(300f, bounds.width, 1f)
                assertTrue(bounds.left >= 10 && bounds.right <= 310 && bounds.bottom <= 742)
                for (element in listOf("settings", "upload", "restore", "target")) {
                    val rect = observed.elements.getValue(element)
                    assertTrue(rect.left >= 10 && rect.right <= 310)
                }
                if (name == "empty") assertTrue(observed.layout!!.cards.isEmpty())
                if (name == "loading") assertEquals(null, observed.layout)
                assertFalse("details" in observed.elements)
                assertTrue(host.calls.isEmpty())
            } finally { scene.close() }
        }
    }

    @Test fun omittedFooterHasNoBlankActionStripWhileExplicitFooterKeepsItsSpace() {
        fun bodyAndFooter(withFooter: Boolean): Pair<Float, Float> {
            var body: Rect? = null
            var action: Rect? = null
            val footer: (@Composable RowScope.() -> Unit)? =
                if (withFooter) ({ SharedActionButton("返回", {}, style = SharedButtonStyle.TEXT,
                    modifier = Modifier.onGloballyPositioned { action = it.boundsInRoot() }) }) else null
            val scene = ImageComposeScene(width = 320, height = 757, density = Density(1f)) {
                SharedConnectionTheme {
                    SharedAppShell(title = "像素地牢", footer = footer) {
                        Spacer(Modifier.fillMaxWidth().height(1600.dp).onGloballyPositioned { body = it.boundsInRoot() })
                    }
                }
            }
            return try { render(scene, "home-shell-footer-$withFooter"); assertNotNull(body).bottom to (action?.height ?: 0f) }
                finally { scene.close() }
        }
        val without = bodyAndFooter(false)
        val with = bodyAndFooter(true)
        assertTrue(with.second >= 46f)
        assertEquals(with.second + 32f + 1f, without.first - with.first, 1f,
            "the measured action + 32px padding + 1px divider is omitted with the footer")
    }

    private fun scene(width: Int, height: Int, host: Host, observed: Observation) =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CompositionLocalProvider(LocalSharedHomeLayoutObserver provides observed,
                LocalSharedHistoryLayoutObserver provides observed) { SharedHomeScreen(host.state, host) }
        }

    private class Observation : SharedHomeLayoutObserver, SharedHistoryLayoutObserver {
        val elements = mutableMapOf<String, Rect>()
        var layout: SharedHistoryCanvasLayout? = null
        var origin = Offset.Zero
        var viewport: Rect? = null
        var scroll: ScrollState? = null
        val pageActions = mutableMapOf<String, Rect>()
        override fun element(name: String, boundsInRoot: Rect) { elements[name] = boundsInRoot }
        override fun canvas(positionInRoot: Offset, layout: SharedHistoryCanvasLayout) { origin = positionInRoot; this.layout = layout }
        override fun viewport(boundsInRoot: Rect, scrollState: ScrollState) { viewport = boundsInRoot; scroll = scrollState }
        override fun pageAction(name: String, boundsInRoot: Rect) { pageActions[name] = boundsInRoot }
        fun rootPoint(point: Offset) = origin + point
        fun renamePoint(id: String) = layout!!.renameActions.getValue(id).let { rootPoint(Offset(it.right - 20, it.center.y)) }
    }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }

    private fun cardBounds(image: BufferedImage): Rect {
        var left = image.width; var top = image.height; var right = 0; var bottom = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if ((image.getRGB(x, y) and 0xffffff) != 0xeff3ef) {
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
        }
        assertTrue(right > left && bottom > top)
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun render(scene: ImageComposeScene, name: String): BufferedImage {
        repeat(3) { scene.render(++frame * 16_000_000).close() }
        return scene.render(++frame * 16_000_000).use { image ->
            val file = Path.of("build/reports/shared-home", "$name.png")
            Files.createDirectories(file.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(file, data.bytes); ImageIO.read(ByteArrayInputStream(data.bytes))
            }
        }
    }
}
