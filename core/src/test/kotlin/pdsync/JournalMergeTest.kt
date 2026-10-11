package pdsync

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class JournalMergeTest {
 private fun obj(text:String)=Json.parseToJsonElement(text).jsonObject
 private val empty=obj("{}")
 private val rat="com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat"
 private val snake="com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Snake"
 private val food="com.shatteredpixel.shatteredpixeldungeon.items.food.Food"
 private fun rows(prefix:String="bestiary",vararg rows:Triple<String,Boolean,Int>):JsonObject=buildJsonObject {
  put("${prefix}_classes",JsonArray(rows.map { JsonPrimitive(it.first) }))
  put("${prefix}_seen",JsonArray(rows.map { JsonPrimitive(it.second) }))
  put(if(prefix=="bestiary")"bestiary_encounters" else "catalog_uses",JsonArray(rows.map { JsonPrimitive(it.third) }))
 }
 private fun count(bundle:JsonObject,cls:String,prefix:String="bestiary"):Int {
  val index=bundle.getValue("${prefix}_classes").jsonArray.indexOf(JsonPrimitive(cls))
  return bundle.getValue(if(prefix=="bestiary")"bestiary_encounters" else "catalog_uses").jsonArray[index].jsonPrimitive.int
 }
 private fun seen(bundle:JsonObject,cls:String,prefix:String="bestiary"):Boolean {
  val index=bundle.getValue("${prefix}_classes").jsonArray.indexOf(JsonPrimitive(cls))
  return bundle.getValue("${prefix}_seen").jsonArray[index].jsonPrimitive.boolean
 }

 @Test fun disjointCounterIncreasesAndIndependentSeenFlagsMergeByClassIdentity() {
  val base=rows("bestiary",Triple(rat,false,5),Triple(snake,true,3))
  val left=rows("bestiary",Triple(snake,true,3),Triple(rat,false,7))
  val right=rows("bestiary",Triple(rat,true,5),Triple(snake,true,9))
  val result=JournalMerge.merge(base,listOf(left,right))
  assertEquals(7,count(result,rat));assertEquals(9,count(result,snake));assertTrue(seen(result,rat))
  assertEquals(result,JournalMerge.merge(base,listOf(right,left)))
  assertEquals(result,JournalMerge.merge(result,listOf(result,result)))
 }

 @Test fun matchingConcurrentCounterIncrementsAreStillAmbiguous() {
  val base=rows("bestiary",Triple(rat,true,5))
  val same=rows("bestiary",Triple(rat,true,6))
  val different=rows("bestiary",Triple(rat,true,7))
  for(branches in listOf(listOf(same,same),listOf(same,different))) {
   val error=assertFailsWith<RunMergeException> { JournalMerge.merge(base,branches) }
   assertContains(error.message!!,"无法证明互不重叠")
  }
  assertFailsWith<RunMergeException> { JournalMerge.merge(null,listOf(same,same)) }
 }

 @Test fun distinctCatalogAndBestiaryCountersKeepExactSingleBranchValues() {
  val left=rows("catalog",Triple(food,true,Int.MAX_VALUE))
  val right=rows("bestiary",Triple(rat,false,1))
  val result=JournalMerge.merge(empty,listOf(left,right))
  assertEquals(Int.MAX_VALUE,count(result,food,"catalog"));assertEquals(1,count(result,rat))
  assertFalse(seen(result,rat)) // encounters do not automatically imply a seen flag
  val next=JsonObject(result+rows("catalog",Triple(food,true,Int.MAX_VALUE)))
  assertEquals(result,JournalMerge.merge(result,listOf(next,result)))
 }

 @Test fun counterAndSeenRegressionsIncludingMissingRowsFail() {
  val old=rows("bestiary",Triple(rat,true,5))
  for(branch in listOf(rows("bestiary",Triple(rat,true,4)),rows("bestiary",Triple(rat,false,5)),empty))
   assertFailsWith<RunMergeException> { JournalMerge.merge(old,listOf(branch,old)) }
 }

 @Test fun documentLevelsMergeWithoutLosingDefaultDungeonIntroduction() {
  val base=obj("""{"documents":{"ADVENTURERS_GUIDE":{"Intro":1}}}""")
  val left=obj("""{"documents":{"ADVENTURERS_GUIDE":{"Intro":2},"SEWERS_GUARD":{"crabs":1}}}""")
  val right=obj("""{"documents":{"ADVENTURERS_GUIDE":{"Intro":1},"SEWERS_GUARD":{"crabs":2},"HALLS_KING":{"Rejection":1}}}""")
  val docs=JournalMerge.merge(base,listOf(left,right))["documents"]!!.jsonObject
  assertEquals(2,docs["ADVENTURERS_GUIDE"]!!.jsonObject["Intro"]!!.jsonPrimitive.int)
  assertEquals(2,docs["SEWERS_GUARD"]!!.jsonObject["crabs"]!!.jsonPrimitive.int)
  assertEquals(1,docs["HALLS_KING"]!!.jsonObject["Rejection"]!!.jsonPrimitive.int)
  assertEquals(2,docs["INTROS"]!!.jsonObject["Dungeon"]!!.jsonPrimitive.int)
  assertEquals(setOf("Dungeon"),docs["INTROS"]!!.jsonObject.keys)
 }

 @Test fun allKnownDocumentNamesAcceptOfficialCaseAndSpelling() {
  val input=obj("""{"documents":{"ADVENTURERS_GUIDE":{"Dieing":1,"Levelling":2},"ALCHEMY_GUIDE":{"Energy_Food":1},"INTROS":{"Sewers":1},"SEWERS_GUARD":{"new_position":2},"PRISON_WARDEN":{"no_support":1},"CAVES_EXPLORER":{"troll":2},"CITY_WARLOCK":{"seen_it":1},"HALLS_KING":{"Rejection":2,"attrition":1}}}""")
  val merged=JournalMerge.merge(empty,listOf(input,empty))["documents"]!!.jsonObject
  input["documents"]!!.jsonObject.forEach { (name,pages) ->
   pages.jsonObject.forEach { (page,value) -> assertEquals(value,merged[name]!!.jsonObject[page]) }
  }
 }

 @Test fun deletesUnreadTransitionsAndUnknownDocumentStatesFail() {
  val old=obj("""{"documents":{"ADVENTURERS_GUIDE":{"Intro":2}}}""")
  for(branch in listOf(empty,obj("""{"documents":{"ADVENTURERS_GUIDE":{"Intro":1}}}"""),obj("""{"documents":{"ADVENTURERS_GUIDE":{"Intro":0}}}""")))
   assertFailsWith<RunMergeException> { JournalMerge.merge(old,listOf(old,branch)) }
  for(value in listOf("3","-1","1.0","\"2\"","null"))
   assertFailsWith<RunMergeException> { JournalMerge.merge(empty,listOf(obj("""{"documents":{"ADVENTURERS_GUIDE":{"Intro":$value}}}"""))) }
 }

 @Test fun preservesStableOpaqueTopLevelDocumentsAndPagesWithoutInterpretingTheirShape() {
  val unknown=obj("""{"future":[7],"documents":{"UNKNOWN_DOC":{"x":"opaque"},"ADVENTURERS_GUIDE":{"Unknown_Page":{"v":4}}}}""")
  val left=obj("""{"future":[7],"documents":{"UNKNOWN_DOC":{"x":"opaque"},"ADVENTURERS_GUIDE":{"Unknown_Page":{"v":4},"Intro":1}}}""")
  val result=JournalMerge.merge(unknown,listOf(left,unknown))
  assertEquals(unknown["future"],result["future"])
  assertEquals(unknown["documents"]!!.jsonObject["UNKNOWN_DOC"],result["documents"]!!.jsonObject["UNKNOWN_DOC"])
  assertEquals(obj("""{"v":4}"""),result["documents"]!!.jsonObject["ADVENTURERS_GUIDE"]!!.jsonObject["Unknown_Page"])
  assertFailsWith<RunMergeException> { JournalMerge.merge(unknown,listOf(left,empty)) }
  // A baseline lacking a document/page is not permission to add unknown schema.
  assertFailsWith<RunMergeException> { JournalMerge.merge(empty,listOf(unknown,unknown)) }
  assertFailsWith<RunMergeException> { JournalMerge.merge(obj("""{"documents":{"ADVENTURERS_GUIDE":{}}}"""),listOf(left,left)) }
 }

 @Test fun validatesParallelArraysAndRejectsLegacyPartialAndMalformedBundles() {
  val invalid=listOf(
   """{"catalog_items":[]}""",
   """{"catalog_classes":[]}""",
   """{"bestiary_classes":["$rat"],"bestiary_seen":[],"bestiary_encounters":[]}""",
   """{"bestiary_classes":["$rat","$rat"],"bestiary_seen":[true,true],"bestiary_encounters":[1,2]}""",
   """{"catalog_classes":["class $food"],"catalog_seen":[true],"catalog_uses":[1]}""",
   """{"catalog_classes":["com.shatteredpixel.shatteredpixeldungeon.items.keys.SkeletonKey"],"catalog_seen":[true],"catalog_uses":[1]}""",
   """{"catalog_classes":["$food"],"catalog_seen":["true"],"catalog_uses":[1]}""",
   """{"catalog_classes":["$food"],"catalog_seen":[true],"catalog_uses":[2147483648]}""",
   """{"catalog_classes":["$food"],"catalog_seen":[true],"catalog_uses":[-1]}""",
   """{"catalog_classes":["$food"],"catalog_seen":[true],"catalog_uses":[1.0]}""",
   """{"catalog_classes":["$food"],"catalog_seen":[true],"catalog_uses":["1"]}""",
   """{"documents":[]}""",
   """{"documents":{"INTROS":null}}"""
  )
  invalid.forEach { text -> assertFailsWith<RunMergeException>(text) { JournalMerge.merge(null,listOf(obj(text))) } }
 }

 @Test fun threeBranchesCanAddDifferentCountersAndStates() {
  val a=rows("bestiary",Triple(rat,true,1))
  val b=rows("bestiary",Triple(snake,true,2))
  val c=rows("catalog",Triple(food,true,3))
  val result=JournalMerge.merge(null,listOf(a,b,c))
  assertEquals(1,count(result,rat));assertEquals(2,count(result,snake));assertEquals(3,count(result,food,"catalog"))
 }
 @Test fun explicitFalseZeroRowsSurviveSoLegacyBadgeMigrationCannotOverrideThem() {
  val base=json.parseToJsonElement("""{"catalog_classes":["com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing"],"catalog_seen":[false],"catalog_uses":[0],"documents":{}}""").jsonObject
  val left=JsonObject(base+("documents" to json.parseToJsonElement("""{"ADVENTURERS_GUIDE":{"Intro":1}}""")))
  val right=JsonObject(base+("documents" to json.parseToJsonElement("""{"ADVENTURERS_GUIDE":{"Examining":1}}""")))
  val merged=JournalMerge.merge(base,listOf(left,right))
  assertEquals(base["catalog_classes"],merged["catalog_classes"])
  assertEquals(base["catalog_seen"],merged["catalog_seen"])
  assertEquals(base["catalog_uses"],merged["catalog_uses"])
 }

 @Test fun explicitUnreadDungeonPageDoesNotBecomeReadAfterMerge() {
  val base=json.parseToJsonElement("""{"documents":{"INTROS":{"Dungeon":0}}}""").jsonObject
  val left=json.parseToJsonElement("""{"documents":{"INTROS":{"Dungeon":0,"Sewers":1}}}""").jsonObject
  val right=json.parseToJsonElement("""{"documents":{"INTROS":{"Dungeon":0,"Prison":1}}}""").jsonObject
  val merged=JournalMerge.merge(base,listOf(left,right))
  assertEquals(JsonPrimitive(0),merged["documents"]!!.jsonObject["INTROS"]!!.jsonObject["Dungeon"])
 }

}
