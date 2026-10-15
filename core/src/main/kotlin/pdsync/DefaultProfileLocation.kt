package pdsync

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path
import java.nio.file.Paths

/** Read-only inspection proved the target absent and its nearest existing parent usable.
 * Only an explicit Next may create it; other errors must never be converted to an empty preview.
 */
class SelectedSaveDirectoryMissing(val directory: Path) : IllegalArgumentException(
 "目录尚不存在；点击下一步可创建空目录"
)

/** Old saveRoot is not silently reused; the new explicit selection is persisted independently. */
class DefaultProfileLocation(
 private val context: SavePathContext = SavePathContext.current(),
 private val paths: GameSavePaths = ShatteredDefaultSavePaths,
 private val adapter: GameAdapter = Shattered,
 private val versions: ProfileVersions = ShatteredProfileVersions
) {
 fun resolve(): Path {
  val raw = Paths.get(paths.resolve(context))
  require(raw.none { it.toString() == "." || it.toString() == ".." }) { "默认存档路径包含 . 或 ..；为避免与游戏访问不同目录，请使用不含这些片段的系统路径。" }
  return raw.toAbsolutePath()
 }
 fun requireDirectory(): Path {
  val root = SafePaths.checked(resolve())
  require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
   "未找到默认存档目录：$root。请先用当前系统账号启动 Shattered Pixel Dungeon 并创建存档，然后退出游戏再刷新。不会改用其他目录。"
  }
  return root
 }
 /** Called only after the discovery page explicitly says Continue prepares this empty default target. */
 fun prepareDefaultDirectory(): Path {
  val root = SafePaths.checked(resolve())
  require(root.parent != null && root != Paths.get(context.userHome ?: "").toAbsolutePath().normalize()) { "默认目录无效" }
  if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return requireDirectory()
  // Check every ancestor before and after creating only the adapter's exact default path.
  Files.createDirectories(root)
  return requireDirectory()
 }
 fun open(config: AppConfig, stateRoot: Path): AppendProfileSession =
  AppendProfileSession(config.copy(saveRoot = selected(config).toString()), stateRoot, adapter, versions)
 fun selected(config: AppConfig): Path = config.selectedSaveRoot?.let { requireSelected(it,false) } ?: requireDirectory()
 fun requireSelected(value: String, create: Boolean): Path {
  val raw=Paths.get(value)
  require(value.isNotBlank() && raw.isAbsolute && raw.none { it.toString() in setOf(".","..") }) { "请输入完整的存档目录路径" }
  val root=SafePaths.checked(raw)
  require(root.parent!=null && root!=Paths.get(context.userHome.orEmpty()).toAbsolutePath().normalize()) { "请选择专用存档目录" }
  val exists = selectedDirectoryExists(root)
  if (!exists) {
   if (!create) throw SelectedSaveDirectoryMissing(root)
   // Recheck after creation: a preview is never permission to trust a changed filesystem.
   Files.createDirectories(SafePaths.checked(root))
  }
  val checked = SafePaths.checked(root)
  require(selectedDirectoryExists(checked)) { "无法创建存档目录" }
  return checked
 }

 /** NoSuchFileException is the sole absent-path proof. Denied/unknown status stays an error. */
 private fun selectedDirectoryExists(root: Path): Boolean {
  var cursor = root
  var missing = false
  while (true) {
   val attributes = try {
    Files.readAttributes(cursor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
   } catch (_: NoSuchFileException) {
    missing = true
    cursor = cursor.parent ?: throw IllegalArgumentException("请选择专用存档目录")
    continue
   }
   require(!attributes.isSymbolicLink && attributes.isDirectory) { "请选择有效的存档目录" }
   require(Files.isReadable(cursor) && Files.isWritable(cursor) && Files.isExecutable(cursor)) {
    "无法确认存档目录的读写权限"
   }
   return !missing
  }
 }
}
