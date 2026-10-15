package pdsync.presentation

/** Shared screen identities. COMPARISON remains readable for legacy saved navigation only. */
enum class SyncPage { HOME, SETTINGS, DISCOVERY, PATH, CONNECTION, COMPARISON, CONFLICT, CONFIRM }

object AppPresentation {
 const val DISCOVERY_TITLE = "1 / 3  选择游戏"
 const val PATH_TITLE = "2 / 3  设置存档路径"
 const val CONNECTION_TITLE = "3 / 3  连接云端"
 const val COMPARISON_TITLE = "选择存档"
 const val COMPLETE_TITLE = "配置完成"
 const val HISTORY_TITLE = "完整档案历史"
 const val AUTOMATIC_PAUSED = "自动上传暂停，请先选择上传或恢复。"
 const val CONFLICT_TITLE = "存档发生冲突"
 const val CONFLICT_EXPLANATION = "覆盖：所选快照成为本机和云端的当前版本。合并：保留各局改动，并校验合并徽章、历史和图鉴。同局分叉各占一个槽位；图鉴次数取最大，排行榜去重后保留最高分11条。累计统计缺少完整证据时保守取较大值，可能少计；格式损坏或超出六槽仍停止，原始快照保留。"
}
