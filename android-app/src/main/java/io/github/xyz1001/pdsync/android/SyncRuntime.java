package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.xyz1001.pdsync.android.SecureConnectionStore;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import pdsync.OnboardingGate;
import pdsync.OnboardingProgress;
import pdsync.android.app.AndroidConnection;
import pdsync.android.app.AndroidGameIdentity;
import pdsync.android.app.AndroidSyncFacade;

/* loaded from: classes.dex */
final class SyncRuntime {
    static final Object OPERATION_LOCK = new Object();
    private static final String PREFS = "sync-presentation-v2";
    private static volatile SecureConnectionStore.Connection connection;
    private static volatile GamePreparationService.PreparedPair selected;

    SyncRuntime() {
    }

    static void setConnection(SecureConnectionStore.Connection connection2) {
        connection = connection2;
    }

    static SecureConnectionStore.Connection connection(Context context) throws Exception {
        SecureConnectionStore.Connection connection2 = connection;
        if (connection2 != null) {
            return connection2;
        }
        SecureConnectionStore.Connection connectionLoad = new SecureConnectionStore(context).load();
        connection = connectionLoad;
        return connectionLoad;
    }

    static synchronized void setPreparedGame(Context context, GamePreparationService.PreparedPair preparedPair) {
        GamePreparationService.PreparedPair preparedPair2 = selected;
        if (preparedPair2 != null && (preparedPair == null || !selectionToken(preparedPair2).equals(selectionToken(preparedPair)))) {
            AutoSyncService.cancelForConnectionChange(context);
        }
        selected = preparedPair;
    }

    static synchronized GamePreparationService.PreparedPair requirePreparedGame(Context context) throws Exception {
        if (selected == null) {
            selected = new GamePreparationService(context).reloadPrepared();
        }
        if (selected == null) {
            throw new IOException("请先选择并准备游戏。");
        }
        return selected;
    }

    static String selectionToken(GamePreparationService.PreparedPair preparedPair) {
        return preparedPair.originIdentity + ":" + preparedPair.sourceApkSetSha256 + ":" + (preparedPair.embeddedBridge() ? preparedPair.bridgeMode + ":" + preparedPair.runtimeDexSha256 : preparedPair.sharedUid);
    }

    static String currentNamespace(Context context) throws Exception {
        GamePreparationService.PreparedPair preparedPairRequirePreparedGame = requirePreparedGame(context);
        String string = prefs(context).getString("namespace." + preparedPairRequirePreparedGame.originIdentity, null);
        return string == null ? AndroidGameIdentity.defaultNamespace(preparedPairRequirePreparedGame.originIdentity) : string;
    }

    static void setNamespace(Context context, String str) throws Exception {
        GamePreparationService.PreparedPair preparedPairRequirePreparedGame = requirePreparedGame(context);
        if (AndroidSyncFacade.hasUnresolvedRestore(new File(context.getFilesDir().getCanonicalFile(), "sync").toPath())) {
            throw new IOException("存在未完成恢复，先处理恢复记录后再关联云端档案。");
        }
        String strTrim = str.trim();
        if (!strTrim.isEmpty() && !strTrim.matches("[A-Za-z0-9_-]{1,96}")) {
            throw new IOException("云端游戏标识格式无效。");
        }
        AutoSyncService.cancelForConnectionChange(context);
        SharedPreferences.Editor editorEdit = prefs(context).edit();
        if (strTrim.isEmpty()) {
            editorEdit.remove("namespace." + preparedPairRequirePreparedGame.originIdentity);
        } else {
            editorEdit.putString("namespace." + preparedPairRequirePreparedGame.originIdentity, strTrim);
        }
        if (!editorEdit.commit()) {
            throw new IOException("无法保存云端关联，未继续同步。");
        }
    }

    static AndroidGameIdentity gameIdentity(Context context, GamePreparationService.PreparedPair preparedPair) {
        String string = prefs(context).getString("namespace." + preparedPair.originIdentity, null);
        if (string == null) {
            string = AndroidGameIdentity.defaultNamespace(preparedPair.originIdentity);
        }
        return new AndroidGameIdentity(preparedPair.gamePackage, preparedPair.gameLabel, preparedPair.originIdentity, string);
    }

    static AndroidSyncFacade facade(Context context, SecureConnectionStore.Connection connection2) throws Exception {
        if (connection2 == null) {
            throw new IllegalStateException("WebDAV 连接尚未配置，未上传存档。");
        }
        connection2.validate();
        GamePreparationService.PreparedPair preparedPairRequirePreparedGame = requirePreparedGame(context);
        AndroidGameIdentity androidGameIdentityGameIdentity = gameIdentity(context, preparedPairRequirePreparedGame);
        return new AndroidSyncFacade(new File(context.getFilesDir().getCanonicalFile(), "sync").toPath(), new AndroidConnection(connection2.endpoint, connection2.username, connection2.password, connection2.profile), GameAccessBackends.forPreparedGame(context, preparedPairRequirePreparedGame, androidGameIdentityGameIdentity), androidGameIdentityGameIdentity);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("sync-presentation-v2", 0);
    }

    private static String scope(Context context, SecureConnectionStore.Connection connection2) throws Exception {
        GamePreparationService.PreparedPair preparedPairRequirePreparedGame = requirePreparedGame(context);
        if (connection2 == null) {
            throw new IOException("No configured connection");
        }
        return PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest((preparedPairRequirePreparedGame.originIdentity + "\n" + currentNamespace(context) + "\n" + connection2.endpoint + "\n" + connection2.username + "\n" + connection2.profile).getBytes(StandardCharsets.UTF_8)));
    }

    static OnboardingProgress onboardingProgress(Context context, SecureConnectionStore.Connection connection2) {
        try {
            return OnboardingProgress.valueOf(prefs(context).getString("onboarding." + scope(context, connection2), "PENDING"));
        } catch (Exception unused) {
            return OnboardingProgress.PENDING;
        }
    }

    static void setOnboardingProgress(Context context, SecureConnectionStore.Connection connection2, OnboardingProgress onboardingProgress) {
        try {
            if (prefs(context).edit().putString("onboarding." + scope(context, connection2), onboardingProgress.name()).commit()) {
            } else {
                throw new IOException("无法保存配置进度。");
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存配置进度，自动上传仍暂停。", e);
        }
    }

    static boolean isAutoUploadPaused(Context context) {
        return prefs(context).getBoolean("autoUploadPaused", false);
    }

    static void setAutoUploadPaused(Context context, boolean z) {
        if (!prefs(context).edit().putBoolean("autoUploadPaused", z).commit()) {
            throw new IllegalStateException("无法保存自动上传设置。");
        }
        if (z) {
            AutoSyncService.cancelForConnectionChange(context);
        }
    }

    static boolean automaticAllowed(Context context, SecureConnectionStore.Connection connection2) {
        if (isAutoUploadPaused(context) || connection2 == null) {
            return false;
        }
        try {
            if (!requirePreparedGame(context).embeddedBridge()) return false;
        } catch (Exception unavailablePreparation) {
            return false;
        }
        return OnboardingGate.allowsAutomatic(onboardingProgress(context, connection2), false, true);
    }
}
