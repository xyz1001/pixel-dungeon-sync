"""Execute the real detached HOME queue against deterministic JVM ports, without a device/server."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body
SRC = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (SRC / 'MainActivity.java').read_text()
SIGNATURES = ('private static final class DetachedHomeRead {', 'private static final class DetachedHomeOutcome {',
 'private void cancelDetachedHomeRead(', 'private void requireDetachedHomeRead(', 'private boolean startDetachedHomeRead(',
 'private boolean startDetachedHomeRead(SecureConnectionStore.Connection account, boolean prepareNamespace,\n                                         boolean checkReadiness, AutoSyncHistoryRefresh.Ticket automatic, boolean fullHistory)', 'private void checkDetachedRootReadiness(', 'private void finishDetachedHomeRead(')

class DetachedHomeQueueExecution(unittest.TestCase):
 def test_real_queue_network_lock_lifetime_cancellation_identity_and_live_access(self):
  methods=[]
  for sig in SIGNATURES:
   start=MAIN.index(sig);methods.append(MAIN[start:MAIN.index('{',start)]+body(MAIN,sig))
  fixture=r'''
package io.github.xyz1001.pdsync.android;
import java.util.*;import java.util.concurrent.*;import java.io.IOException;
public class MainActivity {
 enum DiagnosticAction {INSPECT} enum DiagnosticPhase {QUEUE_WAIT,UI_COMPLETION}
 enum DiagnosticStage {VERIFY,FINISHED} enum DiagnosticResult {STARTED,SKIPPED,SUCCEEDED}
 static class Text {String text(){return "existing message";}}
 static final Text CHECKING_SAVES_144=new Text(),SETTINGS_HAVE_CHANGED_PLEASE_RETRY_137=new Text(),THE_CLOUD_IS_TEMPORARILY_UNAVAILABLE_PLEASE_145=new Text(),THE_CURRENT_SAVE_BACKEND_DOES_NOT_130=new Text();
 static class SecureConnectionStore {static class Connection {}}
 static class SyncRuntime {
  static final Object OPERATION_LOCK=new Object();static Object account;static long generation=1;static int refreshes;
  static AndroidSyncFacade localFacade(MainActivity a,Object account){a.creations++;return a.nextFacade;}
  static void requireOfficialSyncAccess(Object app,Object account,AndroidSyncFacade facade)throws Exception{refreshes++;if(facade.failAuthorization)throw new IOException("offline authorization");facade.gate=true;}
  static void checkOfficialSyncAccess(Object account,AndroidSyncFacade facade)throws Exception {if(!facade.gate)throw new IOException("expired");}
  static void clearOfficialSyncAccess(AndroidSyncFacade facade){facade.gate=false;}
  static SelectedRootGame rootGame(MainActivity a){return a.rootGame;}
  static GameAccessBackend backend(MainActivity a){return a.backend;}
  static boolean rootSelectionMatches(MainActivity a,SelectedRootGame chosen){return a.rootGame==chosen;}
 }
 static class SelectedRootGame {String selectionToken(){return "game";}}
 static class GameAccessBackend {
  enum Mode {ROOT_DIRECT} static class Capabilities {boolean exportSupported=true;}
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);boolean deny;int checks;
  Mode mode(){return Mode.ROOT_DIRECT;} Capabilities capabilities(){return new Capabilities();} void targetGame(){}
  void inspectReady() throws Exception {checks++;entered.countDown();while(true){try{release.await();break;}catch(InterruptedException ignored){}} if(deny)throw new IOException("denied");}
 }
 static class InstallationReadiness {
  enum PackageStatus {VERIFIED} enum AccessStatus {NOT_CHECKED,READY,RECOVERY_NEEDED}
  AccessStatus access; boolean restorePending; InstallationReadiness(AccessStatus s,boolean p){access=s;restorePending=p;}
  static InstallationReadiness rootDirect(PackageStatus p,boolean v,boolean i,boolean pending,AccessStatus a){return new InstallationReadiness(a,pending);}
  boolean canContinue(){return access==AccessStatus.READY;} String statusDescription(){return "not ready";}
 }
 static class DiagnosticTrace {void record(Object... o){}}
 static class DiagnosticLog {static DiagnosticTrace begin(Object... o){return new DiagnosticTrace();}}
 static class DiagnosticTiming {static class Scope implements AutoCloseable {public void close(){}}static Scope start(Object... o){return new Scope();}}
 static class AndroidDiagnosticLogging {static class Scope implements AutoCloseable{public void close(){}}static Scope bind(Object o){return new Scope();}static void failure(Object... o){}}
 static class SyncInspectionDiagnostics {enum Stage{INSPECT}static Exception at(Object stage,Exception e){return e;}}
 static class Snapshot {String getId(){return "tip";}}
 static class AppendCommit {Snapshot getSnapshot(){return new Snapshot();}}
 static class SyncHistory {List<AppendCommit> getCloud(){return List.of(new AppendCommit());}String getDefaultSnapshot(){return "tip";}}
 static class AndroidSyncFacade {
  static class Inspection {String getKind(){return "IDENTICAL";}String getMessage(){return "fresh";}}
  static class ConnectionStatus{boolean isSuccess(){return true;} void requireSuccess(String message) throws IOException {}}
  static class HomeReadContext implements AutoCloseable {volatile boolean closed;final CountDownLatch release;HomeReadContext(CountDownLatch r){release=r;}public void close(){closed=true;release.countDown();}}
  static class HomeReadResult{}
  final Inspection inspection=new Inspection();final SyncHistory history=new SyncHistory();long epoch;long observationGeneration(){return epoch;}
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);boolean gate,supported=true,fail,failClose,failAuthorization;int connects,legacy,accepts;HomeReadContext context;
  AutoCloseable beginHomeReadIdentityScope(){return ()->{if(failClose)throw new IOException("identity changed at close");};}
  ConnectionStatus connect(){connects++;return new ConnectionStatus();}
  HomeReadContext captureHomeReadContext(){return supported?(context=new HomeReadContext(release)):null;}
  HomeReadResult readHomeRemote(HomeReadContext c)throws Exception{
   check(!Thread.holdsLock(SyncRuntime.OPERATION_LOCK),"network still holds global mutation lock");
   check(gate,"reader lease revoked after capture");entered.countDown();release.await();
   if(c.closed)throw new CancellationException();if(fail)throw new IOException("network failed");return new HomeReadResult();
  }
  Inspection acceptHomeObservation(HomeReadResult r){check(Thread.holdsLock(SyncRuntime.OPERATION_LOCK),"accept lost serial lock");accepts++;return inspection;}
  Inspection inspectHome(){check(Thread.holdsLock(SyncRuntime.OPERATION_LOCK),"unsupported fallback escaped lock");legacy++;return inspection;}
  Inspection inspectHomeHistory(){return inspectHome();}
  SyncHistory historyPresentation(Inspection i){check(i==inspection,"unpaired observation");return history;}
 }
 static class AutoSyncHistoryRefresh {static class Ticket{}static class Refresh {int deferred;void deferBeforeRead(Ticket t){deferred++;}}}
 static class AutomaticHistoryState {boolean inFlight;AutomaticHistoryOutcome outcome;final AutoSyncHistoryRefresh.Refresh refresh=new AutoSyncHistoryRefresh.Refresh();}
 static class AutomaticHistoryOutcome {long observationGeneration;AndroidSyncFacade facade;AndroidSyncFacade.Inspection inspection;SyncHistory history;Exception error;AutomaticHistoryOutcome(Object t,Object a){}}
 static class AutoSyncService {static boolean claimHistoryRefresh(MainActivity a,Object t){a.claims++;return true;}}
 static class Installer{boolean hasActiveRequest(){return false;}}
 static class StaleArchiveReadException extends Exception{}
 final ExecutorService homeReader=Executors.newSingleThreadExecutor();final HomeArchiveReadState archiveReads=new HomeArchiveReadState();
 final OfficialNamespacePreparation preparedOfficialNamespace=new OfficialNamespacePreparation();final AutomaticHistoryState automaticHistoryState=new AutomaticHistoryState();
 final Installer installer=new Installer();InstallationReadiness readinessState=new InstallationReadiness(InstallationReadiness.AccessStatus.NOT_CHECKED,false);final BlockingQueue<Runnable> ui=new LinkedBlockingQueue<>();
 volatile DetachedHomeRead detachedHomeRead;SecureConnectionStore.Connection connection=new SecureConnectionStore.Connection();
 AndroidSyncFacade facade,nextFacade=new AndroidSyncFacade();AndroidSyncFacade.Inspection inspection;SyncHistory history;Object homeObservationOwner;
 SelectedRootGame rootGame;GameAccessBackend backend=new GameAccessBackend();GameAccessBackend.Capabilities accessCapabilities;String bridgeAccessCode="NOT_CHECKED";boolean journal;
 boolean busy,cloudVerified,feedbackError,foreground=true,ready=true,official=true;String details="old details",notice="old notice",selected="old",explicitHomeSelection="old",selection="game",namespace="ns",endpoint="url",profile="profile";
 int errors,renders,creations,claims,automaticApplied,readiness;
 MainActivity(){SyncRuntime.account=connection;}
 Object getApplicationContext(){return this;}boolean isForegroundHome(){return foreground;}boolean readyForSaves(){return ready;}boolean isOfficialAccount(Object a){return official;}
 void render(){renders++;}void reportError(Exception e){errors++;details=e.getMessage();}void runOnUiThread(Runnable action){ui.add(action);}
 void readInstallationReadiness(){readiness++;}void checkRootAccess(){}void maybeActivateSetupComponent(){}
 boolean hasAnyRestoreJournal(){return journal;}void drainHomeEntryRead(){}void rememberCurrentAutomaticHistory(){}
 void invalidatePreparedOfficialNamespace(){preparedOfficialNamespace.clear();}String comparisonCopy(){return "read complete";}String safeText(String s){return s;}Object currentHomeContext(int n){return new Object();}
 void drainRenewalArchiveRead(){}void drainAutomaticHistoryRefresh(){if(automaticHistoryState.outcome!=null)automaticApplied++;}
 boolean currentAutomaticHistory(Object t,Object a){return a==connection;}
 OfficialOperationLease.Binding officialOperationBinding(SecureConnectionStore.Connection account)throws Exception{
  if(account!=connection||account!=SyncRuntime.account)throw new IOException("changed");
  return new OfficialOperationLease.Binding(account,SyncRuntime.generation,endpoint,"user",profile,selection,namespace);
 }
 /* METHODS */
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 void finish()throws Exception{Runnable r=ui.poll(5,TimeUnit.SECONDS);check(r!=null,"reader did not terminate");r.run();}
 void close(){cancelDetachedHomeRead();homeReader.shutdownNow();}
 static void good()throws Exception {
  MainActivity a=new MainActivity();int access=SyncRuntime.refreshes;
  try{
   a.startDetachedHomeRead(a.connection,true,true,null);check(!a.busy&&a.archiveReads.loading(),"reader used write-busy");a.finish();
   check(a.inspection==a.nextFacade.inspection&&a.history==a.nextFacade.history&&a.cloudVerified,"paired read not published");
   check(a.nextFacade.connects==1&&!a.nextFacade.gate&&a.readiness==1,"namespace/lease lifetime failed");
   a.nextFacade=new AndroidSyncFacade();a.startDetachedHomeRead(a.connection,true,false,null);a.finish();
   check(a.nextFacade.connects==0&&SyncRuntime.refreshes==access+2,"manual refresh not live or repeated namespace preparation");
   a.nextFacade=new AndroidSyncFacade();a.nextFacade.supported=false;a.startDetachedHomeRead(a.connection,false,false,null);a.finish();check(a.nextFacade.legacy==1,"unsupported fallback missing");
  }finally{a.close();}
 }
 static void cancelledAndStale()throws Exception {
  for(int mode=0;mode<8;mode++){
   MainActivity a=new MainActivity();a.nextFacade.release=new CountDownLatch(1);
   try{
    a.startDetachedHomeRead(a.connection,false,false,null);check(a.nextFacade.entered.await(5,TimeUnit.SECONDS),"no network");
    synchronized(SyncRuntime.OPERATION_LOCK){check(!a.busy,"read blocked write entry");}
    String notice=a.notice;
    if(mode==0){a.foreground=false;a.cancelDetachedHomeRead();}
    if(mode==1)SyncRuntime.generation++;
    if(mode==2)a.connection=new SecureConnectionStore.Connection();
    if(mode==3){SyncRuntime.account=new Object();a.nextFacade.fail=true;}
    if(mode==4)a.selection="other";
    if(mode==5)a.namespace="other";
    if(mode==6)a.endpoint="other";
    if(mode==7){a.busy=true;a.cancelDetachedHomeRead();}
    a.nextFacade.release.countDown();a.finish();
    check(a.inspection==null&&a.history==null&&notice.equals(a.notice)&&a.errors==0,"stale read published");
    check(!a.nextFacade.gate&&a.nextFacade.accepts==0,"stale scope accepted or kept lease");
   }finally{a.close();}
  }
 }
 static void retryAfterFailure()throws Exception{
  for(boolean authorization:new boolean[]{false,true}){
   MainActivity a=new MainActivity();int before=SyncRuntime.refreshes;
   try{
    for(int attempt=0;attempt<2;attempt++){
     a.nextFacade=new AndroidSyncFacade();a.nextFacade.failAuthorization=authorization;a.nextFacade.fail=!authorization;
     a.startDetachedHomeRead(a.connection,false,false,null);a.finish();
     check(a.detachedHomeRead==null&&!a.archiveReads.loading()&&!a.busy&&!a.cloudVerified,"failed read blocked retry");
     check(!a.nextFacade.gate&&a.nextFacade.connects==0,"failed read retained lease or wrote namespace");
    }
    a.nextFacade=new AndroidSyncFacade();a.startDetachedHomeRead(a.connection,false,false,null);a.finish();
    check(a.cloudVerified&&a.errors==2&&SyncRuntime.refreshes==before+3,"online foreground retry did not reauthorize");
    a.nextFacade=new AndroidSyncFacade();a.nextFacade.release=new CountDownLatch(1);
    a.startDetachedHomeRead(a.connection,false,false,null);check(a.nextFacade.entered.await(5,TimeUnit.SECONDS),"no pending read");
    a.foreground=false;a.cancelDetachedHomeRead();a.finish();
    a.foreground=true;a.nextFacade=new AndroidSyncFacade();a.startDetachedHomeRead(a.connection,false,false,null);a.finish();
    check(a.cloudVerified&&!a.archiveReads.loading()&&a.errors==2,"cancel/background return blocked fresh read");
   }finally{a.close();}
  }
 }
 static void automatic()throws Exception{
  MainActivity a=new MainActivity();AutoSyncHistoryRefresh.Ticket ticket=new AutoSyncHistoryRefresh.Ticket();
  try{
   a.nextFacade.release=new CountDownLatch(1);a.startDetachedHomeRead(a.connection,false,false,ticket);a.nextFacade.entered.await(5,TimeUnit.SECONDS);
   check(a.claims==0,"automatic result consumed before read completes");a.cancelDetachedHomeRead();a.finish();
   check(a.claims==0&&a.automaticHistoryState.refresh.deferred==1,"cancelled auto read lost pending result");
   a.nextFacade=new AndroidSyncFacade();a.startDetachedHomeRead(a.connection,false,false,ticket);a.finish();
   check(a.claims==1&&a.automaticApplied==1&&!a.automaticHistoryState.inFlight,"automatic read not accepted once");
   a.ready=false;a.startDetachedHomeRead(a.connection,false,false,new AutoSyncHistoryRefresh.Ticket());a.finish();
   check(a.claims==1,"not-ready automatic read was consumed without I/O");
  }finally{a.close();}
 }
 static void checkedRoot()throws Exception {
  MainActivity a=new MainActivity();a.rootGame=new SelectedRootGame();a.ready=false;
  try {a.startDetachedHomeRead(a.connection,false,true,null,true);a.finish();
   check(a.readinessState.access==InstallationReadiness.AccessStatus.READY&&a.cloudVerified,"checked Root not published");
   check(a.backend.checks==1&&a.nextFacade.connects==0,"Root read must not provision");
  }finally{a.close();}
  for(boolean cancelled:new boolean[]{false,true}){
   MainActivity s=new MainActivity();s.rootGame=new SelectedRootGame();s.backend.release=new CountDownLatch(1);InstallationReadiness before=s.readinessState;
   try{s.startDetachedHomeRead(s.connection,false,true,null,true);check(s.backend.entered.await(5,TimeUnit.SECONDS),"Root probe did not start");
    if(cancelled){s.foreground=false;s.cancelDetachedHomeRead();}else{s.selection="other";}
    s.backend.release.countDown();s.finish();check(s.readinessState==before&&s.inspection==null&&s.creations==0,"stale Root published or reached cloud");
   }finally{s.close();}
  }
  for(boolean journal:new boolean[]{false,true}){
   MainActivity s=new MainActivity();s.rootGame=new SelectedRootGame();s.journal=journal;s.backend.deny=!journal;
   try{s.startDetachedHomeRead(s.connection,false,true,null,true);s.finish();
    check(s.creations==0&&s.nextFacade.connects==0,"denied or recovery read reached cloud");
    check(journal?s.backend.checks==0:s.errors==1,"Root failure/recovery not surfaced");
    if(!journal){s.backend.deny=false;s.nextFacade=new AndroidSyncFacade();s.startDetachedHomeRead(s.connection,false,true,null,true);s.finish();
     check(s.cloudVerified&&s.backend.checks==2&&s.nextFacade.connects==0,"denied Root could not retry read-only");}
   }finally{s.close();}
  }
  MainActivity late=new MainActivity();late.rootGame=new SelectedRootGame();InstallationReadiness before=late.readinessState;
  try{late.startDetachedHomeRead(late.connection,false,true,null,true);Runnable completion=late.ui.poll(5,TimeUnit.SECONDS);
   check(completion!=null,"missing Root completion");late.rootGame=new SelectedRootGame();late.selection="other";completion.run();
   check(late.readinessState==before&&late.inspection==null,"stale Root completion published");
  }finally{late.close();}
 }
 public static void main(String[] args)throws Exception{
  good();cancelledAndStale();retryAfterFailure();automatic();checkedRoot();
  MainActivity a=new MainActivity();try{a.nextFacade.fail=true;a.startDetachedHomeRead(a.connection,false,false,null);a.finish();check(a.errors==1&&!a.busy&&!a.archiveReads.loading(),"failure left loading");}finally{a.close();}
  MainActivity b=new MainActivity();try{b.ready=false;int count=SyncRuntime.refreshes;b.startDetachedHomeRead(b.connection,false,true,null);b.finish();check(SyncRuntime.refreshes==count&&b.nextFacade.accepts==0,"readiness failure issued remote request");}finally{b.close();}
  for(boolean automatic:new boolean[]{false,true}) {
   MainActivity delayed=new MainActivity();try {
    delayed.startDetachedHomeRead(delayed.connection,false,false,automatic?new AutoSyncHistoryRefresh.Ticket():null);
    Runnable completion=delayed.ui.poll(5,TimeUnit.SECONDS);check(completion!=null,"missing completion");
    delayed.nextFacade.epoch++;completion.run();
    check(delayed.inspection==null&&delayed.history==null&&delayed.claims==0&&delayed.automaticApplied==0,"superseded read was published or claimed");
    if(automatic)check(delayed.automaticHistoryState.refresh.deferred==1,"superseded auto read lost pending result");
   }finally{delayed.close();}
   MainActivity failedClose=new MainActivity();try {
    failedClose.nextFacade.failClose=true;failedClose.startDetachedHomeRead(failedClose.connection,false,false,automatic?new AutoSyncHistoryRefresh.Ticket():null);failedClose.finish();
    if(automatic)check(failedClose.automaticHistoryState.outcome.error!=null&&failedClose.automaticHistoryState.outcome.inspection==null&&failedClose.automaticHistoryState.outcome.history==null,"close failure retained automatic proof");
    else check(failedClose.errors==1&&failedClose.inspection==null&&failedClose.history==null,"close failure published manual observation");
   }finally{failedClose.close();}
  }
  HomeReadCancellation c=new HomeReadCancellation();c.cancel();final int[] closed={0};c.attach((AutoCloseable)()->closed[0]++);check(closed[0]==1,"late cancellation attachment leaked");
  System.out.println("Detached HOME queue execution passed");
 }
}
'''
  with tempfile.TemporaryDirectory(prefix='pdsync-detached-home-') as tmp:
   source=Path(tmp)/'MainActivity.java';source.write_text(fixture.replace('/* METHODS */','\n'.join(methods)))
   java=os.environ.get('JAVA_BIN','java')
   command=[java,'-m','jdk.compiler/com.sun.tools.javac.Main','-d',tmp,str(source)]
   command += [str(SRC/name) for name in ('HomeArchiveReadState.java','HomeReadCancellation.java','OfficialOperationLease.java','OfficialNamespacePreparation.java')]
   compiled=subprocess.run(command,capture_output=True,text=True)
   self.assertEqual(0,compiled.returncode,compiled.stderr)
   result=subprocess.run([java,'-cp',tmp,'io.github.xyz1001.pdsync/android/MainActivity'.replace('/','.')],capture_output=True,text=True,timeout=45)
   self.assertEqual(0,result.returncode,result.stderr)
