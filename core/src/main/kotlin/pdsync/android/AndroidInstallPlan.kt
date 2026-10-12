package pdsync.android

import java.util.Collections

// Recovered from the released code-13 APK's Kotlin metadata and DEX instructions.
enum class EvidenceStatus { VERIFIED, UNKNOWN, FAILED }
enum class InstallKind { FRESH_INSTALL, COMPATIBLE_UPDATE, REQUIRES_UNINSTALL }
enum class InstallProblem {
    INVALID_EXPECTATION, UNKNOWN_INSTALLED_STATE, INVALID_INSTALLED_IDENTITY, WRONG_TARGET,
    INCOMPLETE_APK_SET, DUPLICATE_APK, INVALID_APK_EVIDENCE, INCONSISTENT_PACKAGE,
    INCONSISTENT_VERSION, INCONSISTENT_SIGNERS, MISSING_SPLIT_DEPENDENCY,
    SOURCE_INSTALLATION_CHANGED, PREPARED_SET_CHANGED, WRONG_SHARED_UID, WRONG_SANDBOX,
    SHARED_UID_MAX_SDK_PRESENT, WRONG_SIGNER, STAGING_NOT_VERIFIED, REWRITE_NOT_VERIFIED, DOWNGRADE
}

data class PackageTarget(val packageName: String, val androidUserId: Int)
data class ApkInspection(
    val packageName: String,
    val versionCode: Long,
    val splitName: String?,
    val signerCertificateSha256: Set<String>,
    val sharedUserId: String?,
    val targetSandboxVersion: Int,
    val sharedUserMaxSdkVersion: Int?,
    val requiredSplits: Set<String>,
    val inspectedSha256: String,
    val signatureVerification: EvidenceStatus
)
data class InspectedApk(val artifactId: String, val byteCount: Long, val sha256: String, val inspection: ApkInspection)
data class CompleteApkSet(val apks: List<InspectedApk>, val expectedSplits: Set<String>, val inventoryVerification: EvidenceStatus)
data class ApkRewriteEvidence(
    val sourceApkSha256: String,
    val stagedApkSha256: String,
    val sourceProtectedEntriesSha256: Map<String, String>,
    val stagedProtectedEntriesSha256: Map<String, String>,
    val completePayloadInventoryVerification: EvidenceStatus,
    val sourceProtectedManifestSha256: String,
    val stagedProtectedManifestSha256: String,
    val permittedManifestChangesVerification: EvidenceStatus
)
data class StagedApk(
    val apk: InspectedApk,
    val fullCopyConfirmed: Boolean,
    val rereadByteCount: Long?,
    val rereadSha256: String?,
    val rewriteEvidence: ApkRewriteEvidence? = null
)
data class InstalledPackageIdentity(
    val target: PackageTarget,
    val versionCode: Long,
    val signerCertificateSha256: Set<String>,
    val sharedUserId: String?,
    val targetSandboxVersion: Int,
    val sharedUserMaxSdkVersion: Int?,
    val linuxUid: Int,
    val installationInstance: String,
    val apkSha256BySplit: Map<String?, String>
)
sealed interface PackagePresence {
    val target: PackageTarget
    data class Absent(override val target: PackageTarget) : PackagePresence
    data class Present(val identity: InstalledPackageIdentity) : PackagePresence {
        override val target: PackageTarget get() = identity.target
    }
    data class Unknown(override val target: PackageTarget) : PackagePresence
}
sealed interface ApkIntake {
    val apkSet: CompleteApkSet
    data class InstalledPackage(val installation: InstalledPackageIdentity, override val apkSet: CompleteApkSet) : ApkIntake
    data class SelectedFiles(override val apkSet: CompleteApkSet) : ApkIntake
}
data class InstallPreparation(
    val target: PackageTarget,
    val source: ApkIntake,
    val stagedApks: List<StagedApk>,
    val expectedSharedUserId: String,
    val expectedSignerCertificateSha256: Set<String>,
    val installed: PackagePresence,
    val observationId: String
)
sealed interface InstallPreparationResult {
    data class Ready(val plan: AndroidInstallPlan) : InstallPreparationResult
    data class Rejected(val problems: Set<InstallProblem>) : InstallPreparationResult
}

