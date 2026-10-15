package pdsync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Static presentation only. Never loads a game class or executes its launcher. */
internal object DesktopGameIcon {
    internal const val MAX_ICON_BYTES = 256 * 1024
    private val names = listOf("icons/icon_128.png", "icons/icon_256.png", "icons/icon_64.png",
        "icons/icon_48.png", "icons/icon_32.png", "icons/icon_16.png", "icon.png")
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun read(path: Path): ImageBitmap? = runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) > 256L * 1024 * 1024) return null
        ZipFile(path.toFile()).use { zip ->
            val candidates = mutableSetOf<String>()
            val duplicateNames = mutableSetOf<String>()
            var count = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (++count > 50_000) return null
                val entry = entries.nextElement()
                if (entry.name in names && !candidates.add(entry.name)) duplicateNames += entry.name
            }
            for (name in names) {
                if (name in duplicateNames) continue
                val entry = zip.getEntry(name) ?: continue
                if (entry.isDirectory || entry.size !in 1..MAX_ICON_BYTES.toLong()) continue
                val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_ICON_BYTES + 1) }
                if (bytes.size.toLong() != entry.size || !boundedPng(bytes)) continue
                val image = runCatching { Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() } }.getOrNull()
                if (image != null) return image
            }
            null
        }
    }.getOrNull()

    private fun boundedPng(bytes: ByteArray): Boolean {
        if (bytes.size !in 33..MAX_ICON_BYTES || !bytes.copyOfRange(0, 8).contentEquals(pngSignature)) return false
        val header = ByteBuffer.wrap(bytes)
        if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452) return false // PNG IHDR
        return header.getInt(16) in 1..512 && header.getInt(20) in 1..512
    }
}

/** A new selection clears the old bitmap immediately; cancelled/stale loads cannot replace it. */
@Composable
internal fun rememberDesktopGameIcon(target: TargetGame?, read: (Path) -> ImageBitmap? = DesktopGameIcon::read): ImageBitmap? {
    var icon by remember(target) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(target) {
        icon = target?.takeIf { it.trusted }?.let { withContext(Dispatchers.IO) { read(Path.of(it.path)) } }
    }
    return icon
}
