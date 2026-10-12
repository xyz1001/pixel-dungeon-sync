package io.github.xyz1001.pdsync.bridge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.*;
import java.nio.channels.FileChannel;
import java.util.Enumeration;
import java.util.HashSet;


public final class SaveArchive {
    public static final long MAX_BYTES = 67108864;
    public static final int MAX_ENTRIES = 16384;
    public static final int MAX_FILES = 4096;

    private SaveArchive() {
    }

    public static boolean included(String str) {
        return str.equals("rankings.dat") || str.equals("badges.dat") || str.equals("journal.dat") || str.matches("game[0-9]+(/.*)?");
    }

    public static boolean safeName(String str) {
        if (str == null || str.isEmpty() || str.length() > 1024 || !included(str)) {
            return false;
        }
        String[] strArrSplit = str.split("/", -1);
        if (strArrSplit.length > 16) {
            return false;
        }
        for (String str2 : strArrSplit) {
            if (str2.length() > 255 || !str2.matches("[A-Za-z0-9_.-]+") || str2.equals(".") || str2.equals("..") || str2.endsWith(".") || str2.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) {
                return false;
            }
        }
        return true;
    }

    public static void requireCompleteRuns(Set<String> set) throws IOException {
        for (String str : set) {
            if (str.startsWith("game")) {
                if (!set.contains(str.split("/", 2)[0] + "/game.dat")) {
                    throw new IOException("Incomplete run in save set");
                }
            }
        }
    }

