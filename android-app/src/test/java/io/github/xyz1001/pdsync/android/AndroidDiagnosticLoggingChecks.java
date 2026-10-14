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
        check(AndroidDiagnosticLogging.readinessFailure(InstallationReadiness.noSelection()) == DiagnosticFailure.GAME_NOT_SELECTED, "unselected differs from unprobed");
        InstallationReadiness unprobed = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                true, false, false, InstallationReadiness.AccessStatus.NOT_CHECKED);
        check(AndroidDiagnosticLogging.readinessFailure(unprobed) == DiagnosticFailure.NOT_CHECKED, "Root metadata check does not imply permission failure");
        check(AndroidDiagnosticLogging.readinessFailure(unprobed.withAccessCode("FIRST_LAUNCH_NEEDED")) == DiagnosticFailure.FIRST_LAUNCH_NEEDED, "missing save directory explicit");
        check(AndroidDiagnosticLogging.readinessFailure(unprobed.withAccessCode("ROOT_UNRECOGNIZED_LAYOUT")) == DiagnosticFailure.UNRECOGNIZED_LAYOUT, "unrecognized layout explicit");
        check(AndroidDiagnosticLogging.readinessFailure(unprobed.withSystemRequest(true)) == DiagnosticFailure.SYSTEM_REQUEST_PENDING, "system request explicit");
        check(AndroidDiagnosticLogging.readinessFailure(unprobed.withAccessCode("ROOT_MUTATION_UNAVAILABLE")) == DiagnosticFailure.CAPABILITY_UNAVAILABLE, "unsupported capability explicit");
        check(AndroidDiagnosticLogging.readinessFailure(unprobed.withAccessCode("ROOT_TIMEOUT"), "ROOT_TIMEOUT") == DiagnosticFailure.TIMEOUT, "preserve exact whitelisted failure before coarse readiness");
        check(AndroidDiagnosticLogging.classify(new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.SAVE_READINESS, "ROOT_MISSING_DIRECTORY", "PRIVATE_SENTINEL", null)) == DiagnosticFailure.FIRST_LAUNCH_NEEDED, "Root missing directory mapped precisely");
        check(AndroidDiagnosticLogging.inspectionResult("BRANCHED", false) == DiagnosticResult.CONFLICT, "fresh automatic refresh conflict");
        check(AndroidDiagnosticLogging.inspectionResult("BLOCKED", false) == DiagnosticResult.REJECTED, "fresh automatic refresh rejection");
        check(AndroidDiagnosticLogging.inspectionResult("IDENTICAL", true) == DiagnosticResult.FAILED, "caught refresh failure cannot succeed");
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
            check(records.contains("\"readinessCode\":\"ROOT_BUSY\""), "reviewed bridge code retained as closed evidence");
            for (String line : records.split("\\n")) {
                if (line.contains("\"stage\":\"HTTP\"") || line.contains("\"stage\":\"FINISHED\""))
                    check(line.contains(manual.getId()), "same operation correlation restored after nesting");
                if (line.contains("\"stage\":\"VERIFY\"")) check(line.contains(automatic.getId()), "automatic trace bound inside its scope");
            }
            diagnosticSequenceChecks(temporary.resolve("scenarios"));
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(temporary)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (Exception ignored) {} });
            }
        }
        System.out.println("Android diagnostic adapter: " + checks + " host checks passed; no Android service or network executed.");
    }
    private static String exportedEvents() throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(DiagnosticLog.snapshotExport()))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null)
                if ("events.jsonl".equals(entry.getName())) return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        throw new AssertionError("events missing");
    }

    /** Replay the user's readiness/work/terminal sequence through the real persistent recorder. */
    private static void diagnosticSequenceChecks(Path directory) throws Exception {
        DiagnosticLog.configure(directory, "0.5.15-root-test+37", "android");
        InstallationReadiness base = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                true, false, false, InstallationReadiness.AccessStatus.NOT_CHECKED);
        String[] codes = {"NOT_CHECKED", "FIRST_LAUNCH_NEEDED"};
        DiagnosticFailure[] failures = {DiagnosticFailure.NOT_CHECKED, DiagnosticFailure.FIRST_LAUNCH_NEEDED};
        for (int index = 0; index < codes.length; index++) {
            InstallationReadiness state = base.withAccessCode(codes[index]);
            DiagnosticTrace trace = DiagnosticLog.begin(DiagnosticAction.ACCESS_CHECK, false);
            DiagnosticFailure failure = AndroidDiagnosticLogging.readinessFailure(state, codes[index]);
            pdsync.DiagnosticDetails details = AndroidDiagnosticLogging.readinessDetails(state, codes[index]).build();
            for (DiagnosticStage stage : new DiagnosticStage[]{DiagnosticStage.READINESS, DiagnosticStage.WORK, DiagnosticStage.FINISHED})
                trace.record(stage, DiagnosticResult.REJECTED, failure, details);
            String events = exportedEvents();
            int seen = 0;
            for (String line : events.split("\n")) {
                if (!line.contains(trace.getId()) || line.contains("\"stage\":\"QUEUED\"")) continue;
                seen++;
                check(line.contains("\"failure\":\"" + failures[index].name() + "\""), "same precise failure at readiness/work/terminal");
                check(line.contains("\"readinessCode\":\"" + codes[index] + "\""), "readiness code survives export");
                check(line.contains("\"mode\":\"ROOT_DIRECT\""), "mode survives export");
                check(line.contains("\"identityVerified\":\"true\""), "metadata identity distinct from Root access");
            }
            check(seen == 3, "all actual readiness lifecycle events exported");
        }
        android.os.Bundle reply = new android.os.Bundle();
        reply.put("saveLayoutDiagnosticVersion", 1); reply.put("saveContentState", "UNRECOGNIZED_LAYOUT");
        reply.put("includedRootEntries", 2); reply.put("excludedRootEntries", 3); reply.put("unrecognizedRootEntries", 1);
        reply.put("rootEntryNames", new String[]{"PRIVATE_SENTINEL"}); reply.put("rootEntryTypes", new String[]{"REGULAR_FILE"});
        reply.put("rootEntryClassifications", new String[]{"UNRECOGNIZED"}); reply.put("rootEntriesTruncated", true);
        BridgeSaveLayout layout = BridgeSaveLayout.fromReply(reply);
        String[] rootCodes = {"ROOT_BUSY", "ROOT_DENIED_OR_EXITED", "ROOT_UNRECOGNIZED_LAYOUT", "ROOT_IO_ERROR"};
        DiagnosticFailure[] expected = {DiagnosticFailure.BUSY, DiagnosticFailure.AUTHORIZATION, DiagnosticFailure.UNRECOGNIZED_LAYOUT, DiagnosticFailure.STORAGE};
        for (int index = 0; index < rootCodes.length; index++) {
            Throwable cause = BridgeDiagnostics.rootDetail(index == 1 ? "KERNEL_LSTAT_SAVE_FILES" : "LAYOUT_SCAN",
                    index == 1 ? "PERMISSION" : index == 3 ? "IO" : "NONE", index == 1 ? 13 : index == 3 ? 5 : 0);
            BridgeDiagnostics.Failure error = new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.SAVE_READINESS,
                    rootCodes[index], "PRIVATE_SENTINEL /secret/path", cause, null, index == 2 ? layout : null);
            DiagnosticTrace trace = DiagnosticLog.begin(DiagnosticAction.ACCESS_CHECK, false);
            AndroidDiagnosticLogging.failure(trace, DiagnosticStage.READINESS, error);
            AndroidDiagnosticLogging.failure(trace, DiagnosticStage.WORK, error);
            AndroidDiagnosticLogging.failure(trace, DiagnosticStage.FINISHED, error);
            int seen = 0;
            for (String line : exportedEvents().split("\n")) {
                if (!line.contains(trace.getId()) || line.contains("\"stage\":\"QUEUED\"")) continue;
                seen++;
                check(line.contains("\"failure\":\"" + expected[index].name() + "\""), "controlled Root outcome exported");
                check(line.contains("\"readinessCode\":\"" + rootCodes[index] + "\""), "controlled Root code exported");
                check(line.contains("\"failureSource\":\"ROOT\""), "closed source exported");
                if (index == 1) {
                    check(line.contains("\"rootStage\":\"KERNEL_LSTAT_SAVE_FILES\""), "precise permission origin exported");
                    check(line.contains("\"errno\":\"13\""), "bounded errno exported");
                }
                if (index == 2) {
                    check(line.contains("\"saveLayoutIncluded\":\"2\""), "actual scan include count exported");
                    check(line.contains("\"saveLayoutExcluded\":\"3\""), "actual scan exclude count exported");
                    check(line.contains("\"saveLayoutUnrecognized\":\"1\""), "actual scan unknown count exported");
                }
            }
            check(seen == 3, "failure/work/terminal all exported");
        }
        check(!exportedEvents().contains("PRIVATE_SENTINEL"), "messages, paths and entry names omitted from detail export");
    }

}
