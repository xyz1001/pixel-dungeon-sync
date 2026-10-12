package io.github.xyz1001.pdsync.android;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** App-private, atomic local mode selection. Does not touch connections or namespace mappings. */
public final class RootSelectionStore {
    private static final int MAX_BYTES = 65536;
    private final Path root;
    private final Path selection;
    public RootSelectionStore(File filesDir) throws IOException {
        root = filesDir.getCanonicalFile().toPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Selection directory unavailable");
        selection = root.resolve("root-selection-v1.properties");
    }
    public synchronized SelectedRootGame load() throws IOException {
        if (!Files.exists(selection, LinkOption.NOFOLLOW_LINKS)) return null;
        requireFile(selection);
        Properties p = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate root selection property");
                return super.put(key, value);
            }
        };
        try (InputStream in = Files.newInputStream(selection, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (bytes.size() + n > MAX_BYTES) throw new IOException("Root selection exceeds limit");
                bytes.write(buffer, 0, n);
            }
            p.load(new ByteArrayInputStream(bytes.toByteArray()));
        } catch (IllegalArgumentException bad) { throw new IOException("Invalid root selection", bad); }
        if (!"1".equals(required(p,"version"))) throw new IOException("Unsupported root selection version");
        int count = (int) number(p, "apkCount", 1, 64);
        Map<String,String> hashes = new TreeMap<>();
        for (int i = 0; i < count; i++)
            if (hashes.put(required(p,"apk."+i+".path"),required(p,"apk."+i+".hash")) != null)
                throw new IOException("Duplicate APK selection");
        String retained = required(p,"retainedProvenance");
        if (!"true".equals(retained) && !"false".equals(retained)) throw new IOException("Invalid provenance flag");
        if (p.size() != 15 + count * 2) throw new IOException("Unknown root selection fields");
        return new SelectedRootGame(required(p,"package"),required(p,"label"),required(p,"versionName"),
                number(p,"versionCode",0,Long.MAX_VALUE),number(p,"firstInstallTime",0,Long.MAX_VALUE),
                number(p,"lastUpdateTime",0,Long.MAX_VALUE),(int)number(p,"userId",0,21473),
                (int)number(p,"uid",0,Integer.MAX_VALUE),required(p,"dataDir"),
                Arrays.asList(required(p,"currentSigners").split(",",-1)),
                Arrays.asList(required(p,"sourceSigners").split(",",-1)),required(p,"sourceApkSetSha256"),hashes,
                Boolean.parseBoolean(retained));
    }
    public synchronized void save(SelectedRootGame game) throws IOException {
        if (game == null) throw new IOException("Missing root selection");
        Properties p = new Properties();
        p.setProperty("version","1"); p.setProperty("package",game.gamePackage); p.setProperty("label",game.gameLabel);
        p.setProperty("versionName",game.gameVersionName); p.setProperty("versionCode",Long.toString(game.gameVersionCode));
        p.setProperty("firstInstallTime",Long.toString(game.firstInstallTime)); p.setProperty("lastUpdateTime",Long.toString(game.lastUpdateTime));
        p.setProperty("userId",Integer.toString(game.userId)); p.setProperty("uid",Integer.toString(game.uid));
        p.setProperty("dataDir",game.dataDir); p.setProperty("currentSigners",String.join(",",game.currentSignerCertificateSha256));
        p.setProperty("sourceSigners",String.join(",",game.sourceSignerCertificateSha256));
        p.setProperty("sourceApkSetSha256",game.sourceApkSetSha256); p.setProperty("retainedProvenance",Boolean.toString(game.retainedProvenance));
        p.setProperty("apkCount",Integer.toString(game.apkHashes.size())); int i=0;
        for (Map.Entry<String,String> entry:game.apkHashes.entrySet()) {
            p.setProperty("apk."+i+".path",entry.getKey());p.setProperty("apk."+i+".hash",entry.getValue());i++;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); p.store(bytes,"Local root selection; authorization is never persisted");
        if(bytes.size()>MAX_BYTES) throw new IOException("Root selection exceeds limit");
        if(Files.exists(selection,LinkOption.NOFOLLOW_LINKS)) requireFile(selection);
        Path temp=Files.createTempFile(root,".root-selection-",".tmp");
        try {
            try (FileOutputStream out=new FileOutputStream(temp.toFile())) { bytes.writeTo(out);out.getFD().sync(); }
            Files.move(temp,selection,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    public synchronized void clear() throws IOException {
        if (Files.exists(selection,LinkOption.NOFOLLOW_LINKS)) { requireFile(selection); Files.delete(selection); }
    }
    private static void requireFile(Path path) throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>MAX_BYTES) throw new IOException("Invalid root selection file");
    }
    private static String required(Properties p,String key)throws IOException {
        String value=p.getProperty(key);if(value==null)throw new IOException("Missing root selection field");return value;
    }
    private static long number(Properties p,String key,long min,long max)throws IOException {
        try {long n=Long.parseLong(required(p,key));if(n<min||n>max)throw new NumberFormatException();return n;}
        catch(NumberFormatException bad){throw new IOException("Invalid root selection number",bad);}
    }
}
