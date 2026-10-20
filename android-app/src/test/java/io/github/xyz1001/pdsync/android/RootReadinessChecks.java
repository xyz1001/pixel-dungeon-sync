package io.github.xyz1001.pdsync.android;

/** Pure Java readiness policy. Does not request Root or execute Android UI. */
public final class RootReadinessChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (InstallationReadiness.PackageStatus game : new InstallationReadiness.PackageStatus[]{
                InstallationReadiness.PackageStatus.NOT_SELECTED, InstallationReadiness.PackageStatus.NOT_CHECKED,
                InstallationReadiness.PackageStatus.VERIFIED}) {
            for (InstallationReadiness.AccessStatus access : InstallationReadiness.AccessStatus.values()) {
                for (int flags = 0; flags < 8; flags++) {
                    boolean identity = (flags & 1) != 0, system = (flags & 2) != 0, pending = (flags & 4) != 0;
                    InstallationReadiness state = InstallationReadiness.rootDirect(game, identity, system, pending, access);
                    boolean verified = identity && game == InstallationReadiness.PackageStatus.VERIFIED;
                    check(state.rootDirect && !state.embeddedBridge, "Root does not impersonate an embedded bridge");
                    check(state.helper == InstallationReadiness.PackageStatus.NOT_APPLICABLE, "Root has no helper install state");
                    check(state.identityVerified == verified, "Package identity requires actual verification");
                    check(state.canContinue() == (verified && !system && !pending && access == InstallationReadiness.AccessStatus.READY),
                            "Support or installed identity never implies access readiness");
                    check(state.canRequestRootAccess() == (verified && !system && !pending && access != InstallationReadiness.AccessStatus.RECOVERY_NEEDED),
                            "Deliberate action eligibility does not claim READY");
                    check(!state.canContinueInstallation(), "Root never starts installation");
                    check(!state.requiresGameUninstall() && !state.requiresHelperUninstall(), "Root never asks to uninstall");
                    check(!state.canInitializeGame(), "Root does not enter embedded first-launch activation");
                    check(state.withSystemRequest(true).rootDirect && !state.withSystemRequest(true).canContinue(),
                            "Active system request blocks Root continuation and preserves mode");
                    check(state.withAccessCode("ROOT_AUTH_REQUIRED").rootDirect
                                    && !state.withAccessCode("ROOT_AUTH_REQUIRED").canContinue(),
                            "Missing authorization invalidates readiness");
                    check(state.statusDescription() != null && !state.statusDescription().isEmpty(), "Every Root state is explained");
                }
            }
        }
        for (InstallationReadiness.PackageStatus invalid : new InstallationReadiness.PackageStatus[]{
                InstallationReadiness.PackageStatus.FRESH_INSTALL, InstallationReadiness.PackageStatus.COMPATIBLE_UPDATE,
                InstallationReadiness.PackageStatus.REQUIRES_UNINSTALL, InstallationReadiness.PackageStatus.NOT_APPLICABLE}) {
            try {
                InstallationReadiness.rootDirect(invalid, true, false, false, InstallationReadiness.AccessStatus.READY);
                throw new AssertionError("Root accepted installation transition " + invalid);
            } catch (IllegalArgumentException expected) { checks++; }
        }
        InstallationReadiness metadata = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                true, false, false, InstallationReadiness.AccessStatus.NOT_CHECKED);
        check(!metadata.canContinue(), "PM-only verification is not Root authorization");
        check(metadata.canRequestRootAccess(), "Stored selection may request access without a separate restart authorization button");
        check(metadata.installationDescription().contains("尚不代表 Root 权限"), "Identity copy does not claim Root readiness");
        check(metadata.accessDescription().equals(pdsync.i18n.AndroidMessages.INSTALLATION_READINESS_18.text()), "Unverified Root status uses its localized metadata-only copy");
        check(InstallationReadiness.classify("ROOT_AUTH_REQUIRED") == InstallationReadiness.AccessStatus.SECURITY_ERROR, "Authorization failure classified");
        check(InstallationReadiness.classify("ROOT_DENIED_OR_EXITED") == InstallationReadiness.AccessStatus.IPC_ERROR, "Denial classified without fake success");
        check(InstallationReadiness.classify("ROOT_RUNNER_TIMEOUT") == InstallationReadiness.AccessStatus.IPC_ERROR, "Watchdog timeout never implies completion");
        check(InstallationReadiness.classify("ROOT_UNRELIABLE") == InstallationReadiness.AccessStatus.GAME_BUSY, "Unknown process evidence blocks");
        check(InstallationReadiness.classify("ROOT_MISSING_DIRECTORY") == InstallationReadiness.AccessStatus.FIRST_LAUNCH_NEEDED, "Missing save root is not empty ready saves");
        check(InstallationReadiness.classify("ROOT_UNRECOGNIZED_LAYOUT") == InstallationReadiness.AccessStatus.UNRECOGNIZED_LAYOUT, "Unknown layout stays blocked");
        for (String code : new String[]{"ROOT_ATTRIBUTES_UNSUPPORTED", "ROOT_MUTATION_UNAVAILABLE"}) {
            InstallationReadiness unavailable = metadata.withAccessCode(code);
            check(unavailable.access == InstallationReadiness.AccessStatus.CAPABILITY_UNAVAILABLE, "Native/file support failure is explicit");
            check(!unavailable.canContinue(), "Native/file support failure is never readiness");
            check(unavailable.accessDescription().contains("没有把它当成授权或恢复成功"), "Failure copy cannot imply grant or applied result");
        }
        for (String code : new String[]{"ROOT_RECOVERY_REQUIRED", "ROOT_RESTORE_CONFLICT", "ROOT_RECOVERY_CONFLICT",
                "ROOT_RESTORE_COMMITTED_CLEANUP_PENDING", "ROOT_RECOVERY_COMMITTED_CLEANUP_PENDING",
                "ROOT_RESTORE_VERIFY_FAILED", "ROOT_RECOVERY_RECEIPT_INVALID"}) {
            check(metadata.withAccessCode(code).access == InstallationReadiness.AccessStatus.RECOVERY_NEEDED,
                    "Pending/partial result routes to recovery rather than another restore");
        }
        check(metadata.withAccessCode("ROOT_NO_PENDING_RECOVERY").access == InstallationReadiness.AccessStatus.NOT_CHECKED,
                "No pending journal is not a readiness probe");
        System.out.println("Root readiness policy checks passed: " + checks + " assertions; no Root, Android UI, or device execution.");
    }
}
