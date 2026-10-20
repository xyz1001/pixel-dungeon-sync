package android.content
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import java.io.File
import java.io.InputStream
class Context(val packageManager: PackageManager, val resources: Resources,
    val cacheDir: File, val contentResolver: ContentResolver)
abstract class ContentResolver { abstract fun openInputStream(uri: Uri): InputStream? }
