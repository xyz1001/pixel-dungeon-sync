package io.github.xyz1001.pdsync.android;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import pdsync.DiagnosticAction;
import pdsync.DiagnosticFailure;
import pdsync.DiagnosticHttpMethod;
import pdsync.DiagnosticLog;
import pdsync.DiagnosticResult;
import pdsync.DiagnosticStage;
import pdsync.DiagnosticTrace;

/** Executed Android adapter checks against the real persistent recorder; no Android calls. */
public final class AndroidDiagnosticLoggingChecks {
    private static int checks;
    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        check(AndroidDiagnosticLogging.stage(DiagnosticAction.CONNECT) == DiagnosticStage.CONNECTION, "connection stage");
        check(AndroidDiagnosticLogging.stage(DiagnosticAction.PREPARE) == DiagnosticStage.PREPARATION, "preparation stage");
        check(AndroidDiagnosticLogging.stage(DiagnosticAction.INSTALL) == DiagnosticStage.INSTALLATION, "installer stage");
        check(AndroidDiagnosticLogging.stage(DiagnosticAction.RESTORE) == DiagnosticStage.RESTORE, "restore stage");
        check(AndroidDiagnosticLogging.stage(DiagnosticAction.RECOVERY) == DiagnosticStage.RECOVERY, "recovery stage");
        for (String state : new String[]{"UPLOADED", "UNCHANGED"})
            check(AndroidDiagnosticLogging.automaticResult(state) == DiagnosticResult.SUCCEEDED, state);
        check(AndroidDiagnosticLogging.automaticResult("SKIPPED_CONFLICT") == DiagnosticResult.CONFLICT, "conflict distinct");
        for (String state : new String[]{"SKIPPED_UNCERTAIN", "PAUSED"})
            check(AndroidDiagnosticLogging.automaticResult(state) == DiagnosticResult.SKIPPED, state);
        check(AndroidDiagnosticLogging.automaticResult("CANCELLED") == DiagnosticResult.CANCELED, "cancel distinct");
        check(AndroidDiagnosticLogging.automaticResult("BLOCKED") == DiagnosticResult.REJECTED, "guard distinct");
        for (String state : new String[]{"RETRY_LATER", "TIMEOUT", "UNTRUSTED_ARBITRARY"})
            check(AndroidDiagnosticLogging.automaticResult(state) == DiagnosticResult.FAILED, "unknown cannot succeed");
        for (InstallationReadiness.AccessStatus access : InstallationReadiness.AccessStatus.values()) {
            InstallationReadiness state = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                    true, false, false, access);
            DiagnosticFailure category = AndroidDiagnosticLogging.readinessFailure(state);
            check((access == InstallationReadiness.AccessStatus.READY) == (category == DiagnosticFailure.NONE), "only ready succeeds");
            if (access == InstallationReadiness.AccessStatus.GAME_BUSY || access == InstallationReadiness.AccessStatus.SYNC_BUSY)
                check(category == DiagnosticFailure.BUSY, "busy explicit");
        }
        check(AndroidDiagnosticLogging.automaticFailure("SAVE_NOT_STABLE") == DiagnosticFailure.BUSY, "unstable automatic save");
        check(AndroidDiagnosticLogging.automaticFailure("TARGET_CHANGED") == DiagnosticFailure.IDENTITY_MISMATCH, "target mismatch");
        check(AndroidDiagnosticLogging.automaticFailure("BRIDGE_UNAVAILABLE") == DiagnosticFailure.UNAVAILABLE, "bridge unavailable");
        check(AndroidDiagnosticLogging.automaticFailure("RESTORE_RECOVERY_REQUIRED") == DiagnosticFailure.RECOVERY_REQUIRED, "recovery blocked");
        Path temporary = Files.createTempDirectory("android-diagnostic-adapter-");
        try {
            DiagnosticLog.configure(temporary, "0.5.15-root-test+36", "android");
            DiagnosticTrace manual = DiagnosticLog.begin(DiagnosticAction.ACCESS_CHECK, false);
            DiagnosticTrace automatic = DiagnosticLog.begin(DiagnosticAction.AUTOMATIC_SYNC, true);
            try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(manual)) {
                DiagnosticLog.http(DiagnosticHttpMethod.PROPFIND, 404);
                AndroidDiagnosticLogging.failure(manual, DiagnosticStage.READINESS,
                        new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.SAVE_READINESS, "ROOT_BUSY", "SENSITIVE_PATH_TOKEN_PASSWORD", null));
                try (AndroidDiagnosticLogging.Scope nested = AndroidDiagnosticLogging.bind(automatic)) {
                    DiagnosticLog.record(DiagnosticStage.VERIFY, DiagnosticResult.SKIPPED, DiagnosticFailure.BUSY);
                }
                DiagnosticLog.record(DiagnosticStage.FINISHED, DiagnosticResult.REJECTED);
            }
            check(!DiagnosticLog.record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED), "trace cleared on executor reuse");
            AndroidDiagnosticLogging.installerResult(-1);
            AndroidDiagnosticLogging.installerResult(3);
            StringBuilder exported = new StringBuilder();
            try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(DiagnosticLog.snapshotExport()))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) exported.append(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            String records = exported.toString();
            check(records.contains("0.5.15-root-test+36"), "exact Android version and code recorded");
            check(records.contains("\"platform\":\"android\""), "platform recorded");
            check(records.contains("\"httpStatus\":404"), "actual numeric HTTP status retained");
            check(records.contains("\"failure\":\"BUSY\""), "known Root busy recorded");
            check(records.contains("\"result\":\"PENDING\""), "installer pending distinct");
            check(records.contains("\"result\":\"CANCELED\""), "installer cancellation distinct");
            check(records.contains("\"automatic\":true"), "automatic operation distinguished");
            check(!records.contains("SENSITIVE_PATH_TOKEN_PASSWORD"), "exception text never exported");
            check(!records.contains("ROOT_BUSY"), "raw bridge code not serialized");
            for (String line : records.split("\\n")) {
                if (line.contains("\"stage\":\"HTTP\"") || line.contains("\"stage\":\"FINISHED\""))
                    check(line.contains(manual.getId()), "same operation correlation restored after nesting");
                if (line.contains("\"stage\":\"VERIFY\"")) check(line.contains(automatic.getId()), "automatic trace bound inside its scope");
            }
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(temporary)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (Exception ignored) {} });
            }
        }
        System.out.println("Android diagnostic adapter: " + checks + " host checks passed; no Android service or network executed.");
    }
}
