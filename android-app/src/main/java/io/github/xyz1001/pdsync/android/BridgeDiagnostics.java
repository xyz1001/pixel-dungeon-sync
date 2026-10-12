package io.github.xyz1001.pdsync.android;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/* loaded from: classes.dex */
public final class BridgeDiagnostics {
    private static final Set<String> CODES = Collections.unmodifiableSet(new HashSet(Arrays.asList("ACTIVATION_COMPONENT_UNAVAILABLE", "ACTIVATION_LAUNCH_DENIED", "PROVIDER_ACQUIRE_NULL", "PROVIDER_ACQUIRE_FAILED", "PROVIDER_ACQUIRE_SECURITY_REJECTED", "IPC_CLOSE_FAILED", "IPC_REPLY_CLOSE_FAILED", "ANDROID_VERSION_UNSUPPORTED", "ARCHIVE_CHANGED", "BRIDGE_OPERATION_REJECTED", "EXPORT_FAILED", "FIRST_LAUNCH_NEEDED", "GAME_BINDING_MISMATCH", "GAME_FOREGROUND", "GAME_MISSING", "GAME_RUNNING", "GAME_SAVE_RACE", "GAME_SIGNER_MISMATCH", "INSPECT_FAILED", "INSTALLED_APK_SET_MISMATCH", "INTERRUPTED", "INVALID_GAME_BINDING", "INVALID_PROTOCOL", "IPC_CALL_FAILED", "IPC_SECURITY_REJECTED", "LEGACY_RECOVERY_ENDED", "NO_PENDING_RECOVERY", "OK", "PACKAGE_TRUST_CHANGED", "PACKAGE_TRUST_FAILED", "PROCESS_STATE_UNKNOWN", "READINESS_FIELDS_MISSING", "RECOVERY_FAILED", "RECOVERY_IDENTITY_CHANGED", "RECOVERY_REQUIRED", "REPLY_IDENTITY_MISMATCH", "RESTORE_COMMITTED_CLEANUP_PENDING", "RESTORE_FAILED", "RESTORE_ROLLED_BACK", "RESTORE_VERIFY_FAILED", "SAVE_CHANGED", "SAVE_IO_ERROR", "SAVE_NOT_QUIET", "SAVE_NOT_READY", "SAVE_ROOT_UNKNOWN", "SAVE_TOO_LARGE", "SAVE_UNREADABLE", "STALE_EXPORT", "TARGET_IDENTITY_FAILED", "UID_MISMATCH", "UNEXPECTED_SHARED_UID", "UNKNOWN", "UNKNOWN_BRIDGE_FAILURE", "UNRECOGNIZED_SAVE_LAYOUT", "UNSAFE_ARCHIVE_DESCRIPTOR", "UNSAFE_SAVE_PATH")));
    private static final Set<String> ORIGINS = Collections.unmodifiableSet(new HashSet(Arrays.asList("android.content.ContentResolver.call", "android.content.ContentResolver.acquireContentProviderClient", "android.content.ContentResolver.acquireProvider", "android.app.ContextImpl$ApplicationContentResolver.acquireProvider", "android.app.ActivityThread.acquireProvider", "android.app.ActivityThread.installProvider", "android.app.ActivityThread.handleInstallProvider", "android.content.ContentProviderClient.call", "android.content.ContentProviderClient.close", "android.content.ContentProviderClient.closeInternal", "android.content.ContentProviderProxy.call", "android.content.ContentProviderNative.onTransact", "android.content.ContentProvider$Transport.call", "android.content.ContentProvider.call", "android.content.ContentProvider.getCallingPackage", "android.content.ContentProvider.getCallingPackageUnchecked", "android.content.ContentProvider.validateIncomingAuthority", "android.content.ContentProvider.validateIncomingUri", "android.content.ContentProvider.validateIncomingAttributionSource", "android.content.AttributionSource.enforceCallingUid", "android.content.AttributionSource.enforceCallingPid", "android.app.AppOpsManager.checkPackage", "android.app.ActivityManager.getService", "android.app.IActivityManager$Stub$Proxy.getContentProvider", "android.app.IActivityManager$Stub$Proxy.removeContentProvider", "android.app.IActivityManager$Stub$Proxy.refContentProvider", "android.os.Parcel.readException", "android.os.Parcel.readExceptionCode", "android.os.Parcel.createException", "android.os.Parcel.createExceptionOrNull", "android.os.Binder.execTransact", "android.os.Binder.execTransactInternal", "com.android.server.am.ContentProviderHelper.getContentProvider", "com.android.server.am.ContentProviderHelper.getContentProviderImpl", "com.android.server.am.ActivityManagerService.getContentProvider", "com.android.server.appop.AppOpsService.checkPackage", "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider.call", "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider.authenticate", "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider.noExtras", "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider.restoreSave", "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider.onCreate", "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider.prepareArea", "io.github.xyz1001.pdsync.bridge.GameGuard.observe", "io.github.xyz1001.pdsync.bridge.GameGuard.root", "io.github.xyz1001.pdsync.bridge.GameGuard.requireQuiescent")));

