@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package io.github.xyz1001.pdsync.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.*

/** Production shared UI with Android's viewport opt-in; not an Android device/insets test. */
class SharedMobileSpacingRenderTest {
    private val baseline = System.getenv("PDSYNC_LAYOUT_BASELINE") == "1"
    private val output = Path.of("../../code71-evidence", if (baseline) "before" else "after")
    private var clock = 0L
    private fun settle(scene: ImageComposeScene) { repeat(20) { clock += 16_666_667; scene.render(clock).close() } }
    private fun capture(scene: ImageComposeScene, name: String) {
        Files.createDirectories(output)
        scene.render(clock + 16_666_667).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
            Files.write(output.resolve("$name.png"), it.bytes)
        } }
    }
    @Test fun androidStepOneFillsViewportAndDesktopGeometryIsUnchanged() {
        val measurements = mutableListOf("platform,width,height,font,identityTop,identityBottom,nextTop,nextBottom,gap")
        for (android in listOf(true, false)) for ((width, height) in listOf(280 to 900, 320 to 640, 412 to 900, 412 to 921, 412 to 1200, 800 to 900)) for (scale in listOf(1f, 1.4f)) {
            val elements = mutableMapOf<String, Rect>()
            val scene = ImageComposeScene(width, height, Density(1f, scale)) {
                CompositionLocalProvider(
                    LocalSharedInputViewport provides if (android) IntSize(width, height) else null,
                    LocalSharedDiagnosticAction provides SharedDiagnosticAction {},
                    LocalSharedGameSelectionLayoutObserver provides object : SharedGameSelectionLayoutObserver {
                        override fun element(name: String, bounds: Rect) { elements[name] = bounds }
                    }) {
                    SharedGameSelectionScreen(SharedGameSelectionState(), object : SharedGameSelectionActions {
                        override fun perform(control: SharedGameSelectionControl) = Unit
                        override fun chooseCandidate(id: String) = Unit
                        override fun next() = Unit
                    })
                }
            }
            try {
                settle(scene)
                val identity = elements.getValue("identity")
                val next = elements.getValue("next")
                assertTrue(next.bottom <= height, "footer visible")
                assertTrue(identity.left >= 0 && identity.right <= width)
                if (android && !baseline) assertEquals(15f, height - next.bottom, 1f, "footer stays above available viewport bottom: $width/$height/$scale")
                measurements += "${if(android) "android" else "desktop"},$width,$height,$scale,${identity.top},${identity.bottom},${next.top},${next.bottom},${next.top-identity.bottom}"
                capture(scene, "${if(android) "android" else "desktop"}-step1-$width-$height-$scale")
            } finally { scene.close() }
        }
        Files.write(output.resolve("geometry.csv"), measurements)
    }
    @Test fun androidStepTwoAndHomeFitNormalShortAndLargeTextViewports() {
        for ((width, height) in listOf(280 to 640, 320 to 400, 412 to 900, 740 to 210)) for (scale in listOf(1f, 1.4f)) {
            for (page in listOf("chooser", "custom", "home")) {
                val controller = controller()
                if (page == "custom") controller.choose(ConnectionEntryModel.Mode.CUSTOM)
                val scene = ImageComposeScene(width, height, Density(1f, scale)) {
                    CompositionLocalProvider(LocalSharedInputViewport provides IntSize(width, height),
                        LocalSharedDiagnosticAction provides SharedDiagnosticAction {}) {
                        if (page != "home") SharedConnectionScreen(controller)
                        else SharedHomeScreen(SharedHomeState(SharedGameIdentity("像素地牢", "1.0"), "就绪"), object : SharedHomeActions {
                            override fun settings() = Unit
                            override fun upload() = Unit
                            override fun restore() = Unit
                            override fun launch() = Unit
                            override fun selectSnapshot(snapshotId: String) = Unit
                            override fun rename(snapshotId: String?) = Unit
                            override fun changeHistoryPage(page: Int) = Unit
                        })
                    }
                }
                try {
                    settle(scene)
                    capture(scene, "android-$page-$width-$height-$scale")
                    val footer = if(page == "home") null else nodes(scene).firstOrNull { label(it) == "上一步" }
                    if ((!baseline || height >= 360 * scale) && footer != null) {
                        assertTrue(footer.boundsInRoot.height > 0 && footer.boundsInRoot.bottom <= height)
                        val before = footer.boundsInRoot
                        scene.sendPointerEvent(PointerEventType.Scroll, Offset(width / 2f, height / 2f), scrollDelta = Offset(0f, 20f))
                        settle(scene)
                        assertEquals(before, nodes(scene).first { label(it) == "上一步" }.boundsInRoot)
                    } else {
                        // On short/IME viewports the whole card must remain scrollable to its last action.
                        repeat(5) {
                            scene.sendPointerEvent(PointerEventType.Scroll, Offset(width / 2f, height / 2f), scrollDelta = Offset(0f, 100f))
                            settle(scene)
                        }
                        capture(scene, "android-$page-$width-$height-$scale-scrolled")
                        if (footer != null) {
                            val last = nodes(scene).first { label(it) == "上一步" }.boundsInRoot
                            assertTrue(last.height > 0 && last.top >= 0 && last.bottom <= height, "footer reachable on $page $width/$height/$scale: $last")
                        }
                    }
                } finally { scene.close(); controller.close() }
            }
        }
    }
    @Test fun actualAndroidInitialStateAtUserScreenshotRatio() {
        // User attachment is 916 x 2048. Match its aspect ratio in a density-1
        // available-viewport render; no simulated device/system bars are added.
        val width = 412
        val height = 921
        val elements = mutableMapOf<String, Rect>()
        val scene = ImageComposeScene(width, height, Density(1f, 1f)) {
            CompositionLocalProvider(LocalSharedInputViewport provides IntSize(width, height),
                LocalSharedDiagnosticAction provides SharedDiagnosticAction {},
                LocalSharedGameSelectionLayoutObserver provides object : SharedGameSelectionLayoutObserver {
                    override fun element(name: String, bounds: Rect) { elements[name] = bounds }
                }) {
                SharedGameSelectionScreen(SharedGameSelectionState(pageActive = true,
                    access = SharedGameAccessState(SharedGameAccessMode.NONE, SharedGameAccessStatus.UNCHECKED),
                    rows = listOf(
                        SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_INSTALLED, "选择已安装应用"),
                        SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_FILE, "选择 APK"),
                        SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_ROOT, "Root 模式"),
                        SharedGameSelectionRow(SharedGameSelectionControl.SWITCH_TO_EMBEDDED, "修改 APK 模式"))),
                    object : SharedGameSelectionActions {
                        override fun perform(control: SharedGameSelectionControl) = Unit
                        override fun chooseCandidate(id: String) = Unit
                        override fun next() = Unit
                    })
            }
        }
        try {
            settle(scene)
            capture(scene, "android-user-ratio-412-921")
            val next = elements.getValue("next")
            Files.writeString(output.resolve("user-ratio-geometry.txt"), "viewport=412x921; next=$next; buttonBottomToViewportBottom=${height-next.bottom}\n")
            if (!baseline) assertEquals(15f, height-next.bottom, 1f)
        } finally { scene.close() }
    }
    private fun controller() = SharedConnectionController(ConnectionEntryModel.Origin.SETUP, null, "slot", object : SharedConnectionPorts {
        override fun testConnection(config: SharedConnectionConfig): Boolean = error("No network")
        override fun saveAndActivate(config: SharedConnectionConfig) = error("No save")
        override fun cacheDirectory(): Path? = null
        override fun elapsedRealtimeMs() = 0L
        override fun bootCount() = 1
        override fun copyUsername(username: String) = Unit
        override fun decodeImage(bytes: ByteArray) = null
    }, Executor { error("No async") }, Executor { it.run() })
    private fun label(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return descend(owner.unmergedRootSemanticsNode)
    }
}
