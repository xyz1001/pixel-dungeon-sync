package pdsync

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class BadgeMergeTest {
 private fun obj(text:String)=Json.parseToJsonElement(text).jsonObject
 private fun bundle(vararg names:String)=buildJsonObject { put("badges",JsonArray(names.map(::JsonPrimitive))) }
 private fun names(result:JsonObject)=result.getValue("badges").jsonArray.map { it.jsonPrimitive.content }.toSet()

 @Test fun unionsAllBadgesIncludingUnlocksHiddenAndUnknownNames() {
  val base=bundle("MASTERY_WARRIOR")
  val merged=BadgeMerge.merge(base,listOf(bundle("MASTERY_WARRIOR","UNLOCK_CLERIC"),bundle("MASTERY_WARRIOR","FUTURE_BADGE")))
  assertEquals(setOf("MASTERY_WARRIOR","UNLOCK_CLERIC","FUTURE_BADGE"),names(merged))
  assertEquals(merged,BadgeMerge.merge(merged,listOf(merged,merged)))
 }

 @Test fun completesAllOfficialTierAndSummaryRelationshipsButNotUiPrerequisites() {
  val fiveTier=listOf("MONSTERS_SLAIN","GOLD_COLLECTED","ITEM_LEVEL","LEVEL_REACHED","STRENGTH_ATTAINED","FOOD_EATEN","ITEMS_CRAFTED","RESEARCHER","HIGH_SCORE","GAMES_PLAYED")
  val input=fiveTier.map { "${it}_5" }+listOf("BOSS_SLAIN_4","CHAMPION_3","DEATH_FROM_ALL","ALL_ITEMS_IDENTIFIED","PACIFIST_ASCENT")
  val merged=names(BadgeMerge.merge(null,listOf(bundle(*input.toTypedArray()))))
  fiveTier.forEach { prefix -> (1..5).forEach { assertContains(merged,"${prefix}_$it") } }
  (1..4).forEach { assertContains(merged,"BOSS_SLAIN_$it") }
  (1..3).forEach { assertContains(merged,"CHAMPION_$it") }
  listOf("FIRE","GAS","HUNGER","POISON","FALLING","ENEMY_MAGIC","FRIENDLY_MAGIC","SACRIFICE","GRIM_TRAP").forEach { assertContains(merged,"DEATH_FROM_$it") }
  listOf("WEAPONS","ARMOR","WANDS","RINGS","ARTIFACTS","POTIONS","SCROLLS").forEach { assertContains(merged,"ALL_${it}_IDENTIFIED") }
  assertFalse("HAPPY_END" in merged)
  assertFalse("VICTORY" in merged)
 }

 @Test fun resetsRetainEarnedBadgesWhileMalformedNamesStillFail() {
  assertEquals(setOf("UNLOCK_MAGE","UNLOCK_ROGUE"),names(BadgeMerge.merge(bundle("UNLOCK_MAGE"),listOf(bundle(),bundle("UNLOCK_ROGUE")))))
  assertEquals(setOf("UNLOCK_MAGE"),names(BadgeMerge.merge(bundle("UNLOCK_MAGE"),listOf(bundle(),bundle()))))
  for(text in listOf("{}","{\"badges\":null}","{\"badges\":[1]}","{\"badges\":[\"A\",\"A\"]}","{\"badges\":[\"\"]}"))
   assertFailsWith<RunMergeException> { BadgeMerge.merge(null,listOf(obj(text))) }
 }

 @Test fun retainsOpaqueFieldsOnlyWhenEveryInputAgrees() {
  val base=obj("""{"badges":[],"future":{"v":2}}""")
  val left=obj("""{"badges":["UNLOCK_MAGE"],"future":{"v":2}}""")
  val right=obj("""{"badges":["UNLOCK_ROGUE"],"future":{"v":2}}""")
  assertEquals(base["future"],BadgeMerge.merge(base,listOf(left,right))["future"])
  assertFailsWith<RunMergeException> { BadgeMerge.merge(base,listOf(left,bundle("UNLOCK_ROGUE"))) }
  assertFailsWith<RunMergeException> { BadgeMerge.merge(bundle(),listOf(left,right)) }
 }

 @Test fun resultsAreDeterministicUnderBranchAndArrayReordering() {
  val a=bundle("UNLOCK_ROGUE","UNLOCK_MAGE");val b=bundle("VICTORY","UNLOCK_MAGE")
  assertEquals(BadgeMerge.merge(bundle(),listOf(a,b)),BadgeMerge.merge(bundle(),listOf(b,a)))
 }
}
