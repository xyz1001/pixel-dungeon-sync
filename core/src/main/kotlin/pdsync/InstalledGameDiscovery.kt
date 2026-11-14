package pdsync

import pdsync.i18n.CoreMessages
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.security.MessageDigest
import java.nio.file.StandardOpenOption

/** Flat, explicit directory slots, never a recursive directory search. */
data class InstallationSearchRoot(
 val path: String,
 val source: String = CoreMessages.SPECIFIED_INSTALLATION_CANDIDATE_DIRECTORY.text(),
 val relativeDirectories: List<String> = listOf("", "app", "lib/app", "Contents/app"),
 val requireGameName: Boolean = false
)

enum class DefaultSaveDiscoveryReason { NONE, MISSING, UNAVAILABLE }
data class DefaultSaveDiscovery(val path: String?, val available: Boolean, val reason: String?,
 val reasonCode: DefaultSaveDiscoveryReason = if (available) DefaultSaveDiscoveryReason.NONE else DefaultSaveDiscoveryReason.UNAVAILABLE)
data class InstalledTargetCandidate(val path: String, val source: String, val target: TargetGame?, val reason: String) {
 val trusted: Boolean get() = target?.trusted == true
}
data class InstallationDiscoveryResult(
 val defaultSave: DefaultSaveDiscovery,
 val candidates: List<InstalledTargetCandidate>,
 val incomplete: Boolean,
 val diagnostics: List<String>
) {
 val trustedTargets: List<TargetGame> get() = candidates.mapNotNull { it.target?.takeIf { target -> target.trusted } }
 // A bounded/truncated search must never turn the first hit into an automatic choice.
 val suggestedTarget: TargetGame? get() = if (incomplete) null else trustedTargets.singleOrNull()
 val requiresSelection: Boolean get() = trustedTargets.size > 1 || incomplete
}

/**
 * Search hints, not promises that a game was installed there. Portable Windows/Linux builds have
 * no official installation directory. Source evidence and unsupported layouts are documented in
 * docs/INSTALLATION_DISCOVERY.md. This pure function never touches a filesystem.
 */
object ShatteredInstallationPaths {
 fun roots(context: SavePathContext): List<InstallationSearchRoot> {
  val windows = context.osName.contains("Windows")
  val mac = context.osName.contains("Mac")
  val linux = context.osName.contains("Linux") || context.osName.contains("FreeBSD")
  if (!windows && !mac && !linux) return emptyList()
  val home = context.userHome?.let { if (windows) it.replace('\\', '/') else it }
  fun absolute(value: String?): Boolean = value != null && '\u0000' !in value &&
   (if (windows) Regex("^[A-Za-z]:/.*").matches(value) || value.startsWith("//") else value.startsWith('/')) &&
   value.split('/').none { it == "." || it == ".." }
  val roots = mutableListOf<InstallationSearchRoot>()
  val game = "Shattered Pixel Dungeon"
  if (absolute(home)) {
   roots += InstallationSearchRoot("$home/Downloads", CoreMessages.GAME_JAR_CANDIDATE_IN_DOWNLOADS.text(), listOf(""), true)
   val image = if (mac) "$game.app" else game
   roots += InstallationSearchRoot("$home/Downloads/$image", CoreMessages.PORTABLE_PACKAGE_CANDIDATE_IN_DOWNLOADS.text())
   roots += InstallationSearchRoot("$home/Games/$image", CoreMessages.PORTABLE_PACKAGE_CANDIDATE_IN_GAMES.text())
   if (mac) {
    roots += InstallationSearchRoot("$home/Applications/$game.app", CoreMessages.APPLICATION_CANDIDATE_IN_USER_APPLICATIONS.text())
    roots += InstallationSearchRoot("$home/Library/Application Support/Steam/steamapps/common/$game", CoreMessages.GAME_DIRECTORY_CANDIDATE_IN_THE_DEFAULT_STEAM_LIBRARY.text(),
     listOf("", "app", "lib/app", "$game.app/Contents/app"))
   } else if (linux) {
    // Common client layouts only; custom libraries and symlink aliases are deliberately not followed.
    roots += InstallationSearchRoot("$home/.local/share/Steam/steamapps/common/$game", CoreMessages.GAME_DIRECTORY_CANDIDATE_IN_THE_DEFAULT_STEAM_LIBRARY.text())
    roots += InstallationSearchRoot("$home/.steam/steam/steamapps/common/$game", CoreMessages.GAME_DIRECTORY_CANDIDATE_IN_THE_LEGACY_STEAM_LAYOUT.text())
   }
  }
  if (mac) roots += InstallationSearchRoot("/Applications/$game.app", CoreMessages.APPLICATION_CANDIDATE_IN_APPLICATIONS.text())
  if (windows) {
   val programRoots = listOf(context.environment["ProgramFiles(x86)"], context.environment["ProgramFiles"])
    .mapNotNull { it?.replace('\\', '/')?.takeIf(::absolute) }.ifEmpty { listOf("C:/Program Files (x86)", "C:/Program Files") }
   programRoots.distinct().forEach { root ->
    roots += InstallationSearchRoot("$root/Steam/steamapps/common/$game", CoreMessages.GAME_DIRECTORY_CANDIDATE_IN_THE_DEFAULT_STEAM_LIBRARY.text())
   }
  }
  return roots.distinctBy { it.path }
 }
}

