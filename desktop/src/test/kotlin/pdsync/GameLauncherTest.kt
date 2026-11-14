package pdsync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class GameLauncherTest {
 @TempDir lateinit var root: Path
 private fun target(path: Path = root.resolve("selected game.jar"), trusted: Boolean = true) =
  TargetGame(path.toString(), "3.3.8", 896, "fixture", trusted, 802, "identified fixture")
 // Filesystem-backed fixtures must match @TempDir's native path syntax, including Windows drives.
 private fun context() = SavePathContext(System.getProperty("os.name"), root.resolve("home with spaces").toString(), mapOf("XDG_DATA_HOME" to root.resolve("data with spaces").toString()))
 private fun javaName() = if (GameProcessPlatform.current() == GameProcessPlatform.WINDOWS) "javaw.exe" else "java"
 private fun defaultRoot(): Path = DefaultProfileLocation(context()).prepareDefaultDirectory()
 private class Child(var alive: Boolean = true, val id: Long = 123) : Process() {
  override fun getOutputStream() = ByteArrayOutputStream()
  override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
  override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
  override fun waitFor() = 0
  override fun waitFor(timeout: Long, unit: TimeUnit) = !alive
  override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 1
  override fun destroy() { alive = false }
  override fun isAlive() = alive
  override fun pid() = id
 }
 private fun launcher(
  inspect: (Path) -> TargetGame? = { target(it) },
  java: () -> Path? = { root.resolve("runtime/bin/${javaName()}") },
  start: (GameLaunchPlan) -> Process = { Child() },
  pathContext: SavePathContext = context(),
  platform: GameProcessPlatform = GameProcessPlatform.current()
 ) = GameLauncher(pathContext, platform, inspect, java, start, 0)

 @Test fun launchesSelectedJarWithSeparateArgumentsAndMatchingHome() {
  var seen: GameLaunchPlan? = null
  val jar = root.resolve("游戏 folder/name ; $(not-a-command).jar")
  val result = launcher(start = { seen = it; Child() }).launch(jar.toString(), defaultRoot().toString())
  assertIs<GameLaunchResult.Started>(result)
  assertEquals(buildList {
   add(root.resolve("runtime/bin/${javaName()}").toString())
   if (GameProcessPlatform.current() == GameProcessPlatform.MAC) add("-XstartOnFirstThread")
   add("-Duser.home=${context().userHome}"); add("-jar"); add(jar.toString())
  }, seen!!.command)
  assertEquals(jar.parent, seen!!.directory)
 }
 @Test fun defaultSelectionWithoutDirectoryDoesNotCreateOrRewriteSaves() {
  val before = root.toFile().walkTopDown().toList()
  assertIs<GameLaunchResult.Started>(launcher().launch(target().path, null))
  assertEquals(before, root.toFile().walkTopDown().toList())
 }
 @Test fun customSaveDirectoryDoesNotLaunchIntoDifferentDefaultDirectory() {
  val custom = Files.createDirectories(root.resolve("custom-saves"))
  val result = launcher(start = { fail("must not launch") }).launch(target().path, custom.toString())
  assertContains(assertIs<GameLaunchResult.Failed>(result).message, "不是游戏默认位置")
 }
 @Test fun matchingExplicitDefaultIsAllowed() {
  assertIs<GameLaunchResult.Started>(launcher().launch(target().path, defaultRoot().toString()))
 }
 @Test fun invalidSaveDirectoryFailsClosed() {
  val result = launcher(start = { fail("must not launch") }).launch(target().path, "relative")
  assertContains(assertIs<GameLaunchResult.Failed>(result).message, "存档位置")
 }
 @Test fun invalidXdgNeverFallsBackToAnotherDirectory() {
  // This policy check is deliberately Linux-only; do not mix in native Windows temp paths.
  val context = SavePathContext("Linux", "/fixture-home", mapOf("XDG_DATA_HOME" to "relative"))
  val result = launcher(pathContext = context, start = { fail("must not launch") }).launch(target().path, null)
  assertContains(assertIs<GameLaunchResult.Failed>(result).message, "存档位置")
 }
 @Test fun linkedSaveDirectoryFailsClosed() {
  val actual = Files.createDirectories(root.resolve("elsewhere"))
  val link = Files.createSymbolicLink(root.resolve("linked"), actual)
  val result = launcher(start = { fail("must not launch") }).launch(target().path, link.toString())
  assertIs<GameLaunchResult.Failed>(result)
 }
 @Test fun missingOrUnrecognizedJarAsksToChooseAgain() {
  val result = launcher(inspect = { null }, start = { fail("must not launch") }).launch(target().path, null)
  assertContains(assertIs<GameLaunchResult.Failed>(result).message, "重新选择")
 }
 @Test fun rejectedJarExceptionDoesNotLeakPathOrTechnicalDetails() {
  val result = launcher(inspect = { error("private/path/secret") }).launch(target().path, null)
  assertIs<GameLaunchResult.Failed>(result)
  assertFalse(result.message.contains("secret"))
 }
 @Test fun unknownJarCanBeSelectedForLaunchWhenProcessStatusIsStopped() {
  assertIs<GameLaunchResult.Started>(launcher(inspect = { target(it, false) }, start = { Child() }).launch(target().path, null))
 }
 @Test fun blankRelativeOrNativeExecutableCannotLaunch() {
  for (path in listOf("", "relative.jar", root.resolve("shattered.exe").toString(), "\u0000.jar")) {
   assertIs<GameLaunchResult.Failed>(launcher(inspect = { fail("must not inspect") }).launch(path, null))
  }
 }
 @Test fun targetIsFreshlyInspectedOnEveryAttempt() {
  var inspections = 0
  val launch = launcher(inspect = { inspections++; target(it) }, start = { Child(false) })
  repeat(2) { assertIs<GameLaunchResult.Failed>(launch.launch(target().path, null)) }
  assertEquals(2, inspections)
 }
 @Test fun freshLauncherDoesNotConsultExternalProcessInventory() {
  assertEquals(GameProcessStatus.Stopped, launcher().status())
  assertIs<GameLaunchResult.Started>(launcher().launch(target().path, null))
 }
 @Test fun liveChildPreventsDuplicateEvenIfInventoryMissesIt() {
  var starts = 0
  val launch = launcher(start = { starts++; Child() })
  assertIs<GameLaunchResult.Started>(launch.launch(target().path, null))
  assertIs<GameLaunchResult.AlreadyRunning>(launch.launch(target().path, null))
  assertEquals(1, starts)
  assertIs<GameProcessStatus.Running>(launch.status())
 }
 @Test fun closedGameCanBeLaunchedAgain() {
  var starts = 0
  val first = Child()
  val launch = launcher(start = { if (++starts == 1) first else Child(id = 456) })
  assertIs<GameLaunchResult.Started>(launch.launch(target().path, null))
  first.destroy()
  assertEquals(GameProcessStatus.Stopped, launch.status())
  assertEquals(456L, assertIs<GameLaunchResult.Started>(launch.launch(target().path, null)).pid)
  assertEquals(2, starts)
 }
 @Test fun ownedExitIsQueuedOnceAndBoundToTargetAndSaveRoot() {
  val child = Child()
  val game = target().path
  val launch = launcher(start = { child })
  assertIs<GameLaunchResult.Started>(launch.launch(game, null))
  assertNull(launch.pendingExit(game, null))
  child.alive = false
  val exit = assertNotNull(launch.pendingExit(game, null))
  assertEquals(exit, launch.pendingExit(game, null))
  launch.completeExit(exit)
  assertNull(launch.pendingExit(game, null))
 }
 @Test fun completingAnOldExitDoesNotConsumeANewerExit() {
  val children = mutableListOf<Child>()
  val game = target().path
  val launch = launcher(start = { Child(id = 100L + children.size).also(children::add) })
  assertIs<GameLaunchResult.Started>(launch.launch(game, null))
  children.last().alive = false
  val first = assertNotNull(launch.pendingExit(game, null))
  assertIs<GameLaunchResult.Started>(launch.launch(game, null))
  children.last().alive = false
  assertEquals(first, launch.pendingExit(game, null))
  launch.completeExit(first)
  val second = assertNotNull(launch.pendingExit(game, null))
  assertNotEquals(first.generation, second.generation)
  launch.completeExit(first)
  assertEquals(second, launch.pendingExit(game, null))
 }
 @Test fun staleExitCannotAuthorizeAnotherTargetOrSaveRoot() {
  for (changeRoot in listOf(false, true)) {
   val child = Child()
   val game = target().path
   val launch = launcher(start = { child })
   assertIs<GameLaunchResult.Started>(launch.launch(game, null))
   child.alive = false
   assertNull(launch.pendingExit(if (changeRoot) game else root.resolve("other.jar").toString(), if (changeRoot) "other" else null))
   assertNull(launch.pendingExit(game, null))
  }
 }
 @Test fun onlyAnOwnedExitCanAuthorizeOneAutomaticBackup() {
  val child = Child()
  val game = target().path
  val launcher = launcher(start = { child })
  val automatic = AutomaticUpload(quietMillis = 0)
  val old = mapOf("save" to "old")
  val changed = mapOf("save" to "changed")
  automatic.observe(old, false, true, 0)
  automatic.observe(changed, false, true, 1)
  fun ready(now: Long): Boolean = automatic.shouldUploadOwned(launcher.pendingExit(game, null),
   now, true, true, launcher.status() is GameProcessStatus.Stopped, false, false, false)
  assertFalse(ready(2))
  assertIs<GameLaunchResult.Started>(launcher.launch(game, null))
  assertFalse(ready(3))
  child.alive = false
  assertTrue(ready(4))
  val exit = assertNotNull(launcher.pendingExit(game, null))
  automatic.startedOwned(exit); automatic.failed(4, true)
  assertFalse(ready(5003)); assertTrue(ready(5004))
  automatic.startedOwned(exit); automatic.succeeded(changed); launcher.completeExit(exit)
  automatic.observe(mapOf("save" to "external-change"), false, true, 6000)
  assertFalse(ready(6001))
 }
 @Test fun realOwnedHelperIsTrackedWhileOtherJavaDoesNotBlockAndThenExits() {
  val source = root.resolve("OwnedProcessFixture.java")
  Files.writeString(source, "public class OwnedProcessFixture { public static void main(String[] args) throws Exception { Thread.sleep(2000); } }")
  val compiler = assertNotNull(javax.tools.ToolProvider.getSystemJavaCompiler())
  assertEquals(0, compiler.run(null, null, null, "-d", root.toString(), source.toString()))
  val java = assertNotNull(gameJavaExecutable(usable = {
   Files.isRegularFile(it) && Files.isExecutable(it) && it.fileName.toString() in setOf("java", "java.exe")
  }))
  fun helper(): Process = ProcessBuilder(java.toString(), "-cp", root.toString(), "OwnedProcessFixture")
   .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
  val external = helper()
  var owned: Process? = null
  try {
   val launcher = launcher(java = { java }, start = { helper().also { owned = it } })
   assertTrue(external.isAlive)
   assertIs<GameLaunchResult.Started>(launcher.launch(target().path, null))
   assertIs<GameLaunchResult.AlreadyRunning>(launcher.launch(target().path, null))
   assertTrue(assertNotNull(owned).waitFor(10, TimeUnit.SECONDS))
   assertEquals(GameProcessStatus.Stopped, launcher.status())
   assertNotNull(launcher.pendingExit(target().path, null))
   assertTrue(external.waitFor(10, TimeUnit.SECONDS))
  } finally {
   listOfNotNull(external, owned).forEach {
    if (it.isAlive) it.destroyForcibly()
    it.waitFor(10, TimeUnit.SECONDS)
   }
  }
 }
 @Test fun concurrentDoubleClickIsRejectedWhileFirstIsInspecting() {
  val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
  val launch = launcher(inspect = { entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)); target(it) })
  val executor = Executors.newSingleThreadExecutor()
  try {
   val result = executor.submit<GameLaunchResult> { launch.launch(target().path, null) }
   assertTrue(entered.await(5, TimeUnit.SECONDS))
   assertEquals(GameLaunchResult.Busy, launch.launch(target().path, null))
   proceed.countDown()
   assertIs<GameLaunchResult.Started>(result.get(5, TimeUnit.SECONDS))
  } finally { proceed.countDown(); executor.shutdownNow() }
 }
 @Test fun processStartFailureReleasesGuardForRetry() {
  var attempts = 0
  val launch = launcher(start = { if (++attempts == 1) throw java.io.IOException("failed") else Child() })
  assertIs<GameLaunchResult.Failed>(launch.launch(target().path, null))
  assertIs<GameLaunchResult.Started>(launch.launch(target().path, null))
  assertEquals(2, attempts)
 }
 @Test fun earlyExitReleasesGuardForRetry() {
  var attempts = 0
  val launch = launcher(start = { Child(alive = ++attempts != 1) })
  assertContains(assertIs<GameLaunchResult.Failed>(launch.launch(target().path, null)).message, "已退出")
  assertIs<GameLaunchResult.Started>(launch.launch(target().path, null))
 }
 @Test fun invalidPathFailureCanBeRetriedWithFixedSelection() {
  val launch = launcher()
  assertIs<GameLaunchResult.Failed>(launch.launch("", null))
  assertIs<GameLaunchResult.Started>(launch.launch(target().path, null))
 }
 @Test fun missingJavaHasActionableMessageAndDoesNotSpawn() {
  val result = launcher(java = { null }, start = { fail("must not launch") }).launch(target().path, null)
  assertContains(assertIs<GameLaunchResult.Failed>(result).message, "Java")
 }
 @Test fun unsupportedPlatformDoesNotSpawn() {
  assertIs<GameLaunchResult.Failed>(launcher(platform = GameProcessPlatform.UNSUPPORTED, start = { fail("must not launch") }).launch(target().path, null))
 }
 @Test fun macRequiresFirstThreadFlagAndSameHome() {
  val plan = gameLaunchPlan(target(), root.resolve("bin/java"), context().copy(osName = "Mac OS X"), GameProcessPlatform.MAC)
  assertEquals("-XstartOnFirstThread", plan.command[1])
  assertEquals("-Duser.home=${context().userHome}", plan.command[2])
  assertEquals(listOf("-jar", target().path), plan.command.takeLast(2))
 }
 @Test fun windowsDoesNotUseShellOrMacFlag() {
  val plan = gameLaunchPlan(target(), root.resolve("bin/javaw.exe"), context().copy(osName = "Windows 11"), GameProcessPlatform.WINDOWS)
  assertEquals(4, plan.command.size)
  assertFalse(plan.command.any { it == "cmd" || it == "start" || it == "-XstartOnFirstThread" })
 }
 @Test fun currentRuntimeJavaWinsOverEnvironmentFallbacks() {
  val java = root.resolve("runtime/bin/${javaName()}")
  assertEquals(java, gameJavaExecutable(root.resolve("runtime").toString(), mapOf("JAVA_HOME" to root.resolve("jdk").toString(), "PATH" to root.resolve("tools").toString()), GameProcessPlatform.current()) { true })
 }
 @Test fun strippedRuntimeFallsBackToExplicitJavaHomeThenPath() {
  val platform = GameProcessPlatform.current()
  val jdk = root.resolve("jdk/bin/${javaName()}"); val path = root.resolve("tools/${javaName()}")
  val environment = mapOf("JAVA_HOME" to root.resolve("jdk").toString(),
   "PATH" to listOf(root.resolve("missing-tools"), root.resolve("tools")).joinToString(java.io.File.pathSeparator))
  assertEquals(jdk, gameJavaExecutable(root.resolve("stripped").toString(), environment, platform) { it == jdk || it == path })
  assertEquals(path, gameJavaExecutable(root.resolve("stripped").toString(), environment, platform) { it == path })
 }
 @Test fun noRuntimeLauncherNeverFallsBackToSyncExecutableOrWorkingDirectory() {
  val candidates = mutableListOf<Path>()
  assertNull(gameJavaExecutable(null, mapOf("PATH" to ":.:relative"), GameProcessPlatform.LINUX) { candidates.add(it); true })
  assertTrue(candidates.isEmpty())
 }
 @Test fun windowsChoosesJavawAndCanFallbackToJava() {
  val home = root.resolve("runtime").toString()
  assertEquals(root.resolve("runtime/bin/javaw.exe"), gameJavaExecutable(home, emptyMap(), GameProcessPlatform.WINDOWS) { true })
  assertEquals(root.resolve("runtime/bin/java.exe"), gameJavaExecutable(home, emptyMap(), GameProcessPlatform.WINDOWS) { it.fileName.toString() == "java.exe" })
 }
 @Test fun windowsEnvironmentKeysAreCaseInsensitive() {
  val home = root.resolve("external-jdk")
  val path = root.resolve("path-java")
  val environment = mapOf("Java_Home" to home.toString(), "Path" to path.toString())
  assertEquals(home.resolve("bin/javaw.exe"), gameJavaExecutable(null, environment, GameProcessPlatform.WINDOWS) { true })
  assertEquals(path.resolve("java.exe"), gameJavaExecutable(null, environment, GameProcessPlatform.WINDOWS) { it == path.resolve("java.exe") })
 }
 @Test fun executableInspectionFailureIsNotFatal() {
  assertNull(gameJavaExecutable(root.toString(), emptyMap(), GameProcessPlatform.LINUX) { throw SecurityException() })
 }
 @Test fun realJvmEarlyExitIsReportedAndRetryRemainsAvailable() {
  val jar = root.resolve("invalid-launch.jar")
  Files.writeString(jar, "not a runnable jar")
  // javaw can show a blocking error dialog for an invalid JAR on Windows.
  val consoleJava = assertNotNull(gameJavaExecutable(usable = {
   Files.isRegularFile(it) && Files.isExecutable(it) && it.fileName.toString() in setOf("java", "java.exe")
  }))
  val children = mutableListOf<Process>()
  try {
   val launch = GameLauncher(context(), GameProcessPlatform.current(),
    inspect = { target(it) }, javaExecutable = { consoleJava }, start = { plan ->
     val process = ProcessBuilder(plan.command).directory(plan.directory.toFile())
      .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
     children.add(process)
     process.outputStream.close()
     // Establish an exited real child before probing it; host startup speed is not the assertion.
     // The mock-child tests separately cover both outcomes of the production startup window.
     assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Invalid-JAR fixture JVM did not exit")
     assertNotEquals(0, process.exitValue())
     process
    }, startupMillis = 0)
   repeat(2) {
    assertContains(assertIs<GameLaunchResult.Failed>(launch.launch(jar.toString(), null)).message, "已退出")
     assertEquals(GameProcessStatus.Stopped, launch.status())
   }
   assertEquals(2, children.size)
  } finally {
   children.forEach { process ->
    if (process.isAlive) process.destroyForcibly()
    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Fixture JVM must exit before temporary-directory cleanup")
   }
  }
 }
 @Test fun actualJavaResolverSelectsAnExecutableWithJavaName() {
  val java = assertNotNull(gameJavaExecutable())
  assertTrue(Files.isExecutable(java))
  assertTrue(java.fileName.toString() in setOf("java", "java.exe", "javaw.exe"))
 }
}
