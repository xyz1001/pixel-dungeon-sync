package pdsync

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.UUID

/**
 * 3.3.8 rankings merge with a score-ranked display capped at the official capacity.
 * Complete, consistent gameID evidence proves cumulative additions. Missing/truncated/reset
 * evidence instead keeps conservative observed maxima and the full event union's lower bounds;
 * it may undercount lifetime history, but never repeatedly adds an unproved branch delta.
 * Whole records are selected deterministically; gameData is never merged field by field.
 */
internal object RankingsMerge {
 private const val CAPACITY = 11
 private const val HISTORY_CAPACITY = 365_000
 private const val RECORD_CLASS = "com.shatteredpixel.shatteredpixeldungeon.Rankings\$Record"
 private val keys = setOf("records", "latest", "total", "won", "latest_daily", "daily_history_dates", "daily_history_scores")
 private val recordKeys = setOf("__className", "cause", "win", "score", "class", "tier", "level", "depth", "ascending", "gameData", "gameID", "custom_seed", "daily", "date", "version")
 private val heroClasses = setOf("WARRIOR", "MAGE", "ROGUE", "HUNTRESS", "DUELIST", "CLERIC")
 private data class Record(val id: String, val raw: JsonObject, val score: Int, val seed: String,
                           val win: Boolean, val daily: Boolean, val date: LocalDate?, val stableJson: String)
 private data class State(val records: Map<String, Record>, val latest: String?, val total: Int, val won: Int,
                          val daily: Record?, val history: Map<Long, Int>, val exactCounters: Boolean = true)
 private val empty = State(emptyMap(), null, 0, 0, null, emptyMap())

