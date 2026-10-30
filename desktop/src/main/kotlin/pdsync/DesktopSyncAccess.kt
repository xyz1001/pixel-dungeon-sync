package pdsync

import io.github.xyz1001.pdsync.android.OfficialSyncAccess

/** Reuse the shared authenticated lease until its original deadline; never persist it across restarts. */
internal class DesktopSyncAccess(
 private val isExpiry: (Exception) -> Boolean = { it is OfficialSyncAccess.AccessException && it.isExpired },
 private val begin: (String, String, String) -> (() -> Unit) = { endpoint, username, password ->
  val lease = OfficialSyncAccess.beginOperation(endpoint, username, password)
  val check: () -> Unit = { lease.check() }
  check
 }
) {
 @Volatile private var identity: Pair<String, String>? = null
 @Volatile private var check: (() -> Unit)? = null
 @Volatile private var expired = false
 @Volatile private var knownAccess = false
 private data class DisplayExpiry(val identity: Pair<String, String>, val deadline: Long?, val serverTime: Long, val observedNanos: Long)
 @Volatile private var displayExpiry: DisplayExpiry? = null

 /** A fresh account-page result updates status only. It cannot create or replace a request lease. */
 fun observeAccountStatus(endpoint: String, username: String, expiresMs: Long?, serverTimeMs: Long) {
  if (!OfficialSyncAccess.isOfficialEndpoint(endpoint) || serverTimeMs < 0) return
  displayExpiry = DisplayExpiry(endpoint to username, expiresMs, serverTimeMs, System.nanoTime())
 }

 fun reset() { identity = null; check = null; expired = false; knownAccess = false; displayExpiry = null }

 fun beginOperation(config: AppConfig, password: String): () -> Unit {
  if (!OfficialSyncAccess.isOfficialEndpoint(config.endpoint)) return {}
  val nextIdentity = config.endpoint to config.username
  if (identity != nextIdentity) {
   val currentDisplay = displayExpiry?.takeIf { it.identity == nextIdentity }
   reset()
   displayExpiry = currentDisplay
  }
  // The same shared Lease.check used by Android retains the server deadline.
  // Display-only account facts never create a lease, and invalid leases fall through
  // to an authenticated refresh rather than extending their lifetime.
  check?.takeIf { knownAccess }?.let { cached ->
   try { verify(cached); return { verify(cached) } }
   catch (_: Exception) { /* Missing/invalid proof must be refreshed below. */ }
  }
  identity = nextIdentity
  knownAccess = false
  try {
   val fresh = begin(config.endpoint, config.username, password)
   check = fresh
   displayExpiry = null
   expired = false
   knownAccess = true
   return { verify(fresh) }
  } catch (failure: Exception) {
   if (isExpiry(failure)) { expired = true; displayExpiry = null }
   throw failure
  }
 }

 private fun verify(lease: () -> Unit) {
  try { lease() }
  catch (failure: Exception) {
   if (check === lease) knownAccess = false
   if (check === lease && isExpiry(failure)) expired = true
   throw failure
  }
 }

 /** A local monotonic deadline check updates existing status even while automatic sync is paused. */
 fun isExpired(config: AppConfig?): Boolean {
  if (config == null) return false
  displayExpiry?.takeIf { it.identity == (config.endpoint to config.username) }?.let { observed ->
   val elapsed = (System.nanoTime() - observed.observedNanos) / 1_000_000L
   return observed.deadline != null && elapsed >= 0 &&
    (observed.serverTime >= observed.deadline || elapsed >= observed.deadline - observed.serverTime)
  }
  if (identity != (config.endpoint to config.username)) return false
  check?.let { runCatching { verify(it) } }
  return expired
 }

 /** Button eligibility needs an actual known lease, never display-only account facts. */
 fun hasKnownAccess(config: AppConfig?): Boolean {
  if (config == null) return false
  if (!OfficialSyncAccess.isOfficialEndpoint(config.endpoint)) return true
  if (identity != (config.endpoint to config.username) || !knownAccess || isExpired(config)) return false
  val lease = check ?: return false
  return runCatching { verify(lease) }.isSuccess && knownAccess && !expired
 }

 fun store(config: AppConfig, game: String, password: String): AppendOnlyWebDav {
  val guard = beginOperation(config, password)
  return AppendOnlyWebDav(config.endpoint, game, config.profile, config.username, password,
   beforeRequest = WebDavRequestCheck { guard() })
 }
}
