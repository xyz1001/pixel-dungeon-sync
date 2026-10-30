@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pdsync.OnboardingKind

/** Render the actual shared implementation; no cloud/filesystem operations run in composition. */
class SharedComparisonRenderTest {
    private val actions = object : SharedComparisonActions {
        override fun back() = Unit
        override fun refresh() = Unit
        override fun selectProgress(choice: SharedProgressChoice) = Unit
        override fun selectRemote(snapshotId: String) = Unit
        override fun finish(mode: SharedComparisonCompletion) = Unit
        override fun handleConflict() = Unit
        override fun upload() = Unit
        override fun openRecovery() = Unit
    }

    private fun state(kind: OnboardingKind?) = SharedComparisonState(kind,
        cloudVerified = kind != null, pageActive = true, configured = true, restoreSupported = true,
        game = SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"),
        localLabel = "本机 · Desktop", localDetails = "已进行0场游戏",
        remoteChoices = listOf(SharedComparisonRemoteChoice("remote-a", "云端 · Android", "已进行3场游戏")),
        selectedId = "remote-a")

    @Test fun identicalProgressOmitsDetailsAndOrdinaryNoticesAtEveryWidth() {
        for (width in listOf(1165, 500, 320)) {
            val current = state(OnboardingKind.IDENTICAL).copy(canCompleteWithoutTransfer = true)
            assertEquals("进度已同步", current.headline)
            val plain = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                SharedComparisonScreen(current.copy(localDetails = "", remoteChoices = emptyList()), actions)
            }
            val detailed = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                SharedComparisonScreen(current.copy(details = "diagnostic detail", canCopyDiagnostics = true,
                    notice = "两边存档相同，确认后继续。"), actions)
            }
            try {
                val expected = render(plain, "identical-plain-$width")
                val actual = render(detailed, "identical-no-details-$width")
                assertTrue(expected.getRGB(0, 0, width, 757, null, 0, width).contentEquals(
                    actual.getRGB(0, 0, width, 757, null, 0, width)),
                    "identical state must not render optional metadata, detail controls or redundant copy")
            } finally { plain.close(); detailed.close() }
        }
    }

    @Test fun progressSelectionShowsOnlyChoiceCardsAndExistingNavigation() {
        val states = listOf(
            state(OnboardingKind.LOCAL_ONLY).copy(selectedProgress = SharedProgressChoice.LOCAL),
            state(OnboardingKind.REMOTE_ONLY).copy(selectedProgress = SharedProgressChoice.CLOUD),
            state(OnboardingKind.DIFFERENT).copy(canKeepLocalProgress = true,
                selectedProgress = SharedProgressChoice.LOCAL),
        )
        for (width in listOf(500, 320)) for (current in states) {
            val name = "choices-only-${current.kind}-$width"
            val actual = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                SharedComparisonScreen(current.copy(notice = "本机有进度，请选择要继续的进度。"), actions)
            }
            val expected = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                SharedConnectionTheme {
                    SharedAppShell(title = "选择存档", eyebrow = "4 / 4", onboardingStep = 4,
                        footer = {
                            SharedActionButton("上一步", {}, style = SharedButtonStyle.TEXT)
                            Spacer(Modifier.weight(1f))
                            SharedActionButton("完成", {})
                        }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (current.kind != OnboardingKind.REMOTE_ONLY) ComparisonChoiceCard(
                                "使用本机进度", "本机 · Desktop · 已进行0场游戏",
                                current.selectedProgress == SharedProgressChoice.LOCAL, true, {})
                            if (current.kind != OnboardingKind.LOCAL_ONLY) ComparisonChoiceCard(
                                "使用云端进度", "云端 · Android · 已进行3场游戏",
                                current.selectedProgress == SharedProgressChoice.CLOUD, true, {})
                        }
                    }
                }
            }
            try {
                val actualImage = render(actual, name)
                val expectedImage = render(expected, "$name-expected")
                assertTrue(actualImage.getRGB(0, 0, width, 757, null, 0, width).contentEquals(
                    expectedImage.getRGB(0, 0, width, 757, null, 0, width)),
                    "choice cards retain navigation without game identity or extra status copy: $name")
            } finally { actual.close(); expected.close() }
        }
    }

    @Test fun actualFailureNoticeRemainsVisibleOnChoiceScreen() {
        for (error in listOf(false, true)) {
            val current = state(OnboardingKind.LOCAL_ONLY).copy(selectedProgress = SharedProgressChoice.LOCAL,
                notice = "上传未完成，请重试。", feedbackError = error)
            val scene = ImageComposeScene(width = 500, height = 757, density = Density(1f)) {
                SharedComparisonScreen(current, actions)
            }
            try {
                val image = render(scene, "choice-feedback-$error")
                val errorPixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                    .count { (it and 0xffffff) == 0xba1a1a }
                if (error) assertTrue(errorPixels > 0, "actual failure must remain visible; exactErrorPixels=$errorPixels, locale=${pdsync.i18n.UiLocale.locale()}, image=${image.width}x${image.height}")
                else assertEquals(0, errorPixels, "ordinary status must not add a notice")
            } finally { scene.close() }
        }
    }

    @Test fun everyComparisonStateFitsReferenceShellAtDesktopAndNarrowWidths() {
        val states = linkedMapOf(
            "empty" to state(OnboardingKind.BOTH_EMPTY),
            "identical" to state(OnboardingKind.IDENTICAL).copy(canCompleteWithoutTransfer = true),
            "identical-unproven" to state(OnboardingKind.IDENTICAL),
            "local" to state(OnboardingKind.LOCAL_ONLY).copy(selectedProgress = SharedProgressChoice.LOCAL),
            "cloud" to state(OnboardingKind.REMOTE_ONLY).copy(selectedProgress = SharedProgressChoice.CLOUD),
            "cloud-unselected" to state(OnboardingKind.REMOTE_ONLY),
            "different" to state(OnboardingKind.DIFFERENT),
            "keep-local" to state(OnboardingKind.DIFFERENT).copy(canKeepLocalProgress = true,
                selectedProgress = SharedProgressChoice.LOCAL),
            "branched" to state(OnboardingKind.BRANCHED),
            "unknown" to state(null),
            "pending" to state(OnboardingKind.BLOCKED).copy(cloudVerified = false, retryablePending = true),
            "recovery" to state(OnboardingKind.BLOCKED).copy(needsRestoreRecovery = true),
            "busy" to state(null).copy(busy = true),
        )
        for (width in listOf(1165, 500, 320)) for ((name, current) in states) {
            val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                SharedComparisonScreen(current, actions)
            }
            try {
                val image = render(scene, "$name-$width")
                val bounds = nonBackgroundBounds(image)
                val expectedWidth = minOf(860, width - if (width <= 600) 16 else 32)
                assertEquals(expectedWidth.toFloat(), bounds.width, 1f, "$name card width at $width")
                assertTrue(bounds.left >= 8 && bounds.right <= width - 8, "$name viewport sides at $width")
                assertTrue(bounds.bottom <= 742, "$name footer inside viewport at $width")
                if (current.primaryEnabled) {
                    val buttons = buttonRects(image, 0x006877)
                    assertEquals(1, buttons.size, "$name must have one primary at $width")
                    assertTrue(buttons.single().height >= 42, "$name primary target height at $width")
                    assertTrue(buttons.single().right <= width - 30, "$name primary right edge at $width")
                }
            } finally { scene.close() }
        }
    }

    @Test fun sharedDialogWrapsThreeButtonsWithoutClippingAndSupportsReview() {
        for (width in listOf(1165, 500, 320)) for (review in listOf(false, true)) {
            val dialog = SharedConfirmationState(9, if (review) "确认合并" else "如何处理冲突？",
                "将先备份本机存档，再继续操作。", if (review) "合并并上传" else "覆盖本机", true,
                secondaryLabel = if (review) null else "合并", secondaryEnabled = true,
                details = if (review) "已检查全部槽位，可以合并。" else "")
            val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                SharedConnectionTheme {
                    Box(Modifier.fillMaxSize().background(SharedUiColors.background).padding(16.dp),
                        contentAlignment = Alignment.Center) {
                        SharedConfirmationDialogBody(dialog, {}, {}, {},
                            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                            if (!review) {
                                ComparisonChoiceCard("云端 · Android", "已进行3场游戏", false, true, {})
                                ComparisonChoiceCard("云端 · Desktop", "已进行7场游戏", true, true, {})
                            }
                        }
                    }
                }
            }
            try {
                val image = render(scene, "dialog-${if (review) "review" else "conflict"}-$width")
                val dialogBounds = nonBackgroundBounds(image)
                assertEquals(minOf(480, width - 32).toFloat(), dialogBounds.width, 1f,
                    "M3 outline is included in the measured dialog surface")
                assertTrue(dialogBounds.left >= 16 && dialogBounds.right <= width - 16)
                val buttons = buttonRects(image, 0x006877) + buttonRects(image, 0xf1f5f9)
                assertEquals(2, buttons.size)
                buttons.forEach { button ->
                    assertTrue(button.width >= 88, "minimum 90px dialog target: $button at $width")
                    assertTrue(button.height >= 40, "minimum 42px dialog target: $button at $width")
                    assertTrue(button.left >= dialogBounds.left + 24 && button.right <= dialogBounds.right - 24)
                }
                if (width == 320 && !review) assertTrue(buttons.map { it.top }.distinct().size > 1,
                    "three buttons must wrap at 320px rather than squeeze")
            } finally { scene.close() }
        }
    }

    @Test fun longGameAndChoiceTextLeaveFooterReachableInShortViewport() {
        val current = state(OnboardingKind.REMOTE_ONLY).copy(selectedProgress = SharedProgressChoice.CLOUD,
            game = SharedGameIdentity("一个很长的真实游戏名称用于检查窄屏换行与导航", "2026.10.15-long-version"),
            remoteChoices = listOf(SharedComparisonRemoteChoice("remote-a", "云端设备名称".repeat(10), "已进行3场游戏")))
        val scene = ImageComposeScene(width = 320, height = 420, density = Density(1f)) {
            SharedComparisonScreen(current, actions)
        }
        try {
            val image = render(scene, "cloud-short-320")
            val buttons = buttonRects(image, 0x006877)
            assertEquals(1, buttons.size)
            assertTrue(buttons.single().bottom <= 404)
            assertTrue(buttons.single().height >= 42)
        } finally { scene.close() }
    }

    @Test fun longMergeLabelKeepsOneLineAndWrapsItsWholeButtonOnNarrowScreens() {
        val labels = listOf("取消", "覆盖本机", "智能合并双方进度")
        for (width in listOf(1280, 390)) {
            val dialog = SharedConfirmationState(23, "如何处理进度冲突？",
                "将先备份，再替换本机存档。", labels[1], true, labels[2], true)
            val scene = ImageComposeScene(width = width, height = 900, density = Density(1f)) {
                SharedConnectionTheme {
                    Box(Modifier.fillMaxSize().background(SharedUiColors.background)
                        .padding(horizontal = 19.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                        SharedConfirmationDialogBody(dialog, {}, {}, {},
                            Modifier.widthIn(max = 480.dp).fillMaxWidth())
                    }
                }
            }
            try {
                val image = render(scene, "dialog-long-merge-$width")
                val surface = nonBackgroundBounds(image)
                assertEquals(minOf(480, width - 38).toFloat(), surface.width, 1f)
                val nodes = semanticsNodes(scene)
                val buttons = labels.map { label ->
                    val text = nodes.single { node ->
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
                    }
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(assertNotNull(text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
                    val layout = layouts.single()
                    assertEquals(1, layout.lineCount, "$label remains a single line at viewport $width")
                    // Desktop Text can shrink-wrap while its paragraph retains the wider input
                    // constraint. Check real glyph-line extents, not didOverflowWidth's paragraph.
                    assertFalse(layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines)
                    assertFalse(layout.isLineEllipsized(0))
                    assertEquals(label.length, layout.getLineEnd(0), "The entire label is laid out")
                    assertTrue(layout.getLineLeft(0) >= -1 && layout.getLineRight(0) <= layout.size.width + 1,
                        "$label must fit its measured text width at viewport $width")
                    assertTrue(layout.getLineTop(0) >= -1 && layout.getLineBottom(0) <= layout.size.height + 1,
                        "$label must fit its measured text height at viewport $width")
                    nodes.single { node -> node.config.contains(SemanticsActions.OnClick) &&
                        descendants(node).any { it.id == text.id } }.boundsInRoot.also { bounds ->
                        assertEquals(42f, bounds.height, .1f, "M3 one-line action height at viewport $width")
                        assertTrue(bounds.left >= surface.left + 28 && bounds.right <= surface.right - 28,
                            "$label stays within dialog content: $bounds / $surface")
                    }
                }
                assertEquals(buttons[0].top, buttons[1].top, .1f, "Cancel and overwrite share their row")
                assertEquals(10f, buttons[1].left - buttons[0].right, .1f)
                if (width == 1280) {
                    assertEquals(buttons[1].top, buttons[2].top, .1f, "All actions fit the 480px dialog")
                    assertTrue(buttons[2].width > buttons[1].width,
                        "The long merge label receives its natural width, not an equal squeezed share")
                    assertEquals(10f, buttons[2].left - buttons[1].right, .1f)
                } else {
                    assertEquals(buttons[1].bottom + 10, buttons[2].top, .1f,
                        "At 390px the whole merge button wraps to the next row")
                    assertEquals(buttons[0].left, buttons[2].left, .1f)
                    assertEquals(buttons[1].right, buttons[2].right, .1f)
                }
            } finally { scene.close() }
        }
    }

    @Test fun selectedRadioUsesVisibleM3TealOnWhiteChoiceSurface() {
        for (selected in listOf(false, true)) {
            val scene = ImageComposeScene(width = 300, height = 100, density = Density(1f)) {
                SharedConnectionTheme { ComparisonChoiceCard("使用本机进度", "本机", selected, true, {}) }
            }
            try {
                val image = render(scene, "radio-$selected")
                assertEquals(0xffffff, image.getRGB(270, 24) and 0xffffff,
                    "M3 choices retain a white surface, including when selected")
                val teal = (10 until 90).sumOf { y -> (10 until 50).count { x ->
                    (image.getRGB(x, y) and 0xffffff) == 0x006877
                } }
                if (selected) assertTrue(teal > 30, "selected dot and ring must remain visible")
                else assertEquals(0, teal, "unselected radio is muted, not selected teal")
            } finally { scene.close() }
        }
    }

    private fun render(scene: ImageComposeScene, name: String): BufferedImage {
        scene.render(0).close()
        scene.render(16_000_000).close()
        return scene.render(32_000_000).use { image ->
            val file = Path.of("build/reports/shared-comparison", "$name.png")
            Files.createDirectories(file.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(file, data.bytes)
                ImageIO.read(ByteArrayInputStream(data.bytes))
            }
        }
    }

    private fun semanticsNodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        return descendants(owner.unmergedRootSemanticsNode)
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    private fun nonBackgroundBounds(image: BufferedImage): Rect {
        var left = image.width; var top = image.height; var right = 0; var bottom = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if ((image.getRGB(x, y) and 0xffffff) in setOf(0xffffff, 0xcbd5e1)) {
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
            }
        }
        assertTrue(right > left && bottom > top)
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    /** Solid button backgrounds form large connected components; text and radio dots do not. */
    private fun buttonRects(image: BufferedImage, rgb: Int): List<Rect> {
        val seen = BooleanArray(image.width * image.height)
        val queue = IntArray(seen.size)
        val result = mutableListOf<Rect>()
        for (origin in seen.indices) {
            if (seen[origin] || (image.getRGB(origin % image.width, origin / image.width) and 0xffffff) != rgb) continue
            var head = 0; var tail = 1
            queue[0] = origin; seen[origin] = true
            var left = image.width; var top = image.height; var right = 0; var bottom = 0
            while (head < tail) {
                val current = queue[head++]
                val x = current % image.width; val y = current / image.width
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
                val neighbours = intArrayOf(if (x > 0) current - 1 else -1,
                    if (x + 1 < image.width) current + 1 else -1,
                    if (y > 0) current - image.width else -1,
                    if (y + 1 < image.height) current + image.width else -1)
                for (next in neighbours) if (next >= 0 && !seen[next] &&
                    (image.getRGB(next % image.width, next / image.width) and 0xffffff) == rgb) {
                    seen[next] = true; queue[tail++] = next
                }
            }
            if (tail > 700 && bottom - top >= 40 && tail > (right - left) * (bottom - top) / 2) {
                result += Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            }
        }
        return result
    }
}
