"""Newly reconstructed code92 execution checks, using production Java method bodies.

These are host tests with deterministic ports, not Android/device performance measurements.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

HERE = Path(__file__).resolve().parents[1]
MAIN = (HERE.parent / 'main/java/io/github/xyz1001/pdsync/android/MainActivity.java').read_text()


def declaration(signature):
    start = MAIN.index(signature)
    return MAIN[start:MAIN.index('{', start)] + body(MAIN, signature)


class HomeScopeTimingExecution(unittest.TestCase):
    def run_java(self, fixture, methods):
        with tempfile.TemporaryDirectory(prefix='pdsync-home-scope-timing-') as tmp:
            source = Path(tmp) / 'MainActivity.java'
            source.write_text(fixture.replace('/* METHODS */', methods))
            java = os.environ.get('JAVA_BIN', 'java')
            compiled = subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', tmp, str(source)], capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            result = subprocess.run([java, '-cp', tmp, 'MainActivity'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_automatic_history_close_failure_cannot_publish_observation(self):
        refresh = body(MAIN, 'private void drainAutomaticHistoryRefresh()')
        start = refresh.index('AutomaticHistoryOutcome result = new AutomaticHistoryOutcome(ticket, captured);')
        stop = refresh.index('return result;', start) + len('return result;')
        method = 'AutomaticHistoryOutcome read() { Object ticket = new Object(), captured = new Object(); State state = new State();\n' + refresh[start:stop] + '\n}'
        self.run_java(r'''
class MainActivity {
 interface Hook { void run() throws Exception; }
 static class SyncHistory {}
 static class AndroidSyncFacade {
  static class Inspection { String kind="IDENTICAL"; String getKind(){return kind;} boolean getNeedsRestoreRecovery(){return false;} }
  Inspection inspection=new Inspection(); SyncHistory history=new SyncHistory();
  Hook close=()->{}, historyHook=()->{}; int closes;
  AutoCloseable beginHomeReadIdentityScope(){return ()->{closes++;close.run();};}
  Inspection inspectHome(){return inspection;}
  SyncHistory historyPresentation(Inspection observation) throws Exception {historyHook.run();return history;}
 }
 static class AutomaticHistoryOutcome {
  AndroidSyncFacade facade; AndroidSyncFacade.Inspection inspection; SyncHistory history; Exception error;
  AutomaticHistoryOutcome(Object ticket,Object captured){current=this;}
 }
 static AutomaticHistoryOutcome current;
 static class State {AutomaticHistoryOutcome outcome;}
 static class SyncRuntime { static AndroidSyncFacade facade(MainActivity a,Object captured){return a.facade;} }
 enum DiagnosticStage {VERIFY,REFRESH}
 enum DiagnosticFailure {RECOVERY_REQUIRED,NONE}
 static class DiagnosticLog {static void record(Object...args){} static void failure(Object...args){} }
 static class AndroidDiagnosticLogging {static Object inspectionResult(Object...args){return null;} }
 static class SyncInspectionDiagnostics {enum Stage{INSPECT} static Exception at(Stage stage,Exception e){return e;} }
 Object diagnosticInspectionDetails(Object observed){return null;}
 AndroidSyncFacade facade=new AndroidSyncFacade();
 /* METHODS */
 static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
 public static void main(String[] args) throws Exception {
  for(String kind:java.util.List.of("IDENTICAL","BLOCKED")) {
   MainActivity a=new MainActivity(); a.facade.inspection.kind=kind;
   java.io.IOException failure=new java.io.IOException("identity changed at close");
   a.facade.close=()->{
    check(current.inspection==null && current.history==null,"automatic observation published before close");
    throw failure;
   };
   AutomaticHistoryOutcome result=a.read();
   check(result.inspection==null && result.history==null && result.error==failure,"close failure retained automatic proof");
   check(a.facade.closes==1,"identity scope must close once");
  }
  MainActivity a=new MainActivity(); AutomaticHistoryOutcome success=a.read();
  check(success.error==null && success.inspection==a.facade.inspection && success.history==a.facade.history,"valid result lost");
  check(a.facade.closes==1,"success close missing");
  MainActivity blocked=new MainActivity();blocked.facade.inspection.kind="BLOCKED";
  AutomaticHistoryOutcome result=blocked.read();
  check(result.inspection!=null && result.history==null && blocked.facade.closes==1,"blocked close missing");
 }
}
''', method)

    def test_restore_scope_spans_action_full_refresh_and_onboarding(self):
        methods = '\n'.join(declaration(signature) for signature in (
            'private void operation(DiagnosticAction action,',
            'OperationOutcome m54xc977a20(',
        ))
        self.run_java(r"""
