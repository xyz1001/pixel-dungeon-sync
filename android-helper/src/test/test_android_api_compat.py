#!/usr/bin/env python3
"""Run production platform adapters against deliberately incomplete Android stubs.

This checks JVM linkage/dispatch, not Android framework, ART, SELinux or OEM
behavior. API26 runtime stubs omit SigningInfo, StructTimespec and newer members.
"""
import errno
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

BASE = Path(__file__).resolve().parents[1]
PRODUCTION = BASE / "main/java/io/github/xyz1001/pdsync/bridge"
JAVA = os.environ.get("JAVA_BIN", "java")


def write_java(root, name, body):
    path = root / (name.replace(".", "/") + ".java")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(body), encoding="utf-8")
    return path


def platform_stubs(root, sdk):
    files = []
    def add(name, body):
        files.append(write_java(root, name, body))
    add("android.os.Build", """
        package android.os;
        public final class Build {
            public static final class VERSION {
                public static int SDK_INT = Integer.parseInt(System.getProperty("stub.sdk"));
            }
        }
    """)
    flags = "public static final int GET_SIGNING_CERTIFICATES = 134217728;" if sdk >= 28 else ""
    add("android.content.pm.PackageManager", """
        package android.content.pm;
        public class PackageManager {
            public static final int GET_SIGNATURES = 64;
    """ + flags + "}")
    add("android.content.pm.Signature", """
        package android.content.pm;
        public class Signature {
            private final byte[] bytes;
            public Signature(byte[] bytes) { this.bytes = bytes; }
            public byte[] toByteArray() { return bytes; }
        }
    """)
    package_extra = """
        public SigningInfo signingInfo;
        public int versionCodeMajor;
        public long getLongVersionCode() {
            return ((long) versionCodeMajor << 32) | (versionCode & 0xffffffffL);
        }
    """ if sdk >= 28 else ""
    add("android.content.pm.PackageInfo", """
        package android.content.pm;
        public class PackageInfo {
            public int versionCode;
            public Signature[] signatures;
    """ + package_extra + "}")
    if sdk >= 28:
        add("android.content.pm.SigningInfo", """
            package android.content.pm;
            public class SigningInfo {
                public Signature[] signers;
                public Signature[] getApkContentsSigners() { return signers; }
                public Signature[] getSigningCertificateHistory() {
                    throw new AssertionError("Historical signer must never authorize the main app");
                }
            }
        """)
    add("android.os.ParcelFileDescriptor", """
        package android.os;
        public class ParcelFileDescriptor {
            public static final java.io.FileDescriptor TEST_DESCRIPTOR = new java.io.FileDescriptor();
            public int getFd() { return Integer.parseInt(System.getProperty("stub.fd", "-1")); }
            public java.io.FileDescriptor getFileDescriptor() { return TEST_DESCRIPTOR; }
        }
    """)
    add("android.system.ErrnoException", """
        package android.system;
        public class ErrnoException extends Exception {
            public final int errno;
            public ErrnoException(String operation, int errno) { super(operation); this.errno = errno; }
        }
    """)
    os_extra = """
        public static int fcntlInt(java.io.FileDescriptor fd, int cmd, int arg) throws ErrnoException {
            if (fd != android.os.ParcelFileDescriptor.TEST_DESCRIPTOR || cmd != OsConstants.F_GETFL || arg != 0)
                throw new AssertionError("Unexpected fcntl call");
            fcntlCalls++;
            if (Boolean.getBoolean("stub.flagsDenied")) throw new SecurityException("flags denied");
            int error = Integer.getInteger("stub.flagsError", 0);
            if (error != 0) throw new ErrnoException("fcntl", error);
            return Integer.parseInt(System.getProperty("stub.flags"));
        }
    """ if sdk >= 30 else ""
    add("android.system.Os", """
        package android.system;
        public class Os {
            public static int readCalls, writeCalls, fcntlCalls, statCalls, statErrorAt;
            public static StructStat statBefore = new StructStat(), statAfter = new StructStat();
            public static StructStat fstat(java.io.FileDescriptor fd) throws ErrnoException {
                if (fd != android.os.ParcelFileDescriptor.TEST_DESCRIPTOR) throw new AssertionError("Wrong stat descriptor");
                statCalls++;
                if (statCalls == statErrorAt) throw new ErrnoException("fstat", 9);
                return statCalls == 1 ? statBefore : statAfter;
            }
            public static void reset() { readCalls = writeCalls = fcntlCalls = 0; }
            private static void arguments(java.io.FileDescriptor fd, byte[] bytes, int offset, int count, long position, boolean writing) {
                if (fd != android.os.ParcelFileDescriptor.TEST_DESCRIPTOR || bytes == null || bytes.length != 1
                        || offset != 0 || count != (writing ? 0 : 1) || position != 0L)
                    throw new AssertionError("Probe must use same FD, one-byte buffer, fixed offset and zero-byte write");
            }
            public static int pread(java.io.FileDescriptor fd, byte[] bytes, int offset, int count, long position)
                    throws ErrnoException, java.io.InterruptedIOException {
                arguments(fd, bytes, offset, count, position, false); readCalls++;
                if (Boolean.getBoolean("stub.readDenied")) throw new SecurityException("read denied");
                if (Boolean.getBoolean("stub.readInterrupted")) throw new java.io.InterruptedIOException("read interrupted");
                int error = Integer.getInteger("stub.readError", 0);
                int mode = Integer.getInteger("stub.flags", 0) & OsConstants.O_ACCMODE;
                if (error != 0) throw new ErrnoException("pread", error);
                if (mode == OsConstants.O_WRONLY || !"regular".equals(System.getProperty("stub.kind", "regular")))
                    throw new ErrnoException("pread", OsConstants.EBADF);
                return Integer.getInteger("stub.readResult", 1);
            }
            public static int pwrite(java.io.FileDescriptor fd, byte[] bytes, int offset, int count, long position)
                    throws ErrnoException, java.io.InterruptedIOException {
                arguments(fd, bytes, offset, count, position, true); writeCalls++;
                if (readCalls != 1) throw new AssertionError("Readability must be proven before pwrite");
                if (Boolean.getBoolean("stub.writeDenied")) throw new SecurityException("write denied");
                if (Boolean.getBoolean("stub.writeInterrupted")) throw new java.io.InterruptedIOException("write interrupted");
                int error = Integer.getInteger("stub.writeError", 0);
                if (error != 0) throw new ErrnoException("pwrite", error);
                if ((Integer.getInteger("stub.flags", 0) & OsConstants.O_ACCMODE) == OsConstants.O_RDONLY)
                    throw new ErrnoException("pwrite", OsConstants.EBADF);
                return Integer.getInteger("stub.writeResult", 0);
            }
    """ + os_extra + "}")
    cloexec = "public static final int O_CLOEXEC = Integer.parseInt(\"524288\");" if sdk >= 27 else ""
    add("android.system.OsConstants", """
        package android.system;
        public class OsConstants {
            public static final int O_ACCMODE = 3, F_GETFL = 3, O_RDONLY = 0, O_WRONLY = 1, O_RDWR = 2, EBADF = 9;
            public static boolean S_ISREG(int mode) { return (mode & 0170000) == 0100000; }
    """ + cloexec + "}")
    stat_extra = """
        public StructTimespec st_mtim = new StructTimespec(13, 123);
        public StructTimespec st_ctim = new StructTimespec(17, 456);
    """ if sdk >= 27 else ""
    add("android.system.StructStat", """
        package android.system;
        public class StructStat {
            public long st_dev = 1, st_ino = 2, st_nlink = 1, st_size = 12;
            public int st_mode = 32768, st_uid = 10001;
            public long st_mtime = 13, st_ctime = 17;
    """ + stat_extra + "}")
    if sdk >= 27:
        add("android.system.StructTimespec", """
            package android.system;
            public class StructTimespec {
                public final long tv_sec, tv_nsec;
                public StructTimespec(long seconds, long nanos) { tv_sec = seconds; tv_nsec = nanos; }
            }
        """)
    return files


