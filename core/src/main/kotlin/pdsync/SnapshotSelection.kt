package pdsync

import pdsync.i18n.CoreMessages
/**
 * Snapshot-facing lists may collapse exact immutable duplicates, but must never collapse the
 * commit graph: equal content does not prove equal ancestry. Prefer an observed tip, then the
 * lexicographically smallest commit ID, solely as a deterministic handle for those same bytes.
 * Downloads still verify the archive and ProfileSession.remember checks exact ZIP equality.
 */
internal fun Iterable<AppendCommit>.snapshotRepresentatives(preferredIds: Set<String> = emptySet()): List<AppendCommit> =
 groupBy { it.snapshot.id }.values.map { copies ->
  val first = copies.first()
  if (copies.any { it.snapshot != first.snapshot || it.objectBytes != first.objectBytes })
   throw RemoteIntegrityException(CoreMessages.HISTORY_ENTRIES_WITH_THE_SAME_SNAPSHOT_ID_HAVE_DIFFERENT_CONTENTS_NONE_WAS_SELECTED_O.text())
  copies.minWith(compareBy<AppendCommit> { it.id !in preferredIds }.thenBy { it.id })
 }

internal fun CommitGraph.snapshotCommit(snapshotId: String): AppendCommit? =
 commits.values.filter { it.snapshot.id == snapshotId }.snapshotRepresentatives(tips).singleOrNull()

internal fun AppendConflict.snapshotBranch(snapshotId: String): AppendBranch? {
 val copies = branches.filter { it.snapshot.manifest.id == snapshotId }
 val selected = copies.minByOrNull { it.commit.id } ?: return null
 if (copies.any { it.snapshot.manifest != it.commit.snapshot ||
     it.snapshot.manifest != selected.snapshot.manifest || !it.snapshot.zip.contentEquals(selected.snapshot.zip) })
  throw RemoteIntegrityException(CoreMessages.BRANCHES_WITH_THE_SAME_SNAPSHOT_ID_HAVE_DIFFERENT_CONTENTS_NONE_WAS_SELECTED_OR_MERGE.text())
 return selected
}
