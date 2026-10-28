package pdsync

import pdsync.i18n.DesktopMessages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xyz1001.pdsync.android.*
import pdsync.presentation.HistoryPresentation

/** Live host identity. A recreated session or changed account invalidates every retained action. */
internal data class DesktopComparisonContext(
 val generation: Long,
 val session: AppendProfileSession?,
 val observed: OnboardingComparison?,
 val config: AppConfig?,
 val pageActive: Boolean,
 val busy: Boolean,
 val connected: Boolean,
)

internal data class DesktopComparisonProof(
 val token: Long,
 val generation: Long,
 val session: AppendProfileSession,
 val observed: OnboardingComparison,
 val config: AppConfig,
 val selectedId: String?,
 val tips: Set<String>,
 val files: Map<String, String>,
 val baseline: String?,
)

internal data class DesktopComparisonDialog(
 val proof: DesktopComparisonProof,
 val presentation: SharedComparisonConfirmation,
)

/** Owns only choice/token state. Every filesystem/network operation stays in the existing host. */
internal class DesktopSharedComparisonAdapter {
 var selectedProgress by mutableStateOf<SharedProgressChoice?>(null)
  private set
 var selectedId by mutableStateOf<String?>(null)
  private set
 var dialog by mutableStateOf<DesktopComparisonDialog?>(null)
  private set
 private var token = 0L

 fun invalidate() { token++; dialog = null }
 fun observed(value: OnboardingComparison?) {
  invalidate()
  selectedProgress = null
  selectedId = value?.remoteEntries?.singleOrNull()?.entry?.manifest?.id
 }

 fun state(
  current: DesktopComparisonContext,
  game: SharedGameIdentity? = null,
  localLabel: String = DesktopMessages.LOCAL.text(),
  notice: String = "",
  details: String = "",
  restoreSupported: Boolean = true,
 ): SharedComparisonState {
  val value = current.observed
  val recovery = value?.blockReason in setOf(SyncBlockReason.RESTORE_RECOVERY_REQUIRED, SyncBlockReason.LEGACY_PENDING_UPLOAD)
  val localDetails = value?.localLatestModifiedAt?.let { DesktopMessages.SAVE_MODIFIED_1_S.text(HistoryPresentation.time(it)) }.orEmpty()
  return SharedComparisonState(
   kind = value?.kind, cloudVerified = value != null && value.kind != OnboardingKind.BLOCKED,
   needsRestoreRecovery = recovery,
   retryablePending = value?.retryablePending == true && !recovery,
   canCompleteWithoutTransfer = value?.let(::canConfirmDesktopComparison) == true,
   canAdoptRemote = value?.canAdoptRemote == true,
   canKeepLocalProgress = value?.let(::canKeepDesktopLocalProgress) == true,
   restoreSupported = restoreSupported && current.session != null,
   pageActive = current.pageActive, configured = current.connected && current.config != null && current.session != null,
   busy = current.busy, game = game, localLabel = localLabel, localDetails = localDetails,
   localFileCount = value?.localFiles?.size,
   remoteChoices = value?.remoteEntries.orEmpty().map { remote ->
    val manifest = remote.entry.manifest
    SharedComparisonRemoteChoice(manifest.id,
     manifest.directoryMetadata?.displayName?.takeIf(String::isNotBlank)
      ?: DesktopMessages.CLOUD_1_S.text(manifest.metadata?.device?.label ?: DesktopMessages.UNKNOWN_DEVICE.text()),
     HistoryPresentation.time(manifest.metadata?.fileModifiedAt?.values?.maxOrNull()))
   },
   selectedProgress = selectedProgress, selectedId = selectedId,
   notice = notice, details = listOf(value?.blocker.orEmpty(), details, notice).filter(String::isNotBlank).distinct().joinToString("\n"),
   confirmation = dialog?.takeIf { matches(current, it.proof, requireIdle = false) }?.presentation,
   feedbackError = notice.isNotBlank() || value?.blocker != null,
  )
 }

 fun capture(current: DesktopComparisonContext): DesktopComparisonProof? {
  if (!current.pageActive || current.busy || !current.connected) return null
  val manager = current.session ?: return null
  val observed = current.observed ?: return null
  val config = current.config ?: return null
  return DesktopComparisonProof(++token, current.generation, manager, observed, config, selectedId,
   observed.observedTips.toSet(), observed.localFiles.toMap(), observed.baselineId)
 }

