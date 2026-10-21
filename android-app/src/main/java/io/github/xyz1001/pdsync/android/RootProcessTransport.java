package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import io.github.xyz1001.pdsync.root.RootProtocol;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded job. Host tests inject ProcessFactory; they never execute su. */
public final class RootProcessTransport implements AutoCloseable {
    public interface ProcessFactory { Process start(String[] command) throws IOException; }
    public interface Exchange {
        RootProtocol.Response exchange(RootProtocol.Request request, OutputStream archive) throws IOException;
        default RootProtocol.Response exchange(RootProtocol.Request request, InputStream upload, OutputStream archive) throws IOException {
            if(upload!=null || request.archiveLength!=0)throw new Failure("ROOT_RESTORE_UNSUPPORTED");
            return exchange(request,archive);
        }
    }
    public static final class Failure extends IOException {
        public final String code;
        Failure(String code) { super(code); this.code=code; }
    }
    private final ProcessFactory factory;
    private final String[] command;
    private final long timeoutMillis;
    private final boolean commandBudgets;
    private final AtomicReference<Process> active=new AtomicReference<>();
    private volatile boolean closed;

    public RootProcessTransport(String ownInstalledApk) throws IOException {
        this(ownInstalledApk, argv -> new ProcessBuilder(argv).start(), 0);
    }
    public RootProcessTransport(String ownInstalledApk, ProcessFactory factory, long timeoutMillis) throws IOException {
        if(factory==null || timeoutMillis<0 || timeoutMillis>180000 || ownInstalledApk==null
                || !ownInstalledApk.startsWith("/data/app/") || !ownInstalledApk.endsWith(".apk")
                || !ownInstalledApk.matches("/[A-Za-z0-9_./+=@~-]+") || ownInstalledApk.contains("//"))
            throw new IOException(AndroidMessages.ROOT_PROCESS_TRANSPORT_01.text());
        for(String part:ownInstalledApk.split("/")) if(part.equals(".")||part.equals(".."))throw new IOException(AndroidMessages.ROOT_PROCESS_TRANSPORT_01.text());
        this.factory=factory;this.timeoutMillis=timeoutMillis;this.commandBudgets=timeoutMillis==0;
        // Only the app's PM supplied APK enters this tightly encoded, fixed launcher.
        // Every target path, package, fingerprint and operation goes through the binary pipe.
        // Android app-data isolation is a mount namespace, independent of UID 0. Request
        // the root manager's process-local global view; never change its persistent policy,
        // mount/unmount directories, or silently retry in an isolated namespace.
        this.command=new String[]{"su","--mount-master","-c","CLASSPATH='"+ownInstalledApk+"' exec /system/bin/app_process /system/bin io.github.xyz1001.pdsync.root.RootRunner"};
    }
    public RootProtocol.Response exchange(RootProtocol.Request request, OutputStream archive) throws IOException {
        return exchange(request,null,archive);
    }
    public synchronized RootProtocol.Response exchange(RootProtocol.Request request, InputStream upload, OutputStream archive) throws IOException {
        if(closed)throw new Failure("ROOT_CANCELLED");
        ExecutorService executor=Executors.newFixedThreadPool(3,r->{Thread t=new Thread(r,"pdsync-root-pipe");t.setDaemon(true);return t;});
        AtomicBoolean cancelled=new AtomicBoolean();
        Future<RootProtocol.Response> result=executor.submit(()->{
            Process process;
            try {process=factory.start(command.clone());}catch(IOException unavailable){throw new Failure("ROOT_UNAVAILABLE");}
            if(cancelled.get() || closed){destroy(process);throw new Failure("ROOT_CANCELLED");}
            if(!active.compareAndSet(null,process)){destroy(process);throw new Failure("ROOT_BUSY");}
            if(cancelled.get() || closed){active.compareAndSet(process,null);destroy(process);throw new Failure("ROOT_CANCELLED");}
            Future<?> errorDrain=executor.submit(()->{
                try(InputStream error=process.getErrorStream()) {
                    byte[] bytes=new byte[1024];int total=0,n;
                    while((n=error.read(bytes))!=-1)if((total+=n)>16384){destroy(process);throw new Failure("ROOT_DIAGNOSTIC_LIMIT");}
                } catch(IOException failure) {throw new UncheckedIOException(failure);}
            });
            Future<?> requestWriter=executor.submit(()->{
                try(OutputStream input=process.getOutputStream()) {RootProtocol.writeRequest(input,request,upload);}
                catch(IOException failure){throw new UncheckedIOException(failure);}
            });
            try {
                RootProtocol.Response reply=RootProtocol.readResponse(process.getInputStream(),archive);
                RootProtocol.requireReplyMatches(request,reply);
                int exit=process.waitFor();
                errorDrain.get();
                if(exit!=0)throw new Failure(exit==124?"ROOT_RUNNER_TIMEOUT":"ROOT_DENIED_OR_EXITED");
                try {requestWriter.get();}
                catch(ExecutionException failedWrite) {
                    // A root-side typed rejection may stop reading an upload early. Keep that rejection.
                    if(reply.status==RootProtocol.Status.OK)throw new Failure("ROOT_PIPE_FAILED");
                }
                return reply;
            } catch(IOException malformed) {
                if(cancelled.get() || closed)throw new Failure("ROOT_CANCELLED");
                if(malformed instanceof Failure)throw malformed;
                if(!process.isAlive() && process.exitValue()!=0)
                    throw new Failure(process.exitValue()==124?"ROOT_RUNNER_TIMEOUT":"ROOT_DENIED_OR_EXITED");
                throw new Failure("ROOT_INVALID_REPLY");
            } finally {active.compareAndSet(process,null);destroy(process);}
        });
        try{return result.get(commandBudgets?budgetMillis(request.command):timeoutMillis,TimeUnit.MILLISECONDS);}
        catch(TimeoutException timeout){throw new Failure("ROOT_TIMEOUT");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Failure("ROOT_CANCELLED");}
        catch(ExecutionException failed){
            Throwable cause=failed.getCause();
            for(int i=0;i<4&&(cause instanceof ExecutionException||cause instanceof UncheckedIOException);i++)cause=cause.getCause();
            if(cause instanceof Failure)throw (Failure)cause;
            throw new Failure("ROOT_PIPE_FAILED");
        } finally {
            cancelled.set(true);result.cancel(true);Process process=active.getAndSet(null);if(process!=null)destroy(process);executor.shutdownNow();
        }
    }
    public static long budgetMillis(RootProtocol.Command command) {
        if(command==RootProtocol.Command.RESTORE||command==RootProtocol.Command.RECOVER)return 135000;
        if(command==RootProtocol.Command.INSPECT||command==RootProtocol.Command.EXPORT||command==RootProtocol.Command.READ_SNAPSHOT)return 60000;
        return 30000;
    }
    @Override public void close(){closed=true;Process process=active.getAndSet(null);if(process!=null)destroy(process);}
    private static void destroy(Process process) {
        // Kill first: closing a stream while a worker holds its read/write lock may block.
        process.destroy();if(process.isAlive())process.destroyForcibly();
        try{process.getOutputStream().close();}catch(IOException ignored){}
        try{process.getInputStream().close();}catch(IOException ignored){}
        try{process.getErrorStream().close();}catch(IOException ignored){}
    }
}
