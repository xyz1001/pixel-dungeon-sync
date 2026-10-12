package pdsync

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Versions read from verified game.dat bytes, never from an archive's declared version. */
@Serializable
data class SaveVersions(
 val runs: Map<String, Int> = emptyMap(),
 val inactiveSlots: List<String> = emptyList(),
 val unknownReason: String? = null
) {
 fun summary(): String = if (runs.isEmpty()) unknownReason ?: "Unknown save version"
 else runs.toSortedMap().entries.joinToString(", ") { (slot, version) -> "$slot: $version" } +
  if (inactiveSlots.isEmpty()) "" else " (${inactiveSlots.size} inactive slots)"
}

data class TargetGame @JvmOverloads constructor(
 val path: String,
 val displayVersion: String,
 val versionCode: Int,
 val sha256: String,
 /** Legacy field name: identified game metadata, NOT publisher/checksum authentication. */
 val trusted: Boolean,
 val minimumSaveVersion: Int?,
 val reason: String,
 val mergeFormat: String? = null
)

/** Static inspection only: game classes and Java-serialized objects are never loaded. */
object ShatteredVersions {
 private const val MAX_SAVE_INPUT_BYTES = 8 * 1024 * 1024
 private const val MAX_SAVE_EXPANDED_BYTES = 16 * 1024 * 1024
 private const val MAX_JAR_BYTES = 256 * 1024 * 1024
 private const val MAX_JAR_EXPANDED_BYTES = 512L * 1024 * 1024
 private const val MAX_JAR_ENTRIES = 50_000
 private const val MAX_JAR_MANIFEST_BYTES = 64 * 1024
 // Compatibility policies, not a selection allowlist. Other identified versions can be selected.
 // 3.3.8 GamesInProgress.check rejects <802; 4.0.2 rejects <850.
 private val supportedReleases = mapOf("3.3.8" to (896 to 802), "4.0.2" to (922 to 850))
 private const val MAIN_CLASS = "com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher"
 private val gameData = Regex("(game[0-9]+)/game\\.dat")
 private val slotPath = Regex("(game[0-9]+)(?:/.*)?")

