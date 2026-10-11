package pdsync

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class GlobalRecordMergeTest {
 private fun bytes(value:String)=value.encodeToByteArray()
 @Test fun changesToDifferentFilesPreserveTheirOriginalBytes() {
  val base=mapOf("badges.dat" to bytes("{\"badges\":[]}"),"rankings.dat" to bytes("{\"records\":[],\"total\":0,\"won\":0,\"latest\":-1}"))
  val a=base+("badges.dat" to bytes("{\"badges\":[\"MONSTERS_SLAIN_1\"]}"));val b=base+("rankings.dat" to bytes("{ \"records\":[],\"total\":0,\"won\":0,\"latest\":-1}"))
  val result=GlobalRecordMerge.plan(base,listOf(a,b))
  assertContentEquals(a.getValue("badges.dat"),result.files.getValue("badges.dat"))
  assertContentEquals(b.getValue("rankings.dat"),result.files.getValue("rankings.dat"))
  assertEquals(2,result.decisions.size)
 }
 @Test fun unchangedOpaqueBytesAreNotReencodedOrReinterpreted() {
  val old=mapOf("journal.dat" to byteArrayOf(1,2,3))
  assertContentEquals(old.getValue("journal.dat"),GlobalRecordMerge.plan(old,listOf(old,old)).files.getValue("journal.dat"))
 }
 @Test fun deletionCannotSilentlyDiscardGlobalRecords() {
  val base=mapOf("badges.dat" to bytes("old"))
  assertContains(assertFailsWith<RunMergeException>{GlobalRecordMerge.plan(base,listOf(emptyMap(),base))}.message!!,"删除或重置")
 }
 @Test fun corruptConflictingFilesFailWithDomainContext() {
  for(path in listOf("badges.dat","rankings.dat","journal.dat")) {
   val base=mapOf(path to bytes("{}"))
   val error=assertFailsWith<RunMergeException>{GlobalRecordMerge.plan(base,listOf(mapOf(path to bytes("broken")),mapOf(path to bytes("different"))))}
   assertContains(error.message!!,path);assertContains(error.message!!,"全局记录")
  }
 }
 @Test fun staticBundleCodecPreservesUnknownValuesAndLargeIntegerLexemes() {
  val source=json.parseToJsonElement("""{"opaque":{"huge":9223372036854775807,"decimal":1.2300,"text":"图鉴"},"a":[true,null,42]}""").jsonObject
  assertEquals(source,GlobalBundles.decode(GlobalBundles.encode(source),"test"))
  assertContentEquals(GlobalBundles.encode(source),GlobalBundles.encode(source))
 }
 @Test fun codecRejectsAmbiguousAndUnsupportedInput() {
  for(text in listOf("[]","{\"x\":1,\"x\":2}","{\"a\":{\"x\":1,\"\\u0078\":2}}","{} trailing","[".repeat(65)+"]".repeat(65)))
   assertFailsWith<RunMergeException>{GlobalBundles.decode(bytes(text),"badges.dat")}
  assertFailsWith<RunMergeException>{GlobalBundles.decode(byteArrayOf(0x1f,0x8b.toByte(),0),"badges.dat")}
  assertFailsWith<RunMergeException>{GlobalBundles.decode(byteArrayOf(0xc3.toByte(),0x28),"badges.dat")}
 }
 @Test fun unknownFieldsMustHaveUnambiguousSharedValues() {
  val base=json.parseToJsonElement("""{"known":[],"future":{"n":1}}""").jsonObject
  assertEquals(base["future"],mergeOpaqueFields(base,listOf(base,base),setOf("known"),"test")["future"])
  val changed=JsonObject(base+("future" to JsonPrimitive(2)))
  assertFailsWith<RunMergeException>{mergeOpaqueFields(base,listOf(changed,base),setOf("known"),"test")}
 }
 @Test fun legacyBadgeDefaultsCannotCauseHiddenDiscoveryResetOrLoss() {
  val badge=bytes("""{"badges":["ALL_POTIONS_IDENTIFIED"]}""")
  val row=""""catalog_classes":["com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing"],"catalog_seen":[false],"catalog_uses":[0]"""
  val withFalse=bytes("{$row,\"documents\":{}}")
  val base=mapOf("badges.dat" to badge,"journal.dat" to withFalse)
  val a=base+("journal.dat" to bytes("""{"documents":{"ADVENTURERS_GUIDE":{"Intro":1}}}"""))
  val b=base+("journal.dat" to bytes("{$row,\"documents\":{\"ADVENTURERS_GUIDE\":{\"Examining\":1}}}"))
  assertFailsWith<RunMergeException>{GlobalRecordMerge.plan(base,listOf(a,b))}
  val implicit=mapOf("badges.dat" to badge,"journal.dat" to bytes("{}"))
  assertFailsWith<RunMergeException>{GlobalRecordMerge.plan(implicit,listOf(implicit,b))}
  val safeA=base+("journal.dat" to bytes("{$row,\"documents\":{\"ADVENTURERS_GUIDE\":{\"Intro\":1}}}"))
  val merged=GlobalRecordMerge.plan(base,listOf(safeA,b))
  assertEquals(JsonArray(listOf(JsonPrimitive(false))),GlobalBundles.decode(merged.files.getValue("journal.dat"),"journal.dat")["catalog_seen"])
 }

}
