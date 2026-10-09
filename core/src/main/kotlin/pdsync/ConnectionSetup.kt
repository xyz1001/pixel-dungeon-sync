package pdsync

import java.io.IOException

/** Only fresh setup uses this automatic namespace; persisted/injected profiles remain unchanged. */
fun newConnectionConfig(adapter: GameAdapter = Shattered) = AppConfig(endpoint = "", profile = adapter.defaultRemoteProfile)

/** A dialog draft. Secrets are session-only and are deliberately excluded from debug output. */
data class ConnectionDraft(val config: AppConfig, val password: String = "") {
 override fun toString() = "ConnectionDraft(config=$config, password=<redacted>)"
 /** An identity edit must never carry a different service/account's active password with it. */
 fun editingConfig(next: AppConfig): ConnectionDraft {
  val identityChanged = next.endpoint != config.endpoint || next.username != config.username
  return ConnectionDraft(if (identityChanged) next.copy(rememberPassword = false) else next, if (identityChanged) "" else password)
 }
}

class AppliedConnection(val config: AppConfig, val password: String) {
 override fun toString() = "AppliedConnection(config=$config, password=<redacted>)"
}

sealed interface ConnectionTestResult {
 data class Success(val hasRemote: Boolean): ConnectionTestResult
 data class Failure(val message: String, val status: Int? = null): ConnectionTestResult
}

/**
 * Transactional connection model: testConnection is read-only; connect explicitly provisions only the app namespace.
 * Only apply writes configuration, after the current connection has been verified.
 * Call testConnection off the UI thread. Every edit invalidates its proof, including credential,
 * JAR and remember-checkbox edits. The UI should share its operation-busy guard with this model.
 * Dropping password references is best effort; JVM Strings cannot be securely erased in place.
 */
