package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest as JarManifest
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class GameProcessDetectorTest {
 private val root = Files.createTempDirectory("process-game-path-")
 private val jar = root.resolve("game with spaces.jar").also { it.writeText("fixture, never executed") }
 private val target = TargetGame(jar.toString(), "4.0.2", 922, "fixture", true, 850, "test")
 private fun java(pid: Long = 100, args: List<String>? = listOf("-jar", jar.toString()), cwd: String? = null,
  alive: Boolean = true) = ObservedGameProcess(pid, "/usr/bin/java", args, cwd, alive)
 private fun status(vararg processes: ObservedGameProcess): GameProcessStatus = GameProcessDetector(target,
  ProcessInventory { ProcessInventorySnapshot(processes.toList()) }, GameProcessPlatform.current(), -1).status()
 private fun jarWithMain(name: String, main: String): Path = root.resolve(name).also { file ->
  val manifest = JarManifest().apply {
   mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"; mainAttributes[Attributes.Name.MAIN_CLASS] = main
  }
  JarOutputStream(Files.newOutputStream(file), manifest).use { zip ->
   zip.putNextEntry(JarEntry("fixture.txt")); zip.write(byteArrayOf(1)); zip.closeEntry()
  }
 }

 @Test fun absoluteSelectedJarIsRunningAndCollectsEveryGamePid() {
  assertEquals(GameProcessStatus.Running(setOf(100L, 101L)), status(java(), java(101),
   java(102, listOf("org.gradle.launcher.daemon.bootstrap.GradleDaemon", jar.toString()))))
 }
 @Test fun relativeSelectedJarResolvesAgainstActualProcessCwdWithSpaces() {
  assertIs<GameProcessStatus.Running>(status(java(args = listOf("-Xmx512m", "-jar", "./${jar.fileName}"), cwd = root.toString())))
  assertIs<GameProcessStatus.Unknown>(status(java(args = listOf("-jar", jar.fileName.toString()))))
 }
 @Test fun canonicalAliasesMatch() {
  val alias = root.resolve("alias.jar"); Files.createSymbolicLink(alias, jar)
  assertIs<GameProcessStatus.Running>(status(java(args = listOf("-jar", alias.toString()))))
 }
 @Test fun knownMainClassAndModuleLaunchAreRunning() {
  val main = GameProcessDetector.SHATTERED_MAIN
  assertIs<GameProcessStatus.Running>(status(java(args = listOf("-Ddemo=true", "-cp", jar.toString(), main))))
  assertIs<GameProcessStatus.Running>(status(java(args = listOf("--module", "game/$main"))))
 }
 @Test fun selfAndUnrelatedLauncherArgumentsDoNotMatchTargetText() {
  val own = java(7, listOf("pdsync.MainKt", "-jar", jar.toString()))
  val detector = GameProcessDetector(target, ProcessInventory { ProcessInventorySnapshot(listOf(own,
   java(8, listOf("org.gradle.launcher.GradleMain", jar.toString())), ObservedGameProcess(9, "/usr/bin/sleep", listOf("100")))) },
   GameProcessPlatform.LINUX, 7)
  assertEquals(GameProcessStatus.Stopped, detector.status())
  assertTrue(detector.isStopped())
 }
 @Test fun unreadableKnownJavaBlocksButOpaqueUnassociatedHelperDoesNot() {
  assertIs<GameProcessStatus.Unknown>(status(java(args = null)))
  assertIs<GameProcessStatus.Unknown>(status(java(args = emptyList())))
  assertEquals(GameProcessStatus.Stopped, status(ObservedGameProcess(123, null, null)))
  assertEquals(GameProcessStatus.Stopped, status(ObservedGameProcess(2, null, listOf("helper-option"), owner = "player")))
  assertIs<GameProcessStatus.Unknown>(status(ObservedGameProcess(123, null, listOf("-jar", jar.toString()))))
 }
 @Test fun unreadableArgumentFilesAndUnknownLauncherOptionsFailClosed() {
  assertIs<GameProcessStatus.Unknown>(status(java(args = listOf("@secret-options"))))
  assertIs<GameProcessStatus.Unknown>(status(java(args = listOf("--new-unrecognized-java-option", "unrelated.Main"))))
  assertIs<GameProcessStatus.Unknown>(status(java(args = listOf("-jar"))))
 }
 @Test fun nativePackagesAreUnidentifiedRatherThanStopped() {
  assertIs<GameProcessStatus.Unknown>(status(ObservedGameProcess(3, "/opt/Shattered Pixel Dungeon/Shattered Pixel Dungeon", emptyList())))
 }
 @Test fun deadProcessesAreIgnoredAndSecondSampleSeesSpawn() {
  assertEquals(GameProcessStatus.Stopped, status(java(alive = false)))
  var calls = 0
  val detector = GameProcessDetector(target, ProcessInventory {
   ProcessInventorySnapshot(if (++calls == 1) emptyList() else listOf(java()))
  }, GameProcessPlatform.LINUX, -1)
  assertIs<GameProcessStatus.Running>(detector.status()); assertEquals(2, calls)
 }
 @Test fun unknownProcessAppearingBetweenSamplesPreventsStopped() {
  var calls = 0
  assertIs<GameProcessStatus.Unknown>(GameProcessDetector(target, ProcessInventory {
   ProcessInventorySnapshot(if (++calls == 1) emptyList() else listOf(java(args = null)))
  }, GameProcessPlatform.LINUX, -1).status())
 }
 @Test fun runningFirstObservationIsConservativeIfGameExitsMidCheck() {
  var calls = 0
  assertIs<GameProcessStatus.Running>(GameProcessDetector(target, ProcessInventory {
   ProcessInventorySnapshot(if (++calls == 1) listOf(java()) else emptyList())
  }, GameProcessPlatform.LINUX, -1).status())
 }
 @Test fun inventoryFailureAndIncompleteEnumerationAreNeverStopped() {
  assertIs<GameProcessStatus.Unknown>(GameProcessDetector(target, ProcessInventory { error("inventory denied") }, GameProcessPlatform.LINUX).status())
  assertIs<GameProcessStatus.Unknown>(GameProcessDetector(target, ProcessInventory { ProcessInventorySnapshot(emptyList(), false) }, GameProcessPlatform.LINUX).status())
  assertEquals(GameProcessStatus.Stopped, status())
 }
 @Test fun selectedTrustedJarAndSupportedPlatformAreRequired() {
  val empty = ProcessInventory { ProcessInventorySnapshot(emptyList()) }
  assertIs<GameProcessStatus.Unknown>(GameProcessDetector(null, empty).status())
  assertIs<GameProcessStatus.Unknown>(GameProcessDetector(target.copy(trusted = false), empty).status())
  assertIs<GameProcessStatus.Unknown>(GameProcessDetector(target, empty, GameProcessPlatform.UNSUPPORTED).status())
 }
 @Test fun windowsJavawPathsAreCaseInsensitiveAndResolveDotSegments() {
  val nativeWindows = GameProcessPlatform.current() == GameProcessPlatform.WINDOWS
  val base = if (nativeWindows) root.toString() else "C:\\Users\\Player\\Games"
  if (nativeWindows) root.resolve("SPD.jar").writeText("fixture, never executed")
  val windowsTarget = target.copy(path = "$base\\SPD.jar")
  val processes = listOf(ObservedGameProcess(10, "C:\\Java\\bin\\JAVAW.EXE",
   listOf("-jar", base.replace('\\', '/').uppercase(java.util.Locale.ROOT) + "/child/../spd.JAR")))
  assertIs<GameProcessStatus.Running>(GameProcessDetector(windowsTarget, ProcessInventory { ProcessInventorySnapshot(processes) }, GameProcessPlatform.WINDOWS, -1).status())
  val expectedBase=if(nativeWindows)root.toRealPath().toString() else base
  assertEquals(expectedBase.replace('\\', '/').lowercase(java.util.Locale.ROOT) + "/spd.jar", resolveGameProcessPath(".\\SPD.jar", base, GameProcessPlatform.WINDOWS))
  assertNull(resolveGameProcessPath("C:SPD.jar", null, GameProcessPlatform.WINDOWS))
  assertIs<GameProcessStatus.Unknown>(GameProcessDetector(windowsTarget, ProcessInventory {
   ProcessInventorySnapshot(listOf(ObservedGameProcess(11, "C:\\Games\\ShatteredPD.exe", null)))
  }, GameProcessPlatform.WINDOWS, -1).status())
 }
 @Test fun macAbsoluteJarUsesCanonicalPathAndMissingRelativeCwdIsUnknown() {
  val absolute = ProcessInventory { ProcessInventorySnapshot(listOf(java())) }
  assertIs<GameProcessStatus.Running>(GameProcessDetector(target, absolute, GameProcessPlatform.MAC, -1).status())
  assertEquals(GameProcessStatus.Stopped, GameProcessDetector(target, ProcessInventory {
   ProcessInventorySnapshot(listOf(java(args = listOf("-jar", "game.jar"))))
  }, GameProcessPlatform.MAC, -1).status())
 }
 @Test fun sameNamedSecondInstallationCannotBeAssumedUnrelated() {
  val second = root.resolve("another").createDirectories().resolve(jar.fileName).also { it.writeText("other installation") }
  assertIs<GameProcessStatus.Unknown>(status(java(args = listOf("-jar", second.toString()))))
 }
 @Test fun unrelatedUnreadableJarIsOutsideScopeWithoutGameAssociation() {
  val other = root.resolve("unrelated.jar").also { it.writeText("fixture") }
  assertEquals(GameProcessStatus.Stopped, status(java(args = listOf("-jar", other.toString()))))
  assertEquals(GameProcessStatus.Stopped, status(java(args = listOf("-jar", root.resolve("unreadable.jar").toString()))))
  assertIs<GameProcessStatus.Unknown>(status(java(args = listOf("-jar", root.resolve("ShatteredPD-unreadable.jar").toString()))))
 }
 @Test fun boundedManifestRecognizesRenamedGameAndExcludesUnrelatedJavaJar() {
  val renamed = jarWithMain("completely-renamed.jar", GameProcessDetector.SHATTERED_MAIN)
  val other = jarWithMain("unrelated-launcher.jar", "org.example.OtherMain")
  assertIs<GameProcessStatus.Running>(status(java(args = listOf("-jar", renamed.toString()))))
  assertEquals(GameProcessStatus.Stopped, status(java(args = listOf("-jar", other.toString()))))
  val misleadingName = jarWithMain("Shattered-name-but-other-main.jar", "org.example.OtherMain")
  assertEquals(GameProcessStatus.Stopped, status(java(args = listOf("-jar", misleadingName.toString()))))
 }
 @Test fun malformedOrOversizedAssociatedManifestStaysUnknown() {
  val oversized = root.resolve("Shattered-oversized.jar")
  JarOutputStream(Files.newOutputStream(oversized)).use { zip ->
   zip.putNextEntry(JarEntry("META-INF/MANIFEST.MF")); zip.write(ByteArray(64 * 1024 + 1) { 'x'.code.toByte() }); zip.closeEntry()
  }
  assertIs<GameProcessStatus.Unknown>(status(java(args = listOf("-jar", oversized.toString()))))
  val ambiguous = root.resolve("Shattered-ambiguous.jar")
  JarOutputStream(Files.newOutputStream(ambiguous)).use { zip ->
   zip.putNextEntry(JarEntry("META-INF/MANIFEST.MF")); zip.write("Manifest-Version: 1.0\r\nMain-Class: other.Main\r\nMain-Class: ${GameProcessDetector.SHATTERED_MAIN}\r\n\r\n".encodeToByteArray()); zip.closeEntry()
  }
  assertIs<GameProcessStatus.Unknown>(status(java(args = listOf("-jar", ambiguous.toString()))))
 }
 @Test fun sameDetectorRemembersGameThatBecomesOpaqueAndClearsVisiblePidReuse() {
  var observed = java().copy(startToken = "start-one", owner = "player")
  val detector = GameProcessDetector(target, ProcessInventory { ProcessInventorySnapshot(listOf(observed), currentOwner = "player") }, GameProcessPlatform.LINUX, -1)
  assertIs<GameProcessStatus.Running>(detector.status())
  observed = observed.copy(command = null, arguments = null, startToken = null)
  assertIs<GameProcessStatus.Unknown>(detector.status()); assertIs<GameProcessStatus.Unknown>(detector.status())
  observed = observed.copy(startToken = "start-two")
  assertEquals(GameProcessStatus.Stopped, detector.status())
 }
 @Test fun associationIsInstanceScopedAndRemovedAfterACompleteExitObservation() {
  var observed = listOf(java().copy(startToken = "start-one"))
  val inventory = ProcessInventory { ProcessInventorySnapshot(observed) }
  val detector = GameProcessDetector(target, inventory, GameProcessPlatform.LINUX, -1)
  assertIs<GameProcessStatus.Running>(detector.status())
  observed = listOf(observed.single().copy(command = null, arguments = null))
  assertIs<GameProcessStatus.Unknown>(detector.status())
  assertEquals(GameProcessStatus.Stopped, GameProcessDetector(target, inventory, GameProcessPlatform.LINUX, -1).status())
  observed = emptyList(); assertEquals(GameProcessStatus.Stopped, detector.status())
  observed = listOf(ObservedGameProcess(100, null, null, startToken = "start-one"))
  assertEquals(GameProcessStatus.Stopped, detector.status())
 }
 @Test fun incompleteObservationCannotForgetAnAssociatedGame() {
  var sample = ProcessInventorySnapshot(listOf(java().copy(startToken = "start-one")))
  val detector = GameProcessDetector(target, ProcessInventory { sample }, GameProcessPlatform.LINUX, -1)
  assertIs<GameProcessStatus.Running>(detector.status())
  sample = ProcessInventorySnapshot(emptyList(), complete = false)
  assertIs<GameProcessStatus.Unknown>(detector.status())
  sample = ProcessInventorySnapshot(listOf(ObservedGameProcess(100, null, null, startToken = "start-one")))
  assertIs<GameProcessStatus.Unknown>(detector.status())
 }
 @Test fun renamedJavaAndNativeAppWrapperPathsFailClosed() {
  assertIs<GameProcessStatus.Unknown>(status(ObservedGameProcess(7, "/opt/custom-runtime", listOf("-jar", jar.toString()))))
  assertIs<GameProcessStatus.Unknown>(status(ObservedGameProcess(8, "/Applications/Shattered Pixel Dungeon.app/Contents/MacOS/JavaAppLauncher", null)))
 }
 @Test fun onlyPositivelyOtherOsAccountsAreOutsideDefaultSaveScope() {
  fun scoped(process: ObservedGameProcess, currentOwner: String? = "player") = GameProcessDetector(target,
   ProcessInventory { ProcessInventorySnapshot(listOf(process), currentOwner = currentOwner) }, GameProcessPlatform.LINUX, -1).status()
  assertEquals(GameProcessStatus.Stopped, scoped(ObservedGameProcess(3, null, null, owner = "root")))
  assertEquals(GameProcessStatus.Stopped, scoped(java().copy(owner = "other-user")))
  assertIs<GameProcessStatus.Running>(scoped(java().copy(owner = "player")))
  assertIs<GameProcessStatus.Unknown>(scoped(java(args = null).copy(owner = "player")))
  assertIs<GameProcessStatus.Unknown>(scoped(java(args = null)))
  assertEquals(GameProcessStatus.Stopped, scoped(ObservedGameProcess(3, null, null)))
  assertEquals(GameProcessStatus.Stopped, scoped(ObservedGameProcess(3, null, null, owner = "root"), currentOwner = null))
  assertEquals(GameProcessStatus.Stopped, scoped(ObservedGameProcess(3, "/usr/bin/sleep", null)))
 }
 @Test fun windowsAccountIdentityComparisonIgnoresCase() {
  val windows = if (GameProcessPlatform.current() == GameProcessPlatform.WINDOWS) target else target.copy(path = "C:/Games/SPD.jar")
  val detector = GameProcessDetector(windows, ProcessInventory {
   ProcessInventorySnapshot(listOf(ObservedGameProcess(4, "C:/Java/java.exe", null, owner = "DOMAIN\\Player")), currentOwner = "domain\\player")
  }, GameProcessPlatform.WINDOWS, -1)
  assertIs<GameProcessStatus.Unknown>(detector.status())
 }
 @Test fun liveLinuxInventoryObservesHarmlessFixtureAndItsNaturalExit() {
  assumeTrue(GameProcessPlatform.current() == GameProcessPlatform.LINUX)
  val compiler = ToolProvider.getSystemJavaCompiler(); assumeTrue(compiler != null)
  val source = root.resolve("ProcessFixture.java").also {
   it.writeText("public class ProcessFixture { public static void main(String[] args) throws Exception { System.out.println(\"READY\"); Thread.sleep(2000); } }")
  }
  assertEquals(0, compiler.run(null, null, null, "-d", root.toString(), source.toString()))
  val fixtureJar = root.resolve("harmless-process-fixture.jar")
  val manifest = JarManifest().apply {
   mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
   mainAttributes[Attributes.Name.MAIN_CLASS] = "ProcessFixture"
  }
  JarOutputStream(Files.newOutputStream(fixtureJar), manifest).use { zip ->
   zip.putNextEntry(JarEntry("ProcessFixture.class")); zip.write(Files.readAllBytes(root.resolve("ProcessFixture.class"))); zip.closeEntry()
  }
  val longClasspath = root.resolve("unused-classpath-segment").toString().let { (0..160).joinToString(":") { index -> "$it-$index" } }
  val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", longClasspath, "-jar", fixtureJar.fileName.toString())
   .directory(root.toFile()).start()
  // Only this fixture is launched. It exits itself; no existing process is signaled or killed.
  try {
   assertEquals("READY", process.inputStream.bufferedReader().readLine())
   var fallbackReads = 0
   val native = JvmProcessInventory(GameProcessPlatform.LINUX) { pid -> fallbackReads++; LinuxProcessArguments.read(pid) }
   val sample = native.snapshot(); val observed = sample.processes.single { it.pid == process.pid() }
   assertNotNull(observed.command); assertNotNull(observed.arguments); assertEquals(root.toString(), observed.workingDirectory)
   val fixtureOnly = ProcessInventory { native.snapshot().let { it.copy(processes = it.processes.filter { entry -> entry.pid == process.pid() }) } }
   val detector = GameProcessDetector(target.copy(path = fixtureJar.toString()), fixtureOnly, GameProcessPlatform.LINUX)
   assertEquals(GameProcessStatus.Running(setOf(process.pid())), detector.status())
   if (process.info().arguments().isEmpty) {
    assertTrue(fallbackReads > 0)
    val denied = JvmProcessInventory(GameProcessPlatform.LINUX) { null }
    val deniedFixture = ProcessInventory { denied.snapshot().let { it.copy(processes = it.processes.filter { entry -> entry.pid == process.pid() }) } }
    assertIs<GameProcessStatus.Unknown>(GameProcessDetector(target.copy(path = fixtureJar.toString()), deniedFixture, GameProcessPlatform.LINUX).status())
   }
   assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
   assertEquals(GameProcessStatus.Stopped, detector.status())
  } finally { process.waitFor(10, TimeUnit.SECONDS) }
 }
}