    public enum ClientClose {
        NOT_ACQUIRED,
        CLOSED,
        FAILED
    }

    public enum FileError {
        ATOMIC_MOVE_UNSUPPORTED,
        ACCESS_DENIED,
        NOT_FOUND,
        ALREADY_EXISTS,
        FILE_SYSTEM,
        ZIP_INVALID,
        ANDROID_ERRNO,
        IO,
        UNKNOWN
    }

    public enum FileStage {
        HELPER_WORKSPACE,
        READ_ARCHIVE,
        CAPTURE,
        WRITE_BACKUP,
        WRITE_JOURNAL,
        MOVE_OLD,
        MOVE_NEW,
        VERIFY,
        FINALIZE,
        CLEANUP
    }

    public enum HelperReason {
        PROCESS_LIST_UNAVAILABLE,
        SHARED_UID_PROCESS_CRASHING,
        HELPER_PROCESS_AMBIGUOUS,
        UNKNOWN_SHARED_UID_PROCESS,
        PROCESS_IMPORTANCE_UNKNOWN,
        HELPER_PROCESS_NOT_VISIBLE,
        PROCESS_OBSERVATION_DENIED,
        INSTALLED_GAME_IDENTITY_DENIED,
        BINDING_METADATA_TYPE_INVALID,
        BINDING_PROVIDER_INVALID,
        BINDING_VALUES_INVALID,
        WORKSPACE_CREATE_FAILED,
        WORKSPACE_INSPECTION_FAILED,
        ANDROID_SAFE_FILE_OPERATION_REJECTED
    }

    public enum SnapshotStatus {
        RESOLVED,
        NOT_FOUND,
        READ_FAILED
    }

    public enum Stage {
        PACKAGE_TRUST,
        PROVIDER_ACQUIRE,
        HELPER_ACTIVATION,
        IPC_CALL,
        IPC_CLOSE,
        PROTOCOL_REPLY,
        SAVE_READINESS,
        TARGET_IDENTITY,
        EXPORT,
        RESTORE,
        RECOVERY
    }

    public static final class ProviderSnapshot {
        final boolean appEnabled;
        final boolean appInfoPresent;
        final boolean authorityMatches;
        final boolean classMatches;
        final boolean enabled;
        final boolean exported;
        final boolean grantsUris;
        final boolean packageMatches;
        final boolean permissionGranted;
        final boolean processMatches;
        final SnapshotStatus status;
        final boolean stopped;
        final boolean suspended;

        public ProviderSnapshot(SnapshotStatus snapshotStatus, boolean z, boolean z2, boolean z3, boolean z4, boolean z5, boolean z6, boolean z7, boolean z8, boolean z9, boolean z10, boolean z11, boolean z12) {
            this.status = snapshotStatus == null ? SnapshotStatus.READ_FAILED : snapshotStatus;
            this.permissionGranted = z;
            this.enabled = z2;
            this.appInfoPresent = z3;
            this.appEnabled = z4;
            this.stopped = z5;
            this.suspended = z6;
            this.authorityMatches = z7;
            this.classMatches = z8;
            this.packageMatches = z9;
            this.processMatches = z10;
            this.exported = z11;
            this.grantsUris = z12;
        }
    }

