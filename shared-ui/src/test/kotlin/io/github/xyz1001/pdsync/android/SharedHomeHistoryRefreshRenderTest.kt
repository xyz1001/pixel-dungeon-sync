@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import pdsync.i18n.SharedMessages
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.jetbrains.skia.EncodedImageFormat
import pdsync.i18n.UiLocale
import pdsync.presentation.HistoryNode
import pdsync.presentation.HistoryPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The same production header and click path are compiled into desktop and Android. */
class SharedHomeHistoryRefreshRenderTest {
    private var frame = 0L
    private val history = HistoryPresentation(
        listOf(HistoryNode("commit-real", "snapshot-real", "Archive", "10-15 09:30", "", "", 0, 0, selectable = true)),
        emptyList(), 0, 1, 1, 1, false)
    private fun ready() = SharedHomeState(SharedGameIdentity("Shattered Pixel Dungeon", "4.0.2"), "",
        pageActive = true, history = history, selectedSnapshotId = "snapshot-real", canRefreshHistory = true)

    private class Host(initial: SharedHomeState) : SharedHomeActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        override fun settings() { calls += "settings" }
        override fun upload() { calls += "upload" }
        override fun restore() { calls += "restore" }
        override fun launch() { calls += "launch" }
        override fun selectSnapshot(snapshotId: String) { calls += "select:$snapshotId" }
        override fun rename(snapshotId: String?) { calls += "rename:$snapshotId" }
        override fun changeHistoryPage(page: Int) { calls += "page:$page" }
        override fun refreshHistory() {
            if (!state.refreshHistoryEnabled) return
            calls += "refresh"
            state = state.copy(archiveLoading = true)
        }
    }

    @Test fun oneRefreshFitsWithHistoryTitleInBothLanguagesIncludingNarrowLargeText() {
        val previous = UiLocale.locale()
        try {
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                UiLocale.setLocale(locale)
                for ((width, scale) in listOf(320 to 1f, 320 to 1.5f, 320 to 2f, 390 to 1.5f, 390 to 2f, 420 to 1.5f, 420 to 2f, 1165 to 1f)) {
                    val host = Host(ready())
                    val observed = Observation()
                    val scene = ImageComposeScene(width = width, height = 1800, density = Density(1f, scale)) {
                        CompositionLocalProvider(LocalSharedHomeLayoutObserver provides observed,
                            LocalSharedHistoryLayoutObserver provides observed) { SharedHomeScreen(host.state, host) }
                    }
                    try {
                        render(scene, "home-history-refresh-${locale.language}-$width-$scale")
                        assertEquals(setOf("title", "refresh"), observed.header.keys)
                        val section = observed.home.getValue("history")
                        val title = observed.header.getValue("title")
                        val button = observed.header.getValue("refresh")
                        assertTrue(title.width > 0 && title.height <= 2 * 22.5f * scale + 1f,
                            "complete title stays readable in at most two lines; locale=$locale width=$width scale=$scale title=$title button=$button section=$section density=1.0 fontScale=$scale layouts=${titleLayouts(scene)}")
                        if (section.width <= 360f && scale >= 1.5f) {
                            assertTrue(button.top >= title.bottom + 8f - .6f, "large text refresh wraps below the complete title")
                            assertEquals(title.right, button.right, .6f)
                        } else {
                            assertTrue(title.right + 8f <= button.left + .6f, "refresh stays to the right of the title")
                            assertEquals(title.center.y, button.center.y, .6f)
                        }
                        assertTrue(button.width >= 48f && button.height >= 42f)
                        assertTrue(title.left >= section.left && button.right <= section.right)
                        assertTrue(section.left >= 8 && section.right <= width - 8)
                        assertTrue(button.bottom < 1800f, "the actual button can receive pointer input")
                        assertTrue(host.calls.isEmpty(), "rendering never starts a read")
                        val before = host.state
                        click(scene, button.center)
                        assertEquals(listOf("refresh"), host.calls)
                        assertEquals(before.copy(archiveLoading = true), host.state,
                            "only the existing read callback runs, without changing selected IDs or pages")
                        render(scene, "home-history-refresh-loading-${locale.language}-$width-$scale")
                        repeat(3) { click(scene, observed.header.getValue("refresh").center) }
                        assertEquals(listOf("refresh"), host.calls, "the existing loading state blocks repeated clicks")
                    } finally { scene.close() }
                }
            }
        } finally { UiLocale.setLocale(previous) }
    }

    @Test fun homeKeepsOneDisabledRefreshDuringLoadingBusyInactiveAndUnavailableStates() {
        for (populated in listOf(false, true)) {
            val base = ready().let { if (populated) it else it.copy(history = null) }
            val host = Host(base)
            val observed = Observation()
            val scene = ImageComposeScene(width = 320, height = 1800, density = Density(1f)) {
                CompositionLocalProvider(LocalSharedHistoryLayoutObserver provides observed) {
                    SharedHomeScreen(host.state, host)
                }
            }
            try {
                for ((name, blocked) in listOf("disabled" to base.copy(canRefreshHistory = false),
                    "busy" to base.copy(busy = true), "loading" to base.copy(archiveLoading = true),
                    "inactive" to base.copy(pageActive = false))) {
                    host.state = blocked
                    render(scene, "home-history-refresh-$name-$populated")
                    assertEquals(setOf("title", "refresh"), observed.header.keys)
                    repeat(3) { click(scene, observed.header.getValue("refresh").center) }
                    assertTrue(host.calls.isEmpty(), "$name suppresses refresh, including empty history")
                }
                host.state = base
                render(scene, "home-history-refresh-ready-$populated")
                click(scene, observed.header.getValue("refresh").center)
                assertEquals(listOf("refresh"), host.calls, "a completed or failed read can be retried")
            } finally { scene.close() }
        }
    }

    @Test fun otherHistoryContextsRemainUnchangedUnlessTheySupplyARefreshCallback() {
        var callback by mutableStateOf<(() -> Unit)?>(null)
        var enabled by mutableStateOf(false)
        var busy by mutableStateOf(false)
        var loading by mutableStateOf(false)
        var calls = 0
        val observed = Observation()
        val scene = ImageComposeScene(width = 320, height = 600, density = Density(1f)) {
            SharedConnectionTheme {
                CompositionLocalProvider(LocalSharedHistoryLayoutObserver provides observed) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        SharedHistorySection(null, null, busy, {}, {}, {}, loading = loading,
                            onRefresh = callback, refreshEnabled = enabled)
                    }
                }
            }
        }
        try {
            render(scene, "history-without-refresh")
            assertTrue(observed.header.isEmpty(), "omitting the callback retains the existing standalone title")
            callback = { calls++ }
            render(scene, "history-refresh-default-disabled")
            click(scene, observed.header.getValue("refresh").center)
            assertEquals(0, calls)
            enabled = true
            for (state in listOf(true to false, false to true)) {
                busy = state.first; loading = state.second
                render(scene, "history-refresh-guard-${state.first}-${state.second}")
                click(scene, observed.header.getValue("refresh").center)
                assertEquals(0, calls)
            }
            busy = false; loading = false
            render(scene, "history-refresh-enabled")
            click(scene, observed.header.getValue("refresh").center)
            assertEquals(1, calls)
            callback = null
            observed.header.clear()
            render(scene, "history-refresh-removed")
            assertTrue(observed.header.isEmpty(), "removing the callback leaves no new control")
        } finally { scene.close() }
    }

    private fun titleLayouts(scene: ImageComposeScene): String {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return descend(owner.unmergedRootSemanticsNode).filter { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == SharedMessages.SAVE_HISTORY.text() }
        }.flatMap { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.map { layout ->
                "node=${node.id} bounds=${node.boundsInRoot} lines=${layout.lineCount} " +
                    "overflowWidth=${layout.didOverflowWidth} overflowHeight=${layout.didOverflowHeight} " +
                    "exceededMaxLines=${layout.multiParagraph.didExceedMaxLines} size=${layout.size} " +
                    "fontSize=${layout.layoutInput.style.fontSize} lineHeight=${layout.layoutInput.style.lineHeight} " +
                    "lineMetrics=" + (0 until layout.lineCount).joinToString { line ->
                        "$line:[${layout.getLineTop(line)},${layout.getLineBottom(line)}] ellipsized=${layout.isLineEllipsized(line)}"
                    }
            }
        }.joinToString("; ")
    }

    private class Observation : SharedHomeLayoutObserver, SharedHistoryLayoutObserver {
        val home = mutableMapOf<String, Rect>()
        val header = mutableMapOf<String, Rect>()
        override fun element(name: String, boundsInRoot: Rect) { home[name] = boundsInRoot }
        override fun headerElement(name: String, boundsInRoot: Rect) { header[name] = boundsInRoot }
    }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }

    private fun render(scene: ImageComposeScene, name: String) {
        repeat(3) { scene.render(++frame * 16_000_000).close() }
        scene.render(++frame * 16_000_000).use { image ->
            val file = Path.of("build/reports/shared-home-history-refresh", "$name.png")
            Files.createDirectories(file.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(file, it.bytes) }
        }
    }
}
