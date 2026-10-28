package pdsync
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
internal fun GlobalBundles.encode(value: JsonObject): ByteArray = ByteArrayOutputStream().also { out ->
 GZIPOutputStream(out).use { it.write(value.toString().encodeToByteArray()) }
}.toByteArray()
