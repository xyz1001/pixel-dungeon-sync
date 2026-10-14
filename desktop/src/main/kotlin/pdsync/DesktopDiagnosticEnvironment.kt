package pdsync

import java.util.Locale
import java.util.TimeZone

/** Only explicitly approved, non-identifying runtime fields; no commands or hardware probes. */
internal fun configureDesktopDiagnosticEnvironment() {
 DiagnosticEnvironment.configureDesktop(
  runCatching { System.getProperty("os.version") }.getOrNull(),
  runCatching { System.getProperty("os.arch") }.getOrNull(),
  runCatching { Locale.getDefault().toLanguageTag() }.getOrNull(),
  runCatching { TimeZone.getDefault().id }.getOrNull()
 )
}
