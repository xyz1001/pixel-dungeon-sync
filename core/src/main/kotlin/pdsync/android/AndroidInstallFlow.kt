package pdsync.android

import pdsync.i18n.CoreMessages
val ANDROID_LOCAL_DATA_LOSS_WARNING get() = CoreMessages.THIS_REPLACEMENT_REQUIRES_UNINSTALLING_THE_ORIGINAL_GAME_FIRST_INCLUDING_THE_FIRST_SW.text()

enum class InstallStage {
    PREPARED_VERIFIED, AWAITING_WARNING_ACKNOWLEDGEMENT, READY_FOR_INSTALLER,
    AWAITING_UNINSTALL_RESULT, AWAITING_INSTALLER_RESULT, RECOVERABLE, CANCELLED_BEFORE_UNINSTALL, COMPLETED
}
enum class FlowProblem {
    WRONG_STAGE, WARNING_NOT_ACKNOWLEDGED, REUSED_OBSERVATION, WRONG_TARGET,
    UNKNOWN_INSTALLED_STATE, INSTALLED_IDENTITY_CHANGED, STAGING_CHANGED,
    SYSTEM_REQUEST_NOT_SETTLED, UNEXPECTED_SYSTEM_RESULT, INVALID_CHECKPOINT
}
enum class SystemResult { SUCCEEDED, CANCELLED, FAILED, UNKNOWN }
data class InstallRecheck(val observationId: String, val installed: PackagePresence, val stagedApks: List<StagedApk>)
data class AndroidInstallCheckpoint(
    val preparation: InstallPreparation,
    val stage: InstallStage,
    val observations: Set<String>,
    val uninstallRequested: Boolean,
    val installerRequested: Boolean,
    val originalRemovalObserved: Boolean,
    val pendingRequestId: String?
)
sealed interface AndroidSystemRequest {
    val target: PackageTarget
    val requestId: String
    val requiresExplicitSystemUserApproval: Boolean get() = true
    data class Uninstall(override val target: PackageTarget, override val requestId: String) : AndroidSystemRequest
    data class Install(override val target: PackageTarget, override val requestId: String, val stagedApks: List<StagedApk>) : AndroidSystemRequest
}
sealed interface InstallTransition {
    data class Advanced(val flow: AndroidInstallFlow, val systemRequest: AndroidSystemRequest? = null) : InstallTransition
    data class Blocked(val problem: FlowProblem) : InstallTransition
}

