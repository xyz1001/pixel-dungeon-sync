package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

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
import pdsync.android.app.AndroidGameIdentity;
import pdsync.android.app.AndroidGameTarget;


public final class BridgeClient implements GameAccessBackend {
    private static final Capabilities CAPABILITIES = new Capabilities(true, true, true, true);
    private static final long MAX_BYTES = 67108864;
    private static final int MAX_FILES = 4096;
    private final Context context;
    private final AndroidGameIdentity identity;
    private final InstalledBridgeBinding installedSelection;
    private final GamePreparationService.PreparedPair selected;

    public BridgeClient(Context context) throws Exception {
        this(context, SyncRuntime.requirePreparedGame(context), null);
    }

    public BridgeClient(Context context, GamePreparationService.PreparedPair preparedPair, AndroidGameIdentity androidGameIdentity) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext;
        this.installedSelection = null;
        this.selected = preparedPair;
        this.identity = androidGameIdentity == null ? SyncRuntime.gameIdentity(context, preparedPair) : androidGameIdentity;
    }

    public BridgeClient(Context context, InstalledBridgeBinding binding, AndroidGameIdentity identity) {
        this.context = context.getApplicationContext();
        this.selected = null;
        this.installedSelection = java.util.Objects.requireNonNull(binding);
        this.identity = identity == null ? SyncRuntime.gameIdentity(context, binding) : identity;
    }

    private String gamePackage() { return installedSelection == null ? selected.gamePackage : installedSelection.gamePackage; }
    private String originIdentity() { return installedSelection == null ? selected.originIdentity : installedSelection.originIdentity; }
    private String sourceHash() { return installedSelection == null ? selected.sourceApkSetSha256 : installedSelection.sourceApkSetSha256; }
    private String authority() { return installedSelection == null ? selected.authority : installedSelection.authority; }
    private String runtimeVersion() { return installedSelection == null ? selected.bridgeRuntimeVersion : installedSelection.bridgeRuntimeVersion; }
    private boolean embedded() { return installedSelection != null || selected.embeddedBridge(); }
    private boolean legacyUnbound() { return installedSelection == null && selected.legacyUnboundHelper; }
    private String providerPackage() { return embedded() ? gamePackage() : selected.helperPackage; }
    private boolean requireTrust(boolean accessOnly) throws Exception {
        if (installedSelection != null) {
            InstalledBridgeResolver.requireUnchanged(context, installedSelection);
            return false;
        }
        return accessOnly ? PackageTrust.requireBridgeForAccess(context, selected)
                : PackageTrust.requireBridgeForExchange(context, selected, selected.signingCertificateSha256);
    }

    /** First-page handshake only. An old runtime must be updated; inspect is never a fallback. */
    public String authenticateAccess() {
        if (!embedded()) throw upgradeRequired(null);
        try {
            InstalledBridgeBinding binding = installedSelection == null
                    ? InstalledBridgeResolver.resolve(context, selected.gamePackage) : installedSelection;
            requirePureAccessProtocol(context, binding);
            Bundle reply = call("authenticate", null);
            try {
                if ((!reply.getBoolean("accessAuthenticated", false) && !reply.getBoolean("authenticated", false))
                        || reply.getInt("accessProtocolVersion", -1) != 1 || !reply.getBoolean("metadataOnly", false))
                    throw upgradeRequired(null);
                if (reply.containsKey("archive"))
                    throw new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY, "INVALID_PROTOCOL", AndroidMessages.BRIDGE_CLIENT_01.text(), null);
                return AndroidMessages.BRIDGE_CLIENT_02.text();
            } finally { closeReply(reply); }
        } catch (Failure failure) {
            if (failure.getCause() instanceof IllegalArgumentException
                    || "UNKNOWN_METHOD".equals(failure.code) || "UNSUPPORTED_METHOD".equals(failure.code))
                throw upgradeRequired(failure);
            throw failure;
        } catch (Exception error) {
            throw new Failure(BridgeDiagnostics.Stage.IPC_CALL, "ACCESS_AUTHENTICATION_FAILED", AndroidMessages.BRIDGE_CLIENT_03.text(), error);
        }
    }
    private static volatile String bundledRuntimeHash;
    static void requirePureAccessProtocol(Context context, InstalledBridgeBinding binding) throws Exception {
        if (binding == null) throw upgradeRequired(null);
        if ("1".equals(binding.accessProtocol)) return;
        String current = bundledRuntimeHash;
        if (current == null) synchronized (BridgeClient.class) {
            current = bundledRuntimeHash;
            if (current == null) try (InputStream stream = context.getAssets().open("bridge-runtime.dex")) {
                current = InstalledBridgeProtocol.boundedHash(stream); bundledRuntimeHash = current;
            }
        }
        try { InstalledBridgeProtocol.requireKnownRuntime(new File(binding.apkPaths.get(0)), current); }
        catch (Exception unknown) { throw upgradeRequired(unknown); }
        InstalledBridgeResolver.requireUnchanged(context, binding);
    }

    private static Failure upgradeRequired(Throwable cause) {
        return new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY, "PURE_HANDSHAKE_REQUIRED",
                AndroidMessages.BRIDGE_CLIENT_04.text(), cause);
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

        Failure(BridgeDiagnostics.Stage stage, String code, String message, Throwable cause,
                BridgeDiagnostics.TransportInfo transport, BridgeSaveLayout saveLayout) {
            super(stage, code, message, cause, transport, saveLayout);
        }
    }

    
    private Bundle exchange(final String operation, final Bundle extras) throws Exception {
        final boolean legacy;
        try { legacy = requireTrust("authenticate".equals(operation)); }
        catch (Failure error) { throw error; }
        catch (Exception error) { throw new Failure(BridgeDiagnostics.Stage.PACKAGE_TRUST, "PACKAGE_TRUST_FAILED", AndroidMessages.BRIDGE_CLIENT_05.text(), error); }
        BridgeDiagnostics.ProviderSnapshot before = snapshotProvider();
        BridgeDiagnostics.TransportInfo transport = null;
        Bundle reply = null;
        try {
            BridgeTransport.Result<Bundle> result = "authenticate".equals(operation)
                    ? BridgeTransport.exchangeBounded(
                        () -> m2lambda$exchange$0$iogithubxyz1001pdsyncandroidBridgeClient(operation, extras),
                        BridgeClient::closeReply, BridgeClient::lambda$exchange$1, 8000)
                    : BridgeTransport.exchange(
                        () -> m2lambda$exchange$0$iogithubxyz1001pdsyncandroidBridgeClient(operation, extras),
                        BridgeClient::closeReply, BridgeClient::lambda$exchange$1);
            reply=result.reply; transport=result.transport;
            if(reply==null || reply.getInt("apiVersion",-1)!=1)
                throw new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY,"INVALID_PROTOCOL",AndroidMessages.BRIDGE_CLIENT_06.text(),null);
            try {
                if(legacy!=requireTrust("authenticate".equals(operation)))
                    throw new Failure(BridgeDiagnostics.Stage.PACKAGE_TRUST,"RECOVERY_IDENTITY_CHANGED",AndroidMessages.BRIDGE_CLIENT_07.text(),null);
            } catch(Failure error) { throw error; }
            catch(Exception error) { throw new Failure(BridgeDiagnostics.Stage.PACKAGE_TRUST,"PACKAGE_TRUST_CHANGED",AndroidMessages.BRIDGE_CLIENT_08.text(),error); }
            if(reply.getBoolean("ok",false) && (!replyIdentityMatches(reply,"gamePackage",gamePackage(),legacy)
                    || !replyIdentityMatches(reply,"originIdentity",originIdentity(),legacy)
                    || !replyIdentityMatches(reply,"sourceApkSetSha256",sourceHash(),legacy)))
                throw new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY,"REPLY_IDENTITY_MISMATCH",AndroidMessages.BRIDGE_CLIENT_09.text(),null);
            if(reply.getBoolean("ok",false) && embedded()
                    && (!"embedded-v1".equals(reply.getString("bridgeMode"))
                        || !runtimeVersion().equals(reply.getString("bridgeRuntimeVersion"))
                        || !gamePackage().equals(reply.getString("providerPackage"))
                        || !authority().equals(reply.getString("authority"))
                        || !(installedSelection == null ? selected.namespaceId : installedSelection.namespaceId).equals(reply.getString("namespaceId"))
                        || reply.getLong("boundGameVersionCode", -1) != (installedSelection == null ? selected.gameVersionCode : installedSelection.gameVersionCode)))
                throw new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY,"REPLY_IDENTITY_MISMATCH",AndroidMessages.BRIDGE_CLIENT_10.text(),null);
            return reply;
        } catch(Failure error) {
            closeRejectedReply(reply,error);
            if(error.transport!=null) transport=error.transport;
            if(transport==null) throw error;
            Failure enriched=new Failure(error.stage,error.code,error.getMessage(),error.getCause(),transport.withSnapshots(before,snapshotProvider()),error.saveLayout);
            for(Throwable suppressed:error.getSuppressed()) enriched.addSuppressed(suppressed);
            throw enriched;
        } catch(Exception error) {
            closeRejectedReply(reply,error);
            if(transport!=null) throw new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY,"INVALID_PROTOCOL",AndroidMessages.BRIDGE_CLIENT_11.text(),error,transport.withSnapshots(before,snapshotProvider()));
            throw error;
        }
    }

    BridgeTransport.Client m2lambda$exchange$0$iogithubxyz1001pdsyncandroidBridgeClient(final String str, final Bundle bundle) throws Exception {
        final ContentProviderClient contentProviderClientAcquireContentProviderClient = this.context.getContentResolver().acquireContentProviderClient(authority());
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
        return new Failure(stage, str, AndroidMessages.BRIDGE_CLIENT_12.text(), th, transportInfo);
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
            th.addSuppressed(new Failure(BridgeDiagnostics.Stage.IPC_CLOSE, "IPC_REPLY_CLOSE_FAILED", AndroidMessages.BRIDGE_CLIENT_13.text(), e));
        }
    }

    
    private BridgeDiagnostics.ProviderSnapshot snapshotProvider() {
        try {
            boolean permission=context.checkSelfPermission("io.github.xyz1001.pdsync.android.ACCESS_BRIDGE")==0;
            String host=providerPackage();
            String process=embedded()?host+":pdsync_bridge":host;
            ProviderInfo provider=context.getPackageManager().resolveContentProvider(authority(),0);
            if(provider==null) return new BridgeDiagnostics.ProviderSnapshot(BridgeDiagnostics.SnapshotStatus.NOT_FOUND,permission,false,false,false,false,false,false,false,false,false,false,false);
            ApplicationInfo app=provider.applicationInfo;
            return new BridgeDiagnostics.ProviderSnapshot(BridgeDiagnostics.SnapshotStatus.RESOLVED,permission,
                    provider.enabled,app!=null,app!=null&&app.enabled,app!=null&&(app.flags&ApplicationInfo.FLAG_STOPPED)!=0,
                    app!=null&&(app.flags&ApplicationInfo.FLAG_SUSPENDED)!=0,authority().equals(provider.authority),
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
            str3 = AndroidMessages.BRIDGE_CLIENT_14.text();
        } else if ("FIRST_LAUNCH_NEEDED".equals(str)) {
            str3 = AndroidMessages.BRIDGE_CLIENT_15.text();
        } else if ("GAME_FOREGROUND".equals(str) || "GAME_RUNNING".equals(str) || "PROCESS_STATE_UNKNOWN".equals(str) || "SAVE_NOT_QUIET".equals(str)) {
            str3 = AndroidMessages.BRIDGE_CLIENT_16.text();
        } else if (str != null && (str.contains("CHANGED") || str.contains("CONFLICT") || str.contains("FINGERPRINT"))) {
            str3 = AndroidMessages.BRIDGE_CLIENT_17.text();
        } else if ("RESTORE_COMMITTED_CLEANUP_PENDING".equals(str)) {
            str3 = AndroidMessages.BRIDGE_CLIENT_18.text();
        } else if (str != null && (str.contains("RECOVER") || str.contains("ROLLBACK"))) {
            str3 = AndroidMessages.BRIDGE_CLIENT_19.text();
        } else if ("SAVE_IO_ERROR".equals(str)) {
            str3 = AndroidMessages.BRIDGE_CLIENT_20.text();
        } else {
            str3 = AndroidMessages.BRIDGE_CLIENT_21.text();
        }
        if (th == null) {
            th = BridgeDiagnostics.helperDetail(str, str2);
        }
        return new Failure(stage, str, str3, th);
    }

    @Override
    public Mode mode() {
        return embedded() ? Mode.EMBEDDED_BRIDGE : Mode.LEGACY_HELPER_BRIDGE;
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Observation observeLifecycle() {
        try {
            Bundle reply = exchange("inspect", null);
            return new Observation(reply.getBoolean("gameForeground", false),
                    reply.getBoolean("gameProcessPresent", false),
                    reply.getBoolean("ready", false),
                    reply.getBoolean("observationReliable", false));
        } catch (Exception unused) {
            return Observation.unknown();
        }
    }

    @Override
    public String inspectReady() {
        try {
            Bundle bundleCall = call("inspect", null);
            if (!legacyUnbound() && (!bundleCall.containsKey("firstLaunchNeeded") || !bundleCall.containsKey("transferReady"))) {
                throw new Failure(BridgeDiagnostics.Stage.PROTOCOL_REPLY, "READINESS_FIELDS_MISSING", AndroidMessages.BRIDGE_CLIENT_22.text(), null);
            }
            String strFailureCode = BridgeReadiness.failureCode(bundleCall.getBoolean("ready", false), bundleCall.getBoolean("firstLaunchNeeded", false), bundleCall.containsKey("transferReady") ? Boolean.valueOf(bundleCall.getBoolean("transferReady", false)) : null);
            if (strFailureCode != null) {
                Failure readiness = helperFailure(strFailureCode);
                throw new Failure(readiness.stage, readiness.code, readiness.getMessage(), readiness.getCause(),
                        readiness.transport, BridgeSaveLayout.fromReply(bundleCall));
            }
            pdsync.DiagnosticLog.record(pdsync.DiagnosticStage.READINESS, pdsync.DiagnosticResult.SUCCEEDED,
                    pdsync.DiagnosticFailure.NONE, AndroidDiagnosticLogging.layoutDetails(
                            embedded() ? "EMBEDDED_BRIDGE" : "LEGACY_HELPER_BRIDGE",
                            BridgeSaveLayout.fromReply(bundleCall)));
            return AndroidMessages.BRIDGE_CLIENT_23.text();
        } catch (Failure e) {
            throw e;
        } catch (Exception e2) {
            throw new Failure(BridgeDiagnostics.Stage.SAVE_READINESS, "INSPECT_FAILED", AndroidMessages.BRIDGE_CLIENT_24.text(), e2);
        }
    }

    @Override
    public String recoverInterruptedBackup() {
        try {
            return AndroidMessages.BRIDGE_RECOVERED_FILES.text(call("recover", null).getInt("fileCount", 0));
        } catch (Failure e) {
            throw e;
        } catch (Exception e2) {
            throw new Failure(BridgeDiagnostics.Stage.RECOVERY, "RECOVERY_FAILED", AndroidMessages.BRIDGE_CLIENT_27.text(), e2);
        }
    }

    @Override // pdsync.android.app.AndroidGameBridge
    public AndroidGameTarget targetGame() {
        try {
            boolean zRequireBridgeForExchange = requireTrust(false);
            PackageInfo packageInfoInstalled = PackageTrust.installed(this.context, gamePackage());
            String strApkSetSha256 = PackageTrust.apkSetSha256(PackageTrust.installedApks(packageInfoInstalled));
            if (installedSelection == null && !strApkSetSha256.equals(PackageTrust.apkSetSha256(this.selected.gameApks))) {
                throw new Failure(BridgeDiagnostics.Stage.TARGET_IDENTITY, "INSTALLED_APK_SET_MISMATCH", AndroidMessages.BRIDGE_CLIENT_28.text(), null);
            }
            if (installedSelection != null) InstalledBridgeResolver.requireUnchanged(context, installedSelection);
            String string = (packageInfoInstalled.versionName == null || packageInfoInstalled.versionName.trim().isEmpty()) ? Long.toString(PackageTrust.versionCode(packageInfoInstalled)) : packageInfoInstalled.versionName;
            if (zRequireBridgeForExchange && !PackageTrust.legacyRecoveryMarkerPresent(this.context.getFilesDir())) {
                throw new Failure(BridgeDiagnostics.Stage.TARGET_IDENTITY, "LEGACY_RECOVERY_ENDED", AndroidMessages.BRIDGE_CLIENT_29.text(), null);
            }
            return new AndroidGameTarget(this.identity, string, PackageTrust.versionCode(packageInfoInstalled), strApkSetSha256, sourceHash());
        } catch (Failure e) {
            throw e;
        } catch (Exception e2) {
            throw new Failure(BridgeDiagnostics.Stage.TARGET_IDENTITY, "TARGET_IDENTITY_FAILED", AndroidMessages.BRIDGE_CLIENT_30.text(), e2);
        }
    }

    
    
    
    
    
    
    
    
    @Override // pdsync.android.app.AndroidGameBridge
    
    public void exportTo(Path path) {
        try {
            targetGame();
            Bundle reply=call("export",null);
            ParcelFileDescriptor descriptor=AndroidCompat.descriptor(reply,"archive");
            if(descriptor==null) throw new Failure(AndroidMessages.BRIDGE_CLIENT_31.text());
            SaveArchiveCodec.Inventory inventory;
            try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                inventory=SaveArchiveCodec.readExportArchive(input,path);
            }
            if(!DirectoryMetadataKt.contentSha256(inventory.hashes).equals(reply.getString("fingerprint"))
                    || inventory.hashes.size()!=reply.getInt("fileCount",-1) || inventory.bytes!=reply.getLong("byteCount",-1L))
                throw new Failure(AndroidMessages.BRIDGE_CLIENT_32.text());
        } catch(Failure error) { throw error; }
        catch(Exception error) { throw new Failure(BridgeDiagnostics.Stage.EXPORT,"EXPORT_FAILED",AndroidMessages.BRIDGE_CLIENT_33.text(),error); }
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
        catch(Exception error) { throw new Failure(BridgeDiagnostics.Stage.RESTORE,"RESTORE_FAILED",AndroidMessages.BRIDGE_CLIENT_34.text(),error); }
        finally { if(archive!=null) archive.delete(); }
    }

    private static void checkName(String str) {
        if (str.length() > 1024 || str.startsWith("/") || str.contains("\\") || str.contains(":")) {
            throw new Failure(AndroidMessages.BRIDGE_CLIENT_35.text());
        }
        for (String str2 : str.split("/", -1)) {
            if (!str2.matches("[A-Za-z0-9_.-]+") || str2.equals(".") || str2.equals("..") || str2.endsWith(".")) {
                throw new Failure(AndroidMessages.BRIDGE_CLIENT_35.text());
            }
        }
        if (!str.equals("rankings.dat") && !str.equals("badges.dat") && !str.equals("journal.dat") && !str.matches("game[0-9]+/.+")) {
            throw new Failure(AndroidMessages.BRIDGE_CLIENT_36.text());
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
                    throw new IOException(AndroidMessages.BRIDGE_CLIENT_37.text());
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
                    throw new IOException(AndroidMessages.BRIDGE_CLIENT_37.text());
                }
            }
            return i3;
        }
    }
}
