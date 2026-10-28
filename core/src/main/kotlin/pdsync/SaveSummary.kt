package pdsync

import java.nio.file.Path
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

data class SaveSummary(val slots: Map<String,String?>, val plays: Int?, val files: Int) {
 companion object {
  fun from(snapshot: Snapshot): SaveSummary = fromVerifiedEntries(Snapshots.verify(snapshot,Shattered)) { name -> snapshot.manifest.metadata?.fileModifiedAt?.get(name) }
  internal fun fromVerified(verified:VerifiedSnapshot):SaveSummary = fromVerifiedEntries(verified.entries,verified.saveVersions) { null }
  internal fun fromArchiveEntries(bytes:Map<String,ByteArray>,modified:Map<String,Long>,versions:SaveVersions):SaveSummary = fromVerifiedEntries(bytes,versions) { name -> modified[name]?.let { java.time.Instant.ofEpochMilli(it).toString() } }
  internal fun readVerified(root: Path, bytes: Map<String,ByteArray>): SaveSummary = fromVerifiedEntries(bytes) { name -> Files.getLastModifiedTime(SafePaths.checked(root.resolve(name))).toInstant().toString() }
  /** Inventory supplies the complete names and real mtimes; bytes contain summary inputs only. */
  internal fun fromObservation(files:Set<String>, summaryBytes:Map<String,ByteArray>, modifiedMillis:(String)->Long):SaveSummary {
   val slots=files.mapNotNull { Regex("(game[0-9]+)(?:/.*)?").matchEntire(it)?.groupValues?.get(1) }.toSet()
   require(slots.all { "$it/game.dat" in files })
   val wanted=files.filter { it=="rankings.dat" || Regex("game[0-9]+/game\\.dat").matches(it) }.toSet()
   require(summaryBytes.keys==wanted)
   val versions=ShatteredVersions.detect(summaryBytes)
   val inputs=fromVerifiedEntries(summaryBytes,versions) { null }
   return SaveSummary(versions.runs.keys.associateWith { slot -> files.filter { it.startsWith("$slot/") }
    .maxOfOrNull(modifiedMillis)?.let { java.time.Instant.ofEpochMilli(it).toString() } },inputs.plays,files.size)
  }
  private fun fromVerifiedEntries(bytes: Map<String,ByteArray>, versions:SaveVersions=ShatteredVersions.detect(bytes), modifiedAt: (String)->String?): SaveSummary {
   val plays=bytes["rankings.dat"]?.let { source -> runCatching {
    require(source.size<=8*1024*1024)
    val expanded=if(source.size>=2 && source[0]==0x1f.toByte() && source[1]==0x8b.toByte())
     GZIPInputStream(source.inputStream()).use { it.readUpTo(16*1024*1024+1) } else source
    require(expanded.size<=16*1024*1024)
    val text=expanded.decodeToString(throwOnInvalidSequence=true);requireUniqueJsonKeys(text)
    (json.parseToJsonElement(text).jsonObject["total"] as? JsonPrimitive)?.intOrNull?.takeIf { it>=0 }
   }.getOrNull() }
   return SaveSummary(versions.runs.keys.associateWith { slot -> bytes.keys.filter { it.startsWith("$slot/") }.mapNotNull(modifiedAt).maxOrNull() },plays,bytes.size)
  }
  fun read(root: Path): SaveSummary {
   val read=Snapshots.readLocal(root,Shattered)
   return fromVerifiedEntries(read.entries) { name -> read.fingerprint.metadata[name]?.modified?.toInstant()?.toString() }
  }
 }
}
