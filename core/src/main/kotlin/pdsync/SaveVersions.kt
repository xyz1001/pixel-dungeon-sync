package pdsync

import pdsync.i18n.CoreMessages
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
 fun summary(): String = if (runs.isEmpty()) unknownReason ?: CoreMessages.UNKNOWN_SAVE_VERSION.text()
 else runs.toSortedMap().entries.joinToString(", ") { (slot, version) -> "$slot: $version" } +
  if (inactiveSlots.isEmpty()) "" else if (inactiveSlots.size == 1) CoreMessages.ONE_INACTIVE_SLOT.text() else CoreMessages.TEXT_1_S_INACTIVE_SLOTS.text(inactiveSlots.size)
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
   require("$slot/game.dat" in files) { CoreMessages.INCOMPLETE_RUN_1_S_HAS_NO_GAME_DAT.text(slot) }
  }
  files.toSortedMap().forEach { (name, bytes) ->
   val slot = gameData.matchEntire(name)?.groupValues?.get(1) ?: return@forEach
   // SPD removes a run by leaving an empty or one-byte game.dat tombstone.
   if (bytes.size <= 1) { inactive += slot; return@forEach }
   try {
    require(bytes.size <= MAX_SAVE_INPUT_BYTES) { CoreMessages.GAME_DAT_EXCEEDS_THE_8_MIB_INPUT_LIMIT.text() }
    val expanded = if (bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
     GZIPInputStream(bytes.inputStream()).use { input ->
      input.readUpTo(MAX_SAVE_EXPANDED_BYTES + 1).also {
       require(it.size <= MAX_SAVE_EXPANDED_BYTES) { CoreMessages.GAME_DAT_EXCEEDS_THE_16_MIB_EXPANDED_LIMIT.text() }
      }
     }
    } else bytes
    runs[slot] = SaveJson(utf8(expanded)).version()
   } catch (e: IllegalArgumentException) {
    throw IllegalArgumentException(CoreMessages.CANNOT_DETECT_1_S_SAVE_VERSION_2_S.text(slot, e.message), e)
   } catch (e: IOException) {
    throw IllegalArgumentException(CoreMessages.CANNOT_DETECT_1_S_SAVE_VERSION_INVALID_COMPRESSED_SAVE.text(slot), e)
   }
  }
  return SaveVersions(runs, inactive, if (runs.isEmpty())
   CoreMessages.NO_LIVE_GAME_DAT_CONTAINS_A_VERSION_GLOBAL_RECORDS_AND_INACTIVE_SLOTS_CANNOT_ESTABLIS.text() else null)
 }

 /** Reads one bounded byte snapshot of a JAR, preventing hash/manifest reads from racing each other. */
 fun target(path: Path): TargetGame = target(path, MAX_JAR_BYTES, MAX_JAR_EXPANDED_BYTES)

 /** Discovery uses smaller per-candidate limits; no release checksum is required. */
 internal fun target(path: Path, maxInputBytes: Int, maxExpandedBytes: Long): TargetGame {
  require(maxInputBytes in 1..MAX_JAR_BYTES && maxExpandedBytes in 1..MAX_JAR_EXPANDED_BYTES)
  val checked = SafePaths.checked(path)
  require(Files.isRegularFile(checked, LinkOption.NOFOLLOW_LINKS)) { CoreMessages.TARGET_GAME_MUST_BE_A_REGULAR_JAR_FILE.text() }
  require(Files.size(checked) <= maxInputBytes) { CoreMessages.TARGET_JAR_EXCEEDS_THE_1_S_BYTE_INPUT_LIMIT.text(maxInputBytes) }
  val bytes = Files.newInputStream(checked, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
   it.readUpTo(maxInputBytes + 1).also { data ->
    require(data.size <= maxInputBytes) { CoreMessages.TARGET_JAR_EXCEEDS_THE_1_S_BYTE_INPUT_LIMIT.text(maxInputBytes) }
   }
  }
  require(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
   bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) { CoreMessages.TARGET_IS_NOT_A_JAR_ZIP_ARCHIVE.text() }
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
     require(entries.size < MAX_JAR_ENTRIES) { CoreMessages.TARGET_JAR_HAS_TOO_MANY_ENTRIES.text() }
     require(entry.name.length <= 4096) { CoreMessages.TARGET_JAR_ENTRY_NAME_IS_TOO_LONG.text() }
     require(entries.add(entry.name)) { CoreMessages.TARGET_JAR_CONTAINS_DUPLICATE_ENTRIES_1_S.text(entry.name) }
     val isManifest = entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)
     if (isManifest) {
      require(manifest == null && !entry.isDirectory) { CoreMessages.TARGET_JAR_CONTAINS_AMBIGUOUS_MANIFESTS.text() }
      require(entry.size <= MAX_JAR_MANIFEST_BYTES) { CoreMessages.TARGET_MANIFEST_EXCEEDS_THE_64_KIB_LIMIT.text() }
     }
     val output = if (isManifest) ByteArrayOutputStream() else null
     while (true) {
      val count = zip.read(buffer)
      if (count < 0) break
      expanded += count
      require(expanded <= maxExpandedBytes) { CoreMessages.TARGET_JAR_EXCEEDS_THE_EXPANDED_SIZE_LIMIT.text() }
      if (output != null) {
       require(output.size() + count <= MAX_JAR_MANIFEST_BYTES) { CoreMessages.TARGET_MANIFEST_EXCEEDS_THE_64_KIB_LIMIT.text() }
       output.write(buffer, 0, count)
      }
     }
     if (output != null) manifest = output.toByteArray()
     if (entry.name == MAIN_CLASS.replace('.', '/') + ".class" && !entry.isDirectory) launcher = true
     zip.closeEntry()
    }
   }
  } catch (e: IOException) {
   throw IllegalArgumentException(CoreMessages.TARGET_JAR_IS_MALFORMED_OR_UNREADABLE.text(), e)
  }
  require(entries == centralEntries) { CoreMessages.TARGET_JAR_HAS_INCONSISTENT_LOCAL_AND_CENTRAL_ENTRIES.text() }
  val attributes = manifestAttributes(manifest ?: throw IllegalArgumentException(CoreMessages.JAR_HAS_NO_GAME_MANIFEST_GAME_IDENTITY_AND_VERSION_CANNOT_BE_READ.text()))
  require(attributes["specification-title"] == "Shattered Pixel Dungeon" &&
   attributes["implementation-title"] == "com.shatteredpixel.shatteredpixeldungeon" &&
   attributes["main-class"] == MAIN_CLASS && launcher) { CoreMessages.NOT_RECOGNIZED_AS_THE_SHATTERED_PIXEL_DUNGEON_DESKTOP_GAME_GAME_IDENTITY_OR_LAUNCH_CL.text() }
  val display = attributes["specification-version"]
  require(!display.isNullOrBlank() && display.length <= 128 && display.none { it.isISOControl() }) {
   CoreMessages.GAME_MANIFEST_HAS_NO_VALID_RELEASE_VERSION.text()
  }
  val code = positiveInteger(attributes["implementation-version"] ?: "")
   ?: throw IllegalArgumentException(CoreMessages.GAME_MANIFEST_HAS_NO_VALID_INTEGER_INTERNAL_VERSION.text())
  val supported = supportedReleases[display]
  require(supported == null || supported.first == code) {
   CoreMessages.GAME_VERSION_INFORMATION_IS_INCONSISTENT_1_S_HAS_KNOWN_INTERNAL_VERSION_2_S_BUT_THE_M.text(display, supported?.first, code)
  }
  val minimum = supported?.second
  // Retain a content fingerprint only to detect replacement after selection, never as an allowlist.
  val digest = hash(bytes)
  return TargetGame(checked.toString(), display, code, digest, true, minimum,
   if (minimum != null) CoreMessages.SHATTERED_1_S_IDENTIFIED_INTERNAL_VERSION_2_S_SUPPORTED_RESTORE_VERSIONS_3_S_4_S.text(display, code, minimum, code)
   else CoreMessages.SHATTERED_1_S_IDENTIFIED_INTERNAL_VERSION_2_S_AND_CAN_BE_SELECTED_ITS_RESTORE_COMPATI.text(display, code))
 }

 fun requireCompatible(info: SaveVersions, target: TargetGame) {
  require(target.trusted) { CoreMessages.CANNOT_RESTORE_TARGET_GAME_IS_NOT_IDENTIFIED_1_S.text(target.reason) }
  val minimum = target.minimumSaveVersion
  require(minimum != null && minimum > 0 && minimum <= target.versionCode) { CoreMessages.CANNOT_RESTORE_TARGET_GAME_S_SAVE_COMPATIBILITY_RANGE_IS_UNVERIFIED.text() }
  require(info.unknownReason == null && info.runs.isNotEmpty()) {
   CoreMessages.CANNOT_RESTORE_NO_ACTIVE_RUNS_HAVE_A_VERIFIABLE_SAVE_WRITE_VERSION_1_S.text(info.unknownReason.orEmpty())
  }
  val incompatible = info.runs.filterValues { it !in minimum..target.versionCode }
  require(incompatible.isEmpty()) {
   CoreMessages.CANNOT_RESTORE_1_S.text(incompatible.entries.joinToString { "${it.key}: ${it.value}" }) +
    CoreMessages.TARGET_1_S_SUPPORTS_SAVE_WRITE_VERSIONS_2_S_3_S_NEWER_SAVES_CANNOT_BE_DOWNGRADED_AND.text(target.displayVersion, minimum, target.versionCode)
  }
 }

 private fun utf8(bytes: ByteArray): String = try {
  StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
   .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
 } catch (e: java.nio.charset.CharacterCodingException) {
  throw IllegalArgumentException(CoreMessages.INVALID_UTF_8.text(), e)
 }

 /** Inspect both ZIP directory and local headers; streamed ZIP readers alone ignore the directory. */
 private fun jarEntries(bytes: ByteArray, maxExpandedBytes: Long): Set<String> {
  fun short(offset: Int): Int {
   require(offset >= 0 && offset <= bytes.size - 2) { CoreMessages.TRUNCATED_TARGET_JAR.text() }
   return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
  }
  fun word(offset: Int): Long = short(offset).toLong() or (short(offset + 2).toLong() shl 16)
  val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65_557)).firstOrNull {
   word(it) == 0x06054b50L && it.toLong() + 22 + short(it + 20) == bytes.size.toLong()
  } ?: throw IllegalArgumentException(CoreMessages.TARGET_JAR_HAS_NO_VALID_ZIP_DIRECTORY.text())
  require(short(end + 4) == 0 && short(end + 6) == 0 && short(end + 8) == short(end + 10)) {
   CoreMessages.MULTI_VOLUME_TARGET_JARS_ARE_NOT_SUPPORTED.text()
  }
  val count = short(end + 10)
  val size = word(end + 12)
  val start = word(end + 16)
  require(count in 1..MAX_JAR_ENTRIES && start + size == end.toLong()) {
   CoreMessages.TARGET_JAR_HAS_AN_INVALID_OVERSIZED_OR_UNSUPPORTED_ZIP_DIRECTORY.text()
  }
  var cursor = start.toInt()
  val names = hashSetOf<String>()
  val localOffsets = hashSetOf<Long>()
  var expanded = 0L
  repeat(count) {
   require(cursor >= 0 && cursor <= end - 46 && word(cursor) == 0x02014b50L) { CoreMessages.MALFORMED_TARGET_JAR_DIRECTORY_ENTRY.text() }
   val nameSize = short(cursor + 28)
   val extraSize = short(cursor + 30)
   val commentSize = short(cursor + 32)
   val next = cursor.toLong() + 46 + nameSize + extraSize + commentSize
   require(nameSize in 1..4096 && next <= end && short(cursor + 34) == 0) { CoreMessages.MALFORMED_TARGET_JAR_ENTRY_METADATA.text() }
   val name = utf8(bytes.copyOfRange(cursor + 46, cursor + 46 + nameSize))
   require(names.add(name)) { CoreMessages.TARGET_JAR_CONTAINS_DUPLICATE_DIRECTORY_ENTRIES_1_S.text(name) }
   val local = word(cursor + 42)
   require(local <= start - 30 && localOffsets.add(local)) { CoreMessages.TARGET_JAR_CONTAINS_INVALID_OR_ALIASED_LOCAL_ENTRIES.text() }
   val offset = local.toInt()
   require(word(offset) == 0x04034b50L && short(offset + 6) == short(cursor + 8) &&
    short(offset + 8) == short(cursor + 10)) { CoreMessages.TARGET_JAR_HAS_INCONSISTENT_LOCAL_ENTRY_METADATA.text() }
   val localNameSize = short(offset + 26)
   val dataStart = local + 30 + localNameSize + short(offset + 28)
   require(localNameSize == nameSize && dataStart <= start &&
    dataStart + word(cursor + 20) <= start &&
    utf8(bytes.copyOfRange(offset + 30, offset + 30 + localNameSize)) == name) { CoreMessages.TARGET_JAR_HAS_INCONSISTENT_LOCAL_ENTRY_NAMES_OR_SIZES.text() }
   expanded += word(cursor + 24)
   require(expanded <= maxExpandedBytes) { CoreMessages.TARGET_JAR_EXCEEDS_THE_EXPANDED_SIZE_LIMIT.text() }
   cursor = next.toInt()
  }
  require(cursor == end) { CoreMessages.TARGET_JAR_HAS_UNEXPECTED_ZIP_DIRECTORY_DATA.text() }
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
    val previous = key ?: throw IllegalArgumentException(CoreMessages.TARGET_MANIFEST_HAS_AN_ORPHAN_CONTINUATION.text())
    section[previous] = section.getValue(previous) + line.substring(1)
   } else {
    val colon = line.indexOf(':')
    require(colon in 1..70 && line.getOrNull(colon + 1) == ' ') { CoreMessages.MALFORMED_TARGET_MANIFEST_ATTRIBUTE.text() }
    val name = line.substring(0, colon)
    require(name.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) { CoreMessages.MALFORMED_TARGET_MANIFEST_KEY.text() }
    val normalized = name.lowercase(Locale.ROOT)
    require(normalized !in section) { CoreMessages.TARGET_MANIFEST_CONTAINS_DUPLICATE_KEY_1_S.text(name) }
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
   require(peek() == '{') { CoreMessages.GAME_DAT_MUST_BE_A_JSON_OBJECT.text() }
   objectValue(1, root = true)
   whitespace()
   require(offset == text.length) { CoreMessages.TRAILING_DATA_AFTER_GAME_DAT_JSON.text() }
   return writer ?: throw IllegalArgumentException(CoreMessages.MISSING_POSITIVE_INTEGER_TOP_LEVEL_VERSION_INIT_VER_IS_NOT_THE_LAST_WRITER.text())
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
    else -> throw IllegalArgumentException(CoreMessages.MALFORMED_JSON_VALUE_AT_CHARACTER_1_S.text(offset))
   }
  }

  private fun objectValue(depth: Int, root: Boolean = false) {
   require(depth <= 64) { CoreMessages.GAME_DAT_JSON_NESTING_EXCEEDS_64_LEVELS.text() }
   expect('{'); whitespace()
   if (take('}')) return
   while (true) {
    whitespace()
    val key = stringValue(root)
    whitespace(); expect(':'); whitespace()
    if (root && key == "version") {
     require(!seenVersion) { CoreMessages.DUPLICATE_TOP_LEVEL_VERSION.text() }
     seenVersion = true
     val start = offset
     require(peek() == '-' || peek() in '0'..'9') { CoreMessages.TOP_LEVEL_VERSION_MUST_BE_A_POSITIVE_INTEGER.text() }
     numberValue()
     writer = positiveInteger(text.substring(start, offset))
     require(writer != null) { CoreMessages.TOP_LEVEL_VERSION_MUST_BE_A_POSITIVE_INTEGER_WITHIN_THE_32_BIT_RANGE.text() }
    } else value(depth + 1)
    whitespace()
    if (take('}')) return
    expect(',')
   }
  }

  private fun arrayValue(depth: Int) {
   require(depth <= 64) { CoreMessages.GAME_DAT_JSON_NESTING_EXCEEDS_64_LEVELS.text() }
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
    require(char >= ' ') { CoreMessages.UNESCAPED_CONTROL_CHARACTER_IN_JSON_STRING.text() }
    if (char != '\\') result?.append(char)
    else {
     require(offset < text.length) { CoreMessages.TRUNCATED_JSON_ESCAPE.text() }
     val decoded = when (val escaped = text[offset++]) {
      '"', '\\', '/' -> escaped
      'b' -> '\b'
      'f' -> '\u000c'
      'n' -> '\n'
      'r' -> '\r'
      't' -> '\t'
      'u' -> {
       require(offset + 4 <= text.length) { CoreMessages.TRUNCATED_UNICODE_ESCAPE.text() }
       val digits = text.substring(offset, offset + 4)
       require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { CoreMessages.INVALID_UNICODE_ESCAPE.text() }
       offset += 4
       digits.toInt(16).toChar()
      }
      else -> throw IllegalArgumentException(CoreMessages.INVALID_JSON_ESCAPE.text())
     }
     result?.append(decoded)
    }
   }
   throw IllegalArgumentException(CoreMessages.UNTERMINATED_JSON_STRING.text())
  }

  private fun numberValue() {
   take('-')
   if (!take('0')) {
    require(peek() in '1'..'9') { CoreMessages.INVALID_JSON_NUMBER.text() }
    digits()
   }
   if (take('.')) { require(peek() in '0'..'9') { CoreMessages.INVALID_JSON_FRACTION.text() }; digits() }
   if (take('e') || take('E')) {
    if (!take('+')) take('-')
    require(peek() in '0'..'9') { CoreMessages.INVALID_JSON_EXPONENT.text() }; digits()
   }
  }

  private fun digits() { while (peek() in '0'..'9') offset++ }
  private fun literal(value: String) {
   require(text.startsWith(value, offset)) { CoreMessages.INVALID_JSON_LITERAL.text() }; offset += value.length
  }
  private fun whitespace() { while (peek() in " \t\r\n") offset++ }
  private fun peek(): Char = text.getOrNull(offset) ?: '\u0000'
  private fun take(char: Char): Boolean = if (peek() == char) { offset++; true } else false
  private fun expect(char: Char) { require(take(char)) { CoreMessages.EXPECTED_1_S_IN_JSON_AT_CHARACTER_2_S.text(char, offset) } }
 }
}

/** Source fingerprints are provenance, never a package-family compatibility restriction. */
@Serializable data class GameSaveSource(val originalSourceIdentity: String, val originalApkSha256: String) {
 fun validated(): GameSaveSource = also {
  require(Regex("[0-9a-f]{64}").matches(originalSourceIdentity) && Regex("[0-9a-f]{64}").matches(originalApkSha256)) { CoreMessages.INVALID_GAME_SOURCE_PROVENANCE.text() }
 }
}
internal fun requireGamePackage(value: String) {
 require(value.length <= 255 && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value)) { CoreMessages.INVALID_GAME_PACKAGE_FAMILY.text() }
}
internal fun recordedGamePackage(manifest: Manifest): String? = manifest.gamePackage ?: when {
 manifest.game == Shattered.id && manifest.gameSource == null -> "com.shatteredpixel.shatteredpixeldungeon"
 else -> null
}
