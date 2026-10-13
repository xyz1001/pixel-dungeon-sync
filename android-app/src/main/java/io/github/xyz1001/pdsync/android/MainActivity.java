package io.github.xyz1001.pdsync.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import io.github.xyz1001.pdsync.android.BridgeClient;
import io.github.xyz1001.pdsync.android.BridgeDiagnostics;
import io.github.xyz1001.pdsync.android.ConnectionUpdatePolicy;
import io.github.xyz1001.pdsync.android.InstallationReadiness;
import io.github.xyz1001.pdsync.android.MainActivity;
import io.github.xyz1001.pdsync.android.SecureConnectionStore;
import io.github.xyz1001.pdsync.android.TrustedPrivateDirectory;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import io.github.xyz1001.pdsync.android.prepare.PreparationDiagnostics;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import pdsync.AppendCommit;
import pdsync.DirectorySnapshotMetadata;
import pdsync.EmptySetupCompletion;
import pdsync.NoviceCopy;
import pdsync.OnboardingGate;
import pdsync.OnboardingKind;
import pdsync.OnboardingProgress;
import pdsync.SaveSummary;
import pdsync.SyncHistory;
import pdsync.android.app.AndroidSyncFacade;
import pdsync.presentation.HistoryEdge;
import pdsync.presentation.HistoryNode;
import pdsync.presentation.HistoryPresentation;
import pdsync.presentation.SyncPage;

/* loaded from: classes.dex */
public final class MainActivity extends Activity {
    private static final int ACTIVATE_HELPER = 8103;
    private static final int GREEN = -13474224;
    private static final int INK = -14469589;
    private static final int MUTED = -10129303;
    private static final int PICK_APK = 8101;
    private LinearLayout body;
    private boolean busy;
    private boolean cloudVerified;
    private String confirmationButton;
    private Runnable confirmationMerge;
    private SyncPage confirmationReturn;
    private String confirmationText;
    private String confirmationTitle;
    private Runnable confirmedAction;
    private SecureConnectionStore.Connection connection;
    private boolean detailsExpanded;
    private boolean feedbackError;
    private boolean draftLoaded;
    private EditText endpointInput;
    private AndroidSyncFacade facade;
    private AndroidSyncFacade controlledLaunchFacade;
    private String controlledLaunchToken;
    private LinearLayout footer;
    private SyncHistory history;
    private int historyPage;
    private AndroidSyncFacade.Inspection inspection;
    private InstallCoordinator installer;
    private SaveSummary localPreview;
    private EditText passwordInput;
    private GamePreparationService preparation;
    private GamePreparationService.PreparedPair prepared;
    private SelectedRootGame rootGame;
    private GameAccessBackend.Capabilities accessCapabilities;
    private boolean previewAttempted;
    private boolean readinessRefreshPending;
    private boolean receiverRegistered;
    private SyncPage renderedPage;
    private volatile boolean resumed;
    private LinearLayout root;
    private ScrollView scroll;
    private SecureConnectionStore secrets;
    private String selected;
    private TextView status;
    private String testedDraft;
    private ConnectionEntryModel connectionEntry;
    private String officialUser = "";
    private String officialPassword = "";
    private SecureConnectionStore.Connection verifiedOfficial;
    private ConnectionAuthAttempt<SecureConnectionStore.Connection> officialAttempt;
    private RetainedConnectionUi retainedConnectionUi;

    /** Memory-only rotation state; never saved to Bundle, logs, or the connection file. */
    private static final class RetainedConnectionUi {
        final boolean onboarding;
        final ConnectionEntryModel entry;
        final String endpoint, user, password, profile, officialUser, officialPassword, testedProof;
        final ConnectionAuthAttempt<SecureConnectionStore.Connection> attempt;
        final SecureConnectionStore.Connection verified;
        RetainedConnectionUi(MainActivity activity) {
            onboarding = activity.onboardingActive;
            entry = ConnectionEntryModel.restore(activity.connectionEntry.origin(), activity.connectionEntry.mode(), activity.connectionEntry.selectedService());
            endpoint = activity.draftEndpoint; user = activity.draftUser; password = activity.draftPassword;
            profile = activity.draftProfile; officialUser = activity.officialUser; officialPassword = activity.officialPassword;
            attempt = activity.officialAttempt; verified = activity.verifiedOfficial; testedProof = activity.testedDraft;
        }
    }
    private EditText userInput;

