package io.github.xyz1001.pdsync.root;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Looper;
import android.os.UserHandle;
import android.system.Os;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs solely inside the root app_process job. Never used by host tests. */
final class AndroidRootRuntime implements RootRuntime {
    private final AndroidRootFileSystem files = new AndroidRootFileSystem();
    private Context systemContext;
    @Override public int effectiveUid() { return Os.geteuid(); }
    @Override public RootFileSystem fileSystem() { return files; }
    private Context userContext(RootBinding requested) throws IOException {
        try {
            if (systemContext == null) {
                if (Looper.myLooper() == null) Looper.prepare();
                Class<?> type = Class.forName("android.app.ActivityThread");
                Object activityThread = type.getMethod("systemMain").invoke(null);
                systemContext = (Context) type.getMethod("getSystemContext").invoke(activityThread);
            }
            Method create = Context.class.getMethod("createContextAsUser", UserHandle.class, int.class);
            return (Context) create.invoke(systemContext, UserHandle.getUserHandleForUid(requested.uid), 0);
        } catch (ReflectiveOperationException | RuntimeException denied) {
            throw new RootFailure(RootProtocol.Status.UNRELIABLE, denied);
        }
    }
    @Override public RootBinding installed(RootBinding requested) throws IOException {
        try {
            PackageInfo info = userContext(requested).getPackageManager().getPackageInfo(requested.packageName, 0);
            ApplicationInfo app = info.applicationInfo;
            if (app == null || !requested.packageName.equals(info.packageName) || !requested.packageName.equals(app.packageName)
                    || app.uid != requested.uid || (app.flags & ApplicationInfo.FLAG_INSTALLED) == 0)
                throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH);
            Map<String, String> hashes = new TreeMap<>();
            if (app.sourceDir == null || app.splitSourceDirs != null && app.splitSourceDirs.length >= RootBinding.MAX_APKS)
                throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH);
            hashes.put(app.sourceDir, "");
            if (app.splitSourceDirs != null) for (String split : app.splitSourceDirs)
                if (split == null || hashes.put(split, "") != null) throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH);
            // Compare PM-selected metadata before opening even the expected APK files.
            if (!hashes.keySet().equals(requested.apkHashes.keySet())
                    || !(requested.dataDir.equals(app.dataDir)
                        || requested.normalizedDataDir().equals(app.dataDir)
                        || requested.userId == 0 && ("/data/data/" + requested.packageName).equals(app.dataDir)))
                throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH);
            long[] aggregateBytes = {0};
            for (String path : hashes.keySet()) hashes.put(path, files.apkHash(path, aggregateBytes));
            return new RootBinding(app.packageName, requested.userId, app.uid, app.dataDir, requested.sourceIdentity, hashes);
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH, unavailable);
        }
    }
    @Override public RootUidState.Observation observe(int boundUid) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(RootUidState.command(boundUid));
        builder.redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", "/system/bin");
        builder.environment().put("ANDROID_ROOT", "/system");
        final java.lang.Process process;
        try { process = builder.start(); } catch (IOException unavailable) { return RootUidState.Observation.unknown(); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicBoolean invalid = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[512]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (n == 0 || bytes.size() + n > RootUidState.MAX_OUTPUT) {
                        invalid.set(true); process.destroyForcibly(); return;
                    }
                    bytes.write(buffer, 0, n);
                }
            } catch (IOException failure) { invalid.set(true); }
        }, "pdsync-root-state-output");
        reader.setDaemon(true); reader.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(RootUidState.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) return RootUidState.Observation.unknown();
            reader.join(500);
            if (reader.isAlive() || invalid.get()) return RootUidState.Observation.unknown();
            return RootUidState.parse(Build.VERSION.SDK_INT, process.exitValue(), false, bytes.toByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return RootUidState.Observation.unknown();
        } finally {
            process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
        }
    }
    @Override public void quietWait() throws IOException {
        try { Thread.sleep(2000); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new RootFailure(RootProtocol.Status.CHANGED, interrupted);
        }
    }
}
