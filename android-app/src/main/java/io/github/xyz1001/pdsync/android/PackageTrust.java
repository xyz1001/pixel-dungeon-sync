package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.os.Bundle;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/* loaded from: classes.dex */
public final class PackageTrust {
    public static final String ACCESS_BRIDGE = "io.github.xyz1001.pdsync.android.ACCESS_BRIDGE";
    private static final String META = "io.github.xyz1001.pdsync.";

    private PackageTrust() {
    }

    public static PackageInfo installed(Context context, String str) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(str, 134217728 | 128);
    }

    public static String signer(PackageInfo packageInfo) throws Exception {
        if (packageInfo.signingInfo == null || packageInfo.signingInfo.hasMultipleSigners()) {
            throw new SecurityException("应用签名不能确认。");
        }
        Signature[] apkContentsSigners = packageInfo.signingInfo.getApkContentsSigners();
        if (apkContentsSigners == null || apkContentsSigners.length != 1) {
            throw new SecurityException("应用签名不能确认。");
        }
        return hex(MessageDigest.getInstance("SHA-256").digest(apkContentsSigners[0].toByteArray()));
    }

    public static long versionCode(PackageInfo packageInfo) {
        return packageInfo.getLongVersionCode();
    }

    public static void requireBridge(Context context, String str) throws Exception {
        requireBridge(context, SyncRuntime.requirePreparedGame(context), str);
    }

    public static void requireBridge(Context context, GamePreparationService.PreparedPair preparedPair, String str) throws Exception {
        requireBridgeForExchange(context, preparedPair, str);
    }

    static boolean requireBridgeForExchange(Context context, GamePreparationService.PreparedPair preparedPair, String str) throws Exception {
        if (preparedPair == null) {
            throw new SecurityException("尚未选择并准备游戏。");
        }
        boolean z = preparedPair.legacyUnboundHelper;
        if (z) {
            if (!legacyRecoveryIdentityAllowed(legacyRecoveryMarkerPresent(context.getFilesDir()), preparedPair.legacyIdentity, preparedPair.legacyUnboundHelper, preparedPair.gamePackage, preparedPair.helperPackage, preparedPair.sharedUid, preparedPair.authority, preparedPair.originIdentity)) {
                throw new SecurityException("旧版助手只允许处理尚未结束的原有恢复记录。请用保留的原始 APK 重新准备游戏与助手。");
            }
            new GamePreparationService(context).validatePrepared(preparedPair);
        }
        if (str == null || !str.matches("[a-fA-F0-9]{64}")) {
            throw new SecurityException("未找到本机游戏签名密钥。请先准备游戏。");
        }
        PackageManager packageManager = context.getPackageManager();
        PackageInfo packageInfoInstalled = installed(context, preparedPair.helperPackage);
        PackageInfo packageInfoInstalled2 = installed(context, preparedPair.gamePackage);
        PackageInfo packageInfoInstalled3 = installed(context, context.getPackageName());
        if (!signer(packageInfoInstalled).equalsIgnoreCase(str) || !signer(packageInfoInstalled2).equalsIgnoreCase(str)) {
            throw new SecurityException("所选游戏或助手签名与本机密钥不一致，已拒绝存档访问。");
        }
        if (packageInfoInstalled.applicationInfo == null || packageInfoInstalled2.applicationInfo == null || packageInfoInstalled3.applicationInfo == null || packageInfoInstalled.applicationInfo.uid != packageInfoInstalled2.applicationInfo.uid || packageInfoInstalled.applicationInfo.uid == packageInfoInstalled3.applicationInfo.uid || !preparedPair.sharedUid.equals(packageInfoInstalled2.sharedUserId) || !preparedPair.sharedUid.equals(packageInfoInstalled.sharedUserId)) {
            throw new SecurityException("所选游戏和助手尚未建立独立的共享 UID。");
        }
        String[] packagesForUid = packageManager.getPackagesForUid(packageInfoInstalled.applicationInfo.uid);
        if (packagesForUid == null || packagesForUid.length != 2 || !new HashSet(Arrays.asList(packagesForUid)).equals(new HashSet(Arrays.asList(preparedPair.gamePackage, preparedPair.helperPackage)))) {
            throw new SecurityException("共享 UID 中出现了其他应用，已拒绝跨游戏访问。");
        }
        String[] packagesForUid2 = packageManager.getPackagesForUid(packageInfoInstalled3.applicationInfo.uid);
        if (!"io.github.xyz1001.pdsync.android".equals(context.getPackageName()) || packagesForUid2 == null || packagesForUid2.length != 1 || !context.getPackageName().equals(packagesForUid2[0])) {
            throw new SecurityException("同步主应用必须使用独立的 UID。");
        }
        ProviderInfo providerInfoResolveContentProvider = packageManager.resolveContentProvider(preparedPair.authority, 0);
        if (providerInfoResolveContentProvider == null || !preparedPair.helperPackage.equals(providerInfoResolveContentProvider.packageName) || !providerInfoResolveContentProvider.exported || providerInfoResolveContentProvider.grantUriPermissions || !"io.github.xyz1001.pdsync.bridge.SaveBridgeProvider".equals(providerInfoResolveContentProvider.name) || !"io.github.xyz1001.pdsync.android.ACCESS_BRIDGE".equals(providerInfoResolveContentProvider.readPermission) || !"io.github.xyz1001.pdsync.android.ACCESS_BRIDGE".equals(providerInfoResolveContentProvider.writePermission)) {
            throw new SecurityException("存档助手提供方或权限异常，已拒绝连接。");
        }
        if (z) {
            if (preparedPair.signingCertificateSha256.equalsIgnoreCase(str) && apkSetSha256(installedApks(packageInfoInstalled2)).equals(apkSetSha256(preparedPair.gameApks)) && apkSetSha256(installedApks(packageInfoInstalled)).equals(apkSetSha256(Collections.singletonList(preparedPair.helperApk))) && legacyRecoveryMarkerPresent(context.getFilesDir())) {
                return true;
            }
            throw new SecurityException("原有恢复记录或旧版游戏/助手的完整安装内容已变化，已拒绝旧版存档访问。");
        }
        Bundle bundle = packageInfoInstalled.applicationInfo.metaData;
        if (bundle != null && preparedPair.gamePackage.equals(bundle.getString("io.github.xyz1001.pdsync.BOUND_GAME_PACKAGE")) && preparedPair.originIdentity.equals(bundle.getString("io.github.xyz1001.pdsync.BOUND_ORIGIN_IDENTITY")) && preparedPair.sharedUid.equals(bundle.getString("io.github.xyz1001.pdsync.BOUND_SHARED_USER_ID")) && preparedPair.sourceApkSetSha256.equals(bundle.getString("io.github.xyz1001.pdsync.BOUND_SOURCE_APK_SHA256"))) {
            return false;
        }
        throw new SecurityException("助手绑定的游戏来源与当前选择不一致，请完成本次助手安装。");
    }

    static boolean legacyRecoveryIdentityAllowed(boolean z, boolean z2, boolean z3, String str, String str2, String str3, String str4, String str5) {
        return z && z2 && z3 && "com.shatteredpixel.shatteredpixeldungeon".equals(str) && "io.github.xyz1001.pdsync.bridge".equals(str2) && "io.github.xyz1001.pdsync.shared".equals(str3) && "io.github.xyz1001.pdsync.bridge.saves".equals(str4) && "32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89".equals(str5);
    }

    static boolean legacyRecoveryMarkerPresent(File file) throws IOException {
        File file2 = new File(file.getCanonicalFile(), "sync");
        if (!file2.getCanonicalFile().equals(file2.getAbsoluteFile())) {
            return false;
        }
        File file3 = new File(file2, "android-restore.json");
        return Files.isRegularFile(file3.toPath(), LinkOption.NOFOLLOW_LINKS) && file3.length() > 0 && file3.length() <= 16777216;
    }

    public static List<File> installedApks(PackageInfo packageInfo) throws IOException {
        if (packageInfo.applicationInfo == null || packageInfo.applicationInfo.sourceDir == null) {
            throw new IOException("无法读取已安装 APK 位置。");
        }
        ArrayList arrayList = new ArrayList();
        arrayList.add(new File(packageInfo.applicationInfo.sourceDir));
        if (packageInfo.applicationInfo.splitSourceDirs != null) {
            for (String str : packageInfo.applicationInfo.splitSourceDirs) {
                if (str == null) {
                    throw new IOException("安装的分包位置缺失。");
                }
                arrayList.add(new File(str));
            }
        }
        return arrayList;
    }

    public static String apkSetSha256(List<File> list) throws Exception {
        if (list == null || list.isEmpty()) {
            throw new IOException("APK 集合为空。");
        }
        ArrayList arrayList = new ArrayList();
        Iterator<File> it = list.iterator();
        while (it.hasNext()) {
            arrayList.add(sha256(it.next()));
        }
        return ApkSetIdentity.digest(arrayList);
    }

    public static String sha256(File file) throws Exception {
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        FileInputStream fileInputStream = new FileInputStream(file);
        try {
            byte[] bArr = new byte[65536];
            while (true) {
                int i = fileInputStream.read(bArr);
                if (i == -1) {
                    fileInputStream.close();
                    return hex(messageDigest.digest());
                }
                messageDigest.update(bArr, 0, i);
            }
        } catch (Throwable th) {
            try {
                fileInputStream.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    public static String hex(byte[] bArr) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bArr) {
            sb.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & 255)));
        }
        return sb.toString();
    }
}
