package pdsync

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.Collections

/**
 * In-memory evidence for a user selecting one history card. This does not evaluate or change any
 * restore guard. Callers supply the existing guards' values, including the actual final outcome.
 * Only complete canonical file-map digests, counts, a hashed commit UUID and closed facts escape.
 */
class HomeRestoreDiagnosticEvidence private constructor(values: Map<String, String>) {
 val values: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(values))

 /** Declaration order is the diagnostic first-failed-gate order; RESTORE_ALLOWED is an outcome. */
 enum class Gate {
  ACCOUNT_ACCESS, FOREGROUND, IDLE, RESTORE_SUPPORTED, SAVE_ACCESS, CONNECTED, NO_RECOVERY,
  INSPECTED, NO_PENDING, OBSERVATION_CURRENT, CLOUD_VERIFIED, FACADE_AVAILABLE, GAME_SELECTED,
  RUNTIME_ACCOUNT_CURRENT, SETUP_COMPLETE,
  READY_FOR_SAVES, INSPECTION_NOT_BLOCKED, ARCHIVE_IDLE, GRAPH_NO_PENDING, SOURCE_AVAILABLE,
  CONTENT_NEEDED, RESTORE_ALLOWED
 }

 companion object {
  internal const val MAX_BYTES = 4096
  private val codec = Json
  private val serializer = MapSerializer(String.serializer(), String.serializer())
  private val digest = Regex("[a-f0-9]{64}")
  private val states = setOf("AVAILABLE", "ABSENT", "INVALID")
  private val conditionGates = Gate.entries.filter { it != Gate.RESTORE_ALLOWED }
  private val keys = listOf("current.state", "current.file_count", "current.filemap_sha256",
   "target.state", "target.file_count", "target.filemap_sha256", "selected.commit_sha256",
   "selected.tip", "filemaps_equal") + Gate.entries.map { "gate.${it.name}" } + "disabled_reason"

  /** No save reads, archive access, network calls or partial/truncated file-map hashes. */
  @JvmStatic fun capture(currentFiles: Map<String, String>?, targetFiles: Map<String, String>?,
                        selectedCommitId: String?, selectedTip: Boolean,
                        gates: Map<Gate, Boolean>): HomeRestoreDiagnosticEvidence {
   val values = linkedMapOf<String, String>()
   val current = summarize(values, "current", currentFiles)
   val target = summarize(values, "target", targetFiles)
   if (selectedCommitId != null && validRecordId(selectedCommitId))
    values["selected.commit_sha256"] = hash(selectedCommitId.toByteArray(Charsets.UTF_8))
   values["selected.tip"] = selectedTip.toString()
   if (current != null && target != null) values["filemaps_equal"] = (current == target).toString()
   // Copy only named booleans. Missing observations are not invented as passing or failing.
   for (gate in Gate.entries) gates[gate]?.let { values["gate.${gate.name}"] = it.toString() }
   val firstNotTrue = conditionGates.firstOrNull { values["gate.${it.name}"] != "true" }
   values["disabled_reason"] = when {
    firstNotTrue == null -> "NONE"
    values["gate.${firstNotTrue.name}"] == "false" -> firstNotTrue.name
    else -> "UNKNOWN"
   }
   return HomeRestoreDiagnosticEvidence(values)
  }

  private fun summarize(values: MutableMap<String, String>, prefix: String,
                        files: Map<String, String>?): Map<String, String>? {
   if (files == null) { values["$prefix.state"] = "ABSENT"; return null }
   return try {
    // Refuse oversized inputs rather than hashing an incomplete prefix as a complete map.
    require(files.size <= MAX_SNAPSHOT_FILES)
    val snapshot = LinkedHashMap(files)
    val sha = contentSha256(snapshot)
    values["$prefix.state"] = "AVAILABLE"
    values["$prefix.file_count"] = snapshot.size.toString()
    values["$prefix.filemap_sha256"] = sha
    snapshot
   } catch (_: Exception) {
    values["$prefix.state"] = "INVALID"
    null
   }
  }

  internal fun encode(evidence: HomeRestoreDiagnosticEvidence): String = codec.encodeToString(serializer, evidence.values)
  internal fun decode(encoded: String): Map<String, String>? = try {
   if (encoded.length > MAX_BYTES || encoded.toByteArray(Charsets.UTF_8).size > MAX_BYTES) null else {
    val values = codec.decodeFromString(serializer, encoded)
    if (values.isEmpty() || values.size > keys.size || values.any { !valid(it.key, it.value) } ||
        !consistent(values) || codec.encodeToString(serializer, values) != encoded) null else values
   }
  } catch (_: Exception) { null }

  internal fun isValid(encoded: String): Boolean = decode(encoded) != null
  private fun flag(value: String) = value == "true" || value == "false"
  private fun valid(key: String, value: String): Boolean {
   if (key !in keys || value.length > 64) return false
   return when (key) {
    "current.state", "target.state" -> value in states
    "current.file_count", "target.file_count" -> value.toIntOrNull()?.let {
     it in 0..MAX_SNAPSHOT_FILES && value == it.toString()
    } == true
    "current.filemap_sha256", "target.filemap_sha256", "selected.commit_sha256" -> digest.matches(value)
    "disabled_reason" -> value == "NONE" || value == "UNKNOWN" || conditionGates.any { it.name == value }
    else -> flag(value)
   }
  }

  /** Reject forged on-disk state combinations instead of making them exportable. */
  private fun consistent(values: Map<String, String>): Boolean {
   if (values["selected.tip"] == null || values["disabled_reason"] == null) return false
   for (prefix in listOf("current", "target")) {
    val state = values["$prefix.state"] ?: return false
    val count = values["$prefix.file_count"]
    val sha = values["$prefix.filemap_sha256"]
    if (state == "AVAILABLE") { if (count == null || sha == null) return false }
    else if (count != null || sha != null) return false
   }
   val comparable = values["current.state"] == "AVAILABLE" && values["target.state"] == "AVAILABLE"
   if (comparable != values.containsKey("filemaps_equal")) return false
   val firstNotTrue = conditionGates.firstOrNull { values["gate.${it.name}"] != "true" }
   val reason = when {
    firstNotTrue == null -> "NONE"
    values["gate.${firstNotTrue.name}"] == "false" -> firstNotTrue.name
    else -> "UNKNOWN"
   }
   return values["disabled_reason"] == reason
  }
 }
}
