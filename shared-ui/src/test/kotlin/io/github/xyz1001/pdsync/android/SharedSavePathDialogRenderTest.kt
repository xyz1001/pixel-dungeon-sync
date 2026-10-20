@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings editor retains host validation and native folder selection; setup stays read-only. */
class SharedSavePathDialogRenderTest {
    private class Actions(val edit: (String) -> Unit) : SharedSavePathActions {
        val calls = mutableListOf<String>()
        override fun editPath(path: String) { calls += "edit"; edit(path) }
        override fun chooseDirectory() { calls += "choose" }
        override fun useDefault() { calls += "default" }
        override fun retry() { calls += "retry" }
        override fun initializeGame() { calls += "initialize" }
        override fun back() { calls += "cancel" }
        override fun next() { calls += "validate" }
    }

    @Test fun referenceModalRendersAtBothViewportsAndValidatesOnlyOnExplicitSave() {
        for (width in listOf(1280, 390, 320)) {
            val state = mutableStateOf(SharedSavePathState(
                "C:\\Users\\Demo\\AppData\\Roaming\\.shatteredpixel\\Shattered Pixel Dungeon",
                editable = true, summary = null, readStatus = SharedSavePathReadStatus.READY,
                canContinue = true, canRequestRead = true))
            val actions = Actions { state.value = state.value.copy(path = it) }
            val scene = ImageComposeScene(width = width, height = 900, density = Density(1f)) {
                SharedConnectionTheme {
                    Box(Modifier.fillMaxSize().background(SharedUiColors.background)) {
                        SharedSettingsScreen(SharedSettingsState(SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8"),
                            listOf(
                                SharedSettingsRow(SharedSettingsKey.GAME, "游戏管理", "Shattered Pixel Dungeon · 3.3.8", "更换游戏", true),
                                SharedSettingsRow(SharedSettingsKey.CLOUD_CONNECTION, "云端连接", "内置同步服务", "连接设置", true),
                                SharedSettingsRow(SharedSettingsKey.AUTOMATIC_UPLOAD, "自动备份", "已暂停自动上传", "开启自动备份", true),
                                SharedSettingsRow(SharedSettingsKey.REVIEW_SETUP, "使用引导", "重新查看初次配置步骤向导", "查看向导", true),
                                SharedSettingsRow(SharedSettingsKey.SAVE_PATH, "存档目录", state.value.path, "更改路径", true),
                            ), pageActive = false), object : SharedSettingsActions {
                                override fun perform(key: SharedSettingsKey) = Unit
                                override fun back() = Unit
                            })
                        Box(Modifier.fillMaxSize().background(Color(0xFF0F172A).copy(alpha = .5f))
                            .padding(horizontal = 19.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                            SharedSavePathDialogBody(state.value, actions,
                                Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 852.dp))
                        }
                    }
                }
            }
            try {
                render(scene, "$width-path-dialog")
                assertTrue(actions.calls.isEmpty(), "rendering never edits or saves the directory")
                val field = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
                assertTrue(field.boundsInRoot.left >= 19 && field.boundsInRoot.right <= width - 19)
                assertTrue(field.boundsInRoot.width <= 424)
                assertTrue(field.config.getOrNull(SemanticsActions.SetText)!!.action!!.invoke(AnnotatedString("/fixture/new-save")))
                render(scene, "$width-path-edited")
                assertEquals("/fixture/new-save", state.value.path)
                assertEquals(listOf("edit"), actions.calls)
                click(scene, "保存并验证")
                click(scene, "取消")
                assertEquals(listOf("edit", "validate", "cancel"), actions.calls)

                state.value = state.value.copy(canChooseDirectory = true)
                render(scene, "$width-path-chooser")
                click(scene, "选择文件夹…")
                assertEquals("choose", actions.calls.last())
                state.value = state.value.copy(busy = true)
                render(scene, "$width-path-busy")
                val before = actions.calls.toList()
                for (label in listOf("保存并验证", "取消", "选择文件夹…")) {
                    assertTrue(button(scene, label).config.contains(SemanticsProperties.Disabled))
                    click(scene, label)
                }
                assertEquals(before, actions.calls, "hard operations block all stale clicks")
            } finally { scene.close() }
        }
    }

    @Test fun failedValidationCannotEnableSaveWithoutRetryCapability() {
        val state = SharedSavePathState("/fixture/unsafe", editable = true, summary = null,
            readStatus = SharedSavePathReadStatus.FAILED, canContinue = false, canRequestRead = true,
            canRetry = false, notice = "无法读取存档，请重试。")
        val actions = Actions {}
        val scene = ImageComposeScene(width = 390, height = 420, density = Density(1f)) {
            SharedConnectionTheme { SharedSavePathDialogBody(state, actions, Modifier.fillMaxWidth()) }
        }
        try {
            render(scene, "390-path-failed")
            assertTrue(button(scene, "保存并验证").config.contains(SemanticsProperties.Disabled))
            assertFalse(button(scene, "取消").config.contains(SemanticsProperties.Disabled))
            click(scene, "保存并验证")
            assertTrue(actions.calls.isEmpty())
            click(scene, "取消")
            assertEquals(listOf("cancel"), actions.calls)
        } finally { scene.close() }
    }

    private fun render(scene: ImageComposeScene, name: String) {
        repeat(3) { scene.render((it + 1) * 16_000_000L).close() }
        scene.render(64_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
            val output = Path.of("build/reports/shared-save-path-dialog/$name.png")
            Files.createDirectories(output.parent); Files.write(output, data.bytes)
        } }
    }
    private fun click(scene: ImageComposeScene, label: String) {
        val point = button(scene, label).boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }
    private fun button(scene: ImageComposeScene, label: String): SemanticsNode = nodes(scene).single { node ->
        node.config.contains(SemanticsActions.OnClick) && descendants(node).any {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
        }
    }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        return descendants(owner.unmergedRootSemanticsNode)
    }
}
