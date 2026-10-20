package pdsync.presentation

import pdsync.i18n.CoreMessages
import pdsync.i18n.UiLocale
import java.time.format.FormatStyle
import pdsync.AppendCommit
import pdsync.SyncHistory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.PriorityQueue

data class HistoryNode(
 val id: String, val snapshotId: String?, val name: String, val time: String,
 val detail: String, val status: String, val column: Int, val row: Int,
 val dirty: Boolean = false, val selectable: Boolean = false, val cloudHead: Boolean = false
)

/** sameContent is a display-only content match, never an operation or ancestry edge. */
data class HistoryEdge(val fromId: String, val toId: String, val dashed: Boolean = false, val sameContent: Boolean = false)

/** Pure rendering model. Paging and rendering never export saves or refresh a comparison. */
data class HistoryPresentation(
 val nodes: List<HistoryNode>, val edges: List<HistoryEdge>, val page: Int,
 val pages: Int, val rows: Int, val cloudCount: Int, val localEmpty: Boolean
) {
 companion object {
  /** Pages contain complete time rows, so equal-time cloud/local pairs cannot be split. */
  const val PAGE_SIZE = 20

  @JvmStatic fun time(value: String?): String = instant(value)?.let {
   DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(UiLocale.formattingLocale()).withZone(ZoneId.systemDefault()).format(it)
  } ?: CoreMessages.TIME_UNKNOWN.text()

  private fun slots(value: Int?): String = if (value == 1) CoreMessages.ONE_SLOT.text() else CoreMessages.TEXT_1_S_SLOTS.text(value?.toString() ?: "?")

  private fun instant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

  /**
   * A record belongs to its own operation, not the age of the save files it contains. Older
   * ordinary snapshots can approximate that time with their capture. Restore, resolution and
   * import records may reuse an old snapshot, so its capture cannot date their new operation.
   */
  private fun recordTime(commit: AppendCommit): Instant? =
   if (commit.operation != null) instant(commit.operation.preparedAt)
   else if (commit.kind == "snapshot") instant(commit.snapshot.metadata?.capturedAt) else null

  /** Time is primary. Within a tied group, descendants precede parents and IDs settle branches. */
  private fun orderedRecords(commits: Collection<AppendCommit>): List<AppendCommit> =
   commits.groupBy(::recordTime).entries.sortedWith(compareByDescending { it.key }).flatMap { (_, group) ->
    val byId = group.associateBy { it.id }
    val remainingChildren = group.associate { it.id to 0 }.toMutableMap()
    group.forEach { child -> child.parents.filter { it in byId }.forEach { parent ->
     remainingChildren[parent] = remainingChildren.getValue(parent) + 1
    } }
    val ready = PriorityQueue<String>()
    remainingChildren.filterValues { it == 0 }.keys.forEach(ready::add)
    val ordered = mutableListOf<AppendCommit>()
    while (ready.isNotEmpty()) {
     val child = byId.getValue(ready.remove())
     ordered += child
     child.parents.filter { it in byId }.forEach { parent ->
      val remaining = remainingChildren.getValue(parent) - 1
      remainingChildren[parent] = remaining
      if (remaining == 0) ready.add(parent)
     }
    }
    // Verified history is acyclic; keep malformed in-memory input deterministic and visible too.
    val emitted = ordered.mapTo(mutableSetOf()) { it.id }
    ordered + group.filter { it.id !in emitted }.sortedBy { it.id }
   }

  /** Missing counts display as zero without changing the nullable save summary or game data. */
  @JvmStatic fun plays(value: Int?): String = if (value == 1) CoreMessages.ONE_GAME_PLAYED.text() else CoreMessages.TEXT_1_S_GAMES_PLAYED.text(value ?: 0)

  /**
   * Assign global newest-first history-record rows before paging. Equal-time children precede
   * parents with stable IDs for unrelated branches. Opposite lanes can share a row, while two
   * nodes in one lane cannot. A local copy with identical verified cloud content is paired with
   * its established baseline, or otherwise the newest matching record, and displays that record's
   * time. Only unmatched local progress uses its own save-file time. Unknown times sort last.
   *
   * The verified history supplied by the facade is the sole source of nodes and true graph edges.
   * An edge whose other endpoint is on another page is omitted, never redirected to a visible node.
   */
  @JvmStatic fun from(state: SyncHistory, requestedPage: Int): HistoryPresentation {
   data class TimedNode(val node: HistoryNode, val instant: Instant?)
   val allCloud = orderedRecords(state.graph.commits.values)
   val cloudOrder = allCloud.mapIndexed { index, commit -> commit.id to index }.toMap()
   val baseline = state.baselineCommit
   // Content equality proves cloud protection even when this installation has no baseline.
   val matchingCurrent = if (state.currentFiles.isEmpty()) null else
    baseline?.takeIf { it.snapshot.files == state.currentFiles }
     ?: allCloud.firstOrNull { it.snapshot.files == state.currentFiles }
   val all = mutableListOf<TimedNode>()
   allCloud.forEach { commit ->
    val snapshot = commit.snapshot
    val recordedAt = recordTime(commit)
    all += TimedNode(HistoryNode(
     commit.id, snapshot.id,
     state.aliases[snapshot.id]?.takeIf(String::isNotBlank)
      ?: snapshot.directoryMetadata?.displayName?.takeIf(String::isNotBlank)
      ?: CoreMessages.SAVE_1_S.text(commit.id.take(6)),
     time(recordedAt?.toString()),
     CoreMessages.SOURCE_AND_SLOTS.text(snapshot.metadata?.device?.label ?: CoreMessages.UNKNOWN_SOURCE.text(), slots(snapshot.saveVersions?.runs?.size)),
     if (commit.id in state.graph.tips) CoreMessages.CURRENT_CLOUD_BRANCH.text() else CoreMessages.CLOUD_HISTORY.text(),
     0, 0, selectable = true, cloudHead = commit.id in state.graph.tips
    ), recordedAt)
   }
   if (state.localNodeCount > 0) {
    val recordedAt = if (matchingCurrent != null) recordTime(matchingCurrent)
     else state.summary?.slots?.values?.mapNotNull(::instant)?.maxOrNull()
    all += TimedNode(HistoryNode(
     "local-current", null, state.currentName.ifBlank { CoreMessages.CURRENT_LOCAL.text() }, time(recordedAt?.toString()),
     CoreMessages.SLOTS_AND_PLAYS.text(slots(state.summary?.slots?.size), plays(state.summary?.plays)),
     if (state.currentProtected) CoreMessages.SYNCED_TO_CLOUD.text() else CoreMessages.NOT_SYNCED_TO_CLOUD.text(), 1, 0, dirty = !state.currentProtected
    ), recordedAt)
   }
   if (state.localNodeCount == 2 && baseline != null) {
    val snapshot = baseline.snapshot
    val recordedAt = recordTime(baseline)
    all += TimedNode(HistoryNode(
     "local-baseline", snapshot.id,
     state.aliases[snapshot.id]?.takeIf(String::isNotBlank)
      ?: snapshot.directoryMetadata?.displayName?.takeIf(String::isNotBlank) ?: CoreMessages.SYNC_BASELINE.text(),
     time(recordedAt?.toString()), slots(snapshot.saveVersions?.runs?.size), CoreMessages.SYNCED_TO_CLOUD.text(), 1, 0
    ), recordedAt)
   }
   val ordered = all.sortedWith(compareByDescending<TimedNode> { it.instant }.thenBy { it.node.column }
    .thenBy { cloudOrder[it.node.id] ?: Int.MAX_VALUE }.thenBy { it.node.id })
   val globalRows = mutableListOf<MutableList<HistoryNode>>()
   var groupStart = 0
   var previous: Instant? = null
   ordered.forEachIndexed { index, entry ->
    if (index == 0 || entry.instant != previous) {
     groupStart = globalRows.size
     previous = entry.instant
    }
    // Identical file contents share the cloud row despite device timestamp precision differences.
    // This is a visual pairing only, never an invented upload/restore edge or baseline mutation.
    val matchingId = when (entry.node.id) {
     "local-current" -> matchingCurrent?.id
     "local-baseline" -> baseline?.id
     else -> null
    }
    val matchedRow = matchingId?.let { id ->
     globalRows.indexOfFirst { row -> row.any { it.id == id } }
      .takeIf { it >= 0 && globalRows[it].none { node -> node.column == 1 } }
    }
    // Clouds are emitted first within a time group, so only the local lane can reuse a row.
    val existing = matchedRow ?: if (entry.node.column == 1) (groupStart until globalRows.size).firstOrNull { row ->
     globalRows[row].none { it.column == entry.node.column }
    } else null
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
   // Exact file-map equality is the canonical content hash input (no time/name/device metadata).
   // Show a local-to-cloud content match, never infer an upload/restore event or mutate the DAG.
   for ((localId, cloud) in listOf("local-current" to matchingCurrent, "local-baseline" to baseline)) {
    if (cloud != null && localId in visible && cloud.id in visible &&
     edges.none { setOf(it.fromId, it.toId) == setOf(localId, cloud.id) }) {
     edges += HistoryEdge(localId, cloud.id, sameContent = true)
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
