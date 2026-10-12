package io.github.xyz1001.pdsync.android.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

public final class GameSourceIdentity {
    public static final String LEGACY_ORIGIN = "32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89";
    public final String authority;
    public final String gamePackage;
    public final String helperPackage;
    public final boolean legacyTransport;
    public final String namespaceId;
    public final String originIdentity;
    public final String sharedUid;
    public final List<String> signerCertificateSha256;

    public GameSourceIdentity(String str, List<String> list) throws IOException {
        this(str, list, false);
    }

    private GameSourceIdentity(String str, List<String> list, boolean z) throws IOException {
        if (str == null || !str.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+") || str.length() > 255) {
            throw new IOException("APK has an invalid Android package name");
        }
        TreeSet treeSet = new TreeSet();
        if (list != null) {
            for (String str2 : list) {
                if (str2 == null || !str2.matches("[0-9a-f]{64}")) {
                    throw new IOException("Invalid source signing certificate digest");
                }
                if (!treeSet.add(str2)) {
                    throw new IOException("Duplicate source signing certificate");
                }
            }
        }
        if (treeSet.isEmpty()) {
            throw new IOException("Source APK must have a verified signing certificate");
        }
        this.gamePackage = str;
        this.legacyTransport = z;
        List<String> listUnmodifiableList = Collections.unmodifiableList(new ArrayList(treeSet));
        this.signerCertificateSha256 = listUnmodifiableList;
        String strSha256 = ApkArchive.sha256(("pdsync-origin-v1\n" + str + "\n" + String.join("\n", listUnmodifiableList)).getBytes(StandardCharsets.UTF_8));
        this.originIdentity = strSha256;
        StringBuilder sb = new StringBuilder("android-");
        sb.append(strSha256);
        this.namespaceId = sb.toString();
        if (z && (!"com.shatteredpixel.shatteredpixeldungeon".equals(str) || !"32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89".equals(strSha256))) {
            throw new IOException("Legacy group cannot be assigned to a different source origin");
        }
        String str3 = z ? "io.github.xyz1001.pdsync.bridge" : "io.github.xyz1001.pdsync.bridge.g" + strSha256.substring(0, 32);
        this.helperPackage = str3;
        this.sharedUid = z ? "io.github.xyz1001.pdsync.shared" : "io.github.xyz1001.pdsync.shared.g" + strSha256.substring(0, 32);
        StringBuilder sb2 = new StringBuilder();
        sb2.append(str3);
        sb2.append(z ? ".saves" : ".files");
        this.authority = sb2.toString();
    }

    static GameSourceIdentity legacyTransport(GameSourceIdentity gameSourceIdentity) throws IOException {
        return new GameSourceIdentity(gameSourceIdentity.gamePackage, gameSourceIdentity.signerCertificateSha256, true);
    }
}
