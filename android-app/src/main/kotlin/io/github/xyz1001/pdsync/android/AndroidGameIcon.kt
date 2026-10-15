package io.github.xyz1001.pdsync.android

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.math.roundToInt

/** Presentation-only metadata/resources. Never loads game code or changes a selection. */
object AndroidGameIcon {
    private const val ICON_SIZE = 128

    private data class InstalledSource(
        val packageName: String,
        val firstInstallTime: Long,
        val lastUpdateTime: Long,
        val sourcePath: String?,
        val splitPaths: List<String>,
    )

    private data class ArchiveSource(val path: String, val length: Long, val modified: Long)
    private data class CacheKey(
        val packageName: String,
        val sourceIdentity: String,
        val installed: InstalledSource?,
        val archive: ArchiveSource?,
        val configuration: String,
    )

    private data class CacheEntry(val key: CacheKey, val image: ImageBitmap?)
    // Retain only the current selection's small bitmap, including unsuccessful reads.
    // No Activity, PackageManager, Drawable or APK resources survive a call.
    private var cached: CacheEntry? = null

    @JvmStatic
    @Synchronized
    @Suppress("DEPRECATION") // The int flags overload remains available on the minimum API 26.
    fun load(context: Context, packageName: String, sourceIdentity: String, preparedApk: File?): ImageBitmap? {
        return try {
            if (packageName.isBlank()) {
                cached = null
                return null
            }
            val packages = context.packageManager
            val installed = safely { packages.getPackageInfo(packageName, 0) }
                ?.takeIf { it.packageName == packageName }
            val archive = preparedApk?.takeIf { it.isFile }?.let {
                ArchiveSource(it.absolutePath, it.length(), it.lastModified())
            }
            val key = CacheKey(packageName, sourceIdentity, installed?.source(), archive,
                context.resources.configuration.toString())
            cached?.takeIf { it.key == key }?.let { return it.image }
            cached = null
            val image = installed?.applicationInfo?.takeIf { it.icon != 0 }?.let {
                safely { rasterize(packages.getApplicationIcon(packageName)) }
            } ?: archive?.let { safely { readArchive(packages, packageName, it.path) } }
            cached = CacheEntry(key, image)
            image
        } catch (_: Exception) {
            cached = null
            null
        } catch (_: OutOfMemoryError) {
            // A decorative resource must not prevent the selected-game screen from opening.
            cached = null
            null
        }
    }

    private fun PackageInfo.source() = InstalledSource(packageName, firstInstallTime, lastUpdateTime,
        applicationInfo?.sourceDir, applicationInfo?.splitSourceDirs?.toList().orEmpty())

    @Suppress("DEPRECATION")
    private fun readArchive(packages: PackageManager, packageName: String, path: String): ImageBitmap? {
        val archive = packages.getPackageArchiveInfo(path, 0) ?: return null
        if (archive.packageName != packageName) return null
        val application = archive.applicationInfo ?: return null
        if (application.icon == 0) return null
        application.sourceDir = path
        application.publicSourceDir = path
        // Resolve only the exact selected base APK. Missing split resources fall back to
        // initials; do not scan directories or substitute another application's icon.
        val resources = packages.getResourcesForApplication(application)
        return rasterize(resources.getDrawable(application.icon, null))
    }

    private fun rasterize(drawable: Drawable): ImageBitmap {
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val previousBounds = drawable.copyBounds()
        try {
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_SIZE
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_SIZE
            val scale = minOf(ICON_SIZE.toDouble() / width, ICON_SIZE.toDouble() / height)
            val renderedWidth = (width * scale).roundToInt().coerceIn(1, ICON_SIZE)
            val renderedHeight = (height * scale).roundToInt().coerceIn(1, ICON_SIZE)
            val left = (ICON_SIZE - renderedWidth) / 2
            val top = (ICON_SIZE - renderedHeight) / 2
            drawable.setBounds(left, top, left + renderedWidth, top + renderedHeight)
            drawable.draw(Canvas(bitmap))
            return bitmap.asImageBitmap()
        } catch (failure: Exception) {
            bitmap.recycle()
            throw failure
        } finally {
            drawable.bounds = previousBounds
        }
    }

    private inline fun <T> safely(read: () -> T): T? = try {
        read()
    } catch (_: Exception) {
        null
    }
}
