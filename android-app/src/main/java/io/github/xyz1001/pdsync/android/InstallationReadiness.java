package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

/* loaded from: classes.dex */
public final class InstallationReadiness {
    public final AccessStatus access;
    public final PackageStatus game;
    public final PackageStatus helper;
    public final boolean identityVerified;
    public final boolean embeddedBridge;
    public final boolean rootDirect;
    public final boolean restorePending;
    public final boolean systemRequestActive;

    public enum AccessStatus {
        NOT_CHECKED,
        READY,
        FIRST_LAUNCH_NEEDED,
        GAME_BUSY,
        SYNC_BUSY,
        UNRECOGNIZED_LAYOUT,
        RECOVERY_NEEDED,
        SECURITY_ERROR,
        IPC_ERROR,
        ERROR,
        CAPABILITY_UNAVAILABLE
    }

    public enum PackageStatus {
        NOT_SELECTED,
        NOT_CHECKED,
        FRESH_INSTALL,
        COMPATIBLE_UPDATE,
        REQUIRES_UNINSTALL,
        VERIFIED,
        NOT_APPLICABLE
    }

    public InstallationReadiness(PackageStatus packageStatus, PackageStatus packageStatus2, boolean z, boolean z2, boolean z3, AccessStatus accessStatus) {
        this(false, false, packageStatus, packageStatus2, z, z2, z3, accessStatus);
    }

    private InstallationReadiness(boolean embedded, boolean root, PackageStatus packageStatus, PackageStatus packageStatus2, boolean z, boolean z2, boolean z3, AccessStatus accessStatus) {
        if (packageStatus == null || packageStatus == PackageStatus.NOT_APPLICABLE || packageStatus2 == null || accessStatus == null) {
            throw new IllegalArgumentException(AndroidMessages.INSTALLATION_READINESS_01.text());
        }
        this.embeddedBridge = embedded;
        this.rootDirect = root;
        if (embedded && root) throw new IllegalArgumentException(AndroidMessages.INSTALLATION_READINESS_02.text());
        if (root && packageStatus != PackageStatus.NOT_SELECTED && packageStatus != PackageStatus.NOT_CHECKED
                && packageStatus != PackageStatus.VERIFIED) throw new IllegalArgumentException(AndroidMessages.INSTALLATION_READINESS_03.text());
        if ((embedded || root) != (packageStatus2 == PackageStatus.NOT_APPLICABLE)) throw new IllegalArgumentException(AndroidMessages.INSTALLATION_READINESS_04.text());
        this.game = packageStatus;
        this.helper = packageStatus2;
        boolean z4 = z && packageStatus == PackageStatus.VERIFIED && (embedded || root || packageStatus2 == PackageStatus.VERIFIED);
        this.identityVerified = z4;
        this.systemRequestActive = z2;
        this.restorePending = z3;
        if (accessStatus == AccessStatus.READY && !z4) {
            accessStatus = AccessStatus.NOT_CHECKED;
        }
        this.access = accessStatus;
    }

    public static InstallationReadiness embedded(PackageStatus game, boolean identityVerified, boolean systemRequestActive, boolean restorePending, AccessStatus access) {
        return new InstallationReadiness(true, false, game, PackageStatus.NOT_APPLICABLE, identityVerified, systemRequestActive, restorePending, access);
    }

    public static InstallationReadiness rootDirect(PackageStatus game, boolean identityVerified, boolean systemRequestActive, boolean restorePending, AccessStatus access) {
        return new InstallationReadiness(false, true, game, PackageStatus.NOT_APPLICABLE, identityVerified, systemRequestActive, restorePending, access);
    }

    public static InstallationReadiness noSelection() {
        return embedded(PackageStatus.NOT_SELECTED, false, false, false, AccessStatus.NOT_CHECKED);
    }

