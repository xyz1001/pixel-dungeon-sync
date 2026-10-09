package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class SecretStoreTest {
 private val endpoint = "https://example.invalid/dav"
 private val username = "test-account"
 private val password = " fake-only-密码\n'\"\\${'$'}; `\u0000🌱 "
 private fun envelope(value: String) = SystemSecretStore.ENVELOPE + Base64.getEncoder().encodeToString(value.encodeToByteArray())
 private class Call(val args: List<String>, val input: ByteArray, val originalInput: ByteArray)
 private class FakeRunner : SecretCommandRunner {
  val calls = mutableListOf<Call>()
  var response: () -> SecretCommandResult = { SecretCommandResult() }
  override fun run(arguments: List<String>, input: ByteArray): SecretCommandResult {
   calls += Call(arguments.toList(), input.copyOf(), input)
   return response()
  }
 }
 private fun store(platform: SecretPlatform, fake: FakeRunner) = SystemSecretStore(platform, fake, when (platform) {
  SecretPlatform.MACOS -> "/usr/bin/security"
  SecretPlatform.LINUX -> "/usr/bin/secret-tool"
  SecretPlatform.WINDOWS -> "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
  SecretPlatform.UNSUPPORTED -> null
 })

 @Test fun keyUsesCanonicalEndpointAndExactAccount() {
  val first = SystemSecretStore.credentialKey("https://EXAMPLE.invalid:443/dav/", username)
  assertEquals(first, SystemSecretStore.credentialKey(endpoint, username))
  assertTrue(first.matches(Regex("[0-9a-f]{64}")))
  assertNotEquals(first, SystemSecretStore.credentialKey("https://other.invalid/dav", username))
  assertNotEquals(first, SystemSecretStore.credentialKey("https://example.invalid/other", username))
  assertNotEquals(first, SystemSecretStore.credentialKey("https://example.invalid:444/dav", username))
  assertNotEquals(first, SystemSecretStore.credentialKey(endpoint, "other-account"))
  assertNotEquals(first, SystemSecretStore.credentialKey(endpoint, "$username "))
 }

 @Test fun invalidScopesNeverLaunchAProcess() {
  val fake = FakeRunner(); val store = store(SecretPlatform.LINUX, fake)
  for (invalid in listOf("http://example.invalid", "https://u:password@example.invalid", "https://example.invalid?token=x", "https://example.invalid/#x", "invalid")) {
   assertEquals(SecretStoreFailure.INVALID_INPUT, assertIs<SecretReadResult.Unavailable>(store.read(invalid, username)).reason)
   assertIs<SecretWriteResult.Unavailable>(store.save(invalid, username, password))
   assertIs<SecretWriteResult.Unavailable>(store.delete(invalid, username))
  }
  assertTrue(fake.calls.isEmpty())
 }

 @Test fun everyPlatformPassesSecretsOnlyThroughStdinAndWipesBuffers() {
  for (platform in listOf(SecretPlatform.LINUX, SecretPlatform.MACOS, SecretPlatform.WINDOWS)) {
   val fake = FakeRunner(); val store = store(platform, fake)
   assertEquals(SecretWriteResult.Success, store.save(endpoint, username, password))
   val call = fake.calls.single()
   val arguments = call.args.joinToString(" ")
   assertFalse(password in arguments); assertFalse(envelope(password) in arguments)
   assertFalse(username in arguments); assertFalse(endpoint in arguments)
   assertTrue(envelope(password) in call.input.decodeToString())
   assertTrue(call.originalInput.all { it == 0.toByte() })
   assertFalse("-A" in call.args); assertFalse("-v" in call.args)
   assertFalse(call.args.any { it == "cmd.exe" || it == "sh" || it == "osascript" })
  }
 }

 @Test fun linuxOperationsUseIdenticalApplicationAndAccountFilters() {
  val fake = FakeRunner(); val store = store(SecretPlatform.LINUX, fake)
  store.save(endpoint, username, password)
  fake.response = { SecretCommandResult(stdout = envelope(password).encodeToByteArray()) }
  assertEquals(password, assertIs<SecretReadResult.Found>(store.read(endpoint, username)).password)
  fake.response = { SecretCommandResult() }; store.delete(endpoint, username)
  val scope = listOf("application", SystemSecretStore.SERVICE, "account", SystemSecretStore.credentialKey(endpoint, username))
  assertTrue(fake.calls.all { it.args.takeLast(4) == scope })
  assertEquals(listOf("store", "lookup", "clear", "search"), fake.calls.map { it.args[1] })
  assertEquals("--all", fake.calls.last().args[2]); assertFalse(fake.calls.any { "--unlock" in it.args })
  assertEquals(envelope(password), fake.calls.first().input.decodeToString())
  assertTrue(fake.calls.drop(1).all { it.input.isEmpty() })
 }

 @Test fun macSaveIsOneNonVerboseStdinCommandWithSafeAsciiEnvelope() {
  val fake = FakeRunner(); val store = store(SecretPlatform.MACOS, fake)
  store.save(endpoint, username, password)
  val call = fake.calls.single()
  assertEquals(listOf("/usr/bin/security", "-q", "-i"), call.args)
  assertEquals("add-generic-password -U -s ${SystemSecretStore.SERVICE} -a ${SystemSecretStore.credentialKey(endpoint, username)} -w ${envelope(password)}\n", call.input.decodeToString())
  assertEquals(1, call.input.count { it == '\n'.code.toByte() })
  fake.response = { SecretCommandResult(stdout = (envelope(password) + "\n").encodeToByteArray()) }
  assertEquals(password, assertIs<SecretReadResult.Found>(store.read(endpoint, username)).password)
  store.delete(endpoint, username)
  assertEquals("find-generic-password", fake.calls[1].args[1])
  assertEquals("delete-generic-password", fake.calls[2].args[1])
  assertEquals(fake.calls[1].args.drop(2).dropLast(1), fake.calls[2].args.drop(2))
 }

 @Test fun windowsUsesConstantScriptAndNativeCredentialApis() {
  val fake = FakeRunner(); val store = store(SecretPlatform.WINDOWS, fake)
  store.save(endpoint, username, password); store.save(endpoint, "different-account", "different-fake-password")
  assertEquals(fake.calls[0].args, fake.calls[1].args)
  val script = Base64.getDecoder().decode(fake.calls[0].args.last()).toString(Charsets.UTF_16LE)
  assertEquals(WINDOWS_SCRIPT, script)
  assertTrue("CredWriteW" in script); assertTrue("CredReadW" in script); assertTrue("CredDeleteW" in script)
  assertTrue("credential.Persist = 2" in script)
  assertTrue("Console.ReadLine()" in script)
  assertFalse(password in script); assertFalse(username in script)
  assertEquals(listOf("SAVE", SystemSecretStore.credentialKey(endpoint, username), envelope(password), ""), fake.calls[0].input.decodeToString().split('\n'))
 }

 @Test fun emptyAndUnicodeSecretsRoundTripWithoutTrimming() {
  for (platform in listOf(SecretPlatform.LINUX, SecretPlatform.MACOS, SecretPlatform.WINDOWS)) {
   for (value in listOf("", password, "\n\r\n", " leading and trailing ")) {
    val fake = FakeRunner(); val store = store(platform, fake)
    fake.response = { SecretCommandResult(stdout = envelope(value).encodeToByteArray()) }
    assertEquals(value, assertIs<SecretReadResult.Found>(store.read(endpoint, username)).password)
   }
  }
 }

 @Test fun missingEntryIsDifferentFromUnavailableAndDeleteIsIdempotent() {
  for ((platform, code) in listOf(SecretPlatform.LINUX to 1, SecretPlatform.MACOS to 44, SecretPlatform.WINDOWS to 10)) {
   val fake = FakeRunner(); fake.response = { SecretCommandResult(exitCode = if (platform == SecretPlatform.LINUX && fake.calls.last().args[1] == "search") 0 else code) }
   val store = store(platform, fake)
   assertEquals(SecretReadResult.NotFound, store.read(endpoint, username))
   assertEquals(SecretWriteResult.Success, store.delete(endpoint, username))
   assertIs<SecretWriteResult.Unavailable>(store.save(endpoint, username, password))
  }
 }

 @Test fun lockedUnavailableAndTimeoutNeverPretendToHaveSaved() {
  val fake = FakeRunner(); val store = store(SecretPlatform.LINUX, fake)
  for ((result, expected) in listOf(
   SecretCommandResult(exitCode = 1, stderr = "Cannot autolaunch D-Bus without X11 DISPLAY".encodeToByteArray()) to SecretStoreFailure.UNAVAILABLE,
   SecretCommandResult(exitCode = 1, stderr = "The collection is locked".encodeToByteArray()) to SecretStoreFailure.LOCKED,
   SecretCommandResult(failure = SecretStoreFailure.TIMEOUT) to SecretStoreFailure.TIMEOUT
  )) {
   fake.response = { result }
   assertEquals(expected, assertIs<SecretWriteResult.Unavailable>(store.save(endpoint, username, password)).reason)
  }
  val windows = store(SecretPlatform.WINDOWS, fake)
  fake.response = { SecretCommandResult(exitCode = 20) }
  assertEquals(SecretStoreFailure.LOCKED, assertIs<SecretReadResult.Unavailable>(windows.read(endpoint, username)).reason)
  fake.response = { SecretCommandResult(exitCode = 21) }
  assertEquals(SecretStoreFailure.UNAVAILABLE, assertIs<SecretReadResult.Unavailable>(windows.read(endpoint, username)).reason)
 }

 @Test fun linuxLockedItemsAreNotMistakenForMissingOrSuccessfullyDeleted() {
  for (clearExitCode in listOf(0, 1)) {
   val fake = FakeRunner(); val store = store(SecretPlatform.LINUX, fake)
   fake.response = {
    when (fake.calls.last().args[1]) {
     "search" -> SecretCommandResult(stdout = "[/owned-fake-item]\nlabel = Pixel Dungeon Sync WebDAV\n".encodeToByteArray(), stderr = "Cannot retrieve secret from a locked item".encodeToByteArray())
     "clear" -> SecretCommandResult(exitCode = clearExitCode)
     else -> SecretCommandResult(exitCode = 1)
    }
   }
   assertEquals(SecretStoreFailure.LOCKED, assertIs<SecretReadResult.Unavailable>(store.read(endpoint, username)).reason)
   assertEquals(SecretStoreFailure.LOCKED, assertIs<SecretWriteResult.Unavailable>(store.delete(endpoint, username)).reason)
   assertTrue(fake.calls.all { it.args.takeLast(4) == listOf("application", SystemSecretStore.SERVICE, "account", SystemSecretStore.credentialKey(endpoint, username)) })
  }
 }

 @Test fun linuxDeleteVerifiesAbsenceRatherThanTrustingSuccessExitCode() {
  val fake = FakeRunner(); val store = store(SecretPlatform.LINUX, fake)
  fake.response = {
   if (fake.calls.last().args[1] == "search") SecretCommandResult(stdout = "[/owned-fake-item]\nsecret = ${envelope(password)}\n".encodeToByteArray())
   else SecretCommandResult()
  }
  assertEquals(SecretStoreFailure.FAILED, assertIs<SecretWriteResult.Unavailable>(store.delete(endpoint, username)).reason)
 }

 @Test fun absentHelperAndUnsupportedOsNeverRunCommandsOrWriteFallbackFiles() {
  for (platform in SecretPlatform.entries) {
   val fake = FakeRunner(); val store = SystemSecretStore(platform, fake, null)
   assertEquals(SecretStoreFailure.UNAVAILABLE, assertIs<SecretReadResult.Unavailable>(store.read(endpoint, username)).reason)
   assertIs<SecretWriteResult.Unavailable>(store.save(endpoint, username, password))
   assertIs<SecretWriteResult.Unavailable>(store.delete(endpoint, username))
   assertTrue(fake.calls.isEmpty())
  }
 }

 @Test fun malformedOrOversizedStoredEntriesAreNeverReturnedAsPasswords() {
  val fake = FakeRunner(); val store = store(SecretPlatform.LINUX, fake)
  for (value in listOf(password, "pdsync-v1:%%%", envelope("x".repeat(1025)), "pdsync-v1:/w==")) {
   fake.response = { SecretCommandResult(stdout = value.encodeToByteArray()) }
   assertEquals(SecretStoreFailure.INVALID_SECRET, assertIs<SecretReadResult.Unavailable>(store.read(endpoint, username)).reason)
  }
 }

 @Test fun oversizedPasswordDoesNotRunProcessAndBoundaryIsUtf8Bytes() {
  val fake = FakeRunner(); val store = store(SecretPlatform.LINUX, fake)
  assertEquals(SecretStoreFailure.PASSWORD_TOO_LONG, assertIs<SecretWriteResult.Unavailable>(store.save(endpoint, username, "密".repeat(342))).reason)
  assertEquals(SecretStoreFailure.INVALID_SECRET, assertIs<SecretWriteResult.Unavailable>(store.save(endpoint, username, "\ud800")).reason)
  assertTrue(fake.calls.isEmpty())
  assertEquals(SecretWriteResult.Success, store.save(endpoint, username, "x".repeat(1024)))
 }

 @Test fun diagnosticsAndToStringCannotLeakSecretAndOutputIsWiped() {
  val fake = FakeRunner(); val store = store(SecretPlatform.LINUX, fake)
  val error = SecretCommandResult(exitCode = 1, stderr = "failure with $password ${envelope(password)}".encodeToByteArray())
  fake.response = { error }
  val failure = assertIs<SecretWriteResult.Unavailable>(store.save(endpoint, username, password))
  assertFalse(password in failure.message); assertFalse(envelope(password) in failure.toString())
  assertTrue(error.stderr.all { it == 0.toByte() })
  val success = SecretCommandResult(stdout = envelope(password).encodeToByteArray())
  assertFalse(envelope(password) in success.toString())
  fake.response = { success }
  val read = assertIs<SecretReadResult.Found>(store.read(endpoint, username))
  assertEquals(password, read.password); assertFalse(password in read.toString())
  assertTrue(success.stdout.all { it == 0.toByte() })
 }

 @Test fun subprocessExceptionsAreSanitized() {
  val runner = SecretCommandRunner { _, _ -> error(password) }
  val store = SystemSecretStore(SecretPlatform.LINUX, runner, "/fake/secret-tool")
  assertEquals(SecretStoreFailure.FAILED, assertIs<SecretReadResult.Unavailable>(store.read(endpoint, username)).reason)
  assertEquals(SecretStoreFailure.FAILED, assertIs<SecretWriteResult.Unavailable>(store.save(endpoint, username, password)).reason)
 }

 @Test fun processRunnerHandlesMissingExecutableWithoutExposingInput() {
  ProcessSecretCommandRunner().run(listOf("/pdsync-test-nonexistent/helper"), password.encodeToByteArray()).use {
   assertEquals(SecretStoreFailure.UNAVAILABLE, it.failure)
   assertFalse(password in it.toString())
  }
 }

 @Test fun realProcessRunnerUsesPrivatePipesForOwnedFakeSecret() {
  assumeTrue(Files.isExecutable(Path.of("/bin/cat")))
  val input = password.encodeToByteArray()
  try {
   ProcessSecretCommandRunner().run(listOf("/bin/cat"), input).use {
    assertNull(it.failure); assertEquals(0, it.exitCode)
    assertEquals(password, it.stdout.decodeToString()); assertTrue(it.stderr.isEmpty())
   }
  } finally { input.fill(0) }
 }

 @Test fun realProcessRunnerTimesOutWithoutLeakingInput() {
  assumeTrue(Files.isExecutable(Path.of("/bin/sleep")))
  val input = password.encodeToByteArray()
  try {
   ProcessSecretCommandRunner(timeoutMillis = 50).run(listOf("/bin/sleep", "5"), input).use {
    assertEquals(SecretStoreFailure.TIMEOUT, it.failure)
    assertTrue(it.stdout.isEmpty()); assertTrue(it.stderr.isEmpty())
   }
  } finally { input.fill(0) }
 }

 @Test fun currentLinuxWithoutHelperFailsClosedForOwnedFakeCredential() {
  assumeTrue(System.getProperty("os.name").startsWith("Linux"))
  assumeTrue(!Files.exists(Path.of("/usr/bin/secret-tool")) && !Files.exists(Path.of("/bin/secret-tool")))
  val store = SystemSecretStore.create()
  assertEquals(SecretStoreFailure.UNAVAILABLE, assertIs<SecretWriteResult.Unavailable>(store.save(endpoint, username, password)).reason)
  assertEquals(SecretStoreFailure.UNAVAILABLE, assertIs<SecretReadResult.Unavailable>(store.read(endpoint, username)).reason)
  assertEquals(SecretStoreFailure.UNAVAILABLE, assertIs<SecretWriteResult.Unavailable>(store.delete(endpoint, username)).reason)
 }
}
