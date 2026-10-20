@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package io.github.xyz1001.pdsync.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.geometry.Offset
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
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

class SharedExportFeedbackRenderTest {
    @Test fun touchHoldReleaseCancelAndDisabledRenderExistingButtonFeedback() {
        val baseline = System.getenv("PDSYNC_PRESS_BASELINE") == "1"
        for (step in listOf(1, 2)) {
            var exports = 0
            val action = SharedDiagnosticAction { exports++ }
            val controller = controller()
            val scene = ImageComposeScene(354, 800, Density(1f)) {
                CompositionLocalProvider(LocalSharedDiagnosticAction provides action,
                    LocalSharedInputViewport provides androidx.compose.ui.unit.IntSize(354, 800)) {
                    if (step == 1) SharedGameSelectionScreen(SharedGameSelectionState(), object : SharedGameSelectionActions {
                        override fun perform(control: SharedGameSelectionControl) = Unit
                        override fun chooseCandidate(id: String) = Unit
                        override fun next() = Unit
                    }) else SharedConnectionScreen(controller)
                }
            }
            try {
                settle(scene)
                val label = text(scene, "导出日志")
                val control = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button && it.boundsInRoot.contains(label.boundsInRoot.center) }.minBy { it.boundsInRoot.width }
                val bounds = control.boundsInRoot
                val point = bounds.center
                val sample = Offset(bounds.left + 12, bounds.center.y)
                fun pixel(): Int = scene.render(frameTime + 16_666_667L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
                    ImageIO.read(ByteArrayInputStream(it.bytes)).getRGB(sample.x.toInt(), sample.y.toInt())
                } }
                val idle = pixel()
                screenshot(scene, "step$step-touch-idle")
                scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Touch)
                settle(scene)
                screenshot(scene, "step$step-touch-held")
                val held = pixel()
                assertEquals(0, exports, "touch down must not invoke export")
                if (baseline) assertEquals(idle, held, "code71 pure touch has no hover fill")
                else assertEquals(0xfff1f5f9.toInt(), held, "touch hold uses existing reference hover fill")
                scene.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Touch)
                settle(scene)
                screenshot(scene, "step$step-touch-released")
                assertEquals(1, exports)
                assertEquals(idle, pixel(), "touch up clears fill, without sticky hover")
                scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Touch)
                settle(scene)
                scene.sendPointerEvent(PointerEventType.Move, Offset(5f, 700f), type = PointerType.Touch)
                settle(scene)
                scene.sendPointerEvent(PointerEventType.Release, Offset(5f, 700f), type = PointerType.Touch)
                settle(scene)
                assertEquals(1, exports, "drag out cancels export")
                assertEquals(idle, pixel())
                action.enabled = false
                settle(scene)
                val disabled = pixel()
                scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Touch)
                settle(scene)
                screenshot(scene, "step$step-touch-disabled")
                assertEquals(disabled, pixel())
                scene.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Touch)
                settle(scene)
                assertEquals(1, exports)
                action.enabled = true
                settle(scene)
                repeat(3) {
                    scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Touch)
                    repeat(4) { frameTime += 16_666_667L; scene.render(frameTime).close() }
                    if (!baseline) assertNotEquals(idle, pixel(), "short touch already has visible feedback")
                    scene.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Touch)
                    settle(scene)
                    assertEquals(idle, pixel())
                }
                assertEquals(4, exports, "each completed touch invokes exactly once")
                scene.sendPointerEvent(PointerEventType.Move, point, type = PointerType.Mouse)
                settle(scene)
                assertEquals(0xfff1f5f9.toInt(), pixel(), "mouse hover reference unchanged")
                screenshot(scene, "step$step-mouse-hover")
                scene.sendPointerEvent(PointerEventType.Move, Offset(5f, 700f), type = PointerType.Mouse)
                settle(scene)
                assertEquals(idle, pixel())
            } finally { scene.close(); controller.close() }
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
        val dir = Path.of("../../code72-evidence", if (System.getenv("PDSYNC_PRESS_BASELINE") == "1") "before" else "after")
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
