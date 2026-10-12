package io.github.xyz1001.pdsync.android;

/* loaded from: classes.dex */
public final class AutoSyncMessages {
    private AutoSyncMessages() {
    }

    public static String blocked(String str) {
        String str2;
        String strKnownCode = knownCode(str);
        strKnownCode.hashCode();
        switch (strKnownCode) {
            case "HISTORY_CONFLICT":
                str2 = "本机与云端历史需要明确选择，请在首页检查保留的分支。";
                break;
            case "LEGACY_PENDING_UPLOAD":
                str2 = "仍保留旧版待上传快照，请在首页明确归档后再同步。";
                break;
            case "PENDING_UPLOAD":
                str2 = "已有冻结的待上传操作与当前云端状态不一致，请在首页检查并明确重试或归档。";
                break;
            case "RESTORE_RECOVERY_REQUIRED":
                str2 = "上次恢复尚未确认完成，请在首页先检查恢复记录与保留备份。";
                break;
            case "TARGET_CHANGED":
                str2 = "所选游戏、安装版本或来源绑定已变化，请重新选择游戏并比较存档。";
                break;
            case "REMOTE_INCOMPLETE":
                str2 = "云端提交、快照或祖先记录缺失、不完整或暂时无法验证，请稍后重试。";
                break;
            case "BRIDGE_UNAVAILABLE":
                str2 = "存档存档桥未能完成本次访问，请返回首页检查存档桥诊断后重试。";
                break;
            case "BASELINE_UNVERIFIED":
                str2 = "同步基线与当前云端历史的关系无法确认，请返回首页重新比较。";
                break;
            case "REMOTE_ONLY":
                str2 = "云端已有存档，本机没有可上传的存档，请在首页检查是否恢复云端版本。";
                break;
            case "INTERRUPTED":
                str2 = "本次同步检查被中断，请返回首页检查后重试。";
                break;
            case "REMOTE_INTEGRITY_FAILED":
                str2 = "云端不可变历史校验失败，请在首页检查同步记录。";
                break;
            case "TARGET_UNVERIFIED":
                str2 = "无法确认所选游戏或存档桥的身份，请返回首页检查安装与身份诊断。";
                break;
            case "UNSUPPORTED_SAVE_FORMAT":
                str2 = "当前存档格式不受支持或无法读取，请检查所选游戏与存档位置。";
                break;
            case "FIRST_LAUNCH_NEEDED":
                str2 = "请先启动一次所选游戏并正常退出，生成存档目录后重试。";
                break;
            case "UPLOAD_NOT_CONFIRMED":
                str2 = "本次上传结果未确认，已冻结的快照会保留，请在首页检查后重试。";
                break;
            case "SAVE_NOT_STABLE":
                str2 = "游戏状态未确认或存档仍在变化，请正常退出游戏，等待存档稳定后重试。";
                break;
            case "LEGACY_REMOTE_PROTOCOL":
                str2 = "云端仍使用旧版同步协议，请检查档案设置后再同步。";
                break;
            default:
                str2 = "无法完成本机与完整云端历史的安全比较，请在首页检查连接、目录与同步记录。";
                break;
        }
        return "自动上传未完成：" + str2 + "（" + strKnownCode + "）";
    }

    static String knownCode(String str) {
        if (str == null) {
            return "COMPARISON_FAILED";
        }
        switch (str) {
            case "COMPARISON_FAILED":
            case "HISTORY_CONFLICT":
            case "LEGACY_PENDING_UPLOAD":
            case "PENDING_UPLOAD":
            case "RESTORE_RECOVERY_REQUIRED":
            case "TARGET_CHANGED":
            case "REMOTE_INCOMPLETE":
            case "BRIDGE_UNAVAILABLE":
            case "BASELINE_UNVERIFIED":
            case "REMOTE_ONLY":
            case "INTERRUPTED":
            case "REMOTE_INTEGRITY_FAILED":
            case "TARGET_UNVERIFIED":
            case "UNSUPPORTED_SAVE_FORMAT":
            case "FIRST_LAUNCH_NEEDED":
            case "UPLOAD_NOT_CONFIRMED":
            case "SAVE_NOT_STABLE":
            case "LEGACY_REMOTE_PROTOCOL":
                return str;
            default:
                return "COMPARISON_FAILED";
        }
    }

    static String bridgeReasonCode(String str, String str2) {
        if (str2 != null) {
            str2.hashCode();
            switch (str2) {
                case "RESTORE_COMMITTED_CLEANUP_PENDING":
                case "RECOVERY_REQUIRED":
                    return "RESTORE_RECOVERY_REQUIRED";
                case "INSTALLED_APK_SET_MISMATCH":
                case "LEGACY_RECOVERY_ENDED":
                case "PACKAGE_TRUST_CHANGED":
                case "REPLY_IDENTITY_MISMATCH":
                case "RECOVERY_IDENTITY_CHANGED":
                    return "TARGET_CHANGED";
                case "GAME_FOREGROUND":
                case "SAVE_CHANGED":
                case "PROCESS_STATE_UNKNOWN":
                case "SAVE_NOT_QUIET":
                case "GAME_RUNNING":
                    return "SAVE_NOT_STABLE";
                case "UNRECOGNIZED_SAVE_LAYOUT":
                    return "UNSUPPORTED_SAVE_FORMAT";
                case "FIRST_LAUNCH_NEEDED":
                    return "FIRST_LAUNCH_NEEDED";
            }
        }
        if ("TARGET_IDENTITY".equals(str) || "PACKAGE_TRUST".equals(str)) {
            return "TARGET_UNVERIFIED";
        }
        return "BRIDGE_UNAVAILABLE";
    }
}
