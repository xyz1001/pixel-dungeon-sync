package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Installer-only diagnostics: public identity and complete frames, never arbitrary error messages. */
public final class InstallDiagnostics {
    private InstallDiagnostics() {}

    public enum Stage {
        CREATE("create"), OPEN("open"), WRITE("write"), FSYNC("fsync"),
        READ_SOURCE_VERIFY("read-source-verify"), GET_NAMES("getNames"),
        CHECKPOINT("checkpoint"), CALLBACK("callback"), COMMIT("commit"), CLOSE("close"),
        ABANDON("abandon");
        final String label;
        Stage(String label) { this.label = label; }
    }

    static final class StageException extends IOException {
        final Stage stage;
        StageException(Stage stage, Exception cause) {
            super(AndroidMessages.INSTALL_DIAGNOSTICS_01.text() + stage.label, cause);
            this.stage = stage;
        }
    }

    static StageException at(Stage stage, Exception cause) {
        return cause instanceof StageException ? (StageException) cause : new StageException(stage, cause);
    }

    public static final class Failure extends IOException {
        final Stage stage;
        final String packageName;
        final int sessionId, api;
        final boolean commitAttempted;
        Failure(Stage stage, String packageName, int sessionId, int api, boolean commitAttempted, Exception cause) {
            super(AndroidMessages.INSTALL_STAGE_FAILED.text(stage.label), cause);
            this.stage = stage;
            this.packageName = packageName;
            this.sessionId = sessionId;
            this.api = api;
            this.commitAttempted = commitAttempted;
        }
        public String userMessage() {
            return describe(stage, commitAttempted);
        }
    }

    static String describe(Stage stage, boolean commitAttempted) {
        String space = pdsync.i18n.UiLocale.isChinese() ? "" : " ";
        return AndroidMessages.INSTALL_STAGE_FAILED.text(stage.label) + space
                + (commitAttempted ? AndroidMessages.INSTALL_DIAGNOSTICS_04.text() : AndroidMessages.INSTALL_DIAGNOSTICS_05.text())
                + space + AndroidMessages.INSTALL_DIAGNOSTICS_06.text();
    }

    public static Failure failure(Stage stage, String packageName, int sessionId, int api,
                                  boolean commitAttempted, Exception cause) {
        if (cause instanceof StageException) stage = ((StageException) cause).stage;
        return new Failure(stage, packageName, sessionId, api, commitAttempted, cause);
    }

    public static Failure find(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (error != null && seen.add(error)) {
            if (error instanceof Failure) return (Failure) error;
            error = error.getCause();
        }
        return null;
    }

    public static String report(Failure failure) {
        StringBuilder text = new StringBuilder("PDSYNC-INSTALL-DIAGNOSTIC-V1\n");
        text.append("stage=").append(failure.stage.label)
                .append("\npackage=").append(publicName(failure.packageName))
                .append("\nsession_id=").append(failure.sessionId)
                .append("\napi=").append(failure.api)
                .append("\ncommit_attempted=").append(failure.commitAttempted)
                .append("\nverification=accepted-write-sha256-and-length;source-pre-and-post;exact-session-names")
                .append("\nexception_messages=omitted\n");
        appendThrowable(text, "failure", failure,
                Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>()));
        return text.toString();
    }

    private static String publicName(String value) {
        return value != null && value.matches("[A-Za-z0-9_.$<>-]+") ? value : "unknown";
    }

    private static void appendThrowable(StringBuilder text, String relation, Throwable error, Set<Throwable> seen) {
        text.append(relation).append(": ").append(error.getClass().getName());
        if (error instanceof StageException) text.append(" stage=").append(((StageException) error).stage.label);
        if (!seen.add(error)) { text.append(" [already reported]\n"); return; }
        text.append('\n');
        // Do not use Log.getStackTraceString / Log.e(tag, message, error): API 26 suppresses
        // DeadSystemException stacks. Include every frame and cause as plain text instead.
        for (StackTraceElement frame : error.getStackTrace()) {
            text.append("  at ").append(publicName(frame.getClassName())).append('.')
                    .append(publicName(frame.getMethodName())).append('(');
            String file = frame.getFileName();
            text.append(file != null && file.matches("[A-Za-z0-9_$.-]+\\.java") ? file : "Unknown Source")
                    .append(':').append(frame.getLineNumber()).append(")\n");
        }
        for (Throwable suppressed : error.getSuppressed()) appendThrowable(text, "suppressed", suppressed, seen);
        if (error.getCause() != null) appendThrowable(text, "caused by", error.getCause(), seen);
    }
}
