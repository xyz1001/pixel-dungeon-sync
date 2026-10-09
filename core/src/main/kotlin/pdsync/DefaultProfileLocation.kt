package pdsync

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Desktop entry point: the game adapter determines the location, never saved UI configuration. */
class DefaultProfileLocation(
 private val context: SavePathContext = SavePathContext.current(),
 private val paths: GameSavePaths = ShatteredDefaultSavePaths,
 private val adapter: GameAdapter = Shattered,
 private val versions: ProfileVersions = ShatteredProfileVersions
) {
 fun resolve(): Path {
  val raw = Path.of(paths.resolve(context))
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
 fun open(config: AppConfig, stateRoot: Path): AppendProfileSession =
  AppendProfileSession(config.copy(saveRoot = requireDirectory().toString()), stateRoot, adapter, versions)
}
