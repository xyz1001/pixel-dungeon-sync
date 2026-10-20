package android.content.pm

import android.content.res.Resources
import android.graphics.drawable.Drawable

class ApplicationInfo {
    var nonLocalizedLabel: CharSequence? = null
    var labelRes: Int = 0
    var icon: Int = 1
    var sourceDir: String? = null
    var publicSourceDir: String? = null
    var splitSourceDirs: Array<String>? = null
}

class PackageInfo(val packageName: String) {
    var versionName: String? = null
    var firstInstallTime: Long = 1
    var lastUpdateTime: Long = 1
    var applicationInfo: ApplicationInfo? = ApplicationInfo()
}

abstract class PackageManager {
    class NameNotFoundException : Exception()
    abstract fun getPackageInfo(packageName: String, flags: Int): PackageInfo
    abstract fun getApplicationIcon(packageName: String): Drawable
    abstract fun getPackageArchiveInfo(path: String, flags: Int): PackageInfo?
    abstract fun getResourcesForApplication(application: ApplicationInfo): Resources
}