 fun merge(base: JsonObject?, branches: List<JsonObject>): JsonObject {
  if (branches.isEmpty()) globalFail("排行榜缺少待合并分支")
  val original = base?.let(::read) ?: empty
  val states = branches.map(::read)
  val out = mergeOpaqueFields(base, branches, keys, "排行榜")
  val sources = listOf(original) + states
  val allRecords = linkedMapOf<String, Record>()
  val variants = sources.flatMap { it.records.values + listOfNotNull(it.daily) }.groupBy { it.id }
  for ((id, versions) in variants) {
   // One event cannot occupy both official containers. Choosing its role would lose the
   // only daily object while retaining its history, or put a daily row in normal records.
   if (versions.map { it.daily }.distinct().size != 1)
    globalFail("排行榜同一 gameID 的普通/每日归属矛盾")
   allRecords[id] = versions.maxWith(if (versions.first().daily) dailyPreference else recordPreference)
  }
  val records = allRecords.values.filter { !it.daily }
  val observedRows = sources.flatMap { it.records.values }
  // Include every observed identity before display clipping or whole-record selection.
  val countedIds = observedRows.filter { it.seed.isEmpty() }.map { it.id }.toSet()
  val winningIds = observedRows.filter { it.seed.isEmpty() && it.win }.map { it.id }.toSet()
  val consistentIdentityCounts = variants.values.filter { !it.first().daily }.all { versions ->
   versions.map { Pair(it.seed.isEmpty(), it.win) }.distinct().size == 1
  }
  val completeEvidence = sources.all { it.exactCounters } && consistentIdentityCounts && states.all { state ->
   val newCounted = state.records.values.filter { it.id !in original.records && it.seed.isEmpty() }
   state.records.keys.containsAll(original.records.keys) &&
    state.total >= original.total && state.won >= original.won &&
    state.total.toLong() - original.total == newCounted.size.toLong() &&
    state.won.toLong() - original.won == newCounted.count { it.win }.toLong()
  }
  val additions = records.filter { it.id !in original.records && it.seed.isEmpty() }
  val exactTotal = original.total.toLong() + additions.size
  val exactWon = original.won.toLong() + additions.count { it.win }
  val useExact = completeEvidence && exactTotal <= Int.MAX_VALUE && exactWon <= Int.MAX_VALUE
  var won = if (useExact) exactWon.toInt() else maxOf(sources.maxOf { it.won }, winningIds.size)
  var total = if (useExact) exactTotal.toInt() else maxOf(sources.maxOf { it.total }, countedIds.size, won)
  // The official loader treats zero as a legacy sentinel and includes seeded rows in its
  // fallback. Write that interpretation explicitly so reload cannot silently change it.
  // This is compatibility normalization, not proof that seeded events were ordinary runs.
  if (total == 0) total = records.size
  if (won == 0) won = records.count { it.win }
  total = maxOf(total, won)

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

  // Daily rec.date is the actual recorded completion date. A history's largest key can
  // belong to a different event, and must not be used to date its latest_daily object.
  val latestDaily = allRecords.values.filter { it.daily }.maxWithOrNull(dailyPreference)
  val history = linkedMapOf<Long, Int>()
  val historyDates = sources.flatMap { it.history.keys }.toSortedSet()
  if (historyDates.size > HISTORY_CAPACITY) globalFail("每日历史合并超过已验证的 $HISTORY_CAPACITY 条容量")
  for (date in historyDates) {
   history[date] = sources.mapNotNull { it.history[date] }.max()
  }
  latestDaily?.let { out["latest_daily"] = it.raw }
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
  val storedTotal = globalInt(root["total"], "排行榜 total")
  val storedWon = globalInt(root["won"], "排行榜 won")
  val loadedTotal = if (storedTotal == 0) records.size else storedTotal
  val loadedWon = if (storedWon == 0) records.values.count { it.win } else storedWon
  val zeroFallback = loadedTotal != storedTotal || loadedWon != storedWon
  if (storedWon > storedTotal && !zeroFallback) globalFail("排行榜显式胜局数超过总局数")
  // Small/reset counters are incomplete evidence, not malformed JSON. Retain the
  // game's observed counts and the visible ordinary-event lower bound without adding.
  val won = maxOf(loadedWon, records.values.count { it.seed.isEmpty() && it.win })
  val total = maxOf(loadedTotal, records.values.count { it.seed.isEmpty() }, won)
  val exactCounters = !zeroFallback && total == storedTotal && won == storedWon
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
   if (dates.size != scores.size || dates.size > HISTORY_CAPACITY) globalFail("每日日期和成绩未一一对应或超出范围")
   dates.forEachIndexed { i, value ->
    val primitive = value as? JsonPrimitive ?: globalFail("每日日期不是整数")
    val date = primitive.longOrNull
    if (primitive.isString || date == null || !primitive.content.matches(Regex("0|[1-9][0-9]*"))) globalFail("每日日期不是有效的非负 long")
    if (history.put(date, globalInt(scores[i], "每日成绩")) != null) globalFail("每日历史包含重复日期")
   }
  }
  if ((daily == null) != history.isEmpty()) globalFail("每日完整记录与历史数组不一致")
  return State(records, records.keys.elementAtOrNull(latest!!), total, won, daily, history, exactCounters)
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
  val dateText = raw["date"]?.takeUnless { it == JsonNull }?.let { string(it, "排行榜 date") }
  val date = if (dateText.isNullOrEmpty()) null else {
   if (!dateText.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) globalFail("排行榜 date 不是有效日期")
   runCatching { LocalDate.parse(dateText) }.getOrElse { globalFail("排行榜 date 不是有效日期") }
  }
  return Record(id, raw, score, seed, win, daily, date, stableJson(raw).toString())
 }
 private fun string(value: JsonElement?, label: String): String {
  val p = value as? JsonPrimitive ?: globalFail("$label 缺少字符串")
  if (!p.isString || p.content.length > 4096) globalFail("$label 不是受支持的字符串")
  return p.content
 }
 // Prefer an intact details object, then an actual date, score, and the entire stable
 // JSON value. Sorting object keys makes the tie-break independent of source/key order;
 // array order and every value are preserved. Only a whole source record is returned.
 private val recordPreference = compareBy<Record> { it.raw["gameData"] is JsonObject }
  .thenBy { it.date ?: LocalDate.MIN }.thenBy { it.score }.thenBy { it.stableJson }
 private val dailyPreference = compareBy<Record> { it.date ?: LocalDate.MIN }.then(recordPreference)
 private fun stableJson(value: JsonElement): JsonElement = when (value) {
  is JsonObject -> JsonObject(value.toSortedMap().mapValues { stableJson(it.value) })
  is JsonArray -> JsonArray(value.map(::stableJson))
  else -> value
 }
}
