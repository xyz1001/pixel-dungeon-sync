@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.toComposeEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.Component
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryNode
import pdsync.presentation.HistoryPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real shared history layout and pointer/text/key dispatch; persistence is a recording host. */
class SharedInlineNamingRenderTest {
    private var frame = 0L
    private val presentation = HistoryPresentation(listOf(
        HistoryNode("cloud", "real-snapshot", "云端存档", "10-09 09:00", "2 个槽位", "云端当前分支", 0, 0, selectable = true),
        HistoryNode("local-baseline", "real-snapshot", "同步基线", "10-09 09:00", "2 个槽位", "已同步云端", 1, 0),
        HistoryNode("local-current", null, "本机存档", "10-09 10:00", "2 个槽位", "未同步云端", 1, 1, dirty = true)
    ), listOf(HistoryEdge("cloud", "local-baseline"), HistoryEdge("local-baseline", "local-current", true)),
        0, 1, 2, 1, false)

    @Test fun editorStaysUnderClickedNodeAndSaveCancelEscapeUseTheActualDraft() {
        for (width in listOf(1280, 390)) for (action in listOf("save", "cancel", "escape", "enter")) {
            val controller = SharedHistoryNamingController()
            val observation = Observation()
            val saves = mutableListOf<Pair<String?, String>>()
            val scene = ImageComposeScene(width, 900, density = Density(1f)) {
                SharedConnectionTheme {
                    CompositionLocalProvider(LocalSharedHistoryLayoutObserver provides observation,
                        LocalSharedNamingLayoutObserver provides observation) {
                        Column(Modifier.fillMaxSize().background(SharedUiColors.background).padding(16.dp)) {
                            SharedHistorySection(presentation, null, false, {}, { id ->
                                controller.open(id, "原名称", "", { saves += id to it }, null)
                            }, {}, naming = controller.naming)
                        }
                    }
                }
            }
            try {
                render(scene, "$width-$action-closed")
                val nodeId = if (width == 1280) "local-baseline" else "cloud"
                val closedLayout = assertNotNull(observation.layout)
                click(scene, observation.renamePoint(nodeId))
                render(scene, "$width-$action-open")
                val field = observation.fields.getValue("name")
                val card = observation.rootRect(observation.layout!!.cards.getValue(nodeId))
                assertTrue(field.top >= card.bottom, "editor is below the node, not a modal")
                assertTrue(field.left >= card.left && field.right <= card.right)
                val editorRows = observation.layout!!.renameActions.values.filter { it.height > 30 }
                assertEquals(1, editorRows.size, "cloud and baseline share an alias, never duplicate the editor")
                assertTrue(observation.layout!!.height > closedLayout.height)
                val setText = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
                    .config.getOrNull(SemanticsActions.SetText)!!.action!!
                setText(AnnotatedString("待保存的新名称"))
                render(scene, "$width-$action-draft")
                assertEquals("待保存的新名称", controller.naming!!.state.name)
                assertTrue(saves.isEmpty(), "editing never persists an alias")
                when (action) {
                    "save" -> click(scene, observation.fields.getValue("save").center)
                    "cancel" -> click(scene, observation.fields.getValue("cancel").center)
                    "enter" -> key(scene, KeyEvent.VK_ENTER)
                    else -> key(scene, KeyEvent.VK_ESCAPE)
                }
                render(scene, "$width-$action-after")
                assertNull(controller.naming)
                assertEquals(if (action in listOf("save", "enter")) listOf("real-snapshot" to "待保存的新名称") else emptyList(), saves)
                assertEquals(closedLayout.height, observation.layout!!.height)
                setText(AnnotatedString("已关闭编辑器的回调"))
                assertNull(controller.naming)
            } finally { scene.close(); controller.close() }
        }
    }

