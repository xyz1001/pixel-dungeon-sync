package pdsync

import pdsync.i18n.CoreMessages
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Global bundles have no writer-version field. Semantic rules are gated by RunMergePlanner's 3.3.8 target. */
internal fun globalFail(detail: String): Nothing = throw RunMergeException(CoreMessages.GLOBAL_RECORDS_1_S.text(detail))

/** Unknown data is retained verbatim as JSON values, but never interpreted or combined speculatively. */
internal fun mergeOpaqueFields(base: JsonObject?, branches: List<JsonObject>, knownKeys: Set<String>, label: String): MutableMap<String, JsonElement> {
 val all=listOfNotNull(base)+branches
 val keys=all.flatMap { it.keys }.toSet()-knownKeys
 val result=linkedMapOf<String,JsonElement>()
 for(key in keys.sorted()) {
  val values=all.map { it[key] }.distinct()
  if(values.size!=1)globalFail(CoreMessages.UNKNOWN_FIELD_2_S_IN_1_S_CHANGED_AND_CANNOT_BE_SAFELY_INTERPRETED.text(label, key))
  values.single()?.let { result[key]=it }
 }
 return result
}

internal fun globalInt(value: JsonElement?, label: String): Int {
 val primitive=value as? JsonPrimitive ?: globalFail(CoreMessages.TEXT_1_S_IS_MISSING_AN_INTEGER.text(label))
 if(primitive.isString || !primitive.content.matches(Regex("0|[1-9][0-9]*")))globalFail(CoreMessages.TEXT_1_S_MUST_BE_A_NONNEGATIVE_INTEGER.text(label))
 return primitive.intOrNull ?: globalFail(CoreMessages.TEXT_1_S_IS_OUTSIDE_THE_INTEGER_RANGE.text(label))
}
internal fun globalBoolean(value: JsonElement?, label: String): Boolean {
 val primitive=value as? JsonPrimitive ?: globalFail(CoreMessages.TEXT_1_S_IS_MISSING_A_BOOLEAN.text(label))
 if(primitive.isString)return globalFail(CoreMessages.TEXT_1_S_MUST_BE_A_BOOLEAN.text(label))
 return primitive.booleanOrNull ?: globalFail(CoreMessages.TEXT_1_S_MUST_BE_A_BOOLEAN.text(label))
}
internal fun globalStringSet(value: JsonElement?, label: String): Set<String> {
 val array=value as? JsonArray ?: globalFail(CoreMessages.TEXT_1_S_MUST_BE_A_STRING_ARRAY.text(label))
 if(array.size>4096)globalFail(CoreMessages.TEXT_1_S_ARRAY_EXCEEDS_THE_LIMIT.text(label))
 val strings=array.map { item ->
  val p=item as? JsonPrimitive ?: globalFail(CoreMessages.TEXT_1_S_CONTAINS_A_NON_STRING_VALUE.text(label))
  if(!p.isString || p.content.isEmpty() || p.content.length>512)globalFail(CoreMessages.TEXT_1_S_CONTAINS_AN_INVALID_STRING.text(label))
  p.content
 }
 if(strings.distinct().size!=strings.size)globalFail(CoreMessages.TEXT_1_S_CONTAINS_DUPLICATE_ENTRIES.text(label))
 return strings.toSet()
}

/** Bounded static JSON only: never instantiate game classes or Java serialized objects. */
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
  json.parseToJsonElement(text) as? JsonObject ?: globalFail(CoreMessages.TEXT_1_S_IS_NOT_A_TOP_LEVEL_OBJECT.text(path))
 } catch(e: RunMergeException) { throw e }
 catch(e: Exception) { globalFail(CoreMessages.TEXT_1_S_CANNOT_BE_PARSED_AS_A_VERIFIED_GAME_BUNDLE_2_S.text(path, e.javaClass.simpleName)) }

 /** JSONObject/Bundle accepts both JSON and gzip; synthesized bundles use official gzip encoding. */
 fun encode(value: JsonObject): ByteArray {
  fun sorted(element:JsonElement):JsonElement=when(element) {
   is JsonObject -> JsonObject(element.toSortedMap().mapValues { sorted(it.value) })
   is JsonArray -> JsonArray(element.map(::sorted))
   else -> element
  }
  val plain=sorted(value).toString().encodeToByteArray()
  if(plain.size>EXPANDED_LIMIT)globalFail(CoreMessages.MERGED_BUNDLE_EXCEEDS_THE_SIZE_LIMIT.text())
  val out=ByteArrayOutputStream()
  GZIPOutputStream(out).use { it.write(plain) }
  return out.toByteArray().also { if(it.size>INPUT_LIMIT)globalFail(CoreMessages.COMPRESSED_MERGED_BUNDLE_EXCEEDS_THE_SIZE_LIMIT.text()) }
 }
}
