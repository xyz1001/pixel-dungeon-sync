package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Stopped is scoped to the current OS account's selected trusted JAR and visible local processes. */
sealed interface GameProcessStatus {
 data object Stopped : GameProcessStatus
 data class Running(val pids: Set<Long>) : GameProcessStatus
 data class Unknown(val reason: String) : GameProcessStatus
}

enum class GameProcessPlatform {
 LINUX, WINDOWS, MAC, UNSUPPORTED;
 companion object {
  fun current(): GameProcessPlatform {
   val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
   return when { os.startsWith("linux") -> LINUX; os.startsWith("windows") -> WINDOWS;
    os.startsWith("mac") || os.startsWith("darwin") -> MAC; else -> UNSUPPORTED }
  }
 }
}

/** Null command/arguments mean unavailable, not empty. Never render these values in UI or logs. */
data class ObservedGameProcess(
 val pid: Long, val command: String?, val arguments: List<String>?,
 val workingDirectory: String? = null, val alive: Boolean = true,
 val startToken: String? = null, val owner: String? = null
)
data class ProcessInventorySnapshot(val processes: List<ObservedGameProcess>, val complete: Boolean = true, val currentOwner: String? = null)
fun interface ProcessInventory { fun snapshot(): ProcessInventorySnapshot }

/** Reads only executable, argv, start time, and Linux cwd. No environment or full command line. */
class JvmProcessInventory @JvmOverloads constructor(
 private val platform: GameProcessPlatform = GameProcessPlatform.current(),
 private val linuxArguments: (Long) -> List<String>? = LinuxProcessArguments::read
) : ProcessInventory {
 override fun snapshot(): ProcessInventorySnapshot = try {
  var complete = true
  val currentOwner = ProcessHandle.current().info().user().orElse(null)?.takeIf { it.isNotBlank() }
  val result = mutableListOf<ObservedGameProcess>()
  ProcessHandle.allProcesses().use { stream ->
   val iterator = stream.iterator()
   while (iterator.hasNext()) {
    if (result.size >= 32_768) { complete = false; break }
    val process = iterator.next()
    if (process.pid() == ProcessHandle.current().pid() || !process.isAlive) continue
    try {
     val info = process.info()
     val owner = info.user().orElse(null)?.takeIf { it.isNotBlank() }
     // Positively different owners do not use this account's default save directory. Keep the
     // scope evidence without requesting their argv. Unknown owners are never skipped here.
     if (currentOwner != null && owner != null && !sameOwner(currentOwner, owner, platform)) {
      result += ObservedGameProcess(process.pid(), null, null, owner = owner)
      continue
     }
     val command = info.command().orElse(null)
     var args = info.arguments().orElse(null)?.toList()
     if (args == null && mayReadLinuxJavaArguments(command, currentOwner, owner, platform)) {
      val start = info.startInstant().orElse(null)
      if (start != null) {
       val fallback = linuxArguments(process.pid())
       val after = process.info()
       // Kernel stat start ticks bracket the argv read; the same live JDK handle, executable
       // and start instant must also survive the read. Missing identity remains unknown.
       if (process.isAlive && after.startInstant().orElse(null) == start && after.command().orElse(null) == command &&
        after.user().orElse(null) == owner) args = fallback
      }
     }
     val cwd = if (platform == GameProcessPlatform.LINUX && isJavaExecutable(command, platform)) {
      try { Files.readSymbolicLink(Path.of("/proc", process.pid().toString(), "cwd")).toString() }
      catch (_: Exception) { null }
     } else null
     // A process exiting during inspection is harmless. An unreadable live process is not.
     if (process.isAlive) result += ObservedGameProcess(process.pid(), command, args, cwd,
      startToken = info.startInstant().orElse(null)?.toString(), owner = owner)
    } catch (_: Exception) {
     if (process.isAlive) result += ObservedGameProcess(process.pid(), null, null)
    }
   }
  }
  ProcessInventorySnapshot(result, complete, currentOwner)
 } catch (_: Exception) { ProcessInventorySnapshot(emptyList(), false) }
}

private fun executableName(command: String?) = command?.replace('\\', '/')?.substringAfterLast('/')
private fun sameOwner(first: String, second: String, platform: GameProcessPlatform) =
 first.equals(second, ignoreCase = platform == GameProcessPlatform.WINDOWS)
