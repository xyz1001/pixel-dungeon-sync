package io.github.xyz1001.pdsync.android;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import io.github.xyz1001.pdsync.android.BridgeDiagnostics;
import io.github.xyz1001.pdsync.android.BridgeTransport;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import pdsync.DirectoryMetadataKt;
import pdsync.ReasonedSyncFailure;
import pdsync.SyncBlockReason;
import pdsync.android.app.AndroidGameBridge;
import pdsync.android.app.AndroidGameIdentity;
import pdsync.android.app.AndroidGameTarget;


public final class BridgeClient implements AndroidGameBridge {
    private static final long MAX_BYTES = 67108864;
    private static final int MAX_FILES = 4096;
    private final Context context;
    private final AndroidGameIdentity identity;
    private final GamePreparationService preparation;
    private final GamePreparationService.PreparedPair selected;

    public BridgeClient(Context context) throws Exception {
        this(context, SyncRuntime.requirePreparedGame(context), null);
    }

    public BridgeClient(Context context, GamePreparationService.PreparedPair preparedPair, AndroidGameIdentity androidGameIdentity) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext;
        this.preparation = new GamePreparationService(applicationContext);
        this.selected = preparedPair;
        this.identity = androidGameIdentity == null ? SyncRuntime.gameIdentity(context, preparedPair) : androidGameIdentity;
    }

    public static final class Failure extends BridgeDiagnostics.Failure implements ReasonedSyncFailure {
        private static final long serialVersionUID = 1;

        @Override // pdsync.ReasonedSyncFailure
        public SyncBlockReason getSyncBlockReason() {
            return SyncBlockReason.valueOf(AutoSyncMessages.bridgeReasonCode(this.stage.name(), this.code));
        }

        public Failure(String str) {
            this(BridgeDiagnostics.Stage.SAVE_READINESS, "BRIDGE_OPERATION_REJECTED", str, null);
        }

        public Failure(BridgeDiagnostics.Stage stage, String str, String str2, Throwable th) {
            super(stage, str, str2, th);
        }

        public Failure(BridgeDiagnostics.Stage stage, String str, String str2, Throwable th, BridgeDiagnostics.TransportInfo transportInfo) {
            super(stage, str, str2, th, transportInfo);
        }
    }

    
    private Bundle exchange(final String operation, final Bundle extras) throws Exception {
        final boolean legacy;
        try { legacy = PackageTrust.requireBridgeForExchange(context, selected, preparation.currentLocalCertificateSha256()); }
        catch (Exception error) { throw new Failure(BridgeDiagnostics.Stage.PACKAGE_TRUST, "PACKAGE_TRUST_FAILED", "游戏或存档桥的身份检查未通过，请检查详细诊断。不要卸载现有游戏。", error); }
        BridgeDiagnostics.ProviderSnapshot before = snapshotProvider();
        BridgeDiagnostics.TransportInfo transport = null;
        Bundle reply = null;
        try {
            BridgeTransport.Result<Bundle> result = BridgeTransport.exchange(
                    () -> m2lambda$exchange$0$iogithubxyz1001pdsyncandroidBridgeClient(operation, extras),
                    BridgeClient::closeReply, BridgeClient::lambda$exchange$1);
            reply=result.reply; transport=result.transport;
            if(reply==null || reply.getInt("apiVersion",-1)!=1)
                throw new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY,"INVALID_PROTOCOL","存档桥协议无法确认，请复制诊断后检查。",null);
            try {
                if(legacy!=PackageTrust.requireBridgeForExchange(context,selected,preparation.currentLocalCertificateSha256()))
                    throw new Failure(BridgeDiagnostics.Stage.PACKAGE_TRUST,"RECOVERY_IDENTITY_CHANGED","存档桥的恢复身份已变化，请重新检查。",null);
            } catch(Failure error) { throw error; }
            catch(Exception error) { throw new Failure(BridgeDiagnostics.Stage.PACKAGE_TRUST,"PACKAGE_TRUST_CHANGED","存档桥调用期间应用身份发生变化，已停止访问。",error); }
            if(reply.getBoolean("ok",false) && (!replyIdentityMatches(reply,"gamePackage",selected.gamePackage,legacy)
                    || !replyIdentityMatches(reply,"originIdentity",selected.originIdentity,legacy)
                    || !replyIdentityMatches(reply,"sourceApkSetSha256",selected.sourceApkSetSha256,legacy)))
                throw new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY,"REPLY_IDENTITY_MISMATCH","存档桥返回的游戏来源与本次操作绑定不一致。",null);
            if(reply.getBoolean("ok",false) && selected.embeddedBridge()
                    && (!"embedded-v1".equals(reply.getString("bridgeMode"))
                        || !selected.bridgeRuntimeVersion.equals(reply.getString("bridgeRuntimeVersion"))
                        || !selected.gamePackage.equals(reply.getString("providerPackage"))))
                throw new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY,"REPLY_IDENTITY_MISMATCH","内置存档桥返回的运行时身份不一致。",null);
            return reply;
        } catch(Failure error) {
            closeRejectedReply(reply,error);
            if(error.transport!=null) transport=error.transport;
            if(transport==null) throw error;
            Failure enriched=new Failure(error.stage,error.code,error.getMessage(),error.getCause(),transport.withSnapshots(before,snapshotProvider()));
            for(Throwable suppressed:error.getSuppressed()) enriched.addSuppressed(suppressed);
            throw enriched;
        } catch(Exception error) {
            closeRejectedReply(reply,error);
            if(transport!=null) throw new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY,"INVALID_PROTOCOL","存档桥返回内容无法确认，请复制诊断后检查。",error,transport.withSnapshots(before,snapshotProvider()));
            throw error;
        }
    }

    BridgeTransport.Client m2lambda$exchange$0$iogithubxyz1001pdsyncandroidBridgeClient(final String str, final Bundle bundle) throws Exception {
        final ContentProviderClient contentProviderClientAcquireContentProviderClient = this.context.getContentResolver().acquireContentProviderClient(this.selected.authority);
        if (contentProviderClientAcquireContentProviderClient == null) {
            return null;
        }
        return new BridgeTransport.Client<Bundle>() { // from class: io.github.xyz1001.pdsync.android.BridgeClient.1
            
            @Override // io.github.xyz1001.pdsync.android.BridgeTransport.Client
            public Bundle call() throws Exception {
                return contentProviderClientAcquireContentProviderClient.call(str, null, bundle);
            }

            @Override // io.github.xyz1001.pdsync.android.BridgeTransport.Client
            public void close() {
                contentProviderClientAcquireContentProviderClient.close();
            }
        };
    }

    static  BridgeDiagnostics.Failure lambda$exchange$1(BridgeDiagnostics.Stage stage, String str, Throwable th, BridgeDiagnostics.TransportInfo transportInfo) {
        return new Failure(stage, str, "存档桥连接未完成，请复制诊断或重新检查。恢复结果未确认时不要启动游戏，无需重复卸载安装。", th, transportInfo);
    }

    
    public static void closeReply(Bundle bundle) throws Exception {
        ParcelFileDescriptor parcelFileDescriptorDescriptor = bundle == null ? null : AndroidCompat.descriptor(bundle, "archive");
        if (parcelFileDescriptorDescriptor != null) {
            parcelFileDescriptorDescriptor.close();
        }
    }

    private static void closeRejectedReply(Bundle bundle, Throwable th) {
        try {
            closeReply(bundle);
        } catch (Exception e) {
            th.addSuppressed(new Failure(BridgeDiagnostics.Stage.IPC_CLOSE, "IPC_REPLY_CLOSE_FAILED", "存档桥返回资源关闭失败，请保留诊断。", e));
        }
    }

    
    private BridgeDiagnostics.ProviderSnapshot snapshotProvider() {
        try {
            boolean permission=context.checkSelfPermission("io.github.xyz1001.pdsync.android.ACCESS_BRIDGE")==0;
            String host=selected.embeddedBridge()?selected.bridgeHostPackage:selected.helperPackage;
            String process=selected.embeddedBridge()?host+":pdsync_bridge":host;
            ProviderInfo provider=context.getPackageManager().resolveContentProvider(selected.authority,0);
            if(provider==null) return new BridgeDiagnostics.ProviderSnapshot(BridgeDiagnostics.SnapshotStatus.NOT_FOUND,permission,false,false,false,false,false,false,false,false,false,false,false);
            ApplicationInfo app=provider.applicationInfo;
            return new BridgeDiagnostics.ProviderSnapshot(BridgeDiagnostics.SnapshotStatus.RESOLVED,permission,
                    provider.enabled,app!=null,app!=null&&app.enabled,app!=null&&(app.flags&ApplicationInfo.FLAG_STOPPED)!=0,
                    app!=null&&(app.flags&ApplicationInfo.FLAG_SUSPENDED)!=0,selected.authority.equals(provider.authority),
                    "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider".equals(provider.name),host.equals(provider.packageName),
                    process.equals(provider.processName),provider.exported,provider.grantUriPermissions);
        } catch(Exception error) {
            return new BridgeDiagnostics.ProviderSnapshot(BridgeDiagnostics.SnapshotStatus.READ_FAILED,false,false,false,false,false,false,false,false,false,false,false,false);
        }
    }

    private static boolean replyIdentityMatches(Bundle bundle, String str, String str2, boolean z) {
        return !bundle.containsKey(str) ? z : str2.equals(bundle.getString(str));
    }

    private Bundle call(String str, Bundle bundle) throws Exception {
        BridgeDiagnostics.Stage stage;
        Bundle bundleExchange = exchange(str, bundle);
        if (bundleExchange.getBoolean("ok", false)) {
            return bundleExchange;
        }
        String string = bundleExchange.getString("code", "UNKNOWN");
        if ("export".equals(str)) {
            stage = BridgeDiagnostics.Stage.EXPORT;
        } else if ("restore".equals(str)) {
            stage = BridgeDiagnostics.Stage.RESTORE;
        } else {
            stage = "recover".equals(str) ? BridgeDiagnostics.Stage.RECOVERY : BridgeDiagnostics.Stage.SAVE_READINESS;
        }
        Failure failure = helperFailure(string, stage, bundleExchange.getString("message", ""), BridgeDiagnostics.helperFileDetail(bundleExchange.getString("fileStage"), bundleExchange.getString("fileError"), bundleExchange.getInt("fileErrno", -1)));
        closeRejectedReply(bundleExchange, failure);
        throw failure;
    }

    private static Failure helperFailure(String str) {
        return helperFailure(str, BridgeDiagnostics.Stage.SAVE_READINESS, null);
    }

    private static Failure helperFailure(String str, BridgeDiagnostics.Stage stage, String str2) {
        return helperFailure(str, stage, str2, null);
    }

    private static Failure helperFailure(String str, BridgeDiagnostics.Stage stage, String str2, Throwable th) {
        String str3;
        if ("UNRECOGNIZED_SAVE_LAYOUT".equals(str)) {
            str3 = "尚不能识别这个游戏的存档布局，未把它当成空存档，也未套用原版恢复或合并。请检查所选游戏与存档位置。";
        } else if ("FIRST_LAUNCH_NEEDED".equals(str)) {
            str3 = "请先启动一次游戏并正常退出，生成存档目录后再同步。";
        } else if ("GAME_FOREGROUND".equals(str) || "GAME_RUNNING".equals(str) || "PROCESS_STATE_UNKNOWN".equals(str) || "SAVE_NOT_QUIET".equals(str)) {
            str3 = "游戏仍在前台、存档仍在写入或状态暂不明确。正常退出游戏，稍后重试即可，无需强制结束。";
        } else if (str != null && (str.contains("CHANGED") || str.contains("CONFLICT") || str.contains("FINGERPRINT"))) {
            str3 = "本地存档已变化，操作已停止。请重新检查存档后再选择，期间不要启动游戏。";
        } else if ("RESTORE_COMMITTED_CLEANUP_PENDING".equals(str)) {
            str3 = "存档已恢复并核对，但临时文件清理尚未完成。请检查恢复记录确认结果，不要重复恢复或卸载游戏。";
        } else if (str != null && (str.contains("RECOVER") || str.contains("ROLLBACK"))) {
            str3 = "存档桥遇到未完成的存档写入。请不要重新启动游戏，先检查恢复记录。";
        } else if ("SAVE_IO_ERROR".equals(str)) {
            str3 = "存档文件操作未完成，请查看详细诊断中的写入阶段和原因，并检查恢复记录后再启动游戏。无需卸载游戏。";
        } else {
            str3 = "存档桥拒绝了本次访问，请复制诊断或重新检查。无需重复卸载安装。";
        }
        if (th == null) {
            th = BridgeDiagnostics.helperDetail(str, str2);
        }
        return new Failure(stage, str, str3, th);
    }

    public static final class Observation {
        public final boolean foreground;
        public final boolean processPresent;
        public final boolean ready;
        public final boolean reliable;

        Observation(Bundle bundle) {
            this.reliable = bundle.getBoolean("observationReliable", false);
            this.foreground = bundle.getBoolean("gameForeground", false);
            this.processPresent = bundle.getBoolean("gameProcessPresent", false);
            this.ready = bundle.getBoolean("ready", false);
        }
    }

    public Observation observeLifecycle() {
        try {
            return new Observation(exchange("inspect", null));
        } catch (Exception unused) {
            return new Observation(new Bundle());
        }
    }

    public String inspectReady() {
        try {
            Bundle bundleCall = call("inspect", null);
            if (!this.selected.legacyUnboundHelper && (!bundleCall.containsKey("firstLaunchNeeded") || !bundleCall.containsKey("transferReady"))) {
                throw new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY, "READINESS_FIELDS_MISSING", "存档桥未返回完整的存档状态，请复制诊断。", null);
            }
            String strFailureCode = BridgeReadiness.failureCode(bundleCall.getBoolean("ready", false), bundleCall.getBoolean("firstLaunchNeeded", false), bundleCall.containsKey("transferReady") ? Boolean.valueOf(bundleCall.getBoolean("transferReady", false)) : null);
            if (strFailureCode != null) {
                throw helperFailure(strFailureCode);
            }
            return "游戏、存档桥和存档目录已验证；可开始稳定性检查。";
        } catch (Failure e) {
            throw e;
        } catch (Exception e2) {
            throw new Failure(BridgeDiagnostics.Stage.SAVE_READINESS, "INSPECT_FAILED", "无法确认存档访问状态，请复制诊断。不要卸载现有游戏。", e2);
        }
    }

    public String recoverInterruptedBackup() {
        try {
            return "存档桥已恢复并验证中断前备份（" + call("recover", null).getInt("fileCount", 0) + " 个文件）。";
        } catch (Failure e) {
            throw e;
        } catch (Exception e2) {
            throw new Failure(BridgeDiagnostics.Stage.RECOVERY, "RECOVERY_FAILED", "无法确认备份恢复，请不要重新启动游戏并重新检查。", e2);
        }
    }

    @Override // pdsync.android.app.AndroidGameBridge
    public AndroidGameTarget targetGame() {
        try {
            boolean zRequireBridgeForExchange = PackageTrust.requireBridgeForExchange(this.context, this.selected, this.preparation.currentLocalCertificateSha256());
            PackageInfo packageInfoInstalled = PackageTrust.installed(this.context, this.selected.gamePackage);
            String strApkSetSha256 = PackageTrust.apkSetSha256(PackageTrust.installedApks(packageInfoInstalled));
            if (!strApkSetSha256.equals(PackageTrust.apkSetSha256(this.selected.gameApks))) {
                throw new Failure(BridgeDiagnostics.Stage.TARGET_IDENTITY, "INSTALLED_APK_SET_MISMATCH", "已安装的完整 APK 集合与所选游戏不一致，请重新检查安装状态。", null);
            }
            String string = (packageInfoInstalled.versionName == null || packageInfoInstalled.versionName.trim().isEmpty()) ? Long.toString(PackageTrust.versionCode(packageInfoInstalled)) : packageInfoInstalled.versionName;
            if (zRequireBridgeForExchange && !PackageTrust.legacyRecoveryMarkerPresent(this.context.getFilesDir())) {
                throw new Failure(BridgeDiagnostics.Stage.TARGET_IDENTITY, "LEGACY_RECOVERY_ENDED", "原有恢复记录已结束。请重新准备绑定来源的游戏与存档桥。", null);
            }
            return new AndroidGameTarget(this.identity, string, PackageTrust.versionCode(packageInfoInstalled), strApkSetSha256, this.selected.sourceApkSetSha256, zRequireBridgeForExchange);
        } catch (Failure e) {
            throw e;
        } catch (Exception e2) {
            throw new Failure(BridgeDiagnostics.Stage.TARGET_IDENTITY, "TARGET_IDENTITY_FAILED", "所选游戏、分包或签名未通过检查，请重新检查身份与诊断。不要卸载现有游戏。", e2);
        }
    }

    
    
    
    
    
    
    
    
    @Override // pdsync.android.app.AndroidGameBridge
    
    public void exportTo(Path path) {
        try {
            targetGame();
            Bundle reply=call("export",null);
            ParcelFileDescriptor descriptor=AndroidCompat.descriptor(reply,"archive");
            if(descriptor==null) throw new Failure("存档桥未返回存档数据。");
            SaveArchiveCodec.Inventory inventory;
            try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                inventory=SaveArchiveCodec.readExportArchive(input,path);
            }
            if(!DirectoryMetadataKt.contentSha256(inventory.hashes).equals(reply.getString("fingerprint"))
                    || inventory.hashes.size()!=reply.getInt("fileCount",-1) || inventory.bytes!=reply.getLong("byteCount",-1L))
                throw new Failure("存档导出校验失败，已停止同步。");
        } catch(Failure error) { throw error; }
        catch(Exception error) { throw new Failure(BridgeDiagnostics.Stage.EXPORT,"EXPORT_FAILED","存档读取失败。请正常退出游戏，等待存档写入稳定后重新检查。",error); }
    }

    @Override
    public void restoreFrom(Path path, Map<String,String> expectedHashes) {
        File archive=null;
        try {
            targetGame();
            String expected=DirectoryMetadataKt.contentSha256(expectedHashes);
            archive=File.createTempFile("restore-",".zip",context.getNoBackupFilesDir());
            SaveArchiveCodec.writeRestoreArchive(path,archive);
            try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(archive,ParcelFileDescriptor.MODE_READ_ONLY)) {
                Bundle extras=new Bundle(); extras.putParcelable("archive",descriptor); extras.putString("expectedFingerprint",expected);
                Bundle reply=call("restore",extras); closeReply(reply);
            }
        } catch(Failure error) { throw error; }
        catch(Exception error) { throw new Failure(BridgeDiagnostics.Stage.RESTORE,"RESTORE_FAILED","存档恢复结果尚未确认。请不要重新启动游戏，运行“检查未完成恢复”后再继续。",error); }
        finally { if(archive!=null) archive.delete(); }
    }

    private static void checkName(String str) {
        if (str.length() > 1024 || str.startsWith("/") || str.contains("\\") || str.contains(":")) {
            throw new Failure("存档路径不安全。");
        }
        for (String str2 : str.split("/", -1)) {
            if (!str2.matches("[A-Za-z0-9_.-]+") || str2.equals(".") || str2.equals("..") || str2.endsWith(".")) {
                throw new Failure("存档路径不安全。");
            }
        }
        if (!str.equals("rankings.dat") && !str.equals("badges.dat") && !str.equals("journal.dat") && !str.matches("game[0-9]+/.+")) {
            throw new Failure("存档包含未允许的文件。");
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        LimitedInputStream(InputStream inputStream, long j) {
            super(inputStream);
            this.remaining = j;
        }

        @Override // java.io.FilterInputStream, java.io.InputStream
        public int read() throws IOException {
            int i = super.read();
            if (i >= 0) {
                long j = this.remaining - 1;
                this.remaining = j;
                if (j < 0) {
                    throw new IOException("Archive too large");
                }
            }
            return i;
        }

        @Override // java.io.FilterInputStream, java.io.InputStream
        public int read(byte[] bArr, int i, int i2) throws IOException {
            int i3 = super.read(bArr, i, i2);
            if (i3 > 0) {
                long j = this.remaining - i3;
                this.remaining = j;
                if (j < 0) {
                    throw new IOException("Archive too large");
                }
            }
            return i3;
        }
    }
}
