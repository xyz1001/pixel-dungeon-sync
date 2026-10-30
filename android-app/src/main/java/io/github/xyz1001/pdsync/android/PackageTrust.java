package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.os.Build;
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
        return context.getPackageManager().getPackageInfo(str, signingFlags() | PackageManager.GET_META_DATA);
    }

    /** Request current APK signers using the platform API available on this device. */
    @SuppressWarnings("deprecation")
    public static int signingFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    /** Never use certificate history as a substitute for the currently installed APK signer. */
    @SuppressWarnings("deprecation")
    public static Signature[] currentSigners(PackageInfo packageInfo) {
        if (packageInfo == null) throw new SecurityException(AndroidMessages.PACKAGE_TRUST_01.text());
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? Api28.currentSigners(packageInfo) : packageInfo.signatures;
        if (signatures == null || signatures.length == 0) throw new SecurityException(AndroidMessages.PACKAGE_TRUST_01.text());
        for (Signature signature : signatures) {
            if (signature == null) throw new SecurityException(AndroidMessages.PACKAGE_TRUST_01.text());
        }
        return signatures.clone();
    }

    public static String signer(PackageInfo packageInfo) throws Exception {
        Signature[] apkContentsSigners = currentSigners(packageInfo);
        if (apkContentsSigners.length != 1 || (Build.VERSION.SDK_INT >= 28 && Api28.hasMultipleSigners(packageInfo))) {
            throw new SecurityException(AndroidMessages.PACKAGE_TRUST_01.text());
        }
        return hex(MessageDigest.getInstance("SHA-256").digest(apkContentsSigners[0].toByteArray()));
    }

    @SuppressWarnings("deprecation")
    public static long versionCode(PackageInfo packageInfo) {
        return Build.VERSION.SDK_INT >= 28 ? Api28.versionCode(packageInfo) : (packageInfo.versionCode & 0xffffffffL);
    }

    /** Isolate new framework references so API 26 never resolves SigningInfo. */
    @android.annotation.TargetApi(28)
    private static final class Api28 {
        static Signature[] currentSigners(PackageInfo info) {
            return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        }

        static boolean hasMultipleSigners(PackageInfo info) {
            return info.signingInfo.hasMultipleSigners();
        }

        static long versionCode(PackageInfo info) {
            return info.getLongVersionCode();
        }
    }

    public static void requireBridge(Context context, String str) throws Exception {
        requireBridge(context, SyncRuntime.requirePreparedGame(context), str);
    }

    public static void requireBridge(Context context, GamePreparationService.PreparedPair preparedPair, String str) throws Exception {
        requireBridgeForExchange(context, preparedPair, str);
    }

    static boolean requireBridgeForExchange(Context context, GamePreparationService.PreparedPair preparedPair, String str) throws Exception {
        if (preparedPair == null) {
            throw new SecurityException(AndroidMessages.PACKAGE_TRUST_02.text());
        }
        if (preparedPair.embeddedBridge()) {
            requireEmbeddedBridge(context, preparedPair, str);
            return false;
        }
        boolean z = preparedPair.legacyUnboundHelper;
        if (z) {
            if (!legacyRecoveryIdentityAllowed(legacyRecoveryMarkerPresent(context.getFilesDir()), preparedPair.legacyIdentity, preparedPair.legacyUnboundHelper, preparedPair.gamePackage, preparedPair.helperPackage, preparedPair.sharedUid, preparedPair.authority, preparedPair.originIdentity)) {
                throw new SecurityException(AndroidMessages.PACKAGE_TRUST_03.text());
            }
            new GamePreparationService(context).validatePrepared(preparedPair);
        }
        if (str == null || !str.matches("[a-fA-F0-9]{64}")) {
            throw new SecurityException(AndroidMessages.PACKAGE_TRUST_04.text());
        }
        PackageManager packageManager = context.getPackageManager();
        PackageInfo packageInfoInstalled = installed(context, preparedPair.helperPackage);
        PackageInfo packageInfoInstalled2 = installed(context, preparedPair.gamePackage);
        PackageInfo packageInfoInstalled3 = installed(context, context.getPackageName());
        if (!signer(packageInfoInstalled).equalsIgnoreCase(str) || !signer(packageInfoInstalled2).equalsIgnoreCase(str)) {
            throw new BridgeDiagnostics.TrustFailure("LOCAL_SIGNER_MISMATCH", AndroidMessages.PACKAGE_TRUST_05);
        }
        if (packageInfoInstalled.applicationInfo == null || packageInfoInstalled2.applicationInfo == null || packageInfoInstalled3.applicationInfo == null || packageInfoInstalled.applicationInfo.uid != packageInfoInstalled2.applicationInfo.uid || packageInfoInstalled.applicationInfo.uid == packageInfoInstalled3.applicationInfo.uid || !preparedPair.sharedUid.equals(packageInfoInstalled2.sharedUserId) || !preparedPair.sharedUid.equals(packageInfoInstalled.sharedUserId)) {
            throw new BridgeDiagnostics.TrustFailure("SHARED_UID_MISMATCH", AndroidMessages.PACKAGE_TRUST_06);
        }
        String[] packagesForUid = packageManager.getPackagesForUid(packageInfoInstalled.applicationInfo.uid);
        if (packagesForUid == null || packagesForUid.length != 2 || !new HashSet(Arrays.asList(packagesForUid)).equals(new HashSet(Arrays.asList(preparedPair.gamePackage, preparedPair.helperPackage)))) {
            throw new BridgeDiagnostics.TrustFailure("SHARED_UID_MEMBERS_MISMATCH", AndroidMessages.PACKAGE_TRUST_07);
        }
        String[] packagesForUid2 = packageManager.getPackagesForUid(packageInfoInstalled3.applicationInfo.uid);
        if (!"io.github.xyz1001.pdsync.android".equals(context.getPackageName()) || packagesForUid2 == null || packagesForUid2.length != 1 || !context.getPackageName().equals(packagesForUid2[0])) {
            throw new SecurityException(AndroidMessages.PACKAGE_TRUST_08.text());
        }
        ProviderInfo providerInfoResolveContentProvider = packageManager.resolveContentProvider(preparedPair.authority, 0);
        if (providerInfoResolveContentProvider == null || !preparedPair.helperPackage.equals(providerInfoResolveContentProvider.packageName) || !providerInfoResolveContentProvider.exported || providerInfoResolveContentProvider.grantUriPermissions || !"io.github.xyz1001.pdsync.bridge.SaveBridgeProvider".equals(providerInfoResolveContentProvider.name) || !"io.github.xyz1001.pdsync.android.ACCESS_BRIDGE".equals(providerInfoResolveContentProvider.readPermission) || !"io.github.xyz1001.pdsync.android.ACCESS_BRIDGE".equals(providerInfoResolveContentProvider.writePermission)) {
            throw new BridgeDiagnostics.TrustFailure("PROVIDER_IDENTITY_OR_PERMISSION_INVALID", AndroidMessages.PACKAGE_TRUST_09);
        }
        if (z) {
            if (preparedPair.signingCertificateSha256.equalsIgnoreCase(str) && apkSetSha256(installedApks(packageInfoInstalled2)).equals(apkSetSha256(preparedPair.gameApks)) && apkSetSha256(installedApks(packageInfoInstalled)).equals(apkSetSha256(Collections.singletonList(preparedPair.helperApk))) && legacyRecoveryMarkerPresent(context.getFilesDir())) {
                return true;
            }
            throw new SecurityException(AndroidMessages.PACKAGE_TRUST_10.text());
        }
        Bundle bundle = packageInfoInstalled.applicationInfo.metaData;
        if (bundle != null && preparedPair.gamePackage.equals(bundle.getString("io.github.xyz1001.pdsync.BOUND_GAME_PACKAGE")) && preparedPair.originIdentity.equals(bundle.getString("io.github.xyz1001.pdsync.BOUND_ORIGIN_IDENTITY")) && preparedPair.sharedUid.equals(bundle.getString("io.github.xyz1001.pdsync.BOUND_SHARED_USER_ID")) && preparedPair.sourceApkSetSha256.equals(bundle.getString("io.github.xyz1001.pdsync.BOUND_SOURCE_APK_SHA256"))) {
            return false;
        }
        throw new BridgeDiagnostics.TrustFailure("HELPER_BINDING_MISMATCH", AndroidMessages.PACKAGE_TRUST_11);
    }

    /** First-page metadata gate: deliberately excludes receipt/APK hashing and key access. */
    static boolean requireBridgeForAccess(Context context, GamePreparationService.PreparedPair pair) throws Exception {
        if (pair == null || !pair.embeddedBridge())
            throw new SecurityException(AndroidMessages.BRIDGE_CLIENT_04.text());
        InstalledBridgeBinding binding = InstalledBridgeResolver.resolve(context, pair.gamePackage);
        if (binding == null || !pair.gamePackage.equals(pair.bridgeHostPackage)
                || !pair.authority.equals(binding.authority) || !pair.originIdentity.equals(binding.originIdentity)
                || !pair.namespaceId.equals(binding.namespaceId)
                || !pair.sourceApkSetSha256.equals(binding.sourceApkSetSha256)
                || !pair.bridgeRuntimeVersion.equals(binding.bridgeRuntimeVersion)
                || !pair.signingCertificateSha256.equalsIgnoreCase(binding.signingCertificateSha256))
            throw new SecurityException(AndroidMessages.PACKAGE_TRUST_12.text());
        return false;
    }

    private static void requireEmbeddedBridge(Context context, GamePreparationService.PreparedPair pair, String localSigner) throws Exception {
        new GamePreparationService(context).validatePrepared(pair);
        if (localSigner == null || !localSigner.matches("[a-fA-F0-9]{64}")
                || !localSigner.equalsIgnoreCase(pair.signingCertificateSha256))
            throw new SecurityException(AndroidMessages.PACKAGE_TRUST_13.text());
        PackageManager pm = context.getPackageManager();
        PackageInfo game = installed(context, pair.gamePackage);
        PackageInfo main = installed(context, context.getPackageName());
        if (!signer(game).equalsIgnoreCase(localSigner)
                || !apkSetSha256(installedApks(game)).equals(apkSetSha256(pair.gameApks)))
            throw new SecurityException(AndroidMessages.PACKAGE_TRUST_14.text());
        if (!"io.github.xyz1001.pdsync.android".equals(context.getPackageName())
                || game.applicationInfo == null || main.applicationInfo == null
                || game.applicationInfo.uid == main.applicationInfo.uid
                || game.sharedUserId != null || main.sharedUserId != null
                || !solePackage(pm.getPackagesForUid(game.applicationInfo.uid), pair.gamePackage)
                || !solePackage(pm.getPackagesForUid(main.applicationInfo.uid), context.getPackageName()))
            throw new SecurityException(AndroidMessages.PACKAGE_TRUST_15.text());
        String authority = "io.github.xyz1001.pdsync.embedded.g" + pair.originIdentity.substring(0, 32) + ".files";
        ProviderInfo provider = pm.resolveContentProvider(pair.authority, 0);
        if (!pair.gamePackage.equals(pair.bridgeHostPackage) || !authority.equals(pair.authority)
                || provider == null || !pair.authority.equals(provider.authority)
                || !pair.gamePackage.equals(provider.packageName)
                || !"io.github.xyz1001.pdsync.bridge.SaveBridgeProvider".equals(provider.name)
                || !(pair.gamePackage + ":pdsync_bridge").equals(provider.processName)
                || !provider.enabled || provider.applicationInfo == null || !provider.applicationInfo.enabled
                || provider.applicationInfo.uid != game.applicationInfo.uid
                || !provider.exported || provider.grantUriPermissions
                || !emptyPermission(provider.readPermission) || !emptyPermission(provider.writePermission))
            throw new SecurityException(AndroidMessages.PACKAGE_TRUST_16.text());
        Bundle metadata = game.applicationInfo.metaData;
        if (metadata == null || !"embedded-v1".equals(metadata.getString(META + "BRIDGE_MODE"))
                || !"1".equals(pair.bridgeRuntimeVersion)
                || !pair.bridgeRuntimeVersion.equals(metadata.getString(META + "BRIDGE_RUNTIME_VERSION"))
                || !pair.gamePackage.equals(metadata.getString(META + "BOUND_GAME_PACKAGE"))
                || !pair.originIdentity.equals(metadata.getString(META + "BOUND_ORIGIN_IDENTITY"))
                || !pair.namespaceId.equals(metadata.getString(META + "BOUND_NAMESPACE_ID"))
                || !pair.sourceApkSetSha256.equals(metadata.getString(META + "BOUND_SOURCE_APK_SHA256")))
            throw new SecurityException(AndroidMessages.PACKAGE_TRUST_17.text());
    }

    private static boolean solePackage(String[] packages, String expected) {
        return packages != null && packages.length == 1 && expected.equals(packages[0]);
    }

    private static boolean emptyPermission(String permission) {
        return permission == null || permission.isEmpty();
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
            throw new IOException(AndroidMessages.PACKAGE_TRUST_18.text());
        }
        ArrayList arrayList = new ArrayList();
        arrayList.add(new File(packageInfo.applicationInfo.sourceDir));
        if (packageInfo.applicationInfo.splitSourceDirs != null) {
            for (String str : packageInfo.applicationInfo.splitSourceDirs) {
                if (str == null) {
                    throw new IOException(AndroidMessages.PACKAGE_TRUST_19.text());
                }
                arrayList.add(new File(str));
            }
        }
        return arrayList;
    }

    public static String apkSetSha256(List<File> list) throws Exception {
        if (list == null || list.isEmpty()) {
            throw new IOException(AndroidMessages.PACKAGE_TRUST_20.text());
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
