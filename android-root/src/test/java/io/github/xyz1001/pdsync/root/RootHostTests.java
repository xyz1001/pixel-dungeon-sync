package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.bridge.SaveArchive;
import io.github.xyz1001.pdsync.bridge.SaveDirectoryPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Pure host tests. Every runtime/UID/PM/OS boundary is injected; no su or Android tools execute. */
public final class RootHostTests {
    private static int assertions;
    private static final int UID = 10042;
    private static final String HASH = repeat('a', 64);
    private static final String APK = "/data/app/com.example.game-xyz/base.apk";
    private interface Checked { void run() throws Exception; }
    private static String repeat(char value, int count) { char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars); }
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static void bad(Checked work, String label) throws Exception {
        try { work.run(); } catch (IOException | IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Accepted " + label);
    }
    private static RootBinding binding() throws IOException { return binding("com.example.game", 0, UID, "/data/user/0/com.example.game", HASH); }
    private static RootBinding binding(String pkg, int user, int uid, String data, String hash) throws IOException {
        return new RootBinding(pkg, user, uid, data, HASH, Collections.singletonMap(APK, hash));
    }
    private static RootProtocol.Request request(RootProtocol.Command command) throws IOException { return new RootProtocol.Request(command, binding()); }
    public static void main(String[] args) throws Exception {
        bindings(); protocol(); lifecycle(); filesystem(); limitsAndRaces(); service();
        System.out.println("Root host tests passed: " + assertions + " assertions; Android/root runtime NOT tested");
    }
    private static void bindings() throws Exception {
        RootBinding valid = binding();
        check(valid.fingerprint().length() == 64, "binding hash");
        check(valid.matchesInstalled(binding("com.example.game", 0, UID, "/data/data/com.example.game", HASH)), "trusted legacy alias");
        check(!valid.matchesInstalled(binding("com.example.game", 0, UID, valid.dataDir, repeat('b', 64))), "installed APK change");
        check(!valid.fingerprint().equals(binding("com.example.game", 0, UID, "/data/data/com.example.game", HASH).fingerprint()), "wire binds selected PM spelling");
        for (String path : new String[] {"/sdcard/save", "/data/user/0/com.other.game", "/data/user/0/com.example.game/files", "/data/user/0/../com.example.game", "/data/user/00/com.example.game", "/data/user_de/0/com.example.game"})
            bad(() -> binding("com.example.game", 0, UID, path, HASH), "arbitrary data path " + path);
        for (int uid : new int[] {0, 1000, 9999, 20000, 99999, 110042})
            bad(() -> binding("com.example.game", 0, uid, valid.dataDir, HASH), "wrong UID");
        check(binding("com.example.game", 10, 1010042, "/data/user/10/com.example.game", HASH).userId == 10, "secondary user");
        bad(() -> binding("com.example.game", 10, 1010042, "/data/data/com.example.game", HASH), "wrong-user alias");
        for (String pkg : new String[] {"com;id", "../x", "com.x\n", "com.x/x", "com..x", ""})
            bad(() -> binding(pkg, 0, UID, valid.dataDir, HASH), "package injection");
        for (String apk : new String[] {"/proc/self/fd/1", "/data/app/x/../base.apk", "/data/app/x//base.apk", "/sdcard/base.apk", "/data/app/x/base.apk\n", "/data/app/x/base.apk;id"})
            bad(() -> new RootBinding(valid.packageName, 0, UID, valid.dataDir, HASH, Collections.singletonMap(apk, HASH)), "APK injection");
        bad(() -> binding("com.example.game", 0, UID, valid.dataDir, "abc"), "malformed hash");
        Map<String, String> copy = new HashMap<>(); copy.put(APK, HASH);
        RootBinding immutable = new RootBinding(valid.packageName, 0, UID, valid.dataDir, HASH, copy);
        copy.clear(); check(immutable.apkHashes.size() == 1, "defensive binding copy");
    }
    private static byte[] wire(RootProtocol.Request request) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); RootProtocol.writeRequest(output, request); return output.toByteArray();
    }
    private static byte[] frame(byte[] payload) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(output);
        data.writeInt(RootProtocol.MAGIC); data.writeInt(RootProtocol.VERSION); data.writeInt(payload.length); data.write(payload); return output.toByteArray();
    }
    private static void intAt(byte[] bytes, int at, int value) { for (int i = 0; i < 4; i++) bytes[at+i] = (byte) (value >>> (24-8*i)); }
    private static void protocol() throws Exception {
        for (RootProtocol.Command command : RootProtocol.Command.values()) {
            if(command==RootProtocol.Command.RESTORE)continue; // Restore requires a separately streamed, integrity-bound archive.
            RootProtocol.Request decoded = RootProtocol.readRequest(new ByteArrayInputStream(wire(request(command))));
            check(decoded.command == command && decoded.binding.fingerprint().equals(binding().fingerprint()), "command roundtrip");
        }
        byte[] valid = wire(request(RootProtocol.Command.PROBE));
        for (int index : new int[] {0, 4, 8}) {
            byte[] bad = valid.clone(); intAt(bad, index, -1);
            bad(() -> RootProtocol.readRequest(new ByteArrayInputStream(bad)), "bad frame header");
        }
        byte[] large = valid.clone(); intAt(large, 8, RootProtocol.MAX_REQUEST + 1);
        bad(() -> RootProtocol.readRequest(new ByteArrayInputStream(large)), "excessive declared frame");
        for (int length : new int[] {0, 1, 11, 12, valid.length - 1})
            bad(() -> RootProtocol.readRequest(new ByteArrayInputStream(Arrays.copyOf(valid, length))), "truncated request");
        bad(() -> RootProtocol.readRequest(new ByteArrayInputStream(Arrays.copyOf(valid, valid.length+1))), "trailing request");
        byte[] unknown = valid.clone(); unknown[12] = 127;
        bad(() -> RootProtocol.readRequest(new ByteArrayInputStream(unknown)), "unknown command");
        byte[] negativeString = valid.clone(); intAt(negativeString, 13, -1);
        bad(() -> RootProtocol.readRequest(new ByteArrayInputStream(negativeString)), "negative string");
        byte[] excessiveString = valid.clone(); intAt(excessiveString, 13, 2049);
        bad(() -> RootProtocol.readRequest(new ByteArrayInputStream(excessiveString)), "huge string");
        byte[] invalidUtf = valid.clone(); invalidUtf[17] = (byte) 0xff;
        bad(() -> RootProtocol.readRequest(new ByteArrayInputStream(invalidUtf)), "malformed UTF8");
        ByteArrayOutputStream duplicate = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(duplicate);
        data.writeByte(1); RootProtocol.writeString(data, "11111111111111111111111111111111"); RootProtocol.writeString(data, binding().packageName); data.writeInt(0); data.writeInt(UID);
        RootProtocol.writeString(data, binding().dataDir); RootProtocol.writeString(data, HASH); data.writeInt(2);
        for (int i = 0; i < 2; i++) { RootProtocol.writeString(data, APK); RootProtocol.writeString(data, HASH); }
        bad(() -> RootProtocol.readRequest(new ByteArrayInputStream(frame(duplicate.toByteArray()))), "duplicate APK fields");
        byte[] archive = "bounded synthetic archive".getBytes(StandardCharsets.UTF_8);
        RootProtocol.Response response = new RootProtocol.Response(RootProtocol.Status.OK, binding().fingerprint(), "EMPTY_DIRECTORY", 0, 0,
                SaveArchive.fingerprint(Collections.emptyMap()), "ABSENT", true, archive);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); RootProtocol.writeResponse(out, response);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RootProtocol.Response read = RootProtocol.readResponse(new ByteArrayInputStream(out.toByteArray()), sink);
        check(read.archiveLength == archive.length && Arrays.equals(archive, sink.toByteArray()), "stream archive sink");
        bad(() -> RootProtocol.readResponse(new ByteArrayInputStream(out.toByteArray()), null), "missing archive sink");
        byte[] truncated = Arrays.copyOf(out.toByteArray(), out.size()-1);
        bad(() -> RootProtocol.readResponse(new ByteArrayInputStream(truncated), new ByteArrayOutputStream()), "truncated response");
        bad(() -> RootProtocol.readResponse(new ByteArrayInputStream(Arrays.copyOf(out.toByteArray(),out.size()+1)), new ByteArrayOutputStream()), "response trailing data");
        RootProtocol.Response failure = RootProtocol.Response.failure(RootProtocol.Status.UNSUPPORTED, HASH);
        out.reset(); RootProtocol.writeResponse(out, failure);
        check(RootProtocol.readResponse(new ByteArrayInputStream(out.toByteArray()), null).status == RootProtocol.Status.UNSUPPORTED, "failure roundtrip");
        bad(() -> RootProtocol.writeResponse(new ByteArrayOutputStream(), new RootProtocol.Response(RootProtocol.Status.IO_ERROR, HASH, "", 0, 0, "", "UNKNOWN", false, archive)), "archive attached to failure");
    }
    private static RootUidState.Observation observe(int api, String text) { return RootUidState.parse(api, 0, false, text.getBytes(StandardCharsets.UTF_8)); }
    private static void lifecycle() {
        check(observe(26, "2 (TOP)\n").foreground(), "API26 foreground");
        check(observe(36, "2 (TOP)\n").foreground(), "API36 foreground");
        check(observe(26, "5 (TOP_SLEEPING)\n").foreground(), "API26 sleeping top");
        check(observe(36, "12 (TOP_SLEEPING)\n").foreground(), "API36 sleeping top shifted");
        check(observe(26, "17 (CACHED_EMPTY)\n").quiescent(), "API26 cached");
        check(observe(36, "19 (CACHED_EMPTY)\n").quiescent(), "API36 cached");
        check(observe(36, "19 (CACHED_EMPTY)\n").processPresent(), "cached process remains present");
        check(!observe(36, "19 (CACHED_EMPTY)\n").foreground(), "cached PID is not foreground");
        check(!observe(26, "18 (NONEXISTENT)\n").processPresent(), "API26 absent");
        check(observe(36, "20 (NONEXISTENT)\n").quiescent(), "API36 absent");
        check(!observe(26, "11 (SERVICE)\n").quiescent(), "service not quiescent");
        check(!observe(36, "17 (NONEXISTENT)\n").reliable, "unknown integer-name pair rejected");
        check(!observe(26, "2 (NONEXISTENT)\n").reliable, "mismatched number and symbolic name rejected");
        for (String text : new String[] {"", "20", "NONEXISTENT", "20 (UNKNOWN)", "20 (NONEXISTENT)\nextra", "Permission denied", "020 (NONEXISTENT)", "-1 (UNKNOWN)", "20 (NONEXISTENT)\u0000"})
            check(!observe(36, text).reliable, "unreliable UID-state text");
        for (int api = 26; api <= 36; api++) {
            check(observe(api, "20 (NONEXISTENT)\n").quiescent(), "known modern schema without API allowlist");
            check(observe(api, "2 (TOP)\n").foreground(), "known foreground schema without API allowlist");
        }
        check(observe(28, "19 (NONEXISTENT)\n").quiescent(), "API28 distinct absent value");
        check(observe(28, "18 (CACHED_EMPTY)\n").quiescent(), "API28 distinct cached value");
        check(observe(29, "21 (NONEXISTENT)\n").quiescent(), "API29 distinct absent value");
        check(observe(29, "20 (CACHED_EMPTY)\n").quiescent(), "API29 distinct cached value");
        check(observe(29, "13 (TOP_SLEEPING)\n").foreground(), "API29 sleeping top");
        check(!observe(25, "20 (NONEXISTENT)\n").reliable, "below platform minimum");
        check(!RootUidState.parse(36, 1, false, "20 (NONEXISTENT)\n".getBytes(StandardCharsets.UTF_8)).reliable, "nonzero exit");
        check(!RootUidState.parse(36, 0, true, "20 (NONEXISTENT)\n".getBytes(StandardCharsets.UTF_8)).reliable, "timeout");
        check(!RootUidState.parse(36, 0, false, new byte[4097]).reliable, "oversized command output");
        check(Arrays.equals(RootUidState.command(1010042), new String[]{"/system/bin/cmd", "activity", "get-uid-state", "1010042"}), "fixed numeric target argv");
    }
    private static final class Node {
        final long inode; int mode, uid = UID, gid = UID; long links = 1, revision, declared = -1;
        byte[] bytes; final Map<String, Node> children = new TreeMap<>();
        Node(long inode, int type, byte[] bytes) { this.inode = inode; this.mode = type | 0700; this.bytes = bytes; }
        RootFileSystem.Stat stat() { return new RootFileSystem.Stat(1, inode, mode, uid, gid, links,
                declared >= 0 ? declared : bytes == null ? 0 : bytes.length, 1, "1:" + revision); }
    }
    private static final class FakeFs implements RootFileSystem {
        final Node data = new Node(1, Stat.DIRECTORY, null), files = new Node(2, Stat.DIRECTORY, null);
        long next = 3; int operations, scans; boolean rootReplacement;
        String beforeOpenMutation, duringReadMutation; boolean grownRead;
        FakeFs() { data.children.put("files", files); }
        Node put(String path, int type, byte[] bytes) {
            Node parent = files; String[] parts = path.split("/");
            for (int i=0; i<parts.length-1; i++) {
                Node child = parent.children.get(parts[i]);
                if (child == null) { child = new Node(next++, Stat.DIRECTORY, null); parent.children.put(parts[i], child); }
                parent = child;
            }
            Node node = new Node(next++, type, bytes); parent.children.put(parts[parts.length-1], node); return node;
        }
        Node file(String path, String text) { return put(path, Stat.REGULAR, text.getBytes(StandardCharsets.UTF_8)); }
        private final class Ref implements Directory, RegularFile {
            final Node node; final String name; Ref(Node node, String name) { this.node=node; this.name=name; }
            public Stat stat() { operations++; return node.stat(); }
            public void close() { }
            public InputStream input() {
                operations++;
                byte[] bytes = node.bytes == null ? new byte[0] : node.bytes;
                if (grownRead) bytes = Arrays.copyOf(bytes, bytes.length + 1);
                if (name.equals(duringReadMutation)) node.revision++;
                return new ByteArrayInputStream(bytes);
            }
        }
        public Directory openData(RootBinding binding) { operations++; scans++; return new Ref(data, "<data>"); }
        public void revalidateRoot(RootBinding binding, Directory data, Directory files) throws IOException {
            operations++; if (rootReplacement) throw new RootFailure(RootProtocol.Status.CHANGED);
        }
        public Stat childStat(Directory parent, String name) throws IOException {
            operations++; Node child = ((Ref)parent).node.children.get(name);
            if (child == null) throw new RootFailure(RootProtocol.Status.MISSING_DIRECTORY); return child.stat();
        }
        private Ref open(Directory parent, String name) throws IOException {
            operations++; Node node = ((Ref)parent).node.children.get(name);
            if (node == null) throw new RootFailure(RootProtocol.Status.MISSING_DIRECTORY);
            if (name.equals(beforeOpenMutation)) node.revision++;
            return new Ref(node,name);
        }
        public Directory openDirectory(Directory parent, String name) throws IOException { return open(parent,name); }
        public RegularFile openFile(Directory parent, String name) throws IOException { return open(parent,name); }
        public List<String> children(Directory directory, int limit) throws IOException {
            operations++; List<String> names = new ArrayList<>(((Ref)directory).node.children.keySet());
            if (names.size() > limit) throw new RootFailure(RootProtocol.Status.TOO_LARGE); return names;
        }
        public SaveDirectoryPolicy.Content content(Directory directory) throws IOException {
            // Only metadata-only synthetic fixtures reach the real shared policy. Save bytes stay in memory.
            Path fixture = Files.createTempDirectory("pdsync-root-policy-");
            try {
                for (Map.Entry<String, Node> entry : ((Ref)directory).node.children.entrySet()) {
                    Path path = fixture.resolve(entry.getKey());
                    if (entry.getValue().stat().directory()) Files.createDirectory(path);
                    else if (entry.getValue().stat().regular()) Files.createFile(path);
                    else Files.createSymbolicLink(path, fixture.resolve("missing-target"));
                }
                return SaveDirectoryPolicy.content(fixture.toFile());
            } finally {
                try (java.util.stream.Stream<Path> stream = Files.walk(fixture)) {
                    for (Path path : stream.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
                }
            }
        }
    }
    private static RootSaveTree tree(FakeFs fs) throws IOException { return new RootSaveTree(fs,binding()); }
    private static void filesystem() throws Exception {
        FakeFs fs = new FakeFs(); fs.file("rankings.dat", "synthetic rankings"); fs.file("game1/game.dat", "synthetic run"); fs.file("game1/depth1.dat", "synthetic level");
        fs.file("profileInstalled", "runtime marker"); fs.file("profileinstaller_profileWrittenFor_lastUpdateTime.dat", "runtime marker"); fs.file("keybinds.dat", "user setting"); fs.file("settings.dat", "excluded");
        RootSaveTree.Snapshot snapshot = tree(fs).scan(true);
        check(snapshot.inventory.hashes.size() == 3, "complete save allowlist and marker exclusions");
        check(snapshot.same(tree(fs).scan(false)), "repeat stable snapshot");
        byte[] archive = tree(fs).archive(snapshot); Map<String,String> hashes = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) hashes.put(entry.getName(), SaveArchive.sha256(zip.readAllBytes()));
        }
        check(hashes.equals(snapshot.inventory.hashes), "archive exact inventory hashes");
        check(tree(new FakeFs()).archive(tree(new FakeFs()).scan(true)).length == 22, "API26 canonical empty ZIP");
        FakeFs excluded = new FakeFs(); excluded.file("profileInstalled","x");
        check(tree(excluded).scan(false).contentState.equals("NO_TRANSFERABLE_FILES"), "regular runtime marker excluded");
        for (String marker : new String[]{"profileInstalled","profileinstaller_profileWrittenFor_lastUpdateTime.dat","keybinds.dat"}) {
            for (int type : new int[]{RootFileSystem.Stat.DIRECTORY,RootFileSystem.Stat.SYMLINK}) {
                FakeFs wrongType = new FakeFs(); wrongType.put(marker,type,null);
                bad(() -> tree(wrongType).scan(false), "marker impostor not excluded");
            }
        }
        for (String name : new String[]{"unknown.dat","profileInstalled-copy","profileinstaller_profileWrittenFor_lastUpdateTime.dat.bak"}) {
            FakeFs unknown = new FakeFs(); unknown.file(name,"x"); bad(() -> tree(unknown).scan(false), "unknown layout");
        }
        FakeFs nested = new FakeFs(); nested.file("game1/game.dat","x"); nested.put("game1/linked",RootFileSystem.Stat.SYMLINK,null);
        bad(() -> tree(nested).scan(false), "nested symlink");
        FakeFs incomplete = new FakeFs(); incomplete.file("game1/depth1.dat","x");
        bad(() -> tree(incomplete).scan(false), "incomplete run");
        FakeFs names = new FakeFs(); names.file("game1/game.dat","x"); names.file("game1/GAME.dat","y");
        bad(() -> tree(names).scan(false), "case collision");
        FakeFs wrongOwner = new FakeFs(); wrongOwner.file("rankings.dat","x").uid = 0;
        bad(() -> tree(wrongOwner).scan(false), "wrong file owner");
        FakeFs rootOwner = new FakeFs(); rootOwner.data.uid = 0;
        bad(() -> tree(rootOwner).scan(false), "wrong data owner");
        FakeFs filesOwner = new FakeFs(); filesOwner.files.uid = 0;
        bad(() -> tree(filesOwner).scan(false), "wrong files owner");
        FakeFs hardlink = new FakeFs(); hardlink.file("rankings.dat","x").links = 2;
        bad(() -> tree(hardlink).scan(false), "hardlinked save");
        for (int mode : new int[]{0100702,0104720,0102720,0101720}) {
            FakeFs unsafe = new FakeFs(); unsafe.file("rankings.dat","x").mode=mode;
            bad(() -> tree(unsafe).scan(false), "unsafe write or privilege mode");
        }
        FakeFs group = new FakeFs(); Node node=group.file("rankings.dat","x"); node.mode=0100770; node.gid=9999;
        bad(() -> tree(group).scan(false), "foreign group write");
        FakeFs missing = new FakeFs(); missing.data.children.clear(); bad(() -> tree(missing).scan(false), "missing files");
    }
    private static void limitsAndRaces() throws Exception {
        FakeFs bytes = new FakeFs(); bytes.file("rankings.dat","12");
        bad(() -> new RootSaveTree(bytes,binding(),new RootSaveTree.Limits(1,4,8,4)).scan(true), "byte limit");
        FakeFs files = new FakeFs(); files.file("rankings.dat","1"); files.file("badges.dat","2");
        bad(() -> new RootSaveTree(files,binding(),new RootSaveTree.Limits(99,1,8,4)).scan(false), "file limit");
        bad(() -> new RootSaveTree(files,binding(),new RootSaveTree.Limits(99,4,1,4)).scan(false), "entry limit");
        FakeFs deep = new FakeFs(); deep.file("game1/game.dat","1"); deep.file("game1/d/depth.dat","2");
        bad(() -> new RootSaveTree(deep,binding(),new RootSaveTree.Limits(99,4,8,2)).scan(false), "depth limit");
        FakeFs hugeDeclared = new FakeFs(); hugeDeclared.file("rankings.dat","x").declared=Long.MAX_VALUE;
        bad(() -> tree(hugeDeclared).scan(true), "huge file stat before allocation");
        for (String race : new String[]{"open","read","grown","root"}) {
            FakeFs fs = new FakeFs(); fs.file("rankings.dat","x");
            if (race.equals("open")) fs.beforeOpenMutation="rankings.dat";
            if (race.equals("read")) fs.duringReadMutation="rankings.dat";
            if (race.equals("grown")) fs.grownRead=true;
            if (race.equals("root")) fs.rootReplacement=true;
            bad(() -> tree(fs).scan(true), "race " + race);
        }
        FakeFs shrink = new FakeFs(); shrink.file("rankings.dat","x").declared=2;
        bad(() -> tree(shrink).scan(true), "short read");
    }
    private static final class FakeRuntime implements RootRuntime {
        final FakeFs fs = new FakeFs(); int euid, pmCalls, observeCalls;
        RootBinding installed; boolean changeBinding, mutateQuiet, unreliable, foreground, mutateAfterExport, pending, guardChanged;
        int guardOpens, guardChecks, guardRevalidates, guardCloses;
        IOException guardFailure;
        FakeRuntime() throws IOException { installed = binding(); fs.file("rankings.dat","synthetic"); }
        public int effectiveUid() { return euid; }
        public RootBinding installed(RootBinding request) throws IOException {
            pmCalls++; return changeBinding && pmCalls > 1 ? binding("com.example.game",0,UID,request.dataDir,repeat('b',64)) : installed;
        }
        public ReadGuard openReadGuard(RootBinding binding) throws IOException {
            guardOpens++;if(guardFailure!=null)throw guardFailure;
            return new ReadGuard() {
                public void requireClear()throws IOException {guardChecks++;if(pending)throw new RootFailure(RootProtocol.Status.RECOVERY_REQUIRED);}
                public void revalidate()throws IOException {guardRevalidates++;if(guardChanged)throw new RootFailure(RootProtocol.Status.CHANGED);}
                public void close(){guardCloses++;}
            };
        }
        public RootFileSystem fileSystem() { return fs; }
        public RootUidState.Observation observe(int uid) {
            observeCalls++;
            if (mutateAfterExport && observeCalls == 3) fs.rootReplacement=true;
            return unreliable ? RootUidState.Observation.unknown() : RootHostTests.observe(26,foreground ? "2 (TOP)\n" : "17 (CACHED_EMPTY)\n");
        }
        public void quietWait() { if (mutateQuiet) fs.files.children.get("rankings.dat").bytes="different".getBytes(StandardCharsets.UTF_8); }
    }
    private static void service() throws Exception {
        FakeRuntime nonRoot=new FakeRuntime(); nonRoot.euid=UID;
        check(new RootService(nonRoot).execute(request(RootProtocol.Command.EXPORT)).status==RootProtocol.Status.NOT_ROOT,"actual euid gate");
        check(nonRoot.pmCalls==0 && nonRoot.fs.operations==0 && nonRoot.observeCalls==0,"no target touches before root gate");
        for (RootProtocol.Command command : new RootProtocol.Command[]{RootProtocol.Command.RESTORE,RootProtocol.Command.RECOVER}) {
            FakeRuntime runtime=new FakeRuntime();
            check(new RootService(runtime).execute(request(command)).status==RootProtocol.Status.UNSUPPORTED,"write commands unsupported");
            check(runtime.fs.operations==0 && runtime.pmCalls==0,"unsupported never touches target");
        }
        for (RootProtocol.Command command : new RootProtocol.Command[]{RootProtocol.Command.PROBE,RootProtocol.Command.INSPECT,RootProtocol.Command.EXPORT,RootProtocol.Command.OBSERVE}) {
            FakeRuntime runtime=new FakeRuntime(); RootProtocol.Request requested=request(command);
            RootProtocol.Response response=new RootService(runtime).execute(requested);
            RootProtocol.requireReplyMatches(requested,response);
            check(response.outcome==RootProtocol.Outcome.READ_ONLY,"typed read-only outcome");
            check(runtime.guardOpens==(command==RootProtocol.Command.PROBE?0:1),"read interlock opened only for content/lifecycle reads");
            check(runtime.guardCloses==runtime.guardOpens,"read guard closed on completion");
            check(response.status==RootProtocol.Status.OK,"read command success " + command);
            check(runtime.pmCalls==2,"PM binding checked before and after " + command);
            check(response.bindingFingerprint.equals(binding().fingerprint()),"response identity echo");
            check(command==RootProtocol.Command.EXPORT ? response.archiveLength>0 : response.archiveLength==0,"archive command bounded");
        }
        for(io.github.xyz1001.pdsync.root.mutation.MutationOs.Operation operation:io.github.xyz1001.pdsync.root.mutation.MutationOs.Operation.values()) {
            FakeRuntime kernel=new FakeRuntime();
            kernel.guardFailure=new io.github.xyz1001.pdsync.root.mutation.MutationOs.Failure(
                    io.github.xyz1001.pdsync.root.mutation.MutationOs.Error.IO,operation,22,null);
            RootProtocol.Request query=request(RootProtocol.Command.INSPECT);
            RootProtocol.Response error=new RootService(kernel).execute(query);
            check(error.status==RootProtocol.Status.IO_ERROR&&error.errno==22,"bounded kernel errno retained "+operation);
            check(error.diagnosticStage==RootService.kernelStage(operation,RootProtocol.DiagnosticStage.PRIVATE_WORKSPACE),"exact kernel operation "+operation);
            ByteArrayOutputStream output=new ByteArrayOutputStream();RootProtocol.writeResponse(output,error);
            RootProtocol.Response decoded=RootProtocol.readResponse(new ByteArrayInputStream(output.toByteArray()),null);
            RootProtocol.requireReplyMatches(query,decoded);
            check(decoded.diagnosticStage==error.diagnosticStage&&decoded.errno==22,"kernel diagnostic wire roundtrip "+operation);
            check(kernel.fs.operations==0,"failing private workspace never reads saves "+operation);
        }
        FakeRuntime changed=new FakeRuntime(); changed.changeBinding=true;
        check(new RootService(changed).execute(request(RootProtocol.Command.EXPORT)).status==RootProtocol.Status.BINDING_MISMATCH,"PM changes reject export");
        FakeRuntime mismatch=new FakeRuntime(); mismatch.installed=binding("com.example.game",0,UID,binding().dataDir,repeat('b',64));
        check(new RootService(mismatch).execute(request(RootProtocol.Command.INSPECT)).status==RootProtocol.Status.BINDING_MISMATCH,"wrong initial APK");
        check(mismatch.fs.operations==0,"wrong APK never touches saves");
        FakeRuntime mutate=new FakeRuntime(); mutate.mutateQuiet=true;
        check(new RootService(mutate).execute(request(RootProtocol.Command.EXPORT)).status==RootProtocol.Status.CHANGED,"quiet interval mutation");
        FakeRuntime unavailable=new FakeRuntime(); unavailable.unreliable=true;
        check(new RootService(unavailable).execute(request(RootProtocol.Command.EXPORT)).status==RootProtocol.Status.UNRELIABLE,"unknown lifecycle blocks export");
        check(unavailable.fs.operations==0,"unknown lifecycle reads no saves");
        FakeRuntime active=new FakeRuntime(); active.foreground=true;
        check(new RootService(active).execute(request(RootProtocol.Command.EXPORT)).status==RootProtocol.Status.BUSY,"foreground blocks export");
        check(active.fs.operations==0,"foreground reads no saves");
        FakeRuntime lateRoot=new FakeRuntime(); lateRoot.mutateAfterExport=true;
        check(new RootService(lateRoot).execute(request(RootProtocol.Command.EXPORT)).status==RootProtocol.Status.CHANGED,"late root replacement after final lifecycle call");
        FakeRuntime pending=new FakeRuntime();pending.pending=true;
        check(new RootService(pending).execute(request(RootProtocol.Command.EXPORT)).status==RootProtocol.Status.RECOVERY_REQUIRED,"pending root transaction blocks export");
        check(pending.guardCloses==1&&pending.fs.operations==0,"pending read closes guard without touching saves");
        RootProtocol.Response probePending=new RootService(pending).execute(request(RootProtocol.Command.PROBE));
        check(probePending.status==RootProtocol.Status.OK,"exact-target probe remains possible while recovery pending");
        FakeRuntime changedGuard=new FakeRuntime();changedGuard.guardChanged=true;
        check(new RootService(changedGuard).execute(request(RootProtocol.Command.EXPORT)).status==RootProtocol.Status.CHANGED,"late workspace state invalidates read");
        check(changedGuard.guardCloses==1,"changed guard closed");
        RootProtocol.Request requested=request(RootProtocol.Command.INSPECT);
        RootProtocol.RequestFrame malformed=RootProtocol.readRequestFrame(new ByteArrayInputStream(Arrays.copyOf(wire(requested),wire(requested).length+1)));
        FakeRuntime untouched=new FakeRuntime();RootProtocol.Response refused=new RootService(untouched).execute(malformed);
        check(refused.status==RootProtocol.Status.INVALID_REQUEST&&untouched.pmCalls==0,"trailing read frame rejected before PM or saves");
        RootProtocol.requireReplyMatches(requested,refused);
        check(refused.diagnosticStage==RootProtocol.DiagnosticStage.ARCHIVE_READ&&refused.diagnosticCategory==RootProtocol.DiagnosticCategory.MALFORMED,"framing failure safe diagnostics");
        FakeRuntime inspect=new FakeRuntime(); inspect.unreliable=true;
        RootProtocol.Response result=new RootService(inspect).execute(request(RootProtocol.Command.INSPECT));
        check(result.status==RootProtocol.Status.OK && !result.reliable,"inspection does not fabricate lifecycle verification");
    }
}