    public static final class TransportInfo {
        final long acquireMillis;
        final boolean acquired;
        final ProviderSnapshot after;
        final ProviderSnapshot before;
        final ClientClose close;

        TransportInfo(boolean z, long j, ClientClose clientClose) {
            this(z, j, clientClose, null, null);
        }

        private TransportInfo(boolean z, long j, ClientClose clientClose, ProviderSnapshot providerSnapshot, ProviderSnapshot providerSnapshot2) {
            this.acquired = z;
            this.acquireMillis = Math.max(0L, Math.min(600000L, j));
            this.close = clientClose == null ? ClientClose.NOT_ACQUIRED : clientClose;
            this.before = providerSnapshot;
            this.after = providerSnapshot2;
        }

        TransportInfo withSnapshots(ProviderSnapshot providerSnapshot, ProviderSnapshot providerSnapshot2) {
            return new TransportInfo(this.acquired, this.acquireMillis, this.close, providerSnapshot, providerSnapshot2);
        }
    }

    static long boundedAcquireMillis(long j) {
        return Math.max(0L, Math.min(600000L, j / 1000000));
    }

    public static class Failure extends RuntimeException {
        private static final long serialVersionUID = 1;
        public final String code;
        public final Stage stage;
        public final TransportInfo transport;

        public Failure(Stage stage, String str, String str2, Throwable th) {
            this(stage, str, str2, th, null);
        }

        public Failure(Stage stage, String str, String str2, Throwable th, TransportInfo transportInfo) {
            super(str2, th);
            this.stage = stage == null ? Stage.PROTOCOL_REPLY : stage;
            this.code = BridgeDiagnostics.CODES.contains(str) ? str : "UNKNOWN_BRIDGE_FAILURE";
            this.transport = transportInfo;
        }
    }

    private static final class HelperDetail extends IOException {
        private static final long serialVersionUID = 1;
        final HelperReason reason;

        HelperDetail(HelperReason helperReason) {
            super(helperReason.name());
            this.reason = helperReason;
        }
    }

    private static final class FileDetail extends IOException {
        private static final long serialVersionUID = 1;
        final int errno;
        final FileError error;
        final FileStage stage;

        FileDetail(FileStage fileStage, FileError fileError, int i) {
            super("HELPER_FILE_OPERATION_FAILED");
            this.stage = fileStage;
            this.error = fileError;
            this.errno = i;
        }
    }

    static Throwable helperFileDetail(String str, String str2, int i) {
        if (str != null && str2 != null && i >= -1 && i <= 4095) {
            try {
                return new FileDetail(FileStage.valueOf(str), FileError.valueOf(str2), i);
            } catch (IllegalArgumentException unused) {
            }
        }
        return null;
    }

