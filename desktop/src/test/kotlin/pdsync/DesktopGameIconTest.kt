@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package pdsync

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import io.github.xyz1001.pdsync.android.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DesktopGameIconTest {
    @TempDir lateinit var temp: Path
    private fun png(rgb: Int, size: Int = 128): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until size) for (y in 0 until size) image.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
    private fun jar(name: String = "所选 游戏.jar", vararg entries: Pair<String, ByteArray>): Path = temp.resolve(name).also {
        ZipOutputStream(Files.newOutputStream(it)).use { zip -> entries.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        } }
    }
    @Test fun readsActualSelectedJarResourceWithoutChangingIt() {
        val path = jar(entries = arrayOf("icons/icon_128.png" to png(0xffff0000.toInt()), "icons/icon_256.png" to png(0xff0000ff.toInt(), 256)))
        val before = Files.readAllBytes(path)
        val icon = assertNotNull(DesktopGameIcon.read(path))
        assertEquals(128, icon.width); assertEquals(128, icon.height)
        assertContentEquals(before, Files.readAllBytes(path))
    }
    @Test fun missingCorruptOrOversizedIconFallsBackWithoutRejectingTheGame() {
        assertNull(DesktopGameIcon.read(temp.resolve("missing.jar")))
        assertNull(DesktopGameIcon.read(jar("none.jar", "interfaces/icons.png" to png(-1))))
        assertNull(DesktopGameIcon.read(jar("broken.jar", "icons/icon_128.png" to byteArrayOf(1, 2, 3))))
        assertNull(DesktopGameIcon.read(jar("large.jar", "icons/icon_128.png" to ByteArray(DesktopGameIcon.MAX_ICON_BYTES + 1))))
        val enormous = png(-1).also { ByteBuffer.wrap(it).putInt(16, 100_000) }
        assertNull(DesktopGameIcon.read(jar("dimensions.jar", "icons/icon_128.png" to enormous)))
        val fallback = jar("fallback.jar", "icons/icon_128.png" to byteArrayOf(1), "icons/icon_64.png" to png(-1, 64))
        assertEquals(64, assertNotNull(DesktopGameIcon.read(fallback)).width)
    }
    @Test fun duplicateCandidateIsNotChosen() {
        val path = jar("duplicates.jar", "icons/icon_128.png" to png(-1), "icons/icon_129.png" to png(-1))
        // ZIP API won't create duplicate names; replace the equal-length name in both ZIP headers.
        val bytes = Files.readAllBytes(path)
        val from = "icons/icon_129.png".toByteArray(); val to = "icons/icon_128.png".toByteArray()
        for (i in 0..bytes.size - from.size) if (from.indices.all { bytes[i + it] == from[it] }) to.copyInto(bytes, i)
        Files.write(path, bytes)
        assertNull(DesktopGameIcon.read(path))
    }
    @Test fun changingSelectionDiscardsLateLoadsAndDoesNotReloadOnRecomposition() {
        val first = jar("first.jar", "icons/icon_128.png" to png(-1))
        val second = jar("second.jar", "icons/icon_64.png" to png(-1, 64))
        fun target(path: Path) = TargetGame(path.toString(), "1", 896, path.fileName.toString(), true, 802, "identified")
        val selected = mutableStateOf<TargetGame?>(target(first))
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        var visible: androidx.compose.ui.graphics.ImageBitmap? = null
        val scene = ImageComposeScene(500, 757, Density(1f)) {
            visible = rememberDesktopGameIcon(selected.value) { path ->
                reads.incrementAndGet()
                if (path == first) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                DesktopGameIcon.read(path)
            }
        }
        fun await(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!predicate() && System.nanoTime() < deadline) { scene.render(System.nanoTime()).close(); Thread.sleep(10) }
            assertTrue(predicate())
        }
        try {
            await { started.count == 0L }
            selected.value = target(second)
            await { visible?.width == 64 }
            release.countDown()
            repeat(10) { scene.render(System.nanoTime()).close(); Thread.sleep(10) }
            assertEquals(64, visible?.width); assertEquals(2, reads.get())
            selected.value = null
            await { visible == null }
        } finally { release.countDown(); scene.close() }
    }

    @Test fun selectedIconIsRenderedAndMissingReplacementCannotShowPreviousPixels() {
        val path = jar(entries = arrayOf("icons/icon_128.png" to png(0xffff0000.toInt())))
        val image = assertNotNull(DesktopGameIcon.read(path))
        for (width in listOf(1180, 500, 320)) {
            val state = mutableStateOf(SharedGameSelectionState(
                game = SharedGameIdentity("Selected game", "1", image), selectionId = "one", pageActive = true, canContinue = true))
            val measured = mutableMapOf<String, Rect>()
            val observer = object : SharedGameSelectionLayoutObserver {
                override fun element(name: String, bounds: Rect) { measured[name] = bounds }
            }
            val actions = object : SharedGameSelectionActions {
                override fun perform(control: SharedGameSelectionControl) = Unit
                override fun chooseCandidate(id: String) = Unit
                override fun next() = Unit
            }
            val scene = ImageComposeScene(width, 757, Density(1f)) {
                CompositionLocalProvider(LocalSharedGameSelectionLayoutObserver provides observer) { SharedGameSelectionScreen(state.value, actions) }
            }
            fun render(): BufferedImage {
                scene.render(0).close(); scene.render(16_000_000).close()
                return scene.render(32_000_000).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> ImageIO.read(ByteArrayInputStream(data.bytes)) } }
            }
            try {
                val rendered = render(); val rect = measured.getValue("game-icon")
                assertEquals(0xff0000, rendered.getRGB(rect.center.x.toInt(), rect.center.y.toInt()) and 0xffffff)
                val nextBefore = state.value.nextEnabled
                measured.clear()
                state.value = state.value.copy(game = SharedGameIdentity("No icon", "2"), selectionId = "two")
                render()
                assertTrue("game-icon-fallback" in measured); assertFalse("game-icon" in measured)
                assertEquals(nextBefore, state.value.nextEnabled)
            } finally { scene.close() }
        }
    }
}