    public static InstallationReadiness checking(boolean z, boolean z2) {
        PackageStatus packageStatus = z ? PackageStatus.NOT_CHECKED : PackageStatus.NOT_SELECTED;
        return embedded(packageStatus, false, z2, false, AccessStatus.NOT_CHECKED);
    }

    public InstallationReadiness withSystemRequest(boolean z) {
        return new InstallationReadiness(this.embeddedBridge, this.rootDirect, this.game, this.helper, this.identityVerified, z, this.restorePending, this.access);
    }

    public InstallationReadiness withAccessCode(String str) {
        return new InstallationReadiness(this.embeddedBridge, this.rootDirect, this.game, this.helper, this.identityVerified, this.systemRequestActive, this.restorePending, classify(str));
    }

    public boolean installationVerified() {
        return this.identityVerified;
    }

    /** Allows a deliberate Root action to check access; never means access is ready. */
    public boolean canRequestRootAccess() {
        return this.rootDirect && this.identityVerified && !this.systemRequestActive && !this.restorePending
                && this.access != AccessStatus.RECOVERY_NEEDED;
    }

    public boolean canContinue() {
        return this.identityVerified && !this.systemRequestActive && (!this.rootDirect || !this.restorePending) && this.access == AccessStatus.READY;
    }

    public boolean canInitializeGame() {
        return !this.rootDirect && this.identityVerified && !this.systemRequestActive && !this.restorePending && this.access == AccessStatus.FIRST_LAUNCH_NEEDED;
    }

    public boolean canContinueInstallation() {
        if (!this.rootDirect && !this.systemRequestActive && checked(this.game) && (this.embeddedBridge || checked(this.helper))) {
            return needsInstall(this.game) || needsInstall(this.helper);
        }
        return false;
    }

    public boolean requiresGameUninstall() {
        return !this.rootDirect && this.game == PackageStatus.REQUIRES_UNINSTALL;
    }

    public boolean requiresHelperUninstall() {
        return !this.rootDirect && this.helper == PackageStatus.REQUIRES_UNINSTALL;
    }

    private static boolean needsInstall(PackageStatus packageStatus) {
        return packageStatus == PackageStatus.FRESH_INSTALL || packageStatus == PackageStatus.COMPATIBLE_UPDATE || packageStatus == PackageStatus.REQUIRES_UNINSTALL;
    }

    private static boolean checked(PackageStatus packageStatus) {
        return (packageStatus == PackageStatus.NOT_SELECTED || packageStatus == PackageStatus.NOT_CHECKED) ? false : true;
    }

    public String installationDescription() {
        if (this.game == PackageStatus.NOT_SELECTED) {
            return AndroidMessages.INSTALLATION_READINESS_05.text();
        }
        if (this.systemRequestActive) {
            return AndroidMessages.INSTALLATION_READINESS_06.text();
        }
        if (this.rootDirect) {
            return this.identityVerified ? AndroidMessages.INSTALLATION_READINESS_07.text()
                    : AndroidMessages.INSTALLATION_READINESS_08.text();
        }
        if (this.identityVerified) {
            return this.embeddedBridge ? AndroidMessages.INSTALLATION_READINESS_09.text() : AndroidMessages.INSTALLATION_READINESS_10.text();
        }
        if (this.game == PackageStatus.NOT_CHECKED || this.helper == PackageStatus.NOT_CHECKED) {
            return AndroidMessages.INSTALLATION_READINESS_11.text();
        }
        if (this.embeddedBridge) return this.game == PackageStatus.VERIFIED
                ? AndroidMessages.INSTALLATION_READINESS_12.text()
                : AndroidMessages.INSTALLATION_READINESS_13.text();
        return (this.game == PackageStatus.VERIFIED && this.helper == PackageStatus.VERIFIED) ? AndroidMessages.INSTALLATION_READINESS_14.text() : AndroidMessages.INSTALLATION_READINESS_15.text();
    }

