package io.github.xyz1001.pdsync.android;

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
        ERROR
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
            throw new IllegalArgumentException("Readiness states are required");
        }
        this.embeddedBridge = embedded;
        this.rootDirect = root;
        if (embedded && root) throw new IllegalArgumentException("One access mode is required");
        if (root && packageStatus != PackageStatus.NOT_SELECTED && packageStatus != PackageStatus.NOT_CHECKED
                && packageStatus != PackageStatus.VERIFIED) throw new IllegalArgumentException("Root requires an existing installed game");
        if ((embedded || root) != (packageStatus2 == PackageStatus.NOT_APPLICABLE)) throw new IllegalArgumentException("Bridge mode must match package states");
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
            return "尚未选择游戏。";
        }
        if (this.systemRequestActive) {
            return "系统安装或卸载请求尚未结束，请完成或取消当前系统确认。";
        }
        if (this.rootDirect) {
            return this.identityVerified ? "Root 模式：已核对所选已安装游戏的身份；尚不代表 Root 权限或存档可访问。"
                    : "Root 模式：所选已安装游戏的身份尚未核对。不会准备、签名、安装或卸载游戏。";
        }
        if (this.identityVerified) {
            return this.embeddedBridge ? "游戏内置存档桥已验证：本机签名、完整 APK、来源绑定与独立 UID 已核对。" : "旧版游戏与助手已核对，仅用于保留的恢复流程。";
        }
        if (this.game == PackageStatus.NOT_CHECKED || this.helper == PackageStatus.NOT_CHECKED) {
            return "安装状态尚未完成核验，请重新检查状态并查看诊断。";
        }
        if (this.embeddedBridge) return this.game == PackageStatus.VERIFIED
                ? "游戏签名与完整 APK 已核对；内置存档桥身份尚未通过验证，请查看诊断。"
                : "仅需安装准备好的这一组游戏 APK，内置存档桥会随游戏一并安装。";
        return (this.game == PackageStatus.VERIFIED && this.helper == PackageStatus.VERIFIED) ? "游戏与助手的签名和 APK 内容已核对；包身份或共享 UID 尚未通过验证，请查看诊断。" : "游戏与助手尚未全部完成安装，请按下方各自的当前状态继续。";
    }

    public String accessDescription() {
        if (this.systemRequestActive) {
            return "系统请求结束后会重新核验安装和存档访问。";
        }
        if (this.rootDirect && this.restorePending) {
            return "检测到未完成恢复。不能切换游戏或访问方式，请先核对恢复记录。";
        }
        if (this.rootDirect && this.access == AccessStatus.NOT_CHECKED) {
            return "Root 存档访问尚未检查。请保持应用在前台，明确授权后再检查；返回应用不会自动请求 Root。";
        }
        if (this.rootDirect && this.access == AccessStatus.READY) {
            return "本次 Root 存档访问检查通过。后续操作仍会重新核验游戏身份与存档状态。";
        }
        if (this.rootDirect) {
            switch (this.access) {
                case FIRST_LAUNCH_NEEDED: return "所选游戏尚无可读取的存档目录；请先正常游玩并退出，再手动检查。";
                case GAME_BUSY: return "游戏仍运行、存档变化或进程状态不明确。正常退出后再手动检查。";
                case UNRECOGNIZED_LAYOUT: return "Root 已连接，但尚不能识别存档布局；未把它当成空存档。";
                case SECURITY_ERROR: return "Root 权限或所选游戏身份尚未通过验证，请查看诊断并重新授权检查。";
                case IPC_ERROR: return "尚未完成 Root 请求；可能未安装、被拒绝、超时或回复无效。请查看诊断。";
                case RECOVERY_NEEDED: return "有未完成恢复，请先核对恢复记录，暂时不要启动游戏。";
                default: return "Root 存档访问检查未完成，请查看诊断并明确重新检查。";
            }
        }
        if (!this.identityVerified && this.access == AccessStatus.NOT_CHECKED) {
            return "通过完整安装与身份核验后，才能检查存档访问。";
        }
        if (this.restorePending && this.access == AccessStatus.FIRST_LAUNCH_NEEDED) {
            return "检测到未完成恢复。请先在设置中检查恢复记录，再启动游戏或检查存档。";
        }
        switch (this.access.ordinal()) {
            case 1:
                return "存档访问已就绪，可以继续检查本机存档。";
            case 2:
                return "游戏尚未生成存档目录。请启动一次游戏，正常退出后返回这里重新检查存档。";
            case 3:
                return "游戏仍在前台、存档仍在写入或运行状态暂不明确。请正常退出游戏，稍后重新检查。";
            case 4:
                return "另一项存档操作尚未结束。请等待完成后重新检查。";
            case 5:
                return "存档目录已存在，但布局尚不能识别。请查看诊断；未把它当作空存档。";
            case 6:
                return "存档桥有未完成的存档恢复。请先在设置中检查恢复记录，暂时不要启动游戏。";
            case 7:
                return "存档桥的身份或访问权限检查未通过，请查看诊断后重新检查。";
            case 8:
                return "尚未完成与存档桥的通信，请重新检查；若仍失败，请查看诊断。";
            case 9:
                return "存档访问检查失败，请查看诊断并重新检查。";
            default:
                return "安装已核对，存档访问尚未完成检查。";
        }
    }

    public String statusDescription() {
        return this.identityVerified ? accessDescription() : installationDescription();
    }

    public static String packageDescription(PackageStatus packageStatus) {
        int iOrdinal = packageStatus.ordinal();
        if (iOrdinal == 2) {
            return "尚未安装";
        }
        if (iOrdinal == 3) {
            return "需要兼容更新，无需卸载";
        }
        if (iOrdinal == 4) {
            return "当前应用不兼容，替换前需要单独确认卸载";
        }
        if (iOrdinal == 5) {
            return "已安装，签名及完整 APK 内容已核对";
        }
        if (packageStatus == PackageStatus.NOT_APPLICABLE) return "无需独立安装";
        return "尚未完成核验";
    }

    public static AccessStatus classify(String str) {
        if (str == null || str.isEmpty() || "NOT_CHECKED".equals(str)) {
            return AccessStatus.NOT_CHECKED;
        }
        str.hashCode();
        switch (str) {
            case "ROOT_UNAVAILABLE":
            case "ROOT_DENIED_OR_EXITED":
            case "ROOT_TIMEOUT":
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
            case "RESTORE_COMMITTED_CLEANUP_PENDING":
            case "RECOVERY_REQUIRED":
            case "RESTORE_ROLLED_BACK":
            case "RESTORE_VERIFY_FAILED":
                return AccessStatus.RECOVERY_NEEDED;
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
