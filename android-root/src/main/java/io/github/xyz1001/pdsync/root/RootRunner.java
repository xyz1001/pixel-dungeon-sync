package io.github.xyz1001.pdsync.root;

import android.system.Os;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/** app_process entry. One private stdin request, one stdout reply, then immediate process exit. */
public final class RootRunner {
    public static final long MAX_JOB_MILLIS = 60000;
    private RootRunner() { }
    public static void main(String[] args) {
        Thread expiry = new Thread(() -> {
            try { Thread.sleep(MAX_JOB_MILLIS); } catch (InterruptedException ignored) { return; }
            Runtime.getRuntime().halt(124);
        }, "pdsync-root-job-expiry");
        expiry.setDaemon(true); expiry.start();
        try (OutputStream protocol = new FileOutputStream(FileDescriptor.out)) {
            // Framework initialization must not accidentally turn stdout into an unbounded text channel.
            System.setOut(new PrintStream(new OutputStream() { @Override public void write(int value) { } }));
            RootProtocol.Response response;
            if (Os.geteuid() != 0) response = RootProtocol.Response.failure(RootProtocol.Status.NOT_ROOT, "");
            else if (args.length != 0) response = RootProtocol.Response.failure(RootProtocol.Status.INVALID_REQUEST, "");
            else {
                RootProtocol.Request request;
                try { request = RootProtocol.readRequest(System.in); }
                catch (IOException | RuntimeException malformed) {
                    RootProtocol.writeResponse(protocol, RootProtocol.Response.failure(RootProtocol.Status.INVALID_REQUEST, ""));
                    return;
                }
                response = new RootService(new AndroidRootRuntime()).execute(request);
            }
            RootProtocol.writeResponse(protocol, response);
        } catch (IOException | RuntimeException | LinkageError failure) {
            // No exception text or private paths on stdout; caller treats EOF as a failed job.
        } finally {
            // ActivityThread/Binder may create non-daemon framework threads. Never retain root privileges.
            Runtime.getRuntime().halt(0);
        }
    }
}