    public String accessDescription() {
        if (this.systemRequestActive) {
            return AndroidMessages.INSTALLATION_READINESS_16.text();
        }
        if (this.rootDirect && this.restorePending) {
            return AndroidMessages.INSTALLATION_READINESS_17.text();
        }
        if (this.rootDirect && this.access == AccessStatus.NOT_CHECKED) {
            return AndroidMessages.INSTALLATION_READINESS_18.text();
        }
        if (this.rootDirect && this.access == AccessStatus.READY) {
            return AndroidMessages.INSTALLATION_READINESS_19.text();
        }
        if (this.rootDirect) {
            switch (this.access) {
                case FIRST_LAUNCH_NEEDED: return AndroidMessages.INSTALLATION_READINESS_20.text();
                case GAME_BUSY: return AndroidMessages.INSTALLATION_READINESS_21.text();
                case UNRECOGNIZED_LAYOUT: return AndroidMessages.INSTALLATION_READINESS_22.text();
                case SECURITY_ERROR: return AndroidMessages.INSTALLATION_READINESS_23.text();
                case IPC_ERROR: return AndroidMessages.INSTALLATION_READINESS_24.text();
                case RECOVERY_NEEDED: return AndroidMessages.INSTALLATION_READINESS_25.text();
                case CAPABILITY_UNAVAILABLE: return AndroidMessages.INSTALLATION_READINESS_26.text();
                default: return AndroidMessages.INSTALLATION_READINESS_27.text();
            }
        }
        if (!this.identityVerified && this.access == AccessStatus.NOT_CHECKED) {
            return AndroidMessages.INSTALLATION_READINESS_28.text();
        }
        if (this.restorePending && this.access == AccessStatus.FIRST_LAUNCH_NEEDED) {
            return AndroidMessages.INSTALLATION_READINESS_29.text();
        }
        switch (this.access.ordinal()) {
            case 1:
                return AndroidMessages.INSTALLATION_READINESS_30.text();
            case 2:
                return AndroidMessages.INSTALLATION_READINESS_31.text();
            case 3:
                return AndroidMessages.INSTALLATION_READINESS_32.text();
            case 4:
                return AndroidMessages.INSTALLATION_READINESS_33.text();
            case 5:
                return AndroidMessages.INSTALLATION_READINESS_34.text();
            case 6:
                return AndroidMessages.INSTALLATION_READINESS_35.text();
            case 7:
                return AndroidMessages.INSTALLATION_READINESS_36.text();
            case 8:
                return AndroidMessages.INSTALLATION_READINESS_37.text();
            case 9:
                return AndroidMessages.INSTALLATION_READINESS_38.text();
            default:
                return AndroidMessages.INSTALLATION_READINESS_39.text();
        }
    }

    public String statusDescription() {
        return this.identityVerified ? accessDescription() : installationDescription();
    }

    public static String packageDescription(PackageStatus packageStatus) {
        int iOrdinal = packageStatus.ordinal();
        if (iOrdinal == 2) {
            return AndroidMessages.INSTALLATION_READINESS_40.text();
        }
        if (iOrdinal == 3) {
            return AndroidMessages.INSTALLATION_READINESS_41.text();
        }
        if (iOrdinal == 4) {
            return AndroidMessages.INSTALLATION_READINESS_42.text();
        }
        if (iOrdinal == 5) {
            return AndroidMessages.INSTALLATION_READINESS_43.text();
        }
        if (packageStatus == PackageStatus.NOT_APPLICABLE) return AndroidMessages.INSTALLATION_READINESS_44.text();
        return AndroidMessages.INSTALLATION_READINESS_45.text();
    }

