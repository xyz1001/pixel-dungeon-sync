package pdsync

/**
 * Pure, single-owner scheduling policy. Files are content fingerprints, never ZIP IDs or mtimes.
 * Brand-new startup only establishes an observation. A verified persisted baseline detects offline changes. A later local change or observed game exit can enqueue
 * work; an already durable frozen plan may retry after restart. The UI owns the shared operation
 * lock, and must call started synchronously before launching any upload.
 */
internal class AutomaticUpload(private val quietMillis: Long = 2500, private val maxBackoffMillis: Long = 120000) {
 private var observed: Map<String, String>? = null
 private var settled: Map<String, String>? = null
 private var changedAt = 0L
 private var dirty = false
 private var runningSeen = false
 private var inFlight = false
 private var retryAt = 0L
 private var failures = 0
 private var blocked = false
 private var comparisonSkipped = false

 /** A verified persisted sync baseline exposes changes made while the app was closed. */
 fun establishBaseline(files: Map<String, String>) { check(observed == null); settled = files.toMap() }
 fun observe(files: Map<String, String>, running: Boolean, stopped: Boolean, now: Long) {
  val immutable = files.toMap()
  if (observed == null) { observed = immutable; if (settled == null) settled = immutable; dirty = immutable != settled; changedAt = now }
  else if (observed != immutable) { observed = immutable; changedAt = now; dirty = immutable != settled; comparisonSkipped = false }
  if (running) runningSeen = true
  if (stopped && runningSeen) { runningSeen = false; dirty = true; changedAt = now; comparisonSkipped = false }
 }
 fun shouldUpload(now: Long, enabled: Boolean, connected: Boolean, stopped: Boolean, pending: Boolean, conflict: Boolean, busy: Boolean): Boolean =
  enabled && connected && stopped && !busy && !inFlight && !blocked && !comparisonSkipped && !conflict && observed != null &&
   now >= retryAt && now - changedAt >= quietMillis && (pending || dirty && observed!!.isNotEmpty())
 fun started() { check(!inFlight); inFlight = true }
 /** Only the files actually published become settled; concurrent later changes remain dirty. */
 fun succeeded(publishedFiles: Map<String, String>) {
  inFlight = false; failures = 0; retryAt = 0; blocked = false; comparisonSkipped = false
  settled = publishedFiles.toMap(); dirty = observed != settled
 }
 fun failed(now: Long, retryable: Boolean) {
  inFlight = false; failures = (failures + 1).coerceAtMost(16)
  retryAt = now + minOf(maxBackoffMillis, 5000L shl (failures - 1).coerceAtMost(10))
  blocked = !retryable
 }
 /** A rejected comparison is not a publish or a network retry. Wait for a new event or explicit retry,
  * even when an unchanged frozen pending plan remains. Do not invent a settled sync baseline. */
 fun skipped() { inFlight = false; failures = 0; retryAt = 0; comparisonSkipped = true }
 /** A deliberate manual retry or corrected settings can release a validation failure. */
 fun retryAllowed() { blocked = false; comparisonSkipped = false; retryAt = 0 }
 /** Restore/archival is already explicit work. Do not manufacture another change from its writes. */
 fun applied(files: Map<String, String>, now: Long) {
  observed = files.toMap(); settled = files.toMap(); dirty = false; changedAt = now
  runningSeen = false; blocked = false; comparisonSkipped = false; failures = 0; retryAt = 0
 }
 val waitingForRetry get() = failures > 0 && !blocked
 val needsAttention get() = blocked || comparisonSkipped
}
