@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package io.github.xyz1001.pdsync.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.*

class SharedWizardHeaderRenderTest {
    @Test fun exportCentersOnBothTitleStacksAndUsesExistingActionAcrossNavigationAndBusyChanges() {
        for (width in listOf(280, 320, 354, 412, 800)) for (fontScale in listOf(1f, 1.4f)) {
            val step = mutableStateOf(1)
            var exports = 0
            val action = SharedDiagnosticAction { exports++ }
            val controller = controller()
            val scene = ImageComposeScene(width = width, height = 900, density = Density(1f, fontScale)) {
                CompositionLocalProvider(LocalSharedDiagnosticAction provides action) {
                    if (step.value == 1) SharedGameSelectionScreen(SharedGameSelectionState(),
                        object : SharedGameSelectionActions {
                            override fun perform(control: SharedGameSelectionControl) = Unit
                            override fun chooseCandidate(id: String) = Unit
                            override fun next() { step.value = 2 }
                        }) else SharedConnectionScreen(controller)
                }
            }
            try {
                for (current in listOf(1, 2, 1, 2)) {
                    step.value = current
                    settle(scene)
                    if (fontScale == 1f) screenshot(scene, "wizard-step$current-$width")
                    val button = text(scene, "导出日志")
                    val eyebrow = text(scene, "第 $current 步，共 2 步")
                    assertEquals(1, nodes(scene).count { label(it) == "导出日志" })
                    assertTrue(button.boundsInRoot.left >= eyebrow.boundsInRoot.right)
                    assertTrue(button.boundsInRoot.right <= width - 20)
                    val title = text(scene, if (current == 1) "选择游戏" else "连接云端")
                    assertEquals((eyebrow.boundsInRoot.top + title.boundsInRoot.bottom) / 2,
                        button.boundsInRoot.center.y, 1f)
                    val control = nodes(scene).filter {
                        it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button &&
                            it.boundsInRoot.contains(button.boundsInRoot.center)
                    }.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
                    if (fontScale == 1f) {
                        assertEquals(112f, control.boundsInRoot.width, 1f, "reference natural button width")
                        assertEquals(38f, control.boundsInRoot.height, 1f, "reference padded button height")
                    }
                    val before = exports
                    click(scene, button)
                    assertEquals(before + 1, exports)
                    action.enabled = false
                    settle(scene)
                    if (width == 354 && fontScale == 1f) screenshot(scene, "wizard-step$current-disabled-$width")
                    click(scene, text(scene, "导出日志"))
                    assertEquals(before + 1, exports, "disabled export cannot start another picker")
                    action.enabled = true
                    settle(scene)
                }
            } finally { scene.close(); controller.close() }
        }
    }

    @Test fun credentialsExplainRegistrationLoginAndCustomRulesWithoutChangingInputs() {
        for (width in listOf(280, 320, 412)) {
            val controller = controller()
            controller.choose(ConnectionEntryModel.Mode.OFFICIAL)
            val scene = ImageComposeScene(width = width, height = 1500, density = Density(1f)) {
                CompositionLocalProvider(LocalSharedDiagnosticAction provides SharedDiagnosticAction {}) {
                    SharedConnectionScreen(controller)
                }
            }
            try {
                settle(scene)
                val username = text(scene, "注册3–32位小写字母/数字")
                val password = text(scene, "注册6–128字符")
                for (node in listOf(username, password)) {
                    assertTrue(node.boundsInRoot.height > 0, "placeholder remains visible")
                    assertTrue(node.boundsInRoot.left >= 20 && node.boundsInRoot.right <= width - 20)
                    assertTrue(node.boundsInRoot.bottom <= 1500)
                }
                assertTrue(username.boundsInRoot.bottom < text(scene, "密码凭据").boundsInRoot.top)
                assertTrue(password.boundsInRoot.bottom < text(scene, "登录账号").boundsInRoot.top)
                assertEquals(2, nodes(scene).count { it.config.contains(SemanticsProperties.EditableText) })
                assertEquals("", controller.state.username)
                assertEquals("", controller.state.password)
                assertTrue(nodes(scene).filter { it.config.contains(SemanticsProperties.EditableText) }
                    .all { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" },
                    "placeholders must not become editable credential values")
                screenshot(scene, "credential-rules-$width")
                controller.editUsername("0name")
                controller.editPassword("😀😀😀😀😀😀")
                settle(scene)
                assertEquals("0name", controller.state.username)
                assertEquals("😀😀😀😀😀😀", controller.state.password)
                assertFalse(nodes(scene).any { label(it) in listOf("注册3–32位小写字母/数字", "注册6–128字符") },
                    "placeholder disappears when a value exists")
                controller.editUsername("")
                controller.editPassword("")
                settle(scene)
                text(scene, "注册3–32位小写字母/数字")
                text(scene, "注册6–128字符")
                controller.showChooser()
                controller.choose(ConnectionEntryModel.Mode.CUSTOM)
                settle(scene)
                assertFalse(nodes(scene).any { label(it) in listOf("注册3–32位小写字母/数字", "注册6–128字符") })
                controller.editUsername("")
                controller.editPassword("")
                settle(scene)
                text(scene, "服务商提供的用户名")
                text(scene, "密码或应用专用密码")
                screenshot(scene, "credential-custom-$width")
            } finally { scene.close(); controller.close() }
        }
    }

    @Test fun desktopAndNonWizardHeadersDoNotGainDuplicateExportControls() {
        for (supplied in listOf(false, true)) for (step in listOf<Int?>(null, 1, 2)) {
            val scene = ImageComposeScene(width = 320, height = 700, density = Density(1f)) {
                CompositionLocalProvider(LocalSharedDiagnosticAction provides
                    if (supplied) SharedDiagnosticAction {} else null) {
                    SharedConnectionTheme {
                        SharedAppShell("页面", eyebrow = if (step == null) null else "第 $step 步，共 2 步",
                            onboardingStep = step) {}
                    }
                }
            }
            try {
                settle(scene)
                assertEquals(if (supplied && step != null) 1 else 0,
                    nodes(scene).count { label(it) == "导出日志" })
            } finally { scene.close() }
        }
    }

    private fun controller() = SharedConnectionController(ConnectionEntryModel.Origin.SETUP, null, "slot",
        object : SharedConnectionPorts {
            override fun testConnection(config: SharedConnectionConfig): Boolean = error("No network")
            override fun saveAndActivate(config: SharedConnectionConfig) = error("No persistence")
            override fun cacheDirectory(): Path? = null
            override fun elapsedRealtimeMs() = 0L
            override fun bootCount() = 1
            override fun copyUsername(username: String) = Unit
            override fun decodeImage(bytes: ByteArray) = null
        }, Executor { error("No async work") }, Executor { it.run() })
    private fun label(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
    private fun text(scene: ImageComposeScene, value: String) = nodes(scene).single { label(it) == value }
    private var frameTime = 0L
    private fun settle(scene: ImageComposeScene) { repeat(30) { frameTime += 16_666_667L; scene.render(frameTime).close() } }
    private fun click(scene: ImageComposeScene, node: SemanticsNode) {
        scene.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center)
        scene.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center)
        settle(scene)
    }
    private fun screenshot(scene: ImageComposeScene, name: String) {
        val dir = Path.of("../../code73-evidence/after")
        Files.createDirectories(dir)
        scene.render(frameTime + 16_666_667L).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(dir.resolve("$name.png"), it.bytes) }
        }
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return descend(owner.unmergedRootSemanticsNode)
    }
}
