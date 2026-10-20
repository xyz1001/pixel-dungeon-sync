package io.github.xyz1001.pdsync.android.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.Properties;

public final class GameSourceIdentity {
    /** Provenance is independent of output compatibility signing. UNKNOWN branches never claim a signer. */
    public enum Provenance { VERIFIED_SIGNER, INSTALLED_SIGNER, UNSIGNED, INHERITED_EMBEDDED }
    public final Provenance provenance;
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
        this(str, list, Provenance.VERIFIED_SIGNER, null, false);
    }

    public static GameSourceIdentity unsigned(String packageName) throws IOException {
        return new GameSourceIdentity(packageName, Collections.emptyList(), Provenance.UNSIGNED, null, false);
    }

    static GameSourceIdentity of(String packageName, List<String> certificates, Provenance provenance) throws IOException {
        return new GameSourceIdentity(packageName, certificates, provenance, null, false);
    }

    /** The manifest binding preserves an existing namespace, but is not publisher authentication. */
    static GameSourceIdentity inherited(BinaryManifestPatch.BridgeBinding binding) throws IOException {
        return new GameSourceIdentity(binding.gamePackage, Collections.emptyList(), Provenance.INHERITED_EMBEDDED,
                binding.originIdentity, false);
    }

    /** Restores already validated continuity metadata; does not itself verify its source. */
    public static GameSourceIdentity restoreProvenance(String packageName, List<String> certificates,
            Provenance provenance, String validatedOrigin) throws IOException {
        if (provenance == null) throw new IOException("Missing source provenance");
        GameSourceIdentity result = new GameSourceIdentity(packageName, certificates, provenance,
                provenance == Provenance.INHERITED_EMBEDDED ? validatedOrigin : null, false);
        if (!result.originIdentity.equals(validatedOrigin)) throw new IOException("Source provenance origin binding mismatch");
        return result;
    }

    static GameSourceIdentity read(Properties properties) throws IOException {
        Provenance kind;
        try { kind = Provenance.valueOf(properties.getProperty("sourceProvenance", "VERIFIED_SIGNER")); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid source provenance receipt", error); }
        String encoded = properties.getProperty("sourceCertificates", "");
        List<String> certificates = encoded.isEmpty() ? Collections.emptyList() : java.util.Arrays.asList(encoded.split(",", -1));
        return new GameSourceIdentity(properties.getProperty("gamePackage"), certificates, kind,
                kind == Provenance.INHERITED_EMBEDDED ? properties.getProperty("originIdentity") : null, false);
    }

    void write(Properties properties) {
        properties.setProperty("sourceProvenance", provenance.name());
        properties.setProperty("sourceCertificates", String.join(",", signerCertificateSha256));
    }

    private GameSourceIdentity(String str, List<String> list, Provenance kind, String inheritedOrigin, boolean z) throws IOException {
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
        boolean unknown = kind == Provenance.UNSIGNED || kind == Provenance.INHERITED_EMBEDDED;
        if (unknown != treeSet.isEmpty()) throw new IOException("Source signing certificates disagree with explicit provenance");
        if (kind == Provenance.INHERITED_EMBEDDED && (inheritedOrigin == null || !inheritedOrigin.matches("[0-9a-f]{64}")))
            throw new IOException("Invalid inherited embedded origin binding");
        this.provenance = kind;
        this.gamePackage = str;
        this.legacyTransport = z;
        List<String> listUnmodifiableList = Collections.unmodifiableList(new ArrayList(treeSet));
        this.signerCertificateSha256 = listUnmodifiableList;
        String strSha256 = ApkArchive.sha256(("pdsync-origin-v1\n" + str + "\n" + String.join("\n", listUnmodifiableList)).getBytes(StandardCharsets.UTF_8));
        if (kind == Provenance.UNSIGNED) strSha256 = ApkArchive.sha256(("pdsync-unsigned-package-v1\n" + str).getBytes(StandardCharsets.UTF_8));
        if (kind == Provenance.INHERITED_EMBEDDED) strSha256 = inheritedOrigin;
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
        return new GameSourceIdentity(gameSourceIdentity.gamePackage, gameSourceIdentity.signerCertificateSha256, gameSourceIdentity.provenance, null, true);
    }
}
