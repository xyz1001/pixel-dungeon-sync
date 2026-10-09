package pdsync

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only this application's exact endpoint/account entry is ever queried or changed. */
interface SecureCredentialStore {
 fun read(endpoint: String, username: String): SecretReadResult
 fun save(endpoint: String, username: String, password: String): SecretWriteResult
 fun delete(endpoint: String, username: String): SecretWriteResult
}

enum class SecretStoreFailure(val message: String) {
 UNAVAILABLE("系统安全凭据服务不可用；密码仅供本次会话使用，未写入普通配置。"),
 LOCKED("系统凭据库已锁定或拒绝访问。请在系统中解锁后重试；密码未写入普通配置。"),
 TIMEOUT("系统凭据服务未及时响应，无法确认操作结果。请解锁凭据库后重试。"),
 INVALID_INPUT("服务地址或账号无效，未访问系统凭据库。"),
 PASSWORD_TOO_LONG("密码超过安全存储支持的 1024 字节；仅供本次会话使用。"),
 INVALID_SECRET("已保存的凭据格式无效，未使用它。请重新输入密码。"),
 FAILED("系统凭据操作失败，无法确认操作结果；未将密码写入普通配置。")
}

sealed interface SecretReadResult {
 /** Intentionally not a data class: logging a result must never print the password. */
 class Found(val password: String) : SecretReadResult {
  override fun toString() = "SecretReadResult.Found(<redacted>)"
 }
 data object NotFound : SecretReadResult
 data class Unavailable(val reason: SecretStoreFailure) : SecretReadResult {
  val message get() = reason.message
 }
}

sealed interface SecretWriteResult {
 data object Success : SecretWriteResult
 data class Unavailable(val reason: SecretStoreFailure) : SecretWriteResult {
  val message get() = reason.message
 }
}

internal enum class SecretPlatform { WINDOWS, MACOS, LINUX, UNSUPPORTED }
internal enum class SecretOperation { READ, SAVE, DELETE }

/** No shell, secret environment variables, temporary secret files, or inherited console output. */
internal fun interface SecretCommandRunner {
 fun run(arguments: List<String>, input: ByteArray): SecretCommandResult
}

internal class SecretCommandResult(
 val exitCode: Int? = 0,
 val stdout: ByteArray = byteArrayOf(),
 val stderr: ByteArray = byteArrayOf(),
 val failure: SecretStoreFailure? = null
) : AutoCloseable {
 override fun close() { stdout.fill(0); stderr.fill(0) }
 override fun toString() = "SecretCommandResult(exitCode=$exitCode, failure=$failure, output=<redacted>)"
}

internal class ProcessSecretCommandRunner(private val timeoutMillis: Long = 15_000) : SecretCommandRunner {
 override fun run(arguments: List<String>, input: ByteArray): SecretCommandResult {
  val process = try {
   ProcessBuilder(arguments).apply {
    // Stable diagnostics, used only internally to distinguish locked stores from missing entries.
    if (!System.getProperty("os.name").startsWith("Windows", true)) environment()["LC_ALL"] = "C"
   }.start()
  } catch (_: Exception) { return SecretCommandResult(failure = SecretStoreFailure.UNAVAILABLE) }
  val workers = Executors.newFixedThreadPool(3) { runnable -> Thread(runnable, "credential-pipe").apply { isDaemon = true } }
  var stdout: ByteArray? = null
  var stderr: ByteArray? = null
  fun readBounded(stream: InputStream): ByteArray = stream.use {
   val bytes = it.readNBytes(16 * 1024 + 1)
   if (bytes.size > 16 * 1024) { bytes.fill(0); process.destroyForcibly(); error("Credential output exceeded limit") }
   bytes
  }
  val out = workers.submit<ByteArray> { readBounded(process.inputStream).also { stdout = it } }
  val err = workers.submit<ByteArray> { readBounded(process.errorStream).also { stderr = it } }
  val writer = workers.submit { process.outputStream.use { it.write(input) } }
  var transferred = false
  try {
   if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
    process.destroyForcibly()
    return SecretCommandResult(failure = SecretStoreFailure.TIMEOUT)
   }
   // A failing helper may close stdin early. Preserve its safe failure classification.
   if (process.exitValue() == 0) writer.get(1, TimeUnit.SECONDS)
   val result = SecretCommandResult(process.exitValue(), out.get(1, TimeUnit.SECONDS), err.get(1, TimeUnit.SECONDS))
   transferred = true
   return result
  } catch (_: InterruptedException) {
   Thread.currentThread().interrupt()
   return SecretCommandResult(failure = SecretStoreFailure.FAILED)
  } catch (_: Exception) {
   return SecretCommandResult(failure = SecretStoreFailure.FAILED)
  } finally {
   process.destroyForcibly()
   runCatching { process.outputStream.close() }
   runCatching { process.inputStream.close() }
   runCatching { process.errorStream.close() }
   workers.shutdownNow()
   try { workers.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
   // Buffers returned on success are wiped by the caller after decoding.
   if (!transferred) { stdout?.fill(0); stderr?.fill(0) }
  }
 }
}