 /** Call with Snapshots.verify's result so archive checksums/path checks precede inspection. */
 fun detect(files: Map<String, ByteArray>): SaveVersions {
  val runs = sortedMapOf<String, Int>()
  val inactive = mutableListOf<String>()
  files.keys.mapNotNull { slotPath.matchEntire(it)?.groupValues?.get(1) }.distinct().forEach { slot ->
   require("$slot/game.dat" in files) { "Incomplete run: $slot has no game.dat" }
  }
  files.toSortedMap().forEach { (name, bytes) ->
   val slot = gameData.matchEntire(name)?.groupValues?.get(1) ?: return@forEach
   // SPD removes a run by leaving an empty or one-byte game.dat tombstone.
   if (bytes.size <= 1) { inactive += slot; return@forEach }
   try {
    require(bytes.size <= MAX_SAVE_INPUT_BYTES) { "game.dat exceeds the 8 MiB input limit" }
    val expanded = if (bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
     GZIPInputStream(bytes.inputStream()).use { input ->
      input.readUpTo(MAX_SAVE_EXPANDED_BYTES + 1).also {
       require(it.size <= MAX_SAVE_EXPANDED_BYTES) { "game.dat exceeds the 16 MiB expanded limit" }
      }
     }
    } else bytes
    runs[slot] = SaveJson(utf8(expanded)).version()
   } catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("Cannot detect $slot save version: ${e.message}", e)
   } catch (e: IOException) {
    throw IllegalArgumentException("Cannot detect $slot save version: invalid compressed save", e)
   }
  }
  return SaveVersions(runs, inactive, if (runs.isEmpty())
   "No live game.dat contains a version; global records and inactive slots cannot establish compatibility" else null)
 }

 /** Reads one bounded byte snapshot of a JAR, preventing hash/manifest reads from racing each other. */
 fun target(path: Path): TargetGame = target(path, MAX_JAR_BYTES, MAX_JAR_EXPANDED_BYTES)

 /** Discovery uses smaller per-candidate limits; no release checksum is required. */
 internal fun target(path: Path, maxInputBytes: Int, maxExpandedBytes: Long): TargetGame {
  require(maxInputBytes in 1..MAX_JAR_BYTES && maxExpandedBytes in 1..MAX_JAR_EXPANDED_BYTES)
  val checked = SafePaths.checked(path)
  require(Files.isRegularFile(checked, LinkOption.NOFOLLOW_LINKS)) { "Target game must be a regular JAR file" }
  require(Files.size(checked) <= maxInputBytes) { "Target JAR exceeds the $maxInputBytes byte input limit" }
  val bytes = Files.newInputStream(checked, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
   it.readUpTo(maxInputBytes + 1).also { data ->
    require(data.size <= maxInputBytes) { "Target JAR exceeds the $maxInputBytes byte input limit" }
   }
  }
  require(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
   bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) { "Target is not a JAR/ZIP archive" }
  val centralEntries = jarEntries(bytes, maxExpandedBytes)
  var manifest: ByteArray? = null
  var launcher = false
  var expanded = 0L
  val entries = hashSetOf<String>()
  try {
   ZipInputStream(bytes.inputStream()).use { zip ->
    val buffer = ByteArray(8192)
    while (true) {
     val entry = zip.nextEntry ?: break
     require(entries.size < MAX_JAR_ENTRIES) { "Target JAR has too many entries" }
     require(entry.name.length <= 4096) { "Target JAR entry name is too long" }
     require(entries.add(entry.name)) { "Target JAR contains duplicate entries: ${entry.name}" }
     val isManifest = entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)
     if (isManifest) {
      require(manifest == null && !entry.isDirectory) { "Target JAR contains ambiguous manifests" }
      require(entry.size <= MAX_JAR_MANIFEST_BYTES) { "Target manifest exceeds the 64 KiB limit" }
     }
     val output = if (isManifest) ByteArrayOutputStream() else null
     while (true) {
      val count = zip.read(buffer)
      if (count < 0) break
      expanded += count
      require(expanded <= maxExpandedBytes) { "Target JAR exceeds the expanded size limit" }
      if (output != null) {
       require(output.size() + count <= MAX_JAR_MANIFEST_BYTES) { "Target manifest exceeds the 64 KiB limit" }
       output.write(buffer, 0, count)
      }
     }
     if (output != null) manifest = output.toByteArray()
     if (entry.name == MAIN_CLASS.replace('.', '/') + ".class" && !entry.isDirectory) launcher = true
     zip.closeEntry()
    }
   }
  } catch (e: IOException) {
   throw IllegalArgumentException("Target JAR is malformed or unreadable", e)
  }
  require(entries == centralEntries) { "Target JAR has inconsistent local and central entries" }
  val attributes = manifestAttributes(manifest ?: throw IllegalArgumentException("JAR 缺少游戏清单，无法读取游戏标识和版本。"))
  require(attributes["specification-title"] == "Shattered Pixel Dungeon" &&
   attributes["implementation-title"] == "com.shatteredpixel.shatteredpixeldungeon" &&
   attributes["main-class"] == MAIN_CLASS && launcher) { "无法识别为 Shattered Pixel Dungeon 桌面游戏：游戏标识或启动类缺失、不一致。" }
  val display = attributes["specification-version"]
  require(!display.isNullOrBlank() && display.length <= 128 && display.none { it.isISOControl() }) {
   "游戏清单缺少有效的发行版本。"
  }
  val code = positiveInteger(attributes["implementation-version"] ?: "")
   ?: throw IllegalArgumentException("游戏清单缺少有效的整数内部版本。")
  val supported = supportedReleases[display]
  require(supported == null || supported.first == code) {
   "游戏版本信息不一致：$display 的已知内部版本为 ${supported?.first}，清单却声明 $code；无法确认此改版的版本。"
  }
  val minimum = supported?.second
  // Retain a content fingerprint only to detect replacement after selection, never as an allowlist.
  val digest = hash(bytes)
  return TargetGame(checked.toString(), display, code, digest, true, minimum,
   if (minimum != null) "已识别 Shattered $display（内部版本 $code）；恢复版本范围 $minimum–$code。"
   else "已识别 Shattered $display（内部版本 $code），可以选择；该版本的恢复兼容范围尚未确认，暂不支持恢复。")
 }

 fun requireCompatible(info: SaveVersions, target: TargetGame) {
  require(target.trusted) { "无法恢复：尚未识别目标游戏。${target.reason}" }
  val minimum = target.minimumSaveVersion
  require(minimum != null && minimum > 0 && minimum <= target.versionCode) { "无法恢复：目标游戏的存档兼容范围尚未确认。" }
  require(info.unknownReason == null && info.runs.isNotEmpty()) {
   "无法恢复：没有可确认写入版本的进行中存档。${info.unknownReason.orEmpty()}"
  }
  val incompatible = info.runs.filterValues { it !in minimum..target.versionCode }
  require(incompatible.isEmpty()) {
   "无法恢复：${incompatible.entries.joinToString { "${it.key}: ${it.value}" }}；" +
    "目标 ${target.displayVersion} 支持的存档写入版本为 $minimum–${target.versionCode}，不允许将新版存档降级覆盖。"
  }
 }

 private fun utf8(bytes: ByteArray): String = try {
  StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
   .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
 } catch (e: java.nio.charset.CharacterCodingException) {
  throw IllegalArgumentException("Invalid UTF-8", e)
 }

 /** Inspect both ZIP directory and local headers; streamed ZIP readers alone ignore the directory. */
 private fun jarEntries(bytes: ByteArray, maxExpandedBytes: Long): Set<String> {
  fun short(offset: Int): Int {
   require(offset >= 0 && offset <= bytes.size - 2) { "Truncated target JAR" }
   return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
  }
  fun word(offset: Int): Long = short(offset).toLong() or (short(offset + 2).toLong() shl 16)
  val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65_557)).firstOrNull {
   word(it) == 0x06054b50L && it.toLong() + 22 + short(it + 20) == bytes.size.toLong()
  } ?: throw IllegalArgumentException("Target JAR has no valid ZIP directory")
  require(short(end + 4) == 0 && short(end + 6) == 0 && short(end + 8) == short(end + 10)) {
   "Multi-volume target JARs are not supported"
  }
  val count = short(end + 10)
  val size = word(end + 12)
  val start = word(end + 16)
  require(count in 1..MAX_JAR_ENTRIES && start + size == end.toLong()) {
   "Target JAR has an invalid, oversized, or unsupported ZIP directory"
  }
  var cursor = start.toInt()
  val names = hashSetOf<String>()
  val localOffsets = hashSetOf<Long>()
  var expanded = 0L
  repeat(count) {
   require(cursor >= 0 && cursor <= end - 46 && word(cursor) == 0x02014b50L) { "Malformed target JAR directory entry" }
   val nameSize = short(cursor + 28)
   val extraSize = short(cursor + 30)
   val commentSize = short(cursor + 32)
   val next = cursor.toLong() + 46 + nameSize + extraSize + commentSize
   require(nameSize in 1..4096 && next <= end && short(cursor + 34) == 0) { "Malformed target JAR entry metadata" }
   val name = utf8(bytes.copyOfRange(cursor + 46, cursor + 46 + nameSize))
   require(names.add(name)) { "Target JAR contains duplicate directory entries: $name" }
   val local = word(cursor + 42)
   require(local <= start - 30 && localOffsets.add(local)) { "Target JAR contains invalid or aliased local entries" }
   val offset = local.toInt()
   require(word(offset) == 0x04034b50L && short(offset + 6) == short(cursor + 8) &&
    short(offset + 8) == short(cursor + 10)) { "Target JAR has inconsistent local entry metadata" }
   val localNameSize = short(offset + 26)
   val dataStart = local + 30 + localNameSize + short(offset + 28)
   require(localNameSize == nameSize && dataStart <= start &&
    dataStart + word(cursor + 20) <= start &&
    utf8(bytes.copyOfRange(offset + 30, offset + 30 + localNameSize)) == name) { "Target JAR has inconsistent local entry names or sizes" }
   expanded += word(cursor + 24)
   require(expanded <= maxExpandedBytes) { "Target JAR exceeds the expanded size limit" }
   cursor = next.toInt()
  }
  require(cursor == end) { "Target JAR has unexpected ZIP directory data" }
  return names
 }

 /** Unfold first, then check keys case-insensitively; java.util.jar.Manifest silently accepts duplicates. */
 private fun manifestAttributes(bytes: ByteArray): Map<String, String> {
  val lines = utf8(bytes).replace("\r\n", "\n").replace('\r', '\n').split('\n')
  val main = linkedMapOf<String, String>()
  var section = main
  var key: String? = null
  for (line in lines) {
   if (line.isEmpty()) {
    section = linkedMapOf()
    key = null
   } else if (line.startsWith(' ')) {
    val previous = key ?: throw IllegalArgumentException("Target manifest has an orphan continuation")
    section[previous] = section.getValue(previous) + line.substring(1)
   } else {
    val colon = line.indexOf(':')
    require(colon in 1..70 && line.getOrNull(colon + 1) == ' ') { "Malformed target manifest attribute" }
    val name = line.substring(0, colon)
    require(name.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) { "Malformed target manifest key" }
    val normalized = name.lowercase(Locale.ROOT)
    require(normalized !in section) { "Target manifest contains duplicate key: $name" }
    section[normalized] = line.substring(colon + 2)
    key = normalized
   }
  }
  return main
 }

 private fun positiveInteger(value: String): Int? = if (value.matches(Regex("[1-9][0-9]*")))
  value.toIntOrNull()?.takeIf { it > 0 } else null

 /** Small validating scanner avoids JSON object allocation and detects duplicate escaped root keys. */
 private class SaveJson(private val text: String) {
  private var offset = 0
  private var writer: Int? = null
  private var seenVersion = false

  fun version(): Int {
   whitespace()
   require(peek() == '{') { "game.dat must be a JSON object" }
   objectValue(1, root = true)
   whitespace()
   require(offset == text.length) { "Trailing data after game.dat JSON" }
   return writer ?: throw IllegalArgumentException("Missing positive integer top-level version (init_ver is not the last writer)")
  }

  private fun value(depth: Int) {
   whitespace()
   when (peek()) {
    '{' -> objectValue(depth)
    '[' -> arrayValue(depth)
    '"' -> stringValue(false)
    't' -> literal("true")
    'f' -> literal("false")
    'n' -> literal("null")
    '-', in '0'..'9' -> numberValue()
    else -> throw IllegalArgumentException("Malformed JSON value at character $offset")
   }
  }

  private fun objectValue(depth: Int, root: Boolean = false) {
   require(depth <= 64) { "game.dat JSON nesting exceeds 64 levels" }
   expect('{'); whitespace()
   if (take('}')) return
   while (true) {
    whitespace()
    val key = stringValue(root)
    whitespace(); expect(':'); whitespace()
    if (root && key == "version") {
     require(!seenVersion) { "Duplicate top-level version" }
     seenVersion = true
     val start = offset
     require(peek() == '-' || peek() in '0'..'9') { "Top-level version must be a positive integer" }
     numberValue()
     writer = positiveInteger(text.substring(start, offset))
     require(writer != null) { "Top-level version must be a positive integer within the 32-bit range" }
    } else value(depth + 1)
    whitespace()
    if (take('}')) return
    expect(',')
   }
  }

  private fun arrayValue(depth: Int) {
   require(depth <= 64) { "game.dat JSON nesting exceeds 64 levels" }
   expect('['); whitespace()
   if (take(']')) return
   while (true) {
    value(depth + 1); whitespace()
    if (take(']')) return
    expect(',')
   }
  }

  private fun stringValue(capture: Boolean): String? {
   expect('"')
   val result = if (capture) StringBuilder() else null
   while (offset < text.length) {
    val char = text[offset++]
    if (char == '"') return result?.toString()
    require(char >= ' ') { "Unescaped control character in JSON string" }
    if (char != '\\') result?.append(char)
    else {
     require(offset < text.length) { "Truncated JSON escape" }
     val decoded = when (val escaped = text[offset++]) {
      '"', '\\', '/' -> escaped
      'b' -> '\b'
      'f' -> '\u000c'
      'n' -> '\n'
      'r' -> '\r'
      't' -> '\t'
      'u' -> {
       require(offset + 4 <= text.length) { "Truncated Unicode escape" }
       val digits = text.substring(offset, offset + 4)
       require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid Unicode escape" }
       offset += 4
       digits.toInt(16).toChar()
      }
      else -> throw IllegalArgumentException("Invalid JSON escape")
     }
     result?.append(decoded)
    }
   }
   throw IllegalArgumentException("Unterminated JSON string")
  }

  private fun numberValue() {
   take('-')
   if (!take('0')) {
    require(peek() in '1'..'9') { "Invalid JSON number" }
    digits()
   }
   if (take('.')) { require(peek() in '0'..'9') { "Invalid JSON fraction" }; digits() }
   if (take('e') || take('E')) {
    if (!take('+')) take('-')
    require(peek() in '0'..'9') { "Invalid JSON exponent" }; digits()
   }
  }

  private fun digits() { while (peek() in '0'..'9') offset++ }
  private fun literal(value: String) {
   require(text.startsWith(value, offset)) { "Invalid JSON literal" }; offset += value.length
  }
  private fun whitespace() { while (peek() in " \t\r\n") offset++ }
  private fun peek(): Char = text.getOrNull(offset) ?: '\u0000'
  private fun take(char: Char): Boolean = if (peek() == char) { offset++; true } else false
  private fun expect(char: Char) { require(take(char)) { "Expected '$char' in JSON at character $offset" } }
 }
}

/** Source fingerprints are provenance, never a package-family compatibility restriction. */
@Serializable data class GameSaveSource(val originalSourceIdentity: String, val originalApkSha256: String) {
 fun validated(): GameSaveSource = also {
  require(Regex("[0-9a-f]{64}").matches(originalSourceIdentity) && Regex("[0-9a-f]{64}").matches(originalApkSha256)) { "Invalid game source provenance" }
 }
}
internal fun requireGamePackage(value: String) {
 require(value.length <= 255 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value)) { "Invalid game package family" }
}
internal fun recordedGamePackage(manifest: Manifest): String? = manifest.gamePackage ?: when {
 manifest.game == Shattered.id && manifest.gameSource == null -> "com.shatteredpixel.shatteredpixeldungeon"
 manifest.gameSource?.originalSourceIdentity == "32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89" -> "com.shatteredpixel.shatteredpixeldungeon"
 else -> null
}
