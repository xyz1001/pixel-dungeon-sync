package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed command, bounded output/deadline. Successful setup grants no file-operation capability. */
public final class RootAuthorizationProbe {
    public interface ProcessFactory { Process start(String[] command) throws IOException; }
    private RootAuthorizationProbe() { }

    public static void run(ProcessFactory factory, long timeoutMillis) throws IOException {
        if (factory == null || timeoutMillis < 1 || timeoutMillis > 60000) throw new IllegalArgumentException(AndroidMessages.ROOT_AUTHORIZATION_PROBE_01.text());
        ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "pdsync-root-authorization"); t.setDaemon(true); return t;
        });
        java.util.concurrent.atomic.AtomicReference<Process> active = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        Future<?> job = pool.submit(() -> {
            Process process;
            try { process = factory.start(new String[]{"su", "-c", "/system/bin/id -u"}); }
            catch (IOException unavailable) { throw new IOException("ROOT_UNAVAILABLE"); }
            active.set(process);
            if (cancelled.get()) { stop(process); throw new IOException("ROOT_CANCELLED"); }
            try {
                process.getOutputStream().close();
                Future<byte[]> stdout = pool.submit(() -> bounded(process.getInputStream(), 32));
                Future<byte[]> stderr = pool.submit(() -> bounded(process.getErrorStream(), 4096));
                int exit = process.waitFor();
                byte[] reply = readResult(stdout); readResult(stderr);
                String uid = new String(reply, java.nio.charset.StandardCharsets.US_ASCII);
                if (exit != 0) throw new IOException("ROOT_DENIED_OR_EXITED");
                if (!(uid.equals("0") || uid.equals("0\n") || uid.equals("0\r\n"))) throw new IOException("ROOT_NOT_ROOT");
                return null;
            } finally { stop(process); active.compareAndSet(process, null); }
        });
        try { job.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (TimeoutException timeout) { throw new IOException("ROOT_TIMEOUT"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("ROOT_CANCELLED"); }
        catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("ROOT_INVALID_REPLY");
        } finally {
            cancelled.set(true); job.cancel(true);
            Process process = active.getAndSet(null); if (process != null) stop(process);
            pool.shutdownNow();
        }
    }

    private static byte[] readResult(Future<byte[]> future) throws IOException, InterruptedException {
        try { return future.get(); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException
                    && "ROOT_DIAGNOSTIC_LIMIT".equals(failed.getCause().getMessage()))
                throw new IOException("ROOT_DIAGNOSTIC_LIMIT");
            throw new IOException("ROOT_PIPE_FAILED");
        }
    }

    private static byte[] bounded(InputStream input, int limit) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256]; int n;
            while ((n = stream.read(buffer)) != -1) {
                if (out.size() + n > limit) throw new IOException("ROOT_DIAGNOSTIC_LIMIT");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void stop(Process process) {
        try { process.destroy(); } catch (RuntimeException ignored) { }
        try { process.destroyForcibly(); } catch (RuntimeException ignored) { }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }
}
