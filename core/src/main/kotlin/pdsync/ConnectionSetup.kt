package pdsync

import java.io.IOException

/** A dialog draft. Secrets are session-only and are deliberately excluded from debug output. */
data class ConnectionDraft(val config: AppConfig, val password: String = "") {
 override fun toString() = "ConnectionDraft(config=$config, password=<redacted>)"
 /** An identity edit must never carry a different service/account's active password with it. */
 fun editingConfig(next: AppConfig) = ConnectionDraft(next,
  if (next.endpoint != config.endpoint || next.username != config.username) "" else password)
}

class AppliedConnection(val config: AppConfig, val password: String) {
 override fun toString() = "AppliedConnection(config=$config, password=<redacted>)"
}

sealed interface ConnectionTestResult {
 data class Success(val hasRemote: Boolean): ConnectionTestResult
 data class Failure(val message: String, val status: Int? = null): ConnectionTestResult
}

/**
 * Transactional dialog model: testing only reads head.json; only an explicit apply writes config.
 * Call testConnection off the UI thread. Every edit invalidates its proof, including credential,
 * JAR and remember-checkbox edits. The UI should share its operation-busy guard with this model.
 * Dropping password references is best effort; JVM Strings cannot be securely erased in place.
 */
class ConnectionSetup(
 initial: AppConfig? = null,
 private val game: String = "shattered",
 private val probe: ((ConnectionDraft) -> RemoteHead?)? = null
) {
 private val original = (initial ?: AppConfig(endpoint = "", profile = "default")).copy(saveRoot = "", version = null)
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

 fun testConnection(): ConnectionTestResult {
  val draft: ConnectionDraft
  val ticket: Long
  synchronized(lock) {
   check(!closed) { "设置窗口已关闭，请重新打开" }
   check(!running) { "正在测试连接，请稍候" }
   running = true; testedRevision = null; result = null
   draft = current; ticket = revision
  }
  val tested = try {
   val validated = draft.copy(config = draft.config.validated())
   require(game.matches(Regex("[A-Za-z0-9_-]+")))
   require(!validated.config.username.contains(':'))
   val head = if (probe != null) probe.invoke(validated) else WebDav(
    validated.config.endpoint, game, validated.config.profile, validated.config.username, validated.password
   ).head()
   ConnectionTestResult.Success(hasRemote = head != null)
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
   ConnectionTestResult.Failure("服务地址、档案名、用户名或远端档案内容无效。需使用 HTTPS（本机可用 HTTP），档案名为 1–64 位字母、数字、下划线或连字符，用户名不能含冒号。")
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

 /** Cancelling invalidates in-flight results and clears the draft secret without writing anything. */
 fun cancel() = synchronized(lock) {
  current = ConnectionDraft(original); testedRevision = null; result = null; revision++; closed = true
 }
}