private fun isJavaExecutable(command: String?, platform: GameProcessPlatform): Boolean {
 val name = executableName(command) ?: return false
 return if (platform == GameProcessPlatform.WINDOWS) name.lowercase(Locale.ROOT) in setOf("java.exe", "javaw.exe", "java", "javaw")
 else name in setOf("java", "javaw")
}
internal fun mayReadLinuxJavaArguments(command: String?, currentOwner: String?, owner: String?, platform: GameProcessPlatform): Boolean =
 platform == GameProcessPlatform.LINUX && !currentOwner.isNullOrBlank() && !owner.isNullOrBlank() && currentOwner == owner && isJavaExecutable(command, platform)

/**
 * ProcessHandle visibility differs by OS and privileges. Unknown always blocks writes; no process
 * is killed, launched, or attached. Native packages are deliberately not accepted as identified
 * installations. Only positively different OS accounts are excluded; missing owner information
 * never excuses an unreadable game candidate. Other accounts, elevated processes and Steam Cloud
 * can still write files, and these observations cannot exclude a process starting afterwards.
 */
class GameProcessDetector(
 private val target: TargetGame?,
 private val inventory: ProcessInventory = JvmProcessInventory(),
 private val platform: GameProcessPlatform = GameProcessPlatform.current(),
 private val selfPid: Long = ProcessHandle.current().pid(),
 private val resolvePath: (String, String?, GameProcessPlatform) -> String? = ::resolveGameProcessPath
) {
 fun isStopped(): Boolean = status() == GameProcessStatus.Stopped

 fun status(): GameProcessStatus {
  if (platform == GameProcessPlatform.UNSUPPORTED) return GameProcessStatus.Unknown("当前系统不支持自动进程检查")
  if (target == null || !target.trusted) return GameProcessStatus.Unknown("请先选择已验证的官方游戏 JAR")
  val targetPath = resolvePath(target.path, null, platform)
   ?: return GameProcessStatus.Unknown("无法确认所选游戏 JAR 的路径")
  val observations = try { listOf(inventory.snapshot(), inventory.snapshot()) }
   catch (_: Exception) { return GameProcessStatus.Unknown("无法完整读取游戏进程状态") }
  val running = linkedSetOf<Long>()
  var uncertain = observations.any { !it.complete }
  val relevant = mutableListOf<Map<Long, Pair<String?, Match>>>()
  observations.forEach { sample ->
   val seen = mutableMapOf<Long, Pair<String?, Match>>()
   sample.processes.filter { it.alive && it.pid != selfPid }.forEach processLoop@ { process ->
    if (sample.currentOwner != null && process.owner != null && !sameOwner(sample.currentOwner, process.owner, platform)) return@processLoop
    val match = identify(process, targetPath)
    if (match == Match.RUNNING) running += process.pid
    if (match == Match.UNKNOWN) uncertain = true
    if (match != Match.UNRELATED) seen[process.pid] = process.startToken to match
   }
   relevant += seen
  }
  if (running.isNotEmpty()) return GameProcessStatus.Running(running)
  if (uncertain || relevant[0] != relevant[1]) return GameProcessStatus.Unknown("无法确认游戏已退出：部分进程或启动参数不可见，请关闭游戏后重试")
  return GameProcessStatus.Stopped
 }

 private enum class Match { RUNNING, UNKNOWN, UNRELATED }
 private fun identify(process: ObservedGameProcess, targetPath: String): Match {
  val command = process.command?.takeIf { it.isNotBlank() } ?: return Match.UNKNOWN
  val executable = executableName(command)!!.lowercase(Locale.ROOT)
  if (!isJavaExecutable(command, platform)) {
   // Candidate native launcher names are a block, never a claim of verified native support.
   val compact = command.lowercase(Locale.ROOT).removeSuffix(".exe").replace(Regex("[ _-]"), "")
   val possibleJavaWrapper = process.arguments?.any { it == "-jar" || it == SHATTERED_MAIN || it.startsWith('@') } == true
   return if (compact.contains("shatteredpixel") || executable.removeSuffix(".exe") == "shatteredpd" || possibleJavaWrapper) Match.UNKNOWN else Match.UNRELATED
  }
  val args = process.arguments ?: return Match.UNKNOWN
  if (args.isEmpty() || args.size > 16_384 || args.any { it.length > 32_768 }) return Match.UNKNOWN
  var index = 0
  while (index < args.size) {
   val arg = args[index++]
   if (arg.startsWith('@')) return Match.UNKNOWN // @argfile is not expanded or read.
   if (arg == "-jar") {
    val jar = args.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return Match.UNKNOWN
    val resolved = resolvePath(jar, process.workingDirectory, platform) ?: return Match.UNKNOWN
    if (resolved == targetPath) return Match.RUNNING
    // Arbitrarily renamed/copied game JARs can share the default saves. A different filename
    // does not establish another application's identity, so all unidentified JARs block.
    return Match.UNKNOWN
   }
   if (arg == "-m" || arg == "--module") {
    val main = args.getOrNull(index) ?: return Match.UNKNOWN
    return if (main.substringAfter('/') == SHATTERED_MAIN) Match.RUNNING else Match.UNKNOWN
   }
   if (arg in OPTIONS_WITH_VALUE) {
    if (index >= args.size) return Match.UNKNOWN
    index++
   } else if (arg.startsWith("--class-path=") || arg.startsWith("--module-path=") ||
    arg.startsWith("--add-") && arg.contains('=') || arg.startsWith("--enable-native-access=")) {
    // Known Java launcher options with values in the same argument.
   } else if (arg.startsWith("-D") || arg.startsWith("-X") || arg in FLAG_OPTIONS ||
    arg.startsWith("-ea:") || arg.startsWith("-da:")) {
    // JVM options cannot change the launch target parsed below.
   } else if (arg.startsWith('-')) return Match.UNKNOWN
   else return if (arg == SHATTERED_MAIN) Match.RUNNING else Match.UNRELATED
  }
  return Match.UNKNOWN
 }

 companion object {
  const val SHATTERED_MAIN = "com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher"
  private val OPTIONS_WITH_VALUE = setOf("-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path",
   "--add-modules", "--limit-modules", "--add-exports", "--add-opens", "--add-reads", "--patch-module", "--enable-native-access")
  private val FLAG_OPTIONS = setOf("-ea", "-enableassertions", "-da", "-disableassertions", "-esa", "-enablesystemassertions",
   "-dsa", "-disablesystemassertions", "-server", "-client", "--enable-preview", "-showversion", "--show-version")
 }
}

