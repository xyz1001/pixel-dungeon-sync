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
import java.util.UUID;

/** Private single-job stdin/stdout protocol. No Java serialization, paths or commands beyond binding. */
public final class RootProtocol {
    public static final int MAGIC = 0x50445231;
    public static final int VERSION = 2;
    public static final int MAX_METADATA = 65536;
    public static final int MAX_REQUEST = (int) SaveArchive.MAX_BYTES + MAX_METADATA;
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
        MISSING_DIRECTORY(10), UNRECOGNIZED_LAYOUT(11), BUSY(12), RECOVERY_REQUIRED(13),
        RESTORE_CONFLICT(14), RECOVERY_CONFLICT(15), ATTRIBUTES_UNSUPPORTED(16),
        MUTATION_UNAVAILABLE(17), RESTORE_FAILED(18), RECOVERY_FAILED(19), NO_PENDING_RECOVERY(20);
        public final int wire;
        Status(int wire) { this.wire = wire; }
        static Status decode(int wire) throws IOException {
            for (Status status : values()) if (status.wire == wire) return status;
            throw new IOException("Unknown root status");
        }
    }
    public enum DiagnosticStage {
        NONE(0), EUID(1), PM_BINDING(2), ROOT_OPEN(3), LAYOUT_SCAN(4), UID_STATE(5), ARCHIVE_READ(6), PRIVATE_WORKSPACE(7), NATIVE_LOAD(8), MUTATION_PREFLIGHT(9), BACKUP_COPY(10), STAGE_PREPARE(11), ATTRIBUTES_APPLY(12), JOURNAL_READ(13), JOURNAL_WRITE(14), MOVE(15), MOVE_OLD(16), MOVE_NEW(17), VERIFY(18), ROLLBACK(19), RECOVERY(20), CLEANUP(21),
        KERNEL_OPEN_DIRECTORY(22), KERNEL_OPEN_REGULAR(23), KERNEL_DUP(24), KERNEL_FCNTL(25), KERNEL_FSTAT(26), KERNEL_LSTAT(27), KERNEL_READDIR(28), KERNEL_MKDIR(29), KERNEL_PREAD(30), KERNEL_PWRITE(31), KERNEL_FCHMOD(32), KERNEL_FCHOWN(33), KERNEL_FSYNC(34), KERNEL_GETXATTR(35), KERNEL_SETXATTR(36), KERNEL_RENAME(37), KERNEL_LINK(38), KERNEL_REMOVE(39), KERNEL_LOCK(40), KERNEL_TIMESTAMP(41), KERNEL_LSTAT_DATA_PACKAGE(42), KERNEL_LSTAT_SAVE_FILES(43), KERNEL_LSTAT_WORKSPACE(44);
        public final int wire;
        DiagnosticStage(int wire){this.wire=wire;}
        static DiagnosticStage decode(int wire)throws IOException {
            for(DiagnosticStage value:values())if(value.wire==wire)return value;
            throw new IOException("Invalid root diagnostic DiagnosticStage");
        }
    }
    public enum DiagnosticCategory {
        NONE(0), PERMISSION(1), UNSUPPORTED(2), CROSS_DEVICE(3), EXISTS(4), CHANGED(5), MALFORMED(6), LIMIT(7), IO(8), MISSING(9);
        public final int wire;
        DiagnosticCategory(int wire){this.wire=wire;}
        static DiagnosticCategory decode(int wire)throws IOException {
            for(DiagnosticCategory value:values())if(value.wire==wire)return value;
            throw new IOException("Invalid root diagnostic DiagnosticCategory");
        }
    }
    public enum Outcome {
        READ_ONLY(0), UNCHANGED(1), RESTORED(2), RESTORED_CLEANUP_PENDING(3),
        RECOVERED(4), RECOVERED_CLEANUP_PENDING(5), UNKNOWN(6);
        public final int wire;
        Outcome(int wire) { this.wire=wire; }
        static Outcome decode(int wire) throws IOException {
            for (Outcome value:values()) if(value.wire==wire)return value;
            throw new IOException("Unknown root outcome");
        }
    }
    public static final class Request {
        public final Command command;
        public final RootBinding binding;
        public final String requestId, expectedCurrentFingerprint, desiredFingerprint;
        public final int archiveLength;
        public Request(Command command, RootBinding binding) {
            this(command,binding,"",0);
        }
        public Request(Command command, RootBinding binding, String expectedCurrentFingerprint, int archiveLength) {
            this(command,binding,expectedCurrentFingerprint,"",archiveLength);
        }
        public Request(Command command,RootBinding binding,String expectedCurrentFingerprint,String desiredFingerprint,int archiveLength) {
            this(command,binding,UUID.randomUUID().toString().replace("-",""),expectedCurrentFingerprint,desiredFingerprint,archiveLength);
        }
        public Request(Command command, RootBinding binding, String requestId, String expectedCurrentFingerprint,String desiredFingerprint,int archiveLength) {
            if(command==null||binding==null)throw new IllegalArgumentException("Missing request fields");
            this.command=command;this.binding=binding;this.requestId=requestId;
            this.expectedCurrentFingerprint=expectedCurrentFingerprint;this.desiredFingerprint=desiredFingerprint;this.archiveLength=archiveLength;
        }
    }
    /** Metadata is available before archive bytes; finish MUST succeed before starting mutation. */
    public static final class RequestFrame {
        public final Request request;
        private final FrameInput payload;
        private final InputStream envelope;
        private boolean finished;
        private RequestFrame(Request request,FrameInput payload,InputStream envelope) {
            this.request=request;this.payload=payload;this.envelope=envelope;
        }
        public InputStream archive() {return payload;}
        public void finish() throws IOException {
            if(finished)return;
            if(payload.remaining!=0)throw new IOException("Root archive was not fully consumed");
            if(envelope.read()!=-1)throw new IOException("Trailing root request data");
            finished=true;
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
        public final String requestId, expectedCurrentFingerprint, desiredFingerprint;
        public final Command command;
        public final Outcome outcome;
        public final DiagnosticStage diagnosticStage;
        public final DiagnosticCategory diagnosticCategory;
        public final int errno;
        private final byte[] archive;

        /** Legacy local constructor; production replies must bind it using withRequest. */
        public Response(Status status, String bindingFingerprint, String contentState, int fileCount,
                long contentBytes, String contentFingerprint, String observationState, boolean reliable, byte[] archive) {
            this(status,status.name(),bindingFingerprint,contentState,fileCount,contentBytes,contentFingerprint,
                    observationState,reliable,archive==null?0:archive.length,archive,
                    "00000000000000000000000000000000",Command.PROBE,
                    status==Status.OK?Outcome.READ_ONLY:Outcome.UNKNOWN,"","",DiagnosticStage.NONE,DiagnosticCategory.NONE,0);
        }
        private Response(Status status, String detail, String bindingFingerprint, String contentState,
                int fileCount, long contentBytes, String contentFingerprint, String observationState,
                boolean reliable, int archiveLength, byte[] archive, String requestId, Command command,
                Outcome outcome, String expectedCurrentFingerprint,String desiredFingerprint,DiagnosticStage diagnosticStage,DiagnosticCategory diagnosticCategory,int errno) {
            this.status=status;this.detail=detail;this.bindingFingerprint=bindingFingerprint;
            this.contentState=contentState;this.fileCount=fileCount;this.contentBytes=contentBytes;
            this.contentFingerprint=contentFingerprint;this.observationState=observationState;
            this.reliable=reliable;this.archiveLength=archiveLength;this.archive=archive;
            this.requestId=requestId;this.command=command;this.outcome=outcome;
            this.expectedCurrentFingerprint=expectedCurrentFingerprint;this.desiredFingerprint=desiredFingerprint;
            this.diagnosticStage=diagnosticStage;this.diagnosticCategory=diagnosticCategory;this.errno=errno;
        }
        public Response withRequest(Request request,Outcome outcome) {
            return new Response(status,detail,bindingFingerprint,contentState,fileCount,contentBytes,
                    contentFingerprint,observationState,reliable,archiveLength,archive,request.requestId,
                    request.command,outcome,request.expectedCurrentFingerprint,request.desiredFingerprint,diagnosticStage,diagnosticCategory,errno);
        }
        public Response withDiagnostic(DiagnosticStage stage,DiagnosticCategory category,int errno) {
            return new Response(status,detail,bindingFingerprint,contentState,fileCount,contentBytes,contentFingerprint,
                    observationState,reliable,archiveLength,archive,requestId,command,outcome,expectedCurrentFingerprint,desiredFingerprint,
                    stage,category,errno);
        }
        public static Response failure(Status status, String bindingFingerprint) {
            return new Response(status,bindingFingerprint,"",0,0,"","UNKNOWN",false,null);
        }
        public static Response failure(Status status, Request request) throws IOException {
            return failure(status,request.binding.fingerprint()).withRequest(request,Outcome.UNKNOWN);
        }
    }

    private RootProtocol() { }

    public static void writeRequest(OutputStream target, Request request) throws IOException {
        writeRequest(target,request,null);
    }
    /** The archive is streamed, never copied into a request-sized byte array. */
    public static void writeRequest(OutputStream target,Request request,InputStream archive) throws IOException {
        validate(request);
        if(request.archiveLength>0&&archive==null)throw new IOException("Missing root archive");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream payload=new DataOutputStream(bytes);
        payload.writeByte(request.command.wire);writeString(payload,request.requestId);
        writeBinding(payload,request.binding);writeString(payload,request.expectedCurrentFingerprint);writeString(payload,request.desiredFingerprint);
        payload.writeInt(request.archiveLength);payload.flush();
        if(bytes.size()>MAX_METADATA)throw new IOException("Root metadata exceeds limit");
        DataOutputStream output=new DataOutputStream(target);header(output,bytes.size()+request.archiveLength);
        bytes.writeTo(output);
        if(archive!=null) {
            int remaining=request.archiveLength;byte[] buffer=new byte[32768];
            while(remaining>0) {
                int n=archive.read(buffer,0,Math.min(buffer.length,remaining));
                if(n<0)throw new EOFException("Truncated outbound root archive");
                if(n==0)continue;
                output.write(buffer,0,n);remaining-=n;
            }
            if(archive.read()!=-1)throw new IOException("Outbound root archive has trailing data");
        }
        output.flush();
        // Caller closes stdin. finish() on the root side requires EOF before mutation.
    }
    /** Read-only compatibility helper; restore callers must use readRequestFrame. */
    public static Request readRequest(InputStream source) throws IOException {
        RequestFrame frame=readRequestFrame(source);
        if(frame.request.archiveLength!=0)throw new IOException("Streaming root archive consumer required");
        frame.finish();return frame.request;
    }
    public static RequestFrame readRequestFrame(InputStream source) throws IOException {
        DataInputStream envelope=new DataInputStream(source);
        int length=readHeader(envelope,MAX_REQUEST);
        FrameInput frame=new FrameInput(envelope,length);DataInputStream metadata=new DataInputStream(frame);
        Command command=Command.decode(metadata.readUnsignedByte());String requestId=readString(metadata);
        RootBinding binding=readBinding(metadata);String expected=readString(metadata);String desired=readString(metadata);int archiveLength=metadata.readInt();
        Request request=new Request(command,binding,requestId,expected,desired,archiveLength);validate(request);
        if(length-frame.remaining>MAX_METADATA || frame.remaining!=archiveLength)
            throw new IOException("Invalid root archive framing");
        return new RequestFrame(request,frame,envelope);
    }
    private static void validate(Request request) throws IOException {
        if(request==null||request.command==null||request.binding==null||!requestId(request.requestId)
                || request.archiveLength<0||request.archiveLength>SaveArchive.MAX_BYTES
                || request.expectedCurrentFingerprint==null||request.desiredFingerprint==null)throw new IOException("Invalid root request");
        if(request.command==Command.RESTORE) {
            if(!RootBinding.isHash(request.expectedCurrentFingerprint)||!RootBinding.isHash(request.desiredFingerprint)||request.archiveLength<22)
                throw new IOException("Missing restore fingerprint or archive");
        } else if(!request.expectedCurrentFingerprint.isEmpty()||!request.desiredFingerprint.isEmpty()||request.archiveLength!=0)
            throw new IOException("Unexpected root request payload");
    }

    public static void writeResponse(OutputStream target, Response response) throws IOException {
        validate(response);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream metadata = new DataOutputStream(bytes);
        metadata.writeByte(response.status.wire);
        writeString(metadata,response.requestId);metadata.writeByte(response.command.wire);
        metadata.writeByte(response.outcome.wire);writeString(metadata,response.expectedCurrentFingerprint);writeString(metadata,response.desiredFingerprint);
        metadata.writeByte(response.diagnosticStage.wire);metadata.writeByte(response.diagnosticCategory.wire);metadata.writeInt(response.errno);
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
        String requestId=readString(payload);Command command=Command.decode(payload.readUnsignedByte());
        Outcome outcome=Outcome.decode(payload.readUnsignedByte());String expected=readString(payload);String desired=readString(payload);
        DiagnosticStage diagnosticStage=DiagnosticStage.decode(payload.readUnsignedByte());
        DiagnosticCategory diagnosticCategory=DiagnosticCategory.decode(payload.readUnsignedByte());int errno=payload.readInt();
        String detail = readString(payload);
        String binding = readString(payload);
        String contentState = readString(payload);
        int count = payload.readInt(); long bytes = payload.readLong();
        String fingerprint = readString(payload); String state = readString(payload);
        int reliableByte = payload.readUnsignedByte();
        if (reliableByte > 1) throw new IOException("Invalid reliability value");
        int archiveLength = payload.readInt();
        Response response = new Response(status, detail, binding, contentState, count, bytes, fingerprint,
                state, reliableByte == 1, archiveLength, null,requestId,command,outcome,expected,desired,diagnosticStage,diagnosticCategory,errno);
        validate(response);
        if (frame.remaining != archiveLength || (archiveLength > 0 && archiveSink == null))
            throw new IOException("Invalid archive framing or missing sink");
        byte[] buffer = new byte[32768]; int n;
        while ((n = frame.read(buffer)) != -1) archiveSink.write(buffer, 0, n);
        if (envelope.read() != -1) throw new IOException("Trailing root response data");
        return response;
    }

    private static void validate(Response response) throws IOException {
        if (response==null || response.status == null || response.command==null || response.outcome==null || !requestId(response.requestId)
                || response.diagnosticStage==null||response.diagnosticCategory==null||response.errno<0||response.errno>4095
                || response.expectedCurrentFingerprint==null||response.desiredFingerprint==null||response.bindingFingerprint==null||response.contentFingerprint==null
                || response.contentState==null||response.observationState==null
                || !(response.expectedCurrentFingerprint.isEmpty() || RootBinding.isHash(response.expectedCurrentFingerprint))
                || !(response.desiredFingerprint.isEmpty()||RootBinding.isHash(response.desiredFingerprint))
                || (response.command!=Command.RESTORE && (!response.expectedCurrentFingerprint.isEmpty()||!response.desiredFingerprint.isEmpty()))
                || !response.status.name().equals(response.detail)
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
        boolean restoreApplied=response.outcome==Outcome.RESTORED||response.outcome==Outcome.RESTORED_CLEANUP_PENDING;
        boolean recoveryApplied=response.outcome==Outcome.RECOVERED||response.outcome==Outcome.RECOVERED_CLEANUP_PENDING;
        boolean unchanged=response.outcome==Outcome.UNCHANGED;
        if((restoreApplied||recoveryApplied||unchanged) && (response.status!=Status.OK||response.archiveLength!=0
                || !RootBinding.isHash(response.bindingFingerprint)||!RootBinding.isHash(response.contentFingerprint)
                || !response.reliable||!response.observationState.matches("CACHED|ABSENT")
                || !response.contentState.matches("EMPTY_DIRECTORY|NO_TRANSFERABLE_FILES|ALLOWLISTED_FILES_PRESENT")))
            throw new IOException("Invalid applied root receipt");
        if(restoreApplied&&(response.command!=Command.RESTORE||!RootBinding.isHash(response.expectedCurrentFingerprint)||!RootBinding.isHash(response.desiredFingerprint)))
            throw new IOException("Invalid restore receipt");
        if(recoveryApplied&&response.command!=Command.RECOVER)throw new IOException("Invalid recovery receipt");
        if(unchanged&&response.command!=Command.RESTORE&&response.command!=Command.RECOVER)
            throw new IOException("Invalid unchanged receipt");
    }

    /** Bind a private-pipe reply to this exact one-shot request, never to a previous operation. */
    public static void requireReplyMatches(Request request,Response response) throws IOException {
        validate(response);
        // The runner checks effective UID before reading untrusted stdin. Such negative failures cannot echo it.
        boolean beforeRead=(response.status==Status.NOT_ROOT||response.status==Status.INVALID_REQUEST)
                && response.bindingFingerprint.isEmpty()&&response.requestId.equals("00000000000000000000000000000000")
                && response.outcome==Outcome.UNKNOWN;
        if(beforeRead)return;
        if(!request.requestId.equals(response.requestId)||request.command!=response.command
                || !request.expectedCurrentFingerprint.equals(response.expectedCurrentFingerprint)||!request.desiredFingerprint.equals(response.desiredFingerprint)
                || !request.binding.fingerprint().equals(response.bindingFingerprint))
            throw new IOException("Root reply does not match request");
    }

    private static boolean requestId(String value) {return value!=null&&value.matches("[0-9a-f]{32}");}

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
