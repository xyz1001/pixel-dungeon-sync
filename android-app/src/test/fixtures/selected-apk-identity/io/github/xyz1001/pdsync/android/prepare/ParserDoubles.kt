package io.github.xyz1001.pdsync.android.prepare
import java.io.File
/** Deliberately synthetic manifest boundary. Real ZIP/binary XML parsing has separate checks. */
object ApkArchive {
    const val MAX_APK_BYTES = 1024L
    fun manifest(file: File): ByteArray = file.readBytes()
}
object BinaryManifestPatch {
    data class Manifest(val packageName: String, val versionCode: Long, val splitName: String)
    fun inspect(bytes: ByteArray): Manifest {
        val fields = bytes.toString(Charsets.UTF_8).split('|')
        require(fields.size == 4 && fields[0] == "fixture") { "Corrupt manifest" }
        return Manifest(fields[1], fields[2].toLong(), fields[3])
    }
}
