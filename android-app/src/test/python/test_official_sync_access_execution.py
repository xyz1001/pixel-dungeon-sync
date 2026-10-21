"""Execute Android's production per-operation gate and cached-facade dispatch on the host."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

ROOT = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
RUNTIME = (ROOT / 'SyncRuntime.java').read_text()
MAIN = (ROOT / 'MainActivity.java').read_text()
AUTO = (ROOT / 'AutoSyncService.java').read_text()
ACCESS = (Path(__file__).resolve().parents[4] / 'core/src/main/java/io/github/xyz1001/pdsync/android/OfficialSyncAccess.java').read_text()

class OfficialSyncAccessExecution(unittest.TestCase):
    def test_every_reused_facade_reauthorizes_and_auto_checks_at_publication(self):
        sync = body(MAIN, 'private AndroidSyncFacade sync()')
        self.assertIn('requireOfficialSyncAccess(this.connection)', sync)
        self.assertIn('requireSyncFacade()', sync)
        self.assertIn('SyncRuntime.localFacade(this, connection)', body(MAIN, 'private AndroidSyncFacade requireSyncFacade()'))
        factory = body(RUNTIME, 'private static AndroidSyncFacade createFacade(')
        self.assertLess(factory.index('gate.refresh(context)'), factory.index('backend(context)'))
        self.assertIn('androidGameIdentityGameIdentity, gate)', factory)
        auto = body(AUTO, 'private void attemptUpload()')
        self.assertLess(auto.index('SyncRuntime.requireOfficialSyncAccess'), auto.index('this.facade.autoUpload()'))
        history = body(MAIN, 'private void drainAutomaticHistoryRefresh()')
        self.assertIn('startDetachedHomeRead(captured, false, false, ticket)', history)
        reader=body(MAIN, 'private boolean startDetachedHomeRead(')
        self.assertLess(reader.index('lease.authorize('), reader.index('owner.readHomeRemote(context)'))
        self.assertIn('SyncRuntime.requireOfficialSyncAccess(getApplicationContext(), account, owner)', reader)
        # Every cloud action obtains sync(), including inspect/history, conflict and recovery.
        self.assertIn('AndroidSyncFacade owner = sync()', body(MAIN, 'private AndroidSyncFacade.OperationResult restoreForCurrentAccount('))

    def test_local_recovery_accessor_remains_available_without_cloud_authorization(self):
        methods = ('private AndroidSyncFacade localSync() throws Exception ' + body(MAIN, 'private AndroidSyncFacade localSync()')
                   + '\nprivate AndroidSyncFacade sync() throws Exception ' + body(MAIN, 'private AndroidSyncFacade sync()')
                   + '\nprivate AndroidSyncFacade requireSyncFacade() throws Exception ' + body(MAIN, 'private AndroidSyncFacade requireSyncFacade()'))
        for action in ('recoverRestore', 'discardInterruptedRestore', 'archivePending', 'renameHistory', 'renameCurrent', 'confirmRunIdentity'):
            self.assertIn('localSync().' + action + '(', MAIN)
            self.assertNotIn('sync().' + action + '(', MAIN)
        self.assertIn('createFacade(context, connection2, false)', body(RUNTIME, 'static AndroidSyncFacade localFacade('))
        fixture = r'''
import java.io.IOException;
class LocalCheck {
    static class SecureConnectionStore { static class Connection {} }
    static class AndroidSyncFacade { boolean authorized; }
    static class OfficialOperationLease { void invalidate(){} }
    final ThreadLocal<OfficialOperationLease> officialOperationLease = new ThreadLocal<>();
    static class Message { String text(){return "blocked";} }
    static final Message SET_UP_THE_CLOUD_CONNECTION_FIRST_143=new Message(), THE_CONNECTION_HAS_CHANGED_COMPARE_AGAIN_158=new Message();
    static class SyncRuntime {
        static int constructions;
        static AndroidSyncFacade localFacade(Object context, SecureConnectionStore.Connection c){constructions++;return new AndroidSyncFacade();}
        static void clearOfficialSyncAccess(AndroidSyncFacade f){f.authorized=false;}
    }
    SecureConnectionStore.Connection connection=new SecureConnectionStore.Connection();
    AndroidSyncFacade facade; int refreshes;
    void requireOfficialSyncAccess(SecureConnectionStore.Connection c) throws IOException {refreshes++;throw new IOException("offline or expired");}
    /* METHODS */
    public static void main(String[] args) throws Exception {
        LocalCheck app=new LocalCheck();
        AndroidSyncFacade local=app.localSync();
        if(local.authorized || app.refreshes!=0 || SyncRuntime.constructions!=1)throw new AssertionError();
        try{app.sync();throw new AssertionError();}catch(IOException blocked){}
        if(app.refreshes!=1 || app.localSync()!=local || local.authorized)throw new AssertionError();
        local.authorized=true; app.localSync();
        if(local.authorized || SyncRuntime.constructions!=1)throw new AssertionError("local access retains no remote lease");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='pdsync-local-maintenance-') as tmp:
            source = Path(tmp) / 'LocalCheck.java'; source.write_text(fixture.replace('/* METHODS */', methods))
            java = os.environ.get('JAVA_BIN', 'java')
            compiled = subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', tmp, str(source)], capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            run = subprocess.run([java, '-cp', tmp, 'LocalCheck'], capture_output=True, text=True)
            self.assertEqual(0, run.returncode, run.stderr)

    def test_refresh_expiry_restart_renewal_failure_and_custom_isolation(self):
        gate = 'private static final class OfficialOperationGate implements pdsync.WebDavRequestCheck ' + body(RUNTIME, 'private static final class OfficialOperationGate')
        refresh = 'static void requireOfficialSyncAccess(Context context, SecureConnectionStore.Connection account, AndroidSyncFacade facade) throws Exception ' + body(RUNTIME, 'static void requireOfficialSyncAccess(')
        fixture = r'''
import java.io.IOException;
import pdsync.WebDavRequestCheck;
class Check {
  static class Context {}
  static class AndroidSyncFacade {}
  static class SecureConnectionStore { static class Connection {
    static final String PROVIDER_OFFICIAL="official";
    String provider="official", endpoint="official-url", username="u", password="p";
  }}
  static class OfficialServiceRegistrationClient { static final String DAV_URL="official-url"; }
  static class AndroidMessages { static final AndroidMessages SYNC_RUNTIME_09=new AndroidMessages(); String text(){return "blocked";} }
  static long now=10, deadline=20, generation=1; static int requests, cacheWrites; static boolean fail, changeDuringRequest;
  static long connectionGeneration(){return generation;}
  static class ManagedAccountClient {
    ManagedAccountClient(String a,String b,String c){}
    Entitlement entitlement() throws IOException { requests++; if(fail)throw new IOException("offline"); if(changeDuringRequest)generation++; return new Entitlement(); }
    static class Entitlement { final Long expiresMs=deadline, serverTimeMs=now; final String username="u"; void requireSyncAccess() throws IOException { if(now>=expiresMs)throw new IOException("expired"); } }
  }
  static class OfficialSyncAccess {
    static boolean isOfficialEndpoint(String e){return e.equals("official-url");}
    interface Clock { long nanoTime(); default long wallTimeMillis(){return 0;} }
    static Lease fromEntitlement(ManagedAccountClient.Entitlement e, long started, Clock clock){return new Lease(e, started, clock, 0, false);}
    static class AccessException extends IOException { AccessException(boolean expired, String message){super(message);} }
    /* PRODUCTION_LEASE */
  }
  static class CoreSessionMessages { static final AndroidMessages SERVICE_EXPIRED=new AndroidMessages(), THE_SERVICE_RETURNED_AN_INVALID_RESPONSE=new AndroidMessages(); }
  static java.util.Map<AndroidSyncFacade,OfficialOperationGate> officialGates=new java.util.WeakHashMap<>();
  static void rememberEntitlement(Context c, SecureConnectionStore.Connection a, ManagedAccountClient.Entitlement e){cacheWrites++;}
  /* GATE */
  /* REFRESH */
  interface Checked { void run() throws Exception; }
  static void blocked(Checked c) throws Exception { try{c.run();throw new AssertionError("must block");}catch(IOException expected){} }
  static void check(boolean b){if(!b)throw new AssertionError();}
  public static void main(String[] args) throws Exception {
    Context c=new Context(); SecureConnectionStore.Connection a=new SecureConnectionStore.Connection();
    AndroidSyncFacade f=new AndroidSyncFacade(); OfficialOperationGate g=new OfficialOperationGate(a); officialGates.put(f,g);
    requireOfficialSyncAccess(c,a,f); g.check(); check(requests==1 && cacheWrites==1);
    // Simulate deep sleep: advance Android's elapsed realtime while no Java work runs.
    // The production lease must see the same sleep-inclusive clock used at request start.
    now=20; android.os.SystemClock.nowNanos=20_000_000L; blocked(g::check); blocked(()->requireOfficialSyncAccess(c,a,f)); check(cacheWrites==2 && g.lease==null);
    // A recreated process/facade cannot reuse an earlier active authorization.
    OfficialOperationGate restart=new OfficialOperationGate(a); blocked(()->restart.refresh(c)); blocked(restart::check);
    deadline=30; requireOfficialSyncAccess(c,a,f); g.check();
    generation++; blocked(g::check); check(g.lease==null);
    requireOfficialSyncAccess(c,a,f); g.check();
    changeDuringRequest=true; blocked(()->requireOfficialSyncAccess(c,a,f)); blocked(g::check);
    check(g.lease==null); changeDuringRequest=false;
    requireOfficialSyncAccess(c,a,f); g.check();
    fail=true; blocked(()->requireOfficialSyncAccess(c,a,f)); blocked(g::check); fail=false;
    int before=requests; a.provider="custom"; requireOfficialSyncAccess(c,a,f); check(requests==before+1); before=requests;
    a.provider="official"; a.endpoint="https://custom.example/"; requireOfficialSyncAccess(c,a,f); check(requests==before);
    a.endpoint="official-url"; blocked(()->requireOfficialSyncAccess(c,a,new AndroidSyncFacade()));
  }
}
'''
        with tempfile.TemporaryDirectory(prefix='pdsync-entitlement-gate-') as tmp:
            source = Path(tmp) / 'Check.java'
            source.write_text(fixture.replace('/* GATE */', gate).replace('/* REFRESH */', refresh).replace('/* PRODUCTION_LEASE */', 'public static final class Lease implements WebDavRequestCheck ' + body(ACCESS, 'public static final class Lease')))
            port = Path(tmp) / 'pdsync/WebDavRequestCheck.java'; port.parent.mkdir()
            port.write_text('package pdsync; public interface WebDavRequestCheck { void check() throws java.io.IOException; }')
            clock = Path(tmp) / 'android/os/SystemClock.java'; clock.parent.mkdir(parents=True)
            clock.write_text('package android.os; public final class SystemClock { public static long nowNanos=10_000_000L; public static long elapsedRealtimeNanos(){return nowNanos;} }')
            java = os.environ.get('JAVA_BIN', 'java')
            compiled = subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', tmp, str(source), str(port), str(clock)], capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            run = subprocess.run([java, '-cp', tmp, 'Check'], capture_output=True, text=True)
            self.assertEqual(0, run.returncode, run.stderr)
