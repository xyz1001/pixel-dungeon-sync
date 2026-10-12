package io.github.xyz1001.pdsync.bridge;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;


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
            ApplicationInfo applicationInfo = this.context.getPackageManager().getApplicationInfo(this.context.getPackageName(), 128);
            HashMap map = new HashMap();
            for (String str : GameBinding.METADATA_NAMES) {
                String str2 = "io.github.xyz1001.pdsync." + str;
                Object obj = applicationInfo.metaData == null ? null : applicationInfo.metaData.get(str2);
                if (!(obj instanceof String)) {
                    throw new BridgeFailure("INVALID_GAME_BINDING", "Helper game binding metadata is missing or not a string");
                }
                map.put(str2, (String) obj);
            }
            GameBinding gameBinding2 = new GameBinding(map, this.context.getPackageName());
            ProviderInfo providerInfo = this.context.getPackageManager().getProviderInfo(new ComponentName(this.context.getPackageName(), SaveBridgeProvider.class.getName()), 0);
            if (!gameBinding2.authority.equals(providerInfo.authority) || !gameBinding2.helperPackage.equals(providerInfo.packageName) || !providerInfo.exported || providerInfo.grantUriPermissions || !"io.github.xyz1001.pdsync.android.ACCESS_BRIDGE".equals(providerInfo.readPermission) || !"io.github.xyz1001.pdsync.android.ACCESS_BRIDGE".equals(providerInfo.writePermission)) {
                throw new BridgeFailure("INVALID_GAME_BINDING", "Helper provider identity or caller permissions differ from the selected game binding");
            }
            this.binding = gameBinding2;
            return gameBinding2;
        } catch (PackageManager.NameNotFoundException e) {
            throw new BridgeFailure("INVALID_GAME_BINDING", "Cannot inspect this helper's game binding", e);
        } catch (BridgeFailure e2) {
            throw e2;
        } catch (IOException e3) {
            throw new BridgeFailure("INVALID_GAME_BINDING", "Helper game binding is invalid", e3);
        }
    }

    PackageInfo installedGame() throws BridgeFailure {
        boolean z;
        String[] strArr;
        try {
            PackageManager packageManager = this.context.getPackageManager();
            GameBinding gameBindingBinding = binding();
            PackageInfo packageInfo = packageManager.getPackageInfo(gameBindingBinding.gamePackage, 134217728);
            PackageInfo packageInfo2 = packageManager.getPackageInfo(this.context.getPackageName(), 0);
            if (packageInfo.applicationInfo == null || packageInfo2.applicationInfo == null || packageInfo2.applicationInfo.uid != Process.myUid()) {
                throw new BridgeFailure("UID_MISMATCH", "Game and helper do not have the same installed UID");
            }
            try {
                String str = packageInfo.packageName;
                String str2 = packageInfo.sharedUserId;
                String str3 = packageInfo2.sharedUserId;
                long longVersionCode = packageInfo.getLongVersionCode();
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
            throw new BridgeFailure("GAME_MISSING", "The selected patched game or matching helper is not installed", e3);
        } catch (SecurityException e4) {
            throw new BridgeFailure("PROCESS_STATE_UNKNOWN", "Installed game identity cannot be inspected", e4);
        }
    }

    static final class Observation {
        final PackageInfo game;
        final boolean gameForeground;
        final boolean gameProcessPresent;

        Observation(PackageInfo packageInfo, boolean z, boolean z2) {
            this.game = packageInfo;
            this.gameForeground = z;
            this.gameProcessPresent = z2;
        }
    }

    Observation observe() throws BridgeFailure {
        PackageInfo packageInfoInstalledGame = installedGame();
        GameBinding gameBindingBinding = binding();
        try {
            ActivityManager activityManager = (ActivityManager) this.context.getSystemService("activity");
            List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = activityManager == null ? null : activityManager.getRunningAppProcesses();
            if (runningAppProcesses == null) {
                throw new BridgeFailure("PROCESS_STATE_UNKNOWN", "Shared-UID process visibility is unavailable");
            }
            List<ActivityManager.ProcessErrorStateInfo> processesInErrorState = activityManager.getProcessesInErrorState();
            if (processesInErrorState != null) {
                Iterator<ActivityManager.ProcessErrorStateInfo> it = processesInErrorState.iterator();
                while (it.hasNext()) {
                    if (it.next().uid == Process.myUid()) {
                        throw new BridgeFailure("PROCESS_STATE_UNKNOWN", "A shared-UID process is crashing or unresponsive");
                    }
                }
            }
            boolean z = false;
            boolean z2 = false;
            boolean z3 = false;
            for (ActivityManager.RunningAppProcessInfo runningAppProcessInfo : runningAppProcesses) {
                if (runningAppProcessInfo.uid == Process.myUid()) {
                    if (runningAppProcessInfo.pid == Process.myPid()) {
                        if (!gameBindingBinding.isHelperProcess(runningAppProcessInfo.processName, runningAppProcessInfo.pkgList)) {
                            throw new BridgeFailure("PROCESS_STATE_UNKNOWN", "Helper process identity is ambiguous");
                        }
                        z = true;
                    } else {
                        if (!gameBindingBinding.isGameProcess(runningAppProcessInfo.processName, runningAppProcessInfo.pkgList)) {
                            throw new BridgeFailure("PROCESS_STATE_UNKNOWN", "An unknown process is running in the shared UID");
                        }
                        int i = runningAppProcessInfo.importance;
                        if (i <= 0) {
                            throw new BridgeFailure("PROCESS_STATE_UNKNOWN", "Game process importance is unknown");
                        }
                        if (i <= 230 || i == 325) {
                            z2 = true;
                        }
                        z3 = true;
                    }
                }
            }
            if (!z) {
                throw new BridgeFailure("PROCESS_STATE_UNKNOWN", "Cannot verify helper process visibility");
            }
            return new Observation(packageInfoInstalledGame, z2, z3);
        } catch (SecurityException e) {
            throw new BridgeFailure("PROCESS_STATE_UNKNOWN", "Shared-UID process observation was denied", e);
        }
    }

    PackageInfo requireQuiescent() throws BridgeFailure {
        Observation observationObserve = observe();
        if (observationObserve.gameForeground) {
            throw new BridgeFailure("GAME_FOREGROUND", "Return from the game before syncing its saves");
        }
        return observationObserve.game;
    }

    File root() throws BridgeFailure {
        try {
            return binding().saveRoot(requireQuiescent().applicationInfo.dataDir, this.context.getApplicationInfo().dataDir);
        } catch (IOException e) {
            throw new BridgeFailure("SAVE_ROOT_UNKNOWN", "Cannot resolve Android private game directory", e);
        }
    }
}
