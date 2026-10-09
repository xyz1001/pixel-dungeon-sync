package pdsync

/** Inputs are explicit so path rules can be tested without changing process-wide properties. */
data class SavePathContext(
 val osName: String,
 val userHome: String?,
 val environment: Map<String, String> = emptyMap()
) {
 companion object {
  fun current() = SavePathContext(System.getProperty("os.name").orEmpty(), System.getProperty("user.home"), System.getenv().toMap())
 }
}

/**
 * A per-game path policy. Resolution is lexical: it neither creates nor inspects a directory.
 * The result uses forward slashes, also accepted by Windows, to make foreign-platform tests
 * independent of the host's Path provider. Convert with Path.of only on the runtime platform.
 */
fun interface GameSavePaths {
 fun resolve(context: SavePathContext): String
}

/** Official Shattered Pixel Dungeon 4.0.2 desktop rules; see docs/DEFAULT_PATH_RULES.md. */
object ShatteredDefaultSavePaths : GameSavePaths {
 override fun resolve(context: SavePathContext): String {
  // Match the bundled libGDX checks, plus DesktopLauncher's explicit FreeBSD override.
  // In particular, "Darwin" is not evidence of either a Windows or a Mac save directory.
  return when {
   context.osName.contains("FreeBSD") -> linux(context)
   context.osName.contains("Windows") -> {
    val home = windowsHome(context.userHome)
    val data = if (context.osName == "Windows XP") "Application Data" else "AppData/Roaming"
    "$home/$data/.shatteredpixel/Shattered Pixel Dungeon"
   }
   context.osName.contains("Linux") -> linux(context)
   context.osName.contains("Mac") -> unixAbsolute(context.userHome, "user.home") + "/Library/Application Support/Shattered Pixel Dungeon"
   else -> throw IllegalArgumentException("No verified Shattered default save directory for operating system '${context.osName}'.")
  }
 }

 private fun linux(context: SavePathContext): String {
  val xdg = context.environment["XDG_DATA_HOME"]
  // Upstream falls back only for null, not for an empty or relative value. Fail closed
  // on either rather than silently selecting a different profile (or a root directory).
  val base = if (xdg == null) {
   unixAbsolute(context.userHome, "user.home") + "/.local/share"
  } else {
   unixAbsolute(xdg, "XDG_DATA_HOME")
  }
  // Preserve whitespace, repeated separators, and dot segments: no trim or normalization.
  return "$base/.shatteredpixel/shattered-pixel-dungeon"
 }

 private fun unixAbsolute(value: String?, name: String): String {
  require(value != null && value.startsWith('/') && '\u0000' !in value) {
   "$name must be a nonempty absolute path to resolve Shattered's default save directory; no fallback was used."
  }
  return value
 }

 private fun windowsHome(value: String?): String {
  require(!value.isNullOrEmpty()) { "user.home is missing; cannot resolve Shattered's default save directory." }
  val path = value.replace('\\', '/')
  val driveAbsolute = Regex("^[A-Za-z]:/.*").matches(path)
  val uncAbsolute = path.startsWith("//") && path.substring(2).split('/').let { it.size >= 2 && it[0].isNotEmpty() && it[1].isNotEmpty() }
  val body = if (driveAbsolute) path.substring(2) else path
  require((driveAbsolute || uncAbsolute) && body.none { it < ' ' || it in "<>\"|?*:" }) {
   "user.home must be an absolute Windows drive or UNC path to resolve Shattered's default save directory."
  }
  return path
 }
}
