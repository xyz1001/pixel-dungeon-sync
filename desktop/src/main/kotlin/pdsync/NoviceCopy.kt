package pdsync

internal data class DetailsState(val expanded: Boolean = false) {
 fun toggle() = copy(expanded = !expanded)
}

internal object NoviceCopy {
 const val restoreWarning = "将先备份，再替换本机存档；所选存档中没有的进度会移除。"
 fun comparison(kind: OnboardingKind?, busy: Boolean): String = when (kind) {
  null -> if (busy) "正在检查存档…" else "尚未完成检查，请重试。"
  OnboardingKind.BOTH_EMPTY -> "两边都没有存档，先去游戏中创建进度。"
  OnboardingKind.LOCAL_ONLY -> "本机有存档，可以上传。"
  OnboardingKind.REMOTE_ONLY -> "云端有存档，可以恢复到本机。"
  OnboardingKind.IDENTICAL -> "两边存档相同，确认后继续。"
  OnboardingKind.DIFFERENT -> "两边存档不同，请选择要继续的进度。"
  OnboardingKind.BRANCHED -> "云端有多份进度，请选择一份。"
  OnboardingKind.BLOCKED -> "暂时无法比较，请检查问题后重试。"
 }
 fun feedback(raw: String, fallback: String): String = when {
  raw.contains("401") || raw.contains("403") -> "连接失败，请检查用户名、密码和访问权限。"
  raw.contains("不兼容") || raw.contains("兼容范围") || raw.contains("降级") || raw.contains("存档写入版本") -> "暂不能恢复，请检查游戏与存档版本。"
  raw.contains("游戏正在运行") || raw.contains("无法确认游戏") -> "请退出游戏后重试。"
  raw.contains("存档发生变化") || raw.contains("存档仍在写入") -> "存档正在变化，请稍后重试。"
  raw.contains("timed out", true) || raw.contains("timeout", true) || raw.contains("超时") -> "连接超时，请检查网络后重试。"
  raw.length <= 48 && !raw.contains('\n') && !raw.contains(Regex("[A-Za-z]:[\\\\/]|https?://")) -> raw
  else -> fallback
 }
}
