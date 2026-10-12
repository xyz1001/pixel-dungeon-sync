#!/usr/bin/env python3
"""Run production platform adapters against deliberately incomplete Android stubs.

This checks JVM linkage/dispatch, not Android framework, ART, SELinux or OEM
behavior. API26 runtime stubs omit SigningInfo, StructTimespec and newer members.
"""
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
            public int getFd() { return Integer.parseInt(System.getProperty("stub.fd")); }
            public java.io.FileDescriptor getFileDescriptor() { return new java.io.FileDescriptor(); }
        }
    """)
    add("android.system.ErrnoException", """
        package android.system;
        public class ErrnoException extends Exception { }
    """)
    os_extra = """
        public static int fcntlInt(java.io.FileDescriptor fd, int cmd, int arg) throws ErrnoException {
            if (cmd != OsConstants.F_GETFL || arg != 0) throw new AssertionError("Unexpected fcntl call");
            return Integer.parseInt(System.getProperty("stub.flags"));
        }
    """ if sdk >= 30 else ""
    add("android.system.Os", "package android.system; public class Os {" + os_extra + "}")
    cloexec = "public static final int O_CLOEXEC = Integer.parseInt(\"524288\");" if sdk >= 27 else ""
    add("android.system.OsConstants", """
        package android.system;
        public class OsConstants {
            public static final int O_ACCMODE = 3, F_GETFL = 3;
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
        check(AndroidApiCompat.descriptorAccessMode(new ParcelFileDescriptor()) == Integer.parseInt(args[1]), "incoming descriptor mode verified");
        if (sdk < 30) {
            String descriptor = System.getProperty("stub.fd");
            System.setProperty("stub.fd", "-1");
            try { AndroidApiCompat.descriptorAccessMode(new ParcelFileDescriptor()); throw new AssertionError("missing fdinfo accepted"); }
            catch (IOException expectedMissing) { assertions++; }
            System.setProperty("stub.fd", descriptor);
        }
        check(DescriptorFlags.read(new ByteArrayInputStream("pos:\t0\nflags:\t02000000\n".getBytes(StandardCharsets.US_ASCII))) == 02000000, "octal proc flags");
        String[] invalidFlags = {"", "pos: 0\n", "flags: 8\n", "flags: -1\n", "flags: 0x00\n", "flags: 0\nflags: 2\n", "flags: 777777777777\n", "flags: 77777777777\n", "flags: 0\u0000"};
        for (String invalid : invalidFlags) {
            try { DescriptorFlags.read(new ByteArrayInputStream(invalid.getBytes(StandardCharsets.US_ASCII))); throw new AssertionError("malformed fdinfo accepted"); }
            catch (IOException expectedInvalid) { assertions++; }
        }
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
            classes = root / "production-classes"
            compile_command = [JAVA, "com.sun.tools.javac.Main", "-source", "8", "-target", "8", "-Xlint:-options"]
            subprocess.run(compile_command + ["-d", str(classes)] + [str(p) for p in modern_files]
                           + [str(PRODUCTION / name) for name in ("AndroidApiCompat.java", "MainCallerPolicy.java", "DescriptorFlags.java")]
                           + [str(driver)], check=True)
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


if __name__ == "__main__":
    unittest.main()
