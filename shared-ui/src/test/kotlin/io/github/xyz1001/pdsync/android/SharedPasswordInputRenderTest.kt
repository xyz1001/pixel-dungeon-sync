@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.xyz1001.pdsync.android

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.Density
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.*

/** Production Compose fields with a recording platform input connection. This does not
 * emulate Android's keyboard window or prove vendor-specific Android IME behavior. */
class SharedPasswordInputRenderTest {
    @Test fun passwordTypingAndVisibilityPreserveTheActiveInputSession() {
        for (origin in ConnectionEntryModel.Origin.values()) {
            for (mode in listOf(ConnectionEntryModel.Mode.OFFICIAL, ConnectionEntryModel.Mode.CUSTOM)) {
                val controller = controller(origin)
                controller.choose(mode)
                val platform = RecordingInputService()
                val scene = ImageComposeScene(width = 420, height = 1100, density = Density(1f)) {
                    InterceptPlatformTextInput(interceptor = { request, _ ->
                        platform.starts++
                        platform.request = request
                        try { awaitCancellation() } finally { platform.stops++ }
                    }) {
                        SharedConnectionScreen(controller)
                    }
                }
                var time = 0L
                fun settle() { repeat(10) { time += 16_666_667L; scene.render(time).close() } }
                fun password() = nodes(scene).single {
                    it.config.contains(SemanticsProperties.EditableText) &&
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("密码凭据") == true
                }
                fun toggle(label: String) {
                    val button = nodes(scene).single {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
                            it.config.contains(SemanticsActions.OnClick)
                    }
                    scene.sendPointerEvent(PointerEventType.Press, button.boundsInRoot.center)
                    scene.sendPointerEvent(PointerEventType.Release, button.boundsInRoot.center)
                    settle()
                }
                try {
                    settle()
                    assertTrue(password().config.contains(SemanticsProperties.Password), "hidden by default: $origin/$mode")
                    assertTrue(assertNotNull(password().config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
                    settle()
                    assertEquals(1, platform.starts, "focus starts one platform input session")
                    for (letter in "a1B2_3") {
                        platform.edit(listOf(CommitTextCommand(letter.toString(), 1)))
                        settle()
                        assertEquals(1, platform.starts, "typing must not restart input")
                        assertEquals(0, platform.stops, "typing must not close input")
                        assertEquals(true, password().config.getOrNull(SemanticsProperties.Focused))
                    }
                    assertEquals("a1B2_3", controller.state.password)
                    platform.edit(listOf(SetSelectionCommand(2, 4), SetComposingRegionCommand(1, 5)))
                    settle()
                    val before = platform.value
                    assertEquals(TextRange(2, 4), before.selection)
                    assertEquals(TextRange(1, 5), before.composition)
                    for ((label, hidden) in listOf("显示密码" to false, "隐藏密码" to true, "显示密码" to false)) {
                        toggle(label)
                        val layouts = mutableListOf<TextLayoutResult>()
                        assertTrue(assertNotNull(password().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
                        assertEquals(if (hidden) "•".repeat(6) else "a1B2_3", layouts.single().layoutInput.text.text,
                            "visibility changes rendering without changing password keyboard semantics")
                        assertEquals("a1B2_3", controller.state.password)
                        assertEquals(before, platform.value, "toggle preserves value, selection and composing region")
                        assertEquals(TextRange(2, 4), password().config.getOrNull(SemanticsProperties.TextSelectionRange))
                        assertEquals(true, password().config.getOrNull(SemanticsProperties.Focused), "toggle retains focus")
                        assertEquals(1, platform.starts, "toggle must not restart keyboard input")
                        assertEquals(0, platform.stops)
                    }
                    // The same connection still delivers a composing edit after toggling.
                    platform.edit(listOf(SetComposingTextCommand("XY", 1)))
                    settle()
                    assertEquals("aXY3", controller.state.password)
                    assertEquals(1, platform.starts)
                    // A new account must clear the old credential and restore default masking.
                    controller.editUsername("newuser")
                    settle()
                    assertEquals("", controller.state.password)
                    assertEquals("", platform.value.text)
                    assertEquals(null, platform.value.composition)
                    assertTrue(nodes(scene).any {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("显示密码") == true
                    })
                } finally { scene.close(); controller.close() }
            }
        }
    }

    private class RecordingInputService {
        var starts = 0
        var stops = 0
        lateinit var request: PlatformTextInputMethodRequest
        val value: TextFieldValue get() = request.value()
        fun edit(commands: List<EditCommand>) {
            assertEquals(KeyboardType.Password, request.imeOptions.keyboardType,
                "reveal must retain password keyboard")
            request.onEditCommand(commands)
        }
    }

    private fun controller(origin: ConnectionEntryModel.Origin) = SharedConnectionController(origin, null, "slot",
        object : SharedConnectionPorts {
            override fun testConnection(config: SharedConnectionConfig): Boolean = error("No network")
            override fun saveAndActivate(config: SharedConnectionConfig) = error("No persistence")
            override fun cacheDirectory(): Path? = null
            override fun elapsedRealtimeMs() = 0L
            override fun bootCount() = 1
            override fun copyUsername(username: String) = Unit
            override fun decodeImage(bytes: ByteArray) = null
        }, Executor { error("No async work") }, Executor { it.run() })

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return descend(owner.unmergedRootSemanticsNode)
    }
}
