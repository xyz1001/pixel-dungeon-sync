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
        RootSelectionStore store=new RootSelectionStore(dir.toFile());check(store.load()==null,"fresh selection");
        store.save(game);check(store.load().matches(game),"selection survives restart");
        check(store.load().namespaceId.equals(game.namespaceId),"namespace continuity");
        check(store.load().selectionToken().equals(game.selectionToken()),"token continuity");
        String original=new String(Files.readAllBytes(dir.resolve("root-selection-v1.properties")),java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(dir.resolve("root-selection-v1.properties"),(original+"\nuid=10124\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        rejects(store::load,"Invalid root selection");store.save(game);
        rejects(()->new SelectedRootGame(game.gamePackage,"Game","1",1,10,11,0,10123,game.dataDir,
                Collections.singletonList(A),Collections.singletonList(B),B,game.apkHashes,false),"provenance");
        byte[] valid=reply(okay(game));FakeProcess success=new FakeProcess(valid,0);AtomicInteger launches=new AtomicInteger();
        RootProcessTransport transport=new RootProcessTransport("/data/app/self/base.apk",cmd->{
            launches.incrementAndGet();check(cmd.length==3&&cmd[0].equals("su"),"fixed su args");
            check(!cmd[2].contains(game.gamePackage)&&!cmd[2].contains(game.dataDir),"no target shell interpolation");return success;},1000);
        RootProtocol.Request request=new RootProtocol.Request(RootProtocol.Command.PROBE,game.binding());
        check(transport.exchange(request,null).status==RootProtocol.Status.OK,"valid response");
        check(RootProtocol.readRequest(new ByteArrayInputStream(success.written.toByteArray())).binding.matchesInstalled(game.binding()),"binary target binding");
        check(success.destroyed,"process closed");
        rejects(()->new RootProcessTransport("/data/app/self/base.apk",cmd->{throw new IOException("missing su");},100).exchange(request,null),"ROOT_UNAVAILABLE");
        rejects(()->new RootProcessTransport("/data/app/self/base.apk",cmd->new FakeProcess(new byte[0],1),100).exchange(request,null),"ROOT_DENIED_OR_EXITED");
        rejects(()->new RootProcessTransport("/data/app/self/base.apk",cmd->new FakeProcess(Arrays.copyOf(valid,valid.length-2),0),100).exchange(request,null),"ROOT_INVALID_REPLY");
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
        RootGameBridge unauthorized=new RootGameBridge(game,identity,g->verifies.incrementAndGet(),(r,o)->{calls.incrementAndGet();return okayUnchecked(gamet());},()->false,dir.toFile());
        check(unauthorized.mode()==GameAccessBackend.Mode.ROOT_DIRECT,"root mode");
        unauthorized.targetGame();check(calls.get()==0,"target/readiness render never requests root");
        rejects(unauthorized::inspectReady,"授权");check(calls.get()==0,"authorization gates transport");
        unauthorized.probeAuthorization();check(calls.get()==1,"explicit probe only");
        check(!unauthorized.capabilities().restoreSupported&&!unauthorized.capabilities().interruptedBackupRecovery,"readonly caps");
        rejects(()->unauthorized.restoreFrom(dir,Collections.emptyMap()),"尚未实现");
        rejects(unauthorized::recoverInterruptedBackup,"尚未实现");check(calls.get()==1,"unsupported write never launches root");
        RootGameBridge changed=new RootGameBridge(game,identity,g->{if(verifies.incrementAndGet()>0)throw new IOException("changed");},(r,o)->{calls.incrementAndGet();return okayUnchecked(game);},()->true,dir.toFile());
        rejects(changed::targetGame,"ROOT_TARGET_CHANGED");check(calls.get()==1,"target change blocks transport");
        String emptyFingerprint=DirectoryMetadataKt.contentSha256(Collections.emptyMap());
        RootGameBridge wrongBinding=new RootGameBridge(game,identity,g->{},(r,o)->new RootProtocol.Response(
                RootProtocol.Status.OK,A,"EMPTY_DIRECTORY",0,0,emptyFingerprint,"ABSENT",true,null),()->true,dir.toFile());
        rejects(wrongBinding::inspectReady,"ROOT_BINDING_MISMATCH");
        RootGameBridge unknown=new RootGameBridge(game,identity,g->{},(r,o)->new RootProtocol.Response(
                RootProtocol.Status.OK,game.binding().fingerprint(),"EMPTY_DIRECTORY",0,0,emptyFingerprint,"UNKNOWN",false,null),()->true,dir.toFile());
        rejects(unknown::inspectReady,"ROOT_UNRELIABLE");check(!unknown.observeLifecycle().reliable,"unknown lifecycle truthful");
        byte[] emptyArchive=new byte[22];emptyArchive[0]=0x50;emptyArchive[1]=0x4b;emptyArchive[2]=5;emptyArchive[3]=6;
        AtomicInteger exportChecks=new AtomicInteger();
        RootGameBridge emptyExporter=new RootGameBridge(game,identity,g->exportChecks.incrementAndGet(),(r,o)->{
            o.write(emptyArchive);return new RootProtocol.Response(RootProtocol.Status.OK,game.binding().fingerprint(),
                    "EMPTY_DIRECTORY",0,0,emptyFingerprint,"ABSENT",true,emptyArchive);
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
                    "ALLOWLISTED_FILES_PRESENT",1,save.length,saveFingerprint,"CACHED",true,archiveBytes);
        },()->true,dir.toFile());
        Path nonempty=Files.createDirectory(dir.resolve("nonempty-destination"));exporter.exportTo(nonempty);
        check(Arrays.equals(Files.readAllBytes(nonempty.resolve("game0/game.dat")),save),"nonempty export preserves all bytes");
        RootGameBridge badFingerprint=new RootGameBridge(game,identity,g->{},(r,o)->{
            o.write(archiveBytes);return new RootProtocol.Response(RootProtocol.Status.OK,game.binding().fingerprint(),
                    "ALLOWLISTED_FILES_PRESENT",1,save.length,A,"CACHED",true,archiveBytes);
        },()->true,dir.toFile());
        rejects(()->badFingerprint.exportTo(Files.createDirectory(dir.resolve("bad-fingerprint"))),"ROOT_EXPORT_MISMATCH");
        AtomicInteger authorizationReads=new AtomicInteger();
        RootGameBridge revoked=new RootGameBridge(game,identity,g->{},(r,o)->okayUnchecked(game),()->authorizationReads.incrementAndGet()==1,dir.toFile());
        rejects(revoked::inspectReady,"ROOT_CANCELLED");
        AtomicInteger changedChecks=new AtomicInteger();
        RootGameBridge changedAfter=new RootGameBridge(game,identity,g->{if(changedChecks.incrementAndGet()==3)throw new IOException("target changed");},(r,o)->{
            o.write(emptyArchive);return new RootProtocol.Response(RootProtocol.Status.OK,game.binding().fingerprint(),
                    "EMPTY_DIRECTORY",0,0,emptyFingerprint,"ABSENT",true,emptyArchive);
        },()->true,dir.toFile());
        rejects(()->changedAfter.exportTo(Files.createDirectory(dir.resolve("changed-after"))),"ROOT_TARGET_CHANGED");
        RootGameBridge truncatedArchive=new RootGameBridge(game,identity,g->{},(r,o)->{
            o.write(new byte[22]);return new RootProtocol.Response(RootProtocol.Status.OK,game.binding().fingerprint(),
                    "EMPTY_DIRECTORY",0,0,emptyFingerprint,"ABSENT",true,emptyArchive);
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
}
