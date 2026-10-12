package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.bridge.SaveArchive;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** Caller-selected identity. The root runtime independently checks PM and installed bytes. */
public final class RootBinding {
    public static final int MAX_APKS = 64;
    public final String packageName;
    public final int userId;
    public final int uid;
    public final String dataDir;
    public final String sourceIdentity;
    public final SortedMap<String, String> apkHashes;

    public RootBinding(String packageName, int userId, int uid, String dataDir,
            String sourceIdentity, Map<String, String> apkHashes) throws IOException {
        if (packageName == null || packageName.length() > 255
                || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw new IOException("Invalid package binding");
        if (userId < 0 || userId > 21473 || uid < 0 || uid / 100000 != userId
                || uid % 100000 < 10000 || uid % 100000 > 19999)
            throw new IOException("Invalid application UID binding");
        String normal = "/data/user/" + userId + "/" + packageName;
        if (!normal.equals(dataDir) && !(userId == 0 && ("/data/data/" + packageName).equals(dataDir)))
            throw new IOException("Invalid PM data directory");
        if (!isHash(sourceIdentity) || apkHashes == null || apkHashes.isEmpty() || apkHashes.size() > MAX_APKS)
            throw new IOException("Invalid source identity or APK set");
        TreeMap<String, String> copy = new TreeMap<>();
        for (Map.Entry<String, String> item : apkHashes.entrySet()) {
            if (!safeApkPath(item.getKey()) || !isHash(item.getValue())) throw new IOException("Invalid APK binding");
            copy.put(item.getKey(), item.getValue());
        }
        this.packageName = packageName;
        this.userId = userId;
        this.uid = uid;
        this.dataDir = dataDir;
        this.sourceIdentity = sourceIdentity;
        this.apkHashes = Collections.unmodifiableSortedMap(copy);
    }

    public String fingerprint() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        RootProtocol.writeString(output, "PDSYNC-ROOT-BINDING-V1");
        RootProtocol.writeBinding(output, this);
        output.flush();
        return SaveArchive.sha256(bytes.toByteArray());
    }

    public boolean matchesInstalled(RootBinding installed) {
        return installed != null && packageName.equals(installed.packageName) && userId == installed.userId
                && uid == installed.uid && normalizedDataDir().equals(installed.normalizedDataDir())
                && apkHashes.equals(installed.apkHashes);
    }

    public String normalizedDataDir() { return "/data/user/" + userId + "/" + packageName; }
    public static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    static boolean safeApkPath(String path) {
        if (path == null || path.length() > 2048 || !path.endsWith(".apk") || path.contains("//")) return false;
        if (!(path.startsWith("/data/app/") || path.startsWith("/system/app/")
                || path.startsWith("/system/priv-app/") || path.startsWith("/product/app/")
                || path.startsWith("/product/priv-app/") || path.startsWith("/system_ext/app/")
                || path.startsWith("/system_ext/priv-app/") || path.startsWith("/vendor/app/"))) return false;
        for (String piece : path.substring(1).split("/", -1))
            if (piece.length() == 0 || piece.length() > 255 || piece.equals(".") || piece.equals("..")
                    || !piece.matches("[A-Za-z0-9_.+=@~-]+")) return false;
        return true;
    }
}
