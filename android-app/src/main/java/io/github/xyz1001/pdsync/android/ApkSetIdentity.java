package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/* loaded from: classes.dex */
public final class ApkSetIdentity {
    private ApkSetIdentity() {
    }

    public static String digest(List<String> list) {
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException(AndroidMessages.APK_SET_IDENTITY_01.text());
        }
        ArrayList<String> arrayList = new ArrayList(list);
        for (String str : arrayList) {
            if (str == null || !str.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(AndroidMessages.APK_SET_IDENTITY_02.text());
            }
        }
        Collections.sort(arrayList);
        try {
            StringBuilder sb = new StringBuilder("PDSYNC-INSTALLED-APK-SET-V1\n");
            sb.append(arrayList.size());
            sb.append('\n');
            Iterator it = arrayList.iterator();
            while (it.hasNext()) {
                sb.append((String) it.next());
                sb.append('\n');
            }
            byte[] bArrDigest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb2 = new StringBuilder();
            for (byte b : bArrDigest) {
                sb2.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & 255)));
            }
            return sb2.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