    @Test fun currentDirectoryIdentityActionDoesNotSaveTheDraftOrChangeTargetIdentity() {
        val controller = SharedHistoryNamingController()
        val observation = Observation()
        val calls = mutableListOf<String>()
        val scene = ImageComposeScene(1280, 900, density = Density(1f)) {
            SharedConnectionTheme {
                CompositionLocalProvider(LocalSharedHistoryLayoutObserver provides observation,
                    LocalSharedNamingLayoutObserver provides observation) {
                    SharedHistorySection(presentation, null, false, {}, { id ->
                        controller.open(id, "本机名称", "", { calls += "save:$id:$it" }, { calls += "identity:$id" })
                    }, {}, naming = controller.naming)
                }
            }
        }
        try {
            render(scene, "current-closed")
            click(scene, observation.renamePoint("local-current"))
            render(scene, "current-open")
            assertNull(controller.naming!!.snapshotId, "null still means the real local directory")
            click(scene, observation.fields.getValue("maintenance").center)
            render(scene, "current-maintenance")
            assertNull(controller.naming)
            assertEquals(listOf("identity:null"), calls)
        } finally { scene.close(); controller.close() }
    }

    @Test fun busyReplacedAndDisposedInlineCallbacksCannotSaveCancelOrMaintainIdentity() {
        var state by mutableStateOf(SharedNamingState(8, "名称"))
        val observation = Observation()
        val calls = mutableListOf<String>()
        val scene = ImageComposeScene(390, 900, density = Density(1f)) {
            SharedConnectionTheme {
                CompositionLocalProvider(LocalSharedNamingLayoutObserver provides observation) {
                    SharedInlineNaming(SharedHistoryNaming(null, state, { calls += "edit" },
                        { calls += "save" }, { calls += "cancel" }, { calls += "identity" }))
                }
            }
        }
        var retained = emptyList<() -> Boolean>()
        try {
            render(scene, "busy-before")
            retained = nodes(scene).mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }
            assertTrue(retained.size >= 3)
            state = state.copy(busy = true)
            render(scene, "busy-disabled")
            for (name in listOf("save", "cancel", "maintenance")) click(scene, observation.fields.getValue(name).center)
            retained.forEach { it() }
            key(scene, KeyEvent.VK_ESCAPE)
            assertTrue(calls.isEmpty())
            state = state.copy(token = 9, busy = false)
            render(scene, "busy-replaced")
            retained.forEach { it() }
            assertTrue(calls.isEmpty(), "old token cannot act on a replacement editor")
        } finally { scene.close() }
        retained.forEach { it() }
        assertTrue(calls.isEmpty(), "disposed fields cannot act through retained semantics")
    }

    private class Observation : SharedHistoryLayoutObserver, SharedNamingLayoutObserver {
        var layout: SharedHistoryCanvasLayout? = null
        var origin = Offset.Zero
        val fields = mutableMapOf<String, Rect>()
        override fun canvas(positionInRoot: Offset, layout: SharedHistoryCanvasLayout) {
            this.origin = positionInRoot; this.layout = layout
        }
        override fun element(name: String, boundsInRoot: Rect) { fields[name] = boundsInRoot }
        fun rootRect(rect: Rect) = rect.translate(origin)
        fun renamePoint(id: String) = rootRect(layout!!.renameActions.getValue(id)).let {
            Offset(it.right - 20f, it.center.y)
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return descendants(owner.unmergedRootSemanticsNode)
    }

    private fun click(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }

    private fun key(scene: ImageComposeScene, code: Int) {
        val source = object : Component() {}
        for (id in listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED))
            scene.sendKeyEvent(KeyEvent(source, id, 1L, 0, code, KeyEvent.CHAR_UNDEFINED).toComposeEvent())
    }

    private fun render(scene: ImageComposeScene, name: String) {
        repeat(3) { scene.render(++frame * 16_000_000L).close() }
        scene.render(++frame * 16_000_000L).use { image ->
            val path = Path.of("build/reports/shared-inline-naming", "$name.png")
            Files.createDirectories(path.parent)
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(path, it.bytes) }
        }
    }
}
