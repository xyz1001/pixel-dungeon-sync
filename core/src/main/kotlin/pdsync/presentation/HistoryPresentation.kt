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
  /** Pages contain complete time rows, so equal-time cloud/local pairs cannot be split. */
  const val PAGE_SIZE = 20

  @JvmStatic fun time(value: String?): String = instant(value)?.let {
   DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(it)
  } ?: "时间未知"

  private fun instant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

  /** Missing counts display as zero without changing the nullable save summary or game data. */
  @JvmStatic fun plays(value: Int?): String = "已进行${value ?: 0}场游戏"

  /**
   * Assign global newest-first save-content rows before paging. Commit publication time is never
   * a substitute for save-content time. Ties use stable IDs; opposite lanes can share a row, while
   * two nodes in one lane cannot. Unknown times remain unknown and sort last.
   *
   * The verified history supplied by the facade is the sole source of nodes and true graph edges.
   * An edge whose other endpoint is on another page is omitted, never redirected to a visible node.
   */
  @JvmStatic fun from(state: SyncHistory, requestedPage: Int): HistoryPresentation {
   data class TimedNode(val node: HistoryNode, val instant: Instant?)
   val allCloud = state.graph.commits.values
   val all = mutableListOf<TimedNode>()
   allCloud.forEach { commit ->
    val snapshot = commit.snapshot
    val savedAt = snapshot.metadata?.latestFileModifiedAt
    all += TimedNode(HistoryNode(
     commit.id, snapshot.id,
     state.aliases[snapshot.id]?.takeIf(String::isNotBlank)
      ?: snapshot.directoryMetadata?.displayName?.takeIf(String::isNotBlank)
      ?: "存档 ${commit.id.take(6)}",
     time(savedAt),
     "${snapshot.metadata?.device?.label ?: "来源未知"} · ${snapshot.saveVersions?.runs?.size?.toString() ?: "?"} 个槽位",
     if (commit.id in state.graph.tips) "云端当前分支" else "云端历史",
     0, 0, selectable = true
    ), instant(savedAt))
   }
   if (state.localNodeCount > 0) {
    val savedAt = state.summary?.slots?.values?.mapNotNull(::instant)?.maxOrNull()?.toString()
    all += TimedNode(HistoryNode(
     "local-current", null, state.currentName.ifBlank { "本机当前" }, time(savedAt),
     "${state.summary?.slots?.size?.toString() ?: "?"} 个槽位 · ${plays(state.summary?.plays)}",
     if (state.clean) "已同步云端" else "未同步云端", 1, 0, dirty = !state.clean
    ), instant(savedAt))
   }
   val baseline = state.baselineCommit
   if (state.localNodeCount == 2 && baseline != null) {
    val snapshot = baseline.snapshot
    val savedAt = snapshot.metadata?.latestFileModifiedAt
    all += TimedNode(HistoryNode(
     "local-baseline", snapshot.id,
     state.aliases[snapshot.id]?.takeIf(String::isNotBlank)
      ?: snapshot.directoryMetadata?.displayName?.takeIf(String::isNotBlank) ?: "同步基线",
     time(savedAt), "${snapshot.saveVersions?.runs?.size?.toString() ?: "?"} 个槽位", "已同步云端", 1, 0
    ), instant(savedAt))
   }
   val ordered = all.sortedWith(compareByDescending<TimedNode> { it.instant }.thenBy { it.node.id })
   val globalRows = mutableListOf<MutableList<HistoryNode>>()
   var groupStart = 0
   var previous: Instant? = null
   ordered.forEachIndexed { index, entry ->
    if (index == 0 || entry.instant != previous) {
     groupStart = globalRows.size
     previous = entry.instant
    }
    val existing = (groupStart until globalRows.size).firstOrNull { row ->
     globalRows[row].none { it.column == entry.node.column }
    }
    val row = existing ?: globalRows.size.also { globalRows.add(mutableListOf()) }
    globalRows[row] += entry.node.copy(row = row)
   }
   val pages = maxOf(1, (globalRows.size + PAGE_SIZE - 1) / PAGE_SIZE)
   val page = requestedPage.coerceIn(0, pages - 1)
   val firstRow = page * PAGE_SIZE
   val pageRows = globalRows.drop(firstRow).take(PAGE_SIZE)
   val nodes = pageRows.flatten().map { it.copy(row = it.row - firstRow) }
   val visible = nodes.map { it.id }.toSet()
   val edges = mutableListOf<HistoryEdge>()
   allCloud.filter { it.id in visible }.forEach { commit ->
    commit.parents.filter { it in visible }.forEach { parent -> edges += HistoryEdge(parent, commit.id) }
   }
   if (state.localNodeCount > 0 && baseline != null && (state.clean || state.localNodeCount == 2)) {
    val local = if (state.localNodeCount == 2) "local-baseline" else "local-current"
    if (local in visible && baseline.id in visible) when (state.baselineDirection) {
     "upload" -> edges += HistoryEdge(local, baseline.id)
     "restore" -> edges += HistoryEdge(baseline.id, local)
    }
   }
   if ("local-baseline" in visible && "local-current" in visible) {
    edges += HistoryEdge("local-baseline", "local-current", true)
   }
   return HistoryPresentation(nodes, edges, page, pages, maxOf(1, pageRows.size),
    allCloud.size, state.localNodeCount == 0)
  }
 }
}