 fun matches(current: DesktopComparisonContext, proof: DesktopComparisonProof, requireIdle: Boolean = true): Boolean =
  owns(current, proof) && (!requireIdle || !current.busy) && current.observed === proof.observed &&
   selectedId == proof.selectedId && current.observed?.observedTips == proof.tips &&
   current.observed?.localFiles == proof.files && current.observed?.baselineId == proof.baseline

 /** After a successful operation the observation may change, but its host/account must not. */
 fun owns(current: DesktopComparisonContext, proof: DesktopComparisonProof): Boolean =
  current.pageActive && current.connected && current.generation == proof.generation &&
   current.session === proof.session && current.config == proof.config

 fun selectProgress(current: DesktopComparisonContext, choice: SharedProgressChoice) {
  val state = state(current)
  if (!state.canInteract || !state.configured || dialog != null || !state.offers(choice)) return
  invalidate(); selectedProgress = choice
 }

 fun openConflict(current: DesktopComparisonContext): DesktopComparisonProof? {
  val state = state(current)
  if (state.primary != SharedComparisonPrimary.HANDLE_CONFLICT || !state.primaryEnabled) return null
  val proof = capture(current) ?: return null
  dialog = conflict(proof)
  return proof
 }

 fun selectRemote(current: DesktopComparisonContext, id: String) {
  val old = dialog ?: return
  if (old.presentation.stage != SharedComparisonConfirmationStage.CONFLICT ||
   !matches(current, old.proof) || old.proof.observed.remoteEntries.none { it.entry.manifest.id == id }) return
  selectedId = id
  val proof = capture(current) ?: return
  dialog = conflict(proof)
 }

 fun confirmation(current: DesktopComparisonContext, expectedToken: Long,
                  stage: SharedComparisonConfirmationStage): DesktopComparisonProof? {
  val currentDialog = dialog ?: return null
  if (currentDialog.presentation.token != expectedToken || currentDialog.presentation.stage != stage ||
   !matches(current, currentDialog.proof)) return null
  return currentDialog.proof
 }



 /** A rejected no-transfer Finish must no longer display an unrefreshable identical proof. */
 fun invalidateFailedCompletion(current: DesktopComparisonContext, proof: DesktopComparisonProof): Boolean {
  if (!matches(current, proof, requireIdle = false)) return false
  observed(null)
  return true
 }

 fun cancel(current: DesktopComparisonContext, expectedToken: Long) {
  val proof = dialog?.proof ?: return
  if (proof.token == expectedToken && matches(current, proof)) invalidate()
 }

 private fun conflict(proof: DesktopComparisonProof) = DesktopComparisonDialog(proof,
  SharedComparisonConfirmation(proof.token))
}

internal fun canConfirmDesktopComparison(value: OnboardingComparison): Boolean =
 value.kind == OnboardingKind.IDENTICAL && value.baselineLineage == OnboardingLineage.SAME_TIP &&
  value.baselineId != null && value.observedTips == setOf(value.baselineId) && !value.retryablePending && value.blocker == null

internal fun canKeepDesktopLocalProgress(value: OnboardingComparison): Boolean =
 value.kind == OnboardingKind.DIFFERENT && value.baselineLineage == OnboardingLineage.SAME_TIP &&
  value.baselineId != null && value.observedTips == setOf(value.baselineId) && !value.retryablePending && value.blocker == null

/** A returned conflict or an interrupted/frozen upload is not a completed Finish action. */
internal fun desktopOnboardingOperationSucceeded(result: Any?): Boolean = when (result) {
 is AppendPushResult.Success, is OnboardingRestoreResult.Restored, is AppendCommit -> true
 is OnboardingRestoreResult.Published -> result.result is AppendPushResult.Success
 else -> false
}

internal fun desktopCanFinishAfterOperation(requested: Boolean, succeeded: Boolean, fresh: OnboardingComparison): Boolean =
 requested && succeeded && !fresh.retryablePending && fresh.blocker == null &&
  (fresh.kind == OnboardingKind.BOTH_EMPTY || canConfirmDesktopComparison(fresh))
