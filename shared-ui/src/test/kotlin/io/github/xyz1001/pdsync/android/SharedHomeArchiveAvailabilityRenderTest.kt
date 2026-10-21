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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import pdsync.AppendCommit
import pdsync.CommitGraph
import pdsync.Manifest
import pdsync.SyncHistory
import pdsync.presentation.HistoryPresentation

/** Render the shared production page and dispatch real pointers; no alternate test-only controls. */
class SharedHomeArchiveAvailabilityRenderTest {
    private var frame = 0L
    private val localFiles = mapOf("game1/game.dat" to "a".repeat(64))
    private val cloudFiles = mapOf("game2/game.dat" to "b".repeat(64))
    private fun commit(id: String = "cloud", files: Map<String, String> = cloudFiles) =
        AppendCommit(id = id, parents = emptyList(), objectBytes = 1,
            snapshot = Manifest(game = "shattered", profile = "isolated-render-test", version = "922",
                id = "snapshot-$id", parent = null, sha256 = "c".repeat(64), files = files))
    private fun history(local: Map<String, String> = localFiles,
        commits: List<AppendCommit> = listOf(commit())) = SyncHistory(
        CommitGraph(commits.associateBy { it.id }, emptyMap()), null, local, "本机进度", null, emptyMap())

    private fun state(history: SyncHistory?, selectedId: String? = null, verified: Boolean = true): SharedHomeState {
        val available = SharedHomeArchiveAvailability.from(history, selectedId, verified)
        val unchanged = SharedHomeContent.from(history, selectedId, verified, false, false)
        return SharedHomeState(SharedGameIdentity("Shattered Pixel Dungeon", "4.0.2"), "云端已连接",
            pageActive = true, canLaunch = true,
            canUpload = available.uploadSourceAvailable && !unchanged.uploadUnnecessary,
            canRestore = available.restoreSourceAvailable && !unchanged.restoreUnnecessary,
            selectedSnapshotId = selectedId, history = history?.let { HistoryPresentation.from(it, 0) })
    }