/** Read-only onboarding probe. Does not create saves, load classes, launch games, or access DAV. */
class ShatteredInstallationDiscovery internal constructor(
 private val context: SavePathContext,
 private val roots: List<InstallationSearchRoot>,
 private val inspect: (Path) -> TargetGame
) {
 constructor(context: SavePathContext = SavePathContext.current(), roots: List<InstallationSearchRoot>? = null) :
  this(context, roots ?: ShatteredInstallationPaths.roots(context),
   { ShatteredVersions.target(it, MAX_INPUT_BYTES, MAX_EXPANDED_BYTES) })

 companion object {
  const val MAX_ROOTS = 12
  const val MAX_DIRECTORIES = 48
  const val MAX_ENTRIES = 512
  const val MAX_CANDIDATES = 4
  const val MAX_INPUT_BYTES = 64 * 1024 * 1024
  const val MAX_EXPANDED_BYTES = 128L * 1024 * 1024
 }

 /** Explicit selection accepts any readable JAR file; identity is best-effort, not a prerequisite. */
 fun validateJar(path: Path): InstalledTargetCandidate = probe(path, CoreMessages.MANUAL_SELECTION.text()) { checked ->
  val digest = MessageDigest.getInstance("SHA-256")
  Files.newInputStream(checked, StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
   val buffer = ByteArray(8192)
   while (true) {
    val count = input.read(buffer)
    if (count < 0) break
    digest.update(buffer, 0, count)
   }
  }
  val fingerprint = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
  val identified = try { ShatteredVersions.target(checked) } catch (_: IllegalArgumentException) { null }
  if (identified != null && identified.sha256 == fingerprint) identified else TargetGame(
   checked.toString(), CoreMessages.UNKNOWN_SOURCE.text(), 0, fingerprint, false, null,
   CoreMessages.UNKNOWN_SOURCE.text())
 }

 fun discover(configuredTarget: String = ""): InstallationDiscoveryResult {
  val candidates = linkedMapOf<String, InstalledTargetCandidate>()
  val diagnostics = mutableListOf<String>()
  val directories = hashSetOf<Path>()
  var incomplete = false
  var entries = 0
  var attempts = 0
  fun report(message: String) { if (diagnostics.size < 24) diagnostics += message }
  fun add(path: Path, source: String) {
   val key = path.toAbsolutePath().toString()
   if (key in candidates) return
   if (attempts >= MAX_CANDIDATES) {
    incomplete = true; report(CoreMessages.REACHED_THE_LIMIT_OF_1_S_CANDIDATE_JARS_TO_INSPECT_SELECT_THE_JAR_YOU_ACTUALLY_USE_MA.text(MAX_CANDIDATES)); return
   }
   attempts++
   val candidate = probe(path, source, inspect)
   candidates[key] = candidate
   // A candidate that could not be inspected is not evidence that the search is complete.
   if (candidate.target == null) incomplete = true
  }
  if (configuredTarget.isNotBlank()) {
   try { add(Paths.get(configuredTarget), CoreMessages.CONFIGURED_GAME_JAR.text()) }
   catch (e: IllegalArgumentException) {
    report(CoreMessages.INVALID_CONFIGURED_GAME_PATH_1_S.text(e.message))
   }
  }
  if (roots.size > MAX_ROOTS) { incomplete = true; report(CoreMessages.INSTALLATION_CANDIDATE_ROOTS_EXCEED_THE_LIMIT_OF_1_S.text(MAX_ROOTS)) }
  for (root in roots.take(MAX_ROOTS)) {
   if (root.relativeDirectories.size > MAX_DIRECTORIES) {
    incomplete = true; report(CoreMessages.INSTALLATION_DIRECTORY_SLOTS_EXCEED_THE_LIMIT_OF_1_S.text(MAX_DIRECTORIES))
   }
   for (slot in root.relativeDirectories.take(MAX_DIRECTORIES)) {
    if (directories.size >= MAX_DIRECTORIES || entries >= MAX_ENTRIES) {
     incomplete = true; report(CoreMessages.REACHED_THE_BOUNDED_DIRECTORY_SCAN_LIMIT_THE_ENTIRE_DISK_WAS_NOT_SEARCHED.text()); break
    }
    try {
     val base = strictPath(Paths.get(root.path))
     val relative = Paths.get(slot)
     require(!relative.isAbsolute && relative.none { it.toString() == "." || it.toString() == ".." }) { CoreMessages.INSTALLATION_DIRECTORY_SLOTS_MUST_BE_RELATIVE_PATHS_WITHOUT_OR_SEGMENTS.text() }
     val directory = strictPath(base.resolve(relative))
     require(directory.startsWith(base)) { CoreMessages.INSTALLATION_DIRECTORY_SLOTS_CANNOT_LEAVE_THE_CANDIDATE_ROOT.text() }
     if (!directories.add(directory)) continue
     val attributes = attributes(directory) ?: continue
     require(attributes.isDirectory) { CoreMessages.INSTALLATION_CANDIDATE_PATH_IS_NOT_A_DIRECTORY_1_S.text(directory) }
     val found = mutableListOf<Path>()
     Files.newDirectoryStream(directory).use { stream ->
      val iterator = stream.iterator()
      while (iterator.hasNext()) {
       if (entries >= MAX_ENTRIES) {
        incomplete = true; report(CoreMessages.REACHED_THE_LIMIT_OF_1_S_DIRECTORY_ENTRIES_SELECT_THE_JAR_YOU_ACTUALLY_USE_MANUALLY.text(MAX_ENTRIES)); break
       }
       val path = iterator.next(); entries++
       val name = path.fileName.toString().lowercase(Locale.ROOT)
       if (name.endsWith(".jar") && (!root.requireGameName || name.contains("shattered"))) found.add(path)
      }
     }
     // Prefer recognizable game filenames over runtime/dependency JARs. The name grants no trust.
     found.sortedWith(compareBy<Path> {
      val name = it.fileName.toString().lowercase(Locale.ROOT)
      if (name.contains("shattered") || name.startsWith("desktop")) 0 else 1
     }.thenBy { it.fileName.toString() }).forEach { add(it, root.source) }
    } catch (e: Exception) {
     if (e !is IllegalArgumentException && e !is IOException && e !is SecurityException && e !is DirectoryIteratorException) throw e
     incomplete = true; report(CoreMessages.SKIPPED_CANDIDATE_DIRECTORY_1_S_BECAUSE_IT_COULD_NOT_BE_READ_SAFELY_2_S.text(root.path, e.message))
    }
   }
  }
  return InstallationDiscoveryResult(defaultSave(), candidates.values.toList(), incomplete, diagnostics)
 }

 private fun defaultSave(): DefaultSaveDiscovery {
  var path: String? = null
  return try {
   path = ShatteredDefaultSavePaths.resolve(context)
   val checked = strictPath(Paths.get(path))
   val available = attributes(checked)?.isDirectory == true
   DefaultSaveDiscovery(checked.toString(), available, if (available) null else CoreMessages.DEFAULT_SAVE_DIRECTORY_NOT_FOUND.text(),
    if (available) DefaultSaveDiscoveryReason.NONE else DefaultSaveDiscoveryReason.MISSING)
  } catch (e: Exception) {
   if (e !is IllegalArgumentException && e !is IOException && e !is SecurityException && e !is DirectoryIteratorException) throw e
   DefaultSaveDiscovery(path, false, CoreMessages.CANNOT_SAFELY_INSPECT_THE_DEFAULT_SAVE_DIRECTORY_1_S.text(e.message))
  }
 }

 private fun probe(path: Path, source: String, inspector: (Path) -> TargetGame): InstalledTargetCandidate = try {
  val checked = strictPath(path)
  require(checked.fileName.toString().endsWith(".jar", ignoreCase = true)) { CoreMessages.CHOOSE_A_JAR_FILE_NATIVE_LAUNCHERS_ARE_NOT_VERIFIED_TARGET_GAMES.text() }
  val attributes = attributes(checked)
  require(attributes?.isRegularFile == true) { CoreMessages.REGULAR_JAR_FILE_NOT_FOUND.text() }
  val target = inspector(checked)
  InstalledTargetCandidate(checked.toString(), source, target, target.reason)
 } catch (e: Exception) {
  if (e !is IllegalArgumentException && e !is IOException && e !is SecurityException && e !is DirectoryIteratorException) throw e
  InstalledTargetCandidate(path.toString(), source, null, e.message ?: CoreMessages.CANNOT_SAFELY_INSPECT_THIS_JAR.text())
 }

 private fun attributes(path: Path): BasicFileAttributes? = try {
  Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
 } catch (_: NoSuchFileException) { null }

 private fun strictPath(path: Path): Path {
  require(path.isAbsolute) { CoreMessages.AN_ABSOLUTE_PATH_IS_REQUIRED_THE_GAME_LOCATION_WILL_NOT_BE_INFERRED_FROM_THE_WORKING.text() }
  // Reject before SafePaths.normalizes, so link/../target cannot hide a symlink component.
  require(path.none { it.toString() == "." || it.toString() == ".." }) { CoreMessages.PATHS_CANNOT_CONTAIN_OR_SEGMENTS.text() }
  return SafePaths.checked(path)
 }
}
