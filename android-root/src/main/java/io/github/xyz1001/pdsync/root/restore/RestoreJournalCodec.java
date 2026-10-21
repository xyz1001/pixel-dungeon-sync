package io.github.xyz1001.pdsync.root.restore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.Attributes;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.Content;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.Entry;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.Journal;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.Phase;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.Target;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.Tree;
import io.github.xyz1001.pdsync.root.restore.RootRestoreTransaction.Workspace;

/** Bounded, deterministic local-only journal codec. Not a cloud archive or executable format. */
public final class RestoreJournalCodec {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final long MAGIC = 0x50445352544a3031L; // PDSRTJ01
    private RestoreJournalCodec() { }

    public static byte[] encode(Journal journal) throws IOException {
        if (journal == null) throw new IOException("Journal required");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (bytes.size() >= MAX_BYTES - 32) throw new IOException("Restore journal too large");
                bytes.write(value);
            }
            @Override public void write(byte[] value, int offset, int length) throws IOException {
                if (length > MAX_BYTES - 32 - bytes.size()) throw new IOException("Restore journal too large");
                bytes.write(value, offset, length);
            }
        });
        output.writeLong(MAGIC); output.writeInt(journal.schema);
        Workspace workspace = journal.workspace; Target target = workspace.target;
        string(output, workspace.transactionId); string(output, workspace.identityFingerprint);
        string(output, target.bindingFingerprint); string(output, target.canonicalDataDir);
        output.writeInt(target.gameUid); output.writeLong(target.device);
        output.writeLong(target.dataDirInode); output.writeLong(target.filesInode);
        output.writeLong(workspace.device); string(output, journal.phase.name()); output.writeInt(journal.cursor);
        tree(output, journal.before); tree(output, journal.desired); output.flush();
        byte[] body = bytes.toByteArray(); bytes.write(digest(body));
        return bytes.toByteArray();
    }

    /** Adapter must bound the file read to MAX_BYTES before calling this method. */
    public static Journal decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 48 || bytes.length > MAX_BYTES) throw new IOException("Invalid restore journal length");
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
        byte[] checksum = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        if (!MessageDigest.isEqual(checksum, digest(body))) throw new IOException("Restore journal checksum mismatch");
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(body));
        if (input.readLong() != MAGIC) throw new IOException("Invalid restore journal magic");
        int schema = input.readInt();
        String transaction = string(input, 36), workspaceIdentity = string(input, 64);
        String binding = string(input, 64), dataDir = string(input, 2048);
        int uid = input.readInt(); long device = input.readLong();
        Target target = new Target(binding, dataDir, uid, device, input.readLong(), input.readLong());
        Workspace workspace = new Workspace(transaction, workspaceIdentity, target, input.readLong());
        Phase phase;
        try { phase = Phase.valueOf(string(input, 32)); }
        catch (IllegalArgumentException invalid) { throw new IOException("Unknown restore journal phase", invalid); }
        int cursor = input.readInt();
        Tree before = tree(input), desired = tree(input);
        if (input.read() != -1) throw new IOException("Trailing restore journal fields");
        return new Journal(schema, workspace, before, desired, phase, cursor);
    }

    private static void tree(DataOutputStream output, Tree tree) throws IOException {
        output.writeInt(tree.entries.size());
        for (Map.Entry<String, Entry> item : tree.entries.entrySet()) {
            string(output, item.getKey()); Entry entry = item.getValue();
            output.writeBoolean(entry.directory());
            output.writeInt(entry.attributes.uid); output.writeInt(entry.attributes.gid);
            output.writeInt(entry.attributes.mode); string(output, entry.attributes.selinux);
            if (!entry.directory()) {
                string(output, entry.content.sha256); output.writeLong(entry.content.bytes);
                output.writeLong(entry.content.modifiedMillis);
            }
        }
    }

    private static Tree tree(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > RootRestoreTransaction.MAX_ENTRIES) throw new IOException("Excessive journal entries");
        TreeMap<String, Entry> entries = new TreeMap<>();
        String previous = null;
        for (int i = 0; i < count; i++) {
            String path = string(input, 1024);
            if (previous != null && previous.compareTo(path) >= 0) throw new IOException("Duplicate or unsorted journal path");
            previous = path;
            int kind = input.readUnsignedByte();
            if (kind != 0 && kind != 1) throw new IOException("Invalid journal entry type");
            Attributes attrs = new Attributes(input.readInt(), input.readInt(), input.readInt(), string(input, 512));
            Content content = kind == 1 ? null : new Content(string(input, 64), input.readLong(), input.readLong());
            entries.put(path, new Entry(attrs, content));
        }
        return new Tree(entries);
    }

    private static void string(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }
    private static String string(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maximumBytes) throw new IOException("Invalid journal string length");
        byte[] bytes = new byte[length]; input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { throw new IOException("Invalid journal UTF-8", invalid); }
    }
    private static byte[] digest(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }
}
