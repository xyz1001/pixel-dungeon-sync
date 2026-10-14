package pdsync

import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.TimeZone
import kotlin.test.*

class DesktopDiagnosticEnvironmentTest {
 @Test fun desktopAdapterCollectsOnlySupportedRuntimeMetadata() {
  val oldVersion = System.getProperty("os.version")
  val oldArchitecture = System.getProperty("os.arch")
  val oldLocale = Locale.getDefault()
  val oldTimeZone = TimeZone.getDefault()
  try {
   System.setProperty("os.version", "10.0")
   System.setProperty("os.arch", "amd64")
   Locale.setDefault(Locale.forLanguageTag("zh-Hans-CN-x-secret"))
   TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
   configureDesktopDiagnosticEnvironment()
   val value = DiagnosticEnvironment.snapshot()
   assertEquals("10.0", value.osVersion)
   assertEquals(listOf("x86_64"), value.cpuArchitectures)
   assertEquals("zh-Hans-CN", value.locale)
   assertEquals("Asia/Shanghai", value.timeZone)
   assertNull(value.brand); assertNull(value.model); assertNull(value.androidApiLevel)
  } finally {
   if (oldVersion == null) System.clearProperty("os.version") else System.setProperty("os.version", oldVersion)
   if (oldArchitecture == null) System.clearProperty("os.arch") else System.setProperty("os.arch", oldArchitecture)
   Locale.setDefault(oldLocale); TimeZone.setDefault(oldTimeZone)
   DiagnosticEnvironment.configureDesktop(null, null, null, null)
  }
 }
}