class AndroidInstallPlan internal constructor(internal val preparation: InstallPreparation, val kind: InstallKind) {
    val target: PackageTarget get() = preparation.target
    val stagedApks: List<StagedApk> get() = preparation.stagedApks
    val source: ApkIntake get() = preparation.source
    val originalInstallation: PackagePresence get() = preparation.installed
    val versionCode: Long get() = stagedApks.first().apk.inspection.versionCode
    val expectedSharedUserId: String get() = preparation.expectedSharedUserId
    val expectedSignerCertificateSha256: Set<String> get() = preparation.expectedSignerCertificateSha256
    fun newFlow(): AndroidInstallFlow = AndroidInstallFlow.prepared(this)

    internal fun matchesInstalled(identity: InstalledPackageIdentity): Boolean =
        validIdentity(identity) && identity.target == target && identity.versionCode == versionCode &&
            identity.signerCertificateSha256 == expectedSignerCertificateSha256 &&
            identity.sharedUserId == expectedSharedUserId && identity.targetSandboxVersion == 1 &&
            identity.sharedUserMaxSdkVersion == null &&
            identity.apkSha256BySplit == stagedApks.associate { it.apk.inspection.splitName to it.apk.sha256 }

    internal fun matchesStaging(staged: List<StagedApk>): Boolean =
        staged.size == stagedApks.size && staged.all(::validStaging) &&
            staged.map { it.apk to it.rewriteEvidence }.toSet() == stagedApks.map { it.apk to it.rewriteEvidence }.toSet()
}

object AndroidInstallPlanValidator {
    fun prepare(input: InstallPreparation): InstallPreparationResult {
        val preparation = input.snapshot()
        val issues = linkedSetOf<InstallProblem>()
        if (!validPackageName(preparation.target.packageName) || preparation.target.androidUserId < 0 ||
            !validPackageName(preparation.expectedSharedUserId) || !validSigners(preparation.expectedSignerCertificateSha256) ||
            preparation.observationId.isBlank()) issues += InstallProblem.INVALID_EXPECTATION
        if (preparation.installed.target != preparation.target) issues += InstallProblem.WRONG_TARGET
        val installed = (preparation.installed as? PackagePresence.Present)?.identity
        if (preparation.installed is PackagePresence.Unknown) issues += InstallProblem.UNKNOWN_INSTALLED_STATE
        if (installed != null && !validIdentity(installed)) issues += InstallProblem.INVALID_INSTALLED_IDENTITY
        val source = preparation.source.apkSet
        validateSet(source, preparation.target.packageName, issues)
        val staged = CompleteApkSet(preparation.stagedApks.map { it.apk }, source.expectedSplits, source.inventoryVerification)
        validateSet(staged, preparation.target.packageName, issues)
        if (preparation.stagedApks.any { !validStaging(it) }) issues += InstallProblem.STAGING_NOT_VERIFIED
        if (preparation.stagedApks.any { candidate ->
                !validRewrite(candidate, source.apks.singleOrNull { it.inspection.splitName == candidate.apk.inspection.splitName })
            }) issues += InstallProblem.REWRITE_NOT_VERIFIED
        if (preparation.stagedApks.any { candidate -> source.apks.any { it.artifactId == candidate.apk.artifactId } })
            issues += InstallProblem.STAGING_NOT_VERIFIED
        if (source.apks.map { it.inspection.splitName }.toSet() != staged.apks.map { it.inspection.splitName }.toSet() ||
            source.apks.firstOrNull()?.inspection?.versionCode != staged.apks.firstOrNull()?.inspection?.versionCode)
            issues += InstallProblem.PREPARED_SET_CHANGED
        staged.apks.forEach {
            val inspection = it.inspection
            if (inspection.sharedUserId != preparation.expectedSharedUserId) issues += InstallProblem.WRONG_SHARED_UID
            if (inspection.targetSandboxVersion != 1) issues += InstallProblem.WRONG_SANDBOX
            if (inspection.sharedUserMaxSdkVersion != null) issues += InstallProblem.SHARED_UID_MAX_SDK_PRESENT
            if (inspection.signerCertificateSha256 != preparation.expectedSignerCertificateSha256) issues += InstallProblem.WRONG_SIGNER
        }
        val intake = preparation.source
        if (intake is ApkIntake.InstalledPackage) {
            val identity = intake.installation
            if (!validIdentity(identity) || identity != installed || identity.target != preparation.target ||
                identity.apkSha256BySplit != source.apks.associate { it.inspection.splitName to it.sha256 } ||
                source.apks.any { it.inspection.versionCode != identity.versionCode || it.inspection.signerCertificateSha256 != identity.signerCertificateSha256 })
                issues += InstallProblem.SOURCE_INSTALLATION_CHANGED
        }
        val version = staged.apks.firstOrNull()?.inspection?.versionCode
        if (installed != null && version != null && version < installed.versionCode) issues += InstallProblem.DOWNGRADE
        if (issues.isNotEmpty()) return InstallPreparationResult.Rejected(frozenSet(issues))
        val kind = when {
            installed == null -> InstallKind.FRESH_INSTALL
            installed.signerCertificateSha256 == preparation.expectedSignerCertificateSha256 &&
                installed.sharedUserId == preparation.expectedSharedUserId && installed.targetSandboxVersion == 1 &&
                installed.sharedUserMaxSdkVersion == null -> InstallKind.COMPATIBLE_UPDATE
            else -> InstallKind.REQUIRES_UNINSTALL
        }
        return InstallPreparationResult.Ready(AndroidInstallPlan(preparation, kind))
    }
}

