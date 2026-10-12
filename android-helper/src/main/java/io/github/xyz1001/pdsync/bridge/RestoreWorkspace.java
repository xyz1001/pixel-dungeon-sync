package io.github.xyz1001.pdsync.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.UUID;


final class RestoreWorkspace {
    private static final int MAX_RECORD = 4096;
    private static final String PREFIX = ".pdsync-restore-";
    static final String RECEIPT = "restore-workspace.properties";
    final Access access;
    final Path area;
    final String binding;
    final Path parent;
    final Path receipt;
    final long uid;

    interface Access {
        Stamp stat(Path path) throws IOException;

        void sync(Path path) throws IOException;

        void validateBinding() throws IOException;
    }

    static final class Stamp {
        final long dev;
        final long ino;
        final String key;
        final long links;
        final long size;
        final int type;
        final long uid;

        Stamp(long j, long j2, int i, long j3, long j4, long j5, String str) {
            this.dev = j;
            this.ino = j2;
            this.type = i;
            this.uid = j3;
            this.links = j4;
            this.size = j5;
            this.key = str;
        }

        String stable() {
            return this.dev + ":" + this.ino + ":" + this.type + ":" + this.uid;
        }

        boolean directory() {
            return this.type == 16384;
        }

        boolean regular() {
            return this.type == 32768 && this.links == 1;
        }
    }