    /* renamed from: io, reason: collision with root package name */
    private final ExecutorService f9io = Executors.newSingleThreadExecutor();
    private SyncPage page = SyncPage.DISCOVERY;
    private boolean initialLoad = true;
    private boolean onboardingActive = true;
    private String selectedNamespace = "";
    private String notice = "请选择游戏。";
    private String details = "";
    private InstallationReadiness readinessState = InstallationReadiness.noSelection();
    private String diagnosticReport = "";
    private String bridgeAccessCode = "NOT_CHECKED";
    private OnboardingProgress progress = OnboardingProgress.PENDING;
    private String draftEndpoint = "";
    private String draftUser = "";
    private String draftPassword = "";
    private String draftProfile = "default";
    private final InstalledAppPickerModel.State installedPickerState = new InstalledAppPickerModel.State();
    private String connectionLoadError = "";
    private final BroadcastReceiver installChanged = new BroadcastReceiver() { // from class: io.github.xyz1001.pdsync.android.MainActivity.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            MainActivity.this.requestReadinessRefresh();
        }
    };

    /* JADX INFO: Access modifiers changed from: private */
    interface Done<T> {
        void accept(T t) throws Exception;
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface Task<T> {
        T run() throws Exception;
    }

    @Override // android.app.Activity
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().addFlags(8192);
        this.preparation = new GamePreparationService(this);
        this.installer = new InstallCoordinator(this);
        this.secrets = new SecureConnectionStore(this);
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof RetainedConnectionUi) this.retainedConnectionUi = (RetainedConnectionUi) retained;
        if (bundle != null) {
            try {
                this.page = SyncPage.valueOf(bundle.getString("page", "DISCOVERY"));
            } catch (IllegalArgumentException unused) {
            }
            if (this.page == SyncPage.CONFIRM) {
                this.page = SyncPage.HOME;
            }
            this.selected = bundle.getString("selected");
            this.historyPage = bundle.getInt("historyPage", 0);
            this.installedPickerState.setQuery(bundle.getString("installedPicker.query", ""));
            this.installedPickerState.setShowAll(bundle.getBoolean("installedPicker.showAll", false));
        }
        if (this.retainedConnectionUi != null) restoreConnectionUi(this.retainedConnectionUi);
        render();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        if (this.page != SyncPage.CONNECTION || this.connectionEntry == null) return null;
        captureDraft();
        return new RetainedConnectionUi(this);
    }

    private void restoreConnectionUi(RetainedConnectionUi retained) {
        this.onboardingActive = retained.onboarding;
        this.connectionEntry = retained.entry;
        this.draftEndpoint = retained.endpoint; this.draftUser = retained.user;
        this.draftPassword = retained.password; this.draftProfile = retained.profile;
        this.officialUser = retained.officialUser; this.officialPassword = retained.officialPassword;
        this.officialAttempt = retained.attempt; this.verifiedOfficial = retained.verified;
        this.draftLoaded = true;
        this.testedDraft = retained.testedProof;
        this.page = SyncPage.CONNECTION;
    }

    @Override // android.app.Activity
    protected void onSaveInstanceState(Bundle bundle) {
        super.onSaveInstanceState(bundle);
        bundle.putString("page", this.page.name());
        bundle.putString("selected", this.selected);
        bundle.putInt("historyPage", this.historyPage);
        bundle.putString("installedPicker.query", this.installedPickerState.query());
        bundle.putBoolean("installedPicker.showAll", this.installedPickerState.showAll());
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        this.resumed = true;
        if (!this.receiverRegistered) {
            RuntimePlatformCompat.registerInternalReceiver(this, this.installChanged, new IntentFilter("io.github.xyz1001.pdsync.android.INSTALL_CHANGED"));
            this.receiverRegistered = true;
        }
        try {
            AutoSyncService.knownReturn(this);
        } catch (RuntimeException unused) {
        }
        requestReadinessRefresh();
    }

    @Override // android.app.Activity
    protected void onPause() {
        this.resumed = false;
        AutoSyncService.recordDeparture(this);
        super.onPause();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        if (this.receiverRegistered) {
            unregisterReceiver(this.installChanged);
        }
        this.f9io.shutdown();
        super.onDestroy();
    }

    @Override // android.app.Activity
    public void onBackPressed() {
        if (this.busy) {
            this.notice = "操作进行中，请等待完成。";
            this.status.setText("操作进行中，请等待完成。");
            return;
        }
        if (this.page == SyncPage.CONFIRM) {
            cancelConfirmation();
            return;
        }
        if (this.page == SyncPage.CONFLICT) {
            navigate(SyncPage.HOME);
            return;
        }
        if (this.page == SyncPage.SETTINGS) {
            navigate(SyncPage.HOME);
            return;
        }
        if (this.page == SyncPage.CONNECTION) {
            backFromConnection();
            return;
        }
        if (this.page == SyncPage.PATH) {
            navigate(SyncPage.DISCOVERY);
        } else if (this.page == SyncPage.COMPARISON) {
            postpone();
        } else {
            super.onBackPressed();
        }
    }

    private <T> void work(String str, final Task<T> task, final Done<T> done) {
        if (this.busy || isDestroyed()) {
            return;
        }
        captureDraft();
        this.diagnosticReport = "";
        this.details = "";
        this.feedbackError = false;
        this.busy = true;
        this.notice = str;
        this.status.setText(str);
        this.status.setVisibility(View.VISIBLE);
        disable(this.root);
        this.f9io.execute(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda56
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m150lambda$work$2$iogithubxyz1001pdsyncandroidMainActivity(task, done);
            }
        });
    }

    /* renamed from: lambda$work$2$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m150lambda$work$2$iogithubxyz1001pdsyncandroidMainActivity(Task task, final Done done) {
        final Object objRun;
        try {
            synchronized (SyncRuntime.OPERATION_LOCK) {
                objRun = task.run();
            }
            runOnUiThread(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda105
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m148lambda$work$0$iogithubxyz1001pdsyncandroidMainActivity(done, objRun);
                }
            });
        } catch (Exception e) {
            runOnUiThread(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda106
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m149lambda$work$1$iogithubxyz1001pdsyncandroidMainActivity(e);
                }
            });
        }
    }

    /* renamed from: lambda$work$0$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m148lambda$work$0$iogithubxyz1001pdsyncandroidMainActivity(Done done, Object obj) {
        if (isDestroyed()) {
            return;
        }
        this.busy = false;
        try {
            done.accept(obj);
        } catch (Exception e) {
            reportError(e);
        }
        render();
        drainReadinessRefresh();
    }

    /* renamed from: lambda$work$1$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m149lambda$work$1$iogithubxyz1001pdsyncandroidMainActivity(Exception exc) {
        if (isDestroyed()) {
            return;
        }
        this.busy = false;
        reportError(exc);
        render();
        drainReadinessRefresh();
    }

    private void disable(View view) {
        int i = 0;
        view.setEnabled(false);
        if (!(view instanceof ViewGroup)) {
            return;
        }
        while (true) {
            ViewGroup viewGroup = (ViewGroup) view;
            if (i >= viewGroup.getChildCount()) {
                return;
            }
            disable(viewGroup.getChildAt(i));
            i++;
        }
    }

    private void reportError(Exception exc) {
        if (this.officialAttempt != null && this.officialAttempt.isDone()) this.officialAttempt = null;
        captureDiagnostic(exc);
        this.feedbackError = true;
        String strCode = BridgeDiagnostics.code(exc);
        if (strCode != null) {
            this.bridgeAccessCode = strCode;
            this.readinessState = this.readinessState.withAccessCode(strCode);
            this.localPreview = null;
            this.previewAttempted = false;
            this.cloudVerified = false;
            this.notice = NoviceCopy.feedback(safeText(BridgeDiagnostics.find(exc).getMessage()), "无法读取存档，请重试。");
            return;
        }
        this.notice = safeError(exc);
    }

    private void captureDiagnostic(Exception exc) {
        Exception exc2;
        long jVersionCode;
        PreparationDiagnostics.Failure failureFind = PreparationDiagnostics.find(exc);
        InstallDiagnostics.Failure installFailure = InstallDiagnostics.find(exc);
        SyncInspectionDiagnostics.Failure syncFailure = SyncInspectionDiagnostics.find(exc);
        if (installFailure != null) {
            this.diagnosticReport = InstallDiagnostics.report(installFailure);
            exc2 = exc;
        } else if (failureFind != null) {
            this.diagnosticReport = PreparationDiagnostics.report(failureFind);
            exc2 = exc;
        } else if (BridgeDiagnostics.find(exc) != null) {
            String str = "unknown";
            try {
                PackageInfo packageInfoInstalled = PackageTrust.installed(this, getPackageName());
                str = packageInfoInstalled.versionName;
                jVersionCode = PackageTrust.versionCode(packageInfoInstalled);
            } catch (Exception unused) {
                jVersionCode = -1;
            }
            String str2 = str;
            long j = jVersionCode;
            int i = Build.VERSION.SDK_INT;
            GamePreparationService.PreparedPair preparedPair = this.prepared;
            String str3 = selectedGamePackage();
            GamePreparationService.PreparedPair preparedPair2 = this.prepared;
            String str4 = preparedPair2 == null ? null : preparedPair2.bridgeHostPackage;
            GamePreparationService.PreparedPair preparedPair3 = this.prepared;
            exc2 = exc;
            this.diagnosticReport = BridgeDiagnostics.report(exc2, i, str2, j, str3, str4, preparedPair3 != null ? preparedPair3.authority : null);
            if (preparedPair3 != null && preparedPair3.embeddedBridge())
                this.diagnosticReport += "\nbridge_mode=embedded-v1\nprovider_auth=current_main_certificate\nsignature_permission_required=false";
            if (BridgeDiagnostics.find(exc).saveLayout != null)
                this.diagnosticReport += "\nprivacy=Relative entry names and file types only; no file contents or absolute paths. Review names before sharing.";
        } else if (syncFailure != null) {
            String appVersion = "unknown";
            long appVersionCode = -1;
            try {
                PackageInfo app = PackageTrust.installed(this, getPackageName());
                appVersion = app.versionName;
                appVersionCode = PackageTrust.versionCode(app);
            } catch (Exception unused) {
                // Reporting must preserve the original comparison failure if metadata is unavailable.
            }
            this.diagnosticReport = SyncInspectionDiagnostics.report(syncFailure, Build.VERSION.SDK_INT,
                    appVersion, appVersionCode, getPackageName(), selectedGamePackage());
            exc2 = exc;
        } else {
            exc2 = exc;
            this.diagnosticReport = "";
        }
        this.details = this.diagnosticReport.isEmpty() ? safeText(exc2.getMessage()) : this.diagnosticReport;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void copyDiagnostic() {
        if (this.diagnosticReport.isEmpty()) {
            return;
        }
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService("clipboard");
        if (clipboardManager == null) {
            Toast.makeText(this, "系统剪贴板不可用。", 0).show();
            return;
        }
        ClipData clipDataNewPlainText = ClipData.newPlainText("PD Sync diagnostic", this.diagnosticReport);
        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clipDataNewPlainText.getDescription().setExtras(persistableBundle);
        try {
            clipboardManager.setPrimaryClip(clipDataNewPlainText);
            Toast.makeText(this, "已复制脱敏诊断信息。", 0).show();
        } catch (RuntimeException unused) {
            Toast.makeText(this, "无法写入系统剪贴板，请展开查看诊断。", 0).show();
        }
    }

    private boolean readyForSaves() {
        if (this.rootGame != null && !SyncRuntime.rootAuthorized(this)) return false;
        return this.readinessState.withSystemRequest(this.installer.hasActiveRequest()).canContinue();
    }

    /** Eligibility for an explicit action, not evidence that Root access has succeeded. */
    private boolean canRequestSaveAccess() {
        return readyForSaves() || (this.rootGame != null && this.readinessState.canRequestRootAccess()
                && !this.installer.hasActiveRequest() && this.accessCapabilities != null && this.accessCapabilities.exportSupported);
    }

    private boolean restoreNeedsAttention() {
        return this.readinessState.restorePending || this.readinessState.access == InstallationReadiness.AccessStatus.RECOVERY_NEEDED
                || (this.inspection != null && this.inspection.getNeedsRestoreRecovery());
    }

    private boolean automaticObservationSupported() {
        return this.rootGame == null || (this.accessCapabilities != null
                && this.accessCapabilities.lifecycleObservation && this.accessCapabilities.exportSupported);
    }

    private boolean restoreSupported() {
        return this.rootGame == null || (this.accessCapabilities != null && this.accessCapabilities.restoreSupported);
    }

    private boolean backupRecoverySupported() {
        return this.rootGame == null || (this.accessCapabilities != null && this.accessCapabilities.interruptedBackupRecovery);
    }

    private boolean allowRestoreAction() {
        if (restoreSupported()) return true;
        this.notice = "当前存档后端不支持安全恢复或合并写入，现有游戏存档保持不变。";
        render();
        return false;
    }

    private void requireRestoreSupport() throws Exception {
        if (!SyncRuntime.backend(this).capabilities().restoreSupported)
            throw new IOException("当前存档访问方式不支持恢复或合并写入。");
    }

    private String selectedGamePackage() {
        return this.rootGame != null ? this.rootGame.gamePackage : this.prepared == null ? null : this.prepared.gamePackage;
    }

    private String selectedGameLabel() {
        return this.rootGame != null ? this.rootGame.gameLabel + " " + this.rootGame.gameVersionName
                : this.prepared == null ? "尚未选择游戏" : this.prepared.gameLabel + " " + this.prepared.gameVersionName;
    }

    /** Metadata-only refresh. Root is requested solely by an explicit foreground action. */
    private void readRootReadiness() throws Exception {
        this.prepared = null;
        this.accessCapabilities = null;
        this.selectedNamespace = SyncRuntime.currentNamespace(this);
        boolean pending = hasAnyRestoreJournal();
        this.readinessState = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.NOT_CHECKED,
                false, this.installer.hasActiveRequest(), pending, InstallationReadiness.AccessStatus.NOT_CHECKED);
        GameAccessBackend backend = SyncRuntime.backend(this);
        this.accessCapabilities = backend.capabilities();
        backend.targetGame();
        this.readinessState = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                true, this.installer.hasActiveRequest(), pending, InstallationReadiness.AccessStatus.NOT_CHECKED);
    }

    private void checkRootAccess() throws Exception {
        if (!this.resumed) throw new IOException("请返回应用前台，再检查 Root 存档访问。");
        readInstallationReadiness();
        if (this.rootGame == null) throw new IOException("所选访问方式已变化，请重新检查。");
        if (this.installer.hasActiveRequest()) throw new IOException("请先处理当前系统安装请求。");
        if (hasAnyRestoreJournal()) {
            // Same-target authorization remains possible after a crash. Switching is still blocked.
            SyncRuntime.probeRootAuthorization(this);
            this.bridgeAccessCode = "ROOT_RECOVERY_REQUIRED";
            this.readinessState = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                    true, false, true, InstallationReadiness.AccessStatus.RECOVERY_NEEDED);
            return;
        }
        SyncRuntime.backend(this).inspectReady();
        this.bridgeAccessCode = "READY";
        this.readinessState = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                true, false, false, InstallationReadiness.AccessStatus.READY);
    }

    private void requestRootAuthorization() {
        if (this.rootGame == null || !this.resumed || this.busy) return;
        final SelectedRootGame chosen = this.rootGame;
        work("正在通过 Root 检查所选游戏存档…", () -> {
            if (!this.resumed || !chosen.matches(SyncRuntime.rootGame(this)))
                throw new IOException("请在应用前台重新确认所选游戏。");
            checkRootAccess();
            return null;
        }, ignored -> {
            if (!this.resumed) {
                this.readinessState = this.readinessState.withAccessCode("NOT_CHECKED");
                this.notice = "请返回前台后继续检查存档。";
            } else {
                this.readinessRefreshPending = false;
                this.notice = this.readinessState.accessDescription();
                if (this.readinessState.restorePending) this.page = SyncPage.SETTINGS;
            }
        });
    }

    private void launchSelectedRootGame() {
        if (this.rootGame == null) return;
        if (this.readinessState.access != InstallationReadiness.AccessStatus.FIRST_LAUNCH_NEEDED) {
            launchGame();
            return;
        }
        final SelectedRootGame chosen = this.rootGame;
        work("正在核对所选游戏启动入口…", () -> {
            if (this.installer.hasActiveRequest() || hasAnyRestoreJournal())
                throw new IOException("请先处理未完成恢复或系统安装请求。");
            if (!chosen.matches(SyncRuntime.rootGame(this))) throw new IOException("所选游戏已变化，请重新选择。");
            SyncRuntime.backend(this).targetGame();
            Intent launch = getPackageManager().getLaunchIntentForPackage(chosen.gamePackage);
            if (launch == null) throw new IOException("所选游戏没有可用的启动入口。");
            cancelControlledLaunch();
            AutoSyncService.cancelForConnectionChange(this);
            return launch;
        }, launch -> {
            this.readinessState = this.readinessState.withAccessCode("NOT_CHECKED");
            this.localPreview = null;
            this.inspection = null;
            this.history = null;
            this.cloudVerified = false;
            this.notice = "游戏已请求启动。正常退出后可检查 Root 存档访问。";
            startActivity(launch);
        });
    }

    private void chooseRootInstalled() {
        String selectedPackage = selectedGamePackage();
        work("正在只读列出已安装应用…", () -> {
            if (this.installer.hasActiveRequest() || hasAnyRestoreJournal())
                throw new IOException("请先处理未完成恢复或系统安装请求，再切换访问方式。");
            return InstalledAppCatalog.load(this, selectedPackage);
        }, games -> InstalledAppPickerDialog.show(this, "Root 模式：选择已安装游戏", games,
                selectedPackage, installedPickerState, this::selectRootGame));
    }

    private void selectRootGame(String packageName) {
        work("正在核对已安装游戏身份，不更改游戏…", () -> {
            if (restoreNeedsAttention()) throw new IOException("请先核对当前游戏的未完成恢复，不能更换目标。");
            SelectedRootGame chosen = new RootTargetResolver(this).selectInstalled(packageName);
            SyncRuntime.setRootGame(this, chosen);
            cancelControlledLaunch();
            resetAccessSelection();
            readInstallationReadiness();
            return null;
        }, ignored -> this.notice = "已选择 Root 模式。尚未验证存档访问；点击检查或同步时会由 Root 管理器请求权限。");
    }

    private void switchToEmbedded() {
        work("正在切换为内置存档桥…", () -> {
            if (restoreNeedsAttention()) throw new IOException("请先核对当前游戏的未完成恢复，不能切换访问方式。");
            SyncRuntime.useEmbeddedBackend(this);
            cancelControlledLaunch();
            resetAccessSelection();
            readInstallationReadiness();
            return null;
        }, ignored -> this.notice = this.readinessState.statusDescription());
    }

    private void resetAccessSelection() {
        this.rootGame = null;
        this.accessCapabilities = null;
        this.prepared = null;
        this.facade = null;
        this.history = null;
        this.inspection = null;
        this.localPreview = null;
        this.previewAttempted = false;
        this.cloudVerified = false;
        this.selected = null;
        this.confirmedAction = null;
        this.confirmationMerge = null;
        this.progress = OnboardingProgress.PENDING;
        this.onboardingActive = true;
        this.page = SyncPage.DISCOVERY;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestReadinessRefresh() {
        if (this.resumed) {
            if (this.busy) {
                this.readinessRefreshPending = true;
            } else {
                refreshReadiness();
            }
        }
    }

    private void drainReadinessRefresh() {
        if (!this.readinessRefreshPending || !this.resumed || this.busy || isDestroyed()) {
            return;
        }
        refreshReadiness();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refreshReadiness() {
        if (this.busy || isDestroyed()) {
            return;
        }
        this.readinessRefreshPending = false;
        final boolean z = this.initialLoad;
        this.readinessState = InstallationReadiness.checking(this.prepared != null, this.installer.hasActiveRequest());
        this.localPreview = null;
        this.previewAttempted = false;
        this.cloudVerified = false;
        this.inspection = null;
        this.history = null;
        work("正在检查游戏与存档…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda0
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m68x764cc8a4(z);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda1
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m69xebc6eee5(z, obj);
            }
        });
    }

    /* renamed from: lambda$refreshReadiness$3$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m68x764cc8a4(boolean z) throws Exception {
        if (z) {
            try {
                this.connection = SyncRuntime.connection(this);
                this.draftLoaded = false;
                this.testedDraft = null;
            } catch (Exception e) {
                this.connectionLoadError = safeError(e);
            }
            this.initialLoad = false;
        }
        readInstallationReadiness();
        SecureConnectionStore.Connection connection = this.connection;
        if (connection != null) {
            this.progress = SyncRuntime.onboardingProgress(this, connection);
        }
        if (z && this.rootGame == null && readyForSaves() && this.connection != null) {
            try {
                this.facade = null;
                readComparison();
                OnboardingProgress onboardingProgress = this.progress;
                SyncHistory syncHistory = this.history;
                OnboardingProgress onboardingProgressResumedProgress = OnboardingGate.resumedProgress(onboardingProgress, true, (syncHistory == null || syncHistory.getBaselineCommit() == null) ? false : true);
                this.progress = onboardingProgressResumedProgress;
                SyncRuntime.setOnboardingProgress(this, this.connection, onboardingProgressResumedProgress);
            } catch (Exception e2) {
                this.cloudVerified = false;
                reportError(e2);
            }
        }
        return null;
    }

    /* renamed from: lambda$refreshReadiness$4$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m69xebc6eee5(boolean z, Object obj) throws Exception {
        boolean z2 = false;
        if (!z) {
            this.notice = this.readinessState.withSystemRequest(this.installer.hasActiveRequest()).statusDescription();
            if (this.rootGame == null && this.page == SyncPage.HOME && readyForSaves() && this.connection != null) {
                refreshComparison(SyncPage.HOME, false);
                return;
            }
            return;
        }
        if (this.progress != OnboardingProgress.COMPLETE && this.progress != OnboardingProgress.DEFERRED) {
            z2 = true;
        }
        this.onboardingActive = z2;
        if (!canRequestSaveAccess()) {
            this.onboardingActive = true;
            this.page = this.rootGame != null && this.readinessState.restorePending ? SyncPage.SETTINGS : SyncPage.DISCOVERY;
        } else if (this.connection == null) {
            this.onboardingActive = true;
            this.page = SyncPage.PATH;
        } else {
            this.page = this.onboardingActive ? SyncPage.COMPARISON : SyncPage.HOME;
        }
        if (!this.feedbackError) this.notice = !readyForSaves() ? this.readinessState.statusDescription() : "";
        if (this.retainedConnectionUi != null && canRequestSaveAccess()) {
            RetainedConnectionUi retained = this.retainedConnectionUi;
            this.retainedConnectionUi = null;
            restoreConnectionUi(retained);
            if (this.officialAttempt != null) resumeOfficialAttempt();
            return;
        }
        if (this.connectionLoadError.isEmpty()) {
            return;
        }
        if (canRequestSaveAccess()) {
            this.page = SyncPage.CONNECTION;
        }
        this.notice = "连接设置读取失败，请重试。";
        this.feedbackError = true;
    }

    private void readInstallationReadiness() throws Exception {
        this.bridgeAccessCode = "NOT_CHECKED";
        this.readinessState = InstallationReadiness.checking(this.prepared != null, this.installer.hasActiveRequest());
        this.installer.reconcile();
        this.rootGame = SyncRuntime.rootGame(this);
        if (this.rootGame != null) {
            readRootReadiness();
            return;
        }
        this.accessCapabilities = null;
        this.prepared = this.preparation.reloadPrepared();
        if (this.prepared == null) {
            this.readinessState = InstallationReadiness.noSelection();
            return;
        }
        SyncRuntime.setPreparedGame(this, this.prepared);
        this.selectedNamespace = SyncRuntime.currentNamespace(this);
        if (!this.prepared.embeddedBridge()) {
            this.readinessState = new InstallationReadiness(InstallationReadiness.PackageStatus.NOT_CHECKED,
                    InstallationReadiness.PackageStatus.NOT_CHECKED, false, this.installer.hasActiveRequest(),
                    hasAnyRestoreJournal(), InstallationReadiness.AccessStatus.NOT_CHECKED);
            this.notice = "已保留旧版准备记录。先处理未完成恢复，再重新准备带内置存档桥的游戏。";
            return;
        }
        try {
            InstallationReadiness.PackageStatus gameStatus = packageStatus(
                    this.installer.installedMatches(this.prepared.gamePackage, this.prepared.gameApks), this.prepared.gameStrategy);
            boolean restorePending = hasAnyRestoreJournal();
            boolean targetVerified = false;
            String accessCode = "NOT_CHECKED";
            if (gameStatus == InstallationReadiness.PackageStatus.VERIFIED
                    && !this.installer.hasActiveRequest()) {
                try {
                    BridgeClient bridge = new BridgeClient(this, this.prepared, null);
                    bridge.targetGame();
                    targetVerified = true;
                    bridge.inspectReady();
                    accessCode = "READY";
                } catch (Exception failure) {
                    Exception diagnostic = BridgeDiagnostics.code(failure) == null
                            ? new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.PACKAGE_TRUST, "PACKAGE_TRUST_FAILED",
                                    "游戏与内置存档桥身份未通过核验，请查看诊断。", failure)
                            : failure;
                    accessCode = BridgeDiagnostics.code(diagnostic);
                    captureDiagnostic(diagnostic);
                }
            }
            this.readinessState = InstallationReadiness.embedded(gameStatus, targetVerified,
                    this.installer.hasActiveRequest(), restorePending, InstallationReadiness.classify(accessCode));
            this.bridgeAccessCode = accessCode;
        } catch (Exception failure) {
            throw new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.PACKAGE_TRUST, "PACKAGE_TRUST_FAILED",
                    "未能核对已安装应用的签名与完整内容，请查看诊断。", failure);
        }
    }

    private static InstallationReadiness.PackageStatus packageStatus(boolean z, GamePreparationService.InstallStrategy installStrategy) {
        if (z) {
            return InstallationReadiness.PackageStatus.VERIFIED;
        }
        return InstallationReadiness.PackageStatus.valueOf(installStrategy.name());
    }

    private void navigate(SyncPage syncPage) {
        if (this.busy) {
            return;
        }
        captureDraft();
        if (this.page == SyncPage.CONNECTION && syncPage != SyncPage.CONNECTION) this.testedDraft = null;
        if (this.page != syncPage) { this.notice = ""; this.feedbackError = false; this.details = ""; this.diagnosticReport = ""; }
        this.detailsExpanded = false;
        this.page = syncPage;
        if (syncPage == SyncPage.CONNECTION && !this.draftLoaded) {
            loadDraft();
        }
        render();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void render() {
        ScrollView scrollView;
        final int scrollY = (this.renderedPage != this.page || (scrollView = this.scroll) == null) ? 0 : scrollView.getScrollY();
        LinearLayout linearLayoutColumn = column();
        this.root = linearLayoutColumn;
        linearLayoutColumn.setBackgroundColor(-525834);
        final int iDp = dp(16);
        this.root.setPadding(iDp, dp(12), iDp, iDp);
        this.root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda48
            @Override // android.view.View.OnApplyWindowInsetsListener
            public final WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
                return MainActivity.this.m75lambda$render$5$iogithubxyz1001pdsyncandroidMainActivity(iDp, view, windowInsets);
            }
        });
        LinearLayout linearLayoutRow = row();
        this.root.addView(linearLayoutRow);
        linearLayoutRow.addView(label(this.page == SyncPage.HOME ? "像素地牢" : "像素地牢  /  存档同步", this.page == SyncPage.HOME ? 24 : 14, -14469589), new LinearLayout.LayoutParams(0, -2, 1.0f));
        if (this.page == SyncPage.HOME) {
            addButton(linearLayoutRow, "设置", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda49
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m76lambda$render$6$iogithubxyz1001pdsyncandroidMainActivity();
                }
            }, false);
        }
        if (this.page == SyncPage.HOME) {
            this.root.addView(label(this.cloudVerified ? "存档自动同步  /  ● 云端已连接" : "存档自动同步  /  ○ 等待连接", 12, -10129303));
        }
        String visibleNotice = visibleNotice();
        TextView textViewLabel = label(visibleNotice, 14, -13474224);
        textViewLabel.setVisibility(visibleNotice.isEmpty() ? View.GONE : View.VISIBLE);
        this.status = textViewLabel;
        textViewLabel.setPadding(0, dp(8), 0, dp(8));
        this.root.addView(this.status);
        if (this.busy) {
            this.root.addView(new ProgressBar(this, null, 16842872), new LinearLayout.LayoutParams(-1, dp(3)));
        }
        ScrollView scrollView2 = new ScrollView(this);
        this.scroll = scrollView2;
        scrollView2.setFillViewport(true);
        this.root.addView(this.scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        LinearLayout linearLayoutColumn2 = column();
        this.body = linearLayoutColumn2;
        this.scroll.addView(linearLayoutColumn2);
        LinearLayout linearLayoutColumn3 = column();
        this.footer = linearLayoutColumn3;
        this.root.addView(linearLayoutColumn3);
        this.passwordInput = null;
        this.userInput = null;
        this.endpointInput = null;
        switch (AnonymousClass3.$SwitchMap$pdsync$presentation$SyncPage[this.page.ordinal()]) {
            case 1:
                renderHome();
                break;
            case 2:
                renderSettings();
                break;
            case 3:
                renderDiscovery();
                break;
            case 4:
                renderPath();
                break;
            case 5:
                renderConnection();
                break;
            case 6:
                renderComparison();
                break;
            case 7:
                renderConflict();
                break;
            case 8:
                renderConfirmation();
                break;
        }
        if (!this.details.isEmpty() && !(this.page == SyncPage.COMPARISON && showEmptyCompletion() && !this.feedbackError)) {
            button(this.detailsExpanded ? "收起详细信息" : "详细信息", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda51
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m77lambda$render$7$iogithubxyz1001pdsyncandroidMainActivity();
                }
            });
            if (this.detailsExpanded) {
                text(this.details);
                if (!this.diagnosticReport.isEmpty()) button("复制诊断", this::copyDiagnostic);
            }
        }
        setContentView(this.root);
        this.renderedPage = this.page;
        if (scrollY > 0) {
            this.scroll.post(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda52
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m78lambda$render$8$iogithubxyz1001pdsyncandroidMainActivity(scrollY);
                }
            });
        }
        if (this.busy) {
            disable(this.root);
        }
    }

    /* renamed from: lambda$render$5$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ WindowInsets m75lambda$render$5$iogithubxyz1001pdsyncandroidMainActivity(int i, View view, WindowInsets windowInsets) {
        RuntimePlatformCompat.applyInsets(view, windowInsets, i, dp(12), i);
        return windowInsets;
    }

    /* renamed from: lambda$render$6$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m76lambda$render$6$iogithubxyz1001pdsyncandroidMainActivity() {
        navigate(SyncPage.SETTINGS);
    }

    /* renamed from: io.github.xyz1001.pdsync.android.MainActivity$3, reason: invalid class name */
    static /* synthetic */ class AnonymousClass3 {
        static final /* synthetic */ int[] $SwitchMap$pdsync$presentation$SyncPage;

        static {
            int[] iArr = new int[SyncPage.values().length];
            $SwitchMap$pdsync$presentation$SyncPage = iArr;
            try {
                iArr[SyncPage.HOME.ordinal()] = 1;
            } catch (NoSuchFieldError unused) {
            }
            try {
                $SwitchMap$pdsync$presentation$SyncPage[SyncPage.SETTINGS.ordinal()] = 2;
            } catch (NoSuchFieldError unused2) {
            }
            try {
                $SwitchMap$pdsync$presentation$SyncPage[SyncPage.DISCOVERY.ordinal()] = 3;
            } catch (NoSuchFieldError unused3) {
            }
            try {
                $SwitchMap$pdsync$presentation$SyncPage[SyncPage.PATH.ordinal()] = 4;
            } catch (NoSuchFieldError unused4) {
            }
            try {
                $SwitchMap$pdsync$presentation$SyncPage[SyncPage.CONNECTION.ordinal()] = 5;
            } catch (NoSuchFieldError unused5) {
            }
            try {
                $SwitchMap$pdsync$presentation$SyncPage[SyncPage.COMPARISON.ordinal()] = 6;
            } catch (NoSuchFieldError unused6) {
            }
            try {
                $SwitchMap$pdsync$presentation$SyncPage[SyncPage.CONFLICT.ordinal()] = 7;
            } catch (NoSuchFieldError unused7) {
            }
            try {
                $SwitchMap$pdsync$presentation$SyncPage[SyncPage.CONFIRM.ordinal()] = 8;
            } catch (NoSuchFieldError unused8) {
            }
        }
    }

    /* renamed from: lambda$render$7$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m77lambda$render$7$iogithubxyz1001pdsyncandroidMainActivity() {
        this.detailsExpanded = !this.detailsExpanded;
        render();
    }

    /* renamed from: lambda$render$8$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m78lambda$render$8$iogithubxyz1001pdsyncandroidMainActivity(int i) {
        this.scroll.scrollTo(0, i);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int dp(int i) {
        return Math.round(getResources().getDisplayMetrics().density * i);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public LinearLayout column() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        return linearLayout;
    }

    private LinearLayout row() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setGravity(16);
        return linearLayout;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public TextView label(String str, int i, int i2) {
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextSize(i);
        textView.setTextColor(i2);
        return textView;
    }

    private void text(String str) {
        TextView textViewLabel = label(str, 14, -10129303);
        textViewLabel.setPadding(0, dp(5), 0, dp(10));
        this.body.addView(textViewLabel);
    }

    private void heading(String str) {
        TextView textViewLabel = label(str, 20, -14469589);
        textViewLabel.setTypeface(null, 1);
        textViewLabel.setPadding(0, dp(12), 0, dp(10));
        this.body.addView(textViewLabel);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Button addButton(LinearLayout linearLayout, String str, final Runnable runnable, boolean z) {
        Button button = new Button(this);
        button.setText(str);
        button.setAllCaps(false);
        button.setTextSize(14.0f);
        button.setMinHeight(dp(48));
        button.setTextColor(-13474224);
        linearLayout.addView(button, z ? new LinearLayout.LayoutParams(0, -2, 1.0f) : new LinearLayout.LayoutParams(-2, -2));
        button.setOnClickListener(new View.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda94
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                MainActivity.this.m14lambda$addButton$9$iogithubxyz1001pdsyncandroidMainActivity(runnable, view);
            }
        });
        return button;
    }

    /* renamed from: lambda$addButton$9$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m14lambda$addButton$9$iogithubxyz1001pdsyncandroidMainActivity(Runnable runnable, View view) {
        if (this.busy) {
            return;
        }
        runnable.run();
    }

    private Button button(String str, Runnable runnable) {
        Button buttonAddButton = addButton(this.body, str, runnable, false);
        buttonAddButton.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return buttonAddButton;
    }

    private Button foot(String str, Runnable runnable) {
        Button buttonAddButton = addButton(this.footer, str, runnable, false);
        buttonAddButton.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return buttonAddButton;
    }

    private void steps(Runnable runnable, Runnable runnable2, boolean z) {
        LinearLayout linearLayoutRow = row();
        this.footer.addView(linearLayoutRow);
        addButton(linearLayoutRow, "上一步", runnable, true);
        addButton(linearLayoutRow, "下一步", runnable2, true).setEnabled(z);
    }

    private void renderHome() {
        String str2;
        String str3;
        AndroidSyncFacade.Inspection inspection;
        if (!automaticObservationSupported() || SyncRuntime.isAutoUploadPaused(this) || this.progress != OnboardingProgress.COMPLETE) {
            text("自动上传已暂停");
        }
        String automaticStatus = automaticStatusBrief();
        if (!automaticStatus.isEmpty()) text(automaticStatus);
        text(selectedGameLabel());
        if (!readyForSaves()) {
            button("检查存档", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    if (MainActivity.this.rootGame != null) MainActivity.this.requestRootAuthorization();
                    else MainActivity.this.m106x5451cefa();
                }
            });
        }
        LinearLayout linearLayoutRow = row();
        this.body.addView(linearLayoutRow);
        addButton(linearLayoutRow, "启动游戏", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda4
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.launchGame();
            }
        }, true).setEnabled(canRequestSaveAccess() && !this.readinessState.restorePending && ((inspection = this.inspection) == null || !inspection.getNeedsRestoreRecovery()));
        addButton(linearLayoutRow, "上传", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda5
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.requestUpload();
            }
        }, true).setEnabled(canRequestSaveAccess());
        addButton(linearLayoutRow, "恢复", this::requestRestore, true).setEnabled(restoreSupported() && canRequestSaveAccess() && this.connection != null);
        if (!restoreSupported()) text("当前访问方式不支持恢复与合并写入。");
        AndroidSyncFacade.Inspection inspection2 = this.inspection;
        if (inspection2 != null && inspection2.isRetryablePending()) {
            text("上次上传尚未完成，请先重试上传。");
        }
        AndroidSyncFacade.Inspection inspection3 = this.inspection;
        if (inspection3 != null && inspection3.getNeedsRestoreRecovery()) {
            text("有未完成恢复，上传已锁定。请在设置中检查恢复结果。");
        }
        heading("完整档案历史");
        if (this.history == null) {
            str2 = "尚未读取存档历史";
        } else {
            str2 = this.history.getCloud().size() + " 份云端记录";
        }
        text(str2);
        if (this.selected == null) {
            SyncHistory syncHistory = this.history;
            str3 = (syncHistory == null || syncHistory.getGraph().getTips().size() <= 1) ? "默认恢复最新云端存档" : "请选择要恢复的云端分支";
        } else {
            str3 = "恢复目标：" + selectedName();
        }
        text(str3);
        renderHistory();
        button("重新检查存档", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m107xc9cbf53b();
            }
        });
    }

    /* renamed from: lambda$renderHome$10$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m106x5451cefa() {
        navigate(SyncPage.DISCOVERY);
        refreshReadiness();
    }

    /* renamed from: lambda$renderHome$11$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m107xc9cbf53b() {
        refreshComparison(SyncPage.HOME);
    }

    private void renderHistory() {
        SyncHistory syncHistory = this.history;
        if (syncHistory == null) {
            return;
        }
        final HistoryPresentation historyPresentationFrom = HistoryPresentation.from(syncHistory, this.historyPage);
        LinearLayout linearLayoutRow = row();
        this.body.addView(linearLayoutRow);
        linearLayoutRow.addView(label("云端存档", 15, -14469589), new LinearLayout.LayoutParams(0, -2, 1.0f));
        linearLayoutRow.addView(label("本机", 15, -14469589), new LinearLayout.LayoutParams(0, -2, 1.0f));
        this.body.addView(new TimelineView(historyPresentationFrom), new LinearLayout.LayoutParams(-1, -2));
        if (historyPresentationFrom.getPages() > 1) {
            LinearLayout linearLayoutRow2 = row();
            this.body.addView(linearLayoutRow2);
            addButton(linearLayoutRow2, "较新", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda119
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m104x441503ed(historyPresentationFrom);
                }
            }, true).setEnabled(historyPresentationFrom.getPage() > 0);
            linearLayoutRow2.addView(label((historyPresentationFrom.getPage() + 1) + " / " + historyPresentationFrom.getPages(), 14, -10129303));
            addButton(linearLayoutRow2, "更早", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda120
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m105xb98f2a2e(historyPresentationFrom);
                }
            }, true).setEnabled(historyPresentationFrom.getPage() + 1 < historyPresentationFrom.getPages());
        }
    }

    /* renamed from: lambda$renderHistory$12$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m104x441503ed(HistoryPresentation historyPresentation) {
        this.historyPage = historyPresentation.getPage() - 1;
        render();
    }

    /* renamed from: lambda$renderHistory$13$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m105xb98f2a2e(HistoryPresentation historyPresentation) {
        this.historyPage = historyPresentation.getPage() + 1;
        render();
    }

    /* JADX INFO: Access modifiers changed from: private */
    final class TimelineView extends ViewGroup {
        private int[] heights;
        private final HistoryPresentation model;
        private final Map<String, HistoryNode> nodes;
        private final Paint paint;
        private int[] tops;
        private final Map<String, View> views;

        TimelineView(HistoryPresentation historyPresentation) {
            super(MainActivity.this);
            this.views = new LinkedHashMap();
            this.nodes = new LinkedHashMap();
            this.paint = new Paint(1);
            this.model = historyPresentation;
            setWillNotDraw(false);
            for (final HistoryNode historyNode : historyPresentation.getNodes()) {
                LinearLayout linearLayoutColumn = MainActivity.this.column();
                linearLayoutColumn.setPadding(MainActivity.this.dp(9), MainActivity.this.dp(10), MainActivity.this.dp(9), MainActivity.this.dp(7));
                boolean z = historyNode.getSelectable() && historyNode.getSnapshotId().equals(MainActivity.this.selected);
                GradientDrawable gradientDrawable = new GradientDrawable();
                gradientDrawable.setColor(historyNode.getDirty() ? -5976 : z ? -2626595 : -1182996);
                gradientDrawable.setCornerRadius(MainActivity.this.dp(7));
                if (z) {
                    gradientDrawable.setStroke(MainActivity.this.dp(2), -13474224);
                }
                linearLayoutColumn.setBackground(gradientDrawable);
                TextView textViewLabel = MainActivity.this.label(historyNode.getName(), 14, -14469589);
                textViewLabel.setTypeface(null, 1);
                linearLayoutColumn.addView(textViewLabel);
                linearLayoutColumn.addView(MainActivity.this.label(historyNode.getTime(), 12, -10129303));
                linearLayoutColumn.addView(MainActivity.this.label(historyNode.getDetail(), 12, -10129303));
                linearLayoutColumn.addView(MainActivity.this.label(historyNode.getStatus(), 12, -10129303));
                if (historyNode.getSelectable()) {
                    linearLayoutColumn.setContentDescription("选择恢复目标，" + historyNode.getName() + "，" + historyNode.getTime() + "，" + historyNode.getStatus());
                    linearLayoutColumn.setFocusable(true);
                    linearLayoutColumn.setClickable(true);
                    linearLayoutColumn.setSelected(z);
                    linearLayoutColumn.setOnClickListener(new View.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$TimelineView$$ExternalSyntheticLambda0
                        @Override // android.view.View.OnClickListener
                        public final void onClick(View view) {
                            TimelineView.this.m151x357355b2(historyNode, view);
                        }
                    });
                }
                MainActivity.this.addButton(linearLayoutColumn, "命名", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$TimelineView$$ExternalSyntheticLambda1
                    @Override // java.lang.Runnable
                    public final void run() {
                        TimelineView.this.m152x3b772111(historyNode);
                    }
                }, false).setTextSize(12.0f);
                this.views.put(historyNode.getId(), linearLayoutColumn);
                this.nodes.put(historyNode.getId(), historyNode);
                addView(linearLayoutColumn);
            }
            if (historyPresentation.getLocalEmpty()) {
                View viewLabel = MainActivity.this.label("无存档", 14, -10129303);
                this.views.put("empty", viewLabel);
                addView(viewLabel);
            }
        }

        /* renamed from: lambda$new$0$io-github-xyz1001-pdsync-android-MainActivity$TimelineView, reason: not valid java name */
        /* synthetic */ void m151x357355b2(HistoryNode historyNode, View view) {
            if (MainActivity.this.busy) {
                return;
            }
            MainActivity.this.selected = historyNode.getSnapshotId();
            MainActivity.this.render();
        }

        /* renamed from: lambda$new$1$io-github-xyz1001-pdsync-android-MainActivity$TimelineView, reason: not valid java name */
        /* synthetic */ void m152x3b772111(HistoryNode historyNode) {
            MainActivity.this.rename(historyNode.getSnapshotId());
        }

        @Override // android.view.View
        protected void onMeasure(int i, int i2) {
            int row;
            int size = View.MeasureSpec.getSize(i);
            int iMax = Math.max(1, Math.round(size * 0.43f));
            this.heights = new int[this.model.getRows()];
            this.tops = new int[this.model.getRows()];
            Arrays.fill(this.heights, MainActivity.this.dp(160));
            Iterator<Map.Entry<String, View>> it = this.views.entrySet().iterator();
            while (true) {
                row = 0;
                if (!it.hasNext()) {
                    break;
                }
                Map.Entry<String, View> next = it.next();
                next.getValue().measure(View.MeasureSpec.makeMeasureSpec(iMax, 1073741824), View.MeasureSpec.makeMeasureSpec(0, 0));
                HistoryNode historyNode = this.nodes.get(next.getKey());
                if (historyNode != null) {
                    row = historyNode.getRow();
                }
                int[] iArr = this.heights;
                iArr[row] = Math.max(iArr[row], next.getValue().getMeasuredHeight() + MainActivity.this.dp(14));
            }
            int i3 = 0;
            while (true) {
                int[] iArr2 = this.heights;
                if (row >= iArr2.length) {
                    setMeasuredDimension(size, i3);
                    return;
                } else {
                    this.tops[row] = i3;
                    i3 += iArr2[row];
                    row++;
                }
            }
        }

        @Override // android.view.ViewGroup, android.view.View
        protected void onLayout(boolean z, int i, int i2, int i3, int i4) {
            for (Map.Entry<String, View> entry : this.views.entrySet()) {
                HistoryNode historyNode = this.nodes.get(entry.getKey());
                int width = 0;
                int row = historyNode == null ? 0 : historyNode.getRow();
                int column = historyNode == null ? 1 : historyNode.getColumn();
                View value = entry.getValue();
                if (column != 0) {
                    width = getWidth() - value.getMeasuredWidth();
                }
                int iDp = this.tops[row] + MainActivity.this.dp(6);
                value.layout(width, iDp, value.getMeasuredWidth() + width, value.getMeasuredHeight() + iDp);
            }
        }

        @Override // android.view.View
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (this.tops == null) {
                return;
            }
            // Use laid-out cards, never column-edge percentages or row-height estimates.
            Map<String, HistoryArrowGeometry.Box> bounds = new LinkedHashMap<>();
            for (Map.Entry<String, View> entry : this.views.entrySet()) {
                View card = entry.getValue();
                bounds.put(entry.getKey(), new HistoryArrowGeometry.Box(
                        card.getLeft(), card.getTop(), card.getRight(), card.getBottom()));
            }
            List<HistoryArrowGeometry.Box> cards = new ArrayList<>(bounds.values());
            this.paint.setStrokeWidth(MainActivity.this.dp(2));
            this.paint.setStyle(Paint.Style.STROKE);
            for (HistoryEdge historyEdge : this.model.getEdges()) {
                HistoryArrowGeometry.Box from = bounds.get(historyEdge.getFromId());
                HistoryArrowGeometry.Box to = bounds.get(historyEdge.getToId());
                if (from == null || to == null) continue;
                HistoryArrowGeometry.Arrow arrow = HistoryArrowGeometry.between(from, to, cards,
                        MainActivity.this.dp(7), MainActivity.this.dp(4), MainActivity.this.dp(1));
                if (arrow == null) continue;
                this.paint.setColor(historyEdge.getDashed() ? -4879047 : -9004921);
                this.paint.setPathEffect(historyEdge.getDashed() ? new DashPathEffect(new float[]{MainActivity.this.dp(6), MainActivity.this.dp(4)}, 0.0f) : null);
                canvas.drawLine(arrow.startX, arrow.startY, arrow.endX, arrow.endY, this.paint);
                this.paint.setPathEffect(null);
                if (arrow.hasHead) {
                    canvas.drawLine(arrow.leftX, arrow.leftY, arrow.tipX, arrow.tipY, this.paint);
                    canvas.drawLine(arrow.rightX, arrow.rightY, arrow.tipX, arrow.tipY, this.paint);
                }
            }
        }
    }

    private void renderRootDiscovery() {
        heading("1 / 4  选择游戏 · Root");
        text(selectedGameLabel() + "\n" + this.rootGame.gamePackage);
        text(this.readinessState.installationDescription());
        text(this.readinessState.accessDescription());
        text("直接访问现有安装，不准备、重签名、安装或卸载游戏。选择 Root 模式后，检查、同步、恢复及已开启的退出监听可向 Root 管理器请求所选游戏的访问权限；是否保留授权由 Root 管理器决定。选择本身不代表访问成功。");
        if (!restoreSupported()) text("当前后端不支持恢复与合并写入；读取和上传仍需实际访问检查。");
        if (!backupRecoverySupported()) text("当前后端不支持中断事务恢复。");
        text(automaticObservationSupported()
                ? "完成存档比较并开启自动上传后，从本应用启动游戏可进行退出监听；只有可靠的空闲状态与稳定存档才会尝试无冲突上传。"
                : "当前后端不支持退出监听，请手动检查和上传。");
        button("检查 Root 存档访问", this::requestRootAuthorization)
                .setEnabled(!this.installer.hasActiveRequest());
        button("启动所选游戏", this::launchSelectedRootGame)
                .setEnabled(this.readinessState.identityVerified && !this.installer.hasActiveRequest() && !restoreNeedsAttention());
        button("更换 Root 游戏…", this::chooseRootInstalled)
                .setEnabled(!this.installer.hasActiveRequest() && !restoreNeedsAttention());
        button("切换为内置存档桥", this::switchToEmbedded)
                .setEnabled(!this.installer.hasActiveRequest() && !restoreNeedsAttention());
        if (restoreNeedsAttention()) {
            text("有未完成恢复，不能切换游戏或访问方式。请先核对恢复记录。");
            button("查看恢复记录", () -> navigate(SyncPage.SETTINGS));
        }
        foot("下一步", this::m103x3c55a874).setEnabled(canRequestSaveAccess());
    }

    private void renderDiscovery() {
        if (this.rootGame != null) { renderRootDiscovery(); return; }
        InstallationReadiness installationReadinessWithSystemRequest = this.readinessState.withSystemRequest(this.installer.hasActiveRequest());
        heading("1 / 4  选择游戏");
        button("可选：使用 Root 访问已安装游戏", this::chooseRootInstalled)
                .setEnabled(!this.installer.hasActiveRequest() && !this.readinessState.restorePending);
        text(installationReadinessWithSystemRequest.installationDescription());
        if (this.prepared != null) {
            text(installationReadinessWithSystemRequest.accessDescription());
        }
        if (this.prepared != null && this.prepared.embeddedBridge()
                && HelperActivationPolicy.canOffer(installationReadinessWithSystemRequest, this.bridgeAccessCode, false)) {
            text("内置存档桥暂未连接。可打开游戏中的存档桥页面，再返回这里重新核验。");
            button("启用游戏内置存档桥", this::activateHelper);
        }
        if (installationReadinessWithSystemRequest.canInitializeGame()) {
            button("启动游戏一次", this::launchGameForInitialization);
        }
        if (installationReadinessWithSystemRequest.restorePending || installationReadinessWithSystemRequest.access == InstallationReadiness.AccessStatus.RECOVERY_NEEDED) {
            button("检查恢复记录", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda65
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m91x1796ba13();
                }
            });
        }
        text("选择已安装游戏，或选择完整的一组 APK。应用会核对包身份、版本、分包与签名，追加存档桥 DEX 与必要清单并在本机签名。原游戏 DEX 和资源逐项保留；只安装这一组游戏 APK。");
        button("从已安装应用中选择", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda66
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.chooseInstalled();
            }
        });
        if (this.prepared != null && !this.prepared.embeddedBridge()) {
            text("检测到旧版共享 UID 准备记录。它仅保留供恢复核对。请先处理未完成恢复，再从原始 APK 重新准备游戏；不再安装或升级独立助手。");
        }
        button(this.prepared == null ? "选择游戏…" : "更换游戏…", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda68
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.pickApks();
            }
        });
        GamePreparationService.PreparedPair preparedPair4 = this.prepared;
        if (preparedPair4 != null) {
            text(pairSummary(this.prepared));
            if (this.installer.hasActiveRequest() || installationReadinessWithSystemRequest.canContinueInstallation()) {
                text("上次系统安装／卸载记录（单次请求）：\n" + this.installer.summary());
            }
            if (installationReadinessWithSystemRequest.canContinueInstallation()) {
                button("检查并继续安装", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda69
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainActivity.this.continueInstallation();
                    }
                });
            }
        }
        boolean obsoleteLegacyRequest = this.installer.hasObsoleteLegacyRequest(this.prepared);
        if (obsoleteLegacyRequest) {
            text("旧版安装／卸载请求不会继续。请先在 Android 原系统界面中点击取消并关闭；返回后可核对并清理旧请求。");
            button("核对并清理旧版请求", this::confirmObsoleteLegacyCleanup);
        }
        if (this.installer.hasConfirmation() && !obsoleteLegacyRequest) {
            button("继续系统安装／卸载确认", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda70
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.openInstallConfirmation();
                }
            });
        }
        if (this.installer.hasActiveRequest() && !obsoleteLegacyRequest) {
            button("取消待处理安装", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda71
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m96x62f97958();
                }
            });
            button("检查失效系统请求", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda59
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m100xdbe735b1();
                }
            });
        }
        if (installationReadinessWithSystemRequest.canContinueInstallation() && !getPackageManager().canRequestPackageInstalls()) {
            button("允许本应用请求安装 · 打开系统设置", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda60
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m102xc6db8233();
                }
            });
        }
        if (installationReadinessWithSystemRequest.requiresGameUninstall()) {
            text("本次替换必须先卸载原游戏。卸载会不可逆地永久删除全部本地存档和战绩。\n\nAPK 备份不是存档备份，不能恢复已删除的存档。卸载前取消会保留原版；卸载后中断只能保留已准备好的安装包，不能找回存档。\n\n卸载和安装都仍需在 Android 系统界面单独确认。");
        }
        text("本机签名私钥仅保留在主程序的 Android Keystore 中，不上传、不交给游戏。清除数据或卸载主程序会失去密钥，之后可能必须再次卸载游戏。WebDAV 不备份密钥。");
        LinearLayout linearLayoutRow = row();
        this.footer.addView(linearLayoutRow);
        addButton(linearLayoutRow, "重新检查状态", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda61
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.refreshReadiness();
            }
        }, true);
        addButton(linearLayoutRow, "下一步", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda62
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m103x3c55a874();
            }
        }, true).setEnabled(installationReadinessWithSystemRequest.canContinue());
    }

    /* renamed from: lambda$renderDiscovery$14$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m91x1796ba13() {
        navigate(SyncPage.SETTINGS);
    }

    /* renamed from: lambda$renderDiscovery$15$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m92x8d10e054() {
        requestPrepare(this.prepared.gamePackage, null);
    }

    /* renamed from: lambda$renderDiscovery$19$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m96x62f97958() {
        confirm("取消待处理安装？", "会保留已准备的 APK。已经完成的卸载或安装不会撤销；被卸载的本地存档无法恢复。", "取消安装会话", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda157
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m95xed7f5317();
            }
        });
    }

    /* renamed from: lambda$renderDiscovery$16$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m93x28b0695() throws Exception {
        this.installer.cancelSession();
        return null;
    }

    /* renamed from: lambda$renderDiscovery$17$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m94x78052cd6(Object obj) throws Exception {
        refreshReadiness();
    }

    /* renamed from: lambda$renderDiscovery$18$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m95xed7f5317() {
        work("正在取消待处理安装…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda114
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m93x28b0695();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda115
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m94x78052cd6(obj);
            }
        });
    }

    private void confirmObsoleteLegacyCleanup() {
        confirm("已在系统中取消旧版请求？",
                "先在 Android 旧安装／卸载对话框点击取消并关闭。确认后只会核对原应用、取消保留的请求令牌并清理旧记录，不会重新打开或继续旧版安装／卸载。\n\n已经发生的卸载与存档删除不能撤销；若原应用身份发生变化，将停止清理并提示检查。",
                "已取消并关闭，核对清理", () -> work("正在核对并清理旧版请求…",
                        () -> { this.installer.cancelObsoleteLegacyRequest(true); return null; },
                        ignored -> refreshReadiness()));
    }

    /* renamed from: lambda$renderDiscovery$23$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m100xdbe735b1() {
        confirm("旧系统确认界面已关闭？", "仅在系统确认令牌已失效时使用。先关闭所有旧安装／卸载对话框，再继续。会重新检查现有包并保留暂存 APK；不会自动重新卸载。", "旧界面已关闭，检查请求", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda101
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m99x666d0f70();
            }
        });
    }

    /* renamed from: lambda$renderDiscovery$20$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m97x7b78c2ee() throws Exception {
        this.installer.acknowledgeExpiredRequest(true);
        return null;
    }

    /* renamed from: lambda$renderDiscovery$21$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m98xf0f2e92f(Object obj) throws Exception {
        refreshReadiness();
    }

    /* renamed from: lambda$renderDiscovery$22$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m99x666d0f70() {
        work("正在核对系统请求…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda17
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m97x7b78c2ee();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda18
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m98xf0f2e92f(obj);
            }
        });
    }

    /* renamed from: lambda$renderDiscovery$25$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m102xc6db8233() {
        confirm("由你决定是否允许安装", "只会打开 Android 的“安装未知应用”设置。请自行决定是否允许；之后每次安装仍需系统确认。", "打开设置", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda131
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m101x51615bf2();
            }
        });
    }

    /* renamed from: lambda$renderDiscovery$24$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m101x51615bf2() {
        startActivity(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + getPackageName())));
    }

    /* renamed from: lambda$renderDiscovery$26$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m103x3c55a874() {
        if (canRequestSaveAccess()) {
            this.onboardingActive = true;
            navigate(SyncPage.PATH);
            previewPath();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pickApks() {
        if (this.rootGame != null) { this.notice = "Root 模式不准备或安装游戏；请先明确切换为内置存档桥。"; render(); return; }
        if (this.installer.hasActiveRequest()) {
            this.notice = "请先完成或取消当前系统安装。";
            render();
            return;
        }
        Intent type = new Intent("android.intent.action.OPEN_DOCUMENT").addCategory("android.intent.category.OPENABLE").setType("application/vnd.android.package-archive");
        type.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
        type.addFlags(1);
        try {
            startActivityForResult(type, 8101);
        } catch (ActivityNotFoundException unused) {
            this.notice = "设备没有可用的系统文件选择器。";
            render();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void chooseInstalled() {
        if (this.rootGame != null) { this.notice = "Root 模式不准备或安装游戏；请先明确切换为内置存档桥。"; render(); return; }
        String selectedPackage = selectedGamePackage();
        work("正在读取可选择的已安装应用…", () -> {
            this.installer.assertCanPrepare();
            return InstalledAppCatalog.load(this, selectedPackage);
        }, games -> InstalledAppPickerDialog.show(this, "选择游戏", games, selectedPackage,
                installedPickerState, packageName -> requestPrepare(packageName, null)));
    }

    private void requestPrepare(final String str, final List<Uri> list) {
        if (this.rootGame != null) { this.notice = "Root 模式不准备或安装游戏；请先明确切换为内置存档桥。"; render(); return; }
        work("正在检查本机签名密钥…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda121
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m138x3dde23b5(list, str);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda122
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m140x28d27037(str, list, (String) obj);
            }
        });
    }

    /* renamed from: lambda$requestPrepare$30$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ String m138x3dde23b5(List list, String str) throws Exception {
        if (SyncRuntime.rootMode(this)) throw new IOException("Root 模式不执行游戏准备、安装或内置桥激活。");
        this.installer.assertCanPrepare();
        try {
            return this.preparation.currentLocalCertificateSha256();
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(PreparationDiagnostics.Stage.LOCAL_KEY, e, this.preparation.diagnosticMetadata(list == null ? PreparationDiagnostics.Source.INSTALLED_BASE_AND_SPLITS : PreparationDiagnostics.Source.DOCUMENT_PICKER, str, -1L, list == null ? -1 : list.size()));
        }
    }

    /* renamed from: lambda$requestPrepare$31$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m139xb35849f6(String str, List list) {
        prepare(str, list, true);
    }

    /* renamed from: lambda$requestPrepare$32$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m140x28d27037(final String str, final List list, String str2) throws Exception {
        if (str2 == null) {
            confirm("在本机创建游戏签名密钥？", "需要生成一个仅存在本机 Android Keystore 的私钥，签署追加存档桥后的游戏 APK。原游戏代码和资源保持原字节，主程序与游戏各自使用独立 UID。密钥不会上传，也不会发送给游戏。\n\n卸载主程序或清除数据会失去密钥；将来可能需要再次卸载游戏。生成密钥和准备 APK 本身不会卸载任何应用。", "同意并准备", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda158
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m139xb35849f6(str, list);
                }
            });
        } else {
            prepare(str, list, false);
        }
    }

    private void prepare(final String str, final List<Uri> list, final boolean z) {
        this.diagnosticReport = "";
        this.details = "";
        this.detailsExpanded = false;
        work("正在验证并暂存安装包，原游戏保持不变…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda99
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m58lambda$prepare$33$iogithubxyz1001pdsyncandroidMainActivity(list, str, z);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda100
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m59lambda$prepare$34$iogithubxyz1001pdsyncandroidMainActivity((GamePreparationService.PreparedPair) obj);
            }
        });
    }

    /* renamed from: lambda$prepare$33$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ GamePreparationService.PreparedPair m58lambda$prepare$33$iogithubxyz1001pdsyncandroidMainActivity(List list, String str, boolean z) throws Exception {
        if (SyncRuntime.rootMode(this)) throw new IOException("Root 模式不执行游戏准备、安装或内置桥激活。");
        this.installer.assertCanPrepare();
        PreparationDiagnostics.Stage stage = PreparationDiagnostics.Stage.EXTRACTION;
        try (InputStream runtimeDex = getAssets().open("bridge-runtime.dex")) {
            GamePreparationService.PreparedPair pair = list == null
                    ? this.preparation.prepareInstalledGame(str, z, runtimeDex)
                    : this.preparation.prepareSelectedApks(list, z, runtimeDex);
            stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
            this.installer.registerPrepared(pair);
            SyncRuntime.setPreparedGame(this, pair);
            this.selectedNamespace = SyncRuntime.currentNamespace(this);
            SecureConnectionStore.Connection connection = this.connection;
            if (connection != null) SyncRuntime.setOnboardingProgress(this, connection, OnboardingProgress.PENDING);
            return pair;
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(stage, e, this.preparation.diagnosticMetadata(list == null ? PreparationDiagnostics.Source.INSTALLED_BASE_AND_SPLITS : PreparationDiagnostics.Source.DOCUMENT_PICKER, str, -1L, list == null ? -1 : list.size()));
        }
    }

    /* renamed from: lambda$prepare$34$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m59lambda$prepare$34$iogithubxyz1001pdsyncandroidMainActivity(GamePreparationService.PreparedPair preparedPair) throws Exception {
        this.prepared = preparedPair;
        this.readinessState = InstallationReadiness.checking(preparedPair != null, this.installer.hasActiveRequest());
        this.facade = null;
        this.history = null;
        this.inspection = null;
        this.cloudVerified = false;
        this.selected = null;
        this.progress = OnboardingProgress.PENDING;
        this.onboardingActive = true;
        this.localPreview = null;
        this.page = SyncPage.DISCOVERY;
        this.notice = "带内置存档桥的游戏 APK 已完整暂存并复核，正在检查当前安装状态。";
        refreshReadiness();
    }

    private String pairSummary(GamePreparationService.PreparedPair preparedPair) {
        StringBuilder sb = new StringBuilder();
        sb.append(preparedPair.gameLabel);
        sb.append(" ");
        sb.append(preparedPair.gameVersionName);
        sb.append("（");
        sb.append(preparedPair.gameVersionCode);
        sb.append("）\n");
        sb.append(preparedPair.gamePackage);
        sb.append("\n");
        sb.append(preparedPair.gameApks.size());
        sb.append(" 个游戏 APK，");
        sb.append(preparedPair.protectedEntryCount);
        sb.append(" 个代码／资源条目已核对。\n游戏：");
        sb.append(InstallationReadiness.packageDescription(this.readinessState.game));
        sb.append(preparedPair.embeddedBridge() ? "\n存档桥：内置于游戏，随游戏安装" : "\n旧版准备记录：请重新准备内置存档桥游戏");
        sb.append("\n同步空间：");
        sb.append(this.selectedNamespace.isEmpty() ? preparedPair.namespaceId : this.selectedNamespace);
        return sb.toString();
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class InstallNext {
        String name;
        GamePreparationService.PreparedPair pair;
        String receipt;
        boolean uninstall;

        private InstallNext() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void continueInstallation() {
        if (this.rootGame != null) { this.notice = "Root 模式不准备或安装游戏；请先明确切换为内置存档桥。"; render(); return; }
        if (this.installer.hasConfirmation()) {
            openInstallConfirmation();
        } else {
            work("正在重新验证暂存包与现有应用…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda112
                @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
                public final Object run() throws Exception {
                    return MainActivity.this.m32xc4841ad1();
                }
            }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda113
                @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
                public final void accept(Object obj) throws Exception {
                    MainActivity.this.m39x9de049ed((MainActivity.InstallNext) obj);
                }
            });
        }
    }

    /* renamed from: lambda$continueInstallation$35$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ InstallNext m32xc4841ad1() throws Exception {
        if (SyncRuntime.rootMode(this)) throw new IOException("Root 模式不执行安装或卸载。请先明确切换访问方式。");
        this.installer.reconcile();
        if (this.installer.hasActiveRequest()) {
            throw new IOException("系统请求仍在处理中。请完成系统确认，或取消当前安装会话后再试。");
        }
        InstallNext installNext = new InstallNext();
        installNext.pair = this.installer.freshPair();
        installNext.name = this.installer.nextPackage(installNext.pair);
        if (installNext.name != null) {
            installNext.uninstall = installNext.pair.gameStrategy == GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL;
            if (installNext.uninstall) {
                if (hasAnyRestoreJournal()) {
                    throw new IOException("存在未完成存档恢复，请先处理恢复记录，不能卸载游戏。");
                }
                installNext.receipt = this.installer.uninstallConsentReceipt(installNext.name, installNext.pair);
                return installNext;
            }
        }
        return installNext;
    }

    /* renamed from: lambda$continueInstallation$42$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m39x9de049ed(final InstallNext installNext) throws Exception {
        this.prepared = installNext.pair;
        SyncRuntime.setPreparedGame(this, installNext.pair);
        if (installNext.name == null) {
            this.notice = "游戏安装完成，本机签名、完整内容、内置存档桥及独立 UID 已核对。";
            refreshReadiness();
        } else {
            if (installNext.uninstall) {
                confirm("卸载前请确认数据损失", InstallCoordinator.LOSS_WARNING, "我已理解，打开系统卸载", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda72
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainActivity.this.m35x24f28d94(installNext);
                    }
                });
                return;
            }
            StringBuilder sb = new StringBuilder("安装");
            sb.append("带内置存档桥的游戏");
            sb.append("？");
            confirm(sb.toString(), "接下来交给 Android 系统安装。安装包已使用本机密钥签名并验证；必须由你在系统界面明确确认。", "请求系统安装", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda73
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m38x286623ac(installNext);
                }
            });
        }
    }

    /* renamed from: lambda$continueInstallation$36$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m33x39fe4112(InstallNext installNext) throws Exception {
        if (SyncRuntime.rootMode(this)) throw new IOException("Root 模式不执行安装或卸载。请先明确切换访问方式。");
        this.installer.requestUninstall(installNext.name, installNext.receipt);
        return null;
    }

    /* renamed from: lambda$continueInstallation$38$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m35x24f28d94(final InstallNext installNext) {
        work("正在最后核验安装包与当前应用…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda19
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m33x39fe4112(installNext);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda20
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m34xaf786753(obj);
            }
        });
    }

    /* renamed from: lambda$continueInstallation$37$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m34xaf786753(Object obj) throws Exception {
        this.notice = this.installer.summary();
        if (this.installer.hasConfirmation()) {
            openInstallConfirmation();
        }
    }

    /* renamed from: lambda$continueInstallation$39$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m36x9a6cb3d5(InstallNext installNext) throws Exception {
        if (SyncRuntime.rootMode(this)) throw new IOException("Root 模式不执行安装或卸载。请先明确切换访问方式。");
        this.installer.requestInstall(installNext.name);
        return null;
    }

    /* renamed from: lambda$continueInstallation$41$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m38x286623ac(final InstallNext installNext) {
        work("正在向 Android 提交经过验证的安装包…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda123
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m36x9a6cb3d5(installNext);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda124
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m37xb2ebfd6b(obj);
            }
        });
    }

    /* renamed from: lambda$continueInstallation$40$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m37xb2ebfd6b(Object obj) throws Exception {
        this.notice = this.installer.summary();
        if (this.installer.hasConfirmation()) {
            openInstallConfirmation();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openInstallConfirmation() {
        work("正在复核保留的安装包与系统确认…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda92
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m52xf9a573e3();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda93
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m53x6f1f9a24((PendingIntent) obj);
            }
        });
    }

    /* renamed from: lambda$openInstallConfirmation$43$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ PendingIntent m52xf9a573e3() throws Exception {
        this.installer.freshPair();
        return this.installer.confirmationToken();
    }

    /* renamed from: lambda$openInstallConfirmation$44$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m53x6f1f9a24(PendingIntent pendingIntent) throws Exception {
        pendingIntent.send();
        this.installer.confirmationLaunched();
        this.notice = this.installer.summary();
    }

    private String savePathLabel() {
        if (this.rootGame != null) return new java.io.File(this.rootGame.dataDir, "files").getPath();
        String gamePackage = selectedGamePackage();
        if (gamePackage == null) return "尚未选择游戏";
        try {
            return new java.io.File(getPackageManager().getApplicationInfo(gamePackage, 0).dataDir, "files").getPath();
        } catch (PackageManager.NameNotFoundException unavailable) {
            return gamePackage + " / files";
        }
    }

    private boolean showEmptyCompletion() {
        return AndroidUiFlowPolicy.showEmptyCompletion(kind() == null ? null : kind().name(), this.cloudVerified,
                this.inspection != null && this.inspection.getNeedsRestoreRecovery(),
                this.inspection != null && this.inspection.isRetryablePending());
    }

    private String automaticStatusBrief() {
        if (!automaticObservationSupported() || SyncRuntime.isAutoUploadPaused(this) || this.progress != OnboardingProgress.COMPLETE) return "";
        String result = AutoSyncService.status(this);
        if (result.startsWith("从本应用启动游戏后")) return "";
        if (result.contains("HISTORY_CONFLICT")) return "有存档冲突，自动上传已跳过。";
        if (result.contains("并发分支")) return "本机已上传，有存档冲突待处理。";
        if (result.contains("云端随后又有新版本")) return "本机已上传，云端有新进度待选择。";
        if (result.contains("自动上传未完成") || result.contains("未完成") || result.length() > 48) {
            if (this.details.isEmpty()) this.details = safeText(result);
            return "自动上传未完成，请检查存档。";
        }
        return result;
    }

    private String visibleNotice() {
        if (this.busy) return this.notice;
        if (this.feedbackError) return NoviceCopy.feedback(this.notice, "操作未完成，请重试。");
        if (this.page == SyncPage.DISCOVERY) return this.notice;
        if (this.page == SyncPage.COMPARISON && this.notice.equals(comparisonCopy())) return "";
        if (this.notice.equals("请选择游戏。") || this.notice.equals("配置完成。") || this.notice.length() > 48) return "";
        return this.notice;
    }

    private void renderPath() {
        heading("2 / 4  设置存档路径");
        text("存档目录");
        text(savePathLabel());
        if (this.localPreview == null) {
            text("请检查存档。");
        } else {
            StringBuilder sb = new StringBuilder("游戏槽位 ");
            sb.append(this.localPreview.getSlots().size());
            sb.append(" 个 · 游玩 ");
            sb.append(this.localPreview.getPlays() == null ? "未知" : this.localPreview.getPlays());
            sb.append(" 次");
            text(sb.toString());
            if (this.localPreview.getSlots().isEmpty()) {
                text(this.localPreview.getFiles() > 0 || (this.localPreview.getPlays() != null && this.localPreview.getPlays() > 0)
                        ? "有记录，暂无可继续的存档。" : "暂无存档。");
            }
            for (Map.Entry<String, String> entry : this.localPreview.getSlots().entrySet()) {
                text(entry.getKey() + " · " + HistoryPresentation.time(entry.getValue()));
            }
        }
        if (!readyForSaves()) {
            text("暂时无法读取存档，请重新检查。");
        }
        if (this.readinessState.withSystemRequest(this.installer.hasActiveRequest()).canInitializeGame()) {
            button("启动游戏一次", this::launchGameForInitialization);
        }
        button(readyForSaves() ? "重新检查存档" : "重新检查状态", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda87
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m108xe2dc5156();
            }
        });
        steps(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda88
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m109x58567797();
            }
        }, new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda89
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m110xcdd09dd8();
            }
        }, readyForSaves() && this.localPreview != null);
        if (this.rootGame != null || this.localPreview != null || this.previewAttempted || this.busy || !readyForSaves()) {
            return;
        }
        this.body.post(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda90
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m111x434ac419();
            }
        });
    }

    /* renamed from: lambda$renderPath$45$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m108xe2dc5156() {
        if (canRequestSaveAccess()) {
            previewPath();
        } else {
            if (this.rootGame != null) requestRootAuthorization(); else refreshReadiness();
        }
    }

    /* renamed from: lambda$renderPath$46$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m109x58567797() {
        navigate(SyncPage.DISCOVERY);
    }

    /* renamed from: lambda$renderPath$47$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m110xcdd09dd8() {
        this.onboardingActive = true;
        navigate(SyncPage.CONNECTION);
    }

    /* renamed from: lambda$renderPath$48$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m111x434ac419() {
        if (this.rootGame != null || this.page != SyncPage.PATH || this.previewAttempted || this.busy || !this.resumed) {
            return;
        }
        previewPath();
    }

    private void previewPath() {
        if (!canRequestSaveAccess()) {
            if (this.rootGame != null) requestRootAuthorization(); else refreshReadiness();
            return;
        }
        this.previewAttempted = true;
        this.localPreview = null;
        work("正在读取存档…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda44
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m64x685e4018();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda45
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m65xddd86659((SaveSummary) obj);
            }
        });
    }

    /* renamed from: lambda$previewPath$50$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ SaveSummary m64x685e4018() throws Exception {
        if (this.rootGame != null) {
            checkRootAccess();
            if (!readyForSaves()) throw new IOException(this.readinessState.accessDescription());
        }
        TrustedPrivateDirectory.TemporaryDirectory temporaryDirectoryCreateTemporaryDirectory = TrustedPrivateDirectory.resolve(getDataDir().toPath(), getCacheDir().toPath(), Process.myUid(), new TrustedPrivateDirectory.StatReader() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda125
            @Override // io.github.xyz1001.pdsync.android.TrustedPrivateDirectory.StatReader
            public final TrustedPrivateDirectory.State lstat(Path path) throws IOException {
                return MainActivity.lambda$previewPath$49(path);
            }
        }).createTemporaryDirectory("save-preview-");
        try {
            SyncRuntime.backend(this).exportTo(temporaryDirectoryCreateTemporaryDirectory.path());
            SaveSummary saveSummary = SaveSummary.Companion.read(temporaryDirectoryCreateTemporaryDirectory.path());
            temporaryDirectoryCreateTemporaryDirectory.path();
            if (temporaryDirectoryCreateTemporaryDirectory != null) {
                temporaryDirectoryCreateTemporaryDirectory.close();
            }
            return saveSummary;
        } catch (Throwable th) {
            if (temporaryDirectoryCreateTemporaryDirectory != null) {
                try {
                    temporaryDirectoryCreateTemporaryDirectory.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    static /* synthetic */ TrustedPrivateDirectory.State lambda$previewPath$49(Path path) throws IOException {
        try {
            StructStat structStatLstat = Os.lstat(path.toString());
            return new TrustedPrivateDirectory.State(structStatLstat.st_dev, structStatLstat.st_ino, structStatLstat.st_uid, OsConstants.S_ISDIR(structStatLstat.st_mode));
        } catch (ErrnoException e) {
            throw new IOException("无法核验本机预览缓存目录。", e);
        }
    }

    /* renamed from: lambda$previewPath$51$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m65xddd86659(SaveSummary saveSummary) throws Exception {
        this.localPreview = saveSummary;
        this.notice = "";
    }

    private void loadDraft() {
        SecureConnectionStore.Connection connection = this.connection;
        this.draftEndpoint = connection == null ? "" : connection.endpoint;
        SecureConnectionStore.Connection connection2 = this.connection;
        this.draftUser = connection2 == null ? "" : connection2.username;
        SecureConnectionStore.Connection connection3 = this.connection;
        this.draftPassword = connection3 != null ? connection3.password : "";
        SecureConnectionStore.Connection connection4 = this.connection;
        this.draftProfile = connection4 == null ? "default" : connection4.profile;
        boolean official = connection != null && SecureConnectionStore.Connection.PROVIDER_OFFICIAL.equals(connection.provider);
        this.connectionEntry = new ConnectionEntryModel(connection == null ? ConnectionEntryModel.Origin.SETUP : ConnectionEntryModel.Origin.SETTINGS,
                connection == null ? null : official ? ConnectionEntryModel.Mode.OFFICIAL : ConnectionEntryModel.Mode.CUSTOM);
        this.officialUser = official ? connection.username : "";
        this.officialPassword = official ? connection.password : "";
        this.verifiedOfficial = null;
        this.officialAttempt = null;
        this.draftLoaded = true;
        this.testedDraft = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void captureDraft() {
        if (this.renderedPage != SyncPage.CONNECTION || this.connectionEntry == null || this.userInput == null || this.passwordInput == null) return;
        if (this.connectionEntry.mode() == ConnectionEntryModel.Mode.OFFICIAL) {
            this.officialUser = this.userInput.getText().toString();
            this.officialPassword = this.passwordInput.getText().toString();
        } else if (this.connectionEntry.mode() == ConnectionEntryModel.Mode.CUSTOM && this.endpointInput != null) {
            this.draftEndpoint = this.endpointInput.getText().toString();
            this.draftUser = this.userInput.getText().toString();
            this.draftPassword = this.passwordInput.getText().toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String draftFingerprint() {
        if (this.connectionEntry != null && this.connectionEntry.mode() == ConnectionEntryModel.Mode.OFFICIAL)
            return "official\u0000" + this.officialUser + "\u0000" + this.officialPassword + "\u0000" + this.draftProfile;
        return "custom\u0000" + this.draftEndpoint + "\u0000" + this.draftUser + "\u0000" + this.draftPassword + "\u0000" + this.draftProfile;
    }

    private EditText field(String str, String str2, boolean z) {
        this.body.addView(label(str, 13, -10129303));
        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setText(str2);
        editText.setInputType(z ? 129 : 524289);
        editText.setSaveEnabled(false);
        editText.setImportantForAutofill(8);
        this.body.addView(editText);
        return editText;
    }

    private void renderConnection() {
        if (!this.draftLoaded || this.connectionEntry == null) loadDraft();
        heading(this.onboardingActive ? "3 / 4  连接云端" : "连接设置");
        if (this.connectionEntry.mode() == ConnectionEntryModel.Mode.CHOICE) {
            button("使用官方服务", () -> chooseConnectionService(ConnectionEntryModel.Mode.OFFICIAL));
            button("自定义 WebDAV", () -> chooseConnectionService(ConnectionEntryModel.Mode.CUSTOM));
            foot(this.onboardingActive ? "上一步" : "返回", this::backFromConnection);
            return;
        }
        if (this.connectionEntry.mode() == ConnectionEntryModel.Mode.OFFICIAL) renderOfficialConnection();
        else renderCustomConnection();
        if (this.connectionEntry.origin() == ConnectionEntryModel.Origin.SETTINGS)
            button("更换服务", this::showConnectionChoice);
    }

    private void renderCustomConnection() {
        EditText editTextField = field("WebDAV 地址", this.draftEndpoint, false);
        this.endpointInput = editTextField;
        editTextField.setHint("https://example.com/dav");
        this.endpointInput.setInputType(17);
        this.userInput = field("用户名", this.draftUser, false);
        this.passwordInput = field("密码", this.draftPassword, true);
        button("测试连接", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda127
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m88xda0abd19();
            }
        });
        if (!this.connectionLoadError.isEmpty()) text("连接设置读取失败，请重试。");
        if (!this.connectionLoadError.isEmpty() || (this.connection != null && this.connection.isTemporary() && this.secrets.exists())) {
            button(this.connectionLoadError.isEmpty() ? "恢复已保存连接" : "重试读取已保存连接", this::reloadSavedConnection);
        }
        watchConnectionFields(renderConnectionFooter());
    }

    private void renderOfficialConnection() {
        this.userInput = field("用户名", this.officialUser, false);
        this.passwordInput = field("密码", this.officialPassword, true);
        LinearLayout actions = row();
        this.body.addView(actions);
        addButton(actions, "登录", () -> authenticateOfficial(false), true);
        addButton(actions, "注册", () -> authenticateOfficial(true), true);
        watchConnectionFields(renderConnectionFooter());
    }

    private Button renderConnectionFooter() {
        LinearLayout linearLayoutRow = row();
        this.footer.addView(linearLayoutRow);
        addButton(linearLayoutRow, this.onboardingActive ? "上一步" : "返回", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda129
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m89x4f84e35a();
            }
        }, true);
        Button buttonAddButton = addButton(linearLayoutRow, this.onboardingActive ? "下一步" : "保存", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda130
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m90xc4ff099b();
            }
        }, true);
        buttonAddButton.setEnabled(AndroidUiFlowPolicy.canContinue(this.testedDraft, draftFingerprint(), this.busy));
        return buttonAddButton;
    }

    private void watchConnectionFields(final Button buttonAddButton) {
        TextWatcher textWatcher = new TextWatcher() { // from class: io.github.xyz1001.pdsync.android.MainActivity.2
            @Override // android.text.TextWatcher
            public void afterTextChanged(Editable editable) {
            }

            @Override // android.text.TextWatcher
            public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
            }

            @Override // android.text.TextWatcher
            public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                String before = MainActivity.this.draftFingerprint();
                MainActivity.this.captureDraft();
                if (!before.equals(MainActivity.this.draftFingerprint())) MainActivity.this.verifiedOfficial = null;
                MainActivity.this.testedDraft = AndroidUiFlowPolicy.invalidateProofOnEdit(
                        MainActivity.this.testedDraft, before, MainActivity.this.draftFingerprint());
                buttonAddButton.setEnabled(AndroidUiFlowPolicy.canContinue(
                        MainActivity.this.testedDraft, MainActivity.this.draftFingerprint(), MainActivity.this.busy));
                if (!before.equals(MainActivity.this.draftFingerprint()) && !MainActivity.this.busy && !MainActivity.this.feedbackError) {
                    MainActivity.this.notice = "";
                    MainActivity.this.status.setVisibility(View.GONE);
                }
            }
        };
        if (this.endpointInput != null) this.endpointInput.addTextChangedListener(textWatcher);
        this.userInput.addTextChangedListener(textWatcher);
        this.passwordInput.addTextChangedListener(textWatcher);
    }

    private void chooseConnectionService(ConnectionEntryModel.Mode service) {
        if (this.busy || this.connectionEntry == null) return;
        captureDraft();
        if (!this.connectionEntry.choose(service, false)) return;
        this.testedDraft = null;
        this.notice = ""; this.details = ""; this.diagnosticReport = ""; this.feedbackError = false;
        render();
    }

    private void showConnectionChoice() {
        if (this.busy || this.connectionEntry == null) return;
        captureDraft();
        if (!this.connectionEntry.showChooser(false)) return;
        this.testedDraft = null;
        this.notice = ""; this.details = ""; this.diagnosticReport = ""; this.feedbackError = false;
        render();
    }

    private void backFromConnection() {
        if (this.busy) return;
        captureDraft();
        this.testedDraft = null;
        ConnectionEntryModel.Back destination = this.connectionEntry == null
                ? ConnectionEntryModel.Back.SETTINGS : this.connectionEntry.back(false);
        if (destination == ConnectionEntryModel.Back.CHOICE) {
            this.notice = ""; this.details = ""; this.diagnosticReport = ""; this.feedbackError = false;
            render();
        } else if (destination != ConnectionEntryModel.Back.STAY) {
            // A saved connection can be edited either in the wizard or in standalone settings.
            navigate(this.onboardingActive ? SyncPage.PATH : SyncPage.SETTINGS);
        }
    }

    private void authenticateOfficial(final boolean register) {
        if (this.busy || this.connectionEntry == null || this.connectionEntry.mode() != ConnectionEntryModel.Mode.OFFICIAL) return;
        captureDraft();
        final String username = this.officialUser, password = this.officialPassword, profile = this.draftProfile;
        final android.content.Context app = getApplicationContext();
        this.testedDraft = null;
        this.verifiedOfficial = null;
        this.officialAttempt = new ConnectionAuthAttempt<>(draftFingerprint(), () -> {
            OfficialServiceRegistrationClient client = new OfficialServiceRegistrationClient();
            OfficialServiceRegistrationClient.Account account = register ? client.register(username, password) : client.login(username, password);
            SecureConnectionStore.Connection candidate = new SecureConnectionStore.Connection(account.endpoint, account.username,
                    account.password, profile, SecureConnectionStore.Connection.PROVIDER_OFFICIAL);
            candidate.validate();
            MainActivity.lambda$applyConnection$55(SyncRuntime.facade(app, candidate));
            return candidate;
        });
        resumeOfficialAttempt();
    }

    private void resumeOfficialAttempt() {
        final ConnectionAuthAttempt<SecureConnectionStore.Connection> attempt = this.officialAttempt;
        if (attempt == null || this.busy) return;
        work("正在连接…", attempt::await, candidate -> {
            if (this.officialAttempt != attempt) return;
            this.officialAttempt = null;
            if (this.connectionEntry == null || this.connectionEntry.mode() != ConnectionEntryModel.Mode.OFFICIAL
                    || !attempt.proof.equals(draftFingerprint())) return;
            this.verifiedOfficial = candidate;
            this.testedDraft = attempt.proof;
            this.notice = "连接成功。";
        });
    }

    /* renamed from: lambda$renderConnection$52$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m88xda0abd19() {
        applyConnection(false);
    }

    /* renamed from: lambda$renderConnection$53$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m89x4f84e35a() {
        backFromConnection();
    }

    /* renamed from: lambda$renderConnection$54$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m90xc4ff099b() {
        applyConnection(true);
    }

    private void applyConnection(final boolean z) {
        if (this.connectionEntry == null || this.connectionEntry.mode() == ConnectionEntryModel.Mode.CHOICE) return;
        if (!z && this.connectionEntry.mode() != ConnectionEntryModel.Mode.CUSTOM) return;
        captureDraft();
        final String strDraftFingerprint = draftFingerprint();
        if (z && !strDraftFingerprint.equals(this.testedDraft)) {
            this.notice = "请先测试当前连接。";
            render();
        } else {
            final SecureConnectionStore.Connection connection = this.connectionEntry.mode() == ConnectionEntryModel.Mode.OFFICIAL
                    ? this.verifiedOfficial : new SecureConnectionStore.Connection(this.draftEndpoint, this.draftUser, this.draftPassword, this.draftProfile);
            if (connection == null) return;
            this.testedDraft = null;
            work(z ? "正在应用连接设置…" : "正在测试连接…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda116
                @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
                public final Object run() throws Exception {
                    return MainActivity.this.m17xc0060660(connection, z);
                }
            }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda117
                @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
                public final void accept(Object obj) throws Exception {
                    MainActivity.this.m18x35802ca1(strDraftFingerprint, z, obj);
                }
            });
        }
    }

    /* renamed from: lambda$applyConnection$57$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m17xc0060660(final SecureConnectionStore.Connection connection, boolean z) throws Exception {
        connection.validate();
        final AndroidSyncFacade androidSyncFacadeFacade = SyncRuntime.facade(this, connection);
        ConnectionUpdatePolicy.apply(z, connection.isTemporary(), new ConnectionUpdatePolicy.Operation() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda155
            @Override // io.github.xyz1001.pdsync.android.ConnectionUpdatePolicy.Operation
            public final void run() throws Exception {
                MainActivity.lambda$applyConnection$55(androidSyncFacadeFacade);
            }
        }, new ConnectionUpdatePolicy.Operation() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda156
            @Override // io.github.xyz1001.pdsync.android.ConnectionUpdatePolicy.Operation
            public final void run() throws Exception {
                MainActivity.this.m16x4a8be01f(connection);
            }
        });
        if (z) {
            AutoSyncService.cancelForConnectionChange(this);
            this.connection = connection;
            SyncRuntime.setConnection(connection);
            this.facade = androidSyncFacadeFacade;
            if (!connection.isTemporary()) {
                this.connectionLoadError = "";
            }
            this.inspection = null;
            this.history = null;
            this.selected = null;
            this.cloudVerified = false;
            OnboardingProgress onboardingProgress = OnboardingProgress.PENDING;
            this.progress = onboardingProgress;
            SyncRuntime.setOnboardingProgress(this, this.connection, onboardingProgress);
        }
        return null;
    }

    static /* synthetic */ void lambda$applyConnection$55(AndroidSyncFacade androidSyncFacade) throws Exception {
        AndroidSyncFacade.ConnectionStatus connectionStatusConnect = androidSyncFacade.connect();
        if (!connectionStatusConnect.isSuccess()) {
            throw new IOException(connectionStatusConnect.getMessage());
        }
    }

    /* renamed from: lambda$applyConnection$56$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m16x4a8be01f(SecureConnectionStore.Connection connection) throws Exception {
        this.secrets.save(connection);
    }

    /* renamed from: lambda$applyConnection$58$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m18x35802ca1(String str, boolean z, Object obj) throws Exception {
        if (!z) {
            this.testedDraft = str;
            this.notice = "连接测试通过。";
        } else {
            this.testedDraft = null;
            this.onboardingActive = true;
            navigate(SyncPage.COMPARISON);
            refreshComparison(SyncPage.COMPARISON);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reloadSavedConnection() {
        work("正在读取已保存连接…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda110
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m70x54fe8022();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda111
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m71x6d7dc9b8(obj);
            }
        });
    }

    /* renamed from: lambda$reloadSavedConnection$59$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m70x54fe8022() throws Exception {
        SecureConnectionStore.Connection connectionLoad = this.secrets.load();
        if (connectionLoad == null) {
            throw new IOException("没有已保存的连接文件；当前连接与编辑内容未更改。");
        }
        AutoSyncService.cancelForConnectionChange(this);
        this.connection = connectionLoad;
        SyncRuntime.setConnection(connectionLoad);
        this.facade = null;
        this.inspection = null;
        this.history = null;
        this.cloudVerified = false;
        this.progress = SyncRuntime.onboardingProgress(this, connectionLoad);
        this.connectionLoadError = "";
        this.draftLoaded = false;
        this.testedDraft = null;
        return null;
    }

    /* renamed from: lambda$reloadSavedConnection$60$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m71x6d7dc9b8(Object obj) throws Exception {
        this.page = SyncPage.CONNECTION;
        this.notice = "已恢复保存的连接，请测试后继续。";
    }

    private AndroidSyncFacade sync() throws Exception {
        SecureConnectionStore.Connection connection = this.connection;
        if (connection == null) {
            throw new IOException("请先设置云端连接。");
        }
        if (this.facade == null) {
            this.facade = SyncRuntime.facade(this, connection);
        }
        return this.facade;
    }

    private void readComparison() throws Exception {
        this.cloudVerified = false;
        this.history = null;
        this.inspection = null;
        // One facade and one observation own both the action proof and its timeline.
        // Drawing, paging, selecting, or dismissing the timeline must never re-export saves.
        SyncInspectionDiagnostics.Stage stage = SyncInspectionDiagnostics.Stage.RUNTIME;
        try {
            AndroidSyncFacade activeFacade = sync();
            stage = SyncInspectionDiagnostics.Stage.INSPECT;
            AndroidSyncFacade.Inspection inspectionInspect = activeFacade.inspect();
            this.inspection = inspectionInspect;
            if ("BLOCKED".equals(inspectionInspect.getKind())) {
                return;
            }
            stage = SyncInspectionDiagnostics.Stage.HISTORY;
            this.history = activeFacade.historyPresentation(inspectionInspect);
            this.cloudVerified = true;
            HashSet hashSet = new HashSet();
            Iterator<AppendCommit> it = this.history.getCloud().iterator();
            while (it.hasNext()) {
                hashSet.add(it.next().getSnapshot().getId());
            }
            String str = this.selected;
            if (str != null && !hashSet.contains(str)) {
                this.selected = null;
            }
            if (this.selected == null) {
                this.selected = this.history.getDefaultSnapshot();
            }
        } catch (Exception error) {
            // Keep existing structured details and recovery handling exactly as they were.
            if (InstallDiagnostics.find(error) != null || PreparationDiagnostics.find(error) != null
                    || BridgeDiagnostics.find(error) != null) throw error;
            throw SyncInspectionDiagnostics.at(stage, error);
        }
    }

    private void refreshComparison(SyncPage syncPage) {
        refreshComparison(syncPage, true);
    }

    private void refreshComparison(final SyncPage syncPage, final boolean z) {
        if (this.connection == null) {
            navigate(SyncPage.CONNECTION);
            return;
        }
        if (this.busy || isDestroyed()) {
            return;
        }
        if (z) {
            this.readinessState = InstallationReadiness.checking(this.prepared != null, this.installer.hasActiveRequest());
            this.localPreview = null;
            this.previewAttempted = false;
            this.cloudVerified = false;
            this.inspection = null;
            this.history = null;
        }
        work("正在检查存档…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda141
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m66x99ceaa21(z, syncPage);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda142
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m67xf48d062(syncPage, (Boolean) obj);
            }
        });
    }

    /* renamed from: lambda$refreshComparison$61$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Boolean m66x99ceaa21(boolean z, SyncPage syncPage) throws Exception {
        if (z) {
            readInstallationReadiness();
            if (this.rootGame != null) checkRootAccess();
        }
        if (!readyForSaves()) {
            return false;
        }
        if (syncPage == SyncPage.COMPARISON && this.onboardingActive) {
            AutoSyncService.cancelForConnectionChange(this);
            SyncRuntime.setOnboardingProgress(this, this.connection, OnboardingProgress.PENDING);
            this.progress = OnboardingProgress.PENDING;
        }
        readComparison();
        return true;
    }

    /* renamed from: lambda$refreshComparison$62$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m67xf48d062(SyncPage syncPage, Boolean bool) throws Exception {
        this.page = syncPage;
        if (!bool.booleanValue()) {
            this.notice = this.readinessState.statusDescription();
            return;
        }
        this.notice = comparisonCopy();
        AndroidSyncFacade.Inspection inspection = this.inspection;
        this.details = inspection == null ? "" : safeText(inspection.getMessage());
    }

    private OnboardingKind kind() {
        AndroidSyncFacade.Inspection inspection = this.inspection;
        if (inspection == null) {
            return null;
        }
        try {
            return OnboardingKind.valueOf(inspection.getKind());
        } catch (IllegalArgumentException unused) {
            return OnboardingKind.BLOCKED;
        }
    }

    private String comparisonCopy() {
        if (this.cloudVerified && this.inspection != null && this.inspection.getCanKeepLocalProgress()) {
            return "本机有新的游戏进度，可以继续。";
        }
        return NoviceCopy.comparison(this.cloudVerified || kind() == OnboardingKind.BLOCKED ? kind() : null, this.busy);
    }

    private void renderComparison() {
        if (showEmptyCompletion()) {
            heading(this.feedbackError ? "4 / 4  配置尚未完成" : "4 / 4  配置完成");
            if (!this.feedbackError) text("配置完成");
            foot("上一步", () -> navigate(SyncPage.CONNECTION));
            foot("完成", this::completeEmpty).setEnabled(!this.busy && this.onboardingActive && this.connection != null);
            return;
        }
        heading("4 / 4  选择存档");
        text(comparisonCopy());
        if (!this.cloudVerified && kind() != OnboardingKind.BLOCKED) {
            foot("上一步", () -> navigate(SyncPage.CONNECTION));
            foot("重新比较", () -> refreshComparison(SyncPage.COMPARISON));
            return;
        }
        if (this.inspection != null) {
            text("本机 · " + Build.MODEL + "\n" + OnboardingGate.localFileCountLabel(kind(), this.inspection.getLocalFileCount(), this.inspection.isRetryablePending()));
            Iterator<AndroidSyncFacade.Version> it = this.inspection.getRemoteVersions().iterator();
            while (it.hasNext()) {
                versionChoice(it.next());
            }
            if (this.inspection.getNeedsRestoreRecovery()) {
                button("检查未完成恢复", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda134
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainActivity.this.m79x1639f6e();
                    }
                });
            }
        }
        LinearLayout linearLayoutRow = row();
        this.footer.addView(linearLayoutRow);
        addButton(linearLayoutRow, "上一步", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda135
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m80x76ddc5af();
            }
        }, true);
        addButton(linearLayoutRow, "重新比较", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda136
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m81xec57ebf0();
            }
        }, true);
        AndroidSyncFacade.Inspection inspection = this.inspection;
        if (inspection != null && inspection.getCanKeepLocalProgress()) {
            foot("完成", this::completeLocalProgress);
        } else if (inspection != null && inspection.getCanCompleteWithoutTransfer()) {
            foot("完成", this::completeReadyComparison);
        }
        if (inspection != null && (inspection.getLocalFileCount() > 0 || this.inspection.isRetryablePending())) {
            foot(this.inspection.isRetryablePending() ? "重试上传" : this.inspection.getRemoteVersions().isEmpty() ? "上传本机存档" : "上传本机，保留两份", this::upload).setEnabled(kind() != OnboardingKind.BLOCKED || this.inspection.isRetryablePending());
        }
        AndroidSyncFacade.Inspection inspection2 = this.inspection;
        if (inspection2 == null || !inspection2.getCanAdoptRemote()) {
            AndroidSyncFacade.Inspection inspection3 = this.inspection;
            if (inspection3 != null && !inspection3.getRemoteVersions().isEmpty()) {
                foot("恢复所选云端存档", this::requestRestore).setEnabled(restoreSupported() && this.selected != null && kind() != OnboardingKind.BLOCKED);
            }
        } else {
            foot("完成", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda139
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.adopt();
                }
            });
        }
        foot("稍后处理", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda140
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.postpone();
            }
        });
    }

    /* renamed from: lambda$renderComparison$63$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m79x1639f6e() {
        navigate(SyncPage.SETTINGS);
    }

    /* renamed from: lambda$renderComparison$64$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m80x76ddc5af() {
        navigate(SyncPage.CONNECTION);
    }

    /* renamed from: lambda$renderComparison$65$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m81xec57ebf0() {
        refreshComparison(SyncPage.COMPARISON);
    }

    private void versionChoice(final AndroidSyncFacade.Version version) {
        String str;
        RadioButton radioButton = new RadioButton(this);
        StringBuilder sb = new StringBuilder();
        if (version.getDeviceLabel() == null) {
            str = "云端 · 设备未知";
        } else {
            str = "云端 · " + version.getDeviceLabel();
        }
        sb.append(str);
        sb.append("\n");
        sb.append(HistoryPresentation.time(version.getCapturedAt()));
        sb.append(" · ");
        sb.append(version.getFileCount());
        sb.append(" 个文件");
        radioButton.setText(sb.toString());
        radioButton.setTextSize(14.0f);
        radioButton.setChecked(version.getSnapshotId().equals(this.selected));
        radioButton.setMinHeight(dp(72));
        this.body.addView(radioButton);
        radioButton.setOnClickListener(new View.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda53
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                MainActivity.this.m147xa2575931(version, view);
            }
        });
    }

    /* renamed from: lambda$versionChoice$66$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m147xa2575931(AndroidSyncFacade.Version version, View view) {
        this.selected = version.getSnapshotId();
        render();
    }

    /* JADX INFO: Access modifiers changed from: private */
    private void completeReadyComparison() {
        final AndroidSyncFacade owner = this.facade;
        final AndroidSyncFacade.Inspection observed = this.inspection;
        if (owner == null || observed == null || !this.cloudVerified || !observed.getCanCompleteWithoutTransfer()) {
            return;
        }
        work("正在确认同步基线…", () -> {
            if (owner != this.facade) {
                throw new IOException("连接已变化，请重新比较后选择。");
            }
            owner.completeOnboarding(observed);
            SyncRuntime.setOnboardingProgress(this, this.connection, OnboardingProgress.COMPLETE);
            return null;
        }, ignored -> {
            this.progress = OnboardingProgress.COMPLETE;
            this.onboardingActive = false;
            this.page = SyncPage.HOME;
            this.notice = "配置完成。";
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    private void completeLocalProgress() {
        final AndroidSyncFacade owner = this.facade;
        final AndroidSyncFacade.Inspection observed = this.inspection;
        if (owner == null || observed == null || !this.cloudVerified || !observed.getCanKeepLocalProgress()) {
            return;
        }
        work("正在重新核验本机进度与云端基线…", () -> {
            if (owner != this.facade) {
                throw new IOException("连接已变化，请重新比较后选择。");
            }
            owner.completeOnboarding(observed);
            SyncRuntime.setOnboardingProgress(this, this.connection, OnboardingProgress.COMPLETE);
            return null;
        }, ignored -> {
            this.progress = OnboardingProgress.COMPLETE;
            this.onboardingActive = false;
            this.page = SyncPage.HOME;
            this.notice = "配置完成。";
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void completeEmpty() {
        if (!AndroidUiFlowPolicy.canFinishEmpty(kind() == null ? null : kind().name(), this.cloudVerified,
                this.inspection != null && this.inspection.getNeedsRestoreRecovery(),
                this.inspection != null && this.inspection.isRetryablePending(),
                this.page == SyncPage.COMPARISON && this.onboardingActive, this.busy, this.connection != null)) return;
        work("正在完成配置…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda33
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m29xb53dc5d7();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda34
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m30x2ab7ec18(obj);
            }
        });
    }

    /* renamed from: lambda$completeEmpty$67$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m29xb53dc5d7() throws Exception {
        readComparison();
        if (!showEmptyCompletion() || !EmptySetupCompletion.canFinish(kind(), null, this.page == SyncPage.COMPARISON && this.onboardingActive, false, this.connection != null)) {
            throw new IOException("存档已变化，请重新比较后选择。");
        }
        sync().completeOnboarding(this.inspection);
        SyncRuntime.setOnboardingProgress(this, this.connection, OnboardingProgress.COMPLETE);
        return null;
    }

    /* renamed from: lambda$completeEmpty$68$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m30x2ab7ec18(Object obj) throws Exception {
        this.progress = OnboardingProgress.COMPLETE;
        this.onboardingActive = false;
        this.page = SyncPage.HOME;
        this.notice = "配置完成";
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void postpone() {
        work("正在保留当前选择…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda108
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m56lambda$postpone$69$iogithubxyz1001pdsyncandroidMainActivity();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda109
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m57lambda$postpone$70$iogithubxyz1001pdsyncandroidMainActivity(obj);
            }
        });
    }

    /* renamed from: lambda$postpone$69$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m56lambda$postpone$69$iogithubxyz1001pdsyncandroidMainActivity() throws Exception {
        SecureConnectionStore.Connection connection = this.connection;
        if (connection != null) {
            SyncRuntime.setOnboardingProgress(this, connection, OnboardingProgress.DEFERRED);
        }
        AutoSyncService.cancelForConnectionChange(this);
        return null;
    }

    /* renamed from: lambda$postpone$70$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m57lambda$postpone$70$iogithubxyz1001pdsyncandroidMainActivity(Object obj) throws Exception {
        this.progress = OnboardingProgress.DEFERRED;
        this.onboardingActive = false;
        this.page = SyncPage.HOME;
        this.notice = "双方进度已保留，自动上传暂停。之后点击上传或恢复可继续比较。";
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestUpload() {
        if (this.connection == null) {
            navigate(SyncPage.CONNECTION);
            return;
        }
        if (this.progress != OnboardingProgress.COMPLETE || this.inspection == null || !this.cloudVerified) {
            this.onboardingActive = true;
            refreshComparison(SyncPage.COMPARISON);
        } else {
            upload();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void upload() {
        final AndroidSyncFacade.Inspection inspection = this.inspection;
        if (inspection == null) {
            refreshComparison(SyncPage.COMPARISON);
        } else {
            operation("正在上传本机存档…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda160
                @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
                public final Object run() throws Exception {
                    return MainActivity.this.m146lambda$upload$71$iogithubxyz1001pdsyncandroidMainActivity(inspection);
                }
            }, "本机存档已上传并核验。");
        }
    }

    /* renamed from: lambda$upload$71$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m146lambda$upload$71$iogithubxyz1001pdsyncandroidMainActivity(AndroidSyncFacade.Inspection inspection) throws Exception {
        return sync().upload(inspection);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void adopt() {
        AndroidSyncFacade.Inspection inspection = this.inspection;
        if (inspection == null || !inspection.getCanAdoptRemote()) {
            return;
        }
        final AndroidSyncFacade.Inspection inspection2 = this.inspection;
        operation("正在关联相同存档…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda29
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m15lambda$adopt$72$iogithubxyz1001pdsyncandroidMainActivity(inspection2);
            }
        }, "已关联本机与云端相同内容。");
    }

    /* renamed from: lambda$adopt$72$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m15lambda$adopt$72$iogithubxyz1001pdsyncandroidMainActivity(AndroidSyncFacade.Inspection inspection) throws Exception {
        return sync().adoptIdentical(inspection);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestRestore() {
        if (!allowRestoreAction()) return;
        if (this.connection == null) {
            navigate(SyncPage.CONNECTION);
            return;
        }
        if (this.inspection == null || !this.cloudVerified || (this.progress != OnboardingProgress.COMPLETE && this.page == SyncPage.HOME)) {
            this.onboardingActive = true;
            refreshComparison(SyncPage.COMPARISON);
            return;
        }
        if (kind() == OnboardingKind.BRANCHED) {
            navigate(SyncPage.CONFLICT);
            return;
        }
        final String str = this.selected;
        if (str == null) {
            this.notice = "请先选择云端存档。";
            render();
            return;
        }
        final AndroidSyncFacade.Inspection inspection = this.inspection;
        SyncHistory syncHistory = this.history;
        if (syncHistory != null && syncHistory.getCurrentProtected()) {
            operation("正在校验、备份并恢复所选存档…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda13
                @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
                public final Object run() throws Exception {
                    return MainActivity.this.m141x92df934d(inspection, str);
                }
            }, "已恢复并核对游戏存档。");
            return;
        }
        showConfirmation("恢复存档", "所选存档：" + selectedName() + "\n\n将先备份，再替换本机存档；所选存档中没有的进度会移除。\n请退出游戏，并暂停其他存档同步。", "覆盖本机", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda14
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m143x7dd3dfcf(inspection, str);
            }
        }, this::previewMerge);
    }

    /* renamed from: lambda$requestRestore$73$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m141x92df934d(AndroidSyncFacade.Inspection inspection, String str) throws Exception {
        requireRestoreSupport();
        return sync().restoreSelected(inspection, str, false);
    }

    /* renamed from: lambda$requestRestore$74$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m142x859b98e(AndroidSyncFacade.Inspection inspection, String str) throws Exception {
        requireRestoreSupport();
        return sync().restoreSelected(inspection, str, true);
    }

    /* renamed from: lambda$requestRestore$75$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m143x7dd3dfcf(final AndroidSyncFacade.Inspection inspection, final String str) {
        operation("正在校验、备份并恢复所选存档…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda91
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m142x859b98e(inspection, str);
            }
        }, "已恢复并核对游戏存档。");
    }

    private void renderConflict() {
        heading("存档发生冲突");
        text("请选择要继续的进度。");
        if (this.inspection == null || kind() != OnboardingKind.BRANCHED) {
            text("分支已变化，请重新比较。");
            foot("重新比较", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda143
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m83x9a039aa7();
                }
            });
            return;
        }
        boolean z = false;
        for (AndroidSyncFacade.Version version : this.inspection.getRemoteVersions()) {
            versionChoice(version);
            if (version.getSnapshotId().equals(this.selected)) {
                z = true;
            }
        }
        SyncHistory syncHistory = this.history;
        if (syncHistory != null && !syncHistory.getCurrentProtected()) {
            text("本机还有未同步进度。可以先上传本机，保留两份，再选择要继续的进度。");
            button("上传本机，保留两份", this::upload);
        }
        foot("取消", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda144
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m84xf7dc0e8();
            }
        });
        foot("覆盖为所选存档", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda145
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m87x12f15700();
            }
        }).setEnabled(z && restoreSupported());
        foot("合并", this::previewMerge).setEnabled(restoreSupported());
        if (!restoreSupported()) text("当前 Root 后端不能恢复或合并写入；可以保留双方或手动上传本机。");
    }

    /* renamed from: lambda$renderConflict$76$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m83x9a039aa7() {
        refreshComparison(SyncPage.COMPARISON);
    }

    /* renamed from: lambda$renderConflict$77$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m84xf7dc0e8() {
        navigate(this.onboardingActive ? SyncPage.COMPARISON : SyncPage.HOME);
    }

    /* renamed from: lambda$renderConflict$80$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m87x12f15700() {
        if (!allowRestoreAction()) return;
        final String str = this.selected;
        if (str == null) {
            return;
        }
        final AndroidSyncFacade.Inspection inspection = this.inspection;
        showConfirmation("恢复存档", "所选存档：" + selectedName() + "\n\n将先备份，再替换本机存档；所选存档中没有的进度会移除。", "覆盖本机", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda159
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m86xfa720d6a(inspection, str);
            }
        }, this::previewMerge);
    }

    /* renamed from: lambda$renderConflict$78$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m85x84f7e729(AndroidSyncFacade.Inspection inspection, String str) throws Exception {
        requireRestoreSupport();
        return sync().resolveConflict(inspection, str);
    }

    /* renamed from: lambda$renderConflict$79$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m86xfa720d6a(final AndroidSyncFacade.Inspection inspection, final String str) {
        operation("正在验证并发布分支选择…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda102
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m85x84f7e729(inspection, str);
            }
        }, "所选分支已写入游戏并发布。");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void previewMerge() {
        if (!allowRestoreAction()) return;
        final AndroidSyncFacade.Inspection inspection = this.inspection;
        if (inspection == null) {
            refreshComparison(SyncPage.COMPARISON);
        } else {
            work("正在检查共同基线、单局身份与六槽容量…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda40
                @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
                public final Object run() throws Exception {
                    return MainActivity.this.m60x842ac139(inspection);
                }
            }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda41
                @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
                public final void accept(Object obj) throws Exception {
                    MainActivity.this.m63xe49933fc(inspection, (AndroidSyncFacade.MergePreview) obj);
                }
            });
        }
    }

    /* renamed from: lambda$previewMerge$81$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.MergePreview m60x842ac139(AndroidSyncFacade.Inspection inspection) throws Exception {
        return sync().previewMerge(inspection);
    }

    /* renamed from: lambda$previewMerge$84$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m63xe49933fc(final AndroidSyncFacade.Inspection inspection, AndroidSyncFacade.MergePreview mergePreview) throws Exception {
        if (!mergePreview.isAllowed()) {
            this.notice = "无法安全合并，请选择要继续的进度。";
            this.details = safeText(mergePreview.getMessage());
            return;
        }
        StringBuilder sb = new StringBuilder("合并后使用 " + mergePreview.getSlotCount() + " 个槽位（最多 6 个）。\n");
        for (String str : mergePreview.getDecisions()) {
            sb.append("\n• ");
            sb.append(safeText(str));
        }
        sb.append("\n\n先备份再替换本机，并发布合并版本。原始快照保留。");
        showConfirmation("合并存档", sb.toString(), "合并并上传", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda10
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m62x6f1f0dbb(inspection);
            }
        }, null);
    }

    /* renamed from: lambda$previewMerge$82$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m61xf9a4e77a(AndroidSyncFacade.Inspection inspection) throws Exception {
        requireRestoreSupport();
        return sync().mergeConflict(inspection);
    }

    /* renamed from: lambda$previewMerge$83$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m62x6f1f0dbb(final AndroidSyncFacade.Inspection inspection) {
        operation("正在应用并核验安全合并…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda118
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m61xf9a4e77a(inspection);
            }
        }, "合并版本已写入游戏并发布。");
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class OperationOutcome {
        String refreshFailure;
        boolean requiresBridgeUpgrade;
        AndroidSyncFacade.OperationResult result;

        private OperationOutcome() {
        }
    }

    private void operation(String str, final Task<AndroidSyncFacade.OperationResult> task, final String str2) {
        this.confirmedAction = null;
        this.confirmationMerge = null;
        work(str, new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda36
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m54xc977a20(task);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda37
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m55x8211a061(str2, (MainActivity.OperationOutcome) obj);
            }
        });
    }

    /* renamed from: lambda$operation$85$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ OperationOutcome m54xc977a20(Task task) throws Exception {
        SyncHistory syncHistory;
        OperationOutcome operationOutcome = new OperationOutcome();
        operationOutcome.result = (AndroidSyncFacade.OperationResult) task.run();
        GamePreparationService.PreparedPair preparedPair = this.prepared;
        if (preparedPair != null && InstallProgressPolicy.requiresRebindAfterLegacyRecovery(preparedPair.legacyUnboundHelper, hasAnyRestoreJournal())) {
            operationOutcome.requiresBridgeUpgrade = true;
            SyncRuntime.setOnboardingProgress(this, this.connection, OnboardingProgress.PENDING);
            return operationOutcome;
        }
        try {
            if (!readyForSaves() || this.readinessState.restorePending) {
                readInstallationReadiness();
                if (this.rootGame != null && !hasAnyRestoreJournal()) checkRootAccess();
            }
        } catch (Exception e) {
            this.cloudVerified = false;
            reportError(e);
            operationOutcome.refreshFailure = safeText(e.getMessage());
        }
        if (!readyForSaves()) {
            this.cloudVerified = false;
            operationOutcome.refreshFailure = this.readinessState.statusDescription();
            return operationOutcome;
        }
        readComparison();
        if (operationOutcome.result.isConflict() || kind() == OnboardingKind.BRANCHED) {
            SyncRuntime.setOnboardingProgress(this, this.connection, OnboardingProgress.PENDING);
        }
        if (this.cloudVerified && kind() == OnboardingKind.IDENTICAL && (syncHistory = this.history) != null && syncHistory.getBaselineCommit() != null && !operationOutcome.result.isPending() && !operationOutcome.result.isConflict()) {
            sync().completeOnboarding(this.inspection);
            SyncRuntime.setOnboardingProgress(this, this.connection, OnboardingProgress.COMPLETE);
            return operationOutcome;
        }
        return operationOutcome;
    }

    /* renamed from: lambda$operation$86$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m55x8211a061(String str, OperationOutcome operationOutcome) throws Exception {
        if (operationOutcome.requiresBridgeUpgrade) {
            this.facade = null;
            this.inspection = null;
            this.history = null;
            this.cloudVerified = false;
            this.readinessState = InstallationReadiness.checking(this.prepared != null, this.installer.hasActiveRequest());
            this.progress = OnboardingProgress.PENDING;
            this.onboardingActive = true;
            this.page = SyncPage.DISCOVERY;
            this.notice = str + " 旧版恢复记录已处理，请重新准备带内置存档桥的游戏后继续。";
            this.details = "已保留原有历史与待上传版本。重新准备会核对原始 APK 与本机签名；若需更改游戏 UID，仍须先警告并由你确认卸载。";
            return;
        }
        OnboardingProgress onboardingProgress = SyncRuntime.onboardingProgress(this, this.connection);
        this.progress = onboardingProgress;
        if (onboardingProgress == OnboardingProgress.COMPLETE) {
            this.onboardingActive = false;
        }
        if (operationOutcome.result.isConflict() || kind() == OnboardingKind.BRANCHED) {
            this.notice = "发现存档冲突，分支已保留，请选择要继续的进度。";
            this.page = SyncPage.CONFLICT;
        } else if (operationOutcome.result.isPending()) {
            this.notice = "操作已保留待上传版本，请重试上传。";
            this.page = SyncPage.HOME;
        } else {
            this.notice = str;
            this.page = this.onboardingActive ? SyncPage.COMPARISON : SyncPage.HOME;
        }
        if (operationOutcome.refreshFailure != null) {
            this.notice += " 最新状态尚未完成核验，请重新检查。";
            this.details = operationOutcome.refreshFailure;
        }
    }

    private void showConfirmation(String str, String str2, String str3, Runnable runnable, Runnable runnable2) {
        this.confirmationReturn = this.page == SyncPage.CONFIRM ? this.confirmationReturn : this.page;
        this.confirmationTitle = str;
        this.confirmationText = str2;
        this.confirmationButton = str3;
        this.confirmedAction = runnable;
        this.confirmationMerge = runnable2;
        navigate(SyncPage.CONFIRM);
    }

    private void renderConfirmation() {
        String str = this.confirmationTitle;
        if (str == null) {
            str = "操作前确认";
        }
        heading(str);
        String str2 = this.confirmationText;
        if (str2 == null) {
            str2 = "确认已失效，请重新选择。";
        }
        text(str2);
        foot("取消", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda23
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.cancelConfirmation();
            }
        });
        String str3 = this.confirmationButton;
        if (str3 == null) {
            str3 = "确认继续";
        }
        foot(str3, new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda24
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m82x868f6244();
            }
        }).setEnabled(this.confirmedAction != null);
        Runnable runnable = this.confirmationMerge;
        if (runnable != null) {
            foot("合并", runnable);
        }
    }

    /* renamed from: lambda$renderConfirmation$87$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m82x868f6244() {
        Runnable runnable = this.confirmedAction;
        if (runnable != null) {
            runnable.run();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelConfirmation() {
        this.confirmedAction = null;
        this.confirmationMerge = null;
        this.notice = "已取消选择，双方存档未改变。";
        SyncPage syncPage = this.confirmationReturn;
        if (syncPage == null) {
            syncPage = SyncPage.HOME;
        }
        navigate(syncPage);
    }

    private String selectedName() {
        SyncHistory syncHistory = this.history;
        if (syncHistory != null) {
            for (AppendCommit appendCommit : syncHistory.getCloud()) {
                if (appendCommit.getSnapshot().getId().equals(this.selected)) {
                    String str = this.history.getAliases().get(this.selected);
                    if (str != null && !str.trim().isEmpty()) {
                        return str;
                    }
                    DirectorySnapshotMetadata directoryMetadata = appendCommit.getSnapshot().getDirectoryMetadata();
                    if (directoryMetadata != null && !directoryMetadata.getDisplayName().trim().isEmpty()) {
                        return directoryMetadata.getDisplayName();
                    }
                }
            }
        }
        String str2 = this.selected;
        return str2 == null ? "尚未选择" : str2.substring(0, Math.min(8, str2.length()));
    }

    private void renderSettings() {
        String str;
        heading("设置");
        Switch r0 = new Switch(this);
        r0.setText("自动上传");
        r0.setChecked(automaticObservationSupported() && !SyncRuntime.isAutoUploadPaused(this));
        r0.setEnabled(automaticObservationSupported());
        this.body.addView(r0);
        r0.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda74
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                MainActivity.this.m128x5965f8ae(compoundButton, z);
            }
        });
        str = selectedGameLabel();
        text(str);
        button("更换游戏…", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda78
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m129xcee01eef();
            }
        });
        button("重新检查存档", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda79
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m130x445a4530();
            }
        });
        button("云端连接设置", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda80
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m131xb9d46b71();
            }
        });
        button("重新检查四步引导", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda81
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m132x2f4e91b2();
            }
        });
        if (selectedGamePackage() != null) {
            button("关联 PC 档案", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda82
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.editNamespace();
                }
            });
        }
        button("允许同步通知（可选）", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda83
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m133xa4c8b7f3();
            }
        });
        heading("恢复与待上传操作");
        if (restoreNeedsAttention()) {
            button("检查未完成恢复", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda84
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m135x8fbd0475();
                }
            }).setEnabled(restoreSupported());
            button("保留备份并放弃未完成操作", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda85
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m112xc8b22aac();
                }
            });
        }
        button("恢复中断前备份", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda86
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m116x9e9ac3b0();
            }
        }).setEnabled(backupRecoverySupported());
        if (!backupRecoverySupported()) text("当前访问方式不支持中断事务恢复。");
        AndroidSyncFacade.Inspection inspection2 = this.inspection;
        if (inspection2 != null && inspection2.isRetryablePending()) {
            button("检查并归档中断的待上传操作", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda75
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m120x74835cb4();
                }
            });
        }
        button("忘记已保存的连接信息", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda76
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m124xed71190d();
            }
        });
        foot("返回存档", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda77
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m125x62eb3f4e();
            }
        });
    }

    /* renamed from: lambda$renderSettings$90$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m128x5965f8ae(CompoundButton compoundButton, final boolean z) {
        work("正在保存自动上传设置…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda38
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m126xcb6c88d7(z);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda39
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m127x40e6af18(z, obj);
            }
        });
    }

    /* renamed from: lambda$renderSettings$88$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m126xcb6c88d7(boolean z) throws Exception {
        SyncRuntime.setAutoUploadPaused(this, !z);
        if (z) {
            return null;
        }
        AutoSyncService.cancelForConnectionChange(this);
        return null;
    }

    /* renamed from: lambda$renderSettings$89$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m127x40e6af18(boolean z, Object obj) throws Exception {
        this.notice = z ? "自动上传已开启；完成存档比较后生效。" : "自动上传已暂停。";
    }

    /* renamed from: lambda$renderSettings$91$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m129xcee01eef() {
        beginOnboarding(SyncPage.DISCOVERY);
    }

    /* renamed from: lambda$renderSettings$92$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m130x445a4530() {
        refreshComparison(SyncPage.HOME);
    }

    /* renamed from: lambda$renderSettings$93$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m131xb9d46b71() {
        this.draftLoaded = false;
        navigate(SyncPage.CONNECTION);
    }

    /* renamed from: lambda$renderSettings$94$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m132x2f4e91b2() {
        beginOnboarding(SyncPage.DISCOVERY);
    }

    /* renamed from: lambda$renderSettings$95$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m133xa4c8b7f3() {
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8103);
        } else {
            this.notice = "同步通知权限已允许。";
            render();
        }
    }

    /* renamed from: lambda$renderSettings$96$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m134x1a42de34() throws Exception {
        requireRestoreSupport();
        return sync().recoverRestore();
    }

    /* renamed from: lambda$renderSettings$97$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m135x8fbd0475() {
        operation("正在核对未完成恢复…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda107
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m134x1a42de34();
            }
        }, "恢复状态已核对。");
    }

    /* renamed from: lambda$renderSettings$100$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m112xc8b22aac() {
        showConfirmation("结束未完成恢复", "归档待恢复内容并重新读取当前游戏存档，不改写游戏文件。请先正常退出游戏，等待存档稳定。", "归档并重新检查", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda35
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m137x7ab150f7();
            }
        }, null);
    }

    /* renamed from: lambda$renderSettings$98$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m136x5372ab6() throws Exception {
        return sync().discardInterruptedRestore();
    }

    /* renamed from: lambda$renderSettings$99$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m137x7ab150f7() {
        operation("正在归档未完成恢复…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda2
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m136x5372ab6();
            }
        }, "已归档未完成恢复，游戏文件未被替换。");
    }

    /* renamed from: lambda$renderSettings$104$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m116x9e9ac3b0() {
        if (!backupRecoverySupported()) { this.notice = "当前访问方式不支持中断事务恢复。"; render(); return; }
        showConfirmation(this.rootGame != null ? "核对 Root 中断事务" : "恢复中断前的原存档",
                this.rootGame != null ? "核对所选游戏保留的事务与备份。已经提交的恢复只做核验和清理；未完成的替换按原记录回滚。请正常退出游戏，操作时不要重新进入。完成后仍需检查主程序的未完成恢复记录。"
                : "仅用于存档桥提示需要恢复的情况。将用存档桥保留的原始备份恢复中断前存档。请先正常退出游戏，恢复时不要重新进入游戏。完成后仍须检查主程序的未完成恢复记录。",
                this.rootGame != null ? "核对并恢复" : "恢复原始备份", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda16
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m115x29209d6f();
            }
        }, null);
    }

    /* renamed from: lambda$renderSettings$103$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m115x29209d6f() {
        work(this.rootGame != null ? "正在核对 Root 中断事务…" : "正在恢复存档桥中断前备份…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda27
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m113x3e2c50ed();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda28
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m114xb3a6772e((String) obj);
            }
        });
    }

    /* renamed from: lambda$renderSettings$101$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ String m113x3e2c50ed() throws Exception {
        GameAccessBackend backend = SyncRuntime.backend(this);
        if (!backend.capabilities().interruptedBackupRecovery) throw new IOException("当前访问方式不支持中断备份恢复。");
        String strRecoverInterruptedBackup = backend.recoverInterruptedBackup();
        readInstallationReadiness();
        return strRecoverInterruptedBackup;
    }

    /* renamed from: lambda$renderSettings$102$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m114xb3a6772e(String str) throws Exception {
        this.inspection = null;
        this.history = null;
        this.cloudVerified = false;
        this.page = SyncPage.SETTINGS;
        this.notice = (this.rootGame != null ? "Root 事务核对完成。" : "存档桥备份处理完成。") + this.readinessState.statusDescription();
        if (this.diagnosticReport.isEmpty()) {
            this.details = safeText(str);
        }
    }

    /* renamed from: lambda$renderSettings$108$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m120x74835cb4() {
        showConfirmation("结束待上传操作", "只移除待上传标记，历史保留，不删除云端内容。下次同步会重新采集当前档案。", "确认继续", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda152
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m119xff093673();
            }
        }, null);
    }

    /* renamed from: lambda$renderSettings$105$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m117x1414e9f1() throws Exception {
        sync().archivePending();
        return null;
    }

    /* renamed from: lambda$renderSettings$106$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m118x898f1032(Object obj) throws Exception {
        this.inspection = null;
        this.history = null;
        this.page = SyncPage.HOME;
        this.notice = "待上传版本已归档，请重新检查。";
    }

    /* renamed from: lambda$renderSettings$107$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m119xff093673() {
        work("正在归档待上传版本…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda148
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m117x1414e9f1();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda149
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m118x898f1032(obj);
            }
        });
    }

    /* renamed from: lambda$renderSettings$112$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m124xed71190d() {
        showConfirmation("忘记连接信息", "删除主程序保存的连接配置文件及旧版加密记录，不删除本机或云端存档，也不改动游戏签名密钥。", "忘记", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda126
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m123x77f6f2cc();
            }
        }, null);
    }

    /* renamed from: lambda$renderSettings$109$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m121xe9fd82f5() throws Exception {
        AutoSyncService.cancelForConnectionChange(this);
        this.secrets.forget();
        SyncRuntime.setConnection(null);
        return null;
    }

    /* renamed from: lambda$renderSettings$111$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m123x77f6f2cc() {
        work("正在删除保存的连接信息…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda150
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m121xe9fd82f5();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda151
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m122x27ccc8b(obj);
            }
        });
    }

    /* renamed from: lambda$renderSettings$110$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m122x27ccc8b(Object obj) throws Exception {
        this.connection = null;
        this.facade = null;
        this.inspection = null;
        this.history = null;
        this.cloudVerified = false;
        this.draftLoaded = false;
        this.draftPassword = "";
        this.testedDraft = null;
        this.connectionLoadError = "";
        this.progress = OnboardingProgress.PENDING;
        this.onboardingActive = true;
        this.page = SyncPage.CONNECTION;
        this.notice = "已忘记保存的连接信息。";
    }

    /* renamed from: lambda$renderSettings$113$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m125x62eb3f4e() {
        navigate(SyncPage.HOME);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void editNamespace() {
        LinearLayout linearLayoutColumn = column();
        final EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setHint("云端游戏标识");
        editText.setText(this.selectedNamespace);
        linearLayoutColumn.addView(editText);
        final EditText editText2 = new EditText(this);
        editText2.setSingleLine(true);
        editText2.setHint("档案名");
        SecureConnectionStore.Connection connection = this.connection;
        editText2.setText(connection == null ? "default" : connection.profile);
        linearLayoutColumn.addView(editText2);
        new AlertDialog.Builder(this).setTitle("关联同一游戏的 PC 档案").setView(linearLayoutColumn).setMessage("仅当你已确认两端是同一游戏、兼容存档格式时使用。官方破碎的已有空间通常为 shattered，档案通常为 default。留空游戏标识可恢复按来源隔离。").setNegativeButton("取消", (DialogInterface.OnClickListener) null).setPositiveButton("检查关联", new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda9
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.m47x6b6b32d0(editText, editText2, dialogInterface, i);
            }
        }).show();
    }

    /* renamed from: lambda$editNamespace$117$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m47x6b6b32d0(EditText editText, EditText editText2, DialogInterface dialogInterface, int i) {
        final String strTrim = editText.getText().toString().trim();
        final String strTrim2 = editText2.getText().toString().trim();
        StringBuilder sb = new StringBuilder("云端游戏标识：");
        sb.append(strTrim.isEmpty() ? "按游戏来源隔离" : strTrim);
        sb.append("\n档案：");
        sb.append(strTrim2);
        sb.append("\n\n关联其他改版可能把不兼容存档放入同一空间。此设置不会证明格式兼容，也不会绕过恢复校验。会暂停自动上传，重新测试连接并明确比较双方存档。");
        showConfirmation("确认云端档案关联", sb.toString(), "确认关联并重新比较", new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda95
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m46xf5f10c8f(strTrim2, strTrim);
            }
        }, null);
    }

    /* renamed from: lambda$editNamespace$116$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m46xf5f10c8f(final String str, final String str2) {
        work("正在保存云端关联…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda42
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m44xafcc00d(str, str2);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda43
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m45x8076e64e(str, obj);
            }
        });
    }

    /* renamed from: lambda$editNamespace$114$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m44xafcc00d(String str, String str2) throws Exception {
        SecureConnectionStore.Connection connection = this.connection;
        String str3 = connection == null ? "https://example.invalid/dav" : connection.endpoint;
        SecureConnectionStore.Connection connection2 = this.connection;
        String str4 = connection2 == null ? "" : connection2.username;
        SecureConnectionStore.Connection connection3 = this.connection;
        new SecureConnectionStore.Connection(str3, str4, connection3 != null ? connection3.password : "", str).validate();
        SyncRuntime.setNamespace(this, str2);
        this.selectedNamespace = SyncRuntime.currentNamespace(this);
        SecureConnectionStore.Connection connection4 = this.connection;
        if (connection4 == null) {
            return null;
        }
        SyncRuntime.setOnboardingProgress(this, connection4, OnboardingProgress.PENDING);
        return null;
    }

    /* renamed from: lambda$editNamespace$115$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m45x8076e64e(String str, Object obj) throws Exception {
        this.facade = null;
        this.inspection = null;
        this.history = null;
        this.selected = null;
        this.cloudVerified = false;
        this.progress = OnboardingProgress.PENDING;
        this.onboardingActive = true;
        loadDraft();
        this.draftProfile = str;
        this.page = SyncPage.CONNECTION;
        this.notice = "请测试连接，再比较存档。";
    }

    private void beginOnboarding(final SyncPage syncPage) {
        work("正在重新检查配置…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda103
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m19x95b322c4();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda104
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m20xb2d4905(syncPage, obj);
            }
        });
    }

    /* renamed from: lambda$beginOnboarding$118$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m19x95b322c4() throws Exception {
        AutoSyncService.cancelForConnectionChange(this);
        SecureConnectionStore.Connection connection = this.connection;
        if (connection == null) {
            return null;
        }
        SyncRuntime.setOnboardingProgress(this, connection, OnboardingProgress.PENDING);
        return null;
    }

    /* renamed from: lambda$beginOnboarding$119$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m20xb2d4905(SyncPage syncPage, Object obj) throws Exception {
        this.progress = OnboardingProgress.PENDING;
        this.onboardingActive = true;
        this.localPreview = null;
        this.previewAttempted = false;
        this.draftLoaded = false;
        this.page = syncPage;
        this.notice = "请检查所选游戏。";
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void rename(final String str) {
        if (str == null) {
            editCurrent();
            return;
        }
        final EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setHint("本机显示名称");
        SyncHistory syncHistory = this.history;
        editText.setText(syncHistory == null ? "" : syncHistory.getAliases().get(str));
        new AlertDialog.Builder(this).setTitle("历史存档名称").setMessage("保存在此设备，不改写已发布的历史。").setView(editText).setNegativeButton("取消", (DialogInterface.OnClickListener) null).setPositiveButton("保存", new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda57
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.m74lambda$rename$122$iogithubxyz1001pdsyncandroidMainActivity(str, editText, dialogInterface, i);
            }
        }).show();
    }

    /* renamed from: lambda$rename$120$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m72lambda$rename$120$iogithubxyz1001pdsyncandroidMainActivity(String str, EditText editText) throws Exception {
        sync().renameHistory(str, editText.getText().toString());
        // Renaming is an explicit update. Refresh the complete observation, never only its mirror.
        readComparison();
        return null;
    }

    /* renamed from: lambda$rename$121$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m73lambda$rename$121$iogithubxyz1001pdsyncandroidMainActivity(Object obj) throws Exception {
        this.notice = "名称已保存。";
    }

    /* renamed from: lambda$rename$122$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m74lambda$rename$122$iogithubxyz1001pdsyncandroidMainActivity(final String str, final EditText editText, DialogInterface dialogInterface, int i) {
        work("正在保存名称…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda11
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m72lambda$rename$120$iogithubxyz1001pdsyncandroidMainActivity(str, editText);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda12
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m73lambda$rename$121$iogithubxyz1001pdsyncandroidMainActivity(obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void editCurrent() {
        if (this.inspection == null || !this.cloudVerified) {
            refreshComparison(SyncPage.HOME);
            return;
        }
        final EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setHint("存档名称");
        SyncHistory syncHistory = this.history;
        editText.setText(syncHistory == null ? "" : syncHistory.getCurrentName());
        final AndroidSyncFacade.Inspection inspection = this.inspection;
        new AlertDialog.Builder(this).setTitle("存档名称与单局身份").setView(editText).setMessage("存档在同步器未观察期间发生变化时，需要确认是否仍为原来的一局。不能确定时请保留待确认。").setNegativeButton("关闭", (DialogInterface.OnClickListener) null).setNeutralButton("确认单局身份", new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda153
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.m40xb1707a89(dialogInterface, i);
            }
        }).setPositiveButton("保存名称", new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda154
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.m43x11deed4c(inspection, editText, dialogInterface, i);
            }
        }).show();
    }

    /* renamed from: lambda$editCurrent$123$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m40xb1707a89(DialogInterface dialogInterface, int i) {
        chooseRunIdentity();
    }

    /* renamed from: lambda$editCurrent$124$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m41x26eaa0ca(AndroidSyncFacade.Inspection inspection, EditText editText) throws Exception {
        sync().renameCurrent(inspection, editText.getText().toString());
        readComparison();
        return null;
    }

    /* renamed from: lambda$editCurrent$125$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m42x9c64c70b(Object obj) throws Exception {
        this.notice = "名称已保存。";
    }

    /* renamed from: lambda$editCurrent$126$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m43x11deed4c(final AndroidSyncFacade.Inspection inspection, final EditText editText, DialogInterface dialogInterface, int i) {
        work("正在保存名称…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda54
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m41x26eaa0ca(inspection, editText);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda55
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m42x9c64c70b(obj);
            }
        });
    }

    private void chooseRunIdentity() {
        SyncHistory syncHistory;
        if (this.inspection == null || (syncHistory = this.history) == null || syncHistory.getSummary() == null) {
            return;
        }
        final String[] strArr = (String[]) this.history.getSummary().getSlots().keySet().toArray(new String[0]);
        if (strArr.length != 0) {
            new AlertDialog.Builder(this).setTitle("选择需要确认身份的槽位").setItems(strArr, new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda30
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int i) {
                    MainActivity.this.m28x9a90bca9(strArr, dialogInterface, i);
                }
            }).setNegativeButton("取消", (DialogInterface.OnClickListener) null).show();
        } else {
            this.notice = "当前没有可确认身份的游戏槽位。";
            render();
        }
    }

    /* renamed from: lambda$chooseRunIdentity$131$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m28x9a90bca9(String[] strArr, DialogInterface dialogInterface, int i) {
        final String str = strArr[i];
        final AndroidSyncFacade.Inspection inspection = this.inspection;
        new AlertDialog.Builder(this).setTitle("确认 " + str + " 的单局身份").setMessage("只有你确认当前槽位与同步基线是同一局游戏时，才选“同一局继续”。槽位或种子相同不足以推断身份。不确定请取消，保留待确认。").setNegativeButton("取消", (DialogInterface.OnClickListener) null).setNeutralButton("这是新的一局", new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda25
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface2, int i2) {
                MainActivity.this.m25x971d2691(inspection, str, dialogInterface2, i2);
            }
        }).setPositiveButton("同一局继续", new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda26
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface2, int i2) {
                MainActivity.this.m27x25169668(inspection, str, dialogInterface2, i2);
            }
        }).show();
    }

    /* renamed from: lambda$chooseRunIdentity$127$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m24x21a30050(AndroidSyncFacade.Inspection inspection, String str) throws Exception {
        return sync().confirmRunIdentity(inspection, str, false);
    }

    /* renamed from: lambda$chooseRunIdentity$128$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m25x971d2691(final AndroidSyncFacade.Inspection inspection, final String str, DialogInterface dialogInterface, int i) {
        operation("正在记录新局身份…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda31
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m24x21a30050(inspection, str);
            }
        }, "已记录新局身份。");
    }

    /* renamed from: lambda$chooseRunIdentity$129$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m26xc974cd2(AndroidSyncFacade.Inspection inspection, String str) throws Exception {
        return sync().confirmRunIdentity(inspection, str, true);
    }

    /* renamed from: lambda$chooseRunIdentity$130$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m27x25169668(final AndroidSyncFacade.Inspection inspection, final String str, DialogInterface dialogInterface, int i) {
        operation("正在记录单局身份…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda161
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m26xc974cd2(inspection, str);
            }
        }, "已记录身份判断。");
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class ActivationNext {
        final Intent launch;
        final GamePreparationService.PreparedPair pair;

        ActivationNext(GamePreparationService.PreparedPair preparedPair, Intent intent) {
            this.pair = preparedPair;
            this.launch = intent;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void activateHelper() {
        if (this.rootGame != null) { this.notice = "Root 模式不准备或安装游戏；请先明确切换为内置存档桥。"; render(); return; }
        if (this.prepared == null || !this.prepared.embeddedBridge() || !HelperActivationPolicy.canOffer(this.readinessState.withSystemRequest(this.installer.hasActiveRequest()), this.bridgeAccessCode, false)) {
            return;
        }
        work("正在核对游戏内置存档桥激活入口，现有游戏保持不变…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda21
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m12xbb2f51f5();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda22
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m13x30a97836((MainActivity.ActivationNext) obj);
            }
        });
    }

    /* renamed from: lambda$activateHelper$134$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ ActivationNext m12xbb2f51f5() throws Exception {
        if (SyncRuntime.rootMode(this)) throw new IOException("Root 模式不执行游戏准备、安装或内置桥激活。");
        this.installer.reconcile();
        if (this.installer.hasActiveRequest()) {
            throw new IOException("请先完成或取消当前系统安装请求。");
        }
        GamePreparationService.PreparedPair preparedPairFreshPair = this.installer.freshPair();
        if (this.installer.nextPackage(preparedPairFreshPair) != null) {
            throw new IOException("现有安装发生变化，请先重新检查安装状态。");
        }
        new BridgeClient(this, preparedPairFreshPair, null).targetGame();
        Intent intentInstalledIntent = HelperActivation.installedIntent(this, preparedPairFreshPair);
        return new ActivationNext(preparedPairFreshPair, intentInstalledIntent);
    }

    /* renamed from: lambda$activateHelper$135$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m13x30a97836(ActivationNext activationNext) throws Exception {
        this.prepared = activationNext.pair;
        SyncRuntime.setPreparedGame(this, activationNext.pair);
        this.readinessState = InstallationReadiness.checking(true, this.installer.hasActiveRequest());
        this.bridgeAccessCode = "NOT_CHECKED";
        this.localPreview = null;
        this.previewAttempted = false;
        this.inspection = null;
        this.history = null;
        this.cloudVerified = false;
        if (!this.resumed) {
            this.notice = "已核对内置存档桥入口。返回前台后请再次点击启用。";
            this.readinessRefreshPending = true;
        } else {
            this.notice = "即将打开游戏内置存档桥页面，请点击页面中的“返回同步器”。返回后仍需重新检查存档访问。";
            try {
                startActivityForResult(activationNext.launch, 8103);
            } catch (RuntimeException e) {
                throw new BridgeClient.Failure(BridgeDiagnostics.Stage.HELPER_ACTIVATION, "ACTIVATION_LAUNCH_DENIED", "系统未打开内置存档桥激活页面。请保留现有安装并复制诊断，不需要卸载游戏。", e);
            }
        }
    }

    private void readAfterHelperPreparation() {
        this.page = SyncPage.DISCOVERY;
        refreshReadiness();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void launchGameForInitialization() {
        if (this.rootGame != null) { this.notice = "Root 模式不准备或安装游戏；请先明确切换为内置存档桥。"; render(); return; }
        if (this.readinessState.withSystemRequest(this.installer.hasActiveRequest()).canInitializeGame()) {
            this.readinessState = InstallationReadiness.checking(true, this.installer.hasActiveRequest());
            work("正在核对首次启动所需的游戏与内置存档桥身份…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda97
                @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
                public final Object run() throws Exception {
                    return MainActivity.this.m50xa7c297e0();
                }
            }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda98
                @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
                public final void accept(Object obj) throws Exception {
                    MainActivity.this.m51x1d3cbe21((Intent) obj);
                }
            });
        }
    }

    /* renamed from: lambda$launchGameForInitialization$136$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Intent m50xa7c297e0() throws Exception {
        if (SyncRuntime.rootMode(this)) throw new IOException("Root 模式不执行游戏准备、安装或内置桥激活。");
        this.installer.reconcile();
        if (this.installer.hasActiveRequest()) {
            throw new IOException("请先完成或取消当前系统安装请求。");
        }
        GamePreparationService.PreparedPair preparedPairFreshPair = this.installer.freshPair();
        if (this.installer.nextPackage(preparedPairFreshPair) != null) {
            throw new IOException("已安装应用发生变化，请重新检查安装状态。");
        }
        if (hasAnyRestoreJournal()) {
            throw new IOException("存在未完成恢复。请先检查恢复记录，再启动游戏。");
        }
        BridgeClient bridgeClient = new BridgeClient(this, preparedPairFreshPair, null);
        bridgeClient.targetGame();
        try {
            bridgeClient.inspectReady();
        } catch (BridgeClient.Failure e) {
            if (!"FIRST_LAUNCH_NEEDED".equals(e.code)) {
                throw e;
            }
        }
        Intent launchIntentForPackage = getPackageManager().getLaunchIntentForPackage(preparedPairFreshPair.gamePackage);
        if (launchIntentForPackage == null) {
            throw new IOException("游戏启动入口不可用，请重新检查所选游戏。");
        }
        AutoSyncService.cancelForConnectionChange(this);
        SecureConnectionStore.Connection connection = this.connection;
        if (connection != null) {
            SyncRuntime.setOnboardingProgress(this, connection, OnboardingProgress.PENDING);
        }
        return launchIntentForPackage;
    }

    /* renamed from: lambda$launchGameForInitialization$137$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m51x1d3cbe21(Intent intent) throws Exception {
        this.readinessState = this.readinessState.withAccessCode("NOT_CHECKED");
        this.localPreview = null;
        this.previewAttempted = false;
        this.inspection = null;
        this.history = null;
        this.cloudVerified = false;
        this.progress = OnboardingProgress.PENDING;
        this.onboardingActive = true;
        this.notice = "已请求启动游戏。请正常退出后返回这里，等待存档稳定，再重新检查状态。";
        startActivity(intent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void launchGame() {
        final boolean zAllowsAutomatic = OnboardingGate.allowsAutomatic(this.progress, this.onboardingActive, this.cloudVerified);
        work("正在核对所选游戏…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda132
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m48xf90bdc3d();
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda133
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m49x6e86027e(zAllowsAutomatic, (Intent) obj);
            }
        });
    }

    /* renamed from: lambda$launchGame$138$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Intent m48xf90bdc3d() throws Exception {
        cancelControlledLaunch();
        if (hasAnyRestoreJournal()) {
            throw new IOException("存在未完成恢复。请先检查恢复记录，再启动游戏。");
        }
        if (selectedGamePackage() == null) {
            throw new IOException("请先选择游戏。");
        }
        GameAccessBackend backend = SyncRuntime.backend(this);
        if (this.rootGame != null) checkRootAccess(); else backend.inspectReady();
        backend.targetGame();
        Intent launchIntentForPackage = getPackageManager().getLaunchIntentForPackage(SyncRuntime.currentGamePackage(this));
        if (launchIntentForPackage != null) {
            if (this.connection != null && SyncRuntime.automaticAllowed(this, this.connection)) {
                synchronized (SyncRuntime.OPERATION_LOCK) {
                    try {
                        // Startup does not access Root saves; this deliberate launch can refresh them.
                        if (this.rootGame != null && !this.cloudVerified) readComparison();
                        if (this.rootGame == null || this.cloudVerified) {
                            this.controlledLaunchFacade = SyncRuntime.facade(this, this.connection);
                            this.controlledLaunchToken = this.controlledLaunchFacade.beginControlledSession();
                        }
                    } catch (Exception unavailableEvidence) {
                        // Optional identity evidence must not prevent playing a valid installed game.
                        cancelControlledLaunch();
                    }
                }
            }
            return launchIntentForPackage;
        }
        throw new IOException("游戏启动入口不可用，请完成安装。");
    }

    /* renamed from: lambda$launchGame$139$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m49x6e86027e(boolean z, Intent intent) throws Exception {
        boolean allowsAutomatic = this.rootGame == null ? z
                : OnboardingGate.allowsAutomatic(this.progress, this.onboardingActive, this.cloudVerified);
        this.inspection = null;
        this.cloudVerified = false;
        if (!automaticObservationSupported() || this.connection == null || !allowsAutomatic || SyncRuntime.isAutoUploadPaused(this)) {
            cancelControlledLaunch();
            this.notice = "游戏已请求启动。本次未开启退出监听，退出后可手动检查存档。";
        } else {
            SyncRuntime.setConnection(this.connection);
            try {
                AutoSyncService.arm(this, this.controlledLaunchToken);
                this.notice = "已开启本次退出后无冲突自动上传。";
            } catch (RuntimeException unused) {
                cancelControlledLaunch();
                this.notice = "系统未允许后台监听，退出游戏后请返回主页检查。";
            }
        }
        try { startActivity(intent); }
        catch (RuntimeException failure) { cancelControlledLaunch(); throw failure; }
    }

    private void cancelControlledLaunch() {
        if (this.controlledLaunchFacade != null && this.controlledLaunchToken != null)
            this.controlledLaunchFacade.cancelControlledSession(this.controlledLaunchToken);
        this.controlledLaunchFacade = null;
        this.controlledLaunchToken = null;
    }

    private boolean hasAnyRestoreJournal() throws IOException {
        return AndroidSyncFacade.hasUnresolvedRestore(new File(getFilesDir().getCanonicalFile(), "sync").toPath());
    }

    private void confirm(String str, String str2, String str3, final Runnable runnable) {
        new AlertDialog.Builder(this).setTitle(str).setMessage(str2).setNegativeButton("取消", (DialogInterface.OnClickListener) null).setPositiveButton(str3, new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda96
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.m31lambda$confirm$140$iogithubxyz1001pdsyncandroidMainActivity(runnable, dialogInterface, i);
            }
        }).show();
    }

    /* renamed from: lambda$confirm$140$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m31lambda$confirm$140$iogithubxyz1001pdsyncandroidMainActivity(Runnable runnable, DialogInterface dialogInterface, int i) {
        if (this.busy) {
            return;
        }
        runnable.run();
    }

    @Override // android.app.Activity
    protected void onActivityResult(int i, int i2, Intent intent) {
        super.onActivityResult(i, i2, intent);
        if (i == 8103) {
            this.readinessState = InstallationReadiness.checking(this.prepared != null, this.installer.hasActiveRequest());
            this.bridgeAccessCode = "NOT_CHECKED";
            this.localPreview = null;
            this.previewAttempted = false;
            this.cloudVerified = false;
            this.notice = i2 == -1 ? "游戏内置存档桥页面已返回，正在重新核验连接与存档访问。" : "游戏内置存档桥页面已关闭，正在重新检查；尚不能确认存档访问就绪。";
            requestReadinessRefresh();
            return;
        }
        if (i == 8101 && i2 == -1 && intent != null) {
            LinkedHashSet linkedHashSet = new LinkedHashSet();
            if (intent.getClipData() != null) {
                for (int i3 = 0; i3 < intent.getClipData().getItemCount(); i3++) {
                    linkedHashSet.add(intent.getClipData().getItemAt(i3).getUri());
                }
            }
            if (intent.getData() != null) {
                linkedHashSet.add(intent.getData());
            }
            if (linkedHashSet.isEmpty()) {
                return;
            }
            requestPrepare(null, new ArrayList(linkedHashSet));
            return;
        }
        if (i != 8102 || this.busy) {
            return;
        }
        refreshReadiness();
    }

    private String safeText(String str) {
        if (str == null) {
            return "请重新检查后重试。";
        }
        SecureConnectionStore.Connection connection = this.connection;
        if (connection != null && !connection.password.isEmpty()) {
            str = str.replace(this.connection.password, "[已隐藏]");
        }
        if (!this.draftPassword.isEmpty()) {
            str = str.replace(this.draftPassword, "[已隐藏]");
        }
        if (!this.officialPassword.isEmpty()) str = str.replace(this.officialPassword, "[已隐藏]");
        String strReplaceAll = str.replaceAll("(?i)(authorization|password|passwd|token)\\s*[:=]\\s*\\S+", "$1=[已隐藏]");
        if (strReplaceAll.length() <= 1500) {
            return strReplaceAll;
        }
        return strReplaceAll.substring(0, 1500) + "…";
    }

    private String safeError(Exception exc) {
        if (exc instanceof OfficialServiceRegistrationClient.RegistrationException)
            return ((OfficialServiceRegistrationClient.RegistrationException) exc).userMessage();
        InstallDiagnostics.Failure installFailure = InstallDiagnostics.find(exc);
        if (installFailure != null) return installFailure.userMessage();
        PreparationDiagnostics.Failure failureFind = PreparationDiagnostics.find(exc);
        if (failureFind != null) {
            return failureFind.userMessage();
        }
        SyncInspectionDiagnostics.Failure syncFailure = SyncInspectionDiagnostics.find(exc);
        if (syncFailure != null) return syncFailure.userMessage();
        String strSafeText = safeText(exc.getMessage());
        String lowerCase = strSafeText.toLowerCase(Locale.ROOT);
        if (lowerCase.contains("ssl") || lowerCase.contains("certificate path") || lowerCase.contains("handshake")) {
            return "HTTPS 证书或安全连接验证失败，请检查服务器证书。";
        }
        if (lowerCase.contains("split") || lowerCase.contains("分包")) {
            return "分包检查未通过，请选择包含基础包和全部必需分包的同一组 APK。";
        }
        if (lowerCase.contains("signature") || lowerCase.contains("signer") || lowerCase.contains("certificate")) {
            return "签名校验失败，安装已停止，请检查安装包来源与完整性。";
        }
        if (lowerCase.contains("unsupported") || lowerCase.contains("not supported") || lowerCase.contains("unrecognized")) {
            return "暂不支持所选游戏的存档格式或版本，请查看详细信息。";
        }
        if (lowerCase.contains("restore") && (lowerCase.contains("recover") || lowerCase.contains("interrupted"))) {
            return "存在未完成恢复，请在设置中检查恢复记录。";
        }
        if (lowerCase.contains("changed") || lowerCase.contains("compare again")) {
            return "本机或云端状态已变化，请重新比较后选择。";
        }
        if (lowerCase.contains("401") || lowerCase.contains("403") || lowerCase.contains("unauthorized")) {
            return "连接失败，请检查用户名、密码和访问权限。";
        }
        return NoviceCopy.feedback(strSafeText, "操作未完成，请查看详细信息后重试。");
    }
}
