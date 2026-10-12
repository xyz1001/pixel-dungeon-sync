package io.github.xyz1001.pdsync.android;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import io.github.xyz1001.pdsync.android.prepare.HelperUpdatePolicy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
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

    public void registerPrepared(GamePreparationService.PreparedPair preparedPair) throws Exception {
        String str;
        assertCanPrepare();
        this.preparation.validatePrepared(preparedPair);
        if (preparedPair.helperOnlyUpgrade) {
            this.preparation.validateHelperUpdateGame(preparedPair);
        }
        SyncRuntime.setPreparedGame(this.context, preparedPair);
        SharedPreferences.Editor editorPutString = this.prefs.edit().putString("state", "READY");
        if (preparedPair.helperOnlyUpgrade) {
            str = "已准备助手兼容更新，游戏 APK 原字节保留，仅允许覆盖助手，无需卸载游戏。";
        } else {
            str = "两个安装包已暂存并通过签名与内容校验，尚未卸载或安装应用。";
        }
        persist(editorPutString.putString("message", str).remove("confirmation").remove("session").remove("package").remove("nonce"));
    }

    public GamePreparationService.PreparedPair freshPair() throws Exception {
        GamePreparationService.PreparedPair preparedPairReloadPrepared = this.preparation.reloadPrepared();
        if (preparedPairReloadPrepared == null) {
            throw new IOException("未找到完整的安装包，请重新准备游戏。");
        }
        this.preparation.validatePrepared(preparedPairReloadPrepared);
        GamePreparationService.PreparedPair preparedPairRefreshInstallStrategies = this.preparation.refreshInstallStrategies(preparedPairReloadPrepared);
        SyncRuntime.setPreparedGame(this.context, preparedPairRefreshInstallStrategies);
        return preparedPairRefreshInstallStrategies;
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

    public String nextPackage(GamePreparationService.PreparedPair preparedPair) throws Exception {
        if (preparedPair.helperOnlyUpgrade) {
            this.preparation.validateHelperUpdateGame(preparedPair);
            if (preparedPair.helperStrategy == GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL) {
                throw new IOException("助手与保留的升级包不兼容，已停止；仅助手升级不会卸载应用或重新安装游戏。");
            }
            if (!installedMatches(preparedPair.helperPackage, Collections.singletonList(preparedPair.helperApk))) {
                return preparedPair.helperPackage;
            }
            PackageTrust.requireBridge(this.context, preparedPair, preparedPair.signingCertificateSha256);
            return null;
        }
        if (preparedPair.gameStrategy == GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL) {
            return preparedPair.gamePackage;
        }
        if (!installedMatches(preparedPair.helperPackage, Collections.singletonList(preparedPair.helperApk))) {
            return preparedPair.helperPackage;
        }
        if (!installedMatches(preparedPair.gamePackage, preparedPair.gameApks)) {
            return preparedPair.gamePackage;
        }
        PackageTrust.requireBridge(this.context, preparedPair, preparedPair.signingCertificateSha256);
        return null;
    }

    private String identity(String str) throws Exception {
        PackageInfo packageInfoInstalled = PackageTrust.installed(this.context, str);
        return packageInfoInstalled.firstInstallTime + ":" + packageInfoInstalled.lastUpdateTime + ":" + PackageTrust.versionCode(packageInfoInstalled) + ":" + PackageTrust.signer(packageInfoInstalled) + ":" + PackageTrust.apkSetSha256(PackageTrust.installedApks(packageInfoInstalled));
    }

    private static boolean isGame(GamePreparationService.PreparedPair preparedPair, String str) throws IOException {
        if (preparedPair.gamePackage.equals(str)) {
            return true;
        }
        if (preparedPair.helperPackage.equals(str)) {
            return false;
        }
        throw new IOException("系统请求与当前已验证的游戏/助手不一致。");
    }

    public String uninstallConsentReceipt(String str, GamePreparationService.PreparedPair preparedPair) throws Exception {
        HelperUpdatePolicy.requireInstallAction(preparedPair.helperOnlyUpgrade, preparedPair.helperPackage, str, true);
        this.preparation.validatePrepared(preparedPair);
        return PackageTrust.apkSetSha256(preparedPair.gameApks) + ":" + PackageTrust.sha256(preparedPair.helperApk) + ":" + identity(str);
    }

    public void requestUninstall(String str, String str2) throws Exception {
        if (hasActiveRequest()) {
            throw new IOException("请先完成上一个系统请求。");
        }
        GamePreparationService.PreparedPair preparedPairFreshPair = freshPair();
        HelperUpdatePolicy.requireInstallAction(preparedPairFreshPair.helperOnlyUpgrade, preparedPairFreshPair.helperPackage, str, true);
        if ((isGame(preparedPairFreshPair, str) ? preparedPairFreshPair.gameStrategy : preparedPairFreshPair.helperStrategy) != GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL || !uninstallConsentReceipt(str, preparedPairFreshPair).equals(str2)) {
            throw new IOException("安装包或现有应用已变化。请重新检查后再次确认，尚未请求卸载。");
        }
        if (AndroidSyncFacade.hasUnresolvedRestore(new File(this.context.getFilesDir().getCanonicalFile(), "sync").toPath())) {
            throw new IOException("存在未完成存档恢复，不能卸载游戏或助手。请先处理恢复记录与备份。");
        }
        String string = UUID.randomUUID().toString();
        persist(this.prefs.edit().putString("state", "UNINSTALLING").putString("operation", "uninstall").putString("package", str).putString("identity", identity(str)).putBoolean("gameRequest", isGame(preparedPairFreshPair, str)).putString("nonce", string).remove("session").remove("confirmation").putString("message", "等待 Android 系统卸载确认与结果。尚不能确认应用是否被删除。"));
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
        GamePreparationService.PreparedPair pair=freshPair();
        HelperUpdatePolicy.requireInstallAction(pair.helperOnlyUpgrade,pair.helperPackage,packageName,false);
        if(pair.helperOnlyUpgrade) { assertCanPrepare(); preparation.validateHelperUpdateGame(pair); }
        boolean game=isGame(pair,packageName);
        if((game?pair.gameStrategy:pair.helperStrategy)==GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL)
            throw new IOException("当前应用仍需用户确认卸载，不能直接覆盖安装。");
        List<File> apks=game?pair.gameApks:Collections.singletonList(pair.helperApk);
        if(installedMatches(packageName,apks)) { reconcile(); return; }
        PackageInstaller installer=context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName); params.setAppLabel(game?pair.gameLabel:pair.gameLabel+" · 存档助手");
        long size=0; for(File apk:apks) { size=Math.addExact(size,apk.length()); }
        params.setSize(size); params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int sessionId=installer.createSession(params); String nonce=UUID.randomUUID().toString(); boolean commitAttempted=false;
        try(PackageInstaller.Session session=installer.openSession(sessionId)) {
            ArrayList<String> writtenHashes=new ArrayList<>();
            for(int i=0;i<apks.size();i++) {
                File apk=apks.get(i); String name=i==0?"base.apk":"split_"+i+".apk";
                try(InputStream input=new FileInputStream(apk); OutputStream output=session.openWrite(name,0,apk.length())) {
                    byte[] buffer=new byte[65536];int n;
                    while((n=input.read(buffer))!=-1) output.write(buffer,0,n);
                    session.fsync(output);
                }
                try(InputStream staged=session.openRead(name)) {
                    MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;
                    while((n=staged.read(buffer))!=-1) hash.update(buffer,0,n);
                    writtenHashes.add(PackageTrust.hex(hash.digest()));
                }
            }
            preparation.validatePrepared(pair);
            if(pair.helperOnlyUpgrade) { assertCanPrepare(); preparation.validateHelperUpdateGame(pair); }
            String expected=ApkSetIdentity.digest(writtenHashes);
            if(!expected.equals(PackageTrust.apkSetSha256(apks)) || session.getNames().length!=apks.size())
                throw new IOException("系统暂存 APK 完整集合校验失败。");
            persist(prefs.edit().putString("state","INSTALLING").putString("operation","install").putString("package",packageName)
                    .putInt("session",sessionId).putString("nonce",nonce).putString("expectedApkSet",expected).putBoolean("gameRequest",game)
                    .remove("expectedApk").remove("confirmation").putString("message","安装包已交给 Android，等待系统确认或结果。"));
            PendingIntent callback=PendingIntent.getBroadcast(context,sessionId,new Intent(context,InstallResultReceiver.class)
                    .setAction(CHANGED+"."+nonce).putExtra("requestNonce",nonce),AndroidCompat.mutableCallbackFlags());
            // Once commit is attempted its result is uncertain, even if commit or close throws.
            commitAttempted=true; session.commit(callback.getIntentSender());
        } catch(Exception error) {
            if(commitAttempted) {
                try { persist(prefs.edit().putString("message","安装提交结果未知。进度已保留，请返回后检查或取消系统会话；不会自动重复提交。")); }
                catch(IOException checkpointError) { error.addSuppressed(checkpointError); }
            } else {
                try { installer.abandonSession(sessionId); } catch(Exception cleanupError) { error.addSuppressed(cleanupError); }
            }
            throw error;
        }
    }

    public PendingIntent confirmationToken() throws Exception {
        if (!hasConfirmation()) {
            throw new IOException("没有可恢复的系统确认令牌，请使用“检查失效系统请求”。");
        }
        GamePreparationService.PreparedPair preparedPairFreshPair = freshPair();
        if (preparedPairFreshPair.helperOnlyUpgrade) {
            HelperUpdatePolicy.requireResumeConfirmation(true, preparedPairFreshPair.helperPackage, pendingPackage(), this.prefs.getString("operation", ""), AndroidSyncFacade.hasUnresolvedRestore(new File(this.context.getFilesDir().getCanonicalFile(), "sync").toPath()));
            this.preparation.validateHelperUpdateGame(preparedPairFreshPair);
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
            freshPair();
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
            persist(this.prefs.edit().putString("state", "READY").remove("nonce").remove("confirmation").remove("session").putString("message", "失效请求已结束，安装包仍保留。尚未重新请求卸载；点击继续后会重新核验并再次警告、确认。"));
        }
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
                persist(this.prefs.edit().putString("state", "READY").remove("confirmation").remove("session").remove("nonce").putString("message", "已核对本次系统安装的签名及完整 APK 内容；仍需检查另一应用与存档助手连接。"));
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