/**
 * OS-owned persistence only. There is deliberately no file-based or encryption-key fallback.
 * Each subprocess is time-bounded; read/delete can make two calls. Invoke off the UI thread.
 * Passwords travel over private process pipes. The ASCII envelope is an encoding, NOT encryption;
 * protection at rest is provided by Credential Manager / Keychain / Secret Service.
 */
class SystemSecretStore internal constructor(
 private val platform: SecretPlatform,
 private val runner: SecretCommandRunner,
 private val executable: String?
) : SecureCredentialStore {
 companion object {
  internal const val SERVICE = "pixel-dungeon-sync.webdav.v1"
  internal const val ENVELOPE = "pdsync-v1:"
  private const val MAX_PASSWORD_BYTES = 1024

  fun create(): SecureCredentialStore {
   val os = System.getProperty("os.name").lowercase(Locale.ROOT)
   val platform = when {
    os.startsWith("windows") -> SecretPlatform.WINDOWS
    os.startsWith("mac") || os == "darwin" -> SecretPlatform.MACOS
    os.startsWith("linux") -> SecretPlatform.LINUX
    else -> SecretPlatform.UNSUPPORTED
   }
   // Fixed OS locations, never a helper from the working directory or an arbitrary PATH entry.
   val candidates = when (platform) {
    SecretPlatform.WINDOWS -> listOfNotNull(System.getenv("SystemRoot")?.let {
     runCatching { Path.of(it).takeIf { root -> root.isAbsolute }?.resolve("System32/WindowsPowerShell/v1.0/powershell.exe")?.toString() }.getOrNull()
    })
    SecretPlatform.MACOS -> listOf("/usr/bin/security")
    SecretPlatform.LINUX -> listOf("/usr/bin/secret-tool", "/bin/secret-tool")
    SecretPlatform.UNSUPPORTED -> emptyList()
   }
   val executable = candidates.firstOrNull { runCatching { Files.isRegularFile(Path.of(it)) && Files.isExecutable(Path.of(it)) }.getOrDefault(false) }
   return SystemSecretStore(platform, ProcessSecretCommandRunner(), executable)
  }

  internal fun credentialKey(endpoint: String, username: String): String {
   require(endpoint.length <= 8192 && username.length <= 1024)
   val url = endpoint.toHttpUrl()
   require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
   require(url.scheme == "https" || url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost", "::1"))
   val digest = MessageDigest.getInstance("SHA-256")
   listOf(SERVICE, url.toString().trimEnd('/'), username).forEach { value ->
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
   }
   return digest.digest().joinToString("") { "%02x".format(it) }
  }
 }

 override fun read(endpoint: String, username: String): SecretReadResult {
  val key = runCatching { credentialKey(endpoint, username) }.getOrElse { return SecretReadResult.Unavailable(SecretStoreFailure.INVALID_INPUT) }
  return execute(SecretOperation.READ, key).use { result ->
   if (looksMissingOnLinux(result)) return@use linuxRemainingEntryFailure(key)?.let { SecretReadResult.Unavailable(it) } ?: SecretReadResult.NotFound
   if (isMissing(SecretOperation.READ, result)) return@use SecretReadResult.NotFound
   failure(result)?.let { return@use SecretReadResult.Unavailable(it) }
   val text = runCatching { result.stdout.decodeToString(throwOnInvalidSequence = true).removeSuffix("\n").removeSuffix("\r") }.getOrNull()
    ?: return@use SecretReadResult.Unavailable(SecretStoreFailure.INVALID_SECRET)
   if (!text.startsWith(ENVELOPE)) return@use SecretReadResult.Unavailable(SecretStoreFailure.INVALID_SECRET)
   val decoded = runCatching { Base64.getDecoder().decode(text.removePrefix(ENVELOPE)) }.getOrNull()
    ?: return@use SecretReadResult.Unavailable(SecretStoreFailure.INVALID_SECRET)
   try {
    if (decoded.size > MAX_PASSWORD_BYTES) SecretReadResult.Unavailable(SecretStoreFailure.INVALID_SECRET)
    else runCatching { SecretReadResult.Found(decoded.decodeToString(throwOnInvalidSequence = true)) }
     .getOrElse { SecretReadResult.Unavailable(SecretStoreFailure.INVALID_SECRET) }
   } finally { decoded.fill(0) }
  }
 }

 override fun save(endpoint: String, username: String, password: String): SecretWriteResult {
  val key = runCatching { credentialKey(endpoint, username) }.getOrElse { return SecretWriteResult.Unavailable(SecretStoreFailure.INVALID_INPUT) }
  val bytes = runCatching { password.encodeToByteArray(throwOnInvalidSequence = true) }.getOrElse { return SecretWriteResult.Unavailable(SecretStoreFailure.INVALID_SECRET) }
  try {
   if (bytes.size > MAX_PASSWORD_BYTES) return SecretWriteResult.Unavailable(SecretStoreFailure.PASSWORD_TOO_LONG)
   val encoded = ENVELOPE + Base64.getEncoder().encodeToString(bytes)
   return execute(SecretOperation.SAVE, key, encoded).use { result ->
    failure(result)?.let { SecretWriteResult.Unavailable(it) } ?: SecretWriteResult.Success
   }
  } finally { bytes.fill(0) }
 }

 override fun delete(endpoint: String, username: String): SecretWriteResult {
  val key = runCatching { credentialKey(endpoint, username) }.getOrElse { return SecretWriteResult.Unavailable(SecretStoreFailure.INVALID_INPUT) }
  return execute(SecretOperation.DELETE, key).use { result ->
   // libsecret clear removes unlocked matches only. Never call a locked leftover "forgotten".
   if (platform == SecretPlatform.LINUX && result.failure == null && (result.exitCode == 0 || looksMissingOnLinux(result))) {
    linuxRemainingEntryFailure(key)?.let { SecretWriteResult.Unavailable(it) } ?: SecretWriteResult.Success
   } else if (isMissing(SecretOperation.DELETE, result)) SecretWriteResult.Success
   else failure(result)?.let { SecretWriteResult.Unavailable(it) } ?: SecretWriteResult.Success
  }
 }

 private fun looksMissingOnLinux(result: SecretCommandResult) = platform == SecretPlatform.LINUX && result.failure == null &&
  result.exitCode == 1 && result.stderr.isEmpty() && result.stdout.isEmpty()

 private fun linuxRemainingEntryFailure(key: String): SecretStoreFailure? {
  val tool = executable ?: return SecretStoreFailure.UNAVAILABLE
  // --all includes locked matches; never --unlock. This is restricted to our same exact scope.
  // Its output may contain our own secret, so it is captured, never logged, and always wiped.
  val result = try {
   runner.run(listOf(tool, "search", "--all", "application", SERVICE, "account", key), byteArrayOf())
  } catch (_: Exception) { return SecretStoreFailure.FAILED }
  return result.use {
   failure(it)?.let { problem -> return@use problem }
   if (it.stdout.isEmpty() && it.stderr.isEmpty()) null
   else if ("locked" in it.stderr.decodeToString().lowercase(Locale.ROOT)) SecretStoreFailure.LOCKED
   else SecretStoreFailure.FAILED
  }
 }

 private fun execute(operation: SecretOperation, key: String, value: String = ""): SecretCommandResult {
  val tool = executable ?: return SecretCommandResult(failure = SecretStoreFailure.UNAVAILABLE)
  val args: List<String>
  val input: ByteArray
  when (platform) {
   SecretPlatform.LINUX -> {
    val attributes = listOf("application", SERVICE, "account", key)
    args = listOf(tool) + when (operation) {
     SecretOperation.READ -> listOf("lookup")
     SecretOperation.SAVE -> listOf("store", "--label=Pixel Dungeon Sync WebDAV")
     SecretOperation.DELETE -> listOf("clear")
    } + attributes
    input = if (operation == SecretOperation.SAVE) value.encodeToByteArray() else byteArrayOf()
   }
   SecretPlatform.MACOS -> {
    if (operation == SecretOperation.SAVE) {
     // Apple's -i parser reads this single command from a pipe. Base64 excludes metacharacters;
     // no shell, -v, permissive ACL (-A), or password-bearing OS command argument is used.
     args = listOf(tool, "-q", "-i")
     input = "add-generic-password -U -s $SERVICE -a $key -w $value\n".encodeToByteArray()
    } else {
     args = listOf(tool, if (operation == SecretOperation.READ) "find-generic-password" else "delete-generic-password", "-s", SERVICE, "-a", key) +
      if (operation == SecretOperation.READ) listOf("-w") else emptyList()
     input = byteArrayOf()
    }
   }
   SecretPlatform.WINDOWS -> {
    // The encoded script is constant. The operation, hash, and secret are DATA read from stdin.
    args = listOf(tool, "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", WINDOWS_SCRIPT_BASE64)
    input = "${operation.name}\n$key\n$value\n".encodeToByteArray()
   }
   SecretPlatform.UNSUPPORTED -> return SecretCommandResult(failure = SecretStoreFailure.UNAVAILABLE)
  }
  return try { runner.run(args, input) } catch (_: Exception) { SecretCommandResult(failure = SecretStoreFailure.FAILED) }
  finally { input.fill(0) }
 }

 private fun isMissing(operation: SecretOperation, result: SecretCommandResult): Boolean {
  if (result.failure != null || operation == SecretOperation.SAVE) return false
  return when (platform) {
   SecretPlatform.WINDOWS -> result.exitCode == 10
   SecretPlatform.MACOS -> result.exitCode == 44 // errSecItemNotFound (-25300), modulo 256
   SecretPlatform.LINUX -> false // Absence needs the locked-item-aware check above.
   SecretPlatform.UNSUPPORTED -> false
  }
 }

 private fun failure(result: SecretCommandResult): SecretStoreFailure? {
  result.failure?.let { return it }
  if (result.exitCode == 0) return null
  val diagnostic = result.stderr.decodeToString().lowercase(Locale.ROOT)
  return when {
   platform == SecretPlatform.WINDOWS && result.exitCode == 20 -> SecretStoreFailure.LOCKED
   platform == SecretPlatform.WINDOWS && result.exitCode == 21 -> SecretStoreFailure.UNAVAILABLE
   listOf("locked", "denied", "interaction is not allowed", "user canceled", "user cancelled").any { it in diagnostic } -> SecretStoreFailure.LOCKED
   listOf("dbus", "d-bus", "cannot autolaunch", "serviceunknown", "org.freedesktop.secrets", "no such interface").any { it in diagnostic } -> SecretStoreFailure.UNAVAILABLE
   else -> SecretStoreFailure.FAILED
  }
 }
}

/* Native Windows Credential Manager API, invoked in the current user's logon session.
 * CRED_PERSIST_LOCAL_MACHINE survives restart but stays in this user's credential set.
 * https://learn.microsoft.com/windows/win32/api/wincred/nf-wincred-credwritew
 * https://learn.microsoft.com/windows/win32/api/wincred/nf-wincred-credreadw
 */
internal val WINDOWS_SCRIPT: String = """
${'$'}ErrorActionPreference = 'Stop'
try {
 Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
public static class PixelDungeonCredentials {
 [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
 public struct Credential {
  public uint Flags, Type;
  public string TargetName, Comment;
  public uint LastWrittenLow, LastWrittenHigh;
  public uint CredentialBlobSize;
  public IntPtr CredentialBlob;
  public uint Persist, AttributeCount;
  public IntPtr Attributes;
  public string TargetAlias, UserName;
 }
 [DllImport("Advapi32.dll", EntryPoint="CredReadW", CharSet=CharSet.Unicode, SetLastError=true)]
 [return: MarshalAs(UnmanagedType.Bool)] static extern bool Read(string target, uint type, uint flags, out IntPtr credential);
 [DllImport("Advapi32.dll", EntryPoint="CredWriteW", CharSet=CharSet.Unicode, SetLastError=true)]
 [return: MarshalAs(UnmanagedType.Bool)] static extern bool Write(ref Credential credential, uint flags);
 [DllImport("Advapi32.dll", EntryPoint="CredDeleteW", CharSet=CharSet.Unicode, SetLastError=true)]
 [return: MarshalAs(UnmanagedType.Bool)] static extern bool Delete(string target, uint type, uint flags);
 [DllImport("Advapi32.dll")] static extern void CredFree(IntPtr buffer);
 static int Failure() {
  int code = Marshal.GetLastWin32Error();
  return code == 1168 ? 10 : code == 5 ? 20 : code == 1312 ? 21 : 22;
 }
 public static int Run() {
  string operation = Console.ReadLine(), key = Console.ReadLine(), value = Console.ReadLine();
  if (key == null || !Regex.IsMatch(key, "\\A[0-9a-f]{64}\\z")) return 22;
  string target = "pixel-dungeon-sync.webdav.v1/" + key;
  if (operation == "DELETE") return Delete(target, 1, 0) ? 0 : Failure();
  if (operation == "READ") {
   IntPtr ptr;
   if (!Read(target, 1, 0, out ptr)) return Failure();
   byte[] bytes = null;
   try {
    Credential credential = (Credential)Marshal.PtrToStructure(ptr, typeof(Credential));
    if (credential.CredentialBlobSize > 4096) return 22;
    bytes = new byte[credential.CredentialBlobSize];
    Marshal.Copy(credential.CredentialBlob, bytes, 0, bytes.Length);
    Console.Write(Encoding.UTF8.GetString(bytes));
    return 0;
   } finally { if (bytes != null) Array.Clear(bytes, 0, bytes.Length); CredFree(ptr); }
  }
  if (operation == "SAVE" && value != null && value.Length <= 2048 && value.StartsWith("pdsync-v1:")) {
   byte[] bytes = Encoding.UTF8.GetBytes(value);
   IntPtr blob = Marshal.AllocHGlobal(bytes.Length);
   try {
    Marshal.Copy(bytes, 0, blob, bytes.Length);
    Credential credential = new Credential();
    credential.Type = 1; credential.TargetName = target; credential.UserName = key;
    credential.Persist = 2; credential.CredentialBlob = blob;
    credential.CredentialBlobSize = (uint)bytes.Length;
    return Write(ref credential, 0) ? 0 : Failure();
   } finally {
    Array.Clear(bytes, 0, bytes.Length); Marshal.Copy(bytes, 0, blob, bytes.Length); Marshal.FreeHGlobal(blob);
   }
  }
  return 22;
 }
}
'@
 ${'$'}result = [PixelDungeonCredentials]::Run()
 exit ${'$'}result
} catch { exit 22 }
""".trimIndent()

private val WINDOWS_SCRIPT_BASE64: String = Base64.getEncoder().encodeToString(WINDOWS_SCRIPT.toByteArray(Charsets.UTF_16LE))
