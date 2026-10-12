package io.github.xyz1001.pdsync.android.prepare;

import io.github.xyz1001.pdsync.android.prepare.BinaryManifestPatch;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ApkArchive {
    private static final String MANIFEST = "AndroidManifest.xml";
    public static final long MAX_APK_BYTES = 2147483647L;
    private static final int MAX_ENTRIES = 100000;
    private static final long MAX_ENTRY_BYTES = 2147483647L;
    private static final long MAX_UNCOMPRESSED_BYTES = 8589934592L;
    private static final Pattern SIGNATURE = Pattern.compile("META-INF/(?:MANIFEST\\.MF|[^/]+\\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)", 2);

    private ApkArchive() {
    }

    public static String sha256(File file) throws IOException {
        MessageDigest messageDigestDigest = digest();
        FileInputStream fileInputStream = new FileInputStream(file);
        try {
            hashStream(fileInputStream, messageDigestDigest, 2147483647L);
            fileInputStream.close();
            return hex(messageDigestDigest.digest());
        } catch (Throwable th) {
            try {
                fileInputStream.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    public static String sha256(byte[] bArr) {
        return hex(digest().digest(bArr));
    }

    public static BinaryManifestPatch.ManifestInfo validateSource(File file) throws IOException {
        entryHashes(file);
        BinaryManifestPatch.ManifestInfo manifestInfoInspect = BinaryManifestPatch.inspect(manifest(file));
        if (manifestInfoInspect.packageName == null || manifestInfoInspect.packageName.isEmpty()) {
            throw new IOException("APK manifest has no package identity");
        }
        return manifestInfoInspect;
    }

    public static void patchGame(File file, File file2, String str) throws IOException {
        validateSource(file);
        rewriteManifest(file, file2, BinaryManifestPatch.patchGame(manifest(file), str));
        verifyGamePayload(file, file2, str);
    }

    public static void patchHelper(File file, File file2, GameSourceIdentity gameSourceIdentity, String str, long j, String str2) throws IOException {
        validateHelperTemplate(file);
        rewriteManifest(file, file2, BinaryManifestPatch.patchHelper(manifest(file), gameSourceIdentity.helperPackage, gameSourceIdentity.sharedUid, gameSourceIdentity.authority, gameSourceIdentity.gamePackage, gameSourceIdentity.originIdentity, gameSourceIdentity.namespaceId, str, j, str2));
        verifyProtectedPayload(file, file2);
    }

    private static void rewriteManifest(File source, File output, byte[] manifest) throws IOException {
        entryHashes(source);
        if (output.exists() || source.getCanonicalFile().equals(output.getCanonicalFile())) throw new IOException("Output must be a new file");
        try (ZipFile input = new ZipFile(source); FileOutputStream file = new FileOutputStream(output); ZipOutputStream zip = new ZipOutputStream(file)) {
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry original = entries.nextElement();
                if (isSignature(original.getName())) continue;
                ZipEntry entry = new ZipEntry(original.getName()); entry.setMethod(original.getMethod());
                if (original.getTime() >= 0) entry.setTime(original.getTime());
                byte[] replacement = MANIFEST.equals(original.getName()) ? manifest : null;
                if (original.getMethod() == ZipEntry.STORED) {
                    if (replacement == null) { entry.setSize(original.getSize()); entry.setCrc(original.getCrc()); }
                    else { CRC32 crc = new CRC32(); crc.update(replacement); entry.setSize(replacement.length); entry.setCrc(crc.getValue()); }
                }
                zip.putNextEntry(entry);
                if (replacement != null) zip.write(replacement);
                else try (InputStream data = input.getInputStream(original)) { copyLimited(data, zip, MAX_ENTRY_BYTES); }
                zip.closeEntry();
            }
            zip.finish(); zip.flush(); file.getFD().sync();
        } catch (Throwable failure) { output.delete(); throw failure; }
    }

    public static int verifyGamePayload(File file, File file2) throws IOException {
        return verifyGamePayload(file, file2, "io.github.xyz1001.pdsync.shared");
    }

    public static int verifyGamePayload(File file, File file2, String str) throws IOException {
        int iVerifyProtectedPayload = verifyProtectedPayload(file, file2);
        if (Arrays.equals(BinaryManifestPatch.patchGame(manifest(file), str), manifest(file2))) {
            return iVerifyProtectedPayload;
        }
        throw new IOException("Manifest differs from the exact permitted shared-UID and sandbox patch");
    }

    public static int verifyProtectedPayload(File file, File file2) throws IOException {
        Map<String, String> mapProtectedEntries = protectedEntries(entryHashes(file));
        if (!mapProtectedEntries.equals(protectedEntries(entryHashes(file2)))) {
            throw new IOException("Protected APK payload changed; refusing preparation/install");
        }
        return mapProtectedEntries.size();
    }

    public static void verifyHelperPayload(File file, File file2) throws IOException {
        Map<String, String> mapEntryHashes = entryHashes(file);
        Map<String, String> mapEntryHashes2 = entryHashes(file2);
        mapEntryHashes.entrySet().removeIf(new Predicate() { // from class: io.github.xyz1001.pdsync.android.prepare.ApkArchive$$ExternalSyntheticLambda1
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return ApkArchive.isSignature((String) ((Map.Entry) obj).getKey());
            }
        });
        mapEntryHashes2.entrySet().removeIf(new Predicate() { // from class: io.github.xyz1001.pdsync.android.prepare.ApkArchive$$ExternalSyntheticLambda2
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return ApkArchive.isSignature((String) ((Map.Entry) obj).getKey());
            }
        });
        if (!mapEntryHashes.equals(mapEntryHashes2)) {
            throw new IOException("Helper APK payload changed while signing");
        }
    }

    public static BinaryManifestPatch.ManifestInfo validateHelper(File file) throws IOException {
        return validateHelperTemplate(file);
    }

    public static BinaryManifestPatch.ManifestInfo validateHelperTemplate(File file) throws IOException {
        BinaryManifestPatch.ManifestInfo manifestInfoValidateSource = validateSource(file);
        if (!"io.github.xyz1001.pdsync.bridge".equals(manifestInfoValidateSource.packageName) || manifestInfoValidateSource.targetSandboxVersion != 1) {
            throw new IOException("Embedded helper template has unexpected package or sandbox configuration");
        }
        if (manifestInfoValidateSource.versionCode > 0) {
            return manifestInfoValidateSource;
        }
        throw new IOException("Helper template must have a positive version code");
    }

    public static byte[] manifest(File file) throws IOException {
        ZipFile zipFile = new ZipFile(file);
        try {
            ZipEntry entry = zipFile.getEntry("AndroidManifest.xml");
            if (entry == null || entry.isDirectory()) {
                throw new IOException("Missing AndroidManifest.xml");
            }
            byte[] limited = readLimited(zipFile.getInputStream(entry), 16777216);
            zipFile.close();
            return limited;
        } catch (Throwable th) {
            try {
                zipFile.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    public static Map<String, String> entryHashes(File file) throws IOException {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_APK_BYTES) throw new IOException("APK file missing, empty, or oversized");
        Map<String, String> hashes = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries(); long total = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement(); String name = entry.getName();
                if (hashes.size() >= MAX_ENTRIES || unsafeName(name) || hashes.containsKey(name)) throw new IOException("Duplicate, excessive, or unsafe ZIP entry: " + name);
                if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) throw new IOException("Unsupported ZIP compression method");
                if (entry.getSize() < 0 || entry.getSize() > MAX_ENTRY_BYTES || entry.getSize() + total > MAX_UNCOMPRESSED_BYTES) throw new IOException("Oversized ZIP payload");
                MessageDigest digest = digest(); long read;
                try (InputStream data = zip.getInputStream(entry)) { read = hashStream(data, digest, Math.min(MAX_ENTRY_BYTES, MAX_UNCOMPRESSED_BYTES - total)); }
                if (read != entry.getSize()) throw new IOException("ZIP entry size mismatch");
                total += read; hashes.put(name, hex(digest.digest()));
            }
        }
        if (!hashes.containsKey(MANIFEST)) throw new IOException("Missing manifest");
        return hashes;
    }

    static void copyToNewFile(InputStream input, File file, long limit) throws IOException {
        if (input == null) throw new IOException("Source returned no data");
        if (!file.createNewFile()) throw new IOException("Staging output already exists");
        try (InputStream ownedInput = input; FileOutputStream output = new FileOutputStream(file)) {
            if (copyLimited(ownedInput, output, limit) == 0) throw new IOException("Source APK is empty");
            output.getFD().sync();
        } catch (Throwable failure) { file.delete(); throw failure; }
    }

    static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream ownedInput = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyLimited(ownedInput, output, limit); return output.toByteArray();
        }
    }

    private static boolean unsafeName(String str) {
        if (str.isEmpty() || str.startsWith("/") || str.indexOf(92) >= 0 || str.indexOf(0) >= 0) {
            return true;
        }
        for (String str2 : str.split("/", -1)) {
            if (str2.equals("..") || str2.equals(".")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSignature(String str) {
        return SIGNATURE.matcher(str).matches();
    }

    private static Map<String, String> protectedEntries(Map<String, String> map) {
        TreeMap treeMap = new TreeMap(map);
        treeMap.entrySet().removeIf(new Predicate() { // from class: io.github.xyz1001.pdsync.android.prepare.ApkArchive$$ExternalSyntheticLambda0
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return ApkArchive.lambda$protectedEntries$2((Map.Entry) obj);
            }
        });
        return treeMap;
    }

    static /* synthetic */ boolean lambda$protectedEntries$2(Map.Entry entry) {
        return "AndroidManifest.xml".equals(entry.getKey()) || isSignature((String) entry.getKey());
    }

    private static long hashStream(InputStream inputStream, MessageDigest messageDigest, long j) throws IOException {
        byte[] bArr = new byte[65536];
        long j2 = 0;
        while (true) {
            int i = inputStream.read(bArr);
            if (i == -1) {
                return j2;
            }
            j2 += i;
            if (j2 > j) {
                throw new IOException("Input exceeds safety size limit");
            }
            messageDigest.update(bArr, 0, i);
        }
    }

    private static long copyLimited(InputStream inputStream, OutputStream outputStream, long j) throws IOException {
        byte[] bArr = new byte[65536];
        long j2 = 0;
        while (true) {
            int i = inputStream.read(bArr);
            if (i == -1) {
                return j2;
            }
            j2 += i;
            if (j2 > j) {
                throw new IOException("Input exceeds safety size limit");
            }
            outputStream.write(bArr, 0, i);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String hex(byte[] bArr) {
        StringBuilder sb = new StringBuilder(bArr.length * 2);
        for (byte b : bArr) {
            sb.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & 255)));
        }
        return sb.toString();
    }
}
