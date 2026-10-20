package io.github.xyz1001.pdsync.bridge;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;


/** Bound package and save-path identity. Transfers do not poll game lifecycle state. */
final class GameGuard {
    static final String MAIN = "io.github.xyz1001.pdsync.android";
    private GameBinding binding;
    private final Context context;

    GameGuard(Context context) {
        this.context = context;
    }

    GameBinding binding() throws BridgeFailure {
        GameBinding gameBinding = this.binding;
        if (gameBinding != null) {
            return gameBinding;
        }
        try {
            GameBinding gameBinding2 = new GameBinding(BridgeMetadata.read(this.context), this.context.getPackageName());
            ProviderInfo providerInfo = BridgeMetadata.provider(this.context);
            boolean permissionsValid = gameBinding2.embedded
                    ? empty(providerInfo.readPermission) && empty(providerInfo.writePermission)
                    : MainCallerPolicy.PERMISSION.equals(providerInfo.readPermission) && MainCallerPolicy.PERMISSION.equals(providerInfo.writePermission);
            if (!gameBinding2.authority.equals(providerInfo.authority)
                    || !gameBinding2.helperPackage.equals(providerInfo.packageName)
                    || !providerInfo.exported || providerInfo.grantUriPermissions
                    || !gameBinding2.bridgeProcessName().equals(providerInfo.processName)
                    || !permissionsValid) {
                throw new BridgeFailure("INVALID_GAME_BINDING", "Bridge provider identity, process or permissions differ from its binding");
            }
            this.binding = gameBinding2;
            return gameBinding2;
        } catch (PackageManager.NameNotFoundException e) {
            throw new BridgeFailure("INVALID_GAME_BINDING", "Cannot inspect this bridge's game binding", e);
        } catch (BridgeFailure e2) {
            throw e2;
        } catch (IOException e3) {
            throw new BridgeFailure("INVALID_GAME_BINDING", "Bridge game binding is invalid", e3);
        }
    }

    private static boolean empty(String value) { return value == null || value.isEmpty(); }

    PackageInfo installedGame() throws BridgeFailure {
        boolean z;
        String[] strArr;
        try {
            PackageManager packageManager = this.context.getPackageManager();
            GameBinding gameBindingBinding = binding();
            PackageInfo packageInfo = packageManager.getPackageInfo(gameBindingBinding.gamePackage, 0);
            PackageInfo packageInfo2 = packageManager.getPackageInfo(this.context.getPackageName(), 0);
            if (packageInfo.applicationInfo == null || packageInfo2.applicationInfo == null || packageInfo2.applicationInfo.uid != Process.myUid()) {
                throw new BridgeFailure("UID_MISMATCH", "Bridge does not run as its bound package UID");
            }
            try {
                String str = packageInfo.packageName;
                String str2 = packageInfo.sharedUserId;
                String str3 = packageInfo2.sharedUserId;
                long longVersionCode = AndroidApiCompat.versionCode(packageInfo);
                String str4 = packageInfo.versionName;
                int i = packageInfo.applicationInfo.uid;
                int iMyUid = Process.myUid();
                String[] packagesForUid = packageManager.getPackagesForUid(Process.myUid());
                if (packageManager.checkSignatures(gameBindingBinding.gamePackage, this.context.getPackageName()) == 0) {
                    strArr = packagesForUid;
                    z = true;
                } else {
                    z = false;
                    strArr = packagesForUid;
                }
                gameBindingBinding.validateInstalled(str, str2, str3, longVersionCode, str4, i, iMyUid, strArr, z);
                return packageInfo;
            } catch (BridgeFailure e) {
                throw e;
            } catch (IOException e2) {
                throw new BridgeFailure("GAME_BINDING_MISMATCH", "Cannot validate installed game binding", e2);
            }
        } catch (PackageManager.NameNotFoundException e3) {
            throw new BridgeFailure("GAME_MISSING", "The selected patched game or bridge is not installed", e3);
        } catch (SecurityException e4) {
            throw new BridgeFailure("PROCESS_STATE_UNKNOWN", "Installed game identity cannot be inspected", e4);
        }
    }

    File root() throws BridgeFailure {
        try {
            return binding().saveRoot(installedGame().applicationInfo.dataDir, this.context.getApplicationInfo().dataDir);
        } catch (IOException e) {
            throw new BridgeFailure("SAVE_ROOT_UNKNOWN", "Cannot resolve Android private game directory", e);
        }
    }
}
