package pdsync

import java.nio.file.Files
import java.nio.file.Path

/** Only the selected JAR path; connection secrets never enter this incomplete-setup file. */
internal class SetupSelection(private val file: Path) {
 fun load(): String? = if (Files.exists(SafePaths.checked(file)))
  SafePaths.boundedRead(file, 16 * 1024).decodeToString().takeIf { it.isNotBlank() } else null
 fun save(path: String) {
  require(path.isNotBlank() && Path.of(path).isAbsolute)
  val bytes = path.encodeToByteArray()
  require(bytes.size <= 16 * 1024)
  SafePaths.atomicWrite(file, bytes)
 }
}
