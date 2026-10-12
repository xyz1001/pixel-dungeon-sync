package io.github.xyz1001.pdsync.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import io.github.xyz1001.pdsync.android.BridgeClient;
import io.github.xyz1001.pdsync.android.BridgeDiagnostics;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import java.util.Collections;

/* loaded from: classes.dex */
final class HelperActivation {
    private HelperActivation() {
    }

    static Intent installedIntent(Context context, GamePreparationService.PreparedPair preparedPair) throws Exception {
        PackageTrust.requireBridge(context, preparedPair, preparedPair.signingCertificateSha256);
        PackageInfo packageInfoInstalled = PackageTrust.installed(context, preparedPair.helperPackage);
        if (!PackageTrust.apkSetSha256(PackageTrust.installedApks(packageInfoInstalled)).equals(PackageTrust.apkSetSha256(Collections.singletonList(preparedPair.helperApk)))) {
            throw new SecurityException("已安装助手的完整内容发生变化，请重新检查。");
        }
        ComponentName componentName = new ComponentName(preparedPair.helperPackage, "io.github.xyz1001.pdsync.bridge.ActivationActivity");
        try {
            ActivityInfo activityInfo = context.getPackageManager().getActivityInfo(componentName, 512);
            HelperActivationPolicy.requireActivity(preparedPair.helperPackage, activityInfo.name, activityInfo.packageName, activityInfo.processName, activityInfo.permission, activityInfo.exported, activityInfo.enabled, activityInfo.applicationInfo != null && activityInfo.applicationInfo.enabled, activityInfo.applicationInfo == null ? -1 : activityInfo.applicationInfo.uid, packageInfoInstalled.applicationInfo == null ? -2 : packageInfoInstalled.applicationInfo.uid);
            return new Intent().setComponent(componentName);
        } catch (PackageManager.NameNotFoundException e) {
            if (PackageTrust.versionCode(packageInfoInstalled) < 3) {
                return null;
            }
            throw new BridgeClient.Failure(BridgeDiagnostics.Stage.HELPER_ACTIVATION, "ACTIVATION_COMPONENT_UNAVAILABLE", "已安装新版助手，但系统未提供激活页面。请保留现有游戏并复制诊断。", e);
        }
    }
}
