package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** App-private, atomic local mode selection. Does not touch connections or namespace mappings. */
public final class InstalledBridgeSelectionStore {
    private static final int MAX_BYTES = 65536;
    private final Path root;
    private final Path selection;
    public InstalledBridgeSelectionStore(File filesDir) throws IOException {
        root = filesDir.getCanonicalFile().toPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_SELECTION_STORE_01.text());
        selection = root.resolve("installed-bridge-selection-v1.properties");
    }
    public synchronized InstalledBridgeBinding load() throws IOException {
        if (!Files.exists(selection, LinkOption.NOFOLLOW_LINKS)) return null;
        requireFile(selection);
        Properties p = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException(AndroidMessages.INSTALLED_BRIDGE_SELECTION_STORE_02.text());
                return super.put(key, value);
            }
        };
        try (InputStream in = Files.newInputStream(selection, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (bytes.size() + n > MAX_BYTES) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_SELECTION_STORE_03.text());
                bytes.write(buffer, 0, n);
            }
            p.load(new ByteArrayInputStream(bytes.toByteArray()));
        } catch (IllegalArgumentException bad) { throw new IOException(AndroidMessages.INSTALLED_BRIDGE_SELECTION_STORE_04.text(), bad); }
        if (!"1".equals(required(p,"version"))) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_SELECTION_STORE_05.text());
        int count = (int) number(p, "apkCount", 1, 64);
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < count; i++) paths.add(required(p, "apk." + i));
        if (p.size() != 17 + count) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_SELECTION_STORE_06.text());
        return new InstalledBridgeBinding(required(p,"package"), required(p,"label"), required(p,"versionName"),
                number(p,"versionCode",0,Long.MAX_VALUE), required(p,"origin"), required(p,"namespace"),
                required(p,"sourceHash"), required(p,"authority"), required(p,"runtime"), required(p,"accessProtocol"), required(p,"signer"),
                (int)number(p,"uid",0,Integer.MAX_VALUE), required(p,"dataDir"),
                number(p,"firstInstallTime",0,Long.MAX_VALUE), number(p,"lastUpdateTime",0,Long.MAX_VALUE), paths);
    }
    public synchronized void save(InstalledBridgeBinding game) throws IOException {
        if (game == null) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_SELECTION_STORE_07.text());
        Properties p = new Properties();
        p.setProperty("version","1"); p.setProperty("package",game.gamePackage); p.setProperty("label",game.gameLabel);
        p.setProperty("versionName",game.gameVersionName); p.setProperty("versionCode",Long.toString(game.gameVersionCode));
        p.setProperty("firstInstallTime",Long.toString(game.firstInstallTime)); p.setProperty("lastUpdateTime",Long.toString(game.lastUpdateTime));
        p.setProperty("uid",Integer.toString(game.uid)); p.setProperty("dataDir",game.dataDir);
        p.setProperty("origin",game.originIdentity); p.setProperty("namespace",game.namespaceId);
        p.setProperty("sourceHash",game.sourceApkSetSha256); p.setProperty("authority",game.authority);
        p.setProperty("runtime",game.bridgeRuntimeVersion); p.setProperty("accessProtocol",game.accessProtocol); p.setProperty("signer",game.signingCertificateSha256);
        p.setProperty("apkCount",Integer.toString(game.apkPaths.size()));
        for (int i=0; i<game.apkPaths.size(); i++) p.setProperty("apk."+i,game.apkPaths.get(i));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); p.store(bytes,"Local bridge selection; access authorization is never persisted");
        if(bytes.size()>MAX_BYTES) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_SELECTION_STORE_03.text());
        if(Files.exists(selection,LinkOption.NOFOLLOW_LINKS)) requireFile(selection);
        Path temp=Files.createTempFile(root,".installed-bridge-selection-",".tmp");
        try {
            try (FileOutputStream out=new FileOutputStream(temp.toFile())) { bytes.writeTo(out);out.getFD().sync(); }
            Files.move(temp,selection,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    public synchronized void clear() throws IOException {
        if (Files.exists(selection,LinkOption.NOFOLLOW_LINKS)) { requireFile(selection); Files.delete(selection); }
    }
    private static void requireFile(Path path) throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>MAX_BYTES) throw new IOException(AndroidMessages.INSTALLED_BRIDGE_SELECTION_STORE_08.text());
    }
    private static String required(Properties p,String key)throws IOException {
        String value=p.getProperty(key);if(value==null)throw new IOException(AndroidMessages.INSTALLED_BRIDGE_SELECTION_STORE_09.text());return value;
    }
    private static long number(Properties p,String key,long min,long max)throws IOException {
        try {long n=Long.parseLong(required(p,key));if(n<min||n>max)throw new NumberFormatException();return n;}
        catch(NumberFormatException bad){throw new IOException(AndroidMessages.INSTALLED_BRIDGE_SELECTION_STORE_10.text(),bad);}
    }
}
