@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pdsync.OnboardingKind

/** Actual shared appreciation content, semantic state, and pointer dispatch. All image data is synthetic. */
class SharedAppreciationRenderTest {
    private var frame = 0L
    private val widths = listOf(320, 500, 1165)

    private class Host(image: ImageBitmap) {
        var appreciation by mutableStateOf(SharedAppreciationState(
            username = "layout-user", authenticated = true, expiry = "有效期 未设置",
            accessStatus = "", message = "", image = image, busy = false, canRefresh = true,
        ))
        var imageSaveBusy by mutableStateOf(false)
        var imageSaveMessage by mutableStateOf("")
        var saves = 0
        var copies = 0
        var backs = 0
        val finishes = mutableListOf<SharedComparisonCompletion>()
        val save: () -> Unit = { saves++ }
    }

    @Test fun desktopOmitsSaveAndRefreshWhileImageCopyAndReturnStayAvailableAtEveryWidth() {
        for (width in widths) {
            val host = Host(syntheticImage())
            val scene = scene(width, host, androidSave = false)
            try {
                render(scene, "appreciation-desktop-$width")
                val nodes = semanticsNodes(scene)
                assertTrue(hasImage(nodes))
                assertNoRefresh(nodes)
                assertFalse(texts(nodes).any { it == "保存图片" || it == "保存中…" })
                assertEquals(2, nodes.count { it.config.contains(SemanticsActions.OnClick) },
                    "Desktop contains only Copy username and Return, with no save replacement")
                val copy = button(nodes, "复制用户名")
                val back = button(nodes, "返回")
                assertEnabled(copy, true)
                assertWithinViewport(copy.boundsInRoot, width)
                assertWithinViewport(back.boundsInRoot, width)
                click(scene, copy.boundsInRoot.center)
                click(scene, back.boundsInRoot.center)
                assertEquals(1, host.copies)
                assertEquals(1, host.backs)
                assertEquals(0, host.saves)

                // A shared state update must not accidentally expose an Android-only capability.
                host.imageSaveBusy = true
                host.imageSaveMessage = "图片已保存。"
                host.appreciation = host.appreciation.copy(image = null, message = "赞赏码暂时无法加载。")
                render(scene, "appreciation-desktop-missing-$width")
                val missing = semanticsNodes(scene)
                assertFalse(hasImage(missing))
                assertNoRefresh(missing)
                assertFalse(texts(missing).any { it == "保存图片" || it == "保存中…" || it == "图片已保存。" })
                assertTrue("赞赏码暂时无法加载。" in texts(missing))
            } finally { scene.close() }
        }
    }

    @Test fun androidSaveTracksLoadedBusyMissingAndReturnedStateWithoutDispatchingDisabledClicks() {
        for (width in widths) {
            val original = syntheticImage()
            val host = Host(original)
            val scene = scene(width, host, androidSave = true)
            try {
                render(scene, "appreciation-android-ready-$width")
                val ready = semanticsNodes(scene)
                assertTrue(hasImage(ready))
                assertNoRefresh(ready)
                val save = button(ready, "保存图片")
                assertEnabled(save, true)
                assertWithinViewport(save.boundsInRoot, width)
                assertTrue(save.boundsInRoot.height >= 44f, "Save keeps a usable touch target at $width")
                assertEquals(0, host.saves, "Composition must never start a save")
                click(scene, save.boundsInRoot.center)
                assertEquals(1, host.saves)

                host.imageSaveBusy = true
                render(scene, "appreciation-android-saving-$width")
                val saving = semanticsNodes(scene)
                val savingButton = button(saving, "保存中…")
                assertEnabled(savingButton, false)
                assertTrue(hasImage(saving), "Opening the destination picker must not remove the public image")
                click(scene, savingButton.boundsInRoot.center)
                assertEquals(1, host.saves, "A second pointer click cannot enqueue another save")
                assertEnabled(button(saving, "返回"), true)

                host.imageSaveBusy = false
                host.imageSaveMessage = "图片已保存。"
                render(scene, "appreciation-android-returned-$width")
                val returned = semanticsNodes(scene)
                assertTrue(hasImage(returned))
                assertFeedback(returned, "图片已保存。")
                val returnedSave = button(returned, "保存图片")
                assertEnabled(returnedSave, true)
                click(scene, returnedSave.boundsInRoot.center)
                assertEquals(2, host.saves, "Returning from the system picker allows a fresh explicit save")

                host.imageSaveMessage = ""
                host.appreciation = host.appreciation.copy(image = null, message = "赞赏码暂时无法加载。")
                render(scene, "appreciation-android-missing-$width")
                val missing = semanticsNodes(scene)
                val missingSave = button(missing, "保存图片")
                assertFalse(hasImage(missing))
                assertEnabled(missingSave, false)
                click(scene, missingSave.boundsInRoot.center)
                assertEquals(2, host.saves, "Missing bytes cannot be saved")
                assertNoRefresh(missing)

                host.appreciation = host.appreciation.copy(image = original, busy = true, message = "")
                render(scene, "appreciation-android-revalidating-$width")
                val revalidating = semanticsNodes(scene)
                val checkingSave = button(revalidating, "保存图片")
                assertTrue(hasImage(revalidating), "Read-only revalidation retains the displayed image")
                assertEnabled(checkingSave, false)
                click(scene, checkingSave.boundsInRoot.center)
                assertEquals(2, host.saves, "A pending metadata read cannot export a potentially replaced asset")

                host.appreciation = host.appreciation.copy(busy = false)
                host.imageSaveMessage = "" // Cancellation is silent; no extra feedback text.
                render(scene, "appreciation-android-restored-$width")
                val restored = semanticsNodes(scene)
                assertFalse("图片已保存。" in texts(restored))
                val restoredSave = button(restored, "保存图片")
                assertEnabled(restoredSave, true)
                assertWithinViewport(restoredSave.boundsInRoot, width)
                click(scene, restoredSave.boundsInRoot.center)
                assertEquals(3, host.saves)
                click(scene, button(restored, "复制用户名").boundsInRoot.center)
                click(scene, button(restored, "返回").boundsInRoot.center)
                assertEquals(1, host.copies)
                assertEquals(1, host.backs)
                assertNoRefresh(restored)
            } finally { scene.close() }
        }
    }

