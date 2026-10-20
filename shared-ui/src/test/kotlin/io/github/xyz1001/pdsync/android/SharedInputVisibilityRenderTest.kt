@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.toComposeEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.awt.Component
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real shared Compose, focus, typing and scrolling with a host-supplied reduced viewport.
 * This is not an Android IME/insets/device test. No network or authentication is performed.
 */
class SharedInputVisibilityRenderTest {
    @Test fun setupAndSettingsOfficialAndCustomKeepFocusedCredentialsAboveReducedViewport() {
        for (origin in ConnectionEntryModel.Origin.values()) {
            for (mode in listOf(ConnectionEntryModel.Mode.OFFICIAL, ConnectionEntryModel.Mode.CUSTOM)) {
                exercise(origin, mode, fontScale = 1f)
            }
        }
    }

    @Test fun largerTextAndLandscapeCanScrollTheWholeFormInsteadOfCollapsingBodyToZero() {
        exercise(ConnectionEntryModel.Origin.SETTINGS, ConnectionEntryModel.Mode.CUSTOM, fontScale = 1.4f)
    }

    @Test fun desktopDefaultDoesNotOptIntoAndroidWholeCardScroll() {
        var suppliedViewport: IntSize? = IntSize.Zero
        val scene = ImageComposeScene(width = 320, height = 300, density = Density(1f)) {
            suppliedViewport = LocalSharedInputViewport.current
            SharedConnectionTheme {
                SharedAppShell("连接云端", footer = { SharedActionButton("保存", {}) }) {
                    repeat(8) { SharedTextField("字段 $it", "", {}) }
                }
            }
        }
        try {
            repeat(5) { scene.render(it * 16_666_667L).close() }
            assertEquals(null, suppliedViewport, "desktop host does not install Android input behavior")
            fun footer() = nodes(scene).single {
                it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "保存"
            }.boundsInRoot
            val before = footer()
            assertTrue(before.height > 0 && before.bottom <= 300, "desktop footer remains in viewport")
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(160f, 170f), scrollDelta = Offset(0f, 20f))
            repeat(30) { scene.render((it + 5) * 16_666_667L).close() }
            assertEquals(before, footer(), "default desktop footer does not join whole-card scrolling")
        } finally { scene.close() }
    }

    private fun exercise(origin: ConnectionEntryModel.Origin, mode: ConnectionEntryModel.Mode, fontScale: Float) {
        val controller = SharedConnectionController(origin, null, "slot", object : SharedConnectionPorts {
            override fun testConnection(config: SharedConnectionConfig): Boolean = error("No network in UI regression")
            override fun saveAndActivate(config: SharedConnectionConfig) = error("No persistence in UI regression")
            override fun cacheDirectory(): Path? = null
            override fun elapsedRealtimeMs() = 0L
            override fun bootCount() = 1
            override fun copyUsername(username: String) = Unit
            override fun decodeImage(bytes: ByteArray) = null
        }, Executor { error("No asynchronous account work in a layout regression") }, Executor { it.run() })
        controller.choose(mode)
        val viewport = mutableStateOf(IntSize(320, 900))
        val scene = ImageComposeScene(width = 760, height = 940, density = Density(1f, fontScale)) {
            Box(Modifier.size(viewport.value.width.dp, viewport.value.height.dp)) {
                CompositionLocalProvider(LocalSharedInputViewport provides viewport.value,
                    LocalSharedDiagnosticAction provides if (origin == ConnectionEntryModel.Origin.SETUP) SharedDiagnosticAction {} else null) {
                    SharedConnectionScreen(controller)
                }
            }
        }
        var time = 0L
        fun settle() {
            // Drives actual bring-into-view animations and frame-delayed relocation.
            repeat(60) { time += 16_666_667L; scene.render(time).close() }
        }
        fun focus(label: String) {
            val request = input(scene, label).config.getOrNull(SemanticsActions.RequestFocus)
            assertTrue(assertNotNull(request).action?.invoke() == true, "$origin/$mode focus $label")
            settle()
        }
        fun visible(label: String) {
            val field = input(scene, label)
            assertEquals(true, field.config.getOrNull(SemanticsProperties.Focused), "focus survives resize: $label")
            val bounds = field.boundsInRoot
            assertTrue(bounds.height >= 19f * fontScale, "input is not clipped away: $origin/$mode $label $bounds")
            assertTrue(bounds.top >= 0 && bounds.bottom <= viewport.value.height,
                "input is above the keyboard viewport: $origin/$mode $label $bounds in ${viewport.value}")
            val caption = nodes(scene).single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == label
            }.boundsInRoot
            assertTrue(caption.height >= 18f * fontScale && caption.top >= 0 && caption.bottom <= viewport.value.height,
                "the field label also scrolls into view: $label $caption")
        }
        try {
            settle()
            // The same two fields serve both built-in login and registration.
            if (mode == ConnectionEntryModel.Mode.OFFICIAL) {
                for (action in listOf("登录账号", "注册新用户")) assertTrue(nodes(scene).any { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == action } == true
                })
            }
            // Click the actual username at the full-height viewport, then open the keyboard.
            val username = input(scene, "用户名").boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, username)
            scene.sendPointerEvent(PointerEventType.Release, username)
            settle()
            visible("用户名")
            viewport.value = IntSize(320, 400)
            settle()
            visible("用户名")
            focus("密码凭据")
            visible("密码凭据")
            typeX(scene)
            settle()
            assertEquals("x", controller.state.password, "focused password remains editable")

            // Taller IME/landscape: the old fixed chrome leaves no usable field region here.
            viewport.value = IntSize(740, 210)
            settle()
            visible("密码凭据")
            focus("用户名")
            visible("用户名")
            if (mode == ConnectionEntryModel.Mode.CUSTOM) {
                focus("WebDAV 服务器地址")
                visible("WebDAV 服务器地址")
            }
            focus("密码凭据")
            visible("密码凭据")
            // Close/reopen and rotate back without losing values or the active input.
            for (size in listOf(IntSize(320, 900), IntSize(320, 380), IntSize(320, 900))) {
                viewport.value = size
                settle()
                visible("密码凭据")
            }
            assertEquals("x", controller.state.password)
        } finally {
            scene.close()
            controller.close()
        }
    }

    private fun input(scene: ImageComposeScene, label: String) = nodes(scene).single {
        it.config.contains(SemanticsProperties.EditableText) &&
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }

    private fun typeX(scene: ImageComposeScene) {
        val source = object : Component() {}
        for ((id, code) in listOf(KeyEvent.KEY_PRESSED to KeyEvent.VK_X,
            KeyEvent.KEY_TYPED to KeyEvent.VK_UNDEFINED, KeyEvent.KEY_RELEASED to KeyEvent.VK_X)) {
            scene.sendKeyEvent(KeyEvent(source, id, 1L, 0, code, 'x').toComposeEvent())
        }
    }

    /** Same test-only accessor as other pinned ImageComposeScene tests in this source set. */
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return descend(owner.unmergedRootSemanticsNode)
    }
}
