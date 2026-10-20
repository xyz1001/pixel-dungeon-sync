package pdsync

import pdsync.i18n.CoreMessages
import kotlinx.serialization.json.*

/** Shattered 3.3.8 badges.dat: additive badge names plus official downward replacement closure.
 * Source: 00-Evan/shattered-pixel-dungeon v3.3.8, Badges.store/restore/addReplacedBadges.
 * Prerequisites control UI visibility only; they are deliberately not synthesized.
 */
internal object BadgeMerge {
 fun merge(base: JsonObject?, branches: List<JsonObject>): JsonObject {
  if(branches.isEmpty())globalFail(CoreMessages.BADGES_DAT_HAS_NO_BRANCHES_TO_MERGE.text())
  val old=base?.let { globalStringSet(it["badges"], CoreMessages.BADGES_DAT_BADGES_BASELINE.text()) }.orEmpty()
  val sets=branches.map { globalStringSet(it["badges"], "badges.dat/badges") }
  val result=mergeOpaqueFields(base,branches,setOf("badges"),"badges.dat")
  // Preserve earned baseline progress even after a branch resets its badge set.
  // Keep unknown future/mod names too; no enum loading or name guessing.
  val unlocked=(old+sets.flatten()).toMutableSet()
  replacements.forEach { tiers ->
   val highest=tiers.indexOfLast { it in unlocked }
   if(highest>0)unlocked.addAll(tiers.take(highest))
  }
  result["badges"]=JsonArray(unlocked.sorted().map(::JsonPrimitive))
  return JsonObject(result)
 }

 private val replacements=listOf(
  listOf("MONSTERS_SLAIN_1","MONSTERS_SLAIN_2","MONSTERS_SLAIN_3","MONSTERS_SLAIN_4","MONSTERS_SLAIN_5"),
  listOf("GOLD_COLLECTED_1","GOLD_COLLECTED_2","GOLD_COLLECTED_3","GOLD_COLLECTED_4","GOLD_COLLECTED_5"),
  listOf("ITEM_LEVEL_1","ITEM_LEVEL_2","ITEM_LEVEL_3","ITEM_LEVEL_4","ITEM_LEVEL_5"),
  listOf("LEVEL_REACHED_1","LEVEL_REACHED_2","LEVEL_REACHED_3","LEVEL_REACHED_4","LEVEL_REACHED_5"),
  listOf("STRENGTH_ATTAINED_1","STRENGTH_ATTAINED_2","STRENGTH_ATTAINED_3","STRENGTH_ATTAINED_4","STRENGTH_ATTAINED_5"),
  listOf("FOOD_EATEN_1","FOOD_EATEN_2","FOOD_EATEN_3","FOOD_EATEN_4","FOOD_EATEN_5"),
  listOf("ITEMS_CRAFTED_1","ITEMS_CRAFTED_2","ITEMS_CRAFTED_3","ITEMS_CRAFTED_4","ITEMS_CRAFTED_5"),
  listOf("BOSS_SLAIN_1","BOSS_SLAIN_2","BOSS_SLAIN_3","BOSS_SLAIN_4"),
  listOf("RESEARCHER_1","RESEARCHER_2","RESEARCHER_3","RESEARCHER_4","RESEARCHER_5"),
  listOf("HIGH_SCORE_1","HIGH_SCORE_2","HIGH_SCORE_3","HIGH_SCORE_4","HIGH_SCORE_5"),
  listOf("GAMES_PLAYED_1","GAMES_PLAYED_2","GAMES_PLAYED_3","GAMES_PLAYED_4","GAMES_PLAYED_5"),
  listOf("CHAMPION_1","CHAMPION_2","CHAMPION_3"),
  listOf("DEATH_FROM_FIRE","DEATH_FROM_ALL"),
  listOf("DEATH_FROM_GAS","DEATH_FROM_ALL"),
  listOf("DEATH_FROM_HUNGER","DEATH_FROM_ALL"),
  listOf("DEATH_FROM_POISON","DEATH_FROM_ALL"),
  listOf("DEATH_FROM_FALLING","DEATH_FROM_ALL"),
  listOf("DEATH_FROM_ENEMY_MAGIC","DEATH_FROM_ALL"),
  listOf("DEATH_FROM_FRIENDLY_MAGIC","DEATH_FROM_ALL"),
  listOf("DEATH_FROM_SACRIFICE","DEATH_FROM_ALL"),
  listOf("DEATH_FROM_GRIM_TRAP","DEATH_FROM_ALL"),
  listOf("ALL_WEAPONS_IDENTIFIED","ALL_ITEMS_IDENTIFIED"),
  listOf("ALL_ARMOR_IDENTIFIED","ALL_ITEMS_IDENTIFIED"),
  listOf("ALL_WANDS_IDENTIFIED","ALL_ITEMS_IDENTIFIED"),
  listOf("ALL_RINGS_IDENTIFIED","ALL_ITEMS_IDENTIFIED"),
  listOf("ALL_ARTIFACTS_IDENTIFIED","ALL_ITEMS_IDENTIFIED"),
  listOf("ALL_POTIONS_IDENTIFIED","ALL_ITEMS_IDENTIFIED"),
  listOf("ALL_SCROLLS_IDENTIFIED","ALL_ITEMS_IDENTIFIED")
 )
}
