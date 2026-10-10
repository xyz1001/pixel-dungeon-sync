package pdsync

/** Presentation only: finding an installation or directory does not verify save contents. */
internal object DiscoveryCopy {
 fun game(result: InstallationDiscoveryResult?, selected: TargetGame?): String = when {
  selected?.trusted == true -> if (selected.minimumSaveVersion == null) "此游戏版本暂不支持恢复。" else ""
  result == null -> "正在查找游戏…"
  result.incomplete -> "查找未完成，请手动选择游戏。"
  result.trustedTargets.size > 1 -> "请选择要使用的游戏。"
  result.trustedTargets.size == 1 -> "请确认游戏。"
  result.candidates.isNotEmpty() -> "无法识别游戏，请选择其他文件。"
  else -> "未找到游戏，请手动选择。"
 }

 fun directory(save: DefaultSaveDiscovery?): String = when {
  save == null -> "本机存档：尚未检查"
  save.available -> "本机存档：目录已找到，内容待检查"
  save.reason == "未找到默认存档目录。" -> "本机存档：目录尚未创建"
  else -> "本机存档：暂无法检查"
 }
}
