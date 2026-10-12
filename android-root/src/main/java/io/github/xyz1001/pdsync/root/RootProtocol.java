package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.bridge.SaveArchive;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Private single-job stdin/stdout protocol. No Java serialization, paths or commands beyond binding. */
public final class RootProtocol {
    public static final int MAGIC = 0x50445231;
    public static final int VERSION = 1;
    public static final int MAX_REQUEST = 65536;
    public static final int MAX_RESPONSE = (int) SaveArchive.MAX_BYTES + 8192;
    public static final int MAX_STRING = 2048;

    public enum Command {
        PROBE(1), INSPECT(2), EXPORT(3), OBSERVE(4), RESTORE(5), RECOVER(6);
        public final int wire;
        Command(int wire) { this.wire = wire; }
        static Command decode(int wire) throws IOException {
            for (Command command : values()) if (command.wire == wire) return command;
            throw new IOException("Unknown root command");
        }
    }
    public enum Status {
        OK(0), UNSUPPORTED(1), NOT_ROOT(2), INVALID_REQUEST(3), BINDING_MISMATCH(4),
        UNSAFE_PATH(5), CHANGED(6), TOO_LARGE(7), UNRELIABLE(8), IO_ERROR(9),
        MISSING_DIRECTORY(10), UNRECOGNIZED_LAYOUT(11), BUSY(12);
        public final int wire;
        Status(int wire) { this.wire = wire; }
        static Status decode(int wire) throws IOException {
            for (Status status : values()) if (status.wire == wire) return status;
            throw new IOException("Unknown root status");
        }
    }
    public static final class Request {
        public final Command command;
        public final RootBinding binding;
        public Request(Command command, RootBinding binding) {
            if (command == null || binding == null) throw new IllegalArgumentException("Missing request fields");
            this.command = command; this.binding = binding;
        }
    }
    public static final class Response {
        public final Status status;
        public final String detail;
        public final String bindingFingerprint;
        public final String contentState;
        public final int fileCount;
        public final long contentBytes;
        public final String contentFingerprint;
        public final String observationState;
        public final boolean reliable;
        public final int archiveLength;
        private final byte[] archive;

        public Response(Status status, String bindingFingerprint, String contentState, int fileCount,
                long contentBytes, String contentFingerprint, String observationState, boolean reliable, byte[] archive) {
            this(status, status.name(), bindingFingerprint, contentState, fileCount, contentBytes,
                    contentFingerprint, observationState, reliable, archive == null ? 0 : archive.length, archive);
        }
        private Response(Status status, String detail, String bindingFingerprint, String contentState,
                int fileCount, long contentBytes, String contentFingerprint, String observationState,
                boolean reliable, int archiveLength, byte[] archive) {
            this.status = status; this.detail = detail; this.bindingFingerprint = bindingFingerprint;
            this.contentState = contentState; this.fileCount = fileCount; this.contentBytes = contentBytes;
            this.contentFingerprint = contentFingerprint; this.observationState = observationState;
            this.reliable = reliable; this.archiveLength = archiveLength; this.archive = archive;
        }
        public static Response failure(Status status, String bindingFingerprint) {
            return new Response(status, bindingFingerprint, "", 0, 0, "", "UNKNOWN", false, null);
        }
    }

    private RootProtocol() { }

