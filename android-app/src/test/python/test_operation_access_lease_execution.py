"""Execute the foreground operation lease without Android or a real account."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / "main/java/io/github/xyz1001/pdsync/android"
MAIN = (ROOT / "MainActivity.java").read_text()
RUNTIME = (ROOT / "SyncRuntime.java").read_text()
CONTROLLER = (Path(__file__).resolve().parents[4] /
              "shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedConnectionController.kt").read_text()


class OperationAccessLeaseExecution(unittest.TestCase):
    def test_real_scope_counts_deadline_identity_failure_and_close(self):
        fixture = r"""
package io.github.xyz1001.pdsync.android;
import java.io.IOException;
public class LeaseChecks {
    static final Object ACCOUNT = new Object(), FACADE = new Object();
    static int refreshes, checks, clears;
    static boolean expired, changed;
    static OfficialOperationLease.Binding binding(Object account, long generation, String endpoint,
            String username, String profile, String selection, String namespace) {
        return new OfficialOperationLease.Binding(account, generation, endpoint, username, profile, selection, namespace);
    }
    static OfficialOperationLease.Binding normal() {
        return binding(ACCOUNT, 1, "endpoint", "user", "profile", "selection", "namespace");
    }
    static void authorize(OfficialOperationLease lease, OfficialOperationLease.Binding key) throws Exception {
        lease.authorize(key, FACADE, "changed", () -> { refreshes++; },
                () -> { checks++; if (expired) throw new IOException("expired"); },
                () -> { if (changed) throw new IOException("changed"); }, () -> { clears++; });
    }
    static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
    interface Action { void run() throws Exception; }
    static void blocked(Action action) throws Exception {
        try { action.run(); throw new AssertionError("must reject"); } catch (IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        OfficialOperationLease first = new OfficialOperationLease();
        authorize(first, normal()); authorize(first, normal()); authorize(first, normal());
        check(refreshes == 1 && checks == 3, "restore + refresh + complete must share one request");
        expired = true; blocked(() -> authorize(first, normal()));
        check(refreshes == 1 && clears == 1, "deadline check must reject without refreshing");
        first.close(); expired = false;
        try (OfficialOperationLease second = new OfficialOperationLease()) { authorize(second, normal()); }
        check(refreshes == 2 && clears == 2, "another button obtains fresh permission and closes it");
        for (OfficialOperationLease.Binding changedKey : new OfficialOperationLease.Binding[] {
                binding(new Object(), 1, "endpoint", "user", "profile", "selection", "namespace"),
                binding(ACCOUNT, 2, "endpoint", "user", "profile", "selection", "namespace"),
                binding(ACCOUNT, 1, "changed", "user", "profile", "selection", "namespace"),
                binding(ACCOUNT, 1, "endpoint", "changed", "profile", "selection", "namespace"),
                binding(ACCOUNT, 1, "endpoint", "user", "changed", "selection", "namespace"),
                binding(ACCOUNT, 1, "endpoint", "user", "profile", "changed", "namespace"),
                binding(ACCOUNT, 1, "endpoint", "user", "profile", "selection", "changed") }) {
            try (OfficialOperationLease lease = new OfficialOperationLease()) {
                authorize(lease, normal()); int before = refreshes, revoked = clears;
                blocked(() -> authorize(lease, changedKey));
                check(refreshes == before && clears == revoked + 1, "changed identity must revoke, never rebind");
            }
        }
        try (OfficialOperationLease lease = new OfficialOperationLease()) {
            int revoked = clears;
            blocked(() -> lease.authorize(normal(), FACADE, "changed",
                    () -> { throw new IOException("offline"); }, () -> {}, () -> {}, () -> { clears++; }));
            check(clears == revoked + 1, "failed refresh must revoke partial authorization");
        }
        try (OfficialOperationLease lease = new OfficialOperationLease()) {
            int before = refreshes, revoked = clears;
            blocked(() -> lease.authorize(normal(), FACADE, "changed",
                    () -> { refreshes++; changed = true; }, () -> {}, () -> {
                        if (changed) throw new IOException("changed");
                    }, () -> { clears++; }));
            check(refreshes == before + 1 && clears == revoked + 1, "late callback must not retain authorization");
        }
        changed = false;
        try (OfficialOperationLease lease = new OfficialOperationLease()) {
            authorize(lease, normal()); int before = refreshes;
            lease.invalidate(); authorize(lease, normal());
            check(refreshes == before + 1, "local-only maintenance clears permission");
        }
        OfficialOperationLease closed = new OfficialOperationLease(); closed.close();
        blocked(() -> authorize(closed, normal()));
        System.out.println("Operation lease checks passed");
    }
}
"""
        with tempfile.TemporaryDirectory(prefix="pdsync-operation-lease-") as tmp:
            source = Path(tmp) / "LeaseChecks.java"
            source.write_text(fixture)
            java = os.environ.get("JAVA_BIN", "java")
            compiled = subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", tmp,
                                       str(ROOT / "OfficialOperationLease.java"), str(source)],
                                      capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            result = subprocess.run([java, "-cp", tmp, "io.github.xyz1001.pdsync.android.LeaseChecks"],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_foreground_scope_never_changes_appreciation_or_automatic_authorization(self):
        self.assertIn("try (OfficialOperationLease lease = new OfficialOperationLease())", MAIN)
        self.assertIn("finally { officialOperationLease.remove(); }", MAIN)
        self.assertIn("SyncRuntime.checkOfficialSyncAccess(account, owner)", MAIN)
        self.assertIn("connectionGeneration.incrementAndGet()", RUNTIME)
        self.assertIn("val entitlement = client.entitlement()", CONTROLLER)
        self.assertNotIn("OfficialOperationLease", CONTROLLER)
        auto = (ROOT / "AutoSyncService.java").read_text()
        self.assertIn("SyncRuntime.requireOfficialSyncAccess(this, this.connection, this.facade)", auto)
