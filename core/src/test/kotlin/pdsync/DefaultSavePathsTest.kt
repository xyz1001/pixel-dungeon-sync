package pdsync

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultSavePathsTest {
 private fun resolve(os: String, home: String? = "/home/tester", environment: Map<String, String> = emptyMap()) =
  ShatteredDefaultSavePaths.resolve(SavePathContext(os, home, environment))

 @Test fun linuxUsesUserHomeWhenXdgIsAbsent() {
  assertEquals("/home/tester/.local/share/.shatteredpixel/shattered-pixel-dungeon", resolve("Linux"))
 }

 @Test fun linuxHonorsAbsoluteXdgWithoutNeedingUserHome() {
  assertEquals("/data/custom/.shatteredpixel/shattered-pixel-dungeon", resolve("Linux", null, mapOf("XDG_DATA_HOME" to "/data/custom")))
 }

 @Test fun linuxDoesNotUseHomeEnvironmentOrXdgConfigHome() {
  assertEquals("/home/tester/.local/share/.shatteredpixel/shattered-pixel-dungeon", resolve("Linux", environment = mapOf("HOME" to "/wrong", "XDG_CONFIG_HOME" to "/wrong-config")))
 }

 @Test fun linuxPreservesTrailingSlashesAndDotSegments() {
  assertEquals("/data/cache/../saves//.shatteredpixel/shattered-pixel-dungeon", resolve("Linux", environment = mapOf("XDG_DATA_HOME" to "/data/cache/../saves/")))
  assertEquals("/home/tester//.local/share/.shatteredpixel/shattered-pixel-dungeon", resolve("Linux", "/home/tester/"))
 }

 @Test fun linuxPreservesLegalWhitespaceUnicodeAndBackslashes() {
  assertEquals("/data/用户 saves \\ folder /.shatteredpixel/shattered-pixel-dungeon", resolve("Linux", environment = mapOf("XDG_DATA_HOME" to "/data/用户 saves \\ folder ")))
 }

 @Test fun linuxRejectsEmptyXdgInsteadOfUsingTheHomeFallbackOrFilesystemRoot() {
  val error = assertFailsWith<IllegalArgumentException> { resolve("Linux", environment = mapOf("XDG_DATA_HOME" to "")) }
  assertTrue(error.message!!.contains("XDG_DATA_HOME"))
  assertTrue(error.message!!.contains("no fallback"))
 }

 @Test fun linuxRejectsRelativeXdgInsteadOfUsingWorkingDirectory() {
  listOf("data", ".", "../data", "~/data", " ", "C:/data").forEach { invalid ->
   val error = assertFailsWith<IllegalArgumentException> { resolve("Linux", environment = mapOf("XDG_DATA_HOME" to invalid)) }
   assertTrue(error.message!!.contains("XDG_DATA_HOME"))
  }
 }

 @Test fun linuxRejectsNulInXdg() {
  assertFailsWith<IllegalArgumentException> { resolve("Linux", environment = mapOf("XDG_DATA_HOME" to "/data/\u0000saves")) }
 }

 @Test fun unixRequiresUsableHomeOnlyWhenNeeded() {
  listOf("Linux", "Mac OS X").forEach { os ->
   listOf(null, "", "home/tester", "~/tester", "/home/\u0000tester").forEach { invalid ->
    val error = assertFailsWith<IllegalArgumentException> { resolve(os, invalid) }
    assertTrue(error.message!!.contains("user.home"))
   }
  }
 }

 @Test fun windowsUsesJavaUserHomeRatherThanAppDataOrUserProfileEnvironment() {
  val environment = mapOf("APPDATA" to "D:/Redirected", "USERPROFILE" to "E:/Other", "HOME" to "F:/Other", "XDG_DATA_HOME" to "/irrelevant")
  assertEquals("C:/Users/Tester/AppData/Roaming/.shatteredpixel/Shattered Pixel Dungeon", resolve("Windows 11", "C:\\Users\\Tester", environment))
 }

 @Test fun windowsXpRequiresExactOsName() {
  assertEquals("C:/Documents and Settings/Tester/Application Data/.shatteredpixel/Shattered Pixel Dungeon", resolve("Windows XP", "C:/Documents and Settings/Tester"))
  assertEquals("C:/Users/Tester/AppData/Roaming/.shatteredpixel/Shattered Pixel Dungeon", resolve("Windows XP Professional", "C:/Users/Tester"))
 }

 @Test fun windowsSupportsUncHomeWithUnicodeAndSpaces() {
  assertEquals("//server/profiles/小明 Smith/AppData/Roaming/.shatteredpixel/Shattered Pixel Dungeon", resolve("Windows 10", "\\\\server\\profiles\\小明 Smith"))
 }

 @Test fun windowsPreservesHomeDotSegmentsAndTrailingSeparator() {
  assertEquals("C:/Users/Old/../Tester//AppData/Roaming/.shatteredpixel/Shattered Pixel Dungeon", resolve("Windows 11", "C:\\Users\\Old\\..\\Tester\\"))
 }

 @Test fun windowsRejectsMissingRelativeRootRelativeAndMalformedHome() {
  listOf(null, "", "Users/Tester", "C:Users/Tester", "/Users/Tester", "\\Users\\Tester", "//server", "//server/", "C:/user\u0000name", "C:/User?Name", "C:/User:Name").forEach { invalid ->
   val error = assertFailsWith<IllegalArgumentException> { resolve("Windows 11", invalid) }
   assertTrue(error.message!!.contains("user.home"))
  }
 }

 @Test fun macUsesCaseSensitiveTitleWithoutVendorDirectory() {
  assertEquals("/Users/Tester/Library/Application Support/Shattered Pixel Dungeon", resolve("Mac OS X", "/Users/Tester", mapOf("XDG_DATA_HOME" to "")))
 }

 @Test fun macPreservesHomeTrailingSlash() {
  assertEquals("/Users/Tester//Library/Application Support/Shattered Pixel Dungeon", resolve("Mac OS X", "/Users/Tester/"))
 }

 @Test fun freeBsdUsesLaunchersExplicitLinuxOverride() {
  assertEquals("/home/tester/.local/share/.shatteredpixel/shattered-pixel-dungeon", resolve("FreeBSD"))
  assertEquals("/data/.shatteredpixel/shattered-pixel-dungeon", resolve("FreeBSD", null, mapOf("XDG_DATA_HOME" to "/data")))
 }

 @Test fun osDetectionKeepsTheUpstreamCaseSensitiveContainsRules() {
  assertEquals("/home/tester/.local/share/.shatteredpixel/shattered-pixel-dungeon", resolve("GNU/Linux"))
  assertEquals("/Users/Tester/Library/Application Support/Shattered Pixel Dungeon", resolve("Mac", "/Users/Tester"))
  listOf("Darwin", "macOS", "linux", "windows 11", "Plan 9", "", "Android").forEach { unsupported ->
   val error = assertFailsWith<IllegalArgumentException> { resolve(unsupported) }
   assertTrue(error.message!!.contains("No verified Shattered default save directory"))
  }
 }

 @Test fun perGamePolicyCanBeReplacedWithoutReadingHostProperties() {
  val context = SavePathContext("Synthetic OS", null)
  val custom: GameSavePaths = GameSavePaths { input ->
   assertEquals(context, input)
   "/synthetic/other-game"
  }
  assertEquals("/synthetic/other-game", custom.resolve(context))
 }
}
