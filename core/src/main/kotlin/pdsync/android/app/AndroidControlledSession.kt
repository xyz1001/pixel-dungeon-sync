package pdsync.android.app

import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import pdsync.*

/** Process-local, single-use evidence, shared by the activity and service facades. Never portable. */
internal class AndroidControlledSessions(private val now: () -> Long = System::nanoTime) {
 private data class Window(val scope: Path, val token: String, val target: AndroidGameTarget,
  val files: Map<String,String>, val runs: Map<String,RunIdentity>, val evidence: Map<String,RunProgressEvidence>,
  val started: Long, var observed: Long, var foregroundSeen: Boolean = false, var leftForeground: Boolean = false)
 private val windows=mutableMapOf<Path,Window>()
 @Synchronized fun begin(root:Path,scope:Path,target:AndroidGameTarget,files:Map<String,String>,runs:Map<String,RunIdentity>,bytes:Map<String,ByteArray>):String {
  val token=UUID.randomUUID().toString();val time=now()
  windows[root]=Window(scope,token,target,files.toMap(),runs.toMap(),runs.filterValues {it.runUUID!=null}.mapNotNull { (slot,_) ->
   RunProgressEvidence.read(bytes["$slot/game.dat"])?.let {slot to it}
  }.toMap(),time,time)
  return token
 }
 @Synchronized fun cancel(root:Path,token:String?=null) {if(token==null || windows[root]?.token==token)windows.remove(root)}
 @Synchronized fun observe(root:Path,scope:Path,token:String,reliable:Boolean,foreground:Boolean) {
  val window=windows[root] ?: return
  if(window.token!=token)return // A late callback from an old service cannot cancel a newer launch.
  val time=now()
  if(window.scope!=scope || !reliable || !withinWindow(window,time)){windows.remove(root);return}
  if(foreground && window.leftForeground){windows.remove(root);return}
  window.observed=time
  if(foreground)window.foregroundSeen=true else if(window.foregroundSeen)window.leftForeground=true
 }
 /** A verified stable helper export is the exit boundary, even if the activity beats the service. */
 @Synchronized fun consume(root:Path,scope:Path,target:AndroidGameTarget,before:Map<String,String>,runs:Map<String,RunIdentity>,after:Map<String,String>,bytes:Map<String,ByteArray>):Map<String,RunIdentity> {
  val window=windows[root] ?: return emptyMap()
  if(window.scope!=scope || window.target!=target || !withinWindow(window,now()) || window.files!=before || window.runs!=runs) {
   windows.remove(root);return emptyMap()
  }
  if(!window.foregroundSeen) {if(before!=after)windows.remove(root);return emptyMap()}
  windows.remove(root) // Never reuse a window for a later game launch or a retry's newer bytes.
  val hashes=runContentHashes(after)
  return window.evidence.mapNotNull { (slot,previous) ->
   val digest=hashes[slot] ?: return@mapNotNull null
   val current=RunProgressEvidence.read(bytes["$slot/game.dat"]) ?: return@mapNotNull null
   val retainedFiles=before.keys.filter {it.startsWith("$slot/")}.all {it in after}
   if(retainedFiles && current.continues(previous))slot to window.runs.getValue(slot).copy(contentSha256=digest) else null
  }.toMap()
 }
 private fun withinWindow(window:Window,time:Long)=time>=window.started && time-window.started<=6L*60*60*1_000_000_000 && time>=window.observed && time-window.observed<=15L*1_000_000_000
}

/** Structural continuity checks supplement the observed window; seed alone is never sufficient.
 * Repeatable custom/daily seeds and reset/unsupported bundles deliberately remain unconfirmed.
 * This is bounded evidence, not a native unique ID; indistinguishable forged state is not proven. */
internal data class RunProgressEvidence(val fixed:List<JsonElement>,val duration:Double,val counters:List<Long>,val generated:Set<Int>) {
 fun continues(previous:RunProgressEvidence)=fixed==previous.fixed && duration>previous.duration &&
  counters.zip(previous.counters).all {(current,old)->current>=old} && generated.containsAll(previous.generated)
 companion object {
  fun read(source:ByteArray?):RunProgressEvidence?=runCatching {
   val bundle=GlobalBundles.decode(requireNotNull(source),"game.dat")
   fun primitive(objectValue:JsonObject,key:String)=requireNotNull(objectValue[key] as? JsonPrimitive)
   fun number(objectValue:JsonObject,key:String)=primitive(objectValue,key).also {require(!it.isString)}.longOrNull.let {requireNotNull(it).also {v->require(v>=0)}}
   fun flag(key:String)=primitive(bundle,key).also {require(!it.isString)}.booleanOrNull.let(::requireNotNull)
   require(primitive(bundle,"custom_seed").let {it.isString && it.content.isEmpty()})
   require(!flag("daily") && !flag("daily_replay"))
   val hero=requireNotNull(bundle["hero"] as? JsonObject)
   val heroClass=primitive(hero,"class").also {require(it.isString && it.content.isNotBlank())}
   val fixed=listOf("seed","init_ver","version","challenges").map {key->number(bundle,key);bundle.getValue(key)}+heroClass
   require(number(bundle,"init_ver")>0 && number(bundle,"version")>0)
   val duration=primitive(bundle,"duration").also {require(!it.isString)}.doubleOrNull.let(::requireNotNull)
   require(duration.isFinite() && duration>=0)
   val counters=listOf("maxDepth","enemiesSlain","score","foodEaten","ankhsUsed").map {number(bundle,it)}+number(hero,"lvl")
   require(counters.last()>0)
   val levels=requireNotNull(bundle["generated_levels"] as? JsonArray)
   require(levels.size in 1..4096)
   val generated=levels.map {val p=it as? JsonPrimitive;require(p!=null&&!p.isString);requireNotNull(p.intOrNull).also {v->require(v>=0)}}.toSet()
   require(generated.size==levels.size)
   RunProgressEvidence(fixed,duration,counters,generated)
  }.getOrNull()
 }
}