    static Throwable helperDetail(String str, String str2) {
        if (str2 == null) {
            str2 = "";
        }
        if ("PROCESS_STATE_UNKNOWN".equals(str)) {
            if (str2.equals("Shared-UID process visibility is unavailable")) {
                return new HelperDetail(HelperReason.PROCESS_LIST_UNAVAILABLE);
            }
            if (str2.equals("A shared-UID process is crashing or unresponsive")) {
                return new HelperDetail(HelperReason.SHARED_UID_PROCESS_CRASHING);
            }
            if (str2.equals("Helper process identity is ambiguous")) {
                return new HelperDetail(HelperReason.HELPER_PROCESS_AMBIGUOUS);
            }
            if (str2.equals("An unknown process is running in the shared UID")) {
                return new HelperDetail(HelperReason.UNKNOWN_SHARED_UID_PROCESS);
            }
            if (str2.equals("Game process importance is unknown")) {
                return new HelperDetail(HelperReason.PROCESS_IMPORTANCE_UNKNOWN);
            }
            if (str2.equals("Cannot verify helper process visibility")) {
                return new HelperDetail(HelperReason.HELPER_PROCESS_NOT_VISIBLE);
            }
            if (str2.equals("Shared-UID process observation was denied")) {
                return new HelperDetail(HelperReason.PROCESS_OBSERVATION_DENIED);
            }
            if (str2.equals("Installed game identity cannot be inspected")) {
                return new HelperDetail(HelperReason.INSTALLED_GAME_IDENTITY_DENIED);
            }
        }
        if ("INVALID_GAME_BINDING".equals(str)) {
            if (str2.equals("Helper game binding metadata is missing or not a string")) {
                return new HelperDetail(HelperReason.BINDING_METADATA_TYPE_INVALID);
            }
            if (str2.equals("Helper provider identity or caller permissions differ from the selected game binding")) {
                return new HelperDetail(HelperReason.BINDING_PROVIDER_INVALID);
            }
            if (str2.equals("Helper is not bound to a valid selected game; prepare the matching helper again")) {
                return new HelperDetail(HelperReason.BINDING_VALUES_INVALID);
            }
        }
        if (!"SAVE_IO_ERROR".equals(str)) {
            return null;
        }
        if (str2.equals("Save transfer failed safely: Cannot create helper-private workspace")) {
            return new HelperDetail(HelperReason.WORKSPACE_CREATE_FAILED);
        }
        if (str2.equals("Save transfer failed safely: Cannot inspect helper-private workspace")) {
            return new HelperDetail(HelperReason.WORKSPACE_INSPECTION_FAILED);
        }
        if (str2.equals("Android rejected a safe file operation")) {
            return new HelperDetail(HelperReason.ANDROID_SAFE_FILE_OPERATION_REJECTED);
        }
        return null;
    }

    private BridgeDiagnostics() {
    }

    public static Failure find(Throwable th) {
        int i = 0;
        while (th != null && i < 10) {
            if (th instanceof Failure) {
                return (Failure) th;
            }
            i++;
            th = th.getCause();
        }
        return null;
    }

    public static String code(Throwable th) {
        Failure failureFind = find(th);
        if (failureFind == null) {
            return null;
        }
        return failureFind.code;
    }

    public static String report(Throwable th, int i, String str, long j, String str2, String str3, String str4) {
        Failure failureFind = find(th);
        StringBuilder sb = new StringBuilder("PD Sync bridge diagnostic v2\n");
        sb.append("stage=");
        sb.append(failureFind == null ? "UNCLASSIFIED" : failureFind.stage.name());
        sb.append("\ncode=");
        sb.append(failureFind == null ? "UNKNOWN_BRIDGE_FAILURE" : failureFind.code);
        sb.append("\napi=");
        sb.append(i);
        sb.append("\napp_version=");
        sb.append(token(str, 64));
        sb.append("\napp_version_code=");
        sb.append(j);
        sb.append("\ngame_package=");
        sb.append(token(str2, 255));
        sb.append("\nhelper_package=");
        sb.append(token(str3, 255));
        sb.append("\nauthority=");
        sb.append(token(str4, 255));
        if (failureFind != null && failureFind.transport != null) {
            TransportInfo transportInfo = failureFind.transport;
            sb.append("\nprovider_acquired=");
            sb.append(transportInfo.acquired);
            sb.append("\nacquire_ms_bounded=");
            sb.append(transportInfo.acquireMillis);
            sb.append("\nclient_close=");
            sb.append(transportInfo.close.name());
            appendSnapshot(sb, "before_acquire", transportInfo.before);
            appendSnapshot(sb, "after_failure", transportInfo.after);
        }
        Set setNewSetFromMap = Collections.newSetFromMap(new IdentityHashMap());
        int i2 = 0;
        Throwable cause = th;
        for (int i3 = 0; cause != null && i3 < 8 && setNewSetFromMap.add(cause); i3++) {
            sb.append("\ncause[");
            sb.append(i3);
            sb.append("]=");
            sb.append(token(cause.getClass().getName(), 200));
            sb.append("; reason=");
            sb.append(reason(cause, str4));
            if (cause instanceof FileDetail) {
                FileDetail fileDetail = (FileDetail) cause;
                sb.append("\nhelper_file_stage=");
                sb.append(fileDetail.stage.name());
                sb.append("\nhelper_file_error=");
                sb.append(fileDetail.error.name());
                sb.append("\nhelper_file_errno=");
                sb.append(fileDetail.errno);
            }
            appendOrigins(sb, cause);
            Throwable[] suppressed = cause.getSuppressed();
            int length = suppressed.length;
            int i4 = 0;
            while (i4 < length) {
                Throwable cause2 = suppressed[i4];
                if (i2 >= 2) {
                    break;
                }
                int i5 = i2 + 1;
                for (int i6 = 0; cause2 != null && i6 < 3 && setNewSetFromMap.add(cause2); i6++) {
                    sb.append("\nsuppressed[");
                    sb.append(i2);
                    sb.append("].cause[");
                    sb.append(i6);
                    sb.append("]=");
                    sb.append(token(cause2.getClass().getName(), 200));
                    sb.append("; reason=");
                    sb.append(reason(cause2, str4));
                    appendOrigins(sb, cause2);
                    cause2 = cause2.getCause();
                }
                i4++;
                i2 = i5;
            }
            cause = cause.getCause();
        }
        return sb.toString();
    }