DRIVER = r'''
package io.github.xyz1001.pdsync.bridge;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.system.StructStat;
import android.system.Os;
import android.system.ErrnoException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import android.os.ParcelFileDescriptor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class AndroidApiCompatTests {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static byte[][] bytes(Signature[] signers) {
        if (signers == null) return null;
        byte[][] result = new byte[signers.length][];
        for (int i = 0; i < signers.length; i++) result[i] = signers[i] == null ? null : signers[i].toByteArray();
        return result;
    }
    private static void denied(String pin, Signature[] signers, String reason) {
        try { MainCallerPolicy.requirePinnedSigner(pin, bytes(signers)); }
        catch (SecurityException expected) { assertions++; return; }
        throw new AssertionError("Expected refusal: " + reason);
    }
    private static Object modernSigners(PackageInfo info, Signature[] signers) throws Exception {
        Class<?> type = Class.forName("android.content.pm.SigningInfo");
        Object signingInfo = type.getConstructor().newInstance();
        type.getField("signers").set(signingInfo, signers);
        PackageInfo.class.getField("signingInfo").set(info, signingInfo);
        return signingInfo;
    }
    private interface Action { void run() throws Exception; }
    private static void rejected(Class<? extends Throwable> type, Action action, String reason) throws Exception {
        try { action.run(); }
        catch (Throwable expected) {
            check(type.isInstance(expected), reason + " must preserve failure type: " + expected);
            return;
        }
        throw new AssertionError("Expected refusal: " + reason);
    }
    private static void resetProbe(int flags) {
        Os.reset();
        System.setProperty("stub.flags", Integer.toString(flags));
        System.setProperty("stub.kind", "regular");
        for (String key : new String[]{"flagsError", "flagsDenied", "readError", "writeError", "readResult", "writeResult",
                "readDenied", "writeDenied", "readInterrupted", "writeInterrupted"})
            System.clearProperty("stub." + key);
    }
    private static int mode() throws Exception { return AndroidApiCompat.descriptorAccessMode(new ParcelFileDescriptor()); }
    private static void knownFlagsReadabilityTests(int sdk, int access) throws Exception {
        if (access != 0) return;
        for (String kind : new String[]{"path-only", "closed"}) {
            resetProbe(0); System.setProperty("stub.kind", kind);
            rejected(ErrnoException.class, () -> mode(), "known readonly flags still reject " + kind);
            check(Os.writeCalls == 0, "available flags never need pwrite");
            check(Os.fcntlCalls == (sdk >= 30 ? 1 : 0), "API-specific flags retained");
        }
        resetProbe(0); System.setProperty("stub.readError", "13");
        rejected(ErrnoException.class, () -> mode(), "known readonly flags cannot bypass unreadable descriptor");
        check(Os.writeCalls == 0, "read-denied flags path never invokes fallback");
    }
    private static Integer procFlags(java.io.InputStream input) throws Exception {
        java.lang.reflect.Method read = AndroidApiCompat.class.getDeclaredMethod("readLegacyDescriptorFlags", java.io.InputStream.class);
        read.setAccessible(true);
        try { return (Integer) read.invoke(null, input); }
        catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw (Error) cause;
        }
    }
    private static void procDataTests() throws Exception {
        for (int access : new int[]{0, 1, 2}) {
            byte[] value = ("pos: 0\nflags: " + access + "\n").getBytes(StandardCharsets.US_ASCII);
            check(procFlags(new ByteArrayInputStream(value)) == access, "readable proc flags retained");
        }
        for (String value : new String[]{"", "pos: 0\n", "flags: 8\n", "flags: 0\nflags: 2\n"})
            rejected(IOException.class, () -> procFlags(new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII))),
                    "malformed readable proc data never falls back");
        rejected(IOException.class, () -> procFlags(new ByteArrayInputStream(new byte[4097])), "oversize proc data never falls back");
        check(procFlags(new java.io.InputStream() {
            public int read() throws IOException { throw new IOException("unavailable"); }
        }) == null, "proc read IO denial allows fallback");
        check(procFlags(new java.io.InputStream() {
            public int read() { throw new SecurityException("unavailable"); }
        }) == null, "proc read security denial allows fallback");
        check(procFlags(new java.io.InputStream() {
            private boolean first = true;
            public int read() throws IOException { throw new IOException("unavailable"); }
            public int read(byte[] b, int off, int len) throws IOException {
                if (!first) throw new IOException("read interrupted before complete flags");
                first = false; b[off] = 'f'; return 1;
            }
        }) == null, "partial proc read failure does not parse incomplete flags");
        check(Os.writeCalls == 0, "malformed or readable proc tests never probe writes");
    }
    private static RestoreDescriptorGate gate() {
        resetProbe(0); System.setProperty("stub.fd", "-1");
        Os.statCalls = 0; Os.statErrorAt = 0;
        Os.statBefore = new StructStat(); Os.statAfter = new StructStat();
        return new RestoreDescriptorGate();
    }
    private static void descriptorGateTests() throws Exception {
        RestoreDescriptorGate good = gate(); good.validate(new ParcelFileDescriptor(), 10001);
        check(good.stage == FileFailureDetails.Stage.EXTRACT_ARCHIVE, "valid archive reaches extraction stage");
        check(Os.statCalls == 2, "same descriptor rechecked after readonly proof");
        java.nio.file.Path empty = java.nio.file.Files.createTempDirectory("pdsync-empty-archive-");
        try {
            rejected(java.util.zip.ZipException.class, () -> SaveArchive.extract(new ByteArrayInputStream(new byte[0]), empty.toFile()),
                    "readable empty descriptor is still rejected by actual ZIP validation");
        } finally { java.nio.file.Files.delete(empty); }
        for (int change = 0; change < 7; change++) {
            RestoreDescriptorGate invalid = gate();
            if (change == 0) Os.statBefore.st_mode = 0040000;
            if (change == 1) Os.statBefore.st_uid++;
            if (change == 2) Os.statBefore.st_nlink = 0;
            if (change == 3) Os.statBefore.st_nlink = 2;
            if (change == 4) Os.statBefore.st_size = -1;
            if (change == 5) Os.statBefore.st_size = SaveArchive.MAX_BYTES + 1;
            if (change == 6) Os.statBefore.st_mode = 0010000;
            rejected(BridgeFailure.class, () -> invalid.validate(new ParcelFileDescriptor(), 10001), "unsafe fstat precondition " + change);
            check(Os.readCalls == 0 && Os.writeCalls == 0 && Os.fcntlCalls == 0, "unsafe object never probed");
            check(invalid.stage == FileFailureDetails.Stage.ARCHIVE_DESCRIPTOR_STAT, "precondition stage retained");
        }
        for (int at : new int[]{1, 2}) {
            RestoreDescriptorGate invalid = gate(); Os.statErrorAt = at;
            rejected(ErrnoException.class, () -> invalid.validate(new ParcelFileDescriptor(), 10001), "fstat failure " + at);
            check(invalid.stage == (at == 1 ? FileFailureDetails.Stage.ARCHIVE_DESCRIPTOR_STAT
                    : FileFailureDetails.Stage.ARCHIVE_DESCRIPTOR_RECHECK), "precise fstat stage");
        }
        for (int change = 0; change < 7; change++) {
            RestoreDescriptorGate invalid = gate();
            if (change == 0) Os.statAfter.st_ino++;
            if (change == 1) Os.statAfter.st_uid++;
            if (change == 2) Os.statAfter.st_nlink++;
            if (change == 3) Os.statAfter.st_size++;
            if (change == 4) Os.statAfter.st_mtime++;
            if (change == 5) Os.statAfter.st_ctime++;
            if (change == 6) Os.statAfter.st_dev++;
            // Modern identity uses StructTimespec, so change those fields too.
            if (change == 4 || change == 5) {
                try {
                    java.lang.reflect.Field field = StructStat.class.getField(change == 4 ? "st_mtim" : "st_ctim");
                    field.set(Os.statAfter, field.getType().getConstructor(long.class, long.class).newInstance(99L, 1L));
                } catch (NoSuchFieldException legacy) { }
            }
            rejected(BridgeFailure.class, () -> invalid.validate(new ParcelFileDescriptor(), 10001), "identity changed during access proof " + change);
            check(invalid.stage == FileFailureDetails.Stage.ARCHIVE_DESCRIPTOR_RECHECK, "changed descriptor never extracted");
        }
        RestoreDescriptorGate unreadable = gate(); System.setProperty("stub.readError", "13");
        rejected(ErrnoException.class, () -> unreadable.validate(new ParcelFileDescriptor(), 10001), "unreadable archive");
        check(unreadable.stage == FileFailureDetails.Stage.ARCHIVE_DESCRIPTOR_READ, "pread failure stage");
        if (android.os.Build.VERSION.SDK_INT < 30) {
            RestoreDescriptorGate denied = gate(); System.setProperty("stub.writeError", "13");
            rejected(ErrnoException.class, () -> denied.validate(new ParcelFileDescriptor(), 10001), "zero-byte probe denied");
            check(denied.stage == FileFailureDetails.Stage.ARCHIVE_DESCRIPTOR_WRITE, "pwrite failure stage");
        } else {
            RestoreDescriptorGate denied = gate(); System.setProperty("stub.flagsError", "13");
            rejected(ErrnoException.class, () -> denied.validate(new ParcelFileDescriptor(), 10001), "fcntl failure");
            check(denied.stage == FileFailureDetails.Stage.ARCHIVE_DESCRIPTOR_FLAGS, "fcntl failure stage");
            check(Os.readCalls == 0 && Os.writeCalls == 0, "modern flags error never falls back");
        }
        String[] securityOperations = android.os.Build.VERSION.SDK_INT >= 30
                ? new String[]{"flags", "read"} : new String[]{"read", "write"};
        for (String operation : securityOperations) {
            RestoreDescriptorGate denied = gate(); System.setProperty("stub." + operation + "Denied", "true");
            try { denied.validate(new ParcelFileDescriptor(), 10001); throw new AssertionError("Security denial accepted"); }
            catch (IOException failure) {
                check(failure.getCause() instanceof SecurityException, "security denial cause retained");
                check(FileFailureDetails.classify(failure, -1) == FileFailureDetails.Error.ACCESS_DENIED, "security denial classified");
                check(denied.stage.name().equals("ARCHIVE_DESCRIPTOR_" + operation.toUpperCase(java.util.Locale.ROOT)),
                        "security denial precise stage retained");
            }
        }
        RestoreDescriptorGate writable = gate(); System.setProperty("stub.flags", "2");
        rejected(BridgeFailure.class, () -> writable.validate(new ParcelFileDescriptor(), 10001), "writable archive");
        check(Os.statCalls == 1, "writable archive never rechecked or extracted");
    }
    private static void descriptorFallbackTests() throws Exception {
        // fdinfo is deliberately unavailable, while the mocked owned FD remains
        // readable. No newer Android APIs exist on the API26 runtime classpath.
        System.setProperty("stub.fd", "-1");
        resetProbe(0);
        check(mode() == 0, "readonly regular descriptor works with absent proc data");
        check(Os.readCalls == 1 && Os.writeCalls == 1 && Os.fcntlCalls == 0, "exact legacy probe calls");
        resetProbe(0); System.setProperty("stub.readResult", "0");
        check(mode() == 0, "empty readonly file passes access check; ZIP validator rejects later");
        resetProbe(2);
        check(mode() != 0, "writable regular descriptor rejected with absent proc data");
        check(Os.readCalls == 1 && Os.writeCalls == 1, "writable check still writes zero bytes");
        resetProbe(1);
        rejected(ErrnoException.class, () -> mode(), "write-only descriptor");
        check(Os.writeCalls == 0, "unreadable descriptor never reaches pwrite");
        for (String kind : new String[]{"path-only", "closed"}) {
            resetProbe(0); System.setProperty("stub.kind", kind);
            rejected(ErrnoException.class, () -> mode(), kind + " must not mistake EBADF for readonly");
            check(Os.writeCalls == 0, kind + " rejected by readability proof");
        }
        for (int error : new int[]{1, 4, 5, 9, 13, 22, 29, 95}) {
            resetProbe(0); System.setProperty("stub.readError", Integer.toString(error));
            rejected(ErrnoException.class, () -> mode(), "pread errno=" + error);
            check(Os.writeCalls == 0, "read failure cannot reach readonly proof");
        }
        for (int error : new int[]{1, 4, 5, 13, 22, 28, 29, 30, 95}) {
            resetProbe(0); System.setProperty("stub.writeError", Integer.toString(error));
            rejected(ErrnoException.class, () -> mode(), "pwrite errno=" + error + " is not readonly proof");
        }
        for (String operation : new String[]{"read", "write"}) {
            resetProbe(0); System.setProperty("stub." + operation + "Denied", "true");
            rejected(IOException.class, () -> mode(), operation + " security denial");
            resetProbe(0); System.setProperty("stub." + operation + "Interrupted", "true");
            rejected(java.io.InterruptedIOException.class, () -> mode(), operation + " interruption");
        }
        for (int result : new int[]{-1, 2}) {
            resetProbe(0); System.setProperty("stub.readResult", Integer.toString(result));
            rejected(IOException.class, () -> mode(), "unexpected pread result");
            check(Os.writeCalls == 0, "invalid read result cannot reach proof");
        }
        for (int result : new int[]{-1, 1}) {
            resetProbe(2); System.setProperty("stub.writeResult", Integer.toString(result));
            rejected(IOException.class, () -> mode(), "unexpected zero-byte pwrite result");
        }
    }
    public static void main(String[] args) throws Exception {
        int sdk = Integer.parseInt(args[0]);
        PackageInfo info = new PackageInfo();
        info.versionCode = 17;
        check(AndroidApiCompat.versionCode(info) == 17L, "base version");
        check(AndroidApiCompat.signingFlags() == (sdk >= 28 ? 134217728 : 64), "API-specific signing flags");
        byte[] certificate = "public-main-test-certificate".getBytes(StandardCharsets.UTF_8);
        Signature good = new Signature(certificate);
        Signature bad = new Signature("wrong-current-certificate".getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(certificate))
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        String pin = hex.toString();
        info.signatures = new Signature[]{good};
        if (sdk >= 28) {
            denied(pin, AndroidApiCompat.currentSigners(info), "no legacy fallback for absent SigningInfo");
            modernSigners(info, new Signature[]{bad});
            denied(pin, AndroidApiCompat.currentSigners(info), "legacy signer cannot override current signer");
            modernSigners(info, new Signature[]{good});
            PackageInfo.class.getField("versionCodeMajor").setInt(info, 2);
            check(AndroidApiCompat.versionCode(info) == ((2L << 32) | 17), "modern full version code");
            info.versionCode = -1;
            check(AndroidApiCompat.versionCode(info) == ((2L << 32) | 0xffffffffL), "modern unsigned low version bits");
        } else {
            info.versionCode = -1;
            check(AndroidApiCompat.versionCode(info) == -1L, "legacy version is signed, never silently accepted as a huge version");
            try { Class.forName("android.content.pm.SigningInfo"); throw new AssertionError("stub leaked modern class"); }
            catch (ClassNotFoundException expected) { assertions++; }
        }
        MainCallerPolicy.requirePinnedSigner(pin, bytes(AndroidApiCompat.currentSigners(info))); assertions++;
        Signature[][] rejected = {null, new Signature[0], new Signature[]{null},
            new Signature[]{new Signature(new byte[0])}, new Signature[]{bad}, new Signature[]{good, good}};
        for (Signature[] signers : rejected) {
            if (sdk >= 28) modernSigners(info, signers); else info.signatures = signers;
            denied(pin, AndroidApiCompat.currentSigners(info), "missing, empty, malformed, wrong or multiple signers");
        }
        StructStat stat = new StructStat();
        String expected = "1:2:32768:10001:1:12:13:" + (sdk >= 27 ? "123:17:456" : "0:17:0");
        check(AndroidApiCompat.statIdentity(stat).equals(expected), "stat identity uses available timestamp precision");
        stat.st_ino++;
        check(!AndroidApiCompat.statIdentity(stat).equals(expected), "inode replacement rejected");
        stat.st_ino--; stat.st_uid++;
        check(!AndroidApiCompat.statIdentity(stat).equals(expected), "owner change rejected");
        stat.st_uid--; stat.st_nlink++;
        check(!AndroidApiCompat.statIdentity(stat).equals(expected), "link count change rejected");
        if (sdk == 26) {
            try { Class.forName("android.system.StructTimespec"); throw new AssertionError("stub leaked nanosecond class"); }
            catch (ClassNotFoundException expectedMissing) { assertions++; }
        }
        check(AndroidApiCompat.closeOnExecFlag() == 02000000, "atomic CLOEXEC flag retained");
        Os.reset();
        check(AndroidApiCompat.descriptorAccessMode(new ParcelFileDescriptor()) == Integer.parseInt(args[1]), "incoming descriptor mode verified");
        check(Os.writeCalls == 0, "known kernel flags never invoke fallback");
        check(Os.fcntlCalls == (sdk >= 30 ? 1 : 0), "modern fcntl isolated from old runtimes");
        knownFlagsReadabilityTests(sdk, Integer.parseInt(args[1]));
        if (sdk < 30) { procDataTests(); descriptorFallbackTests(); }
        check(DescriptorFlags.read(new ByteArrayInputStream("pos:\t0\nflags:\t02000000\n".getBytes(StandardCharsets.US_ASCII))) == 02000000, "octal proc flags");
        String[] invalidFlags = {"", "pos: 0\n", "flags: 8\n", "flags: -1\n", "flags: 0x00\n", "flags: 0\nflags: 2\n", "flags: 777777777777\n", "flags: 77777777777\n", "flags: 0\u0000"};
        for (String invalid : invalidFlags) {
            try { DescriptorFlags.read(new ByteArrayInputStream(invalid.getBytes(StandardCharsets.US_ASCII))); throw new AssertionError("malformed fdinfo accepted"); }
            catch (IOException expectedInvalid) { assertions++; }
        }
        descriptorGateTests();
        byte[] tooLong = new byte[4097];
        try { DescriptorFlags.read(new ByteArrayInputStream(tooLong)); throw new AssertionError("oversize fdinfo accepted"); }
        catch (IOException expectedOversize) { assertions++; }
        System.out.println("PASS: API " + sdk + " adapter linkage and security dispatch, " + assertions + " assertions; JVM stubs only.");
    }
}
'''


