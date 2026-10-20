@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.jetbrains.skia.EncodedImageFormat
import pdsync.i18n.SharedMessages
import pdsync.i18n.UiLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Production body and lifecycle with synthetic observations; no save or service writes. */
class SharedBranchChoiceDialogRenderTest {
    private var frame = 0L
    private fun ready() = SharedBranchChoiceState(Any(), listOf(
        SharedBranchChoice("snapshot-a", "Expedition A", "Desktop · 6 files"),
        SharedBranchChoice("snapshot-b", "Expedition B", "Android · 4 files")),
        pageActive = true, connected = true, observationCurrent = true,
        overwriteSupported = true, mergeSupported = true)

    private class Host(initial: SharedBranchChoiceState) : SharedBranchChoiceActions {
        var state by mutableStateOf(initial)
        var visible by mutableStateOf(true)
        var actions by mutableStateOf<SharedBranchChoiceActions>(this)
        lateinit var interactions: SharedBranchChoiceInteractions
        val calls = mutableListOf<String>()
        override fun select(snapshotId: String) {
            calls += "select:$snapshotId"; state = state.copy(selectedId = snapshotId)
        }
        override fun back() { calls += "back"; visible = false; state = state.copy(pageActive = false) }
        override fun refresh() = start("refresh")
        override fun overwrite(snapshotId: String) = start("overwrite:$snapshotId")
        override fun merge() = start("merge")
        override fun preserveLocal() = start("preserve")
        private fun start(action: String) {
            // Hosts retain the synchronous operation lock used by the real platforms.
            if (!state.canInteract) return
            calls += action; state = state.copy(busy = true)
        }
    }

    @Test fun explicitSelectionConfirmationCancelAndRepeatKeepOriginalWriteSemantics() {
        val host = Host(ready())
        val scene = scene(host)
        try {
            render(scene, "chooser")
            assertTrue(host.calls.isEmpty(), "rendering never performs a read or write")
            click(scene, "Expedition B")
            render(scene, "selected")
            assertEquals("snapshot-b", host.state.selectedId)
            assertEquals(listOf("select:snapshot-b"), host.calls)

            click(scene, SharedMessages.HANDLE_CONFLICT.text())
            render(scene, "confirmation")
            val cancelled = host.interactions.presentation!!.token
            assertTrue(SharedMessages.CONFLICT_TITLE.text() in modalTexts(scene))
            assertFalse(SharedMessages.CHOOSE_CLOUD_SAVE.text() in modalTexts(scene),
                "the confirmation replaces the chooser inside the same modal body")
            click(scene, SharedMessages.CANCEL.text())
            render(scene, "confirmation-cancelled")
            assertNull(host.interactions.presentation)
            host.interactions.confirm(cancelled)
            assertEquals(listOf("select:snapshot-b"), host.calls)
            assertEquals("snapshot-b", host.state.selectedId)

            click(scene, SharedMessages.HANDLE_CONFLICT.text())
            render(scene, "confirmation-reopened")
            val current = host.interactions.presentation!!.token
            assertTrue(current > cancelled)
            click(scene, SharedMessages.USE_SELECTED.text())
            repeat(3) { host.interactions.confirm(current); host.interactions.merge(current) }
            assertEquals(listOf("select:snapshot-b", "overwrite:snapshot-b"), host.calls)
            render(scene, "overwrite-busy")
            for (label in listOf(SharedMessages.CANCEL.text(), SharedMessages.HANDLE_CONFLICT.text())) {
                assertTrue(button(scene, label).config.contains(SemanticsProperties.Disabled))
                click(scene, label)
            }
            host.interactions.dismissBranchDialog()
            assertEquals(2, host.calls.size, "busy chooser cannot dismiss or submit again")
        } finally { scene.close() }

        val merge = Host(ready())
        val mergeScene = scene(merge)
        try {
            render(mergeScene, "merge-chooser")
            click(mergeScene, SharedMessages.HANDLE_CONFLICT.text())
            render(mergeScene, "merge-confirmation")
            assertTrue(button(mergeScene, SharedMessages.USE_SELECTED.text()).config.contains(SemanticsProperties.Disabled))
            click(mergeScene, SharedMessages.MERGE.text())
            assertEquals(listOf("merge"), merge.calls, "merge does not invent a selected snapshot")
        } finally { mergeScene.close() }
    }

