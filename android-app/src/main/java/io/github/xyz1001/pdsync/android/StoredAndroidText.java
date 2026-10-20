package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;
import pdsync.i18n.UiText;

/** Only app-owned status fields use this exact resource codec. Never applied to user data. */
final class StoredAndroidText {
    private static final String PREFIX = "pdsync.ui:";
    private static final UiText[] MESSAGES = {
        AndroidMessages.AUTO_STOPPED, AndroidMessages.AUTO_STOPPED_PENDING,
        AndroidMessages.AUTO_TIMED_OUT, AndroidMessages.AUTO_TIMED_OUT_PENDING,
        AndroidMessages.SYSTEM_CONFIRMATION_TOKEN_01,
        AndroidMessages.SYSTEM_CONFIRMATION_TOKEN_02,
        AndroidMessages.AUTO_SYNC_SERVICE_01,
        AndroidMessages.AUTO_SYNC_SERVICE_02,
        AndroidMessages.AUTO_SYNC_SERVICE_03,
        AndroidMessages.AUTO_SYNC_SERVICE_04,
        AndroidMessages.AUTO_SYNC_SERVICE_05,
        AndroidMessages.AUTO_SYNC_SERVICE_06,
        AndroidMessages.AUTO_SYNC_SERVICE_07,
        AndroidMessages.AUTO_SYNC_SERVICE_08,
        AndroidMessages.AUTO_SYNC_SERVICE_09,
        AndroidMessages.AUTO_SYNC_SERVICE_10,
        AndroidMessages.AUTO_SYNC_SERVICE_11,
        AndroidMessages.AUTO_SYNC_SERVICE_12,
        AndroidMessages.AUTO_SYNC_SERVICE_13,
        AndroidMessages.AUTO_SYNC_SERVICE_14,
        AndroidMessages.AUTO_SYNC_SERVICE_15,
        AndroidMessages.AUTO_SYNC_SERVICE_16,
        AndroidMessages.AUTO_SYNC_SERVICE_17,
        AndroidMessages.AUTO_SYNC_SERVICE_18,
        AndroidMessages.AUTO_SYNC_SERVICE_19,
        AndroidMessages.AUTO_SYNC_SERVICE_20,
        AndroidMessages.AUTO_SYNC_SERVICE_21,
        AndroidMessages.AUTO_SYNC_SERVICE_22,
        AndroidMessages.AUTO_SYNC_SERVICE_23,
        AndroidMessages.AUTO_SYNC_SERVICE_24,
        AndroidMessages.AUTO_SYNC_SERVICE_25,
        AndroidMessages.AUTO_SYNC_SERVICE_26,
        AndroidMessages.AUTO_SYNC_SERVICE_27,
        AndroidMessages.AUTO_SYNC_SERVICE_28,
        AndroidMessages.AUTO_SYNC_SERVICE_29,
        AndroidMessages.AUTO_SYNC_SERVICE_30,
        AndroidMessages.AUTO_SYNC_SERVICE_31,
        AndroidMessages.AUTO_SYNC_SERVICE_32,
        AndroidMessages.AUTO_SYNC_SERVICE_33,
        AndroidMessages.AUTO_SYNC_SERVICE_34,
        AndroidMessages.AUTO_SYNC_STATUS_POLICY_01,
        AndroidMessages.AUTO_SYNC_MESSAGES_01,
        AndroidMessages.AUTO_SYNC_MESSAGES_02,
        AndroidMessages.AUTO_SYNC_MESSAGES_03,
        AndroidMessages.AUTO_SYNC_MESSAGES_04,
        AndroidMessages.AUTO_SYNC_MESSAGES_05,
        AndroidMessages.AUTO_SYNC_MESSAGES_06,
        AndroidMessages.AUTO_SYNC_MESSAGES_07,
        AndroidMessages.AUTO_SYNC_MESSAGES_08,
        AndroidMessages.AUTO_SYNC_MESSAGES_09,
        AndroidMessages.AUTO_SYNC_MESSAGES_10,
        AndroidMessages.AUTO_SYNC_MESSAGES_11,
        AndroidMessages.AUTO_SYNC_MESSAGES_12,
        AndroidMessages.AUTO_SYNC_MESSAGES_13,
        AndroidMessages.AUTO_SYNC_MESSAGES_14,
        AndroidMessages.AUTO_SYNC_MESSAGES_15,
        AndroidMessages.AUTO_SYNC_MESSAGES_16,
        AndroidMessages.AUTO_SYNC_MESSAGES_17,
        AndroidMessages.AUTO_SYNC_MESSAGES_18,
        AndroidMessages.AUTO_SYNC_MESSAGES_19,
        AndroidMessages.INSTALL_RESULT_RECEIVER_01,
        AndroidMessages.INSTALL_RESULT_RECEIVER_02,
        AndroidMessages.INSTALL_RESULT_RECEIVER_03,
        AndroidMessages.INSTALL_RESULT_RECEIVER_04,
        AndroidMessages.INSTALL_RESULT_RECEIVER_05,
        AndroidMessages.INSTALL_COORDINATOR_01,
        AndroidMessages.INSTALL_COORDINATOR_02,
        AndroidMessages.INSTALL_COORDINATOR_03,
        AndroidMessages.INSTALL_COORDINATOR_04,
        AndroidMessages.INSTALL_COORDINATOR_05,
        AndroidMessages.INSTALL_COORDINATOR_06,
        AndroidMessages.INSTALL_COORDINATOR_07,
        AndroidMessages.INSTALL_COORDINATOR_08,
        AndroidMessages.INSTALL_COORDINATOR_09,
        AndroidMessages.INSTALL_COORDINATOR_10,
        AndroidMessages.INSTALL_COORDINATOR_11,
        AndroidMessages.INSTALL_COORDINATOR_12,
        AndroidMessages.INSTALL_COORDINATOR_13,
        AndroidMessages.INSTALL_COORDINATOR_14,
        AndroidMessages.INSTALL_COORDINATOR_15,
        AndroidMessages.INSTALL_COORDINATOR_16,
        AndroidMessages.INSTALL_COORDINATOR_17,
        AndroidMessages.INSTALL_COORDINATOR_18,
        AndroidMessages.INSTALL_COORDINATOR_19,
        AndroidMessages.INSTALL_COORDINATOR_20,
        AndroidMessages.INSTALL_COORDINATOR_21,
        AndroidMessages.INSTALL_COORDINATOR_22,
        AndroidMessages.INSTALL_COORDINATOR_23,
        AndroidMessages.INSTALL_COORDINATOR_24,
        AndroidMessages.INSTALL_COORDINATOR_25,
        AndroidMessages.INSTALL_COORDINATOR_26,
        AndroidMessages.INSTALL_COORDINATOR_27,
        AndroidMessages.INSTALL_COORDINATOR_28,
        AndroidMessages.INSTALL_COORDINATOR_29,
        AndroidMessages.INSTALL_COORDINATOR_30,
        AndroidMessages.INSTALL_COORDINATOR_31,
        AndroidMessages.INSTALL_COORDINATOR_32,
        AndroidMessages.INSTALL_COORDINATOR_33,
        AndroidMessages.INSTALL_COORDINATOR_34,
        AndroidMessages.INSTALL_COORDINATOR_35,
        AndroidMessages.INSTALL_COORDINATOR_36,
        AndroidMessages.INSTALL_COORDINATOR_37,
        AndroidMessages.INSTALL_COORDINATOR_38,
        AndroidMessages.INSTALL_COORDINATOR_39,
        AndroidMessages.INSTALL_COORDINATOR_40,
        AndroidMessages.INSTALL_COORDINATOR_41,
        AndroidMessages.INSTALL_COORDINATOR_42,
        AndroidMessages.INSTALL_COORDINATOR_43,
        AndroidMessages.INSTALL_COORDINATOR_44,
        AndroidMessages.INSTALL_COORDINATOR_45,
        AndroidMessages.INSTALL_COORDINATOR_46,
        AndroidMessages.INSTALL_COORDINATOR_47,
        AndroidMessages.INSTALL_COORDINATOR_48,
        AndroidMessages.INSTALL_COORDINATOR_49,
        AndroidMessages.INSTALL_COORDINATOR_50,
        AndroidMessages.INSTALL_COORDINATOR_51,
        AndroidMessages.INSTALL_COORDINATOR_52,
        AndroidMessages.INSTALL_COORDINATOR_53,
        AndroidMessages.INSTALL_COORDINATOR_54,
        AndroidMessages.INSTALL_COORDINATOR_55,
        AndroidMessages.INSTALL_COORDINATOR_56,
        AndroidMessages.INSTALL_COORDINATOR_57,
        AndroidMessages.INSTALL_COORDINATOR_58,
        AndroidMessages.INSTALL_COORDINATOR_59,
        AndroidMessages.INSTALL_COORDINATOR_60,
        AndroidMessages.INSTALL_COORDINATOR_61,
        AndroidMessages.INSTALL_COORDINATOR_62,
        AndroidMessages.INSTALL_COORDINATOR_63,
        AndroidMessages.INSTALL_COORDINATOR_64,
        AndroidMessages.INSTALL_COORDINATOR_65,
        AndroidMessages.INSTALL_COORDINATOR_66,
        AndroidMessages.INSTALL_COORDINATOR_67,
        AndroidMessages.INSTALL_COORDINATOR_68,
        AndroidMessages.INSTALL_COORDINATOR_69,
        AndroidMessages.INSTALL_COORDINATOR_70,
        AndroidMessages.INSTALL_COORDINATOR_71,
        AndroidMessages.INSTALL_COORDINATOR_72,
        AndroidMessages.INSTALL_COORDINATOR_73,
        AndroidMessages.INSTALL_COORDINATOR_74,
        AndroidMessages.INSTALL_COORDINATOR_75,
        AndroidMessages.INSTALL_COORDINATOR_76,
        AndroidMessages.INSTALL_COORDINATOR_77
    };
    private StoredAndroidText() {}
    static String store(String message) {
        if (message == null) return "";
        for (UiText text : MESSAGES) {
            if (message.equals(text.chinese()) || message.equals(text.english())) return PREFIX + text.key();
        }
        return message;
    }
    static String render(String stored) {
        if (stored == null) return "";
        if (stored.startsWith("pdsync.install.failure:")) {
            String[] fields = stored.substring("pdsync.install.failure:".length()).split(":", -1);
            if (fields.length == 2 && ("true".equals(fields[1]) || "false".equals(fields[1]))) {
                try { return InstallDiagnostics.describe(InstallDiagnostics.Stage.valueOf(fields[0]), Boolean.parseBoolean(fields[1])); }
                catch (IllegalArgumentException invalid) { /* Preserve an unrecognized stored status. */ }
            }
        }
        if (stored.startsWith("pdsync.auto.blocked:"))
            return AutoSyncMessages.blocked(stored.substring("pdsync.auto.blocked:".length()));
        for (UiText text : MESSAGES) {
            if (stored.equals(PREFIX + text.key()) || stored.equals(text.chinese()) || stored.equals(text.english()))
                return text.text();
        }
        return AutoSyncMessages.renderLegacy(stored);
    }
    static boolean is(String stored, UiText text) {
        return stored != null && (stored.equals(PREFIX + text.key())
                || stored.equals(text.chinese()) || stored.equals(text.english()));
    }
}
