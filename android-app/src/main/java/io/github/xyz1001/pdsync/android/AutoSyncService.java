package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import io.github.xyz1001.pdsync.android.AutoSyncPolicy;
import io.github.xyz1001.pdsync.android.SecureConnectionStore;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import pdsync.android.app.AndroidSyncFacade;
import pdsync.DiagnosticAction;
import pdsync.DiagnosticLog;
import pdsync.DiagnosticFailure;
import pdsync.DiagnosticDetails;
import pdsync.DiagnosticResult;
import pdsync.DiagnosticStage;
import pdsync.DiagnosticTrace;

/* loaded from: classes.dex */
public final class AutoSyncService extends Service {
    static final String HISTORY_CHANGED = "io.github.xyz1001.pdsync.android.AUTO_SYNC_FINISHED";
    private static final String ARM = "pdsync.ARM";
    private static final String CHANNEL = "game-exit-sync";
    private static final int NOTIFICATION = 7301;
    public static final String PREFS = "auto-sync-session-v1";
    private static final String RETURN = "pdsync.RETURN";
    private static final String STOP = "pdsync.STOP";
    private DiagnosticTrace diagnosticTrace;
    private boolean diagnosticFinished;
    private volatile DiagnosticFailure lastDiagnosticFailure = DiagnosticFailure.NONE;
    private SecureConnectionStore.Connection connection;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private AndroidSyncFacade facade;
    private AutoSyncPolicy policy;
    private SharedPreferences prefs;
    private volatile boolean publicationAttemptInProgress;
    private volatile boolean returned;
    private String selectedGameToken;
    private String selectedNamespace;
    private volatile boolean stopped;
    private String token;

    @Override // android.app.Service
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static String arm(Context context) { return arm(context, null); }

