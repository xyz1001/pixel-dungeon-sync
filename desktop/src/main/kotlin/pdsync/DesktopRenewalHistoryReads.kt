package pdsync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Account-page facts schedule one read after returning HOME; they never authorize DAV.
 * Kept above the connection page so SETTINGS/Back cannot lose a successful renewal.
 */
internal class DesktopRenewalHistoryReads {
 data class Request internal constructor(val owner: AppConfig, internal val revision: Long)

 private var owner: AppConfig? = null
 private var revision = 0L
 private var refreshing = false
 private var available = true
 private var recoveryNeeded = false
 var pending by mutableStateOf<Request?>(null)
  private set
 var blocksHomeObservation by mutableStateOf(false)
  private set

 fun refreshStarted(config: AppConfig, homeAvailable: Boolean) {
  if (owner != config) {
   reset()
   owner = config
   available = homeAvailable
   recoveryNeeded = !homeAvailable
  } else if (!homeAvailable) recoveryNeeded = true
  revision++
  refreshing = true
  // Do not forget an unconsumed recovery when Refresh is pressed again before Back.
  pending = null
  blocksHomeObservation = true
 }

 fun accountAccessChanged(config: AppConfig, freshAvailable: Boolean) {
  if (owner != config || !refreshing) return
  refreshing = false
  available = freshAvailable
  if (!freshAvailable) {
   recoveryNeeded = true
   pending = null
   blocksHomeObservation = true
   return
  }
  pending = if (recoveryNeeded) Request(config, revision) else null
  blocksHomeObservation = pending != null
 }

 /** Busy, SETTINGS, still-expired and closing windows leave the request unconsumed. */
 fun takeForHome(config: AppConfig?, home: Boolean, ready: Boolean): Request? {
  if (owner != null && owner != config) { reset(); return null }
  val request = pending ?: return null
  if (!home || !ready || refreshing || !available) return null
  pending = null
  recoveryNeeded = false
  blocksHomeObservation = false
  return request
 }

 fun isCurrent(request: Request, config: AppConfig?): Boolean =
  owner == config && request.owner == config && request.revision == revision && available && !refreshing

 /** A failed/unknown account refresh must not be bypassed by the HOME read button. */
 fun allowsManualRefresh(config: AppConfig?): Boolean =
  config != null &&
   (owner == null || owner == config && available && !refreshing)

 fun manualRequest(config: AppConfig?): Request? =
  config?.takeIf(::allowsManualRefresh)?.let { Request(it, revision) }

 fun isManualCurrent(request: Request, config: AppConfig?): Boolean =
  request.owner == config && request.revision == revision && allowsManualRefresh(config)

 fun reset() {
  revision++
  owner = null
  refreshing = false
  available = true
  recoveryNeeded = false
  pending = null
  blocksHomeObservation = false
 }
}
