package io.github.xyz1001.pdsync.android.prepare;

import io.github.xyz1001.pdsync.android.prepare.BinaryManifestPatch;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApkSetValidator {
    public static final int MAX_APKS = 256;
    public static final long MAX_SET_BYTES = 8589934592L;

    public static final class SourceApk {
        public final File file;
        public final BinaryManifestPatch.ManifestInfo manifest;
        public final List<String> signerCertificateSha256;
        public final GameSourceIdentity.Provenance provenance;

        public SourceApk(File file, BinaryManifestPatch.ManifestInfo manifestInfo, List<String> list) {
            this(file, manifestInfo, list, GameSourceIdentity.Provenance.VERIFIED_SIGNER);
        }

        SourceApk(File file, BinaryManifestPatch.ManifestInfo manifestInfo, LocalApkSigner.SourceSignatures signatures) {
            this(file, manifestInfo, signatures.certificates, GameSourceIdentity.Provenance.valueOf(signatures.kind.name()));
        }

        private SourceApk(File file, BinaryManifestPatch.ManifestInfo manifestInfo, List<String> list, GameSourceIdentity.Provenance provenance) {
            this.file = file;
            this.manifest = manifestInfo;
            this.provenance = provenance;
            ArrayList arrayList = new ArrayList(list);
            Collections.sort(arrayList);
            this.signerCertificateSha256 = Collections.unmodifiableList(arrayList);
        }
    }

    public static final class ValidatedSet {
        public final List<SourceApk> apks;
        public final SourceApk base;
        public final GameSourceIdentity identity;

        ValidatedSet(SourceApk sourceApk, List<SourceApk> list) throws IOException {
            this.base = sourceApk;
            this.apks = Collections.unmodifiableList(new ArrayList(list));
            this.identity = GameSourceIdentity.of(sourceApk.manifest.packageName, sourceApk.signerCertificateSha256, sourceApk.provenance);
        }
    }

    private ApkSetValidator() {
    }

    public static ValidatedSet inspect(List<File> list, boolean z) throws Exception {
        return inspect(list, z, null);
    }

    static ValidatedSet inspect(List<File> list, boolean z, InstalledSourceProof installedSourceProof) throws Exception {
        if (list == null || list.isEmpty() || list.size() > 256) {
            throw new IOException("Select one base APK and its splits (maximum 256 files for resource safety)");
        }
        if (installedSourceProof != null) {
            if (!z) {
                throw new IOException("Installed-source proof requires a complete installed APK set");
            }
            installedSourceProof.validate(list);
        }
        ArrayList arrayList = new ArrayList();
        long length = 0;
        for (File file : list) {
            length += file.length();
            if (length > 8589934592L) {
                throw new IOException("APK set exceeds the 8 GiB technical staging limit");
            }
            BinaryManifestPatch.ManifestInfo manifestInfoValidateSource = ApkArchive.validateSource(file);
            if (installedSourceProof != null) {
                installedSourceProof.validateManifest(manifestInfoValidateSource);
            }
            arrayList.add(new SourceApk(file, manifestInfoValidateSource, LocalApkSigner.sourceSignatures(file, installedSourceProof)));
        }
        return validate(arrayList, z);
    }

    public static ValidatedSet validate(List<SourceApk> list, boolean z) throws IOException {
        return validate(list, z, PreparationPlatform.currentApi());
    }

    static ValidatedSet validate(List<SourceApk> list, boolean z, int api) throws IOException {
        PreparationPlatform.requireSupportedApi(api);
        if (list == null || list.isEmpty() || list.size() > 256) {
            throw new IOException("Invalid APK set size");
        }
        LinkedHashMap<String, SourceApk> linkedHashMap = new LinkedHashMap<>();
        SourceApk sourceApk = null;
        for (SourceApk sourceApk2 : list) {
            BinaryManifestPatch.ManifestInfo manifestInfo = sourceApk2.manifest;
            if (manifestInfo.versionCode < 0) {
                throw new IOException("Unsupported negative combined Android version code");
            }
            if (manifestInfo.minSdkVersion > api) {
                throw new IOException("APK requires Android API " + manifestInfo.minSdkVersion + "; this device runs Android API " + api);
            }
            String strNormalized = normalized(manifestInfo.splitName);
            if (strNormalized.isEmpty()) {
                if (sourceApk != null) {
                    throw new IOException("Selected APK set contains more than one base APK");
                }
                sourceApk = sourceApk2;
            } else if (linkedHashMap.put(strNormalized, sourceApk2) != null) {
                throw new IOException("Duplicate split APK name: " + strNormalized);
            }
        }
        if (sourceApk == null) {
            throw new IOException("Selected APK set is missing its base APK");
        }
        for (SourceApk sourceApk3 : list) {
            BinaryManifestPatch.ManifestInfo manifestInfo2 = sourceApk3.manifest;
            if (!sourceApk.manifest.packageName.equals(manifestInfo2.packageName)) {
                throw new IOException("Mixed APK packages: " + manifestInfo2.packageName);
            }
            if (sourceApk.manifest.versionCode != manifestInfo2.versionCode) {
                throw new IOException("Split APK version code differs from the base: " + normalized(manifestInfo2.splitName));
            }
            if (sourceApk.provenance != sourceApk3.provenance || !sourceApk.signerCertificateSha256.equals(sourceApk3.signerCertificateSha256)) {
                throw new IOException("Split APK signer differs from the base: " + normalized(manifestInfo2.splitName));
            }
            String strNormalized2 = normalized(manifestInfo2.configForSplit);
            if (!strNormalized2.isEmpty() && (!linkedHashMap.containsKey(strNormalized2) || !((SourceApk) linkedHashMap.get(strNormalized2)).manifest.isFeatureSplit)) {
                throw new IOException("Configuration split targets a missing feature split: " + strNormalized2);
            }
            if (manifestInfo2.isFeatureSplit && !strNormalized2.isEmpty()) {
                throw new IOException("Feature split cannot also configure another split: " + manifestInfo2.splitName);
            }
            for (String str : manifestInfo2.usesSplits) {
                if (!linkedHashMap.containsKey(str)) {
                    throw new IOException("Required uses-split dependency is missing: " + str);
                }
                if (!((SourceApk) linkedHashMap.get(str)).manifest.isFeatureSplit) {
                    throw new IOException("uses-split dependency is not a feature: " + str);
                }
            }
        }
        HashMap map = new HashMap();
        Iterator it = linkedHashMap.keySet().iterator();
        while (it.hasNext()) {
            visit((String) it.next(), linkedHashMap, map);
        }
        Iterator<SourceApk> it2 = list.iterator();
        while (it2.hasNext()) {
            BinaryManifestPatch.ManifestInfo manifestInfo3 = it2.next().manifest;
            if (normalized(manifestInfo3.splitName).isEmpty() || manifestInfo3.isFeatureSplit) {
                HashSet hashSet = new HashSet(manifestInfo3.splitTypes);
                boolean z2 = false;
                for (SourceApk sourceApk4 : linkedHashMap.values()) {
                    if (!sourceApk4.manifest.isFeatureSplit && normalized(sourceApk4.manifest.configForSplit).equals(normalized(manifestInfo3.splitName))) {
                        hashSet.addAll(sourceApk4.manifest.splitTypes);
                        z2 = true;
                    }
                }
                for (String type : manifestInfo3.requiredSplitTypes) {
                    if (!hashSet.contains(type)) throw new IOException("Missing required split type '" + type + "' for " + (normalized(manifestInfo3.splitName).isEmpty() ? "base" : manifestInfo3.splitName));
                }
                if (manifestInfo3.isSplitRequired && !z2 && manifestInfo3.requiredSplitTypes.isEmpty()) throw new IOException("APK declares required configuration splits but none were supplied for " + (normalized(manifestInfo3.splitName).isEmpty() ? "base" : manifestInfo3.splitName));
                if (manifestInfo3.isSplitRequired && manifestInfo3.requiredSplitTypes.isEmpty() && !z) throw new IOException("This APK uses legacy isSplitRequired without enumerating requiredSplitTypes. A document-selected set cannot prove its complete device configuration; select this installed game's complete base/split set instead.");
            }
        }
        ArrayList arrayList = new ArrayList();
        arrayList.add(sourceApk);
        ArrayList arrayList2 = new ArrayList(linkedHashMap.keySet());
        Collections.sort(arrayList2);
        Iterator it4 = arrayList2.iterator();
        while (it4.hasNext()) {
            arrayList.add((SourceApk) linkedHashMap.get((String) it4.next()));
        }
        return new ValidatedSet(sourceApk, arrayList);
    }

    public static String sourceSetSha256(ValidatedSet validatedSet) throws IOException {
        StringBuilder sb = new StringBuilder("pdsync-apk-set-v1\n");
        for (SourceApk sourceApk : validatedSet.apks) {
            String strNormalized = normalized(sourceApk.manifest.splitName);
            sb.append(strNormalized.length());
            sb.append(':');
            sb.append(strNormalized);
            sb.append(':');
            sb.append(ApkArchive.sha256(sourceApk.file));
            sb.append('\n');
        }
        return ApkArchive.sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void visit(String str, Map<String, SourceApk> map, Map<String, Integer> map2) throws IOException {
        Integer num = map2.get(str);
        if (num != null) {
            if (num.intValue() != 1) {
                return;
            }
            throw new IOException("Cyclic split dependency at " + str);
        }
        map2.put(str, 1);
        Iterator<String> it = map.get(str).manifest.usesSplits.iterator();
        while (it.hasNext()) {
            visit(it.next(), map, map2);
        }
        map2.put(str, 2);
    }

    private static String normalized(String str) {
        return str == null ? "" : str;
    }
}