    public static AccessStatus classify(String str) {
        if (str == null || str.isEmpty() || "NOT_CHECKED".equals(str) || "ROOT_NO_PENDING_RECOVERY".equals(str)) {
            return AccessStatus.NOT_CHECKED;
        }
        str.hashCode();
        switch (str) {
            case "ROOT_UNAVAILABLE":
            case "ROOT_DENIED_OR_EXITED":
            case "ROOT_TIMEOUT":
            case "ROOT_RUNNER_TIMEOUT":
            case "ROOT_CANCELLED":
            case "ROOT_INVALID_REPLY":
            case "ROOT_PIPE_FAILED":
            case "NO_PROVIDER_REPLY":
            case "INVALID_PROTOCOL":
            case "IPC_CLOSE_FAILED":
            case "IPC_REPLY_CLOSE_FAILED":
            case "PROVIDER_ACQUIRE_FAILED":
            case "PROVIDER_ACQUIRE_NULL":
            case "ACTIVATION_LAUNCH_DENIED":
            case "ACTIVATION_COMPONENT_UNAVAILABLE":
            case "IPC_CALL_FAILED":
                return AccessStatus.IPC_ERROR;
            case "ROOT_RECOVERY_REQUIRED":
            case "ROOT_RESTORE_CONFLICT":
            case "ROOT_RECOVERY_CONFLICT":
            case "ROOT_RESTORE_FAILED":
            case "ROOT_RESTORE_VERIFY_FAILED":
            case "ROOT_RESTORE_RECEIPT_INVALID":
            case "ROOT_RECOVERY_FAILED":
            case "ROOT_RECOVERY_VERIFY_FAILED":
            case "ROOT_RECOVERY_RECEIPT_INVALID":
            case "ROOT_RESTORE_COMMITTED_CLEANUP_PENDING":
            case "ROOT_RECOVERY_COMMITTED_CLEANUP_PENDING":
            case "RESTORE_COMMITTED_CLEANUP_PENDING":
            case "RECOVERY_REQUIRED":
            case "RESTORE_ROLLED_BACK":
            case "RESTORE_VERIFY_FAILED":
                return AccessStatus.RECOVERY_NEEDED;
            case "ROOT_ATTRIBUTES_UNSUPPORTED":
            case "ROOT_MUTATION_UNAVAILABLE":
                return AccessStatus.CAPABILITY_UNAVAILABLE;
            case "ROOT_AUTH_REQUIRED":
            case "ROOT_TARGET_CHANGED":
            case "ROOT_BINDING_MISMATCH":
            case "ROOT_NOT_ROOT":
            case "ROOT_UNSAFE_PATH":
            case "IPC_SECURITY_REJECTED":
            case "UNEXPECTED_SHARED_UID":
            case "UID_MISMATCH":
            case "PACKAGE_TRUST_FAILED":
            case "GAME_BINDING_MISMATCH":
            case "REPLY_IDENTITY_MISMATCH":
            case "PROVIDER_ACQUIRE_SECURITY_REJECTED":
            case "GAME_SIGNER_MISMATCH":
            case "TARGET_IDENTITY_FAILED":
            case "INVALID_GAME_BINDING":
                return AccessStatus.SECURITY_ERROR;
            case "SYNC_BUSY":
            case "OPERATION_BUSY":
                return AccessStatus.SYNC_BUSY;
            case "ROOT_UNRELIABLE":
            case "ROOT_BUSY":
            case "ROOT_CHANGED":
            case "GAME_FOREGROUND":
            case "GAME_SAVE_RACE":
            case "SAVE_CHANGED":
            case "PROCESS_STATE_UNKNOWN":
            case "SAVE_NOT_QUIET":
            case "GAME_RUNNING":
                return AccessStatus.GAME_BUSY;
            case "READY":
                return AccessStatus.READY;
            case "ROOT_UNRECOGNIZED_LAYOUT":
            case "UNRECOGNIZED_SAVE_LAYOUT":
                return AccessStatus.UNRECOGNIZED_LAYOUT;
            case "ROOT_MISSING_DIRECTORY":
            case "FIRST_LAUNCH_NEEDED":
                return AccessStatus.FIRST_LAUNCH_NEEDED;
            default:
                return AccessStatus.ERROR;
        }
    }
}
