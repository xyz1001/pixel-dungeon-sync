package pdsync

/** Closed, credential-free reasons for UI and automatic-sync decisions. */
enum class SyncBlockReason(val safeMessage: String) {
 RESTORE_RECOVERY_REQUIRED("An interrupted restore needs verification or recovery."),
 LEGACY_PENDING_UPLOAD("A legacy pending snapshot needs an explicit decision."),
 PENDING_UPLOAD("A frozen pending upload needs an explicit decision."),
 SAVE_NOT_STABLE("The game state or save files are not stable enough to sync."),
 LEGACY_REMOTE_PROTOCOL("The cloud uses the legacy sync protocol."),
 REMOTE_INCOMPLETE("Cloud history contains missing, incomplete or unverifiable data."),
 REMOTE_INTEGRITY_FAILED("Immutable cloud history failed integrity verification."),
 UNSUPPORTED_SAVE_FORMAT("The save format is unsupported or unreadable."),
 TARGET_CHANGED("The selected game, installed build or source binding changed."),
 TARGET_UNVERIFIED("The selected game or helper identity could not be verified."),
 BRIDGE_UNAVAILABLE("The game helper could not complete the requested operation."),
 FIRST_LAUNCH_NEEDED("Launch the selected game once and exit normally before syncing."),
 COMPARISON_FAILED("A safe comparison could not be completed."),
 BASELINE_UNVERIFIED("The sync baseline could not be related to the observed cloud history."),
 REMOTE_ONLY("Cloud saves exist but this device has no local save to upload."),
 HISTORY_CONFLICT("Local and cloud histories require an explicit choice."),
 INTERRUPTED("The sync check was interrupted."),
 UPLOAD_NOT_CONFIRMED("The upload is not confirmed; any frozen snapshot remains available for retry.");

 companion object {
  fun fromFailure(failure: Exception): SyncBlockReason = when (failure) {
   is ReasonedSyncFailure -> failure.syncBlockReason
   is SaveNotStableException -> SAVE_NOT_STABLE
   is LegacyProtocolException -> LEGACY_REMOTE_PROTOCOL
   is UnsettledRemoteException -> REMOTE_INCOMPLETE
   is RemoteIntegrityException -> REMOTE_INTEGRITY_FAILED
   is InterruptedException -> INTERRUPTED
   else -> COMPARISON_FAILED
  }
 }
}
interface ReasonedSyncFailure { val syncBlockReason: SyncBlockReason }
