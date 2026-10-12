package io.github.xyz1001.pdsync.bridge;

import io.github.xyz1001.pdsync.android.SaveArchiveCodec;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.*;

public final class ArchiveInteropTests {
    static int count;
    interface Checked {void run() throws Exception;}
    static void check(boolean yes,String message) {count++;if(!yes)throw new AssertionError(message);}
    static void denied(Checked work,String message)throws Exception {count++;try{work.run();}catch(IOException expected){return;}throw new AssertionError("Expected rejection: "+message);}
    static Path dir(Path root,String name)throws IOException{return Files.createDirectory(root.resolve(name));}
    static byte[] zip(String... names)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream out=new ZipOutputStream(bytes)){for(String n:names){out.putNextEntry(new ZipEntry(n));out.write(n.getBytes());out.closeEntry();}}return bytes.toByteArray();}
    public static void main(String[] args)throws Exception{
        Path base=Files.createTempDirectory("pdsync-archive-interop-"),local=dir(base,"local"),game=dir(local,"game1");
        Files.write(game.resolve("game.dat"),new byte[]{0,1,2,3}); Files.writeString(game.resolve("level1.dat"),"level-fixture"); Files.writeString(local.resolve("rankings.dat"),"global-fixture");
        File archive=base.resolve("restore.zip").toFile(); SaveArchiveCodec.writeRestoreArchive(local,archive);
        Path helper=dir(base,"helper");SaveArchive.Inventory extracted;
        try(InputStream in=Files.newInputStream(archive.toPath())){extracted=SaveArchive.extract(in,helper.toFile());}
        check(extracted.hashes.size()==3,"main writer -> helper reader");
        ByteArrayOutputStream exported=new ByteArrayOutputStream();SaveArchive.write(helper.toFile(),extracted,exported);
        SaveArchiveCodec.Inventory back=SaveArchiveCodec.readExportArchive(new ByteArrayInputStream(exported.toByteArray()),dir(base,"back"));
        check(back.hashes.equals(extracted.hashes),"helper writer -> main reader hashes");
        check(back.bytes==extracted.bytes,"roundtrip bytes");
        check(SaveArchive.fingerprint(back.hashes).equals(extracted.fingerprint()),"cross-protocol canonical fingerprint");
        check(Arrays.equals(Files.readAllBytes(base.resolve("back/game1/game.dat")),Files.readAllBytes(game.resolve("game.dat"))),"binary save preserved");
        check(Files.getLastModifiedTime(base.resolve("back/game1/game.dat")).toMillis()/1000==Files.getLastModifiedTime(game.resolve("game.dat")).toMillis()/1000,"mtime second preserved");
        for(String name:List.of("game1/../escape","game1/NUL","game1/a.","game1/a\\b","settings.dat","game1/")){
            denied(()->SaveArchiveCodec.validateName(name),"unsafe name "+name);
        }
        denied(()->SaveArchiveCodec.readExportArchive(new ByteArrayInputStream(zip("game1/game.dat","game1/A.dat","game1/a.dat")),dir(base,"aliases")),"case collision");
        denied(()->SaveArchiveCodec.readExportArchive(new ByteArrayInputStream(zip("game1/level.dat")),dir(base,"incomplete")),"incomplete export");
        Path nonempty=dir(base,"nonempty");Files.writeString(nonempty.resolve("existing"),"keep");
        denied(()->SaveArchiveCodec.readExportArchive(new ByteArrayInputStream(zip()),nonempty),"nonempty target");
        check(Files.readString(nonempty.resolve("existing")).equals("keep"),"nonempty target retained");
        Path linked=dir(base,"linked");Files.createSymbolicLink(linked.resolve("rankings.dat"),local.resolve("rankings.dat"));
        denied(()->SaveArchiveCodec.writeRestoreArchive(linked,base.resolve("link.zip").toFile()),"linked input");
        Path future=dir(base,"future");Files.writeString(future.resolve("rankings.dat"),"future");Files.setLastModifiedTime(future.resolve("rankings.dat"),FileTime.fromMillis(2147483648000L));
        denied(()->SaveArchiveCodec.writeRestoreArchive(future,base.resolve("future.zip").toFile()),"unsafe ZIP timestamp");
        Path empty=dir(base,"empty");File emptyZip=base.resolve("empty.zip").toFile();SaveArchiveCodec.writeRestoreArchive(empty,emptyZip);
        try(InputStream input=Files.newInputStream(emptyZip.toPath())){check(SaveArchive.extract(input,dir(base,"helperempty").toFile()).hashes.isEmpty(),"empty save set roundtrip");}
        System.out.println("PASS: "+count+" main/helper archive interoperability assertions; real production codec classes.");
    }
}
