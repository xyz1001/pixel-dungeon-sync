package pdsync

import pdsync.i18n.CoreMessages
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import java.nio.file.Path
import java.nio.file.Paths
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
      try { Files.readSymbolicLink(Paths.get("/proc", process.pid().toString(), "cwd")).toString() }
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
 * installations. The supported game is an ordinary-permission process: an opaque executable
 * without game association is outside scope, not a global blocker. Missing information on a
 * Java or remembered game candidate still blocks. Other accounts, elevated processes and Steam Cloud
 * can still write files, and these observations cannot exclude a process starting afterwards.
 */
class GameProcessDetector(
 private val target: TargetGame?,
 private val inventory: ProcessInventory = JvmProcessInventory(),
 private val platform: GameProcessPlatform = GameProcessPlatform.current(),
 private val selfPid: Long = ProcessHandle.current().pid(),
 private val resolvePath: (String, String?, GameProcessPlatform) -> String? = ::resolveGameProcessPath
) {
 private data class Association(val startToken: String?, val owner: String?, val scopeOwner: String?)
 // One detector belongs to one immutable target/platform. Main reuses it only for that target.
 private val associated = linkedMapOf<Long, Association>()
 fun isStopped(): Boolean = status() == GameProcessStatus.Stopped

 @Synchronized fun status(): GameProcessStatus {
  if (platform == GameProcessPlatform.UNSUPPORTED) return GameProcessStatus.Unknown(CoreMessages.AUTOMATIC_PROCESS_CHECKS_ARE_NOT_SUPPORTED_ON_THIS_SYSTEM.text())
  if (target == null) return GameProcessStatus.Unknown(CoreMessages.FIRST_SELECT_A_SHATTERED_GAME_JAR_WITH_A_RECOGNIZABLE_VERSION.text())
  val targetPath = resolvePath(target.path, null, platform)
   ?: return GameProcessStatus.Unknown(CoreMessages.CANNOT_VERIFY_THE_SELECTED_GAME_JAR_S_PATH.text())
  val observations = try { listOf(inventory.snapshot(), inventory.snapshot()) }
   catch (_: Exception) { return GameProcessStatus.Unknown(CoreMessages.CANNOT_FULLY_READ_GAME_PROCESS_STATUS.text()) }
  val running = linkedSetOf<Long>()
  var uncertain = observations.any { !it.complete }
  val relevant = mutableListOf<Map<Long, Pair<String?, Match>>>()
  observations.forEach { sample ->
   val seen = mutableMapOf<Long, Pair<String?, Match>>()
   sample.processes.filter { it.alive && it.pid != selfPid }.forEach processLoop@ { process ->
    if (sample.currentOwner != null && process.owner != null && !sameOwner(sample.currentOwner, process.owner, platform)) {
     associated.remove(process.pid)
     return@processLoop
    }
    val previous = associated[process.pid]
    if (previous != null && (
     previous.startToken != null && process.startToken != null && previous.startToken != process.startToken ||
     previous.owner != null && process.owner != null && !sameOwner(previous.owner, process.owner, platform) ||
     previous.scopeOwner != null && sample.currentOwner != null && !sameOwner(previous.scopeOwner, sample.currentOwner, platform))) associated.remove(process.pid)
    val observed = identify(process, targetPath)
    val match = if (observed == Match.UNRELATED && process.pid in associated) Match.UNKNOWN else observed
    if (observed == Match.RUNNING || observed == Match.UNKNOWN && visiblyAssociated(process, targetPath)) {
     if (process.pid !in associated && associated.size >= 32_768) uncertain = true
     else associated[process.pid] = Association(process.startToken ?: associated[process.pid]?.startToken,
      process.owner ?: associated[process.pid]?.owner, sample.currentOwner ?: associated[process.pid]?.scopeOwner)
    }
    if (match == Match.RUNNING) running += process.pid
    if (match == Match.UNKNOWN) uncertain = true
    if (match != Match.UNRELATED) seen[process.pid] = process.startToken to match
   }
   relevant += seen
  }
  // Only a complete last observation may forget an exited game. No global/cross-target cache.
  if (observations.last().complete) {
   val alive = observations.last().processes.filter { it.alive }.mapTo(hashSetOf()) { it.pid }
   associated.keys.retainAll(alive)
  }
  if (running.isNotEmpty()) return GameProcessStatus.Running(running)
  if (uncertain || relevant[0] != relevant[1]) return GameProcessStatus.Unknown(CoreMessages.CANNOT_CONFIRM_THAT_THE_GAME_HAS_EXITED_SOME_PROCESSES_OR_LAUNCH_ARGUMENTS_ARE_HIDDEN.text())
  return GameProcessStatus.Stopped
 }

 private enum class Match { RUNNING, UNKNOWN, UNRELATED }
 private fun identify(process: ObservedGameProcess, targetPath: String): Match {
  val command = process.command?.takeIf { it.isNotBlank() }
   ?: return if (visiblyAssociated(process, targetPath)) Match.UNKNOWN else Match.UNRELATED
  if (!isJavaExecutable(command, platform)) {
   // Candidate native launcher names are a block, never a claim of verified native support.
   return if (visiblyAssociated(process, targetPath)) Match.UNKNOWN else Match.UNRELATED
  }
  val args = process.arguments ?: return Match.UNKNOWN
  if (args.isEmpty() || args.size > 16_384 || args.any { it.length > 32_768 }) return Match.UNKNOWN
  var index = 0
  while (index < args.size) {
   val arg = args[index++]
   if (arg.startsWith('@')) return Match.UNKNOWN // @argfile is not expanded or read.
   if (arg == "-jar") {
    val jar = args.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return Match.UNKNOWN
    val associatedName = gameJarName(jar, targetPath)
    val resolved = resolvePath(jar, process.workingDirectory, platform)
     ?: return if (associatedName) Match.UNKNOWN else Match.UNRELATED
    if (resolved == targetPath) return Match.RUNNING
    return when (inspectGameProcessJarIdentity(resolved)) {
     GameProcessJarIdentity.SHATTERED -> Match.RUNNING
     GameProcessJarIdentity.OTHER -> Match.UNRELATED
     GameProcessJarIdentity.UNIDENTIFIED -> if (associatedName) Match.UNKNOWN else Match.UNRELATED
    }
   }
   if (arg == "-m" || arg == "--module") {
    val main = args.getOrNull(index) ?: return Match.UNKNOWN
    return if (main.substringAfter('/') == SHATTERED_MAIN) Match.RUNNING else if (!target!!.trusted) Match.UNKNOWN else Match.UNRELATED
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
   else return if (arg == SHATTERED_MAIN) Match.RUNNING else if (!target!!.trusted) Match.UNKNOWN else Match.UNRELATED
  }
  return Match.UNKNOWN
 }

 private fun gameJarName(value: String, targetPath: String): Boolean {
  val name = executableName(value)?.lowercase(Locale.ROOT) ?: return false
  return name == targetPath.substringAfterLast('/').lowercase(Locale.ROOT) || name.contains("shattered")
 }
 private fun visiblyAssociated(process: ObservedGameProcess, targetPath: String): Boolean {
  val command = process.command.orEmpty().lowercase(Locale.ROOT)
  val compact = command.replace(Regex("[ _-]"), "")
  if (compact.contains("shatteredpixel") || executableName(command)?.removeSuffix(".exe") == "shatteredpd") return true
  val args = process.arguments ?: return false
  if (args.size > 16_384 || args.any { it.length > 32_768 }) return false
  if (args.any { it == SHATTERED_MAIN || it.substringAfter('/') == SHATTERED_MAIN }) return true
  return args.zipWithNext().any { (flag, value) -> flag == "-jar" &&
   (gameJarName(value, targetPath) || resolvePath(value, process.workingDirectory, platform) == targetPath) }
 }

 companion object {
  const val SHATTERED_MAIN = "com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher"
  private val OPTIONS_WITH_VALUE = setOf("-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path",
   "--add-modules", "--limit-modules", "--add-exports", "--add-opens", "--add-reads", "--patch-module", "--enable-native-access")
  private val FLAG_OPTIONS = setOf("-ea", "-enableassertions", "-da", "-disableassertions", "-esa", "-enablesystemassertions",
   "-dsa", "-disablesystemassertions", "-server", "-client", "--enable-preview", "-showversion", "--show-version")
 }
}

private enum class GameProcessJarIdentity { SHATTERED, OTHER, UNIDENTIFIED }

/** Static bounded manifest inspection only. No game code, signatures, or services are loaded. */
private fun inspectGameProcessJarIdentity(value: String): GameProcessJarIdentity {
 return try {
  val path = Paths.get(value)
  val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
  if (!before.isRegularFile || before.size() > 256L * 1024 * 1024) return GameProcessJarIdentity.UNIDENTIFIED
  var bytes: ByteArray? = null
  ZipFile(path.toFile(), StandardCharsets.UTF_8).use { zip ->
   val entries = zip.entries(); var count = 0
   while (entries.hasMoreElements()) {
    val entry = entries.nextElement()
    if (++count > 50_000 || entry.name.length > 4096) return GameProcessJarIdentity.UNIDENTIFIED
    if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) {
     if (bytes != null || entry.isDirectory || entry.size > 64 * 1024) return GameProcessJarIdentity.UNIDENTIFIED
     bytes = zip.getInputStream(entry).use { it.readUpTo(64 * 1024 + 1) }
    }
   }
  }
  val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
  if (before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() || before.fileKey() != after.fileKey()) return GameProcessJarIdentity.UNIDENTIFIED
  val raw = bytes ?: return GameProcessJarIdentity.UNIDENTIFIED
  if (raw.size > 64 * 1024) return GameProcessJarIdentity.UNIDENTIFIED
  val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
   .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
  val attributes = linkedMapOf<String, String>(); var last: String? = null
  for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
   if (line.isEmpty()) break
   if (line.startsWith(' ')) {
    val key = last ?: return GameProcessJarIdentity.UNIDENTIFIED
    attributes[key] = attributes.getValue(key) + line.drop(1)
   } else {
    val colon = line.indexOf(": ")
    if (colon <= 0) return GameProcessJarIdentity.UNIDENTIFIED
    val key = line.substring(0, colon).lowercase(Locale.ROOT)
    if (!key.matches(Regex("[a-z0-9_-]+")) || key in attributes) return GameProcessJarIdentity.UNIDENTIFIED
    attributes[key] = line.substring(colon + 2); last = key
   }
  }
  val main = attributes["main-class"]?.trim() ?: return GameProcessJarIdentity.UNIDENTIFIED
  if (main == GameProcessDetector.SHATTERED_MAIN) return GameProcessJarIdentity.SHATTERED
  if (attributes["implementation-title"] == "com.shatteredpixel.shatteredpixeldungeon" ||
   attributes["specification-title"] == "Shattered Pixel Dungeon") return GameProcessJarIdentity.SHATTERED
  if (main.length > 512 || !main.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) GameProcessJarIdentity.UNIDENTIFIED
  else GameProcessJarIdentity.OTHER
 } catch (_: Exception) { GameProcessJarIdentity.UNIDENTIFIED }
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
   Paths.get(normalized).toRealPath().toString().replace('\\', '/') else normalized
  canonical.lowercase(Locale.ROOT)
 } else {
  val path = Paths.get(value)
  val absolute = if (path.isAbsolute) path else cwd?.let { Paths.get(it).takeIf(Path::isAbsolute)?.resolve(path) } ?: return null
  absolute.toRealPath().toString()
 }
 } catch (_: Exception) { null }
}
