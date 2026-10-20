@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
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
import pdsync.i18n.UiLocale
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Synthetic verified history enters the actual shared component used by desktop and Android. */
class SharedHistoryRecordOrderRenderTest {
    private var frame = 0L
    private val oldFiles = mapOf("game1/game.dat" to "a".repeat(64))
    private val otherFiles = mapOf("game1/game.dat" to "b".repeat(64))
    private val dirtyFiles = mapOf("game1/game.dat" to "d".repeat(64))
    private val source = DeviceIdentity("source-device", "Desktop")
    private val recordTimes = mapOf(
        "original" to "2026-10-18T10:00:00Z",
        "branch" to "2026-10-19T10:00:00Z",
        "resolution" to "2026-10-20T10:00:00Z",
    )

    @Test fun resolutionUsesItsOwnRecordTimeAndMatchingLocalShowsTheSameTimeInBothLanguages() {
        val previous = UiLocale.locale()
        try {
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                UiLocale.setLocale(locale)
                for ((width, scale) in listOf(1165 to 1f, 320 to 1f, 320 to 1.5f, 320 to 2f)) {
                    val state = resolutionHistory(chinese = locale.language == "zh")
                    val view = HistoryPresentation.from(state, 0)
                    val observed = Observation()
                    val scene = scene(width, 1800, scale, observed) {
                        SharedHistorySection(view, state.defaultSnapshot, false, {}, {}, {})
                    }
                    try {
                        render(scene, "record-order-${locale.language}-$width-$scale")
                        val layout = assertNotNull(observed.layout)
                        assertEquals(listOf("resolution", "branch", "original"),
                            view.nodes.filter { it.column == 0 }.map { it.id })
                        assertEquals(3, view.cloudCount, "resolution does not collapse the reused snapshot's original record")
                        recordTimes.forEach { (id, time) ->
                            assertEquals(HistoryPresentation.time(time), view.nodes.single { it.id == id }.time)
                            assertTrue(HistoryPresentation.time(time) in visibleTexts(scene),
                                "the actual component renders $id's record time")
                        }
                        val local = view.nodes.single { it.id == "local-current" }
                        assertEquals(HistoryPresentation.time(recordTimes.getValue("resolution")), local.time)
                        assertEquals(2, visibleTexts(scene).count { it == local.time },
                            "cloud record and matching local card actually display the same time")
                        assertFalse(local.dirty)
                        assertEquals(layout.cards.getValue("resolution").center.y,
                            layout.cards.getValue("local-current").center.y)
                        assertCloudTopOrder(layout, listOf("resolution", "branch", "original"))
                        assertEquals(setOf(
                            HistoryEdge("original", "branch"),
                            HistoryEdge("original", "resolution"),
                            HistoryEdge("branch", "resolution"),
                            HistoryEdge("resolution", "local-current"),
                        ), layout.arrows.map { it.edge }.toSet())
                        assertArrowOutsideTargets(layout)
                        val restore = layout.arrows.single { it.edge.toId == "local-current" }
                        assertTrue(restore.tip.x > restore.start.x, "the restore arrow continues to point toward local")
                        assertEquals(state.graph.commits.getValue("original").snapshot,
                            state.graph.commits.getValue("resolution").snapshot,
                            "this really is a resolution record retaining the older snapshot")
                        assertEquals("2026-10-01T10:00:00Z", state.summary!!.slots.getValue("game1"))

                        if (width == 320) {
                            val viewport = assertNotNull(observed.viewport)
                            assertTrue(viewport.left >= 0f && viewport.right <= width)
                            assertTrue(assertNotNull(observed.scroll).maxValue > 0)
                            val before = observed.origin
                            scene.sendPointerEvent(PointerEventType.Scroll, viewport.center, scrollDelta = Offset(80f, 0f))
                            render(scene, "record-order-${locale.language}-$width-$scale-local")
                            assertTrue(observed.origin.x < before.x, "the existing horizontal scroll exposes the local record")
                            assertEquals(observed.scroll!!.maxValue, observed.scroll!!.value,
                                "the local screenshot shows the complete right-hand card")
                            assertEquals(layout.cards, observed.layout!!.cards)
                        }
                    } finally { scene.close() }
                }
            }
        } finally { UiLocale.setLocale(previous) }
    }

    @Test fun dirtyLocalRetainsActualTimeAndAllThreeBaselineArrowMeaningsSurviveRecordOrdering() {
        for (direction in listOf("upload", "restore", null)) {
            val state = resolutionHistory().copy(
                currentFiles = dirtyFiles,
                summary = SaveSummary(mapOf("game1" to "2026-10-20T11:00:00Z"), 1, 1),
                baselineDirection = direction,
            )
            val view = HistoryPresentation.from(state, 0)
            val observed = Observation()
            val scene = scene(640, 1800, 1f, observed) {
                SharedHistorySection(view, null, false, {}, {}, {})
            }
            try {
                render(scene, "record-order-dirty-${direction ?: "content-match"}")
                val layout = assertNotNull(observed.layout)
                val dirty = view.nodes.single { it.id == "local-current" }
                assertTrue(dirty.dirty)
                assertEquals(HistoryPresentation.time("2026-10-20T11:00:00Z"), dirty.time)
                assertEquals(HistoryPresentation.time(recordTimes.getValue("resolution")),
                    view.nodes.single { it.id == "local-baseline" }.time)
                assertTrue(layout.cards.getValue("local-current").bottom < layout.cards.getValue("resolution").top)
                assertEquals(layout.cards.getValue("resolution").center.y,
                    layout.cards.getValue("local-baseline").center.y)
                val expected = when (direction) {
                    "restore" -> HistoryEdge("resolution", "local-baseline")
                    "upload" -> HistoryEdge("local-baseline", "resolution")
                    else -> HistoryEdge("local-baseline", "resolution", sameContent = true)
                }
                val cross = layout.arrows.single { it.edge == expected }
                assertEquals(direction != "restore", cross.tip.x < cross.start.x)
                val change = layout.arrows.single { it.edge.dashed }
                assertEquals(HistoryEdge("local-baseline", "local-current", dashed = true), change.edge)
                assertTrue(change.tip.y < change.start.y, "the unsynced change still points from baseline to current")
                assertArrowOutsideTargets(layout)
                assertEquals(3, state.graph.commits.size)
            } finally { scene.close() }
        }
    }

    @Test fun equalRecordTimeResolution92StaysAboveParents5And6WithoutReversingTheirArrows() {
        val time = "2026-10-20T12:00:00Z"
        val parent5 = commit("5", time, "2026-10-19T12:00:00Z")
        val parent6 = commit("6", time, "2026-10-20T12:00:00Z", files = otherFiles)
        val child92 = parent5.copy(id = "92", parents = listOf("5", "6"), kind = "resolution")
        for ((index, commits) in listOf(listOf(parent5, parent6, child92),
            listOf(child92, parent6, parent5), listOf(parent6, parent5, child92)).withIndex()) {
            val state = SyncHistory(CommitGraph(commits.associateBy { it.id }, emptyMap()),
                null, emptyMap(), "", null, emptyMap())
            val view = HistoryPresentation.from(state, 0)
            val observed = Observation()
            val scene = scene(640, 1000, 1f, observed) {
                SharedHistorySection(view, null, false, {}, {}, {})
            }
            try {
                render(scene, "record-order-resolution92-tie-$index")
                val layout = assertNotNull(observed.layout)
                assertEquals(listOf("92", "5", "6"), view.nodes.map { it.id },
                    "same-time child precedes parents; unrelated parents keep stable ID order")
                assertCloudTopOrder(layout, listOf("92", "5", "6"))
                assertEquals(setOf(HistoryEdge("5", "92"), HistoryEdge("6", "92")),
                    layout.arrows.map { it.edge }.toSet())
                layout.arrows.forEach { arrow ->
                    assertTrue(arrow.tip.y < arrow.start.y, "the parent-to-child arrow points up to the newer row")
                    assertEquals(layout.cards.getValue("92").center.x, arrow.tip.x)
                }
                assertArrowOutsideTargets(layout)
            } finally { scene.close() }
        }
    }

    @Test fun pagingAndReadRefreshKeepRecordOrderCompletePairsAndTheActualSelectedSnapshot() {
        val start = Instant.parse("2026-10-01T00:00:00Z")
        fun record(index: Int) = commit("record-%02d".format(index), start.plusSeconds(index * 3600L).toString(),
            start.plusSeconds((30 - index) * 3600L).toString(),
            files = mapOf("game1/game.dat" to "%064x".format(index + 1)))
        val commits = (0..24).map(::record)
        val paired = commits[5]
        var state by mutableStateOf(SyncHistory(
            CommitGraph(commits.associateBy { it.id }, emptyMap()), paired.id, paired.snapshot.files, "",
            SaveSummary(mapOf("game1" to "2026-09-01T00:00:00Z"), 1, 1), emptyMap(), "upload"))
        var page by mutableStateOf(0)
        var selected by mutableStateOf<String?>(null)
        var loading by mutableStateOf(false)
        val calls = mutableListOf<String>()
        val observed = Observation()
        val scene = scene(640, 5200, 1f, observed) {
            SharedHistorySection(HistoryPresentation.from(state, page), selected, false,
                { selected = it; calls += "select:$it" }, { calls += "rename:$it" }, { page = it },
                loading = loading, onRefresh = { loading = true; calls += "refresh" }, refreshEnabled = true)
        }
        try {
            render(scene, "record-order-pages-first")
            val first = HistoryPresentation.from(state, page)
            assertEquals((24 downTo 5).map { "record-%02d".format(it) },
                first.nodes.filter { it.column == 0 }.map { it.id })
            assertEquals(21, observed.layout!!.cards.size, "20 complete rows include the local pair on the last row")
            assertEquals(observed.layout!!.cards.getValue(paired.id).center.y,
                observed.layout!!.cards.getValue("local-current").center.y)
            click(scene, observed.point(observed.layout!!.cards.getValue(paired.id).center))
            assertEquals(paired.snapshot.id, selected)
            click(scene, observed.pages.getValue("older").center)
            render(scene, "record-order-pages-older")
            assertEquals(1, page)
            assertEquals((4 downTo 0).map { "record-%02d".format(it) }, observed.layout!!.cards.keys.toList())
            assertTrue(observed.layout!!.arrows.isEmpty(), "off-page endpoints never gain substitute arrows")

            click(scene, observed.header.getValue("refresh").center)
            render(scene, "record-order-pages-loading")
            repeat(3) { click(scene, observed.header.getValue("refresh").center) }
            assertEquals(listOf("select:${paired.snapshot.id}", "refresh"), calls)
            val added = record(25)
            state = state.copy(graph = CommitGraph((commits + added).reversed().associateBy { it.id }, emptyMap()))
            loading = false
            render(scene, "record-order-pages-refreshed")
            assertEquals(1, page)
            assertEquals(paired.snapshot.id, selected, "read refresh never replaces the selected restore target")
            val refreshed = HistoryPresentation.from(state, page)
            assertEquals((5 downTo 0).map { "record-%02d".format(it) },
                refreshed.nodes.filter { it.column == 0 }.map { it.id })
            assertEquals(observed.layout!!.cards.getValue(paired.id).center.y,
                observed.layout!!.cards.getValue("local-current").center.y,
                "a pair moves across the page boundary together")
            assertEquals(listOf(HistoryEdge("local-current", paired.id)), refreshed.edges)
            repeat(2) {
                click(scene, observed.pages.getValue("newer").center)
                render(scene, "record-order-pages-newer-$it")
                assertEquals(0, page)
                assertEquals("record-25", HistoryPresentation.from(state, page).nodes.first().id)
                assertEquals(paired.snapshot.id, selected)
                click(scene, observed.pages.getValue("older").center)
                render(scene, "record-order-pages-back-$it")
                assertEquals(1, page)
                assertEquals(paired.snapshot.id, selected)
            }
            assertEquals(26, state.graph.commits.size)
            assertEquals(paired.snapshot.files, state.currentFiles)
        } finally { scene.close() }
    }

    private fun resolutionHistory(chinese: Boolean = true): SyncHistory {
        val original = commit("original", recordTimes.getValue("original"), "2026-10-19T12:00:00Z")
        val branch = commit("branch", recordTimes.getValue("branch"), "2026-10-20T12:00:00Z",
            parents = listOf(original.id), files = otherFiles)
        val resolution = original.copy(id = "resolution", parents = listOf(original.id, branch.id),
            kind = "resolution", operation = operation(recordTimes.getValue("resolution")))
        return SyncHistory(CommitGraph(listOf(branch, original, resolution).associateBy { it.id }, emptyMap()),
            resolution.id, oldFiles, "", SaveSummary(mapOf("game1" to "2026-10-01T10:00:00Z"), 1, 1),
            mapOf(original.snapshot.id to if (chinese) "远征记录" else "Expedition",
                branch.snapshot.id to if (chinese) "另一条进度" else "Other progress"), "restore")
    }

    private fun operation(time: String) = CommitOperationMetadata(device = source, preparedAt = time)

    private fun commit(id: String, recordedAt: String, modifiedAt: String,
        parents: List<String> = emptyList(), files: Map<String, String> = oldFiles) = AppendCommit(
        id = id, parents = parents, objectBytes = 1, operation = operation(recordedAt),
        snapshot = Manifest(game = "shattered", profile = "test", version = "922", id = "snapshot-$id",
            parent = null, sha256 = "c".repeat(64), files = files, saveVersions = SaveVersions(mapOf("game1" to 922)),
            metadata = SnapshotMetadata(device = source, capturedAt = modifiedAt,
                fileModifiedAt = mapOf("game1/game.dat" to modifiedAt))))

    private fun scene(width: Int, height: Int, scale: Float, observed: Observation,
        content: @androidx.compose.runtime.Composable () -> Unit) =
        ImageComposeScene(width = width, height = height, density = Density(1f, scale)) {
            SharedConnectionTheme {
                CompositionLocalProvider(LocalSharedHistoryLayoutObserver provides observed) {
                    Column(Modifier.fillMaxSize().background(Color.White).padding(12.dp)) { content() }
                }
            }
        }

    private fun assertCloudTopOrder(layout: SharedHistoryCanvasLayout, ids: List<String>) {
        ids.zipWithNext().forEach { (newer, older) ->
            assertTrue(layout.cards.getValue(newer).bottom < layout.cards.getValue(older).top,
                "$newer must appear physically above $older")
        }
    }

    private fun assertArrowOutsideTargets(layout: SharedHistoryCanvasLayout) {
        layout.arrows.forEach { arrow ->
            val target = layout.cards.getValue(arrow.edge.toId)
            assertFalse(target.contains(arrow.tip), "arrow tip outside ${arrow.edge.toId}")
            assertFalse(target.contains(arrow.headLeft), "left arrow head outside ${arrow.edge.toId}")
            assertFalse(target.contains(arrow.headRight), "right arrow head outside ${arrow.edge.toId}")
        }
    }

    private class Observation : SharedHistoryLayoutObserver {
        var layout: SharedHistoryCanvasLayout? = null
        var origin = Offset.Zero
        var viewport: Rect? = null
        var scroll: ScrollState? = null
        val pages = mutableMapOf<String, Rect>()
        val header = mutableMapOf<String, Rect>()
        override fun canvas(positionInRoot: Offset, layout: SharedHistoryCanvasLayout) {
            origin = positionInRoot; this.layout = layout
        }
        override fun viewport(boundsInRoot: Rect, scrollState: ScrollState) {
            viewport = boundsInRoot; scroll = scrollState
        }
        override fun pageAction(name: String, boundsInRoot: Rect) { pages[name] = boundsInRoot }
        override fun headerElement(name: String, boundsInRoot: Rect) { header[name] = boundsInRoot }
        fun point(offset: Offset) = origin + offset
    }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }

    private fun visibleTexts(scene: ImageComposeScene): List<String> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        return descendants(owner.unmergedRootSemanticsNode).flatMap {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty()
        }.map { it.text }
    }

    private fun render(scene: ImageComposeScene, name: String) {
        repeat(3) { scene.render(++frame * 16_000_000).close() }
        scene.render(++frame * 16_000_000).use { image ->
            val file = Path.of("build/reports/shared-history-record-order", "$name.png")
            Files.createDirectories(file.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(file, it.bytes) }
        }
    }
}
