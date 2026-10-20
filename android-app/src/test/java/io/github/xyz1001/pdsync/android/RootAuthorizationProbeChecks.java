package io.github.xyz1001.pdsync.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Fixed-command host regression; no su or device is executed. */
public final class RootAuthorizationProbeChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        for (String success : new String[]{"0", "0\n", "0\r\n"}) {
            Fake process = new Fake(success, "", 0, false);
            RootAuthorizationProbe.run(argv -> {
                check(Arrays.equals(argv, new String[]{"su", "-c", "/system/bin/id -u"}), "fixed argv");
                return process;
            }, 1000);
            check(process.destroyed, "success process cleaned");
        }
        rejects("ROOT_NOT_ROOT", new Fake("2000\n", "", 0, false));
        rejects("ROOT_NOT_ROOT", new Fake("prefix 0\n", "", 0, false));
        rejects("ROOT_NOT_ROOT", new Fake("0\nextra", "", 0, false));
        rejects("ROOT_DENIED_OR_EXITED", new Fake("0\n", "secret token text", 1, false));
        rejects("ROOT_DIAGNOSTIC_LIMIT", new Fake("x".repeat(33), "", 0, false));
        rejects("ROOT_DIAGNOSTIC_LIMIT", new Fake("0", "x".repeat(4097), 0, false));
        expect("ROOT_UNAVAILABLE", () -> RootAuthorizationProbe.run(argv -> { throw new IOException("secret path"); }, 1000));
        Fake delayed = new Fake("0", "", 0, true);
        expect("ROOT_TIMEOUT", () -> RootAuthorizationProbe.run(argv -> delayed, 30));
        check(delayed.destroyed, "timeout cleaned");
        CountDownLatch started = new CountDownLatch(1);
        Fake interrupted = new Fake("0", "", 0, true);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try { RootAuthorizationProbe.run(argv -> { started.countDown(); return interrupted; }, 1000); }
            catch (Throwable e) { result.set(e); check(Thread.currentThread().isInterrupted(), "interrupt preserved"); }
        });
        t.start(); check(started.await(1, TimeUnit.SECONDS), "started"); t.interrupt(); t.join(1000);
        check(result.get() instanceof IOException && "ROOT_CANCELLED".equals(result.get().getMessage()), "cancelled");
        check(interrupted.destroyed, "cancel cleaned");
        // A delayed launcher must not leave a process running after the caller's deadline.
        CountDownLatch release = new CountDownLatch(1), returned = new CountDownLatch(1);
        Fake late = new Fake("0", "", 0, false);
        expect("ROOT_TIMEOUT", () -> RootAuthorizationProbe.run(argv -> {
            while (true) { try { release.await(); break; } catch (InterruptedException ignored) { } }
            returned.countDown(); return late;
        }, 30));
        release.countDown(); check(returned.await(1, TimeUnit.SECONDS), "late returned");
        check(late.closed.await(1, TimeUnit.SECONDS), "late process cleaned");
        System.out.println("Root authorization probe: " + checks + " checks passed");
    }
    private interface Operation { void run() throws Exception; }
    private static void rejects(String code, Fake process) throws Exception {
        expect(code, () -> RootAuthorizationProbe.run(argv -> process, 1000));
        check(process.destroyed, "failed process cleaned");
    }
    private static void expect(String code, Operation task) throws Exception {
        try { task.run(); throw new AssertionError("expected " + code); }
        catch (IOException failure) { check(code.equals(failure.getMessage()), "closed code: " + code); check(failure.getCause() == null, "no raw cause"); }
    }
    private static synchronized void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static final class Fake extends Process {
        final InputStream stdout, stderr; final int exit; final boolean waits;
        volatile boolean destroyed; final CountDownLatch closed = new CountDownLatch(1);
        Fake(String out, String err, int exit, boolean waits) {
            stdout = new ByteArrayInputStream(out.getBytes(StandardCharsets.US_ASCII));
            stderr = new ByteArrayInputStream(err.getBytes(StandardCharsets.US_ASCII)); this.exit = exit; this.waits = waits;
        }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public InputStream getInputStream() { return stdout; }
        public InputStream getErrorStream() { return stderr; }
        public int waitFor() throws InterruptedException { if (waits) closed.await(); return exit; }
        public int exitValue() { return exit; }
        public void destroy() { destroyed = true; closed.countDown(); }
        public Process destroyForcibly() { destroy(); return this; }
    }
}
