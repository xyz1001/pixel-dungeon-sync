package io.github.xyz1001.pdsync.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;


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

    static void requirePinnedSigner(String str, byte[][] bArr) {
        String strTrim = str != null ? str.replace(":", "").toLowerCase(Locale.ROOT).trim() : "";
        if (!strTrim.matches("[0-9a-f]{64}")) {
            throw new SecurityException("Main certificate pin is not configured");
        }
        if (bArr != null && bArr.length == 1) {
            byte[] bArr2 = bArr[0];
            if (bArr2 != null && bArr2.length != 0) {
                try {
                    byte[] bArrDigest = MessageDigest.getInstance("SHA-256").digest(bArr[0]);
                    StringBuilder sb = new StringBuilder(64);
                    for (byte b : bArrDigest) {
                        sb.append(Character.forDigit((b >>> 4) & 15, 16));
                        sb.append(Character.forDigit(b & 15, 16));
                    }
                    if (MessageDigest.isEqual(strTrim.getBytes(StandardCharsets.US_ASCII), sb.toString().getBytes(StandardCharsets.US_ASCII))) {
                        return;
                    } else {
                        throw new SecurityException("Main app certificate pin mismatch");
                    }
                } catch (NoSuchAlgorithmException e) {
                    throw new SecurityException("Cannot authenticate main app", e);
                }
            }
        }
        throw new SecurityException("Main app certificate pin mismatch");
    }
}
