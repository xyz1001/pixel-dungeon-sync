package io.github.xyz1001.pdsync.android.prepare;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

final class InstalledExtractionGuard {
    private static final ReadOnlyCheck SYSTEM_FILE = new ReadOnlyCheck() { // from class: io.github.xyz1001.pdsync.android.prepare.InstalledExtractionGuard$$ExternalSyntheticLambda0
        @Override // io.github.xyz1001.pdsync.android.prepare.InstalledExtractionGuard.ReadOnlyCheck
        public final boolean isReadOnly(File file) throws IOException {
            return InstalledExtractionGuard.isProtectedSystemFile(file);
        }
    };

    interface ReadOnlyCheck {
        boolean isReadOnly(File file) throws IOException;
    }

    static final class Snapshot {
        final List<String> certificates;
        final List<File> files;
        final long firstInstallTime;
        final long lastUpdateTime;
        final String packageName;
        final int uid;
        final long versionCode;

        Snapshot(String str, long j, long j2, long j3, int i, List<File> list, List<String> list2) {
            this.packageName = str;
            this.versionCode = j;
            this.firstInstallTime = j2;
            this.lastUpdateTime = j3;
            this.uid = i;
            this.files = Collections.unmodifiableList(new ArrayList(list));
            this.certificates = Collections.unmodifiableList(new ArrayList(list2));
        }

        void requireSame(Snapshot snapshot) throws IOException {
            if (snapshot == null || !this.packageName.equals(snapshot.packageName) || this.versionCode != snapshot.versionCode || this.firstInstallTime != snapshot.firstInstallTime || this.lastUpdateTime != snapshot.lastUpdateTime || this.uid != snapshot.uid || !this.files.equals(snapshot.files) || !this.certificates.equals(snapshot.certificates)) {
                throw new IOException("Installed game changed during extraction; select it again");
            }
        }
    }

    public static boolean isProtectedSystemFile(File file) throws IOException {
        if (!file.isFile() || file.canWrite() || !file.getAbsoluteFile().equals(file.getCanonicalFile())) {
            return false;
        }
        do {
            file = file.getParentFile();
            if (file == null) {
                return true;
            }
        } while (!file.canWrite());
        return false;
    }

    private InstalledExtractionGuard() {
    }

    static List<String> capture(List<File> list) throws IOException {
        return capture(list, SYSTEM_FILE);
    }

    static List<String> capture(List<File> list, ReadOnlyCheck readOnlyCheck) throws IOException {
        if (list == null || list.isEmpty() || list.size() > 256) {
            throw new IOException("Installed APK set is empty or oversized");
        }
        HashSet hashSet = new HashSet();
        ArrayList arrayList = new ArrayList();
        for (File file : list) {
            if (file == null || !hashSet.add(file.getCanonicalFile()) || !readOnlyCheck.isReadOnly(file)) {
                throw new IOException("Installed APK source is not a distinct read-only system file");
            }
            arrayList.add(ApkArchive.sha256(file));
            if (!readOnlyCheck.isReadOnly(file)) {
                throw new IOException("Installed APK source changed while reading");
            }
        }
        return Collections.unmodifiableList(arrayList);
    }

    static void verifyCopies(List<File> list, List<File> list2, List<String> list3) throws IOException {
        verifyCopies(list, list2, list3, SYSTEM_FILE);
    }

    static void verifyCopies(List<File> list, List<File> list2, List<String> list3, ReadOnlyCheck readOnlyCheck) throws IOException {
        if (list2 == null || list3 == null || list.size() != list2.size() || list.size() != list3.size()) {
            throw new IOException("Installed APK extraction is incomplete");
        }
        if (!list3.equals(capture(list, readOnlyCheck))) {
            throw new IOException("Installed APK bytes changed during extraction");
        }
        for (int i = 0; i < list2.size(); i++) {
            if (!list3.get(i).equals(ApkArchive.sha256(list2.get(i)))) {
                throw new IOException("Extracted APK bytes differ from the selected installation");
            }
        }
    }
}
