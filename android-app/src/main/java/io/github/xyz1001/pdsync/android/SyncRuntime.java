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
    private static RootGameBridge rootBackend;
    private static String rootBackendToken;

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

    /** Local selection only. Loading/rendering this never launches a privileged process. */
    static SelectedRootGame rootGame(Context context) throws Exception {
        return new RootSelectionStore(context.getFilesDir()).load();
    }

    static boolean rootMode(Context context) throws Exception { return rootGame(context) != null; }

    static void setRootGame(Context context, SelectedRootGame game) throws Exception {
        synchronized (OPERATION_LOCK) {
            requireBackendSwitchAllowed(context);
            requireFreshBackendComparison(context);
            new RootTargetResolver(context).requireUnchanged(game);
            AutoSyncService.cancelForConnectionChange(context);
            closeRootBackend();
            new RootSelectionStore(context.getFilesDir()).save(game);
        }
    }

    static void useEmbeddedBackend(Context context) throws Exception {
        synchronized (OPERATION_LOCK) {
            requireBackendSwitchAllowed(context);
            requireFreshBackendComparison(context);
            AutoSyncService.cancelForConnectionChange(context);
            closeRootBackend();
            new RootSelectionStore(context.getFilesDir()).clear();
        }
    }

    private static void requireFreshBackendComparison(Context context) throws IOException {
        if (!prefs(context).edit().putBoolean("backendComparisonRequired", true).commit())
            throw new IOException("无法保存后端切换状态，已停止切换。");
    }

    private static void requireBackendSwitchAllowed(Context context) throws Exception {
        if (new InstallCoordinator(context).hasActiveRequest())
            throw new IOException("系统安装或卸载请求尚未结束，不能切换存档后端。");
        if (AndroidSyncFacade.hasUnresolvedRestore(new File(context.getFilesDir().getCanonicalFile(), "sync").toPath()))
            throw new IOException("存在未完成恢复，请先在原后端处理恢复记录，不能切换存档后端。");
    }

    /** Explicit persisted Root selection opts in to deliberate operations; the su manager grants access. */
    static boolean rootAuthorized(Context context) {
        try { return rootGame(context) != null; }
        catch (Exception invalid) { return false; }
    }

    /** Metadata-only cancellation gate. It never probes root or reads target save data. */
    static boolean rootSelectionMatches(Context context, SelectedRootGame expected) {
        try {
            SelectedRootGame current = rootGame(context);
            return expected != null && expected.matches(current)
                    && expected.selectionToken().equals(current.selectionToken());
        } catch (Exception invalid) { return false; }
    }

    /** Must be reached only from an explicit user authorization action. */
    static String probeRootAuthorization(Context context) throws Exception {
        synchronized (OPERATION_LOCK) {
            SelectedRootGame game = rootGame(context);
            if (game == null) throw new IOException("请先选择 Root 游戏。");
            // Recovery needs root on this exact existing selection even when a restore is pending.
            if (new InstallCoordinator(context).hasActiveRequest())
                throw new IOException("系统安装或卸载请求尚未结束，请先完成当前系统请求。");
            String result;
            try (RootGameBridge probe = new RootGameBridge(context, game, rootIdentity(context, game))) {
                result = probe.probeAuthorization();
            }
            SelectedRootGame current = rootGame(context);
            if (current == null || !current.matches(game)) throw new IOException("Root 选择已变化，请重新授权。");
            return result;
        }
    }

    static synchronized GameAccessBackend backend(Context context) throws Exception {
        SelectedRootGame root = rootGame(context);
        if (root != null) {
            String token = root.selectionToken() + ":" + currentNamespace(context);
            if (rootBackend == null || !token.equals(rootBackendToken)) {
                closeRootBackend();
                rootBackend = (RootGameBridge) GameAccessBackends.forRootGame(context, root, rootIdentity(context, root));
                rootBackendToken = token;
            }
            return rootBackend;
        }
        closeRootBackend();
        GamePreparationService.PreparedPair prepared = requirePreparedGame(context);
        return GameAccessBackends.forPreparedGame(context, prepared, gameIdentity(context, prepared));
    }

    private static synchronized void closeRootBackend() {
        if (rootBackend != null) rootBackend.close();
        rootBackend = null; rootBackendToken = null;
    }

    static String currentSelectionToken(Context context) throws Exception {
        SelectedRootGame root = rootGame(context);
        return root == null ? selectionToken(requirePreparedGame(context)) : root.selectionToken();
    }

    static String currentGamePackage(Context context) throws Exception {
        SelectedRootGame root = rootGame(context);
        return root == null ? requirePreparedGame(context).gamePackage : root.gamePackage;
    }

    static AndroidGameIdentity currentGameIdentity(Context context) throws Exception {
        SelectedRootGame root = rootGame(context);
        return root == null ? gameIdentity(context, requirePreparedGame(context)) : rootIdentity(context, root);
    }

    private static String currentOrigin(Context context) throws Exception {
        SelectedRootGame root = rootGame(context);
        return root == null ? requirePreparedGame(context).originIdentity : root.originIdentity;
    }

    private static AndroidGameIdentity rootIdentity(Context context, SelectedRootGame root) {
        String namespace = prefs(context).getString("namespace." + root.originIdentity, null);
        if (namespace == null) namespace = AndroidGameIdentity.defaultNamespace(root.originIdentity);
        return new AndroidGameIdentity(root.gamePackage, root.gameLabel, root.originIdentity, namespace);
    }

    static String selectionToken(GamePreparationService.PreparedPair preparedPair) {
        return preparedPair.originIdentity + ":" + preparedPair.sourceApkSetSha256 + ":" + (preparedPair.embeddedBridge() ? preparedPair.bridgeMode + ":" + preparedPair.runtimeDexSha256 : preparedPair.sharedUid);
    }

    static String currentNamespace(Context context) throws Exception {
        String origin = currentOrigin(context);
        String string = prefs(context).getString("namespace." + origin, null);
        return string == null ? AndroidGameIdentity.defaultNamespace(origin) : string;
    }

    static void setNamespace(Context context, String str) throws Exception {
        String origin = currentOrigin(context);
        if (AndroidSyncFacade.hasUnresolvedRestore(new File(context.getFilesDir().getCanonicalFile(), "sync").toPath())) {
            throw new IOException("存在未完成恢复，先处理恢复记录后再关联云端档案。");
        }
        String strTrim = str.trim();
        if (!strTrim.isEmpty() && !strTrim.matches("[A-Za-z0-9_-]{1,96}")) {
            throw new IOException("云端游戏标识格式无效。");
        }
        AutoSyncService.cancelForConnectionChange(context);
        closeRootBackend();
        SharedPreferences.Editor editorEdit = prefs(context).edit();
        if (strTrim.isEmpty()) {
            editorEdit.remove("namespace." + origin);
        } else {
            editorEdit.putString("namespace." + origin, strTrim);
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
        AndroidGameIdentity androidGameIdentityGameIdentity = currentGameIdentity(context);
        return new AndroidSyncFacade(new File(context.getFilesDir().getCanonicalFile(), "sync").toPath(), new AndroidConnection(connection2.endpoint, connection2.username, connection2.password, connection2.profile, connection2.retainsLegacyOfficialScope()), backend(context), androidGameIdentityGameIdentity);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("sync-presentation-v2", 0);
    }

    private static String scope(Context context, SecureConnectionStore.Connection connection2) throws Exception {
        String origin = currentOrigin(context);
        if (connection2 == null) {
            throw new IOException("No configured connection");
        }
        return PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest((origin + "\n" + currentNamespace(context) + "\n" + connection2.stateEndpoint() + "\n" + connection2.username + "\n" + connection2.profile).getBytes(StandardCharsets.UTF_8)));
    }

    static OnboardingProgress onboardingProgress(Context context, SecureConnectionStore.Connection connection2) {
        if (prefs(context).getBoolean("backendComparisonRequired", false)) return OnboardingProgress.PENDING;
        try {
            return OnboardingProgress.valueOf(prefs(context).getString("onboarding." + scope(context, connection2), "PENDING"));
        } catch (Exception unused) {
            return OnboardingProgress.PENDING;
        }
    }

    static void setOnboardingProgress(Context context, SecureConnectionStore.Connection connection2, OnboardingProgress onboardingProgress) {
        try {
            if (prefs(context).edit().putString("onboarding." + scope(context, connection2), onboardingProgress.name())
                    .putBoolean("backendComparisonRequired", false).commit()) {
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

    static boolean backendSupportsAutomatic(GameAccessBackend.Mode mode, GameAccessBackend.Capabilities capabilities) {
        return mode != null && mode != GameAccessBackend.Mode.LEGACY_HELPER_BRIDGE && capabilities != null
                && capabilities.exportSupported && capabilities.lifecycleObservation;
    }

    static boolean automaticAllowed(Context context, SecureConnectionStore.Connection connection2) {
        if (isAutoUploadPaused(context) || connection2 == null) {
            return false;
        }
        try {
            GameAccessBackend access = backend(context); // Construction and capabilities never invoke su.
            if (!backendSupportsAutomatic(access.mode(), access.capabilities())) return false;
            boolean pending = AndroidSyncFacade.hasUnresolvedRestore(
                    new File(context.getFilesDir().getCanonicalFile(), "sync").toPath());
            return OnboardingGate.allowsAutomatic(onboardingProgress(context, connection2), pending, true);
        } catch (Exception unavailableSelection) {
            return false;
        }
    }
}
