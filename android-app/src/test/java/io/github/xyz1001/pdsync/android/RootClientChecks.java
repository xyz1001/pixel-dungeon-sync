package io.github.xyz1001.pdsync.android;

import io.github.xyz1001.pdsync.root.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import pdsync.DirectoryMetadataKt;
import java.util.zip.*;
import pdsync.android.app.AndroidGameIdentity;

/** JVM-only: every transport launch is replaced by an in-memory fake process. */
public final class RootClientChecks {
    private static int checks;
    private static final String A=String.join("",Collections.nCopies(64,"a"));
    private static final String B=String.join("",Collections.nCopies(64,"b"));
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static SelectedRootGame selection()throws Exception {
        return new SelectedRootGame("com.example.game","Game","1",1,10,11,0,10123,
                "/data/user/0/com.example.game",Collections.singletonList(A),Collections.singletonList(A),B,
                Collections.singletonMap("/data/app/game/base.apk",A),false);
    }
    private interface Throwing {void run()throws Exception;}
    private static void rejects(Throwing call,String expected)throws Exception {
        try{call.run();throw new AssertionError("Expected "+expected);}catch(AssertionError failure){throw failure;}
        catch(Exception failure){check(expected==null||failure.toString().contains(expected),"Wrong failure "+failure);}
    }
    private static byte[] reply(RootProtocol.Response response)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();RootProtocol.writeResponse(out,response);return out.toByteArray();
    }
    private static RootProtocol.Response okay(SelectedRootGame game)throws Exception {
        return new RootProtocol.Response(RootProtocol.Status.OK,game.binding().fingerprint(),"",0,0,"","UNKNOWN",false,null);
    }
    private static final class FakeProcess extends Process {
        final InputStream input;final ByteArrayOutputStream written=new ByteArrayOutputStream();final int exit;
        volatile boolean destroyed;
        FakeProcess(byte[] reply,int exit){this.input=new ByteArrayInputStream(reply);this.exit=exit;}
        FakeProcess(InputStream input){this.input=input;this.exit=0;}
        @Override public OutputStream getOutputStream(){return written;}
        @Override public InputStream getInputStream(){return input;}
        @Override public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        @Override public int waitFor(){return exit;}
        @Override public int exitValue(){return exit;}
        @Override public void destroy(){destroyed=true;try{input.close();}catch(IOException ignored){}}
        @Override public Process destroyForcibly(){destroy();return this;}
        @Override public boolean isAlive(){return !destroyed && !(input instanceof ByteArrayInputStream);}
    }
    private static final class BlockingInput extends InputStream {
        volatile boolean closed;
        @Override public synchronized int read()throws IOException {
            while(!closed)try{wait();}catch(InterruptedException interrupted){throw new IOException("interrupted");}
            return -1;
        }
        @Override public synchronized void close(){closed=true;notifyAll();}
    }
    public static void main(String[] args)throws Exception {
        SelectedRootGame game=selection();Path dir=Files.createTempDirectory("root-client-host-");
        diagnosticLoggingChecks(dir, game);
        String[] rootCodes={"ROOT_AUTH_REQUIRED","ROOT_UNAVAILABLE","ROOT_DENIED_OR_EXITED","ROOT_TIMEOUT",
                "ROOT_RUNNER_TIMEOUT","ROOT_CANCELLED","ROOT_INVALID_REPLY","ROOT_PIPE_FAILED","ROOT_DIAGNOSTIC_LIMIT","ROOT_TARGET_CHANGED",
                "ROOT_BINDING_MISMATCH","ROOT_ACCESS_FAILED","ROOT_SAVE_NOT_READY","ROOT_UNEXPECTED_ARCHIVE",
                "ROOT_EXPORT_MISMATCH","ROOT_EXPORT_FAILED","ROOT_ARCHIVE_INCOMPLETE","ROOT_RESTORE_UNSUPPORTED",
                "ROOT_RECOVERY_UNSUPPORTED","ROOT_UNSUPPORTED","ROOT_NOT_ROOT","ROOT_INVALID_REQUEST","ROOT_UNSAFE_PATH",
                "ROOT_CHANGED","ROOT_TOO_LARGE","ROOT_UNRELIABLE","ROOT_IO_ERROR","ROOT_MISSING_DIRECTORY",
                "ROOT_UNRECOGNIZED_LAYOUT","ROOT_BUSY"};
        for(String code:rootCodes)check(new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.SAVE_READINESS,code,"bounded",null).code.equals(code),"preserved typed root diagnostic "+code);
        check(new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.SAVE_READINESS,"ROOT_ARBITRARY_UNTRUSTED","bounded",null).code.equals("UNKNOWN_BRIDGE_FAILURE"),"unknown codes stay filtered");
        RootSelectionStore store=new RootSelectionStore(dir.toFile());check(store.load()==null,"fresh selection");
        store.save(game);check(store.load().matches(game),"selection survives restart");
        check(store.load().namespaceId.equals(game.namespaceId),"namespace continuity");
        check(store.load().selectionToken().equals(game.selectionToken()),"token continuity");
        String original=new String(Files.readAllBytes(dir.resolve("root-selection-v1.properties")),java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(dir.resolve("root-selection-v1.properties"),(original+"\nuid=10124\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        rejects(store::load,"Invalid root selection");store.save(game);
        rejects(()->new SelectedRootGame(game.gamePackage,"Game","1",1,10,11,0,10123,game.dataDir,
                Collections.singletonList(A),Collections.singletonList(B),B,game.apkHashes,false),"provenance");
        RootProtocol.Request request=new RootProtocol.Request(RootProtocol.Command.PROBE,game.binding());
        byte[] valid=reply(okay(game).withRequest(request,RootProtocol.Outcome.READ_ONLY));FakeProcess success=new FakeProcess(valid,0);AtomicInteger launches=new AtomicInteger();
        RootProcessTransport transport=new RootProcessTransport("/data/app/self/base.apk",cmd->{
            launches.incrementAndGet();check(cmd.length==4&&cmd[0].equals("su")&&cmd[1].equals("--mount-master")&&cmd[2].equals("-c"),"fixed job-local global mount namespace args");
            check(!cmd[3].contains(game.gamePackage)&&!cmd[3].contains(game.dataDir),"no target shell interpolation");return success;},1000);
        check(transport.exchange(request,null).status==RootProtocol.Status.OK,"valid response");
        check(RootProtocol.readRequest(new ByteArrayInputStream(success.written.toByteArray())).binding.matchesInstalled(game.binding()),"binary target binding");
        check(success.destroyed,"process closed");
        rejects(()->new RootProcessTransport("/data/app/self/base.apk",cmd->{throw new IOException("missing su");},100).exchange(request,null),"ROOT_UNAVAILABLE");
        AtomicInteger unsupportedNamespace=new AtomicInteger();
        rejects(()->new RootProcessTransport("/data/app/self/base.apk",cmd->{unsupportedNamespace.incrementAndGet();return new FakeProcess(new byte[0],1);},100).exchange(request,null),"ROOT_DENIED_OR_EXITED");
        check(unsupportedNamespace.get()==1,"unsupported namespace option fails without an isolated-namespace retry");
        rejects(()->new RootProcessTransport("/data/app/self/base.apk",cmd->new FakeProcess(Arrays.copyOf(valid,valid.length-2),0),100).exchange(request,null),"ROOT_INVALID_REPLY");
        rejects(()->new RootProcessTransport("/data/app/self/base.apk",cmd->new FakeProcess(new byte[0],124),100).exchange(request,null),"ROOT_RUNNER_TIMEOUT");
        byte[] trailing=Arrays.copyOf(valid,valid.length+1);
        rejects(()->new RootProcessTransport("/data/app/self/base.apk",cmd->new FakeProcess(trailing,0),100).exchange(request,null),"ROOT_INVALID_REPLY");
        FakeProcess blocked=new FakeProcess(new BlockingInput());long start=System.nanoTime();
        rejects(()->new RootProcessTransport("/data/app/self/base.apk",cmd->blocked,50).exchange(request,null),"ROOT_TIMEOUT");
        check(blocked.destroyed&&(System.nanoTime()-start)<2000000000L,"timeout kills child promptly");
        rejects(()->new RootProcessTransport("/data/app/a'; touch /tmp/b/base.apk",cmd->success,50),"fixed root launcher");
        FakeProcess late=new FakeProcess(valid,0);CountDownLatch release=new CountDownLatch(1), started=new CountDownLatch(1);
        RootProcessTransport delayed=new RootProcessTransport("/data/app/self/base.apk",cmd->{
            started.countDown();while(true){try{release.await();break;}catch(InterruptedException ignored){}}
            return late;
        },50);
        rejects(()->delayed.exchange(request,null),"ROOT_TIMEOUT");check(started.getCount()==0,"factory actually started");
        release.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!late.destroyed&&System.nanoTime()<until)Thread.sleep(2);
        check(late.destroyed&&late.written.size()==0,"late launch after timeout destroyed before I/O");
        FakeProcess cancelled=new FakeProcess(new BlockingInput());CountDownLatch launched=new CountDownLatch(1);
        RootProcessTransport cancellation=new RootProcessTransport("/data/app/self/base.apk",cmd->{launched.countDown();return cancelled;},2000);
        AtomicReference<Throwable> cancelError=new AtomicReference<>();
        Thread active=new Thread(()->{try{cancellation.exchange(request,null);}catch(Throwable error){cancelError.set(error);}});
        active.start();check(launched.await(1,TimeUnit.SECONDS),"cancel started");cancellation.close();active.join(2000);
        check(!active.isAlive()&&cancelled.destroyed&&cancelError.get()!=null,"close kills active process");
        AtomicInteger calls=new AtomicInteger(),verifies=new AtomicInteger();
        AndroidGameIdentity identity=new AndroidGameIdentity(game.gamePackage,game.gameLabel,game.originIdentity,game.namespaceId);
        RootGameBridge unauthorized=new RootGameBridge(game,identity,g->verifies.incrementAndGet(),(r,o)->{calls.incrementAndGet();return okayUnchecked(gamet()).withRequest(r,RootProtocol.Outcome.READ_ONLY);},()->false,dir.toFile());
        check(unauthorized.mode()==GameAccessBackend.Mode.ROOT_DIRECT,"root mode");
        unauthorized.targetGame();check(calls.get()==0,"target/readiness render never requests root");
        rejects(unauthorized::inspectReady,"Root 游戏选择");check(calls.get()==0,"authorization gates transport");
        unauthorized.probeAuthorization();check(calls.get()==1,"explicit probe only");
        // A fresh process can use the same persisted opt-in for a deliberate operation; construction is passive.
        AtomicInteger restartedCalls=new AtomicInteger();
        RootGameBridge restarted=new RootGameBridge(store.load(),identity,g->{},(r,o)->{
            restartedCalls.incrementAndGet();return new RootProtocol.Response(RootProtocol.Status.OK,game.binding().fingerprint(),
                    "EMPTY_DIRECTORY",0,0,DirectoryMetadataKt.contentSha256(Collections.emptyMap()),"ABSENT",true,null).withRequest(r,RootProtocol.Outcome.READ_ONLY);
        },()->{try{return game.matches(store.load());}catch(IOException invalid){return false;}},dir.toFile());
        restarted.targetGame();check(restartedCalls.get()==0,"restart construction/target is passive");
        restarted.inspectReady();check(restartedCalls.get()==1,"explicit post-restart inspect needs no redundant manual probe");

        check(!unauthorized.capabilities().restoreSupported&&!unauthorized.capabilities().interruptedBackupRecovery,"readonly caps");
        rejects(()->unauthorized.restoreFrom(dir,Collections.emptyMap()),"尚未提供");
        try{unauthorized.restoreFrom(dir,Collections.emptyMap());}catch(BridgeDiagnostics.Failure failure){
            check(failure.code.equals("ROOT_RESTORE_UNSUPPORTED")&&failure.stage==BridgeDiagnostics.Stage.RESTORE,"restore stage/code preserved");}
        rejects(unauthorized::recoverInterruptedBackup,"尚未提供");check(calls.get()==1,"unsupported write never launches root");
        RootGameBridge changed=new RootGameBridge(game,identity,g->{if(verifies.incrementAndGet()>0)throw new IOException("changed");},(r,o)->{calls.incrementAndGet();return okayUnchecked(game).withRequest(r,RootProtocol.Outcome.READ_ONLY);},()->true,dir.toFile());
        rejects(changed::targetGame,"ROOT_TARGET_CHANGED");check(calls.get()==1,"target change blocks transport");
        String emptyFingerprint=DirectoryMetadataKt.contentSha256(Collections.emptyMap());
        RootGameBridge wrongBinding=new RootGameBridge(game,identity,g->{},(r,o)->new RootProtocol.Response(
                RootProtocol.Status.OK,A,"EMPTY_DIRECTORY",0,0,emptyFingerprint,"ABSENT",true,null).withRequest(r,RootProtocol.Outcome.READ_ONLY),()->true,dir.toFile());
        rejects(wrongBinding::inspectReady,"ROOT_REPLY_MISMATCH");
        RootGameBridge unknown=new RootGameBridge(game,identity,g->{},(r,o)->new RootProtocol.Response(
                RootProtocol.Status.OK,game.binding().fingerprint(),"EMPTY_DIRECTORY",0,0,emptyFingerprint,"UNKNOWN",false,null).withRequest(r,RootProtocol.Outcome.READ_ONLY),()->true,dir.toFile());
        rejects(unknown::inspectReady,"ROOT_UNRELIABLE");check(!unknown.observeLifecycle().reliable,"unknown lifecycle truthful");
        byte[] emptyArchive=new byte[22];emptyArchive[0]=0x50;emptyArchive[1]=0x4b;emptyArchive[2]=5;emptyArchive[3]=6;
        AtomicInteger exportChecks=new AtomicInteger();
        RootGameBridge emptyExporter=new RootGameBridge(game,identity,g->exportChecks.incrementAndGet(),(r,o)->{
            o.write(emptyArchive);return new RootProtocol.Response(RootProtocol.Status.OK,game.binding().fingerprint(),
                    "EMPTY_DIRECTORY",0,0,emptyFingerprint,"ABSENT",true,emptyArchive).withRequest(r,RootProtocol.Outcome.READ_ONLY);
        },()->true,dir.toFile());
        Path emptyDestination=Files.createDirectory(dir.resolve("empty-destination"));
        emptyExporter.exportTo(emptyDestination);check(exportChecks.get()==4,"PM target before/after transport and archive");
        ByteArrayOutputStream zipped=new ByteArrayOutputStream();
        byte[] save="verified game data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(ZipOutputStream zip=new ZipOutputStream(zipped)){zip.putNextEntry(new ZipEntry("game0/game.dat"));zip.write(save);zip.closeEntry();}
        byte[] archiveBytes=zipped.toByteArray();
        String saveHash=io.github.xyz1001.pdsync.bridge.SaveArchive.sha256(save);
        String saveFingerprint=DirectoryMetadataKt.contentSha256(Collections.singletonMap("game0/game.dat",saveHash));
        RootGameBridge exporter=new RootGameBridge(game,identity,g->{},(r,o)->{
            o.write(archiveBytes);return new RootProtocol.Response(RootProtocol.Status.OK,game.binding().fingerprint(),
                    "ALLOWLISTED_FILES_PRESENT",1,save.length,saveFingerprint,"CACHED",true,archiveBytes).withRequest(r,RootProtocol.Outcome.READ_ONLY);
        },()->true,dir.toFile());
        Path nonempty=Files.createDirectory(dir.resolve("nonempty-destination"));exporter.exportTo(nonempty);
        check(Arrays.equals(Files.readAllBytes(nonempty.resolve("game0/game.dat")),save),"nonempty export preserves all bytes");
        RootGameBridge badFingerprint=new RootGameBridge(game,identity,g->{},(r,o)->{
            o.write(archiveBytes);return new RootProtocol.Response(RootProtocol.Status.OK,game.binding().fingerprint(),
                    "ALLOWLISTED_FILES_PRESENT",1,save.length,A,"CACHED",true,archiveBytes).withRequest(r,RootProtocol.Outcome.READ_ONLY);
        },()->true,dir.toFile());
        rejects(()->badFingerprint.exportTo(Files.createDirectory(dir.resolve("bad-fingerprint"))),"ROOT_EXPORT_MISMATCH");
        try{badFingerprint.exportTo(Files.createDirectory(dir.resolve("bad-fingerprint-stage")));}
        catch(BridgeDiagnostics.Failure failure){check(failure.code.equals("ROOT_EXPORT_MISMATCH")&&failure.stage==BridgeDiagnostics.Stage.EXPORT,"export stage/code preserved");}
        AtomicInteger authorizationReads=new AtomicInteger();
        RootGameBridge revoked=new RootGameBridge(game,identity,g->{},(r,o)->okayUnchecked(game).withRequest(r,RootProtocol.Outcome.READ_ONLY),()->authorizationReads.incrementAndGet()==1,dir.toFile());
        rejects(revoked::inspectReady,"ROOT_CANCELLED");
        AtomicInteger changedChecks=new AtomicInteger();
        RootGameBridge changedAfter=new RootGameBridge(game,identity,g->{if(changedChecks.incrementAndGet()==3)throw new IOException("target changed");},(r,o)->{
            o.write(emptyArchive);return new RootProtocol.Response(RootProtocol.Status.OK,game.binding().fingerprint(),
                    "EMPTY_DIRECTORY",0,0,emptyFingerprint,"ABSENT",true,emptyArchive).withRequest(r,RootProtocol.Outcome.READ_ONLY);
        },()->true,dir.toFile());
        rejects(()->changedAfter.exportTo(Files.createDirectory(dir.resolve("changed-after"))),"ROOT_TARGET_CHANGED");
        RootGameBridge truncatedArchive=new RootGameBridge(game,identity,g->{},(r,o)->{
            o.write(new byte[22]);return new RootProtocol.Response(RootProtocol.Status.OK,game.binding().fingerprint(),
                    "EMPTY_DIRECTORY",0,0,emptyFingerprint,"ABSENT",true,emptyArchive).withRequest(r,RootProtocol.Outcome.READ_ONLY);
        },()->true,dir.toFile());
        rejects(()->truncatedArchive.exportTo(Files.createDirectory(dir.resolve("bad-archive"))),"ROOT_EXPORT_FAILED");
        Path empty=Files.createTempFile(dir,"empty-",".zip");byte[] emptyZip=new byte[22];emptyZip[0]=0x50;emptyZip[1]=0x4b;emptyZip[2]=5;emptyZip[3]=6;Files.write(empty,emptyZip);
        RootExportValidation.requireCompleteArchive(empty.toFile(),22,0);check(true,"empty archive framing");
        Files.write(empty,new byte[22]);rejects(()->RootExportValidation.requireCompleteArchive(empty.toFile(),22,0),"Incomplete");
        store.clear();check(store.load()==null,"switch clears root only");
        System.out.println("Root client host checks passed: "+checks+" (no root/phone calls)");
    }
    private static SelectedRootGame gamet(){try{return selection();}catch(Exception e){throw new AssertionError(e);}}
    private static RootProtocol.Response okayUnchecked(SelectedRootGame game){try{return okay(game);}catch(Exception e){throw new AssertionError(e);}}
    private static String diagnosticEvents() throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(pdsync.DiagnosticLog.snapshotExport()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
                if ("events.jsonl".equals(entry.getName())) return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new AssertionError("events missing");
    }
    private static void diagnosticLoggingChecks(Path directory, SelectedRootGame game) throws Exception {
        pdsync.DiagnosticLog.configure(directory.resolve("diagnostics"), "0.5.15-root-test+37", "android");
        RootProcessTransport.Exchange busyReply = (request, archive) -> new RootProtocol.Response(RootProtocol.Status.OK,
                game.binding().fingerprint(), "ALLOWLISTED_FILES_PRESENT", 4, 1234, A, "FOREGROUND", true, null)
                .withRequest(request, RootProtocol.Outcome.READ_ONLY);
        RootGameBridge bridge = new RootGameBridge(game, null, selected -> {}, busyReply, () -> true, directory.toFile());
        pdsync.DiagnosticTrace trace = pdsync.DiagnosticLog.begin(pdsync.DiagnosticAction.ACCESS_CHECK, false);
        try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(trace)) {
            try { bridge.inspectReady(); throw new AssertionError("busy inspection accepted"); }
            catch (BridgeDiagnostics.Failure failure) {
                check("ROOT_BUSY".equals(failure.code), "existing busy decision preserved");
                AndroidDiagnosticLogging.failure(trace, pdsync.DiagnosticStage.FINISHED, failure);
            }
        }
        String events = diagnosticEvents();
        check(events.contains("\"rootCommand\":\"INSPECT\""), "actual request command exported");
        check(events.contains("\"readinessCode\":\"OK\""), "protocol response is only OK");
        check(!events.contains("\"readinessCode\":\"READY\""), "busy response never marked ready");
        check(events.contains("\"observationState\":\"FOREGROUND\""), "real UID observation preserved");
        check(events.contains("\"fileCount\":\"4\""), "inventory retained before busy readiness rejection");
        check(events.contains("\"contentBytes\":\"1234\""), "bounded inventory size retained");
        check(events.contains("\"failure\":\"BUSY\""), "busy terminal error retained");
        RootProcessTransport.Exchange permissionReply = (request, archive) -> RootProtocol.Response.failure(RootProtocol.Status.NOT_ROOT,
                request).withDiagnostic(RootProtocol.DiagnosticStage.EUID, RootProtocol.DiagnosticCategory.PERMISSION, 13);
        RootGameBridge denied = new RootGameBridge(game, null, selected -> {}, permissionReply, () -> true, directory.toFile());
        pdsync.DiagnosticTrace rejected = pdsync.DiagnosticLog.begin(pdsync.DiagnosticAction.ACCESS_CHECK, false);
        try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(rejected)) {
            try { denied.inspectReady(); throw new AssertionError("denied request accepted"); }
            catch (BridgeDiagnostics.Failure failure) { AndroidDiagnosticLogging.failure(rejected, pdsync.DiagnosticStage.FINISHED, failure); }
        }
        for (String line : diagnosticEvents().split("\n")) if (line.contains(rejected.getId())) {
            check(!line.contains("\"fileCount\""), "default zero failure inventory omitted");
            check(!line.contains("\"contentBytes\""), "default zero failure size omitted");
        }
        AtomicReference<String> observation = new AtomicReference<>("FOREGROUND");
        RootProcessTransport.Exchange observedReply = (request, archive) -> new RootProtocol.Response(RootProtocol.Status.OK,
                game.binding().fingerprint(), "", 0, 0, "", observation.get(), true, null)
                .withRequest(request, RootProtocol.Outcome.READ_ONLY);
        RootGameBridge observed = new RootGameBridge(game, null, selected -> {}, observedReply, () -> true, directory.toFile());
        pdsync.DiagnosticTrace monitored = pdsync.DiagnosticLog.begin(pdsync.DiagnosticAction.AUTOMATIC_SYNC, true);
        try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(monitored)) {
            for (int i = 0; i < 10; i++) observed.observeLifecycle();
            observation.set("CACHED");
            for (int i = 0; i < 10; i++) observed.observeLifecycle();
        }
        int transitions = 0;
        for (String line : diagnosticEvents().split("\n")) if (line.contains(monitored.getId()) && line.contains("\"stage\":\"BRIDGE\"")) {
            transitions++;
            check(line.contains("\"rootCommand\":\"OBSERVE\""), "actual observation command exported");
            check(!line.contains("\"fileCount\""), "observation does not invent empty inventory");
        }
        check(transitions == 2, "only changed observation states are logged, not every poll");
        check(!diagnosticEvents().contains(game.gamePackage), "package and binding identifiers omitted");
    }

}
