package io.github.xyz1001.pdsync.bridge;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;


public final class GameBinding {
    private static final String LEGACY_ORIGIN = "32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89";
    public static final String[] METADATA_NAMES = {"BOUND_GAME_PACKAGE", "BOUND_SHARED_USER_ID", "BOUND_ORIGIN_IDENTITY", "BOUND_NAMESPACE_ID", "BOUND_SOURCE_APK_SHA256", "BOUND_GAME_VERSION_CODE", "BOUND_GAME_VERSION_NAME"};
    public static final String PREFIX = "io.github.xyz1001.pdsync.";
    public final String authority;
    public final String gamePackage;
    public final long gameVersionCode;
    public final String gameVersionName;
    public final String helperPackage;
    public final String namespaceId;
    public final String originIdentity;
    public final String sharedUserId;
    public final String sourceApkSha256;

    public GameBinding(Map<String, String> map, String str) throws IOException {
        String str2;
        String strValue = value(map, "BOUND_GAME_PACKAGE");
        this.gamePackage = strValue;
        String strValue2 = value(map, "BOUND_ORIGIN_IDENTITY");
        this.originIdentity = strValue2;
        String strValue3 = value(map, "BOUND_NAMESPACE_ID");
        this.namespaceId = strValue3;
        String strValue4 = value(map, "BOUND_SHARED_USER_ID");
        this.sharedUserId = strValue4;
        String strValue5 = value(map, "BOUND_SOURCE_APK_SHA256");
        this.sourceApkSha256 = strValue5;
        String strValue6 = value(map, "BOUND_GAME_VERSION_NAME");
        this.gameVersionName = strValue6;
        String strValue7 = value(map, "BOUND_GAME_VERSION_CODE");
        if (validPackage(strValue) && strValue2.matches("[0-9a-f]{64}") && strValue5.matches("[0-9a-f]{64}")) {
            if (strValue3.equals("android-" + strValue2) && strValue7.matches("0|[1-9][0-9]{0,18}") && strValue6.length() <= 1024) {
                try {
                    this.gameVersionCode = Long.parseLong(strValue7);
                    String str3 = "io.github.xyz1001.pdsync.bridge";
                    boolean zEquals = "io.github.xyz1001.pdsync.bridge".equals(str);
                    if (zEquals && (!"32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89".equals(strValue2) || !"com.shatteredpixel.shatteredpixeldungeon".equals(strValue))) {
                        throw invalid();
                    }
                    if (!zEquals) {
                        str3 = "io.github.xyz1001.pdsync.bridge.g" + strValue2.substring(0, 32);
                    }
                    this.helperPackage = str3;
                    StringBuilder sb = new StringBuilder();
                    sb.append(str3);
                    sb.append(zEquals ? ".saves" : ".files");
                    this.authority = sb.toString();
                    if (zEquals) {
                        str2 = "io.github.xyz1001.pdsync.shared";
                    } else {
                        str2 = "io.github.xyz1001.pdsync.shared.g" + strValue2.substring(0, 32);
                    }
                    if (!str3.equals(str) || str3.equals(strValue) || "io.github.xyz1001.pdsync.android".equals(strValue) || !strValue4.equals(str2)) {
                        throw invalid();
                    }
                    return;
                } catch (NumberFormatException unused) {
                    throw invalid();
                }
            }
        }
        throw invalid();
    }

    private static String value(Map<String, String> map, String str) throws IOException {
        String str2;
        if (map == null) {
            str2 = null;
        } else {
            str2 = map.get("io.github.xyz1001.pdsync." + str);
        }
        if (str2 != null) {
            return str2;
        }
        throw invalid();
    }

    private static BridgeFailure invalid() {
        return new BridgeFailure("INVALID_GAME_BINDING", "Helper is not bound to a valid selected game; prepare the matching helper again");
    }

    public static boolean validPackage(String str) {
        return str != null && str.length() <= 255 && str.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    public void validateInstalled(String str, String str2, String str3, long j, String str4, int i, int i2, String[] strArr, boolean z) throws IOException {
        if (this.gamePackage.equals(str) && j == this.gameVersionCode) {
            String str5 = this.gameVersionName;
            if (str4 == null) {
                str4 = "";
            }
            if (str5.equals(str4)) {
                if (i != i2 || !this.sharedUserId.equals(str2) || !this.sharedUserId.equals(str3)) {
                    throw new BridgeFailure("UID_MISMATCH", "Selected game and helper are not in their bound shared UID group");
                }
                HashSet hashSet = new HashSet(Arrays.asList(this.gamePackage, this.helperPackage));
                if (strArr == null || strArr.length != 2 || !new HashSet(Arrays.asList(strArr)).equals(hashSet)) {
                    throw new BridgeFailure("UNEXPECTED_SHARED_UID", "The shared UID must contain exactly the selected game and its helper");
                }
                if (!z) {
                    throw new BridgeFailure("GAME_SIGNER_MISMATCH", "Installed game and helper signatures do not match");
                }
                return;
            }
        }
        throw new BridgeFailure("GAME_BINDING_MISMATCH", "Installed game differs from the selected APK; prepare its matching helper again");
    }

    
    
    public boolean isGameProcess(String name, String[] packages) {
        return name != null && (gamePackage.equals(name) || name.startsWith(gamePackage + ":"))
                && packages != null && packages.length == 1 && gamePackage.equals(packages[0]);
    }

    public boolean isHelperProcess(String str, String[] strArr) {
        return this.helperPackage.equals(str) && strArr != null && strArr.length == 1 && this.helperPackage.equals(strArr[0]);
    }

    public File saveRoot(String str, String str2) throws IOException {
        if (str == null || str2 == null || !new File(str).isAbsolute() || !new File(str2).isAbsolute()) {
            throw new BridgeFailure("SAVE_ROOT_UNKNOWN", "Android did not report separate private game and helper directories");
        }
        File canonicalFile = new File(str).getCanonicalFile();
        File canonicalFile2 = new File(str2).getCanonicalFile();
        if (canonicalFile.toPath().startsWith(canonicalFile2.toPath()) || canonicalFile2.toPath().startsWith(canonicalFile.toPath())) {
            throw new BridgeFailure("SAVE_ROOT_UNKNOWN", "Game and helper private directories are not isolated");
        }
        return new File(canonicalFile, "files");
    }
}
