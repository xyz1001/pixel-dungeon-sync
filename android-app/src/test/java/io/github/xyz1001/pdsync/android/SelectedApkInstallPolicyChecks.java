package io.github.xyz1001.pdsync.android;

import java.io.IOException;

/** Executes the production policy; PackageInstaller/system UI still needs device validation. */
public final class SelectedApkInstallPolicyChecks {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        String picked = "selected-file-receipt", another = "another-preparation";
        check(!SelectedApkInstallPolicy.pending(null), "missing historical intent is not a picked-file operation");
        check(!SelectedApkInstallPolicy.pending(""), "installed application selection keeps existing reuse");
        check(SelectedApkInstallPolicy.requiresInstallation(picked, picked), "selected file always needs installation");
        check(!SelectedApkInstallPolicy.requiresInstallation("", picked), "installed source may reuse exact bytes");
        for (String missingOrStale : new String[]{null, "", another}) {
            try {
                SelectedApkInstallPolicy.requiresInstallation(picked, missingOrStale);
                throw new AssertionError("Pending file cannot be satisfied by missing/different receipt");
            } catch (IOException expected) { checks++; }
        }
        for (String operation : new String[]{"install", "uninstall", ""}) {
            for (String state : new String[]{"READY", "INSTALLING", "PENDING_CONFIRMATION", "RESULT"}) {
                for (boolean returned : new boolean[]{false, true}) {
                    for (boolean sessionPresent : new boolean[]{false, true}) {
                        boolean expected = operation.equals("install") && !state.equals("READY")
                                && (state.equals("RESULT") || returned || !sessionPresent);
                        boolean boundary = SelectedApkInstallPolicy.mayCheckInstalledComponent(operation, picked, picked,
                                state, returned, sessionPresent);
                        check(boundary == expected, "only actual install-return boundary may inspect components");
                        check(SelectedApkInstallPolicy.acceptsInstalledComponent(boundary, true) == expected,
                                "valid component is sufficient after return regardless of callback status or selected version");
                        check(!SelectedApkInstallPolicy.acceptsInstalledComponent(boundary, false),
                                "missing/malformed/other component never authorizes Next");
                    }
                }
            }
        }
        for (String wrongRequest : new String[]{null, "", another}) {
            check(!SelectedApkInstallPolicy.mayCheckInstalledComponent("install", picked, wrongRequest, "RESULT", true, false),
                    "unrelated result cannot consume a newly selected file before its own system flow");
        }
        check(!SelectedApkInstallPolicy.mayCheckInstalledComponent("install", "", "", "RESULT", true, false),
                "a stray result without an actual preparation-bound system request is ignored");
        check(!SelectedApkInstallPolicy.mayCheckInstalledComponent("install", picked, picked, "INSTALLING", false, true),
                "old bridge cannot skip selected-file installation while confirmation is still active");
        check(SelectedApkInstallPolicy.mayCheckInstalledComponent("install", new String(picked), new String(picked),
                "INSTALLING", false, false), "cold restart recovers missing callback after session disappearance");
        check(SelectedApkInstallPolicy.mayCheckInstalledComponent("install", picked, picked, "PENDING_CONFIRMATION", true, true),
                "activity cancellation return permits the current package component per explicit user policy");
        check(SelectedApkInstallPolicy.recoverIntent("", true, picked, "", ""),
                "receipt publication before preference registration is recovered after process death");
        check(!SelectedApkInstallPolicy.recoverIntent("", true, picked, picked, ""),
                "successful receipt does not demand a second installation after restart");
        check(!SelectedApkInstallPolicy.recoverIntent("", true, picked, "", picked),
                "accepted installed-source choice deliberately replaces file intent");
        check(!SelectedApkInstallPolicy.recoverIntent(picked, true, another, "", ""),
                "different current receipt does not silently replace the pending one");
        check(!SelectedApkInstallPolicy.recoverIntent("", false, picked, "", ""),
                "historical or installed-source receipts gain no new forced install");
        check(SelectedApkInstallPolicy.mayReuseInstalledSelection("", null, "", ""),
                "no receipt needed for a discovered installed bridge");
        check(SelectedApkInstallPolicy.mayReuseInstalledSelection("", picked, "", picked),
                "accepted installed-source choice does not require retained receipt contents");
        check(SelectedApkInstallPolicy.mayReuseInstalledSelection("", picked, picked, ""),
                "verified completion does not require retained receipt contents");
        check(!SelectedApkInstallPolicy.mayReuseInstalledSelection("", another, picked, picked),
                "newly committed selected receipt cannot inherit prior selection override");
        check(!SelectedApkInstallPolicy.mayReuseInstalledSelection(picked, picked, picked, picked),
                "unfulfilled explicit intent takes priority over historical completion");
        System.out.println("Selected APK installation policy: " + checks + " checks passed.");
    }
}
