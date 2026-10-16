package pdsync

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** APK intake facts, never an arbitrary exception/message bag. Used only by preparation diagnostics. */
internal object PreparationDiagnosticEvidence {
 private const val MAX_BYTES = 24 * 1024
 private const val MAX_FIELDS = 320
 private val codec = Json
 private val serializer = MapSerializer(String.serializer(), String.serializer())
 private val pkg = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
 private val className = Regex("[A-Za-z_$][A-Za-z0-9_.$]*")
 private val frame = Regex("[A-Za-z_$][A-Za-z0-9_.$]*\\.[A-Za-z_$<>][A-Za-z0-9_$<>]*:-?[0-9]{1,9}")
 private val input = Regex("input\\.([0-9]+)\\.(.+)")
 private val cause = Regex("cause\\.([0-9]+)\\.(.+)")
 private val issue = Regex("verifier\\.issue\\.([0-9]+)")
 private val parameter = Regex("verifier\\.parameter\\.([0-9]+)")
 private val algorithms = setOf("SHA1", "SHA-1", "SHA256", "SHA-256", "SHA384", "SHA-384", "SHA512", "SHA-512",
  "RSA", "DSA", "EC", "PKCS1", "RSASSA-PSS", "SHA256withRSA", "SHA512withRSA", "OMITTED")
 private val sourceTokens = setOf("INSTALLED_BASE_AND_SPLITS", "DOCUMENT_PICKER", "RETAINED_PREPARATION", "UNKNOWN")
 private val stageTokens = setOf("RUNTIME_CHECK", "EXTRACTION", "SOURCE_SIGNATURE_SET", "MANIFEST_PATCH", "LOCAL_KEY",
  "GAME_SIGNING", "HELPER_SIGNING", "OUTPUT_SIGNATURE", "PAYLOAD_VALIDATION", "RECEIPT_VALIDATION")
 private val metadataTokens = setOf("NOT_CHECKED", "PARSED", "FAILED")
 // Native apksig Issue enum names and fixed PreparationDiagnostics reason codes, not input text.
 private val reasonTokens = setOf(
  "ACCESS_DENIED",
  "APK_SET_BASE_MISSING",
  "APK_SET_MULTIPLE_BASES",
  "APK_SET_STRUCTURE_OR_SIGNER_MISMATCH",
  "APK_VERIFIER_REJECTED",
  "CERTIFICATE_PROCESSING_FAILED",
  "CRYPTO_ALGORITHM_UNAVAILABLE",
  "CRYPTO_KEY_REJECTED",
  "CRYPTO_PARAMETERS_REJECTED",
  "CRYPTO_PROVIDER_OPERATION_FAILED",
  "CRYPTO_PROVIDER_UNAVAILABLE",
  "CRYPTO_SIGNATURE_OPERATION_FAILED",
  "DEX_CHECKSUM_MISMATCH",
  "EMBEDDED_COMPONENT_IN_SPLIT",
  "EMBEDDED_RUNTIME_BOUNDARY_AMBIGUOUS",
  "EMBEDDED_RUNTIME_MISSING",
  "EMBEDDED_RUNTIME_NOT_RECOGNIZED",
  "EMBEDDED_UPGRADE_MANIFEST_CHANGED",
  "EMBEDDED_UPGRADE_PAYLOAD_CHANGED",
  "FILE_OR_ACCESS_UNAVAILABLE",
  "INHERITED_BINDING_MISMATCH",
  "INHERITED_RUNTIME_DIGEST_MISMATCH",
  "INPUT_CACHE_MISMATCH",
  "INSTALLED_SOURCE_CHANGED",
  "INSTALLED_SOURCE_CONTAINER_AMBIGUOUS",
  "INSTALLED_SOURCE_COPY_MISMATCH",
  "INSTALLED_SOURCE_NOT_PROTECTED",
  "KEYSTORE_OPERATION_FAILED",
  "KEY_CREATION_CONSENT_REQUIRED",
  "KEY_UNRECOVERABLE",
  "LOCAL_IDENTITY_MISMATCH",
  "LOCAL_KEY_MISSING_OR_CHANGED",
  "MANIFEST_INVALID_OR_UNSUPPORTED",
  "OMITTED",
  "OUTPUT_SIGNATURE_INVALID",
  "OUTPUT_SIGNATURE_SCHEME_MISSING",
  "OUTPUT_SIGNER_COUNT_INVALID",
  "PAYLOAD_OR_DIGEST_MISMATCH",
  "PUBLIC_SIGNER_WITHOUT_SOURCE_BINDING",
  "RECEIPT_BRIDGE_MODE_UNSUPPORTED",
  "RECEIPT_DIGEST_INVALID",
  "RECEIPT_FIELD_MISSING",
  "RECEIPT_FORMAT_UNSUPPORTED",
  "RECEIPT_ID_MISMATCH",
  "RECEIPT_NUMBER_INVALID",
  "RECEIPT_OBSOLETE_HELPER_FIELDS",
  "RECEIPT_OR_SOURCE_BINDING_INVALID",
  "RECEIPT_PAYLOAD_COUNT_MISMATCH",
  "RECEIPT_PROVIDER_IDENTITY_MISMATCH",
  "RECEIPT_SOURCE_VERSION_MISMATCH",
  "RUNTIME_DEX_NAME_INVALID",
  "RUNTIME_VERSION_UNSUPPORTED",
  "SELECTED_PACKAGE_UNAVAILABLE",
  "SIGNING_CERTIFICATE_MISMATCH",
  "SOURCE_NAMESPACE_MISMATCH",
  "SOURCE_PROVENANCE_MISMATCH",
  "SOURCE_SIGNATURE_INVALID",
  "SOURCE_SIGNER_MISSING",
  "SOURCE_SIGNING_BLOCK_OR_GAP",
  "SOURCE_SIGNING_CONTAINER_PRESENT_OR_AMBIGUOUS",
  "SPLIT_DEPENDENCY_CYCLE",
  "SPLIT_DEPENDENCY_MISSING",
  "SPLIT_PACKAGE_MISMATCH",
  "SPLIT_SIGNER_OR_PROVENANCE_MISMATCH",
  "SPLIT_VERSION_MISMATCH",
  "STAGED_GAME_DIGEST_MISMATCH",
  "STAGED_RUNTIME_DIGEST_MISMATCH",
  "STAGING_PATH_OUTSIDE_PRIVATE_ROOT",
  "TECHNICAL_SIZE_LIMIT",
  "ZIP_ALIGNMENT_INVALID",
  "ZIP_CENTRAL_DIRECTORY_INVALID",
  "ZIP_CRC_MISMATCH",
  "ZIP_DATA_DESCRIPTOR_INVALID",
  "ZIP_ENTRY_DUPLICATE_OR_UNSAFE",
  "ZIP_ENTRY_NAME_INVALID",
  "ZIP_EOCD_INVALID",
  "ZIP_EXTRA_FIELD_INVALID",
  "ZIP_LOCAL_CENTRAL_MISMATCH",
  "ZIP_LOCAL_RECORD_INVALID",
  "ZIP_PREAMBLE_UNSUPPORTED"
 )
 private val issueTokens = setOf(
  "APK_SIG_BLOCK_UNKNOWN_ENTRY_ID",
  "JAR_SIG_DID_NOT_VERIFY",
  "JAR_SIG_DUPLICATE_MANIFEST_SECTION",
  "JAR_SIG_DUPLICATE_SIG_FILE_SECTION",
  "JAR_SIG_DUPLICATE_ZIP_ENTRY",
  "JAR_SIG_MALFORMED_CERTIFICATE",
  "JAR_SIG_MANIFEST_MAIN_SECTION_DIGEST_DID_NOT_VERIFY",
  "JAR_SIG_MANIFEST_SECTION_DIGEST_DID_NOT_VERIFY",
  "JAR_SIG_MAX_SIGNATURES_EXCEEDED",
  "JAR_SIG_MISSING",
  "JAR_SIG_MISSING_APK_SIG_REFERENCED",
  "JAR_SIG_MISSING_FILE",
  "JAR_SIG_MISSING_VERSION_ATTR_IN_SIG_FILE",
  "JAR_SIG_MISSING_ZIP_ENTRY_REFERENCED_IN_MANIFEST",
  "JAR_SIG_NO_APK_SIG_STRIP_PROTECTION",
  "JAR_SIG_NO_MANIFEST",
  "JAR_SIG_NO_MANIFEST_DIGEST_IN_SIG_FILE",
  "JAR_SIG_NO_SIGNATURES",
  "JAR_SIG_NO_SIGNED_ZIP_ENTRIES",
  "JAR_SIG_NO_SIGNERS",
  "JAR_SIG_NO_ZIP_ENTRY_DIGEST_IN_MANIFEST",
  "JAR_SIG_NO_ZIP_ENTRY_DIGEST_IN_SIG_FILE",
  "JAR_SIG_PARSE_EXCEPTION",
  "JAR_SIG_UNKNOWN_APK_SIG_SCHEME_ID",
  "JAR_SIG_UNNNAMED_MANIFEST_SECTION",
  "JAR_SIG_UNNNAMED_SIG_FILE_SECTION",
  "JAR_SIG_UNPROTECTED_ZIP_ENTRY",
  "JAR_SIG_UNSUPPORTED_SIG_ALG",
  "JAR_SIG_VERIFY_EXCEPTION",
  "JAR_SIG_ZIP_ENTRY_DIGEST_DID_NOT_VERIFY",
  "JAR_SIG_ZIP_ENTRY_NOT_SIGNED",
  "JAR_SIG_ZIP_ENTRY_SIGNERS_MISMATCH",
  "MALFORMED_APK",
  "MIN_SIG_SCHEME_FOR_TARGET_SDK_NOT_MET",
  "NO_SIG_FOR_TARGET_SANDBOX_VERSION",
  "SOURCE_STAMP_CERTIFICATE_MISMATCH_BETWEEN_SIGNATURE_BLOCK_AND_APK",
  "SOURCE_STAMP_CERT_DIGEST_AND_SIG_BLOCK_MISSING",
  "SOURCE_STAMP_DID_NOT_VERIFY",
  "SOURCE_STAMP_EXPECTED_DIGEST_MISMATCH",
  "SOURCE_STAMP_INVALID_TIMESTAMP",
  "SOURCE_STAMP_MALFORMED_ATTRIBUTE",
  "SOURCE_STAMP_MALFORMED_CERTIFICATE",
  "SOURCE_STAMP_MALFORMED_LINEAGE",
  "SOURCE_STAMP_MALFORMED_SIGNATURE",
  "SOURCE_STAMP_NO_SIGNATURE",
  "SOURCE_STAMP_NO_SUPPORTED_SIGNATURE",
  "SOURCE_STAMP_POR_CERT_MISMATCH",
  "SOURCE_STAMP_POR_DID_NOT_VERIFY",
  "SOURCE_STAMP_SIGNATURE_BLOCK_WITHOUT_CERT_DIGEST",
  "SOURCE_STAMP_SIGNATURE_SCHEME_NOT_AVAILABLE",
  "SOURCE_STAMP_SIG_MISSING",
  "SOURCE_STAMP_UNKNOWN_ATTRIBUTE",
  "SOURCE_STAMP_UNKNOWN_SIG_ALGORITHM",
  "SOURCE_STAMP_VERIFY_EXCEPTION",
  "UNEXPECTED_EXCEPTION",
  "V2_SIG_APK_DIGEST_DID_NOT_VERIFY",
  "V2_SIG_DID_NOT_VERIFY",
  "V2_SIG_MALFORMED_ADDITIONAL_ATTRIBUTE",
  "V2_SIG_MALFORMED_CERTIFICATE",
  "V2_SIG_MALFORMED_DIGEST",
  "V2_SIG_MALFORMED_PUBLIC_KEY",
  "V2_SIG_MALFORMED_SIGNATURE",
  "V2_SIG_MALFORMED_SIGNER",
  "V2_SIG_MALFORMED_SIGNERS",
  "V2_SIG_MAX_SIGNATURES_EXCEEDED",
  "V2_SIG_MISSING",
  "V2_SIG_MISSING_APK_SIG_REFERENCED",
  "V2_SIG_NO_CERTIFICATES",
  "V2_SIG_NO_SIGNATURES",
  "V2_SIG_NO_SIGNERS",
  "V2_SIG_NO_SUPPORTED_SIGNATURES",
  "V2_SIG_PUBLIC_KEY_MISMATCH_BETWEEN_CERTIFICATE_AND_SIGNATURES_RECORD",
  "V2_SIG_SIG_ALG_MISMATCH_BETWEEN_SIGNATURES_AND_DIGESTS_RECORDS",
  "V2_SIG_UNKNOWN_ADDITIONAL_ATTRIBUTE",
  "V2_SIG_UNKNOWN_APK_SIG_SCHEME_ID",
  "V2_SIG_UNKNOWN_SIG_ALGORITHM",
  "V2_SIG_VERIFY_EXCEPTION",
  "V31_BLOCK_FOUND_WITHOUT_V3_BLOCK",
  "V31_BLOCK_MISSING",
  "V31_ROTATION_MIN_SDK_ATTR_MISSING",
  "V31_ROTATION_MIN_SDK_MISMATCH",
  "V31_ROTATION_TARGETS_DEV_RELEASE_ATTR_ON_V3_SIGNER",
  "V3_INCONSISTENT_LINEAGES",
  "V3_INCONSISTENT_SDK_VERSIONS",
  "V3_MAX_SDK_VERSION_MISMATCH_BETWEEN_SIGNER_AND_SIGNED_DATA_RECORD",
  "V3_MIN_SDK_VERSION_MISMATCH_BETWEEN_SIGNER_AND_SIGNED_DATA_RECORD",
  "V3_MISSING_SDK_VERSIONS",
  "V3_SIG_APK_DIGEST_DID_NOT_VERIFY",
  "V3_SIG_DID_NOT_VERIFY",
  "V3_SIG_INVALID_SDK_VERSIONS",
  "V3_SIG_MALFORMED_ADDITIONAL_ATTRIBUTE",
  "V3_SIG_MALFORMED_CERTIFICATE",
  "V3_SIG_MALFORMED_DIGEST",
  "V3_SIG_MALFORMED_LINEAGE",
  "V3_SIG_MALFORMED_PUBLIC_KEY",
  "V3_SIG_MALFORMED_SIGNATURE",
  "V3_SIG_MALFORMED_SIGNER",
  "V3_SIG_MALFORMED_SIGNERS",
  "V3_SIG_MULTIPLE_PAST_SIGNERS",
  "V3_SIG_MULTIPLE_SIGNERS",
  "V3_SIG_NO_CERTIFICATES",
  "V3_SIG_NO_SIGNATURES",
  "V3_SIG_NO_SIGNERS",
  "V3_SIG_NO_SUPPORTED_SIGNATURES",
  "V3_SIG_PAST_SIGNERS_MISMATCH",
  "V3_SIG_POR_CERT_MISMATCH",
  "V3_SIG_POR_DID_NOT_VERIFY",
  "V3_SIG_PUBLIC_KEY_MISMATCH_BETWEEN_CERTIFICATE_AND_SIGNATURES_RECORD",
  "V3_SIG_SIG_ALG_MISMATCH_BETWEEN_SIGNATURES_AND_DIGESTS_RECORDS",
  "V3_SIG_UNKNOWN_ADDITIONAL_ATTRIBUTE",
  "V3_SIG_UNKNOWN_SIG_ALGORITHM",
  "V3_SIG_VERIFY_EXCEPTION",
  "V41_SIG_NEEDS_TWO_SIGNERS",
  "V4_SIG_APK_ROOT_DID_NOT_VERIFY",
  "V4_SIG_APK_TREE_DID_NOT_VERIFY",
  "V4_SIG_DID_NOT_VERIFY",
  "V4_SIG_MALFORMED_CERTIFICATE",
  "V4_SIG_MALFORMED_PUBLIC_KEY",
  "V4_SIG_MALFORMED_SIGNERS",
  "V4_SIG_MULTIPLE_SIGNERS",
  "V4_SIG_NO_CERTIFICATE",
  "V4_SIG_NO_SIGNATURES",
  "V4_SIG_NO_SUPPORTED_SIGNATURES",
  "V4_SIG_PUBLIC_KEY_MISMATCH_BETWEEN_CERTIFICATE_AND_SIGNATURES_RECORD",
  "V4_SIG_UNEXPECTED_DIGESTS",
  "V4_SIG_UNKNOWN_SIG_ALGORITHM",
  "V4_SIG_V2_V3_DIGESTS_MISMATCH",
  "V4_SIG_V2_V3_SIGNERS_MISMATCH",
  "V4_SIG_VERIFY_EXCEPTION",
  "V4_SIG_VERSION_NOT_CURRENT"
 )
 fun encode(values: Map<String, String>?): String? {
  if (values == null) return null
  val kept = linkedMapOf<String, String>()
  var bytes = 2
  for ((key, value) in values) {
   if (!valid(key, value)) continue
   val addition = codec.encodeToString(serializer, mapOf(key to value)).toByteArray(Charsets.UTF_8).size
   if (kept.size >= MAX_FIELDS - 1 || bytes + addition > MAX_BYTES - 64) {
    kept["evidence_truncated"] = "true"; break
   }
   kept[key] = value; bytes += addition
  }
  return if (kept.isEmpty()) null else codec.encodeToString(serializer, kept)
 }
 fun decode(encoded: String): Map<String, String>? = try {
  if (encoded.toByteArray(Charsets.UTF_8).size > MAX_BYTES) null else {
   val values = codec.decodeFromString(serializer, encoded)
   if (values.isEmpty() || values.size > MAX_FIELDS || values.any { !valid(it.key, it.value) } || codec.encodeToString(serializer, values) != encoded) null else values
  }
 } catch (_: Exception) { null }
 fun isValid(encoded: String): Boolean = decode(encoded) != null
 private fun number(value: String, max: Long = Long.MAX_VALUE): Boolean =
  value.toLongOrNull()?.let { it in 0..max && it.toString() == value } == true
 private fun flag(value: String): Boolean = value == "true" || value == "false"
 private fun packageName(value: String): Boolean = value == "unknown" || (value.length <= 255 && pkg.matches(value))
 private fun knownClass(value: String): Boolean = value == "Throwable" || (value.length <= 180 && className.matches(value) &&
  listOf("java.", "javax.", "android.", "com.android.apksig.", "io.github.xyz1001.pdsync.", "sun.security.").any(value::startsWith))
 private fun canonicalIndex(value: String, max: Int): Boolean = value.toIntOrNull()?.let { it in 0..max && it.toString() == value } == true
 private fun safeParameter(value: String): Boolean {
  if (value in algorithms || flag(value) || number(value) || value.toLongOrNull()?.let { it < 0 && it.toString() == value } == true) return true
  if (value.length > 180 || ".." in value || ':' in value || '@' in value) return false
  return Regex("(?:AndroidManifest\\.xml|classes(?:[2-9]|[1-9][0-9]+)?\\.dex|META-INF/[A-Za-z0-9_.-]{1,80}|lib/(?:arm64-v8a|armeabi-v7a|x86|x86_64)/lib[A-Za-z0-9_.-]{1,80}\\.so)").matches(value)
 }
 private fun valid(key: String, value: String): Boolean {
  if (key.length > 80 || value.length > 512) return false
  when (key) {
   "source" -> return value in sourceTokens
   "stage" -> return value in stageTokens
   "api", "verifier.api" -> return number(value, 1000)
   "app_version_code", "game_version_code" -> return number(value)
   "apk_count" -> return number(value, 256)
   "game_package" -> return packageName(value)
   "verifier.elapsed_ms" -> return number(value, 7L * 24 * 60 * 60 * 1000)
   "inputs_truncated", "causes_truncated", "verifier.issues_truncated", "evidence_truncated" -> return flag(value)
   "verifier.schemes" -> return value == "unknown" || Regex("v1=(?:true|false),v2=(?:true|false),v3=(?:true|false),v31=(?:true|false),v4=(?:true|false)").matches(value)
  }
  input.matchEntire(key)?.let { match ->
   if (!canonicalIndex(match.groupValues[1], 15)) return false
   return when (match.groupValues[2]) {
    "bytes", "cache_bytes" -> number(value, 4L * 1024 * 1024 * 1024)
    "copy_ms" -> number(value, 7L * 24 * 60 * 60 * 1000)
    "sha256", "cache_sha256" -> value == "unknown" || Regex("[a-f0-9]{64}").matches(value)
    "eof", "cache_match" -> flag(value)
    "metadata_status" -> value in metadataTokens
    "package" -> packageName(value)
    "version_code" -> number(value)
    "min_sdk", "target_sdk" -> number(value, 1000)
    else -> false
   }
  }
  cause.matchEntire(key)?.let { match ->
   if (!canonicalIndex(match.groupValues[1], 7)) return false
   return when (val part = match.groupValues[2]) {
    "class" -> knownClass(value)
    "reason" -> value in reasonTokens
    "frames_truncated" -> flag(value)
    else -> part.startsWith("frame.") && canonicalIndex(part.removePrefix("frame."), 5) && value.length <= 240 &&
     frame.matches(value) && knownClass(value.substringBeforeLast('.'))
   }
  }
  issue.matchEntire(key)?.let { return canonicalIndex(it.groupValues[1], 11) && value in issueTokens }
  parameter.matchEntire(key)?.let { return canonicalIndex(it.groupValues[1], 31) && safeParameter(value) }
  return false
 }
}
