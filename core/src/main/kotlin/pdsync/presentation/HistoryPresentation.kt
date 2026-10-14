package pdsync.presentation

import pdsync.SyncHistory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HistoryNode(
 val id: String, val snapshotId: String?, val name: String, val time: String,
 val detail: String, val status: String, val column: Int, val row: Int,
 val dirty: Boolean = false, val selectable: Boolean = false
)

data class HistoryEdge(val fromId: String, val toId: String, val dashed: Boolean = false)

/** Pure rendering model. Paging and rendering never export saves or refresh a comparison. */
data class HistoryPresentation(
 val nodes: List<HistoryNode>, val edges: List<HistoryEdge>, val page: Int,
 val pages: Int, val rows: Int, val cloudCount: Int, val localEmpty: Boolean
) {
 companion object {
  const val PAGE_SIZE = 20

  @JvmStatic fun time(value: String?): String = value?.let {
   runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull()
  } ?: "时间未知"

  /** Missing counts display as zero without changing the nullable save summary or game data. */
  @JvmStatic fun plays(value: Int?): String = "已进行${value ?: 0}场游戏"

  /** The verified history supplied by the facade is the sole source of nodes and edges. */
  @JvmStatic fun from(state: SyncHistory, requestedPage: Int): HistoryPresentation {
   val allCloud = state.cloud
   val pages = maxOf(1, (allCloud.size + PAGE_SIZE - 1) / PAGE_SIZE)
   val page = requestedPage.coerceIn(0, pages - 1)
   val cloud = allCloud.drop(page * PAGE_SIZE).take(PAGE_SIZE)
   val nodes = mutableListOf<HistoryNode>()
   cloud.forEachIndexed { index, commit ->
    val snapshot = commit.snapshot
    nodes += HistoryNode(
     commit.id, snapshot.id,
     state.aliases[snapshot.id]?.takeIf(String::isNotBlank)
      ?: snapshot.directoryMetadata?.displayName?.takeIf(String::isNotBlank)
      ?: "存档 ${commit.id.take(6)}",
     time(snapshot.metadata?.latestFileModifiedAt ?: snapshot.metadata?.capturedAt),
     "${snapshot.metadata?.device?.label ?: "来源未知"} · ${snapshot.saveVersions?.runs?.size?.toString() ?: "?"} 个槽位",
     if (commit.id in state.graph.tips) "云端当前分支" else "云端历史",
     0, index, selectable = true
    )
   }
   if (state.localNodeCount > 0) nodes += HistoryNode(
    "local-current", null, state.currentName.ifBlank { "本机当前" },
    time(state.summary?.slots?.values?.filterNotNull()?.maxOrNull()),
    "${state.summary?.slots?.size?.toString() ?: "?"} 个槽位 · ${plays(state.summary?.plays)}",
    if (state.clean) "已同步云端" else "未同步云端", 1, 0, dirty = !state.clean
   )
   val baseline = state.baselineCommit
   if (state.localNodeCount == 2 && baseline != null) {
    val snapshot = baseline.snapshot
    nodes += HistoryNode(
     "local-baseline", snapshot.id,
     state.aliases[snapshot.id]?.takeIf(String::isNotBlank)
      ?: snapshot.directoryMetadata?.displayName?.takeIf(String::isNotBlank) ?: "同步基线",
     time(snapshot.metadata?.latestFileModifiedAt ?: snapshot.metadata?.capturedAt),
     "${snapshot.saveVersions?.runs?.size?.toString() ?: "?"} 个槽位", "已同步云端", 1, 1
    )
   }
   val visible = nodes.map { it.id }.toSet()
   val edges = mutableListOf<HistoryEdge>()
   cloud.forEach { commit -> commit.parents.filter { it in visible }.forEach { parent ->
    edges += HistoryEdge(parent, commit.id)
   } }
   if (state.localNodeCount > 0 && baseline != null && baseline.id in visible && (state.clean || state.localNodeCount == 2)) {
    val local = if (state.localNodeCount == 2) "local-baseline" else "local-current"
    when (state.baselineDirection) {
     "upload" -> edges += HistoryEdge(local, baseline.id)
     "restore" -> edges += HistoryEdge(baseline.id, local)
    }
   }
   if (state.localNodeCount == 2) edges += HistoryEdge("local-baseline", "local-current", true)
   return HistoryPresentation(nodes.toList(), edges.toList(), page, pages,
    maxOf(1, cloud.size, state.localNodeCount), allCloud.size, state.localNodeCount == 0)
  }
 }
}
