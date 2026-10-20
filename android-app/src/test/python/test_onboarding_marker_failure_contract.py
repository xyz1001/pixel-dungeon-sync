"""Execute the production marker methods against a host-only failed-commit preference double.

This models Android's memory-before-disk failure semantics, not a real Android runtime.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path
from test_embedded_install_contract import body

SOURCE = (Path(__file__).resolve().parents[2] / "main/java/io/github/xyz1001/pdsync/android/SyncRuntime.java").read_text()


class OnboardingMarkerFailureContract(unittest.TestCase):
    def test_failed_disk_commit_cannot_appear_complete_after_activity_recreation(self):
        read = body(SOURCE, "static OnboardingProgress onboardingProgress(")
        write = body(SOURCE, "static void setOnboardingProgress(")
        self.assertIn("failedOnboardingWrites.contains(key)", read)
        self.assertIn("failedOnboardingWrites.add(key)", write)
        self.assertLess(write.index(".commit()"), write.index("failedOnboardingWrites.remove(key)"))
        java = '''
import java.io.IOException;
import pdsync.i18n.AndroidMessages;
import java.util.*;
public class MarkerFailureChecks {
    enum OnboardingProgress { PENDING, COMPLETE }
    static class Context {
        final Prefs preferences;
        Context(Prefs preferences) { this.preferences = preferences; }
    }
    static class SecureConnectionStore {
        static class Connection {
            final String scope;
            Connection(String scope) { this.scope = scope; }
        }
    }
    static class Prefs {
        final Map<String,String> memory = new HashMap<>();
        final Map<String,String> disk = new HashMap<>();
        boolean fail;
        boolean getBoolean(String key, boolean fallback) { return fallback; }
        String getString(String key, String fallback) { return memory.getOrDefault(key, fallback); }
        Editor edit() { return new Editor(); }
        class Editor {
            String key, value;
            Editor putString(String key, String value) { this.key = key; this.value = value; return this; }
            Editor putBoolean(String key, boolean value) { return this; }
            boolean commit() {
                memory.put(key, value);
                if (fail) { fail = false; return false; }
                disk.put(key, value);
                return true;
            }
        }
    }
    static final Set<String> failedOnboardingWrites = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static Prefs prefs(Context context) { return context.preferences; }
    static String scope(Context context, SecureConnectionStore.Connection connection) { return connection.scope; }
    static OnboardingProgress onboardingProgress(Context context, SecureConnectionStore.Connection connection2)
''' + read + '''
    static void setOnboardingProgress(Context context, SecureConnectionStore.Connection connection2, OnboardingProgress onboardingProgress)
''' + write + '''
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] ignored) {
        Prefs preferences = new Prefs();
        Context firstActivity = new Context(preferences);
        SecureConnectionStore.Connection account = new SecureConnectionStore.Connection("game/account");
        SecureConnectionStore.Connection other = new SecureConnectionStore.Connection("other-game/account");
        setOnboardingProgress(firstActivity, account, OnboardingProgress.PENDING);
        preferences.fail = true;
        boolean rejected = false;
        try { setOnboardingProgress(firstActivity, account, OnboardingProgress.COMPLETE); }
        catch (IllegalStateException expected) { rejected = true; }
        require(rejected);
        require("COMPLETE".equals(preferences.memory.get("onboarding.game/account")));
        require("PENDING".equals(preferences.disk.get("onboarding.game/account")));
        require(onboardingProgress(firstActivity, account) == OnboardingProgress.PENDING);
        Context recreatedActivity = new Context(preferences);
        require(onboardingProgress(recreatedActivity, account) == OnboardingProgress.PENDING);
        setOnboardingProgress(recreatedActivity, other, OnboardingProgress.COMPLETE);
        require(onboardingProgress(recreatedActivity, other) == OnboardingProgress.COMPLETE);
        require(onboardingProgress(recreatedActivity, account) == OnboardingProgress.PENDING);
        setOnboardingProgress(recreatedActivity, account, OnboardingProgress.COMPLETE);
        require(onboardingProgress(recreatedActivity, account) == OnboardingProgress.COMPLETE);
        require("COMPLETE".equals(preferences.disk.get("onboarding.game/account")));
        require(failedOnboardingWrites.isEmpty());
        System.out.println("MarkerFailureChecks: 10 checks passed");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix="pdsync-marker-failure-") as temporary:
            source = Path(temporary) / "MarkerFailureChecks.java"
            source.write_text(java)
            compiled = subprocess.run(["javac", "-sourcepath", str(Path(__file__).resolve().parents[4] / "core/src/main/java"), "-d", temporary, str(source)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(["java", "-cp", temporary, "MarkerFailureChecks"], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("10 checks passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
