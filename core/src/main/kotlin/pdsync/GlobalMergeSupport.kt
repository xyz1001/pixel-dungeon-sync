package pdsync

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Global bundles have no writer-version field. Semantic rules are gated by RunMergePlanner's 3.3.8 target. */
internal fun globalFail(detail: String): Nothing = throw RunMergeException("全局记录：$detail")

/** Unknown data is retained verbatim as JSON values, but never interpreted or combined speculatively. */
internal fun mergeOpaqueFields(base: JsonObject?, branches: List<JsonObject>, knownKeys: Set<String>, label: String): MutableMap<String, JsonElement> {
 val all=listOfNotNull(base)+branches
 val keys=all.flatMap { it.keys }.toSet()-knownKeys
 val result=linkedMapOf<String,JsonElement>()
 for(key in keys.sorted()) {
  val values=all.map { it[key] }.distinct()
  if(values.size!=1)globalFail("$label 的未知字段 $key 发生变化，无法安全解释")
  values.single()?.let { result[key]=it }
 }
 return result
}

internal fun globalInt(value: JsonElement?, label: String): Int {
 val primitive=value as? JsonPrimitive ?: globalFail("$label 缺少整数")
 if(primitive.isString || !primitive.content.matches(Regex("0|[1-9][0-9]*")))globalFail("$label 必须为非负整数")
 return primitive.intOrNull ?: globalFail("$label 超出整数范围")
}
internal fun globalBoolean(value: JsonElement?, label: String): Boolean {
 val primitive=value as? JsonPrimitive ?: globalFail("$label 缺少布尔值")
 if(primitive.isString)return globalFail("$label 必须为布尔值")
 return primitive.booleanOrNull ?: globalFail("$label 必须为布尔值")
}
internal fun globalStringSet(value: JsonElement?, label: String): Set<String> {
 val array=value as? JsonArray ?: globalFail("$label 必须为字符串数组")
 if(array.size>4096)globalFail("$label 数组超出范围")
 val strings=array.map { item ->
  val p=item as? JsonPrimitive ?: globalFail("$label 含非字符串")
  if(!p.isString || p.content.isEmpty() || p.content.length>512)globalFail("$label 含无效字符串")
  p.content
 }
 if(strings.distinct().size!=strings.size)globalFail("$label 含重复条目")
 return strings.toSet()
}

/** Bounded static JSON only: never instantiate game classes or Java serialized objects. */
internal object GlobalBundles {
 private const val INPUT_LIMIT=8*1024*1024
 private const val EXPANDED_LIMIT=16*1024*1024
 fun decode(source: ByteArray, path: String): JsonObject = try {
  require(source.size in 2..INPUT_LIMIT) { "文件大小不受支持" }
  val bytes=if(source[0]==0x1f.toByte() && source[1]==0x8b.toByte())
   GZIPInputStream(source.inputStream()).use { it.readUpTo(EXPANDED_LIMIT+1) } else source
  require(bytes.size<=EXPANDED_LIMIT) { "解压后超出大小限制" }
  val text=bytes.decodeToString(throwOnInvalidSequence=true)
  requireUniqueJsonKeys(text)
  json.parseToJsonElement(text) as? JsonObject ?: globalFail("$path 顶层不是对象")
 } catch(e: RunMergeException) { throw e }
 catch(e: Exception) { globalFail("$path 无法解析为已验证的游戏 Bundle（${e.javaClass.simpleName}）") }

 /** JSONObject/Bundle accepts both JSON and gzip; synthesized bundles use official gzip encoding. */
 fun encode(value: JsonObject): ByteArray {
  fun sorted(element:JsonElement):JsonElement=when(element) {
   is JsonObject -> JsonObject(element.toSortedMap().mapValues { sorted(it.value) })
   is JsonArray -> JsonArray(element.map(::sorted))
   else -> element
  }
  val plain=sorted(value).toString().encodeToByteArray()
  if(plain.size>EXPANDED_LIMIT)globalFail("合并后的 Bundle 超出大小限制")
  val out=ByteArrayOutputStream()
  GZIPOutputStream(out).use { it.write(plain) }
  return out.toByteArray().also { if(it.size>INPUT_LIMIT)globalFail("合并后的 Bundle 压缩体超出大小限制") }
 }
}