    public static String arm(Context context, String controlledToken) {
        AndroidDiagnosticLogging.configure(context);
        DiagnosticTrace armTrace = DiagnosticLog.begin(DiagnosticAction.AUTOMATIC_SYNC, true);
        SharedPreferences sharedPreferences = context.getSharedPreferences("auto-sync-session-v1", 0);
        try {
            if (!SyncRuntime.automaticAllowed(context, SyncRuntime.connection(context))) {
                throw new IllegalStateException(AndroidMessages.AUTO_SYNC_SERVICE_01.text());
            }
            String strSelectionToken = SyncRuntime.currentSelectionToken(context);
            String strCurrentNamespace = SyncRuntime.currentNamespace(context);
            String connectionScope = SyncRuntime.connectionScope(context, SyncRuntime.connection(context));
            if (isActive(sharedPreferences) && (!strSelectionToken.equals(sharedPreferences.getString("gameSelection", "")) || !strCurrentNamespace.equals(sharedPreferences.getString("gameNamespace", "")) || !connectionScope.equals(sharedPreferences.getString("connectionScope", "")))) {
                cancelForConnectionChange(context);
            }
            if (isActive(sharedPreferences) && !sharedPreferences.getString("token", "").isEmpty()) {
                String string = sharedPreferences.getString("token", "");
                if (controlledToken != null && !controlledToken.equals(string))
                    throw new IllegalStateException(AndroidMessages.AUTO_SYNC_SERVICE_02.text());
                startMonitoring(context, sharedPreferences, new Intent(context, (Class<?>) AutoSyncService.class).setAction("pdsync.ARM").putExtra("token", string));
                armTrace.record(DiagnosticStage.GUARD, DiagnosticResult.SUCCEEDED);
                armTrace.record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED);
                return string;
            }
            String string2 = controlledToken == null ? UUID.randomUUID().toString() : controlledToken;
            if (!sharedPreferences.edit().putString("token", string2).putString("state", "ARMED").putLong("startedAt", System.currentTimeMillis()).putString("gameSelection", strSelectionToken).putString("gameNamespace", strCurrentNamespace).putString("connectionScope", connectionScope).putBoolean("departed", false).putBoolean("foregroundSeen", false).putString("result", StoredAndroidText.store(AndroidMessages.AUTO_SYNC_SERVICE_03.text())).commit()) {
                throw new IllegalStateException(AndroidMessages.AUTO_SYNC_SERVICE_04.text());
            }
            startMonitoring(context, sharedPreferences, new Intent(context, (Class<?>) AutoSyncService.class).setAction("pdsync.ARM").putExtra("token", string2));
            armTrace.record(DiagnosticStage.GUARD, DiagnosticResult.SUCCEEDED);
            armTrace.record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED);
            return string2;
        } catch (Exception e) {
            AndroidDiagnosticLogging.failure(armTrace, DiagnosticStage.GUARD, e);
            AndroidDiagnosticLogging.failure(armTrace, DiagnosticStage.FINISHED, e);
            throw new IllegalStateException(AndroidMessages.AUTO_SYNC_SERVICE_05.text(), e);
        }
    }

    public static void cancelForConnectionChange(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("auto-sync-session-v1", 0);
        if (isActive(sharedPreferences)) {
            cancelEvidence(context, sharedPreferences.getString("token", ""));
            sharedPreferences.edit().putString("state", "CANCELLED").putString("result", StoredAndroidText.store(AndroidMessages.AUTO_SYNC_SERVICE_06.text())).commit();
            context.stopService(new Intent(context, (Class<?>) AutoSyncService.class));
            notifyHistoryChanged(context);
        }
    }

    public static void recordDeparture(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("auto-sync-session-v1", 0);
        if (isActive(sharedPreferences)) {
            sharedPreferences.edit().putBoolean("departed", true).commit();
        }
    }

    public static void knownReturn(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("auto-sync-session-v1", 0);
        if (isActive(sharedPreferences) && System.currentTimeMillis() - sharedPreferences.getLong("startedAt", 0L) > 21600000) {
            sharedPreferences.edit().putString("state", "EXPIRED").putString("result", StoredAndroidText.store(AndroidMessages.AUTO_SYNC_SERVICE_07.text())).commit();
            notifyHistoryChanged(context);
        } else if (isActive(sharedPreferences) && sharedPreferences.getBoolean("departed", false)) {
            startMonitoring(context, sharedPreferences, new Intent(context, (Class<?>) AutoSyncService.class).setAction("pdsync.RETURN").putExtra("token", sharedPreferences.getString("token", "")));
        }
    }

    private static void startMonitoring(Context context, SharedPreferences sharedPreferences, Intent intent) {
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            sharedPreferences.edit().putString("state", "BLOCKED").putString("result", StoredAndroidText.store(AndroidMessages.AUTO_SYNC_SERVICE_08.text())).commit();
            notifyHistoryChanged(context);
            throw e;
        }
    }

    private static boolean isActive(SharedPreferences sharedPreferences) {
        String string = sharedPreferences.getString("state", "");
        return string.equals("ARMED") || string.equals("PLAYING") || string.equals("SYNCING");
    }

    private static final String CHECKED_IDLE_FAILURE = "checkedIdleFailure";
    private static final String READ_HISTORY = "readHistoryRecord";

    private static AutoSyncStatusPolicy.Record statusRecord(SharedPreferences preferences) {
        // One snapshot prevents mixing a previous token with a newer session's result.
        java.util.Map<String, ?> values = preferences.getAll();
        Object started = values.get("startedAt");
        return new AutoSyncStatusPolicy.Record(statusString(values, "token", ""),
                statusString(values, "state", ""), statusString(values, "result", AndroidMessages.AUTO_SYNC_SERVICE_09.text()),
                statusString(values, "gameSelection", ""), statusString(values, "gameNamespace", ""),
                started instanceof Long ? (Long) started : 0L, statusString(values, "connectionScope", ""));
    }

    static AutoSyncStatusPolicy.Record historyRefreshRecord(Context context) {
        return statusRecord(context.getSharedPreferences(PREFS, 0));
    }

    static boolean historyRefreshPending(Context context, AutoSyncStatusPolicy.Record record) {
        return AutoSyncHistoryRefresh.unconsumed(record, context.getSharedPreferences(PREFS, 0).getString(READ_HISTORY, ""));
    }

    /** Persist the exact terminal result when its completed read is accepted; cancellation stays pending. */
    static synchronized boolean claimHistoryRefresh(Context context, AutoSyncHistoryRefresh.Ticket ticket) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, 0);
        AutoSyncStatusPolicy.Record current = statusRecord(preferences);
        return ticket.recordKey.equals(current.key()) && historyRefreshPending(context, current)
                && preferences.edit().putString(READ_HISTORY, ticket.recordKey).commit();
    }

    private static void notifyHistoryChanged(Context context) {
        context.sendBroadcast(new Intent(HISTORY_CHANGED).setPackage(context.getPackageName()));
    }

    private static String statusString(java.util.Map<String, ?> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    static AutoSyncStatusPolicy.Record beginManualInspection(Context context) {
        try {
            if (!SyncRuntime.rootMode(context)) return null;
            return AutoSyncStatusPolicy.capture(statusRecord(context.getSharedPreferences(PREFS, 0)),
                    SyncRuntime.currentSelectionToken(context), SyncRuntime.currentNamespace(context));
        } catch (Exception unavailable) { return null; }
    }

    static void verifiedManualInspection(Context context, AutoSyncStatusPolicy.Record before,
            AndroidSyncFacade.Inspection inspection) {
        if (before == null || inspection == null) return;
        try {
            if (!SyncRuntime.rootMode(context)) return;
            SharedPreferences preferences = context.getSharedPreferences(PREFS, 0);
            String acknowledgment = AutoSyncStatusPolicy.acknowledgment(before, statusRecord(preferences),
                    SyncRuntime.currentSelectionToken(context), SyncRuntime.currentNamespace(context),
                    inspection.getKind(), inspection.getNeedsRestoreRecovery(), inspection.isRetryablePending());
            // Separate exact-record acknowledgment cannot overwrite a concurrently started
            // listener or a newer failure. The original terminal state/result remain intact.
            if (acknowledgment != null) preferences.edit().putString(CHECKED_IDLE_FAILURE, acknowledgment).commit();
        } catch (Exception unavailable) { /* Keep the historical warning if the scope cannot be proved. */ }
    }

    public static String status(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, 0);
        AutoSyncStatusPolicy.Record current = statusRecord(preferences);
        try {
            if (!current.token.isEmpty() && (!current.gameSelection.equals(SyncRuntime.currentSelectionToken(context))
                    || !current.namespace.equals(SyncRuntime.currentNamespace(context))
                    || !current.connectionScope.equals(SyncRuntime.connectionScope(context, SyncRuntime.connection(context))))) return "";
            if (!SyncRuntime.rootMode(context)) return StoredAndroidText.render(current.result);
            return StoredAndroidText.render(AutoSyncStatusPolicy.visibleResult(current, preferences.getString(CHECKED_IDLE_FAILURE, ""),
                    SyncRuntime.currentSelectionToken(context), SyncRuntime.currentNamespace(context)));
        } catch (Exception unavailable) { return ""; }
    }

    /** Stable compact status classification; never infer operational state from translated prose. */
    public static String resultKind(Context context) {
        if (status(context).isEmpty()) return "IDLE";
        AutoSyncStatusPolicy.Record record = statusRecord(context.getSharedPreferences(PREFS, 0));
        if (StoredAndroidText.is(record.result, AndroidMessages.AUTO_SYNC_SERVICE_09)) return "IDLE";
        if (StoredAndroidText.is(record.result, AndroidMessages.AUTO_SYNC_SERVICE_30)) return "UPLOADED_CONFLICT";
        if (StoredAndroidText.is(record.result, AndroidMessages.AUTO_SYNC_SERVICE_27)) return "UPLOADED_NEWER_CLOUD";
        if ("SKIPPED_CONFLICT".equals(record.state)) return "HISTORY_CONFLICT";
        if ("UPLOADED".equals(record.state) || "UNCHANGED".equals(record.state)) return "SUCCESS";
        if ("ARMED".equals(record.state) || "PLAYING".equals(record.state) || "SYNCING".equals(record.state)) return "IDLE";
        return "INCOMPLETE";
    }

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
        AndroidDiagnosticLogging.configure(this);
        this.prefs = getSharedPreferences("auto-sync-session-v1", 0);
        ((NotificationManager) getSystemService(NotificationManager.class)).createNotificationChannel(new NotificationChannel("game-exit-sync", AndroidMessages.AUTO_SYNC_SERVICE_10.text(), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        DiagnosticApplication.updateLocale(configuration);
        ((NotificationManager) getSystemService(NotificationManager.class)).createNotificationChannel(
                new NotificationChannel(CHANNEL, AndroidMessages.AUTO_SYNC_SERVICE_10.text(), NotificationManager.IMPORTANCE_LOW));
        if (!stopped && prefs != null && isActive(prefs)) {
            ((NotificationManager) getSystemService(NotificationManager.class)).notify(NOTIFICATION,
                    notification(StoredAndroidText.render(prefs.getString("result", ""))));
        }
    }

    private Notification notification(String str) {
        return new Notification.Builder(this, "game-exit-sync").setSmallIcon(17301628).setContentTitle(AndroidMessages.AUTO_SYNC_SERVICE_11.text()).setContentText(str).setStyle(new Notification.BigTextStyle().bigText(str)).setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, (Class<?>) MainActivity.class), 201326592)).setOngoing(true).addAction(new Notification.Action.Builder((Icon) null, AndroidMessages.AUTO_SYNC_SERVICE_12.text(), PendingIntent.getService(this, 1, new Intent(this, (Class<?>) AutoSyncService.class).setAction("pdsync.STOP"), 201326592)).build()).build();
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int i, int i2) {
        if (intent == null) {
            stopSelf();
            return 2;
        }
        if ("pdsync.STOP".equals(intent.getAction())) {
            finish("CANCELLED", (this.publicationAttemptInProgress ? AndroidMessages.AUTO_STOPPED_PENDING : AndroidMessages.AUTO_STOPPED).text());
            return 2;
        }
        String stringExtra = intent.getStringExtra("token");
        if (stringExtra == null || !stringExtra.equals(this.prefs.getString("token", "")) || !isActive(this.prefs)) {
            stopSelf();
            return 2;
        }
        try {
            RuntimePlatformCompat.startDataSyncForeground(this, 7301, notification(AndroidMessages.AUTO_SYNC_SERVICE_16.text()));
            if ("pdsync.RETURN".equals(intent.getAction())) {
                this.returned = true;
            }
            if (stringExtra.equals(this.token)) {
                return 2;
            }
            this.token = stringExtra;
            this.diagnosticTrace = DiagnosticLog.begin(DiagnosticAction.AUTOMATIC_SYNC, true);
            this.diagnosticFinished = false;
            this.lastDiagnosticFailure = DiagnosticFailure.NONE;
            this.policy = new AutoSyncPolicy(this.prefs.getLong("startedAt", System.currentTimeMillis()), this.prefs.getBoolean("foregroundSeen", false));
            this.executor.execute(new Runnable() { // from class: io.github.xyz1001.pdsync.android.AutoSyncService$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    AutoSyncService.this.m1x57256bb9();
                }
            });
            return 2;
        } catch (RuntimeException unused) {
            automaticFailure(DiagnosticStage.RUNTIME, unused);
            finish("BLOCKED", AndroidMessages.AUTO_SYNC_SERVICE_17.text());
            return 2;
        }
    }

    /* renamed from: lambda$onStartCommand$0$io-github-xyz1001-pdsync-android-AutoSyncService, reason: not valid java name */
    /* synthetic */ void m1x57256bb9() {
        try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(automaticTrace())) {
            this.connection = SyncRuntime.connection(this);
            this.selectedGameToken = SyncRuntime.currentSelectionToken(this);
            this.selectedNamespace = SyncRuntime.currentNamespace(this);
            if (!this.selectedGameToken.equals(this.prefs.getString("gameSelection", "")) || !this.selectedNamespace.equals(this.prefs.getString("gameNamespace", "")) || !SyncRuntime.connectionScope(this, this.connection).equals(this.prefs.getString("connectionScope", "")) || !SyncRuntime.automaticAllowed(this, this.connection)) {
                throw new IllegalStateException(AndroidMessages.AUTO_SYNC_SERVICE_18.text());
            }
            this.facade = SyncRuntime.facade(this, this.connection);
            poll();
        } catch (Exception unused) {
            automaticFailure(DiagnosticStage.GUARD, unused);
            finish("BLOCKED", unused instanceof ManagedAccountClient.ManagedAccountException
                    || unused instanceof OfficialSyncAccess.AccessException
                    ? unused.getMessage() : AndroidMessages.AUTO_SYNC_SERVICE_20.text());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void poll() {
        try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(automaticTrace())) {
        if (AutoSyncPolicy.mayPublish(this.stopped, isActive(this.prefs), this.token, this.prefs.getString("token", ""))) {
            if (!currentGameAndPolicy()) {
                finish("PAUSED", AndroidMessages.AUTO_SYNC_SERVICE_21.text());
                return;
            }
            // Returning to this app after departure is an existing passive trigger.
            // Do not query game processes or fabricate foreground/session evidence.
            // Keep the trigger set so existing transient retries can finish publication.
            if (this.returned) {
                attemptUpload();
                return;
            }
            if (System.currentTimeMillis() - this.prefs.getLong("startedAt", 0L) > 21600000L) {
                finish("EXPIRED", AndroidMessages.AUTO_SYNC_SERVICE_07.text());
                return;
            }
            schedule(1500L);
        }

        } catch (RuntimeException failure) {
            AndroidDiagnosticLogging.failure(automaticTrace(), DiagnosticStage.RUNTIME, failure);
            failDiagnostics(failure);
            throw failure;
        }
    }

    private void schedule(long j) {
        if (this.stopped) {
            return;
        }
        this.executor.schedule(new Runnable() { // from class: io.github.xyz1001.pdsync.android.AutoSyncService$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                AutoSyncService.this.poll();
            }
        }, j, TimeUnit.MILLISECONDS);
    }

    private void attemptUpload() {
        if (this.stopped) {
            return;
        }
        automaticTrace().record(DiagnosticStage.WORK, DiagnosticResult.STARTED);
        this.prefs.edit().putString("state", "SYNCING").putString("result", StoredAndroidText.store(AndroidMessages.AUTO_SYNC_SERVICE_24.text())).commit();
        ((NotificationManager) getSystemService(NotificationManager.class)).notify(7301, notification(AndroidMessages.AUTO_SYNC_SERVICE_25.text()));
        try {
            synchronized (SyncRuntime.OPERATION_LOCK) {
                if (AutoSyncPolicy.mayPublish(this.stopped, isActive(this.prefs), this.token, this.prefs.getString("token", ""))) {
                    if (!currentGameAndPolicy()) {
                        finish("PAUSED", AndroidMessages.AUTO_SYNC_SERVICE_26.text());
                        return;
                    }
                    this.publicationAttemptInProgress = true;
                    try {
                        // The listener can outlive the actual service deadline. Recheck immediately
                        // before automatic I/O even when its facade was created while active.
                        SyncRuntime.requireOfficialSyncAccess(this, this.connection, this.facade);
                        AndroidSyncFacade.AutoUploadResult autoUploadResultAutoUpload = this.facade.autoUpload();
                        String status = autoUploadResultAutoUpload.getStatus();
                        this.lastDiagnosticFailure = AndroidDiagnosticLogging.automaticFailure(autoUploadResultAutoUpload.getReasonCode());
                        automaticTrace().record(DiagnosticStage.VERIFY, AndroidDiagnosticLogging.automaticResult(status),
                                AndroidDiagnosticLogging.automaticFailure(autoUploadResultAutoUpload.getReasonCode()),
                                DiagnosticDetails.builder().syncBlockReason(autoUploadResultAutoUpload.getReasonCode())
                                        .failureSource("SYNC_COMPARE").build());
                        if (status.equals("UPLOADED")) {
                            finish("UPLOADED", autoUploadResultAutoUpload.getNeedsUserChoice() ? AndroidMessages.AUTO_SYNC_SERVICE_27.text() : AndroidMessages.AUTO_SYNC_SERVICE_28.text());
                            return;
                        }
                        if (status.equals("UNCHANGED")) {
                            finish("UNCHANGED", AndroidMessages.AUTO_SYNC_SERVICE_29.text());
                            return;
                        }
                        if (status.equals("SKIPPED_CONFLICT")) {
                            finish("SKIPPED_CONFLICT", autoUploadResultAutoUpload.getSnapshotId() != null ? AndroidMessages.AUTO_SYNC_SERVICE_30.text() : "pdsync.auto.blocked:" + AutoSyncMessages.knownCode(autoUploadResultAutoUpload.getReasonCode()));
                        } else if (status.equals("SKIPPED_UNCERTAIN")) {
                            finish("SKIPPED_UNCERTAIN", "pdsync.auto.blocked:" + AutoSyncMessages.knownCode(autoUploadResultAutoUpload.getReasonCode()));
                        } else {
                            retryTransient("pdsync.auto.blocked:" + AutoSyncMessages.knownCode(autoUploadResultAutoUpload.getReasonCode()));
                        }
                    } finally {
                        this.publicationAttemptInProgress = false;
                    }
                }
            }
        } catch (Exception unused) {
            automaticFailure(DiagnosticStage.WORK, unused);
            if (unused instanceof OfficialSyncAccess.AccessException
                    || (unused instanceof ManagedAccountClient.ManagedAccountException
                    && (((ManagedAccountClient.ManagedAccountException) unused).kind == ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED
                    || ((ManagedAccountClient.ManagedAccountException) unused).kind == ManagedAccountClient.FailureKind.ACCOUNT_INACTIVE))) {
                finish("BLOCKED", unused.getMessage());
            } else retryTransient(AndroidMessages.AUTO_SYNC_SERVICE_31.text());
        }
    }

    private boolean currentGameAndPolicy() {
        try {
            String str = this.selectedGameToken;
            if (str != null && str.equals(SyncRuntime.currentSelectionToken(this)) && this.selectedNamespace.equals(SyncRuntime.currentNamespace(this))) {
                if (SyncRuntime.connectionScope(this, SyncRuntime.connection(this)).equals(this.prefs.getString("connectionScope", ""))
                        && SyncRuntime.automaticAllowed(this, this.connection)) {
                    return true;
                }
            }
        } catch (Exception unused) {
        }
        return false;
    }

    private void retryTransient(String str) {
        long jNextTransientRetryMillis = this.policy.nextTransientRetryMillis();
        if (jNextTransientRetryMillis >= 0) {
            automaticTrace().record(DiagnosticStage.WORK, DiagnosticResult.PENDING);
            schedule(jNextTransientRetryMillis);
        } else {
            finish("RETRY_LATER", str);
        }
    }

    private synchronized void finish(String str, String str2) {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        finishDiagnostics(AndroidDiagnosticLogging.automaticResult(str),
                "TIMEOUT".equals(str) ? DiagnosticFailure.TIMEOUT : DiagnosticFailure.NONE);
        cancelEvidence(this, this.token);
        String str3 = this.token;
        boolean published = false;
        if (str3 == null || str3.equals(this.prefs.getString("token", ""))) {
            published = this.prefs.edit().putString("state", str).putString("result", StoredAndroidText.store(str2)).commit();
        }
        this.executor.shutdownNow();
        stopForeground(1);
        stopSelf();
        if (published) notifyHistoryChanged(this);
    }

    public void onTimeout(int i, int i2) {
        finish("TIMEOUT", (this.publicationAttemptInProgress ? AndroidMessages.AUTO_TIMED_OUT_PENDING : AndroidMessages.AUTO_TIMED_OUT).text());
    }

    private static void cancelEvidence(Context context, String token) {
        if (token == null || token.isEmpty()) return;
        try { AndroidSyncFacade.cancelControlledSession(new java.io.File(context.getFilesDir().getCanonicalFile(), "sync").toPath(), token); }
        catch (Exception ignored) { /* No evidence is persisted; expiry and process death also invalidate it. */ }
    }

    private synchronized void automaticFailure(DiagnosticStage stage, Throwable error) {
        if (this.diagnosticFinished) return;
        this.lastDiagnosticFailure = AndroidDiagnosticLogging.classify(error);
        AndroidDiagnosticLogging.failure(automaticTrace(), stage, error);
    }

    private synchronized void failDiagnostics(Throwable failure) {
        if (this.diagnosticFinished) return;
        AndroidDiagnosticLogging.failure(automaticTrace(), DiagnosticStage.FINISHED, failure);
        this.diagnosticFinished = true;
    }

    private synchronized void finishDiagnostics(DiagnosticResult result, DiagnosticFailure failure) {
        if (this.diagnosticFinished) return;
        if (failure == DiagnosticFailure.NONE && (result == DiagnosticResult.FAILED || result == DiagnosticResult.REJECTED))
            failure = this.lastDiagnosticFailure;
        if (failure == DiagnosticFailure.NONE && result == DiagnosticResult.CANCELED) failure = DiagnosticFailure.CANCELED;
        automaticTrace().record(DiagnosticStage.FINISHED, result, failure);
        this.diagnosticFinished = true;
    }

    private synchronized DiagnosticTrace automaticTrace() {
        if (this.diagnosticTrace == null) this.diagnosticTrace = DiagnosticLog.begin(DiagnosticAction.AUTOMATIC_SYNC, true);
        return this.diagnosticTrace;
    }

    @Override // android.app.Service
    public void onDestroy() {
        if (!this.stopped && this.diagnosticTrace != null)
            finishDiagnostics(DiagnosticResult.CANCELED, DiagnosticFailure.CANCELED);
        cancelEvidence(this, this.token);
        this.stopped = true;
        this.executor.shutdownNow();
        super.onDestroy();
    }
}
