package pdsync

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class RankingsMergeTest {
 private fun record(number: Int, score: Int = number * 100, win: Boolean = false, seed: String = "", daily: Boolean = false): JsonObject = buildJsonObject {
  put("__className", "com.shatteredpixel.shatteredpixeldungeon.Rankings\$Record")
  put("gameID", "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}")
  put("score", score); put("win", win); put("custom_seed", seed); put("daily", daily)
  put("class", "WARRIOR"); put("tier", 1); put("level", 1); put("depth", 1); put("ascending", false)
  put("date", "2026-10-11"); put("version", "v3.3.8")
  put("gameData", buildJsonObject { put("hero", buildJsonObject { put("HP", 1); put("equipment", JsonArray(listOf(JsonPrimitive(number)))) }) })
 }
 private fun ranking(records: List<JsonObject>, total: Int = records.count { it["custom_seed"]!!.jsonPrimitive.content.isEmpty() },
                     won: Int = records.count { it["custom_seed"]!!.jsonPrimitive.content.isEmpty() && it["win"]!!.jsonPrimitive.boolean },
                     latest: Int = records.lastIndex, daily: JsonObject? = null, history: Map<Long, Int> = emptyMap()): JsonObject = buildJsonObject {
  put("records", JsonArray(records)); put("total", total); put("won", won); put("latest", latest)
  daily?.let { put("latest_daily", it) }
  put("daily_history_dates", JsonArray(history.keys.map { JsonPrimitive(it) }))
  put("daily_history_scores", JsonArray(history.values.map { JsonPrimitive(it) }))
 }
 private fun change(value: JsonObject, key: String, replacement: JsonElement?) = JsonObject(value.toMutableMap().apply { if (replacement == null) remove(key) else put(key, replacement) })
 private fun merged(base: JsonObject?, vararg branches: JsonObject) = RankingsMerge.merge(base, branches.toList())
 private fun assertRejected(base: JsonObject?, vararg branches: JsonObject, reason: String? = null) {
  val e = assertFailsWith<RunMergeException> { merged(base, *branches) }
  if (reason != null) assertContains(e.message!!, reason)
 }
 private fun ids(value: JsonObject) = value["records"]!!.jsonArray.map { it.jsonObject["gameID"]!!.jsonPrimitive.content }.toSet()

 @Test fun independentVisibleEventsAddExactlyAndKeepWholeRecords() {
  val b = record(1); val l = record(2, win = true); val r = record(3)
  val base = ranking(listOf(b), 100, 20)
  val result = merged(base, ranking(listOf(b, l), 101, 21), ranking(listOf(b, r), 101, 20))
  assertEquals(102, result["total"]!!.jsonPrimitive.int); assertEquals(21, result["won"]!!.jsonPrimitive.int)
  assertEquals(setOf(b, l, r), result["records"]!!.jsonArray.toSet())
  assertEquals(-1, result["latest"]!!.jsonPrimitive.int)
 }

 @Test fun overlappingEventsAndThreeBranchesCountOnlyTheUnion() {
  val b = record(1); val shared = record(2, win = true); val l = record(3); val r = record(4)
  val result = merged(ranking(listOf(b)), ranking(listOf(b, shared, l)), ranking(listOf(b, shared, r)), ranking(listOf(b, shared)))
  assertEquals(4, result["total"]!!.jsonPrimitive.int); assertEquals(1, result["won"]!!.jsonPrimitive.int)
  assertEquals(4, ids(result).size)
 }

 @Test fun mergingRetriesAndAlreadyMergedBranchesIsIdempotent() {
  val b = record(1); val base = ranking(listOf(b)); val left = ranking(listOf(b, record(2))); val right = ranking(listOf(b, record(3)))
  val result = merged(base, left, right)
  assertEquals(result, merged(base, result, right)); assertEquals(result, merged(base, right, result))
  assertEquals(result, merged(base, result, result)); assertEquals(result, merged(result, result, result))
  assertEquals(result, merged(base, right, left))
 }

 @Test fun absentFileBaseRequiresCompleteVisibleHistory() {
  val result = merged(null, ranking(listOf(record(1))), ranking(listOf(record(2, win = true))))
  assertEquals(2, result["total"]!!.jsonPrimitive.int); assertEquals(1, result["won"]!!.jsonPrimitive.int)
  assertRejected(null, ranking(listOf(record(1)), 25), ranking(listOf(record(2))), reason = "完整证明")
 }

 @Test fun missingCountedEventsAndWrongWonDeltaAreRejected() {
  val b = record(1); val base = ranking(listOf(b))
  assertRejected(base, ranking(listOf(b, record(2)), 3), ranking(listOf(b, record(3))), reason = "完整证明")
  assertRejected(ranking(listOf(b), 10, 2), ranking(listOf(b, record(2)), 11, 3), ranking(listOf(b, record(3)), 11, 2), reason = "完整证明")
 }

 @Test fun seededRowsArePreservedButNotCounted() {
  val b = record(1); val custom = record(2, score = 999_999, seed = "same-seed"); val regular = record(3)
  val result = merged(ranking(listOf(b)), ranking(listOf(b, custom)), ranking(listOf(b, regular)))
  assertEquals(2, result["total"]!!.jsonPrimitive.int)
  assertEquals(custom, result["records"]!!.jsonArray.last())
  assertEquals(setOf(b, custom, regular), result["records"]!!.jsonArray.toSet())
 }

 @Test fun differentGameIdsAreNotDeduplicatedByMatchingGameplayOrSeed() {
  val a = record(1, score = 300, seed = "seed"); val b = change(a, "gameID", record(2)["gameID"])
  val baseRecord = record(3)
  val result = merged(ranking(listOf(baseRecord)), ranking(listOf(baseRecord, a)), ranking(listOf(baseRecord, b)))
  assertEquals(3, ids(result).size); assertEquals(1, result["total"]!!.jsonPrimitive.int)
 }

 @Test fun baselineRecordDisappearanceIsNotGuessedAsEvictionOrDeletion() {
  val b = record(1); val base = ranking(listOf(b), 10)
  assertRejected(base, ranking(listOf(record(2)), 11), ranking(listOf(b, record(3)), 11), reason = "基线记录消失")
 }

 @Test fun counterResetIsRejected() {
  val b = record(1); val base = ranking(listOf(b), 10, 5)
  assertRejected(base, ranking(listOf(b), 9, 5), ranking(listOf(b, record(2)), 11, 5), reason = "统计减少")
  assertRejected(base, ranking(listOf(b), 10, 4), ranking(listOf(b, record(2)), 11, 5), reason = "统计减少")
 }

 @Test fun allInputRowsMustFitOfficialCapacity() {
  val rows = (1..10).map { record(it) }; val base = ranking(rows)
  assertRejected(base, ranking(rows + record(11)), ranking(rows + record(12)), reason = "需要 12 条")
  val result = merged(ranking(rows.take(9)), ranking(rows.take(9) + record(10)), ranking(rows.take(9) + record(11)))
  assertEquals(11, ids(result).size)
 }

 @Test fun identicalIdWithDifferentPayloadAndDuplicateIdsAreRejected() {
  val b = record(1); val altered = change(b, "gameData", buildJsonObject { put("hero", "changed") })
  assertRejected(ranking(listOf(b)), ranking(listOf(altered)), ranking(listOf(b, record(2))), reason = "同一 gameID")
  assertRejected(ranking(emptyList()), ranking(listOf(b, b)), ranking(listOf(record(2))), reason = "重复 gameID")
 }

 @Test fun unstableMissingInvalidAndNoncanonicalIdsAreRejected() {
  val b = record(1)
  for (value in listOf<JsonElement?>(null, JsonPrimitive(""), JsonPrimitive("123"), JsonPrimitive("00000000-0000-0000-0000-00000000000A"))) {
   assertRejected(ranking(emptyList()), ranking(listOf(change(b, "gameID", value))), ranking(listOf(record(2))))
  }
 }

 @Test fun zeroCounterFallbackDoesNotInventStatistics() {
  val seeded = record(1, seed = "custom")
  assertRejected(ranking(emptyList()), ranking(listOf(seeded), 0, 0), ranking(listOf(record(2))), reason = "零计数")
  val seededWin = record(1, seed = "custom", win = true)
  assertRejected(ranking(listOf(record(3))), ranking(listOf(record(3), seededWin), 1, 0), ranking(listOf(record(3), record(2))), reason = "零计数")
 }

 @Test fun counterOverflowAndInvalidNumericEncodingsAreRejected() {
  val b = record(1); val base = ranking(listOf(b), Int.MAX_VALUE - 1)
  assertRejected(base, ranking(listOf(b, record(2)), Int.MAX_VALUE), ranking(listOf(b, record(3)), Int.MAX_VALUE), reason = "溢出")
  for (value in listOf(JsonPrimitive(-1), JsonPrimitive(1.0), JsonPrimitive("1"), JsonPrimitive(2147483648L), JsonPrimitive(true))) {
   assertRejected(ranking(emptyList()), change(ranking(listOf(b)), "total", value), ranking(listOf(record(2))))
  }
 }

 @Test fun ordinaryComparatorSortsScoreAndUsesOfficialHashTie() {
  val b = record(1, score = 100); val l = record(2, score = 300); val r = record(3, score = 300)
  val result = merged(ranking(listOf(b)), ranking(listOf(b, l)), ranking(listOf(b, r)))
  assertEquals(listOf(r, l, b), result["records"]!!.jsonArray.toList())
 }

 @Test fun overflowingUuidHashTieFailsRatherThanUsingNontransitiveOrder() {
  val b = record(1, score = 50)
  val ids = (1..1000).map { java.util.UUID.nameUUIDFromBytes("test-$it".toByteArray()).toString() }
  val low = ids.minBy { it.hashCode() }; val high = ids.maxBy { it.hashCode() }
  assertTrue(high.hashCode().toLong() - low.hashCode() > Int.MAX_VALUE)
  val left = change(record(2, score = 100), "gameID", JsonPrimitive(low)); val right = change(record(3, score = 100), "gameID", JsonPrimitive(high))
  assertRejected(ranking(listOf(b)), ranking(listOf(b, left)), ranking(listOf(b, right)), reason = "排序")
 }

 @Test fun latestIsAnIdentityReferenceAfterSortingAndAmbiguityClearsHighlight() {
  val b = record(1, score = 400); val l = record(2, score = 100)
  val base = ranking(listOf(b)); val result = merged(base, ranking(listOf(b, l)), base)
  assertEquals(1, result["latest"]!!.jsonPrimitive.int)
  assertEquals(l, result["records"]!!.jsonArray[result["latest"]!!.jsonPrimitive.int])
  assertEquals(-1, merged(base, ranking(listOf(b, l)), ranking(listOf(b, record(3))))["latest"]!!.jsonPrimitive.int)
 }

 @Test fun negativeOneLatestRoundTripsAndInvalidIndexesFail() {
  val b = record(1); val base = ranking(listOf(b), latest = -1)
  assertEquals(-1, merged(base, base, base)["latest"]!!.jsonPrimitive.int)
  for (value in listOf(JsonPrimitive(-2), JsonPrimitive(1), JsonPrimitive("0"), JsonPrimitive(0.0)))
   assertRejected(base, change(base, "latest", value), ranking(listOf(b, record(2))))
 }

 @Test fun unknownRootFieldsAreRetainedOnlyWhenUnchanged() {
  val b = record(1); val opaque = buildJsonObject { put("future", JsonArray(listOf(JsonPrimitive(7)))) }
  fun extra(value: JsonObject) = change(value, "future_metadata", opaque)
  val result = merged(extra(ranking(listOf(b))), extra(ranking(listOf(b, record(2)))), extra(ranking(listOf(b, record(3)))))
  assertEquals(opaque, result["future_metadata"])
  assertRejected(extra(ranking(listOf(b))), extra(ranking(listOf(b, record(2)))), ranking(listOf(b, record(3))), reason = "未知字段")
 }

 @Test fun unknownRecordFieldsWrongTypesAndDailyInNormalListAreRejected() {
  val base = ranking(emptyList())
  for (invalid in listOf(change(record(1), "future", JsonPrimitive(true)), change(record(1), "win", JsonPrimitive("false")), record(1, daily = true), change(record(1), "__className", JsonPrimitive("evil.Class")), change(record(1), "gameData", JsonArray(emptyList())))) {
   assertRejected(base, ranking(listOf(invalid)), ranking(listOf(record(2))))
  }
 }

 @Test fun uniqueDailyUpdateAndIndependentNormalAdditionArePreserved() {
  val b = record(1); val oldDaily = record(20, daily = true, seed = "daily"); val newDaily = record(21, daily = true, seed = "daily")
  val base = ranking(listOf(b), daily = oldDaily, history = mapOf(100L to 2000))
  val result = merged(base, ranking(listOf(b), daily = newDaily, history = mapOf(100L to 2000, 101L to 2100)), ranking(listOf(b, record(2)), daily = oldDaily, history = mapOf(100L to 2000)))
  assertEquals(newDaily, result["latest_daily"]); assertEquals(listOf(100L, 101L), result["daily_history_dates"]!!.jsonArray.map { it.jsonPrimitive.long })
  assertEquals(2, result["total"]!!.jsonPrimitive.int)
 }

 @Test fun differentDailyDetailsCannotFitSingleOfficialContainer() {
  val b = record(1); val base = ranking(listOf(b))
  assertRejected(base, ranking(listOf(b), daily = record(20, daily = true), history = mapOf(100L to 2000)), ranking(listOf(b), daily = record(21, daily = true), history = mapOf(101L to 2100)), reason = "latest_daily")
 }

 @Test fun dailyScoresMergeByDateAndConflictingScoresAreRejected() {
  val b = record(1); val daily = record(20, daily = true); val base = ranking(listOf(b), daily = daily, history = mapOf(100L to 2000))
  val left = ranking(listOf(b), daily = daily, history = mapOf(100L to 2000, 101L to 900))
  val right = ranking(listOf(b), daily = daily, history = mapOf(100L to 2000, 102L to 700))
  val result = merged(base, left, right)
  assertEquals(listOf(100L, 101L, 102L), result["daily_history_dates"]!!.jsonArray.map { it.jsonPrimitive.long })
  assertEquals(listOf(2000, 900, 700), result["daily_history_scores"]!!.jsonArray.map { it.jsonPrimitive.int })
  assertRejected(base, left, ranking(listOf(b), daily = daily, history = mapOf(100L to 2000, 101L to 901)), reason = "同一日期")
 }

 @Test fun malformedDailyPairsAndDailyDeletionAreRejected() {
  val b = record(1); val daily = record(20, daily = true); val base = ranking(listOf(b), daily = daily, history = mapOf(100L to 2000))
  assertRejected(base, ranking(listOf(b)), base, reason = "每日完整记录被删除")
  assertRejected(base, change(base, "daily_history_scores", JsonArray(emptyList())), base, reason = "一一对应")
  assertRejected(base, change(base, "daily_history_dates", JsonArray(listOf(JsonPrimitive(100L), JsonPrimitive(100L)))).let { change(it, "daily_history_scores", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(1)))) }, base, reason = "重复日期")
  assertRejected(base, change(base, "latest_daily", null), base, reason = "不一致")
 }

 @Test fun originalInputsAreNeverMutatedBySuccessOrFailure() {
  val b = record(1); val base = ranking(listOf(b)); val left = ranking(listOf(b, record(2))); val right = ranking(listOf(b, record(3)))
  val before = listOf(base, left, right).map { it.toString() }
  merged(base, left, right)
  assertEquals(before, listOf(base, left, right).map { it.toString() })
  assertRejected(base, change(left, "total", JsonPrimitive(20)), right)
  assertEquals(before, listOf(base, left, right).map { it.toString() })
 }

 @Test fun explicitFutureVersionsCannotMasqueradeAsSupportedRankings() {
  val base = ranking(emptyList())
  for (version in listOf("v3.3.9", "v3.4.0", "v4.0.2", "unrecognized", "v999999999999.0.0"))
   assertRejected(base, ranking(listOf(change(record(1), "version", JsonPrimitive(version)))), ranking(listOf(record(2))), reason = "版本")
  assertRejected(base, ranking(listOf(change(record(1), "gameData", buildJsonObject { put("game_version", 922) }))), ranking(listOf(record(2))), reason = "896")
  val historic = change(record(1), "version", JsonPrimitive("v1.2.3"))
  assertEquals(2, ids(merged(base, ranking(listOf(historic)), ranking(listOf(record(2))))).size)
 }
}
