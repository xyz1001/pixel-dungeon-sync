"""Execute production operation/result handlers: cloud refresh cannot erase local recovery success."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

MAIN = (Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()

class LocalRecoveryOutcomeExecution(unittest.TestCase):
    def test_completed_local_result_survives_expiry_and_offline_refresh(self):
        methods = 'static final class OperationOutcome ' + body(MAIN, 'static final class OperationOutcome')
        methods += '\nOperationOutcome m54xc977a20(Task task) throws Exception ' + body(MAIN, 'OperationOutcome m54xc977a20(')
        methods += '\nvoid m55x8211a061(String str, OperationOutcome operationOutcome) throws Exception ' + body(MAIN, 'void m55x8211a061(')
        fixture = r'''
import java.io.IOException;
class Check {
    enum DiagnosticFailure { NONE, FAILED }
    enum DiagnosticStage { VERIFY, REFRESH }
    enum DiagnosticResult { CONFLICT, PENDING, SUCCEEDED, REJECTED }
    enum OnboardingProgress { COMPLETE, PENDING }
    enum OnboardingKind { IDENTICAL, BRANCHED, UNKNOWN }
    enum SyncPage { DISCOVERY, CONNECTION, HOME, CONFLICT }
    interface Task { Object run() throws Exception; }
    static class AndroidSyncFacade {
        static class OperationResult { boolean isConflict(){return false;} boolean isPending(){return false;} }
        void completeOnboarding(Object i){}
    }
    static class SyncHistory { Object getBaselineCommit(){return null;} }
    static class GamePreparationService { static class PreparedPair { boolean legacyUnboundHelper; } }
    static class InstallProgressPolicy { static boolean requiresRebindAfterLegacyRecovery(boolean a,boolean b){return false;} }
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
        static void setOnboardingProgress(Object a,Object b,Object c){}
        static OnboardingProgress onboardingProgress(Object a,Object b){return OnboardingProgress.COMPLETE;}
    }
    static class Message { String text(){return " / latest state unavailable";} }
    static final Message LEGACY_RESTORE_RECORDS_HANDLED_PREPARE_THE_171=new Message(), EXISTING_HISTORY_AND_PENDING_UPLOADS_ARE_172=new Message(),
      A_SAVE_CONFLICT_WAS_FOUND_BRANCHES_173=new Message(), THE_OPERATION_RETAINED_A_PENDING_UPLOAD_174=new Message(), THE_LATEST_STATE_HAS_NOT_BEEN_175=new Message();
    GamePreparationService.PreparedPair prepared;
    InstallationReadiness readinessState=new InstallationReadiness(); Installer installer=new Installer();
    Object rootGame, connection, inspection=new Object(), bridgeAccessCode;
    AndroidSyncFacade facade=new AndroidSyncFacade(); SyncHistory history=new SyncHistory();
    boolean cloudVerified=true,onboardingActive;
    OnboardingProgress progress=OnboardingProgress.COMPLETE; SyncPage page=SyncPage.HOME;
    String notice,details,failure; int reads,mutations;
    boolean readyForSaves(){return true;} boolean hasAnyRestoreJournal(){return false;}
    void readInstallationReadiness(){} void checkRootAccess(){} void reportError(Exception e){}
    String safeText(String s){return s;} String safeError(Exception e){return e.getMessage();}
    Object diagnosticWorkDetails(){return null;} OnboardingKind kind(){return OnboardingKind.UNKNOWN;}
    AndroidSyncFacade sync(){return facade;}
    void readComparison() throws IOException {reads++;if(failure!=null)throw new IOException(failure);}
    /* METHODS */
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args) throws Exception {
        for(String failure:new String[]{"service expired","network offline"}){
            Check app=new Check();app.failure=failure;
            AndroidSyncFacade.OperationResult result=new AndroidSyncFacade.OperationResult();
            OperationOutcome out=app.m54xc977a20(()->{app.mutations++;return result;});
            check(out.result==result && out.refreshFailure.equals(failure));
            check(out.refreshDiagnosticFailure==DiagnosticFailure.FAILED && !app.cloudVerified && app.inspection==null && app.history==null);
            app.m55x8211a061("restore succeeded",out);
            check(app.notice.startsWith("restore succeeded") && app.details.equals(failure));
            check(app.mutations==1 && app.reads==1);
        }
        Check actualFailure=new Check();
        try{actualFailure.m54xc977a20(()->{throw new IOException("restore failed");});throw new AssertionError();}catch(IOException expected){}
        check(actualFailure.reads==0);
        Check success=new Check(); OperationOutcome out=success.m54xc977a20(()->new AndroidSyncFacade.OperationResult());
        check(out.refreshFailure==null && success.reads==1);
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='pdsync-recovery-outcome-') as tmp:
            source = Path(tmp) / 'Check.java'; source.write_text(fixture.replace('/* METHODS */', methods))
            java = os.environ.get('JAVA_BIN', 'java')
            compiled = subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', tmp, str(source)], capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            run = subprocess.run([java, '-cp', tmp, 'Check'], capture_output=True, text=True)
            self.assertEqual(0, run.returncode, run.stderr)