    RestoreWorkspace(File file, File file2, String str, long j, Access access) throws IOException {
        Path pathNormalize = file.toPath().toAbsolutePath().normalize();
        this.parent = pathNormalize;
        Path pathNormalize2 = file2.toPath().toAbsolutePath().normalize();
        this.area = pathNormalize2;
        this.receipt = pathNormalize2.resolve("restore-workspace.properties");
        this.binding = str;
        this.uid = j;
        this.access = access;
        if (!pathNormalize.equals(file.getCanonicalFile().toPath()) || !str.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
    }

    boolean recorded() throws IOException {
        return optional(this.receipt) != null;
    }

    
    public Stamp optional(Path path) throws IOException {
        try {
            return this.access.stat(path);
        } catch (NoSuchFileException unused) {
            return null;
        }
    }

    
    public Stamp directory(Path path) throws IOException {
        Stamp stampStat = this.access.stat(path);
        if (stampStat.directory() && stampStat.uid == this.uid && stampStat.key != null) {
            return stampStat;
        }
        throw invalid();
    }

    
    public void validateParent(String str, String str2) throws IOException {
        this.access.validateBinding();
        directory(this.area);
        Stamp stampDirectory = directory(this.parent);
        if (stampDirectory.stable().equals(str) && stampDirectory.key.equals(str2)) {
            Path path = this.parent;
            if (path.equals(path.toFile().getCanonicalFile().toPath())) {
                return;
            }
        }
        throw invalid();
    }

    Handle create() throws IOException {
        this.access.validateBinding();
        directory(this.area);
        Stamp stampDirectory = directory(this.parent);
        if (recorded()) {
            throw new IOException("An earlier restore workspace must be cleaned first");
        }
        Path pathResolve = this.parent.resolve(".pdsync-restore-" + UUID.randomUUID());
        Files.createDirectory(pathResolve, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Stamp stampDirectory2 = directory(pathResolve);
        if (stampDirectory2.dev != stampDirectory.dev) {
            throw invalid();
        }
        Handle handle = new Handle(pathResolve, stampDirectory.stable(), stampDirectory.key, stampDirectory2.stable(), stampDirectory2.key);
        handle.validate();
        this.access.sync(pathResolve);
        this.access.sync(this.parent);
        Properties propertiesRecord = handle.record();
        Path pathResolve2 = this.area.resolve("restore-workspace.properties.new");
        Stamp stampOptional = optional(pathResolve2);
        if (stampOptional != null) {
            if (!stampOptional.regular() || stampOptional.uid != this.uid || stampOptional.size > 4096) {
                throw invalid();
            }
            Files.delete(pathResolve2);
        }
        Files.createFile(pathResolve2, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        OutputStream outputStreamNewOutputStream = Files.newOutputStream(pathResolve2, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            propertiesRecord.store(outputStreamNewOutputStream, "Exact private restore staging receipt");
            if (outputStreamNewOutputStream != null) {
                outputStreamNewOutputStream.close();
            }
            FileChannel fileChannelOpen = FileChannel.open(pathResolve2, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                fileChannelOpen.force(true);
                if (fileChannelOpen != null) {
                    fileChannelOpen.close();
                }
                handle.validate();
                if (recorded()) {
                    throw invalid();
                }
                Files.move(pathResolve2, this.receipt, StandardCopyOption.ATOMIC_MOVE);
                this.access.sync(this.area);
                handle.validateReceipt();
                return handle;
            } finally {
            }
        } finally {
        }
    }

    Handle recordedHandle() throws IOException {
        this.access.validateBinding();
        directory(this.area);
        Stamp stampStat = this.access.stat(this.receipt);
        if (!stampStat.regular() || stampStat.uid != this.uid || stampStat.size < 1 || stampStat.size > 4096) {
            throw invalid();
        }
        Properties properties = new Properties();
        try {
            InputStream inputStreamNewInputStream = Files.newInputStream(this.receipt, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try {
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                byte[] bArr = new byte[512];
                while (true) {
                    int i = inputStreamNewInputStream.read(bArr);
                    if (i == -1) {
                        properties.load(new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));
                        if (inputStreamNewInputStream != null) {
                            inputStreamNewInputStream.close();
                        }
                        Stamp stampStat2 = this.access.stat(this.receipt);
                        if (!stampStat.stable().equals(stampStat2.stable()) || stampStat.size != stampStat2.size || !stampStat2.regular()) {
                            throw invalid();
                        }
                        HashSet hashSet = new HashSet(Arrays.asList("version", "binding", "parent", "basename", "parentIdentity", "parentKey", "workIdentity", "workKey"));
                        String property = properties.getProperty("basename", "");
                        if (!properties.stringPropertyNames().equals(hashSet) || !"1".equals(properties.getProperty("version")) || !this.binding.equals(properties.getProperty("binding")) || !this.parent.toString().equals(properties.getProperty("parent")) || !property.matches("\\.pdsync-restore-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                            throw invalid();
                        }
                        Handle handle = new Handle(this.parent.resolve(property), properties.getProperty("parentIdentity"), properties.getProperty("parentKey"), properties.getProperty("workIdentity"), properties.getProperty("workKey"));
                        handle.validateParent();
                        if (optional(handle.path) != null) {
                            handle.validate();
                        }
                        return handle;
                    }
                    if (byteArrayOutputStream.size() + i > 4096) {
                        throw invalid();
                    }
                    byteArrayOutputStream.write(bArr, 0, i);
                }
            } finally {
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid restore workspace receipt", e);
        }
    }

    void cleanupRecorded() throws IOException {
        if (recorded()) {
            recordedHandle().cleanup();
        }
    }

    final class Handle {
        private final String parentIdentity;
        private final String parentKey;
        final Path path;
        private final String workIdentity;
        private final String workKey;

        Handle(Path path, String str, String str2, String str3, String str4) {
            this.path = path;
            this.parentIdentity = str;
            this.parentKey = str2;
            this.workIdentity = str3;
            this.workKey = str4;
        }

        File file() {
            return this.path.toFile();
        }

        void validateParent() throws IOException {
            RestoreWorkspace.this.validateParent(this.parentIdentity, this.parentKey);
        }

        void validate() throws IOException {
            validateParent();
            Stamp stampDirectory = RestoreWorkspace.this.directory(this.path);
            if (!this.workIdentity.equals(stampDirectory.stable()) || !this.workKey.equals(stampDirectory.key) || !this.path.getParent().equals(RestoreWorkspace.this.parent)) {
                throw RestoreWorkspace.invalid();
            }
        }

        Properties record() {
            Properties properties = new Properties();
            properties.setProperty("version", "1");
            properties.setProperty("binding", RestoreWorkspace.this.binding);
            properties.setProperty("parent", RestoreWorkspace.this.parent.toString());
            properties.setProperty("basename", this.path.getFileName().toString());
            properties.setProperty("parentIdentity", this.parentIdentity);
            properties.setProperty("parentKey", this.parentKey);
            properties.setProperty("workIdentity", this.workIdentity);
            properties.setProperty("workKey", this.workKey);
            return properties;
        }

        void validateReceipt() throws IOException {
            if (!record().equals(RestoreWorkspace.this.recordedHandle().record())) {
                throw RestoreWorkspace.invalid();
            }
        }

        void cleanup() throws IOException {
            validateReceipt();
            validateParent();
            if (RestoreWorkspace.this.optional(this.path) != null) {
                validate();
                DirectoryStream<Path> directoryStreamNewDirectoryStream = Files.newDirectoryStream(RestoreWorkspace.this.parent);
                try {
                    if (!(directoryStreamNewDirectoryStream instanceof SecureDirectoryStream)) {
                        throw new IOException("Secure staging cleanup is unavailable");
                    }
                    SecureDirectoryStream secureDirectoryStream = (SecureDirectoryStream) directoryStreamNewDirectoryStream;
                    RestoreWorkspace.requireKey(secureDirectoryStream, this.parentKey);
                    SecureDirectoryStream<Path> secureDirectoryStreamNewDirectoryStream = secureDirectoryStream.newDirectoryStream(this.path.getFileName(), LinkOption.NOFOLLOW_LINKS);
                    try {
                        RestoreWorkspace.requireKey(secureDirectoryStreamNewDirectoryStream, this.workKey);
                        validate();
                        clear(secureDirectoryStreamNewDirectoryStream, ((PosixFileAttributeView) secureDirectoryStreamNewDirectoryStream.getFileAttributeView(PosixFileAttributeView.class)).readAttributes().owner(), 0, new int[]{0});
                        if (secureDirectoryStreamNewDirectoryStream != null) {
                            secureDirectoryStreamNewDirectoryStream.close();
                        }
                        validate();
                        if (!this.workKey.equals(RestoreWorkspace.key(((BasicFileAttributeView) secureDirectoryStream.getFileAttributeView(this.path.getFileName(), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)).readAttributes()))) {
                            throw RestoreWorkspace.invalid();
                        }
                        secureDirectoryStream.deleteDirectory(this.path.getFileName());
                        if (directoryStreamNewDirectoryStream != null) {
                            directoryStreamNewDirectoryStream.close();
                        }
                    } finally {
                    }
                } catch (Throwable th) {
                    if (directoryStreamNewDirectoryStream != null) {
                        try {
                            directoryStreamNewDirectoryStream.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                    }
                    throw th;
                }
            }
            validateParent();
            RestoreWorkspace.this.access.sync(RestoreWorkspace.this.parent);
            validateReceipt();
            Files.delete(RestoreWorkspace.this.receipt);
            RestoreWorkspace.this.access.sync(RestoreWorkspace.this.area);
        }

        private void clear(SecureDirectoryStream<Path> secureDirectoryStream, UserPrincipal userPrincipal, int i, int[] iArr) throws IOException {
            if (i > 20) {
                throw RestoreWorkspace.invalid();
            }
            for (Path path : secureDirectoryStream) {
                validate();
                int i2 = iArr[0] + 1;
                iArr[0] = i2;
                if (i2 > 131072) {
                    throw RestoreWorkspace.invalid();
                }
                Path fileName = path.getFileName();
                PosixFileAttributes attributes = ((PosixFileAttributeView) secureDirectoryStream.getFileAttributeView(fileName, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)).readAttributes();
                if (!userPrincipal.equals(attributes.owner())) {
                    throw RestoreWorkspace.invalid();
                }
                if (attributes.isDirectory()) {
                    SecureDirectoryStream<Path> secureDirectoryStreamNewDirectoryStream = secureDirectoryStream.newDirectoryStream(fileName, LinkOption.NOFOLLOW_LINKS);
                    try {
                        RestoreWorkspace.requireKey(secureDirectoryStreamNewDirectoryStream, RestoreWorkspace.key(attributes));
                        clear(secureDirectoryStreamNewDirectoryStream, userPrincipal, i + 1, iArr);
                        if (secureDirectoryStreamNewDirectoryStream != null) {
                            secureDirectoryStreamNewDirectoryStream.close();
                        }
                        validate();
                        if (!RestoreWorkspace.key(attributes).equals(RestoreWorkspace.key(((BasicFileAttributeView) secureDirectoryStream.getFileAttributeView(fileName, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)).readAttributes()))) {
                            throw RestoreWorkspace.invalid();
                        }
                        secureDirectoryStream.deleteDirectory(fileName);
                    } catch (Throwable th) {
                        if (secureDirectoryStreamNewDirectoryStream != null) {
                            try {
                                secureDirectoryStreamNewDirectoryStream.close();
                            } catch (Throwable th2) {
                                th.addSuppressed(th2);
                            }
                        }
                        throw th;
                    }
                } else {
                    if (!attributes.isRegularFile() && !attributes.isSymbolicLink()) {
                        throw RestoreWorkspace.invalid();
                    }
                    validate();
                    secureDirectoryStream.deleteFile(fileName);
                }
            }
        }
    }

    
    public static void requireKey(SecureDirectoryStream<Path> secureDirectoryStream, String str) throws IOException {
        BasicFileAttributes attributes = ((BasicFileAttributeView) secureDirectoryStream.getFileAttributeView(BasicFileAttributeView.class)).readAttributes();
        if (!attributes.isDirectory() || !str.equals(key(attributes))) {
            throw invalid();
        }
    }

    static String key(BasicFileAttributes basicFileAttributes) throws IOException {
        if (basicFileAttributes.fileKey() != null) {
            return basicFileAttributes.fileKey().toString();
        }
        throw invalid();
    }

    
    public static IOException invalid() {
        return new IOException("Restore workspace identity or receipt is invalid");
    }
}
