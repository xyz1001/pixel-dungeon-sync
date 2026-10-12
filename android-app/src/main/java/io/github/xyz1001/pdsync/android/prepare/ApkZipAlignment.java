package io.github.xyz1001.pdsync.android.prepare;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ApkZipAlignment {
    private ApkZipAlignment() {
    }

    public static int verify(File file) throws IOException {
        RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
        try {
            long length = randomAccessFile.length();
            if (length < 22 || length > 2147483647L) {
                throw new IOException("Invalid APK ZIP length");
            }
            int iMin = (int) Math.min(length, 65557L);
            byte[] bArr = new byte[iMin];
            long j = length - iMin;
            randomAccessFile.seek(j);
            randomAccessFile.readFully(bArr);
            int i = iMin - 22;
            while (true) {
                if (i < 0) {
                    i = -1;
                    break;
                }
                if (u32(bArr, i) == 101010256 && i + 22 + u16(bArr, i + 20) == iMin) {
                    break;
                }
                i--;
            }
            if (i >= 0 && u16(bArr, i + 4) == 0 && u16(bArr, i + 6) == 0) {
                int i2 = i + 10;
                if (u16(bArr, i + 8) == u16(bArr, i2)) {
                    int iU16 = u16(bArr, i2);
                    long jU32 = u32(bArr, i + 12);
                    long jU322 = u32(bArr, i + 16);
                    long j2 = j + i;
                    if (iU16 < 1 || iU16 > 20000 || jU32 + jU322 != j2) {
                        throw new IOException("Unsupported APK ZIP directory layout");
                    }
                    int i3 = 0;
                    int i4 = 0;
                    int i5 = 0;
                    long j3 = jU322;
                    while (i4 < iU16) {
                        long j4 = j3 + 46;
                        if (j4 > j2) {
                            throw new IOException("Truncated ZIP central directory");
                        }
                        byte[] bArr2 = new byte[46];
                        randomAccessFile.seek(j3);
                        randomAccessFile.readFully(bArr2);
                        if (u32(bArr2, i3) != 33639248) {
                            throw new IOException("Invalid ZIP central entry");
                        }
                        int iU162 = u16(bArr2, 8);
                        int iU163 = u16(bArr2, 10);
                        int i6 = iU16;
                        int iU164 = u16(bArr2, 28);
                        long j5 = j2;
                        int iU165 = u16(bArr2, 30);
                        int iU166 = u16(bArr2, 32);
                        int i7 = i4;
                        long jU323 = u32(bArr2, 42);
                        long j6 = j3;
                        long jU324 = u32(bArr2, 20);
                        if ((iU162 & 1) == 0 && u16(bArr2, 34) == 0 && iU164 != 0) {
                            long j7 = iU164;
                            long j8 = j4 + j7;
                            long j9 = iU165;
                            long j10 = iU166;
                            if (j8 + j9 + j10 <= j5) {
                                long j11 = 30 + jU323;
                                if (j11 <= jU322) {
                                    byte[] bArr3 = new byte[iU164];
                                    randomAccessFile.readFully(bArr3);
                                    byte[] bArr4 = new byte[30];
                                    randomAccessFile.seek(jU323);
                                    randomAccessFile.readFully(bArr4);
                                    if (u32(bArr4, 0) != 67324752 || u16(bArr4, 6) != iU162 || u16(bArr4, 8) != iU163 || u16(bArr4, 26) != iU164) {
                                        throw new IOException("ZIP local/central metadata mismatch");
                                    }
                                    long jU16 = j11 + j7 + u16(bArr4, 28);
                                    if (jU16 + jU324 > jU322) {
                                        throw new IOException("ZIP entry crosses APK directory");
                                    }
                                    byte[] bArr5 = new byte[iU164];
                                    randomAccessFile.readFully(bArr5);
                                    if (!Arrays.equals(bArr5, bArr3)) {
                                        throw new IOException("ZIP local/central name mismatch");
                                    }
                                    if (iU163 == 0) {
                                        String str = new String(bArr3, StandardCharsets.UTF_8);
                                        int i8 = (str.startsWith("lib/") && str.endsWith(".so")) ? 16384 : 4;
                                        if (jU16 % i8 != 0) {
                                            throw new IOException("Unaligned APK entry: " + str + " requires " + i8 + "-byte alignment");
                                        }
                                        i5++;
                                    }
                                    j3 = j6 + j7 + 46 + j9 + j10;
                                    i4 = i7 + 1;
                                    i3 = 0;
                                    iU16 = i6;
                                    j2 = j5;
                                    continue;
                                }
                            }
                        }
                        throw new IOException("Unsupported/invalid APK ZIP entry");
                    }
                    if (j3 != j2) {
                        throw new IOException("ZIP directory entry count mismatch");
                    }
                    randomAccessFile.close();
                    return i5;
                }
            }
            throw new IOException("Unsupported multipart/invalid APK ZIP");
        } catch (Throwable th) {
            try { randomAccessFile.close(); } catch (Throwable closing) { th.addSuppressed(closing); }
            throw th;
        }
    }

    private static int u16(byte[] bArr, int i) {
        return ((bArr[i + 1] & 255) << 8) | (bArr[i] & 255);
    }

    private static long u32(byte[] bArr, int i) {
        return ((bArr[i + 3] & 255L) << 24) | (bArr[i] & 255) | ((bArr[i + 1] & 255) << 8) | ((bArr[i + 2] & 255) << 16);
    }
}
