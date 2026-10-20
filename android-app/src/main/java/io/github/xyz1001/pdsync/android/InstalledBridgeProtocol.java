package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded historical runtime proof before any IPC into an unmarked provider. */
final class InstalledBridgeProtocol {
    static final String CODE45_RUNTIME = "92aa07a4ebc64ccc9f78d3dae160afe191a342e7596c5e4b26cf56eb3699893d";
    static final long MAX_RUNTIME_BYTES = 16L * 1024 * 1024;
    private InstalledBridgeProtocol() { }

    static String requireKnownRuntime(File baseApk, String currentRuntimeHash) throws Exception {
        BasicFileAttributes before = Files.readAttributes(baseApk.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink()) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_01.text());
        String candidate = null;
        try (ZipFile zip = new ZipFile(baseApk)) {
            Set<String> entries = new HashSet<>(); Set<Integer> dex = new HashSet<>();
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement(); String name = entry.getName();
                if (!entries.add(name) || entries.size() > 65536) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_02.text());
                if ("classes.dex".equals(name)) dex.add(1);
                else if (name.matches("classes[2-9][0-9]*\\.dex") || name.matches("classes1[0-9]+\\.dex")) {
                    int index;
                    try { index = Integer.parseInt(name.substring(7, name.length()-4)); }
                    catch (NumberFormatException bad) { throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_03.text(), bad); }
                    if (index > 64) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_04.text());
                    dex.add(index);
                }
            }
            if (dex.isEmpty()) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_05.text());
            for (int i=1; i<=dex.size(); i++) if (!dex.contains(i)) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_06.text());
            // Our injection appends exactly one independent runtime to the base APK.
            String name = dex.size()==1 ? "classes.dex" : "classes"+dex.size()+".dex";
            ZipEntry runtime = zip.getEntry(name);
            if (runtime == null || runtime.isDirectory() || runtime.getSize() <= 0 || runtime.getSize() > MAX_RUNTIME_BYTES
                    || runtime.getCrc() < 0 || (runtime.getMethod()!=ZipEntry.STORED && runtime.getMethod()!=ZipEntry.DEFLATED))
                throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_07.text());
            CRC32 crc = new CRC32(); MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count=0;
            try (InputStream stream = zip.getInputStream(runtime)) {
                byte[] buffer = new byte[16384]; int read;
                while ((read=stream.read(buffer))!=-1) {
                    count+=read; if(count>MAX_RUNTIME_BYTES || count>runtime.getSize()) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_08.text());
                    digest.update(buffer,0,read); crc.update(buffer,0,read);
                }
            }
            if (count!=runtime.getSize() || crc.getValue()!=runtime.getCrc()) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_09.text());
            candidate = PackageTrust.hex(digest.digest());
            if (!CODE45_RUNTIME.equals(candidate) && !candidate.equals(currentRuntimeHash))
                throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_10.text());
        }
        BasicFileAttributes after = Files.readAttributes(baseApk.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || before.size()!=after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(),after.fileKey())) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_11.text());
        return candidate;
    }

    static String boundedHash(InputStream stream) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[16384]; long count=0; int read;
        while((read=stream.read(buffer))!=-1) {
            count+=read;if(count>MAX_RUNTIME_BYTES)throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_12.text());digest.update(buffer,0,read);
        }
        if(count==0)throw new IOException(AndroidMessages.INSTALLED_BRIDGE_PROTOCOL_13.text());return PackageTrust.hex(digest.digest());
    }
}
