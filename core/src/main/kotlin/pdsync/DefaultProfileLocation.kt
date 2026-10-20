package pdsync

import pdsync.i18n.CoreMessages
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
 CoreMessages.THE_DIRECTORY_DOES_NOT_EXIST_YET_CHOOSE_NEXT_TO_CREATE_AN_EMPTY_DIRECTORY.text()
)

/** Desktop account boundary. Never import the old, account-unbound cache or include secrets. */
internal fun desktopAccountStateRoot(stateRoot: Path, config: AppConfig): Path {
 val validated = config.validated()
 val identity = listOf(validated.endpoint, validated.username, validated.profile, validated.connectionProvider)
 val key = hash(identity.joinToString("") { "${it.length}:$it" }.encodeToByteArray())
 return stateRoot.resolve("desktop-accounts-v1").resolve(key)
}

/** Old saveRoot is not silently reused; the new explicit selection is persisted independently. */
class DefaultProfileLocation(
 private val context: SavePathContext = SavePathContext.current(),
 private val paths: GameSavePaths = ShatteredDefaultSavePaths,
 private val adapter: GameAdapter = Shattered,
 private val versions: ProfileVersions = ShatteredProfileVersions
) {
 fun resolve(): Path {
  val raw = Paths.get(paths.resolve(context))
  require(raw.none { it.toString() == "." || it.toString() == ".." }) { CoreMessages.THE_DEFAULT_SAVE_PATH_CONTAINS_OR_SEGMENTS_USE_A_SYSTEM_PATH_WITHOUT_THESE_SEGMENTS_T.text() }
  return raw.toAbsolutePath()
 }
 fun requireDirectory(): Path {
  val root = SafePaths.checked(resolve())
  require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
   CoreMessages.DEFAULT_SAVE_DIRECTORY_NOT_FOUND_1_S_START_SHATTERED_PIXEL_DUNGEON_WITH_THE_CURRENT_S.text(root)
  }
  return root
 }
 /** Called only after the discovery page explicitly says Continue prepares this empty default target. */
 fun prepareDefaultDirectory(): Path {
  val root = SafePaths.checked(resolve())
  require(root.parent != null && root != Paths.get(context.userHome ?: "").toAbsolutePath().normalize()) { CoreMessages.INVALID_DEFAULT_DIRECTORY.text() }
  if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return requireDirectory()
  // Check every ancestor before and after creating only the adapter's exact default path.
  Files.createDirectories(root)
  return requireDirectory()
 }
 fun open(config: AppConfig, stateRoot: Path): AppendProfileSession =
  AppendProfileSession(config.copy(saveRoot = selected(config).toString()), desktopAccountStateRoot(stateRoot, config), adapter, versions)
 fun selected(config: AppConfig): Path = config.selectedSaveRoot?.let { requireSelected(it,false) } ?: requireDirectory()
 fun requireSelected(value: String, create: Boolean): Path {
  val raw=Paths.get(value)
  require(value.isNotBlank() && raw.isAbsolute && raw.none { it.toString() in setOf(".","..") }) { CoreMessages.ENTER_THE_FULL_SAVE_DIRECTORY_PATH.text() }
  val root=SafePaths.checked(raw)
  require(root.parent!=null && root!=Paths.get(context.userHome.orEmpty()).toAbsolutePath().normalize()) { CoreMessages.CHOOSE_A_DEDICATED_SAVE_DIRECTORY.text() }
  val exists = selectedDirectoryExists(root)
  if (!exists) {
   if (!create) throw SelectedSaveDirectoryMissing(root)
   // Recheck after creation: a preview is never permission to trust a changed filesystem.
   Files.createDirectories(SafePaths.checked(root))
  }
  val checked = SafePaths.checked(root)
  require(selectedDirectoryExists(checked)) { CoreMessages.CANNOT_CREATE_THE_SAVE_DIRECTORY.text() }
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
    cursor = cursor.parent ?: throw IllegalArgumentException(CoreMessages.CHOOSE_A_DEDICATED_SAVE_DIRECTORY.text())
    continue
   }
   require(!attributes.isSymbolicLink && attributes.isDirectory) { CoreMessages.CHOOSE_A_VALID_SAVE_DIRECTORY.text() }
   require(Files.isReadable(cursor) && Files.isWritable(cursor) && Files.isExecutable(cursor)) {
    CoreMessages.CANNOT_VERIFY_READ_AND_WRITE_PERMISSIONS_FOR_THE_SAVE_DIRECTORY.text()
   }
   return !missing
  }
 }
}
