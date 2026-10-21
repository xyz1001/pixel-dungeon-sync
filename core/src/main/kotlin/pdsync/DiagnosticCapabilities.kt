package pdsync

import java.util.EnumMap

/** Closed evidence from the actual capability check, never a second handshake or persistent cache. */
enum class DiagnosticCapability { READ_SNAPSHOT, RESTORE_RESULT }
enum class DiagnosticCapabilityState {
 SUPPORTED, NOT_EMBEDDED, AUTHENTICATE_UNSUPPORTED, PROTOCOL_UNSUPPORTED,
 VERSION_MISSING, VERSION_UNSUPPORTED, INVALID_REPLY, AUTHENTICATION_UNVERIFIED, FAILED, CANCELED
}

internal class DiagnosticCapabilityCollector {
 private val states=EnumMap<DiagnosticCapability,DiagnosticCapabilityState>(DiagnosticCapability::class.java)
 private var versions:List<Int>?=null
 @Synchronized fun record(capability:DiagnosticCapability,state:DiagnosticCapabilityState) { states[capability]=state }
 @Synchronized fun reply(accessProtocol:Int,singlePassRead:Int,readSnapshot:Int,restoreResult:Int) {
  versions=listOf(accessProtocol,singlePassRead,readSnapshot,restoreResult)
 }
 @Synchronized fun snapshot():DiagnosticDetails {
  val result=DiagnosticDetails.builder()
  states.forEach { (capability,state)->result.helperCapability(capability,state) }
  versions?.let { result.helperCapabilityVersions(it[0],it[1],it[2],it[3]) }
  return result.build()
 }
}

/** In-memory only; the existing FINISHED record flushes these bounded observations once. */
object DiagnosticCapabilities {
 @JvmStatic fun record(capability:DiagnosticCapability,state:DiagnosticCapabilityState) {
  DiagnosticLog.currentTrace()?.capabilities?.record(capability,state)
 }
 @JvmStatic fun reply(accessProtocol:Int,singlePassRead:Int,readSnapshot:Int,restoreResult:Int) {
  DiagnosticLog.currentTrace()?.capabilities?.reply(accessProtocol,singlePassRead,readSnapshot,restoreResult)
 }
}
