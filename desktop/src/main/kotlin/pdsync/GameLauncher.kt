package pdsync

import pdsync.i18n.DesktopMessages

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class GameLaunchPlan(val command: List<String>, val directory: Path)

internal sealed interface GameLaunchResult {
 val message: String
 data class Started(val target: TargetGame, val pid: Long) : GameLaunchResult {
  override val message get() = DesktopMessages.GAME_STARTED.text()
 }
 data class AlreadyRunning(val status: GameProcessStatus.Running) : GameLaunchResult {
  override val message get() = DesktopMessages.THE_GAME_IS_ALREADY_RUNNING.text()
 }
 data object Busy : GameLaunchResult { override val message get() = DesktopMessages.STARTING_THE_GAME_PLEASE_WAIT.text() }
 data class Failed(override val message: String) : GameLaunchResult
}

/** Never treats the application's native launcher as java or asks a shell to open a file. */
internal fun gameJavaExecutable(
 javaHome: String? = System.getProperty("java.home"),
 environment: Map<String, String> = System.getenv(),
 platform: GameProcessPlatform = GameProcessPlatform.current(),
 usable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) }
): Path? {
 val names = if (platform == GameProcessPlatform.WINDOWS) listOf("javaw.exe", "java.exe") else listOf("java")
 fun environmentValue(name: String): String? = if (platform == GameProcessPlatform.WINDOWS)
  environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value else environment[name]
 val candidates = mutableListOf<Path>()
 fun addDirectory(value: String?) {
  if (value.isNullOrBlank()) return
  runCatching { Path.of(value).takeIf { it.isAbsolute }?.let { base -> names.forEach { candidates.add(base.resolve(it)) } } }
 }
 listOf(javaHome, environmentValue("JAVA_HOME")).distinct().forEach { home ->
  if (!home.isNullOrBlank()) runCatching { addDirectory(Path.of(home).resolve("bin").toString()) }
 }
 // A stripped jpackage runtime can lack bin/java. Only explicit absolute PATH entries
 // are eligible; never execute java from the working directory or use file associations.
 environmentValue("PATH")?.split(if (platform == GameProcessPlatform.WINDOWS) ';' else ':')?.take(64)?.forEach(::addDirectory)
 return candidates.distinct().firstOrNull { runCatching { usable(it) }.getOrDefault(false) }
}

internal fun gameLaunchPlan(target: TargetGame, java: Path, context: SavePathContext, platform: GameProcessPlatform): GameLaunchPlan {
 require(platform != GameProcessPlatform.UNSUPPORTED)
 require(target.trusted)
 val jar = Path.of(target.path)
 require(jar.isAbsolute && java.isAbsolute)
 val home = requireNotNull(context.userHome)
 require(home.isNotBlank() && '\u0000' !in home)
 return GameLaunchPlan(buildList {
  add(java.toString())
  if (platform == GameProcessPlatform.MAC) add("-XstartOnFirstThread")
  // Match the sync client's effective JVM home, even in an isolated/portable invocation.
  add("-Duser.home=$home")
  add("-jar"); add(jar.toString())
 }, requireNotNull(jar.parent))
}

