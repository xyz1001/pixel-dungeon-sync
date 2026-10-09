package pdsync
import java.nio.file.Files
import kotlin.io.path.*

/** Loopback-only isolated end-to-end harness; never accepts a real save path. */
fun main() {
 val root = Files.createTempDirectory("pds-e2e-")
 val backup = Files.createTempDirectory("pds-backup-")
 val state = Files.createTempDirectory("pds-state-")
 try {
  root.resolve("game1").createDirectories()
  root.resolve("game1/game.dat").writeText("synthetic-run")
  root.resolve("game1/depth1.dat").writeText("synthetic-floor")
  root.resolve("rankings.dat").writeText("synthetic-record")
  val store = AppendOnlyWebDav("http://127.0.0.1:8765", "shattered", "demo", "demo", "demo")
  val repository = AppendRepository(state, Shattered, "demo")
  check(repository.discover(store).also { it.requireSettled() }.commits.isEmpty()) { "Use a fresh isolated demo server directory" }
  fun publish(parents: List<String>, kind: String = "snapshot"): AppendCommit {
   val plan = repository.prepare(Snapshots.capture(root, Shattered, "demo", "synthetic-v1", null), parents, kind)
   val commit = repository.publish(store, plan)
   repository.setBaseline(commit.id, repository.discover(store)); repository.clearPending()
   return commit
  }
  val first = publish(emptyList())
  root.resolve("journal.dat").writeText("synthetic-journal")
  val second = publish(listOf(first.id))
  check(repository.discover(store).tips == setOf(second.id))
  val downloaded = repository.download(store, second)
  root.resolve("game1/game.dat").writeText("modified")
  val backupPath = Snapshots.restore(downloaded, root, backup, Shattered, "synthetic-v1", { true })
  check(root.resolve("game1/game.dat").readText() == "synthetic-run")
  check(root.resolve("journal.dat").readText() == "synthetic-journal")
  check(backupPath.exists())
  val sibling = publish(listOf(first.id))
  check(repository.discover(store).tips == setOf(second.id, sibling.id))
  val resolution = publish(listOf(second.id, sibling.id), "resolution")
  check(repository.discover(store).tips == setOf(resolution.id))
  check(!store.legacyPresent())
  println("PDSYNC_E2E_OK immutable-upload/readback/parent-DAG/restore/backup/retained-branches/resolution/no-head")
 } finally {
  listOf(root, backup, state).forEach { path -> Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } } }
 }
}
