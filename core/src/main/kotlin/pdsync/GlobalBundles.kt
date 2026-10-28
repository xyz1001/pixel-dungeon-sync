package pdsync

import pdsync.i18n.CoreMessages
import kotlinx.serialization.json.*
import java.util.zip.GZIPInputStream

/** Bounded game-bundle decoding for normal progress observation. */
internal object GlobalBundles {
 private const val INPUT_LIMIT=8*1024*1024
 private const val EXPANDED_LIMIT=16*1024*1024
 fun decode(source: ByteArray, path: String): JsonObject = try {
  require(source.size in 2..INPUT_LIMIT) { CoreMessages.UNSUPPORTED_FILE_SIZE.text() }
  val bytes=if(source[0]==0x1f.toByte() && source[1]==0x8b.toByte())
   GZIPInputStream(source.inputStream()).use { it.readUpTo(EXPANDED_LIMIT+1) } else source
  require(bytes.size<=EXPANDED_LIMIT) { CoreMessages.DECOMPRESSED_DATA_EXCEEDS_THE_SIZE_LIMIT.text() }
  val text=bytes.decodeToString(throwOnInvalidSequence=true)
  requireUniqueJsonKeys(text)
  json.parseToJsonElement(text) as? JsonObject ?: error(CoreMessages.TEXT_1_S_IS_NOT_A_TOP_LEVEL_OBJECT.text(path))
 } catch(e: Exception) { error(CoreMessages.TEXT_1_S_CANNOT_BE_PARSED_AS_A_VERIFIED_GAME_BUNDLE_2_S.text(path, e.javaClass.simpleName)) }

}
