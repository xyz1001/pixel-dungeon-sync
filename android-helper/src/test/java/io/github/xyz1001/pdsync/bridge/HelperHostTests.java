package io.github.xyz1001.pdsync.bridge;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.zip.*;

/** Dependency-free tests for the production helper's portable security boundaries. */
public final class HelperHostTests {
    private static int assertions;
    interface Checked { void run() throws Exception; }
    static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    static void denied(Checked action, String message) throws Exception {
        assertions++;
        try { action.run(); } catch (IOException | SecurityException expected) { return; }
        throw new AssertionError("Expected refusal: " + message);
    }
    static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (String name : names) { z.putNextEntry(new ZipEntry(name)); z.write("fixture".getBytes()); z.closeEntry(); }
        }
        return out.toByteArray();
    }
    static Path dir(Path base, String name) throws IOException { return Files.createDirectory(base.resolve(name)); }
    static void archiveTests(Path base) throws Exception {
        for (String n : List.of("rankings.dat", "badges.dat", "journal.dat", "game1/game.dat", "game10/levels/level1.dat")) check(SaveArchive.safeName(n), n);
        for (String n : List.of("../rankings.dat", "game1/../x", "game1/CON", "game1/foo.", "game1/a\\b", "game1//game.dat", "/rankings.dat", "settings.dat")) check(!SaveArchive.safeName(n), "reject " + n);
        Path extract = dir(base,"extract");
        SaveArchive.Inventory inventory = SaveArchive.extract(new ByteArrayInputStream(zip("game1/game.dat", "game1/level1.dat", "rankings.dat")), extract.toFile());
        check(inventory.hashes.size()==3 && inventory.bytes==21, "ZIP inventory");
        ByteArrayOutputStream roundtrip = new ByteArrayOutputStream(); SaveArchive.write(extract.toFile(), inventory, roundtrip);
        SaveArchive.Inventory restored = SaveArchive.extract(new ByteArrayInputStream(roundtrip.toByteArray()),dir(base,"roundtrip").toFile());
        check(restored.fingerprint().equals(inventory.fingerprint()), "roundtrip fingerprint");
        check(inventory.fingerprint().matches("[0-9a-f]{64}"), "fingerprint format");
        denied(() -> SaveArchive.extract(new ByteArrayInputStream(zip("game1/../evil")),dir(base,"traversal").toFile()),"traversal");
        denied(() -> SaveArchive.extract(new ByteArrayInputStream(zip("game1/level1.dat")),dir(base,"incomplete").toFile()),"incomplete run");
        denied(() -> SaveArchive.extract(new ByteArrayInputStream(zip("game1/game.dat","game1/A.dat","game1/a.dat")),dir(base,"alias").toFile()),"case collision");
        Path linkRoot=dir(base,"linkroot"); Files.createSymbolicLink(linkRoot.resolve("game1"),extract.resolve("game1"));
        denied(() -> SaveArchive.extract(new ByteArrayInputStream(zip("game1/game.dat")),linkRoot.toFile()),"linked staging parent");
        Files.writeString(extract.resolve("rankings.dat"),"mutated");
        denied(() -> SaveArchive.write(extract.toFile(),inventory,new ByteArrayOutputStream()),"staging tampering");
        denied(() -> SaveArchive.fingerprint(Map.of("../bad","0".repeat(64))),"unsafe manifest path");
        denied(() -> SaveArchive.fingerprint(Map.of("rankings.dat","bad")),"invalid digest");
        Path empty=dir(base,"emptyzip");
        check(SaveArchive.extract(new ByteArrayInputStream(zip()),empty.toFile()).hashes.isEmpty(),"empty ZIP allowed");
    }
    static void policyTests(Path base) throws Exception {
        check(SaveDirectoryPolicy.content(base.resolve("missing").toFile()).state==SaveDirectoryPolicy.ContentState.MISSING_DIRECTORY,"missing save root");
        Path root=dir(base,"policy");
        check(SaveDirectoryPolicy.content(root.toFile()).state==SaveDirectoryPolicy.ContentState.EMPTY_DIRECTORY,"empty save root");
        Files.writeString(root.resolve("profileInstalled"),"runtime marker");
        check(SaveDirectoryPolicy.content(root.toFile()).state==SaveDirectoryPolicy.ContentState.NO_TRANSFERABLE_FILES,"profile marker excluded");
        Files.writeString(root.resolve("rankings.dat"),"rankings");
        check(SaveDirectoryPolicy.content(root.toFile()).state==SaveDirectoryPolicy.ContentState.ALLOWLISTED_FILES_PRESENT,"valid included entry");
        Files.writeString(root.resolve("unknown.dat"),"unknown");
        denied(()->SaveDirectoryPolicy.content(root.toFile()).requireTransferable(),"unknown layout not empty");
        Files.delete(root.resolve("unknown.dat")); Files.delete(root.resolve("rankings.dat"));
        Files.createSymbolicLink(root.resolve("rankings.dat"),root.resolve("profileInstalled"));
        denied(()->SaveDirectoryPolicy.content(root.toFile()),"linked included entry");
    }
    static void identityTests(Path base) throws Exception {
        String origin="1".repeat(64), main="io.github.xyz1001.pdsync.android";
        String game="test.pixel.game", helper="io.github.xyz1001.pdsync.bridge.g"+origin.substring(0,32), shared="io.github.xyz1001.pdsync.shared.g"+origin.substring(0,32);
        Map<String,String> metadata=new HashMap<>();
        String[] values={game,shared,origin,"android-"+origin,"2".repeat(64),"17","v17"};
        for(int i=0;i<values.length;i++) metadata.put(GameBinding.PREFIX+GameBinding.METADATA_NAMES[i],values[i]);
        GameBinding binding=new GameBinding(metadata,helper);
        binding.validateInstalled(game,shared,shared,17,"v17",42,42,new String[]{game,helper},true); assertions++;
        check(binding.isGameProcess(game,new String[]{game}),"main game process");
        check(binding.isGameProcess(game+":worker",new String[]{game}),"game worker process");
        check(!binding.isGameProcess(game+"fake",new String[]{game}),"prefix spoof");
        check(!binding.isGameProcess(game,new String[]{game,helper}),"ambiguous shared UID process");
        denied(()->binding.validateInstalled(game,shared,shared,17,"v17",42,42,new String[]{game,helper,"evil.pkg"},true),"third shared UID member");
        denied(()->binding.validateInstalled(game,shared,shared,17,"v17",42,43,new String[]{game,helper},true),"UID mismatch");
        denied(()->binding.validateInstalled(game,shared,shared,17,"v17",42,42,new String[]{game,helper},false),"signer mismatch");
        denied(()->binding.saveRoot(base.toString(),base.resolve("child").toString()),"nested private roots");
        MainCallerPolicy.requireIdentity(main,10,11,10,new String[]{main}); assertions++;
        denied(()->MainCallerPolicy.requireIdentity(main,11,11,11,new String[]{main}),"main shared UID refusal");
        denied(()->MainCallerPolicy.requireIdentity(main,10,11,10,new String[]{main,"evil.pkg"}),"main independent UID requirement");
        byte[] certificate="public-test-certificate".getBytes();
        MainCallerPolicy.requirePinnedSigner(SaveArchive.sha256(certificate),new byte[][]{certificate}); assertions++;
        denied(()->MainCallerPolicy.requirePinnedSigner("0".repeat(64),new byte[][]{certificate}),"wrong certificate pin");
    }
    static void workspaceTests(Path base) throws Exception {
        Path parent=dir(base,"game-data"),area=dir(base,"helper-data");
        long uid=((Number)Files.getAttribute(parent,"unix:uid")).longValue();
        RestoreWorkspace.Access access=new RestoreWorkspace.Access(){
            public RestoreWorkspace.Stamp stat(Path path) throws IOException {
                Map<String,Object> attrs=Files.readAttributes(path,"unix:dev,ino,mode,uid,nlink,size",LinkOption.NOFOLLOW_LINKS);
                String key=RestoreWorkspace.key(Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS));
                return new RestoreWorkspace.Stamp(((Number)attrs.get("dev")).longValue(),((Number)attrs.get("ino")).longValue(),((Number)attrs.get("mode")).intValue()&0170000,((Number)attrs.get("uid")).longValue(),((Number)attrs.get("nlink")).longValue(),((Number)attrs.get("size")).longValue(),key);
            }
            public void sync(Path path) { }
            public void validateBinding() { }
        };
        RestoreWorkspace workspace=new RestoreWorkspace(parent.toFile(),area.toFile(),"3".repeat(64),uid,access);
        RestoreWorkspace.Handle handle=workspace.create();
        check(handle.path.getParent().equals(parent),"staging is a sibling on game data mount");
        check(workspace.recorded(),"durable identity receipt");
        Path child=Files.createDirectory(handle.path.resolve("incoming")); Files.writeString(child.resolve("rankings.dat"),"data");
        handle.cleanup(); check(!Files.exists(handle.path)&&!workspace.recorded(),"exact workspace cleanup");
        RestoreWorkspace.Handle swapped=workspace.create();
        Path moved=parent.resolve("retained-original"); Files.move(swapped.path,moved); Files.createDirectory(swapped.path);
        denied(swapped::cleanup,"inode substituted workspace");
        check(Files.exists(swapped.path)&&Files.exists(moved),"substituted and original directories retained after refusal");
    }
    public static void main(String[] args) throws Exception {
        Path base=Files.createTempDirectory("pdsync-helper-host-");
        archiveTests(base); policyTests(base); identityTests(base); workspaceTests(base);
        System.out.println("PASS: "+assertions+" helper host security assertions; no Android runtime executed.");
    }
}
