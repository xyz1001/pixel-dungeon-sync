package pdsync

import kotlinx.serialization.json.*
import java.util.UUID

/**
 * 3.3.8 rankings merge with a score-ranked display capped at the official capacity.
 * Counters describe recorded end-of-game submissions, not
 * active-run identities. Each branch must expose every counted addition since the base.
 * Counters are proved against the complete event union before display rows are trimmed.
 * No game classes are instantiated and no missing events or counts are guessed.
 */
internal object RankingsMerge {
 private const val CAPACITY = 11
 private const val RECORD_CLASS = "com.shatteredpixel.shatteredpixeldungeon.Rankings\$Record"
 private val keys = setOf("records", "latest", "total", "won", "latest_daily", "daily_history_dates", "daily_history_scores")
 private val recordKeys = setOf("__className", "cause", "win", "score", "class", "tier", "level", "depth", "ascending", "gameData", "gameID", "custom_seed", "daily", "date", "version")
 private val heroClasses = setOf("WARRIOR", "MAGE", "ROGUE", "HUNTRESS", "DUELIST", "CLERIC")
 private data class Record(val id: String, val raw: JsonObject, val score: Int, val seed: String, val win: Boolean)
 private data class State(val records: Map<String, Record>, val latest: String?, val total: Int, val won: Int,
                          val daily: Record?, val history: Map<Long, Int>)
 private val empty = State(emptyMap(), null, 0, 0, null, emptyMap())

 fun merge(base: JsonObject?, branches: List<JsonObject>): JsonObject {
  if (branches.isEmpty()) globalFail("排行榜缺少待合并分支")
  val original = base?.let(::read) ?: empty
  val states = branches.map(::read)
  val out = mergeOpaqueFields(base, branches, keys, "排行榜")
  val allRecords = linkedMapOf<String, Record>()
  // Include the base: losing an old retained record is not silently treated as a deletion.
  (listOf(original) + states).forEach { state ->
   (state.records.values + listOfNotNull(state.daily)).forEach { rec ->
    val previous = allRecords.putIfAbsent(rec.id, rec)
    if (previous != null && previous.raw != rec.raw) globalFail("排行榜同一 gameID 出现不同内容，无法安全去重")
   }
  }
  val additions = linkedMapOf<String, Record>()
  for (state in states) {
   if (!state.records.keys.containsAll(original.records.keys)) globalFail("排行榜基线记录消失，可能已截断、删除或重置")
   if (state.total < original.total || state.won < original.won) globalFail("排行榜累计统计减少，不能合并重置后的历史")
   val newRecords = state.records.filterKeys { it !in original.records }
   val counted = newRecords.values.filter { it.seed.isEmpty() }
   if (state.total.toLong() - original.total != counted.size.toLong() ||
       state.won.toLong() - original.won != counted.count { it.win }.toLong()) {
    globalFail("排行榜累计增量无法由可见的新 gameID 完整证明，可能有截断或历史重叠")
   }
   additions.putAll(newRecords)
  }
  val records = (original.records + additions).values.toList()
  val total = checkedCount(original.total, additions.values.count { it.seed.isEmpty() }, "总局数")
  val won = checkedCount(original.won, additions.values.count { it.seed.isEmpty() && it.win }, "胜局数")
  validateZeroFallback(records, total, won)

  // User-selected merge policy: highest scores across all normal/custom-seed rows.
  // Official load preserves this order. A future game submission applies the game's own
  // ordinary-first/latest-retaining policy. Compare without signed subtraction overflow;
  // use the complete stable ID to break the (possible) String.hashCode collision.
  val sorted = records.sortedWith(
   compareByDescending<Record> { it.score }.thenByDescending { it.id.hashCode() }.thenBy { it.id }
  ).take(CAPACITY)
  val changedLatest = states.map { it.latest }.filter { it != original.latest }.distinct()
  val latest = when (changedLatest.size) { 0 -> original.latest; 1 -> changedLatest.single(); else -> null }
  // A truncated latest ID, like an ambiguous latest, becomes -1 (no highlight).
  // RankingsScene only compares pos == latest; no dangling index is written.
  out["records"] = JsonArray(sorted.map { it.raw })
  out["latest"] = JsonPrimitive(sorted.indexOfFirst { it.id == latest })
  out["total"] = JsonPrimitive(total)
  out["won"] = JsonPrimitive(won)

  val changedDaily = states.map { it.daily?.raw }.filter { it != original.daily?.raw }.distinct()
  if (states.any { original.daily != null && it.daily == null }) globalFail("每日完整记录被删除，无法无损合并")
  if (changedDaily.size > 1) globalFail("双方新增了不同的每日完整记录，单个 latest_daily 无法无损保留")
  val latestDaily = if (changedDaily.isEmpty()) original.daily?.raw else changedDaily.single()
  val dates = (original.history.keys + states.flatMap { it.history.keys }).sorted()
  val history = linkedMapOf<Long, Int>()
  for (date in dates) {
   val old = original.history[date]
   if (old != null && states.any { date !in it.history }) globalFail("每日历史日期被删除，无法安全合并")
   val changes = states.map { it.history[date] }.filter { it != old }.distinct()
   if (changes.size > 1) globalFail("同一日期的每日成绩有不同修改，不能选择或捏造分数")
   val value = if (changes.isEmpty()) old else changes.single()
   value?.let { history[date] = it }
  }
  latestDaily?.let { out["latest_daily"] = it }
  out["daily_history_dates"] = JsonArray(history.keys.map { JsonPrimitive(it) })
  out["daily_history_scores"] = JsonArray(history.values.map { JsonPrimitive(it) })
  return JsonObject(out)
 }

