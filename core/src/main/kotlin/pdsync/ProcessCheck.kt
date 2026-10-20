package pdsync

import pdsync.i18n.CoreMessages
import java.nio.file.Paths

/** Read-only diagnostic entry point: no saves, credentials, cloud access, launches or process changes. */
fun main(args: Array<String>) {
 require(args.size == 1) { "Usage: pdsync.ProcessCheckKt <game.jar>" }
 val target = ShatteredVersions.target(Paths.get(args.single()))
 println(CoreMessages.VALIDATION_IDENTIFIED_GAME_TARGET_1_S.text(target.trusted))
 val inventory = JvmProcessInventory().snapshot()
 val status = GameProcessDetector(target).status()
 println(CoreMessages.VALIDATION_CURRENT_ACCOUNT_VISIBLE_PROCESS_STATUS_1_S.text(status::class.simpleName))
 when (status) {
  is GameProcessStatus.Running -> println(CoreMessages.VALIDATION_DETECTED_GAME_PROCESS_IDS_1_S.text(status.pids.sorted().joinToString()))
  is GameProcessStatus.Unknown -> println(status.reason)
  GameProcessStatus.Stopped -> println(CoreMessages.VALIDATION_NO_GAME_DETECTED_IN_THE_SUPPORTED_VISIBLE_CURRENT_ACCOUNT_SCOPE_THIS_IS_NOT_A_WRITE_LOCK.text())
 }
 println(CoreMessages.VALIDATION_INVENTORY_COMPLETE_1_S.text(inventory.complete))
 println(CoreMessages.VALIDATION_VISIBLE_PROCESSES_WITH_UNAVAILABLE_EXECUTABLE_IDENTITY_1_S.text(inventory.processes.filter { it.command.isNullOrBlank() }.map { it.pid }.sorted().joinToString().ifEmpty { "none" }))
 println(CoreMessages.VALIDATION_NO_COMMAND_LINES_USERNAMES_ENVIRONMENT_VARIABLES_FILE_CONTENTS_OR_CREDENTIALS_ARE_INCLUDED.text())
}
