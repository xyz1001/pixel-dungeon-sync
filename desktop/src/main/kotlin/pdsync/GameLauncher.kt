package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class GameLaunchPlan(val command: List<String>, val directory: Path)

internal sealed interface GameLaunchResult {
 val message: String
 data class Started(val target: TargetGame, val pid: Long) : GameLaunchResult {
  override val message = "游戏已启动。"
 }
 data class AlreadyRunning(val status: GameProcessStatus.Running) : GameLaunchResult {
  override val message = "游戏已在运行。"
 }
 data object Busy : GameLaunchResult { override val message = "正在启动游戏，请稍候。" }
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
    inspect(path)?.takeIf { it.trusted } ?: return GameLaunchResult.Failed("游戏文件不可用，请在设置中重新选择游戏。")
   } catch (_: Exception) { return GameLaunchResult.Failed("游戏文件不可用，请在设置中重新选择游戏。") }
   when (val state = status(target)) {
    is GameProcessStatus.Running -> return GameLaunchResult.AlreadyRunning(state)
    is GameProcessStatus.Unknown -> return GameLaunchResult.Failed("无法确认游戏状态，请关闭游戏后重试。")
    GameProcessStatus.Stopped -> Unit
   }
   if (platform == GameProcessPlatform.UNSUPPORTED) return GameLaunchResult.Failed("当前系统暂不支持启动游戏。")
   // No game save-directory override exists. Do not silently launch against another profile.
   try {
    val location = DefaultProfileLocation(context)
    val defaultRoot = SafePaths.checked(location.resolve())
    val selected = selectedSaveRoot?.let { location.requireSelected(it, false) } ?: defaultRoot
    if (selected != defaultRoot) return GameLaunchResult.Failed("所选存档目录不是游戏默认位置，请在设置中核对路径后启动。")
   } catch (_: Exception) { return GameLaunchResult.Failed("无法确认游戏存档位置，请在设置中核对路径。") }
   val java = javaExecutable() ?: return GameLaunchResult.Failed("未找到 Java 启动程序，请安装 Java 17 或更新同步器。")
   val process = try { start(gameLaunchPlan(target, java, context, platform)) }
    catch (_: Exception) { return GameLaunchResult.Failed("启动失败，请检查 Java 环境或重新选择游戏后重试。") }
   child = process
   // Detect immediate JVM/native-library failures without blocking the UI or piping output.
   if (process.waitFor(startupMillis, TimeUnit.MILLISECONDS)) {
    child = null
    return GameLaunchResult.Failed("游戏启动后已退出，请检查 Java 环境或重新选择游戏后重试。")
   }
   return GameLaunchResult.Started(target, process.pid())
  } catch (_: InterruptedException) {
   Thread.currentThread().interrupt()
   return GameLaunchResult.Failed("启动检查已中断，请检查游戏窗口后重试。")
  } catch (_: Exception) {
   return GameLaunchResult.Failed("启动失败，请检查 Java 环境或重新选择游戏后重试。")
  } finally { starting.set(false) }
 }
}
