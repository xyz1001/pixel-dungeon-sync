package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Reconstructed from verified code92 DEX; scoped read-only installed-APK identity checks. */
final class InstalledApkIdentityScope {
    private final ThreadLocal<Read> active = new ThreadLocal<>();
    private final Context context;
    private final FilesAccess files;
    private final InstalledBridgeBinding selected;

    interface FilesAccess {
        String sha256(File file) throws Exception;
        String stamp(File file) throws Exception;
    }

    InstalledApkIdentityScope(Context context, InstalledBridgeBinding selected) {
        this(context, selected, new AndroidFiles());
    }

    InstalledApkIdentityScope(Context context, InstalledBridgeBinding selected, FilesAccess files) {
        this.context = context;
        this.selected = selected;
        this.files = files;
    }

    AutoCloseable begin() throws Exception {
        // StructStat's nanosecond fields are unavailable on API26. Keep its full checks.
        if (Build.VERSION.SDK_INT < 27) return () -> {};
        if (active.get() != null) throw new IOException("Nested HOME identity scope");
        Read read = new Read();
        active.set(read);
        return read;
    }

    String apkSetSha256(PackageInfo installed) throws Exception {
        Read read = active.get();
        if (read == null) return fullHash(installed);
        read.requireSame(installed);
        return read.hash;
    }

    void requireNoReadScope() throws IOException {
        if (active.get() != null) throw new IOException("Mutation inside read-only HOME identity scope");
    }

    void invalidate() {
        Read read = active.get();
        if (read != null) read.invalid = true;
    }

    private String fullHash(PackageInfo installed) throws Exception {
        List<String> hashes = new ArrayList<>();
        for (File apk : PackageTrust.installedApks(installed)) hashes.add(files.sha256(apk));
        return ApkSetIdentity.digest(hashes);
    }

    private List<String> capture(PackageInfo supplied) throws Exception {
        InstalledBridgeBinding current = InstalledBridgeResolver.requireUnchanged(context, selected);
        PackageInfo installed = PackageTrust.installed(context, selected.gamePackage);
        List<String> token = packageToken(installed);
        if (supplied != null && !token.equals(packageToken(supplied)))
            throw new IOException("Installed package changed during HOME identity check");
        List<File> apks = PackageTrust.installedApks(installed);
        List<String> paths = new ArrayList<>();
        for (File apk : apks) paths.add(apk.getAbsolutePath());
        if (!current.gamePackage.equals(installed.packageName)
                || !current.gamePackage.equals(installed.applicationInfo.packageName)
                || installed.sharedUserId != null || !installed.applicationInfo.enabled
                || current.uid != installed.applicationInfo.uid
                || !current.dataDir.equals(installed.applicationInfo.dataDir)
                || current.gameVersionCode != PackageTrust.versionCode(installed)
                || !current.gameVersionName.equals(installed.versionName == null ? "" : installed.versionName)
                || current.firstInstallTime != installed.firstInstallTime
                || current.lastUpdateTime != installed.lastUpdateTime
                || !current.signingCertificateSha256.equals(PackageTrust.signer(installed))
                || !paths.equals(current.apkPaths))
            throw new IOException("Installed package changed between PM checks");
        Collections.addAll(token, current.selectionToken(), current.authority, current.namespaceId,
                current.dataDir, current.bridgeRuntimeVersion, current.accessProtocol);
        for (File apk : apks) {
            if (!apk.isAbsolute() || !apk.getAbsoluteFile().equals(apk.getCanonicalFile()))
                throw new IOException("Unstable installed APK path");
            token.add(files.stamp(apk));
        }
        return Collections.unmodifiableList(token);
    }

    private static List<String> packageToken(PackageInfo installed) throws Exception {
        if (installed == null || installed.applicationInfo == null)
            throw new IOException("Missing installed package");
        List<String> token = new ArrayList<>();
        Collections.addAll(token, installed.packageName, installed.applicationInfo.packageName,
                installed.sharedUserId, installed.versionName, Long.toString(PackageTrust.versionCode(installed)),
                Long.toString(installed.firstInstallTime), Long.toString(installed.lastUpdateTime),
                Integer.toString(installed.applicationInfo.uid), Boolean.toString(installed.applicationInfo.enabled),
                installed.applicationInfo.dataDir, PackageTrust.signer(installed), installed.applicationInfo.sourceDir);
        addArray(token, installed.applicationInfo.splitSourceDirs);
        addArray(token, installed.splitNames);
        return token;
    }

    private static void addArray(List<String> token, String[] values) {
        token.add(values == null ? null : Integer.toString(values.length));
        if (values != null) token.addAll(Arrays.asList(values));
    }

    private final class Read implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        private boolean invalid;
        private String hash;
        private List<String> token;

        Read() throws Exception {
            token = capture(null);
            hash = fullHash(PackageTrust.installed(context, selected.gamePackage));
            requireSame(null);
        }

        private void requireSame(PackageInfo supplied) throws Exception {
            if (Thread.currentThread() != owner || closed || invalid)
                throw new IOException("Inactive HOME identity scope");
            try {
                if (!token.equals(capture(supplied))) throw new IOException("HOME installation identity changed");
            } catch (Exception error) {
                invalid = true;
                throw error;
            }
        }

        @Override public void close() throws Exception {
            if (Thread.currentThread() != owner) throw new IOException("HOME identity scope thread changed");
            if (closed) return;
            try {
                requireSame(null);
                String after = fullHash(PackageTrust.installed(context, selected.gamePackage));
                requireSame(null);
                if (!Objects.equals(hash, after)) throw new IOException("HOME APK contents changed");
            } finally {
                closed = true;
                token = null;
                hash = null;
                active.remove();
            }
        }
    }

    private static final class AndroidFiles implements FilesAccess {
        @Override public String sha256(File file) throws Exception { return PackageTrust.sha256(file); }

        @Override public String stamp(File file) throws Exception {
            if (Build.VERSION.SDK_INT < 27) throw new IOException("High-resolution installed APK stamp unavailable");
            StructStat stat = Os.lstat(file.getPath());
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 0)
                throw new IOException("Installed APK is not a regular file");
            return stat.st_dev + ":" + stat.st_ino + ":" + stat.st_mode + ":" + stat.st_uid + ":" + stat.st_gid
                    + ":" + stat.st_nlink + ":" + stat.st_size + ":" + Times.value(stat);
        }
    }

    private static final class Times {
        static String value(StructStat stat) {
            return stat.st_mtim.tv_sec + ":" + stat.st_mtim.tv_nsec + ":" + stat.st_ctim.tv_sec + ":" + stat.st_ctim.tv_nsec;
        }
    }
}
