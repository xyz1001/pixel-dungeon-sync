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
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedGameSelectionRenderTest {
    @Test fun selectionStartsATwoStepWizard() {
        val source = Files.readString(Path.of("../shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedGameSelectionScreen.kt"))
        assertTrue(source.contains("eyebrow = SharedMessages.STEP_ONE.text()"))
        assertEquals("第 1 步，共 2 步", pdsync.i18n.SharedMessages.STEP_ONE.chinese())
        assertEquals("Step 1 of 2", pdsync.i18n.SharedMessages.STEP_ONE.english())
        assertFalse(source.contains("1 / 4"))
    }
    private class Observation : SharedGameSelectionLayoutObserver {
        val bounds = mutableMapOf<String, Rect>()
        val lines = mutableMapOf<String, Int>()
        val overflow = mutableSetOf<String>()
        override fun element(name: String, bounds: Rect) { this.bounds[name] = bounds }
        override fun text(name: String, lineCount: Int, overflow: Boolean) {
            lines[name] = lineCount
            if (overflow) this.overflow += name else this.overflow -= name
        }
    }
    private class Host(initial: SharedGameSelectionState) : SharedGameSelectionActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        var onPerform: (SharedGameSelectionControl) -> Unit = {}
        override fun perform(control: SharedGameSelectionControl) { calls += control.name; onPerform(control) }
        override fun chooseCandidate(id: String) {
            val candidate = state.candidates.single { it.id == id }
            calls += id
            state = state.copy(game = candidate.game, selectionId = id, location = candidate.location, canContinue = true)
        }
        override fun next() { calls += "next"; state = state.copy(busy = true) }
    }
    private fun ready() = SharedGameSelectionState(
        game = SharedGameIdentity("Shattered Pixel Dungeon", "4.0.2"), selectionId = "/games/selected.jar",
        location = "/games/selected.jar", pageActive = true, canContinue = true,
        rows = listOf(SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_FILE, "重新选择")),
    )
    private fun scene(width: Int, height: Int, host: Host, observed: Observation, fontScale: Float = 1f) =
        ImageComposeScene(width = width, height = height, density = Density(1f, fontScale)) {
            CompositionLocalProvider(LocalSharedGameSelectionLayoutObserver provides observed) {
                SharedGameSelectionScreen(host.state, host)
            }
        }

    @Test fun realSharedCardAndSingleNextFitDesktopAndNarrowScreens() {
        for (width in listOf(1180, 560, 500, 320)) {
            val host = Host(ready())
            val observed = Observation()
            val scene = scene(width, 757, host, observed)
            try {
                val image = render(scene, "game-selection-$width")
                assertCardInViewport(image)
                val card = observed.bounds.getValue("identity")
                val next = observed.bounds.getValue("next")
                assertTrue(card.left >= 28f && card.right <= width - 28f)
                assertTrue(next.height >= 42f && next.bottom < 742f)
                assertTrue(card.bottom < next.top)
                assertTrue(observed.overflow.isEmpty())
                assertTrue(host.state.selectionStatus.isEmpty(), "selected desktop game needs no found-status label")
                assertEquals("Shattered Pixel Dungeon", host.state.game?.name)
                assertEquals("4.0.2", host.state.game?.version)
                click(scene, observed.bounds.getValue("action:CHOOSE_FILE").center)
                render(scene, "game-selection-cancel-$width")
                assertEquals(listOf("CHOOSE_FILE"), host.calls)
                assertEquals("/games/selected.jar", host.state.selectionId)
                click(scene, next.center); click(scene, next.center)
                render(scene, "game-selection-next-$width")
                assertEquals(listOf("CHOOSE_FILE", "next"), host.calls)
            } finally { scene.close() }
        }
    }

    @Test fun emptyAndUnverifiedSelectionsNeverAdvanceAndCandidateSelectionIsReal() {
        val observed = Observation()
        val host = Host(ready().copy(game = null, selectionId = null, canContinue = false, candidates = listOf(
            SharedGameCandidate("one", SharedGameIdentity("Game one", "1"), "/games/one.jar"),
            SharedGameCandidate("two", SharedGameIdentity("Game two", "2"), "/games/two.jar"),
        )))
        val scene = scene(500, 757, host, observed)
        try {
            render(scene, "game-selection-candidates")
            click(scene, observed.bounds.getValue("next").center)
            assertTrue(host.calls.isEmpty())
            click(scene, observed.bounds.getValue("action:CHOOSE_FILE").center)
            render(scene, "game-picker-open")
            click(scene, observed.bounds.getValue("candidate:two").center)
            render(scene, "game-selection-candidate-chosen")
            assertEquals("two", host.state.selectionId)
            assertEquals("Game two", host.state.game?.name)
            click(scene, observed.bounds.getValue("next").center)
            render(scene, "game-selection-candidate-next")
            assertEquals(listOf("two", "next"), host.calls)
        } finally { scene.close() }
    }

    @Test fun longIdentityPathAndManyAndroidActionsScrollWithoutMovingFooter() {
        val observed = Observation()
        val host = Host(ready().copy(
            game = SharedGameIdentity("一个很长的已选择游戏名称，用于确认窄屏可以换行并保持操作可达", "2026.10.15"),
            location = "/storage/emulated/0/很长的真实目录/" + "selected-game-path-".repeat(12),
            rows = listOf(SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_INSTALLED, "从已安装应用中选择")),
        ))
        val scene = scene(320, 600, host, observed)
        try {
            render(scene, "game-selection-long-320")
            assertTrue(observed.lines.getValue("game-name") > 1)
            assertFalse("details" in observed.bounds)
            assertFalse("location" in observed.bounds)
            assertTrue(observed.overflow.isEmpty())
            host.state = host.state.copy(rows = SharedGameSelectionControl.entries.filter {
                it != SharedGameSelectionControl.COPY_DIAGNOSTICS
            }.map { SharedGameSelectionRow(it, "实际平台操作：${it.name}") })
            render(scene, "game-selection-many-actions-320")
            val footer = observed.bounds.getValue("next")
            val first = observed.bounds.getValue("identity")
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(160f, first.top + 24f), scrollDelta = Offset(0f, 12f))
            assertCardInViewport(render(scene, "game-selection-scrolled-320"))
            assertEquals(footer, observed.bounds.getValue("next"))
            assertTrue(observed.bounds.getValue("identity").top < first.top)
            assertFalse(footer.bottom > 585f)
        } finally { scene.close() }
    }

    private fun android(mode: SharedGameAccessMode = SharedGameAccessMode.NONE,
        status: SharedGameAccessStatus = SharedGameAccessStatus.UNCHECKED) = ready().copy(
        game = SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"), selectionId = "game.example",
        canContinue = false, access = SharedGameAccessState(mode, status), rows = buildList {
            add(SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_INSTALLED, "选择已安装应用"))
            add(SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_FILE, "选择 APK 文件"))
            add(SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_ROOT, "Root 模式"))
            add(SharedGameSelectionRow(SharedGameSelectionControl.SWITCH_TO_EMBEDDED, "修改 APK 模式"))
        },
    )

    @Test fun androidAccessCardsMatchReferenceArrangementAndUseRealCallbacks() {
        for (width in listOf(390, 1280)) {
            val host = Host(android())
            val observed = Observation()
            val scene = scene(width, 900, host, observed)
            try {
                render(scene, "android-initial-$width")
                val root = observed.bounds.getValue("action:CHOOSE_ROOT")
                val apk = observed.bounds.getValue("action:SWITCH_TO_EMBEDDED")
                if (width == 390) {
                    assertEquals(root.left, apk.left)
                    assertTrue(apk.top >= root.bottom + 11f)
                } else {
                    assertEquals(root.top, apk.top)
                    assertTrue(apk.left >= root.right + 11f)
                }
                assertTrue(root.height in 80f..120f && apk.height in 80f..120f)
                click(scene, apk.center)
                assertEquals(listOf("SWITCH_TO_EMBEDDED"), host.calls)
                assertFalse(host.state.nextEnabled)
                assertFalse("action:CONTINUE_INSTALLATION" in observed.bounds)

                host.state = android(SharedGameAccessMode.APK).copy(notice = "请选择修改 APK 模式以继续安装。")
                val selectedImage = render(scene, "android-apk-selected-$width")
                val selectedCard = observed.bounds.getValue("action:SWITCH_TO_EMBEDDED")
                assertEquals(0xdff6fb, selectedImage.getRGB(selectedCard.left.toInt() + 5,
                    selectedCard.center.y.toInt()) and 0xffffff)
                assertNoDuplicateInstallPanel(scene, observed)
                click(scene, selectedCard.center)
                assertEquals(listOf("SWITCH_TO_EMBEDDED", "SWITCH_TO_EMBEDDED"), host.calls)
                click(scene, observed.bounds.getValue("next").center)
                assertEquals(2, host.calls.size)
                assertTrue(observed.overflow.isEmpty())
            } finally { scene.close() }
        }
    }

    @Test fun everyApkStateOmitsTheEntireInstallPanelEvenWithALegacyHostRow() {
        for (width in listOf(390, 1280)) {
            val observed = Observation()
            val host = Host(android())
            val scene = scene(width, 900, host, observed)
            try {
                for (status in SharedGameAccessStatus.entries) {
                    host.state = android(SharedGameAccessMode.APK, status).copy(
                        notice = when (status) {
                            SharedGameAccessStatus.UNCHECKED -> "请选择修改 APK 模式以继续安装。"
                            SharedGameAccessStatus.WORKING -> "正在准备游戏安装，请稍候。"
                            SharedGameAccessStatus.READY -> "组件已就绪，已验证存档访问。"
                            SharedGameAccessStatus.FAILED -> "安装失败，请点击修改 APK 模式重试。"
                        }, noticeError = status == SharedGameAccessStatus.FAILED,
                        busy = status == SharedGameAccessStatus.WORKING,
                        canContinue = status == SharedGameAccessStatus.READY,
                        rows = android().rows + listOf(
                            SharedGameSelectionRow(SharedGameSelectionControl.CONTINUE_INSTALLATION, "开始注入与安装"),
                            SharedGameSelectionRow(SharedGameSelectionControl.CANCEL_ACCESS_SELECTION, "取消本次选择")))
                    render(scene, "android-apk-no-panel-${status.name.lowercase()}-$width")
                    assertNoDuplicateInstallPanel(scene, observed)
                    assertFalse(host.state.notice in text(scene), "Access-mode notices must never render")
                    assertEquals(status == SharedGameAccessStatus.WORKING,
                        nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
                    assertEquals(status != SharedGameAccessStatus.READY,
                        button(scene, "下一步：连接云端").config.contains(SemanticsProperties.Disabled))
                    val cancel = button(scene, "取消本次选择")
                    assertEquals(status == SharedGameAccessStatus.WORKING,
                        cancel.config.contains(SemanticsProperties.Disabled))
                    if (status != SharedGameAccessStatus.WORKING) {
                        click(scene, cancel.boundsInRoot.center)
                        assertEquals("CANCEL_ACCESS_SELECTION", host.calls.last())
                    }
                }
            } finally { scene.close() }
        }
    }

    @Test fun allAccessModeBottomNoticesStayAbsentAcrossStateChanges() {
        val messages = listOf(
            "请选择游戏。", "正在准备游戏安装，请稍候。", "系统安装尚未完成。可取消后重试。",
            "已取消安装。", "安装完成，正在连接游戏组件。", "安装失败，请重试。",
            "尚未授权 Root。", "Root 授权被拒绝。", "Root 检测失败。", "Root 授权成功。",
            "游戏存档组件需要兼容更新，请重新准备安装包。", "游戏组件已连接。",
        )
        for (width in listOf(390, 1280)) {
            val observed = Observation()
            val host = Host(android())
            val scene = scene(width, 900, host, observed)
            try {
                for (mode in SharedGameAccessMode.entries) {
                    for ((index, message) in messages.withIndex()) {
                        val status = when (index) {
                            1, 2, 4 -> SharedGameAccessStatus.WORKING
                            5, 7, 8, 10 -> SharedGameAccessStatus.FAILED
                            9, 11 -> SharedGameAccessStatus.READY
                            else -> SharedGameAccessStatus.UNCHECKED
                        }
                        host.state = android(mode, status).copy(notice = message,
                            warning = "有未完成恢复，请先检查恢复记录。", noticeError = index % 2 == 0,
                            busy = status == SharedGameAccessStatus.WORKING,
                            canContinue = status == SharedGameAccessStatus.READY)
                        observed.bounds.clear()
                        render(scene, "access-no-notices-${mode.name.lowercase()}-$index-$width")
                        val labels = text(scene)
                        assertFalse(message in labels)
                        assertFalse(host.state.warning in labels)
                        assertFalse(observed.bounds.containsKey("notice"))
                        assertFalse(observed.bounds.containsKey("warning"))
                        assertTrue("Root 模式" in labels && "修改 APK 模式" in labels)
                        assertEquals(!host.state.nextEnabled,
                            button(scene, "下一步：连接云端").config.contains(SemanticsProperties.Disabled))
                    }
                }
                host.state = ready().copy(notice = "桌面发现诊断", warning = "桌面发现警告")
                render(scene, "desktop-notices-preserved-$width")
                assertTrue("桌面发现诊断" in text(scene))
                assertTrue("桌面发现警告" in text(scene))
            } finally { scene.close() }
        }
    }

    @Test fun apkCardDispatchesOnlyOnExplicitClicksAcrossBusyRecompositionBackAndRetry() {
        val observed = Observation()
        val host = Host(android())
        host.onPerform = { control ->
            if (control == SharedGameSelectionControl.SWITCH_TO_EMBEDDED) {
                host.state = host.state.copy(access = SharedGameAccessState(SharedGameAccessMode.APK,
                    SharedGameAccessStatus.WORKING), busy = true, notice = "正在准备游戏安装，请稍候。", noticeError = false)
            }
        }
        val scene = scene(390, 900, host, observed)
        try {
            render(scene, "android-apk-direct-before-click")
            val retained = button(scene, "修改 APK 模式").config.getOrNull(SemanticsActions.OnClick)!!.action!!
            assertTrue(host.calls.isEmpty())
            click(scene, observed.bounds.getValue("action:SWITCH_TO_EMBEDDED").center)
            render(scene, "android-apk-direct-working")
            assertEquals(listOf("SWITCH_TO_EMBEDDED"), host.calls)
            assertTrue(button(scene, "修改 APK 模式").config.contains(SemanticsProperties.Disabled))
            repeat(3) {
                retained()
                click(scene, observed.bounds.getValue("action:SWITCH_TO_EMBEDDED").center)
                host.state = host.state.copy(notice = "正在准备游戏安装，阶段 ${it + 1}。")
                render(scene, "android-apk-direct-recomposition-$it")
            }
            assertEquals(listOf("SWITCH_TO_EMBEDDED"), host.calls)
            host.state = host.state.copy(pageActive = false, busy = false,
                access = SharedGameAccessState(SharedGameAccessMode.APK, SharedGameAccessStatus.FAILED),
                notice = "安装失败，请点击修改 APK 模式重试。", noticeError = true)
            render(scene, "android-apk-direct-back-inactive")
            retained()
            assertEquals(listOf("SWITCH_TO_EMBEDDED"), host.calls)
            host.state = host.state.copy(pageActive = true)
            render(scene, "android-apk-direct-back-returned")
            assertNoDuplicateInstallPanel(scene, observed)
            assertEquals(listOf("SWITCH_TO_EMBEDDED"), host.calls,
                "Returning to the selection page must not automatically restart installation")
            click(scene, observed.bounds.getValue("action:SWITCH_TO_EMBEDDED").center)
            render(scene, "android-apk-direct-retry")
            assertEquals(listOf("SWITCH_TO_EMBEDDED", "SWITCH_TO_EMBEDDED"), host.calls)
        } finally { scene.close() }
    }

    @Test fun androidWorkingFailedAndReadyStatesNeverPretendToInstall() {
        for (width in listOf(390, 1280)) {
            val observed = Observation()
            val host = Host(android(SharedGameAccessMode.APK, SharedGameAccessStatus.WORKING).copy(
                notice = "系统安装尚未完成。可取消后重试。", rows = android().rows.map { it.copy(enabled = false) } +
                    SharedGameSelectionRow(SharedGameSelectionControl.CANCEL_INSTALLATION, "取消安装")))
            val scene = scene(width, 900, host, observed)
            try {
                render(scene, "android-installing-$width")
                click(scene, observed.bounds.getValue("action:CANCEL_INSTALLATION").center)
                assertEquals(listOf("CANCEL_INSTALLATION"), host.calls)
                assertFalse(host.state.nextEnabled)
                host.state = android(SharedGameAccessMode.APK, SharedGameAccessStatus.FAILED).copy(
                    notice = "安装失败，请检查系统提示后重试。", noticeError = true)
                render(scene, "android-failed-$width")
                assertNoDuplicateInstallPanel(scene, observed)
                assertFalse("安装失败，请检查系统提示后重试。" in text(scene))
                click(scene, observed.bounds.getValue("action:SWITCH_TO_EMBEDDED").center)
                assertEquals(listOf("CANCEL_INSTALLATION", "SWITCH_TO_EMBEDDED"), host.calls)
                host.state = android(SharedGameAccessMode.APK, SharedGameAccessStatus.READY)
                render(scene, "android-bridge-ready-unverified-$width")
                assertFalse(host.state.nextEnabled, "The presentation cannot replace the host prerequisite")
                host.state = host.state.copy(canContinue = true)
                render(scene, "android-bridge-ready-$width")
                assertTrue(host.state.nextEnabled)
                host.state = android(SharedGameAccessMode.ROOT, SharedGameAccessStatus.READY).copy(canContinue = true,
                    notice = "Root 授权成功。")
                render(scene, "android-root-ready-$width")
                assertTrue(host.state.nextEnabled)
            } finally { scene.close() }
        }
    }

    @Test fun pendingAndExpiredRequestRecoveryStaysVisibleUniqueAndClickableWithoutScrolling() {
        val recoveryRows = listOf(
            SharedGameSelectionRow(SharedGameSelectionControl.OPEN_SYSTEM_CONFIRMATION, "打开系统确认"),
            SharedGameSelectionRow(SharedGameSelectionControl.CANCEL_INSTALLATION, "取消安装"),
            SharedGameSelectionRow(SharedGameSelectionControl.CHECK_EXPIRED_REQUEST, "检查过期请求"),
            SharedGameSelectionRow(SharedGameSelectionControl.CHECK_EXTERNAL_INSTALL, "检查外部安装"),
        )
        for ((width, height, fontScale) in listOf(Triple(320, 600, 1f), Triple(390, 757, 1.3f), Triple(1280, 900, 1f))) {
            val observed = Observation()
            val host = Host(android(SharedGameAccessMode.APK, SharedGameAccessStatus.WORKING).copy(
                game = SharedGameIdentity("Long game metadata", "4.0.2\n".repeat(40)),
                notice = "系统确认尚未完成，请打开系统确认或取消当前请求。".repeat(40),
                rows = android(SharedGameAccessMode.APK).rows.map { it.copy(enabled = false) } + recoveryRows.take(2)))
            val scene = scene(width, height, host, observed, fontScale)
            try {
                render(scene, "android-pending-recovery-$width")
                assertNoDuplicateInstallPanel(scene, observed)
                for (row in recoveryRows.take(2)) {
                    val button = button(scene, row.label) // single() also rejects duplicate body/footer semantics.
                    assertFalse(button.config.contains(SemanticsProperties.Disabled))
                    assertInViewport(observed.bounds.getValue("action:${row.control.name}"), width, height)
                    click(scene, button.boundsInRoot.center)
                }
                assertEquals(recoveryRows.take(2).map { it.control.name }, host.calls)
                assertTrue(button(scene, "下一步：连接云端").config.contains(SemanticsProperties.Disabled))
                assertTrue(button(scene, "选择已安装应用").config.contains(SemanticsProperties.Disabled))

                host.state = host.state.copy(access = SharedGameAccessState(SharedGameAccessMode.APK, SharedGameAccessStatus.FAILED),
                    notice = "安装请求已过期，请检查请求或取消后重试。".repeat(40), noticeError = true,
                    rows = android(SharedGameAccessMode.APK).rows.map { it.copy(enabled = false) } + recoveryRows)
                render(scene, "android-expired-recovery-$width")
                assertNoDuplicateInstallPanel(scene, observed)
                val fixed = recoveryRows.associate { it.control to observed.bounds.getValue("action:${it.control.name}") }
                for (row in recoveryRows) {
                    assertInViewport(fixed.getValue(row.control), width, height)
                    click(scene, button(scene, row.label).boundsInRoot.center)
                }
                assertEquals((recoveryRows.take(2) + recoveryRows).map { it.control.name }, host.calls)
                val bodyTop = observed.bounds.getValue("identity").top
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(width / 2f, bodyTop + 24f), scrollDelta = Offset(0f, 12f))
                render(scene, "android-expired-recovery-scrolled-$width")
                assertTrue(observed.bounds.getValue("identity").top < bodyTop)
                for (row in recoveryRows) {
                    assertEquals(fixed.getValue(row.control), observed.bounds.getValue("action:${row.control.name}"))
                    click(scene, button(scene, row.label).boundsInRoot.center)
                }
                assertEquals((recoveryRows.take(2) + recoveryRows + recoveryRows).map { it.control.name }, host.calls)

                val retained = recoveryRows.map { button(scene, it.label).config.getOrNull(SemanticsActions.OnClick)!!.action!! }
                val callCount = host.calls.size
                host.state = host.state.copy(pageActive = false)
                render(scene, "android-recovery-inactive-$width")
                recoveryRows.forEach { assertTrue(button(scene, it.label).config.contains(SemanticsProperties.Disabled)) }
                retained.forEach { it() }
                assertEquals(callCount, host.calls.size, "An inactive page cannot dispatch retained recovery callbacks")
                host.state = host.state.copy(pageActive = true, busy = true)
                render(scene, "android-recovery-busy-$width")
                retained.forEach { it() }
                assertEquals(callCount, host.calls.size, "Moving recovery controls does not bypass a busy host")
                host.state = host.state.copy(busy = false, rows = host.state.rows.map { it.copy(enabled = false) })
                render(scene, "android-recovery-disabled-$width")
                retained.forEach { it() }
                assertEquals(callCount, host.calls.size, "Disabled recovery rows remain disabled")
                host.state = host.state.copy(pageActive = true, rows = host.state.rows.filterNot { row -> recoveryRows.any { it.control == row.control } })
                render(scene, "android-recovery-resolved-$width")
                retained.forEach { it() }
                assertEquals(callCount, host.calls.size, "Resolved requests cannot dispatch stale recovery callbacks")
                assertTrue(nodes(scene).none { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                    .any { text -> recoveryRows.any { it.label == text.text } } })
            } finally { scene.close() }
        }
    }

    @Test fun failedInstallationNeverShowsWorkingTextOrProgressEvenWithStaleBusyFlag() {
        val observed = Observation()
        val host = Host(android(SharedGameAccessMode.APK, SharedGameAccessStatus.WORKING).copy(busy = true,
            notice = "正在修改或安装 APK，请完成系统提示。"))
        val scene = scene(390, 757, host, observed)
        try {
            render(scene, "android-working-progress")
            assertTrue(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            host.state = host.state.copy(access = SharedGameAccessState(SharedGameAccessMode.APK, SharedGameAccessStatus.FAILED),
                notice = "系统确认已失效，请检查请求后重试。", noticeError = true)
            render(scene, "android-failed-no-working")
            val text = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            assertFalse("系统确认已失效，请检查请求后重试。" in text)
            assertNoDuplicateInstallPanel(scene, observed)
            assertFalse(text.any { it.contains("正在修改或安装") })
            assertFalse(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
        } finally { scene.close() }
    }

    @Test fun removedInstallerEntryAndHeadingNeverReturnFromStaleHostRows() {
        for (width in listOf(320, 390, 1280)) {
            val observed = Observation()
            val host = Host(android(SharedGameAccessMode.APK, SharedGameAccessStatus.READY).copy(
                canContinue = true, notice = "", rows = android().rows +
                    SharedGameSelectionRow(SharedGameSelectionControl.USE_OTHER_INSTALLER, "选择其他安装器")))
            val scene = scene(width, 757, host, observed)
            try {
                render(scene, "android-clean-apk-ready-$width")
                assertFalse(text(scene).any { it == "选择其他安装器" || it == "安装请求" })
                assertFalse(observed.bounds.containsKey("action:USE_OTHER_INSTALLER"))
                assertFalse(observed.bounds.containsKey("notice"))
                assertFalse(observed.bounds.containsKey("installation-recovery"))
                click(scene, button(scene, "下一步：连接云端").boundsInRoot.center)
                assertEquals(listOf("next"), host.calls)
            } finally { scene.close() }
        }
    }

    @Test fun externalRequestRecoveryStaysReachableAndNeverInventsSuccess() {
        val rows = listOf(
            SharedGameSelectionRow(SharedGameSelectionControl.CHECK_EXTERNAL_INSTALL, "检查外部安装"),
            SharedGameSelectionRow(SharedGameSelectionControl.CANCEL_INSTALLATION, "结束等待"),
        )
        for (width in listOf(320, 390, 1280)) {
            val observed = Observation()
            val host = Host(android(SharedGameAccessMode.APK, SharedGameAccessStatus.WORKING).copy(
                notice = "请在选择的安装器中完成安装，返回后检查实际安装结果。".repeat(40),
                rows = android(SharedGameAccessMode.APK).rows.map { it.copy(enabled = false) } + rows))
            val scene = scene(width, 600, host, observed)
            try {
                render(scene, "android-other-installer-$width")
                val text = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                assertFalse(text.any { it.startsWith("请在选择的安装器中完成安装，返回后检查实际安装结果。") })
                assertNoDuplicateInstallPanel(scene, observed)
                assertFalse(text.any { it.contains("请完成系统提示") || it == "打开系统确认" },
                    "The external installer flow must not require the built-in system installer")
                for (row in rows) {
                    val button = button(scene, row.label)
                    assertFalse(button.config.contains(SemanticsProperties.Disabled))
                    assertInViewport(observed.bounds.getValue("action:${row.control.name}"), width, 600)
                    click(scene, button.boundsInRoot.center)
                    assertFalse(host.state.nextEnabled, "A chooser/check/end-wait callback is not installation verification")
                    assertEquals(SharedGameAccessStatus.WORKING, host.state.access?.status)
                }
                assertEquals(rows.map { it.control.name }, host.calls)
                click(scene, button(scene, "下一步：连接云端").boundsInRoot.center)
                assertEquals(rows.map { it.control.name }, host.calls)
                host.state = host.state.copy(access = SharedGameAccessState(SharedGameAccessMode.APK, SharedGameAccessStatus.READY))
                render(scene, "android-other-installer-unverified-$width")
                assertTrue(button(scene, "下一步：连接云端").config.contains(SemanticsProperties.Disabled),
                    "Even a ready badge needs the host's verified prerequisite")
            } finally { scene.close() }
        }
    }

    @Test fun sharedInstalledPickerUsesActualCandidatesAndKeepsFileChoiceReachable() {
        for (width in listOf(390, 1280)) {
            val observed = Observation()
            val host = Host(android())
            val candidates = (1..18).map { SharedGameCandidate("game.$it",
                SharedGameIdentity("Installed game $it", "1.0.$it"), "game.$it") }
            var pickerOpen by mutableStateOf(true)
            var fileChoices = 0
            val scene = ImageComposeScene(width = width, height = 900, density = Density(1f)) {
                CompositionLocalProvider(LocalSharedGameSelectionLayoutObserver provides observed) {
                    SharedGameSelectionScreen(host.state, host)
                    if (pickerOpen) SharedGamePickerDialog(candidates, "game.1", true,
                        onChoose = { host.calls += it; pickerOpen = false }, onDismiss = { pickerOpen = false },
                        fileLabel = "从手机存储选择 APK 文件…", fileDescription = "免 Root · 选取未安装的 .apk 安装包进行注入",
                        onChooseFile = { fileChoices++; pickerOpen = false }, searchQuery = "")
                }
            }
            try {
                render(scene, "android-picker-$width")
                val dialog = observed.bounds.getValue("game-picker")
                val file = observed.bounds.getValue("picker-file")
                assertTrue(dialog.width <= width - 39f)
                assertTrue(file.bottom <= 875f)
                assertTrue(observed.bounds.containsKey("candidate:game.1"))
                click(scene, file.center)
                render(scene, "android-picker-file-chosen-$width")
                assertEquals(1, fileChoices)
                assertTrue(host.calls.isEmpty(), "Choosing a file must not select a fabricated installed app")
            } finally { scene.close() }
        }
    }

    private fun text(scene: ImageComposeScene): List<String> = nodes(scene)
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun assertNoDuplicateInstallPanel(scene: ImageComposeScene, observed: Observation) {
        assertFalse("installation" in observed.bounds, "The entire duplicate installation surface must be absent")
        assertFalse("action:CONTINUE_INSTALLATION" in observed.bounds, "There must be no second start/retry button")
        val removed = setOf("该模式需为目标游戏注入同步组件并重新签名安装。", "开始注入与安装", "重试安装",
            "重新修改安装", "修改版组件已就绪，无需再次安装。", "安装遇到问题，请检查提示后重试。")
        assertTrue(text(scene).none { it in removed }, "No removed panel copy may remain in production semantics")
    }
    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }
    private fun assertInViewport(bounds: Rect, width: Int, height: Int) {
        assertTrue(bounds.left >= 28f && bounds.right <= width - 28f, "Recovery action must fit horizontally: $bounds")
        assertTrue(bounds.top >= 0f && bounds.bottom <= height - 12f && bounds.height >= 42f,
            "Recovery action must be visible and tappable without scrolling: $bounds")
    }
    private fun button(scene: ImageComposeScene, label: String): SemanticsNode = nodes(scene).single { node ->
        node.config.contains(SemanticsActions.OnClick) && descendants(node).any {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
        }
    }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        return descendants(owner.unmergedRootSemanticsNode)
    }
    private fun render(scene: ImageComposeScene, name: String): BufferedImage {
        scene.render(0).close(); scene.render(16_000_000).close()
        return scene.render(32_000_000).use { image ->
            val file = Path.of("build/reports/shared-game-selection", "$name.png")
            Files.createDirectories(file.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                Files.write(file, data.bytes)
                ImageIO.read(ByteArrayInputStream(data.bytes))
            }
        }
    }
    private fun assertCardInViewport(image: BufferedImage) {
        val margin = 2 // Ambient shadow extends outside the card.
        for (x in 0 until margin) for (y in 0 until image.height) {
            assertEquals(0xf8fafc, image.getRGB(x, y) and 0xffffff)
            assertEquals(0xf8fafc, image.getRGB(image.width - x - 1, y) and 0xffffff)
        }
    }
}
