package pdsync

import kotlinx.serialization.json.*

/** Shattered 3.3.8 global Journal schema, not the per-run Notes stored in game.dat.
 * Source: official v3.3.8 Journal, Catalog, Bestiary, Document and Bundle classes.
 * Known counters keep the greatest observed value, including the common baseline.
 * This is deterministic and reset-safe, but does not claim an exact total across concurrent play.
 */
internal object JournalMerge {
 private data class Entry(val seen: Boolean, val count: Int)
 private val absent=Entry(false,0)
 private val binaryClass=Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
 private val knownKeys=setOf("catalog_classes","catalog_seen","catalog_uses",
  "bestiary_classes","bestiary_seen","bestiary_encounters","documents")

 fun merge(base: JsonObject?, branches: List<JsonObject>): JsonObject {
  if(branches.isEmpty())globalFail("journal.dat 缺少合并分支")
  if((listOfNotNull(base)+branches).any { "catalog_items" in it })
   globalFail("journal.dat 包含旧版 catalog_items，尚未验证迁移合并")
  val result=mergeOpaqueFields(base,branches,knownKeys,"journal.dat")
  mergeEntries(base,branches,"catalog","uses",result)
  mergeEntries(base,branches,"bestiary","encounters",result)
  result["documents"]=mergeDocuments(base,branches)
  return JsonObject(result)
 }

 private fun entries(bundle: JsonObject?, prefix: String, counter: String): Map<String,Entry> {
  if(bundle==null)return emptyMap()
  val fields=listOf("${prefix}_classes","${prefix}_seen","${prefix}_$counter")
  if(fields.none { it in bundle })return emptyMap()
  if(fields.any { it !in bundle })globalFail("journal.dat/$prefix 平行数组不完整")
  val classes=globalStringSet(bundle[fields[0]],"journal.dat/${fields[0]}").toList()
  if(classes.any { !binaryClass.matches(it) || it=="com.shatteredpixel.shatteredpixeldungeon.items.keys.SkeletonKey" })
   globalFail("journal.dat/$prefix 存在未经验证的旧版或非法类名")
  val seen=bundle[fields[1]] as? JsonArray ?: globalFail("journal.dat/${fields[1]} 必须为数组")
  val counts=bundle[fields[2]] as? JsonArray ?: globalFail("journal.dat/${fields[2]} 必须为数组")
  if(classes.size!=seen.size || classes.size!=counts.size)
   globalFail("journal.dat/$prefix 平行数组长度不一致")
  return classes.mapIndexed { index,cls -> cls to Entry(
   globalBoolean(seen[index],"journal.dat/$prefix/$cls/seen"),
   globalInt(counts[index],"journal.dat/$prefix/$cls/$counter")
  ) }.toMap()
 }

 private fun mergeEntries(base: JsonObject?, branches: List<JsonObject>, prefix: String,
  counter: String, result: MutableMap<String,JsonElement>) {
  val old=entries(base,prefix,counter)
  val variants=branches.map { entries(it,prefix,counter) }
  val merged=linkedMapOf<String,Entry>()
  (old.keys+variants.flatMap { it.keys }).sorted().forEach { cls ->
   val before=old[cls] ?: absent
   val states=variants.map { it[cls] ?: absent }
   val entry=Entry(before.seen || states.any { it.seen },maxOf(before.count,states.maxOf { it.count }))
   // Explicit false/zero overrides old ALL_*_IDENTIFIED badge migration during official restore.
   // Keep every represented class; omitting such a row can turn an unseen item into seen.
   merged[cls]=entry
  }
  result["${prefix}_classes"]=JsonArray(merged.keys.map(::JsonPrimitive))
  result["${prefix}_seen"]=JsonArray(merged.values.map { JsonPrimitive(it.seen) })
  result["${prefix}_$counter"]=JsonArray(merged.values.map { JsonPrimitive(it.count) })
 }

 private fun objectField(bundle: JsonObject?, field: String, label: String): JsonObject? {
  if(bundle==null || field !in bundle)return null
  return bundle[field] as? JsonObject ?: globalFail("$label 必须为对象")
 }

 private fun mergeDocuments(base: JsonObject?, branches: List<JsonObject>): JsonObject {
  val old=objectField(base,"documents","journal.dat/documents") ?: if(base!=null)JsonObject(emptyMap()) else null
  val versions=branches.map { objectField(it,"documents","journal.dat/documents") ?: JsonObject(emptyMap()) }
  val result=mergeOpaqueFields(old,versions,pages.keys,"journal.dat/documents")
  pages.forEach { (doc,knownPages) ->
   val before=objectField(old,doc,"journal.dat/documents/$doc") ?: if(old!=null)JsonObject(emptyMap()) else null
   val after=versions.map { objectField(it,doc,"journal.dat/documents/$doc") ?: JsonObject(emptyMap()) }
   val pageResult=mergeOpaqueFields(before,after,knownPages,"journal.dat/documents/$doc")
   knownPages.sorted().forEach { page ->
    // Unlike the other pages, this introduction is already read in a fresh profile.
    val default=if(doc=="INTROS" && page=="Dungeon")2 else 0
    fun state(obj:JsonObject?):Int {
     if(obj==null || page !in obj)return default
     val value=globalInt(obj[page],"journal.dat/documents/$doc/$page")
     if(value>2)globalFail("journal.dat/documents/$doc/$page 包含未知页面状态")
     return value
    }
    val previous=state(before)
    val states=after.map(::state)
    val merged=maxOf(previous,states.max())
    // An explicit 0 can override Dungeon's nonzero default. Do not erase that override.
    if(merged>0 || merged!=default)pageResult[page]=JsonPrimitive(merged)
   }
   if(pageResult.isNotEmpty())result[doc]=JsonObject(pageResult)
  }
  return JsonObject(result)
 }

 // Names are persisted verbatim, including the official legacy spellings and case.
 private val pages=mapOf(
  "ADVENTURERS_GUIDE" to setOf("Intro","Examining","Surprise_Attacks","Identifying","Food","Alchemy","Dieing","Searching","Strength","Upgrades","Looting","Levelling","Positioning","Magic"),
  "ALCHEMY_GUIDE" to setOf("Potions","Stones","Energy_Food","Exotic_Potions","Exotic_Scrolls","Bombs","Weapons","Brews_Elixirs","Spells"),
  "INTROS" to setOf("Dungeon","Sewers","Prison","Caves","City","Halls"),
  "SEWERS_GUARD" to setOf("new_position","dangerous","crabs","guild","lost","not_worth"),
  "PRISON_WARDEN" to setOf("journal","recruits","mines","rotberry","no_support","letter"),
  "CAVES_EXPLORER" to setOf("expedition","gold","troll","city","alive","report"),
  "CITY_WARLOCK" to setOf("old_king","resistance","failure","more_powerful","new_power","seen_it"),
  "HALLS_KING" to setOf("Rejection","amulet","ritual","new_king","thing","attrition")
 )
}
