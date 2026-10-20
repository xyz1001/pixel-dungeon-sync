package pdsync

import pdsync.i18n.CoreMessages
/** The three persistent global bundles are independent merge domains, never one all-or-nothing blob. */
internal object GlobalRecordMerge {
 data class Result(val files: Map<String,ByteArray>,val decisions: List<String>)
 private val supported=setOf("badges.dat","journal.dat","rankings.dat")
 fun plan(base: Map<String,ByteArray>, branches: List<Map<String,ByteArray>>): Result {
  require(branches.isNotEmpty())
  val keys=(base.keys+branches.flatMap { it.keys }).sorted()
  if(keys.any { it !in supported })globalFail(CoreMessages.UNSUPPORTED_GLOBAL_FILES_FOUND.text())
  val output=linkedMapOf<String,ByteArray>();val decisions=mutableListOf<String>()
  fun same(a:ByteArray?,b:ByteArray?)=if(a==null)b==null else b!=null&&a.contentEquals(b)
  for(path in keys) {
   val old=base[path]
   val changed=branches.map { it[path] }.filter { !same(old,it) }
   if(changed.isEmpty()) { old?.let { output[path]=it };continue }
   // A missing whole file is a reset, not evidence that earned progress should be erased.
   // Even an all-reset recovery validates the retained baseline before writing it.
   val originals=changed.filterNotNull().ifEmpty { listOf(requireNotNull(old)) }
   val before=old?.let { GlobalBundles.decode(it,path) }
   val inputs=originals.map { GlobalBundles.decode(it,path) }
   val result=when(path) {
    "badges.dat" -> BadgeMerge.merge(before,inputs)
    "journal.dat" -> JournalMerge.merge(before,inputs)
    "rankings.dat" -> RankingsMerge.merge(before,inputs)
    else -> globalFail(CoreMessages.UNSUPPORTED_GLOBAL_FILE_1_S.text(path))
   }
   // Reuse an exact original encoding if semantics match; otherwise write deterministic gzip JSON.
   output[path]=originals.zip(inputs).firstOrNull { it.second==result }?.first ?: GlobalBundles.encode(result)
   decisions+=CoreMessages.TEXT_1_S_MERGED_2_S_BRANCHES_AFTER_3_3_8_SEMANTIC_VALIDATION.text(path, inputs.size)
  }
  if(keys.any { path -> path in setOf("badges.dat","journal.dat") && branches.any { !same(base[path],it[path]) } })
   validateLegacyCatalogDefaults(base,branches,output)
  return Result(output,decisions)
 }
 /** Old ALL_* badges supply implicit seen=true before modern rows override them.
  * Without a class/category migration model, reject ambiguous missing-row/false combinations.
  */
 private fun validateLegacyCatalogDefaults(base:Map<String,ByteArray>, branches:List<Map<String,ByteArray>>, result:Map<String,ByteArray>) {
  val legacy=setOf("ALL_ITEMS_IDENTIFIED","ALL_WEAPONS_IDENTIFIED","ALL_ARMOR_IDENTIFIED","ALL_WANDS_IDENTIFIED","ALL_RINGS_IDENTIFIED","ALL_ARTIFACTS_IDENTIFIED","ALL_POTIONS_IDENTIFIED","ALL_SCROLLS_IDENTIFIED")
  fun hasLegacy(files:Map<String,ByteArray>):Boolean=files["badges.dat"]?.let {
   globalStringSet(GlobalBundles.decode(it,"badges.dat")["badges"],"badges.dat/badges").any { name -> name in legacy }
  } ?: false
  val sources=listOf(base)+branches
  val flags=sources.map(::hasLegacy)
  if(flags.none { it })return
  fun rows(files:Map<String,ByteArray>):Map<String,Boolean> {
   val bundle=files["journal.dat"]?.let { GlobalBundles.decode(it,"journal.dat") } ?: return emptyMap()
   val classes=bundle["catalog_classes"] ?: return emptyMap()
   val names=globalStringSet(classes,"journal.dat/catalog_classes").toList()
   val states=bundle["catalog_seen"] as? kotlinx.serialization.json.JsonArray ?: globalFail(CoreMessages.DISCOVERY_ARRAY_IS_MISSING_FROM_THE_LEGACY_BADGE_CATALOG.text())
   if(states.size!=names.size)globalFail(CoreMessages.PARALLEL_ARRAYS_IN_THE_LEGACY_BADGE_CATALOG_ARE_INCOMPLETE.text())
   return names.mapIndexed { i,name -> name to globalBoolean(states[i],"journal.dat/catalog_seen") }.toMap()
  }
  val input=sources.map(::rows);val output=rows(result)
  if(flags.first())for(name in input.drop(1).flatMap { it.keys }.toSet()) {
   if(name !in input.first() && input.drop(1).any { it[name]==false })
    globalFail(CoreMessages.LEGACY_BADGE_DRIVEN_CATALOG_STATE_COULD_REGRESS_VERIFIED_CATEGORY_MIGRATION_INFORMATI.text())
  }
  for((name,seen) in output)if(!seen && input.indices.any { flags[it] && name !in input[it] })
   globalFail(CoreMessages.LEGACY_BADGES_AND_MISSING_CATALOG_ENTRIES_IMPLY_DISCOVERY_STATE_THAT_CANNOT_BE_DISCAR.text())
 }

}