    @Test fun backOutsideRemovalAndReopenDisposeOldConfirmationAndUseLatestActions() {
        val host = Host(ready().copy(selectedId = "snapshot-a"))
        val scene = scene(host)
        try {
            render(scene, "dismiss-chooser")
            host.interactions.primary()
            render(scene, "dismiss-confirmation")
            val old = host.interactions
            val oldToken = old.presentation!!.token
            old.dismissBranchDialog()
            render(scene, "dismiss-inner")
            assertNull(old.presentation)
            assertTrue(host.visible)
            assertTrue(host.calls.isEmpty(), "Back/outside first cancels only the confirmation")
            old.dismissBranchDialog()
            render(scene, "dismiss-outer")
            assertFalse(host.visible)
            assertEquals(listOf("back"), host.calls)
            assertTrue(SharedMessages.SAVE_HISTORY.text() in texts(nodes(scene)),
                "home remains composed after dismissal")
            old.confirm(oldToken); old.merge(oldToken); old.primary(); old.select("snapshot-b")
            old.preserveLocal(); old.dismissBranchDialog()
            assertEquals(listOf("back"), host.calls, "removed modal callbacks are inert")

            host.state = ready().copy(selectedId = "snapshot-a")
            host.visible = true
            render(scene, "fresh-chooser")
            assertNotSame(old, host.interactions)
            assertNull(host.interactions.presentation, "reopening never resurrects a confirmation")
            host.interactions.primary()
            render(scene, "fresh-confirmation")
            old.confirm(oldToken)
            assertEquals(listOf("back"), host.calls)
            val live = host.interactions
            val liveToken = live.presentation!!.token
            host.visible = false
            render(scene, "external-removal")
            live.confirm(liveToken); live.merge(liveToken)
            assertEquals(listOf("back"), host.calls, "external navigation also disposes write callbacks")

            host.visible = true
            render(scene, "actions-original")
            val latestCalls = mutableListOf<String>()
            host.actions = object : SharedBranchChoiceActions by host {
                override fun back() { latestCalls += "latest-back" }
            }
            render(scene, "actions-updated")
            host.interactions.dismissBranchDialog()
            assertEquals(listOf("latest-back"), latestCalls)
            assertEquals(listOf("back"), host.calls)
        } finally { scene.close() }
    }

    @Test fun busyStaleAndRecoveryCapabilitiesRemainFailClosedInTheModal() {
        val host = Host(ready().copy(selectedId = "snapshot-a", canPreserveLocal = true))
        val scene = scene(host)
        try {
            render(scene, "guards-ready")
            host.interactions.primary()
            render(scene, "guards-confirmation")
            val token = host.interactions.presentation!!.token
            host.state = host.state.copy(busy = true)
            render(scene, "guards-busy")
            for (label in listOf(SharedMessages.CANCEL.text(), SharedMessages.USE_SELECTED.text(), SharedMessages.MERGE.text())) {
                assertTrue(button(scene, label).config.contains(SemanticsProperties.Disabled))
                click(scene, label)
            }
            host.interactions.dismissBranchDialog()
            assertNotNull(host.interactions.presentation, "busy keeps the current confirmation locked")
            assertTrue(host.calls.isEmpty())
            host.state = host.state.copy(busy = false, observation = Any())
            render(scene, "guards-new-observation")
            assertNull(host.interactions.presentation)
            host.interactions.confirm(token); host.interactions.merge(token)
            assertTrue(host.calls.isEmpty())

            host.state = host.state.copy(resolutionBlocked = true)
            render(scene, "guards-pending-upload")
            assertTrue(button(scene, SharedMessages.HANDLE_CONFLICT.text()).config.contains(SemanticsProperties.Disabled))
            click(scene, SharedMessages.UPLOAD_KEEP_BOTH.text())
            assertEquals(listOf("preserve"), host.calls)
            host.state = host.state.copy(busy = false, observationCurrent = false, branches = emptyList())
            render(scene, "guards-stale-observation")
            assertTrue(button(scene, SharedMessages.UPLOAD_KEEP_BOTH.text()).config.contains(SemanticsProperties.Disabled))
            click(scene, SharedMessages.COMPARE_AGAIN.text())
            assertEquals(listOf("preserve", "refresh"), host.calls)
        } finally { scene.close() }
    }

