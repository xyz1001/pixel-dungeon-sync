package io.github.xyz1001.pdsync.android.prepare;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only DEX class-definition inventory. It never loads, links, or executes input code. */
public final class PayloadInventory {
    public static final int MAX_DEX_BYTES = 256 * 1024 * 1024;
    private static final Pattern DEX_ENTRY = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");
    private static final String RUNTIME_PREFIX = "Lio/github/xyz1001/pdsync/bridge/";
    private PayloadInventory() { }

    public static boolean isDexEntry(String name) { return DEX_ENTRY.matcher(name).matches(); }

    public static byte[] readRuntime(File runtime) throws IOException {
        if (runtime == null || !runtime.isFile() || runtime.length() < 112 || runtime.length() > MAX_DEX_BYTES)
            throw new IOException("Bridge DEX missing, truncated, or oversized");
        try (InputStream input = new FileInputStream(runtime)) { return ApkArchive.readLimited(input, MAX_DEX_BYTES); }
    }

    static void validateSourceDex(File source) throws IOException {
        try (ZipFile zip = new ZipFile(source)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            int dexCount = 0; Set<String> names = new HashSet<>();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!isDexEntry(entry.getName())) continue;
                require(!entry.isDirectory() && entry.getSize() >= 112 && entry.getSize() <= MAX_DEX_BYTES,
                        "Source DEX missing, truncated, or oversized");
                try (InputStream input = zip.getInputStream(entry)) { classNames(ApkArchive.readLimited(input, MAX_DEX_BYTES)); }
                dexCount++; names.add(entry.getName());
            }
            for (int i = 1; i <= dexCount; i++) require(names.contains(i == 1 ? "classes.dex" : "classes" + i + ".dex"),
                    "Source APK has a non-contiguous DEX entry sequence");
        }
    }

    public static void requireNoClassCollisions(List<File> sourceApks, File runtime) throws IOException {
        requireNoClassCollisions(sourceApks, readRuntime(runtime));
    }

    static void requireNoClassCollisions(List<File> sourceApks, byte[] runtime) throws IOException {
        require(sourceApks != null && !sourceApks.isEmpty(), "Missing source APK set for DEX collision check");
        Set<String> bridgeClasses = classNames(runtime);
        require(bridgeClasses.contains(RUNTIME_PREFIX + "SaveBridgeProvider;"), "Bridge DEX has no SaveBridgeProvider");
        for (String name : bridgeClasses) require(name.startsWith(RUNTIME_PREFIX), "Bridge DEX defines an unexpected class namespace");
        Set<String> sourcePaths = new HashSet<>();
        for (File source : sourceApks) {
            require(sourcePaths.add(source.getCanonicalPath()), "Duplicate source APK in DEX collision check");
            try (ZipFile zip = new ZipFile(source)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                Set<String> names = new HashSet<>();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    require(names.add(entry.getName()), "Duplicate source ZIP entry in DEX collision check");
                    if (!isDexEntry(entry.getName())) continue;
                    require(!entry.isDirectory() && entry.getSize() >= 112 && entry.getSize() <= MAX_DEX_BYTES, "Source DEX missing, truncated, or oversized");
                    byte[] bytes;
                    try (InputStream input = zip.getInputStream(entry)) { bytes = ApkArchive.readLimited(input, MAX_DEX_BYTES); }
                    Set<String> originalClasses = classNames(bytes);
                    for (String name : bridgeClasses)
                        require(!originalClasses.contains(name), "Source already defines injected bridge class: " + name);
                }
            }
        }
    }

    /** Supports standard little-endian DEX 035 through 040. Unknown/container formats fail closed. */
    public static Set<String> classNames(byte[] dex) throws IOException {
        require(dex != null && dex.length >= 112 && dex.length <= MAX_DEX_BYTES, "Invalid DEX length");
        require(dex[0] == 'd' && dex[1] == 'e' && dex[2] == 'x' && dex[3] == '\n' && dex[7] == 0, "Invalid DEX magic");
        String version = new String(dex, 4, 3, java.nio.charset.StandardCharsets.US_ASCII);
        require("035".equals(version) || "037".equals(version) || "038".equals(version)
                || "039".equals(version) || "040".equals(version), "Unsupported DEX version");
        require(u32(dex, 32) == dex.length && u32(dex, 36) == 112 && u32(dex, 40) == 0x12345678L, "Invalid DEX header");
        Adler32 checksum = new Adler32(); checksum.update(dex, 12, dex.length - 12);
        require(checksum.getValue() == u32(dex, 8), "DEX checksum mismatch");
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1"); sha1.update(dex, 32, dex.length - 32);
            require(Arrays.equals(sha1.digest(), Arrays.copyOfRange(dex, 12, 32)), "DEX signature mismatch");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        int stringCount = count(dex, 56, 1048576), typeCount = count(dex, 64, 65535), classCount = count(dex, 96, 65535);
        int strings = table(dex, 60, stringCount, 4), types = table(dex, 68, typeCount, 4), classes = table(dex, 100, classCount, 32);
        require(classCount <= typeCount, "Invalid DEX class definition count");
        int dataSize = count(dex, 104, dex.length), dataStart = (int)u32(dex, 108);
        require(dataStart >= 112 && dataStart <= dex.length && dataSize == dex.length - dataStart, "Invalid DEX data bounds");
        Set<String> result = new LinkedHashSet<>();
        long descriptorCharacters = 0;
        for (int index = 0; index < classCount; index++) {
            long typeIndex = u32(dex, classes + index * 32);
            require(typeIndex < typeCount, "Invalid DEX class type index");
            long stringIndex = u32(dex, types + (int)typeIndex * 4);
            require(stringIndex < stringCount, "Invalid DEX class descriptor index");
            long stringOffset = u32(dex, strings + (int)stringIndex * 4);
            require(stringOffset >= dataStart && stringOffset < dex.length, "Invalid DEX class descriptor offset");
            String descriptor = dexString(dex, (int)stringOffset);
            descriptorCharacters += descriptor.length();
            require(descriptorCharacters <= 8 * 1024 * 1024, "Excessive overlapping DEX class descriptors");
            require(descriptor.length() >= 3 && descriptor.charAt(0) == 'L' && descriptor.endsWith(";")
                    && descriptor.indexOf('.') < 0 && descriptor.indexOf('\0') < 0, "Invalid DEX class descriptor");
            require(result.add(descriptor), "Duplicate DEX class definition");
        }
        return Collections.unmodifiableSet(result);
    }

    private static String dexString(byte[] dex, int start) throws IOException {
        int at = start, length = 0, shift = 0;
        while (true) {
            require(at < dex.length && shift <= 28, "Invalid DEX string length");
            int value = dex[at++] & 255;
            if (shift == 28) require((value & 0xf0) == 0, "DEX string length overflow");
            length |= (value & 127) << shift;
            if ((value & 128) == 0) break;
            shift += 7;
        }
        require(length >= 0 && length <= 32767, "DEX class descriptor too long");
        int end = at;
        while (end < dex.length && dex[end] != 0 && end - at <= 65535) end++;
        require(end < dex.length && end - at <= 65535, "Unterminated DEX class descriptor");
        byte[] mutf8 = new byte[end - at + 2];
        mutf8[0] = (byte)((end - at) >>> 8); mutf8[1] = (byte)(end - at);
        System.arraycopy(dex, at, mutf8, 2, end - at);
        String decoded = new DataInputStream(new ByteArrayInputStream(mutf8)).readUTF();
        require(decoded.length() == length, "DEX string length mismatch");
        return decoded;
    }

    private static int count(byte[] dex, int offset, int max) throws IOException {
        long count = u32(dex, offset); require(count <= max, "Excessive DEX table size"); return (int)count;
    }
    private static int table(byte[] dex, int offset, int count, int width) throws IOException {
        long at = u32(dex, offset);
        require(count == 0 ? at == 0 : at >= 112 && (at & 3) == 0 && at + (long)count * width <= dex.length, "Invalid DEX table bounds");
        return (int)at;
    }
    private static long u32(byte[] bytes, int offset) throws IOException { return BinaryManifestPatch.u32(bytes, offset); }
    private static void require(boolean condition, String message) throws IOException { BinaryManifestPatch.require(condition, message); }
}