// Recovered from code-13 DEX, preserving request correlation and fail-closed recovery.
class AndroidInstallFlow private constructor(
    val plan: AndroidInstallPlan,
    val stage: InstallStage,
    private val observations: Set<String>,
    private val uninstallRequested: Boolean,
    private val installerRequested: Boolean,
    val originalRemovalObserved: Boolean,
    val pendingRequestId: String?
) {
    val warning: String? get() = if (plan.kind == InstallKind.REQUIRES_UNINSTALL) ANDROID_LOCAL_DATA_LOSS_WARNING else null
    val mustRetainStagedApks: Boolean get() = stage != InstallStage.COMPLETED && stage != InstallStage.CANCELLED_BEFORE_UNINSTALL
    val localDataMayHaveBeenDeleted: Boolean get() = uninstallRequested

    fun checkpoint() = AndroidInstallCheckpoint(plan.preparation, stage, observations, uninstallRequested,
        installerRequested, originalRemovalObserved, pendingRequestId)

    fun begin(): InstallTransition = if (stage == InstallStage.PREPARED_VERIFIED)
        advanced(if (plan.kind == InstallKind.REQUIRES_UNINSTALL) InstallStage.AWAITING_WARNING_ACKNOWLEDGEMENT else InstallStage.READY_FOR_INSTALLER)
    else blocked(FlowProblem.WRONG_STAGE)

    fun acknowledgeLossAndRequestUninstall(acknowledged: Boolean, recheck: InstallRecheck): InstallTransition {
        if (stage != InstallStage.AWAITING_WARNING_ACKNOWLEDGEMENT) return blocked(FlowProblem.WRONG_STAGE)
        if (!acknowledged) return blocked(FlowProblem.WARNING_NOT_ACKNOWLEDGED)
        check(recheck)?.let { return blocked(it) }
        if (recheck.installed != plan.originalInstallation) return blocked(FlowProblem.INSTALLED_IDENTITY_CHANGED)
        return advanced(InstallStage.AWAITING_UNINSTALL_RESULT, recheck.observationId, uninstall = true,
            request = AndroidSystemRequest.Uninstall(plan.target, recheck.observationId))
    }

    fun requestInstall(recheck: InstallRecheck): InstallTransition {
        if (stage != InstallStage.READY_FOR_INSTALLER) return blocked(FlowProblem.WRONG_STAGE)
        check(recheck)?.let { return blocked(it) }
        val expected = if (plan.kind == InstallKind.REQUIRES_UNINSTALL) PackagePresence.Absent(plan.target) else plan.originalInstallation
        if (recheck.installed != expected) return blocked(FlowProblem.INSTALLED_IDENTITY_CHANGED)
        return advanced(InstallStage.AWAITING_INSTALLER_RESULT, recheck.observationId, installer = true,
            request = AndroidSystemRequest.Install(plan.target, recheck.observationId, plan.stagedApks))
    }

    fun uninstallResult(requestId: String, result: SystemResult, systemUserApproved: Boolean, observed: PackagePresence): InstallTransition {
        if (stage != InstallStage.AWAITING_UNINSTALL_RESULT) return blocked(FlowProblem.WRONG_STAGE)
        if (requestId != pendingRequestId) return blocked(FlowProblem.UNEXPECTED_SYSTEM_RESULT)
        if (observed.target != plan.target) return blocked(FlowProblem.WRONG_TARGET)
        if (result == SystemResult.SUCCEEDED && systemUserApproved && observed is PackagePresence.Absent)
            return advanced(InstallStage.READY_FOR_INSTALLER, removed = true)
        if (result == SystemResult.CANCELLED && observed == plan.originalInstallation)
            return advanced(InstallStage.CANCELLED_BEFORE_UNINSTALL, uninstall = false)
        return advanced(InstallStage.RECOVERABLE, removed = originalRemovalObserved || observed is PackagePresence.Absent)
    }

    fun installerResult(requestId: String, result: SystemResult, systemUserApproved: Boolean, observed: PackagePresence): InstallTransition {
        if (stage != InstallStage.AWAITING_INSTALLER_RESULT) return blocked(FlowProblem.WRONG_STAGE)
        if (requestId != pendingRequestId) return blocked(FlowProblem.UNEXPECTED_SYSTEM_RESULT)
        if (observed.target != plan.target) return blocked(FlowProblem.WRONG_TARGET)
        return if (result == SystemResult.SUCCEEDED && systemUserApproved && observed is PackagePresence.Present && plan.matchesInstalled(observed.identity))
            advanced(InstallStage.COMPLETED) else advanced(InstallStage.RECOVERABLE)
    }

    fun cancel(): InstallTransition = when {
        stage in terminalStages -> blocked(FlowProblem.WRONG_STAGE)
        uninstallRequested || installerRequested -> advanced(InstallStage.RECOVERABLE)
        else -> advanced(InstallStage.CANCELLED_BEFORE_UNINSTALL)
    }
    fun interrupted(): InstallTransition = if (stage in terminalStages) blocked(FlowProblem.WRONG_STAGE) else advanced(InstallStage.RECOVERABLE)

    fun resume(recheck: InstallRecheck, systemRequestsSettled: Boolean): InstallTransition {
        if (stage != InstallStage.RECOVERABLE) return blocked(FlowProblem.WRONG_STAGE)
        if (!systemRequestsSettled) return blocked(FlowProblem.SYSTEM_REQUEST_NOT_SETTLED)
        check(recheck)?.let { return blocked(it) }
        val installed = recheck.installed
        if (installerRequested && installed is PackagePresence.Present && plan.matchesInstalled(installed.identity))
            return advanced(InstallStage.COMPLETED, recheck.observationId)
        if (installed == plan.originalInstallation && !originalRemovalObserved)
            return advanced(if (plan.kind == InstallKind.REQUIRES_UNINSTALL) InstallStage.AWAITING_WARNING_ACKNOWLEDGEMENT else InstallStage.READY_FOR_INSTALLER,
                recheck.observationId, uninstall = false, installer = false)
        if (installed is PackagePresence.Absent && uninstallRequested)
            return advanced(InstallStage.READY_FOR_INSTALLER, recheck.observationId, removed = true)
        return blocked(FlowProblem.INSTALLED_IDENTITY_CHANGED)
    }

    private fun check(recheck: InstallRecheck): FlowProblem? = when {
        recheck.observationId.isBlank() || recheck.observationId in observations -> FlowProblem.REUSED_OBSERVATION
        recheck.installed.target != plan.target -> FlowProblem.WRONG_TARGET
        recheck.installed is PackagePresence.Unknown -> FlowProblem.UNKNOWN_INSTALLED_STATE
        recheck.installed is PackagePresence.Present && !validIdentity(recheck.installed.identity) -> FlowProblem.INSTALLED_IDENTITY_CHANGED
        !plan.matchesStaging(recheck.stagedApks) -> FlowProblem.STAGING_CHANGED
        else -> null
    }

    private fun advanced(
        next: InstallStage,
        observation: String? = null,
        uninstall: Boolean = uninstallRequested,
        installer: Boolean = installerRequested,
        removed: Boolean = originalRemovalObserved,
        request: AndroidSystemRequest? = null
    ): InstallTransition = InstallTransition.Advanced(AndroidInstallFlow(plan, next,
        if (observation == null) observations else frozenSet(observations + observation), uninstall, installer, removed,
        request?.requestId ?: if (next == InstallStage.RECOVERABLE) pendingRequestId else null), request)

    companion object {
        private val terminalStages = setOf(InstallStage.CANCELLED_BEFORE_UNINSTALL, InstallStage.COMPLETED)

        fun restore(checkpoint: AndroidInstallCheckpoint): InstallTransition {
            val prepared = AndroidInstallPlanValidator.prepare(checkpoint.preparation)
            if (prepared !is InstallPreparationResult.Ready || checkpoint.observations.any { it.isBlank() } ||
                checkpoint.preparation.observationId !in checkpoint.observations ||
                (checkpoint.pendingRequestId != null && checkpoint.pendingRequestId !in checkpoint.observations) ||
                (checkpoint.originalRemovalObserved && !checkpoint.uninstallRequested) ||
                (checkpoint.uninstallRequested && prepared.plan.kind != InstallKind.REQUIRES_UNINSTALL) ||
                (checkpoint.stage == InstallStage.AWAITING_UNINSTALL_RESULT && !checkpoint.uninstallRequested) ||
                (checkpoint.stage == InstallStage.AWAITING_INSTALLER_RESULT && !checkpoint.installerRequested))
                return blocked(FlowProblem.INVALID_CHECKPOINT)
            if (checkpoint.stage in terminalStages) return blocked(FlowProblem.WRONG_STAGE)
            return InstallTransition.Advanced(AndroidInstallFlow(prepared.plan, InstallStage.RECOVERABLE,
                frozenSet(checkpoint.observations), checkpoint.uninstallRequested, checkpoint.installerRequested,
                checkpoint.originalRemovalObserved, checkpoint.pendingRequestId))
        }

        internal fun prepared(plan: AndroidInstallPlan) = AndroidInstallFlow(plan, InstallStage.PREPARED_VERIFIED,
            frozenSet(setOf(plan.preparation.observationId)), false, false, false, null)
        private fun blocked(problem: FlowProblem) = InstallTransition.Blocked(problem)
    }
}
