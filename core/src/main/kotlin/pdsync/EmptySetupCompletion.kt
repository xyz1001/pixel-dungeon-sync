package pdsync

/** Only a successfully verified empty comparison may use the no-action completion screen. */
object EmptySetupCompletion {
 const val message = "配置完成"
 @JvmStatic fun isEmpty(kind: OnboardingKind?, blocker: String?) = kind == OnboardingKind.BOTH_EMPTY && blocker == null
 @JvmStatic fun canNavigate(pageActive: Boolean, busy: Boolean) = pageActive && !busy
 @JvmStatic fun canFinish(kind: OnboardingKind?, blocker: String?, pageActive: Boolean, busy: Boolean, configured: Boolean) =
  isEmpty(kind, blocker) && canNavigate(pageActive, busy) && configured
}
