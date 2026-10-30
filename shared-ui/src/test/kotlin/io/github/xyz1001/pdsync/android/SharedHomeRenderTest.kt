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
import kotlin.test.assertContentEquals
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
        override fun appreciate() {
            if (!state.appreciationEnabled) return
            calls += "appreciate"; state = state.copy(pageActive = false)
        }
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
                assertEquals(minOf(860, width - if (width <= 600) 16 else 32).toFloat(), bounds.width, 1f)
                assertTrue(bounds.bottom <= 745f)
                val status = observed.elements.getValue("status")
                val upload = observed.elements.getValue("upload")
                val restore = observed.elements.getValue("restore")
                val launch = observed.elements.getValue("launch")
                val history = observed.elements.getValue("history")
                val settings = observed.elements.getValue("settings")
                val gap = 16f
                assertTrue(settings.bottom <= status.top, "settings stays in the compact header")
                if (width > 600) assertEquals(upload.top, restore.top) else assertTrue(restore.top >= upload.bottom)
                assertEquals(upload.width, restore.width, 1f)
                if (width > 600) assertEquals(gap, restore.left - upload.right, .6f)
                assertTrue(upload.height >= 64f && restore.height >= 64f)
                assertTrue(status.bottom < upload.top && restore.bottom <= launch.top && launch.bottom <= history.top)
                for (element in listOf(status, upload, restore, launch, history, settings)) {
                    assertTrue(element.left >= bounds.left && element.right <= bounds.right, "content fits at $width: $element")
                }
                assertFalse("target" in observed.elements)
                assertEquals(0f, history.top - launch.bottom, .6f, "history owns its reference heading margin; no explanation-card spacer")
                assertTrue(assertNotNull(observed.viewport).top > history.top, "M3 graph heading precedes viewport")
                assertEquals(status.left, history.left, .6f)
                assertEquals(status.right, history.right, .6f)
                assertFalse("details-toggle" in observed.elements)
                assertFalse("details" in observed.elements)
                assertTrue(host.calls.isEmpty(), "composition must not invoke a platform operation")
            } finally { scene.close() }
        }
    }

    @Test fun expiredStatusContainsOnlyTheAuthorizedSupportActionAndRetainsOtherFeedback() {
        val previousLocale = pdsync.i18n.UiLocale.locale()
        try {
            for (locale in listOf(java.util.Locale.SIMPLIFIED_CHINESE, java.util.Locale.ENGLISH)) {
                pdsync.i18n.UiLocale.setLocale(locale)
                for ((width, scale) in listOf(320 to 1f, 320 to 1.5f, 320 to 2f, 1165 to 1f)) {
                    val expired = pdsync.i18n.CoreSessionMessages.SERVICE_EXPIRED.text()
                    val base = ready().copy(status = expired, statusKind = SharedHomeStatusKind.EXPIRED,
                        canAppreciate = true, canUpload = false, canRestore = false, notice = "$expired\n$expired")
                    val host = Host(base)
                    val observed = Observation()
                    val scene = ImageComposeScene(width = width, height = 1600, density = Density(1f, scale)) {
                        CompositionLocalProvider(LocalSharedHomeLayoutObserver provides observed,
                            LocalSharedHistoryLayoutObserver provides observed) { SharedHomeScreen(host.state, host) }
                    }
                    try {
                        render(scene, "home-expired-${locale.language}-$width-$scale")
                        val status = observed.elements.getValue("status")
                        val button = observed.elements.getValue("appreciate")
                        val label = observed.elements.getValue("expiry-status-text")
                        assertTrue(button.width >= 48 && button.height >= 42,
                            "support action minimum size; node=appreciate locale=$locale viewportWidthDp=$width density=1.0 fontScale=$scale buttonPx=$button buttonDp=${button.width}x${button.height} labelPx=$label statusPx=$status")
                        assertTrue(button.left >= status.left && button.right <= status.right)
                        assertTrue(button.top >= status.top && button.bottom <= status.bottom)
                        assertTrue(status.left >= 8 && status.right <= width - 8)
                        if (width == 320 && scale >= 1.5f) {
                            assertTrue(label.width >= 160f, "large-text status keeps enough width for complete words")
                            assertTrue(label.height <= 2 * 21f * scale + 1f, "expiry needs at most two readable lines")
                            assertTrue(button.top >= label.bottom + 8f, "the same action wraps below the status")
                        } else {
                            assertTrue(label.right <= button.left, "ordinary sizes retain the inline action")
                        }
                        assertFalse("notice" in observed.elements, "no second expiry line remains")
                        repeat(3) { click(scene, button.center) }
                        assertEquals(listOf("appreciate"), host.calls, "only existing-page navigation runs")
                        host.state = base.copy(notice = "$expired\nUnrelated feedback")
                        render(scene, "home-expired-feedback-${locale.language}-$width-$scale")
                        assertTrue("notice" in observed.elements)
                        for (state in listOf(base.copy(canAppreciate = false),
                            base.copy(status = "Ready", statusKind = SharedHomeStatusKind.NEUTRAL))) {
                            observed.elements.clear()
                            host.state = state
                            render(scene, "home-renewed-${locale.language}-$width-$scale-${state.canAppreciate}")
                            assertFalse("appreciate" in observed.elements)
                        }
                    } finally { scene.close() }
                }
            }
        } finally { pdsync.i18n.UiLocale.setLocale(previousLocale) }
    }

    @Test fun defaultAndSelectedExplanationsAndRoutineSuccessHaveNoPixelsButErrorsRemain() {
        for (width in listOf(1165, 320)) {
            val observed = Observation()
            val host = Host(ready())
            val scene = scene(width, 1600, host, observed)
            try {
                val baseline = render(scene, "home-compact-$width")
                val baselineHistory = observed.elements.getValue("history")
                host.state = host.state.copy(notice = "",
                    restoreTarget = host.state.restoreTarget!!.copy(name = "Removed explanation", notice = "本机已是此进度，无需恢复。"))
                val routine = render(scene, "home-compact-routine-$width")
                assertContentEquals(baseline.getRGB(0, 0, width, baseline.height, null, 0, width),
                    routine.getRGB(0, 0, width, routine.height, null, 0, width))
                assertFalse("target" in observed.elements)
                assertFalse("notice" in observed.elements)
                host.state = host.state.copy(selectedSnapshotId = "snapshot-a")
                render(scene, "home-compact-selected-$width")
                assertEquals(baselineHistory, observed.elements.getValue("history"), "selection adds no explanation space")
                host.state = host.state.copy(notice = "读取云端失败，请重试。", noticeError = true,
                    restoreTarget = host.state.restoreTarget!!.copy(notice = "暂不能恢复，请重新检查。", noticeError = true))
                render(scene, "home-compact-errors-$width")
                assertTrue(observed.elements.getValue("target-error").height > 0)
                assertTrue(observed.elements.getValue("notice").height > 0)
                assertTrue(observed.elements.getValue("history").top > baselineHistory.top)
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

    @Test fun technicalDetailsNeverAddAnEntryOrContentToHome() {
        for (width in listOf(1165, 560, 320)) {
            val observed = Observation()
            val host = Host(ready().copy(notice = "存档暂不可用，请重试。", noticeError = true))
            val scene = scene(width, 1600, host, observed)
            try {
                val baseline = render(scene, "home-no-details-$width")
                val baselineElements = observed.elements.toMap()
                assertEquals(setOf("settings", "status", "upload", "restore", "launch", "notice", "history"),
                    baselineElements.keys)
                assertEquals(12f, baselineElements.getValue("history").top - baselineElements.getValue("notice").bottom,
                    .6f, "history follows the notice directly, without a replacement entry")
                host.state = host.state.copy(details = "Retained watcher diagnostic\n" + "detail ".repeat(100))
                val withDetails = render(scene, "home-ignores-details-$width")
                assertEquals(baselineElements, observed.elements, "diagnostics must not change the home layout")
                assertContentEquals(
                    baseline.getRGB(0, 0, width, baseline.height, null, 0, width),
                    withDetails.getRGB(0, 0, width, withDetails.height, null, 0, width),
                    "diagnostics must not add visible text, buttons, or a replacement entry",
                )
                assertFalse("details-toggle" in observed.elements)
                assertFalse("details" in observed.elements)
                assertEquals(setOf("commit-a", "local-current"), observed.layout!!.cards.keys)
                assertTrue(host.calls.isEmpty(), "diagnostic changes never invoke a platform operation")
            } finally { scene.close() }
        }
    }

    @Test fun narrowHistoryScrollsOnItsOwnWithoutTechnicalDetails() {
        val observed = Observation()
        val host = Host(ready().copy(details = "Retained watcher diagnostic\nA long diagnostic line " + "detail ".repeat(40)))
        val scene = scene(320, 1600, host, observed)
        try {
            render(scene, "home-narrow-without-details")
            assertFalse("details-toggle" in observed.elements)
            assertFalse("details" in observed.elements)
            val statusBefore = observed.elements.getValue("status")
            val historyBefore = observed.elements.getValue("history")
            val viewport = assertNotNull(observed.viewport)
            val origin = observed.origin
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(viewport.center.x, viewport.top + 80),
                scrollDelta = Offset(8f, 0f))
            render(scene, "home-narrow-history-scrolled")
            assertTrue(observed.scroll!!.value > 0)
            assertTrue(observed.origin.x < origin.x)
            assertEquals(viewport, observed.viewport)
            assertEquals(statusBefore, observed.elements.getValue("status"))
            assertEquals(historyBefore, observed.elements.getValue("history"))
            assertTrue(observed.layout!!.cards.values.all { it.width == 188f })
            assertFalse("details-toggle" in observed.elements)
            assertFalse("details" in observed.elements)
            assertTrue(host.calls.isEmpty(), "horizontal scroll never invokes business operations")
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
                assertEquals(304f, bounds.width, 1f)
                assertTrue(bounds.left >= 8 && bounds.right <= 312 && bounds.bottom <= 745)
                for (element in listOf("settings", "upload", "restore", "history")) {
                    val rect = observed.elements.getValue(element)
                    assertTrue(rect.left >= 10 && rect.right <= 310)
                }
                if (name == "empty") assertTrue(observed.layout!!.cards.isEmpty())
                if (name == "loading") assertEquals(null, observed.layout)
                assertFalse("details-toggle" in observed.elements)
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
        assertTrue(with.second >= 42f)
        assertEquals(with.second + 28f + 1f, without.first - with.first, 1f,
            "the measured action + 32px padding + 1px divider is omitted with the footer")
    }

    @Test fun archiveLoadingCoversViewportAllowsNavigationAndClearsForCompletionOrNavigation() {
        for ((width, fontScale) in listOf(320 to 1f, 320 to 2f, 1165 to 1f)) {
            for (populated in listOf(false, true)) {
                val observed = Observation()
                val base = ready().let { if (populated) it else it.copy(history = null, restoreTarget = null) }
                val host = Host(base.copy(archiveLoading = true))
                val scene = ImageComposeScene(width = width, height = 757, density = Density(1f, fontScale)) {
                    CompositionLocalProvider(LocalSharedHomeLayoutObserver provides observed,
                        LocalSharedHistoryLayoutObserver provides observed) { SharedHomeScreen(host.state, host) }
                }
                try {
                    repeat(40) { scene.render(++frame * 16_000_000).close() }
                    render(scene, "home-archive-loading-$width-$fontScale-$populated")
                    val scrim = observed.elements.getValue("archive-loading-scrim")
                    val panel = observed.elements.getValue("archive-loading-panel")
                    assertEquals(Rect(0f, 0f, width.toFloat(), 757f), scrim)
                    assertTrue(panel.left >= 24 && panel.right <= width - 24)
                    assertTrue(panel.top >= 24 && panel.bottom <= 733)
                    assertEquals(width / 2f, panel.center.x, 1f)
                    assertEquals(757 / 2f, panel.center.y, 1f)
                    repeat(3) {
                        for (name in listOf("settings", "upload", "restore", "launch")) {
                            click(scene, observed.elements.getValue(name).center)
                        }
                    }
                    assertEquals(List(3) { "settings" }, host.calls, "only navigation is available while archives load")
                    host.calls.clear()
                    observed.elements.clear()
                    host.state = base.copy(busy = true, pending = SharedHomeOperation.UPLOAD)
                    render(scene, "home-archive-complete-other-busy-$width-$fontScale-$populated")
                    assertFalse("archive-loading-scrim" in observed.elements, "ordinary busy is not an archive read")
                    observed.elements.clear()
                    host.state = base.copy(archiveLoading = true, pageActive = false)
                    render(scene, "home-archive-left-$width-$fontScale-$populated")
                    assertFalse("archive-loading-scrim" in observed.elements, "inactive pages cannot retain a modal")
                    host.state = base
                    render(scene, "home-archive-ready-$width-$fontScale-$populated")
                    click(scene, observed.elements.getValue("settings").center)
                    assertEquals(listOf("settings"), host.calls, "completion restores actions")
                } finally { scene.close() }
            }
        }
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
        for (y in 0 until image.height) for (x in 0 until image.width) if ((image.getRGB(x, y) and 0xffffff) in setOf(0xffffff, 0xcbd5e1)) {
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
