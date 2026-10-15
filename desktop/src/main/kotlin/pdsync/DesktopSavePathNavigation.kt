package pdsync

/** A settings edit is cancellable until the selected path has actually been committed. */
internal class DesktopSavePathNavigation {
 internal data class SettingsReturn(
  val path: String,
  val setupDraft: AppConfig,
  val onboardingActive: Boolean,
 )

 private var settingsReturn: SettingsReturn? = null

 fun beginSettings(adapter: DesktopSavePathAdapter, path: String, setupDraft: AppConfig, onboardingActive: Boolean) {
  settingsReturn = SettingsReturn(path, setupDraft, onboardingActive)
  adapter.editPath(path)
 }

 /** Setup and successfully saved edits must still complete the existing comparison flow. */
 fun beginSetup() { settingsReturn = null }

 fun cancel(adapter: DesktopSavePathAdapter): SettingsReturn? {
  val previous = settingsReturn ?: return null
  settingsReturn = null
  adapter.editPath(previous.path)
  return previous
 }
}
