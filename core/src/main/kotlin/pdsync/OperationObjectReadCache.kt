package pdsync

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Reconstructed from the shipped code92 APK. An operation owns this disposable byte cache.
 * It does not establish remote integrity: callers still validate every manifest and ZIP.
 * Disk reservations are cumulative for the operation, even after an entry is removed.
 */
internal class OperationObjectReadCache private constructor(
 private val directory: Path?,
 private val activeChannel: FileChannel? = null,
 private val activeLock: FileLock? = null,
 private val memoryOnly: Boolean = false
) : AutoCloseable {
 private data class Entry(val size: Int, val memory: ByteArray? = null, val file: Path? = null, val digest: String? = null)
 private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
 private var memoryBytes = 0L
 private var diskBytes = 0L
 private var diskEntries = 0
 private var disabled = directory == null && !memoryOnly

 fun contains(key: String): Boolean = !disabled && entries.containsKey(key)

 fun read(key: String): ByteArray? {
  if (disabled) return null
  val entry = entries[key] ?: return null
  entry.memory?.let { return it.copyOf() }
  return try {
   val file = SafePaths.checked(requireNotNull(entry.file))
   if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) != entry.size.toLong()) throw IOException()
   val bytes = readFile(file, entry.size)
   if (bytes.size != entry.size || hash(bytes) != entry.digest) throw IOException()
   bytes
  } catch (_: Exception) {
   remove(key)
   null
  }
 }

 fun remember(key: String, bytes: ByteArray) {
  if (disabled || bytes.size > MAX_SNAPSHOT_BYTES) return
  remove(key)
  if (memoryOnly) {
   if (bytes.size > MAX_MEMORY_BYTES) return
   while (memoryBytes + bytes.size > MAX_MEMORY_BYTES || entries.size >= MAX_MEMORY_ENTRIES) {
    remove(entries.entries.iterator().next().key)
   }
   entries[key] = Entry(bytes.size, memory = bytes.copyOf())
   memoryBytes += bytes.size
   return
  }
  if (diskBytes + bytes.size > MAX_REMOTE_SCAN_BYTES || diskEntries >= MAX_COMMITS) return
  diskBytes += bytes.size
  diskEntries++
  try {
   val file = SafePaths.checked(requireNotNull(directory).resolve("${UUID.randomUUID()}.bin"))
   Files.newOutputStream(file, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.write(bytes) }
   entries[key] = Entry(bytes.size, file = file, digest = hash(bytes))
  } catch (_: Exception) {
   disabled = true
   entries.clear()
  }
 }

 fun remove(key: String) {
  val entry = entries.remove(key) ?: return
  if (entry.memory != null) memoryBytes -= entry.size
  entry.file?.let { file ->
   try {
    val checked = SafePaths.checked(file)
    if (Files.isRegularFile(checked, NOFOLLOW_LINKS)) Files.deleteIfExists(checked)
   } catch (_: Exception) {}
  }
 }

 override fun close() {
  disabled = true
  entries.clear()
  memoryBytes = 0
  try { activeLock?.release() } catch (_: Exception) {}
  try { activeChannel?.close() } catch (_: Exception) {}
  directory?.let { try { cleanInactiveDirectory(it) } catch (_: Exception) {} }
 }

 companion object {
  private const val MAX_MEMORY_BYTES = 8 * 1024 * 1024
  private const val MAX_MEMORY_ENTRIES = 256
  private const val MAX_CLEANUP_SCAN = 128
  private const val PREFIX = "pdsync-object-read-v1-"
  private const val MARKER = "owner-v1"
  private const val LOCK = "active.lock"
  private const val MAINTENANCE = "maintenance-v1.lock"
  private val markerBytes = "pdsync-operation-object-read-v1\n".encodeToByteArray()
  private val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
  private val directoryName = Regex("$PREFIX$uuid")
  private val objectName = Regex("$uuid\\.bin")
  private val localMaintenance = Any()

  fun open(root: Path?): OperationObjectReadCache {
   if (root == null) return OperationObjectReadCache(null, memoryOnly = true)
   return try {
    synchronized(localMaintenance) {
     val checked = SafePaths.checked(root)
     createDirectory(checked, parents = true)
     require(Files.isDirectory(SafePaths.checked(checked), NOFOLLOW_LINKS))
     val maintenance = SafePaths.checked(checked.resolve(MAINTENANCE))
     if (Files.exists(maintenance, NOFOLLOW_LINKS) && !Files.isRegularFile(maintenance, NOFOLLOW_LINKS)) {
      return@synchronized OperationObjectReadCache(null)
     }
     FileChannel.open(maintenance, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
      val lock = tryLock(channel) ?: return@use OperationObjectReadCache(null)
      lock.use {
       Files.newDirectoryStream(checked).use { paths ->
        val iterator = paths.iterator()
        var scanned = 0
        while (scanned++ < MAX_CLEANUP_SCAN && iterator.hasNext()) {
         try { cleanInactiveDirectory(iterator.next()) } catch (_: Exception) {}
        }
       }
       createScope(checked)
      }
     }
    }
   } catch (_: Exception) {
    OperationObjectReadCache(null)
   }
  }

  private fun createScope(root: Path): OperationObjectReadCache {
   val directory = SafePaths.checked(root.resolve("$PREFIX${UUID.randomUUID()}"))
   createDirectory(directory)
   var channel: FileChannel? = null
   var lock: FileLock? = null
   try {
    channel = FileChannel.open(SafePaths.checked(directory.resolve(LOCK)), CREATE_NEW, WRITE, NOFOLLOW_LINKS)
    lock = channel.lock()
    Files.newOutputStream(SafePaths.checked(directory.resolve(MARKER)), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use {
     it.write(markerBytes)
    }
    return OperationObjectReadCache(directory, channel, lock)
   } catch (error: Exception) {
    try { lock?.release() } catch (_: Exception) {}
    try { channel?.close() } catch (_: Exception) {}
    for (name in listOf(MARKER, LOCK)) {
     try {
      val path = SafePaths.checked(directory.resolve(name))
      if (Files.isRegularFile(path, NOFOLLOW_LINKS)) Files.deleteIfExists(path)
     } catch (_: Exception) {}
    }
    try { Files.deleteIfExists(SafePaths.checked(directory)) } catch (_: Exception) {}
    throw error
   }
  }

  /** Only recognized, inactive, non-symlink directories are eligible for best-effort cleanup. */
  private fun cleanInactiveDirectory(candidate: Path) {
   if (!directoryName.matches(candidate.fileName.toString())) return
   val directory = SafePaths.checked(candidate)
   if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return
   val children = mutableListOf<Path>()
   Files.newDirectoryStream(directory).use { paths ->
    for (path in paths) {
     if (children.size >= MAX_COMMITS + 2) return
     val name = path.fileName.toString()
     if (name != MARKER && name != LOCK && !objectName.matches(name)) return
     if (!Files.isRegularFile(SafePaths.checked(path), NOFOLLOW_LINKS)) return
     children.add(path)
    }
   }
   if (children.none { it.fileName.toString() == MARKER } || children.none { it.fileName.toString() == LOCK }) return
   if (!readFile(directory.resolve(MARKER), markerBytes.size).contentEquals(markerBytes)) return
   FileChannel.open(SafePaths.checked(directory.resolve(LOCK)), WRITE, NOFOLLOW_LINKS).use { channel ->
    val lock = tryLock(channel) ?: return
    lock.use {
     for (path in children.filter { objectName.matches(it.fileName.toString()) }) {
      if (!Files.isRegularFile(SafePaths.checked(path), NOFOLLOW_LINKS)) return
      Files.delete(path)
     }
    }
   }
   for (name in listOf(MARKER, LOCK)) {
    val path = SafePaths.checked(directory.resolve(name))
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return
    Files.delete(path)
   }
   Files.delete(directory)
  }

  private fun tryLock(channel: FileChannel): FileLock? =
   try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }

  /** A fixed allocation, exact length ceiling, and one-byte overrun probe. */
  private fun readFile(path: Path, limit: Int): ByteArray =
   Files.newInputStream(SafePaths.checked(path), READ, NOFOLLOW_LINKS).use { input ->
    val bytes = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
     val count = input.read(bytes, offset, limit - offset)
     if (count < 0) return@use bytes.copyOf(offset)
     if (count == 0) {
      val next = input.read()
      if (next < 0) return@use bytes.copyOf(offset)
      bytes[offset++] = next.toByte()
     } else offset += count
    }
    if (input.read() != -1) throw IOException()
    bytes
   }

  private fun createDirectory(path: Path, parents: Boolean = false) {
   val attributes = if ("posix" in path.fileSystem.supportedFileAttributeViews())
    arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
   else emptyArray()
   if (parents) Files.createDirectories(path, *attributes) else Files.createDirectory(path, *attributes)
  }
 }
}
