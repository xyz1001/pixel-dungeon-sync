package pdsync

/** Presentation only: finding an installation or directory does not verify save contents. */
internal object DiscoveryCopy {
 fun game(result: InstallationDiscoveryResult?, selected: TargetGame?): String = when {
  selected?.trusted == true -> "已确认可信游戏，仅静态校验，未运行游戏。" +
   if (result?.incomplete == true) "安装位置扫描未完成，但当前选择已单独通过可信校验。" else "可继续连接云端。"
  result == null -> "尚未完成游戏检查，请等待扫描结果或手动选择游戏 JAR。"
  result.incomplete -> "安装位置扫描未完成，不能自动确认游戏；请从候选中明确选择，或手动选择官方游戏 JAR。"
  result.trustedTargets.size > 1 -> "找到多个可信游戏，请从候选中明确选择要使用的一份。"
  result.trustedTargets.size == 1 -> "找到可信游戏候选，请确认要使用的游戏。"
  result.candidates.isNotEmpty() -> "发现游戏候选，但未通过可信版本校验；请手动选择已核实的官方 4.0.2 JAR。"
  else -> "在已检查的位置未找到可信游戏，请手动选择已核实的官方 4.0.2 JAR。"
 }

 fun directory(save: DefaultSaveDiscovery?): String = when {
  save == null -> "此路径是默认存档目标位置；目录检查尚未完成。存档内容将在第 3 步核验。"
  save.available -> "默认目录存在，但尚未检查是否有存档。存档内容将在第 3 步连接云端后核验与比较。"
  else -> (save.reason ?: "默认目录尚未确认可用。") +
   "此路径只是默认存档目标位置。继续时仅在路径安全检查通过后准备空目录，不创建游戏存档或上传内容；存档内容将在第 3 步核验。"
 }
}
