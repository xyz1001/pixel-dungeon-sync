package io.github.xyz1001.pdsync.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import java.io.File
import java.nio.file.Files

/** Runs the real adapter, with PM/drawing doubles only. No APK parsing or device execution. */
object AndroidGameIconChecks {
    private var assertions = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Files.createTempDirectory("pdsync-icon-checks").toFile()
        try {
            installedPriorityAndCache(directory)
            exactArchiveAndInvalidation(directory)
            failureAndSelectionIsolation(directory)
            println("Android game-icon checks passed: $assertions assertions; host boundary doubles only.")
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun installedPriorityAndCache(directory: File) {
        val pm = FakePm()
        val context = Context(pm, Resources())
        val selected = PackageInfo("com.example.selected")
        selected.applicationInfo!!.sourceDir = "/installed/selected/base.apk"
        val icon = TestDrawable(512, 256)
        icon.bounds = Rect(3, 4, 5, 6)
        pm.installed = selected
        pm.installedIcon = icon
        val archive = File(directory, "selected.apk").apply { writeText("archive") }
        val first = AndroidGameIcon.load(context, selected.packageName, "source-one", archive)
        expect(first?.width == 128 && first.height == 128, "All raster output is fixed at 128px")
        expect(icon.drawnBounds == Rect(0, 32, 128, 96), "Non-square icons preserve their aspect ratio")
        expect(icon.bounds == Rect(3, 4, 5, 6), "The drawable's original bounds are restored")
        expect(pm.iconReads == 1 && pm.archiveReads == 0, "Installed icon is preferred over archive")
        expect(AndroidGameIcon.load(context, selected.packageName, "source-one", archive) === first,
            "Repeated renders reuse exactly the same bitmap")
        expect(pm.iconReads == 1 && pm.archiveReads == 0, "Repeated renders do not decode resources")
        selected.lastUpdateTime++
        expect(AndroidGameIcon.load(context, selected.packageName, "source-one", archive) !== first,
            "Package updates invalidate the icon")
        selected.applicationInfo!!.sourceDir = "/installed/selected-new/base.apk"
        AndroidGameIcon.load(context, selected.packageName, "source-one", archive)
        expect(pm.iconReads == 3, "Installed source-path changes invalidate the icon")
        selected.applicationInfo!!.splitSourceDirs = arrayOf("/installed/selected-new/config.apk")
        AndroidGameIcon.load(context, selected.packageName, "source-one", archive)
        expect(pm.iconReads == 4, "Installed split-path changes invalidate the icon")
        AndroidGameIcon.load(context, selected.packageName, "source-two", archive)
        expect(pm.iconReads == 5, "Changed selected-source identity invalidates the icon")
        context.resources.configuration.identity = "night-and-new-density"
        AndroidGameIcon.load(context, selected.packageName, "source-two", archive)
        expect(pm.iconReads == 6, "Android configuration changes invalidate the icon")
        expect(AndroidGameIcon.load(context, "", "source-two", null) == null, "Blank selection returns no icon")
        AndroidGameIcon.load(context, selected.packageName, "source-two", archive)
        expect(pm.iconReads == 7, "Clearing the selection discards its cached image")
    }

    private fun exactArchiveAndInvalidation(directory: File) {
        val pm = FakePm()
        val context = Context(pm, Resources())
        val selected = PackageInfo("com.example.archive")
        val archive = File(directory, "exact-archive.apk").apply { writeText("archive") }
        pm.archive = selected
        pm.archiveIcon = TestDrawable()
        val first = AndroidGameIcon.load(context, selected.packageName, "archive-source", archive)
        expect(first != null && first.width == 128, "Uninstalled prepared APK can supply an icon")
        expect(pm.lastArchivePath == archive.absolutePath, "Archive lookup receives only the exact selected file")
        expect(pm.lastApplication === selected.applicationInfo, "Archive metadata owns the resource lookup")
        expect(selected.applicationInfo!!.sourceDir == archive.absolutePath &&
            selected.applicationInfo!!.publicSourceDir == archive.absolutePath, "Both archive resource paths are bound")
        expect(pm.archiveIcon.drawnBounds == Rect(0, 0, 128, 128), "No intrinsic size still draws bounded output")
        expect(AndroidGameIcon.load(context, selected.packageName, "archive-source", archive) === first,
            "Archive bitmap is cached across renders")
        expect(pm.archiveReads == 1 && pm.resourceReads == 1, "Archive parse and resource resolution occur once")
        archive.appendText("changed")
        AndroidGameIcon.load(context, selected.packageName, "archive-source", archive)
        expect(pm.archiveReads == 2, "Changing archive file size invalidates the parsed result")
        archive.setLastModified(archive.lastModified() + 5000)
        AndroidGameIcon.load(context, selected.packageName, "archive-source", archive)
        expect(pm.archiveReads == 3, "Changing archive modification time invalidates the parsed result")
        AndroidGameIcon.load(context, selected.packageName, "new-archive-source", archive)
        expect(pm.archiveReads == 4, "Changed archive source identity invalidates the parsed result")
        pm.installed = PackageInfo(selected.packageName)
        pm.installedIcon = TestDrawable()
        AndroidGameIcon.load(context, selected.packageName, "new-archive-source", archive)
        expect(pm.iconReads == 1 && pm.archiveReads == 4, "New installation switches immediately to its installed icon")
    }

    private fun failureAndSelectionIsolation(directory: File) {
        val pm = FakePm()
        val context = Context(pm, Resources())
        pm.installed = PackageInfo("com.example.first")
        pm.installedIcon = TestDrawable()
        val first = AndroidGameIcon.load(context, "com.example.first", "first", null)
        expect(first != null, "Initial selected icon is available")
        pm.installed = null
        expect(AndroidGameIcon.load(context, "com.example.missing", "missing", null) == null,
            "A different unavailable selection never reuses the previous icon")
        pm.installed = PackageInfo("com.example.second")
        pm.installedIcon = TestDrawable(Int.MAX_VALUE, 1)
        val second = AndroidGameIcon.load(context, "com.example.second", "second", null)
        expect(second != null && second !== first, "Switching packages gets the new package's bitmap")
        expect(pm.installedIcon.drawnBounds == Rect(0, 63, 128, 64), "Extreme intrinsic dimensions stay bounded")
        pm.installedIcon = TestDrawable(throws = true)
        pm.installed!!.lastUpdateTime++
        expect(AndroidGameIcon.load(context, "com.example.second", "second", null) == null,
            "Drawing failures use the initials fallback")
        expect(Bitmap.created.last().recycled, "Failed raster allocations are recycled")
        val iconReads = pm.iconReads
        AndroidGameIcon.load(context, "com.example.second", "second", null)
        expect(pm.iconReads == iconReads, "Failed resources are cached without repeat decoding")

        val archive = File(directory, "failure.apk").apply { writeText("archive") }
        pm.installed = null
        pm.archive = PackageInfo("com.example.other")
        expect(AndroidGameIcon.load(context, "com.example.expected", "expected", archive) == null,
            "Mismatched archive package cannot provide another game's icon")
        expect(pm.resourceReads == 0, "Mismatched archive never resolves resources")
        pm.archive = PackageInfo("com.example.expected")
        pm.archiveIcon = TestDrawable()
        pm.installed = PackageInfo("com.example.expected")
        pm.iconFailure = true
        expect(AndroidGameIcon.load(context, "com.example.expected", "expected", archive) != null,
            "Installed icon failure can fall back to the matching prepared archive")
        pm.installed = null
        pm.archive!!.applicationInfo!!.icon = 0
        expect(AndroidGameIcon.load(context, "com.example.expected", "no-icon", archive) == null,
            "No declared archive icon preserves initials rather than a generic Android icon")
        pm.archive!!.applicationInfo!!.icon = 1
        pm.archiveFailure = true
        expect(AndroidGameIcon.load(context, "com.example.expected", "broken-archive", archive) == null,
            "Malformed or inaccessible archive resources return null")
        val archiveReads = pm.archiveReads
        AndroidGameIcon.load(context, "com.example.expected", "broken-archive", archive)
        expect(pm.archiveReads == archiveReads, "Malformed archives are not reparsed on every render")
        archive.delete()
        expect(AndroidGameIcon.load(context, "com.example.expected", "broken-archive", archive) == null,
            "Removed archive returns no old bitmap")
        expect(pm.archiveReads == archiveReads, "Missing files are never sent to the archive parser")
    }

    private fun expect(condition: Boolean, message: String) {
        assertions++
        check(condition) { message }
    }

    private class TestDrawable(
        override val intrinsicWidth: Int = -1,
        override val intrinsicHeight: Int = -1,
        val throws: Boolean = false,
    ) : Drawable() {
        var drawnBounds: Rect? = null
        override fun draw(canvas: Canvas) {
            drawnBounds = bounds.copy()
            check(canvas.bitmap.width == 128 && canvas.bitmap.height == 128)
            if (throws) error("Unreadable drawable")
        }
    }

    private class FakePm : PackageManager() {
        var installed: PackageInfo? = null
        var archive: PackageInfo? = null
        var installedIcon = TestDrawable()
        var archiveIcon = TestDrawable()
        var iconReads = 0
        var archiveReads = 0
        var resourceReads = 0
        var iconFailure = false
        var archiveFailure = false
        var lastArchivePath: String? = null
        var lastApplication: ApplicationInfo? = null
        override fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
            check(flags == 0)
            return installed?.takeIf { it.packageName == packageName } ?: throw NameNotFoundException()
        }
        override fun getApplicationIcon(packageName: String): Drawable {
            check(packageName == installed!!.packageName)
            iconReads++
            if (iconFailure) throw NameNotFoundException()
            return installedIcon
        }
        override fun getPackageArchiveInfo(path: String, flags: Int): PackageInfo? {
            check(flags == 0)
            archiveReads++
            lastArchivePath = path
            if (archiveFailure) error("Unreadable archive")
            return archive
        }
        override fun getResourcesForApplication(application: ApplicationInfo): Resources {
            resourceReads++
            lastApplication = application
            return object : Resources() {
                override fun getDrawable(id: Int, theme: Any?): Drawable {
                    check(id == application.icon)
                    return archiveIcon
                }
            }
        }
    }
}