class ConnectionSetup(
 initial: AppConfig? = null,
 private val game: String = "shattered",
 private val probe: ((ConnectionDraft) -> RemoteHead?)? = null
) {
 private val original = (initial ?: newConnectionConfig()).copy(saveRoot = "", version = null)
 private val lock = Any()
 private var current = ConnectionDraft(original)
 private var running = false
 private var closed = false
 private var revision = 0L
 private var testedRevision: Long? = null
 private var result: ConnectionTestResult? = null
 val draft: ConnectionDraft get() = synchronized(lock) { current }
 val busy: Boolean get() = synchronized(lock) { running }
 val canApply: Boolean get() = synchronized(lock) { !closed && !running && testedRevision == revision && result is ConnectionTestResult.Success }
 val lastTest: ConnectionTestResult? get() = synchronized(lock) { result }

 fun update(config: AppConfig, password: String) = synchronized(lock) {
  check(!closed) { "设置窗口已关闭，请重新打开" }
  check(!running) { "正在测试连接，请稍候" }
  val next = ConnectionDraft(config.copy(saveRoot = "", version = null), password)
  if (next != current) {
   current = next; revision++; testedRevision = null; result = null
  }
 }

 /** Automatic/reconnection checks never create remote directories. */
 fun testConnection(): ConnectionTestResult = checkConnection(createNamespace = false)

 /** Explicit user Connect action may create the two app-owned remote collections. */
 fun connect(): ConnectionTestResult = checkConnection(createNamespace = true)

 private fun checkConnection(createNamespace: Boolean): ConnectionTestResult {
  val draft: ConnectionDraft
  val ticket: Long
  synchronized(lock) {
   check(!closed) { "设置窗口已关闭，请重新打开" }
   check(!running) { "正在测试连接，请稍候" }
   running = true; testedRevision = null; result = null
   draft = current; ticket = revision
  }
  val stillCurrent = { synchronized(lock) { !closed && revision == ticket } }
  val tested = try {
   val validated = draft.copy(config = draft.config.validated())
   require(game.matches(Regex("[A-Za-z0-9_-]+")))
   require(!validated.config.username.contains(':'))
   val hasRemote = if (probe != null) probe.invoke(validated) != null else {
    val store = AppendOnlyWebDav(validated.config.endpoint, game, validated.config.profile, validated.config.username, validated.password)
    if (createNamespace) store.ensureNamespace(stillCurrent)
    check(stillCurrent()) { "连接已取消" }
    if (store.legacyPresent()) throw LegacyProtocolException()
    val cursors = mutableSetOf<String>(); val ids = mutableSetOf<String>()
    var cursor: String? = null; var pages = 0; var listed = 0
    do {
     require(++pages <= MAX_COMMITS)
     check(stillCurrent()) { "连接已取消" }
     val page = store.listCommits(cursor)
     listed += page.ids.size; require(listed <= MAX_COMMITS)
     ids.addAll(page.ids); require(ids.size <= MAX_COMMITS)
     cursor = page.next
     if (cursor != null) require(cursors.add(cursor))
    } while (cursor != null)
    ids.isNotEmpty()
   }
   ConnectionTestResult.Success(hasRemote = hasRemote)
  } catch (e: NamespaceProvisioningException) {
   ConnectionTestResult.Failure(when (e.reason) {
    NamespaceFailureReason.ROOT_MISSING -> "这个地址下没有可用的 WebDAV 根目录（HTTP 404），请检查服务地址。不会创建上层目录。"
    NamespaceFailureReason.NOT_COLLECTION -> "服务地址或同步路径被同名文件占用，不能用作同步文件夹。请检查 WebDAV 地址。"
    NamespaceFailureReason.AUTHENTICATION -> "认证失败（HTTP 401），请检查用户名和密码。"
    NamespaceFailureReason.FORBIDDEN -> "账号没有访问或创建同步目录的权限（HTTP 403），请检查 WebDAV 权限。"
    NamespaceFailureReason.PARENT_MISSING -> "服务器找不到同步文件夹的父目录（HTTP 409），请检查服务地址及目录状态后重试。"
    NamespaceFailureReason.HTTP_ERROR -> "服务器未接受目录创建或验证（HTTP ${e.statusCode}），请检查 WebDAV 服务后重试。"
    NamespaceFailureReason.UNSAFE_RESPONSE -> "服务器返回的目录验证信息无效，已停止创建。请检查 WebDAV 地址及服务兼容性。"
    NamespaceFailureReason.VERIFICATION_FAILED -> "尚不能确认同步文件夹已经创建。请重试；不会覆盖同名文件或上传存档。"
    NamespaceFailureReason.NETWORK -> "连接中断，无法确认同步目录状态。请检查网络后重试；已创建的空文件夹会保留。"
    NamespaceFailureReason.CANCELLED -> "连接已取消。已创建的空同步文件夹会保留。"
   }, e.statusCode)
  } catch (_: LegacyProtocolException) {
   ConnectionTestResult.Failure("发现旧版 head.json，请停用旧客户端后明确安排迁移。未上传或改写存档；本次可能已创建的空应用目录会保留。")
  } catch (e: WebDavException) {
   ConnectionTestResult.Failure(when (e.statusCode) {
    401 -> "认证失败（HTTP 401），请检查用户名和密码。"
    403 -> "服务拒绝访问（HTTP 403），请检查账号权限或重新输入密码。"
    else -> "连接测试失败（HTTP ${e.statusCode}），请检查 WebDAV 地址及服务状态。"
   }, e.statusCode)
  } catch (_: IOException) {
   ConnectionTestResult.Failure("无法连接 WebDAV：请检查网络、地址、证书及服务状态后重试。")
  } catch (_: Exception) {
   // Do not echo arbitrary URL/parser/transport exceptions that might contain entered secrets.
   ConnectionTestResult.Failure("服务地址、账号或云端目录列表无效。请检查 WebDAV 地址，使用 HTTPS（仅本机允许 HTTP）；用户名不能含冒号。")
  } catch (e: Throwable) {
   synchronized(lock) { running = false }
   throw e
  }
  synchronized(lock) {
   // Publish result and end busy together, so a later test cannot be overwritten by this one.
   running = false
   if (!closed && revision == ticket) {
    result = tested
    if (tested is ConnectionTestResult.Success) testedRevision = ticket
   }
  }
  return tested
 }

 fun apply(configFile: ConfigFile, rememberPasswordStored: Boolean = draft.config.rememberPassword): AppliedConnection = synchronized(lock) {
  check(!closed && !running && testedRevision == revision && result is ConnectionTestResult.Success) { "请先成功测试当前配置，再保存" }
  require(!rememberPasswordStored || current.config.rememberPassword) { "未请求保存密码" }
  val safe = current.config.validated().copy(saveRoot = "", rememberPassword = rememberPasswordStored)
  // Failed persistence leaves the tested draft available for retry, without claiming it was applied.
  configFile.save(safe)
  val applied = AppliedConnection(safe, current.password)
  current = ConnectionDraft(safe); testedRevision = null; result = null; revision++; closed = true
  applied
 }

 /** Cancel invalidates results and clears the draft; any already-created empty remote folders remain. */
 fun cancel() = synchronized(lock) {
  current = ConnectionDraft(original); testedRevision = null; result = null; revision++; closed = true
 }
}