private fun validateSet(set: CompleteApkSet, packageName: String, issues: MutableSet<InstallProblem>) {
    val splits = set.apks.map { it.inspection.splitName }
    if (set.inventoryVerification != EvidenceStatus.VERIFIED || set.expectedSplits.any { !validSplit(it) } ||
        splits.count { it == null } != 1 || splits.filterNotNull().toSet() != set.expectedSplits)
        issues += InstallProblem.INCOMPLETE_APK_SET
    if (splits.toSet().size != splits.size || set.apks.map { it.artifactId }.toSet().size != set.apks.size)
        issues += InstallProblem.DUPLICATE_APK
    if (set.apks.any { !validApk(it) }) issues += InstallProblem.INVALID_APK_EVIDENCE
    if (set.apks.any { it.inspection.packageName != packageName }) issues += InstallProblem.INCONSISTENT_PACKAGE
    if (set.apks.map { it.inspection.versionCode }.toSet().size > 1) issues += InstallProblem.INCONSISTENT_VERSION
    if (set.apks.map { it.inspection.signerCertificateSha256 }.toSet().size > 1) issues += InstallProblem.INCONSISTENT_SIGNERS
    if (set.apks.any { !set.expectedSplits.containsAll(it.inspection.requiredSplits) }) issues += InstallProblem.MISSING_SPLIT_DEPENDENCY
}

private val sha256Pattern = Regex("[0-9a-f]{64}")
private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
private val splitPattern = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")
private fun validPackageName(value: String) = packagePattern.matches(value)
private fun validSplit(value: String) = splitPattern.matches(value)
private fun validHash(value: String) = sha256Pattern.matches(value)
private fun validSigners(value: Set<String>) = value.isNotEmpty() && value.all(::validHash)
private fun validApk(apk: InspectedApk): Boolean {
    if (apk.artifactId.isBlank() || apk.byteCount <= 0 || !validHash(apk.sha256)) return false
    val inspection = apk.inspection
    if (inspection.signatureVerification != EvidenceStatus.VERIFIED || inspection.inspectedSha256 != apk.sha256 ||
        !validPackageName(inspection.packageName) || inspection.versionCode < 0 || !validSigners(inspection.signerCertificateSha256)) return false
    if (inspection.splitName != null && !validSplit(inspection.splitName)) return false
    if (inspection.requiredSplits.any { !validSplit(it) }) return false
    return (inspection.sharedUserId == null || validPackageName(inspection.sharedUserId)) &&
        inspection.targetSandboxVersion in 1..2 && (inspection.sharedUserMaxSdkVersion == null || inspection.sharedUserMaxSdkVersion > 0)
}
internal fun validStaging(staged: StagedApk) = validApk(staged.apk) && staged.fullCopyConfirmed &&
    staged.rereadByteCount == staged.apk.byteCount && staged.rereadSha256 == staged.apk.sha256
