package io.github.xyz1001.pdsync.bridge;

final class MainCallerPolicy {
    static final String MAIN = "io.github.xyz1001.pdsync.android";
    static final String PERMISSION = "io.github.xyz1001.pdsync.android.ACCESS_BRIDGE";

    private MainCallerPolicy() {
    }

    static void requirePackage(String str) {
        if (!"io.github.xyz1001.pdsync.android".equals(str)) {
            throw new SecurityException("Unexpected caller package");
        }
    }

    static void requireIdentity(String str, int i, int i2, int i3, String[] strArr) {
        requirePackage(str);
        if (i < 0 || i == i2) {
            throw new SecurityException("Shared-UID callers are never authorized");
        }
        if (strArr == null || strArr.length != 1 || !"io.github.xyz1001.pdsync.android".equals(strArr[0])) {
            throw new SecurityException("Main app must use an independent UID");
        }
        if (i3 != i) {
            throw new SecurityException("Main caller UID mismatch");
        }
    }

}
