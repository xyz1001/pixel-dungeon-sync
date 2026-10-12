package io.github.xyz1001.pdsync.android.prepare;

import io.github.xyz1001.pdsync.android.prepare.BinaryManifestPatch;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

final class InstalledSourceProof {
    private static final String PREFIX = "installedSourceProof.";
    final List<String> certificates;
    private final List<String> hashes;
    final String packageName;
    final long versionCode;

    private InstalledSourceProof(String str, long j, List<String> list, List<String> list2) throws IOException {
        GameSourceIdentity gameSourceIdentity = new GameSourceIdentity(str, list);
        if (j < 0) {
            throw new IOException("Invalid installed-source proof version");
        }
        if (gameSourceIdentity.signerCertificateSha256.size() > 32) {
            throw new IOException("Invalid installed-source proof signer count");
        }
        if (list2 == null || list2.isEmpty() || list2.size() > 256) {
            throw new IOException("Invalid installed-source proof APK count");
        }
        ArrayList arrayList = new ArrayList();
        for (String str2 : list2) {
            if (str2 == null || !str2.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid installed-source proof APK digest");
            }
            arrayList.add(str2);
        }
        Collections.sort(arrayList);
        this.packageName = gameSourceIdentity.gamePackage;
        this.versionCode = j;
        this.certificates = gameSourceIdentity.signerCertificateSha256;
        this.hashes = Collections.unmodifiableList(arrayList);
    }

    static InstalledSourceProof record(String str, long j, List<String> list, List<File> list2) throws IOException {
        return new InstalledSourceProof(str, j, list, hashes(list2));
    }

    static InstalledSourceProof readFrom(Properties properties) throws IOException, NumberFormatException {
        String property = properties.getProperty("installedSourceProof.format");
        if (property == null) {
            Iterator<String> it = properties.stringPropertyNames().iterator();
            while (it.hasNext()) {
                if (it.next().startsWith("installedSourceProof.")) {
                    throw new IOException("Incomplete installed-source proof receipt");
                }
            }
            return null;
        }
        if (!"1".equals(property)) {
            throw new IOException("Unsupported installed-source proof receipt");
        }
        long jNumber = number(properties, "versionCode", 0L, Long.MAX_VALUE);
        int iNumber = (int) number(properties, "apkCount", 1L, 256L);
        ArrayList arrayList = new ArrayList();
        for (int i = 0; i < iNumber; i++) {
            arrayList.add(required(properties, "sha256." + i));
        }
        return new InstalledSourceProof(required(properties, "packageName"), jNumber, Arrays.asList(required(properties, "certificates").split(",", -1)), arrayList);
    }

    void writeTo(Properties properties) {
        Iterator it = new ArrayList(properties.stringPropertyNames()).iterator();
        while (it.hasNext()) {
            String str = (String) it.next();
            if (str.startsWith("installedSourceProof.")) {
                properties.remove(str);
            }
        }
        properties.setProperty("installedSourceProof.format", "1");
        properties.setProperty("installedSourceProof.packageName", this.packageName);
        properties.setProperty("installedSourceProof.versionCode", Long.toString(this.versionCode));
        properties.setProperty("installedSourceProof.certificates", String.join(",", this.certificates));
        properties.setProperty("installedSourceProof.apkCount", Integer.toString(this.hashes.size()));
        for (int i = 0; i < this.hashes.size(); i++) {
            properties.setProperty("installedSourceProof.sha256." + i, this.hashes.get(i));
        }
    }

    void validate(List<File> list) throws IOException {
        if (!this.hashes.equals(hashes(list))) {
            throw new IOException("Installed-source proof APK set digest mismatch");
        }
        Iterator<File> it = list.iterator();
        while (it.hasNext()) {
            validateManifest(ApkArchive.validateSource(it.next()));
        }
    }

    void validateFile(File file) throws IOException {
        if (file == null || !file.isFile() || !this.hashes.contains(ApkArchive.sha256(file))) {
            throw new IOException("Installed-source proof APK digest mismatch");
        }
        validateManifest(ApkArchive.validateSource(file));
    }

    void validateManifest(BinaryManifestPatch.ManifestInfo manifestInfo) throws IOException {
        if (!this.packageName.equals(manifestInfo.packageName) || this.versionCode != manifestInfo.versionCode) {
            throw new IOException("Installed-source proof package/version mismatch");
        }
    }

    private static List<String> hashes(List<File> list) throws IOException {
        if (list == null || list.isEmpty() || list.size() > 256) {
            throw new IOException("Invalid installed-source proof APK count");
        }
        ArrayList arrayList = new ArrayList();
        long j = 0;
        for (File file : list) {
            if (file == null || !file.isFile()) {
                throw new IOException("Installed-source proof APK unavailable");
            }
            long length = file.length();
            if (length <= 0 || length > 2147483647L || j > 8589934592L - length) {
                throw new IOException("Installed-source proof APK size limit exceeded");
            }
            j += length;
            arrayList.add(ApkArchive.sha256(file));
        }
        Collections.sort(arrayList);
        return arrayList;
    }

    private static String required(Properties properties, String str) throws IOException {
        String property = properties.getProperty("installedSourceProof." + str);
        if (property == null || property.isEmpty()) {
            throw new IOException("Incomplete installed-source proof receipt");
        }
        return property;
    }

    private static long number(Properties properties, String str, long j, long j2) throws IOException, NumberFormatException {
        try {
            String strRequired = required(properties, str);
            if (!strRequired.matches("[0-9]{1,19}")) {
                throw new NumberFormatException();
            }
            long j3 = Long.parseLong(strRequired);
            if (j3 < j || j3 > j2) {
                throw new NumberFormatException();
            }
            return j3;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid installed-source proof receipt number", e);
        }
    }
}