    private static void appendSnapshot(StringBuilder sb, String str, ProviderSnapshot providerSnapshot) {
        sb.append('\n');
        sb.append(str);
        sb.append('=');
        if (providerSnapshot == null) {
            sb.append("NOT_SAMPLED");
            return;
        }
        sb.append(providerSnapshot.status.name());
        if (providerSnapshot.status == SnapshotStatus.READ_FAILED) {
            return;
        }
        sb.append("; permission_granted=");
        sb.append(providerSnapshot.permissionGranted);
        if (providerSnapshot.status != SnapshotStatus.RESOLVED) {
            return;
        }
        sb.append("; provider_enabled=");
        sb.append(providerSnapshot.enabled);
        sb.append("; app_info_present=");
        sb.append(providerSnapshot.appInfoPresent);
        sb.append("; app_enabled=");
        sb.append(providerSnapshot.appEnabled);
        sb.append("; app_stopped=");
        sb.append(providerSnapshot.stopped);
        sb.append("; app_suspended=");
        sb.append(providerSnapshot.suspended);
        sb.append("; authority_matches=");
        sb.append(providerSnapshot.authorityMatches);
        sb.append("; class_matches=");
        sb.append(providerSnapshot.classMatches);
        sb.append("; package_matches=");
        sb.append(providerSnapshot.packageMatches);
        sb.append("; process_matches=");
        sb.append(providerSnapshot.processMatches);
        sb.append("; exported=");
        sb.append(providerSnapshot.exported);
        sb.append("; grants_uris=");
        sb.append(providerSnapshot.grantsUris);
    }

    private static String token(String str, int i) {
        return (str == null || str.length() > i || !str.matches("[A-Za-z0-9_.$-]+")) ? "unknown" : str;
    }

    private static void appendOrigins(StringBuilder sb, Throwable th) {
        LinkedHashSet linkedHashSet = new LinkedHashSet();
        StackTraceElement[] stackTrace = th.getStackTrace();
        for (int i = 0; i < stackTrace.length && i < 32 && linkedHashSet.size() < 4; i++) {
            String str = stackTrace[i].getClassName() + "." + stackTrace[i].getMethodName();
            if (ORIGINS.contains(str)) {
                linkedHashSet.add(str);
            }
        }
        if (!linkedHashSet.isEmpty()) {
            sb.append("; origin=");
            sb.append(String.join(",", linkedHashSet));
        }
        String strRemoteOrigins = remoteOrigins(th.getClass().getName(), th.getMessage());
        if (strRemoteOrigins.isEmpty()) {
            return;
        }
        sb.append("; remote_origin=");
        sb.append(strRemoteOrigins);
    }

