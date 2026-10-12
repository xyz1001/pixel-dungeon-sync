package io.github.xyz1001.pdsync.root;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Looper;
import android.os.UserHandle;
import android.system.Os;
import io.github.xyz1001.pdsync.root.mutation.AndroidMutationOs;
import io.github.xyz1001.pdsync.root.mutation.MutationOs;
import java.io.File;
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
    private RootInstalledStamp installedStamp;
    private static final String MAIN_PACKAGE="io.github.xyz1001.pdsync.android";
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
    private PackageInfo packageInfo(RootBinding requested) throws IOException {
        try {
            PackageInfo info=userContext(requested).getPackageManager().getPackageInfo(requested.packageName,0);
            ApplicationInfo app=info.applicationInfo;
            if(app==null || !requested.packageName.equals(info.packageName) || !requested.packageName.equals(app.packageName)
                    || app.uid!=requested.uid || (app.flags&ApplicationInfo.FLAG_INSTALLED)==0 || !app.enabled
                    || app.sourceDir==null || app.splitSourceDirs!=null&&app.splitSourceDirs.length>=RootBinding.MAX_APKS)
                throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH);
            Map<String,String> paths=new TreeMap<>();paths.put(app.sourceDir,"");
            if(app.splitSourceDirs!=null)for(String split:app.splitSourceDirs)
                if(split==null || paths.put(split,"")!=null)throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH);
            if(!paths.keySet().equals(requested.apkHashes.keySet())
                    || !(requested.dataDir.equals(app.dataDir)||requested.normalizedDataDir().equals(app.dataDir)
                    || requested.userId==0&&("/data/data/"+requested.packageName).equals(app.dataDir)))
                throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH);
            return info;
        }catch(android.content.pm.PackageManager.NameNotFoundException unavailable) {
            throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH,unavailable);
        }
    }
    private RootInstalledStamp stamp(RootBinding requested,PackageInfo info)throws IOException {
        Map<String,String> metadata=new TreeMap<>();
        for(String path:requested.apkHashes.keySet())metadata.put(path,files.apkMetadata(path));
        long version=Build.VERSION.SDK_INT>=28?NewVersion.code(info):(info.versionCode&0xffffffffL);
        return new RootInstalledStamp(requested,version,info.firstInstallTime,info.lastUpdateTime,metadata);
    }
    private static final class NewVersion { static long code(PackageInfo info){return info.getLongVersionCode();} }
    @Override public RootBinding installed(RootBinding requested) throws IOException {
        installedStamp=null;
        PackageInfo before=packageInfo(requested);RootInstalledStamp first=stamp(requested,before);
        Map<String,String> hashes=new TreeMap<>();long[] aggregateBytes={0};
        for(String path:requested.apkHashes.keySet())hashes.put(path,files.apkHash(path,aggregateBytes));
        PackageInfo after=packageInfo(requested);RootInstalledStamp last=stamp(requested,after);
        if(!first.same(last))throw new RootFailure(RootProtocol.Status.CHANGED);
        RootBinding actual=new RootBinding(after.packageName,requested.userId,after.applicationInfo.uid,
                after.applicationInfo.dataDir,requested.sourceIdentity,hashes);
        if(!requested.matchesInstalled(actual))throw new RootFailure(RootProtocol.Status.BINDING_MISMATCH);
        installedStamp=last;
        return actual;
    }
    @Override public void verifyInstalledMetadata(RootBinding requested)throws IOException {
        RootInstalledStamp expected=installedStamp;
        if(expected==null || !expected.binds(requested)) { installed(requested);return; }
        if(!expected.same(stamp(requested,packageInfo(requested))))
            throw new RootFailure(RootProtocol.Status.CHANGED);
    }
    @Override public MutationOs readMutationOs(RootBinding requested)throws IOException {
        verifyInstalledMetadata(requested);
        return new AndroidMutationOs();
    }
    @Override public MutationOs mutationOs(RootBinding requested)throws IOException {
        verifyInstalledMetadata(requested);
        try {
            PackageInfo own=userContext(requested).getPackageManager().getPackageInfo(MAIN_PACKAGE,0);
            ApplicationInfo app=own.applicationInfo;
            if(app==null || !MAIN_PACKAGE.equals(own.packageName) || !MAIN_PACKAGE.equals(app.packageName)
                    || app.uid/100000!=requested.userId || (app.flags&ApplicationInfo.FLAG_INSTALLED)==0
                    || app.sourceDir==null || app.nativeLibraryDir==null || !app.enabled)
                throw new IOException("ROOT_ATOMIC_LIBRARY_UNAVAILABLE");
            // CLASSPATH is fixed by our main-side launcher, never supplied by a target/archive field.
            // Ensure the running code and PM-selected main install are the same installation.
            String classpath=System.getenv("CLASSPATH");
            if(classpath==null || !classpath.equals(app.sourceDir)
                    || !new File(classpath).getCanonicalPath().equals(classpath))
                throw new IOException("ROOT_ATOMIC_LIBRARY_UNAVAILABLE");
            RootAtomicRename atomic=RootAtomicRename.loadFromNativeLibraryDir(app.nativeLibraryDir);
            PackageInfo after=userContext(requested).getPackageManager().getPackageInfo(MAIN_PACKAGE,0);
            if(after.applicationInfo==null || app.uid!=after.applicationInfo.uid
                    || own.firstInstallTime!=after.firstInstallTime || own.lastUpdateTime!=after.lastUpdateTime
                    || !app.sourceDir.equals(after.applicationInfo.sourceDir)
                    || !app.nativeLibraryDir.equals(after.applicationInfo.nativeLibraryDir))
                throw new IOException("ROOT_ATOMIC_LIBRARY_UNAVAILABLE");
            return new AndroidMutationOs(atomic);
        }catch(android.content.pm.PackageManager.NameNotFoundException | IOException unavailable) {
            throw new MutationOs.Failure(MutationOs.Error.UNSUPPORTED,unavailable);
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
