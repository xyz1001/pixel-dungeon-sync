package pdsync

/** The persisted decision gate is independent of the visible page or a canceled dialog. */
internal object OnboardingGate {
 fun resumedProgress(progress: OnboardingProgress, trustedTarget: Boolean, hasBaseline: Boolean): OnboardingProgress = when {
  !trustedTarget -> OnboardingProgress.PENDING
  progress == OnboardingProgress.COMPLETE && !hasBaseline -> OnboardingProgress.PENDING
  progress == OnboardingProgress.LEGACY -> if (hasBaseline) OnboardingProgress.COMPLETE else OnboardingProgress.PENDING
  else -> progress
 }
 fun allowsAutomatic(progress: OnboardingProgress?, wizardActive: Boolean, cloudVerified: Boolean): Boolean =
  progress == OnboardingProgress.COMPLETE && !wizardActive && cloudVerified
}
