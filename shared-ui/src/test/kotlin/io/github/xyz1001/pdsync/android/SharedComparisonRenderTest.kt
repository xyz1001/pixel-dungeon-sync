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
                    SharedAppShell(title = "选择存档", eyebrow = "4 / 4", game = current.game,
                        footer = {
                            SharedActionButton("上一步", {}, style = SharedButtonStyle.TEXT)
                            Spacer(Modifier.weight(1f))
                            SharedActionButton("完成", {})
                        }) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    "choice cards retain identity and navigation without extra status copy: $name")
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
                    .count { (it and 0xffffff) == 0x924832 }
                if (error) assertTrue(errorPixels > 0, "actual failure must remain visible")
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
                val expectedWidth = minOf(820, width - if (width <= 560) 20 else 36)
                assertEquals(expectedWidth.toFloat(), bounds.width, 1f, "$name card width at $width")
                assertTrue(bounds.left >= 10 && bounds.right <= width - 10, "$name viewport sides at $width")
                assertTrue(bounds.bottom <= 742, "$name footer inside viewport at $width")
                if (current.primaryEnabled) {
                    val buttons = buttonRects(image, 0x326650)
                    assertEquals(1, buttons.size, "$name must have one primary at $width")
                    assertTrue(buttons.single().height >= 46, "$name primary target height at $width")
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
                            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
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
                assertEquals(minOf(440, width - 32).toFloat(), dialogBounds.width, 1f)
                assertTrue(dialogBounds.left >= 16 && dialogBounds.right <= width - 16)
                val buttons = buttonRects(image, 0x326650) + buttonRects(image, 0xe9efea)
                assertEquals(if (review) 2 else 3, buttons.size)
                buttons.forEach { button ->
                    assertTrue(button.width >= 90, "minimum 90px dialog target: $button at $width")
                    assertTrue(button.height >= 46, "minimum 46px dialog target: $button at $width")
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
            val buttons = buttonRects(image, 0x326650)
            assertEquals(1, buttons.size)
            assertTrue(buttons.single().bottom <= 404)
            assertTrue(buttons.single().height >= 46)
        } finally { scene.close() }
    }

    @Test fun selectedRadioUsesVisibleReferenceGreenRatherThanPaleSecondaryBackground() {
        for (selected in listOf(false, true)) {
            val scene = ImageComposeScene(width = 300, height = 100, density = Density(1f)) {
                SharedConnectionTheme { ComparisonChoiceCard("使用本机进度", "本机", selected, true, {}) }
            }
            try {
                val image = render(scene, "radio-$selected")
                val green = (10 until 90).sumOf { y -> (10 until 50).count { x ->
                    (image.getRGB(x, y) and 0xffffff) == 0x326650
                } }
                if (selected) assertTrue(green > 30, "selected dot and ring must remain visible")
                else assertEquals(0, green, "unselected radio is muted, not selected green")
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

    private fun nonBackgroundBounds(image: BufferedImage): Rect {
        var left = image.width; var top = image.height; var right = 0; var bottom = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if ((image.getRGB(x, y) and 0xffffff) != 0xeff3ef) {
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
