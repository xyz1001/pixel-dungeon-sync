package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

/* loaded from: classes.dex */
public final class AutoSyncMessages {
    private AutoSyncMessages() {
    }

    public static String blocked(String code) {
        return AndroidMessages.AUTO_BLOCKED_FORMAT.text(detail(code).text(), knownCode(code));
    }

    /** Exact legacy app-owned status migration; arbitrary error strings are never translated. */
    static String renderLegacy(String stored) {
        String[] codes = {"COMPARISON_FAILED", "HISTORY_CONFLICT", "LEGACY_PENDING_UPLOAD", "PENDING_UPLOAD",
                "RESTORE_RECOVERY_REQUIRED", "TARGET_CHANGED", "REMOTE_INCOMPLETE", "BRIDGE_UNAVAILABLE",
                "BASELINE_UNVERIFIED", "REMOTE_ONLY", "INTERRUPTED", "REMOTE_INTEGRITY_FAILED",
                "TARGET_UNVERIFIED", "UNSUPPORTED_SAVE_FORMAT", "FIRST_LAUNCH_NEEDED", "UPLOAD_NOT_CONFIRMED",
                "SAVE_NOT_STABLE", "LEGACY_REMOTE_PROTOCOL"};
        for (String code : codes) {
            pdsync.i18n.UiText text = detail(code);
            if (stored.equals(AndroidMessages.AUTO_SYNC_MESSAGES_19.chinese() + text.chinese() + "（" + code + "）")
                    || stored.equals(AndroidMessages.AUTO_SYNC_MESSAGES_19.english() + text.english() + "（" + code + "）"))
                return blocked(code);
        }
        return stored;
    }

    private static pdsync.i18n.UiText detail(String str) {
        pdsync.i18n.UiText str2;
        String strKnownCode = knownCode(str);
        strKnownCode.hashCode();
        switch (strKnownCode) {
            case "HISTORY_CONFLICT":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_01;
                break;
            case "LEGACY_PENDING_UPLOAD":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_02;
                break;
            case "PENDING_UPLOAD":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_03;
                break;
            case "RESTORE_RECOVERY_REQUIRED":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_04;
                break;
            case "TARGET_CHANGED":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_05;
                break;
            case "REMOTE_INCOMPLETE":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_06;
                break;
            case "BRIDGE_UNAVAILABLE":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_07;
                break;
            case "BASELINE_UNVERIFIED":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_08;
                break;
            case "REMOTE_ONLY":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_09;
                break;
            case "INTERRUPTED":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_10;
                break;
            case "REMOTE_INTEGRITY_FAILED":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_11;
                break;
            case "TARGET_UNVERIFIED":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_12;
                break;
            case "UNSUPPORTED_SAVE_FORMAT":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_13;
                break;
            case "FIRST_LAUNCH_NEEDED":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_14;
                break;
            case "UPLOAD_NOT_CONFIRMED":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_15;
                break;
            case "SAVE_NOT_STABLE":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_16;
                break;
            case "LEGACY_REMOTE_PROTOCOL":
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_17;
                break;
            default:
                str2 = AndroidMessages.AUTO_SYNC_MESSAGES_18;
                break;
        }
        return str2;
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
