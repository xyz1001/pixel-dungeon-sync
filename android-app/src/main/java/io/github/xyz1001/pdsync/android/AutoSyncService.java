package io.github.xyz1001.pdsync.android;

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

/* loaded from: classes.dex */
public final class AutoSyncService extends Service {
    static final String HISTORY_CHANGED = "io.github.xyz1001.pdsync.android.AUTO_SYNC_FINISHED";
    private static final String ARM = "pdsync.ARM";
    private static final String CHANNEL = "game-exit-sync";
    private static final int NOTIFICATION = 7301;
    public static final String PREFS = "auto-sync-session-v1";
    private static final String RETURN = "pdsync.RETURN";
    private static final String STOP = "pdsync.STOP";
    private GameAccessBackend bridge;
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
        SharedPreferences sharedPreferences = context.getSharedPreferences("auto-sync-session-v1", 0);
        try {
            if (!SyncRuntime.automaticAllowed(context, SyncRuntime.connection(context))) {
                throw new IllegalStateException("自动上传暂停，请先完成存档选择。");
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
                    throw new IllegalStateException("上一轮退出监听尚未结束，本次身份连续性不作推断。");
                startMonitoring(context, sharedPreferences, new Intent(context, (Class<?>) AutoSyncService.class).setAction("pdsync.ARM").putExtra("token", string));
                return string;
            }
            String string2 = controlledToken == null ? UUID.randomUUID().toString() : controlledToken;
            if (!sharedPreferences.edit().putString("token", string2).putString("state", "ARMED").putLong("startedAt", System.currentTimeMillis()).putString("gameSelection", strSelectionToken).putString("gameNamespace", strCurrentNamespace).putString("connectionScope", connectionScope).putBoolean("departed", false).putBoolean("foregroundSeen", false).putString("result", "等待本次游戏退出后检查自动上传。").commit()) {
                throw new IllegalStateException("无法保存自动同步会话，本次不会后台上传。");
            }
            startMonitoring(context, sharedPreferences, new Intent(context, (Class<?>) AutoSyncService.class).setAction("pdsync.ARM").putExtra("token", string2));
            return string2;
        } catch (Exception e) {
            throw new IllegalStateException("自动上传尚未就绪，请先完成游戏与云端存档配置。", e);
        }
    }

    public static void cancelForConnectionChange(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("auto-sync-session-v1", 0);
        if (isActive(sharedPreferences)) {
            cancelEvidence(context, sharedPreferences.getString("token", ""));
            sharedPreferences.edit().putString("state", "CANCELLED").putString("result", "连接设置已变更，旧连接的本次监听已停止。此前已提交的上传仍可能完成；下次启动游戏使用新设置。").commit();
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
            sharedPreferences.edit().putString("state", "EXPIRED").putString("result", "上次监听已超出 Android 数据同步会话时限，请手动检查或重新启动游戏。").commit();
            notifyHistoryChanged(context);
        } else if (isActive(sharedPreferences) && sharedPreferences.getBoolean("departed", false)) {
            startMonitoring(context, sharedPreferences, new Intent(context, (Class<?>) AutoSyncService.class).setAction("pdsync.RETURN").putExtra("token", sharedPreferences.getString("token", "")));
        }
    }

    private static void startMonitoring(Context context, SharedPreferences sharedPreferences, Intent intent) {
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            sharedPreferences.edit().putString("state", "BLOCKED").putString("result", "系统没有允许本次后台同步，请返回首页手动检查。").commit();
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
                statusString(values, "state", ""), statusString(values, "result", "从本应用启动游戏后，正常退出且没有冲突时自动上传。"),
                statusString(values, "gameSelection", ""), statusString(values, "gameNamespace", ""),
                started instanceof Long ? (Long) started : 0L, statusString(values, "connectionScope", ""));
    }

    static AutoSyncStatusPolicy.Record historyRefreshRecord(Context context) {
        return statusRecord(context.getSharedPreferences(PREFS, 0));
    }

    static boolean historyRefreshPending(Context context, AutoSyncStatusPolicy.Record record) {
        return AutoSyncHistoryRefresh.unconsumed(record, context.getSharedPreferences(PREFS, 0).getString(READ_HISTORY, ""));
    }

    /** Persist the exact consumed terminal result before I/O, so restart cannot replay it. */
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
            if (!SyncRuntime.rootMode(context)) return current.result;
            return AutoSyncStatusPolicy.visibleResult(current, preferences.getString(CHECKED_IDLE_FAILURE, ""),
                    SyncRuntime.currentSelectionToken(context), SyncRuntime.currentNamespace(context));
        } catch (Exception unavailable) { return ""; }
    }

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
        this.prefs = getSharedPreferences("auto-sync-session-v1", 0);
        ((NotificationManager) getSystemService(NotificationManager.class)).createNotificationChannel(new NotificationChannel("game-exit-sync", "退出游戏后同步", 2));
    }

    private Notification notification(String str) {
        return new Notification.Builder(this, "game-exit-sync").setSmallIcon(17301628).setContentTitle("像素地牢 · 退出后同步").setContentText(str).setStyle(new Notification.BigTextStyle().bigText(str)).setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, (Class<?>) MainActivity.class), 201326592)).setOngoing(true).addAction(new Notification.Action.Builder((Icon) null, "停止本次监听", PendingIntent.getService(this, 1, new Intent(this, (Class<?>) AutoSyncService.class).setAction("pdsync.STOP"), 201326592)).build()).build();
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int i, int i2) {
        if (intent == null) {
            stopSelf();
            return 2;
        }
        if ("pdsync.STOP".equals(intent.getAction())) {
            StringBuilder sb = new StringBuilder("已停止本次退出同步监听。");
            sb.append(this.publicationAttemptInProgress ? "已提交的上传仍可能完成，请检查原连接。" : "");
            sb.append("下次从本应用启动游戏会重新开启。");
            finish("CANCELLED", sb.toString());
            return 2;
        }
        String stringExtra = intent.getStringExtra("token");
        if (stringExtra == null || !stringExtra.equals(this.prefs.getString("token", "")) || !isActive(this.prefs)) {
            stopSelf();
            return 2;
        }
        try {
            RuntimePlatformCompat.startDataSyncForeground(this, 7301, notification("等待游戏退出或进入后台；有冲突会跳过上传。"));
            if ("pdsync.RETURN".equals(intent.getAction())) {
                this.returned = true;
            }
            if (stringExtra.equals(this.token)) {
                return 2;
            }
            this.token = stringExtra;
            this.policy = new AutoSyncPolicy(this.prefs.getLong("startedAt", System.currentTimeMillis()), this.prefs.getBoolean("foregroundSeen", false));
            this.executor.execute(new Runnable() { // from class: io.github.xyz1001.pdsync.android.AutoSyncService$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    AutoSyncService.this.m1x57256bb9();
                }
            });
            return 2;
        } catch (RuntimeException unused) {
            finish("BLOCKED", "系统不允许本次后台同步。请返回首页手动检查；游戏仍可正常使用。");
            return 2;
        }
    }

    /* renamed from: lambda$onStartCommand$0$io-github-xyz1001-pdsync-android-AutoSyncService, reason: not valid java name */
    /* synthetic */ void m1x57256bb9() {
        try {
            this.connection = SyncRuntime.connection(this);
            this.selectedGameToken = SyncRuntime.currentSelectionToken(this);
            this.selectedNamespace = SyncRuntime.currentNamespace(this);
            if (!this.selectedGameToken.equals(this.prefs.getString("gameSelection", "")) || !this.selectedNamespace.equals(this.prefs.getString("gameNamespace", "")) || !SyncRuntime.connectionScope(this, this.connection).equals(this.prefs.getString("connectionScope", "")) || !SyncRuntime.automaticAllowed(this, this.connection)) {
                throw new IllegalStateException("Game selection changed");
            }
            this.bridge = SyncRuntime.backend(this);
            if (!this.bridge.capabilities().lifecycleObservation)
                throw new IllegalStateException("Selected access backend cannot observe lifecycle");
            this.facade = SyncRuntime.facade(this, this.connection);
            poll();
        } catch (Exception unused) {
            finish("BLOCKED", "本次自动同步未开始：WebDAV 连接或未完成恢复需要检查。请返回首页处理。");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void poll() {
        if (AutoSyncPolicy.mayPublish(this.stopped, isActive(this.prefs), this.token, this.prefs.getString("token", ""))) {
            if (!currentGameAndPolicy()) {
                finish("PAUSED", "游戏、云端档案或自动上传设置已变化，本次监听已停止。");
                return;
            }
            if (this.returned) {
                this.policy.knownReturn();
                this.returned = false;
            }
            AndroidSyncFacade.ControlledObservation controlledObservation = this.facade.beginControlledObservation(this.token);
            GameAccessBackend.Observation observationObserveLifecycle = this.bridge.observeLifecycle();
            this.facade.observeControlledSession(controlledObservation, observationObserveLifecycle.reliable, observationObserveLifecycle.foreground);
            // Root can reliably report an active background process. It is not yet a quiet exit.
            // CACHED or ABSENT produces ready=true; process absence alone is never required.
            boolean reliableForAutomatic = reliableForAutomaticObservation(this.bridge.mode(), observationObserveLifecycle);
            AutoSyncPolicy.Step stepObserve = this.policy.observe(reliableForAutomatic, observationObserveLifecycle.foreground, System.currentTimeMillis());
            if (stepObserve == AutoSyncPolicy.Step.STOP_UNKNOWN) {
                finish("BLOCKED", AutoSyncStatusPolicy.IDLE_FAILURE);
                return;
            }
            if (stepObserve == AutoSyncPolicy.Step.STOP_UNOBSERVED) {
                finish("BLOCKED", "未观察到本次游戏前台活动，未猜测退出事件。请从本应用重新启动游戏或手动检查存档。");
                return;
            }
            if (stepObserve == AutoSyncPolicy.Step.UPLOAD) {
                attemptUpload();
                return;
            }
            if (observationObserveLifecycle.reliable && observationObserveLifecycle.foreground) {
                this.prefs.edit().putBoolean("foregroundSeen", true).putString("state", "PLAYING").putString("result", "游戏进行中，退出后将检查无冲突上传。").commit();
            }
            schedule(1500L);
        }
    }

    static boolean reliableForAutomaticObservation(GameAccessBackend.Mode mode, GameAccessBackend.Observation observation) {
        return observation.reliable && (mode != GameAccessBackend.Mode.ROOT_DIRECT
                || observation.foreground || observation.ready);
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
        this.prefs.edit().putString("state", "SYNCING").putString("result", "已观察到退出／后台，正在检查稳定存档与冲突。").commit();
        ((NotificationManager) getSystemService(NotificationManager.class)).notify(7301, notification("正在检查稳定存档；只有无冲突时才上传。"));
        try {
            synchronized (SyncRuntime.OPERATION_LOCK) {
                if (AutoSyncPolicy.mayPublish(this.stopped, isActive(this.prefs), this.token, this.prefs.getString("token", ""))) {
                    if (!currentGameAndPolicy()) {
                        finish("PAUSED", "当前配置不允许自动上传，本次已跳过。");
                        return;
                    }
                    this.publicationAttemptInProgress = true;
                    try {
                        AndroidSyncFacade.AutoUploadResult autoUploadResultAutoUpload = this.facade.autoUpload();
                        String status = autoUploadResultAutoUpload.getStatus();
                        if (status.equals("UPLOADED")) {
                            finish("UPLOADED", autoUploadResultAutoUpload.getNeedsUserChoice() ? "本机版本已上传，但云端随后又有新版本。请检查后再恢复，未自动选择赢家。" : "退出后自动上传成功。");
                            return;
                        }
                        if (status.equals("UNCHANGED")) {
                            finish("UNCHANGED", "本机与云端一致或没有新存档，无需上传。");
                            return;
                        }
                        if (status.equals("SKIPPED_CONFLICT")) {
                            finish("SKIPPED_CONFLICT", autoUploadResultAutoUpload.getSnapshotId() != null ? "同步期间出现并发分支，已保留分支并停止后续自动同步。请在首页手动处理。" : AutoSyncMessages.blocked(autoUploadResultAutoUpload.getReasonCode()));
                        } else if (status.equals("SKIPPED_UNCERTAIN")) {
                            finish("SKIPPED_UNCERTAIN", AutoSyncMessages.blocked(autoUploadResultAutoUpload.getReasonCode()));
                        } else {
                            retryTransient(AutoSyncMessages.blocked(autoUploadResultAutoUpload.getReasonCode()));
                        }
                    } finally {
                        this.publicationAttemptInProgress = false;
                    }
                }
            }
        } catch (Exception unused) {
            retryTransient("本次自动同步未完成。存档未被当作上传成功，请在首页检查后重试。");
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
        cancelEvidence(this, this.token);
        String str3 = this.token;
        boolean published = false;
        if (str3 == null || str3.equals(this.prefs.getString("token", ""))) {
            published = this.prefs.edit().putString("state", str).putString("result", str2).commit();
        }
        this.executor.shutdownNow();
        stopForeground(1);
        stopSelf();
        if (published) notifyHistoryChanged(this);
    }

    public void onTimeout(int i, int i2) {
        StringBuilder sb = new StringBuilder("Android 的后台数据同步时限已到，本次监听已停止。");
        sb.append(this.publicationAttemptInProgress ? "已提交的上传仍可能完成，请检查状态。" : "");
        sb.append("可从首页检查或下次启动游戏时重新开启。");
        finish("TIMEOUT", sb.toString());
    }

    private static void cancelEvidence(Context context, String token) {
        if (token == null || token.isEmpty()) return;
        try { AndroidSyncFacade.cancelControlledSession(new java.io.File(context.getFilesDir().getCanonicalFile(), "sync").toPath(), token); }
        catch (Exception ignored) { /* No evidence is persisted; expiry and process death also invalidate it. */ }
    }

    @Override // android.app.Service
    public void onDestroy() {
        cancelEvidence(this, this.token);
        this.stopped = true;
        this.executor.shutdownNow();
        super.onDestroy();
    }
}
