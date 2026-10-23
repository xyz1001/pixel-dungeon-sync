package pdsync

import java.util.EnumMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json

/** Bounded in-memory operation totals. No paths, URLs, content or account identifiers. */
@Serializable enum class DiagnosticTransferKind { OBJECT_GET, COMMIT_GET, LIST, OBJECT_PUT, COMMIT_PUT, NAMESPACE, OTHER, CACHE_HIT, CACHE_MISS }
@Serializable data class DiagnosticTransferMeasurement(val count:Long,val failed:Long,val canceled:Long,
 val headersMs:Long,val bodyMs:Long,val totalMs:Long,val sentBytes:Long,val receivedBytes:Long)
internal class DiagnosticTransferCollector {
 private val values=EnumMap<DiagnosticTransferKind,DiagnosticTransferMeasurement>(DiagnosticTransferKind::class.java)
 @Synchronized fun add(kind:DiagnosticTransferKind,headers:Long,body:Long,total:Long,sent:Long,received:Long,result:DiagnosticResult) {
  val old=values[kind] ?: DiagnosticTransferMeasurement(0,0,0,0,0,0,0,0)
  fun sum(a:Long,b:Long)=(a+b.coerceIn(0,LIMIT)).coerceAtMost(LIMIT)
  values[kind]=DiagnosticTransferMeasurement(sum(old.count,1),sum(old.failed,if(result==DiagnosticResult.FAILED)1 else 0),
   sum(old.canceled,if(result==DiagnosticResult.CANCELED)1 else 0),sum(old.headersMs,headers/1_000_000),sum(old.bodyMs,body/1_000_000),
   sum(old.totalMs,total/1_000_000),sum(old.sentBytes,sent),sum(old.receivedBytes,received))
 }
 @Synchronized fun snapshot()=values.toMap()
 companion object {const val LIMIT=1_000_000_000_000L}
}
internal object DiagnosticTransferEvidence {
 private val codec=Json {encodeDefaults=true}
 private val serializer=MapSerializer(DiagnosticTransferKind.serializer(),DiagnosticTransferMeasurement.serializer())
 fun encode(values:Map<DiagnosticTransferKind,DiagnosticTransferMeasurement>):String? = if(values.isEmpty())null else codec.encodeToString(serializer,values.toSortedMap(compareBy{it.ordinal})).takeIf{it.length<=4096}
 fun isValid(value:String):Boolean=try {
  require(value.length<=4096);requireUniqueJsonKeys(value)
  val values=codec.decodeFromString(serializer,value)
  values.isNotEmpty() && values.values.all {v->listOf(v.count,v.failed,v.canceled,v.headersMs,v.bodyMs,v.totalMs,v.sentBytes,v.receivedBytes).all {it in 0..DiagnosticTransferCollector.LIMIT} && v.count>0 && v.failed+v.canceled<=v.count} && encode(values)==value
 }catch(_:Exception){false}
}
