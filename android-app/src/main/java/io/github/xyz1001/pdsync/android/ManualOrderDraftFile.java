package io.github.xyz1001.pdsync.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A tiny non-secret draft per account in a controller-supplied app-private directory.
 * The directory must be inside Context.getFilesDir(), never shared/external storage.
 * It contains no passwords, payment receipts or authentication keys. SHA-256 filenames
 * prevent account strings from becoming filesystem paths; hashing is not encryption.
 */
public final class ManualOrderDraftFile implements ManualPaymentFlow.DraftStore {
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 16 * 1024;
    private final Path directory;

    public ManualOrderDraftFile(File privateDirectory) {
        if (privateDirectory == null) throw new IllegalArgumentException("Missing private directory");
        directory = privateDirectory.toPath().toAbsolutePath().normalize();
    }

    @Override public synchronized ManualPaymentFlow.Draft load(String accountScope) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid private draft directory");
        Path file = path(accountScope);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES)
            throw new IOException("Invalid sandbox draft file");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_BYTES) throw new IOException("Sandbox draft too large");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != VERSION) throw new IOException("Unknown sandbox draft version");
            String owner = in.readUTF();
            String plan = in.readUTF();
            String key = in.readBoolean() ? in.readUTF() : null;
            String order = in.readBoolean() ? in.readUTF() : null;
            if (in.read() != -1 || !accountScope.equals(owner)) throw new IOException("Invalid sandbox draft identity");
            return new ManualPaymentFlow.Draft(owner, plan, key, order);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid sandbox draft data");
        }
    }

    @Override public synchronized void save(ManualPaymentFlow.Draft draft) throws IOException {
        if (draft == null) throw new IllegalArgumentException("Missing draft");
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid private draft directory");
        Path target = path(draft.accountScope);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid sandbox draft target");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            out.writeUTF(draft.accountScope);
            out.writeUTF(draft.planId);
            out.writeBoolean(draft.requestKey != null);
            if (draft.requestKey != null) out.writeUTF(draft.requestKey);
            out.writeBoolean(draft.orderId != null);
            if (draft.orderId != null) out.writeUTF(draft.orderId);
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("Sandbox draft too large");
        Path temp = Files.createTempFile(directory, ".sandbox-draft-", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
                bytes.writeTo(out);
                out.getFD().sync();
            }
            // Fail closed if this filesystem cannot atomically replace a private draft.
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
                parent.force(true);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path path(String scope) throws IOException {
        if (scope == null || scope.isEmpty() || scope.length() > 1024) throw new IOException("Invalid account scope");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder("sandbox-order-");
            for (byte b : digest) name.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return directory.resolve(name.append(".draft").toString());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("Draft digest unavailable");
        }
    }
}
