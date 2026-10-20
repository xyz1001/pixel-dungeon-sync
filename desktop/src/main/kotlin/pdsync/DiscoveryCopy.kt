package pdsync

import pdsync.i18n.DesktopMessages

/** Presentation only: finding an installation or directory does not verify save contents. */
internal object DiscoveryCopy {
 fun game(result: InstallationDiscoveryResult?, selected: TargetGame?): String = when {
  selected?.trusted == true -> if (selected.minimumSaveVersion == null) DesktopMessages.RESTORING_IS_NOT_SUPPORTED_FOR_THIS_GAME_VERSION_YET.text() else ""
  result == null -> DesktopMessages.LOOKING_FOR_GAMES.text()
  result.incomplete -> DesktopMessages.SEARCH_INCOMPLETE_SELECT_THE_GAME_MANUALLY.text()
  result.trustedTargets.size > 1 -> DesktopMessages.SELECT_THE_GAME_TO_USE.text()
  result.trustedTargets.size == 1 -> DesktopMessages.PLEASE_CONFIRM_THE_GAME.text()
  result.candidates.isNotEmpty() -> DesktopMessages.GAME_NOT_RECOGNIZED_SELECT_ANOTHER_FILE.text()
  else -> DesktopMessages.NO_GAME_FOUND_SELECT_ONE_MANUALLY.text()
 }

 fun directory(save: DefaultSaveDiscovery?): String = when {
  save == null -> DesktopMessages.LOCAL_SAVES_NOT_CHECKED.text()
  save.available -> DesktopMessages.LOCAL_SAVES_FOLDER_FOUND_CONTENTS_NOT_CHECKED.text()
  save.reasonCode == DefaultSaveDiscoveryReason.MISSING -> DesktopMessages.LOCAL_SAVES_FOLDER_NOT_CREATED_YET.text()
  else -> DesktopMessages.LOCAL_SAVES_CANNOT_CHECK_RIGHT_NOW.text()
 }
}
