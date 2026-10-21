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
import java.io.ByteArrayInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

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
        return descriptorAccessMode(descriptor, stage -> { });
    }

    static int descriptorAccessMode(ParcelFileDescriptor descriptor, Consumer<FileFailureDetails.Stage> stage)
            throws IOException, ErrnoException {
        try {
            return checkedDescriptorAccessMode(descriptor, stage);
        } catch (SecurityException denied) {
            // Preserve the exact probe stage through provider cleanup, retaining
            // the cause so the redacted diagnostic classifies ACCESS_DENIED.
            throw new IOException("Cannot verify archive descriptor access mode", denied);
        }
    }

    private static int checkedDescriptorAccessMode(ParcelFileDescriptor descriptor, Consumer<FileFailureDetails.Stage> stage)
            throws IOException, ErrnoException {
        stage.accept(FileFailureDetails.Stage.ARCHIVE_DESCRIPTOR_FLAGS);
        Integer flags = Build.VERSION.SDK_INT >= 30
                ? Integer.valueOf(Api30.descriptorFlags(descriptor)) : legacyDescriptorFlags(descriptor);
        if (flags != null && (flags & OsConstants.O_ACCMODE) != OsConstants.O_RDONLY)
            return flags & OsConstants.O_ACCMODE;
        // Public Os.pread/pwrite exist from API21; public fcntlInt only from API30.
        // The caller must first fstat and validate a bounded, caller-owned regular
        // file. Prove readability on this exact descriptor: O_PATH also makes
        // pwrite fail with EBADF and must never count as read-only. A positioned
        // one-byte read accepts EOF but does not change the shared file offset.
        FileDescriptor fd = descriptor.getFileDescriptor();
        byte[] probe = new byte[1];
        stage.accept(FileFailureDetails.Stage.ARCHIVE_DESCRIPTOR_READ);
        int read = Os.pread(fd, probe, 0, 1, 0L);
        if (read < 0 || read > 1) throw new IOException("Cannot verify archive descriptor readability");
        if (flags != null) return OsConstants.O_RDONLY;
        stage.accept(FileFailureDetails.Stage.ARCHIVE_DESCRIPTOR_WRITE);
        try {
            // Only unavailable proc data needs this fallback. This protocol uses
            // caller-created private local archives, not arbitrary pseudo-files.
            // A nonempty backing array ensures the API26 Java/JNI path reaches
            // the syscall; byteCount is strictly zero and the offset is fixed.
            // No bytes can be written, including when given a writable archive.
            if (Os.pwrite(fd, probe, 0, 0, 0L) != 0)
                throw new IOException("Cannot verify archive descriptor access mode");
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.EBADF) return OsConstants.O_RDONLY;
            throw error; // EACCES, EPERM, EINVAL and every other error fail closed.
        }
        return OsConstants.O_RDWR; // Writable; the caller must reject it.
    }

    private static Integer legacyDescriptorFlags(ParcelFileDescriptor descriptor) throws IOException {
        // Retain the stronger kernel flags when proc is accessible. On OEMs that
        // hide fdinfo, fall back without reopening the caller's archive by path.
        final InputStream input;
        try {
            input = new FileInputStream("/proc/self/fdinfo/" + descriptor.getFd());
        } catch (IOException | SecurityException unavailable) {
            return null;
        }
        try (InputStream owned = input) {
            return readLegacyDescriptorFlags(owned);
        }
    }

    private static Integer readLegacyDescriptorFlags(InputStream input) throws IOException {
        byte[] data = new byte[4097];
        int size = 0;
        try {
            int count;
            while (size < data.length && (count = input.read(data, size, data.length - size)) != -1)
                size += count;
        } catch (IOException | SecurityException unavailable) {
            return null;
        }
        // Parsing is outside the fallback catch: readable malformed/oversize
        // data must fail closed, never be replaced by the weaker probe result.
        return DescriptorFlags.read(new ByteArrayInputStream(data, 0, size));
    }

    static long modifiedMillis(StructStat stat) throws IOException {
        long seconds = stat.st_mtime;
        if (seconds < Long.MIN_VALUE / 1000 || seconds > (Long.MAX_VALUE - 999) / 1000) throw new IOException("Invalid file timestamp");
        return seconds * 1000 + (Build.VERSION.SDK_INT >= 27 ? Api27.modifiedNanos(stat) / 1000000 : 0);
    }
    static String statIdentity(StructStat stat) {
        String object = stat.st_dev + ":" + stat.st_ino + ":" + stat.st_mode + ":"
                + stat.st_uid + ":" + stat.st_nlink + ":" + stat.st_size + ":";
        // API 26 exposes seconds only. Keep every identity/owner/link check and the
        // per-file descriptor checks; never interpret a timestamp as proof
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

        static long modifiedNanos(StructStat stat) { return stat.st_mtim.tv_nsec; }

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
