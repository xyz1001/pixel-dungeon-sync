@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat
import pdsync.AppendCommit
import pdsync.CommitGraph
import pdsync.CommitOperationMetadata
import pdsync.DeviceIdentity
import pdsync.Manifest
import pdsync.SaveSummary
import pdsync.SaveVersions
import pdsync.SnapshotMetadata
import pdsync.SyncHistory
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real production Compose measurement, software rendering and pointer dispatch, without a fixture in production. */
class SharedHistoryRenderTest {
    private val oldFiles = mapOf("game1/game.dat" to "a".repeat(64))
    private val newFiles = mapOf("game1/game.dat" to "b".repeat(64))
    private val longName = "这是一段需要完整换行显示且不能被省略的真实存档名称用于验证长名称仍然能够看清"
    private var frame = 0L

    @Test fun desktopAndNarrowLayoutsKeepReadableLanesAndMeasuredTrueParentArrows() {
        val view = HistoryPresentation.from(history(), 0)
        for (width in listOf(1165, 500, 320)) {
            val observed = Observation()
            var selected: String? = null
            val renamed = mutableListOf<String?>()
            val scene = ImageComposeScene(width = width, height = 1800, density = Density(1f)) {
                SharedConnectionTheme {
                    CompositionLocalProvider(LocalSharedHistoryLayoutObserver provides observed) {
                        SharedAppShell(title = "像素地牢", eyebrow = "存档同步",
                            game = SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"),
                            footer = { SharedActionButton("返回", {}, style = SharedButtonStyle.TEXT) }) {
                            SharedHistorySection(view, selected, false, { selected = it }, { renamed += it }, {})
                        }
                    }
                }
            }
            try {
                val pixels = render(scene, "history-$width")
                val canvas = assertNotNull(observed.layout)
                val viewport = assertNotNull(observed.viewport)
                assertEquals(maxOf(440f, viewport.width), canvas.width, 1f)
                assertTrue(viewport.left >= 0 && viewport.right <= width, "the history viewport must fit $width")
                assertTrue(canvas.cards.values.all { it.width == canvas.width / 2 - 32 }, "no compressed cards at $width")
                val newest = canvas.cards.getValue("newest")
                val current = canvas.cards.getValue("local-current")
                val middle = canvas.cards.getValue("middle")
                val base = canvas.cards.getValue("base")
                val baseline = canvas.cards.getValue("local-baseline")
                assertTrue(newest.top < current.top && current.top < middle.top && middle.top < base.top)
                assertEquals(base.center.y, baseline.center.y, "a baseline and its cloud record align measured opposite-lane centers")
                assertTrue(base.height > baseline.height, "different measured heights must still share their time-row center")
                assertTrue(newest.height > base.height, "real measured long names and details must wrap")
                assertEquals(canvas.width / 2, baseline.center.x - base.center.x)
                assertEquals(view.edges.toSet(), canvas.arrows.map { it.edge }.toSet())
                val transfer = canvas.arrows.single { it.edge == HistoryEdge("local-baseline", "base", sameContent = true) }
                assertEquals(Offset(baseline.left, baseline.center.y), transfer.start)
                assertTrue(transfer.tip.x > base.right && transfer.tip.x < transfer.start.x,
                    "local baseline points left toward matching cloud content and stops outside its card")
                for (arrow in canvas.arrows) {
                    val target = canvas.cards.getValue(arrow.edge.toId)
                    assertFalse(target.contains(arrow.tip), "tip outside card: ${arrow.edge}")
                    assertFalse(target.contains(arrow.headLeft)); assertFalse(target.contains(arrow.headRight))
                    val source = canvas.cards.getValue(arrow.edge.fromId)
                    if (source.center.x == target.center.x) {
                        assertEquals(source.center.x, arrow.start.x)
                        assertEquals(target.center.x, arrow.tip.x)
                    }
                }
                if (width == 1165) {
                    val cloudEdge = canvas.arrows.single { it.edge == HistoryEdge("middle", "newest") }
                    assertColorNear(pixels, observed.rootPoint((cloudEdge.start + cloudEdge.tip) / 2f),
                        0x94a3b8, "the measured cloud edge must actually be painted")
                    assertColorNear(pixels, observed.rootPoint(transfer.tip), 0x006877,
                        "the transfer arrowhead must actually be painted outside the target")
                    val dirtyEdge = canvas.arrows.single { it.edge.dashed }
                    val upper = dirtyEdge.tip.y.toInt() + 16
                    val lower = dirtyEdge.start.y.toInt() - 16
                    val colors = (upper..lower).map { y -> pixels.getRGB(
                        observed.rootPoint(dirtyEdge.start).x.toInt(), observed.rootPoint(Offset(0f, y.toFloat())).y.toInt()) and 0xffffff }
                    assertTrue(colors.any { nearColor(it, 0x855300) }, "dashed baseline stroke must be painted")
                    assertTrue(colors.any { nearColor(it, 0xffffff) }, "dashed baseline must have actual gaps")
                }
                if (viewport.width < 440) {
                    val origin = observed.origin
                    val scroll = assertNotNull(observed.scroll)
                    assertTrue(scroll.maxValue > 0)
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(viewport.center.x, viewport.top + 70),
                        scrollDelta = Offset(8f, 0f))
                    render(scene, "history-$width-scrolled")
                    assertTrue(scroll.value > 0, "history must really scroll with pointer input")
                    assertEquals(viewport, observed.viewport, "only the history canvas scrolls, not the page")
                    assertTrue(observed.origin.x < origin.x)
                    assertEquals(canvas.cards, observed.layout!!.cards, "scrolling does not reflow or shrink lanes")
                } else {
                    click(scene, observed.rootPoint(newest.center))
                    assertEquals("snapshot-newest", selected)
                    click(scene, observed.renamePoint("base"))
                    click(scene, observed.renamePoint("local-current"))
                    assertEquals(listOf("snapshot-base", null), renamed, "native rename targets remain real IDs")
                }
            } finally { scene.close() }
        }
    }

    @Test fun pagingSelectionRenameAndBusyStateUseRealSnapshotIdsWithoutLosingSelection() {
        val commits = (0..23).map { index -> commit("commit-%02d".format(index),
            java.time.Instant.parse("2026-10-01T00:00:00Z").plusSeconds(index * 3600L).toString(),
            files = mapOf("game1/game.dat" to "%064x".format(index + 1))) }
        val state = SyncHistory(CommitGraph(commits.associateBy { it.id }, emptyMap()), null,
            emptyMap(), "", null, emptyMap())
        var page by mutableStateOf(0)
        var selected by mutableStateOf<String?>(null)
        var busy by mutableStateOf(false)
        var selections = 0
        val renames = mutableListOf<String?>()
        val observed = Observation()
        val scene = ImageComposeScene(width = 640, height = 4700, density = Density(1f)) {
            SharedConnectionTheme {
                CompositionLocalProvider(LocalSharedHistoryLayoutObserver provides observed) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        SharedHistorySection(HistoryPresentation.from(state, page), selected, busy,
                            { selected = it; selections++ }, { renames += it }, { page = it })
                    }
                }
            }
        }
        try {
            render(scene, "history-pages-first")
            assertEquals(20, observed.layout!!.cards.size)
            click(scene, observed.pageActions.getValue("older").center)
            render(scene, "history-pages-second")
            assertEquals(1, page)
            val node = HistoryPresentation.from(state, page).nodes.first()
            val cardPoint = observed.rootPoint(observed.layout!!.cards.getValue(node.id).center)
            click(scene, cardPoint)
            render(scene, "history-page-selected")
            assertEquals(node.snapshotId, selected)
            assertEquals(1, selections)
            click(scene, observed.renamePoint(node.id))
            assertEquals(listOf(node.snapshotId), renames)
            busy = true
            render(scene, "history-page-busy")
            click(scene, cardPoint)
            click(scene, observed.renamePoint(node.id))
            click(scene, observed.pageActions.getValue("newer").center)
            assertEquals(1, selections); assertEquals(1, renames.size); assertEquals(1, page)
            busy = false
            render(scene, "history-page-unblocked")
            repeat(2) {
                click(scene, observed.pageActions.getValue("newer").center)
                render(scene, "history-pages-return-$it")
                assertEquals(0, page)
                assertEquals(node.snapshotId, selected, "changing page must not silently change the restore target")
                click(scene, observed.pageActions.getValue("older").center)
                render(scene, "history-pages-selected-return-$it")
                assertEquals(1, page); assertEquals(node.snapshotId, selected)
                assertTrue(node.id in observed.layout!!.cards)
            }
            assertEquals(24, state.graph.commits.size)
            assertTrue(state.currentFiles.isEmpty())
        } finally { scene.close() }
    }

    @Test fun emptyHistoryHasNoInventedCardsOrEdges() {
        val state = SyncHistory(CommitGraph(emptyMap(), emptyMap()), null, emptyMap(), "", null, emptyMap())
        val observed = Observation()
        val scene = ImageComposeScene(width = 320, height = 500, density = Density(1f)) {
            SharedConnectionTheme {
                CompositionLocalProvider(LocalSharedHistoryLayoutObserver provides observed) {
                    SharedHistorySection(HistoryPresentation.from(state, 0), null, false, {}, {}, {})
                }
            }
        }
        try {
            render(scene, "history-empty")
            assertTrue(observed.layout!!.cards.isEmpty()); assertTrue(observed.layout!!.arrows.isEmpty())
            assertEquals(126f, observed.layout!!.height)
        } finally { scene.close() }
    }

    @Test fun matchingContentWithoutBaselineRendersSyncedCompactPairAtEveryWidth() {
        val remote = commit("remote", "2026-10-03T22:59:00.123Z")
        for (width in listOf(1165, 750, 500, 320)) {
            val state = SyncHistory(CommitGraph(mapOf(remote.id to remote), emptyMap()), null, oldFiles,
                "本机当前", SaveSummary(mapOf("game1" to "2026-10-03T22:59:00Z"), 1, 1), emptyMap())
            val presentation = HistoryPresentation.from(state, 0)
            val observed = Observation()
            val scene = ImageComposeScene(width = width, height = 600, density = Density(1f)) {
                SharedConnectionTheme {
                    CompositionLocalProvider(LocalSharedHistoryLayoutObserver provides observed) {
                        SharedHistorySection(presentation, remote.snapshot.id, false, {}, {}, {})
                    }
                }
            }
            try {
                val pixels = render(scene, "history-synced-no-baseline-$width")
                val layout = assertNotNull(observed.layout)
                assertEquals(maxOf(440f, width.toFloat()), layout.width)
                val cloud = layout.cards.getValue("remote")
                val local = layout.cards.getValue("local-current")
                assertEquals(cloud.center.y, local.center.y)
                assertEquals(16f, layout.width - local.right)
                assertTrue(layout.height <= maxOf(cloud.height, local.height) + 57f)
                assertFalse(presentation.nodes.single { it.id == "local-current" }.dirty)
                val relation = layout.arrows.single()
                assertTrue(relation.edge.sameContent)
                assertEquals(32f, local.left - cloud.right)
                assertEquals("local-current", relation.edge.fromId)
                assertEquals("remote", relation.edge.toId)
                assertEquals(local.left, relation.start.x)
                assertTrue(relation.tip.x > cloud.right && relation.tip.x < relation.start.x)
                assertTrue(relation.headLeft.x > relation.tip.x && relation.headRight.x > relation.tip.x)
                assertColorNear(pixels, observed.rootPoint(relation.tip), 0x006877, "single left-facing arrowhead must be painted")
                val middle = (relation.start + relation.tip) / 2f
                assertColorNear(pixels, observed.rootPoint(middle), 0x006877, "unlabelled connector must remain continuous")
            } finally { scene.close() }
        }
    }

    private fun history(): SyncHistory {
        val commits = listOf(commit("newest", "2026-10-10T00:00:00Z", listOf("middle"),
                files = mapOf("game1/game.dat" to "c".repeat(64))),
            commit("middle", "2026-10-08T00:00:00Z", listOf("base"),
                files = mapOf("game1/game.dat" to "d".repeat(64))),
            commit("base", "2026-10-07T00:00:00Z"))
        return SyncHistory(CommitGraph(commits.associateBy { it.id }, emptyMap()), "base", newFiles,
            longName, SaveSummary(mapOf("game1" to "2026-10-09T00:00:00Z"), 28, 1),
            mapOf("snapshot-newest" to longName), "restore")
    }

    private fun commit(id: String, time: String, parents: List<String> = emptyList(),
        files: Map<String, String> = oldFiles) = AppendCommit(
        id = id, parents = parents, objectBytes = 1,
        operation = CommitOperationMetadata(device = DeviceIdentity("source-device", "MacBook"), preparedAt = time),
        snapshot = Manifest(game = "shattered", profile = "test", version = "922", id = "snapshot-$id",
            parent = null, sha256 = "c".repeat(64), files = files, saveVersions = SaveVersions(mapOf("game1" to 922)),
            metadata = SnapshotMetadata(device = DeviceIdentity("source-device", if (id == "newest" || id == "base")
                "A real device label with enough text to wrap within the readable card" else "MacBook"),
                capturedAt = "2026-10-15T00:00:00Z", fileModifiedAt = mapOf("game1/game.dat" to time))))

    private class Observation : SharedHistoryLayoutObserver {
        var layout: SharedHistoryCanvasLayout? = null
        var origin = Offset.Zero
        var viewport: Rect? = null
        var scroll: ScrollState? = null
        val pageActions = mutableMapOf<String, Rect>()
        override fun canvas(positionInRoot: Offset, layout: SharedHistoryCanvasLayout) {
            origin = positionInRoot; this.layout = layout
        }
        override fun viewport(boundsInRoot: Rect, scrollState: ScrollState) {
            viewport = boundsInRoot; scroll = scrollState
        }
        override fun pageAction(name: String, boundsInRoot: Rect) { pageActions[name] = boundsInRoot }
        fun rootPoint(point: Offset) = point + origin
        fun renamePoint(id: String): Offset = layout!!.renameActions.getValue(id).let {
            rootPoint(Offset(it.right - 20, it.center.y))
        }
    }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }

    private fun nearColor(actual: Int, expected: Int): Boolean = listOf(0, 8, 16).all { shift ->
        kotlin.math.abs(((actual shr shift) and 0xff) - ((expected shr shift) and 0xff)) <= 22
    }

    private fun assertColorNear(image: BufferedImage, point: Offset, color: Int, message: String) {
        assertTrue((-2..2).any { dx -> (-2..2).any { dy ->
            val x = point.x.toInt() + dx; val y = point.y.toInt() + dy
            x in 0 until image.width && y in 0 until image.height && nearColor(image.getRGB(x, y) and 0xffffff, color)
        } }, message)
    }

    private fun render(scene: ImageComposeScene, name: String): BufferedImage {
        repeat(2) { scene.render(frame++ * 16_000_000).close() }
        return scene.render(frame++ * 16_000_000).use { image ->
            val file = Path.of("build/reports/shared-history", "$name.png")
            Files.createDirectories(file.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use {
                Files.write(file, it.bytes)
                ImageIO.read(ByteArrayInputStream(it.bytes))
            }
        }
    }
}
