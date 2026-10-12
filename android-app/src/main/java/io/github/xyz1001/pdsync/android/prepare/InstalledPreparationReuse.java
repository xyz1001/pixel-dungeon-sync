package io.github.xyz1001.pdsync.android.prepare;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class InstalledPreparationReuse {

    static final class Installed {
        final List<String> apkHashes;
        final List<String> certificates;
        final String packageName;
        final String sharedUid;
        final long versionCode;

        Installed(String str, long j, String str2, List<String> list, List<String> list2) {
            this.packageName = str;
            this.versionCode = j;
            this.sharedUid = str2;
            this.certificates = InstalledPreparationReuse.frozen(list);
            this.apkHashes = InstalledPreparationReuse.frozen(list2);
        }
    }

    static final class Receipt {
        final List<String> apkHashes;
        final String localCertificate;
        final GameSourceIdentity source;
        final long versionCode;
        final boolean embeddedBridge;

        Receipt(GameSourceIdentity gameSourceIdentity, long j, String str, List<String> list) {
            this(gameSourceIdentity, j, str, list, false);
        }

        Receipt(GameSourceIdentity gameSourceIdentity, long j, String str, List<String> list, boolean embeddedBridge) {
            this.embeddedBridge = embeddedBridge;
            this.source = gameSourceIdentity;
            this.versionCode = j;
            this.localCertificate = str;
            this.apkHashes = InstalledPreparationReuse.frozen(list);
        }
    }

    private InstalledPreparationReuse() {
    }

    static List<File> prioritizeCurrentReceipt(File[] fileArr, final String str) {
        if (fileArr == null) {
            return Collections.EMPTY_LIST;
        }
        ArrayList arrayList = new ArrayList();
        Collections.addAll(arrayList, fileArr);
        arrayList.sort(Comparator.comparing(new Function() { // from class: io.github.xyz1001.pdsync.android.prepare.InstalledPreparationReuse$$ExternalSyntheticLambda0
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                String str2 = str;
                File file = (File) obj;
                return Boolean.valueOf(!file.getName().equals(str2));
            }
        }).thenComparing(new Function() { // from class: io.github.xyz1001.pdsync.android.prepare.InstalledPreparationReuse$$ExternalSyntheticLambda1
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return ((File) obj).getName();
            }
        }));
        return Collections.unmodifiableList(arrayList);
    }

    /** New embedded preparations may accept a foreign re-signed or shared-UID game as a new source. */
    static boolean requiresEmbeddedReceipt(List<String> certificates, String localCertificate) throws IOException {
        if (localCertificate == null) return false;
        if (!localCertificate.matches("[0-9a-f]{64}")) throw new IOException("Invalid local signing certificate");
        if (certificates == null || !certificates.contains(localCertificate)) return false;
        if (!Collections.singletonList(localCertificate).equals(certificates)) throw new IOException("Installed game does not have the exact current local signer");
        return true;
    }

    static boolean requiresReceipt(List<String> list, String str, String str2) throws IOException {
        boolean z = "io.github.xyz1001.pdsync.shared".equals(str) || (str != null && str.startsWith("io.github.xyz1001.pdsync.shared.g"));
        if (str2 == null) {
            if (z) {
                throw new IOException("The original local signing key is missing. Preserve the installed game and helper; do not uninstall them.");
            }
            return false;
        }
        if (!str2.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid local signing certificate");
        }
        if (list == null || !list.contains(str2)) {
            if (z) {
                throw new IOException("The installed shared-UID game belongs to another local signing key. Preserve its original installation.");
            }
            return false;
        }
        if (Collections.singletonList(str2).equals(list)) {
            return true;
        }
        throw new IOException("Installed game does not have the exact current local signer");
    }

    static GameSourceIdentity requireMatch(Installed installed, Receipt receipt, String str) throws IOException {
        if (installed == null || receipt == null || receipt.source == null) {
            throw new IOException("Installed local game has no matching original publisher receipt");
        }
        if (!requiresReceipt(installed.certificates, installed.sharedUid, str) || !Collections.singletonList(str).equals(installed.certificates) || !str.equals(receipt.localCertificate)) {
            throw new IOException("Installed game and retained receipt do not use the current local signer");
        }
        if (!receipt.source.gamePackage.equals(installed.packageName) || receipt.versionCode != installed.versionCode || !Objects.equals(receipt.embeddedBridge ? null : receipt.source.sharedUid, installed.sharedUid)) {
            throw new IOException("Installed game package, version or shared UID differs from its retained receipt");
        }
        if (!sameApkSet(installed.apkHashes, receipt.apkHashes)) {
            throw new IOException("Installed game APK set differs from its retained receipt");
        }
        return receipt.source;
    }

    static boolean sameApkSet(List<String> list, List<String> list2) {
        if (!validHashes(list) || !validHashes(list2)) {
            return false;
        }
        ArrayList arrayList = new ArrayList(list);
        ArrayList arrayList2 = new ArrayList(list2);
        Collections.sort(arrayList);
        Collections.sort(arrayList2);
        return arrayList.equals(arrayList2);
    }

    private static boolean validHashes(List<String> list) {
        if (list == null || list.isEmpty() || list.size() > 256) {
            return false;
        }
        for (String str : list) {
            if (str == null || !str.matches("[0-9a-f]{64}")) {
                return false;
            }
        }
        return true;
    }

    public static List<String> frozen(List<String> list) {
        return list == null ? Collections.EMPTY_LIST : Collections.unmodifiableList(new ArrayList(list));
    }
}
