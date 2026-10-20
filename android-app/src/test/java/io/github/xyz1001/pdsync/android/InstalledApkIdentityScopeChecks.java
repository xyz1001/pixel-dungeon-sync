package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.system.StructStat;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Newly reconstructed regressions, not recovered original tests; no Android/device claims. */
public final class InstalledApkIdentityScopeChecks {
    private static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
    static void rejects(Action action, String detail) throws Exception {
        checks++;
        try { action.run(); }
        catch (IOException | SecurityException expected) { return; }
        throw new AssertionError(detail);
    }
    public static void main(String[] args) throws Exception {
        reuseAndBoundaries(); invalidationAndIsolation(); packageFences(); statFences(); racesAndPaths(); api26Fallback();
        System.out.println("Reconstructed code92 installed identity scope: " + checks + " assertions passed. Host PM/stat doubles; no device/ART proof.");
    }

    static void reuseAndBoundaries() throws Exception {
        try (Fixture f = new Fixture()) {
            InstalledApkIdentityScope scope = f.scope();
            String expected = scope.apkSetSha256(f.game);
            check(f.files.hashes == 2, "outside scope fully hashes base and split");
            AutoCloseable read = scope.begin();
            check(f.files.hashes == 4, "entry hashes the complete APK set once");
            int pmBefore = f.pmReads;
            for (int i = 0; i < 20; i++) check(expected.equals(scope.apkSetSha256(f.game)), "same scoped identity");
            check(f.files.hashes == 4, "intermediate target calls do not rehash APK bytes");
            check(f.pmReads >= pmBefore + 40, "each intermediate target reads fresh resolver and PM metadata");
            rejects(scope::begin, "nested scope rejected");
            rejects(scope::requireNoReadScope, "read scope blocks mutating operation");
            check(expected.equals(scope.apkSetSha256(f.game)), "nested/mutation rejection does not revive or damage active read");
            read.close();
            check(f.files.hashes == 6, "exit fully hashes base and split");
            read.close();
            check(f.files.hashes == 6, "second close is idempotent");
            scope.requireNoReadScope();
            scope.apkSetSha256(f.game);
            check(f.files.hashes == 8, "after close no cached authority remains");
            try (AutoCloseable next = scope.begin()) { scope.apkSetSha256(f.game); }
            check(f.files.hashes == 12, "next operation gets new entry and exit hashes");
        }
    }

