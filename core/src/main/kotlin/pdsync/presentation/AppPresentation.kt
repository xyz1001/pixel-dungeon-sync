package pdsync.presentation

import pdsync.i18n.CoreMessages
/** Shared screen identities. PATH and COMPARISON remain readable for legacy navigation and standalone settings. */
enum class SyncPage { HOME, SETTINGS, DISCOVERY, PATH, CONNECTION, COMPARISON, CONFLICT, CONFIRM }

object AppPresentation {
 val DISCOVERY_TITLE get() = CoreMessages.TEXT_1_2_CHOOSE_GAME.text()
 val PATH_TITLE get() = CoreMessages.SAVE_LOCATION.text()
 val CONNECTION_TITLE get() = CoreMessages.TEXT_2_2_CONNECT_CLOUD.text()
 val COMPARISON_TITLE get() = CoreMessages.CHOOSE_SAVE.text()
 val COMPLETE_TITLE get() = CoreMessages.SETUP_COMPLETE.text()
 val HISTORY_TITLE get() = CoreMessages.COMPLETE_PROFILE_HISTORY.text()
 val AUTOMATIC_PAUSED get() = CoreMessages.AUTOMATIC_UPLOAD_IS_PAUSED_CHOOSE_UPLOAD_OR_RESTORE_FIRST.text()
 val CONFLICT_TITLE get() = CoreMessages.SAVE_CONFLICT.text()
 val CONFLICT_EXPLANATION get() = CoreMessages.OVERWRITE_THE_SELECTED_SNAPSHOT_BECOMES_THE_CURRENT_LOCAL_AND_CLOUD_VERSION_MERGE_PRE.text()
}