class MainActivity {
 enum DiagnosticAction{RESTORE,RESOLVE,PUSH}
 enum DiagnosticStage{VERIFY,REFRESH}
 enum DiagnosticResult{CONFLICT,PENDING,SUCCEEDED,REJECTED}
 enum DiagnosticFailure{NONE,FAILED}
 enum OnboardingProgress{PENDING,COMPLETE}
 enum OnboardingKind{IDENTICAL}
 interface Task<T>{T run() throws Exception;}
 interface Done<T>{void accept(T value) throws Exception;}
 static class AndroidSyncFacade {
  static class OperationResult {boolean isConflict(){return false;}boolean isPending(){return false;}}
  boolean active,expected;int opened,closed,completed;
  <T>T withRestoreReadScope(java.util.concurrent.Callable<T> task) throws Exception {
   check(!active,"scope nested unexpectedly");opened++;active=true;
   try{return task.call();}finally{active=false;closed++;}
  }
  void completeOnboarding(Object observed){check(active==expected,"onboarding outside action scope");completed++;}
 }
 static class OperationOutcome{String refreshFailure;DiagnosticFailure refreshDiagnosticFailure=DiagnosticFailure.NONE;boolean requiresBridgeUpgrade;AndroidSyncFacade.OperationResult result;}
 static class SyncHistory{Object getBaselineCommit(){return new Object();}}
 static class GamePreparationService{static class PreparedPair{boolean legacyUnboundHelper;}}
 static class InstallProgressPolicy{static boolean requiresRebindAfterLegacyRecovery(boolean a,boolean b){return false;}}
 static class Readiness{boolean restorePending;String statusDescription(){return "ready";}}
 static class DiagnosticLog{static void record(Object...args){}static void failure(Object...args){}}
 static class AndroidDiagnosticLogging{static DiagnosticFailure classify(Exception e){return DiagnosticFailure.FAILED;}static DiagnosticFailure readinessFailure(Object...args){return DiagnosticFailure.FAILED;}}
 static class SyncRuntime{static void setOnboardingProgress(Object...args){}}
 Object confirmedAction,confirmationMerge,rootGame,connection,inspection=new Object(),bridgeAccessCode;
 GamePreparationService.PreparedPair prepared;Readiness readinessState=new Readiness();SyncHistory history=new SyncHistory();
 AndroidSyncFacade facade=new AndroidSyncFacade();boolean cloudVerified=true,failRefresh;int reads;OperationOutcome outcome;
 boolean readyForSaves(){return true;}boolean hasAnyRestoreJournal(){return false;}
 void readInstallationReadiness(){}void checkRootAccess(){}void reportError(Exception e){}
 String safeText(String value){return value;}String safeError(Exception e){return e.getMessage();}
 Object diagnosticWorkDetails(){return null;}OnboardingKind kind(){return OnboardingKind.IDENTICAL;}
 AndroidSyncFacade sync(){return facade;}
 void readComparison(boolean prepare,boolean preview) throws Exception{
  check(prepare && !preview,"post-action used metadata preview");
  check(facade.active==facade.expected,"post-action read outside scope");reads++;
  if(failRefresh)throw new java.io.IOException("refresh unavailable");
 }
 <T>void work(DiagnosticAction action,String label,Task<T> task,Done<T> done){try{done.accept(task.run());}catch(Exception e){throw new RuntimeException(e);}}
 void m55x8211a061(String label,OperationOutcome result){outcome=result;}
 /* METHODS */
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  for(DiagnosticAction action:DiagnosticAction.values())for(boolean failed:new boolean[]{false,true}){
   MainActivity a=new MainActivity();a.facade.expected=action!=DiagnosticAction.PUSH;a.failRefresh=failed;
   a.operation(action,"existing progress",()->{check(a.facade.active==a.facade.expected,"restore action outside scope");return new AndroidSyncFacade.OperationResult();},"existing success");
   check(a.reads==1,"post-action read missing");
   check(a.facade.opened==(a.facade.expected?1:0) && a.facade.closed==a.facade.opened && !a.facade.active,"scope leaked");
   check(a.facade.completed==(failed?0:1),"onboarding completion mismatch");
   if(failed)check(a.outcome.refreshFailure!=null && a.history==null && a.inspection==null && !a.cloudVerified,"failed refresh retained proof");
  }
 }
}
""", methods)

    def test_finished_record_waits_for_ui_phase_on_success_error_and_destroy(self):
        methods = '\n'.join(declaration(signature) for signature in (
            'void m150lambda$work$2$iogithubxyz1001pdsyncandroidMainActivity(',
            'void m148lambda$work$0$iogithubxyz1001pdsyncandroidMainActivity(',
            'void m149lambda$work$1$iogithubxyz1001pdsyncandroidMainActivity(',
        ))
        self.run_java(r'''
import java.util.*;
class MainActivity {
 enum DiagnosticAction{INSPECT,LAUNCH}
 static class OfficialOperationLease implements AutoCloseable { public void close(){} }
 final ThreadLocal<OfficialOperationLease> officialOperationLease = new ThreadLocal<>();
 void invalidatePreparedOfficialNamespace(){}
 enum DiagnosticStage{WORK,FINISHED,GAME_LAUNCH,REFRESH}
 enum DiagnosticResult{STARTED,SUCCEEDED}
 enum DiagnosticFailure{NONE}
 enum DiagnosticPhase{OPERATION_LOCK_WAIT,UI_COMPLETION}
 static class DiagnosticDetails{}
 interface Task {Object run() throws Exception;}
 interface Done {void accept(Object result) throws Exception;}
 static class DiagnosticTrace {
  DiagnosticAction getAction(){return DiagnosticAction.INSPECT;}
  List<DiagnosticTiming.Scope> scopes=new ArrayList<>();int finished;boolean failed;
  void record(DiagnosticStage stage,Object...args){
   if(stage==DiagnosticStage.FINISHED){
    check(scopes.stream().allMatch(s->s.closed),"FINISHED before timing scope close");finished++;
   }
  }
 }
 static class DiagnosticTiming {
  static class Scope implements AutoCloseable {
   boolean closed, failed, canceled; Scope(DiagnosticTrace trace){trace.scopes.add(this);}
   public void close(){closed=true;} void failed(Throwable e){failed=true;close();} void canceled(){canceled=true;close();}
  }
  static Scope start(DiagnosticTrace trace,DiagnosticPhase phase){return new Scope(trace);}
 }
 static class AndroidDiagnosticLogging {
  static class Scope implements AutoCloseable{public void close(){}}
  static Scope bind(DiagnosticTrace trace){return new Scope();}
  static DiagnosticStage stage(DiagnosticAction action){return DiagnosticStage.WORK;}
  static void failure(DiagnosticTrace trace,DiagnosticStage stage,Throwable e){trace.failed=true;trace.record(stage);}
 }
 static class SyncRuntime{static final Object OPERATION_LOCK=new Object();}
 static class StaleArchiveReadException extends Exception{}
 static class Message{String text(){return "unavailable";}}
 static final Message COULD_NOT_READ_CONNECTION_SETTINGS_PLEASE_2=new Message();
 boolean destroyed,busy=true,sharedReloadPending,renderFails;String connectionLoadError;int rendered,errors,done;
 List<Runnable> queued=new ArrayList<>();
 boolean isDestroyed(){return destroyed;}
 void runOnUiThread(Runnable action){queued.add(action);}
 DiagnosticResult diagnosticWorkResult(DiagnosticAction action,Object value){return DiagnosticResult.SUCCEEDED;}
 DiagnosticFailure diagnosticWorkFailure(DiagnosticAction action,Object value,DiagnosticResult result){return DiagnosticFailure.NONE;}
 DiagnosticDetails diagnosticWorkDetails(){return new DiagnosticDetails();}
 void reportError(Exception error){errors++;}
 void render(){rendered++;if(renderFails)throw new IllegalStateException("render failed");}
 void rememberCurrentAutomaticHistory(){} void drainReadinessRefresh(){} void drainRenewalArchiveRead(){}
 void drainAutomaticHistoryRefresh(){} void drainExternalInstallation(){}
 /* METHODS */
 static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
 public static void main(String[] args) throws Exception {
  for(int scenario=0;scenario<5;scenario++) {
   MainActivity a=new MainActivity();DiagnosticTrace trace=new DiagnosticTrace();final int value=scenario;
   a.m150lambda$work$2$iogithubxyz1001pdsyncandroidMainActivity(()->{
    check(trace.scopes.get(0).closed,"lock wait not closed before work");
    if(value==1)throw new java.io.IOException("read failed");return "ok";
   },result->{a.done++;if(value==2)throw new java.io.IOException("callback failed");},trace);
   check(trace.finished==0 && a.queued.size()==1,"finished before queued UI callback");
   if(scenario==3)a.destroyed=true;if(scenario==4)a.renderFails=true;
   try{a.queued.remove(0).run();if(scenario==4)throw new AssertionError("render error swallowed");}
   catch(IllegalStateException failure){check(scenario==4,"unexpected failure");}
   check(trace.finished==1,"FINISHED must occur exactly once");
   check(trace.scopes.stream().allMatch(s->s.closed),"timing scope leaked");
   if(scenario==0)check(!trace.failed && a.done==1 && !a.busy,"successful completion changed");
   if(scenario==1 || scenario==2 || scenario==4)check(trace.failed,"failure recorded as success");
   if(scenario==3)check(trace.scopes.get(1).canceled && a.done==0,"destroyed UI not canceled");
  }
 }
}
''', methods)
        work = body(MAIN, 'private <T> void work(DiagnosticAction action, String str, final Task<T> task, final Done<T> done, boolean preserveAutomaticHistory)')
        self.assertLess(work.index('DiagnosticPhase.QUEUE_WAIT'), work.index('this.f9io.execute'))
        self.assertLess(work.index('queueWait.close()'), work.index('MainActivity.this.m150lambda$work$2'))


if __name__ == '__main__':
    unittest.main()
