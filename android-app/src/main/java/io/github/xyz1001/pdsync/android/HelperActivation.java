package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

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
        PackageTrust.requireBridgeForAccess(context, preparedPair);
        return installedIntent(context, InstalledBridgeResolver.resolve(context, preparedPair.gamePackage));
    }

    static Intent installedIntent(Context context, InstalledBridgeBinding binding) throws Exception {
        InstalledBridgeResolver.requireUnchanged(context, binding);
        BridgeClient.requirePureAccessProtocol(context, binding);
        PackageInfo packageInfoInstalled = PackageTrust.installed(context, binding.gamePackage);
        ComponentName componentName = new ComponentName(binding.gamePackage, "io.github.xyz1001.pdsync.bridge.ActivationActivity");
        try {
            ActivityInfo activityInfo = context.getPackageManager().getActivityInfo(componentName, 512);
            HelperActivationPolicy.requireEmbeddedActivity(binding.gamePackage, activityInfo.name, activityInfo.packageName, activityInfo.processName, activityInfo.permission, activityInfo.exported, activityInfo.enabled, activityInfo.applicationInfo != null && activityInfo.applicationInfo.enabled, activityInfo.applicationInfo == null ? -1 : activityInfo.applicationInfo.uid, packageInfoInstalled.applicationInfo == null ? -2 : packageInfoInstalled.applicationInfo.uid);
            return new Intent().setComponent(componentName);
        } catch (PackageManager.NameNotFoundException e) {
            throw new BridgeClient.Failure(BridgeDiagnostics.Stage.HELPER_ACTIVATION, "ACTIVATION_COMPONENT_UNAVAILABLE", AndroidMessages.HELPER_ACTIVATION_01.text(), e);
        }
    }
}
