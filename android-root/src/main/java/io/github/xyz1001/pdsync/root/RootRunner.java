package io.github.xyz1001.pdsync.root;

import android.system.Os;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** app_process entry. One bounded private-pipe job, no daemon or arbitrary command arguments. */
public final class RootRunner {
    public static final long MAX_JOB_MILLIS=120000;
    private RootRunner() { }
    public static long jobBudgetMillis(RootProtocol.Command command) {
        if(command==RootProtocol.Command.RESTORE||command==RootProtocol.Command.RECOVER)return MAX_JOB_MILLIS;
        if(command==RootProtocol.Command.INSPECT||command==RootProtocol.Command.EXPORT||command==RootProtocol.Command.READ_SNAPSHOT)return 50000;
        return 25000;
    }
    public static void main(String[] args) {
        long started=System.nanoTime();
        AtomicLong deadline=new AtomicLong(started+TimeUnit.MILLISECONDS.toNanos(25000));
        Thread expiry=new Thread(()->{
            while(true) {
                long remaining=deadline.get()-System.nanoTime();
                if(remaining<=0)Runtime.getRuntime().halt(124);
                try{Thread.sleep(Math.max(1,Math.min(1000,TimeUnit.NANOSECONDS.toMillis(remaining))));}
                catch(InterruptedException ignored){/* The watchdog must not silently disappear. */}
            }
        },"pdsync-root-job-expiry");
        expiry.setDaemon(true);expiry.start();
        try(OutputStream protocol=new FileOutputStream(FileDescriptor.out)) {
            System.setOut(new PrintStream(new OutputStream(){@Override public void write(int value){}}));
            RootProtocol.Response response;
            // Never read a caller-selected binding or initialize framework access before checking effective UID.
            if(Os.geteuid()!=0)response=RootProtocol.Response.failure(RootProtocol.Status.NOT_ROOT,"")
                    .withDiagnostic(RootProtocol.DiagnosticStage.EUID,RootProtocol.DiagnosticCategory.PERMISSION,0);
            else if(args==null||args.length!=0)response=RootProtocol.Response.failure(RootProtocol.Status.INVALID_REQUEST,"");
            else {
                RootProtocol.RequestFrame frame;
                try{frame=RootProtocol.readRequestFrame(System.in);}
                catch(IOException|RuntimeException malformed) {
                    RootProtocol.writeResponse(protocol,RootProtocol.Response.failure(RootProtocol.Status.INVALID_REQUEST,"")
                            .withDiagnostic(RootProtocol.DiagnosticStage.ARCHIVE_READ,RootProtocol.DiagnosticCategory.MALFORMED,0));
                    return;
                }
                deadline.set(started+TimeUnit.MILLISECONDS.toNanos(jobBudgetMillis(frame.request.command)));
                response=new RootService(new AndroidRootRuntime()).execute(frame);
            }
            RootProtocol.writeResponse(protocol,response);
        }catch(IOException|RuntimeException|LinkageError failure) {
            // EOF/exit is failure. Never emit raw paths, exception messages, stderr or save data as diagnostics.
        }finally {
            // Binder/framework may create non-daemon threads. Always end the root runtime after one response.
            Runtime.getRuntime().halt(0);
        }
    }
}
