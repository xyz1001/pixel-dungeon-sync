package pdsync

/** The three persistent global bundles are independent merge domains, never one all-or-nothing blob. */
internal object GlobalRecordMerge {
 data class Result(val files: Map<String,ByteArray>,val decisions: List<String>)
 private val supported=setOf("badges.dat","journal.dat","rankings.dat")
 fun plan(base: Map<String,ByteArray>, branches: List<Map<String,ByteArray>>): Result {
  require(branches.isNotEmpty())
  val keys=(base.keys+branches.flatMap { it.keys }).sorted()
  if(keys.any { it !in supported })globalFail("存在不支持的全局文件")
  val output=linkedMapOf<String,ByteArray>();val decisions=mutableListOf<String>()
  fun same(a:ByteArray?,b:ByteArray?)=if(a==null)b==null else b!=null&&a.contentEquals(b)
  for(path in keys) {
   val old=base[path]
   val changed=branches.map { it[path] }.filter { !same(old,it) }
   if(changed.isEmpty()) { old?.let { output[path]=it };continue }
   if(changed.any { it==null })globalFail("$path 被删除或重置，不能在合并中推断是否应丢弃记录")
   val originals=changed.filterNotNull()
   val before=old?.let { GlobalBundles.decode(it,path) }
   val inputs=originals.map { GlobalBundles.decode(it,path) }
   val result=when(path) {
    "badges.dat" -> BadgeMerge.merge(before,inputs)
    "journal.dat" -> JournalMerge.merge(before,inputs)
    "rankings.dat" -> RankingsMerge.merge(before,inputs)
    else -> globalFail("不支持的全局文件 $path")
   }
   if(changed.size==1 && path!="journal.dat") {
    // Other domains preserve the sole source encoding after validation. Journal must
    // apply its baseline-aware maxima even when the only changed branch was reset.
    output[path]=originals.single();decisions+="$path：校验后采用唯一分支的改动";continue
   }
   // Reuse an exact original encoding if semantics match; otherwise write deterministic gzip JSON.
   output[path]=originals.zip(inputs).firstOrNull { it.second==result }?.first ?: GlobalBundles.encode(result)
   decisions+="$path：通过 3.3.8 语义校验后合并 ${inputs.size} 个分支"
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
   val states=bundle["catalog_seen"] as? kotlinx.serialization.json.JsonArray ?: globalFail("旧徽章图鉴的发现数组缺失")
   if(states.size!=names.size)globalFail("旧徽章图鉴的平行数组不完整")
   return names.mapIndexed { i,name -> name to globalBoolean(states[i],"journal.dat/catalog_seen") }.toMap()
  }
  val input=sources.map(::rows);val output=rows(result)
  if(flags.first())for(name in input.drop(1).flatMap { it.keys }.toSet()) {
   if(name !in input.first() && input.drop(1).any { it[name]==false })
    globalFail("旧徽章驱动的图鉴状态可能回退，缺少已验证的分类迁移依据")
  }
  for((name,seen) in output)if(!seen && input.indices.any { flags[it] && name !in input[it] })
   globalFail("旧徽章与缺席的图鉴条目存在隐式发现状态，不能丢弃该状态")
 }

}
