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
        denied(()->binding.validateInstalled(game,shared,shared,18,"v17",42,42,new String[]{game,helper},true),"version mismatch");
        denied(()->binding.validateInstalled(game,shared,shared,(1L<<32)|17,"v17",42,42,new String[]{game,helper},true),"modern major version mismatch");
        denied(()->binding.saveRoot(base.toString(),base.resolve("child").toString()),"nested private roots");
        Path gameData=dir(base,"canonical-game"),helperData=dir(base,"canonical-helper");
        Path dataAlias=base.resolve("android-data-alias"); Files.createSymbolicLink(dataAlias,gameData);
        check(binding.saveRoot(dataAlias.toString(),helperData.toString()).equals(gameData.resolve("files").toFile()),"Android data-directory alias canonicalized");
        denied(()->binding.saveRoot(dataAlias.toString(),gameData.toString()),"aliased game and helper data roots rejected");
        denied(()->binding.saveRoot("relative-game",helperData.toString()),"relative game data root rejected");
        for(int importance:new int[]{100,125,130,150,200,230,325})
            check(GameBinding.blocksTransfer(importance),"foreground/perceptible and API26/API36 top-sleeping blocked: "+importance);
        for(int importance:new int[]{300,350,400,1000})
            check(!GameBinding.blocksTransfer(importance),"background state still requires quiet hash observation: "+importance);
        denied(()->GameBinding.blocksTransfer(0),"missing process importance fails closed");
        denied(()->GameBinding.blocksTransfer(-1),"invalid process importance fails closed");
        MainCallerPolicy.requireIdentity(main,10,11,10,new String[]{main}); assertions++;
        denied(()->MainCallerPolicy.requireIdentity(main,11,11,11,new String[]{main}),"main shared UID refusal");
        denied(()->MainCallerPolicy.requireIdentity(main,10,11,10,new String[]{main,"evil.pkg"}),"main independent UID requirement");
        byte[] certificate="public-test-certificate".getBytes();
        MainCallerPolicy.requirePinnedSigner(SaveArchive.sha256(certificate),new byte[][]{certificate}); assertions++;
        denied(()->MainCallerPolicy.requirePinnedSigner("0".repeat(64),new byte[][]{certificate}),"wrong certificate pin");
        denied(()->MainCallerPolicy.requirePinnedSigner("",new byte[][]{certificate}),"missing certificate pin");
        denied(()->MainCallerPolicy.requirePinnedSigner(SaveArchive.sha256(certificate),new byte[][]{certificate,certificate}),"multiple signers rejected");
    }
    static void embeddedIdentityTests(Path base) throws Exception {
        String game="test.embedded.game", origin="5".repeat(64);
        Map<String,String> metadata=new HashMap<>();
        String[] values={"embedded-v1",game,origin,"android-"+origin,"6".repeat(64),"19","v19",MainCallerPolicy.MAIN_SIGNER_SHA256,"1"};
        for(int i=0;i<values.length;i++) metadata.put(GameBinding.PREFIX+GameBinding.EMBEDDED_METADATA_NAMES[i],values[i]);
        GameBinding binding=new GameBinding(metadata,game);
        check(binding.embedded && binding.helperPackage.equals(game) && binding.sharedUserId.isEmpty(),"embedded runtime has no helper or shared UID requirement");
        check(binding.authority.equals("io.github.xyz1001.pdsync.embedded.g"+origin.substring(0,32)+".files"),"embedded authority contract");
        check(binding.bridgeProcessName().equals(game+":pdsync_bridge"),"dedicated bridge process");
        binding.validateInstalled(game,null,null,19,"v19",42,42,new String[]{game},false); assertions++;
        denied(()->binding.validateInstalled(game,null,null,19,"v19",42,43,new String[]{game},true),"embedded UID mismatch");
        denied(()->binding.validateInstalled(game,null,null,19,"v19",42,42,new String[]{game,"evil.pkg"},true),"additional game UID package");
        denied(()->binding.validateInstalled(game,null,null,19,"v19",42,42,null,true),"unobservable game UID packages");
        denied(()->binding.validateInstalled(game,null,null,20,"v19",42,42,new String[]{game},true),"embedded wrong version");
        denied(()->binding.validateInstalled(game,null,null,19,"other",42,42,new String[]{game},true),"embedded wrong version name");
        check(binding.isHelperProcess(game+":pdsync_bridge",new String[]{game}),"own process recognized");
        check(!binding.isHelperProcess(game,new String[]{game}),"main game process cannot masquerade as bridge");
        check(!binding.isGameProcess(game+":pdsync_bridge",new String[]{game}),"duplicate bridge name is ambiguous");
        check(binding.isGameProcess(game,new String[]{game}),"main game process observed");
        check(binding.isGameProcess(game+":remote",new String[]{game}),"secondary game process observed");
        check(binding.isGameProcess("custom.named.process",new String[]{game}),"explicitly named game process observed by ownership");
        check(!binding.isGameProcess("custom.named.process",new String[]{"evil.pkg"}),"foreign package rejected");
        check(!binding.isGameProcess(game,new String[]{game,"evil.pkg"}),"ambiguous package process rejected");
        Path own=dir(base,"embedded-game-data"),foreign=dir(base,"embedded-foreign-data");
        Path alias=base.resolve("embedded-data-alias"); Files.createSymbolicLink(alias,own);
        check(binding.saveRoot(alias.toString(),own.toString()).equals(own.resolve("files").toFile()),"canonical own files root");
        denied(()->binding.saveRoot(own.toString(),foreign.toString()),"embedded foreign data directory rejected");
        denied(()->binding.saveRoot("relative",own.toString()),"embedded relative data directory rejected");
        denied(()->new GameBinding(metadata,"test.different.game"),"embedded context package must match binding");
        for(String key:GameBinding.EMBEDDED_METADATA_NAMES) {
            Map<String,String> missing=new HashMap<>(metadata); missing.remove(GameBinding.PREFIX+key);
            denied(()->new GameBinding(missing,game),"required embedded metadata "+key);
        }
        for(String[] changed:new String[][]{{"BRIDGE_MODE","embedded-v2"},{"BRIDGE_RUNTIME_VERSION","2"},
                {"MAIN_APP_CERT_SHA256","0".repeat(64)},{"BOUND_GAME_PACKAGE",MainCallerPolicy.MAIN},
                {"BOUND_NAMESPACE_ID","android-other"},{"BOUND_ORIGIN_IDENTITY","bad"},
                {"BOUND_SOURCE_APK_SHA256","bad"},{"BOUND_GAME_VERSION_CODE","9223372036854775808"}}) {
            Map<String,String> invalid=new HashMap<>(metadata); invalid.put(GameBinding.PREFIX+changed[0],changed[1]);
            denied(()->new GameBinding(invalid,game),"invalid embedded metadata "+changed[0]);
        }
        MainCallerPolicy.requireIdentity(MainCallerPolicy.MAIN,10,42,10,new String[]{MainCallerPolicy.MAIN}); assertions++;
        denied(()->MainCallerPolicy.requireIdentity(MainCallerPolicy.MAIN,42,42,42,new String[]{MainCallerPolicy.MAIN}),"own game UID cannot call bridge");
        denied(()->MainCallerPolicy.requireIdentity("evil.pkg",10,42,10,new String[]{MainCallerPolicy.MAIN}),"claimed package cannot override Binder identity");
        denied(()->MainCallerPolicy.requireIdentity(MainCallerPolicy.MAIN,10,42,11,new String[]{MainCallerPolicy.MAIN}),"installed main UID must equal Binder UID");
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
        RestoreWorkspace.Handle linked=workspace.create();
        Path outside=dir(base,"outside-cleanup"); Files.writeString(outside.resolve("keep.dat"),"keep");
        Files.createSymbolicLink(linked.path.resolve("linked-directory"),outside);
        linked.cleanup();
        check(Files.readString(outside.resolve("keep.dat")).equals("keep"),"secure cleanup never follows linked directories");
        check(!Files.exists(linked.path)&&!workspace.recorded(),"linked entry removed without following it");
        RestoreWorkspace.Handle swapped=workspace.create();
        Path moved=parent.resolve("retained-original"); Files.move(swapped.path,moved); Files.createDirectory(swapped.path);
        denied(swapped::cleanup,"inode substituted workspace");
        check(Files.exists(swapped.path)&&Files.exists(moved),"substituted and original directories retained after refusal");
        Path otherArea=dir(base,"helper-other-mount-test");
        RestoreWorkspace.Access crossDevice=new RestoreWorkspace.Access(){
            public RestoreWorkspace.Stamp stat(Path path) throws IOException {
                RestoreWorkspace.Stamp stamp=access.stat(path);
                if(path.getFileName().toString().startsWith(".pdsync-restore-"))
                    return new RestoreWorkspace.Stamp(stamp.dev+1,stamp.ino,stamp.type,stamp.uid,stamp.links,stamp.size,stamp.key);
                return stamp;
            }
            public void sync(Path path) { }
            public void validateBinding() { }
        };
        RestoreWorkspace crossMount=new RestoreWorkspace(parent.toFile(),otherArea.toFile(),"4".repeat(64),uid,crossDevice);
        denied(crossMount::create,"restore staging on a different device rejected");
        check(!crossMount.recorded(),"cross-device staging never authorized for mutation");
    }
    public static void main(String[] args) throws Exception {
        Path base=Files.createTempDirectory("pdsync-helper-host-");
        archiveTests(base); policyTests(base); identityTests(base); embeddedIdentityTests(base); workspaceTests(base);
        System.out.println("PASS: "+assertions+" helper host security assertions; no Android runtime executed.");
    }
}