    @Test fun sameAndEmptyStepFourEmbedImageAndKeepCompletionWithoutAccountOrExtraNavigation() {
        for (width in widths) for (kind in listOf(OnboardingKind.IDENTICAL, OnboardingKind.BOTH_EMPTY)) {
            for (androidSave in listOf(false, true)) {
                val host = Host(syntheticImage())
                host.appreciation = host.appreciation.copy(accessStatus = "private account status",
                    message = "private account refresh error")
                val scene = comparisonScene(width, host, kind, androidSave)
                try {
                    render(scene, "inline-${kind.name.lowercase()}-${if (androidSave) "android" else "desktop"}-$width")
                    val nodes = semanticsNodes(scene)
                    val labels = texts(nodes)
                    assertTrue(hasImage(nodes))
                    assertTrue("赞赏可续期一年" in labels)
                    assertTrue((if (kind == OnboardingKind.IDENTICAL) "进度已同步" else "配置完成") in labels)
                    assertFalse(labels.any { it.contains("layout-user") || it.contains("用户名") || it.contains("有效期") ||
                        it.contains("private account") || it == "详细信息" || it == "返回" || it == "赞赏" })
                    assertFalse(labels.any { it == "上一步" } && kind == OnboardingKind.IDENTICAL)
                    assertNoRefresh(nodes)
                    val finish = button(nodes, "完成")
                    assertEnabled(finish, true)
                    assertWithinViewport(finish.boundsInRoot, width)
                    if (androidSave) {
                        val save = button(nodes, "保存图片")
                        assertEnabled(save, true)
                        assertWithinViewport(save.boundsInRoot, width)
                        click(scene, save.boundsInRoot.center)
                        assertEquals(1, host.saves)
                    } else {
                        assertFalse("保存图片" in labels)
                    }
                    click(scene, finish.boundsInRoot.center)
                    assertEquals(listOf(if (kind == OnboardingKind.IDENTICAL) SharedComparisonCompletion.CONFIRM
                        else SharedComparisonCompletion.EMPTY), host.finishes)
                    assertEquals(0, host.copies)
                    assertEquals(0, host.backs)

                    host.appreciation = host.appreciation.copy(image = null, message = "private account refresh error")
                    render(scene, "inline-missing-${kind.name.lowercase()}-$androidSave-$width")
                    val failed = semanticsNodes(scene)
                    assertTrue("赞赏码暂时无法加载。" in texts(failed))
                    assertFalse(texts(failed).any { it.contains("private account") || it.contains("用户名") })
                    assertEnabled(button(failed, "完成"), true)
                    if (androidSave) assertEnabled(button(failed, "保存图片"), false)
                } finally { scene.close() }
            }
        }
    }

    @Test fun customDavStepFourHasNoAppreciationAndInlineFailureCannotEnableBlockedComparison() {
        val host = Host(syntheticImage())
        val noSlot = comparisonScene(500, host, OnboardingKind.BOTH_EMPTY, androidSave = false, showAppreciation = false)
        try {
            render(noSlot, "custom-comparison-no-appreciation")
            val nodes = semanticsNodes(noSlot)
            assertFalse(hasImage(nodes))
            assertFalse(texts(nodes).any { it.contains("赞赏") || it.contains("用户名") || it == "保存图片" })
            assertEnabled(button(nodes, "完成"), true)
        } finally { noSlot.close() }

        host.appreciation = host.appreciation.copy(image = null, busy = true)
        val blocked = comparisonScene(320, host, null, androidSave = true, comparisonBusy = true)
        try {
            render(blocked, "inline-loading-comparison-blocked")
            assertEnabled(button(semanticsNodes(blocked), "重新检查"), false)
            host.appreciation = host.appreciation.copy(busy = false, message = "image failure")
            render(blocked, "inline-failed-comparison-still-blocked")
            val nodes = semanticsNodes(blocked)
            assertTrue("赞赏码暂时无法加载。" in texts(nodes))
            assertEnabled(button(nodes, "重新检查"), false)
            assertTrue(host.finishes.isEmpty())
        } finally { blocked.close() }
    }