class AndroidApiCompatTests(unittest.TestCase):
    def test_legacy_and_modern_linkage(self):
        with tempfile.TemporaryDirectory(prefix="pdsync-helper-api-") as temporary:
            root = Path(temporary)
            modern_files = platform_stubs(root / "compile-stubs", 36)
            driver = write_java(root / "driver", "io.github.xyz1001.pdsync.bridge.AndroidApiCompatTests", DRIVER)
            # Compile the actual production descriptor gate verbatim, with only
            # its surrounding Binder/workspace machinery replaced by a harness.
            provider = (PRODUCTION / "SaveBridgeProvider.java").read_text()
            start = provider.index("                this.stage = FileFailureDetails.Stage.ARCHIVE_DESCRIPTOR_STAT;")
            end_text = "                this.stage = FileFailureDetails.Stage.EXTRACT_ARCHIVE;"
            end = provider.index(end_text, start) + len(end_text)
            gate = write_java(root / "driver", "io.github.xyz1001.pdsync.bridge.RestoreDescriptorGate", """
                package io.github.xyz1001.pdsync.bridge;
                import android.os.ParcelFileDescriptor;
                import android.system.*;
                class RestoreDescriptorGate {
                    FileFailureDetails.Stage stage;
                    void validate(ParcelFileDescriptor descriptor, int callerUid) throws Exception {
            """ + provider[start:end] + """
                    }
                }
                class SafeSaveTree {
                    static String identity(StructStat stat) { return AndroidApiCompat.statIdentity(stat); }
                }
            """)
            classes = root / "production-classes"
            compile_command = [JAVA, "com.sun.tools.javac.Main", "-source", "8", "-target", "8", "-Xlint:-options"]
            subprocess.run(compile_command + ["-d", str(classes)] + [str(p) for p in modern_files]
                           + [str(PRODUCTION / name) for name in ("AndroidApiCompat.java", "MainCallerPolicy.java", "DescriptorFlags.java", "FileFailureDetails.java", "SaveArchive.java", "BridgeFailure.java")]
                           + [str(driver), str(gate)], check=True)
            # Compile-time Android classes must never leak into any runtime test.
            import shutil
            shutil.rmtree(classes / "android")
            for sdk in (26, 27, 28, 29, 30, 36):
                with self.subTest(sdk=sdk):
                    runtime_classes = root / ("runtime-classes-" + str(sdk))
                    stubs = platform_stubs(root / ("runtime-stubs-" + str(sdk)), sdk)
                    subprocess.run(compile_command + ["-d", str(runtime_classes)] + [str(p) for p in stubs], check=True)
                    fixture = root / "descriptor-fixture"
                    fixture.write_text("archive fixture", encoding="ascii")
                    for mode in (os.O_RDONLY, os.O_WRONLY, os.O_RDWR):
                        fd = os.open(fixture, mode)
                        try:
                            subprocess.run([JAVA, "-Xverify:all", "-Dstub.sdk=" + str(sdk),
                                            "-Dstub.fd=" + str(fd), "-Dstub.flags=" + str(mode | os.O_CLOEXEC), "-cp",
                                            os.pathsep.join((str(classes), str(runtime_classes))),
                                            "io.github.xyz1001.pdsync.bridge.AndroidApiCompatTests", str(sdk), str(mode)],
                                           check=True, pass_fds=(fd,))
                        finally:
                            os.close(fd)


    def test_host_kernel_zero_byte_probe_preserves_fixture(self):
        # Real Linux syscalls over disposable test fixtures only. This does not
        # execute Android libcore/JNI, ART, SELinux, Binder or the HONOR device.
        def identity(stat):
            return (stat.st_dev, stat.st_ino, stat.st_mode, stat.st_uid,
                    stat.st_nlink, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns)
        with tempfile.TemporaryDirectory(prefix="pdsync-descriptor-kernel-") as temporary:
            path = Path(temporary) / "archive-fixture"
            for content in (b"", b"private synthetic archive fixture"):
                path.write_bytes(content)
                for flags in (os.O_RDONLY, os.O_WRONLY, os.O_RDWR, os.O_RDWR | os.O_APPEND):
                    with self.subTest(size=len(content), flags=flags):
                        fd = os.open(path, flags)
                        try:
                            os.lseek(fd, 7, os.SEEK_SET)
                            before = identity(os.fstat(fd))
                            if flags & os.O_ACCMODE == os.O_WRONLY:
                                with self.assertRaises(OSError) as caught:
                                    os.pread(fd, 1, 0)
                                self.assertEqual(errno.EBADF, caught.exception.errno)
                            else:
                                self.assertEqual(content[:1], os.pread(fd, 1, 0))
                            if flags & os.O_ACCMODE == os.O_RDONLY:
                                with self.assertRaises(OSError) as caught:
                                    os.pwrite(fd, b"", 0)
                                self.assertEqual(errno.EBADF, caught.exception.errno)
                            else:
                                self.assertEqual(0, os.pwrite(fd, b"", 0))
                            self.assertEqual(7, os.lseek(fd, 0, os.SEEK_CUR))
                            self.assertEqual(before, identity(os.fstat(fd)))
                            self.assertEqual(content, path.read_bytes())
                        finally:
                            os.close(fd)
            if hasattr(os, "O_PATH"):
                fd = os.open(path, os.O_PATH)
                try:
                    for call in (lambda: os.pread(fd, 1, 0), lambda: os.pwrite(fd, b"", 0)):
                        with self.assertRaises(OSError) as caught:
                            call()
                        self.assertEqual(errno.EBADF, caught.exception.errno)
                finally:
                    os.close(fd)
            fd = os.open(path, os.O_RDONLY)
            os.close(fd)
            with self.assertRaises(OSError) as caught:
                os.pread(fd, 1, 0)
            self.assertEqual(errno.EBADF, caught.exception.errno)

    def test_descriptor_gate_preserves_transaction_order(self):
        source = (PRODUCTION / "SaveBridgeProvider.java").read_text()
        block = source[source.index("    private Bundle restoreSave("):source.index("    private Bundle recover(")]
        self.assertLess(block.index("descriptorBefore.st_uid != callerUid"), block.index("AndroidApiCompat.descriptorAccessMode"))
        self.assertEqual(3, block.count("Os.fstat(descriptor.getFileDescriptor())"))
        self.assertLess(block.index("ARCHIVE_DESCRIPTOR_RECHECK"), block.index("SaveArchive.extract"))
        self.assertLess(block.index("SaveArchive.extract"), block.index("Restore archive changed while reading"))
        self.assertLess(block.index("Restore archive changed while reading"), block.index("SafeSaveTree.State captured"))
        self.assertLess(block.index("expected.equals(captured.inventory.fingerprint())"), block.index("replace(tree, incoming"))
        for forbidden in ('writePending(', 'writeArchive(', 'restorePending(', '"original"', '"removed"', 'WRITE_BACKUP', 'WRITE_JOURNAL', 'RESTORE_ROLLED_BACK'):
            self.assertNotIn(forbidden, block)
        self.assertIn('tree.scan(null, true)', block)
        self.assertIn('RESTORE_INCOMPLETE', block)
        self.assertNotIn('cleanupFinalizedBackups', source)
        self.assertNotIn('deleteUnusedBackup', source)
        self.assertNotIn('Files.deleteIfExists(new File(this.backups', source)
        self.assertIn("READ_ARCHIVE", (PRODUCTION / "FileFailureDetails.java").read_text())


if __name__ == "__main__":
    unittest.main()
