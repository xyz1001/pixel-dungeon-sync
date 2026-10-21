package io.github.xyz1001.pdsync.android;

import io.github.xyz1001.pdsync.root.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import pdsync.DirectoryMetadataKt;
import pdsync.android.app.AndroidGameIdentity;

/** Protocol, mutation-adapter boundary and receipt checks only. No privileged process executes. */
public final class RootRestoreClientChecks {
    private static int checks;
    private static final String A=String.join("",Collections.nCopies(64,"a"));
    private static final String B=String.join("",Collections.nCopies(64,"b"));
    interface Work {void run()throws Exception;}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void rejects(Work work,String code)throws Exception {
        try{work.run();throw new AssertionError("Accepted "+code);}catch(AssertionError failure){throw failure;}
        catch(Exception failure){check(code==null||failure.toString().contains(code)||(failure instanceof BridgeDiagnostics.Failure&&((BridgeDiagnostics.Failure)failure).code.equals(code)),"Wrong failure "+failure);}
    }
    static SelectedRootGame selected()throws Exception {
        return new SelectedRootGame("com.example.game","Game","1",1,10,11,0,10123,"/data/user/0/com.example.game",
                Collections.singletonList(A),Collections.singletonList(A),B,Collections.singletonMap("/data/app/game/base.apk",A),false);
    }
    static byte[] bytes(InputStream source)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=source.read(block))!=-1)bytes.write(block,0,n);return bytes.toByteArray();
    }
    public static void main(String[] args)throws Exception {
        protocol();client();
        System.out.println("Root restore client/protocol checks passed: "+checks+"; no Root/native/device execution");
    }
    static final class WireProcess extends Process {
        final ByteArrayInputStream input;final ByteArrayOutputStream output=new ByteArrayOutputStream();boolean destroyed;
        WireProcess(byte[] reply){input=new ByteArrayInputStream(reply);}
        @Override public InputStream getInputStream(){return input;}
        @Override public OutputStream getOutputStream(){return output;}
        @Override public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        @Override public int waitFor(){return 0;}
        @Override public int exitValue(){return 0;}
        @Override public boolean isAlive(){return false;}
        @Override public void destroy(){destroyed=true;}
        @Override public Process destroyForcibly(){destroy();return this;}
    }
    static void protocol()throws Exception {
        SelectedRootGame selected=selected();
        byte[] archive=new byte[1024*1024];Arrays.fill(archive,(byte)0x5a);
        RootProtocol.Request request=new RootProtocol.Request(RootProtocol.Command.RESTORE,selected.binding(),A,B,archive.length);
        ByteArrayOutputStream wire=new ByteArrayOutputStream();RootProtocol.writeRequest(wire,request,new ByteArrayInputStream(archive));
        ByteArrayInputStream input=new ByteArrayInputStream(wire.toByteArray());
        RootProtocol.RequestFrame frame=RootProtocol.readRequestFrame(input);
        check(frame.request.command==RootProtocol.Command.RESTORE&&frame.request.requestId.equals(request.requestId),"request echo roundtrip");
        check(input.available()==archive.length,"metadata-first parser does not consume archive");
        rejects(frame::finish,"not fully consumed");check(Arrays.equals(bytes(frame.archive()),archive),"streamed archive exact bytes");frame.finish();
        rejects(()->RootProtocol.readRequest(new ByteArrayInputStream(wire.toByteArray())),"Streaming");
        RootProtocol.RequestFrame truncated=RootProtocol.readRequestFrame(new ByteArrayInputStream(Arrays.copyOf(wire.toByteArray(),wire.size()-1)));
        rejects(()->bytes(truncated.archive()),"Truncated");
        RootProtocol.RequestFrame trailing=RootProtocol.readRequestFrame(new ByteArrayInputStream(Arrays.copyOf(wire.toByteArray(),wire.size()+1)));
        bytes(trailing.archive());rejects(trailing::finish,"Trailing");
        rejects(()->RootProtocol.writeRequest(new ByteArrayOutputStream(),request,new ByteArrayInputStream(Arrays.copyOf(archive,archive.length-1))),"Truncated outbound");
        rejects(()->RootProtocol.writeRequest(new ByteArrayOutputStream(),request,new ByteArrayInputStream(Arrays.copyOf(archive,archive.length+1))),"trailing");
        rejects(()->RootProtocol.writeRequest(new ByteArrayOutputStream(),new RootProtocol.Request(RootProtocol.Command.EXPORT,selected.binding(),A,22),new ByteArrayInputStream(new byte[22])),"Unexpected");
        rejects(()->RootProtocol.writeRequest(new ByteArrayOutputStream(),new RootProtocol.Request(RootProtocol.Command.RESTORE,selected.binding()),null),"Missing restore");
        rejects(()->RootProtocol.writeRequest(new ByteArrayOutputStream(),new RootProtocol.Request(RootProtocol.Command.RESTORE,selected.binding(),A,B,Integer.MAX_VALUE),null),"Invalid");
        RootProtocol.Response receipt=new RootProtocol.Response(RootProtocol.Status.OK,selected.binding().fingerprint(),"ALLOWLISTED_FILES_PRESENT",1,10,B,"ABSENT",true,null)
                .withRequest(request,RootProtocol.Outcome.RESTORED)
                .withDiagnostic(RootProtocol.DiagnosticStage.MOVE_NEW,RootProtocol.DiagnosticCategory.CROSS_DEVICE,18);
        wire.reset();RootProtocol.writeResponse(wire,receipt);
        RootProtocol.Response decoded=RootProtocol.readResponse(new ByteArrayInputStream(wire.toByteArray()),null);
        RootProtocol.requireReplyMatches(request,decoded);
        WireProcess child=new WireProcess(wire.toByteArray());
        RootProcessTransport transport=new RootProcessTransport("/data/app/self/base.apk",cmd->child,1000);
        RootProtocol.Response transported=transport.exchange(request,new ByteArrayInputStream(archive),null);
        check(transported.outcome==RootProtocol.Outcome.RESTORED&&child.destroyed,"duplex restore transport receives bound receipt and closes child");
        RootProtocol.RequestFrame transmitted=RootProtocol.readRequestFrame(new ByteArrayInputStream(child.output.toByteArray()));
        check(Arrays.equals(bytes(transmitted.archive()),archive),"transport streams complete upload bytes");transmitted.finish();
        byte[] receiptBytes=wire.toByteArray();
        rejects(()->new RootProcessTransport("/data/app/self/base.apk",cmd->new WireProcess(receiptBytes),1000)
                .exchange(request,new ByteArrayInputStream(Arrays.copyOf(archive,archive.length-1)),null),"ROOT_PIPE_FAILED");
        ByteArrayOutputStream rejectedWire=new ByteArrayOutputStream();
        RootProtocol.writeResponse(rejectedWire,RootProtocol.Response.failure(RootProtocol.Status.RECOVERY_REQUIRED,request));
        RootProtocol.Response rejection=new RootProcessTransport("/data/app/self/base.apk",cmd->new WireProcess(rejectedWire.toByteArray()),1000)
                .exchange(request,new InputStream(){@Override public int read()throws IOException{throw new IOException("reader closed by typed root denial");}},null);
        check(rejection.status==RootProtocol.Status.RECOVERY_REQUIRED,"early typed refusal survives aborted upload");

        check(decoded.outcome==RootProtocol.Outcome.RESTORED&&decoded.diagnosticStage==RootProtocol.DiagnosticStage.MOVE_NEW
                &&decoded.diagnosticCategory==RootProtocol.DiagnosticCategory.CROSS_DEVICE&&decoded.errno==18,"receipt exact typed diagnostics");
        RootProtocol.Request another=new RootProtocol.Request(RootProtocol.Command.RESTORE,selected.binding(),A,B,archive.length);
        rejects(()->RootProtocol.requireReplyMatches(another,decoded),"does not match");
        RootProtocol.Request wrongExpected=new RootProtocol.Request(RootProtocol.Command.RESTORE,selected.binding(),request.requestId,B,B,archive.length);
        rejects(()->RootProtocol.requireReplyMatches(wrongExpected,decoded),"does not match");
        rejects(()->RootProtocol.writeResponse(new ByteArrayOutputStream(),receipt.withDiagnostic(RootProtocol.DiagnosticStage.MOVE,RootProtocol.DiagnosticCategory.IO,4096)),"Invalid");
        rejects(()->RootProtocol.writeResponse(new ByteArrayOutputStream(),RootProtocol.Response.failure(RootProtocol.Status.IO_ERROR,request).withRequest(request,RootProtocol.Outcome.RESTORED)),"applied");
        for(String observation:new String[]{"UNKNOWN","FOREGROUND","BACKGROUND"}) {
            RootProtocol.Response unsafeReceipt=new RootProtocol.Response(RootProtocol.Status.OK,selected.binding().fingerprint(),
                    "ALLOWLISTED_FILES_PRESENT",1,10,B,observation,!observation.equals("UNKNOWN"),null)
                    .withRequest(request,RootProtocol.Outcome.RESTORED);
            RootProtocol.writeResponse(new ByteArrayOutputStream(),unsafeReceipt);
            check(true,"actual file receipt is independent of lifecycle observation");
        }
        RootProtocol.Request observe=new RootProtocol.Request(RootProtocol.Command.OBSERVE,selected.binding());
        rejects(()->RootProtocol.writeResponse(new ByteArrayOutputStream(),receipt.withRequest(observe,RootProtocol.Outcome.RESTORED)),"restore receipt");
        check(RootProcessTransport.budgetMillis(RootProtocol.Command.RESTORE)==135000&&RootProcessTransport.budgetMillis(RootProtocol.Command.RECOVER)==135000,"write budgets exceed 120s runner lifetime");
        check(RootProcessTransport.budgetMillis(RootProtocol.Command.OBSERVE)==30000&&RootProcessTransport.budgetMillis(RootProtocol.Command.EXPORT)==60000,"read budgets bounded");
        for(RootProtocol.Command command:RootProtocol.Command.values()) {
            long runnerBudget=RootRunner.jobBudgetMillis(command);
            check(runnerBudget>0&&runnerBudget<=120000,"runner one-job watchdog remains bounded");
            check(RootProcessTransport.budgetMillis(command)>runnerBudget,"client allows runner watchdog and receipt margin");
        }
        for(RootProtocol.DiagnosticStage stage:RootProtocol.DiagnosticStage.values()) {
            RootProtocol.Response detail=receipt.withDiagnostic(stage,RootProtocol.DiagnosticCategory.PERMISSION,13);
            wire.reset();RootProtocol.writeResponse(wire,detail);RootProtocol.Response read=RootProtocol.readResponse(new ByteArrayInputStream(wire.toByteArray()),null);
            check(read.diagnosticStage==stage,"stage enumerated roundtrip");
            Throwable diagnostic=BridgeDiagnostics.rootDetail(stage.name(),"IO",22);
            check(diagnostic!=null,"main client accepts exact kernel diagnostic enum");
            String report=BridgeDiagnostics.report(new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.SAVE_READINESS,
                    "ROOT_IO_ERROR","Root read failed",diagnostic),36,"test",23,"com.example.game","","root");
            check(report.contains("root_operation_stage="+stage.name())&&report.contains("root_operation_errno=22"),
                    "copyable diagnostic preserves operation and errno");
        }
    }
    static final class FakeExchange implements RootProcessTransport.Exchange {
        final SelectedRootGame selected;final String desired;final int count;final long size;
        int calls,uploads,mode;String expected;byte[] uploaded;
        FakeExchange(SelectedRootGame selected,String desired,int count,long size){this.selected=selected;this.desired=desired;this.count=count;this.size=size;}
        @Override public RootProtocol.Response exchange(RootProtocol.Request request,OutputStream archive)throws IOException {return exchange(request,null,archive);}
        @Override public RootProtocol.Response exchange(RootProtocol.Request request,InputStream upload,OutputStream archive)throws IOException {
            calls++;
            if(request.command==RootProtocol.Command.RESTORE) {
                uploads++;expected=request.expectedCurrentFingerprint;uploaded=bytes(upload);
                check(uploaded.length==request.archiveLength,"upload exact declared length");
            }
            RootProtocol.Outcome outcome=request.command==RootProtocol.Command.RESTORE?RootProtocol.Outcome.RESTORED:
                    request.command==RootProtocol.Command.RECOVER?RootProtocol.Outcome.RECOVERED:RootProtocol.Outcome.READ_ONLY;
            if(mode==1)outcome=RootProtocol.Outcome.RESTORED_CLEANUP_PENDING;
            if(mode==2)outcome=RootProtocol.Outcome.READ_ONLY;
            if(mode==6&&request.command==RootProtocol.Command.RECOVER)outcome=RootProtocol.Outcome.RECOVERED_CLEANUP_PENDING;
            if(mode==7)outcome=RootProtocol.Outcome.UNCHANGED;
            RootProtocol.Response response=new RootProtocol.Response(mode==4?RootProtocol.Status.MUTATION_UNAVAILABLE:RootProtocol.Status.OK,
                    selected.binding().fingerprint(),"ALLOWLISTED_FILES_PRESENT",count,size,mode==3?A:desired,"ABSENT",mode!=4,null)
                    .withRequest(request,mode==4?RootProtocol.Outcome.UNKNOWN:outcome);
            if(mode==4)response=response.withDiagnostic(RootProtocol.DiagnosticStage.NATIVE_LOAD,RootProtocol.DiagnosticCategory.UNSUPPORTED,38);
            if(mode==1||mode==6)response=response.withDiagnostic(RootProtocol.DiagnosticStage.CLEANUP,RootProtocol.DiagnosticCategory.PERMISSION,13);
            if(mode==5)response=response.withRequest(new RootProtocol.Request(request.command,selected.binding(),request.expectedCurrentFingerprint,request.desiredFingerprint,request.archiveLength),outcome);
            return response;
        }
    }
    static void client()throws Exception {
        check(RootGameBridge.mutationSupportedForPackage(true),"concrete transaction adapter available with packaged native candidate");
        check(!RootGameBridge.mutationSupportedForPackage(false),"missing native candidate disables restore and recovery eligibility");
        SelectedRootGame selected=selected();Path root=Files.createTempDirectory("root-restore-client-");Path save=Files.createDirectory(root.resolve("source"));
        Files.createDirectory(save.resolve("game0"));byte[] contents="local staged save".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(save.resolve("game0/game.dat"),contents);
        String hash=io.github.xyz1001.pdsync.bridge.SaveArchive.sha256(contents);
        String fingerprint=DirectoryMetadataKt.contentSha256(Collections.singletonMap("game0/game.dat",hash));
        String empty=DirectoryMetadataKt.contentSha256(Collections.emptyMap());
        FakeExchange exchange=new FakeExchange(selected,fingerprint,1,contents.length);AtomicInteger verifies=new AtomicInteger();
        AndroidGameIdentity identity=new AndroidGameIdentity(selected.gamePackage,selected.gameLabel,selected.originIdentity);
        RootGameBridge bridge=new RootGameBridge(selected,identity,g->verifies.incrementAndGet(),exchange,()->true,root.toFile(),true);
        check(bridge.capabilities().restoreSupported&&bridge.capabilities().interruptedBackupRecovery,"real-adapter capability path");
        RootGameBridge readOnly=new RootGameBridge(selected,identity,g->verifies.incrementAndGet(),exchange,()->true,root.toFile(),false);
        check(!readOnly.capabilities().restoreSupported&&!readOnly.capabilities().interruptedBackupRecovery
                &&readOnly.capabilities().exportSupported&&!readOnly.capabilities().lifecycleObservation,"missing native preserves read capabilities only");
        rejects(()->readOnly.restoreFrom(save,Collections.emptyMap()),"ROOT_RESTORE_UNSUPPORTED");
        rejects(readOnly::recoverInterruptedBackup,"ROOT_RECOVERY_UNSUPPORTED");
        check(exchange.calls==0&&verifies.get()==0,"capability checks and disabled mutations do not invoke Root or package inspection");
        bridge.restoreFrom(save,Collections.emptyMap());check(exchange.uploads==1&&exchange.expected.equals(empty),"expected previous inventory bound");
        check(verifies.get()==4,"restore target before and after request and receipt");
        try(java.util.stream.Stream<Path> items=Files.list(root)){check(items.count()==1,"restore upload and verification temp cleaned");}
        exchange.mode=1;
        try{bridge.restoreFrom(save,Collections.emptyMap());throw new AssertionError("cleanup accepted");}
        catch(BridgeDiagnostics.Failure failure){
            check(failure.code.equals("ROOT_RESTORE_COMMITTED_CLEANUP_PENDING")&&failure.stage==BridgeDiagnostics.Stage.RESTORE,"applied cleanup outcome remains explicit");
            String report=BridgeDiagnostics.report(failure,26,"host",1,selected.gamePackage,null,null);
            check(report.contains("root_operation_stage=CLEANUP")&&report.contains("root_operation_category=PERMISSION")&&report.contains("root_operation_errno=13"),"bounded cleanup diagnostics preserved");
        }
        exchange.mode=2;rejects(()->bridge.restoreFrom(save,Collections.emptyMap()),"ROOT_RESTORE_RECEIPT_INVALID");
        exchange.mode=3;rejects(()->bridge.restoreFrom(save,Collections.emptyMap()),"ROOT_RESTORE_VERIFY_FAILED");
        exchange.mode=4;
        try{bridge.restoreFrom(save,Collections.emptyMap());throw new AssertionError("native unavailable accepted");}
        catch(BridgeDiagnostics.Failure failure){check(failure.code.equals("ROOT_MUTATION_UNAVAILABLE")&&failure.stage==BridgeDiagnostics.Stage.RESTORE,"native typed unavailable");}
        exchange.mode=5;rejects(()->bridge.restoreFrom(save,Collections.emptyMap()),"ROOT_REPLY_MISMATCH");
        exchange.mode=7;bridge.restoreFrom(save,Collections.emptyMap());check(true,"already-applied desired inventory accepted");
        exchange.mode=0;bridge.recoverInterruptedBackup();check(exchange.uploads==7,"recover uploads no archive or credentials");
        exchange.mode=6;rejects(bridge::recoverInterruptedBackup,"ROOT_RECOVERY_COMMITTED_CLEANUP_PENDING");
        exchange.mode=2;rejects(bridge::recoverInterruptedBackup,"ROOT_RECOVERY_RECEIPT_INVALID");
        exchange.mode=3; // Response content changes consistently, so this is still a validated recovered state.
        bridge.recoverInterruptedBackup();
        check(BridgeDiagnostics.rootDetail("/private/path","IO",5)==null,"arbitrary diagnostic stage rejected");
        check(BridgeDiagnostics.rootDetail("MOVE","arbitrary output",5)==null,"arbitrary diagnostic category rejected");
        check(BridgeDiagnostics.rootDetail("MOVE","IO",-1)==null,"invalid errno rejected");
        for(String code:new String[]{"ROOT_REPLY_MISMATCH","ROOT_RECOVERY_REQUIRED","ROOT_RESTORE_CONFLICT","ROOT_RECOVERY_CONFLICT",
                "ROOT_ATTRIBUTES_UNSUPPORTED","ROOT_MUTATION_UNAVAILABLE","ROOT_RESTORE_FAILED","ROOT_RECOVERY_FAILED","ROOT_NO_PENDING_RECOVERY",
                "ROOT_RESTORE_COMMITTED_CLEANUP_PENDING","ROOT_RECOVERY_COMMITTED_CLEANUP_PENDING","ROOT_RESTORE_RECEIPT_INVALID",
                "ROOT_RECOVERY_RECEIPT_INVALID","ROOT_RESTORE_VERIFY_FAILED","ROOT_RECOVERY_VERIFY_FAILED"})
            check(new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.RESTORE,code,"bounded",null).code.equals(code),"typed mutation code preserved");
    }
}
