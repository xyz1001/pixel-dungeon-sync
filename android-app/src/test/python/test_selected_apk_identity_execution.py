"""Execute production metadata completion ownership using deterministic background/main queues."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

SRC = Path(__file__).resolve().parents[2] / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (SRC / 'MainActivity.java').read_text()

class SelectedApkIdentityExecution(unittest.TestCase):
    def test_async_metadata_only_updates_the_current_request_across_rotation(self):
        method = 'private void readPendingApkIdentity(GameModeChoice.Request<Uri> request) ' + body(MAIN, 'private void readPendingApkIdentity(')
        harness = r'''
package io.github.xyz1001.pdsync.android;
import java.util.*;
import static pdsync.i18n.AndroidHostMessages.*;
public class MainActivity {
 static class Uri {}
 static class Context {}
 static class Looper { static Object getMainLooper() { return null; } }
 static class Handler {
  static final List<Runnable> main = new ArrayList<>();
  Handler(Object ignored) {} void post(Runnable r) { main.add(r); }
  static void drain() { while (!main.isEmpty()) main.remove(0).run(); }
 }
 static class Queue { final List<Runnable> jobs = new ArrayList<>(); void execute(Runnable r) { jobs.add(r); } void run() { jobs.remove(0).run(); } }
 static class SharedGameIdentity { final String name; SharedGameIdentity(String s) { name = s; } }
 static class SelectedApkIdentity { static SharedGameIdentity result; static SharedGameIdentity read(Context c, List<Uri> uris) { return result; } }
 static class SetupSession { final GameModeChoice<Uri> modeChoice = new GameModeChoice<>(); final Queue executor = new Queue(); SharedGameIdentity pendingGameIdentity, selectedFileIdentity; GameModeChoice.Request<Uri> fileIdentityRequest; GameModeChoice.Source<Uri> selectedSource; MainActivity owner; }
 SetupSession setupSession = new SetupSession(); int renders; boolean destroyed;
 MainActivity() { setupSession.owner = this; }
 Context getApplicationContext() { return new Context(); } boolean isDestroyed() { return destroyed; } void render() { renders++; }
 METHOD
 static void check(boolean c) { if (!c) throw new AssertionError(); }
 static GameModeChoice.Request<Uri> offer(MainActivity a, Uri uri) { a.setupSession.fileIdentityRequest = null; return a.setupSession.modeChoice.offer(GameModeChoice.Source.files(List.of(uri))); }
 public static void main(String[] args) {
  MainActivity a = new MainActivity(); Uri uri = new Uri();
  var first = offer(a, uri); a.readPendingApkIdentity(first);
  check(a.renders == 0 && a.setupSession.pendingGameIdentity == null);
  SelectedApkIdentity.result = new SharedGameIdentity("first"); a.setupSession.executor.run();
  check(a.renders == 0); Handler.drain(); check(a.renders == 1 && a.setupSession.pendingGameIdentity.name.equals("first"));
  // Same URI, new request: old completion must not overwrite the newer selection.
  var old = offer(a, uri); a.readPendingApkIdentity(old); var newer = offer(a, uri);
  SelectedApkIdentity.result = new SharedGameIdentity("stale"); a.setupSession.executor.run(); Handler.drain(); check(a.renders == 1);
  a.readPendingApkIdentity(newer); a.setupSession.modeChoice.cancel(newer); a.setupSession.fileIdentityRequest = null;
  a.setupSession.executor.run(); Handler.drain(); check(a.renders == 1);
  // Completion must use the retained session's new owner, without a destroyed Activity.
  var rotated = offer(a, new Uri()); a.readPendingApkIdentity(rotated); a.setupSession.executor.run();
  MainActivity b = new MainActivity(); b.setupSession = a.setupSession; b.setupSession.owner = b; a.destroyed = true;
  SelectedApkIdentity.result = null; Handler.drain(); check(a.renders == 1 && b.renders == 1);
  // Null/failed read keeps existing card unchanged.
  var fail = offer(b, new Uri()); b.readPendingApkIdentity(fail); b.setupSession.executor.run(); Handler.drain(); check(b.renders == 1);
  // Committing the exact source keeps metadata visible during preparation.
  var committed = offer(b, new Uri()); b.readPendingApkIdentity(committed);
  b.setupSession.modeChoice.dispatch(committed, GameModeChoice.Mode.APK, new GameModeChoice.Actions<Uri>() {
   public void root(GameModeChoice.Request<Uri> r) {} public void apk(GameModeChoice.Request<Uri> r) { b.setupSession.selectedSource = r.source; b.setupSession.modeChoice.complete(r); }
  });
  SelectedApkIdentity.result = new SharedGameIdentity("late"); b.setupSession.executor.run(); Handler.drain(); check(b.renders == 2 && b.setupSession.selectedFileIdentity.name.equals("late"));
  System.out.println("Selected APK async ownership checks passed");
 }
}
'''.replace('METHOD', method)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'MainActivity.java').write_text(harness)
            subprocess.run(['javac', '-sourcepath', str(SRC.parents[8] / 'core/src/main/java'), '-d', tmp, str(root / 'MainActivity.java'), str(SRC / 'GameModeChoice.java')], check=True, capture_output=True)
            subprocess.run(['java', '-cp', tmp, 'io.github.xyz1001.pdsync.android.MainActivity'], check=True, capture_output=True)

    def test_offer_reads_only_after_nonempty_picker_success_and_retains_neutral_fallback(self):
        offer = body(MAIN, 'private void offerGameModeChoice(')
        self.assertIn('if (source.apks != null) readPendingApkIdentity(this.setupSession.modeChoice.pending());', offer)
        picker = body(MAIN, 'protected void onActivityResult(')
        self.assertIn('Cancel keeps the current prepared game and Root selection unchanged.', picker)
        self.assertIn('if (linkedHashSet.isEmpty())', picker)
        self.assertIn('new SharedGameIdentity(APK_FILE_SELECTED_77.text(), WAITING_TO_CHECK_THE_INSTALLATION_PACKAGE_78.text())', MAIN)