/** Blocking work belongs on Dispatchers.IO. The guard also protects non-UI/repeated callers. */
internal class GameLauncher(
 private val processStatus: (TargetGame?) -> GameProcessStatus,
 private val context: SavePathContext = SavePathContext.current(),
 private val platform: GameProcessPlatform = GameProcessPlatform.current(),
 private val inspect: (Path) -> TargetGame? = { ShatteredInstallationDiscovery().validateJar(it).target },
 private val javaExecutable: () -> Path? = { gameJavaExecutable() },
 private val start: (GameLaunchPlan) -> Process = { plan ->
  ProcessBuilder(plan.command).directory(plan.directory.toFile())
   .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
   .start().also { process -> runCatching { process.outputStream.close() } }
 },
 private val startupMillis: Long = 1500
) {
 private val starting = AtomicBoolean(false)
 @Volatile private var child: Process? = null

 fun status(target: TargetGame?): GameProcessStatus {
  child?.let { if (it.isAlive) return GameProcessStatus.Running(setOf(it.pid())) }
  return processStatus(target)
 }

 fun launch(configuredJar: String, selectedSaveRoot: String?): GameLaunchResult {
  if (!starting.compareAndSet(false, true)) return GameLaunchResult.Busy
  try {
   val target = try {
    val path = Path.of(configuredJar)
    require(configuredJar.isNotBlank() && path.isAbsolute && path.fileName.toString().endsWith(".jar", true))
    inspect(path)?.takeIf { it.trusted } ?: return GameLaunchResult.Failed(DesktopMessages.GAME_FILE_UNAVAILABLE_SELECT_THE_GAME_AGAIN_IN_SETTINGS.text())
   } catch (_: Exception) { return GameLaunchResult.Failed(DesktopMessages.GAME_FILE_UNAVAILABLE_SELECT_THE_GAME_AGAIN_IN_SETTINGS.text()) }
   when (val state = status(target)) {
    is GameProcessStatus.Running -> return GameLaunchResult.AlreadyRunning(state)
    is GameProcessStatus.Unknown -> return GameLaunchResult.Failed(DesktopMessages.CANNOT_CONFIRM_GAME_STATUS_CLOSE_THE_GAME_AND_TRY_AGAIN.text())
    GameProcessStatus.Stopped -> Unit
   }
   if (platform == GameProcessPlatform.UNSUPPORTED) return GameLaunchResult.Failed(DesktopMessages.LAUNCHING_THE_GAME_IS_NOT_SUPPORTED_ON_THIS_SYSTEM.text())
   // No game save-directory override exists. Do not silently launch against another profile.
   try {
    val location = DefaultProfileLocation(context)
    val defaultRoot = SafePaths.checked(location.resolve())
    val selected = selectedSaveRoot?.let { location.requireSelected(it, false) } ?: defaultRoot
    if (selected != defaultRoot) return GameLaunchResult.Failed(DesktopMessages.THE_SELECTED_SAVE_FOLDER_IS_NOT_THE_GAME_S_DEFAULT_LOCATION_CHECK_THE_PATH.text())
   } catch (_: Exception) { return GameLaunchResult.Failed(DesktopMessages.CANNOT_CONFIRM_THE_GAME_S_SAVE_LOCATION_CHECK_THE_PATH_IN_SETTINGS.text()) }
   val java = javaExecutable() ?: return GameLaunchResult.Failed(DesktopMessages.JAVA_LAUNCHER_NOT_FOUND_INSTALL_JAVA_17_OR_UPDATE_THE_SYNC_APP.text())
   val process = try { start(gameLaunchPlan(target, java, context, platform)) }
    catch (_: Exception) { return GameLaunchResult.Failed(DesktopMessages.LAUNCH_FAILED_CHECK_JAVA_OR_SELECT_THE_GAME_AGAIN_AND_RETRY.text()) }
   child = process
   // Detect immediate JVM/native-library failures without blocking the UI or piping output.
   if (process.waitFor(startupMillis, TimeUnit.MILLISECONDS)) {
    child = null
    return GameLaunchResult.Failed(DesktopMessages.THE_GAME_EXITED_AFTER_LAUNCH_CHECK_JAVA_OR_SELECT_THE_GAME_AGAIN_AND_RETRY.text())
   }
   return GameLaunchResult.Started(target, process.pid())
  } catch (_: InterruptedException) {
   Thread.currentThread().interrupt()
   return GameLaunchResult.Failed(DesktopMessages.LAUNCH_CHECK_INTERRUPTED_CHECK_THE_GAME_WINDOW_AND_TRY_AGAIN.text())
  } catch (_: Exception) {
   return GameLaunchResult.Failed(DesktopMessages.LAUNCH_FAILED_CHECK_JAVA_OR_SELECT_THE_GAME_AGAIN_AND_RETRY.text())
  } finally { starting.set(false) }
 }
}
