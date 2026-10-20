package io.github.xyz1001.pdsync.android

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Exercises the production selection reader and icon adapter, with Android/parser boundaries doubled. */
object SelectedApkIdentityChecks {
    private var assertions = 0
    private fun expect(value: Boolean, why: String) { assertions++; check(value) { why } }
    @JvmStatic fun main(args: Array<String>) {
        val cache = Files.createTempDirectory("selected-apk-tests-").toFile()
        val sentinel = File(cache, "unrelated-cache").apply { writeText("keep") }
        try {
            val provider = Provider()
            val pm = Pm()
            val context = Context(pm, Resources(), cache, provider)
            fun read(vararg uris: Uri): SharedGameIdentity? {
                val result = SelectedApkIdentity.read(context, uris.toList())
                expect(cache.listFiles()!!.toList() == listOf(sentinel), "Every success/early return/failure removes only its staging directory")
                expect(provider.opened == provider.closed, "Every opened content stream is closed")
                return result
            }
            val one = provider.add("one", "fixture|example.game|1|")
            val two = provider.add("two", "fixture|example.game|2|")
            val split = provider.add("split", "fixture|example.game|1|config.en")
            val splitAgain = provider.add("split-again", "fixture|example.game|1|config.en")
            val other = provider.add("other", "fixture|other.game|1|config.fr")
            val secondBase = provider.add("second-base", "fixture|example.game|1|")
            val corrupt = provider.add("corrupt", "not an apk")
            val blank = provider.add("blank", "fixture| |1|")
            val missing = Uri("content://missing")
            val throwing = Uri("content://throw")
            val first = read(one)
            expect(first?.label == "Archive one" && first.versionName == "v1", "Selected resource label/version are retained")
            expect(first?.icon?.width == 128 && first.icon.height == 128, "Selected icon is rasterized")
            expect(pm.drawn == listOf(1L), "First archive supplies its own icon")
            val second = read(two)
            expect(second?.label == "Archive two" && second.versionName == "v2", "Same package in different URI resolves new resources")
            expect(second?.icon !== first?.icon && pm.drawn == listOf(1L, 2L), "Same package cannot reuse previous URI icon")
            expect(pm.installedReads == 0, "Selected archive never queries installed metadata or icons")
            expect(read(split, one)?.label == "Archive one", "Base APK is found after preceding splits")
            expect(pm.archiveBodies.last() == "fixture|example.game|1|", "Only the selected base is sent to PackageManager")
            pm.literalLabel = "Literal name"
            expect(read(one)?.label == "Literal name", "Nonlocalized labels work")
            pm.literalLabel = "  "
            expect(read(one)?.label == "Archive one", "Blank literal label falls back to resource")
            pm.literalLabel = null
            pm.noLabel = true; pm.noIcon = true
            val neutral = read(one)
            expect(neutral?.label == "已选择 APK 文件" && neutral.icon == null, "Missing declared label/icon preserves neutral presentation")
            pm.noLabel = false; pm.noIcon = false; pm.failText = true; pm.failDrawable = true
            val brokenResources = read(one)
            expect(brokenResources?.label == "已选择 APK 文件" && brokenResources.icon == null, "Unresolvable label/icon uses neutral presentation")
            pm.failText = false; pm.failDrawable = false
            expect(read(corrupt) == null, "Corrupt manifest returns no identity")
            expect(read(missing) == null, "Null provider stream returns no identity")
            expect(read(throwing) == null, "Provider exception returns no identity")
            expect(read(blank) == null, "Blank manifest package is rejected")
            expect(read(split) == null, "Split-only selection is rejected")
            expect(read(one, other) == null, "Mixed packages are rejected")
            expect(read(one, two) == null, "Mixed versions are rejected")
            expect(read(one, secondBase) == null, "Multiple base APKs are rejected")
            expect(read(one, split, splitAgain) == null, "Duplicate split names are rejected")
            expect(read(one, one) == null, "Duplicate URIs are rejected")
            expect(read() == null, "Empty selection is rejected")
            expect(read(Uri("file:///tmp/one.apk")) == null, "Non-content schemes are rejected")
            expect(read(*Array(257) { Uri("content://many/$it") }) == null, "Excessive selections are rejected before opening")
            val oversized = provider.add("large", "x".repeat(1025))
            expect(read(oversized) == null, "Per-archive staging byte limit is enforced")
            pm.wrongPackage = true
            expect(read(one) == null, "PackageManager/manifest disagreement is rejected")
            pm.wrongPackage = false; pm.noApplication = true
            expect(read(one) == null, "Missing application metadata is rejected")
            pm.noApplication = false; pm.nullArchive = true
            expect(read(one) == null, "Unrecognized archive returns no identity")
            pm.nullArchive = false
            Thread.currentThread().interrupt()
            try { expect(read(one) == null, "Interrupted staging returns no identity") }
            finally { Thread.interrupted() }
            expect(read(two)?.label == "Archive two", "A failed previous selection cannot poison later metadata")
            println("Selected APK identity checks passed: $assertions assertions; host Android/parser doubles only.")
        } finally { cache.deleteRecursively() }
    }

    private class Provider : ContentResolver() {
        val data = mutableMapOf<Uri, ByteArray>()
        var opened = 0; var closed = 0
        fun add(name: String, contents: String) = Uri("content://$name").also { data[it] = contents.toByteArray() }
        override fun openInputStream(uri: Uri): InputStream? {
            if (uri.value == "content://throw") error("Permission denied")
            val bytes = data[uri] ?: return null
            opened++
            return object : java.io.ByteArrayInputStream(bytes) { override fun close() { closed++; super.close() } }
        }
    }

    private class Pm : PackageManager() {
        var installedReads = 0
        val drawn = mutableListOf<Long>()
        val archiveBodies = mutableListOf<String>()
        var literalLabel: String? = null
        var noLabel = false; var noIcon = false; var failText = false; var failDrawable = false
        var wrongPackage = false; var noApplication = false; var nullArchive = false
        override fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
            installedReads++; error("Installed metadata must not be consulted")
        }
        override fun getApplicationIcon(packageName: String): Drawable {
            installedReads++; error("Installed icon must not be consulted")
        }
        override fun getPackageArchiveInfo(path: String, flags: Int): PackageInfo? {
            check(flags == 0)
            val body = File(path).readText()
            archiveBodies.add(body)
            if (nullArchive) return null
            val fields = body.split('|')
            check(fields[3].isEmpty()) { "Resource lookup must use the base" }
            return PackageInfo(if (wrongPackage) "wrong.game" else fields[1]).apply {
                versionName = "v${fields[2]}"
                if (noApplication) applicationInfo = null
                else applicationInfo!!.apply {
                    nonLocalizedLabel = literalLabel
                    labelRes = if (noLabel) 0 else 7
                    icon = if (noIcon) 0 else 9
                }
            }
        }
        override fun getResourcesForApplication(application: ApplicationInfo): Resources {
            check(application.sourceDir == application.publicSourceDir)
            val file = File(checkNotNull(application.sourceDir))
            check(file.isFile) { "Resources must be loaded before staging cleanup" }
            val version = file.readText().split('|')[2].toLong()
            return object : Resources() {
                override fun getText(id: Int): CharSequence {
                    check(id == 7)
                    if (failText) error("Missing label resource")
                    return if (version == 1L) "Archive one" else "Archive two"
                }
                override fun getDrawable(id: Int, theme: Any?): Drawable {
                    check(id == 9)
                    if (failDrawable) error("Missing icon resource")
                    return object : Drawable() {
                        override fun draw(canvas: Canvas) { drawn.add(version) }
                    }
                }
            }
        }
    }
}
