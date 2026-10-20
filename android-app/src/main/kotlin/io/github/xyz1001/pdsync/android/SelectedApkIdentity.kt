package io.github.xyz1001.pdsync.android

import pdsync.i18n.AndroidHostMessages.*

import android.content.Context
import android.net.Uri
import io.github.xyz1001.pdsync.android.prepare.ApkArchive
import io.github.xyz1001.pdsync.android.prepare.BinaryManifestPatch
import java.io.File
import java.io.IOException

/** Read-only presentation metadata. Call off the UI thread; never trusts or installs an APK. */
object SelectedApkIdentity {
    @JvmStatic
    @Suppress("DEPRECATION")
    fun read(context: Context, uris: List<Uri>): SharedGameIdentity? {
        var directory: File? = null
        return try {
            if (uris.isEmpty() || uris.size > 256 || uris.distinct().size != uris.size ||
                uris.any { it.scheme != "content" }) return null
            directory = File.createTempFile("selected-apk-", ".metadata", context.cacheDir).also {
                if (!it.delete() || !it.mkdir()) throw IOException("Cannot stage APK metadata")
            }
            var packageName: String? = null
            var version = -1L
            var base: File? = null
            val splits = mutableSetOf<String>()
            var total = 0L
            for ((index, uri) in uris.withIndex()) {
                val copy = File(directory, "$index.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    copy.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw IOException("Cancelled metadata read")
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count; total += count
                            if (size > ApkArchive.MAX_APK_BYTES || total > 8589934592L)
                                throw IOException("APK metadata staging limit")
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IOException("Cannot read APK")
                val manifest = BinaryManifestPatch.inspect(ApkArchive.manifest(copy))
                if (manifest.packageName.isNullOrBlank()) return null
                if (packageName == null) { packageName = manifest.packageName; version = manifest.versionCode }
                else if (packageName != manifest.packageName || version != manifest.versionCode) return null
                if (manifest.splitName.isEmpty()) {
                    if (base != null) return null
                    base = copy
                } else if (!splits.add(manifest.splitName)) return null
            }
            val path = base?.absolutePath ?: return null
            val packages = context.packageManager
            val info = packages.getPackageArchiveInfo(path, 0) ?: return null
            if (info.packageName != packageName) return null
            val application = info.applicationInfo ?: return null
            application.sourceDir = path
            application.publicSourceDir = path
            // Read the selected archive's own resource table, never the installed package.
            // Missing labels/icons retain the existing neutral presentation, not invented metadata.
            val resources = safely { packages.getResourcesForApplication(application) }
            val label = application.nonLocalizedLabel?.toString()?.takeIf { it.isNotBlank() }
                ?: if (application.labelRes != 0) safely { resources?.getText(application.labelRes)?.toString() }
                    ?.takeIf { it.isNotBlank() } else null
            val icon = AndroidGameIcon.loadArchive(context, info.packageName, base)
            SharedGameIdentity(label ?: APK_FILE_SELECTED_77.text(), info.versionName, icon)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            directory?.deleteRecursively()
        }
    }

    private inline fun <T> safely(read: () -> T): T? = try { read() } catch (_: Exception) { null }
}
