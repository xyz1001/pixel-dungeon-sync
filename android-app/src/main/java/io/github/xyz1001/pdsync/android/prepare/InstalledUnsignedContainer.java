package io.github.xyz1001.pdsync.android.prepare;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict ZIP/container validation, independent of apksig signature verification. */
final class InstalledUnsignedContainer {
    private static final Pattern V1_ARTIFACT = Pattern.compile(
            "META-INF/(?:MANIFEST\\.MF|[^/]+\\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)", Pattern.CASE_INSENSITIVE);
    private static final long ZIP_LIMIT = 0xffffffffL;

    private InstalledUnsignedContainer() {}

    static void requireAbsentSigningBlock(File file) throws IOException {
        requireContainer(file, false);
    }

    /** Call only after cryptographic verification succeeds at the source API. */
    static void requireSignedContainer(File file) throws IOException {
        requireContainer(file, true);
    }

    private static void requireContainer(File file, boolean verifiedSigned) throws IOException {
        if (file == null || !file.isFile()) throw invalid("source is unavailable");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            if (length < 22 || length > Integer.MAX_VALUE) throw invalid("file size is invalid");
            int tailLength = (int) Math.min(length, 22L + 65535L);
            byte[] tail = read(input, length - tailLength, tailLength);
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.length) {
                    if (eocd >= 0) throw invalid("multiple end-of-directory records are plausible");
                    eocd = i;
                }
            }
            if (eocd < 0) throw invalid("end-of-directory record is missing or has trailing bytes");
            long eocdOffset = length - tailLength + eocd;
            int recordCount = u16(tail, eocd + 10);
            long directorySize = u32(tail, eocd + 12);
            long directoryOffset = u32(tail, eocd + 16);
            if (u16(tail, eocd + 4) != 0 || u16(tail, eocd + 6) != 0
                    || u16(tail, eocd + 8) != recordCount
                    || recordCount == 0 || recordCount == 65535
                    || directorySize == ZIP_LIMIT || directoryOffset == ZIP_LIMIT
                    || directoryOffset + directorySize != eocdOffset
                    || directorySize < 46L * recordCount) {
                throw invalid("end-of-directory bounds, disks, or counts are invalid");
            }
            if (eocdOffset >= 20 && u32(read(input, eocdOffset - 20, 4), 0) == 0x07064b50L) {
                throw invalid("ZIP64 signing containers are unsupported");
            }
            List<Entry> entries = new ArrayList<>();
            Set<String> names = new HashSet<>();
            long cursor = directoryOffset;
            for (int index = 0; index < recordCount; index++) {
                if (cursor > eocdOffset - 46) throw invalid("central-directory entry is truncated");
                byte[] header = read(input, cursor, 46);
                if (u32(header, 0) != 0x02014b50L) throw invalid("central-directory signature is invalid");
                int nameLength = u16(header, 28);
                int extraLength = u16(header, 30);
                int commentLength = u16(header, 32);
                int flags = u16(header, 8);
                int method = u16(header, 10);
                long compressed = u32(header, 20);
                long uncompressed = u32(header, 24);
                long localOffset = u32(header, 42);
                long next = cursor + 46L + nameLength + extraLength + commentLength;
                if (nameLength == 0 || next > eocdOffset || u16(header, 34) != 0
                        || compressed == ZIP_LIMIT || uncompressed == ZIP_LIMIT || localOffset == ZIP_LIMIT
                        || localOffset >= directoryOffset || (flags & ~0x080e) != 0
                        || (method != 0 && method != 8) || (method == 0 && (flags & 6) != 0)
                        || (method == 0 && compressed != uncompressed)) {
                    throw invalid("central-directory entry fields are unsupported or inconsistent");
                }
                byte[] rawName = read(input, cursor + 46, nameLength);
                String name = decodeName(rawName);
                if (!names.add(name)) throw invalid("duplicate ZIP entry name");
                if (!verifiedSigned && V1_ARTIFACT.matcher(name).matches()) {
                    throw invalid("source retains unverified v1 signing artifacts");
                }
                validateExtra(read(input, cursor + 46 + nameLength, extraLength));
                entries.add(new Entry(localOffset, rawName, flags, method, u32(header, 16), compressed, uncompressed));
                cursor = next;
            }
            if (cursor != eocdOffset) throw invalid("extra central-directory data remains");
            Collections.sort(entries, new Comparator<Entry>() {
                @Override public int compare(Entry first, Entry second) {
                    return Long.compare(first.offset, second.offset);
                }
            });
            if (entries.get(0).offset != 0) throw invalid("ZIP contains a leading gap or preamble");
            long blockStart = verifiedSigned ? signingBlockStart(input, directoryOffset) : directoryOffset;
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                boolean last = index + 1 == entries.size();
                long boundary = last ? blockStart : entries.get(index + 1).offset;
                validateLocal(input, entry, boundary, last && blockStart != directoryOffset);
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Source signing container could not be classified safely", e);
        }
    }

    private static void validateLocal(RandomAccessFile input, Entry entry, long boundary,
                                      boolean allowSigningBlockPadding) throws IOException {
        if (entry.offset > boundary - 30) throw invalid("overlapping or truncated local ZIP records");
        byte[] header = read(input, entry.offset, 30);
        int nameLength = u16(header, 26);
        int extraLength = u16(header, 28);
        long payload = entry.offset + 30L + nameLength + extraLength;
        long payloadEnd = payload + entry.compressed;
        if (u32(header, 0) != 0x04034b50L || u16(header, 6) != entry.flags
                || u16(header, 8) != entry.method || nameLength != entry.name.length
                || payload > boundary || payloadEnd > boundary) {
            throw invalid("local record differs from its directory or overlaps another record");
        }
        if (!Arrays.equals(entry.name, read(input, entry.offset + 30, nameLength))) {
            throw invalid("local and central ZIP entry names differ");
        }
        validateExtra(read(input, entry.offset + 30 + nameLength, extraLength));
        long crc = u32(header, 14);
        long compressed = u32(header, 18);
        long uncompressed = u32(header, 22);
        if (allowSigningBlockPadding) {
            long end = payloadEnd;
            if ((entry.flags & 8) != 0) {
                if (end > boundary - 12) throw invalid("signed APK data descriptor is truncated");
                byte[] descriptor = read(input, end, (int) Math.min(16, boundary - end));
                // CRC32 can itself equal the optional descriptor signature. Select
                // the long form only when all its fields match the central record.
                boolean signature = descriptor.length == 16 && u32(descriptor, 0) == 0x08074b50L
                        && u32(descriptor, 4) == entry.crc && u32(descriptor, 8) == entry.compressed
                        && u32(descriptor, 12) == entry.uncompressed;
                end += signature ? 16 : 12;
            }
            validateSigningPadding(input, end, boundary);
            boundary = end;
        }
        if ((entry.flags & 8) == 0) {
            if (crc != entry.crc || compressed != entry.compressed || uncompressed != entry.uncompressed) {
                throw invalid("local and central ZIP sizes or CRC differ");
            }
            if (payloadEnd != boundary) throw invalid("source retains a signing block or unexplained ZIP gap");
            return;
        }
        if ((crc != 0 && crc != entry.crc) || (compressed != 0 && compressed != entry.compressed)
                || (uncompressed != 0 && uncompressed != entry.uncompressed)) {
            throw invalid("local data-descriptor fields disagree with the directory");
        }
        long descriptorSize = boundary - payloadEnd;
        if (descriptorSize != 12 && descriptorSize != 16) {
            throw invalid("source retains a signing block, gap, or malformed data descriptor");
        }
        byte[] descriptor = read(input, payloadEnd, (int) descriptorSize);
        int offset = descriptorSize == 16 ? 4 : 0;
        if ((offset == 4 && u32(descriptor, 0) != 0x08074b50L)
                || u32(descriptor, offset) != entry.crc
                || u32(descriptor, offset + 4) != entry.compressed
                || u32(descriptor, offset + 8) != entry.uncompressed) {
            throw invalid("ZIP data descriptor is invalid");
        }
    }

    private static long signingBlockStart(RandomAccessFile input, long directoryOffset) throws IOException {
        if (directoryOffset < 32) return directoryOffset;
        byte[] footer = read(input, directoryOffset - 24, 24);
        byte[] magic = "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);
        if (!Arrays.equals(magic, Arrays.copyOfRange(footer, 8, 24))) return directoryOffset;
        long size = u64(footer, 0);
        if (size < 24 || size > directoryOffset - 8) throw invalid("APK signing block size is invalid");
        long start = directoryOffset - size - 8;
        if (u64(read(input, start, 8), 0) != size) throw invalid("APK signing block header and footer disagree");
        long cursor = start + 8, limit = directoryOffset - 24;
        Set<Long> identifiers = new HashSet<>();
        while (cursor < limit) {
            if (cursor > limit - 12) throw invalid("APK signing block pair is truncated");
            byte[] pair = read(input, cursor, 12);
            long pairSize = u64(pair, 0);
            if (pairSize < 4 || pairSize > limit - cursor - 8) throw invalid("APK signing block pair is out of bounds");
            if (!identifiers.add(u32(pair, 8))) throw invalid("APK signing block has duplicate scheme identifiers");
            cursor += 8 + pairSize;
        }
        if (cursor != limit || identifiers.isEmpty()) throw invalid("APK signing block pairs are incomplete");
        return start;
    }

    private static void validateSigningPadding(RandomAccessFile input, long recordsEnd, long blockStart) throws IOException {
        if (recordsEnd == blockStart) return;
        long padding = blockStart - recordsEnd;
        long expected = (4096 - recordsEnd % 4096) % 4096;
        if (padding <= 0 || padding >= 4096 || padding != expected || blockStart % 4096 != 0) {
            throw invalid("signed APK has a noncanonical gap before its signing block");
        }
        for (byte value : read(input, recordsEnd, (int) padding)) {
            if (value != 0) throw invalid("signed APK has nonzero pre-block alignment padding");
        }
    }

    private static long u64(byte[] bytes, int offset) throws IOException {
        long low = u32(bytes, offset), high = u32(bytes, offset + 4);
        if ((high & 0x80000000L) != 0) throw invalid("unsigned ZIP block length exceeds supported range");
        return low | (high << 32);
    }

    private static String decodeName(byte[] bytes) throws IOException {
        final String name;
        try {
            name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw invalid("ZIP entry name is not canonical UTF-8");
        }
        if (name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0 || name.startsWith("/")) {
            throw invalid("ZIP entry name is ambiguous");
        }
        String[] segments = name.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            if (segments[index].equals(".") || segments[index].equals("..")
                    || (segments[index].isEmpty() && index != segments.length - 1)) {
                throw invalid("ZIP entry name has noncanonical path segments");
            }
        }
        return name;
    }

    private static void validateExtra(byte[] extra) throws IOException {
        for (int cursor = 0; cursor < extra.length;) {
            if (cursor > extra.length - 4) {
                // Android aapt/zipalign also uses 1..3 zero bytes as local-file
                // alignment padding. It is bounded by the declared extra length,
                // so cannot conceal a signing block between ZIP records.
                for (int index = cursor; index < extra.length; index++) {
                    if (extra[index] != 0) throw invalid("ZIP extra field is truncated");
                }
                return;
            }
            int tag = u16(extra, cursor);
            int size = u16(extra, cursor + 2);
            if (tag == 1 || cursor + 4L + size > extra.length) {
                throw invalid("ZIP64 or malformed extra field is unsupported");
            }
            cursor += 4 + size;
        }
    }

    private static byte[] read(RandomAccessFile input, long offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > input.length() - length) throw invalid("ZIP read is out of bounds");
        byte[] result = new byte[length];
        input.seek(offset);
        input.readFully(result);
        return result;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return (u16(bytes, offset) | ((long) u16(bytes, offset + 2) << 16));
    }

    private static IOException invalid(String detail) {
        return new IOException("APK signing container is structurally unsafe: " + detail);
    }

    private static final class Entry {
        final long offset, crc, compressed, uncompressed;
        final byte[] name;
        final int flags, method;
        Entry(long offset, byte[] name, int flags, int method, long crc, long compressed, long uncompressed) {
            this.offset = offset;
            this.name = name;
            this.flags = flags;
            this.method = method;
            this.crc = crc;
            this.compressed = compressed;
            this.uncompressed = uncompressed;
        }
    }
}
