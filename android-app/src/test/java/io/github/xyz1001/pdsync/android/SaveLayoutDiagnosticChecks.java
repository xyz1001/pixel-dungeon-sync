package io.github.xyz1001.pdsync.android;

import android.os.Bundle;
import java.util.Arrays;
import java.util.List;

/** Host-only tests: malformed provider metadata is never copied into a shareable report. */
public final class SaveLayoutDiagnosticChecks {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static Bundle reply(String... names) {
        Bundle reply = new Bundle();
        reply.put("ok", true);
        reply.put("ready", true);
        reply.put("transferReady", false);
        reply.put("firstLaunchNeeded", false);
        reply.put("saveLayoutDiagnosticVersion", 1);
        reply.put("saveContentState", "UNRECOGNIZED_LAYOUT");
        reply.put("includedRootEntries", 0);
        reply.put("excludedRootEntries", 0);
        reply.put("unrecognizedRootEntries", names.length);
        reply.put("rootEntryNames", names);
        String[] types = new String[names.length], classes = new String[names.length];
        Arrays.fill(types, "REGULAR_FILE"); Arrays.fill(classes, "UNRECOGNIZED");
        reply.put("rootEntryTypes", types);
        reply.put("rootEntryClassifications", classes);
        reply.put("rootEntriesTruncated", false);
        return reply;
    }
    private static String report(BridgeSaveLayout layout) {
        BridgeDiagnostics.Failure failure = new BridgeDiagnostics.Failure(
                BridgeDiagnostics.Stage.SAVE_READINESS, "UNRECOGNIZED_SAVE_LAYOUT", "private-message", null, null, layout);
        return BridgeDiagnostics.report(failure, 26, "test", 1L, "test.game", "test.game", "test.authority");
    }
    private static void invalid(Bundle reply, String message) {
        BridgeSaveLayout layout = BridgeSaveLayout.fromReply(reply);
        check(layout != null && !layout.valid && layout.entries.isEmpty() && layout.truncated, message);
    }
    public static void main(String[] args) {
        Bundle valid = reply("game1", "profileInstalled", "unknown-link");
        valid.put("includedRootEntries", 1); valid.put("excludedRootEntries", 1); valid.put("unrecognizedRootEntries", 1);
        valid.put("rootEntryTypes", new String[]{"DIRECTORY", "REGULAR_FILE", "SYMLINK"});
        valid.put("rootEntryClassifications", new String[]{"INCLUDED", "EXCLUDED", "UNRECOGNIZED"});
        BridgeSaveLayout layout = BridgeSaveLayout.fromReply(valid);
        String report = report(layout);
        check(layout.valid && !layout.truncated && layout.entries.size() == 3, "complete bounded inventory retained");
        check(report.contains("stage=SAVE_READINESS") && report.contains("code=UNRECOGNIZED_SAVE_LAYOUT"), "readiness failure preserved");
        check("UNRECOGNIZED_SAVE_LAYOUT".equals(BridgeReadiness.failureCode(true, false, false)), "ok inspect does not bypass transfer gate");
        for (String expected : List.of("game1; type=DIRECTORY; classification=INCLUDED", "profileInstalled; type=REGULAR_FILE; classification=EXCLUDED",
                "unknown-link; type=SYMLINK; classification=UNRECOGNIZED", "included_root_entries=1", "excluded_root_entries=1", "unrecognized_root_entries=1"))
            check(report.contains(expected), "retains " + expected);
        check(!report.contains("private-message"), "raw failure message not included");
        ((String[]) valid.get("rootEntryNames"))[0] = "/private/after-parse";
        check(report(layout).contains("game1; type=DIRECTORY") && !report(layout).contains("after-parse"), "does not retain mutable provider arrays");
        check(BridgeSaveLayout.fromReply(new Bundle()) == null && BridgeSaveLayout.fromReply(null) == null, "old bridges compatible");

        for (String malicious : new String[]{"/data/private/secret-file", "../secret-file", "C:\\secret-file", "foo/bar", "foo\\bar", "..", ".", "",
                "name\nstage=OK", "name\rsecret", "name\u0000secret", "name\u202esecret", "password=secret", "https://secret", "secret@example.com", "a".repeat(97), null}) {
            BridgeSaveLayout filtered = BridgeSaveLayout.fromReply(reply(malicious, "known.dat"));
            String safeReport = report(filtered);
            check(filtered.valid && filtered.truncated && filtered.entries.size() == 1, "malformed name omitted");
            check(safeReport.contains("known.dat; type=REGULAR_FILE"), "safe adjacent name retained");
            check(!safeReport.contains("secret") && !safeReport.contains("stage=OK"), "no path, controls or credential-shaped data");
        }
        Bundle unexpectedType = reply("safe"); unexpectedType.put("rootEntryTypes", new String[]{"/private/secret"});
        check(BridgeSaveLayout.fromReply(unexpectedType).entries.isEmpty(), "untrusted file type omitted");
        Bundle unexpectedClass = reply("safe"); unexpectedClass.put("rootEntryClassifications", new String[]{"EXCLUDED\nsecret"});
        check(BridgeSaveLayout.fromReply(unexpectedClass).entries.isEmpty(), "untrusted classification omitted");
        Bundle badState = reply("safe"); badState.put("saveContentState", "/private/secret"); invalid(badState, "untrusted state invalid");
        for (Object count : new Object[]{-1, Integer.MAX_VALUE, 16385, 1L, "1", null}) {
            Bundle bad = reply("safe"); bad.put("unrecognizedRootEntries", count); invalid(bad, "hostile counts invalid");
        }
        Bundle sum = reply("safe"); sum.put("includedRootEntries", 16384); sum.put("excludedRootEntries", 16384); invalid(sum, "sum cannot overflow or exceed entry scan bound");
        for (String field : new String[]{"rootEntryNames", "rootEntryTypes", "rootEntryClassifications", "rootEntriesTruncated", "saveLayoutDiagnosticVersion"}) {
            Bundle bad = reply("safe"); bad.put(field, "private-secret"); invalid(bad, "wrong type: " + field);
        }
        Bundle mismatch = reply("safe"); mismatch.put("rootEntryTypes", new String[0]); invalid(mismatch, "parallel array mismatch invalid");
        Bundle extraNames = reply("safe"); extraNames.put("unrecognizedRootEntries", 0); invalid(extraNames, "more rows than counts invalid");
        Bundle duplicate = reply("same", "same");
        check(BridgeSaveLayout.fromReply(duplicate).entries.size() == 1 && BridgeSaveLayout.fromReply(duplicate).truncated, "duplicate names omitted");
        Bundle sparse = reply("safe"); sparse.put("unrecognizedRootEntries", 10);
        check(BridgeSaveLayout.fromReply(sparse).truncated, "omitted provider rows marked even if flag lies");

        String[] many = new String[4096];
        for (int i = 0; i < many.length; i++) many[i] = "entry" + i;
        BridgeSaveLayout bounded = BridgeSaveLayout.fromReply(reply(many));
        check(bounded.entries.size() == BridgeSaveLayout.MAX_ENTRIES && bounded.truncated, "entry bound enforced independently of provider");
        check(bounded.unrecognized == 4096, "full count retained on truncation");
        for (int i = 0; i < 64; i++) many[i] = "a".repeat(90) + i;
        BridgeSaveLayout bytes = BridgeSaveLayout.fromReply(reply(many));
        int payload = 0;
        for (BridgeSaveLayout.Entry entry : bytes.entries) payload += entry.name.length() + "REGULAR_FILE".length() + "UNRECOGNIZED".length();
        check(payload <= BridgeSaveLayout.MAX_BYTES && bytes.entries.size() < 64 && bytes.truncated, "total ASCII payload byte bound");
        check(report(bytes).length() < 12000, "whole diagnostic has bounded row formatting overhead");
        System.out.println("PASS: " + assertions + " save-layout diagnostic assertions; no Android runtime executed.");
    }
}
