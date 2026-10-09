package pdsync

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** Flat, explicit directory slots, never a recursive directory search. */
data class InstallationSearchRoot(
 val path: String,
 val source: String = "指定安装候选目录",
 val relativeDirectories: List<String> = listOf("", "app", "lib/app", "Contents/app"),
 val requireGameName: Boolean = false
)

data class DefaultSaveDiscovery(val path: String?, val available: Boolean, val reason: String?)
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
   roots += InstallationSearchRoot("$home/Downloads", "下载目录中的游戏 JAR 候选", listOf(""), true)
   val image = if (mac) "$game.app" else game
   roots += InstallationSearchRoot("$home/Downloads/$image", "下载目录中的便携包候选")
   roots += InstallationSearchRoot("$home/Games/$image", "Games 目录中的便携包候选")
   if (mac) {
    roots += InstallationSearchRoot("$home/Applications/$game.app", "用户 Applications 中的应用候选")
    roots += InstallationSearchRoot("$home/Library/Application Support/Steam/steamapps/common/$game", "默认 Steam 库中的游戏目录候选",
     listOf("", "app", "lib/app", "$game.app/Contents/app"))
   } else if (linux) {
    // Common client layouts only; custom libraries and symlink aliases are deliberately not followed.
    roots += InstallationSearchRoot("$home/.local/share/Steam/steamapps/common/$game", "默认 Steam 库中的游戏目录候选")
    roots += InstallationSearchRoot("$home/.steam/steam/steamapps/common/$game", "旧 Steam 布局中的游戏目录候选")
   }
  }
  if (mac) roots += InstallationSearchRoot("/Applications/$game.app", "Applications 中的应用候选")
  if (windows) {
   val programRoots = listOf(context.environment["ProgramFiles(x86)"], context.environment["ProgramFiles"])
    .mapNotNull { it?.replace('\\', '/')?.takeIf(::absolute) }.ifEmpty { listOf("C:/Program Files (x86)", "C:/Program Files") }
   programRoots.distinct().forEach { root ->
    roots += InstallationSearchRoot("$root/Steam/steamapps/common/$game", "默认 Steam 库中的游戏目录候选")
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

 /** Manual selection uses the same authoritative inspector and may inspect one larger valid JAR. */
 fun validateJar(path: Path): InstalledTargetCandidate = probe(path, "手动选择", ShatteredVersions::target)

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
    incomplete = true; report("候选 JAR 达到 $MAX_CANDIDATES 个检查上限；请手动选择实际使用的 JAR。"); return
   }
   attempts++
   val candidate = probe(path, source, inspect)
   candidates[key] = candidate
   // A candidate that could not be inspected is not evidence that the search is complete.
   if (candidate.target == null) incomplete = true
  }
  if (configuredTarget.isNotBlank()) {
   try { add(Path.of(configuredTarget), "已配置的游戏 JAR") }
   catch (e: IllegalArgumentException) {
    report("已配置的游戏路径无效：${e.message}")
   }
  }
  if (roots.size > MAX_ROOTS) { incomplete = true; report("安装候选根目录超过 $MAX_ROOTS 个上限。") }
  for (root in roots.take(MAX_ROOTS)) {
   if (root.relativeDirectories.size > MAX_DIRECTORIES) {
    incomplete = true; report("安装目录槽位超过 $MAX_DIRECTORIES 个上限。")
   }
   for (slot in root.relativeDirectories.take(MAX_DIRECTORIES)) {
    if (directories.size >= MAX_DIRECTORIES || entries >= MAX_ENTRIES) {
     incomplete = true; report("目录检查达到有界扫描上限；未搜索整个磁盘。"); break
    }
    try {
     val base = strictPath(Path.of(root.path))
     val relative = Path.of(slot)
     require(!relative.isAbsolute && relative.none { it.toString() == "." || it.toString() == ".." }) { "安装目录槽位必须是相对路径且不含 . 或 .." }
     val directory = strictPath(base.resolve(relative))
     require(directory.startsWith(base)) { "安装目录槽位不能离开候选根目录" }
     if (!directories.add(directory)) continue
     val attributes = attributes(directory) ?: continue
     require(attributes.isDirectory) { "安装候选路径不是目录：$directory" }
     val found = mutableListOf<Path>()
     Files.newDirectoryStream(directory).use { stream ->
      val iterator = stream.iterator()
      while (iterator.hasNext()) {
       if (entries >= MAX_ENTRIES) {
        incomplete = true; report("目录条目达到 $MAX_ENTRIES 个上限；请手动选择实际使用的 JAR。"); break
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
     incomplete = true; report("跳过无法安全读取的候选目录 ${root.path}：${e.message}")
    }
   }
  }
  return InstallationDiscoveryResult(defaultSave(), candidates.values.toList(), incomplete, diagnostics)
 }

 private fun defaultSave(): DefaultSaveDiscovery {
  var path: String? = null
  return try {
   path = ShatteredDefaultSavePaths.resolve(context)
   val checked = strictPath(Path.of(path))
   val available = attributes(checked)?.isDirectory == true
   DefaultSaveDiscovery(checked.toString(), available, if (available) null else "未找到默认存档目录。")
  } catch (e: Exception) {
   if (e !is IllegalArgumentException && e !is IOException && e !is SecurityException && e !is DirectoryIteratorException) throw e
   DefaultSaveDiscovery(path, false, "无法安全检查默认存档目录：${e.message}")
  }
 }

 private fun probe(path: Path, source: String, inspector: (Path) -> TargetGame): InstalledTargetCandidate = try {
  val checked = strictPath(path)
  require(checked.fileName.toString().endsWith(".jar", ignoreCase = true)) { "请选择 JAR 文件；原生启动器不是已验证的目标游戏。" }
  val attributes = attributes(checked)
  require(attributes?.isRegularFile == true) { "未找到普通 JAR 文件。" }
  val target = inspector(checked)
  InstalledTargetCandidate(checked.toString(), source, target, target.reason)
 } catch (e: Exception) {
  if (e !is IllegalArgumentException && e !is IOException && e !is SecurityException && e !is DirectoryIteratorException) throw e
  InstalledTargetCandidate(path.toString(), source, null, e.message ?: "无法安全检查此 JAR。")
 }

 private fun attributes(path: Path): BasicFileAttributes? = try {
  Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
 } catch (_: NoSuchFileException) { null }

 private fun strictPath(path: Path): Path {
  require(path.isAbsolute) { "必须使用绝对路径；不会从工作目录猜测游戏位置。" }
  // Reject before SafePaths.normalizes, so link/../target cannot hide a symlink component.
  require(path.none { it.toString() == "." || it.toString() == ".." }) { "路径不能含 . 或 .. 片段。" }
  return SafePaths.checked(path)
 }
}
