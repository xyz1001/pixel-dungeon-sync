"""Execute the real MainActivity receipt path without a device, account, or server."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / "main/java/io/github/xyz1001/pdsync/android"
MAIN = (ROOT / "MainActivity.java").read_text()


class RestoreReceiptExecution(unittest.TestCase):
    def test_receipt_skips_reexport_and_stale_results_never_publish(self):
        methods = []
        for signature in (
            "static final class OperationOutcome", "private static final class OperationReceiptContext",
            "private static final class OperationReceipt {",
            "private OperationReceiptContext captureOperationReceiptContext(",
            "private boolean currentOperationReceipt(", "private boolean isStaleOperationReceipt(",
            "private boolean prepareOperationReceipt(", "private boolean applyOperationReceipt(",
            "OperationOutcome m54xc977a20(", "void m55x8211a061(",
        ):
            start = MAIN.index(signature)
            methods.append(MAIN[start:MAIN.index("{", start)] + body(MAIN, signature))
        fixture = r"""
package io.github.xyz1001.pdsync.android;
import java.io.IOException;
import java.util.*;
public class MainActivity {
    enum DiagnosticAction { RESTORE, RESOLVE, PUSH }
    DiagnosticAction activeWorkAction=DiagnosticAction.RESTORE;
    enum DiagnosticFailure { NONE, FAILED }
    enum DiagnosticStage { VERIFY, REFRESH }
    enum DiagnosticResult { CONFLICT, PENDING, SUCCEEDED, REJECTED }
    enum OnboardingProgress { COMPLETE, PENDING }
    enum OnboardingKind { IDENTICAL, BRANCHED, UNKNOWN }
    enum SyncPage { DISCOVERY, CONNECTION, HOME, CONFLICT }
    interface Task { Object run() throws Exception; }
    interface Action { void run() throws Exception; }
    interface Change { void run(MainActivity activity); }
    static class SecureConnectionStore { static class Connection {} }
    static class AndroidSyncFacade {
        static class Inspection { boolean recovery; boolean getNeedsRestoreRecovery() { return recovery; } }
        static class OperationResult {
            final Inspection refreshed; boolean complete = true, pending, conflict;
            OperationResult(Inspection refreshed) { this.refreshed = refreshed; }
            boolean isConflict(){return conflict;} boolean isPending(){return pending;}
            Inspection getRefreshedInspection(){return refreshed;}
            boolean getConfigurationComplete(){return complete;}
        }
        int historyReads, completions; long epoch; Action historyHook = () -> {};
        long observationGeneration(){return epoch;}
        SyncHistory receiptHistory = new SyncHistory();
        SyncHistory historyPresentation(Inspection inspection) throws Exception { historyReads++; historyHook.run(); return receiptHistory; }
        void completeOnboarding(Object inspection){completions++;}
    }
    static class Snapshot { String getId(){return "target";} }
    static class AppendCommit { Snapshot getSnapshot(){return new Snapshot();} }
    static class SyncHistory {
        Object getBaselineCommit(){return new Object();}
        List<AppendCommit> getCloud(){return List.of(new AppendCommit());}
        String getDefaultSnapshot(){return "target";}
    }
    static class GamePreparationService { static class PreparedPair { boolean legacyUnboundHelper; } }
    static class InstallProgressPolicy { static boolean requiresRebindAfterLegacyRecovery(boolean legacy,boolean journal){return legacy && journal;} }
    static class InstallationReadiness {
        boolean restorePending;
        String statusDescription(){return "ready";}
        static InstallationReadiness checking(boolean a,boolean b){return new InstallationReadiness();}
    }
    static class Installer { boolean hasActiveRequest(){return false;} }
    static class DiagnosticLog { static void record(Object... args){} static void failure(Object... args){} }
    static class AndroidDiagnosticLogging {
        static DiagnosticFailure classify(Exception e){return DiagnosticFailure.FAILED;}
        static DiagnosticFailure readinessFailure(Object a,Object b){return DiagnosticFailure.FAILED;}
    }
    static class SyncRuntime {
        static Object account;
        static void setOnboardingProgress(MainActivity activity,Object account,OnboardingProgress progress){activity.markers++;activity.stored=progress;}
        static OnboardingProgress onboardingProgress(MainActivity activity,Object account){return activity.stored;}
    }
    static class Message { String text(){return " / latest state unavailable";} }
    static final Message LEGACY_RESTORE_RECORDS_HANDLED_PREPARE_THE_171=new Message(), EXISTING_HISTORY_AND_PENDING_UPLOADS_ARE_172=new Message(),
      A_SAVE_CONFLICT_WAS_FOUND_BRANCHES_173=new Message(), THE_OPERATION_RETAINED_A_PENDING_UPLOAD_174=new Message(), THE_LATEST_STATE_HAS_NOT_BEEN_175=new Message();
    GamePreparationService.PreparedPair prepared;
    InstallationReadiness readinessState=new InstallationReadiness(); Installer installer=new Installer();
    Object rootGame,bridgeAccessCode,homeObservationOwner;
    SecureConnectionStore.Connection connection=new SecureConnectionStore.Connection();
    AndroidSyncFacade.Inspection inspection=new AndroidSyncFacade.Inspection();
    AndroidSyncFacade facade=new AndroidSyncFacade(); SyncHistory history=new SyncHistory();
    boolean cloudVerified=true,onboardingActive,destroyed,journal,unavailable;
    long generation=1;String endpoint="endpoint",username="user",profile="profile",selection="selection",namespace="namespace";
    OnboardingProgress progress=OnboardingProgress.COMPLETE,stored=OnboardingProgress.COMPLETE; SyncPage page=SyncPage.HOME;
    String notice="unchanged notice",details="unchanged details",failure,selected="old",explicitHomeSelection="old";
    int reads,mutations,markers;
    MainActivity(){SyncRuntime.account=connection;}
    boolean isDestroyed(){return destroyed;}
    OfficialOperationLease.Binding officialOperationBinding(SecureConnectionStore.Connection account) throws Exception {
        if(unavailable || connection!=account || SyncRuntime.account!=account)throw new IOException("changed");
        return new OfficialOperationLease.Binding(account,generation,endpoint,username,profile,selection,namespace);
    }
    Object currentHomeContext(int token){return new Object();}
    boolean readyForSaves(){return true;} boolean hasAnyRestoreJournal(){return journal;}
    void readInstallationReadiness(){} void checkRootAccess(){} void reportError(Exception e){}
    String safeText(String s){return s;} String safeError(Exception e){return e.getMessage();}
    Object diagnosticWorkDetails(){return null;} OnboardingKind kind(){return journal?OnboardingKind.UNKNOWN:OnboardingKind.IDENTICAL;}
    AndroidSyncFacade sync(){return facade;}
    void readComparison(boolean prepare, boolean preview) throws IOException {
        check(!prepare && !preview,"restore fallback must be full but cloud read-only");reads++;
        if(failure!=null)throw new IOException(failure);
        if(journal){cloudVerified=false;history=null;}
    }
    /* METHODS */
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static AndroidSyncFacade.OperationResult result(){return new AndroidSyncFacade.OperationResult(new AndroidSyncFacade.Inspection());}
    OperationOutcome run(AndroidSyncFacade.OperationResult result) throws Exception {
        return m54xc977a20(()->{mutations++;return result;});
    }
    static void unchanged(MainActivity a,AndroidSyncFacade.Inspection oldInspection,SyncHistory oldHistory){
        check(a.inspection==oldInspection && a.history==oldHistory && "unchanged notice".equals(a.notice)
                && "unchanged details".equals(a.details),"stale callback overwrote current UI");
    }
    public static void main(String[] args) throws Exception {
        MainActivity a=new MainActivity();AndroidSyncFacade.OperationResult result=result();
        AndroidSyncFacade.Inspection oldInspection=a.inspection;SyncHistory oldHistory=a.history;
        OperationOutcome receipt=a.run(result);
        check(a.mutations==1 && a.reads==0 && a.facade.completions==0 && a.facade.historyReads==1 && a.markers==1,
                "valid receipt repeated export/full inspect/completion");
        unchanged(a,oldInspection,oldHistory);
        a.m55x8211a061("restore succeeded",receipt);
        check(a.inspection==result.refreshed && a.history==a.facade.receiptHistory && a.cloudVerified
                && "target".equals(a.selected) && a.explicitHomeSelection==null && "restore succeeded".equals(a.notice),
                "paired receipt did not publish");
        MainActivity noMarker=new MainActivity();AndroidSyncFacade.OperationResult incomplete=result();incomplete.complete=false;
        noMarker.run(incomplete);check(noMarker.markers==0 && noMarker.reads==0,"receipt fabricated completion");
        MainActivity fallback=new MainActivity();fallback.run(new AndroidSyncFacade.OperationResult(null));
        check(fallback.reads==1 && fallback.facade.completions==0,"restore fallback published onboarding");
        MainActivity conflicted=new MainActivity();AndroidSyncFacade.OperationResult conflict=result();conflict.conflict=true;
        OperationOutcome conflictReceipt=conflicted.run(conflict);
        check(conflicted.reads==0 && conflicted.facade.historyReads==1 && conflicted.markers==0
                && conflicted.facade.completions==0,"verified conflict repeated full inspect/export or marked completion");
        conflicted.m55x8211a061("restored with concurrent branch",conflictReceipt);
        check(conflicted.inspection==conflict.refreshed && conflicted.history==conflicted.facade.receiptHistory
                && conflicted.page==SyncPage.CONFLICT,"verified conflict receipt hid the existing branch-choice state");
        for(int reason=0;reason<3;reason++){
            MainActivity guarded=new MainActivity();AndroidSyncFacade.OperationResult guardedResult=result();
            if(reason==0)guardedResult.pending=true;
            if(reason==1)guardedResult.refreshed.recovery=true;
            if(reason==2)guarded.journal=true;
            guarded.run(guardedResult);
            check(guarded.reads==1 && guarded.facade.historyReads==0,"receipt bypassed pending/recovery gate");
        }
        MainActivity legacy=new MainActivity();legacy.prepared=new GamePreparationService.PreparedPair();
        legacy.prepared.legacyUnboundHelper=true;legacy.journal=true;
        OperationOutcome upgraded=legacy.run(result());
        check(upgraded.requiresBridgeUpgrade && legacy.reads==0 && legacy.facade.historyReads==0,"receipt bypassed legacy recovery handling");
        for(Change change:new Change[]{
            x->x.connection=new SecureConnectionStore.Connection(),x->SyncRuntime.account=new Object(),
            x->x.generation++,x->x.endpoint="changed",x->x.username="changed",x->x.profile="changed",
            x->x.selection="changed",x->x.namespace="changed",x->x.facade=new AndroidSyncFacade(),
            x->x.destroyed=true,x->x.unavailable=true}) {
            MainActivity late=new MainActivity();AndroidSyncFacade.Inspection before=late.inspection;SyncHistory history=late.history;
            OperationOutcome out=late.run(result());change.run(late);late.m55x8211a061("old success",out);
            unchanged(late,before,history);
        }
        for(boolean duringHistory:new boolean[]{false,true}){
            MainActivity stale=new MainActivity();AndroidSyncFacade.Inspection before=stale.inspection;SyncHistory history=stale.history;
            if(duringHistory)stale.facade.historyHook=()->stale.generation++;
            OperationOutcome out=stale.m54xc977a20(()->{if(!duringHistory)stale.generation++;return result();});
            check(out.stale && stale.reads==0 && stale.markers==0,"stale worker refreshed/re-marked another scope");
            stale.m55x8211a061("old success",out);unchanged(stale,before,history);
        }
        MainActivity newer=new MainActivity();OperationOutcome older=newer.run(result());
        AndroidSyncFacade.Inspection newerInspection=newer.inspection;SyncHistory newerHistory=newer.history;
        newer.facade.epoch++;newer.m55x8211a061("old success",older);unchanged(newer,newerInspection,newerHistory);
        MainActivity racing=new MainActivity();racing.facade.historyHook=()->racing.facade.epoch++;
        OperationOutcome raced=racing.run(result());
        check(raced.stale && racing.markers==0 && raced.receipt==null,"same-facade stale worker published receipt");
        MainActivity readFailure=new MainActivity();readFailure.facade.historyHook=()->{throw new IOException("refresh unavailable");};
        OperationOutcome failed=readFailure.run(result());
        check(readFailure.mutations==1 && failed.refreshFailure!=null && !readFailure.cloudVerified
                && readFailure.inspection==null && readFailure.history==null,"receipt error erased recovery outcome or retained proof");
        readFailure.m55x8211a061("restore succeeded",failed);
        check(readFailure.notice.startsWith("restore succeeded") && readFailure.details.equals("refresh unavailable"),"refresh error lost existing success warning");
        MainActivity actualFailure=new MainActivity();
        try{actualFailure.m54xc977a20(()->{throw new IOException("restore failed");});throw new AssertionError();}
        catch(IOException expected){}
        check(actualFailure.reads==0 && actualFailure.facade.historyReads==0,"failed restore consumed receipt");
        System.out.println("Restore receipt execution checks passed");
    }
}
"""
        with tempfile.TemporaryDirectory(prefix="pdsync-restore-receipt-") as tmp:
            source = Path(tmp) / "MainActivity.java"
            source.write_text(fixture.replace("/* METHODS */", "\n".join(methods)))
            java = os.environ.get("JAVA_BIN", "java")
            compiled = subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", tmp,
                                       str(source), str(ROOT / "OfficialOperationLease.java")],
                                      capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            run = subprocess.run([java, "-cp", tmp, "io.github.xyz1001.pdsync.android.MainActivity"],
                                 capture_output=True, text=True)
            self.assertEqual(0, run.returncode, run.stderr)
