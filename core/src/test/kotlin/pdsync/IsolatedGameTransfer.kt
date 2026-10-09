package pdsync

import java.nio.file.Path
import kotlin.io.path.createDirectories

/** Opt-in test driver; never discovers or modifies a user's installed game paths. */
object IsolatedGameTransfer {
 @JvmStatic fun main(args: Array<String>) {
  require(args.size == 6 && args[0] == "--confirm-isolated-copies-game-stopped") {
   "Usage: --confirm-isolated-copies-game-stopped SOURCE TARGET STATE LOOPBACK_URL TARGET_GAME_JAR"
  }
  val source = Path.of(args[1]); val target = Path.of(args[2]); val state = Path.of(args[3])
  target.createDirectories()
  require(args[4].startsWith("http://127.0.0.1:")) { "This test driver only uses a local demo store" }
  val from = ProfileSession(AppConfig(source.toString(), args[4], "demo", targetGame = args[5]), state)
  val to = ProfileSession(AppConfig(target.toString(), args[4], "demo", targetGame = args[5]), state)
  val store = WebDav(args[4], "shattered", "demo", "demo", "demo")
  check(from.push(store) { true } is PushResult.Success) { "Unexpected conflict; no automatic resolution" }
  val remote = to.readRemote(store) ?: error("Remote not published")
  val result = to.restoreRemote(remote.manifest.id, store) { true }
  val actual = Snapshots.capture(target, Shattered, "demo", "verification", null)
  check(actual.manifest.files == remote.manifest.files)
  println("REAL_COPY_TRANSFER_OK ${remote.manifest.id} files=${actual.manifest.files.size} backup=${result.backupPath}")
  println(remote.manifest.toJson())
 }
}