    static String remoteOrigins(String str, String str2) {
        int iIndexOf;
        if (!"android.os.RemoteException".equals(str) || str2 == null || !str2.startsWith("Remote stack trace:\n")) {
            return "";
        }
        String strSubstring = str2.substring(0, Math.min(8192, str2.length()));
        LinkedHashSet linkedHashSet = new LinkedHashSet();
        String[] strArrSplit = strSubstring.split("\n", 34);
        for (int i = 1; i < strArrSplit.length && i <= 32 && linkedHashSet.size() < 4; i++) {
            String strTrim = strArrSplit[i].trim();
            if (strTrim.startsWith("at ") && (iIndexOf = strTrim.indexOf(40, 3)) >= 0 && iIndexOf <= 200) {
                String strSubstring2 = strTrim.substring(3, iIndexOf);
                if (ORIGINS.contains(strSubstring2)) {
                    linkedHashSet.add(strSubstring2);
                }
            }
        }
        return String.join(",", linkedHashSet);
    }

    private static String reason(Throwable th, String str) {
        if (th instanceof Failure) {
            return ((Failure) th).code;
        }
        if (th instanceof HelperDetail) {
            return ((HelperDetail) th).reason.name();
        }
        if (th instanceof FileDetail) {
            return "HELPER_FILE_OPERATION_FAILED";
        }
        if (th instanceof SecurityException) {
            String message = th.getMessage();
            String lowerCase = message == null ? "" : message.substring(0, Math.min(4096, message.length())).toLowerCase(Locale.ROOT);
            if (lowerCase.contains("main app certificate pin mismatch")) {
                return "MAIN_CERTIFICATE_PIN_MISMATCH";
            }
            if (lowerCase.contains("main certificate pin is not configured")) {
                return "MAIN_CERTIFICATE_PIN_MISSING";
            }
            if (lowerCase.contains("unexpected caller package")) {
                return "CALLER_PACKAGE_MISMATCH";
            }
            if (lowerCase.contains("main caller uid mismatch")) {
                return "CALLER_UID_MISMATCH";
            }
            if (lowerCase.contains("shared-uid callers are never authorized")) {
                return "SHARED_UID_CALLER_REJECTED";
            }
            if (lowerCase.contains("main app must use an independent uid")) {
                return "CALLER_UID_GROUP_REJECTED";
            }
            if (lowerCase.contains("main-app signature permission") || lowerCase.contains("permission denial")) {
                return "BINDER_PERMISSION_REJECTED";
            }
            return lowerCase.contains("存档助手提供方或权限异常") ? "PROVIDER_IDENTITY_OR_PERMISSION_INVALID" : lowerCase.contains("尚未建立独立的共享 uid") ? "SHARED_UID_MISMATCH" : lowerCase.contains("共享 uid 中出现了其他应用") ? "SHARED_UID_MEMBERS_MISMATCH" : lowerCase.contains("助手绑定的游戏来源") ? "HELPER_BINDING_MISMATCH" : lowerCase.contains("签名与本机密钥不一致") ? "LOCAL_SIGNER_MISMATCH" : "ACCESS_OR_IDENTITY_REJECTED";
        }
        if (th instanceof FileNotFoundException) {
            return "FILE_OR_ACCESS_UNAVAILABLE";
        }
        if (th instanceof IOException) {
            return "IO_FAILED";
        }
        if (!(th instanceof IllegalArgumentException)) {
            return th instanceof IllegalStateException ? "INVALID_STATE" : "EXCEPTION_RETAINED";
        }
        String message2 = th.getMessage();
        if (str != null && !"unknown".equals(token(str, 255))) {
            if (("Unknown authority " + str).equals(message2)) {
                return "FRAMEWORK_UNKNOWN_AUTHORITY";
            }
        }
        return "Unknown bridge method".equals(message2) ? "PROVIDER_METHOD_REJECTED" : "No path or argument is accepted".equals(message2) ? "PROVIDER_ARGUMENT_REJECTED" : "No extras accepted".equals(message2) ? "PROVIDER_EXTRAS_REJECTED" : "restore requires exactly archive and expectedFingerprint".equals(message2) ? "RESTORE_EXTRAS_REJECTED" : "Expected content fingerprint is required".equals(message2) ? "RESTORE_FINGERPRINT_REJECTED" : "Read-only archive descriptor is required".equals(message2) ? "RESTORE_DESCRIPTOR_REJECTED" : "ARGUMENT_OR_PROVIDER_UNAVAILABLE";
    }
}
