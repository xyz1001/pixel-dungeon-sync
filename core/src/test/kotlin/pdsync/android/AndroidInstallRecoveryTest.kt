package pdsync.android

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Pure, synthetic recovery regression cases. No APK execution, signing, or device access. */
class AndroidInstallRecoveryTest {
    private val target = PackageTarget("example.game", 0)
    private val signer = "a".repeat(64)
    private val sourceHash = "b".repeat(64)
    private val stagedHash = "c".repeat(64)
    private val payloadHash = "d".repeat(64)
    private val shared = "example.shared"
    private fun input(): InstallPreparation {
        val source = InspectedApk("source", 100, sourceHash, ApkInspection(target.packageName, 5, null,
            setOf(signer), null, 1, null, emptySet(), sourceHash, EvidenceStatus.VERIFIED))
        val apk = source.copy(artifactId = "staged", sha256 = stagedHash,
            inspection = source.inspection.copy(sharedUserId = shared, inspectedSha256 = stagedHash))
        val entries = mapOf("classes.dex" to payloadHash, "res/raw/data" to payloadHash)
        val evidence = ApkRewriteEvidence(sourceHash, stagedHash, entries, entries, EvidenceStatus.VERIFIED,
            payloadHash, payloadHash, EvidenceStatus.VERIFIED)
        return InstallPreparation(target, ApkIntake.SelectedFiles(CompleteApkSet(listOf(source), emptySet(), EvidenceStatus.VERIFIED)),
            listOf(StagedApk(apk, true, 100, stagedHash, evidence)), shared, setOf(signer), PackagePresence.Absent(target), "prepare")
    }
    private fun plan(input: InstallPreparation = input()) = assertIs<InstallPreparationResult.Ready>(AndroidInstallPlanValidator.prepare(input)).plan
    private fun reject(input: InstallPreparation, reason: InstallProblem) = assertContains(assertIs<InstallPreparationResult.Rejected>(AndroidInstallPlanValidator.prepare(input)).problems, reason)
    private fun advance(result: InstallTransition) = assertIs<InstallTransition.Advanced>(result).flow
    private fun installed(input: InstallPreparation, signer: String = this.signer, shared: String? = this.shared,
        version: Long = 5, hash: String = stagedHash) = PackagePresence.Present(InstalledPackageIdentity(target, version, setOf(signer), shared,
        1, null, 10001, "synthetic-instance", mapOf(null to hash)))
    private fun recheck(flow: AndroidInstallFlow, id: String, observed: PackagePresence = flow.plan.originalInstallation) =
        InstallRecheck(id, observed, flow.plan.stagedApks)

