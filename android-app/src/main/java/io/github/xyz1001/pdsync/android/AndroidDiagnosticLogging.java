package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import pdsync.DiagnosticAction;
import pdsync.DiagnosticLog;
import pdsync.DiagnosticFailure;
import pdsync.DiagnosticResult;
import pdsync.DiagnosticStage;
import pdsync.DiagnosticTrace;

/** Only closed diagnostic enums cross this adapter. No UI details or app identifiers are logged. */
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
        if (state.systemRequestActive) return DiagnosticFailure.BUSY;
        if (state.restorePending) return DiagnosticFailure.RECOVERY_REQUIRED;
        switch (state.access) {
            case READY: return state.identityVerified ? DiagnosticFailure.NONE : DiagnosticFailure.IDENTITY_MISMATCH;
            case GAME_BUSY: case SYNC_BUSY: return DiagnosticFailure.BUSY;
            case RECOVERY_NEEDED: return DiagnosticFailure.RECOVERY_REQUIRED;
            case SECURITY_ERROR: return DiagnosticFailure.AUTHORIZATION;
            case IPC_ERROR: case CAPABILITY_UNAVAILABLE: return DiagnosticFailure.UNAVAILABLE;
            case FIRST_LAUNCH_NEEDED: case NOT_CHECKED: return DiagnosticFailure.NOT_READY;
            default: return DiagnosticFailure.VALIDATION;
        }
    }
    static void failure(DiagnosticTrace trace, DiagnosticStage stage, Throwable error) {
        String code = BridgeDiagnostics.code(error);
        if (code == null) { trace.failure(stage, error); return; }
        DiagnosticFailure category;
        switch (code) {
            case "ROOT_BUSY": case "GAME_FOREGROUND": case "GAME_RUNNING": case "SAVE_NOT_QUIET":
            case "GAME_SAVE_RACE": case "SYNC_BUSY": case "OPERATION_BUSY": category = DiagnosticFailure.BUSY; break;
            case "ROOT_RECOVERY_REQUIRED": case "RECOVERY_REQUIRED": category = DiagnosticFailure.RECOVERY_REQUIRED; break;
            case "ROOT_AUTH_REQUIRED": case "ROOT_DENIED_OR_EXITED": case "PROVIDER_ACQUIRE_SECURITY_REJECTED":
            case "IPC_SECURITY_REJECTED": category = DiagnosticFailure.AUTHORIZATION; break;
            case "ROOT_TARGET_CHANGED": case "ROOT_BINDING_MISMATCH": case "GAME_BINDING_MISMATCH":
            case "GAME_SIGNER_MISMATCH": case "PACKAGE_TRUST_FAILED": case "TARGET_IDENTITY_FAILED":
            case "UID_MISMATCH": case "UNEXPECTED_SHARED_UID": category = DiagnosticFailure.IDENTITY_MISMATCH; break;
            case "ROOT_TIMEOUT": case "ROOT_RUNNER_TIMEOUT": category = DiagnosticFailure.TIMEOUT; break;
            case "ROOT_CANCELLED": case "INTERRUPTED": category = DiagnosticFailure.CANCELED; break;
            case "ROOT_UNAVAILABLE": case "PROVIDER_ACQUIRE_NULL": case "PROVIDER_ACQUIRE_FAILED":
            case "IPC_CALL_FAILED": category = DiagnosticFailure.UNAVAILABLE; break;
            case "ROOT_SAVE_NOT_READY": case "SAVE_NOT_READY": case "FIRST_LAUNCH_NEEDED": category = DiagnosticFailure.NOT_READY; break;
            default: category = DiagnosticFailure.VALIDATION;
        }
        trace.record(stage, category == DiagnosticFailure.CANCELED ? DiagnosticResult.CANCELED : DiagnosticResult.FAILED, category);
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
    static void installerResult(int status) {
        DiagnosticLog.begin(DiagnosticAction.INSTALL, false).record(DiagnosticStage.INSTALLATION,
                status == -1 ? DiagnosticResult.PENDING : status == 0 ? DiagnosticResult.SUCCEEDED
                        : status == 3 ? DiagnosticResult.CANCELED : DiagnosticResult.FAILED);
    }
}
