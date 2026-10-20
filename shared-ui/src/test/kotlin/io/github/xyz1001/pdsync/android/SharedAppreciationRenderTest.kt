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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pdsync.OnboardingKind
import pdsync.i18n.SharedMessages
import pdsync.i18n.UiLocale

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
        var refreshes = 0
        val finishes = mutableListOf<SharedComparisonCompletion>()
        val save: () -> Unit = { saves++ }
        val refresh: () -> Unit = { refreshes++ }
    }

    @Test fun desktopRefreshQueriesExplicitlyWhileImageCopyAndReturnStayAvailableAtEveryWidth() {
        for (width in widths) {
            val host = Host(syntheticImage())
            val scene = scene(width, host, androidSave = false)
            try {
                render(scene, "appreciation-desktop-$width")
                val nodes = semanticsNodes(scene)
                assertTrue(hasImage(nodes))
                assertFalse(texts(nodes).any { it == "保存图片" || it == "保存中…" })
                assertEquals(4, nodes.count { it.config.contains(SemanticsActions.OnClick) },
                    "Desktop contains image expansion, Copy username, Refresh and Return")
                val refresh = button(nodes, "刷新")
                assertEnabled(refresh, true)
                assertWithinViewport(refresh.boundsInRoot, width)
                assertEquals(0, host.refreshes, "Composition must never start a refresh")
                click(scene, refresh.boundsInRoot.center)
                assertEquals(1, host.refreshes)
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
                val missingRefresh = button(missing, "刷新")
                assertEnabled(missingRefresh, true)
                click(scene, missingRefresh.boundsInRoot.center)
                assertEquals(2, host.refreshes, "A missing image must not block querying the latest expiry")
                assertFalse(texts(missing).any { it == "保存图片" || it == "保存中…" || it == "图片已保存。" })
                assertTrue("赞赏码暂时无法加载。" in texts(missing))

                host.appreciation = host.appreciation.copy(busy = true, canRefresh = false)
                render(scene, "appreciation-desktop-refreshing-$width")
                val pendingRefresh = button(semanticsNodes(scene), "刷新")
                assertEnabled(pendingRefresh, false)
                click(scene, pendingRefresh.boundsInRoot.center)
                assertEquals(2, host.refreshes, "A pending query must not dispatch another refresh")

                host.appreciation = host.appreciation.copy(busy = false, canRefresh = true,
                    expiry = "有效期 2027-10-20", message = "")
                render(scene, "appreciation-desktop-refreshed-$width")
                val refreshed = semanticsNodes(scene)
                assertTrue("有效期 2027-10-20" in texts(refreshed))
                assertEnabled(button(refreshed, "刷新"), true)
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
                val refresh = button(ready, "刷新")
                assertEnabled(refresh, true)
                assertWithinViewport(refresh.boundsInRoot, width)
                assertEquals(0, host.refreshes)
                click(scene, refresh.boundsInRoot.center)
                assertEquals(1, host.refreshes)
                val save = button(ready, "保存图片")
                assertEnabled(save, true)
                assertWithinViewport(save.boundsInRoot, width)
                assertTrue(save.boundsInRoot.height >= 42f, "Save keeps a usable touch target at $width")
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
                assertEnabled(button(missing, "刷新"), true)

                host.appreciation = host.appreciation.copy(image = original, busy = true, canRefresh = false, message = "")
                render(scene, "appreciation-android-revalidating-$width")
                val revalidating = semanticsNodes(scene)
                val checkingSave = button(revalidating, "保存图片")
                assertTrue(hasImage(revalidating), "Read-only revalidation retains the displayed image")
                assertEnabled(checkingSave, false)
                click(scene, checkingSave.boundsInRoot.center)
                assertEquals(2, host.saves, "A pending metadata read cannot export a potentially replaced asset")
                val checkingRefresh = button(revalidating, "刷新")
                assertEnabled(checkingRefresh, false)
                click(scene, checkingRefresh.boundsInRoot.center)
                assertEquals(1, host.refreshes, "Refresh is disabled during an active query")

                host.appreciation = host.appreciation.copy(busy = false, canRefresh = true)
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
                val restoredRefresh = button(restored, "刷新")
                assertEnabled(restoredRefresh, true)
                assertWithinViewport(restoredRefresh.boundsInRoot, width)
                click(scene, restoredRefresh.boundsInRoot.center)
                assertEquals(2, host.refreshes)
            } finally { scene.close() }
        }
    }

    @Test fun sameAndEmptyStepFourEmbedImageAndKeepCompletionWithoutAccountOrExtraNavigation() {
        for (width in widths) for (kind in listOf(OnboardingKind.IDENTICAL, OnboardingKind.BOTH_EMPTY)) {
            for (androidSave in listOf(false, true)) {
                val host = Host(syntheticImage())
                host.appreciation = host.appreciation.copy(accessStatus = "private account status",
                    message = "刷新失败，请稍后重试。")
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
                    assertFeedback(nodes, "刷新失败，请稍后重试。")
                    assertFalse(labels.any { it == "上一步" } && kind == OnboardingKind.IDENTICAL)
                    val refresh = button(nodes, "刷新")
                    assertEnabled(refresh, true)
                    assertWithinViewport(refresh.boundsInRoot, width)
                    assertEquals(0, host.refreshes, "Embedding the support panel must not start a refresh")
                    click(scene, refresh.boundsInRoot.center)
                    assertEquals(1, host.refreshes)
                    assertTrue(host.finishes.isEmpty(), "Refreshing account data must not finish the comparison")
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

                    host.appreciation = host.appreciation.copy(image = null, message = "刷新失败，请稍后重试。")
                    render(scene, "inline-missing-${kind.name.lowercase()}-$androidSave-$width")
                    val failed = semanticsNodes(scene)
                    assertFalse(hasImage(failed))
                    assertFalse(texts(failed).any { it.contains("private account") || it.contains("用户名") })
                    assertFeedback(failed, "刷新失败，请稍后重试。")
                    assertEnabled(button(failed, "完成"), true)
                    if (androidSave) assertEnabled(button(failed, "保存图片"), false)
                    val failedRefresh = button(failed, "刷新")
                    assertEnabled(failedRefresh, true)
                    click(scene, failedRefresh.boundsInRoot.center)
                    assertEquals(2, host.refreshes)

                    host.appreciation = host.appreciation.copy(busy = true, canRefresh = false)
                    render(scene, "inline-refreshing-${kind.name.lowercase()}-$androidSave-$width")
                    val pending = semanticsNodes(scene)
                    val pendingRefresh = button(pending, "刷新")
                    assertEnabled(pendingRefresh, false)
                    click(scene, pendingRefresh.boundsInRoot.center)
                    assertEquals(2, host.refreshes)
                    assertEnabled(button(pending, "完成"), true,
                        "Refreshing support information cannot block completion")
                } finally { scene.close() }
            }
        }
    }

    @Test fun stepThreeRefreshUpdatesExpiryAndDisablesDuringQueriesAtEveryWidth() {
        for (width in widths) for (androidSave in listOf(false, true)) {
            val host = Host(syntheticImage())
            val scene = accountScene(width, host, androidSave)
            try {
                render(scene, "account-refresh-ready-$androidSave-$width")
                val ready = semanticsNodes(scene)
                assertTrue(hasImage(ready))
                val refresh = button(ready, "刷新")
                assertEnabled(refresh, true)
                assertWithinViewport(refresh.boundsInRoot, width)
                assertTrue(refresh.boundsInRoot.height >= 42f, "Refresh keeps a usable touch target at $width")
                assertEquals(0, host.refreshes, "Rendering account details does not query them again")
                click(scene, refresh.boundsInRoot.center)
                assertEquals(1, host.refreshes)
                assertEquals(0, host.saves)
                assertEquals(0, host.copies)

                // Each guard independently prevents accidental duplicate dispatch.
                for ((busy, canRefresh) in listOf(true to true, false to false, true to false)) {
                    host.appreciation = host.appreciation.copy(busy = busy, canRefresh = canRefresh)
                    render(scene, "account-refresh-disabled-$busy-$canRefresh-$androidSave-$width")
                    val disabled = button(semanticsNodes(scene), "刷新")
                    assertEnabled(disabled, false)
                    click(scene, disabled.boundsInRoot.center)
                    assertEquals(1, host.refreshes)
                }

                host.appreciation = host.appreciation.copy(busy = false, canRefresh = true,
                    expiry = "有效期 2027-10-20", message = "刷新失败，请稍后重试。")
                render(scene, "account-refresh-failed-$androidSave-$width")
                val failed = semanticsNodes(scene)
                assertTrue("有效期 2027-10-20" in texts(failed), "The latest confirmed expiry remains visible")
                assertFeedback(failed, "刷新失败，请稍后重试。")
                val retry = button(failed, "刷新")
                assertEnabled(retry, true)
                click(scene, retry.boundsInRoot.center)
                assertEquals(2, host.refreshes)
            } finally { scene.close() }
        }
    }

    @Test fun englishRefreshRemainsReachableAndUnclippedAtNarrowWidth() {
        val previous = Locale.getDefault()
        try {
            UiLocale.setLocale(Locale.US)
            for (androidSave in listOf(false, true)) {
                for (surface in listOf("standalone", "account", "inline")) {
                    val host = Host(syntheticImage())
                    host.appreciation = host.appreciation.copy(expiry = "Expires 2027-10-20")
                    val scene = when (surface) {
                        "account" -> accountScene(320, host, androidSave)
                        "inline" -> comparisonScene(320, host, OnboardingKind.BOTH_EMPTY, androidSave)
                        else -> scene(320, host, androidSave)
                    }
                    try {
                        render(scene, "appreciation-$surface-en-US-$androidSave-320")
                        val nodes = semanticsNodes(scene)
                        assertFalse(texts(nodes).any { Regex("[\\u4e00-\\u9fff]").containsMatchIn(it) },
                            "$surface must use English labels")
                        if (surface == "account") {
                            val copy = button(nodes, "Copy username")
                            assertEnabled(copy, true)
                            assertWithinViewport(copy.boundsInRoot, 320)
                            assertTextFits(copy)
                            click(scene, copy.boundsInRoot.center)
                            assertEquals(1, host.copies)
                            if (androidSave) {
                                val save = button(nodes, "Save support QR code")
                                assertEnabled(save, true)
                                assertWithinViewport(save.boundsInRoot, 320)
                                assertTextFits(save)
                                assertTrue(save.boundsInRoot.bottom <= copy.boundsInRoot.top,
                                    "Narrow account actions stack so both existing labels remain readable")
                                click(scene, save.boundsInRoot.center)
                                assertEquals(1, host.saves)
                            }
                        }
                        val refresh = button(nodes, "Refresh")
                        assertEnabled(refresh, true)
                        assertWithinViewport(refresh.boundsInRoot, 320)
                        assertTextFits(refresh)
                        click(scene, refresh.boundsInRoot.center)
                        assertEquals(1, host.refreshes)

                        host.appreciation = host.appreciation.copy(busy = true, canRefresh = true)
                        render(scene, "appreciation-$surface-en-US-refreshing-$androidSave-320")
                        val disabled = button(semanticsNodes(scene), "Refresh")
                        assertEnabled(disabled, false, "Busy must disable Refresh even if canRefresh remains true")
                        click(scene, disabled.boundsInRoot.center)
                        assertEquals(1, host.refreshes)
                    } finally { scene.close() }
                }
            }
        } finally { Locale.setDefault(previous); UiLocale.useSystemLocale() }
    }

    @Test fun customDavStepFourHasNoAppreciationAndInlineFailureCannotEnableBlockedComparison() {
        val host = Host(syntheticImage())
        val noSlot = comparisonScene(500, host, OnboardingKind.BOTH_EMPTY, androidSave = false, showAppreciation = false)
        try {
            render(noSlot, "custom-comparison-no-appreciation")
            val nodes = semanticsNodes(noSlot)
            assertFalse(hasImage(nodes))
            assertFalse(texts(nodes).any { it.contains("赞赏") || it.contains("用户名") ||
                it == "保存图片" || it == "刷新" })
            assertEnabled(button(nodes, "完成"), true)
        } finally { noSlot.close() }

        host.appreciation = host.appreciation.copy(image = null, busy = true, canRefresh = false)
        val blocked = comparisonScene(320, host, null, androidSave = true, comparisonBusy = true)
        try {
            render(blocked, "inline-loading-comparison-blocked")
            assertEnabled(button(semanticsNodes(blocked), "重新检查"), false)
            assertEnabled(button(semanticsNodes(blocked), "刷新"), false)
            host.appreciation = host.appreciation.copy(busy = false, canRefresh = true, message = "")
            render(blocked, "inline-failed-comparison-still-blocked")
            val nodes = semanticsNodes(blocked)
            assertTrue("赞赏码暂时无法加载。" in texts(nodes))
            assertEnabled(button(nodes, "重新检查"), false)
            val refresh = button(nodes, "刷新")
            assertEnabled(refresh, true)
            click(blocked, refresh.boundsInRoot.center)
            assertEquals(1, host.refreshes)
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
                        imageSaveBusy = host.imageSaveBusy, imageSaveMessage = host.imageSaveMessage,
                        refresh = host.refresh)
                }) else null)
        }
    }

    private fun scene(width: Int, host: Host, androidSave: Boolean) =
        ImageComposeScene(width = width, height = 757, density = Density(1f)) {
            SharedConnectionTheme {
                SharedAppShell(title = SharedMessages.SUPPORT.text(), footer = {
                    SharedActionButton(SharedMessages.BACK.text(), { host.backs++ }, style = SharedButtonStyle.TEXT)
                }) {
                    SharedForm {
                        SharedAppreciationContent(host.appreciation, { host.copies++ },
                            onSaveImage = if (androidSave) host.save else null,
                            imageSaveBusy = host.imageSaveBusy, imageSaveMessage = host.imageSaveMessage,
                            refresh = host.refresh)
                    }
                }
            }
        }

    private fun accountScene(width: Int, host: Host, androidSave: Boolean) =
        ImageComposeScene(width = width, height = 757, density = Density(1f)) {
            SharedConnectionTheme {
                SharedAppShell(title = SharedMessages.CONNECT_CLOUD.text(), eyebrow = "3 / 4", onboardingStep = 3) {
                    SharedForm {
                        SharedOfficialAccountPanel(SharedConnectionState(
                            origin = ConnectionEntryModel.Origin.SETUP, mode = ConnectionEntryModel.Mode.OFFICIAL,
                            endpoint = "", username = requireNotNull(host.appreciation.username), password = "",
                            busy = host.appreciation.busy, canCancel = true, canEdit = false, canGoBack = true,
                            officialAuthenticated = true, showOfficialAccount = true, officialExpired = false,
                            officialExpiry = host.appreciation.expiry, officialAccessStatus = "",
                            showInlineAppreciation = true, canContinue = true, notice = "", error = false,
                            showChangeService = false, appreciationLabel = SharedMessages.SUPPORT.text(),
                            canAppreciate = true, appreciation = host.appreciation, appreciationInline = true,
                            recoveryMessage = "", recoveryLabel = ""),
                            copyUsername = { host.copies++ }, openAppreciation = {},
                            onSaveImage = if (androidSave) host.save else null,
                            imageSaveBusy = host.imageSaveBusy, imageSaveMessage = host.imageSaveMessage,
                            refresh = host.refresh)
                    }
                }
            }
        }

    /** A solid test swatch, deliberately not a real payment code. */
    private fun syntheticImage() = ImageBitmap(24, 24).also {
        Canvas(it).drawRect(Rect(0f, 0f, 24f, 24f), Paint().apply { color = Color(0xff326650) })
    }

    private fun assertFeedback(nodes: List<SemanticsNode>, message: String) {
        val notice = nodes.single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == message } == true }
        assertEquals(LiveRegionMode.Polite, notice.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    private fun hasImage(nodes: List<SemanticsNode>) = nodes.any {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("赞赏码") == true
    }

    private fun assertEnabled(node: SemanticsNode, enabled: Boolean, message: String? = null) {
        assertEquals(!enabled, node.config.contains(SemanticsProperties.Disabled), message)
    }

    private fun assertTextFits(node: SemanticsNode) {
        for (textNode in descendants(node)) {
            val layouts = mutableListOf<TextLayoutResult>()
            textNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (layout in layouts) {
                assertFalse(layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines)
                assertTrue(layout.size.width <= textNode.boundsInRoot.width + 1f,
                    "The label's measured width must fit its visible bounds")
                assertTrue(layout.size.height <= textNode.boundsInRoot.height + 1f,
                    "The label's measured height must fit its visible bounds")
                for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line))
                    assertTrue(layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                }
            }
        }
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
