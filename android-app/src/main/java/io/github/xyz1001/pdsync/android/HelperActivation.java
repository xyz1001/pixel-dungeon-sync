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
        if (!preparedPair.embeddedBridge()) throw new SecurityException("旧版助手激活已停用，请重新准备游戏。");
        PackageInfo packageInfoInstalled = PackageTrust.installed(context, preparedPair.gamePackage);
        ComponentName componentName = new ComponentName(preparedPair.gamePackage, "io.github.xyz1001.pdsync.bridge.ActivationActivity");
        try {
            ActivityInfo activityInfo = context.getPackageManager().getActivityInfo(componentName, 512);
            HelperActivationPolicy.requireEmbeddedActivity(preparedPair.gamePackage, activityInfo.name, activityInfo.packageName, activityInfo.processName, activityInfo.permission, activityInfo.exported, activityInfo.enabled, activityInfo.applicationInfo != null && activityInfo.applicationInfo.enabled, activityInfo.applicationInfo == null ? -1 : activityInfo.applicationInfo.uid, packageInfoInstalled.applicationInfo == null ? -2 : packageInfoInstalled.applicationInfo.uid);
            return new Intent().setComponent(componentName);
        } catch (PackageManager.NameNotFoundException e) {
            throw new BridgeClient.Failure(BridgeDiagnostics.Stage.HELPER_ACTIVATION, "ACTIVATION_COMPONENT_UNAVAILABLE", "系统未提供游戏内置存档桥的激活页面。请保留现有游戏并复制诊断。", e);
        }
    }
}
