package pdsync

/** The persisted decision gate is independent of the visible page or a canceled dialog. */
object OnboardingGate {
 @JvmStatic fun resumedProgress(progress: OnboardingProgress, trustedTarget: Boolean, hasBaseline: Boolean): OnboardingProgress = when {
  !trustedTarget -> OnboardingProgress.PENDING
  // Explicit setup completion (including BOTH_EMPTY) is not a claim of sync ancestry.
  // Only legacy settings need a real baseline to infer a returning setup decision.
  progress == OnboardingProgress.LEGACY -> if (hasBaseline) OnboardingProgress.COMPLETE else OnboardingProgress.PENDING
  else -> progress
 }
 /** A safety backup must not become the default selected restore target after successful setup. */
 @JvmStatic fun homeSelection(baselineId: String?, tipSnapshots: Map<String, String>): String? =
  if (baselineId != null && tipSnapshots.keys == setOf(baselineId)) tipSnapshots[baselineId] else null
 /** An incomplete comparison carries no proven local count; zero would falsely imply emptiness. */
 @JvmStatic fun localFileCountLabel(kind: OnboardingKind, count: Int, retryablePending: Boolean): String =
  if (kind == OnboardingKind.BLOCKED && !retryablePending) "文件数量尚未完成核验" else "$count 个文件"
 @JvmStatic fun allowsAutomatic(progress: OnboardingProgress?, wizardActive: Boolean, cloudVerified: Boolean): Boolean =
  progress == OnboardingProgress.COMPLETE && !wizardActive && cloudVerified
}
