package io.github.xyz1001.pdsync.bridge;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.FileInputStream;
import java.io.IOException;

/** Platform access that differs between Android 8 (API 26) and newer releases. */
final class AndroidApiCompat {
    private AndroidApiCompat() { }

    @SuppressWarnings("deprecation")
    static int signingFlags() {
        return Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    /** Never accepts a historical signing certificate or a legacy fallback on API 28+. */
    @SuppressWarnings("deprecation")
    static Signature[] currentSigners(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? Api28.currentSigners(info) : info.signatures;
    }

    @SuppressWarnings("deprecation")
    static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? Api28.versionCode(info) : info.versionCode;
    }

    static int closeOnExecFlag() {
        // Linux UAPI O_CLOEXEC is 02000000 on Android's supported architectures.
        // Android 8 exposes Os.open but did not publish this constant until API27.
        // Keep it in the atomic open flags instead of a racy post-open update.
        return Build.VERSION.SDK_INT >= 27 ? Api27.closeOnExecFlag() : 02000000;
    }

    static int descriptorAccessMode(ParcelFileDescriptor descriptor) throws IOException, ErrnoException {
        if (Build.VERSION.SDK_INT >= 30) return Api30.descriptorFlags(descriptor) & OsConstants.O_ACCMODE;
        // No public fcntl API exists before API30. Read the kernel's flags for
        // this already-owned descriptor; never reopen the caller's file by path
        // or assume a readable descriptor is read-only. Inaccessible/malformed
        // proc data fails closed before any save mutation.
        try (FileInputStream input = new FileInputStream("/proc/self/fdinfo/" + descriptor.getFd())) {
            return DescriptorFlags.read(input) & OsConstants.O_ACCMODE;
        } catch (SecurityException error) {
            throw new IOException("Cannot verify archive descriptor access mode", error);
        }
    }

    static String statIdentity(StructStat stat) {
        String object = stat.st_dev + ":" + stat.st_ino + ":" + stat.st_mode + ":"
                + stat.st_uid + ":" + stat.st_nlink + ":" + stat.st_size + ":";
        // API 26 exposes seconds only. Keep every identity/owner/link check and the
        // repeated full-content hash scans; never interpret a timestamp as proof
        // that a background writer cannot be active.
        return object + (Build.VERSION.SDK_INT >= 27 ? Api27.timestamps(stat)
                : stat.st_mtime + ":0:" + stat.st_ctime + ":0");
    }

    // Separate classes prevent old runtimes from resolving unavailable platform
    // types, fields, or methods on the API 26 execution path.
    private static final class Api27 {
        static int closeOnExecFlag() {
            return OsConstants.O_CLOEXEC;
        }

        static String timestamps(StructStat stat) {
            return stat.st_mtim.tv_sec + ":" + stat.st_mtim.tv_nsec + ":"
                    + stat.st_ctim.tv_sec + ":" + stat.st_ctim.tv_nsec;
        }
    }

    private static final class Api28 {
        static Signature[] currentSigners(PackageInfo info) {
            return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        }

        static long versionCode(PackageInfo info) {
            return info.getLongVersionCode();
        }
    }

    private static final class Api30 {
        static int descriptorFlags(ParcelFileDescriptor descriptor) throws ErrnoException {
            return Os.fcntlInt(descriptor.getFileDescriptor(), OsConstants.F_GETFL, 0);
        }
    }
}
