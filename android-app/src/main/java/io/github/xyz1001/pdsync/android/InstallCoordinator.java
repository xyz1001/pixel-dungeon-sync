package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

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
import io.github.xyz1001.pdsync.android.prepare.ValidatedPreparationCache;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import pdsync.android.app.AndroidSyncFacade;
import pdsync.DiagnosticDetails;
import pdsync.DiagnosticLog;
import pdsync.DiagnosticStage;
import pdsync.DiagnosticResult;
import pdsync.DiagnosticFailure;


public final class InstallCoordinator {
    public static final String CHANGED = "io.github.xyz1001.pdsync.android.INSTALL_CHANGED";
    public static final int DELETE_REQUEST = 8102;
    public static final pdsync.i18n.UiText LOSS_WARNING = AndroidMessages.INSTALL_COORDINATOR_01;
    static final String PREFS = "install-checkpoint-v1";
    private final Context context;
    private final SharedPreferences prefs;
    private final GamePreparationService preparation;
    private GamePreparationService.PreparedPair validatedPair;
    private InstallSessionWriter.Snapshot validatedOutputs;
    private String approvedUninstallReceipt;
    private String approvedTargetStamp;
    private String approvedTargetIdentity;

    public String requestNonce() { return prefs.getString("nonce", ""); }
    public boolean hasPendingResult() { return hasActiveRequest() || "RESULT".equals(state()) || hasReturnedSelectedRequest(); }
    private boolean hasReturnedSelectedRequest() {
        String required = prefs.getString("selectedFileInstallReceipt", "");
        return "READY".equals(state()) && "install".equals(prefs.getString("operation", ""))
                && !required.isEmpty() && required.equals(prefs.getString("installRequestReceipt", ""))
                && !pendingPackage().isEmpty();
    }
    public boolean mayContinueAfterUninstall() {
        if (!prefs.getBoolean("continueAfterUninstall", false) || hasActiveRequest()) return false;
        try { PackageTrust.installed(context, pendingPackage()); return false; }
        catch (PackageManager.NameNotFoundException absent) { return true; }
        catch (Exception invalid) { return false; }
    }
    public void clearAutomaticContinuation() throws IOException {
        persist(prefs.edit().putBoolean("continueAfterUninstall", false));
    }

