package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Comparison-only diagnostics. Never copy arbitrary exception messages or private paths. */
public final class SyncInspectionDiagnostics {
    private SyncInspectionDiagnostics() {}

    public enum Stage {
        RUNTIME("runtime", "初始化同步运行时"),
        INSPECT("inspect", "读取并比较存档"),
        HISTORY("history", "生成存档历史");

        final String label, description;
        Stage(String label, String description) { this.label = label; this.description = description; }
    }

    public static final class Failure extends IOException {
        final Stage stage;
        private Failure(Stage stage, Exception cause) {
            super("Sync inspection stage: " + stage.label, cause);
            this.stage = stage;
        }
        public String userMessage() {
            return "同步检查未完成：" + stage.description + "（" + stage.label
                    + "）失败。请复制脱敏诊断查看具体原因。";
        }
    }

    public static Failure at(Stage stage, Exception cause) {
        return cause instanceof Failure ? (Failure) cause : new Failure(stage, cause);
    }

    public static Failure find(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (error != null && seen.add(error)) {
            if (error instanceof Failure) return (Failure) error;
            error = error.getCause();
        }
        return null;
    }

    public static String report(Failure failure, int api, String appVersion, long appVersionCode,
                                String appPackage, String gamePackage) {
        StringBuilder text = new StringBuilder("PDSYNC-SYNC-INSPECTION-DIAGNOSTIC-V1\n");
        text.append("stage=").append(failure.stage.label)
                .append("\napi=").append(api)
                .append("\napp_version=").append(version(appVersion))
                .append("\napp_version_code=").append(appVersionCode)
                .append("\napp_package=").append(publicName(appPackage))
                .append("\ngame_package=").append(publicName(gamePackage))
                .append("\nexception_messages=omitted\n");
        appendThrowables(text, failure);
        return text.toString();
    }

    private static String publicName(String value) {
        return value != null && value.matches("[A-Za-z0-9_.$<>-]+") ? value : "unknown";
    }

    private static String version(String value) {
        return value != null && value.length() <= 40
                && value.matches("[0-9]+(?:\\.[0-9]+){0,3}(?:-(?:test|debug|release))?") ? value : "unknown";
    }

    private static final class RelatedError {
        final String relation;
        final Throwable error;
        RelatedError(String relation, Throwable error) { this.relation = relation; this.error = error; }
    }

    private static void appendThrowables(StringBuilder text, Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        ArrayDeque<RelatedError> pending = new ArrayDeque<>();
        pending.push(new RelatedError("failure", failure));
        while (!pending.isEmpty()) {
            RelatedError next = pending.pop();
            Throwable error = next.error;
            text.append(next.relation).append(": ").append(publicName(error.getClass().getName()));
            if (error instanceof Failure) text.append(" stage=").append(((Failure) error).stage.label);
            if (!seen.add(error)) { text.append(" [already reported]\n"); continue; }
            text.append('\n');
            // Plain frames retain platform and Kotlin origins without Throwable.toString(),
            // Android's special-case stack filtering, arbitrary messages, or absolute paths.
            for (StackTraceElement frame : error.getStackTrace()) {
                text.append("  at ").append(publicName(frame.getClassName())).append('.')
                        .append(publicName(frame.getMethodName())).append('(');
                String file = frame.getFileName();
                text.append(file != null && file.matches("[A-Za-z0-9_$.-]+\\.(?:java|kt)") ? file : "Unknown Source")
                        .append(':').append(frame.getLineNumber()).append(")\n");
            }
            if (error.getCause() != null) pending.push(new RelatedError("caused by", error.getCause()));
            Throwable[] suppressed = error.getSuppressed();
            for (int i = suppressed.length - 1; i >= 0; i--)
                pending.push(new RelatedError("suppressed", suppressed[i]));
        }
    }
}
