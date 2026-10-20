package pdsync

import pdsync.i18n.CoreMessages
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
  if (branches.isEmpty()) globalFail(CoreMessages.RANKINGS_HAS_NO_BRANCHES_TO_MERGE.text())
  val original = base?.let(::read) ?: empty
  val states = branches.map(::read)
  val out = mergeOpaqueFields(base, branches, keys, CoreMessages.RANKINGS.text())
  val sources = listOf(original) + states
  val allRecords = linkedMapOf<String, Record>()
  val variants = sources.flatMap { it.records.values + listOfNotNull(it.daily) }.groupBy { it.id }
  for ((id, versions) in variants) {
   // One event cannot occupy both official containers. Choosing its role would lose the
   // only daily object while retaining its history, or put a daily row in normal records.
   if (versions.map { it.daily }.distinct().size != 1)
    globalFail(CoreMessages.RANKINGS_ASSIGNS_THE_SAME_GAMEID_TO_BOTH_REGULAR_AND_DAILY_RUNS.text())
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
  if (historyDates.size > HISTORY_CAPACITY) globalFail(CoreMessages.MERGED_DAILY_HISTORY_EXCEEDS_THE_VERIFIED_CAPACITY_OF_1_S_ENTRIES.text(HISTORY_CAPACITY))
  for (date in historyDates) {
   history[date] = sources.mapNotNull { it.history[date] }.max()
  }
  latestDaily?.let { out["latest_daily"] = it.raw }
  out["daily_history_dates"] = JsonArray(history.keys.map { JsonPrimitive(it) })
  out["daily_history_scores"] = JsonArray(history.values.map { JsonPrimitive(it) })
  return JsonObject(out)
 }

 private fun read(root: JsonObject): State {
  val array = root["records"] as? JsonArray ?: globalFail(CoreMessages.RANKINGS_RECORDS_IS_NOT_AN_ARRAY.text())
  if (array.size > CAPACITY) globalFail(CoreMessages.RANKINGS_INPUT_EXCEEDS_THE_VERIFIED_CAPACITY_OF_1_S_ENTRIES.text(CAPACITY))
  val records = linkedMapOf<String, Record>()
  array.forEach { value ->
   val rec = record(value, false)
   if (records.put(rec.id, rec) != null) globalFail(CoreMessages.RANKINGS_CONTAINS_DUPLICATE_GAMEIDS.text())
  }
  val storedTotal = globalInt(root["total"], CoreMessages.RANKINGS_TOTAL.text())
  val storedWon = globalInt(root["won"], CoreMessages.RANKINGS_WON.text())
  val loadedTotal = if (storedTotal == 0) records.size else storedTotal
  val loadedWon = if (storedWon == 0) records.values.count { it.win } else storedWon
  val zeroFallback = loadedTotal != storedTotal || loadedWon != storedWon
  if (storedWon > storedTotal && !zeroFallback) globalFail(CoreMessages.EXPLICIT_RANKING_WIN_COUNT_EXCEEDS_TOTAL_RUNS.text())
  // Small/reset counters are incomplete evidence, not malformed JSON. Retain the
  // game's observed counts and the visible ordinary-event lower bound without adding.
  val won = maxOf(loadedWon, records.values.count { it.seed.isEmpty() && it.win })
  val total = maxOf(loadedTotal, records.values.count { it.seed.isEmpty() }, won)
  val exactCounters = !zeroFallback && total == storedTotal && won == storedWon
  val latestValue = root["latest"] as? JsonPrimitive ?: globalFail(CoreMessages.RANKINGS_IS_MISSING_THE_LATEST_INDEX.text())
  val latest = latestValue.intOrNull
  if (latestValue.isString || latest == null || (latest !in 0 until records.size && latest != -1 && !(records.isEmpty() && latest == 0)))
   globalFail(CoreMessages.RANKINGS_LATEST_INDEX_IS_OUTSIDE_THE_RECORD_RANGE.text())
  val daily = root["latest_daily"]?.takeUnless { it == JsonNull }?.let { record(it, true) }
  val dateValues = root["daily_history_dates"]
  val scoreValues = root["daily_history_scores"]
  val history = linkedMapOf<Long, Int>()
  if (dateValues != null || scoreValues != null) {
   val dates = dateValues as? JsonArray ?: globalFail(CoreMessages.DAILY_DATES_IS_NOT_AN_ARRAY.text())
   val scores = scoreValues as? JsonArray ?: globalFail(CoreMessages.DAILY_SCORES_IS_NOT_AN_ARRAY.text())
   if (dates.size != scores.size || dates.size > HISTORY_CAPACITY) globalFail(CoreMessages.DAILY_DATES_AND_SCORES_ARE_NOT_ONE_TO_ONE_OR_EXCEED_THE_ALLOWED_RANGE.text())
   dates.forEachIndexed { i, value ->
    val primitive = value as? JsonPrimitive ?: globalFail(CoreMessages.DAILY_DATE_IS_NOT_AN_INTEGER.text())
    val date = primitive.longOrNull
    if (primitive.isString || date == null || !primitive.content.matches(Regex("0|[1-9][0-9]*"))) globalFail(CoreMessages.DAILY_DATE_IS_NOT_A_VALID_NONNEGATIVE_LONG.text())
    if (history.put(date, globalInt(scores[i], CoreMessages.DAILY_SCORE.text())) != null) globalFail(CoreMessages.DAILY_HISTORY_CONTAINS_DUPLICATE_DATES.text())
   }
  }
  if ((daily == null) != history.isEmpty()) globalFail(CoreMessages.COMPLETE_DAILY_RECORDS_DO_NOT_MATCH_THE_HISTORY_ARRAYS.text())
  return State(records, records.keys.elementAtOrNull(latest!!), total, won, daily, history, exactCounters)
 }

 private fun record(value: JsonElement, daily: Boolean): Record {
  val raw = value as? JsonObject ?: globalFail(CoreMessages.RANKINGS_ENTRY_IS_NOT_AN_OBJECT.text())
  if ((raw.keys - recordKeys).isNotEmpty()) globalFail(CoreMessages.RANKINGS_ENTRY_CONTAINS_UNVERIFIED_FIELDS_THEIR_STATISTICAL_MEANING_CANNOT_BE_INTERPR.text())
  if (string(raw["__className"], CoreMessages.RANKINGS_RECORD_CLASS.text()) != RECORD_CLASS) globalFail(CoreMessages.RANKINGS_RECORD_IS_NOT_A_VERIFIED_OFFICIAL_TYPE.text())
  val id = string(raw["gameID"], CoreMessages.RANKINGS_GAMEID.text())
  if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) globalFail(CoreMessages.RANKINGS_HAS_NO_VALID_STABLE_GAMEID_AN_IDENTITY_CANNOT_BE_FABRICATED.text())
  val score = globalInt(raw["score"], CoreMessages.RANKINGS_SCORE.text())
  val seed = string(raw["custom_seed"], CoreMessages.RANKINGS_CUSTOM_SEED.text())
  val win = globalBoolean(raw["win"], CoreMessages.RANKINGS_WIN.text())
  if (globalBoolean(raw["daily"], CoreMessages.RANKINGS_DAILY.text()) != daily) globalFail(CoreMessages.REGULAR_AND_DAILY_RANKINGS_RECORDS_ARE_MIXED.text())
  if (string(raw["class"], CoreMessages.RANKINGS_HERO_CLASS.text()) !in heroClasses) globalFail(CoreMessages.RANKINGS_CONTAINS_AN_UNKNOWN_HERO_CLASS.text())
  listOf("tier", "level", "depth").forEach { globalInt(raw[it], CoreMessages.RANKINGS_1_S.text(it)) }
  globalBoolean(raw["ascending"], CoreMessages.RANKINGS_ASCENDING.text())
  listOf("cause", "date", "version").forEach { key -> raw[key]?.takeUnless { it == JsonNull }?.let { string(it, CoreMessages.RANKINGS_1_S.text(key)) } }
  raw["version"]?.takeUnless { it == JsonNull }?.let { value ->
   val version = string(value, CoreMessages.RANKINGS_VERSION.text())
   if (version.isNotEmpty()) {
    val parts = Regex("v([0-9]+)\\.([0-9]+)\\.([0-9]+)").matchEntire(version)?.groupValues?.drop(1)?.map { it.toIntOrNull() }
    if (parts == null || parts.any { it == null }) globalFail(CoreMessages.RANKINGS_CONTAINS_AN_UNRECOGNIZED_RECORD_VERSION.text())
    val numbers = parts.filterNotNull()
    if (numbers[0] > 3 || (numbers[0] == 3 && (numbers[1] > 3 || (numbers[1] == 3 && numbers[2] > 8))))
     globalFail(CoreMessages.RANKINGS_RECORD_VERSION_EXCEEDS_THE_VERIFIED_3_3_8_VERSION.text())
   }
  }
  raw["gameData"]?.let { data ->
   if (data !is JsonObject) globalFail(CoreMessages.RANKINGS_GAMEDATA_IS_NOT_A_COMPLETE_OBJECT.text())
   data["game_version"]?.let { if (globalInt(it, CoreMessages.RANKINGS_INITIAL_VERSION.text()) > 896) globalFail(CoreMessages.RANKINGS_INITIAL_VERSION_EXCEEDS_THE_VERIFIED_896_VERSION.text()) }
  }
  val dateText = raw["date"]?.takeUnless { it == JsonNull }?.let { string(it, CoreMessages.RANKINGS_DATE.text()) }
  val date = if (dateText.isNullOrEmpty()) null else {
   if (!dateText.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) globalFail(CoreMessages.RANKINGS_DATE_IS_NOT_A_VALID_DATE.text())
   runCatching { LocalDate.parse(dateText) }.getOrElse { globalFail(CoreMessages.RANKINGS_DATE_IS_NOT_A_VALID_DATE.text()) }
  }
  return Record(id, raw, score, seed, win, daily, date, stableJson(raw).toString())
 }
 private fun string(value: JsonElement?, label: String): String {
  val p = value as? JsonPrimitive ?: globalFail(CoreMessages.TEXT_1_S_IS_MISSING_A_STRING.text(label))
  if (!p.isString || p.content.length > 4096) globalFail(CoreMessages.TEXT_1_S_IS_NOT_A_SUPPORTED_STRING.text(label))
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
