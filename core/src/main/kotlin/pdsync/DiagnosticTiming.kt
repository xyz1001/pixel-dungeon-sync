package pdsync

import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json

/** Reconstructed from code92 DEX: bounded in-memory totals, emitted only with FINISHED. */
@Serializable enum class DiagnosticPhase {
 QUEUE_WAIT, OPERATION_LOCK_WAIT, IDENTITY_VERIFICATION, SAVE_EXPORT, LOCAL_EXPORT_VERIFY,
 MIRROR_COPY, LOCAL_SUMMARY, COMPARISON, REMOTE_METADATA, UI_COMPLETION,
 BRIDGE_READ_SNAPSHOT, BRIDGE_SAVE_EXPORT, BRIDGE_RESTORE_WRITE,
 ENTITLEMENT, UPLOAD_PREFLIGHT, UPLOAD_CAPTURE, UPLOAD_PUBLISH, UPLOAD_DELTA, UPLOAD_REFRESH, HISTORY_OBJECT_READ
}
@Serializable data class DiagnosticPhaseMeasurement(val durationMs:Long,val count:Int,val failed:Int=0,val canceled:Int=0,val skipped:Int=0)
internal class DiagnosticPhaseCollector {
 private data class Total(var nanos:Long=0,var count:Int=0,var failed:Int=0,var canceled:Int=0,var skipped:Int=0)
 private val totals=EnumMap<DiagnosticPhase,Total>(DiagnosticPhase::class.java)
 @Synchronized fun add(phase:DiagnosticPhase,nanos:Long,result:DiagnosticResult) {
  val total=totals.getOrPut(phase){Total()};if(total.count==DiagnosticTimingEvidence.MAX_COUNT)return
  total.nanos+=nanos.coerceIn(0,DiagnosticTimingEvidence.MAX_NANOS-total.nanos);total.count++
  when(result){DiagnosticResult.FAILED->total.failed++;DiagnosticResult.CANCELED->total.canceled++;DiagnosticResult.SKIPPED->total.skipped++;else->Unit}
 }
 @Synchronized fun snapshot():Map<DiagnosticPhase,DiagnosticPhaseMeasurement> = totals.mapValues { (_,t)->DiagnosticPhaseMeasurement(t.nanos/1_000_000,t.count,t.failed,t.canceled,t.skipped) }
}
internal object DiagnosticTimingEvidence {
 const val MAX_COUNT=1_000_000
 const val MAX_NANOS=604_800_000_000_000L
 private const val MAX_LENGTH=4096
 private val codec=Json { encodeDefaults=true }
 private val serializer=MapSerializer(DiagnosticPhase.serializer(),DiagnosticPhaseMeasurement.serializer())
 fun encode(values:Map<DiagnosticPhase,DiagnosticPhaseMeasurement>):String? = if(valid(values))codec.encodeToString(serializer,values.toSortedMap(compareBy{it.ordinal})).takeIf{it.length<=MAX_LENGTH}else null
 fun decode(value:String):Map<DiagnosticPhase,DiagnosticPhaseMeasurement>? = try {
  require(value.length<=MAX_LENGTH);requireUniqueJsonKeys(value)
  codec.decodeFromString(serializer,value).takeIf {valid(it)&&encode(it)==value}
 }catch(_:Exception){null}
 fun isValid(value:String)=decode(value)!=null
 private fun valid(values:Map<DiagnosticPhase,DiagnosticPhaseMeasurement>)= values.isNotEmpty()&&values.size<=DiagnosticPhase.entries.size&&values.values.all {
  it.durationMs in 0..604_800_000L && it.count in 1..MAX_COUNT && it.failed in 0..it.count && it.canceled in 0..it.count && it.skipped in 0..it.count && it.failed+it.canceled+it.skipped<=it.count
 }
}
object DiagnosticTiming {
 fun interface Task<T> { @Throws(Exception::class) fun run():T }
 @JvmStatic fun start(phase:DiagnosticPhase):Scope=start(DiagnosticLog.currentTrace(),phase)
 @JvmStatic fun start(trace:DiagnosticTrace?,phase:DiagnosticPhase):Scope=Scope(trace,phase,System::nanoTime)
 @JvmStatic @Throws(Exception::class) fun <T> measure(phase:DiagnosticPhase,task:Task<T>):T {
  val scope=start(phase)
  try { return task.run() }catch(failure:Throwable){scope.failed(failure);throw failure}finally{scope.close()}
 }
 class Scope internal constructor(private val trace:DiagnosticTrace?,private val phase:DiagnosticPhase,private val nanoTime:()->Long):AutoCloseable {
  private val started=if(trace==null)0L else nanoTime()
  private val finished=AtomicBoolean(false)
  fun succeeded()=finish(DiagnosticResult.SUCCEEDED)
  fun canceled()=finish(DiagnosticResult.CANCELED)
  fun skipped()=finish(DiagnosticResult.SKIPPED)
  fun failed(failure:Throwable)=finish(if(DiagnosticLog.classify(failure)==DiagnosticFailure.CANCELED)DiagnosticResult.CANCELED else DiagnosticResult.FAILED)
  private fun finish(result:DiagnosticResult){if(trace!=null&&finished.compareAndSet(false,true))trace.timings.add(phase,(nanoTime()-started).coerceAtLeast(0),result)}
  override fun close()=succeeded()
 }
}