    public static String sha256(byte[] bArr) {
        return hex(digest().digest(bArr));
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static String hex(byte[] bArr) {
        StringBuilder sb = new StringBuilder(bArr.length * 2);
        for (byte b : bArr) {
            sb.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & 255)));
        }
        return sb.toString();
    }

    public static String fingerprint(Map<String, String> map) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
        dataOutputStream.write("PDSYNC-CONTENT-V1".getBytes(StandardCharsets.UTF_8));
        dataOutputStream.writeInt(map.size());
        for (Map.Entry<String, String> entry : new TreeMap<String, String>(map).entrySet()) {
            if (!safeName((String) entry.getKey()) || !((String) entry.getValue()).matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid content manifest");
            }
            byte[] bytes = ((String) entry.getKey()).getBytes(StandardCharsets.UTF_8);
            dataOutputStream.writeInt(bytes.length);
            dataOutputStream.write(bytes);
            int i = 0;
            while (i < 64) {
                int i2 = i + 2;
                dataOutputStream.writeByte(Integer.parseInt(((String) entry.getValue()).substring(i, i2), 16));
                i = i2;
            }
        }
        dataOutputStream.close();
        return sha256(byteArrayOutputStream.toByteArray());
    }

    public static final class Inventory {
        public final long bytes;
        public final SortedMap<String, String> hashes;

        public Inventory(Map<String, String> map, long j) {
            this.hashes = Collections.unmodifiableSortedMap(new TreeMap(map));
            this.bytes = j;
        }

        public String fingerprint() throws IOException {
            return SaveArchive.fingerprint(this.hashes);
        }
    }

    public static Inventory extract(InputStream input, File root) throws IOException {
        File archive = File.createTempFile("bridge-archive-", ".zip", root);
        try {
            try (InputStream limited = new LimitedInputStream(input, MAX_BYTES);
                 OutputStream output = Files.newOutputStream(archive.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[32768]; int n;
                while ((n = limited.read(buffer)) != -1) output.write(buffer, 0, n);
            }
            return readZip(archive, root);
        } finally { Files.deleteIfExists(archive.toPath()); }
    }

    static Inventory verify(File file) throws IOException {
        long size = Files.size(file.toPath());
        if (size < 0 || size > 67108864) {
            throw new ZipException("Invalid backup archive size");
        }
        return readZip(file, null);
    }

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    private static Inventory readZip(File archive, File root) throws IOException {
        TreeMap<String, String> hashes = new TreeMap<>();
        Set<String> names = new HashSet<>();
        long total = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[32768];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !safeName(name) || !names.add(name.toLowerCase(Locale.ROOT)) || hashes.size() >= MAX_FILES)
                    throw new ZipException("Unsafe, duplicate, or excessive ZIP entry");
                if (entry.getSize() < 0 || entry.getSize() > MAX_BYTES - total || entry.getCrc() < 0
                        || (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED))
                    throw new ZipException("Invalid ZIP entry size, checksum, or method");
                File target = root == null ? null : new File(root, name);
                if (target != null) ensureParents(root, target.getParentFile());
                MessageDigest hash = digest(); CRC32 crc = new CRC32(); long count = 0;
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = target == null ? new Discard() : Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        count += n; total += n;
                        if (total > MAX_BYTES || count > entry.getSize()) throw new ZipException("Expanded save exceeds declared size or 64 MiB");
                        output.write(buffer, 0, n); hash.update(buffer, 0, n); crc.update(buffer, 0, n);
                    }
                }
                if (count != entry.getSize() || crc.getValue() != entry.getCrc()) throw new ZipException("ZIP entry integrity check failed");
                if (target != null) {
                    try (FileChannel channel = FileChannel.open(target.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
                    if (entry.getLastModifiedTime() != null) Files.setLastModifiedTime(target.toPath(), entry.getLastModifiedTime());
                }
                hashes.put(name, hex(hash.digest()));
            }
        }
        requireCompleteRuns(hashes.keySet());
        return new Inventory(hashes, total);
    }

    private static final class Discard extends OutputStream {
        @Override // java.io.OutputStream
        public void write(int i) {
        }

        @Override // java.io.OutputStream
        public void write(byte[] bArr, int i, int i2) {
        }

        private Discard() {
        }
    }

    static void ensureParents(File file, File file2) throws IOException {
        Path pathNormalize = file.toPath().toAbsolutePath().normalize();
        Path pathNormalize2 = file2.toPath().toAbsolutePath().normalize();
        if (!pathNormalize2.startsWith(pathNormalize) || !Files.isDirectory(pathNormalize, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid staging root");
        }
        Iterator<Path> it = pathNormalize.relativize(pathNormalize2).iterator();
        while (it.hasNext()) {
            pathNormalize = pathNormalize.resolve(it.next());
            try {
                Files.createDirectory(pathNormalize, new FileAttribute[0]);
            } catch (FileAlreadyExistsException unused) {
            }
            if (!Files.isDirectory(pathNormalize, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Linked or invalid staging directory");
            }
        }
    }

    
    
    
    
    
    
    
    
    public static void write(File root, Inventory inventory, OutputStream output) throws IOException {
        if (inventory.hashes.size() > MAX_FILES || inventory.bytes < 0 || inventory.bytes > MAX_BYTES) throw new IOException("Invalid save inventory");
        long total = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new LimitedOutputStream(output, MAX_BYTES))) {
            byte[] buffer = new byte[32768];
            for (Map.Entry<String, String> item : inventory.hashes.entrySet()) {
                if (!safeName(item.getKey())) throw new IOException("Unsafe staging path");
                File file = new File(root, item.getKey());
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setLastModifiedTime(Files.getLastModifiedTime(file.toPath(), LinkOption.NOFOLLOW_LINKS));
                zip.putNextEntry(entry); MessageDigest hash = digest();
                try (InputStream input = Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        total += n;
                        if (total > MAX_BYTES || total > inventory.bytes) throw new IOException("Staging tree changed or grew");
                        zip.write(buffer, 0, n); hash.update(buffer, 0, n);
                    }
                }
                if (!hex(hash.digest()).equals(item.getValue())) throw new IOException("Staging content changed");
                zip.closeEntry();
            }
            if (total != inventory.bytes) throw new IOException("Staging content size changed");
        }
    }

    public static final class LimitedInputStream extends FilterInputStream {
        private long count;
        private final long limit;

        public LimitedInputStream(InputStream inputStream, long j) {
            super(inputStream);
            this.limit = j;
        }

        private void add(int i) throws IOException {
            if (i > 0) {
                long j = this.count + i;
                this.count = j;
                if (j > this.limit) {
                    throw new IOException("Compressed save exceeds 64 MiB");
                }
            }
        }

        @Override // java.io.FilterInputStream, java.io.InputStream
        public int read() throws IOException {
            int i = super.read();
            if (i != -1) {
                add(1);
            }
            return i;
        }

        @Override // java.io.FilterInputStream, java.io.InputStream
        public int read(byte[] bArr, int i, int i2) throws IOException {
            int i3 = this.in.read(bArr, i, i2);
            add(i3);
            return i3;
        }
    }

    public static final class LimitedOutputStream extends FilterOutputStream {
        private long count;
        private final long limit;

        public LimitedOutputStream(OutputStream outputStream, long j) {
            super(outputStream);
            this.limit = j;
        }

        @Override // java.io.FilterOutputStream, java.io.OutputStream
        public void write(int i) throws IOException {
            long j = this.count + 1;
            this.count = j;
            if (j > this.limit) {
                throw new IOException("Archive exceeds 64 MiB");
            }
            this.out.write(i);
        }

        @Override // java.io.FilterOutputStream, java.io.OutputStream
        public void write(byte[] bArr, int i, int i2) throws IOException {
            long j = this.count + i2;
            this.count = j;
            if (j > this.limit) {
                throw new IOException("Archive exceeds 64 MiB");
            }
            this.out.write(bArr, i, i2);
        }
    }
}