    private String targetStamp(String packageName) throws Exception {
        PackageInfo info = PackageTrust.installed(context, packageName);
        StringBuilder value = new StringBuilder(info.packageName).append(':').append(info.firstInstallTime)
                .append(':').append(info.lastUpdateTime).append(':').append(PackageTrust.versionCode(info))
                .append(':').append(PackageTrust.signer(info)).append(':').append(info.sharedUserId);
        if (info.applicationInfo == null) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_02.text());
        value.append(':').append(info.applicationInfo.uid);
        for (File apk : PackageTrust.installedApks(info)) {
            if (!apk.isFile() || !apk.getAbsolutePath().equals(apk.getCanonicalPath()))
                throw new IOException(AndroidMessages.INSTALL_COORDINATOR_03.text());
            value.append(':').append(apk.getPath()).append(':').append(apk.length()).append(':').append(apk.lastModified());
        }
        return value.toString();
    }

    private void rememberOutputs(GamePreparationService.PreparedPair pair) throws Exception {
        ValidatedPreparationCache.requireUnchanged(pair);
        if (validatedPair != null && validatedOutputs != null
                && validatedPair.gameApks.equals(pair.gameApks)
                && ValidatedPreparationCache.isUnchanged(validatedPair)) {
            validatedPair = pair;
            return;
        }
        validatedOutputs = InstallSessionWriter.inspect(pair.gameApks);
        validatedPair = pair;
    }

    public InstallCoordinator(Context context) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext;
        this.prefs = applicationContext.getSharedPreferences("install-checkpoint-v1", 0);
        this.preparation = new GamePreparationService(applicationContext);
    }

    private static void phase(String phase, String cache, long started, DiagnosticResult result) {
        DiagnosticLog.record("PREPARE_VALIDATION".equals(phase) ? DiagnosticStage.VERIFY : DiagnosticStage.INSTALLATION,
                result, result == DiagnosticResult.FAILED ? DiagnosticFailure.OTHER : DiagnosticFailure.NONE,
                DiagnosticDetails.builder().failureSource("INSTALLATION")
                        .installationPhase(phase).installationCache(cache)
                        .installationElapsedMs(Math.max(0L, android.os.SystemClock.elapsedRealtime() - started)).build());
    }

    private void persist(SharedPreferences.Editor editor) throws IOException {
        if (!editor.commit()) {
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_04.text());
        }
    }

    public String state() {
        return this.prefs.getString("state", "READY");
    }

    public boolean hasActiveRequest() {
        return state().equals("INSTALLING") || state().equals("PENDING_CONFIRMATION") || state().equals("UNINSTALLING") || externalInstallPending();
    }

    public boolean externalInstallPending() {
        return "EXTERNAL_INSTALL".equals(state()) && "external-install".equals(prefs.getString("operation", ""));
    }

    /** A finished external Activity is not proof that its background installation finished. */
    public boolean externalInstallObservation() {
        return "EXTERNAL_RETURNED".equals(state()) && "external-install".equals(prefs.getString("operation", ""));
    }

    public int externalActivityRequestCode() { return prefs.getInt("externalActivityRequestCode", 8107); }

    public void externalActivityReturned(String nonce) throws IOException {
        if (!externalInstallPending() || !requestNonce().equals(nonce)) return;
        // Release only our UI wait. Keep the immutable share and verification identity intact.
        persist(prefs.edit().putString("state", "EXTERNAL_RETURNED"));
        Log.i("PDSyncInstall", "externalActivityReturned EXTERNAL_INSTALL->EXTERNAL_RETURNED; businessLock=false");
    }

    /** Explicit source replacement supersedes observation, never cancels another app's work. */
    public void supersedeExternalObservation() throws IOException {
        if (!externalInstallObservation()) return;
        clearCleanupCheckpoint(prefs.getString("message", ""));
    }

    public boolean canOfferExternalInstaller() {
        return !externalInstallPending() && (!hasPendingResult() || "install".equals(prefs.getString("operation", "")));
    }

    /** Called only after explicit confirmation to close our old request and choose another installer. */
    public ExternalInstallerHandoff.Ticket prepareExternalInstall() throws Exception {
        if (!canOfferExternalInstaller()) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_05.text());
        if (AndroidSyncFacade.hasUnresolvedRestore(new File(context.getFilesDir().getCanonicalFile(), "sync").toPath()))
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_06.text());
        GamePreparationService.PreparedPair pair = freshPair();
        if (pair.gameApks.size() != 1) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_07.text());
        // Separate launch identity from the share nonce. Never recycle a live Activity's result code.
        int requestCode = prefs.getInt("externalActivitySequence", 15999) + 1;
        if (requestCode < 16000 || requestCode > 65534) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_08.text());
        ExternalInstallerHandoff.Ticket ticket = ExternalInstallerHandoff.prepare(context, pair);
        try {
        synchronized (SystemConfirmationToken.CHECKPOINT_LOCK) {
            if (!canOfferExternalInstaller()) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_09.text());
            if ("install".equals(prefs.getString("operation", ""))
                    && (hasPendingResult() || prefs.contains("session"))) {
                if (!pair.gamePackage.equals(pendingPackage())) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_10.text());
                int session = prefs.getInt("session", -1);
                if (session < 0) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_11.text());
                PackageInstaller installer = context.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionInfo info = installer.getSessionInfo(session);
                if (info != null) {
                    if (!pair.gamePackage.equals(info.getAppPackageName())) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_12.text());
                    boolean owned = false;
                    for (PackageInstaller.SessionInfo mine : installer.getMySessions())
                        if (mine.getSessionId() == session) { owned = true; break; }
                    if (!owned || info.isActive()) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_13.text());
                    installer.abandonSession(session);
                    if (installer.getSessionInfo(session) != null) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_14.text());
                }
                SystemConfirmationToken.cancel(context, requestNonce(), prefs.getString("confirmation", null));
                clearCleanupCheckpoint(AndroidMessages.INSTALL_COORDINATOR_15.text());
            }
        }
            persist(prefs.edit().putString("state", "EXTERNAL_INSTALL").putString("operation", "external-install")
                    .putString("nonce", ticket.nonce).putString("package", ticket.packageName)
                    .putInt("externalActivitySequence", requestCode).putInt("externalActivityRequestCode", requestCode)
                    .putString("externalSigner", ticket.signerSha256).putLong("externalVersion", ticket.versionCode)
                    .putString("externalApkSet", ticket.apkSetSha256).putString("installRequestReceipt", ticket.receiptId)
                    .putBoolean("installedVerified", false).putBoolean("continueAfterUninstall", false)
                    .remove("session").remove("confirmation").remove("confirmationUnavailable").remove("confirmationLaunchBlocked")
                    .remove("automaticConfirmationAttemptedNonce").remove("confirmationLaunchedNonce").remove("installationReturned")
                    .putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_16.text())));
        } catch (Exception failure) { ExternalInstallerHandoff.revoke(context, ticket.nonce); throw failure; }
        return ticket;
    }

    /** External Activity results never authorize success. Verify OS package metadata and actual installed bytes. */
    public void checkExternalInstall() throws Exception {
        if (!externalInstallPending() && !externalInstallObservation()) return;
        final String nonce = requestNonce(), name = pendingPackage();
        final String receipt = prefs.getString("installRequestReceipt", "");
        if (!receipt.equals(preparation.currentReceiptId())) return;
        try {
            String before = targetStamp(name);
            PackageInfo installed = PackageTrust.installed(context, name);
            if (PackageTrust.versionCode(installed) != prefs.getLong("externalVersion", -1L)
                    || !PackageTrust.signer(installed).equalsIgnoreCase(prefs.getString("externalSigner", ""))
                    || !PackageTrust.apkSetSha256(PackageTrust.installedApks(installed)).equals(prefs.getString("externalApkSet", ""))) {
                persist(prefs.edit().putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_17.text())));
                return;
            }
            InstalledBridgeBinding binding = InstalledBridgeResolver.resolve(context, name);
            if (binding == null || !before.equals(targetStamp(name))) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_18.text());
            if ((!externalInstallPending() && !externalInstallObservation()) || !nonce.equals(requestNonce())
                    || !receipt.equals(preparation.currentReceiptId())) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_08.text());
            ExternalInstallerHandoff.revoke(context, nonce);
            persist(prefs.edit().putString("completedFileInstallReceipt", prefs.getString("installRequestReceipt", ""))
                    .putString("installedComponentToken", binding.selectionToken()).putBoolean("installedVerified", true)
                    .putString("state", "READY").remove("operation").remove("nonce").remove("package")
                    .remove("selectedFileInstallReceipt").remove("installRequestReceipt")
                    .remove("externalSigner").remove("externalVersion").remove("externalApkSet")
                    .putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_19.text())));
            SyncRuntime.setInstalledBridge(context, binding);
        } catch (PackageManager.NameNotFoundException absent) {
            persist(prefs.edit().putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_20.text())));
        }
    }

    /** Definitive launch failure is cleanup, never evidence of a successful install. */
    public void externalLaunchFailed() throws Exception {
        if (!externalInstallPending()) return;
        ExternalInstallerHandoff.revoke(context, requestNonce());
        clearCleanupCheckpoint(AndroidMessages.INSTALL_COORDINATOR_21.text());
        clearAutomaticContinuation();
    }

    /** Explicitly end only our wait/share. This cannot stop another app's installation. */
    public void endExternalInstallWait() throws Exception {
        if (!externalInstallPending()) return;
        try { checkExternalInstall(); } catch (Exception unverified) {
            // Explicitly ending our share/wait must still work when package verification fails.
        }
        if (!externalInstallPending()) return;
        ExternalInstallerHandoff.revoke(context, requestNonce());
        clearCleanupCheckpoint(AndroidMessages.INSTALL_COORDINATOR_22.text());
        clearAutomaticContinuation();
    }

    public String pendingPackage() {
        return this.prefs.getString("package", "");
    }

    public boolean originalRemoved() {
        return this.prefs.getBoolean("gameRemoved", false);
    }

    public boolean hasConfirmation() {
        return !prefs.getBoolean("confirmationUnavailable", false) && (state().equals("PENDING_CONFIRMATION") || state().equals("UNINSTALLING") || state().equals("INSTALLING")) && this.prefs.contains("confirmation");
    }

    /** Durable recovery state, not evidence that the underlying system operation ended. */
    public boolean confirmationUnavailable() {
        return hasActiveRequest() && (prefs.getBoolean("confirmationUnavailable", false) || prefs.getBoolean("confirmationLaunchBlocked", false));
    }

    public boolean confirmationLaunchBlocked() {
        return hasActiveRequest() && prefs.getBoolean("confirmationLaunchBlocked", false);
    }

    public boolean automaticConfirmationAttempted() {
        return !requestNonce().isEmpty() && requestNonce().equals(prefs.getString("automaticConfirmationAttemptedNonce", ""));
    }

    public void markConfirmationAttempt() throws IOException {
        persist(prefs.edit().putString("automaticConfirmationAttemptedNonce", requestNonce()));
    }

    public boolean hasObsoleteLegacyRequest(GamePreparationService.PreparedPair pair) {
        String operation = this.prefs.getString("operation", "");
        return hasActiveRequest() && pair != null && !pair.embeddedBridge()
                && ("install".equals(operation) || "uninstall".equals(operation))
                && InstallProgressPolicy.cleanupTargetMatches(false, pair.gamePackage, pair.helperPackage, pendingPackage());
    }

    public String lastResultMessage() {
        return prefs.getBoolean("installedVerified", false) ? AndroidMessages.INSTALL_COORDINATOR_23.text() : AndroidMessages.INSTALL_COORDINATOR_24.text();
    }

    public String summary() {
        String string = StoredAndroidText.render(this.prefs.getString("message", AndroidMessages.INSTALL_COORDINATOR_25.text()));
        if (!originalRemoved()) {
            return string;
        }
        return string + AndroidMessages.INSTALL_COORDINATOR_26.text();
    }

    public void assertCanPrepare() throws IOException {
        if (hasActiveRequest()) {
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_27.text());
        }
        if (AndroidSyncFacade.hasUnresolvedRestore(new File(this.context.getFilesDir().getCanonicalFile(), "sync").toPath())) {
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_28.text());
        }
    }

    public void registerPrepared(GamePreparationService.PreparedPair pair, boolean selectedFile) throws Exception {
        assertCanPrepare();
        supersedeExternalObservation();
        requireEmbedded(pair);
        this.preparation.reloadPreparedForInstallation(pair);
        rememberOutputs(pair);
        SyncRuntime.setPreparedGame(this.context, pair);
        persist(this.prefs.edit().putString("state", "READY").putBoolean("installedVerified", false)
                .putString("selectedFileInstallReceipt", selectedFile ? pair.installationReceiptId() : "")
                .putString("replacedFileInstallReceipt", selectedFile ? "" : pair.installationReceiptId())
                .remove("installRequestReceipt").remove("systemStatus").remove("confirmationUnavailable").remove("confirmationLaunchBlocked").remove("automaticConfirmationAttemptedNonce").remove("confirmationLaunchedNonce").remove("installationReturned")
                .putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_29.text()))
                .remove("confirmation").remove("session").remove("package").remove("nonce").putBoolean("continueAfterUninstall", false));
    }

    public boolean mayReuseInstalledSelection() throws IOException {
        return SelectedApkInstallPolicy.mayReuseInstalledSelection(prefs.getString("selectedFileInstallReceipt", ""),
                preparation.currentReceiptId(), prefs.getString("completedFileInstallReceipt", ""),
                prefs.getString("replacedFileInstallReceipt", ""));
    }

    /** Persisted across system cancellation, backgrounding and process restart. No APK reads. */
    public boolean requiresSelectedFileInstallation(GamePreparationService.PreparedPair pair) throws IOException {
        String required = prefs.getString("selectedFileInstallReceipt", "");
        if (pair != null && SelectedApkInstallPolicy.recoverIntent(required, pair.selectedFileInstallation,
                pair.installationReceiptId(), prefs.getString("completedFileInstallReceipt", ""),
                prefs.getString("replacedFileInstallReceipt", ""))) {
            // The source intent is committed inside the receipt before publishing it as current.
            // This closes a process-death window before registerPrepared can update preferences.
            required = pair.installationReceiptId();
            persist(prefs.edit().putString("selectedFileInstallReceipt", required));
        }
        return SelectedApkInstallPolicy.requiresInstallation(required, pair == null ? null : pair.installationReceiptId());
    }

    /** Only an explicit, accepted installed-application choice can replace a picked-file intent. */
    public void selectInstalledSource() throws Exception {
        assertCanPrepare();
        supersedeExternalObservation();
        String previous = preparation.currentReceiptId();
        persist(prefs.edit().putString("replacedFileInstallReceipt", previous == null ? "" : previous)
                .remove("selectedFileInstallReceipt").remove("installRequestReceipt").remove("systemStatus").remove("confirmationUnavailable").remove("confirmationLaunchBlocked").remove("automaticConfirmationAttemptedNonce").remove("confirmationLaunchedNonce").remove("installationReturned"));
    }

    private static void requireEmbedded(GamePreparationService.PreparedPair pair) throws IOException {
        if (pair == null || !pair.embeddedBridge() || pair.helperOnlyUpgrade)
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_30.text());
    }

    public GamePreparationService.PreparedPair freshPair() throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        String cache = validatedPair == null ? "SESSION_MISSING"
                : ValidatedPreparationCache.isUnchanged(validatedPair) ? "CACHE_HIT" : "FILE_CHANGED";
        GamePreparationService.PreparedPair pair = preparation.reloadPreparedForInstallation(validatedPair);
        if (pair == null) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_31.text());
        requireEmbedded(pair);
        rememberOutputs(pair);
        SyncRuntime.setPreparedGame(context, pair);
        phase("PREPARE_VALIDATION", cache, started, DiagnosticResult.SUCCEEDED);
        Log.i("PDInstall", "validated_session_ready cache=" + cache + " elapsed_ms=" + (android.os.SystemClock.elapsedRealtime() - started));
        return pair;
    }

    /** This loader authorizes cleanup only. Never use it to install, uninstall, or reopen confirmation. */
    private GamePreparationService.PreparedPair retainedCleanupPair() throws Exception {
        GamePreparationService.PreparedPair pair = this.preparation.reloadPrepared();
        if (pair == null) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_32.text());
        this.preparation.validatePrepared(pair);
        if (!InstallProgressPolicy.cleanupTargetMatches(pair.embeddedBridge(), pair.gamePackage, pair.helperPackage, pendingPackage()))
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_33.text());
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
        ValidatedPreparationCache.requireUnchanged(pair);
        if (requiresSelectedFileInstallation(pair)
                || pair.gameStrategy == GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL
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
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_34.text());
        return true;
    }

    public String uninstallConsentReceipt(String packageName, GamePreparationService.PreparedPair pair) throws Exception {
        isGame(pair, packageName);
        ValidatedPreparationCache.requireUnchanged(pair);
        rememberOutputs(pair);
        approvedTargetIdentity = identity(packageName); // Full target proof before showing consent.
        approvedTargetStamp = targetStamp(packageName);
        approvedUninstallReceipt = UUID.randomUUID().toString();
        return approvedUninstallReceipt;
    }

    public void requestUninstall(String str, String str2) throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        if (hasActiveRequest()) {
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_35.text());
        }
        GamePreparationService.PreparedPair preparedPairFreshPair = validatedPair;
        ValidatedPreparationCache.requireUnchanged(preparedPairFreshPair);
        isGame(preparedPairFreshPair, str);
        if (preparedPairFreshPair.gameStrategy != GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL
                || approvedUninstallReceipt == null || !approvedUninstallReceipt.equals(str2)
                || !targetStamp(str).equals(approvedTargetStamp)) {
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_36.text());
        }
        if (AndroidSyncFacade.hasUnresolvedRestore(new File(this.context.getFilesDir().getCanonicalFile(), "sync").toPath())) {
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_37.text());
        }
        String string = UUID.randomUUID().toString();
        persist(this.prefs.edit().putBoolean("installedVerified", false).putString("state", "UNINSTALLING").remove("confirmationUnavailable").remove("confirmationLaunchBlocked").remove("automaticConfirmationAttemptedNonce").putString("operation", "uninstall").putString("package", str).putString("identity", approvedTargetIdentity).putString("targetStamp", approvedTargetStamp).putString("uninstallPreparedApkSet", validatedOutputs.apkSetSha256).putString("uninstallOrigin", preparedPairFreshPair.originIdentity).putBoolean("continueAfterUninstall", false).putBoolean("gameRequest", isGame(preparedPairFreshPair, str)).putString("nonce", string).remove("session").remove("confirmation").putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_38.text())));
        try {
            this.context.getPackageManager().getPackageInstaller().uninstall(str, PendingIntent.getBroadcast(this.context, string.hashCode(), new Intent(this.context, (Class<?>) InstallResultReceiver.class).setAction("io.github.xyz1001.pdsync.android.INSTALL_CHANGED." + string).putExtra("requestNonce", string), AndroidCompat.mutableCallbackFlags()).getIntentSender());
            phase("UNINSTALL_REQUEST", "CACHE_HIT", started, DiagnosticResult.PENDING);
        } catch (IllegalArgumentException | SecurityException e) {
            persist(this.prefs.edit().putString("state", "READY").remove("nonce").remove("confirmation").putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_39.text())));
            throw e;
        }
    }

    
    
    
    
    
    
    public void requestInstall(String packageName) throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        if(hasActiveRequest()) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_40.text());
        if(!context.getPackageManager().canRequestPackageInstalls()) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_41.text());
        assertCanPrepare();
        GamePreparationService.PreparedPair pair=freshPair();
        boolean game=isGame(pair,packageName);
        if(pair.gameStrategy==GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL)
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_42.text());
        List<File> apks=pair.gameApks;
        // Strategy and PM metadata were refreshed; source bytes are verified while writing below.
        PackageInstaller installer=null;
        int sessionId=-1; boolean commitAttempted=false;
        InstallDiagnostics.Stage stage=InstallDiagnostics.Stage.READ_SOURCE_VERIFY;
        try {
            InstallSessionWriter.Snapshot source=validatedOutputs;
            ValidatedPreparationCache.requireUnchanged(pair);
            stage=InstallDiagnostics.Stage.CREATE;
            installer=context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(packageName); params.setAppLabel(pair.gameLabel);
            params.setSize(source.length); RuntimePlatformCompat.requireInstallConfirmation(params);
            sessionId=installer.createSession(params); String nonce=UUID.randomUUID().toString();
            stage=InstallDiagnostics.Stage.OPEN;
            try(PackageInstaller.Session session=installer.openSession(sessionId)) {
                long writeStarted = android.os.SystemClock.elapsedRealtime();
                String expected=InstallSessionWriter.write(source,new InstallSessionWriter.Session() {
                    public OutputStream openWrite(String name,long length) throws Exception {
                        return session.openWrite(name,0,length);
                    }
                    public void fsync(OutputStream output) throws Exception { session.fsync(output); }
                });
                phase("SESSION_WRITE", "NOT_APPLICABLE", writeStarted, DiagnosticResult.SUCCEEDED);
                stage=InstallDiagnostics.Stage.READ_SOURCE_VERIFY;
                ValidatedPreparationCache.requireUnchanged(pair);
                assertCanPrepare();
                stage=InstallDiagnostics.Stage.GET_NAMES;
                InstallSessionWriter.requireNames(session.getNames(),apks.size());
                stage=InstallDiagnostics.Stage.CHECKPOINT;
                persist(prefs.edit().putBoolean("installedVerified", false).putString("state","INSTALLING").putString("operation","install").putString("package",packageName)
                        .putInt("session",sessionId).putString("nonce",nonce).putString("expectedApkSet",expected)
                        .putString("installRequestReceipt", pair.installationReceiptId()).remove("systemStatus")
                        .remove("confirmationUnavailable").remove("confirmationLaunchBlocked").remove("automaticConfirmationAttemptedNonce").remove("confirmationLaunchedNonce").remove("installationReturned").putBoolean("gameRequest",game)
                        .putBoolean("continueAfterUninstall", false).remove("expectedApk").remove("confirmation").putString("message",StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_43.text())));
                stage=InstallDiagnostics.Stage.CALLBACK;
                PendingIntent callback=PendingIntent.getBroadcast(context,sessionId,new Intent(context,InstallResultReceiver.class)
                        .setAction(CHANGED+"."+nonce).putExtra("requestNonce",nonce),AndroidCompat.mutableCallbackFlags());
                android.content.IntentSender sender=callback.getIntentSender();
                stage=InstallDiagnostics.Stage.COMMIT;
                // Once commit is attempted its result is uncertain, even if commit or close throws.
                long commitStarted = android.os.SystemClock.elapsedRealtime();
                commitAttempted=true; session.commit(sender);
                phase("INSTALL_COMMIT", "NOT_APPLICABLE", commitStarted, DiagnosticResult.PENDING);
                stage=InstallDiagnostics.Stage.CLOSE;
            }
            phase("INSTALL_SESSION", "CACHE_HIT", started, DiagnosticResult.PENDING);
        } catch(Exception error) {
            phase("INSTALL_SESSION", "NOT_APPLICABLE", started, commitAttempted ? DiagnosticResult.PENDING : DiagnosticResult.FAILED);
            InstallDiagnostics.Failure failure=InstallDiagnostics.failure(stage,packageName,sessionId,
                    Build.VERSION.SDK_INT,commitAttempted,error);
            if(commitAttempted) {
                try { persist(prefs.edit().putString("message","pdsync.install.failure:" + failure.stage.name() + ":" + failure.commitAttempted)); }
                catch(IOException checkpointError) { failure.addSuppressed(InstallDiagnostics.at(InstallDiagnostics.Stage.CHECKPOINT,checkpointError)); }
            } else {
                // Only this invocation's successfully created session is eligible for cleanup.
                if(installer!=null && sessionId>=0) {
                    try { installer.abandonSession(sessionId); }
                    catch(Exception cleanupError) { failure.addSuppressed(InstallDiagnostics.at(InstallDiagnostics.Stage.ABANDON,cleanupError)); }
                }
                // A session which definitely never reached commit is not an installation return boundary.
                // Only clear this invocation's checkpoint; keep the selected-file intent for an explicit retry.
                if (sessionId >= 0 && prefs.getInt("session", -1) == sessionId
                        && "install".equals(prefs.getString("operation", ""))) {
                    try {
                        clearCleanupCheckpoint(AndroidMessages.INSTALL_COORDINATOR_44.text());
                        persist(prefs.edit().remove("installRequestReceipt").remove("installationReturned")
                                .remove("confirmationUnavailable").remove("confirmationLaunchBlocked").remove("automaticConfirmationAttemptedNonce").remove("confirmationLaunchedNonce").putBoolean("installedVerified", false));
                    } catch (IOException checkpointError) {
                        failure.addSuppressed(InstallDiagnostics.at(InstallDiagnostics.Stage.CHECKPOINT, checkpointError));
                    }
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
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_45.text());
        }
        PendingIntent pendingIntentFind = refreshConfirmationAvailability();
        if (pendingIntentFind == null) {
            // A missing token alone never authorizes abandoning a live installer session.
            reconcile();
            throw new IOException(hasActiveRequest()
                    ? AndroidMessages.INSTALL_COORDINATOR_46.text()
                    : AndroidMessages.INSTALL_COORDINATOR_47.text());
        }
        boolean hotSession = validatedPair != null && ValidatedPreparationCache.isUnchanged(validatedPair);
        GamePreparationService.PreparedPair preparedPairFreshPair = freshPair();
        isGame(preparedPairFreshPair, pendingPackage());
        if ("uninstall".equals(this.prefs.getString("operation", ""))) {
            if (!hotSession && !identity(pendingPackage()).equals(prefs.getString("identity", "")))
                throw new IOException(AndroidMessages.INSTALL_COORDINATOR_48.text());
            if (!targetStamp(pendingPackage()).equals(this.prefs.getString("targetStamp", ""))
                    || !preparedPairFreshPair.originIdentity.equals(this.prefs.getString("uninstallOrigin", ""))
                    || !validatedOutputs.apkSetSha256.equals(this.prefs.getString("uninstallPreparedApkSet", "")))
                throw new IOException(AndroidMessages.INSTALL_COORDINATOR_49.text());
            if (AndroidSyncFacade.hasUnresolvedRestore(new File(this.context.getFilesDir().getCanonicalFile(), "sync").toPath()))
                throw new IOException(AndroidMessages.INSTALL_COORDINATOR_50.text());
        }
        try { SystemConfirmationToken.requireEnabledActivity(context, prefs.getString("confirmation", null)); }
        catch (IOException disabled) {
            persist(prefs.edit().putBoolean("confirmationLaunchBlocked", true).putString("message", StoredAndroidText.store(disabled.getMessage())));
            throw disabled;
        }
        if (prefs.getBoolean("confirmationLaunchBlocked", false))
            persist(prefs.edit().remove("confirmationLaunchBlocked").putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_51.text())));
        return pendingIntentFind;
    }

    public void acknowledgeExpiredRequest(boolean z) throws Exception {
        reconcile();
        if (hasActiveRequest()) {
            boolean z2 = SystemConfirmationToken.find(this.context, this.prefs.getString("nonce", ""), this.prefs.getString("confirmation", null)) != null;
            if (!InstallProgressPolicy.mayResetExpiredConfirmation(z, z2)) {
                throw new IOException(z2 ? AndroidMessages.INSTALL_COORDINATOR_52.text() : AndroidMessages.INSTALL_COORDINATOR_53.text());
            }
            final String cleanupNonce = requestNonce();
            GamePreparationService.PreparedPair cleanupPair = retainedCleanupPair();
            String strPendingPackage = pendingPackage();
            if ("uninstall".equals(this.prefs.getString("operation", ""))) {
                try {
                    if (!identity(strPendingPackage).equals(this.prefs.getString("identity", ""))) {
                        throw new IOException(AndroidMessages.INSTALL_COORDINATOR_54.text());
                    }
                } catch (PackageManager.NameNotFoundException unused) {
                    reconcile();
                    return;
                }
            }
            synchronized (SystemConfirmationToken.CHECKPOINT_LOCK) {
                if (!hasActiveRequest() || !cleanupNonce.equals(requestNonce()))
                    throw new IOException(AndroidMessages.INSTALL_COORDINATOR_55.text());
                if (SystemConfirmationToken.find(context, requestNonce(), prefs.getString("confirmation", null)) != null)
                    throw new IOException(AndroidMessages.INSTALL_COORDINATOR_56.text());
                if ("install".equals(prefs.getString("operation", ""))) {
                    int session = prefs.getInt("session", -1);
                    PackageInstaller installer = context.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionInfo info = session < 0 ? null : installer.getSessionInfo(session);
                    if (info != null) {
                        if (!strPendingPackage.equals(info.getAppPackageName()))
                            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_57.text());
                        installer.abandonSession(session);
                    }
                }
            clearCleanupCheckpoint(cleanupPair.embeddedBridge()
                    ? AndroidMessages.INSTALL_COORDINATOR_58.text()
                    : AndroidMessages.INSTALL_COORDINATOR_59.text());
            }
        }
    }

    /** Explicit cancellation of obsolete flows; never replays a legacy PendingIntent. */
    public void cancelObsoleteLegacyRequest(boolean dialogClosedByUser) throws Exception {
        if (!dialogClosedByUser) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_60.text());
        reconcile();
        if (!hasActiveRequest()) return;
        GamePreparationService.PreparedPair pair = retainedCleanupPair();
        boolean uninstall = "uninstall".equals(this.prefs.getString("operation", ""));
        if (!uninstall && !"install".equals(this.prefs.getString("operation", "")))
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_61.text());
        boolean unchanged = !uninstall || identity(pendingPackage()).equals(this.prefs.getString("identity", ""));
        if (!InstallProgressPolicy.mayCancelObsoleteLegacy(dialogClosedByUser, pair.embeddedBridge(),
                InstallProgressPolicy.cleanupTargetMatches(pair.embeddedBridge(), pair.gamePackage, pair.helperPackage, pendingPackage()),
                uninstall, unchanged))
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_62.text());
        if (!uninstall) {
            int session = this.prefs.getInt("session", -1);
            PackageInstaller installer = this.context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionInfo info = session < 0 ? null : installer.getSessionInfo(session);
            if (info != null) {
                if (!pendingPackage().equals(info.getAppPackageName()))
                    throw new IOException(AndroidMessages.INSTALL_COORDINATOR_63.text());
                installer.abandonSession(session);
            }
        }
        SystemConfirmationToken.cancel(this.context, this.prefs.getString("nonce", ""), this.prefs.getString("confirmation", null));
        clearCleanupCheckpoint(uninstall
                ? AndroidMessages.INSTALL_COORDINATOR_64.text()
                : AndroidMessages.INSTALL_COORDINATOR_65.text());
    }

    private void clearCleanupCheckpoint(String message) throws IOException {
        persist(this.prefs.edit().putString("state", "READY").putString("message", StoredAndroidText.store(message))
                .remove("confirmationUnavailable").remove("confirmationLaunchBlocked").remove("automaticConfirmationAttemptedNonce").remove("nonce").remove("confirmation").remove("session").remove("operation").remove("package")
                .remove("identity").remove("expectedApkSet").remove("expectedApk")
                .remove("uninstallPreparedApkSet").remove("uninstallOrigin")
                .remove("externalSigner").remove("externalVersion").remove("externalApkSet"));
    }

    public void settleClosedSystemRequest() throws Exception {
        if (externalInstallPending()) throw new IOException(AndroidMessages.INSTALL_COORDINATOR_66.text());
        reconcile();
        if (!hasPendingResult()) return;
        if ("uninstall".equals(prefs.getString("operation", ""))) {
            if (!targetStamp(pendingPackage()).equals(prefs.getString("targetStamp", "")))
                throw new IOException(AndroidMessages.INSTALL_COORDINATOR_67.text());
        } else {
            int session = prefs.getInt("session", -1);
            if (session >= 0 && context.getPackageManager().getPackageInstaller().getSessionInfo(session) != null)
                context.getPackageManager().getPackageInstaller().abandonSession(session);
        }
        SystemConfirmationToken.cancel(context, prefs.getString("nonce", ""), prefs.getString("confirmation", null));
        clearCleanupCheckpoint(AndroidMessages.INSTALL_COORDINATOR_68.text());
        clearAutomaticContinuation();
    }

    public void confirmationLaunched() throws IOException {
        boolean zEquals = "uninstall".equals(this.prefs.getString("operation", ""));
        persist(this.prefs.edit().putString("confirmationLaunchedNonce", requestNonce()).putString("state", zEquals ? "UNINSTALLING" : "INSTALLING").putString("message", StoredAndroidText.store(zEquals ? AndroidMessages.INSTALL_COORDINATOR_69.text() : AndroidMessages.INSTALL_COORDINATOR_70.text())));
    }

    /** A launch exception did not transfer control to the system. Keep the same confirmation retryable. */
    public void confirmationLaunchFailed() throws IOException {
        persist(prefs.edit().remove("confirmationLaunchedNonce").putString("state", "PENDING_CONFIRMATION")
                .putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_71.text())));
    }

    public void cancelSession() throws Exception {
        if ("uninstall".equals(this.prefs.getString("operation", "")) && hasActiveRequest()) {
            throw new IOException(AndroidMessages.INSTALL_COORDINATOR_72.text());
        }
        int i = this.prefs.getInt("session", -1);
        if (i != -1) {
            PackageInstaller packageInstaller = this.context.getPackageManager().getPackageInstaller();
            if (packageInstaller.getSessionInfo(i) != null) {
                packageInstaller.abandonSession(i);
            }
        }
        SystemConfirmationToken.cancel(this.context, this.prefs.getString("nonce", ""), this.prefs.getString("confirmation", null));
        persist(this.prefs.edit().putString("state", "READY").putBoolean("continueAfterUninstall", false).remove("session").remove("confirmation").remove("nonce").putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_73.text())));
    }

    /** No APK reads: only a persisted request and PackageInstaller state define this boundary. */
    public boolean installationResultBoundary() {
        if (!"install".equals(prefs.getString("operation", "")) || !hasPendingResult()) return false;
        // Version 48 could clear the callback after the system installed the game, retaining this bound receipt.
        String state = hasReturnedSelectedRequest() ? "RESULT" : state();
        boolean returned = prefs.getBoolean("installationReturned", false);
        boolean sessionPresent = !"RESULT".equals(state) && !returned
                && context.getPackageManager().getPackageInstaller().getSessionInfo(prefs.getInt("session", -1)) != null;
        return SelectedApkInstallPolicy.mayCheckInstalledComponent(prefs.getString("operation", ""),
                prefs.getString("selectedFileInstallReceipt", ""), prefs.getString("installRequestReceipt", ""),
                state, returned, sessionPresent);
    }

    public boolean confirmationWasLaunched() {
        return "install".equals(prefs.getString("operation", "")) && !requestNonce().isEmpty()
                && requestNonce().equals(prefs.getString("confirmationLaunchedNonce", ""));
    }

    /** This proof describes a PM component, not save access or successful installation of selected bytes. */
    public boolean acceptedInstalledComponent(InstalledBridgeBinding binding) {
        return binding != null && prefs.getBoolean("installedVerified", false)
                && binding.selectionToken().equals(prefs.getString("installedComponentToken", ""))
                && !hasPendingResult();
    }

    public void reconcile() throws Exception { reconcile(false); }

    public void reconcile(boolean activityReturned) throws Exception {
        if (externalInstallPending()) return; // Only explicit external checks verify this separate route.
        if (activityReturned && "install".equals(prefs.getString("operation", "")) && hasPendingResult())
            persist(prefs.edit().putBoolean("installationReturned", true));
        long started = android.os.SystemClock.elapsedRealtime();
        boolean pending = hasPendingResult();
        try {
            refreshConfirmationAvailability();
            reconcileRequest();
            releaseProvenExpiredConfirmation();
        } finally {
            if (pending && !hasPendingResult()) phase("INSTALL_RESULT", "NOT_APPLICABLE", started,
                    prefs.getBoolean("installedVerified", false) || prefs.getBoolean("continueAfterUninstall", false)
                            ? DiagnosticResult.SUCCEEDED : DiagnosticResult.FAILED);
        }
    }

    /** Atomic with receiver token publication; never latch stale failure over a new callback. */
    private PendingIntent refreshConfirmationAvailability() throws Exception {
        synchronized (SystemConfirmationToken.CHECKPOINT_LOCK) {
            if (!hasActiveRequest() || !("PENDING_CONFIRMATION".equals(state()) || prefs.contains("confirmation"))) return null;
            PendingIntent token = SystemConfirmationToken.find(context, requestNonce(), prefs.getString("confirmation", null));
            if (token == null) {
                persist(prefs.edit().putBoolean("confirmationUnavailable", true)
                        .putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_46.text())));
            } else if (prefs.getBoolean("confirmationUnavailable", false)) {
                persist(prefs.edit().remove("confirmationUnavailable").putString("message", StoredAndroidText.store(prefs.getBoolean("confirmationLaunchBlocked", false) ? prefs.getString("message", "") : AndroidMessages.INSTALL_COORDINATOR_74.text())));
            }
            return token;
        }
    }

    /** Only positively absent, identified install sessions may release a lost-token checkpoint.
     * Never cancel a session, infer an uninstall result, or submit another operation here.
     * Result reconciliation above gets first chance to recognize an installed component.
     */
    private void releaseProvenExpiredConfirmation() throws Exception {
        synchronized (SystemConfirmationToken.CHECKPOINT_LOCK) {
        if (!confirmationUnavailable() || !"install".equals(prefs.getString("operation", ""))) return;
        int session = prefs.getInt("session", -1);
        if (session < 0 || context.getPackageManager().getPackageInstaller().getSessionInfo(session) != null) return;
        if (SystemConfirmationToken.find(context, requestNonce(), prefs.getString("confirmation", null)) != null) return;
        clearCleanupCheckpoint(AndroidMessages.INSTALL_COORDINATOR_47.text());
        clearAutomaticContinuation();
        }
    }

    private void reconcileRequest() throws Exception {
        String strState = hasReturnedSelectedRequest() ? "RESULT" : state();
        String strPendingPackage = pendingPackage();
        if ("uninstall".equals(this.prefs.getString("operation", "")) && (strState.equals("UNINSTALLING") || strState.equals("PENDING_CONFIRMATION") || strState.equals("RESULT"))) {
            try {
                String strIdentity = targetStamp(strPendingPackage);
                if (InstallProgressPolicy.uninstallSettled(true, strState.equals("RESULT"))) {
                    persist(this.prefs.edit().putString("state", "READY").remove("confirmation").remove("nonce").putString("message", StoredAndroidText.store(strIdentity.equals(this.prefs.getString("targetStamp", "")) ? AndroidMessages.INSTALL_COORDINATOR_75.text() : AndroidMessages.INSTALL_COORDINATOR_76.text())));
                    return;
                }
                return;
            } catch (PackageManager.NameNotFoundException unused) {
                SystemConfirmationToken.cancel(this.context, this.prefs.getString("nonce", ""), this.prefs.getString("confirmation", null));
                SharedPreferences.Editor editorPutString = this.prefs.edit().putBoolean("continueAfterUninstall", true).putString("state", "READY").remove("confirmation").remove("nonce").putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_77.text()));
                if (this.prefs.getBoolean("gameRequest", false)) {
                    editorPutString.putBoolean("gameRemoved", true);
                }
                persist(editorPutString);
                return;
            }
        }
        if (strState.equals("INSTALLING") || strState.equals("PENDING_CONFIRMATION") || strState.equals("RESULT")) {
            if (!installationResultBoundary()) return; // Never consume a selected file before system UI returns.
            InstalledBridgeBinding component = null;
            try {
                component = InstalledBridgeResolver.resolve(context, strPendingPackage);
            } catch (PackageManager.NameNotFoundException absent) {
                // PackageManager publication may follow the system result; the caller retries within its bounded budget.
            } catch (IOException | SecurityException invalid) {
                // An unrelated or malformed component cannot authorize access. Keep the failure in diagnostics.
                DiagnosticLog.record(DiagnosticStage.INSTALLATION, DiagnosticResult.REJECTED,
                        AndroidDiagnosticLogging.classify(invalid), AndroidDiagnosticLogging.failureDetails(invalid));
            }
            if (!SelectedApkInstallPolicy.acceptsInstalledComponent(true, component != null)) return;
            // The user explicitly accepts the currently installed same-package component after an install flow,
            // even when the platform returns a cancellation code or omits the final session callback.
            SystemConfirmationToken.cancel(context, requestNonce(), prefs.getString("confirmation", null));
            persist(prefs.edit().putString("completedFileInstallReceipt", prefs.getString("installRequestReceipt", ""))
                    .putString("installedComponentToken", component.selectionToken())
                    .remove("selectedFileInstallReceipt").remove("installRequestReceipt")
                    .putBoolean("installedVerified", true).putString("state", "READY")
                    .remove("confirmation").remove("session").remove("nonce")
                    .putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_COORDINATOR_23.text())));
            // Backend switching remains forbidden while any installation is active. Persist completion first;
            // a crash before this bind is recovered from the completed receipt and current PM metadata.
            SyncRuntime.setInstalledBridge(context, component);
        }
    }
}
