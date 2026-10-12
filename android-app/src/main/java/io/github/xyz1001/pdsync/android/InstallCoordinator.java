package io.github.xyz1001.pdsync.android;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import pdsync.android.app.AndroidSyncFacade;


public final class InstallCoordinator {
    public static final String CHANGED = "io.github.xyz1001.pdsync.android.INSTALL_CHANGED";
    public static final int DELETE_REQUEST = 8102;
    public static final String LOSS_WARNING = "本次替换必须先卸载原游戏。卸载会不可逆地永久删除全部本地存档和战绩。\n\nAPK 备份不是存档备份，不能恢复已删除的存档。卸载前取消会保留原版；卸载后中断只能保留已准备好的安装包，不能找回存档。\n\n卸载和安装都仍需在 Android 系统界面单独确认。";
    static final String PREFS = "install-checkpoint-v1";
    private final Context context;
    private final SharedPreferences prefs;
    private final GamePreparationService preparation;

    public InstallCoordinator(Context context) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext;
        this.prefs = applicationContext.getSharedPreferences("install-checkpoint-v1", 0);
        this.preparation = new GamePreparationService(applicationContext);
    }

    private void persist(SharedPreferences.Editor editor) throws IOException {
        if (!editor.commit()) {
            throw new IOException("无法保存安装进度，已停止操作。");
        }
    }

    public String state() {
        return this.prefs.getString("state", "READY");
    }

    public boolean hasActiveRequest() {
        return state().equals("INSTALLING") || state().equals("PENDING_CONFIRMATION") || state().equals("UNINSTALLING");
    }

    public String pendingPackage() {
        return this.prefs.getString("package", "");
    }

    public boolean originalRemoved() {
        return this.prefs.getBoolean("gameRemoved", false);
    }

    public boolean hasConfirmation() {
        return (state().equals("PENDING_CONFIRMATION") || state().equals("UNINSTALLING") || state().equals("INSTALLING")) && this.prefs.contains("confirmation");
    }

    public boolean hasObsoleteLegacyRequest(GamePreparationService.PreparedPair pair) {
        String operation = this.prefs.getString("operation", "");
        return hasActiveRequest() && pair != null && !pair.embeddedBridge()
                && ("install".equals(operation) || "uninstall".equals(operation))
                && InstallProgressPolicy.cleanupTargetMatches(false, pair.gamePackage, pair.helperPackage, pendingPackage());
    }

    public String summary() {
        String string = this.prefs.getString("message", "还没有待处理的系统安装操作。");
        if (!originalRemoved()) {
            return string;
        }
        return string + "\n原游戏曾被卸载，本机原存档和战绩不能由 APK 恢复。";
    }

    public void assertCanPrepare() throws IOException {
        if (hasActiveRequest()) {
            throw new IOException("已有系统安装或卸载尚未结束，请先继续或取消该操作。");
        }
        if (AndroidSyncFacade.hasUnresolvedRestore(new File(this.context.getFilesDir().getCanonicalFile(), "sync").toPath())) {
            throw new IOException("存在未完成存档恢复，请先处理恢复记录后再更换游戏。");
        }
    }

    public void registerPrepared(GamePreparationService.PreparedPair pair) throws Exception {
        assertCanPrepare();
        requireEmbedded(pair);
        this.preparation.validatePrepared(pair);
        SyncRuntime.setPreparedGame(this.context, pair);
        persist(this.prefs.edit().putString("state", "READY")
                .putString("message", "游戏 APK 集合与内置存档桥已暂存并通过签名与内容校验，尚未卸载或安装应用。")
                .remove("confirmation").remove("session").remove("package").remove("nonce"));
    }

    private static void requireEmbedded(GamePreparationService.PreparedPair pair) throws IOException {
        if (pair == null || !pair.embeddedBridge() || pair.helperOnlyUpgrade)
            throw new IOException("旧版助手安装流程已停用。请先处理原有恢复记录，再重新准备带内置存档桥的游戏。");
    }

    public GamePreparationService.PreparedPair freshPair() throws Exception {
        GamePreparationService.PreparedPair preparedPairReloadPrepared = this.preparation.reloadPrepared();
        if (preparedPairReloadPrepared == null) {
            throw new IOException("未找到完整的安装包，请重新准备游戏。");
        }
        requireEmbedded(preparedPairReloadPrepared);
        this.preparation.validatePrepared(preparedPairReloadPrepared);
        GamePreparationService.PreparedPair preparedPairRefreshInstallStrategies = this.preparation.refreshInstallStrategies(preparedPairReloadPrepared);
        SyncRuntime.setPreparedGame(this.context, preparedPairRefreshInstallStrategies);
        return preparedPairRefreshInstallStrategies;
    }

    /** This loader authorizes cleanup only. Never use it to install, uninstall, or reopen confirmation. */
    private GamePreparationService.PreparedPair retainedCleanupPair() throws Exception {
        GamePreparationService.PreparedPair pair = this.preparation.reloadPrepared();
        if (pair == null) throw new IOException("无法核对旧系统请求对应的准备记录，尚未清理请求。");
        this.preparation.validatePrepared(pair);
        if (!InstallProgressPolicy.cleanupTargetMatches(pair.embeddedBridge(), pair.gamePackage, pair.helperPackage, pendingPackage()))
            throw new IOException("旧系统请求的应用与保留的准备记录不匹配，尚未清理请求。");
        return pair;
    }

    public boolean installedMatches(String str, List<File> list) throws Exception {
        try {
            PackageInfo packageInfoInstalled = PackageTrust.installed(this.context, str);
            if (PackageTrust.signer(packageInfoInstalled).equalsIgnoreCase(this.preparation.currentLocalCertificateSha256())) {
                if (PackageTrust.apkSetSha256(PackageTrust.installedApks(packageInfoInstalled)).equals(PackageTrust.apkSetSha256(list))) {
                    return true;
                }
            }
        } catch (PackageManager.NameNotFoundException unused) {
        }
        return false;
    }

    public String nextPackage(GamePreparationService.PreparedPair pair) throws Exception {
        requireEmbedded(pair);
        this.preparation.validatePrepared(pair);
        if (pair.gameStrategy == GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL
                || !installedMatches(pair.gamePackage, pair.gameApks)) return pair.gamePackage;
        PackageTrust.requireBridge(this.context, pair, pair.signingCertificateSha256);
        return null;
    }

    private String identity(String str) throws Exception {
        PackageInfo packageInfoInstalled = PackageTrust.installed(this.context, str);
        return packageInfoInstalled.firstInstallTime + ":" + packageInfoInstalled.lastUpdateTime + ":" + PackageTrust.versionCode(packageInfoInstalled) + ":" + PackageTrust.signer(packageInfoInstalled) + ":" + PackageTrust.apkSetSha256(PackageTrust.installedApks(packageInfoInstalled));
    }

    private static boolean isGame(GamePreparationService.PreparedPair pair, String packageName) throws IOException {
        requireEmbedded(pair);
        if (!pair.gamePackage.equals(packageName))
            throw new IOException("系统请求必须只指向已验证的游戏，不能安装或卸载独立助手。");
        return true;
    }

    public String uninstallConsentReceipt(String packageName, GamePreparationService.PreparedPair pair) throws Exception {
        isGame(pair, packageName);
        this.preparation.validatePrepared(pair);
        return pair.originIdentity + ":" + pair.signingCertificateSha256 + ":"
                + PackageTrust.apkSetSha256(pair.gameApks) + ":" + identity(packageName);
    }

    public void requestUninstall(String str, String str2) throws Exception {
        if (hasActiveRequest()) {
            throw new IOException("请先完成上一个系统请求。");
        }
        GamePreparationService.PreparedPair preparedPairFreshPair = freshPair();
        isGame(preparedPairFreshPair, str);
        if (preparedPairFreshPair.gameStrategy != GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL || !uninstallConsentReceipt(str, preparedPairFreshPair).equals(str2)) {
            throw new IOException("安装包或现有应用已变化。请重新检查后再次确认，尚未请求卸载。");
        }
        if (AndroidSyncFacade.hasUnresolvedRestore(new File(this.context.getFilesDir().getCanonicalFile(), "sync").toPath())) {
            throw new IOException("存在未完成存档恢复，不能卸载游戏。请先处理恢复记录与备份。");
        }
        String string = UUID.randomUUID().toString();
        persist(this.prefs.edit().putString("state", "UNINSTALLING").putString("operation", "uninstall").putString("package", str).putString("identity", identity(str)).putString("uninstallPreparedApkSet", PackageTrust.apkSetSha256(preparedPairFreshPair.gameApks)).putString("uninstallOrigin", preparedPairFreshPair.originIdentity).putBoolean("gameRequest", isGame(preparedPairFreshPair, str)).putString("nonce", string).remove("session").remove("confirmation").putString("message", "等待 Android 系统卸载确认与结果。尚不能确认应用是否被删除。"));
        try {
            this.context.getPackageManager().getPackageInstaller().uninstall(str, PendingIntent.getBroadcast(this.context, string.hashCode(), new Intent(this.context, (Class<?>) InstallResultReceiver.class).setAction("io.github.xyz1001.pdsync.android.INSTALL_CHANGED." + string).putExtra("requestNonce", string), AndroidCompat.mutableCallbackFlags()).getIntentSender());
        } catch (IllegalArgumentException | SecurityException e) {
            persist(this.prefs.edit().putString("state", "READY").remove("nonce").remove("confirmation").putString("message", "系统拒绝创建卸载请求，未请求系统删除应用。请检查设备限制。"));
            throw e;
        }
    }

    
    
    
    
    
    
    public void requestInstall(String packageName) throws Exception {
        if(hasActiveRequest()) throw new IOException("上一个系统安装请求尚未结束，请勿重复提交。");
        if(!context.getPackageManager().canRequestPackageInstalls()) throw new IOException("请先点击“允许本应用请求安装”，在系统设置中自行确认。");
        assertCanPrepare();
        GamePreparationService.PreparedPair pair=freshPair();
        boolean game=isGame(pair,packageName);
        if(pair.gameStrategy==GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL)
            throw new IOException("当前应用仍需用户确认卸载，不能直接覆盖安装。");
        List<File> apks=pair.gameApks;
        if(installedMatches(packageName,apks)) { reconcile(); return; }
        PackageInstaller installer=null;
        int sessionId=-1; boolean commitAttempted=false;
        InstallDiagnostics.Stage stage=InstallDiagnostics.Stage.READ_SOURCE_VERIFY;
        try {
            InstallSessionWriter.Snapshot source=InstallSessionWriter.inspect(apks);
            stage=InstallDiagnostics.Stage.CREATE;
            installer=context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(packageName); params.setAppLabel(pair.gameLabel);
            params.setSize(source.length); RuntimePlatformCompat.requireInstallConfirmation(params);
            sessionId=installer.createSession(params); String nonce=UUID.randomUUID().toString();
            stage=InstallDiagnostics.Stage.OPEN;
            try(PackageInstaller.Session session=installer.openSession(sessionId)) {
                String expected=InstallSessionWriter.write(source,new InstallSessionWriter.Session() {
                    public OutputStream openWrite(String name,long length) throws Exception {
                        return session.openWrite(name,0,length);
                    }
                    public void fsync(OutputStream output) throws Exception { session.fsync(output); }
                });
                stage=InstallDiagnostics.Stage.READ_SOURCE_VERIFY;
                preparation.validatePrepared(pair);
                assertCanPrepare();
                InstallSessionWriter.verifySources(source);
                stage=InstallDiagnostics.Stage.GET_NAMES;
                InstallSessionWriter.requireNames(session.getNames(),apks.size());
                stage=InstallDiagnostics.Stage.CHECKPOINT;
                persist(prefs.edit().putString("state","INSTALLING").putString("operation","install").putString("package",packageName)
                        .putInt("session",sessionId).putString("nonce",nonce).putString("expectedApkSet",expected).putBoolean("gameRequest",game)
                        .remove("expectedApk").remove("confirmation").putString("message","安装包已交给 Android，等待系统确认或结果。"));
                stage=InstallDiagnostics.Stage.CALLBACK;
                PendingIntent callback=PendingIntent.getBroadcast(context,sessionId,new Intent(context,InstallResultReceiver.class)
                        .setAction(CHANGED+"."+nonce).putExtra("requestNonce",nonce),AndroidCompat.mutableCallbackFlags());
                android.content.IntentSender sender=callback.getIntentSender();
                stage=InstallDiagnostics.Stage.COMMIT;
                // Once commit is attempted its result is uncertain, even if commit or close throws.
                commitAttempted=true; session.commit(sender);
                stage=InstallDiagnostics.Stage.CLOSE;
            }
        } catch(Exception error) {
            InstallDiagnostics.Failure failure=InstallDiagnostics.failure(stage,packageName,sessionId,
                    Build.VERSION.SDK_INT,commitAttempted,error);
            if(commitAttempted) {
                try { persist(prefs.edit().putString("message",failure.userMessage())); }
                catch(IOException checkpointError) { failure.addSuppressed(InstallDiagnostics.at(InstallDiagnostics.Stage.CHECKPOINT,checkpointError)); }
            } else {
                // Only this invocation's successfully created session is eligible for cleanup.
                if(installer!=null && sessionId>=0) {
                    try { installer.abandonSession(sessionId); }
                    catch(Exception cleanupError) { failure.addSuppressed(InstallDiagnostics.at(InstallDiagnostics.Stage.ABANDON,cleanupError)); }
                }
            }
            String report=InstallDiagnostics.report(failure);
            // Plain text, in bounded chunks: Android 8's Throwable logger hides DeadSystemException.
            for(int offset=0;offset<report.length();offset+=3000)
                Log.e("PDInstall",report.substring(offset,Math.min(offset+3000,report.length())));
            throw failure;
        }
    }

    public PendingIntent confirmationToken() throws Exception {
        if (!hasConfirmation()) {
            throw new IOException("没有可恢复的系统确认令牌，请使用“检查失效系统请求”。");
        }
        GamePreparationService.PreparedPair preparedPairFreshPair = freshPair();
        isGame(preparedPairFreshPair, pendingPackage());
        if ("uninstall".equals(this.prefs.getString("operation", ""))) {
            if (!identity(pendingPackage()).equals(this.prefs.getString("identity", ""))
                    || !preparedPairFreshPair.originIdentity.equals(this.prefs.getString("uninstallOrigin", ""))
                    || !PackageTrust.apkSetSha256(preparedPairFreshPair.gameApks).equals(this.prefs.getString("uninstallPreparedApkSet", "")))
                throw new IOException("原游戏或已验证安装包发生变化，不能续用旧卸载确认。请关闭系统对话框并重新检查。");
            if (AndroidSyncFacade.hasUnresolvedRestore(new File(this.context.getFilesDir().getCanonicalFile(), "sync").toPath()))
                throw new IOException("存在未完成存档恢复，不能继续卸载游戏。请先处理恢复记录。");
        }
        PendingIntent pendingIntentFind = SystemConfirmationToken.find(this.context, this.prefs.getString("nonce", ""), this.prefs.getString("confirmation", null));
        if (pendingIntentFind != null) {
            return pendingIntentFind;
        }
        throw new IOException("系统确认令牌已失效（可能由于进程回收或设备重启）。不会重放不完整请求；请使用“检查失效系统请求”。");
    }

    public void acknowledgeExpiredRequest(boolean z) throws Exception {
        reconcile();
        if (hasActiveRequest()) {
            boolean z2 = SystemConfirmationToken.find(this.context, this.prefs.getString("nonce", ""), this.prefs.getString("confirmation", null)) != null;
            if (!InstallProgressPolicy.mayResetExpiredConfirmation(z, z2)) {
                throw new IOException(z2 ? "原系统确认令牌仍有效，请继续该请求并在系统界面确认或取消。" : "需先确认旧系统对话框已关闭。");
            }
            GamePreparationService.PreparedPair cleanupPair = retainedCleanupPair();
            String strPendingPackage = pendingPackage();
            if ("uninstall".equals(this.prefs.getString("operation", ""))) {
                try {
                    if (!identity(strPendingPackage).equals(this.prefs.getString("identity", ""))) {
                        throw new IOException("现有应用身份已变化，不能续用旧卸载许可。请重新准备并检查。");
                    }
                } catch (PackageManager.NameNotFoundException unused) {
                    reconcile();
                    return;
                }
            } else {
                int i = this.prefs.getInt("session", -1);
                if (i >= 0 && this.context.getPackageManager().getPackageInstaller().getSessionInfo(i) != null) {
                    this.context.getPackageManager().getPackageInstaller().abandonSession(i);
                }
            }
            clearCleanupCheckpoint(cleanupPair.embeddedBridge()
                    ? "失效请求已结束，安装包仍保留。尚未重新请求卸载；点击继续后会重新核验并再次警告、确认。"
                    : "旧版失效请求已清理，准备记录仍保留。请重新核对现有安装并准备内置存档桥游戏；不会继续旧版安装或卸载。");
        }
    }

    /** Explicit cancellation of obsolete flows; never replays a legacy PendingIntent. */
    public void cancelObsoleteLegacyRequest(boolean dialogClosedByUser) throws Exception {
        if (!dialogClosedByUser) throw new IOException("请先在 Android 中取消并关闭旧安装／卸载界面，再明确确认清理。");
        reconcile();
        if (!hasActiveRequest()) return;
        GamePreparationService.PreparedPair pair = retainedCleanupPair();
        boolean uninstall = "uninstall".equals(this.prefs.getString("operation", ""));
        if (!uninstall && !"install".equals(this.prefs.getString("operation", "")))
            throw new IOException("旧系统请求的操作类型无法核对，尚未清理。");
        boolean unchanged = !uninstall || identity(pendingPackage()).equals(this.prefs.getString("identity", ""));
        if (!InstallProgressPolicy.mayCancelObsoleteLegacy(dialogClosedByUser, pair.embeddedBridge(),
                InstallProgressPolicy.cleanupTargetMatches(pair.embeddedBridge(), pair.gamePackage, pair.helperPackage, pendingPackage()),
                uninstall, unchanged))
            throw new IOException("仅能清理已关闭界面、身份未变的旧版请求；尚未清理或重试任何操作。");
        if (!uninstall) {
            int session = this.prefs.getInt("session", -1);
            PackageInstaller installer = this.context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionInfo info = session < 0 ? null : installer.getSessionInfo(session);
            if (info != null) {
                if (!pendingPackage().equals(info.getAppPackageName()))
                    throw new IOException("系统安装会话与旧请求目标不一致，尚未取消会话。");
                installer.abandonSession(session);
            }
        }
        SystemConfirmationToken.cancel(this.context, this.prefs.getString("nonce", ""), this.prefs.getString("confirmation", null));
        clearCleanupCheckpoint(uninstall
                ? "你已确认在系统中取消并关闭旧界面；已核对原应用身份未变，仅清理旧请求令牌与记录。请重新准备内置存档桥游戏。"
                : "已取消旧版待处理安装会话并清理请求记录。不会继续旧版安装；已经发生的安装或卸载不能撤销，请重新检查并准备游戏。");
    }

    private void clearCleanupCheckpoint(String message) throws IOException {
        persist(this.prefs.edit().putString("state", "READY").putString("message", message)
                .remove("nonce").remove("confirmation").remove("session").remove("operation").remove("package")
                .remove("identity").remove("expectedApkSet").remove("expectedApk")
                .remove("uninstallPreparedApkSet").remove("uninstallOrigin"));
    }

    public void confirmationLaunched() throws IOException {
        boolean zEquals = "uninstall".equals(this.prefs.getString("operation", ""));
        persist(this.prefs.edit().putString("state", zEquals ? "UNINSTALLING" : "INSTALLING").putString("message", zEquals ? "请在 Android 卸载界面确认或取消；应用不会自动同意。" : "请在 Android 安装界面确认或取消；应用不会自动同意。"));
    }

    public void cancelSession() throws Exception {
        if ("uninstall".equals(this.prefs.getString("operation", "")) && hasActiveRequest()) {
            throw new IOException("请点击“继续系统确认”，在 Android 卸载界面取消。系统确认尚未结束，不能当作原应用已保留。");
        }
        int i = this.prefs.getInt("session", -1);
        if (i != -1) {
            PackageInstaller packageInstaller = this.context.getPackageManager().getPackageInstaller();
            if (packageInstaller.getSessionInfo(i) != null) {
                packageInstaller.abandonSession(i);
            }
        }
        SystemConfirmationToken.cancel(this.context, this.prefs.getString("nonce", ""), this.prefs.getString("confirmation", null));
        persist(this.prefs.edit().putString("state", "READY").remove("session").remove("confirmation").remove("nonce").putString("message", "已取消待处理安装请求。准备好的 APK 仍保留；已经发生的卸载不能撤销。"));
    }

    public void reconcile() throws Exception {
        String strState = state();
        String strPendingPackage = pendingPackage();
        boolean z = false;
        if ("uninstall".equals(this.prefs.getString("operation", "")) && (strState.equals("UNINSTALLING") || strState.equals("PENDING_CONFIRMATION") || strState.equals("RESULT"))) {
            try {
                String strIdentity = identity(strPendingPackage);
                if (InstallProgressPolicy.uninstallSettled(true, strState.equals("RESULT"))) {
                    persist(this.prefs.edit().putString("state", "READY").remove("confirmation").remove("nonce").putString("message", strIdentity.equals(this.prefs.getString("identity", "")) ? "系统卸载已取消或未完成，原应用仍在。继续前会重新检查并再次请求确认。" : "现有应用已变化，请重新准备游戏。"));
                    return;
                }
                return;
            } catch (PackageManager.NameNotFoundException unused) {
                SystemConfirmationToken.cancel(this.context, this.prefs.getString("nonce", ""), this.prefs.getString("confirmation", null));
                SharedPreferences.Editor editorPutString = this.prefs.edit().putString("state", "READY").remove("confirmation").remove("nonce").putString("message", "已确认应用被卸载。已验证的安装包仍在，可点击继续安装。");
                if (this.prefs.getBoolean("gameRequest", false)) {
                    editorPutString.putBoolean("gameRemoved", true);
                }
                persist(editorPutString);
                return;
            }
        }
        if (strState.equals("INSTALLING") || strState.equals("PENDING_CONFIRMATION") || strState.equals("RESULT")) {
            try {
                PackageInfo packageInfoInstalled = PackageTrust.installed(this.context, strPendingPackage);
                if (PackageTrust.signer(packageInfoInstalled).equalsIgnoreCase(this.preparation.currentLocalCertificateSha256())) {
                    if (this.prefs.contains("expectedApkSet")) {
                        if (PackageTrust.apkSetSha256(PackageTrust.installedApks(packageInfoInstalled)).equals(this.prefs.getString("expectedApkSet", ""))) {
                            z = true;
                        }
                    } else if (PackageTrust.installedApks(packageInfoInstalled).size() == 1 && PackageTrust.sha256(new File(packageInfoInstalled.applicationInfo.sourceDir)).equals(this.prefs.getString("expectedApk", ""))) {
                        z = true;
                    }
                }
            } catch (PackageManager.NameNotFoundException unused2) {
            }
            if (z) {
                SystemConfirmationToken.cancel(this.context, this.prefs.getString("nonce", ""), this.prefs.getString("confirmation", null));
            }
            if (z) {
                persist(this.prefs.edit().putString("state", "READY").remove("confirmation").remove("session").remove("nonce").putString("message", "已核对本次系统安装的签名及完整 APK 内容；仍需核验游戏内置存档桥与存档访问。"));
            } else {
                if (!strState.equals("RESULT")) {
                    if (this.context.getPackageManager().getPackageInstaller().getSessionInfo(this.prefs.getInt("session", -1)) == null) {
                        persist(this.prefs.edit().putString("state", "READY").remove("confirmation").remove("session").remove("nonce").putString("message", "上次系统会话已结束，但未验证到目标安装。安装包仍保留，可重新检查后重试。"));
                        return;
                    }
                    return;
                }
                persist(this.prefs.edit().putString("state", "READY").remove("confirmation").remove("session").remove("nonce").putString("message", "系统安装未完成或安装后校验不一致。安装包仍保留，可重新检查后重试。"));
            }
        }
    }
}
