package pdsync

import pdsync.i18n.CoreMessages
sealed interface SavedConnectionResult {
 class Connected(val connection: AppliedConnection): SavedConnectionResult
 class Retry(val connection: AppliedConnection, val message: String): SavedConnectionResult
 data class NeedsInput(val message: String): SavedConnectionResult
}

/** Load only the file password. Never import a legacy OS credential. Reconnection is read-only. */
fun restoreSavedConnection(config: AppConfig): SavedConnectionResult {
 val secret = config.password ?: return SavedConnectionResult.NeedsInput(CoreMessages.ENTER_YOUR_PASSWORD_AND_CONNECT_IT_WILL_THEN_BE_REMEMBERED_AUTOMATICALLY.text())
 val checker = ConnectionSetup(config)
 return try {
  checker.update(config, secret)
  when (val checked = checker.testConnection()) {
   is ConnectionTestResult.Success -> SavedConnectionResult.Connected(AppliedConnection(config, secret))
   is ConnectionTestResult.Failure -> if (checked.status in setOf(401, 403)) SavedConnectionResult.NeedsInput(checked.message)
    else SavedConnectionResult.Retry(AppliedConnection(config, secret), checked.message)
  }
 } finally { checker.cancel() }
}
