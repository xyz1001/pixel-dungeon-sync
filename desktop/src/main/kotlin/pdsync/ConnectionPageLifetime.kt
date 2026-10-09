package pdsync

/**
 * Per-page synchronous guard. A disposed page cannot start another attempt, persist a late
 * verification result, or publish a callback. Persistence and disposal are linearized so a
 * write already in progress completes before disposal; no write can start after disposal.
 */
internal class ConnectionPageLifetime {
 private val lock = Any()
 private var active = true
 private var running = false
 private var generation = 0L

 fun begin(): Long? = synchronized(lock) {
  if (!active || running) null else { running = true; ++generation }
 }

 fun isCurrent(ticket: Long): Boolean = synchronized(lock) {
  active && running && generation == ticket
 }

 fun <T> persist(ticket: Long, action: () -> T): T? = synchronized(lock) {
  if (active && running && generation == ticket) action() else null
 }

 fun finish(ticket: Long): Boolean = synchronized(lock) {
  if (!active || !running || generation != ticket) false
  else { running = false; true }
 }

 fun close(cancel: () -> Unit) = synchronized(lock) {
  if (active) {
   active = false; running = false; generation++
   cancel()
  }
 }
}