    private fun comparisonScene(width: Int, host: Host, kind: OnboardingKind?, androidSave: Boolean,
        showAppreciation: Boolean = true, comparisonBusy: Boolean = false): ImageComposeScene {
        val actions = object : SharedComparisonActions {
            override fun back() { host.backs++ }
            override fun refresh() = Unit
            override fun selectProgress(choice: SharedProgressChoice) = Unit
            override fun selectRemote(snapshotId: String) = Unit
            override fun finish(mode: SharedComparisonCompletion) { host.finishes += mode }
            override fun handleConflict() = Unit
            override fun upload() = Unit
            override fun openRecovery() = Unit
        }
        return ImageComposeScene(width = width, height = 757, density = Density(1f)) {
            SharedComparisonScreen(SharedComparisonState(kind, cloudVerified = kind != null,
                canCompleteWithoutTransfer = kind == OnboardingKind.IDENTICAL, pageActive = true,
                configured = true, busy = comparisonBusy, game = SharedGameIdentity("private game title", "3.3.8"),
                localDetails = "hidden comparison detail", details = "hidden comparison detail"), actions,
                appreciation = if (showAppreciation) ({
                    SharedInlineAppreciationContent(host.appreciation, false, { host.copies++ },
                        onSaveImage = if (androidSave) host.save else null,
                        imageSaveBusy = host.imageSaveBusy, imageSaveMessage = host.imageSaveMessage)
                }) else null)
        }
    }

    private fun scene(width: Int, host: Host, androidSave: Boolean) =
        ImageComposeScene(width = width, height = 757, density = Density(1f)) {
            SharedConnectionTheme {
                SharedAppShell(title = "赞赏", footer = {
                    SharedActionButton("返回", { host.backs++ }, style = SharedButtonStyle.TEXT)
                }) {
                    SharedForm {
                        SharedAppreciationContent(host.appreciation, { host.copies++ },
                            onSaveImage = if (androidSave) host.save else null,
                            imageSaveBusy = host.imageSaveBusy, imageSaveMessage = host.imageSaveMessage)
                    }
                }
            }
        }

    /** A solid test swatch, deliberately not a real payment code. */
    private fun syntheticImage() = ImageBitmap(24, 24).also {
        Canvas(it).drawRect(Rect(0f, 0f, 24f, 24f), Paint().apply { color = Color(0xff326650) })
    }

    private fun assertNoRefresh(nodes: List<SemanticsNode>) {
        assertFalse(texts(nodes).any { it.contains("刷新") }, "The rendered page must not ask for manual refresh")
    }

    private fun assertFeedback(nodes: List<SemanticsNode>, message: String) {
        val notice = nodes.single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == message } == true }
        assertEquals(LiveRegionMode.Polite, notice.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    private fun hasImage(nodes: List<SemanticsNode>) = nodes.any {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("赞赏码") == true
    }

    private fun assertEnabled(node: SemanticsNode, enabled: Boolean) {
        assertEquals(!enabled, node.config.contains(SemanticsProperties.Disabled))
    }

    private fun assertWithinViewport(bounds: Rect, width: Int) {
        assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.width > 0)
        assertTrue(bounds.top >= 0 && bounds.bottom <= 757 && bounds.height > 0)
    }

    private fun button(nodes: List<SemanticsNode>, label: String): SemanticsNode = nodes.single {
        it.config.contains(SemanticsActions.OnClick) && label in texts(descendants(it))
    }

    private fun texts(nodes: List<SemanticsNode>) = nodes.flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    /** Pinned Compose 1.9.3 exposes its offscreen semantics owner through the scene implementation. */
    private fun semanticsNodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run {
            isAccessible = true; get(scene)
        }
        val root = backing.javaClass.getDeclaredField("mainOwner").run {
            isAccessible = true; get(backing)
        }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        return descendants(owner.unmergedRootSemanticsNode)
    }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }

    private fun render(scene: ImageComposeScene, name: String) {
        repeat(2) { frame += 16_000_000; scene.render(frame).close() }
        frame += 16_000_000
        scene.render(frame).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                val file = Path.of("build/reports/shared-appreciation", "$name.png")
                Files.createDirectories(file.parent)
                Files.write(file, data.bytes)
            }
        }
    }
}