private fun validRewrite(staged: StagedApk, source: InspectedApk?): Boolean {
    val evidence = staged.rewriteEvidence ?: return false
    return source != null && evidence.sourceApkSha256 == source.sha256 && evidence.stagedApkSha256 == staged.apk.sha256 &&
        evidence.completePayloadInventoryVerification == EvidenceStatus.VERIFIED &&
        evidence.permittedManifestChangesVerification == EvidenceStatus.VERIFIED &&
        evidence.sourceProtectedEntriesSha256.isNotEmpty() && evidence.sourceProtectedEntriesSha256 == evidence.stagedProtectedEntriesSha256 &&
        evidence.sourceProtectedEntriesSha256.all { (name, hash) -> validProtectedEntry(name) && validHash(hash) } &&
        validHash(evidence.sourceProtectedManifestSha256) && evidence.sourceProtectedManifestSha256 == evidence.stagedProtectedManifestSha256
}
private fun validProtectedEntry(name: String) = name.isNotBlank() && name != "AndroidManifest.xml" &&
    !name.startsWith("/") && '\\' !in name && '\u0000' !in name && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
internal fun validIdentity(identity: InstalledPackageIdentity): Boolean =
    validPackageName(identity.target.packageName) && identity.target.androidUserId >= 0 && identity.versionCode >= 0 &&
        validSigners(identity.signerCertificateSha256) && identity.linuxUid >= 0 && identity.installationInstance.isNotBlank() &&
        identity.apkSha256BySplit.containsKey(null) && identity.apkSha256BySplit.values.all(::validHash) &&
        identity.apkSha256BySplit.keys.filterNotNull().all(::validSplit) && identity.targetSandboxVersion in 1..2 &&
        (identity.sharedUserId == null || validPackageName(identity.sharedUserId)) &&
        (identity.sharedUserMaxSdkVersion == null || identity.sharedUserMaxSdkVersion > 0)

internal fun <T> frozenList(items: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
internal fun <T> frozenSet(items: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(items))
private fun InspectedApk.snapshot() = copy(inspection = inspection.copy(
    signerCertificateSha256 = frozenSet(inspection.signerCertificateSha256), requiredSplits = frozenSet(inspection.requiredSplits)))
internal fun StagedApk.snapshot() = copy(apk = apk.snapshot(), rewriteEvidence = rewriteEvidence?.let {
    it.copy(sourceProtectedEntriesSha256 = Collections.unmodifiableMap(LinkedHashMap(it.sourceProtectedEntriesSha256)),
        stagedProtectedEntriesSha256 = Collections.unmodifiableMap(LinkedHashMap(it.stagedProtectedEntriesSha256)))
})
private fun CompleteApkSet.snapshot() = copy(apks = frozenList(apks.map { it.snapshot() }), expectedSplits = frozenSet(expectedSplits))
internal fun InstalledPackageIdentity.snapshot() = copy(signerCertificateSha256 = frozenSet(signerCertificateSha256),
    apkSha256BySplit = Collections.unmodifiableMap(LinkedHashMap(apkSha256BySplit)))
internal fun PackagePresence.snapshot(): PackagePresence = when (this) {
    is PackagePresence.Absent -> copy()
    is PackagePresence.Unknown -> copy()
    is PackagePresence.Present -> PackagePresence.Present(identity.snapshot())
}
private fun InstallPreparation.snapshot() = copy(
    source = when (val intake = source) {
        is ApkIntake.InstalledPackage -> intake.copy(installation = intake.installation.snapshot(), apkSet = intake.apkSet.snapshot())
        is ApkIntake.SelectedFiles -> intake.copy(apkSet = intake.apkSet.snapshot())
    },
    stagedApks = frozenList(stagedApks.map { it.snapshot() }),
    expectedSignerCertificateSha256 = frozenSet(expectedSignerCertificateSha256), installed = installed.snapshot()
)
