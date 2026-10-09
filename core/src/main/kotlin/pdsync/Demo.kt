package pdsync
import java.nio.file.Files
import kotlin.io.path.*

/** Loopback-only isolated end-to-end harness; never accepts a real save path. */
fun main() {
 val root=Files.createTempDirectory("pds-e2e-")
 val backup=Files.createTempDirectory("pds-backup-")
 try {
  root.resolve("game1").createDirectories()
  root.resolve("game1/game.dat").writeText("synthetic-run")
  root.resolve("game1/depth1.dat").writeText("synthetic-floor")
  root.resolve("rankings.dat").writeText("synthetic-record")
  val store=WebDav("http://127.0.0.1:8765","shattered","demo","demo","demo")
  check(store.head() == null) { "Use a fresh isolated demo server directory" }
  val first=Snapshots.capture(root,Shattered,"demo","synthetic-v1",null)
  Sync(store).push(first)
  root.resolve("journal.dat").writeText("synthetic-journal")
  val second=Snapshots.capture(root,Shattered,"demo","synthetic-v1",first.manifest.id)
  Sync(store).push(second)
  check(store.head()!!.manifest == second.manifest)
  val downloaded=store.download(second.manifest)
  root.resolve("game1/game.dat").writeText("modified")
  val backupPath=Snapshots.restore(downloaded,root,backup,Shattered,"synthetic-v1",{true})
  check(root.resolve("game1/game.dat").readText() == "synthetic-run")
  check(root.resolve("journal.dat").readText() == "synthetic-journal")
  check(backupPath.exists())
  try { Sync(store).push(first); error("Expected conflict") } catch(_: Conflict) {}
  println("PDSYNC_E2E_OK upload/readback/conditional-parent/restore/backup/offline-conflict")
 } finally {
  listOf(root,backup).forEach { p -> Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } } }
 }
}
