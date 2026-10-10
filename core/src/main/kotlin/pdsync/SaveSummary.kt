package pdsync

import java.nio.file.Path
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

data class SaveSummary(val slots: Map<String,String?>, val plays: Int?, val files: Int) {
 companion object {
  fun from(snapshot: Snapshot): SaveSummary {
   val bytes=Snapshots.verify(snapshot,Shattered)
   val versions=ShatteredVersions.detect(bytes)
   val plays=bytes["rankings.dat"]?.let { source -> runCatching {
    require(source.size<=8*1024*1024)
    val expanded=if(source.size>=2 && source[0]==0x1f.toByte() && source[1]==0x8b.toByte())
     GZIPInputStream(source.inputStream()).use { it.readNBytes(16*1024*1024+1) } else source
    require(expanded.size<=16*1024*1024)
    val text=expanded.decodeToString(throwOnInvalidSequence=true);requireUniqueJsonKeys(text)
    (json.parseToJsonElement(text).jsonObject["total"] as? JsonPrimitive)?.intOrNull?.takeIf { it>=0 }
   }.getOrNull() }
   return SaveSummary(versions.runs.keys.associateWith { slot -> snapshot.manifest.metadata?.fileModifiedAt?.filterKeys { it.startsWith("$slot/") }?.values?.maxOrNull() },plays,bytes.size)
  }
  fun read(root: Path): SaveSummary {
   val snapshot=Snapshots.capture(root,Shattered,"preview","preview",null)
   val summary=from(snapshot)
   return summary.copy(slots=summary.slots.mapValues { (slot,_) -> snapshot.manifest.files.keys.filter { it.startsWith("$slot/") }.map { Files.getLastModifiedTime(SafePaths.checked(root.resolve(it))).toInstant().toString() }.maxOrNull() })
  }
 }
}
