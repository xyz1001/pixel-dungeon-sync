package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.util.zip.ZipException;

/** Pure JVM diagnostics checks; the Android comparison wiring is checked separately. */
public final class SyncInspectionDiagnosticChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static String report(SyncInspectionDiagnostics.Failure failure) {
        return SyncInspectionDiagnostics.report(failure, 26, "0.4.2-test", 18,
                "io.github.xyz1001.pdsync.android", "com.example.game");
    }

    public static void main(String[] args) throws Exception {
        phaseAndArchiveCause();
        hostileText();
        suppressionAndCycles();
        deepCauseGraph();
        System.out.println("SyncInspectionDiagnosticChecks: " + checks + " checks passed");
    }

    private static void phaseAndArchiveCause() {
        for (SyncInspectionDiagnostics.Stage stage : SyncInspectionDiagnostics.Stage.values()) {
            ZipException empty = new ZipException("No entries");
            empty.setStackTrace(new StackTraceElement[] {
                    new StackTraceElement("java.util.zip.ZipOutputStream", "finish", "ZipOutputStream.java", 360),
                    new StackTraceElement("io.github.xyz1001.pdsync.core.SaveSnapshots", "empty", "Core.kt", 124),
                    new StackTraceElement("io.github.xyz1001.pdsync.core.AndroidSyncFacade", "inspect", "AndroidSyncFacade.kt", 311)
            });
            IOException wrapped = new IOException("https://private.example/dav password=not-for-report", empty);
            SyncInspectionDiagnostics.Failure failure = SyncInspectionDiagnostics.at(stage, wrapped);
            String text = report(failure);
            check(text.startsWith("PDSYNC-SYNC-INSPECTION-DIAGNOSTIC-V1\nstage=" + stage.label), "distinct sync phase");
            check(text.contains("\napi=26\napp_version=0.4.2-test\napp_version_code=18"), "public app metadata");
            check(text.contains("\napp_package=io.github.xyz1001.pdsync.android\ngame_package=com.example.game"), "public package metadata");
            check(text.contains("caused by: java.io.IOException"), "outer cause retained");
            check(text.contains("caused by: java.util.zip.ZipException"), "specific empty archive exception visible");
            check(text.contains("java.util.zip.ZipOutputStream.finish(ZipOutputStream.java:360)"), "platform source and line retained");
            check(text.contains("io.github.xyz1001.pdsync.core.SaveSnapshots.empty(Core.kt:124)"), "Kotlin origin and line retained");
            check(text.contains("AndroidSyncFacade.inspect(AndroidSyncFacade.kt:311)"), "calling core frame retained");
            check(!text.contains("No entries") && !text.contains("private.example") && !text.contains("not-for-report"), "arbitrary messages omitted");
            check(failure.getCause() == wrapped, "wrapper preserves exact original exception");
            check(SyncInspectionDiagnostics.at(SyncInspectionDiagnostics.Stage.HISTORY, failure) == failure, "precise original phase not replaced");
            check(SyncInspectionDiagnostics.find(new IOException("outer", failure)) == failure, "UI locates wrapped diagnostic");
            check(failure.userMessage().contains(stage.label), "safe UI message names phase");
            check(!failure.userMessage().contains("not-for-report"), "safe UI message does not inspect cause text");
        }
    }

    private static final class HostileException extends IOException {
        @Override public String getMessage() { throw new AssertionError("raw message must never be read"); }
        @Override public String toString() { throw new AssertionError("raw exception must never be formatted"); }
    }

    private static void hostileText() {
        HostileException hostile = new HostileException();
        hostile.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("bad/private-key", "token=secret", "/data/user/private-save/Core.kt", 44),
                new StackTraceElement("safe.Class", "method", "C:\\Users\\private-save\\Core.java", 45),
                new StackTraceElement("safe.Class", "method", "https://private.example/Save.java", 46),
                new StackTraceElement("safe.Class", "method", "Class.java\npassword=secret", 47)
        });
        SyncInspectionDiagnostics.Failure failure = SyncInspectionDiagnostics.at(SyncInspectionDiagnostics.Stage.INSPECT, hostile);
        String text = SyncInspectionDiagnostics.report(failure, 26, "token=secret", 18,
                "package/private-key", "https://private.example");
        check(text.contains("unknown.unknown(Unknown Source:44)"), "malformed frame identities rejected");
        check(text.contains("safe.Class.method(Unknown Source:45)"), "Windows paths omitted");
        check(text.contains("app_version=unknown") && text.contains("app_package=unknown")
                && text.contains("game_package=unknown"), "invalid public metadata rejected");
        for (String secret : new String[]{"private-key", "secret", "private-save", "private.example", "password=", "token="})
            check(!text.contains(secret), "no private text: " + secret);
        check(!text.contains("PDSYNC-INSTALL") && !text.contains("session_id="), "sync report never uses installer phase metadata");
    }

    private static void suppressionAndCycles() {
        IOException first = new IOException("private first"), second = new IOException("private second", first);
        first.initCause(second);
        IOException suppressed = new IOException("private suppressed");
        StackTraceElement[] frames = new StackTraceElement[120];
        for (int i = 0; i < frames.length; i++)
            frames[i] = new StackTraceElement("io.github.xyz1001.pdsync.core.Close", "frame" + i, "Close.kt", i);
        suppressed.setStackTrace(frames);
        first.addSuppressed(suppressed);
        second.addSuppressed(suppressed);
        suppressed.addSuppressed(first);
        String text = report(SyncInspectionDiagnostics.at(SyncInspectionDiagnostics.Stage.HISTORY, first));
        check(text.contains("suppressed: java.io.IOException"), "suppressed errors included");
        check(text.contains("Close.frame119(Close.kt:119)"), "full long suppressed stack retained");
        check(text.contains("[already reported]"), "cause and suppression cycles terminate");
        check(text.indexOf("Close.frame119") == text.lastIndexOf("Close.frame119"), "shared throwable frames emitted once");
        check(!text.contains("private first") && !text.contains("private second") && !text.contains("private suppressed"), "cycle messages omitted");
        check(SyncInspectionDiagnostics.find(first) == null, "cycle search terminates without diagnostic");
        check(SyncInspectionDiagnostics.find(null) == null, "null search supported");
    }

    private static void deepCauseGraph() {
        Throwable cause = null;
        for (int i = 0; i < 1500; i++) {
            IOException next = new IOException("private", cause);
            next.setStackTrace(new StackTraceElement[] { new StackTraceElement("safe.Class", "frame" + i, "Core.kt", i) });
            cause = next;
        }
        String text = report(SyncInspectionDiagnostics.at(SyncInspectionDiagnostics.Stage.INSPECT, (Exception) cause));
        check(text.contains("frame0(Core.kt:0)") && text.contains("frame1499(Core.kt:1499)"), "full cause graph without recursion or silent truncation");
        check(!text.contains("private"), "deep graph messages omitted");
    }
}
