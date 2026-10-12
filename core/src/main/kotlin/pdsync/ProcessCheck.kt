package pdsync

import java.nio.file.Paths

/** Read-only diagnostic entry point: no saves, credentials, cloud access, launches or process changes. */
fun main(args: Array<String>) {
 require(args.size == 1) { "Usage: pdsync.ProcessCheckKt <game.jar>" }
 val target = ShatteredVersions.target(Paths.get(args.single()))
 println("Identified game target: ${target.trusted}")
 val inventory = JvmProcessInventory().snapshot()
 val status = GameProcessDetector(target).status()
 println("Current-account visible process status: ${status::class.simpleName}")
 when (status) {
  is GameProcessStatus.Running -> println("Detected game process IDs: ${status.pids.sorted().joinToString()}")
  is GameProcessStatus.Unknown -> println(status.reason)
  GameProcessStatus.Stopped -> println("No game detected in the supported visible current-account scope; this is not a write lock.")
 }
 println("Inventory complete: ${inventory.complete}")
 println("Visible processes with unavailable executable identity: ${inventory.processes.filter { it.command.isNullOrBlank() }.map { it.pid }.sorted().joinToString().ifEmpty { "none" }}")
 println("No command lines, usernames, environment variables, file contents or credentials are included.")
}
