package pdsync

import pdsync.i18n.CoreMessages
import java.io.IOException

/** Only fresh setup uses this automatic namespace; persisted/injected profiles remain unchanged. */
fun newConnectionConfig(adapter: GameAdapter = Shattered) = AppConfig(endpoint = "", profile = adapter.defaultRemoteProfile, onboardingProgress = OnboardingProgress.PENDING)

/** A dialog draft. Secrets are session-only and are deliberately excluded from debug output. */
data class ConnectionDraft(val config: AppConfig, val password: String = "") {
 override fun toString() = "ConnectionDraft(config=$config, password=<redacted>)"
 /** An identity edit must never carry a different service/account's active password with it. */
 fun editingConfig(next: AppConfig): ConnectionDraft {
  val identityChanged = next.endpoint != config.endpoint || next.username != config.username
  return ConnectionDraft(if (identityChanged) next.copy(rememberPassword = false, password = null, onboardingProgress = OnboardingProgress.PENDING) else next, if (identityChanged) "" else password)
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
 private val probe: ((ConnectionDraft) -> RemoteHead?)? = null,
 private val beforeRequest: WebDavRequestCheck = WebDavRequestCheck {}
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
  check(!closed) { CoreMessages.SETTINGS_WAS_CLOSED_PLEASE_REOPEN_IT.text() }
  check(!running) { CoreMessages.TESTING_THE_CONNECTION_PLEASE_WAIT.text() }
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
   check(!closed) { CoreMessages.SETTINGS_WAS_CLOSED_PLEASE_REOPEN_IT.text() }
   check(!running) { CoreMessages.TESTING_THE_CONNECTION_PLEASE_WAIT.text() }
   running = true; testedRevision = null; result = null
   draft = current; ticket = revision
  }
  val stillCurrent = { synchronized(lock) { !closed && revision == ticket } }
  val tested = try {
   val validated = draft.copy(config = draft.config.validated())
   require(game.matches(Regex("[A-Za-z0-9_-]+")))
   require(!validated.config.username.contains(':'))
   val hasRemote = if (probe != null) probe.invoke(validated) != null else {
    val store = AppendOnlyWebDav(validated.config.endpoint, game, validated.config.profile, validated.config.username, validated.password, beforeRequest = beforeRequest)
    if (createNamespace) store.ensureNamespace(stillCurrent)
    check(stillCurrent()) { CoreMessages.CONNECTION_CANCELED.text() }
    if (store.legacyPresent()) throw LegacyProtocolException()
    val cursors = mutableSetOf<String>(); val ids = mutableSetOf<String>()
    var cursor: String? = null; var pages = 0; var listed = 0
    do {
     require(++pages <= MAX_COMMITS)
     check(stillCurrent()) { CoreMessages.CONNECTION_CANCELED.text() }
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
    NamespaceFailureReason.ROOT_MISSING -> CoreMessages.NO_USABLE_WEBDAV_ROOT_EXISTS_AT_THIS_ADDRESS_HTTP_404_CHECK_THE_SERVICE_ADDRESS_PAREN.text()
    NamespaceFailureReason.NOT_COLLECTION -> CoreMessages.THE_SERVICE_ADDRESS_OR_SYNC_PATH_IS_OCCUPIED_BY_A_FILE_AND_CANNOT_BE_USED_AS_A_SYNC_F.text()
    NamespaceFailureReason.AUTHENTICATION -> CoreMessages.AUTHENTICATION_FAILED_HTTP_401_CHECK_YOUR_USERNAME_AND_PASSWORD.text()
    NamespaceFailureReason.FORBIDDEN -> CoreMessages.THIS_ACCOUNT_CANNOT_ACCESS_OR_CREATE_THE_SYNC_DIRECTORY_HTTP_403_CHECK_WEBDAV_PERMISS.text()
    NamespaceFailureReason.PARENT_MISSING -> CoreMessages.THE_SERVER_CANNOT_FIND_THE_SYNC_FOLDER_S_PARENT_DIRECTORY_HTTP_409_CHECK_THE_SERVICE.text()
    NamespaceFailureReason.HTTP_ERROR -> CoreMessages.THE_SERVER_REJECTED_DIRECTORY_CREATION_OR_VERIFICATION_HTTP_1_S_CHECK_THE_WEBDAV_SERV.text(e.statusCode)
    NamespaceFailureReason.UNSAFE_RESPONSE -> CoreMessages.THE_SERVER_RETURNED_INVALID_DIRECTORY_VERIFICATION_INFORMATION_CREATION_STOPPED_CHECK.text()
    NamespaceFailureReason.VERIFICATION_FAILED -> CoreMessages.THE_SYNC_FOLDER_COULD_NOT_YET_BE_CONFIRMED_RETRY_EXISTING_FILES_WILL_NOT_BE_OVERWRITT.text()
    NamespaceFailureReason.NETWORK -> CoreMessages.CONNECTION_INTERRUPTED_THE_SYNC_DIRECTORY_S_STATE_COULD_NOT_BE_CONFIRMED_CHECK_YOUR_N.text()
    NamespaceFailureReason.CANCELLED -> CoreMessages.CONNECTION_CANCELED_ANY_EMPTY_SYNC_FOLDERS_ALREADY_CREATED_ARE_RETAINED.text()
   }, e.statusCode)
  } catch (_: LegacyProtocolException) {
   ConnectionTestResult.Failure(CoreMessages.LEGACY_HEAD_JSON_DETECTED_STOP_USING_THE_OLD_CLIENT_AND_EXPLICITLY_ARRANGE_MIGRATION.text())
  } catch (e: WebDavException) {
   ConnectionTestResult.Failure(when (e.statusCode) {
    401 -> CoreMessages.AUTHENTICATION_FAILED_HTTP_401_CHECK_YOUR_USERNAME_AND_PASSWORD.text()
    403 -> CoreMessages.ACCESS_DENIED_HTTP_403_CHECK_ACCOUNT_PERMISSIONS_OR_RE_ENTER_YOUR_PASSWORD.text()
    else -> CoreMessages.CONNECTION_TEST_FAILED_HTTP_1_S_CHECK_THE_WEBDAV_ADDRESS_AND_SERVICE_STATUS.text(e.statusCode)
   }, e.statusCode)
  } catch (e: io.github.xyz1001.pdsync.android.OfficialSyncAccess.AccessException) {
   ConnectionTestResult.Failure(e.message ?: CoreMessages.CANNOT_CONNECT_TO_WEBDAV_CHECK_YOUR_NETWORK_ADDRESS_CERTIFICATE_AND_SERVICE_STATUS_TH.text())
  } catch (_: IOException) {
   ConnectionTestResult.Failure(CoreMessages.CANNOT_CONNECT_TO_WEBDAV_CHECK_YOUR_NETWORK_ADDRESS_CERTIFICATE_AND_SERVICE_STATUS_TH.text())
  } catch (_: Exception) {
   // Do not echo arbitrary URL/parser/transport exceptions that might contain entered secrets.
   ConnectionTestResult.Failure(CoreMessages.INVALID_SERVICE_ADDRESS_ACCOUNT_OR_CLOUD_DIRECTORY_LISTING_CHECK_THE_WEBDAV_ADDRESS_A.text())
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

 fun apply(configFile: ConfigFile, rememberPasswordStored: Boolean = draft.config.rememberPassword, filePassword: String? = null): AppliedConnection = synchronized(lock) {
  check(!closed && !running && testedRevision == revision && result is ConnectionTestResult.Success) { CoreMessages.SUCCESSFULLY_TEST_THE_CURRENT_SETTINGS_BEFORE_SAVING.text() }
  require(!rememberPasswordStored || current.config.rememberPassword) { CoreMessages.PASSWORD_SAVING_WAS_NOT_REQUESTED.text() }
  val safe = current.config.validated().copy(saveRoot = "", rememberPassword = rememberPasswordStored, password = filePassword)
  // Failed persistence leaves the tested draft available for retry, without claiming it was applied.
  configFile.save(safe)
  val applied = AppliedConnection(safe, current.password)
  current = ConnectionDraft(safe); testedRevision = null; result = null; revision++; closed = true
  applied
 }

 /** The explicit Connect action saves the submitted password to the local configuration file. */
 fun applyFilePassword(configFile: ConfigFile): AppliedConnection = synchronized(lock) {
  apply(configFile, false, current.password)
 }

 /** Cancel invalidates results and clears the draft; any already-created empty remote folders remain. */
 fun cancel() = synchronized(lock) {
  current = ConnectionDraft(original); testedRevision = null; result = null; revision++; closed = true
 }
}
