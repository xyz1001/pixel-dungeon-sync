package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import pdsync.DiagnosticAction;
import pdsync.DiagnosticLog;
import pdsync.DiagnosticFailure;
import pdsync.DiagnosticDetails;
import pdsync.DiagnosticResult;
import pdsync.DiagnosticStage;
import pdsync.DiagnosticTrace;

/** Only closed enums and bounded preparation facts cross this adapter; no raw exception messages. */
final class AndroidDiagnosticLogging {
    private static boolean configured;
    static synchronized void configure(Context context) {
        if (configured) return;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            DiagnosticLog.configure(context.getFilesDir().getCanonicalFile().toPath(),
                    info.versionName + "+" + (android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode), "android");
            configured = true;
            DiagnosticLog.begin(DiagnosticAction.APP_START, false).record(DiagnosticStage.RUNTIME, DiagnosticResult.SUCCEEDED);
        } catch (Exception ignored) { /* Diagnostic storage failure must not disable the app. */ }
    }
    static DiagnosticStage stage(DiagnosticAction action) {
        switch (action) {
            case CONNECT: return DiagnosticStage.CONNECTION;
            case PREPARE: return DiagnosticStage.PREPARATION;
            case INSTALL: return DiagnosticStage.INSTALLATION;
            case ACCESS_CHECK: return DiagnosticStage.READINESS;
            case LAUNCH: return DiagnosticStage.GAME_LAUNCH;
            case RESTORE: return DiagnosticStage.RESTORE;
            case RECOVERY: return DiagnosticStage.RECOVERY;
            default: return DiagnosticStage.WORK;
        }
    }
    static DiagnosticFailure readinessFailure(InstallationReadiness state) {
        return readinessFailure(state, null);
    }
    static DiagnosticFailure readinessFailure(InstallationReadiness state, String code) {
        if (state.systemRequestActive) return DiagnosticFailure.SYSTEM_REQUEST_PENDING;
        if (state.restorePending) return DiagnosticFailure.RECOVERY_REQUIRED;
        if (state.game == InstallationReadiness.PackageStatus.NOT_SELECTED) return DiagnosticFailure.GAME_NOT_SELECTED;
        if (code != null && !"READY".equals(code) && !"NOT_CHECKED".equals(code)) return bridgeFailure(code);
        switch (state.access) {
            case READY: return state.identityVerified ? DiagnosticFailure.NONE : DiagnosticFailure.IDENTITY_MISMATCH;
            case GAME_BUSY: case SYNC_BUSY: return DiagnosticFailure.BUSY;
            case RECOVERY_NEEDED: return DiagnosticFailure.RECOVERY_REQUIRED;
            case SECURITY_ERROR: return DiagnosticFailure.AUTHORIZATION;
            case IPC_ERROR: return DiagnosticFailure.UNAVAILABLE;
            case CAPABILITY_UNAVAILABLE: return DiagnosticFailure.CAPABILITY_UNAVAILABLE;
            case FIRST_LAUNCH_NEEDED: return DiagnosticFailure.FIRST_LAUNCH_NEEDED;
            case NOT_CHECKED: return DiagnosticFailure.NOT_CHECKED;
            case UNRECOGNIZED_LAYOUT: return DiagnosticFailure.UNRECOGNIZED_LAYOUT;
            default: return DiagnosticFailure.VALIDATION;
        }
    }
    static DiagnosticFailure classify(Throwable error) {
        Throwable accountFailure = error;
        for (int depth = 0; depth < 8 && accountFailure != null; depth++, accountFailure = accountFailure.getCause()) {
            if (accountFailure instanceof ManagedAccountClient.ManagedAccountException)
                return ((ManagedAccountClient.ManagedAccountException) accountFailure).diagnosticFailure();
        }
        if (io.github.xyz1001.pdsync.android.prepare.PreparationDiagnostics.find(error) != null)
            return DiagnosticFailure.VALIDATION;
        String code = BridgeDiagnostics.code(error);
        if (code != null) return bridgeFailure(code);
        SyncInspectionDiagnostics.Failure inspection = SyncInspectionDiagnostics.find(error);
        return DiagnosticLog.classify(inspection != null && inspection.getCause() != null ? inspection.getCause() : error);
    }
    static void failure(DiagnosticTrace trace, DiagnosticStage stage, Throwable error) {
        DiagnosticFailure category = classify(error);
        trace.record(stage, category == DiagnosticFailure.CANCELED ? DiagnosticResult.CANCELED : DiagnosticResult.FAILED,
                category, failureDetails(error));
    }
    static DiagnosticFailure bridgeFailure(String code) {
        switch (code) {
            case "ROOT_BUSY": case "ROOT_UNRELIABLE": case "ROOT_CHANGED": case "GAME_FOREGROUND":
            case "GAME_RUNNING": case "SAVE_NOT_QUIET": case "PROCESS_STATE_UNKNOWN": case "SAVE_CHANGED":
            case "ACCESS_WORKERS_BUSY": case "GAME_SAVE_RACE": case "SYNC_BUSY": case "OPERATION_BUSY": return DiagnosticFailure.BUSY;
            case "ROOT_RECOVERY_REQUIRED": case "RECOVERY_REQUIRED": case "ROOT_RESTORE_CONFLICT":
            case "ROOT_RECOVERY_CONFLICT": case "ROOT_RESTORE_COMMITTED_CLEANUP_PENDING":
            case "ROOT_RECOVERY_COMMITTED_CLEANUP_PENDING": case "RESTORE_COMMITTED_CLEANUP_PENDING":
            case "RESTORE_ROLLED_BACK": return DiagnosticFailure.RECOVERY_REQUIRED;
            case "ACCESS_AUTHENTICATION_FAILED": case "ROOT_AUTH_REQUIRED": case "ROOT_DENIED_OR_EXITED": case "ROOT_NOT_ROOT":
            case "ROOT_UNSAFE_PATH": case "PROVIDER_ACQUIRE_SECURITY_REJECTED":
            case "IPC_SECURITY_REJECTED": return DiagnosticFailure.AUTHORIZATION;
            case "ROOT_TARGET_CHANGED": case "ROOT_BINDING_MISMATCH": case "GAME_BINDING_MISMATCH":
            case "GAME_SIGNER_MISMATCH": case "PACKAGE_TRUST_FAILED": case "TARGET_IDENTITY_FAILED":
            case "INVALID_GAME_BINDING": case "REPLY_IDENTITY_MISMATCH":
            case "UID_MISMATCH": case "UNEXPECTED_SHARED_UID": return DiagnosticFailure.IDENTITY_MISMATCH;
            case "ACCESS_HANDSHAKE_TIMEOUT": case "ROOT_TIMEOUT": case "ROOT_RUNNER_TIMEOUT": return DiagnosticFailure.TIMEOUT;
            case "ACCESS_HANDSHAKE_CANCELED": case "ROOT_CANCELLED": case "INTERRUPTED": return DiagnosticFailure.CANCELED;
            case "ROOT_UNAVAILABLE": case "PROVIDER_ACQUIRE_NULL": case "PROVIDER_ACQUIRE_FAILED":
            case "NO_PROVIDER_REPLY": case "IPC_CALL_FAILED": return DiagnosticFailure.UNAVAILABLE;
            case "PURE_HANDSHAKE_REQUIRED": case "UNKNOWN_METHOD": case "UNSUPPORTED_METHOD":
            case "ROOT_ATTRIBUTES_UNSUPPORTED": case "ROOT_MUTATION_UNAVAILABLE":
            case "ROOT_RESTORE_UNSUPPORTED": case "ROOT_RECOVERY_UNSUPPORTED": return DiagnosticFailure.CAPABILITY_UNAVAILABLE;
            case "ROOT_MISSING_DIRECTORY": case "FIRST_LAUNCH_NEEDED": return DiagnosticFailure.FIRST_LAUNCH_NEEDED;
            case "ROOT_UNRECOGNIZED_LAYOUT": case "UNRECOGNIZED_SAVE_LAYOUT": return DiagnosticFailure.UNRECOGNIZED_LAYOUT;
            case "ROOT_SAVE_NOT_READY": case "SAVE_NOT_READY": return DiagnosticFailure.NOT_READY;
            case "ROOT_IO_ERROR": case "SAVE_IO_ERROR": case "SAVE_UNREADABLE": return DiagnosticFailure.STORAGE;
            default: return DiagnosticFailure.VALIDATION;
        }
    }
    static DiagnosticDetails.Builder readinessDetails(InstallationReadiness state, String code) {
        return DiagnosticDetails.builder().mode(state.rootDirect ? "ROOT_DIRECT"
                : state.embeddedBridge ? "EMBEDDED_BRIDGE" : "LEGACY_HELPER_BRIDGE")
                .readinessCode(code).access(state.access.name()).gamePackageStatus(state.game.name())
                .helperPackageStatus(state.helper.name()).identityVerified(state.identityVerified)
                .systemRequestActive(state.systemRequestActive).restorePending(state.restorePending)
                .failureSource("SAVE_READINESS");
    }
    static DiagnosticDetails layoutDetails(String mode, BridgeSaveLayout layout) {
        DiagnosticDetails.Builder builder = DiagnosticDetails.builder().mode(mode).failureSource("SAVE_READINESS");
        appendLayout(builder, layout);
        return builder.build();
    }
    private static void appendLayout(DiagnosticDetails.Builder builder, BridgeSaveLayout layout) {
        if (layout != null) builder.saveLayout(layout.state, layout.included, layout.excluded,
                layout.unrecognized, layout.truncated, layout.valid);
    }
    static DiagnosticDetails failureDetails(Throwable error) {
        return failureDetailsBuilder(error).build();
    }
    static DiagnosticDetails.Builder failureDetailsBuilder(Throwable error) {
        DiagnosticDetails.Builder builder = DiagnosticDetails.builder();
        if (io.github.xyz1001.pdsync.android.prepare.PreparationDiagnostics.find(error) != null)
            builder.failureSource("PREPARATION").preparationEvidence(
                    io.github.xyz1001.pdsync.android.prepare.PreparationDiagnostics.details(error));
        BridgeDiagnostics.Failure bridge = BridgeDiagnostics.find(error);
        if (bridge != null) {
            builder.readinessCode(bridge.code).bridgeStage(bridge.stage.name()).failureSource(bridge.stage.name());
            if (bridge.code.startsWith("ROOT_")) builder.mode("ROOT_DIRECT");
            appendLayout(builder, bridge.saveLayout);
        }
        BridgeDiagnostics.RootEvidence root = BridgeDiagnostics.rootEvidence(error);
        if (root != null) builder.mode("ROOT_DIRECT").failureSource("ROOT")
                .rootStage(root.stage.name()).rootCategory(root.category.name()).errno(root.errno);
        SyncInspectionDiagnostics.Failure inspection = SyncInspectionDiagnostics.find(error);
        if (bridge == null && inspection != null) builder.failureSource(inspection.stage == SyncInspectionDiagnostics.Stage.HISTORY
                ? "REMOTE_HISTORY" : "SYNC_COMPARE");
        return builder;
    }

    static DiagnosticFailure automaticFailure(String reason) {
        if (reason == null) return DiagnosticFailure.NONE;
        switch (reason) {
            case "SAVE_NOT_STABLE": return DiagnosticFailure.BUSY;
            case "RESTORE_RECOVERY_REQUIRED": return DiagnosticFailure.RECOVERY_REQUIRED;
            case "TARGET_CHANGED": case "TARGET_UNVERIFIED": return DiagnosticFailure.IDENTITY_MISMATCH;
            case "BRIDGE_UNAVAILABLE": return DiagnosticFailure.UNAVAILABLE;
            case "FIRST_LAUNCH_NEEDED": case "REMOTE_ONLY": return DiagnosticFailure.NOT_READY;
            case "INTERRUPTED": return DiagnosticFailure.INTERRUPTED;
            default: return DiagnosticFailure.VALIDATION;
        }
    }
    static Scope bind(DiagnosticTrace trace) { return new Scope(trace); }
    static final class Scope implements AutoCloseable {
        private AutoCloseable binding;
        Scope(DiagnosticTrace trace) {
            try { binding = DiagnosticLog.bind(trace); }
            catch (Exception ignored) { /* Logging must not alter operation behavior. */ }
        }
        @Override public void close() {
            if (binding != null) try { binding.close(); }
            catch (Exception ignored) { /* Logging must not alter operation behavior. */ }
        }
    }
    static DiagnosticResult inspectionResult(String kind, boolean failed) {
        if (failed) return DiagnosticResult.FAILED;
        if ("BLOCKED".equals(kind)) return DiagnosticResult.REJECTED;
        if ("BRANCHED".equals(kind)) return DiagnosticResult.CONFLICT;
        return DiagnosticResult.SUCCEEDED;
    }
    static DiagnosticResult automaticResult(String state) {
        switch (state) {
            case "UPLOADED": case "UNCHANGED": return DiagnosticResult.SUCCEEDED;
            case "SKIPPED_CONFLICT": return DiagnosticResult.CONFLICT;
            case "SKIPPED_UNCERTAIN": case "PAUSED": return DiagnosticResult.SKIPPED;
            case "CANCELLED": return DiagnosticResult.CANCELED;
            case "BLOCKED": return DiagnosticResult.REJECTED;
            default: return DiagnosticResult.FAILED;
        }
    }
    static void confirmationEvent(String phase, DiagnosticResult result) {
        DiagnosticLog.begin(DiagnosticAction.INSTALL, false).record(DiagnosticStage.INSTALLATION, result,
                result == DiagnosticResult.FAILED ? DiagnosticFailure.OTHER : DiagnosticFailure.NONE,
                DiagnosticDetails.builder().failureSource("INSTALLATION").installationPhase(phase).build());
    }
    static void installerResult(int status) {
        DiagnosticLog.begin(DiagnosticAction.INSTALL, false).record(DiagnosticStage.INSTALLATION,
                status == -1 ? DiagnosticResult.PENDING : status == 0 ? DiagnosticResult.SUCCEEDED
                        : status == 3 ? DiagnosticResult.CANCELED : DiagnosticResult.FAILED);
    }
}
