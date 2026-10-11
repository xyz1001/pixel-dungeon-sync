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

 @Test fun absentFileBaseUsesExactEvidenceOrConservativeObservedHistory() {
  val result = merged(null, ranking(listOf(record(1))), ranking(listOf(record(2, win = true))))
  assertEquals(2, result["total"]!!.jsonPrimitive.int); assertEquals(1, result["won"]!!.jsonPrimitive.int)
  val incomplete = merged(null, ranking(listOf(record(1)), 25), ranking(listOf(record(2))))
  assertEquals(25, incomplete["total"]!!.jsonPrimitive.int)
  assertEquals(2, ids(incomplete).size)
 }

 @Test fun missingCountedEventsAndUnprovedWonDeltaUseObservedMaxima() {
  val b = record(1); val base = ranking(listOf(b))
  assertEquals(3, merged(base, ranking(listOf(b, record(2)), 3), ranking(listOf(b, record(3))))["total"]!!.jsonPrimitive.int)
  val uncertain = merged(ranking(listOf(b), 10, 2), ranking(listOf(b, record(2)), 11, 3), ranking(listOf(b, record(3)), 11, 2))
  assertEquals(11, uncertain["total"]!!.jsonPrimitive.int)
  assertEquals(3, uncertain["won"]!!.jsonPrimitive.int)
 }

 @Test fun seededRowsArePreservedButNotCounted() {
  val b = record(1); val custom = record(2, score = 999_999, seed = "same-seed"); val regular = record(3)
  val result = merged(ranking(listOf(b)), ranking(listOf(b, custom)), ranking(listOf(b, regular)))
  assertEquals(2, result["total"]!!.jsonPrimitive.int)
  assertEquals(custom, result["records"]!!.jsonArray.first())
  assertEquals(setOf(b, custom, regular), result["records"]!!.jsonArray.toSet())
 }

 @Test fun differentGameIdsAreNotDeduplicatedByMatchingGameplayOrSeed() {
  val a = record(1, score = 300, seed = "seed"); val b = change(a, "gameID", record(2)["gameID"])
  val baseRecord = record(3)
  val result = merged(ranking(listOf(baseRecord)), ranking(listOf(baseRecord, a)), ranking(listOf(baseRecord, b)))
  assertEquals(3, ids(result).size); assertEquals(1, result["total"]!!.jsonPrimitive.int)
 }

 @Test fun baselineRecordDisappearanceKeepsTheBaseAndObservedCounts() {
  val b = record(1); val base = ranking(listOf(b), 10)
  val result = merged(base, ranking(listOf(record(2)), 11), ranking(listOf(b, record(3)), 11))
  assertEquals(11, result["total"]!!.jsonPrimitive.int)
  assertEquals(setOf(b, record(2), record(3)), result["records"]!!.jsonArray.toSet())
 }

 @Test fun counterResetsKeepTheLargestBaselineOrBranchObservation() {
  val b = record(1); val base = ranking(listOf(b), 10, 5)
  for (reset in listOf(ranking(listOf(b), 9, 5), ranking(listOf(b), 10, 4))) {
   val result = merged(base, reset, ranking(listOf(b, record(2)), 11, 5))
   assertEquals(11, result["total"]!!.jsonPrimitive.int)
   assertEquals(5, result["won"]!!.jsonPrimitive.int)
  }
  val bothReset = merged(base, ranking(emptyList()), ranking(listOf(record(2))))
  assertEquals(10, bothReset["total"]!!.jsonPrimitive.int)
  assertEquals(5, bothReset["won"]!!.jsonPrimitive.int)
 }

 @Test fun capacityKeepsHighestElevenWhileCountsUseTheCompleteEventUnion() {
  val rows = (1..10).map { record(it, win = it == 1) }; val base = ranking(rows)
  val left = ranking(rows + record(11, win = true)); val right = ranking(rows + record(12))
  val result = merged(base, left, right)
  assertEquals((12 downTo 2).map { record(it)["gameID"] }, result["records"]!!.jsonArray.map { it.jsonObject["gameID"] })
  assertEquals(12, result["total"]!!.jsonPrimitive.int)
  assertEquals(2, result["won"]!!.jsonPrimitive.int) // Includes the winning base row that was trimmed.
  assertEquals(-1, result["latest"]!!.jsonPrimitive.int)
  assertEquals(result, merged(base, right, left)); assertEquals(result, merged(base, left, right))
  assertEquals(result, merged(result, result, result))
  assertEquals(result, merged(base, result, right))
  assertRejected(base, ranking(rows + record(11) + record(12)), right, reason = "输入超过")
 }

 @Test fun seededHighScoresCompeteForCapacityWithoutIncreasingCumulativeCounts() {
  val rows = (1..10).map { record(it) }; val base = ranking(rows)
  val custom = record(11, score = Int.MAX_VALUE, seed = "custom")
  val result = merged(base, ranking(rows + custom), ranking(rows + record(12)))
  assertEquals(custom, result["records"]!!.jsonArray.first())
  assertEquals(11, result["total"]!!.jsonPrimitive.int)
  assertEquals(0, result["won"]!!.jsonPrimitive.int)
  assertEquals(11, ids(result).size)
 }

 @Test fun aUniqueLatestTrimmedOutOfTheTopElevenHasNoDanglingHighlight() {
  val rows = (1..10).map { record(it, score = 1000 + it) }; val base = ranking(rows, latest = -1)
  val lowest = record(11, score = 0); val highest = record(12, score = Int.MAX_VALUE)
  val result = merged(base, ranking(rows + lowest), ranking(rows + highest, latest = -1))
  assertEquals(-1, result["latest"]!!.jsonPrimitive.int)
  assertFalse(ids(result).contains(lowest["gameID"]!!.jsonPrimitive.content))
  assertEquals(highest, result["records"]!!.jsonArray.first())
  assertEquals(12, result["total"]!!.jsonPrimitive.int)
 }

 @Test fun tiesAtTheCutoffUseHashThenFullIdWithStableOrdering() {
  val lowId = "12e8b071-8d85-3fba-83d3-d91ca9f2a5b0"
  val highId = "ddc76671-5131-3180-9670-caaac9a8d580"
  assertEquals(lowId.hashCode(), highId.hashCode())
  val rows = (1..10).map { record(it, score = 200) }; val base = ranking(rows, latest = -1)
  val low = change(record(11, score = 100), "gameID", JsonPrimitive(lowId))
  val high = change(record(12, score = 100), "gameID", JsonPrimitive(highId))
  val left = ranking(rows + low); val right = ranking(rows + high)
  val result = merged(base, left, right)
  assertEquals(low, result["records"]!!.jsonArray.last())
  assertFalse(ids(result).contains(highId))
  assertEquals(result, merged(base, right, left))
 }

 @Test fun identicalIdsChooseOneWholePayloadWhileDuplicateInputIdsStillFail() {
  val b = record(1)
  val altered = change(change(b, "score", JsonPrimitive(900)), "gameData", buildJsonObject { put("hero", "changed"); put("unique", true) })
  val base = ranking(listOf(b)); val left = ranking(listOf(altered)); val right = ranking(listOf(b, record(2)))
  val result = merged(base, left, right)
  assertEquals(altered, result["records"]!!.jsonArray.first())
  assertEquals(2, result["total"]!!.jsonPrimitive.int)
  assertEquals(result, merged(base, right, left))
  assertRejected(ranking(emptyList()), ranking(listOf(b, b)), ranking(listOf(record(2))), reason = "重复 gameID")
 }

 @Test fun unstableMissingInvalidAndNoncanonicalIdsAreRejected() {
  val b = record(1)
  for (value in listOf<JsonElement?>(null, JsonPrimitive(""), JsonPrimitive("123"), JsonPrimitive("00000000-0000-0000-0000-00000000000A"))) {
   assertRejected(ranking(emptyList()), ranking(listOf(change(b, "gameID", value))), ranking(listOf(record(2))))
  }
 }

 @Test fun zeroSentinelsAreNormalizedToTheOfficialLoaderInterpretation() {
  val seeded = record(1, seed = "custom")
  val onlySeeded = merged(null, ranking(listOf(seeded), 0, 0))
  assertEquals(1, onlySeeded["total"]!!.jsonPrimitive.int)
  assertEquals(0, onlySeeded["won"]!!.jsonPrimitive.int)
  assertEquals(onlySeeded, merged(null, onlySeeded, onlySeeded))
  val seededWin = record(1, seed = "custom", win = true)
  val result = merged(ranking(listOf(record(3))), ranking(listOf(record(3), seededWin), 1, 0), ranking(listOf(record(3), record(2))))
  assertEquals(2, result["total"]!!.jsonPrimitive.int)
  assertEquals(1, result["won"]!!.jsonPrimitive.int)
  assertEquals(result, merged(result, result, result))
  // Official won=0 fallback includes seeded winners; make total >= won explicitly.
  val winners = listOf(record(4, seed = "custom", win = true), record(5, seed = "custom", win = true))
  val repaired = merged(null, ranking(winners, 1, 0))
  assertEquals(2, repaired["total"]!!.jsonPrimitive.int)
  assertEquals(2, repaired["won"]!!.jsonPrimitive.int)
 }

 @Test fun counterAdditionOverflowFallsBackButInvalidNumericEncodingsFail() {
  val b = record(1); val base = ranking(listOf(b), Int.MAX_VALUE - 1)
  val result = merged(base, ranking(listOf(b, record(2)), Int.MAX_VALUE), ranking(listOf(b, record(3)), Int.MAX_VALUE))
  assertEquals(Int.MAX_VALUE, result["total"]!!.jsonPrimitive.int)
  assertEquals(0, result["won"]!!.jsonPrimitive.int)
  val winner = record(4, win = true)
  val wins = merged(ranking(listOf(winner), Int.MAX_VALUE - 1, Int.MAX_VALUE - 1),
   ranking(listOf(winner, record(5, win = true)), Int.MAX_VALUE, Int.MAX_VALUE),
   ranking(listOf(winner, record(6, win = true)), Int.MAX_VALUE, Int.MAX_VALUE))
  assertEquals(Int.MAX_VALUE, wins["total"]!!.jsonPrimitive.int)
  assertEquals(Int.MAX_VALUE, wins["won"]!!.jsonPrimitive.int)
  for (value in listOf(JsonPrimitive(-1), JsonPrimitive(1.0), JsonPrimitive("1"), JsonPrimitive(2147483648L), JsonPrimitive(true))) {
   assertRejected(ranking(emptyList()), change(ranking(listOf(b)), "total", value), ranking(listOf(record(2))))
  }
 }

 @Test fun ordinaryComparatorSortsScoreAndUsesOfficialHashTie() {
  val b = record(1, score = 100); val l = record(2, score = 300); val r = record(3, score = 300)
  val result = merged(ranking(listOf(b)), ranking(listOf(b, l)), ranking(listOf(b, r)))
  assertEquals(listOf(r, l, b), result["records"]!!.jsonArray.toList())
 }

 @Test fun overflowingUuidHashTieUsesSafeStableComparison() {
  val b = record(1, score = 50)
  val ids = (1..1000).map { java.util.UUID.nameUUIDFromBytes("test-$it".toByteArray()).toString() }
  val low = ids.minBy { it.hashCode() }; val high = ids.maxBy { it.hashCode() }
  assertTrue(high.hashCode().toLong() - low.hashCode() > Int.MAX_VALUE)
  val left = change(record(2, score = 100), "gameID", JsonPrimitive(low)); val right = change(record(3, score = 100), "gameID", JsonPrimitive(high))
  val base = ranking(listOf(b)); val l = ranking(listOf(b, left)); val r = ranking(listOf(b, right))
  val result = merged(base, l, r)
  assertEquals(listOf(right, left, b), result["records"]!!.jsonArray.toList())
  assertEquals(result, merged(base, r, l))
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

 @Test fun differentDailyDetailsSelectOneCompleteDeterministicRecord() {
  val b = record(1); val base = ranking(listOf(b))
  val first = record(20, daily = true); val second = record(21, daily = true)
  val left = ranking(listOf(b), daily = first, history = mapOf(100L to 2000))
  val right = ranking(listOf(b), daily = second, history = mapOf(101L to 2100))
  val result = merged(base, left, right)
  assertEquals(second, result["latest_daily"])
  assertEquals(listOf(100L, 101L), result["daily_history_dates"]!!.jsonArray.map { it.jsonPrimitive.long })
  assertEquals(result, merged(base, right, left))
 }

 @Test fun dailyScoresKeepEachDatesMaximumIncludingBaselineHistory() {
  val b = record(1); val daily = record(20, daily = true); val base = ranking(listOf(b), daily = daily, history = mapOf(100L to 2000))
  val left = ranking(listOf(b), daily = daily, history = mapOf(100L to 2000, 101L to 900))
  val right = ranking(listOf(b), daily = daily, history = mapOf(100L to 2000, 102L to 700))
  val result = merged(base, left, right)
  assertEquals(listOf(100L, 101L, 102L), result["daily_history_dates"]!!.jsonArray.map { it.jsonPrimitive.long })
  assertEquals(listOf(2000, 900, 700), result["daily_history_scores"]!!.jsonArray.map { it.jsonPrimitive.int })
  val conflict = merged(base, left, ranking(listOf(b), daily = daily, history = mapOf(100L to 1900, 101L to 901)))
  assertEquals(listOf(2000, 901), conflict["daily_history_scores"]!!.jsonArray.map { it.jsonPrimitive.int })
  val deleted = merged(base, ranking(listOf(b)), ranking(listOf(b), daily = daily, history = mapOf(102L to 700)))
  assertEquals(listOf(100L, 102L), deleted["daily_history_dates"]!!.jsonArray.map { it.jsonPrimitive.long })
  assertEquals(listOf(2000, 700), deleted["daily_history_scores"]!!.jsonArray.map { it.jsonPrimitive.int })
 }

 @Test fun dailyDeletionRetainsHistoryButMalformedPairsAreRejected() {
  val b = record(1); val daily = record(20, daily = true); val base = ranking(listOf(b), daily = daily, history = mapOf(100L to 2000))
  assertEquals(base, merged(base, ranking(listOf(b)), base))
  assertRejected(base, change(base, "daily_history_scores", JsonArray(emptyList())), base, reason = "一一对应")
  assertRejected(base, change(base, "daily_history_dates", JsonArray(listOf(JsonPrimitive(100L), JsonPrimitive(100L)))).let { change(it, "daily_history_scores", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(1)))) }, base, reason = "重复日期")
  assertRejected(base, change(base, "latest_daily", null), base, reason = "不一致")
 }

 @Test fun originalInputsAreNeverMutatedBySuccessOrFailure() {
  val b = record(1); val base = ranking(listOf(b)); val left = ranking(listOf(b, record(2))); val right = ranking(listOf(b, record(3)))
  val before = listOf(base, left, right).map { it.toString() }
  merged(base, left, right)
  assertEquals(before, listOf(base, left, right).map { it.toString() })
  assertRejected(base, change(left, "total", JsonPrimitive(-1)), right)
  assertEquals(before, listOf(base, left, right).map { it.toString() })
 }


 @Test fun fallbackUsesTheEntireVisibleEventUnionBeforeTopElevenClipping() {
  val base = ranking(listOf(record(1, win = true)), 1, 1)
  val leftRows = (1..8).map { record(it, win = true) }
  val rightRows = (9..16).map { record(it, win = true) }
  // Both source counters are smaller than their visible evidence and one lost the base.
  val left = ranking(leftRows, 1, 1); val right = ranking(rightRows, 1, 1)
  val result = merged(base, left, right)
  assertEquals(16, result["total"]!!.jsonPrimitive.int)
  assertEquals(16, result["won"]!!.jsonPrimitive.int)
  assertEquals(11, ids(result).size)
  assertEquals(result, merged(base, right, left))
  assertEquals(result, merged(base, result, right))
  assertEquals(result, merged(result, result, result))
 }

 @Test fun fallbackDoesNotAddHiddenLifetimeHistoriesOrLoseBaseCounts() {
  val b = record(1); val base = ranking(listOf(b), 1000, 80)
  val left = ranking(listOf(record(2)), 1005, 81)
  val right = ranking(listOf(record(3)), 1005, 81)
  val result = merged(base, left, right)
  assertEquals(1005, result["total"]!!.jsonPrimitive.int)
  assertEquals(81, result["won"]!!.jsonPrimitive.int)
  assertEquals(result, merged(base, result, right))
  assertEquals(result, merged(base, right, result))
  val reset = merged(base, ranking(emptyList()), ranking(listOf(record(4)), 1))
  assertEquals(1000, reset["total"]!!.jsonPrimitive.int)
  assertEquals(80, reset["won"]!!.jsonPrimitive.int)
 }

 @Test fun sameIdCountAttributeConflictsChooseWholeRecordsAndDisableExactAddition() {
  val b = record(1); val base = ranking(listOf(b), 10, 2)
  val regular = record(2, win = true)
  val seeded = change(change(regular, "custom_seed", JsonPrimitive("custom")), "score", JsonPrimitive(9999))
  val left = ranking(listOf(b, regular), 11, 3)
  val right = ranking(listOf(b, seeded, record(3)), 11, 2)
  val result = merged(base, left, right)
  assertEquals(seeded, result["records"]!!.jsonArray.first())
  assertEquals(11, result["total"]!!.jsonPrimitive.int) // Exact addition would incorrectly say 12.
  assertEquals(3, result["won"]!!.jsonPrimitive.int)
  assertEquals(result, merged(base, right, left))
  val changedWin = change(change(regular, "win", JsonPrimitive(false)), "score", JsonPrimitive(9999))
  val wonConflict = merged(base, left, ranking(listOf(b, changedWin, record(3)), 12, 2))
  assertEquals(changedWin, wonConflict["records"]!!.jsonArray.first())
  assertEquals(12, wonConflict["total"]!!.jsonPrimitive.int)
  assertEquals(3, wonConflict["won"]!!.jsonPrimitive.int)
 }

 @Test fun wholeRecordPreferenceUsesDetailsThenDateThenScoreAndCanonicalContent() {
  val old = change(record(1, score = 100), "date", JsonPrimitive("2026-09-30"))
  val newer = change(record(1, score = 50), "date", JsonPrimitive("2026-10-01"))
  val incomplete = change(change(record(1, score = 999), "date", JsonPrimitive("2026-10-02")), "gameData", null)
  val base = ranking(listOf(old))
  val left = ranking(listOf(newer)); val right = ranking(listOf(incomplete))
  assertEquals(newer, merged(base, left, right)["records"]!!.jsonArray.single())
  val a = change(newer, "gameData", buildJsonObject { put("hero", buildJsonObject { put("z", 1); put("a", 2) }) })
  val z = change(newer, "gameData", buildJsonObject { put("hero", buildJsonObject { put("a", 3); put("z", 0) }) })
  val zReordered = change(z, "gameData", buildJsonObject { put("hero", buildJsonObject { put("z", 0); put("a", 3) }) })
  val result = merged(base, ranking(listOf(a)), ranking(listOf(z)))
  assertEquals(z, result["records"]!!.jsonArray.single())
  assertEquals(result, merged(base, ranking(listOf(zReordered)), ranking(listOf(a))))
  assertEquals(result, merged(base, result, ranking(listOf(a))))
  assertTrue(result["records"]!!.jsonArray.single() in listOf(old, a, z))
 }

 @Test fun dailyDateWinsOverScoreDetailsHistoryKeysAndBranchOrder() {
  val old = change(record(20, daily = true, score = Int.MAX_VALUE), "date", JsonPrimitive("2026-09-30"))
  val newer = change(record(21, daily = true, score = 1), "date", JsonPrimitive("2026-10-01"))
  val newestWithoutDetails = change(change(record(22, daily = true, score = 0), "date", JsonPrimitive("2026-10-02")), "gameData", null)
  val base = ranking(emptyList(), daily = old, history = mapOf(999L to Int.MAX_VALUE))
  val left = ranking(emptyList(), daily = newer, history = mapOf(100L to 1))
  val right = ranking(emptyList(), daily = newestWithoutDetails, history = mapOf(101L to 0))
  val result = merged(base, left, right)
  assertEquals(newestWithoutDetails, result["latest_daily"])
  assertEquals(listOf(100L, 101L, 999L), result["daily_history_dates"]!!.jsonArray.map { it.jsonPrimitive.long })
  assertEquals(result, merged(base, right, left))
  assertEquals(result, merged(base, result, left))
  assertEquals(0, result["total"]!!.jsonPrimitive.int)
 }

 @Test fun sameDailyIdPrefersActualNewerDateBeforeDetailsCompleteness() {
  val olderComplete = change(record(20, daily = true, score = 9999), "date", JsonPrimitive("2026-09-30"))
  val newerWithoutDetails = change(change(olderComplete, "date", JsonPrimitive("2026-10-01")), "gameData", null)
  val base = ranking(emptyList(), daily = olderComplete, history = mapOf(100L to 9999))
  val newer = ranking(emptyList(), daily = newerWithoutDetails, history = mapOf(101L to 9999))
  val result = merged(base, base, newer)
  assertEquals(newerWithoutDetails, result["latest_daily"])
  assertEquals(listOf(100L, 101L), result["daily_history_dates"]!!.jsonArray.map { it.jsonPrimitive.long })
  assertEquals(result, merged(base, newer, base))
  assertEquals(result, merged(base, result, base))
  assertEquals(result, merged(result, result, result))
 }

 @Test fun dailyHistoryUnionHonorsTheSameCapacityAsItsReader() {
  val capacity = 365_000
  val daily = record(20, daily = true)
  val score = JsonPrimitive(1)
  val largest = change(change(ranking(emptyList(), daily = daily, history = mapOf(0L to 1)),
   "daily_history_dates", JsonArray((0 until capacity).map { JsonPrimitive(it.toLong()) })),
   "daily_history_scores", JsonArray(List(capacity) { score }))
  assertEquals(capacity, merged(null, largest)["daily_history_dates"]!!.jsonArray.size)
  val extra = ranking(emptyList(), daily = daily, history = mapOf(capacity.toLong() to 1))
  assertRejected(null, largest, extra, reason = "每日历史合并超过")
 }

 @Test fun dailySameDateAndScoreTiesKeepOneCompleteObjectDeterministically() {
  val a = record(20, daily = true, score = 900)
  val b = change(record(21, daily = true, score = 900), "gameData", buildJsonObject { put("hero", "second") })
  val left = ranking(emptyList(), daily = a, history = mapOf(100L to 900))
  val right = ranking(emptyList(), daily = b, history = mapOf(100L to 800))
  val result = merged(null, left, right)
  assertTrue(result["latest_daily"] in listOf(a, b))
  assertEquals(listOf(900), result["daily_history_scores"]!!.jsonArray.map { it.jsonPrimitive.int })
  assertEquals(result, merged(null, right, left))
  assertEquals(result, merged(null, result, left))
  assertEquals(result, merged(result, result, result))
 }

 @Test fun dailyPayloadConflictsDoNotWeakenIndependentNormalCounterEvidence() {
  val b = record(1); val daily = record(20, daily = true, seed = "daily")
  val changedDaily = change(daily, "win", JsonPrimitive(true))
  val history = mapOf(100L to 2000)
  val base = ranking(listOf(b), 10, 0, daily = daily, history = history)
  val left = ranking(listOf(b, record(2)), 11, 0, daily = changedDaily, history = history)
  val right = ranking(listOf(b, record(3)), 11, 0, daily = daily, history = history)
  val result = merged(base, left, right)
  assertEquals(12, result["total"]!!.jsonPrimitive.int)
  assertEquals(0, result["won"]!!.jsonPrimitive.int)
  assertTrue(result["latest_daily"] in listOf(daily, changedDaily))
  assertEquals(result, merged(base, right, left))
 }

 @Test fun legacyMissingDatesRemainSupportedButInvalidActualDatesFail() {
  val legacy = change(record(1), "date", null)
  val emptyDate = change(record(2), "date", JsonPrimitive(""))
  val nullDate = change(record(3), "date", JsonNull)
  assertEquals(3, ids(merged(null, ranking(listOf(legacy, emptyDate, nullDate)))).size)
  for (invalid in listOf("2026-02-30", "2026-2-01", "2026-13-01", "tomorrow"))
   assertRejected(null, ranking(listOf(change(record(1), "date", JsonPrimitive(invalid)))), reason = "有效日期")
  val dated = record(21, daily = true, score = 1)
  val undated = change(record(20, daily = true, score = Int.MAX_VALUE), "date", null)
  val result = merged(null, ranking(emptyList(), daily = undated, history = mapOf(1L to 20)), ranking(emptyList(), daily = dated, history = mapOf(2L to 21)))
  assertEquals(dated, result["latest_daily"])
 }

 @Test fun contradictoryExplicitCountersAndCrossContainerIdentitiesStillFail() {
  assertRejected(null, ranking(listOf(record(1)), 1, 2), reason = "胜局数超过")
  assertRejected(null, ranking(emptyList(), 0, 1), reason = "胜局数超过")
  val ordinary = record(1)
  val daily = change(ordinary, "daily", JsonPrimitive(true))
  assertRejected(null, ranking(listOf(ordinary)), ranking(emptyList(), daily = daily, history = mapOf(1L to 100)), reason = "归属矛盾")
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
