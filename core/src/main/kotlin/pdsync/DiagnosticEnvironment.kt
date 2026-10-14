package pdsync

import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.util.Collections
import java.util.IllformedLocaleException
import java.util.Locale

/**
 * The entire optional environment allowlist for a local diagnostic export. The platform adapters
 * pass only public OS/build fields, never a property dump, hostname, account, path or device ID.
 * App version and platform already live in DiagnosticLog's export metadata. Values are bounded and
 * validated before publication; unsupported values become null, rather than leaking raw input.
 */
object DiagnosticEnvironment {
 @Serializable data class Snapshot internal constructor(
  val osVersion: String? = null,
  val androidApiLevel: Int? = null,
  val brand: String? = null,
  val model: String? = null,
  val cpuArchitectures: List<String> = emptyList(),
  val locale: String? = null,
  val timeZone: String? = null
 )

 @Volatile private var configured = Snapshot()
 private val versionPattern = Regex("[A-Za-z0-9][A-Za-z0-9._+()-]{0,63}")
 private val labelPattern = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} ._()+-]{0,79}")
 private val languagePattern = Regex("[a-zA-Z]{2,3}")
 private val scriptPattern = Regex("[a-zA-Z]{4}")
 private val regionPattern = Regex("(?:[a-zA-Z]{2}|[0-9]{3})")

 /** No identifier APIs or permissions are needed to supply these Android Build fields. */
 @JvmStatic fun configureAndroid(
  osVersion: String?, apiLevel: Int, brand: String?, model: String?, cpuArchitectures: Array<out String?>?,
  locale: String?, timeZone: String?
 ) {
  configured = Snapshot(
   osVersion = safeValue(osVersion, 64, versionPattern), androidApiLevel = apiLevel.takeIf { it in 1..999 },
   brand = safeValue(brand, 80, labelPattern), model = safeValue(model, 80, labelPattern),
   cpuArchitectures = safeArchitectures(cpuArchitectures), locale = safeLocale(locale), timeZone = safeTimeZone(timeZone)
  )
 }

 /** Desktop collection is deliberately limited to os.version and os.arch, not arbitrary properties. */
 @JvmStatic fun configureDesktop(osVersion: String?, cpuArchitecture: String?, locale: String?, timeZone: String?) {
  configured = Snapshot(
   osVersion = safeValue(osVersion, 64, versionPattern), cpuArchitectures = safeArchitectures(arrayOf(cpuArchitecture ?: "")),
   locale = safeLocale(locale), timeZone = safeTimeZone(timeZone)
  )
 }

 /** A fixed, immutable view, so a concurrent configure cannot mix fields in one exported bundle. */
 @JvmStatic fun snapshot(): Snapshot = configured
 internal fun resetForTests() { configured = Snapshot() }

 private fun safeValue(value: String?, maxLength: Int, pattern: Regex): String? =
  value?.takeIf { it.length in 1..maxLength && pattern.matches(it) }

 private fun safeArchitectures(values: Array<out String?>?): List<String> {
  val safe = values.orEmpty().take(16).mapNotNull { value ->
   if (value == null || value.length > 24) return@mapNotNull null
   when (value.lowercase(Locale.ROOT)) {
    "amd64", "x86_64" -> "x86_64"
    "x86", "i386", "i486", "i586", "i686" -> "x86"
    "aarch64", "arm64", "arm64-v8a" -> "arm64-v8a"
    "arm", "arm32", "armv7l", "armeabi-v7a" -> "armeabi-v7a"
    "armeabi" -> "armeabi"
    "riscv64" -> "riscv64"
    "ppc64", "ppc64le", "s390x" -> value.lowercase(Locale.ROOT)
    else -> null
   }
  }.distinct().take(8)
  return Collections.unmodifiableList(safe)
 }

 /** Keep only language/script/region; custom variants and private-use extensions are never exported. */
 private fun safeLocale(value: String?): String? {
  if (value == null || value.length !in 2..96 || !Regex("[A-Za-z0-9-]+").matches(value)) return null
  return try {
   val locale = Locale.Builder().setLanguageTag(value).build()
   if (!languagePattern.matches(locale.language)) return null
   val script = locale.script.takeIf { scriptPattern.matches(it) }
   val region = locale.country.takeIf { regionPattern.matches(it) }
   listOfNotNull(locale.language.lowercase(Locale.ROOT), script, region).joinToString("-")
  } catch (_: IllformedLocaleException) { null }
 }

 /** Accept only actual system time-zone names and ISO offsets, not arbitrary custom TimeZone IDs. */
 private fun safeTimeZone(value: String?): String? {
  if (value == null || value.length !in 1..64) return null
  return try {
   when {
    value in ZoneId.getAvailableZoneIds() || value in setOf("UTC", "GMT", "UT", "Z") -> ZoneId.of(value).id
    Regex("(?:UTC|GMT|UT)?[+-][0-9]{2}:[0-9]{2}").matches(value) -> ZoneId.of(value).id
    else -> null
   }
  } catch (_: java.time.DateTimeException) { null }
 }
}
