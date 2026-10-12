package io.github.xyz1001.pdsync.android.prepare;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

final class LegacyManifestPatch {
    public static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    public static final String GAME_PACKAGE = "com.shatteredpixel.shatteredpixeldungeon";
    public static final String HELPER_PACKAGE = "io.github.xyz1001.pdsync.bridge";
    private static final long NONE = 4294967295L;
    public static final String SHARED_UID = "io.github.xyz1001.pdsync.shared";
    public static final int VERSION_CODE = 896;
    public static final String VERSION_NAME = "3.3.8";

    private LegacyManifestPatch() {
    }

    public static final class ManifestInfo {
        public final boolean hasSharedUserMaxSdkVersion;
        public final String packageName;
        public final String sharedUserId;
        public final int targetSandboxVersion;
        public final long versionCode;
        public final String versionName;

        ManifestInfo(List<Attribute> list, List<String> list2) throws IOException {
            this.packageName = LegacyManifestPatch.stringValue(LegacyManifestPatch.find(list, null, "package"), list2, null);
            this.sharedUserId = LegacyManifestPatch.stringValue(LegacyManifestPatch.find(list, "http://schemas.android.com/apk/res/android", "sharedUserId"), list2, null);
            this.versionName = LegacyManifestPatch.stringValue(LegacyManifestPatch.find(list, "http://schemas.android.com/apk/res/android", "versionName"), list2, "");
            this.versionCode = LegacyManifestPatch.intValue(LegacyManifestPatch.find(list, "http://schemas.android.com/apk/res/android", "versionCode"), 0L);
            this.targetSandboxVersion = (int) LegacyManifestPatch.intValue(LegacyManifestPatch.find(list, "http://schemas.android.com/apk/res/android", "targetSandboxVersion"), 1L);
            this.hasSharedUserMaxSdkVersion = LegacyManifestPatch.find(list, "http://schemas.android.com/apk/res/android", "sharedUserMaxSdkVersion") != null;
        }
    }

    public static ManifestInfo inspect(byte[] bArr) throws IOException {
        Document document = new Document(bArr);
        return new ManifestInfo(document.attributes, document.strings);
    }