 private fun read(root: JsonObject): State {
  val array = root["records"] as? JsonArray ?: globalFail("排行榜 records 不是数组")
  if (array.size > CAPACITY) globalFail("排行榜输入超过已验证的 $CAPACITY 条容量")
  val records = linkedMapOf<String, Record>()
  array.forEach { value ->
   val rec = record(value, false)
   if (records.put(rec.id, rec) != null) globalFail("排行榜包含重复 gameID")
  }
  val total = globalInt(root["total"], "排行榜 total")
  val won = globalInt(root["won"], "排行榜 won")
  if (won > total || total < records.values.count { it.seed.isEmpty() } || won < records.values.count { it.seed.isEmpty() && it.win })
   globalFail("排行榜累计统计与可见记录矛盾")
  validateZeroFallback(records.values, total, won)
  val latestValue = root["latest"] as? JsonPrimitive ?: globalFail("排行榜缺少 latest 索引")
  val latest = latestValue.intOrNull
  if (latestValue.isString || latest == null || (latest !in 0 until records.size && latest != -1 && !(records.isEmpty() && latest == 0)))
   globalFail("排行榜 latest 索引超出记录范围")
  val daily = root["latest_daily"]?.takeUnless { it == JsonNull }?.let { record(it, true) }
  val dateValues = root["daily_history_dates"]
  val scoreValues = root["daily_history_scores"]
  val history = linkedMapOf<Long, Int>()
  if (dateValues != null || scoreValues != null) {
   val dates = dateValues as? JsonArray ?: globalFail("每日日期不是数组")
   val scores = scoreValues as? JsonArray ?: globalFail("每日成绩不是数组")
   if (dates.size != scores.size || dates.size > 365_000) globalFail("每日日期和成绩未一一对应或超出范围")
   dates.forEachIndexed { i, value ->
    val primitive = value as? JsonPrimitive ?: globalFail("每日日期不是整数")
    val date = primitive.longOrNull
    if (primitive.isString || date == null || !primitive.content.matches(Regex("0|[1-9][0-9]*"))) globalFail("每日日期不是有效的非负 long")
    if (history.put(date, globalInt(scores[i], "每日成绩")) != null) globalFail("每日历史包含重复日期")
   }
  }
  if ((daily == null) != history.isEmpty()) globalFail("每日完整记录与历史数组不一致")
  return State(records, records.keys.elementAtOrNull(latest!!), total, won, daily, history)
 }

 private fun record(value: JsonElement, daily: Boolean): Record {
  val raw = value as? JsonObject ?: globalFail("排行榜条目不是对象")
  if ((raw.keys - recordKeys).isNotEmpty()) globalFail("排行榜条目含未验证字段，不能解释其统计语义")
  if (string(raw["__className"], "排行榜记录类") != RECORD_CLASS) globalFail("排行榜记录不是已验证的官方类型")
  val id = string(raw["gameID"], "排行榜 gameID")
  if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) globalFail("排行榜缺少稳定有效的 gameID，不能补造身份")
  val score = globalInt(raw["score"], "排行榜 score")
  val seed = string(raw["custom_seed"], "排行榜 custom_seed")
  val win = globalBoolean(raw["win"], "排行榜 win")
  if (globalBoolean(raw["daily"], "排行榜 daily") != daily) globalFail("普通和每日排行榜记录混放")
  if (string(raw["class"], "排行榜职业") !in heroClasses) globalFail("排行榜含未知职业")
  listOf("tier", "level", "depth").forEach { globalInt(raw[it], "排行榜 $it") }
  globalBoolean(raw["ascending"], "排行榜 ascending")
  listOf("cause", "date", "version").forEach { key -> raw[key]?.takeUnless { it == JsonNull }?.let { string(it, "排行榜 $key") } }
  raw["version"]?.takeUnless { it == JsonNull }?.let { value ->
   val version = string(value, "排行榜 version")
   if (version.isNotEmpty()) {
    val parts = Regex("v([0-9]+)\\.([0-9]+)\\.([0-9]+)").matchEntire(version)?.groupValues?.drop(1)?.map { it.toIntOrNull() }
    if (parts == null || parts.any { it == null }) globalFail("排行榜存在无法识别的记录版本")
    val numbers = parts.filterNotNull()
    if (numbers[0] > 3 || (numbers[0] == 3 && (numbers[1] > 3 || (numbers[1] == 3 && numbers[2] > 8))))
     globalFail("排行榜记录版本超出已验证的 3.3.8")
   }
  }
  raw["gameData"]?.let { data ->
   if (data !is JsonObject) globalFail("排行榜 gameData 不是完整对象")
   data["game_version"]?.let { if (globalInt(it, "排行榜初始版本") > 896) globalFail("排行榜初始版本超出已验证的 896") }
  }
  return Record(id, raw, score, seed, win)
 }
 private fun string(value: JsonElement?, label: String): String {
  val p = value as? JsonPrimitive ?: globalFail("$label 缺少字符串")
  if (!p.isString || p.content.length > 4096) globalFail("$label 不是受支持的字符串")
  return p.content
 }
 private fun checkedCount(base: Int, addition: Int, label: String): Int {
  val value = base.toLong() + addition
  if (value > Int.MAX_VALUE) globalFail("排行榜 $label 合并溢出")
  return value.toInt()
 }
 private fun validateZeroFallback(records: Collection<Record>, total: Int, won: Int) {
  // Rankings.load() substitutes records.size()/all winning rows when a stored count is 0.
  if ((total == 0 && records.isNotEmpty()) || (won == 0 && records.any { it.win }))
   globalFail("排行榜零计数会触发游戏的历史回填，缺少可信统计基线")
 }
}
