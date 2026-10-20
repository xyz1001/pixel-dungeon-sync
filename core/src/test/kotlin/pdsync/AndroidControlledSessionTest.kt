package pdsync

import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import pdsync.android.app.*
import kotlin.test.*

internal fun controlledRun(duration:Double=2.0,seed:Long=5352470251118,custom:String="",daily:Boolean=false,version:Int=896):ByteArray=GlobalBundles.encode(buildJsonObject {
 put("version",version);put("init_ver",version);put("seed",seed);put("custom_seed",custom);put("daily",daily);put("daily_replay",false);put("challenges",0)
 put("duration",duration);put("maxDepth",1);put("enemiesSlain",1);put("score",1);put("foodEaten",0);put("ankhsUsed",0)
 put("generated_levels",buildJsonArray {add(1)})
 put("hero",buildJsonObject {put("class","WARRIOR");put("lvl",1);put("pos",140+duration.toInt())})
})

class AndroidControlledSessionTest {
 private val root=Path.of("/synthetic/root");private val scope=root.resolve("scope")
 private val identity=AndroidGameIdentity("synthetic.game","Synthetic","a".repeat(64))
 private val target=AndroidGameTarget(identity,"synthetic",896,"b".repeat(64))
 private val id="12345678-1234-4234-8234-123456789012"
 private val before=mapOf("game1/game.dat" to controlledRun(),"game1/depth1.dat" to byteArrayOf(2))
 private val files=before.mapValues {hash(it.value)}
 private val runs=mapOf("game1" to RunIdentity(id,runContentHashes(files).getValue("game1")))
 private val after=before+("game1/game.dat" to controlledRun(3.0))
 private var ticks=0L
 private val sessions=AndroidControlledSessions {ticks}
 private fun begin(runMap:Map<String,RunIdentity> = runs)=sessions.begin(root,scope,target,files,runMap,before)
 private fun consume(bytes:Map<String,ByteArray> = after,runMap:Map<String,RunIdentity> = runs,installed:AndroidGameTarget=target)=sessions.consume(root,scope,installed,files,runMap,bytes.mapValues {hash(it.value)},bytes)
 @Test fun observedContinuationKeepsKnownIdentityExactlyOnce() {
  val token=begin();sessions.observe(root,scope,token,true,true)
  assertEquals(id,consume().getValue("game1").runUUID);assertTrue(consume().isEmpty())
 }
 @Test fun mereReturnWithoutForegroundAndChangedBeforeForegroundStayUnknown() {
  val token=begin();sessions.observe(root,scope,token,true,false);assertTrue(consume().isEmpty())
  sessions.observe(root,scope,token,true,true);assertTrue(consume().isEmpty())
 }
 @Test fun unchangedPrelaunchInspectionDoesNotConsumeWindow() {
  val token=begin();assertTrue(consume(before).isEmpty());sessions.observe(root,scope,token,true,true)
  assertEquals(id,consume().getValue("game1").runUUID)
 }
 @Test fun unknownMetadataNeverUsesPreviousIdentity() {
  val unknown=runs.mapValues {it.value.copy(runUUID=null,previousRunUUID=id)}
  val token=begin(unknown);sessions.observe(root,scope,token,true,true);assertTrue(consume(runMap=unknown).isEmpty())
 }
 @Test fun unknownObservationGapExpiryAndRestartInvalidateWindow() {
  var token=begin();sessions.observe(root,scope,token,true,true);sessions.observe(root,scope,token,false,false);assertTrue(consume().isEmpty())
  token=begin();sessions.observe(root,scope,token,true,true);ticks+=16_000_000_000;assertTrue(consume().isEmpty())
  token=begin();ticks+=6L*60*60*1_000_000_000+1;sessions.observe(root,scope,token,true,true);assertTrue(consume().isEmpty())
  token=begin();sessions.observe(root,scope,token,true,true)
  assertTrue(AndroidControlledSessions {ticks}.consume(root,scope,target,files,runs,after.mapValues {hash(it.value)},after).isEmpty())
 }
 @Test fun resumedGameAfterObservedExitCannotExtendTheOriginalWindow() {
  val token=begin();sessions.observe(root,scope,token,true,true);sessions.observe(root,scope,token,true,false)
  sessions.observe(root,scope,token,true,true);assertTrue(consume().isEmpty())
 }
 @Test fun staleTokenCannotCancelNewLaunchAndTargetsCannotShareProof() {
  val old=begin();val current=begin();sessions.cancel(root,old);sessions.observe(root,scope,old,false,false)
  sessions.observe(root,scope,current,true,true);assertEquals(id,consume().getValue("game1").runUUID)
  val token=begin();sessions.observe(root,scope,token,true,true);assertTrue(consume(installed=target.copy(apkSha256="c".repeat(64))).isEmpty())
 }
 @Test fun scopeChangesAndSidecarEditsRejectContinuity() {
  var token=begin();sessions.observe(root,scope.resolve("other"),token,true,true);assertTrue(consume().isEmpty())
  token=begin();sessions.observe(root,scope,token,true,true)
  assertTrue(consume(runMap=runs.mapValues {it.value.copy(runUUID=null,previousRunUUID=id)}).isEmpty())
 }
 @Test fun newSeedResetNewSlotTombstoneAndMissingFloorNeverInherit() {
  val cases=listOf(after+("game1/game.dat" to controlledRun(3.0,seed=99)),after+("game1/game.dat" to controlledRun(0.0)),
   after.mapKeys {it.key.replace("game1/","game2/")},after+("game1/game.dat" to byteArrayOf(1)),after-"game1/depth1.dat")
  cases.forEach {bytes->val token=begin();sessions.observe(root,scope,token,true,true);assertTrue(consume(bytes).isEmpty())}
 }
 @Test fun repeatableSeedsMalformedMissingAndResetCountersAreRejected() {
  assertNull(RunProgressEvidence.read(controlledRun(custom="ABC")));assertNull(RunProgressEvidence.read(controlledRun(daily=true)))
  val original=GlobalBundles.decode(controlledRun(),"game.dat")
  listOf("seed","init_ver","version","duration","hero","generated_levels","daily","daily_replay","custom_seed").forEach {key->assertNull(RunProgressEvidence.read(GlobalBundles.encode(JsonObject(original-key))),key)}
  assertNull(RunProgressEvidence.read("{\"seed\":1,\"seed\":2}".encodeToByteArray()))
  val next=GlobalBundles.decode(controlledRun(3.0),"game.dat")
  for(key in listOf("score","enemiesSlain","maxDepth")) {
   val reset=RunProgressEvidence.read(GlobalBundles.encode(JsonObject(next+(key to JsonPrimitive(0)))))!!
   assertFalse(reset.continues(RunProgressEvidence.read(controlledRun())!!))
  }
 }
 @Test fun structuralSupportDoesNotIntroduceAVersionAllowlistButVersionChangesFail() {
  assertTrue(RunProgressEvidence.read(controlledRun(3.0,version=2000))!!.continues(RunProgressEvidence.read(controlledRun(version=2000))!!))
  assertFalse(RunProgressEvidence.read(controlledRun(3.0,version=897))!!.continues(RunProgressEvidence.read(controlledRun())!!))
 }
 @Test fun blockingTrustedObservationChecksFreshnessAtEntryAndResultOnlyOnce() {
  val token=begin();val proof=sessions.beginObservation(root,scope,token)
  ticks+=16_000_000_000;sessions.observe(root,scope,proof,true,true)
  assertEquals(id,consume().getValue("game1").runUUID)
  val next=begin();val once=sessions.beginObservation(root,scope,next);sessions.observe(root,scope,once,true,true)
  ticks+=16_000_000_000;sessions.observe(root,scope,once,true,true)
  assertTrue(consume().isEmpty()) // Replaying an old result never refreshes a genuine idle gap.
 }
 @Test fun trustedExportCanFinishAfterFifteenSecondsButCannotStartAfterAnIdleGap() {
  val token=begin();sessions.observe(root,scope,token,true,true)
  val proof=sessions.beginExport(root,scope);ticks+=16_000_000_000
  assertEquals(id,sessions.consume(proof,root,scope,target,files,runs,after.mapValues {hash(it.value)},after).getValue("game1").runUUID)
  assertTrue(sessions.consume(proof,root,scope,target,files,runs,after.mapValues {hash(it.value)},after).isEmpty())
  val next=begin();sessions.observe(root,scope,next,true,true);ticks+=16_000_000_000
  assertNull(sessions.beginExport(root,scope));assertNull(sessions.beginObservation(root,scope,next))
 }
 @Test fun slowUnchangedPrelaunchExportClosesOnlyItsIoGapWithoutInventingForeground() {
  val token=begin();val proof=sessions.beginExport(root,scope);ticks+=16_000_000_000
  assertTrue(sessions.consume(proof,root,scope,target,files,runs,files,before).isEmpty())
  val observation=sessions.beginObservation(root,scope,token);assertNotNull(observation)
  ticks+=16_000_000_000;sessions.observe(root,scope,observation,true,true)
  assertEquals(id,consume().getValue("game1").runUUID)
  begin();val unchanged=sessions.beginExport(root,scope);ticks+=16_000_000_000
  assertTrue(sessions.consume(unchanged,root,scope,target,files,runs,files,before).isEmpty())
  assertTrue(consume().isEmpty()) // An unchanged export by itself is never foreground evidence.
 }
 @Test fun failedUnreliableCancelledAndReplacedProofsCannotBlessAContinuation() {
  var token=begin();sessions.observe(root,scope,token,true,true)
  var proof=sessions.beginExport(root,scope);sessions.abandonExport(proof)
  assertTrue(sessions.consume(proof,root,scope,target,files,runs,after.mapValues {hash(it.value)},after).isEmpty())
  token=begin();sessions.observe(root,scope,token,true,true);proof=sessions.beginExport(root,scope)
  sessions.observe(root,scope,token,false,false)
  assertTrue(sessions.consume(proof,root,scope,target,files,runs,after.mapValues {hash(it.value)},after).isEmpty())
  val old=begin();val observation=sessions.beginObservation(root,scope,old);val current=begin()
  sessions.observe(root,scope,observation,true,true);assertTrue(consume(before).isEmpty())
  sessions.observe(root,scope,current,true,true);assertEquals(id,consume().getValue("game1").runUUID)
  token=begin();val cancelled=sessions.beginObservation(root,scope,token);sessions.cancel(root,token)
  sessions.observe(root,scope,cancelled,true,true);assertTrue(consume().isEmpty())
 }
 @Test fun observationLeasesStillRejectUnknownScopeOverlapAndAbsoluteExpiry() {
  var token=begin();var proof=sessions.beginObservation(root,scope,token)
  ticks+=16_000_000_000;sessions.observe(root,scope,proof,false,true);assertTrue(consume().isEmpty())
  token=begin();proof=sessions.beginObservation(root,scope,token)
  sessions.observe(root,scope.resolve("other"),proof,true,true);assertTrue(consume().isEmpty())
  token=begin();proof=sessions.beginObservation(root,scope,token);assertNull(sessions.beginObservation(root,scope,token))
  sessions.observe(root,scope,proof,true,true);assertTrue(consume().isEmpty())
  token=begin();proof=sessions.beginObservation(root,scope,token)
  ticks+=6L*60*60*1_000_000_000+1;sessions.observe(root,scope,proof,true,true);assertTrue(consume().isEmpty())
 }
 @Test fun exportLeasesStillBindOriginalBaselineTargetAndKnownIdentity() {
  val changedRuns=runs.mapValues {it.value.copy(runUUID=null,previousRunUUID=id)}
  for((otherTarget,otherRuns) in listOf(target.copy(apkSha256="c".repeat(64)) to runs,target to changedRuns)) {
   val token=begin();sessions.observe(root,scope,token,true,true);val proof=sessions.beginExport(root,scope);ticks+=16_000_000_000
   assertTrue(sessions.consume(proof,root,scope,otherTarget,files,otherRuns,after.mapValues {hash(it.value)},after).isEmpty())
  }
  val token=begin(changedRuns);sessions.observe(root,scope,token,true,true);val proof=sessions.beginExport(root,scope);ticks+=16_000_000_000
  assertTrue(sessions.consume(proof,root,scope,target,files,changedRuns,after.mapValues {hash(it.value)},after).isEmpty())
 }
 @Test fun exportLeaseDoesNotSurviveRestartNewLaunchOrAbsoluteExpiry() {
  var token=begin();sessions.observe(root,scope,token,true,true);var proof=sessions.beginExport(root,scope)
  assertTrue(AndroidControlledSessions {ticks}.consume(proof,root,scope,target,files,runs,after.mapValues {hash(it.value)},after).isEmpty())
  val current=begin();sessions.abandonExport(proof)
  assertTrue(sessions.consume(proof,root,scope,target,files,runs,after.mapValues {hash(it.value)},after).isEmpty())
  sessions.observe(root,scope,current,true,true);assertEquals(id,consume().getValue("game1").runUUID)
  token=begin();sessions.observe(root,scope,token,true,true);proof=sessions.beginExport(root,scope)
  ticks+=6L*60*60*1_000_000_000+1
  assertTrue(sessions.consume(proof,root,scope,target,files,runs,after.mapValues {hash(it.value)},after).isEmpty())
 }
}
