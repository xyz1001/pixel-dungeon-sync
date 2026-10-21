package pdsync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/** Explicit opt-in fixture transfer, not a claim of native game/UI runtime verification. */
object IsolatedDefaultPathTransfer {
 @JvmStatic fun main(args: Array<String>) {
  require(args.size == 5 && args[0] == "--isolated-default-path-fixtures")
  val base = Path.of(args[1]).toAbsolutePath(); val fixture = Path.of(args[2]).toAbsolutePath(); val jar = args[3]; val endpoint = args[4]
  require(endpoint.startsWith("http://127.0.0.1:")); require(!base.exists()) { "Use a fresh isolated fixture directory" }
  base.createDirectories(); SafePaths.disjoint(base, fixture)
  val fromLocation = DefaultProfileLocation(SavePathContext("Linux", base.resolve("A/home").toString(), mapOf("XDG_DATA_HOME" to base.resolve("A/data").toString())))
  val toLocation = DefaultProfileLocation(SavePathContext("Linux", base.resolve("B/home").toString(), emptyMap()))
  val fromRoot = fromLocation.resolve(); val toRoot = toLocation.resolve()
  Files.walk(fixture).use { files -> files.forEach { path ->
   require(!Files.isSymbolicLink(path)); val output = fromRoot.resolve(fixture.relativize(path).toString())
   if (Files.isDirectory(path)) output.createDirectories() else { output.parent.createDirectories(); Files.copy(path, output) }
  } }
  toRoot.createDirectories()
  val config = AppConfig(saveRoot = base.resolve("ignored-manual-path").toString(), endpoint = endpoint, profile = "demo", targetGame = jar, username = "demo")
  val source = fromLocation.open(config, base.resolve("state")); val target = toLocation.open(config, base.resolve("state"))
  val before = Snapshots.capture(fromRoot, Shattered, "demo", "fixture", null)
  val store = AppendOnlyWebDav(endpoint, "shattered", "demo", "demo", "demo")
  val setup = ConnectionSetup(config); setup.update(config, "demo")
  check(setup.testConnection() is ConnectionTestResult.Success)
  check(source.push(store) { true } is AppendPushResult.Success)
  val remote = target.readRemote(store).single().entry
  val restored = target.restoreRemote(remote.manifest.id, store) { true }
  check(Snapshots.capture(toRoot, Shattered, "demo", "fixture", null).manifest.files == before.manifest.files)
  check(Snapshots.capture(fromRoot, Shattered, "demo", "fixture", null).manifest.files == before.manifest.files)
  check(target.history().none { it.reason == "恢复前自动备份" })
  println("DEFAULT_PATH_REAL_FIXTURE_TRANSFER_OK files=${remote.manifest.files.size} versions=${remote.manifest.saveVersions?.summary()}")
  println("source=${source.root}\ntarget=${target.root}\nremote=${remote.manifest.id}\nbackup=none")
  println("Source/target bytes match; original fixture untouched. Native game launch and UI are not covered by this driver.")
 }
}