    private class Host(initial: SharedHomeState) : SharedHomeActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        override fun upload() { calls += "upload" }
        override fun restore() { calls += "restore" }
        override fun launch() { calls += "launch" }
        override fun settings() { calls += "settings" }
        override fun selectSnapshot(snapshotId: String) { calls += "select:$snapshotId" }
        override fun rename(snapshotId: String?) { calls += "rename:$snapshotId" }
        override fun changeHistoryPage(page: Int) { calls += "page:$page" }
    }

    private data class Case(val name: String, val state: SharedHomeState, val expected: List<String>)

    @Test fun archiveMatrixRendersDisabledButtonsAndNeverDispatchesTheirRepeatedPointers() {
        val observed = history()
        val cases = listOf(
            Case("both-empty", state(history(emptyMap(), emptyList())), emptyList()),
            Case("local-only", state(history(commits = emptyList())), listOf("upload")),
            Case("cloud-only", state(history(local = emptyMap())), listOf("restore")),
            Case("both-different", state(observed), listOf("upload", "restore")),
            Case("both-identical", state(history(local = cloudFiles)), emptyList()),
            Case("empty-cloud-snapshot", state(history(emptyMap(), listOf(commit(files = emptyMap())))), emptyList()),
            Case("missing-selection", state(observed, selectedId = "missing-snapshot"), listOf("upload")),
            Case("unknown", state(null), emptyList()),
            Case("unverified", state(observed, verified = false), emptyList()),
            Case("pending-cloud", state(observed.copy(graph = observed.graph.copy(
                pending = mapOf("incomplete" to "unverified archive")))), emptyList()),
            Case("branch-chooser", state(history(emptyMap(), listOf(commit("left"), commit("right")))), listOf("restore")),
        )
        for (width in listOf(1165, 320)) for (case in cases) {
            val host = Host(case.state)
            val bounds = Observation()
            val scene = scene(width, host, bounds)
            try {
                val pixels = render(scene, "${case.name}-$width")
                assertEquals(setOf("settings", "status", "upload", "restore", "launch", "history"), bounds.elements.keys,
                    "Archive availability changes existing buttons only: ${case.name} at $width")
                for (action in listOf("upload", "restore")) {
                    val rect = bounds.elements.getValue(action)
                    assertTrue(rect.left >= 0 && rect.right <= width && rect.top >= 0 && rect.bottom <= 757)
                    val button = semanticsNodes(scene).single {
                        it.config.contains(SemanticsActions.OnClick) && it.boundsInRoot == rect
                    }
                    assertEquals(action !in case.expected, button.config.contains(SemanticsProperties.Disabled))
                    if (action !in case.expected) {
                        assertDisabledFill(pixels, rect, "${case.name}: $action")
                        assertTrue(texts(descendants(button)).none { it in setOf("推荐", "解决冲突") },
                            "A disabled $action must not retain a recommendation badge")
                    }
                }
                if (case.expected.isEmpty()) assertTrue(texts(semanticsNodes(scene)).none { it in setOf("推荐", "解决冲突") })
                repeat(3) { clickArchives(scene, bounds) }
                assertEquals(List(3) { case.expected }.flatten(), host.calls,
                    "Only nonempty verified sources can reach host actions: ${case.name} at $width")
                click(scene, bounds.elements.getValue("settings").center)
                click(scene, bounds.elements.getValue("launch").center)
                assertEquals(listOf("settings", "launch"), host.calls.takeLast(2),
                    "Empty archives do not disable unrelated settings or launch")
            } finally { scene.close() }
        }
    }

    @Test fun repeatedReadsFailureAndGameSwitchClearOldCapabilitiesWithoutChangingTheOverlay() {
        for (width in listOf(1165, 320)) {
            val ready = state(history())
            val empty = state(history(emptyMap(), emptyList()))
            val failed = state(history(), verified = false).copy(notice = "读取云端失败，请重试。", noticeError = true)
            val switched = state(null).copy(game = SharedGameIdentity("另一游戏", "1.0"))
            val host = Host(ready)
            val bounds = Observation()
            val scene = scene(width, host, bounds)
            try {
                render(scene, "sequence-ready-$width")
                clickArchives(scene, bounds)
                assertEquals(listOf("upload", "restore"), host.calls)
                host.calls.clear()
                for ((phase, next) in listOf("empty" to empty, "failure" to failed, "game-switch" to switched,
                    "empty-again" to empty, "recovered" to ready)) {
                    host.state = host.state.copy(archiveLoading = true)
                    render(scene, "sequence-loading-$phase-$width")
                    assertEquals(Rect(0f, 0f, width.toFloat(), 757f), bounds.elements.getValue("archive-loading-scrim"))
                    val panel = bounds.elements.getValue("archive-loading-panel")
                    assertEquals(width / 2f, panel.center.x, 1f)
                    assertEquals(757 / 2f, panel.center.y, 1f)
                    repeat(3) {
                        clickArchives(scene, bounds)
                        click(scene, bounds.elements.getValue("settings").center)
                        click(scene, bounds.elements.getValue("launch").center)
                    }
                    assertEquals(List(3) { "settings" }, host.calls, "Read loading preserves navigation during $phase")
                    host.calls.clear()
                    bounds.elements.clear()
                    host.state = next
                    render(scene, "sequence-$phase-$width")
                    assertFalse("archive-loading-scrim" in bounds.elements)
                    assertFalse("archive-loading-panel" in bounds.elements)
                    repeat(3) { clickArchives(scene, bounds) }
                    assertEquals(if (phase == "recovered") List(3) { listOf("upload", "restore") }.flatten() else emptyList(),
                        host.calls, "Stale capabilities cannot survive $phase")
                    host.calls.clear()
                }
            } finally { scene.close() }
        }
    }

    @Test fun nullHistoryStopsReadingAfterFailureOrIdleWhileConfirmedEmptyStillRendersEmptyHistory() {
        for (width in listOf(1165, 320)) {
            val host = Host(state(null).copy(archiveLoading = true))
            val bounds = Observation()
            val scene = scene(width, host, bounds)
            try {
                render(scene, "null-history-loading-$width")
                assertTrue("正在读取存档历史…" in texts(semanticsNodes(scene)))
                assertTrue("archive-loading-scrim" in bounds.elements)
                assertTrue("archive-loading-panel" in bounds.elements)

                val failed = state(null).copy(notice = "读取云端失败，请重试。", noticeError = true)
                for ((phase, terminal) in listOf("failure" to failed, "idle" to state(null))) {
                    bounds.elements.clear()
                    host.state = terminal
                    render(scene, "null-history-$phase-$width")
                    val visibleTexts = texts(semanticsNodes(scene))
                    assertFalse("正在读取存档历史…" in visibleTexts,
                        "Null history must not imply an active read after $phase at $width")
                    assertFalse("archive-loading-scrim" in bounds.elements)
                    assertFalse("archive-loading-panel" in bounds.elements)
                    assertFalse("暂无存档历史" in visibleTexts,
                        "An unavailable history is not a verified empty history")
                    if (phase == "failure") assertTrue("读取云端失败，请重试。" in visibleTexts)
                }

                host.state = state(history(emptyMap(), emptyList()))
                render(scene, "confirmed-empty-history-$width")
                val visibleTexts = texts(semanticsNodes(scene))
                assertTrue("暂无存档历史" in visibleTexts)
                assertFalse("正在读取存档历史…" in visibleTexts)
                assertFalse("archive-loading-scrim" in bounds.elements)
                assertFalse("archive-loading-panel" in bounds.elements)
            } finally { scene.close() }
        }
    }

    @Test fun retainedCallbacksReadNewArchiveCapabilitiesAndNeverKeepAnOldGameSource() {
        val host = Host(state(history()))
        val interactions = SharedHomeInteractions({ host.state }, { host })
        val retainedUpload = interactions::upload
        val retainedRestore = interactions::restore
        retainedUpload(); retainedRestore()
        assertEquals(listOf("upload", "restore"), host.calls)
        host.calls.clear()
        val transitions = listOf(
            host.state.copy(archiveLoading = true),
            state(history(emptyMap(), emptyList())),
            state(history(), verified = false),
            state(null).copy(game = SharedGameIdentity("另一游戏", "1.0")),
            state(history()).copy(pageActive = false),
            state(history()).copy(busy = true),
        )
        for (next in transitions) {
            host.state = next
            repeat(3) { retainedUpload(); retainedRestore() }
            assertTrue(host.calls.isEmpty())
        }
        host.state = state(history(local = emptyMap()))
        retainedUpload(); retainedRestore()
        assertEquals(listOf("restore"), host.calls)
    }

    private class Observation : SharedHomeLayoutObserver {
        val elements = mutableMapOf<String, Rect>()
        override fun element(name: String, boundsInRoot: Rect) { elements[name] = boundsInRoot }
    }

    private fun scene(width: Int, host: Host, bounds: Observation) =
        ImageComposeScene(width = width, height = 757, density = Density(1f)) {
            CompositionLocalProvider(LocalSharedHomeLayoutObserver provides bounds) { SharedHomeScreen(host.state, host) }
        }

    private fun clickArchives(scene: ImageComposeScene, bounds: Observation) {
        click(scene, bounds.elements.getValue("upload").center)
        click(scene, bounds.elements.getValue("restore").center)
    }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }

    private fun assertDisabledFill(image: BufferedImage, bounds: Rect, context: String) {
        var disabledPixels = 0
        var pixels = 0
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                if (image.getRGB(x, y) and 0xffffff == 0xf9fafc) disabledPixels++
                pixels++
            }
        }
        assertTrue(disabledPixels > pixels / 2, "The existing button has its disabled background: $context")
    }

    /** The pinned Compose version has no public ImageComposeScene semantics accessor. */
    private fun semanticsNodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        return descendants(owner.unmergedRootSemanticsNode)
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun texts(nodes: List<SemanticsNode>) = nodes.flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
    }

    private fun render(scene: ImageComposeScene, name: String): BufferedImage {
        repeat(4) { scene.render(++frame * 16_000_000).close() }
        return scene.render(++frame * 16_000_000).use { image ->
            val file = Path.of("build/reports/shared-home-availability", "$name.png")
            Files.createDirectories(file.parent)
            val nodes = semanticsNodes(scene)
            Files.writeString(file.resolveSibling("$name.semantics.txt"), texts(nodes).joinToString("\n") +
                "\n\nACTIONS\n" + nodes.filter { it.config.contains(SemanticsActions.OnClick) }.joinToString("\n") {
                    "${texts(descendants(it)).joinToString(" / ")}; disabled=${it.config.contains(SemanticsProperties.Disabled)}"
                })
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(file, data.bytes)
                ImageIO.read(ByteArrayInputStream(data.bytes))
            }
        }
    }
}
