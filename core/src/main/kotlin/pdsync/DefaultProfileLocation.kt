package pdsync

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

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
  if(create)Files.createDirectories(root)
  require(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) { "目录尚不存在；点击下一步可创建空目录" }
  return root
 }
}