    public static void writeRequest(OutputStream target, Request request) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(bytes);
        payload.writeByte(request.command.wire);
        writeBinding(payload, request.binding);
        payload.flush();
        if (bytes.size() > MAX_REQUEST) throw new IOException("Root request exceeds limit");
        DataOutputStream output = new DataOutputStream(target);
        header(output, bytes.size()); bytes.writeTo(output); output.flush();
        // Caller must close subprocess stdin. readRequest requires EOF, prohibiting a second job.
    }

    public static Request readRequest(InputStream source) throws IOException {
        DataInputStream input = new DataInputStream(source);
        int length = readHeader(input, MAX_REQUEST);
        byte[] bytes = new byte[length]; input.readFully(bytes);
        if (input.read() != -1) throw new IOException("Trailing root request data");
        DataInputStream payload = new DataInputStream(new ByteArrayInputStream(bytes));
        Command command = Command.decode(payload.readUnsignedByte());
        RootBinding binding = readBinding(payload);
        if (payload.read() != -1) throw new IOException("Trailing root payload fields");
        return new Request(command, binding);
    }

    public static void writeResponse(OutputStream target, Response response) throws IOException {
        validate(response);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream metadata = new DataOutputStream(bytes);
        metadata.writeByte(response.status.wire);
        writeString(metadata, response.detail);
        writeString(metadata, response.bindingFingerprint);
        writeString(metadata, response.contentState);
        metadata.writeInt(response.fileCount);
        metadata.writeLong(response.contentBytes);
        writeString(metadata, response.contentFingerprint);
        writeString(metadata, response.observationState);
        metadata.writeBoolean(response.reliable);
        metadata.writeInt(response.archiveLength);
        metadata.flush();
        if (response.archiveLength != 0 && (response.archive == null || response.archive.length != response.archiveLength))
            throw new IOException("Missing archive payload");
        DataOutputStream output = new DataOutputStream(target);
        header(output, bytes.size() + response.archiveLength);
        bytes.writeTo(output);
        if (response.archive != null) output.write(response.archive);
        output.flush();
    }

    /** Streams archive to the caller's private uncommitted sink; no 64 MiB frame duplication. */
    public static Response readResponse(InputStream source, OutputStream archiveSink) throws IOException {
        DataInputStream envelope = new DataInputStream(source);
        FrameInput frame = new FrameInput(envelope, readHeader(envelope, MAX_RESPONSE));
        DataInputStream payload = new DataInputStream(frame);
        Status status = Status.decode(payload.readUnsignedByte());
        String detail = readString(payload);
        String binding = readString(payload);
        String contentState = readString(payload);
        int count = payload.readInt(); long bytes = payload.readLong();
        String fingerprint = readString(payload); String state = readString(payload);
        int reliableByte = payload.readUnsignedByte();
        if (reliableByte > 1) throw new IOException("Invalid reliability value");
        int archiveLength = payload.readInt();
        Response response = new Response(status, detail, binding, contentState, count, bytes, fingerprint,
                state, reliableByte == 1, archiveLength, null);
        validate(response);
        if (frame.remaining != archiveLength || (archiveLength > 0 && archiveSink == null))
            throw new IOException("Invalid archive framing or missing sink");
        byte[] buffer = new byte[32768]; int n;
        while ((n = frame.read(buffer)) != -1) archiveSink.write(buffer, 0, n);
        if (envelope.read() != -1) throw new IOException("Trailing root response data");
        return response;
    }

    private static void validate(Response response) throws IOException {
        if (response.status == null || !response.status.name().equals(response.detail)
                || !(response.bindingFingerprint.isEmpty() || RootBinding.isHash(response.bindingFingerprint))
                || !(response.contentFingerprint.isEmpty() || RootBinding.isHash(response.contentFingerprint))
                || !response.contentState.matches("[A-Z_]{0,48}")
                || !response.observationState.matches("UNKNOWN|FOREGROUND|BACKGROUND|CACHED|ABSENT")
                || response.fileCount < 0 || response.fileCount > SaveArchive.MAX_FILES
                || response.contentBytes < 0 || response.contentBytes > SaveArchive.MAX_BYTES
                || response.archiveLength < 0 || response.archiveLength > SaveArchive.MAX_BYTES
                || (response.status != Status.OK && (response.archiveLength != 0 || response.reliable))
                || (response.reliable && response.observationState.equals("UNKNOWN")))
            throw new IOException("Invalid root response");
    }

    static void writeBinding(DataOutputStream output, RootBinding binding) throws IOException {
        writeString(output, binding.packageName); output.writeInt(binding.userId); output.writeInt(binding.uid);
        writeString(output, binding.dataDir); writeString(output, binding.sourceIdentity);
        output.writeInt(binding.apkHashes.size());
        for (Map.Entry<String, String> item : binding.apkHashes.entrySet()) {
            writeString(output, item.getKey()); writeString(output, item.getValue());
        }
    }
    private static RootBinding readBinding(DataInputStream input) throws IOException {
        String packageName = readString(input); int userId = input.readInt(); int uid = input.readInt();
        String dir = readString(input); String source = readString(input); int count = input.readInt();
        if (count < 1 || count > RootBinding.MAX_APKS) throw new IOException("Invalid APK count");
        TreeMap<String, String> hashes = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            String path = readString(input); String hash = readString(input);
            if (hashes.put(path, hash) != null) throw new IOException("Duplicate APK binding");
        }
        return new RootBinding(packageName, userId, uid, dir, source, hashes);
    }
    static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) throw new IOException("Missing string");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING) throw new IOException("Root string exceeds limit");
        output.writeInt(bytes.length); output.write(bytes);
    }
    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING) throw new IOException("Invalid root string length");
        byte[] bytes = new byte[length]; input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { throw new IOException("Invalid UTF-8", invalid); }
    }
    private static void header(DataOutputStream output, int length) throws IOException {
        output.writeInt(MAGIC); output.writeInt(VERSION); output.writeInt(length);
    }
    private static int readHeader(DataInputStream input, int maximum) throws IOException {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unsupported root framing");
        int length = input.readInt();
        if (length < 1 || length > maximum) throw new IOException("Invalid root frame length");
        return length;
    }
    private static final class FrameInput extends InputStream {
        final InputStream input; int remaining;
        FrameInput(InputStream input, int remaining) { this.input = input; this.remaining = remaining; }
        @Override public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = input.read(); if (value == -1) throw new EOFException("Truncated frame");
            remaining--; return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int n = input.read(bytes, offset, Math.min(length, remaining));
            if (n == -1) throw new EOFException("Truncated frame");
            remaining -= n; return n;
        }
    }
}
