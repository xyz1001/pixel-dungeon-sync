package pdsync

import pdsync.i18n.CoreMessages
data class DetailsState(val expanded: Boolean = false) {
 fun toggle() = copy(expanded = !expanded)
}

object NoviceCopy {
 val restoreWarning get() = CoreMessages.YOUR_LOCAL_SAVES_WILL_BE_BACKED_UP_THEN_REPLACED_PROGRESS_ABSENT_FROM_THE_SELECTED_SA.text()
 @JvmStatic fun comparison(kind: OnboardingKind?, busy: Boolean): String = when (kind) {
  null -> if (busy) CoreMessages.CHECKING_SAVES.text() else CoreMessages.THE_CHECK_IS_NOT_COMPLETE_PLEASE_RETRY.text()
  OnboardingKind.BOTH_EMPTY -> CoreMessages.NEITHER_SIDE_HAS_SAVES_CREATE_SOME_PROGRESS_IN_THE_GAME_FIRST.text()
  OnboardingKind.LOCAL_ONLY -> CoreMessages.THIS_DEVICE_HAS_SAVES_READY_TO_UPLOAD.text()
  OnboardingKind.REMOTE_ONLY -> CoreMessages.THE_CLOUD_HAS_SAVES_READY_TO_RESTORE_TO_THIS_DEVICE.text()
  OnboardingKind.IDENTICAL -> CoreMessages.BOTH_SIDES_HAVE_THE_SAME_SAVES_CONFIRM_TO_CONTINUE.text()
  OnboardingKind.DIFFERENT -> CoreMessages.THE_SAVES_DIFFER_CHOOSE_THE_PROGRESS_YOU_WANT_TO_CONTINUE.text()
  OnboardingKind.BRANCHED -> CoreMessages.THE_CLOUD_HAS_MULTIPLE_BRANCHES_OF_PROGRESS_CHOOSE_ONE.text()
  OnboardingKind.BLOCKED -> CoreMessages.CANNOT_COMPARE_RIGHT_NOW_RESOLVE_THE_ISSUE_AND_RETRY.text()
 }
 /** Exact bilingual presentation allowlist; independent of the current system locale. */
 @JvmStatic fun isComparisonMessage(value: String): Boolean = listOf(
  CoreMessages.CHECKING_SAVES,
  CoreMessages.THE_CHECK_IS_NOT_COMPLETE_PLEASE_RETRY,
  CoreMessages.NEITHER_SIDE_HAS_SAVES_CREATE_SOME_PROGRESS_IN_THE_GAME_FIRST,
  CoreMessages.THIS_DEVICE_HAS_SAVES_READY_TO_UPLOAD,
  CoreMessages.THE_CLOUD_HAS_SAVES_READY_TO_RESTORE_TO_THIS_DEVICE,
  CoreMessages.BOTH_SIDES_HAVE_THE_SAME_SAVES_CONFIRM_TO_CONTINUE,
  CoreMessages.THE_SAVES_DIFFER_CHOOSE_THE_PROGRESS_YOU_WANT_TO_CONTINUE,
  CoreMessages.THE_CLOUD_HAS_MULTIPLE_BRANCHES_OF_PROGRESS_CHOOSE_ONE,
  CoreMessages.CANNOT_COMPARE_RIGHT_NOW_RESOLVE_THE_ISSUE_AND_RETRY
 ).any { value == it.chinese() || value == it.english() }

 @JvmStatic fun feedback(raw: String, fallback: String): String = when {
  containsAny(raw, "本机设置未保存", "Local settings were not saved") -> CoreMessages.SETTINGS_WERE_NOT_SAVED_CHECK_CONFIGURATION_DIRECTORY_PERMISSIONS_AND_RETRY.text()
  raw.contains("401") || raw.contains("403") -> CoreMessages.CONNECTION_FAILED_CHECK_YOUR_USERNAME_PASSWORD_AND_ACCESS_PERMISSIONS.text()
  containsAny(raw, "不兼容", "兼容范围", "降级", "存档写入版本", "incompatible", "compatibility range", "downgrad", "save write version", "supported restore versions") -> CoreMessages.CANNOT_RESTORE_RIGHT_NOW_CHECK_THE_GAME_AND_SAVE_VERSIONS.text()
  containsAny(raw, "游戏正在运行", "无法确认游戏", "game is running", "cannot confirm that the game", "cannot confirm the game") -> CoreMessages.EXIT_THE_GAME_AND_RETRY.text()
  containsAny(raw, "存档发生变化", "存档仍在写入", "saves changed", "saves are still being written") -> CoreMessages.SAVES_ARE_CHANGING_PLEASE_RETRY_SHORTLY.text()
  raw == "WebDAV network request failed [TIMEOUT]" -> CoreMessages.CONNECTION_TIMED_OUT_PLEASE_RETRY.text()
  raw.startsWith("WebDAV network request failed [") -> CoreMessages.NETWORK_CONNECTION_FAILED_PLEASE_RETRY.text()
  raw.contains("timed out", true) || raw.contains("timeout", true) || raw.contains("超时") -> CoreMessages.CONNECTION_TIMED_OUT_CHECK_YOUR_NETWORK_AND_RETRY.text()
  raw.length <= 48 && !raw.contains('\n') && !raw.contains(Regex("[A-Za-z]:[\\\\/]|https?://")) -> raw
  else -> fallback
 }
 // Legacy free-form errors are normalized for presentation only, never for sync decisions.
 private fun containsAny(raw: String, vararg tokens: String) = tokens.any { raw.contains(it, ignoreCase = true) }
}