/** Windows lexical normalization is also testable on Linux; native hosts additionally resolve aliases. */
internal fun resolveGameProcessPath(value: String, cwd: String?, platform: GameProcessPlatform): String? {
 return try {
 if (value.isBlank() || value.any { it == '\u0000' || it == '\n' || it == '\r' }) null
 else if (platform == GameProcessPlatform.WINDOWS) {
  val slash = value.replace('\\', '/')
  fun absolute(path: String) = Regex("^[A-Za-z]:/.*").matches(path) || path.startsWith("//")
  val full = if (absolute(slash)) slash else if (cwd != null && absolute(cwd.replace('\\', '/')) && !slash.contains(':') && !slash.startsWith('/'))
   cwd.replace('\\', '/') + "/" + slash else return null
  val parts = mutableListOf<String>()
  full.split('/').filter { it.isNotEmpty() }.forEach { part ->
   when (part) { "." -> Unit; ".." -> { if (parts.size <= if (full.startsWith("//")) 2 else 1) return null; parts.removeAt(parts.lastIndex) }; else -> parts += part }
  }
  val normalized = (if (full.startsWith("//")) "//" else "") + parts.joinToString("/")
  val canonical = if (GameProcessPlatform.current() == GameProcessPlatform.WINDOWS)
   Path.of(normalized).toRealPath().toString().replace('\\', '/') else normalized
  canonical.lowercase(Locale.ROOT)
 } else {
  val path = Path.of(value)
  val absolute = if (path.isAbsolute) path else cwd?.let { Path.of(it).takeIf(Path::isAbsolute)?.resolve(path) } ?: return null
  absolute.toRealPath().toString()
 }
 } catch (_: Exception) { null }
}
