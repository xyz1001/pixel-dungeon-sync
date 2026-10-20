package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** Bounded, fail-closed archive codec shared by the production bridge and host tests. */
public final class SaveArchiveCodec {
    public static final long MAX_BYTES = 67108864;
    private static final int MAX_FILES = 4096;
    private SaveArchiveCodec() { }

    public static final class Inventory {
        public final Map<String,String> hashes;
        public final long bytes;
        Inventory(Map<String,String> hashes,long bytes) { this.hashes=Collections.unmodifiableMap(new TreeMap<>(hashes)); this.bytes=bytes; }
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException e) { throw new AssertionError(e); } }
    private static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(64); for(byte b:bytes) { s.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16)); } return s.toString(); }
    public static void validateName(String name) throws IOException {
        if (name==null || name.isEmpty() || name.length()>1024 || name.startsWith("/") || name.contains("\\") || name.contains(":")) throw new IOException(AndroidMessages.BRIDGE_CLIENT_35.text());
        String[] segments=name.split("/",-1);
        if(segments.length>16) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_01.text());
        for(String segment:segments) {
            if(segment.length()>255 || !segment.matches("[A-Za-z0-9_.-]+") || segment.equals(".") || segment.equals("..") || segment.endsWith(".") || segment.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) throw new IOException(AndroidMessages.BRIDGE_CLIENT_35.text());
        }
        if(!name.equals("rankings.dat") && !name.equals("badges.dat") && !name.equals("journal.dat") && !name.matches("game[0-9]+/.+")) throw new IOException(AndroidMessages.BRIDGE_CLIENT_36.text());
    }
    private static void requireCompleteRuns(Set<String> names) throws IOException {
        for(String name:names) if(name.startsWith("game") && !names.contains(name.split("/",2)[0]+"/game.dat")) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_02.text());
    }
    public static void writeRestoreArchive(Path root,File archive) throws IOException {
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_03.text());
        Set<String> aliases=new HashSet<>(),names=new HashSet<>(); long total=0;
        try(OutputStream output=Files.newOutputStream(archive.toPath(),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            Stream<Path> walk=Files.walk(root)) {
            // Delay ZIP stream creation: Android 8 rejects finish() when there are no entries.
            ZipOutputStream zip=null;
            try {
                Iterator<Path> paths=walk.sorted().iterator();
                while(paths.hasNext()) {
                    Path file=paths.next(); if(file.equals(root)) continue;
                    BasicFileAttributes before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(before.isSymbolicLink()) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_04.text());
                    if(before.isDirectory()) continue;
                    if(!before.isRegularFile()) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_05.text());
                    String name=root.relativize(file).toString().replace(File.separatorChar,'/'); validateName(name);
                    if(!aliases.add(name.toLowerCase(Locale.ROOT)) || names.size()>=MAX_FILES) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_06.text());
                    names.add(name);
                    long millis=before.lastModifiedTime().toMillis();
                    if(millis<0 || millis/1000>2147483647L) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_07.text());
                    if(zip==null) zip=new ZipOutputStream(output);
                    ZipEntry entry=new ZipEntry(name); entry.setLastModifiedTime(FileTime.fromMillis(millis/1000*1000)); zip.putNextEntry(entry);
                    long size=0;
                    try(InputStream input=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer=new byte[8192]; int n;
                        while((n=input.read(buffer))!=-1) {
                            total+=n;size+=n;
                            if(total>MAX_BYTES || size>before.size()) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_08.text());
                            zip.write(buffer,0,n);
                        }
                    }
                    BasicFileAttributes after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(size!=before.size() || !after.isRegularFile() || !Objects.equals(before.fileKey(),after.fileKey()) || after.size()!=before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime())) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_09.text());
                    zip.closeEntry();
                }
                requireCompleteRuns(names);
                if(zip==null) {
                    // Standards-valid empty ZIP EOCD; no placeholder save files or directories.
                    byte[] empty=new byte[22];empty[0]=0x50;empty[1]=0x4b;empty[2]=0x05;empty[3]=0x06;
                    output.write(empty);
                }
            } finally { if(zip!=null) zip.close(); }
        }
        if(archive.length()>MAX_BYTES) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_10.text());
    }
    private static void ensureParents(Path root,Path parent) throws IOException {
        Path current=root.toAbsolutePath().normalize(),target=parent.toAbsolutePath().normalize();
        if(!target.startsWith(current) || !Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS)) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_11.text());
        for(Path part:current.relativize(target)) {
            current=current.resolve(part);
            try { Files.createDirectory(current); } catch(FileAlreadyExistsException ignored) { }
            if(!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS)) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_12.text());
        }
    }
    public static Inventory readExportArchive(InputStream archive,Path root) throws IOException {
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_13.text());
        try(Stream<Path> entries=Files.list(root)) { if(entries.findAny().isPresent()) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_14.text()); }
        Map<String,String> hashes=new TreeMap<>(); Set<String> aliases=new HashSet<>(); long total=0;
        try(ZipInputStream zip=new ZipInputStream(new FilterInputStream(archive) {
            long count;
            private void add(int n)throws IOException { if(n>0 && (count+=n)>MAX_BYTES) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_10.text()); }
            @Override public int read()throws IOException { int n=in.read(); if(n>=0)add(1);return n; }
            @Override public int read(byte[] b,int o,int n)throws IOException { int read=in.read(b,o,n);add(read);return read; }
        })) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName(); validateName(name);
                if(entry.isDirectory() || !aliases.add(name.toLowerCase(Locale.ROOT)) || hashes.size()>=MAX_FILES) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_15.text());
                Path target=root.resolve(name).normalize();
                if(!target.toAbsolutePath().startsWith(root.toAbsolutePath().normalize())) throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_16.text());
                ensureParents(root,target.getParent()); MessageDigest hash=digest();
                try(OutputStream output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer=new byte[8192]; int n;
                    while((n=zip.read(buffer))!=-1) { total+=n; if(total>MAX_BYTES)throw new IOException(AndroidMessages.SAVE_ARCHIVE_CODEC_17.text()); output.write(buffer,0,n);hash.update(buffer,0,n); }
                }
                zip.closeEntry(); // Validates entry CRC and size, including data-descriptor ZIPs.
                if(entry.getLastModifiedTime()!=null) Files.setLastModifiedTime(target,entry.getLastModifiedTime());
                hashes.put(name,hex(hash.digest()));
            }
        }
        requireCompleteRuns(hashes.keySet()); return new Inventory(hashes,total);
    }
}