    @Test fun freshAndCompatibleKindsMatchRelease() {
        val fresh = input()
        assertEquals(InstallKind.FRESH_INSTALL, plan(fresh).kind)
        assertEquals(InstallKind.COMPATIBLE_UPDATE, plan(fresh.copy(installed = installed(fresh))).kind)
        assertEquals(InstallKind.REQUIRES_UNINSTALL, plan(fresh.copy(installed = installed(fresh, shared = null))).kind)
    }
    @Test fun unknownAndWrongUserAreRejected() {
        val input = input()
        reject(input.copy(installed = PackagePresence.Unknown(target)), InstallProblem.UNKNOWN_INSTALLED_STATE)
        reject(input.copy(installed = PackagePresence.Absent(target.copy(androidUserId = 10))), InstallProblem.WRONG_TARGET)
    }
    @Test fun unverifiedInventoriesAndMissingBaseAreRejected() {
        val input = input(); val source = input.source.apkSet
        reject(input.copy(source = ApkIntake.SelectedFiles(source.copy(inventoryVerification = EvidenceStatus.UNKNOWN))), InstallProblem.INCOMPLETE_APK_SET)
        reject(input.copy(source = ApkIntake.SelectedFiles(source.copy(apks = emptyList()))), InstallProblem.INCOMPLETE_APK_SET)
        reject(input.copy(source = ApkIntake.SelectedFiles(source.copy(expectedSplits = setOf("feature")))), InstallProblem.INCOMPLETE_APK_SET)
    }
    @Test fun duplicateAndInvalidSplitEvidenceAreRejected() {
        val input = input(); val source = input.source.apkSet
        reject(input.copy(source = ApkIntake.SelectedFiles(source.copy(apks = source.apks + source.apks))), InstallProblem.DUPLICATE_APK)
        reject(input.copy(source = ApkIntake.SelectedFiles(source.copy(apks = listOf(source.apks[0].copy(
            inspection = source.apks[0].inspection.copy(requiredSplits = setOf("feature"))))))), InstallProblem.MISSING_SPLIT_DEPENDENCY)
    }
    @Test fun allProtectedEntriesAreChecked() {
        for (name in listOf("../data", "path/../data", "path//data", "path/./data", "AndroidManifest.xml", "/absolute", "a\\b", "nul\u0000")) {
            val input = input(); val staged = input.stagedApks.single(); val evidence = staged.rewriteEvidence!!
            val entries = linkedMapOf("classes.dex" to payloadHash, name to payloadHash)
            reject(input.copy(stagedApks = listOf(staged.copy(rewriteEvidence = evidence.copy(
                sourceProtectedEntriesSha256 = entries, stagedProtectedEntriesSha256 = entries)))), InstallProblem.REWRITE_NOT_VERIFIED)
        }
    }
    @Test fun everyPayloadHashAndManifestProofMustMatch() {
        val input = input(); val staged = input.stagedApks.single(); val proof = staged.rewriteEvidence!!
        val entries = linkedMapOf("classes.dex" to payloadHash, "assets/data" to "bad")
        reject(input.copy(stagedApks = listOf(staged.copy(rewriteEvidence = proof.copy(
            sourceProtectedEntriesSha256 = entries, stagedProtectedEntriesSha256 = entries)))), InstallProblem.REWRITE_NOT_VERIFIED)
        reject(input.copy(stagedApks = listOf(staged.copy(rewriteEvidence = proof.copy(stagedProtectedManifestSha256 = sourceHash)))), InstallProblem.REWRITE_NOT_VERIFIED)
    }
    @Test fun unverifiedCopyAndReusedSourcePathAreRejected() {
        val input = input(); val staged = input.stagedApks.single()
        reject(input.copy(stagedApks = listOf(staged.copy(rereadByteCount = 99))), InstallProblem.STAGING_NOT_VERIFIED)
        reject(input.copy(stagedApks = listOf(staged.copy(apk = staged.apk.copy(artifactId = "source")))), InstallProblem.STAGING_NOT_VERIFIED)
    }
    @Test fun preparedVersionAndSharedUidMustMatch() {
        val input = input(); val staged = input.stagedApks.single()
        reject(input.copy(stagedApks = listOf(staged.copy(apk = staged.apk.copy(inspection = staged.apk.inspection.copy(versionCode = 6))))), InstallProblem.PREPARED_SET_CHANGED)
        reject(input.copy(expectedSharedUserId = "other.shared"), InstallProblem.WRONG_SHARED_UID)
        reject(input.copy(installed = installed(input, version = 6)), InstallProblem.DOWNGRADE)
    }
    @Test fun extractedSourceRequiresExactInstalledIdentityAndApkHashes() {
        val input = input(); val identity = installed(input, hash = sourceHash).identity
        val extracted = input.copy(source = ApkIntake.InstalledPackage(identity, input.source.apkSet), installed = PackagePresence.Present(identity))
        assertEquals(InstallKind.COMPATIBLE_UPDATE, plan(extracted).kind)
        reject(extracted.copy(source = ApkIntake.InstalledPackage(identity.copy(apkSha256BySplit = mapOf(null to stagedHash)), input.source.apkSet)), InstallProblem.SOURCE_INSTALLATION_CHANGED)
    }
    @Test fun preparedPlanDeepCopiesInputsAndIsUnmodifiable() {
        val input = input(); val entries = linkedMapOf("classes.dex" to payloadHash)
        val staged = input.stagedApks.single().let { it.copy(rewriteEvidence = it.rewriteEvidence!!.copy(
            sourceProtectedEntriesSha256 = entries, stagedProtectedEntriesSha256 = entries)) }
        val apks = arrayListOf(staged); val plan = plan(input.copy(stagedApks = apks))
        apks.clear(); entries.clear()
        assertEquals(1, plan.stagedApks.size)
        assertEquals(1, plan.stagedApks.single().rewriteEvidence!!.sourceProtectedEntriesSha256.size)
        assertFailsWith<UnsupportedOperationException> { (plan.stagedApks as MutableList).clear() }
    }
    @Test fun freshInstallRequiresCorrelatedApprovalAndExactObservation() {
        val ready = advance(plan().newFlow().begin())
        val request = assertIs<InstallTransition.Advanced>(ready.requestInstall(recheck(ready, "install")))
        assertTrue(request.systemRequest!!.requiresExplicitSystemUserApproval)
        assertEquals(FlowProblem.UNEXPECTED_SYSTEM_RESULT, assertIs<InstallTransition.Blocked>(request.flow.installerResult("wrong", SystemResult.SUCCEEDED, true, installed(input()))).problem)
        assertEquals(InstallStage.RECOVERABLE, advance(request.flow.installerResult("install", SystemResult.SUCCEEDED, false, installed(input()))).stage)
        val done = advance(request.flow.installerResult("install", SystemResult.SUCCEEDED, true, installed(input())))
        assertEquals(InstallStage.COMPLETED, done.stage)
        assertFalse(done.mustRetainStagedApks)
    }
    @Test fun replacementNeedsWarningAndSeparateSystemUninstall() {
        val input = input(); val original = installed(input, shared = null)
        val warning = advance(plan(input.copy(installed = original)).newFlow().begin())
        assertEquals(ANDROID_LOCAL_DATA_LOSS_WARNING, warning.warning)
        assertEquals(FlowProblem.WARNING_NOT_ACKNOWLEDGED, assertIs<InstallTransition.Blocked>(warning.acknowledgeLossAndRequestUninstall(false, recheck(warning, "uninstall"))).problem)
        val uninstall = advance(warning.acknowledgeLossAndRequestUninstall(true, recheck(warning, "uninstall")))
        assertTrue(uninstall.localDataMayHaveBeenDeleted)
        val ready = advance(uninstall.uninstallResult("uninstall", SystemResult.SUCCEEDED, true, PackagePresence.Absent(target)))
        assertTrue(ready.originalRemovalObserved)
        assertEquals(InstallStage.READY_FOR_INSTALLER, ready.stage)
        assertEquals(InstallStage.AWAITING_INSTALLER_RESULT, advance(ready.requestInstall(recheck(ready, "install", PackagePresence.Absent(target)))).stage)
    }
    @Test fun cancellationBeforeRemovalRetainsOriginal() {
        val input = input(); val original = installed(input, shared = null)
        val warning = advance(plan(input.copy(installed = original)).newFlow().begin())
        assertEquals(InstallStage.CANCELLED_BEFORE_UNINSTALL, advance(warning.cancel()).stage)
        val uninstall = advance(warning.acknowledgeLossAndRequestUninstall(true, recheck(warning, "u")))
        val cancelled = advance(uninstall.uninstallResult("u", SystemResult.CANCELLED, false, original))
        assertEquals(InstallStage.CANCELLED_BEFORE_UNINSTALL, cancelled.stage)
        assertFalse(cancelled.localDataMayHaveBeenDeleted)
    }
    @Test fun recoveryBlocksUnsettledRequestsAndReusedObservation() {
        val ready = advance(plan().newFlow().begin())
        assertEquals(FlowProblem.REUSED_OBSERVATION, assertIs<InstallTransition.Blocked>(ready.requestInstall(recheck(ready, "prepare"))).problem)
        val request = advance(ready.requestInstall(recheck(ready, "i")))
        val restored = advance(AndroidInstallFlow.restore(request.checkpoint()))
        assertEquals("i", restored.pendingRequestId)
        assertEquals(FlowProblem.SYSTEM_REQUEST_NOT_SETTLED, assertIs<InstallTransition.Blocked>(restored.resume(recheck(restored, "resume"), false)).problem)
        assertEquals(InstallStage.COMPLETED, advance(restored.resume(recheck(restored, "resume", installed(input())), true)).stage)
    }
    @Test fun forgedCheckpointAndChangedStagingFailClosed() {
        val initial = plan().newFlow()
        assertEquals(FlowProblem.INVALID_CHECKPOINT, assertIs<InstallTransition.Blocked>(AndroidInstallFlow.restore(initial.checkpoint().copy(originalRemovalObserved = true))).problem)
        assertEquals(FlowProblem.INVALID_CHECKPOINT, assertIs<InstallTransition.Blocked>(AndroidInstallFlow.restore(initial.checkpoint().copy(pendingRequestId = "never-issued"))).problem)
        val ready = advance(initial.begin())
        assertEquals(FlowProblem.STAGING_CHANGED, assertIs<InstallTransition.Blocked>(ready.requestInstall(recheck(ready, "i").copy(stagedApks = emptyList()))).problem)
    }
}