    public static byte[] patch(byte[] bArr) throws IOException {
        int i;
        Document document = new Document(bArr);
        ManifestInfo manifestInfo = new ManifestInfo(document.attributes, document.strings);
        require("com.shatteredpixel.shatteredpixeldungeon".equals(manifestInfo.packageName), "Unexpected game package");
        require(896 == manifestInfo.versionCode && "3.3.8".equals(manifestInfo.versionName), "Only Shattered 3.3.8 (896) is supported");
        require(manifestInfo.sharedUserId == null && !manifestInfo.hasSharedUserMaxSdkVersion, "Expected original independent-UID manifest");
        require(manifestInfo.targetSandboxVersion == 2, "Expected original targetSandboxVersion=2");
        Attribute attributeFind = find(document.attributes, "http://schemas.android.com/apk/res/android", "targetSandboxVersion");
        require(attributeFind != null, "Missing explicit original sandbox attribute");
        byte[] bArrAppendStrings = appendStrings(document.parts.get(document.pool), Arrays.asList("sharedUserId", "io.github.xyz1001.pdsync.shared"));
        List<String> listStrings = strings(bArrAppendStrings);
        int iIndexOf = listStrings.indexOf("sharedUserId");
        int iIndexOf2 = listStrings.indexOf("io.github.xyz1001.pdsync.shared");
        int iIndexOf3 = listStrings.indexOf("http://schemas.android.com/apk/res/android");
        require(iIndexOf3 >= 0, "Missing Android namespace");
        byte[] bArr2 = document.parts.get(document.map);
        require(u16(bArr2, 2) == 8 && (bArr2.length - 8) % 4 == 0, "Malformed resource map");
        int length = (bArr2.length - 8) / 4;
        require(length <= listStrings.size(), "Invalid resource-map count");
        final byte[] bArrCopyOf = Arrays.copyOf(bArr2, (Math.max(length, iIndexOf + 1) * 4) + 8);
        int i2 = (iIndexOf * 4) + 8;
        long jU32 = u32(bArrCopyOf, i2);
        require(jU32 == 0 || jU32 == 16842763, "Conflicting sharedUserId resource ID");
        put32(bArrCopyOf, 4, bArrCopyOf.length);
        put32(bArrCopyOf, i2, 16842763L);
        ArrayList arrayList = new ArrayList();
        for (Attribute attribute : document.attributes) {
            byte[] bArr3 = (byte[]) attribute.bytes.clone();
            if (attribute == attributeFind) {
                i = iIndexOf3;
                put32(bArr3, 8, 4294967295L);
                put32(bArr3, 16, 1L);
            } else {
                i = iIndexOf3;
            }
            arrayList.add(bArr3);
            iIndexOf3 = i;
        }
        byte[] bArr4 = new byte[20];
        put32(bArr4, 0, iIndexOf3);
        put32(bArr4, 4, iIndexOf);
        long j = iIndexOf2;
        put32(bArr4, 8, j);
        put16(bArr4, 12, 8);
        bArr4[15] = 3;
        put32(bArr4, 16, j);
        arrayList.add(bArr4);
        arrayList.sort(Comparator.comparingLong(new ToLongFunction() { // from class: io.github.xyz1001.pdsync.android.prepare.LegacyManifestPatch$$ExternalSyntheticLambda0
            @Override // java.util.function.ToLongFunction
            public final long applyAsLong(Object obj) {
                return LegacyManifestPatch.resourceId(bArrCopyOf, (byte[]) obj);
            }
        }));
        byte[] bArrCopyOf2 = Arrays.copyOf(document.parts.get(document.root), (arrayList.size() * 20) + 36);
        put32(bArrCopyOf2, 4, bArrCopyOf2.length);
        put16(bArrCopyOf2, 28, arrayList.size());
        for (int i3 = 0; i3 < arrayList.size(); i3++) {
            System.arraycopy(arrayList.get(i3), 0, bArrCopyOf2, (i3 * 20) + 36, 20);
        }
        document.parts.set(document.pool, bArrAppendStrings);
        document.parts.set(document.map, bArrCopyOf);
        document.parts.set(document.root, bArrCopyOf2);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(new byte[8]);
        Iterator<byte[]> it = document.parts.iterator();
        while (it.hasNext()) {
            byteArrayOutputStream.write(it.next());
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        put16(byteArray, 0, 3);
        put16(byteArray, 2, 8);
        put32(byteArray, 4, byteArray.length);
        ManifestInfo manifestInfoInspect = inspect(byteArray);
        require("io.github.xyz1001.pdsync.shared".equals(manifestInfoInspect.sharedUserId) && manifestInfoInspect.targetSandboxVersion == 1, "Patch self-check failed");
        return byteArray;
    }

    public static long resourceId(byte[] bArr, byte[] bArr2) {
        int iUnchecked32 = (int) unchecked32(bArr2, 4);
        long jUnchecked32 = (iUnchecked32 < 0 || (((long) iUnchecked32) * 4) + 12 > ((long) bArr.length)) ? 0L : unchecked32(bArr, (iUnchecked32 * 4) + 8);
        if (jUnchecked32 == 0) {
            return 4294967295L;
        }
        return jUnchecked32;
    }

    private static final class Document {
        final List<Attribute> attributes;
        int map;
        final List<byte[]> parts = new ArrayList();
        int pool;
        int root;
        final List<String> strings;

        Document(byte[] bArr) throws IOException {
            int i;
            int i2;
            this.pool = -1;
            this.map = -1;
            this.root = -1;
            LegacyManifestPatch.require(bArr != null && bArr.length >= 8 && bArr.length <= 4194304, "Invalid binary XML length");
            LegacyManifestPatch.require(LegacyManifestPatch.u16(bArr, 0) == 3 && LegacyManifestPatch.u16(bArr, 2) == 8 && LegacyManifestPatch.u32(bArr, 4) == ((long) bArr.length), "Not complete Android binary XML");
            int i3 = 8;
            while (i3 < bArr.length) {
                LegacyManifestPatch.require(i3 + 8 <= bArr.length, "Truncated binary XML chunk");
                int iU16 = LegacyManifestPatch.u16(bArr, i3);
                int iU162 = LegacyManifestPatch.u16(bArr, i3 + 2);
                long jU32 = LegacyManifestPatch.u32(bArr, i3 + 4);
                LegacyManifestPatch.require(iU162 >= 8 && jU32 >= ((long) iU162) && jU32 <= ((long) (bArr.length - i3)), "Invalid binary XML chunk bounds");
                int size = this.parts.size();
                if (iU16 == 1) {
                    LegacyManifestPatch.require(this.pool < 0, "Duplicate string pool");
                    this.pool = size;
                }
                if (iU16 == 384) {
                    LegacyManifestPatch.require(this.map < 0, "Duplicate resource map");
                    this.map = size;
                }
                if (iU16 == 258 && this.root < 0) {
                    this.root = size;
                }
                int i4 = ((int) jU32) + i3;
                this.parts.add(Arrays.copyOfRange(bArr, i3, i4));
                i3 = i4;
            }
            int i5 = this.pool;
            LegacyManifestPatch.require(i5 >= 0 && (i = this.map) >= 0 && (i2 = this.root) >= 0 && i5 < i2 && i < i2, "Unfamiliar manifest structure");
            List<String> listStrings = LegacyManifestPatch.strings(this.parts.get(this.pool));
            this.strings = listStrings;
            this.attributes = LegacyManifestPatch.attributes(this.parts.get(this.root), listStrings);
        }
    }

    private static final class Attribute {
        final byte[] bytes;
        final String name;
        final String namespace;

        Attribute(String str, String str2, byte[] bArr) {
            this.namespace = str;
            this.name = str2;
            this.bytes = bArr;
        }
    }

    public static Attribute find(List<Attribute> list, String str, String str2) {
        for (Attribute attribute : list) {
            if (Objects.equals(attribute.namespace, str) && attribute.name.equals(str2)) {
                return attribute;
            }
        }
        return null;
    }

    public static String stringValue(Attribute attribute, List<String> list, String str) throws IOException {
        if (attribute == null) {
            return str;
        }
        require((attribute.bytes[15] & 255) == 3, "Expected string attribute: " + attribute.name);
        return stringAt(list, u32(attribute.bytes, 16));
    }

    public static long intValue(Attribute attribute, long j) throws IOException {
        if (attribute == null) {
            return j;
        }
        require(attribute.bytes[15] == 16 || attribute.bytes[15] == 17, "Expected integer attribute: " + attribute.name);
        return u32(attribute.bytes, 16);
    }

    public static List<Attribute> attributes(byte[] bArr, List<String> list) throws IOException {
        require(bArr.length >= 36 && u16(bArr, 2) == 16 && "manifest".equals(stringAt(list, u32(bArr, 20))), "Expected manifest root");
        int iU16 = u16(bArr, 28);
        require(u16(bArr, 24) == 20 && u16(bArr, 26) == 20 && (((long) iU16) * 20) + 36 == ((long) bArr.length), "Unsupported root attribute layout");
        for (int i = 30; i < 36; i++) {
            require(bArr[i] == 0, "Unsupported special root attribute");
        }
        ArrayList arrayList = new ArrayList();
        HashSet hashSet = new HashSet();
        for (int i2 = 0; i2 < iU16; i2++) {
            int i3 = i2 * 20;
            byte[] bArrCopyOfRange = Arrays.copyOfRange(bArr, i3 + 36, i3 + 56);
            require(u16(bArrCopyOfRange, 12) == 8 && bArrCopyOfRange[14] == 0, "Invalid typed attribute");
            long jU32 = u32(bArrCopyOfRange, 0);
            String strStringAt = jU32 == 4294967295L ? null : stringAt(list, jU32);
            String strStringAt2 = stringAt(list, u32(bArrCopyOfRange, 4));
            require(hashSet.add(String.valueOf(strStringAt) + "\u0000" + strStringAt2), "Duplicate manifest attribute");
            if (u32(bArrCopyOfRange, 8) != 4294967295L) {
                stringAt(list, u32(bArrCopyOfRange, 8));
            }
            arrayList.add(new Attribute(strStringAt, strStringAt2, bArrCopyOfRange));
        }
        return arrayList;
    }

    public static List<String> strings(byte[] bArr) throws IOException {
        Charset charset;
        int i = 2;
        require(bArr.length >= 28 && u16(bArr, 2) == 28 && u32(bArr, 12) == 0 && u32(bArr, 24) == 0, "Styled/unfamiliar string pool unsupported");
        long jU32 = u32(bArr, 8);
        long jU322 = u32(bArr, 20);
        require(jU32 <= 10000 && jU322 >= (4 * jU32) + 28 && jU322 <= ((long) bArr.length), "Invalid string pool");
        int i2 = (int) jU32;
        int i3 = (int) jU322;
        boolean z = (u32(bArr, 16) & 256) != 0;
        ArrayList arrayList = new ArrayList();
        int i4 = 0;
        while (i4 < i2) {
            long jU323 = u32(bArr, (i4 * 4) + 28);
            require(jU323 < ((long) (bArr.length - i3)), "Invalid string offset");
            int[] iArr = {((int) jU323) + i3};
            long length = length(bArr, iArr, z);
            long length2 = z ? length(bArr, iArr, true) : 2 * length;
            boolean z2 = z;
            require(length2 <= ((long) ((bArr.length - iArr[0]) - (z ? 1 : i))), "Truncated pool string");
            int i5 = iArr[0];
            int i6 = (int) length2;
            int i7 = i5 + i6;
            require(bArr[i7] == 0 && (z2 || bArr[i7 + 1] == 0), "Unterminated pool string");
            charset = z2 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE;
            String string;
            try {
                string = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bArr, i5, i6)).toString();
            } catch (CharacterCodingException e) { throw new IOException("Invalid pool string encoding", e); }
            require(((long) string.length()) == length, "String character length mismatch");
            arrayList.add(string);
            i4++;
            z = z2;
            i = 2;
        }
        return arrayList;
    }

    private static long length(byte[] bArr, int[] iArr, boolean z) throws IOException {
        int i = iArr[0];
        if (z) {
            bounds(bArr, i, 1);
            int i2 = i + 1;
            byte b = bArr[i];
            int i3 = b & 255;
            if ((b & 128) != 0) {
                bounds(bArr, i2, 1);
                i3 = ((b & 127) << 8) | (bArr[i2] & 255);
                i2 = i + 2;
            }
            iArr[0] = i2;
            return i3;
        }
        long jU16 = u16(bArr, i);
        int i4 = i + 2;
        if ((32768 & jU16) != 0) {
            jU16 = ((jU16 & 32767) << 16) | u16(bArr, i4);
            i4 = i + 4;
        }
        iArr[0] = i4;
        return jU16;
    }

    private static byte[] appendStrings(byte[] bArr, List<String> list) throws IOException {
        List<String> listStrings = strings(bArr);
        int size = listStrings.size();
        int iU32 = (int) u32(bArr, 20);
        boolean z = (u32(bArr, 16) & 256) != 0;
        ArrayList arrayList = new ArrayList();
        for (int i = 0; i < size; i++) {
            arrayList.add(Long.valueOf(u32(bArr, (i * 4) + 28)));
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byteArrayOutputStream.write(bArr, iU32, bArr.length - iU32);
        for (String str : list) {
            if (!listStrings.contains(str)) {
                arrayList.add(Long.valueOf(byteArrayOutputStream.size()));
                byte[] bytes = str.getBytes(z ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
                require(str.length() <= 127 && (!z || bytes.length <= 127), "Added string too long");
                byteArrayOutputStream.write(str.length());
                byteArrayOutputStream.write(z ? bytes.length : 0);
                byteArrayOutputStream.write(bytes);
                byteArrayOutputStream.write(0);
                if (!z) {
                    byteArrayOutputStream.write(0);
                }
                listStrings.add(str);
            }
        }
        while (byteArrayOutputStream.size() % 4 != 0) {
            byteArrayOutputStream.write(0);
        }
        int size2 = (listStrings.size() * 4) + 28;
        int size3 = byteArrayOutputStream.size() + size2;
        byte[] bArr2 = new byte[size3];
        put16(bArr2, 0, 1);
        put16(bArr2, 2, 28);
        put32(bArr2, 4, size3);
        put32(bArr2, 8, listStrings.size());
        put32(bArr2, 16, u32(bArr, 16) & (-2));
        put32(bArr2, 20, size2);
        for (int i2 = 0; i2 < arrayList.size(); i2++) {
            put32(bArr2, (i2 * 4) + 28, ((Long) arrayList.get(i2)).longValue());
        }
        System.arraycopy(byteArrayOutputStream.toByteArray(), 0, bArr2, size2, byteArrayOutputStream.size());
        return bArr2;
    }

    private static String stringAt(List<String> list, long j) throws IOException {
        require(j >= 0 && j < ((long) list.size()), "Invalid string index");
        return list.get((int) j);
    }

    public static int u16(byte[] bArr, int i) throws IOException {
        bounds(bArr, i, 2);
        return ((bArr[i + 1] & 255) << 8) | (bArr[i] & 255);
    }

    public static long u32(byte[] bArr, int i) throws IOException {
        bounds(bArr, i, 4);
        return unchecked32(bArr, i);
    }

    private static long unchecked32(byte[] bArr, int i) {
        return ((bArr[i + 3] & 255L) << 24) | (bArr[i] & 255) | ((bArr[i + 1] & 255) << 8) | ((bArr[i + 2] & 255) << 16);
    }

    private static void put16(byte[] bArr, int i, int i2) {
        bArr[i] = (byte) i2;
        bArr[i + 1] = (byte) (i2 >>> 8);
    }

    private static void put32(byte[] bArr, int i, long j) {
        for (int i2 = 0; i2 < 4; i2++) {
            bArr[i + i2] = (byte) (j >>> (i2 * 8));
        }
    }

    private static void bounds(byte[] bArr, int i, int i2) throws IOException {
        require(i >= 0 && i <= bArr.length - i2, "Truncated binary XML");
    }

    public static void require(boolean z, String str) throws IOException {
        if (!z) {
            throw new IOException(str);
        }
    }
}
