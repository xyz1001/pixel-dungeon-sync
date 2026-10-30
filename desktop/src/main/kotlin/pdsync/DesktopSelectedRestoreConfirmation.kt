package pdsync

/** A HOME card is an explicit source only while it still belongs to the observed cloud graph. */
internal fun desktopHasExplicitCloudRestoreSelection(history: SyncHistory?, selected: String?, selectedByUser: Boolean) =
 selectedByUser && selected != null && history?.cloud?.any { it.snapshot.id == selected } == true

/** Bind overwrite consent to the exact account/session, observation and source displayed. */
internal class DesktopSelectedRestoreConfirmation(
 val snapshotId: String,
 private val session: Any?,
 private val config: AppConfig?,
 private val history: SyncHistory?
) {
 fun isCurrent(session: Any?, config: AppConfig?, history: SyncHistory?, selected: String?, selectedByUser: Boolean) =
  this.session === session && this.config == config && this.history === history &&
   selectedByUser && snapshotId == selected && desktopHasExplicitCloudRestoreSelection(history, selected, selectedByUser)
}