    @Test fun detailsStayCollapsedAndPreserveExpansionWhenInnerConfirmationIsCancelled() {
        val host = Host(ready().copy(details = "Synthetic maintenance details"))
        val scene = scene(host)
        try {
            render(scene, "details-collapsed")
            assertFalse(host.state.details in modalTexts(scene))
            click(scene, SharedMessages.DETAILS.text())
            render(scene, "details-expanded")
            assertTrue(host.state.details in modalTexts(scene))
            host.interactions.primary()
            render(scene, "details-confirmation")
            host.interactions.dismissBranchDialog()
            render(scene, "details-return")
            assertTrue(host.state.details in modalTexts(scene))
            host.state = host.state.copy(observation = Any())
            render(scene, "details-new-observation")
            assertFalse(host.state.details in modalTexts(scene))
            assertTrue(host.calls.isEmpty())
        } finally { scene.close() }
    }

    @Test fun bilingualLongListsScrollWithReachableActionsAtNarrowAndLargeFontSizes() {
        val previous = UiLocale.locale()
        try {
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                UiLocale.setLocale(locale)
                for ((width, height, scale) in listOf(Triple(1280, 900, 1f), Triple(390, 640, 1f),
                    Triple(320, 420, 1f), Triple(320, 640, 1.5f), Triple(320, 640, 2f))) {
                    val chinese = locale.language == "zh"
                    val host = Host(ready().copy(branches = (1..12).map {
                        SharedBranchChoice("snapshot-$it",
                            if (chinese) "测试存档 $it · 很长的设备与存档名称" else "Expedition $it with a very long device and save name",
                            if (chinese) "测试设备 · 6 个文件" else "Synthetic device · 6 files")
                    }, selectedId = "snapshot-1"))
                    val scene = scene(host, width, height, scale)
                    val id = "${locale.language}-$width-$height-$scale"
                    try {
                        render(scene, "layout-$id")
                        assertModalLayout(scene, width, height)
                        val before = listOf(SharedMessages.CANCEL.text(), SharedMessages.HANDLE_CONFLICT.text())
                            .associateWith { button(scene, it).boundsInRoot }
                        val scroll = modalNodes(scene).single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                        assertTrue(scroll.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!.maxValue() > 0f)
                        scene.sendPointerEvent(PointerEventType.Scroll, Offset(width / 2f, before.values.first().top - 30f),
                            scrollDelta = Offset(0f, 20f))
                        render(scene, "scrolled-$id")
                        assertTrue(modalNodes(scene).single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                            .config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!.value() > 0f)
                        before.forEach { (label, bounds) -> assertEquals(bounds, button(scene, label).boundsInRoot) }
                        assertTrue(host.calls.isEmpty())
                        click(scene, SharedMessages.HANDLE_CONFLICT.text())
                        render(scene, "confirmation-$id")
                        assertModalLayout(scene, width, height)
                        for (label in listOf(SharedMessages.CANCEL.text(), SharedMessages.USE_SELECTED.text(), SharedMessages.MERGE.text())) {
                            assertFalse(button(scene, label).config.contains(SemanticsProperties.Disabled))
                        }
                        click(scene, SharedMessages.CANCEL.text())
                        render(scene, "cancelled-$id")
                        assertTrue(host.calls.isEmpty(), "layout, scrolling and cancellation never write")
                    } finally { scene.close() }
                }
            }
        } finally { UiLocale.setLocale(previous) }
    }

    private fun scene(host: Host, width: Int = 390, height: Int = 900, scale: Float = 1f) =
        ImageComposeScene(width = width, height = height, density = Density(1f, scale)) {
            SharedConnectionTheme {
                Box(Modifier.fillMaxSize().background(SharedUiColors.background)) {
                    SharedHomeScreen(SharedHomeState(null, "Synthetic home", pageActive = false),
                        object : SharedHomeActions {
                            override fun settings() = Unit
                            override fun upload() = Unit
                            override fun restore() = Unit
                            override fun launch() = Unit
                            override fun selectSnapshot(snapshotId: String) = Unit
                            override fun rename(snapshotId: String?) = Unit
                            override fun changeHistoryPage(page: Int) = Unit
                        })
                    if (host.visible) {
                        val interactions = rememberSharedBranchChoiceDialogInteractions(host.state, host.actions)
                        host.interactions = interactions
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f))
                            .padding(horizontal = 19.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                            SharedBranchChoiceDialogBody(host.state, interactions,
                                Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = (height - 48).dp)
                                    .testTag("branch-dialog"))
                        }
                    }
                }
            }
        }

    private fun assertModalLayout(scene: ImageComposeScene, width: Int, height: Int) {
        val modal = modalRoot(scene).boundsInRoot
        assertTrue(modal.left >= 19f && modal.right <= width - 19f && modal.width <= 480f)
        assertTrue(modal.top >= 24f && modal.bottom <= height - 24f)
        val labels = if (SharedMessages.CONFLICT_TITLE.text() in modalTexts(scene))
            listOf(SharedMessages.CANCEL.text(), SharedMessages.USE_SELECTED.text(), SharedMessages.MERGE.text())
        else listOf(SharedMessages.CANCEL.text(), SharedMessages.HANDLE_CONFLICT.text())
        val bounds = labels.map { button(scene, it).boundsInRoot }
        bounds.forEach {
            assertTrue(it.width > 0f && it.height >= 42f && it.left >= modal.left && it.right <= modal.right)
            assertTrue(it.top >= modal.top && it.bottom <= modal.bottom, "footer is fully visible: $it in $modal")
        }
        bounds.zipWithNext().forEach { (a, b) ->
            assertTrue(a.right <= b.left || a.bottom <= b.top, "footer buttons never overlap")
        }
        for (node in modalNodes(scene)) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.forEach { layout ->
                assertFalse(layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines,
                    "text stays readable: ${layout.layoutInput.text.text}")
                for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line))
                    assertTrue(layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                }
            }
        }
    }

    private fun render(scene: ImageComposeScene, name: String) {
        repeat(4) { scene.render(++frame * 16_000_000L).close() }
        scene.render(++frame * 16_000_000L).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                val file = Path.of("build/reports/upload-dialog", "$name.png")
                Files.createDirectories(file.parent); Files.write(file, data.bytes)
            }
        }
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val point = button(scene, label).boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }
    private fun button(scene: ImageComposeScene, label: String) = modalNodes(scene).single { node ->
        node.config.contains(SemanticsActions.OnClick) && label in texts(descendants(node))
    }
    private fun modalRoot(scene: ImageComposeScene) = nodes(scene).single {
        it.config.getOrNull(SemanticsProperties.TestTag) == "branch-dialog"
    }
    private fun modalNodes(scene: ImageComposeScene) = descendants(modalRoot(scene))
    private fun modalTexts(scene: ImageComposeScene) = texts(modalNodes(scene))
    private fun texts(nodes: List<SemanticsNode>) = nodes.flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty()
    }.map { it.text }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        return descendants(owner.unmergedRootSemanticsNode)
    }
}