    static void invalidationAndIsolation() throws Exception {
        try (Fixture f = new Fixture()) {
            InstalledApkIdentityScope a = f.scope(), b = f.scope();
            AutoCloseable read = a.begin();
            b.apkSetSha256(f.game);
            check(f.files.hashes == 4, "another backend instance must perform its own full hash");
            b.requireNoReadScope();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    a.apkSetSha256(f.game);
                    a.requireNoReadScope();
                    rejects(read::close, "foreign-thread close rejected");
                    try (AutoCloseable independent = a.begin()) { a.apkSetSha256(f.game); }
                    a.invalidate(); // No active read here; must not invalidate owner's read.
                } catch (Throwable error) { failure.set(error); }
            });
            worker.start(); worker.join();
            if (failure.get() != null) throw new AssertionError("cross-thread checks", failure.get());
            check(f.files.hashes == 10, "other thread fully hashes outside scope and owns a separate scoped read");
            check(a.apkSetSha256(f.game) != null, "wrong-thread close leaves owner read active");
            a.invalidate();
            rejects(() -> a.apkSetSha256(f.game), "explicit invalidation rejects further reuse");
            rejects(read::close, "invalid scope cannot close successfully");
            a.requireNoReadScope();
            try (AutoCloseable fresh = a.begin()) { check(a.apkSetSha256(f.game) != null, "new read works after failed close cleanup"); }
        }
    }

    static void packageFences() throws Exception {
        List<Consumer<Fixture>> mutations = List.of(
            f -> f.game.packageName = "changed.package", f -> f.game.applicationInfo.packageName = "changed.app",
            f -> f.game.sharedUserId = "shared", f -> f.game.versionName = "changed",
            f -> { f.game.version++; f.game.versionCode++; }, f -> f.game.firstInstallTime++, f -> f.game.lastUpdateTime++,
            f -> f.game.applicationInfo.uid++, f -> f.game.applicationInfo.enabled = false,
            f -> f.game.applicationInfo.dataDir += "-other",
            f -> { f.game.signatures = new Signature[]{new Signature(new byte[]{9})}; f.game.signingInfo.signatures = f.game.signatures; },
            f -> f.game.applicationInfo.sourceDir = f.split.toString(),
            f -> f.game.applicationInfo.splitSourceDirs = new String[]{f.base.toString()},
            f -> f.game.splitNames = new String[]{"renamed"},
            f -> f.provider.enabled = false, f -> f.provider.grantUriPermissions = true,
            f -> f.provider.processName = Fixture.GAME,
            f -> f.game.applicationInfo.metaData.put(Fixture.META + "BRIDGE_ACCESS_PROTOCOL", ""),
            f -> f.game.applicationInfo.metaData.put(Fixture.META + "BOUND_SOURCE_APK_SHA256", "d".repeat(64)),
            f -> f.gameMembers = new String[]{Fixture.GAME, "other.package"}
        );
        for (int i = 0; i < mutations.size(); i++) {
            try (Fixture f = new Fixture()) {
                InstalledApkIdentityScope scope = f.scope();
                AutoCloseable read = scope.begin();
                mutations.get(i).accept(f);
                rejects(() -> scope.apkSetSha256(f.game), "fresh metadata fence " + i);
                rejects(read::close, "metadata failure invalidates close " + i);
                scope.requireNoReadScope();
            }
        }
        try (Fixture f = new Fixture()) {
            InstalledApkIdentityScope scope = f.scope();
            AutoCloseable read = scope.begin();
            String original = f.game.splitNames[0];
            f.game.splitNames[0] = "bad";
            rejects(() -> scope.apkSetSha256(f.game), "split name in-place mutation detected");
            f.game.splitNames[0] = original;
            rejects(() -> scope.apkSetSha256(f.game), "restored metadata cannot revive invalidated scope");
            rejects(read::close, "restored invalid read close fails");
        }
        try (Fixture f = new Fixture()) {
            f.game.splitNames = null;
            InstalledApkIdentityScope scope = f.scope();
            AutoCloseable read = scope.begin();
            f.game.splitNames = new String[0];
            rejects(() -> scope.apkSetSha256(f.game), "null and empty split-name arrays differ");
            rejects(read::close, "null/empty mismatch invalidated read");
        }
        try (Fixture f = new Fixture()) {
            InstalledApkIdentityScope scope = f.scope();
            AutoCloseable read = scope.begin();
            PackageInfo stale = new PackageInfo();
            stale.packageName = Fixture.GAME; stale.applicationInfo = f.game.applicationInfo;
            stale.version = 17; stale.versionCode = 17; stale.versionName = "v17";
            stale.firstInstallTime = 1; stale.lastUpdateTime = 2; stale.signatures = f.game.signatures; stale.signingInfo = f.game.signingInfo;
            stale.splitNames = new String[]{"stale"};
            rejects(() -> scope.apkSetSha256(stale), "caller PM snapshot must equal independent fresh PM read");
            rejects(read::close, "stale caller PM snapshot invalidates scope");
        }
        try (Fixture f = new Fixture()) {
            InstalledApkIdentityScope scope = f.scope();
            AutoCloseable read = scope.begin();
            // Resolver observes the selected state, then the independent PM read changes.
            f.afterGameLookup = () -> { if (++f.lookupHookCount == 2) f.game.applicationInfo.packageName = "interleaved.app"; };
            rejects(() -> scope.apkSetSha256(f.game), "PM changes between resolver and independent capture rejected");
            rejects(read::close, "interleaved PM failure invalidates");
        }
    }

    static void statFences() throws Exception {
        List<Consumer<StructStat>> mutations = List.of(
            s -> s.st_dev++, s -> s.st_ino++, s -> s.st_mode ^= 1, s -> s.st_uid++, s -> s.st_gid++,
            s -> s.st_nlink++, s -> s.st_size++, s -> s.st_mtim.tv_sec++, s -> s.st_mtim.tv_nsec++,
            s -> s.st_ctim.tv_sec++, s -> s.st_ctim.tv_nsec++
        );
        for (int i = 0; i < mutations.size(); i++) {
            try (Fixture f = new Fixture()) {
                InstalledApkIdentityScope scope = f.androidScope();
                AutoCloseable read = scope.begin();
                mutations.get(i).accept(Os.values.get(f.base.toString()));
                rejects(() -> scope.apkSetSha256(f.game), "real AndroidFiles stamp field " + i);
                rejects(read::close, "stamp invalidation close " + i);
            }
        }
        for (long bad : new long[]{-1, 0}) {
            try (Fixture f = new Fixture()) {
                if (bad < 0) Os.values.get(f.base.toString()).st_size = -1;
                else Os.values.get(f.base.toString()).st_mode = 0120777;
                InstalledApkIdentityScope scope = f.androidScope();
                rejects(scope::begin, "negative size or non-regular lstat rejected at entry");
                scope.requireNoReadScope();
            }
        }
    }

    static void racesAndPaths() throws Exception {
        try (Fixture f = new Fixture()) {
            InstalledApkIdentityScope scope = f.scope();
            f.files.afterHash = () -> f.files.stamp += "changed";
            rejects(scope::begin, "entry hash race caught by second stat fence");
            scope.requireNoReadScope();
            f.files.afterHash = null;
            try (AutoCloseable read = scope.begin()) { check(scope.apkSetSha256(f.game) != null, "entry failure leaves no active read"); }
        }
        try (Fixture f = new Fixture()) {
            InstalledApkIdentityScope scope = f.scope();
            AutoCloseable read = scope.begin();
            Files.writeString(f.base, "new"); // Deterministic unchanged test stamp, simulating hidden content change.
            check(scope.apkSetSha256(f.game) != null, "intermediate reads use captured hash while fences are equal");
            rejects(read::close, "exit full hash catches same-size content change despite unchanged stat seam");
            scope.requireNoReadScope();
            try (AutoCloseable next = scope.begin()) { check(scope.apkSetSha256(f.game) != null, "new operation captures changed content"); }
        }
        try (Fixture f = new Fixture()) {
            InstalledApkIdentityScope scope = f.scope();
            AutoCloseable read = scope.begin();
            f.files.afterHash = () -> f.files.stamp += "exit-race";
            rejects(read::close, "exit hash race caught by posthash metadata fence");
            scope.requireNoReadScope();
        }
        try (Fixture f = new Fixture()) {
            InstalledApkIdentityScope scope = f.scope();
            AutoCloseable read = scope.begin();
            f.files.failHash = true;
            rejects(read::close, "hash IO failure propagates on exit");
            scope.requireNoReadScope();
        }
        try (Fixture f = new Fixture()) {
            Path link = f.root.resolve("link.apk"); Files.createSymbolicLink(link, f.base);
            f.game.applicationInfo.sourceDir = link.toString();
            rejects(() -> f.scope().begin(), "canonical symlink APK path rejected");
        }
        try (Fixture f = new Fixture()) {
            f.game.applicationInfo.sourceDir = f.root + "/./base.apk";
            rejects(() -> f.scope().begin(), "noncanonical dot path rejected");
        }
        try (Fixture f = new Fixture()) {
            f.game.applicationInfo.sourceDir = "relative.apk";
            rejects(() -> f.scope().begin(), "relative APK path rejected");
        }
        try (Fixture f = new Fixture()) {
            InstalledApkIdentityScope scope = f.scope();
            AutoCloseable read = scope.begin();
            f.files.failStamp = true;
            rejects(() -> scope.apkSetSha256(f.game), "stat IO failure propagated");
            f.files.failStamp = false;
            rejects(() -> scope.apkSetSha256(f.game), "stat availability restoration cannot revive read");
            rejects(read::close, "failed stat invalidation survives close");
        }
    }

    static void api26Fallback() throws Exception {
        try (Fixture f = new Fixture()) {
            Build.VERSION.SDK_INT = 26;
            InstalledApkIdentityScope scope = f.scope();
            f.files.failStamp = true;
            try (AutoCloseable first = scope.begin(); AutoCloseable second = scope.begin()) {
                scope.requireNoReadScope();
                String before = scope.apkSetSha256(f.game);
                Files.writeString(f.base, "new");
                scope.invalidate();
                String after = scope.apkSetSha256(f.game);
                check(!before.equals(after), "API26 fully rehashes every request even during no-op scope");
                check(f.files.hashes == 4 && f.files.stamps == 0, "API26 avoids high-resolution stat completely");
            }
            try (AutoCloseable ignored = f.androidScope().begin()) { check(true, "API26 default files begin does not link stat fields"); }
            Build.VERSION.SDK_INT = 27;
            f.files.failStamp = false;
            try (AutoCloseable read = scope.begin()) { check(f.files.hashes == 6, "API27 enables entry hashing and high-resolution scope"); }
        }
    }

    static final class CountingFiles implements InstalledApkIdentityScope.FilesAccess {
        int hashes, stamps;
        String stamp = "stable";
        boolean failHash, failStamp;
        Action afterHash;
        public String sha256(File file) throws Exception {
            hashes++;
            if (failHash) throw new IOException("Injected hash failure");
            String hash = PackageTrust.sha256(file);
            if (afterHash != null) afterHash.run();
            return hash;
        }
        public String stamp(File file) throws IOException {
            stamps++;
            if (failStamp) throw new IOException("Injected stat failure");
            return file.getPath() + ":" + stamp;
        }
    }

    static final class Fixture extends PackageManager implements AutoCloseable {
        static final String GAME = "test.actual.game", ORIGIN = "a".repeat(64), SOURCE = "b".repeat(64), META = InstalledBridgeResolver.PREFIX;
        final Path root = Files.createTempDirectory("identity-scope-fixture-").toRealPath();
        final Path base = Files.writeString(root.resolve("base.apk"), "old"), split = Files.writeString(root.resolve("split.apk"), "dex");
        final PackageInfo game = new PackageInfo(), main = new PackageInfo();
        final ProviderInfo provider = new ProviderInfo();
        final CountingFiles files = new CountingFiles();
        String[] gameMembers = {GAME};
        int pmReads, lookupHookCount;
        Runnable afterGameLookup;
        final Context context = new Context() {
            public String getPackageName() { return InstalledBridgeResolver.MAIN; }
            public PackageManager getPackageManager() { return Fixture.this; }
            public File getFilesDir() { throw new AssertionError("Identity checks must not touch application data"); }
        };
        Fixture() throws Exception {
            Build.VERSION.SDK_INT = 36;
            game.packageName = GAME; game.version = 17; game.versionCode = 17; game.versionName = "v17"; game.firstInstallTime = 1; game.lastUpdateTime = 2;
            game.applicationInfo = new ApplicationInfo(); game.applicationInfo.packageName = GAME; game.applicationInfo.uid = 200; game.applicationInfo.enabled = true;
            game.applicationInfo.dataDir = "/unread/data/game"; game.applicationInfo.sourceDir = base.toString();
            game.applicationInfo.splitSourceDirs = new String[]{split.toString()}; game.splitNames = new String[]{"split"};
            game.signatures = new Signature[]{new Signature(new byte[]{1,2,3})}; game.signingInfo = new SigningInfo(); game.signingInfo.signatures = game.signatures;
            main.packageName = InstalledBridgeResolver.MAIN; main.applicationInfo = new ApplicationInfo(); main.applicationInfo.uid = 100;
            Bundle m = new Bundle(); game.applicationInfo.metaData = m;
            String[] keys = {"BRIDGE_MODE", "BRIDGE_RUNTIME_VERSION", "BRIDGE_ACCESS_PROTOCOL", "BOUND_GAME_PACKAGE", "BOUND_ORIGIN_IDENTITY", "BOUND_NAMESPACE_ID", "BOUND_SOURCE_APK_SHA256", "BOUND_GAME_VERSION_CODE", "BOUND_GAME_VERSION_NAME"};
            String[] values = {"embedded-v1", "1", "1", GAME, ORIGIN, "android-" + ORIGIN, SOURCE, "17", "v17"};
            for (int i = 0; i < keys.length; i++) m.put(META + keys[i], values[i]);
            provider.name = InstalledBridgeResolver.PROVIDER; provider.authority = "io.github.xyz1001.pdsync.embedded.g" + ORIGIN.substring(0,32) + ".files";
            provider.packageName = GAME; provider.processName = GAME + ":pdsync_bridge"; provider.enabled = true; provider.exported = true; provider.applicationInfo = game.applicationInfo;
            game.providers = new ProviderInfo[]{provider};
            Os.values.put(base.toString(), new StructStat()); Os.values.put(split.toString(), new StructStat());
        }
        InstalledApkIdentityScope scope() throws Exception { return new InstalledApkIdentityScope(context, InstalledBridgeResolver.resolve(context, GAME), files); }
        InstalledApkIdentityScope androidScope() throws Exception { return new InstalledApkIdentityScope(context, InstalledBridgeResolver.resolve(context, GAME)); }
        public PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
            pmReads++;
            if (name.equals(GAME)) { if (afterGameLookup != null) afterGameLookup.run(); return game; }
            if (name.equals(main.packageName)) return main;
            throw new NameNotFoundException();
        }
        public String[] getPackagesForUid(int uid) { return uid == 200 ? gameMembers : uid == 100 ? new String[]{InstalledBridgeResolver.MAIN} : null; }
        public ProviderInfo resolveContentProvider(String authority, int flags) { return provider.authority.equals(authority) ? provider : null; }
        @Override public void close() throws IOException {
            Os.values.remove(base.toString()); Os.values.remove(split.toString());
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
