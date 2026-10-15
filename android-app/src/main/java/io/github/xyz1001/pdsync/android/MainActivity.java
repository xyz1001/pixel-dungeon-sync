package io.github.xyz1001.pdsync.android;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
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
import android.widget.TextView;
import android.widget.Toast;
import io.github.xyz1001.pdsync.android.BridgeClient;
import io.github.xyz1001.pdsync.android.BridgeDiagnostics;
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
import pdsync.DiagnosticAction;
import pdsync.DiagnosticLog;
import pdsync.DiagnosticFailure;
import pdsync.DiagnosticDetails;
import pdsync.DiagnosticResult;
import pdsync.DiagnosticStage;
import pdsync.DiagnosticTrace;
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
import pdsync.presentation.HistoryPresentation;
import pdsync.presentation.SyncPage;

/* loaded from: classes.dex */
public final class MainActivity extends ComponentActivity implements SharedConnectionHost {
    private static final int ACTIVATE_HELPER = 8103;
    private static final int GREEN = -13474224;
    private static final int INK = -14469589;
    private static final int MUTED = -10129303;
    private static final int PICK_APK = 8101;
    private final AndroidDiagnosticExport diagnosticExport = AndroidDiagnosticExport.instance();
    private boolean diagnosticPickerReturn;
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
    private AndroidSyncFacade facade;
    private AndroidSyncFacade controlledLaunchFacade;
    private String controlledLaunchToken;
    private LinearLayout footer;
    private SyncHistory history;
    private int historyPage;
    private int sharedHomeRenderToken;
    private int sharedSettingsRenderToken;
    private int settingsConfirmationToken;
    private AlertDialog settingsDialog;
    private String explicitHomeSelection;
    private DiagnosticAction activeWorkAction = DiagnosticAction.OTHER;
    private AndroidSyncFacade.Inspection inspection;
    private InstallCoordinator installer;
    private SaveSummary localPreview;
    private GamePreparationService preparation;
    private GamePreparationService.PreparedPair prepared;
    private SelectedRootGame rootGame;
    private GameAccessBackend.Capabilities accessCapabilities;
    private final PathPreviewState pathPreviewState = new PathPreviewState();
    private boolean pathPreviewPosted;
    private int sharedPathRenderToken;
    private int sharedGameRenderToken;
    private boolean gameFilePickerOpen;
    private final ComparisonProofState comparisonProofState = new ComparisonProofState();
    private ComparisonProofState.Ticket activeSharedComparisonClaim;
    private int sharedComparisonRenderToken;
    private String comparisonDialogMessage = "";
    private String comparisonDialogDetails = "";
    private final Handler pathPreviewHandler = new Handler(Looper.getMainLooper());
    private final Runnable pathPreviewEntry = this::m111x434ac419;
    private boolean readinessRefreshPending;
    private boolean receiverRegistered;
    private AutomaticHistoryState automaticHistoryState = new AutomaticHistoryState();
    private SyncPage renderedPage;
    private volatile boolean resumed;
    private LinearLayout root;
    private ScrollView scroll;
    private SecureConnectionStore secrets;
    private String selected;
    private TextView status;
    private AndroidConnectionComposition.Session sharedConnectionSession;
    private SharedConnectionConfig pendingSharedCommit;
    private int sharedCommitSerial;
    private boolean sharedReloadPending;
    private boolean sharedAppreciationOnly;
    private RetainedConnectionUi retainedConnectionUi;
    private OnBackPressedCallback navigationBack;

    /** Memory-only rotation state. Credentials and pending auth never enter saved instance state. */
    private static final class RetainedConnectionUi {
        final boolean onboarding, draftLoaded, appreciationOnly;
        final SyncPage page;
        final String profile;
        final AndroidConnectionComposition.Session session;
        final SharedConnectionConfig pendingCommit;
        final AutomaticHistoryState automaticHistory;
        RetainedConnectionUi(MainActivity activity) {
            automaticHistory = activity.automaticHistoryState;
            onboarding = activity.onboardingActive;
            draftLoaded = activity.draftLoaded;
            page = activity.page;
            profile = activity.draftProfile;
            session = activity.sharedConnectionSession;
            pendingCommit = activity.pendingSharedCommit;
            appreciationOnly = activity.sharedAppreciationOnly;
        }
    }

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
    private String draftProfile = "default";
    private final InstalledAppPickerModel.State installedPickerState = new InstalledAppPickerModel.State();
    private String connectionLoadError = "";
    private final BroadcastReceiver installChanged = new BroadcastReceiver() { // from class: io.github.xyz1001.pdsync.android.MainActivity.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (AutoSyncService.HISTORY_CHANGED.equals(intent.getAction())) {
                MainActivity.this.drainAutomaticHistoryRefresh();
            } else {
                MainActivity.this.requestReadinessRefresh();
            }
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
        this.navigationBack = new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { MainActivity.this.onBackPressed(); }
        };
        getOnBackPressedDispatcher().addCallback(this, this.navigationBack);
        AndroidDiagnosticLogging.configure(this);
        diagnosticExport.attach(this);
        if (bundle != null) {
            diagnosticExport.restorePicker(bundle.getBoolean("diagnostics.picker", false));
            diagnosticPickerReturn = bundle.getBoolean("diagnostics.return", false);
        }
        getWindow().addFlags(8192);
        this.preparation = new GamePreparationService(this);
        this.installer = new InstallCoordinator(this);
        this.secrets = new SecureConnectionStore(this);
        Object retained = getLastCustomNonConfigurationInstance();
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
            this.explicitHomeSelection = bundle.getString("home.explicitSelection");
            this.historyPage = bundle.getInt("historyPage", 0);
            this.installedPickerState.setQuery(bundle.getString("installedPicker.query", ""));
            this.installedPickerState.setShowAll(bundle.getBoolean("installedPicker.showAll", false));
        }
        if (this.retainedConnectionUi != null) restoreConnectionUi(this.retainedConnectionUi);
        render();
    }

    @Override public Object onRetainCustomNonConfigurationInstance() {
        if (this.sharedConnectionSession == null && this.automaticHistoryState.outcome == null
                && !this.automaticHistoryState.inFlight) return null;
        if (this.sharedConnectionSession != null) this.sharedConnectionSession.getController().detach();
        return new RetainedConnectionUi(this);
    }

    private void restoreConnectionUi(RetainedConnectionUi retained) {
        this.automaticHistoryState = retained.automaticHistory;
        this.onboardingActive = retained.onboarding;
        this.draftProfile = retained.profile;
        this.draftLoaded = retained.draftLoaded;
        this.page = retained.page == SyncPage.CONFIRM ? SyncPage.HOME : retained.page;
        this.sharedConnectionSession = retained.session;
        this.pendingSharedCommit = retained.pendingCommit;
        this.sharedAppreciationOnly = retained.appreciationOnly;
        if (this.sharedConnectionSession != null) this.sharedConnectionSession.getController().attach(this);
    }

    @Override // android.app.Activity
    protected void onSaveInstanceState(Bundle bundle) {
        super.onSaveInstanceState(bundle);
        bundle.putBoolean("diagnostics.picker", diagnosticExport.selecting());
        bundle.putBoolean("diagnostics.return", diagnosticPickerReturn);
        bundle.putString("page", this.page.name());
        bundle.putString("selected", this.selected);
        bundle.putString("home.explicitSelection", this.explicitHomeSelection);
        bundle.putInt("historyPage", this.historyPage);
        bundle.putString("installedPicker.query", this.installedPickerState.query());
        bundle.putBoolean("installedPicker.showAll", this.installedPickerState.showAll());
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        final int commitBeforeResume = this.sharedCommitSerial;
        this.resumed = true;
        if (this.sharedConnectionSession != null) this.sharedConnectionSession.getController().foreground(true);
        if (!this.receiverRegistered) {
            IntentFilter changes = new IntentFilter("io.github.xyz1001.pdsync.android.INSTALL_CHANGED");
            changes.addAction(AutoSyncService.HISTORY_CHANGED);
            RuntimePlatformCompat.registerInternalReceiver(this, this.installChanged, changes);
            this.receiverRegistered = true;
        }
        onDiagnosticExportChanged();
        if (diagnosticPickerReturn || diagnosticExport.busy()) {
            if (!diagnosticExport.selecting()) diagnosticPickerReturn = false;
            return; // Returning from the local log picker must not inspect Root or the network.
        }
        // A resumed queued commit starts a comparison that already checks readiness.
        // Do not queue another readiness refresh that would erase that comparison result.
        if (commitBeforeResume != this.sharedCommitSerial || drainSharedCommit()) return;
        try {
            AutoSyncService.knownReturn(this);
        } catch (RuntimeException unused) {
        }
        requestReadinessRefresh();
    }

    @Override // android.app.Activity
    protected void onPause() {
        this.resumed = false;
        ++this.sharedHomeRenderToken;
        ++this.sharedSettingsRenderToken;
        dismissSettingsDialog();
        this.comparisonProofState.reset();
        invalidatePathPreview();
        if (this.sharedConnectionSession != null) this.sharedConnectionSession.getController().foreground(false);
        if (!diagnosticExport.selecting()) AutoSyncService.recordDeparture(this);
        super.onPause();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        ++this.sharedHomeRenderToken;
        ++this.sharedSettingsRenderToken;
        dismissSettingsDialog();
        this.comparisonProofState.reset();
        invalidatePathPreview();
        diagnosticExport.detach(this);
        if (this.sharedConnectionSession != null) {
            this.sharedConnectionSession.getController().detach();
            if (!isChangingConfigurations()) closeSharedConnection();
        }
        if (this.receiverRegistered) {
            unregisterReceiver(this.installChanged);
        }
        this.f9io.shutdown();
        super.onDestroy();
    }

    @Override // android.app.Activity
    public void onBackPressed() {
        if (this.sharedConnectionSession != null && (this.page == SyncPage.CONNECTION || this.sharedAppreciationOnly)) {
            this.sharedConnectionSession.getController().back();
            return;
        }
        if (this.page == SyncPage.COMPARISON && !this.busy && this.comparisonProofState.dialog() != null) {
            cancelSharedConflict(this.comparisonProofState.dialog().token);
            return;
        }
        if (this.busy) {
            this.notice = "操作进行中，请等待完成。";
            if (this.page == SyncPage.PATH && !this.sharedAppreciationOnly) renderSharedSavePath();
            else if (this.status != null) this.status.setText("操作进行中，请等待完成。");
            else render();
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
        if (this.page == SyncPage.PATH) {
            navigate(SyncPage.DISCOVERY);
        } else if (this.page == SyncPage.COMPARISON) {
            navigate(SyncPage.CONNECTION);
        } else {
            // The AndroidX dispatcher also receives system/predictive Back on modern Android.
            // Disable our forwarding callback only while invoking its ordinary Activity fallback.
            this.navigationBack.setEnabled(false);
            try { super.onBackPressed(); }
            finally { this.navigationBack.setEnabled(true); }
        }
    }

    private <T> void work(String str, final Task<T> task, final Done<T> done) {
        work(DiagnosticAction.OTHER, str, task, done, false);
    }

    private <T> void work(DiagnosticAction action, String str, final Task<T> task, final Done<T> done) {
        work(action, str, task, done, false);
    }

    private <T> void work(String str, final Task<T> task, final Done<T> done, boolean preserveAutomaticHistory) {
        work(DiagnosticAction.INSPECT, str, task, done, preserveAutomaticHistory);
    }

    private <T> void work(DiagnosticAction action, String str, final Task<T> task, final Done<T> done, boolean preserveAutomaticHistory) {
        if (this.busy || isDestroyed()) {
            return;
        }
        final ComparisonProofState.Ticket comparisonClaim = this.activeSharedComparisonClaim;
        invalidatePathPreview();
        if (!preserveAutomaticHistory) this.automaticHistoryState.outcome = null;
        this.diagnosticReport = "";
        this.details = "";
        this.feedbackError = false;
        this.busy = true;
        this.activeWorkAction = action;
        this.notice = str;
        if (this.page == SyncPage.PATH && !this.sharedAppreciationOnly) {
            // PATH has no native status/root views. Publish busy state before starting I/O.
            renderSharedSavePath();
        } else if (this.status == null || this.root == null || this.renderedPage != this.page) {
            // A callback can change pages before the replacement native hierarchy exists.
            render();
        } else {
            this.status.setText(str);
            this.status.setVisibility(View.VISIBLE);
            disable(this.root);
        }
        final DiagnosticTrace trace = DiagnosticLog.begin(action, false);
        this.f9io.execute(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda56
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m150lambda$work$2$iogithubxyz1001pdsyncandroidMainActivity(task, done, trace, comparisonClaim);
            }
        });
    }

    /* renamed from: lambda$work$2$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m150lambda$work$2$iogithubxyz1001pdsyncandroidMainActivity(Task task, final Done done, final DiagnosticTrace trace, final ComparisonProofState.Ticket comparisonClaim) {
        final Object objRun;
        try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(trace)) {
            trace.record(AndroidDiagnosticLogging.stage(trace.getAction()), DiagnosticResult.STARTED);
            synchronized (SyncRuntime.OPERATION_LOCK) {
                objRun = task.run();
            }
            final DiagnosticResult result = diagnosticWorkResult(trace.getAction(), objRun);
            final DiagnosticFailure failure = diagnosticWorkFailure(trace.getAction(), objRun, result);
            final DiagnosticDetails details = diagnosticWorkDetails();
            trace.record(DiagnosticStage.WORK, result, failure, details);
            runOnUiThread(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda105
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m148lambda$work$0$iogithubxyz1001pdsyncandroidMainActivity(done, objRun, trace, result, failure, details, comparisonClaim);
                }
            });
        } catch (Exception e) {
            AndroidDiagnosticLogging.failure(trace, AndroidDiagnosticLogging.stage(trace.getAction()), e);
            AndroidDiagnosticLogging.failure(trace, DiagnosticStage.FINISHED, e);
            runOnUiThread(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda106
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m149lambda$work$1$iogithubxyz1001pdsyncandroidMainActivity(e, comparisonClaim);
                }
            });
        }
    }

    /* renamed from: lambda$work$0$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m148lambda$work$0$iogithubxyz1001pdsyncandroidMainActivity(Done done, Object obj, DiagnosticTrace trace, DiagnosticResult result, DiagnosticFailure failure, DiagnosticDetails details, ComparisonProofState.Ticket comparisonClaim) {
        if (isDestroyed()) {
            trace.record(DiagnosticStage.FINISHED, result, failure, details);
            return;
        }
        this.busy = false;
        try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(trace)) {
            done.accept(obj);
            if (trace.getAction() == DiagnosticAction.LAUNCH)
                trace.record(DiagnosticStage.GAME_LAUNCH, DiagnosticResult.SUCCEEDED);
            rememberCurrentAutomaticHistory();
            trace.record(DiagnosticStage.FINISHED, result, failure, details);
        } catch (Exception e) {
            AndroidDiagnosticLogging.failure(trace, trace.getAction() == DiagnosticAction.LAUNCH
                    ? DiagnosticStage.GAME_LAUNCH : DiagnosticStage.REFRESH, e);
            AndroidDiagnosticLogging.failure(trace, DiagnosticStage.FINISHED, e);
            reportWorkFailure(e, comparisonClaim);
        }
        render();
        drainReadinessRefresh();
        drainAutomaticHistoryRefresh();
    }

    /* renamed from: lambda$work$1$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m149lambda$work$1$iogithubxyz1001pdsyncandroidMainActivity(Exception exc, ComparisonProofState.Ticket comparisonClaim) {
        if (isDestroyed()) {
            return;
        }
        this.busy = false;
        if (this.sharedReloadPending) {
            this.sharedReloadPending = false;
            this.connectionLoadError = "连接设置读取失败，请重试。";
        }
        reportWorkFailure(exc, comparisonClaim);
        rememberCurrentAutomaticHistory();
        render();
        drainReadinessRefresh();
        drainAutomaticHistoryRefresh();
    }

    /** A rejected Finish/write must allow a fresh inspection, never reuse its stale frozen proof. */
    private void reportWorkFailure(Exception failure, ComparisonProofState.Ticket comparisonClaim) {
        if (comparisonClaim != null) {
            // A previous job cannot clear a newer account, observation, choice or page.
            boolean pageActive = this.page == SyncPage.COMPARISON && !this.sharedAppreciationOnly;
            if (!this.comparisonProofState.invalidateFailure(comparisonClaim, currentComparisonContext(),
                    pageActive) && !invalidateUninspectedComparisonFailure(comparisonClaim, pageActive)) return;
            this.cloudVerified = false;
            this.inspection = null;
            this.history = null;
            this.comparisonDialogMessage = "";
            this.comparisonDialogDetails = "";
        }
        reportError(failure);
    }

    /** A failed re-read still owns its diagnostic before an Inspection exists, but only for its target. */
    private boolean invalidateUninspectedComparisonFailure(ComparisonProofState.Ticket comparisonClaim, boolean pageActive) {
        if (this.inspection != null) return false;
        try {
            return this.comparisonProofState.invalidateUninspectedFailure(comparisonClaim, this.facade, this.connection,
                    SyncRuntime.currentSelectionToken(this) + "\n" + SyncRuntime.currentNamespace(this), pageActive);
        } catch (Exception unavailable) { return false; }
    }

    private DiagnosticResult diagnosticWorkResult(DiagnosticAction action, Object result) {
        if (result instanceof DiagnosticResult) return (DiagnosticResult) result;
        if (result instanceof AutomaticHistoryOutcome) {
            AutomaticHistoryOutcome outcome = (AutomaticHistoryOutcome) result;
            return AndroidDiagnosticLogging.inspectionResult(outcome.inspection == null ? null : outcome.inspection.getKind(), outcome.error != null);
        }
        if (action == DiagnosticAction.ACCESS_CHECK && !this.readinessState.canContinue()) {
            DiagnosticLog.record(DiagnosticStage.READINESS, DiagnosticResult.REJECTED,
                    AndroidDiagnosticLogging.readinessFailure(this.readinessState, this.bridgeAccessCode), diagnosticWorkDetails());
            return DiagnosticResult.REJECTED;
        }
        if (result instanceof OperationOutcome) {
            OperationOutcome outcome = (OperationOutcome) result;
            if (outcome.refreshFailure != null) return DiagnosticResult.FAILED;
            if (outcome.result.isConflict()) return DiagnosticResult.CONFLICT;
            if (outcome.result.isPending()) return DiagnosticResult.PENDING;
        }
        if (action == DiagnosticAction.INSPECT && this.inspection != null) {
            if ("BLOCKED".equals(this.inspection.getKind())) {
                DiagnosticLog.record(DiagnosticStage.GUARD, DiagnosticResult.REJECTED,
                        this.inspection.getNeedsRestoreRecovery() ? DiagnosticFailure.RECOVERY_REQUIRED : DiagnosticFailure.VALIDATION);
                return DiagnosticResult.REJECTED;
            }
            if ("BRANCHED".equals(this.inspection.getKind())) return DiagnosticResult.CONFLICT;
        }
        return DiagnosticResult.SUCCEEDED;
    }

    private static DiagnosticDetails diagnosticInspectionDetails(AndroidSyncFacade.Inspection inspection) {
        DiagnosticDetails.Builder details = DiagnosticDetails.builder().syncCompare(inspection.getKind())
                .syncBlockReason(inspection.diagnosticBlockReason()).localFileCount(inspection.getLocalFileCount())
                .failureSource("SYNC_COMPARE");
        // Multiple remote branches have no single remote save file count.
        if (inspection.getRemoteVersions().size() == 1)
            details.remoteFileCount(inspection.getRemoteVersions().get(0).getFileCount());
        return details.build();
    }

    private DiagnosticDetails diagnosticWorkDetails() {
        DiagnosticDetails.Builder details = AndroidDiagnosticLogging.readinessDetails(this.readinessState, this.bridgeAccessCode);
        if (this.rootGame != null) details.gameVersionCode(this.rootGame.gameVersionCode);
        else if (this.prepared != null) details.gameVersionCode(this.prepared.gameVersionCode);
        return details.build();
    }

    private DiagnosticFailure diagnosticWorkFailure(DiagnosticAction action, Object value, DiagnosticResult result) {
        if (action == DiagnosticAction.ACCESS_CHECK && result == DiagnosticResult.REJECTED)
            return AndroidDiagnosticLogging.readinessFailure(this.readinessState, this.bridgeAccessCode);
        if (value instanceof AutomaticHistoryOutcome) {
            AutomaticHistoryOutcome outcome = (AutomaticHistoryOutcome) value;
            if (outcome.error != null) return AndroidDiagnosticLogging.classify(outcome.error);
            if (outcome.inspection != null && outcome.inspection.getNeedsRestoreRecovery()) return DiagnosticFailure.RECOVERY_REQUIRED;
        }
        if (value instanceof OperationOutcome && ((OperationOutcome) value).refreshFailure != null)
            return ((OperationOutcome) value).refreshDiagnosticFailure;
        if (action == DiagnosticAction.INSPECT && result == DiagnosticResult.REJECTED && this.inspection != null)
            return this.inspection.getNeedsRestoreRecovery() ? DiagnosticFailure.RECOVERY_REQUIRED : DiagnosticFailure.VALIDATION;
        return result == DiagnosticResult.FAILED ? DiagnosticFailure.OTHER
                : result == DiagnosticResult.REJECTED ? DiagnosticFailure.VALIDATION : DiagnosticFailure.NONE;
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
        captureDiagnostic(exc);
        this.feedbackError = true;
        String strCode = BridgeDiagnostics.code(exc);
        if (strCode != null) {
            this.bridgeAccessCode = strCode;
            this.readinessState = this.readinessState.withAccessCode(strCode);
            this.localPreview = null;
            resetPathPreview();
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

    private SharedGameIdentity selectedGameIdentity() {
        if (this.rootGame != null) return new SharedGameIdentity(this.rootGame.gameLabel, this.rootGame.gameVersionName);
        if (this.prepared != null) return new SharedGameIdentity(this.prepared.gameLabel, this.prepared.gameVersionName);
        return null;
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
        work(DiagnosticAction.ACCESS_CHECK, "正在通过 Root 检查所选游戏存档…", () -> {
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
        work(DiagnosticAction.LAUNCH, "正在核对所选游戏启动入口…", () -> {
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
        work(DiagnosticAction.INSPECT, "正在只读列出已安装应用…", () -> {
            if (this.installer.hasActiveRequest() || hasAnyRestoreJournal())
                throw new IOException("请先处理未完成恢复或系统安装请求，再切换访问方式。");
            return InstalledAppCatalog.load(this, selectedPackage);
        }, games -> InstalledAppPickerDialog.show(this, "Root 模式：选择已安装游戏", games,
                selectedPackage, installedPickerState, this::selectRootGame));
    }

    private void selectRootGame(String packageName) {
        work(DiagnosticAction.INSPECT, "正在核对已安装游戏身份，不更改游戏…", () -> {
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
        work(DiagnosticAction.CONFIGURE, "正在切换为内置存档桥…", () -> {
            if (restoreNeedsAttention()) throw new IOException("请先核对当前游戏的未完成恢复，不能切换访问方式。");
            SyncRuntime.useEmbeddedBackend(this);
            cancelControlledLaunch();
            resetAccessSelection();
            readInstallationReadiness();
            return null;
        }, ignored -> this.notice = this.readinessState.statusDescription());
    }

    private void resetAccessSelection() {
        this.comparisonProofState.reset();
        this.rootGame = null;
        this.accessCapabilities = null;
        this.prepared = null;
        this.facade = null;
        this.history = null;
        this.inspection = null;
        this.localPreview = null;
        resetPathPreview();
        this.cloudVerified = false;
        this.selected = null;
        this.explicitHomeSelection = null;
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
        this.comparisonProofState.reset();
        this.readinessRefreshPending = false;
        if (this.automaticHistoryState.outcome != null) this.automaticHistoryState.outcome.applied = false;
        final boolean z = this.initialLoad;
        this.readinessState = InstallationReadiness.checking(this.prepared != null, this.installer.hasActiveRequest());
        this.localPreview = null;
        resetPathPreview();
        this.cloudVerified = false;
        this.inspection = null;
        this.history = null;
        work(DiagnosticAction.ACCESS_CHECK, "正在检查游戏与存档…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda0
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m68x764cc8a4(z);
            }
        }, new Done() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda1
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Done
            public final void accept(Object obj) throws Exception {
                MainActivity.this.m69xebc6eee5(z, obj);
            }
        }, true);
    }

    /* renamed from: lambda$refreshReadiness$3$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m68x764cc8a4(boolean z) throws Exception {
        if (z) {
            try {
                this.connection = SyncRuntime.connection(this);
                this.draftLoaded = false;
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
        if (z && this.rootGame == null && readyForSaves() && this.connection != null && !hasAutomaticHistoryRefresh()) {
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
            if (this.rootGame == null && this.page == SyncPage.HOME && readyForSaves() && this.connection != null && !hasAutomaticHistoryRefresh()) {
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
        if (this.retainedConnectionUi != null
                && (canRequestSaveAccess() || this.retainedConnectionUi.session != null)) {
            RetainedConnectionUi retained = this.retainedConnectionUi;
            this.retainedConnectionUi = null;
            // onCreate already restored/attached the live controller. Readiness may finish
            // after a commit callback; never overwrite its pending result with the old snapshot.
            this.onboardingActive = retained.onboarding;
            this.page = retained.page == SyncPage.CONFIRM ? SyncPage.HOME : retained.page;
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
                    DiagnosticLog.record(DiagnosticStage.READINESS, DiagnosticResult.FAILED,
                            AndroidDiagnosticLogging.classify(diagnostic), AndroidDiagnosticLogging.failureDetails(diagnostic));
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
        if (this.page != syncPage) {
            ++this.sharedHomeRenderToken;
            ++this.sharedSettingsRenderToken;
            dismissSettingsDialog();
            if (this.page == SyncPage.CONFIRM) ++this.settingsConfirmationToken;
            this.comparisonProofState.reset();
            invalidatePathPreview();
        }
        if (syncPage != SyncPage.CONNECTION) closeSharedConnection();
        if (this.page != syncPage) { this.notice = ""; this.feedbackError = false; this.details = ""; this.diagnosticReport = ""; }
        this.detailsExpanded = false;
        this.page = syncPage;
        if (syncPage == SyncPage.CONNECTION && !this.draftLoaded) {
            loadDraft();
        }
        render();
        if (syncPage == SyncPage.HOME) drainAutomaticHistoryRefresh();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void render() {
        if (isDestroyed()) return;
        if (!this.busy && this.activeSharedComparisonClaim != null) {
            this.comparisonProofState.releaseClaim(this.activeSharedComparisonClaim);
            this.activeSharedComparisonClaim = null;
        }
        if (this.page != SyncPage.COMPARISON) this.comparisonProofState.reset();
        if (this.page != SyncPage.PATH || this.sharedAppreciationOnly) invalidatePathPreview();
        if (drainSharedCommit()) return;
        if (this.page == SyncPage.CONNECTION || this.sharedAppreciationOnly) {
            renderSharedConnection();
            return;
        }
        closeSharedConnection();
        if (this.page == SyncPage.DISCOVERY) {
            renderDiscovery();
            return;
        }
        if (this.page == SyncPage.PATH) {
            renderSharedSavePath();
            return;
        }
        if (this.page == SyncPage.COMPARISON) {
            renderSharedComparison();
            return;
        }
        if (this.page == SyncPage.HOME) {
            renderHome();
            return;
        }
        if (this.page == SyncPage.SETTINGS) {
            renderSettings();
            return;
        }
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
        linearLayoutRow.addView(label("像素地牢  /  存档同步", 14, INK), new LinearLayout.LayoutParams(0, -2, 1.0f));
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
        switch (AnonymousClass3.$SwitchMap$pdsync$presentation$SyncPage[this.page.ordinal()]) {
            case 1:
                throw new IllegalStateException("Home is hosted by the shared Compose screen");
            case 2:
                throw new IllegalStateException("Settings is hosted by the shared Compose screen");
            case 3:
                throw new IllegalStateException("Game selection is hosted by the shared Compose screen");
            case 4:
                throw new IllegalStateException("Save path is hosted by the shared Compose screen");
            case 5:
                throw new IllegalStateException("Connection is hosted by the shared Compose screen");
            case 6:
                throw new IllegalStateException("Comparison is hosted by the shared Compose screen");
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
            final ScrollView renderedScroll = this.scroll;
            renderedScroll.post(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda52
                @Override // java.lang.Runnable
                public final void run() {
                    if (MainActivity.this.scroll != renderedScroll || MainActivity.this.isDestroyed()) return;
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

    /** The shared page only consumes this already-observed history; drawing never exports saves. */
    private void renderHome() {
        final int renderToken = ++this.sharedHomeRenderToken;
        final HomeActionContext owner = currentHomeContext(renderToken);
        final SharedHomeContent content = homeContent();
        String homeStatus = this.cloudVerified
                ? content.getUploadUnnecessary() ? "已同步" : "云端已连接"
                : this.connection == null ? "等待连接" : "存档待检查";
        if (!automaticObservationSupported() || SyncRuntime.isAutoUploadPaused(this) || this.progress != OnboardingProgress.COMPLETE)
            homeStatus += " · 自动上传已暂停";
        String automaticStatus = automaticStatusBrief();
        if (!automaticStatus.isEmpty()) homeStatus += "\n" + automaticStatus;
        String homeNotice = visibleNotice();
        String capabilityNotice = restoreNeedsAttention()
                ? "有未完成恢复，请在设置中检查恢复结果。"
                : this.inspection != null && this.inspection.isRetryablePending()
                ? "上次上传尚未完成，请重试上传。"
                : !readyForSaves() ? "请在设置中重新检查存档。"
                : !restoreSupported() ? "当前访问方式不支持恢复与合并写入。" : "";
        if (!capabilityNotice.isEmpty() && !homeNotice.contains(capabilityNotice))
            homeNotice = homeNotice.isEmpty() ? capabilityNotice : homeNotice + "\n" + capabilityNotice;
        // These are platform capabilities, not permission inferred from a label, PID or timestamp.
        final boolean canUpload = canRequestSaveAccess() && !restoreNeedsAttention() && !content.getUploadUnnecessary();
        final boolean canRestore = restoreSupported() && canRequestSaveAccess()
                && this.connection != null && !restoreNeedsAttention() && !content.getRestoreUnnecessary();
        final boolean canLaunch = canRequestSaveAccess() && !restoreNeedsAttention();
        this.root = null;
        this.status = null;
        this.body = null;
        this.footer = null;
        this.scroll = null;
        this.renderedPage = this.page;
        AndroidOnboardingComposition.showHome(this, this.history, this.historyPage, selectedGameIdentity(),
                homeStatus, isForegroundHome(), this.busy, canUpload, canRestore, canLaunch, homeOperation(),
                this.selected, this.explicitHomeSelection, content, homeNotice, this.feedbackError, this.details,
                new SharedHomeActions() {
                    @Override public void settings() {
                        if (isCurrentHomeAction(owner)) navigate(SyncPage.SETTINGS);
                    }
                    @Override public void upload() {
                        if (isCurrentHomeAction(owner) && canRequestSaveAccess() && !restoreNeedsAttention()
                                && !homeContent().getUploadUnnecessary()) requestUpload();
                    }
                    @Override public void restore() {
                        if (isCurrentHomeAction(owner) && restoreSupported() && canRequestSaveAccess()
                                && connection != null && !restoreNeedsAttention()
                                && !homeContent().getRestoreUnnecessary()) requestRestore();
                    }
                    @Override public void launch() {
                        if (isCurrentHomeAction(owner) && canRequestSaveAccess() && !restoreNeedsAttention()) launchGame();
                    }
                    @Override public void selectSnapshot(String snapshotId) {
                        if (!isCurrentHomeAction(owner) || !hasHomeSnapshot(snapshotId)) return;
                        selected = snapshotId;
                        explicitHomeSelection = snapshotId;
                        render();
                    }
                    @Override public void rename(String snapshotId) {
                        if (!isCurrentHomeAction(owner) || history == null) return;
                        if (snapshotId == null ? history.getLocalNodeCount() > 0 : hasHomeSnapshot(snapshotId))
                            MainActivity.this.rename(snapshotId);
                    }
                    @Override public void changeHistoryPage(int requestedPage) {
                        if (!isCurrentHomeAction(owner) || history == null) return;
                        HistoryPresentation visible = HistoryPresentation.from(history, historyPage);
                        if (requestedPage < 0 || requestedPage >= visible.getPages() || requestedPage == visible.getPage()) return;
                        historyPage = requestedPage;
                        render();
                    }
                });
    }

    /** Uses only the paired in-memory inspection/history; failure is never equality evidence. */
    private SharedHomeContent homeContent() {
        return SharedHomeContent.from(this.history, this.selected,
                this.cloudVerified && this.connection != null && this.facade != null && this.inspection != null
                        && this.progress == OnboardingProgress.COMPLETE && this.readinessState.canContinue()
                        && !restoreNeedsAttention() && kind() != OnboardingKind.BLOCKED,
                this.inspection != null && this.inspection.isRetryablePending(), kind() == OnboardingKind.BRANCHED);
    }

    private boolean isForegroundHome() {
        return this.resumed && !isDestroyed() && this.page == SyncPage.HOME && !this.sharedAppreciationOnly;
    }

    private HomeActionContext currentHomeContext(int renderToken) {
        try {
            return new HomeActionContext(renderToken, this.connection, this.facade, this.inspection, this.history,
                    SyncRuntime.currentSelectionToken(this) + "\n" + SyncRuntime.currentNamespace(this));
        } catch (Exception unavailable) { return null; }
    }

    private boolean isCurrentHomeAction(HomeActionContext owner) {
        return owner != null && owner.accepts(currentHomeContext(this.sharedHomeRenderToken), isForegroundHome(), this.busy);
    }

    /** Full verified history owns target selection even when its card is on another page. */
    private boolean hasHomeSnapshot(String snapshotId) {
        if (snapshotId == null || this.history == null) return false;
        for (AppendCommit commit : this.history.getCloud())
            if (snapshotId.equals(commit.getSnapshot().getId())) return true;
        return false;
    }

    private SharedHomeOperation homeOperation() {
        if (!this.busy) return SharedHomeOperation.NONE;
        switch (this.activeWorkAction) {
            case PUSH: return SharedHomeOperation.UPLOAD;
            case RESTORE: return SharedHomeOperation.RESTORE;
            case MERGE: return SharedHomeOperation.MERGE;
            case LAUNCH: return SharedHomeOperation.LAUNCH;
            default: return SharedHomeOperation.NONE;
        }
    }

    /** Step 1 uses the same Compose identity card, controls and fixed footer on both platforms. */
    private void renderDiscovery() {
        final int renderToken = ++this.sharedGameRenderToken;
        this.root = null;
        this.body = null;
        this.footer = null;
        this.scroll = null;
        this.status = null;
        this.renderedPage = this.page;
        AndroidOnboardingComposition.showGameSelection(this, gameSelectionState(), new SharedGameSelectionActions() {
            @Override public void perform(SharedGameSelectionControl control) {
                if (!isCurrentGameAction(renderToken) || !gameSelectionState().actionEnabled(control)) return;
                switch (control) {
                    case CHOOSE_INSTALLED: chooseInstalled(); break;
                    case CHOOSE_FILE: pickApks(); break;
                    case CHOOSE_ROOT: chooseRootInstalled(); break;
                    case SWITCH_TO_EMBEDDED: switchToEmbedded(); break;
                    case CHECK_ACCESS: requestRootAuthorization(); break;
                    case LAUNCH_GAME: launchSelectedRootGame(); break;
                    case ACTIVATE_BRIDGE: activateHelper(); break;
                    case INITIALIZE_GAME: launchGameForInitialization(); break;
                    case OPEN_RECOVERY: navigate(SyncPage.SETTINGS); break;
                    case CONTINUE_INSTALLATION: continueInstallation(); break;
                    case OPEN_SYSTEM_CONFIRMATION: openInstallConfirmation(); break;
                    case CANCEL_INSTALLATION: m96x62f97958(); break;
                    case CHECK_EXPIRED_REQUEST: m100xdbe735b1(); break;
                    case CLEAN_LEGACY_REQUEST: confirmObsoleteLegacyCleanup(); break;
                    case OPEN_INSTALL_SETTINGS: m102xc6db8233(); break;
                    case RESCAN: refreshReadiness(); break;
                    case COPY_DIAGNOSTICS: copyDiagnostic(); break;
                }
            }
            @Override public void chooseCandidate(String id) { /* Android uses its filtered installed-app dialog. */ }
            @Override public void next() {
                if (isCurrentGameAction(renderToken) && gameSelectionState().getNextEnabled()) m103x3c55a874();
            }
        });
    }

    private boolean isCurrentGameAction(int renderToken) {
        return renderToken == this.sharedGameRenderToken && this.resumed && !isDestroyed()
                && this.page == SyncPage.DISCOVERY && !this.sharedAppreciationOnly && !this.busy && !this.gameFilePickerOpen;
    }

    /** Describes capabilities only. APK preparation, Root probes and destructive confirmations remain in their original adapters. */
    private SharedGameSelectionState gameSelectionState() {
        InstallationReadiness ready = this.readinessState.withSystemRequest(this.installer.hasActiveRequest());
        List<SharedGameSelectionRow> rows = new ArrayList<>();
        boolean activeRequest = this.installer.hasActiveRequest();
        boolean recovery = restoreNeedsAttention();
        String warning = recovery ? "有未完成恢复，请先检查恢复记录。" : "";
        String extra = ready.installationDescription() + "\n" + ready.accessDescription();
        String location = selectedGamePackage();
        if (this.rootGame != null) {
            rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CHECK_ACCESS, "检查 Root 存档访问", !activeRequest));
            rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.LAUNCH_GAME, "启动所选游戏", ready.identityVerified && !activeRequest && !recovery));
            rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_ROOT, "更换 Root 游戏…", !activeRequest && !recovery));
            rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.SWITCH_TO_EMBEDDED, "切换为内置存档桥", !activeRequest && !recovery));
            if (recovery) rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.OPEN_RECOVERY, "查看恢复记录", true));
            extra += "\nRoot 检查、同步与恢复会请求所选游戏的访问权限，是否保留授权由 Root 管理器决定。";
            if (!restoreSupported()) extra += "\n当前访问方式不支持恢复与合并写入。";
            if (!backupRecoverySupported()) extra += "\n当前访问方式不支持中断事务恢复。";
            if (!automaticObservationSupported()) extra += "\n当前访问方式不支持退出监听，请手动检查和上传。";
        } else {
            rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_INSTALLED, "从已安装应用中选择", !activeRequest && !recovery));
            rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_FILE, this.prepared == null ? "选择 APK…" : "更换 APK…", !activeRequest && !recovery));
            rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_ROOT, "使用 Root 选择游戏…", !activeRequest && !recovery));
            if (this.prepared != null && this.prepared.embeddedBridge()
                    && HelperActivationPolicy.canOffer(ready, this.bridgeAccessCode, false))
                rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.ACTIVATE_BRIDGE, "启用游戏内置存档桥", true));
            if (ready.canInitializeGame()) rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.INITIALIZE_GAME, "启动游戏一次", true));
            if (recovery) rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.OPEN_RECOVERY, "检查恢复记录", true));
            if (this.prepared != null) {
                extra += "\n" + pairSummary(this.prepared);
                if (activeRequest || ready.canContinueInstallation()) extra += "\n系统安装／卸载记录：\n" + this.installer.summary();
                if (ready.canContinueInstallation()) rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CONTINUE_INSTALLATION, "检查并继续安装", true));
                if (!this.prepared.embeddedBridge()) warning = "旧版准备记录仅供恢复核对。处理未完成恢复后，请从原始 APK 重新准备游戏。";
            }
            boolean obsolete = this.installer.hasObsoleteLegacyRequest(this.prepared);
            if (obsolete) {
                warning = "请先在 Android 系统界面取消并关闭旧版安装／卸载请求，再核对清理。";
                rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CLEAN_LEGACY_REQUEST, "核对并清理旧版请求", true));
            }
            if (this.installer.hasConfirmation() && !obsolete)
                rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.OPEN_SYSTEM_CONFIRMATION, "继续系统安装／卸载确认", true));
            if (activeRequest && !obsolete) {
                rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CANCEL_INSTALLATION, "取消待处理安装", true));
                rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CHECK_EXPIRED_REQUEST, "检查失效系统请求", true));
            }
            if (ready.canContinueInstallation() && !getPackageManager().canRequestPackageInstalls())
                rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.OPEN_INSTALL_SETTINGS, "打开系统安装设置", true));
            if (ready.requiresGameUninstall()) warning = "替换前必须卸载原游戏，会永久删除全部本地存档和战绩。APK 备份不能恢复存档。卸载和安装仍需在 Android 系统界面分别确认。";
            rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.RESCAN, "重新检查状态", true));
            extra += "\n选择完整的一组 APK；原游戏代码和资源逐项保留。签名私钥仅保留在本机 Android Keystore，卸载主程序或清除数据会失去密钥，之后可能必须再次卸载游戏。WebDAV 不备份密钥。";
        }
        String message = visibleNotice();
        if (!this.busy && !this.feedbackError && message.length() > 64) {
            extra += "\n" + message;
            message = !canRequestSaveAccess() && this.prepared != null ? ready.accessDescription() : "";
        }
        if (!this.details.isEmpty()) extra += "\n" + this.details;
        SharedGameIdentity game = selectedGameIdentity();
        return new SharedGameSelectionState(game, location, game == null ? "" : this.rootGame != null ? "Root" : ready.canContinue() ? "已就绪" : "已选择",
                location == null ? "" : location, rows, Collections.emptyList(), this.resumed && this.page == SyncPage.DISCOVERY && !this.sharedAppreciationOnly,
                this.busy || this.gameFilePickerOpen, canRequestSaveAccess(), message, this.feedbackError, warning, extra, !this.diagnosticReport.isEmpty());
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
        work(DiagnosticAction.INSTALL, "正在取消待处理安装…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda114
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
                "已取消并关闭，核对清理", () -> work(DiagnosticAction.INSTALL, "正在核对并清理旧版请求…",
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
        work(DiagnosticAction.INSTALL, "正在核对系统请求…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda17
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
        if (this.busy || this.gameFilePickerOpen || !this.resumed || isDestroyed() || this.page != SyncPage.DISCOVERY) return;
        if (canRequestSaveAccess()) {
            this.onboardingActive = true;
            navigate(SyncPage.PATH);
            previewPath();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pickApks() {
        if (this.busy || this.gameFilePickerOpen || isDestroyed()) return;
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
            this.gameFilePickerOpen = true;
            render();
            startActivityForResult(type, PICK_APK);
        } catch (ActivityNotFoundException unused) {
            this.gameFilePickerOpen = false;
            this.notice = "设备没有可用的系统文件选择器。";
            render();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void chooseInstalled() {
        if (this.rootGame != null) { this.notice = "Root 模式不准备或安装游戏；请先明确切换为内置存档桥。"; render(); return; }
        String selectedPackage = selectedGamePackage();
        work(DiagnosticAction.INSPECT, "正在读取可选择的已安装应用…", () -> {
            this.installer.assertCanPrepare();
            return InstalledAppCatalog.load(this, selectedPackage);
        }, games -> InstalledAppPickerDialog.show(this, "选择游戏", games, selectedPackage,
                installedPickerState, packageName -> requestPrepare(packageName, null)));
    }

    private void requestPrepare(final String str, final List<Uri> list) {
        if (this.rootGame != null) { this.notice = "Root 模式不准备或安装游戏；请先明确切换为内置存档桥。"; render(); return; }
        work(DiagnosticAction.PREPARE, "正在检查本机签名密钥…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda121
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
        work(DiagnosticAction.PREPARE, "正在验证并暂存安装包，原游戏保持不变…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda99
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
        this.explicitHomeSelection = null;
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
            work(DiagnosticAction.INSTALL, "正在重新验证暂存包与现有应用…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda112
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
        work(DiagnosticAction.INSTALL, "正在最后核验安装包与当前应用…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda19
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
        work(DiagnosticAction.INSTALL, "正在向 Android 提交经过验证的安装包…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda123
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
        work(DiagnosticAction.INSTALL, "正在复核保留的安装包与系统确认…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda92
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

    /** Android resolves and reads the real game path; shared Compose owns only presentation. */
    private void renderSharedSavePath() {
        bindPathPreviewContext();
        final int renderToken = ++this.sharedPathRenderToken;
        String pathNotice = visibleNotice();
        if (pathNotice.isEmpty() && !readyForSaves() && !this.pathPreviewState.inFlight())
            pathNotice = "暂时无法读取存档，请重新检查。";
        SharedSavePathReadStatus readStatus = this.pathPreviewState.inFlight() ? SharedSavePathReadStatus.READING
                : this.pathPreviewState.failed() ? SharedSavePathReadStatus.FAILED
                : readyForSaves() && this.localPreview != null ? SharedSavePathReadStatus.READY
                : canRequestSaveAccess() ? SharedSavePathReadStatus.UNREAD : SharedSavePathReadStatus.FAILED;
        this.root = null;
        this.status = null;
        this.body = null;
        this.footer = null;
        this.scroll = null;
        this.renderedPage = this.page;
        AndroidOnboardingComposition.showSavePath(this, savePathLabel(), this.localPreview,
                this.busy, pathNotice, this.details, readyForSaves() && this.localPreview != null,
                readStatus, this.pathPreviewState.continueAfterRead(),
                canRequestSaveAccess() || readStatus == SharedSavePathReadStatus.FAILED, selectedGameIdentity(),
                this.readinessState.withSystemRequest(this.installer.hasActiveRequest()).canInitializeGame(),
                readyForSaves() ? "重新检查存档" : "重新检查状态", !this.diagnosticReport.isEmpty(),
                () -> { if (isCurrentPathAction(renderToken)) m108xe2dc5156(); },
                () -> {
                    if (isCurrentPathAction(renderToken) && !this.pathPreviewState.inFlight()
                            && this.readinessState.withSystemRequest(this.installer.hasActiveRequest()).canInitializeGame())
                        launchGameForInitialization();
                },
                () -> { if (isCurrentPathAction(renderToken)) m109x58567797(); },
                () -> { if (isCurrentPathAction(renderToken)) requestPathNext(); },
                () -> {
                    if (isCurrentPathAction(renderToken) && !this.diagnosticReport.isEmpty()) copyDiagnostic();
                });
        schedulePathPreviewEntry();
    }

    /** UI render tokens protect actions only, never a legitimate in-flight read. */
    private boolean isCurrentPathAction(int renderToken) {
        return renderToken == this.sharedPathRenderToken && this.page == SyncPage.PATH
                && !this.sharedAppreciationOnly && this.resumed && !this.busy && !isDestroyed();
    }

    private boolean isForegroundPath() {
        return this.resumed && !isDestroyed() && this.page == SyncPage.PATH && !this.sharedAppreciationOnly;
    }

    /** All components describe the actual selected backend; render count is not read identity. */
    private PathPreviewState.Context currentPathPreviewContext() {
        try {
            return new PathPreviewState.Context(SyncRuntime.currentSelectionToken(this),
                    SyncRuntime.currentNamespace(this), savePathLabel());
        } catch (Exception unavailable) {
            return null;
        }
    }

    private void bindPathPreviewContext() {
        if (this.pathPreviewState.bind(currentPathPreviewContext())) this.localPreview = null;
    }

    private void invalidatePathPreview() {
        cancelPathPreviewEntry();
        this.pathPreviewState.invalidate();
    }

    private void resetPathPreview() {
        cancelPathPreviewEntry();
        this.pathPreviewState.reset();
    }

    /** One Activity entry event per readiness observation, never a Compose/recomposition effect. */
    private void schedulePathPreviewEntry() {
        if (this.rootGame != null || this.localPreview != null || this.pathPreviewState.attempted() || this.busy
                || this.pathPreviewState.inFlight() || this.page != SyncPage.PATH || !this.resumed
                || !readyForSaves() || isDestroyed() || this.pathPreviewPosted) return;
        this.pathPreviewPosted = true;
        this.pathPreviewHandler.post(this.pathPreviewEntry);
    }

    private void cancelPathPreviewEntry() {
        this.pathPreviewHandler.removeCallbacks(this.pathPreviewEntry);
        this.pathPreviewPosted = false;
    }

    /* Existing readiness action remains separate from a read proof or continuation. */
    /* synthetic */ void m108xe2dc5156() {
        if (!isForegroundPath() || this.busy || this.pathPreviewState.inFlight()) return;
        if (readyForSaves()) previewPath(false);
        else if (this.rootGame != null) requestRootAuthorization();
        else refreshReadiness();
    }

    /* synthetic */ void m109x58567797() {
        navigate(SyncPage.DISCOVERY);
    }

    /* synthetic */ void m110xcdd09dd8() {
        if (!isForegroundPath() || this.busy || !readyForSaves() || this.localPreview == null) return;
        this.onboardingActive = true;
        navigate(SyncPage.CONNECTION);
    }

    private void requestPathNext() {
        if (!isForegroundPath() || this.busy) return;
        bindPathPreviewContext();
        if (this.pathPreviewState.inFlight()) {
            if (this.pathPreviewState.queueNext(currentPathPreviewContext(), isForegroundPath(), this.busy))
                renderSharedSavePath();
            return;
        }
        if (readyForSaves() && this.localPreview != null && !this.pathPreviewState.failed()) {
            m110xcdd09dd8();
        } else if (this.pathPreviewState.failed() && !readyForSaves()) {
            m108xe2dc5156();
        } else {
            previewPath(true);
        }
    }

    /* synthetic */ void m111x434ac419() {
        this.pathPreviewPosted = false;
        if (this.rootGame != null || this.page != SyncPage.PATH || this.localPreview != null
                || this.pathPreviewState.attempted() || this.pathPreviewState.inFlight() || this.busy
                || !this.resumed || !readyForSaves() || isDestroyed()) return;
        previewPath(false);
    }

    private void previewPath() { previewPath(false); }

    /** Ordinary read has its own ownership; it never sets or clears hard-operation busy. */
    private void previewPath(boolean continueWhenReady) {
        if (this.busy || !this.resumed || isDestroyed() || this.page != SyncPage.PATH
                || this.sharedAppreciationOnly || this.pathPreviewState.inFlight()) return;
        cancelPathPreviewEntry();
        if (!canRequestSaveAccess()) {
            if (this.rootGame != null) requestRootAuthorization(); else refreshReadiness();
            return;
        }
        bindPathPreviewContext();
        final PathPreviewState.Request request = this.pathPreviewState.begin(currentPathPreviewContext(),
                isForegroundPath(), this.busy, canRequestSaveAccess(), continueWhenReady);
        if (request == null) return;
        this.localPreview = null;
        this.diagnosticReport = "";
        this.details = "";
        this.feedbackError = false;
        this.notice = "正在读取存档…";
        renderSharedSavePath();
        final PathPreviewOutcome outcome = new PathPreviewOutcome(this.readinessState);
        final DiagnosticTrace trace = DiagnosticLog.begin(DiagnosticAction.INSPECT, false);
        this.f9io.execute(() -> {
            try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(trace)) {
                trace.record(DiagnosticStage.READINESS, DiagnosticResult.STARTED);
                synchronized (SyncRuntime.OPERATION_LOCK) {
                    if (!isCurrentPathRead(request)) {
                        trace.record(DiagnosticStage.FINISHED, DiagnosticResult.CANCELED);
                        return;
                    }
                    outcome.summary = readPathPreview(request, outcome);
                }
                trace.record(DiagnosticStage.WORK, DiagnosticResult.SUCCEEDED);
                runOnUiThread(() -> finishPathPreview(request, outcome));
                trace.record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED);
            } catch (Exception failure) {
                outcome.failure = failure;
                AndroidDiagnosticLogging.failure(trace, DiagnosticStage.WORK, failure);
                AndroidDiagnosticLogging.failure(trace, DiagnosticStage.FINISHED, failure);
                runOnUiThread(() -> finishPathPreview(request, outcome));
            }
        });
    }

    /** Worker-local evidence. Root inspection cannot publish Activity readiness after Back/pause. */
    private static final class PathPreviewOutcome {
        final InstallationReadiness expectedReadiness;
        InstallationReadiness rootReadiness;
        SaveSummary summary;
        Exception failure;
        PathPreviewOutcome(InstallationReadiness expectedReadiness) {
            this.expectedReadiness = expectedReadiness;
        }
    }

    private boolean isCurrentPathRead(PathPreviewState.Request request) {
        return this.pathPreviewState.isCurrent(request, currentPathPreviewContext(), isForegroundPath(), this.busy);
    }

    /** Same Root proof as the hard operation, with all readiness kept local to this read. */
    private InstallationReadiness checkRootAccess(PathPreviewState.Request request, GameAccessBackend backend) throws Exception {
        if (!isCurrentPathRead(request)) throw new IOException("存档检查已取消。");
        this.installer.reconcile();
        SelectedRootGame chosen = SyncRuntime.rootGame(this);
        if (chosen == null || !chosen.selectionToken().equals(request.context.selectionToken))
            throw new IOException("所选访问方式已变化，请重新检查。");
        backend.targetGame();
        if (!backend.capabilities().exportSupported) throw new IOException("当前存档后端不支持读取存档。");
        if (this.installer.hasActiveRequest()) throw new IOException("请先处理当前系统安装请求。");
        if (hasAnyRestoreJournal()) {
            // Reading never performs recovery. The original explicit recovery flow remains available.
            return InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                    true, false, true, InstallationReadiness.AccessStatus.RECOVERY_NEEDED);
        }
        if (!isCurrentPathRead(request)) throw new IOException("存档检查已取消。");
        backend.inspectReady();
        return InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                true, false, false, InstallationReadiness.AccessStatus.READY);
    }

    private SaveSummary readPathPreview(PathPreviewState.Request request, PathPreviewOutcome outcome) throws Exception {
        GameAccessBackend backend = SyncRuntime.backend(this);
        if (backend.mode() == GameAccessBackend.Mode.ROOT_DIRECT) {
            outcome.rootReadiness = checkRootAccess(request, backend);
            if (!outcome.rootReadiness.canContinue()) throw new IOException(outcome.rootReadiness.accessDescription());
        } else if (!readyForSaves()) {
            throw new IOException("请先完成存档访问检查。");
        }
        if (!isCurrentPathRead(request)) throw new IOException("存档检查已取消。");
        if (this.installer.hasActiveRequest() || hasAnyRestoreJournal())
            throw new IOException("请先完成存档访问检查或处理未完成恢复。");
        try (TrustedPrivateDirectory.TemporaryDirectory temporaryDirectory = TrustedPrivateDirectory.resolve(
                getDataDir().toPath(), getCacheDir().toPath(), Process.myUid(), MainActivity::lambda$previewPath$49)
                .createTemporaryDirectory("save-preview-")) {
            if (!isCurrentPathRead(request)) throw new IOException("存档检查已取消。");
            backend.exportTo(temporaryDirectory.path());
            return SaveSummary.Companion.read(temporaryDirectory.path());
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

    private void finishPathPreview(PathPreviewState.Request request, PathPreviewOutcome outcome) {
        // A readiness refresh is a new observation even if it happens to have the same values.
        if (this.readinessState != outcome.expectedReadiness) return;
        boolean accessReady = outcome.rootReadiness == null ? readyForSaves()
                : outcome.rootReadiness.withSystemRequest(this.installer.hasActiveRequest()).canContinue()
                    && SyncRuntime.rootAuthorized(this);
        PathPreviewState.Completion completion = this.pathPreviewState.finish(request,
                currentPathPreviewContext(), isForegroundPath(), this.busy, accessReady,
                outcome.summary != null, outcome.failure == null);
        if (completion == PathPreviewState.Completion.STALE) return;
        if (outcome.rootReadiness != null) {
            this.readinessState = outcome.rootReadiness;
            this.bridgeAccessCode = outcome.rootReadiness.restorePending ? "ROOT_RECOVERY_REQUIRED" : "READY";
        }
        if (outcome.failure != null || !readyForSaves() || outcome.summary == null) {
            reportPathPreviewError(outcome.failure != null ? outcome.failure
                    : new IOException("存档访问状态已变化，请重试。"));
        } else {
            this.localPreview = outcome.summary;
            this.notice = "";
            this.feedbackError = false;
            if (completion == PathPreviewState.Completion.ADVANCE) {
                m110xcdd09dd8();
                return;
            }
        }
        renderSharedSavePath();
    }

    /** A failed read stays attempted/FAILED; generic reportError would reset and auto-retry it. */
    private void reportPathPreviewError(Exception failure) {
        captureDiagnostic(failure);
        this.localPreview = null;
        this.feedbackError = true;
        String code = BridgeDiagnostics.code(failure);
        if (code != null) {
            this.bridgeAccessCode = code;
            this.readinessState = this.readinessState.withAccessCode(code);
            this.cloudVerified = false;
            this.notice = NoviceCopy.feedback(safeText(BridgeDiagnostics.find(failure).getMessage()), "无法读取存档，请重试。");
        } else this.notice = safeError(failure);
    }

    private void loadDraft() {
        this.draftProfile = this.connection == null ? "default" : this.connection.profile;
        this.draftLoaded = true;
    }

    /** Connection/account, save path and comparison use the same shared Compose pages. */
    private void renderSharedConnection() {
        if (!this.draftLoaded) loadDraft();
        if (this.sharedConnectionSession == null) {
            this.sharedConnectionSession = AndroidConnectionComposition.create(getApplicationContext(), this.connection, this.draftProfile,
                    this.onboardingActive ? ConnectionEntryModel.Origin.SETUP : ConnectionEntryModel.Origin.SETTINGS);
            this.sharedConnectionSession.getController().attach(this);
        }
        final AndroidConnectionComposition.Session visibleSession = this.sharedConnectionSession;
        boolean canRestore = !this.connectionLoadError.isEmpty()
                || (this.connection != null && this.connection.isTemporary() && this.secrets.exists());
        visibleSession.getController().setSavedConnectionRecovery(
                this.connectionLoadError.isEmpty() ? "" : "连接设置读取失败，请重试。",
                canRestore ? (this.connectionLoadError.isEmpty() ? "恢复已保存连接" : "重试读取已保存连接") : "");
        visibleSession.getController().foreground(this.resumed);
        // foreground can synchronously deliver a deferred commit and navigate away.
        if (this.sharedConnectionSession != visibleSession
                || (this.page != SyncPage.CONNECTION && !this.sharedAppreciationOnly)) return;
        // Existing readiness/diagnostic work can finish while this page is mounted.
        // Keep its legacy status targets detached rather than rendering a second connection UI.
        this.root = column();
        this.status = label("", 14, GREEN);
        this.scroll = null;
        this.renderedPage = this.page;
        AndroidConnectionComposition.show(this, visibleSession, selectedGameIdentity());
    }

    private void closeSharedConnection() {
        AndroidConnectionComposition.Session session = this.sharedConnectionSession;
        this.sharedConnectionSession = null;
        this.retainedConnectionUi = null;
        this.sharedAppreciationOnly = false;
        if (session != null) session.close();
    }

    @Override public void onBack(ConnectionEntryModel.Back destination) {
        if (destination == ConnectionEntryModel.Back.STAY || destination == ConnectionEntryModel.Back.CHOICE) return;
        if (this.sharedAppreciationOnly) {
            closeSharedConnection();
            render();
            return;
        }
        navigate(this.onboardingActive ? SyncPage.PATH : SyncPage.SETTINGS);
    }

    @Override public void onBusyChanged(boolean busy) {
        // The shared screen owns its operation state. Do not overwrite unrelated readiness work.
    }

    @Override public void onReloadSavedConnection() {
        if (this.busy || this.sharedConnectionSession == null) return;
        this.sharedConnectionSession.getController().foreground(false);
        this.sharedReloadPending = true;
        reloadSavedConnection();
    }

    @Override public void onCommitted(SharedConnectionConfig config) {
        this.pendingSharedCommit = config;
        drainSharedCommit();
    }

    private boolean drainSharedCommit() {
        if (this.pendingSharedCommit == null || this.busy || !this.resumed || isDestroyed()) return false;
        SharedConnectionConfig config = this.pendingSharedCommit;
        this.pendingSharedCommit = null;
        this.sharedCommitSerial++;
        this.connection = AndroidConnectionComposition.toStored(config);
        // Existing history/automatic guards use identity: the host and runtime must share this object.
        SyncRuntime.setConnection(this.connection);
        this.facade = null;
        this.connectionLoadError = "";
        this.inspection = null;
        this.history = null;
        this.selected = null;
        this.explicitHomeSelection = null;
        this.cloudVerified = false;
        this.progress = OnboardingProgress.PENDING;
        this.onboardingActive = true;
        closeSharedConnection();
        this.page = SyncPage.COMPARISON;
        render();
        refreshComparison(SyncPage.COMPARISON);
        return true;
    }

    /** Provider alone is not authority to forward saved credentials to the official service. */
    private static boolean isOfficialAccount(SecureConnectionStore.Connection account) {
        return account != null && SecureConnectionStore.Connection.PROVIDER_OFFICIAL.equals(account.provider)
                && OfficialServiceRegistrationClient.DAV_URL.equals(account.endpoint)
                && account.username != null && !account.username.isEmpty() && account.password != null;
    }

    private static TrustedEntitlementCache entitlementCache(android.content.Context app) {
        return new TrustedEntitlementCache(new File(app.getFilesDir(), "managed-account-expiry-v1").toPath());
    }

    private static int bootCount(android.content.Context app) {
        try { return Math.max(-1, android.provider.Settings.Global.getInt(app.getContentResolver(), "boot_count", -1)); }
        catch (RuntimeException unavailable) { return -1; }
    }

    private static void rememberEntitlement(android.content.Context app, SecureConnectionStore.Connection source,
                                            ManagedAccountClient.Entitlement entitlement) {
        if (!isOfficialAccount(source) || !source.username.equals(entitlement.username)) return;
        try {
            entitlementCache(app).write(new TrustedEntitlementCache.Record(source.endpoint, entitlement.username,
                    entitlement.expiresMs, entitlement.active, entitlement.serverTimeMs,
                    android.os.SystemClock.elapsedRealtime(), bootCount(app)));
        } catch (IOException | RuntimeException unavailable) { /* Optional display cache never grants or blocks access. */ }
    }

    private String officialAppreciationLabel(String username) {
        TrustedEntitlementCache.Record cached = entitlementCache(getApplicationContext())
                .read(OfficialServiceRegistrationClient.DAV_URL, username);
        return cached != null && cached.expired(android.os.SystemClock.elapsedRealtime(), bootCount(getApplicationContext()))
                ? "赞赏续期" : "赞赏";
    }

    private void showManagedAccount(SecureConnectionStore.Connection source) {
        if (this.busy || !isOfficialAccount(source) || isDestroyed()) return;
        closeSharedConnection();
        this.sharedConnectionSession = AndroidConnectionComposition.create(getApplicationContext(), source, source.profile,
                ConnectionEntryModel.Origin.SETTINGS);
        this.sharedAppreciationOnly = true;
        this.sharedConnectionSession.getController().attach(this);
        this.sharedConnectionSession.getController().foreground(this.resumed);
        this.sharedConnectionSession.getController().openAppreciationOnly();
        render();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reloadSavedConnection() {
        work(DiagnosticAction.CONFIGURE, "正在读取已保存连接…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda110
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
        return null;
    }

    /* renamed from: lambda$reloadSavedConnection$60$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m71x6d7dc9b8(Object obj) throws Exception {
        this.sharedReloadPending = false;
        closeSharedConnection();
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

    /** Shared only across Activity rotation; never writes credentials or save data to a Bundle. */
    private static final class AutomaticHistoryState {
        final AutoSyncHistoryRefresh refresh = new AutoSyncHistoryRefresh();
        AutoSyncHistoryRefresh.Ticket ticket;
        SecureConnectionStore.Connection connection;
        volatile AutomaticHistoryOutcome outcome;
        volatile boolean inFlight;
    }

    private static final class AutomaticHistoryOutcome {
        final AutoSyncHistoryRefresh.Ticket ticket;
        final SecureConnectionStore.Connection connection;
        AndroidSyncFacade facade;
        AndroidSyncFacade.Inspection inspection;
        SyncHistory history;
        Exception error;
        boolean applied;
        AutomaticHistoryOutcome(AutoSyncHistoryRefresh.Ticket ticket, SecureConnectionStore.Connection connection) {
            this.ticket = ticket; this.connection = connection;
        }
    }

    private boolean hasAutomaticHistoryRefresh() {
        try {
            AutoSyncStatusPolicy.Record record = AutoSyncService.historyRefreshRecord(this);
            return this.connection != null && this.connection == SyncRuntime.connection(this)
                    && (this.automaticHistoryState.outcome != null || (this.automaticHistoryState.refresh.needsRead(record)
                            && AutoSyncService.historyRefreshPending(this, record)))
                    && AutoSyncHistoryRefresh.matches(record, SyncRuntime.currentSelectionToken(this),
                            SyncRuntime.currentNamespace(this), SyncRuntime.connectionScope(this, this.connection));
        } catch (Exception unavailable) { return false; }
    }

    private boolean currentAutomaticHistory(AutoSyncHistoryRefresh.Ticket ticket, SecureConnectionStore.Connection connection) {
        try {
            return connection != null && this.connection == connection && SyncRuntime.connection(this) == connection
                    && this.automaticHistoryState.refresh.accepts(ticket, AutoSyncService.historyRefreshRecord(this),
                            SyncRuntime.currentSelectionToken(this), SyncRuntime.currentNamespace(this),
                            SyncRuntime.connectionScope(this, connection));
        } catch (Exception unavailable) { return false; }
    }

    /** A deliberate operation may produce a newer comparison. Cache that observation, not the old one. */
    private void rememberCurrentAutomaticHistory() {
        AutomaticHistoryState state = this.automaticHistoryState;
        if (this.busy || state.outcome != null || !this.cloudVerified || this.inspection == null || this.history == null
                || this.facade == null || !currentAutomaticHistory(state.ticket, state.connection)) return;
        AutomaticHistoryOutcome current = new AutomaticHistoryOutcome(state.ticket, state.connection);
        current.facade = this.facade; current.inspection = this.inspection; current.history = this.history;
        current.applied = true;
        state.outcome = current;
    }

    /** Terminal-session notification/resume only: one read, never a polling loop or another upload. */
    private void drainAutomaticHistoryRefresh() {
        if (!this.resumed || this.busy || isDestroyed() || this.page != SyncPage.HOME
                || this.connection == null || !canRequestSaveAccess() || this.installer.hasActiveRequest()
                || this.readinessState.restorePending) return;
        AutomaticHistoryOutcome completed = this.automaticHistoryState.outcome;
        if (completed != null) {
            if (currentAutomaticHistory(completed.ticket, completed.connection)) {
                if (!completed.applied) {
                    completed.applied = true;
                    if (completed.error != null) {
                        this.cloudVerified = false;
                        this.inspection = null;
                        this.history = null;
                        reportError(completed.error);
                    } else {
                        this.facade = completed.facade;
                        this.inspection = completed.inspection;
                        this.history = completed.history;
                        this.cloudVerified = completed.history != null;
                        if (this.rootGame != null && this.cloudVerified)
                            this.readinessState = this.readinessState.withAccessCode("READY");
                        if (this.history != null) {
                            boolean found = false;
                            for (AppendCommit commit : this.history.getCloud())
                                if (commit.getSnapshot().getId().equals(this.selected)) found = true;
                            if (!found) {
                                this.selected = this.history.getDefaultSnapshot();
                                this.explicitHomeSelection = null;
                            }
                        } else {
                            this.selected = null;
                            this.explicitHomeSelection = null;
                        }
                        this.notice = comparisonCopy();
                        this.details = this.inspection == null ? "" : safeText(this.inspection.getMessage());
                    }
                    render();
                }
                return;
            }
            this.automaticHistoryState.outcome = null;
        }
        if (this.automaticHistoryState.inFlight) return;
        final SecureConnectionStore.Connection captured = this.connection;
        final AutoSyncHistoryRefresh.Ticket ticket;
        try {
            if (captured != SyncRuntime.connection(this)) return;
            AutoSyncStatusPolicy.Record record = AutoSyncService.historyRefreshRecord(this);
            if (!AutoSyncService.historyRefreshPending(this, record)) return;
            ticket = this.automaticHistoryState.refresh.begin(record,
                    SyncRuntime.currentSelectionToken(this), SyncRuntime.currentNamespace(this),
                    SyncRuntime.connectionScope(this, captured), this.resumed && this.page == SyncPage.HOME, !this.busy);
        } catch (Exception unavailable) { return; }
        if (ticket == null) return;
        final AutomaticHistoryState state = this.automaticHistoryState;
        state.ticket = ticket; state.connection = captured; state.inFlight = true;
        work(DiagnosticAction.INSPECT, "正在检查存档…", () -> {
            // work() holds the global operation lock. A task paused before any read is deferred.
            // Rotation shares this holder, so the replacement Activity receives the same result.
            try {
                if (!this.resumed || !currentAutomaticHistory(ticket, captured)) {
                    state.refresh.deferBeforeRead(ticket);
                    return DiagnosticResult.SKIPPED;
                }
                if (!AutoSyncService.claimHistoryRefresh(this, ticket)) return DiagnosticResult.SKIPPED;
                AutomaticHistoryOutcome result = new AutomaticHistoryOutcome(ticket, captured);
                try {
                    result.facade = SyncRuntime.facade(this, captured);
                    result.inspection = result.facade.inspect();
                    DiagnosticLog.record(DiagnosticStage.VERIFY,
                            AndroidDiagnosticLogging.inspectionResult(result.inspection.getKind(), false),
                            result.inspection.getNeedsRestoreRecovery() ? DiagnosticFailure.RECOVERY_REQUIRED : DiagnosticFailure.NONE,
                            diagnosticInspectionDetails(result.inspection));
                    if (!"BLOCKED".equals(result.inspection.getKind()))
                        result.history = result.facade.historyPresentation(result.inspection);
                } catch (Exception error) {
                    DiagnosticLog.failure(DiagnosticStage.REFRESH, error);
                    result.error = SyncInspectionDiagnostics.at(SyncInspectionDiagnostics.Stage.INSPECT, error);
                }
                state.outcome = result;
                return result;
            } finally { state.inFlight = false; }
        }, ignored -> { /* Only drainAutomaticHistoryRefresh applies the scoped foreground result. */ }, true);
    }

    private void readComparison() throws Exception {
        final ComparisonProofState.Ticket reinspectionOwner = this.activeSharedComparisonClaim;
        final boolean ownedReinspection = this.comparisonProofState.beginReinspection(reinspectionOwner,
                currentComparisonContext(), isForegroundComparison());
        if (!ownedReinspection) this.comparisonProofState.reset();
        this.cloudVerified = false;
        this.history = null;
        this.inspection = null;
        // One facade and one observation own both the action proof and its timeline.
        // Drawing, paging, selecting, or dismissing the timeline must never re-export saves.
        SyncInspectionDiagnostics.Stage stage = SyncInspectionDiagnostics.Stage.RUNTIME;
        AutoSyncStatusPolicy.Record oldIdleFailure = AutoSyncService.beginManualInspection(this);
        try {
            AndroidSyncFacade activeFacade = sync();
            stage = SyncInspectionDiagnostics.Stage.INSPECT;
            AndroidSyncFacade.Inspection inspectionInspect = activeFacade.inspect();
            this.inspection = inspectionInspect;
            DiagnosticLog.record(DiagnosticStage.VERIFY,
                    AndroidDiagnosticLogging.inspectionResult(inspectionInspect.getKind(), false),
                    inspectionInspect.getNeedsRestoreRecovery() ? DiagnosticFailure.RECOVERY_REQUIRED : DiagnosticFailure.NONE,
                    diagnosticInspectionDetails(inspectionInspect));
            if ("BLOCKED".equals(inspectionInspect.getKind())) {
                return;
            }
            stage = SyncInspectionDiagnostics.Stage.HISTORY;
            this.history = activeFacade.historyPresentation(inspectionInspect);
            this.cloudVerified = true;
            AutoSyncService.verifiedManualInspection(this, oldIdleFailure, inspectionInspect);
            HashSet hashSet = new HashSet();
            Iterator<AppendCommit> it = this.history.getCloud().iterator();
            while (it.hasNext()) {
                hashSet.add(it.next().getSnapshot().getId());
            }
            String str = this.selected;
            if (str != null && !hashSet.contains(str)) {
                this.selected = null;
                this.explicitHomeSelection = null;
            }
            if (this.selected == null) {
                this.selected = this.history.getDefaultSnapshot();
            }
        } catch (Exception error) {
            // Keep existing structured details and recovery handling exactly as they were.
            if (InstallDiagnostics.find(error) != null || PreparationDiagnostics.find(error) != null
                    || BridgeDiagnostics.find(error) != null) throw error;
            throw SyncInspectionDiagnostics.at(stage, error);
        } finally {
            // This is failure ownership only. A refreshed observation never inherits action intent.
            if (ownedReinspection) this.comparisonProofState.finishReinspection(reinspectionOwner,
                    currentComparisonContext(), isForegroundComparison());
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
            resetPathPreview();
            this.cloudVerified = false;
            this.inspection = null;
            this.history = null;
        }
        work(DiagnosticAction.INSPECT, "正在检查存档…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda141
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

    private void renderSharedComparison() {
        if (this.comparisonProofState.bind(currentComparisonContext())) {
            this.comparisonDialogMessage = "";
            this.comparisonDialogDetails = "";
        }
        final int renderToken = ++this.sharedComparisonRenderToken;
        ComparisonProofState.Ticket dialog = this.comparisonProofState.dialog();
        SharedComparisonConfirmation confirmation = dialog == null ? null : new SharedComparisonConfirmation(
                dialog.token, dialog.stage == ComparisonProofState.Stage.MERGE_REVIEW
                    ? SharedComparisonConfirmationStage.MERGE_REVIEW : SharedComparisonConfirmationStage.CONFLICT,
                this.comparisonDialogMessage.isEmpty() ? "将先备份，再替换本机存档。请退出游戏，并暂停其他存档同步。" : this.comparisonDialogMessage,
                this.comparisonDialogDetails,
                dialog.stage == ComparisonProofState.Stage.CONFLICT && restoreSupported(),
                dialog.stage == ComparisonProofState.Stage.MERGE_REVIEW && restoreSupported());
        ComparisonProofState.Progress choice = this.comparisonProofState.progress();
        this.root = null;
        this.status = null;
        this.body = null;
        this.footer = null;
        this.scroll = null;
        this.renderedPage = this.page;
        AndroidOnboardingComposition.showComparison(this, this.inspection, this.history, selectedGameIdentity(),
                Build.MODEL, this.cloudVerified, restoreSupported(), this.connection != null,
                isForegroundComparison(), this.busy,
                choice == null ? null : SharedProgressChoice.valueOf(choice.name()), this.comparisonProofState.selectedId(),
                visibleNotice(), this.details, !this.diagnosticReport.isEmpty(), confirmation, this.feedbackError,
                new SharedComparisonActions() {
                    @Override public void back() { if (isCurrentComparisonAction(renderToken)) navigate(SyncPage.CONNECTION); }
                    @Override public void refresh() { if (isCurrentComparisonAction(renderToken)) refreshComparison(SyncPage.COMPARISON); }
                    @Override public void selectProgress(SharedProgressChoice value) {
                        if (isCurrentComparisonAction(renderToken)) selectSharedProgress(value);
                    }
                    @Override public void selectRemote(String snapshotId) {
                        if (isCurrentComparisonAction(renderToken)) selectSharedRemote(snapshotId);
                    }
                    @Override public void finish(SharedComparisonCompletion mode) {
                        if (isCurrentComparisonAction(renderToken)) finishSharedComparison(mode);
                    }
                    @Override public void handleConflict() { if (isCurrentComparisonAction(renderToken)) openSharedConflict(); }
                    @Override public void upload() { if (isCurrentComparisonAction(renderToken)) MainActivity.this.upload(); }
                    @Override public void openRecovery() { if (isCurrentComparisonAction(renderToken)) navigate(SyncPage.SETTINGS); }
                    @Override public void copyDiagnostics() { if (isCurrentComparisonAction(renderToken)) copyDiagnostic(); }
                    @Override public void confirmConflict(long token) { if (isCurrentComparisonAction(renderToken)) confirmSharedConflict(token); }
                    @Override public void previewMerge(long token) { if (isCurrentComparisonAction(renderToken)) previewSharedMerge(token); }
                    @Override public void confirmMerge(long token) { if (isCurrentComparisonAction(renderToken)) confirmSharedMerge(token); }
                    @Override public void cancelConflict(long token) { if (isCurrentComparisonAction(renderToken)) cancelSharedConflict(token); }
                });
    }

    private boolean isForegroundComparison() {
        return this.resumed && !isDestroyed() && this.page == SyncPage.COMPARISON && !this.sharedAppreciationOnly;
    }

    private boolean isCurrentComparisonAction(int renderToken) {
        return renderToken == this.sharedComparisonRenderToken && isForegroundComparison() && !this.busy;
    }

    private ComparisonProofState.Context currentComparisonContext() {
        if (this.facade == null || this.connection == null || this.inspection == null) return null;
        try {
            return new ComparisonProofState.Context(this.facade, this.connection, this.inspection,
                    SyncRuntime.currentSelectionToken(this) + "\n" + SyncRuntime.currentNamespace(this));
        } catch (Exception unavailable) { return null; }
    }

    private boolean usableSharedComparison() {
        return this.comparisonProofState.matches(currentComparisonContext()) && this.cloudVerified
                && this.inspection != null && kind() != null && kind() != OnboardingKind.BLOCKED
                && !this.inspection.getNeedsRestoreRecovery() && !this.inspection.isRetryablePending();
    }

    private boolean hasSharedRemote(String snapshotId) {
        if (snapshotId == null || this.inspection == null) return false;
        for (AndroidSyncFacade.Version remote : this.inspection.getRemoteVersions())
            if (snapshotId.equals(remote.getSnapshotId())) return true;
        return false;
    }

    private void selectSharedProgress(SharedProgressChoice choice) {
        if (!usableSharedComparison() || this.comparisonProofState.dialog() != null) return;
        boolean local = choice == SharedProgressChoice.LOCAL;
        boolean offered = local ? kind() == OnboardingKind.LOCAL_ONLY
                || kind() == OnboardingKind.DIFFERENT && this.inspection.getCanKeepLocalProgress()
                : kind() == OnboardingKind.REMOTE_ONLY
                || kind() == OnboardingKind.DIFFERENT && this.inspection.getCanKeepLocalProgress();
        if (!offered) return;
        String soleRemote = this.inspection.getRemoteVersions().size() == 1
                ? this.inspection.getRemoteVersions().get(0).getSnapshotId() : null;
        this.comparisonProofState.selectProgress(ComparisonProofState.Progress.valueOf(choice.name()), soleRemote);
        renderSharedComparison();
    }

    private void selectSharedRemote(String snapshotId) {
        if (!usableSharedComparison() || !hasSharedRemote(snapshotId)) return;
        if (this.comparisonProofState.selectRemote(snapshotId) == null) return;
        this.comparisonDialogMessage = "";
        this.comparisonDialogDetails = "";
        renderSharedComparison();
    }

    private ComparisonProofState.Ticket captureSharedFinish() {
        ComparisonProofState.Ticket proof = this.comparisonProofState.capture(currentComparisonContext(),
                isForegroundComparison(), this.busy);
        if (proof != null) this.activeSharedComparisonClaim = proof;
        return proof;
    }

    private void requireSharedComparisonProof(ComparisonProofState.Ticket proof) throws IOException {
        requireSharedComparisonProof(proof, false);
    }

    private void requireSharedComparisonProof(ComparisonProofState.Ticket proof, boolean allowPendingUpload) throws IOException {
        if (!this.comparisonProofState.claimedCurrent(proof, currentComparisonContext(), isForegroundComparison())
                || this.inspection == null || this.inspection.getNeedsRestoreRecovery()
                || (!this.cloudVerified && !(allowPendingUpload && this.inspection.isRetryablePending())))
            throw new IOException("存档或连接状态已变化，请重新比较后选择。");
    }

    private void finishSharedComparison(SharedComparisonCompletion mode) {
        if (!usableSharedComparison() || this.comparisonProofState.dialog() != null) return;
        ComparisonProofState.Progress choice = this.comparisonProofState.progress();
        switch (mode) {
            case EMPTY: if (kind() == OnboardingKind.BOTH_EMPTY) completeEmpty(); break;
            case CONFIRM:
                if (kind() == OnboardingKind.IDENTICAL && this.inspection.getCanCompleteWithoutTransfer()) completeReadyComparison();
                break;
            case ADOPT: if (kind() == OnboardingKind.IDENTICAL && this.inspection.getCanAdoptRemote()) adopt(); break;
            case KEEP_LOCAL:
                if (kind() == OnboardingKind.DIFFERENT && choice == ComparisonProofState.Progress.LOCAL
                        && this.inspection.getCanKeepLocalProgress()) completeLocalProgress();
                break;
            case UPLOAD_LOCAL:
                if (kind() == OnboardingKind.LOCAL_ONLY && choice == ComparisonProofState.Progress.LOCAL) upload();
                break;
            case RESTORE_REMOTE:
                if (kind() == OnboardingKind.REMOTE_ONLY && choice == ComparisonProofState.Progress.CLOUD)
                    restoreSharedRemote();
                break;
            default: break;
        }
    }

    private void restoreSharedRemote() {
        if (!usableSharedComparison() || kind() != OnboardingKind.REMOTE_ONLY
                || this.inspection.getLocalFileCount() != 0 || !restoreSupported()
                || !hasSharedRemote(this.comparisonProofState.selectedId())) return;
        final ComparisonProofState.Ticket proof = captureSharedFinish();
        if (proof == null) return;
        final AndroidSyncFacade.Inspection observed = (AndroidSyncFacade.Inspection) proof.context.inspection;
        operation(DiagnosticAction.RESTORE, "正在恢复云端存档…", () -> {
            requireSharedComparisonProof(proof);
            if (!"REMOTE_ONLY".equals(observed.getKind()) || observed.getLocalFileCount() != 0)
                throw new IOException("本机存档已变化，请重新比较。");
            requireRestoreSupport();
            return restoreForCurrentAccount(observed, proof.snapshotId, false, proof);
        }, "已恢复并核对游戏存档。");
    }

    private void openSharedConflict() {
        if (!usableSharedComparison() || (kind() != OnboardingKind.DIFFERENT && kind() != OnboardingKind.BRANCHED)) return;
        if (this.comparisonProofState.openConflict() == null) return;
        this.comparisonDialogMessage = "";
        this.comparisonDialogDetails = "";
        renderSharedComparison();
    }

    private void cancelSharedConflict(long token) {
        if (this.comparisonProofState.cancel(token, currentComparisonContext(), isForegroundComparison(), this.busy)) {
            this.comparisonDialogMessage = "";
            this.comparisonDialogDetails = "";
            renderSharedComparison();
        }
    }

    private ComparisonProofState.Ticket claimSharedConflict(long token, ComparisonProofState.Stage stage) {
        if (!usableSharedComparison() || !restoreSupported()) return null;
        ComparisonProofState.Ticket proof = this.comparisonProofState.claim(token, currentComparisonContext(),
                isForegroundComparison(), this.busy, stage);
        if (proof != null) this.activeSharedComparisonClaim = proof;
        return proof;
    }

    private void confirmSharedConflict(long token) {
        if (!hasSharedRemote(this.comparisonProofState.selectedId())
                || (kind() != OnboardingKind.DIFFERENT && kind() != OnboardingKind.BRANCHED)) return;
        final ComparisonProofState.Ticket proof = claimSharedConflict(token, ComparisonProofState.Stage.CONFLICT);
        if (proof == null) return;
        final AndroidSyncFacade owner = (AndroidSyncFacade) proof.context.facade;
        final AndroidSyncFacade.Inspection observed = (AndroidSyncFacade.Inspection) proof.context.inspection;
        operation(DiagnosticAction.RESOLVE, "正在恢复并核验所选进度…", () -> {
            requireSharedComparisonProof(proof);
            requireRestoreSupport();
            if ("BRANCHED".equals(observed.getKind())) {
                requireOfficialSyncAccess((SecureConnectionStore.Connection) proof.context.connection);
                requireSharedComparisonProof(proof);
                return owner.resolveConflict(observed, proof.snapshotId);
            }
            return restoreForCurrentAccount(observed, proof.snapshotId, true, proof);
        }, "已恢复并核对游戏存档。");
    }

    private static final class ComparisonMergeOutcome {
        AndroidSyncFacade.MergePreview preview;
        Exception failure;
    }

    private void requireSharedMergePreview(ComparisonProofState.Ticket proof) throws IOException {
        if (!this.comparisonProofState.current(proof.token, currentComparisonContext(), isForegroundComparison(),
                false, ComparisonProofState.Stage.CONFLICT) || !usableSharedComparison())
            throw new IOException("存档或连接状态已变化，请重新比较后选择。");
    }

    private void previewSharedMerge(long token) {
        if (!usableSharedComparison() || !restoreSupported()
                || !this.comparisonProofState.current(token, currentComparisonContext(), isForegroundComparison(),
                        this.busy, ComparisonProofState.Stage.CONFLICT)) return;
        final ComparisonProofState.Ticket proof = this.comparisonProofState.dialog();
        final AndroidSyncFacade owner = (AndroidSyncFacade) proof.context.facade;
        final AndroidSyncFacade.Inspection observed = (AndroidSyncFacade.Inspection) proof.context.inspection;
        work(DiagnosticAction.MERGE, "正在检查能否合并…", () -> {
            ComparisonMergeOutcome result = new ComparisonMergeOutcome();
            try {
                requireSharedMergePreview(proof);
                requireRestoreSupport();
                requireOfficialSyncAccess((SecureConnectionStore.Connection) proof.context.connection);
                requireSharedMergePreview(proof);
                result.preview = owner.previewMerge(observed);
            } catch (Exception failure) { result.failure = failure; }
            return result;
        }, result -> {
            if (!this.comparisonProofState.current(proof.token, currentComparisonContext(), isForegroundComparison(),
                    this.busy, ComparisonProofState.Stage.CONFLICT)) return;
            boolean allowed = result.failure == null && result.preview != null && result.preview.isAllowed();
            ComparisonProofState.Ticket reviewed = this.comparisonProofState.review(proof,
                    currentComparisonContext(), isForegroundComparison(), this.busy, allowed);
            if ((allowed && reviewed == null) || (!allowed && this.comparisonProofState.dialog() == null)) return;
            this.notice = "";
            this.feedbackError = false;
            if (allowed) {
                this.comparisonDialogMessage = "合并后使用 " + result.preview.getSlotCount() + " 个槽位（最多 6 个）。";
                this.comparisonDialogDetails = safeText(String.join("\n", result.preview.getDecisions()))
                        + "\n先备份再替换本机，并发布合并版本。";
            } else {
                this.comparisonDialogMessage = "无法安全合并，请选择要继续的进度。";
                this.comparisonDialogDetails = result.failure != null ? safeError(result.failure)
                        : result.preview == null ? "未能完成合并检查，请重试。" : safeText(result.preview.getMessage());
                if (result.failure != null) captureDiagnostic(result.failure);
            }
        });
    }

    private void confirmSharedMerge(long token) {
        final ComparisonProofState.Ticket proof = claimSharedConflict(token, ComparisonProofState.Stage.MERGE_REVIEW);
        if (proof == null) return;
        final AndroidSyncFacade owner = (AndroidSyncFacade) proof.context.facade;
        final AndroidSyncFacade.Inspection observed = (AndroidSyncFacade.Inspection) proof.context.inspection;
        operation(DiagnosticAction.MERGE, "正在应用并核验安全合并…", () -> {
            requireSharedComparisonProof(proof);
            requireRestoreSupport();
            requireOfficialSyncAccess((SecureConnectionStore.Connection) proof.context.connection);
            requireSharedComparisonProof(proof);
            return owner.mergeConflict(observed);
        }, "合并版本已写入游戏并发布。");
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
        final ComparisonProofState.Ticket proof = captureSharedFinish();
        if (proof == null) return;
        work(DiagnosticAction.RESOLVE, "正在确认同步基线…", () -> {
            requireSharedComparisonProof(proof);
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
        final ComparisonProofState.Ticket proof = captureSharedFinish();
        if (proof == null) return;
        work(DiagnosticAction.INSPECT, "正在重新核验本机进度与云端基线…", () -> {
            requireSharedComparisonProof(proof);
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
        final ComparisonProofState.Ticket proof = captureSharedFinish();
        if (proof == null) return;
        work(DiagnosticAction.CONFIGURE, "正在完成配置…", () -> {
            requireSharedComparisonProof(proof);
            return m29xb53dc5d7();
        }, this::m30x2ab7ec18);
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
        work(DiagnosticAction.RESOLVE, "正在保留当前选择…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda108
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
            return;
        }
        final boolean shared = this.page == SyncPage.COMPARISON;
        final ComparisonProofState.Ticket proof = shared ? captureSharedFinish() : null;
        if (shared && proof == null) return;
        operation(DiagnosticAction.PUSH, "正在上传本机存档…", () -> {
            if (proof != null) requireSharedComparisonProof(proof, true);
            return m146lambda$upload$71$iogithubxyz1001pdsyncandroidMainActivity(inspection);
        }, "本机存档已上传并核验。");
    }

    /* renamed from: lambda$upload$71$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m146lambda$upload$71$iogithubxyz1001pdsyncandroidMainActivity(AndroidSyncFacade.Inspection inspection) throws Exception {
        return sync().upload(inspection);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void adopt() {
        final AndroidSyncFacade.Inspection observed = this.inspection;
        if (observed == null || !observed.getCanAdoptRemote()) return;
        final boolean shared = this.page == SyncPage.COMPARISON;
        final ComparisonProofState.Ticket proof = shared ? captureSharedFinish() : null;
        if (shared && proof == null) return;
        operation(DiagnosticAction.RESOLVE, "正在关联相同存档…", () -> {
            if (proof != null) requireSharedComparisonProof(proof);
            return m15lambda$adopt$72$iogithubxyz1001pdsyncandroidMainActivity(observed);
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
            operation(DiagnosticAction.RESTORE, "正在校验、备份并恢复所选存档…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda13
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
        return restoreForCurrentAccount(inspection, str, false);
    }

    /* renamed from: lambda$requestRestore$74$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m142x859b98e(AndroidSyncFacade.Inspection inspection, String str) throws Exception {
        requireRestoreSupport();
        return restoreForCurrentAccount(inspection, str, true);
    }

    /** Expired official accounts cannot download or restore; renewal re-enables the usual path. */
    private AndroidSyncFacade.OperationResult restoreForCurrentAccount(AndroidSyncFacade.Inspection inspection,
                                                                       String snapshotId, boolean replace) throws Exception {
        return restoreForCurrentAccount(inspection, snapshotId, replace, null);
    }

    private AndroidSyncFacade.OperationResult restoreForCurrentAccount(AndroidSyncFacade.Inspection inspection,
            String snapshotId, boolean replace, ComparisonProofState.Ticket proof) throws Exception {
        final SecureConnectionStore.Connection account = this.connection;
        requireOfficialSyncAccess(account);
        if (proof != null) requireSharedComparisonProof(proof);
        AndroidSyncFacade owner = proof == null ? sync() : (AndroidSyncFacade) proof.context.facade;
        return owner.restoreSelected(inspection, snapshotId, replace);
    }

    private void requireOfficialSyncAccess(SecureConnectionStore.Connection account) throws Exception {
        if (account == null || this.connection != account) throw new IOException("连接已变化，请重新比较。");
        if (isOfficialAccount(account)) {
            ManagedAccountClient.Entitlement entitlement = new ManagedAccountClient(
                    account.endpoint, account.username, account.password).entitlement();
            if (this.connection != account) throw new IOException("连接已变化，请重新比较。");
            rememberEntitlement(getApplicationContext(), account, entitlement);
            entitlement.requireSyncAccess();
        }
    }

    /* renamed from: lambda$requestRestore$75$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m143x7dd3dfcf(final AndroidSyncFacade.Inspection inspection, final String str) {
        operation(DiagnosticAction.RESTORE, "正在校验、备份并恢复所选存档…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda91
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m142x859b98e(inspection, str);
            }
        }, "已恢复并核对游戏存档。");
    }

    private void renderConflict() {
        final LinearLayout ownerRoot = this.root;
        final AndroidSyncFacade.Inspection observed = this.inspection;
        final AndroidSyncFacade ownerFacade = this.facade;
        final SecureConnectionStore.Connection ownerConnection = this.connection;
        final HomeActionContext ownerContext = currentHomeContext(this.sharedHomeRenderToken);
        final SharedBranchChoiceActions actions = new SharedBranchChoiceActions() {
            private boolean current() {
                return root == ownerRoot && page == SyncPage.CONFLICT && resumed && !isDestroyed()
                        && !busy && !sharedAppreciationOnly && inspection == observed && facade == ownerFacade
                        && connection == ownerConnection && ownerContext != null
                        && ownerContext.accepts(currentHomeContext(sharedHomeRenderToken), true, busy);
            }
            private boolean hasBranch(String snapshotId) {
                if (observed == null || kind() != OnboardingKind.BRANCHED || !cloudVerified) return false;
                for (AndroidSyncFacade.Version branch : observed.getRemoteVersions())
                    if (branch.getSnapshotId().equals(snapshotId)) return true;
                return false;
            }
            private boolean canResolve() {
                return current() && observed != null && kind() == OnboardingKind.BRANCHED && cloudVerified
                        && connection != null && restoreSupported() && !restoreNeedsAttention()
                        && !observed.isRetryablePending();
            }
            @Override public void select(String snapshotId) {
                if (!current() || !hasBranch(snapshotId)) return;
                selected = snapshotId;
                render();
            }
            @Override public void back() { if (current()) m84xf7dc0e8(); }
            @Override public void refresh() { if (current()) m83x9a039aa7(); }
            @Override public void overwrite(String snapshotId) {
                if (canResolve() && hasBranch(snapshotId) && snapshotId.equals(selected))
                    m86xfa720d6a(observed, snapshotId);
            }
            @Override public void merge() { if (canResolve()) previewMerge(); }
            @Override public void preserveLocal() {
                if (current() && cloudVerified && observed != null && kind() == OnboardingKind.BRANCHED
                        && connection != null && !restoreNeedsAttention() && history != null
                        && !history.getCurrentProtected()) upload();
            }
        };
        // render() supplies a legacy root for this route. Replace all of its chrome, rather than
        // nesting the shared card inside a second heading, scroll view or footer.
        ownerRoot.removeAllViews();
        ownerRoot.setPadding(0, 0, 0, 0);
        ownerRoot.setOnApplyWindowInsetsListener(null);
        ownerRoot.addView(AndroidBranchChoiceComposition.view(this, observed, this.history,
                selectedGameIdentity(), this.selected, this.resumed && !isDestroyed(), this.busy,
                this.connection != null, this.cloudVerified, restoreSupported(), restoreNeedsAttention(),
                visibleNotice(), this.details, actions), new LinearLayout.LayoutParams(-1, -1));
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
        operation(DiagnosticAction.RESOLVE, "正在验证并发布分支选择…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda102
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
            work(DiagnosticAction.MERGE, "正在检查共同基线、单局身份与六槽容量…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda40
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
        operation(DiagnosticAction.MERGE, "正在应用并核验安全合并…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda118
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m61xf9a4e77a(inspection);
            }
        }, "合并版本已写入游戏并发布。");
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class OperationOutcome {
        String refreshFailure;
        DiagnosticFailure refreshDiagnosticFailure = DiagnosticFailure.NONE;
        boolean requiresBridgeUpgrade;
        AndroidSyncFacade.OperationResult result;

        private OperationOutcome() {
        }
    }

    private void operation(DiagnosticAction action, String str, final Task<AndroidSyncFacade.OperationResult> task, final String str2) {
        this.confirmedAction = null;
        this.confirmationMerge = null;
        work(action, str, new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda36
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
        DiagnosticLog.record(DiagnosticStage.VERIFY, operationOutcome.result.isConflict() ? DiagnosticResult.CONFLICT
                : operationOutcome.result.isPending() ? DiagnosticResult.PENDING : DiagnosticResult.SUCCEEDED);
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
            DiagnosticLog.failure(DiagnosticStage.REFRESH, e);
            this.cloudVerified = false;
            reportError(e);
            operationOutcome.refreshFailure = safeText(e.getMessage());
            operationOutcome.refreshDiagnosticFailure = AndroidDiagnosticLogging.classify(e);
        }
        if (!readyForSaves()) {
            DiagnosticLog.record(DiagnosticStage.REFRESH, DiagnosticResult.REJECTED,
                    AndroidDiagnosticLogging.readinessFailure(this.readinessState, this.bridgeAccessCode), diagnosticWorkDetails());
            this.cloudVerified = false;
            operationOutcome.refreshFailure = this.readinessState.statusDescription();
            operationOutcome.refreshDiagnosticFailure = AndroidDiagnosticLogging.readinessFailure(this.readinessState, this.bridgeAccessCode);
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
            this.page = this.onboardingActive ? SyncPage.COMPARISON : SyncPage.CONFLICT;
        } else if (operationOutcome.result.isPending()) {
            this.notice = "操作已保留待上传版本，请重试上传。";
            this.page = this.onboardingActive ? SyncPage.COMPARISON : SyncPage.HOME;
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
        boolean fromSettings = this.page == SyncPage.SETTINGS
                || this.page == SyncPage.CONFIRM && this.confirmationReturn == SyncPage.SETTINGS;
        SettingsActionContext settingsOwner = fromSettings ? currentSettingsContext(this.sharedSettingsRenderToken) : null;
        int settingsTicket = ++this.settingsConfirmationToken;
        this.confirmationReturn = this.page == SyncPage.CONFIRM ? this.confirmationReturn : this.page;
        this.confirmationTitle = str;
        this.confirmationText = str2;
        this.confirmationButton = str3;
        this.confirmedAction = fromSettings ? guardSettingsConfirmation(settingsOwner, settingsTicket, runnable) : runnable;
        this.confirmationMerge = fromSettings ? guardSettingsConfirmation(settingsOwner, settingsTicket, runnable2) : runnable2;
        navigate(SyncPage.CONFIRM);
    }

    private Runnable guardSettingsConfirmation(SettingsActionContext owner, int ticket, Runnable action) {
        if (owner == null || action == null) return null;
        return () -> {
            if (!this.resumed || isDestroyed() || this.busy || this.page != SyncPage.CONFIRM
                    || this.confirmationReturn != SyncPage.SETTINGS || ticket != this.settingsConfirmationToken) return;
            if (!owner.sameTarget(currentSettingsContext(this.sharedSettingsRenderToken))) {
                cancelConfirmation();
                this.notice = "设置已变化，请重新选择操作。";
                render();
                return;
            }
            ++this.settingsConfirmationToken;
            action.run();
        };
    }

    private void renderConfirmation() {
        final int token = this.settingsConfirmationToken;
        final Runnable confirm = this.confirmedAction;
        final Runnable merge = this.confirmationMerge;
        String title = this.confirmationTitle == null ? "操作前确认" : this.confirmationTitle;
        if (merge != null && "恢复存档".equals(title)) title = "如何处理冲突？";
        SharedConfirmationState state = new SharedConfirmationState(token, title,
                this.confirmationText == null ? "确认已失效，请重新选择。" : this.confirmationText,
                this.confirmationButton == null ? "确认继续" : this.confirmationButton,
                confirm != null, merge == null ? null : "合并", merge != null, this.busy,
                this.feedbackError ? visibleNotice() : "");
        this.body.addView(AndroidHomeDialogComposition.confirmationView(this, state,
                () -> {
                    if (isCurrentConfirmation(token) && this.confirmedAction == confirm && confirm != null) confirm.run();
                }, merge == null ? null : () -> {
                    if (isCurrentConfirmation(token) && this.confirmationMerge == merge) merge.run();
                }, () -> {
                    if (isCurrentConfirmation(token)) cancelConfirmation();
                }), new LinearLayout.LayoutParams(0, 0));
    }

    private boolean isCurrentConfirmation(int token) {
        return this.resumed && !isDestroyed() && !this.busy && this.page == SyncPage.CONFIRM
                && this.settingsConfirmationToken == token;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelConfirmation() {
        ++this.settingsConfirmationToken;
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

    /** Real settings values and capabilities; the shared page owns all settings layout. */
    private void renderSettings() {
        dismissSettingsDialog();
        final SettingsActionContext owner = currentSettingsContext(++this.sharedSettingsRenderToken);
        final SettingsCapabilities capabilities = settingsCapabilities();
        final boolean selectedGame = selectedGamePackage() != null;
        final boolean automaticSupported = selectedGame && automaticObservationSupported();
        final boolean automaticPaused = SyncRuntime.isAutoUploadPaused(this);
        List<SharedSettingsRow> rows = new ArrayList<>();
        rows.add(new SharedSettingsRow(SharedSettingsKey.GAME, "游戏", selectedGameLabel()
                + (selectedGame ? "\n" + settingsAccessStatus() : ""), "更换", capabilities.canChangeGame));
        rows.add(new SharedSettingsRow(SharedSettingsKey.CLOUD_CONNECTION, "云端连接", settingsConnectionValue(),
                "修改", !this.installer.hasActiveRequest()));
        if (isOfficialAccount(this.connection)) {
            rows.add(new SharedSettingsRow(SharedSettingsKey.ACCOUNT_APPRECIATION, "官方账号", this.connection.username,
                    officialAppreciationLabel(this.connection.username), !this.installer.hasActiveRequest()));
        }
        if (selectedGame) rows.add(new SharedSettingsRow(SharedSettingsKey.SAVE_PATH, "存档位置", savePathLabel(), null, false));
        rows.add(new SharedSettingsRow(SharedSettingsKey.AUTOMATIC_UPLOAD, "自动上传",
                !selectedGame ? "尚未选择游戏" : !automaticSupported ? "当前访问方式不支持"
                        : automaticPaused ? "已暂停" : this.progress == OnboardingProgress.COMPLETE ? "已开启" : "已开启 · 完成引导后生效",
                automaticSupported ? automaticPaused ? "开启" : "暂停" : null, capabilities.canToggleAutomatic));
        if (selectedGame) rows.add(new SharedSettingsRow(SharedSettingsKey.RECHECK_SAVES, "存档检查",
                restoreNeedsAttention() ? "有未完成恢复" : this.cloudVerified ? "已检查" : "待检查", "重新检查",
                !this.installer.hasActiveRequest()));
        rows.add(new SharedSettingsRow(SharedSettingsKey.REVIEW_SETUP, "四步引导",
                this.progress == OnboardingProgress.COMPLETE ? "已完成" : "尚未完成", "重新检查", capabilities.canChangeGame));
        if (selectedGame) rows.add(new SharedSettingsRow(SharedSettingsKey.LINK_PROFILE, "PC 档案关联",
                this.selectedNamespace.isEmpty() ? "按游戏来源隔离" : this.selectedNamespace + " · "
                        + (this.connection == null ? this.draftProfile : this.connection.profile), "修改", capabilities.canChangeGame));
        if (Build.VERSION.SDK_INT >= 33) {
            boolean notificationsAllowed = checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
            rows.add(new SharedSettingsRow(SharedSettingsKey.SYNC_NOTIFICATIONS, "同步通知",
                    notificationsAllowed ? "已允许" : "未允许", notificationsAllowed ? null : "允许", !notificationsAllowed));
        }
        rows.add(new SharedSettingsRow(SharedSettingsKey.EXPORT_LOGS, "日志", diagnosticExport.busy() ? "正在导出…" : "导出到所选目录",
                "导出日志", !diagnosticExport.busy()));
        if (capabilities.hasMaintenance() || !this.details.isEmpty()) {
            rows.add(new SharedSettingsRow(SharedSettingsKey.MAINTENANCE, "恢复与维护",
                    restoreNeedsAttention() ? "有未完成恢复" : this.inspection != null && this.inspection.isRetryablePending()
                            ? "有待上传操作" : "按需检查", "打开", true));
        }
        this.root = null;
        this.status = null;
        this.body = null;
        this.footer = null;
        this.scroll = null;
        this.renderedPage = this.page;
        SharedSettingsState state = new SharedSettingsState(selectedGameIdentity(), rows, isForegroundSettings(),
                this.busy, true, visibleNotice(), this.feedbackError);
        AndroidOnboardingComposition.showSettings(this, state, new SharedSettingsActions() {
            @Override public void perform(SharedSettingsKey key) {
                if (!isCurrentSettingsAction(owner)) return;
                SettingsCapabilities current = settingsCapabilities();
                switch (key) {
                    case GAME:
                        if (current.canChangeGame) m129xcee01eef();
                        break;
                    case CLOUD_CONNECTION:
                        if (!installer.hasActiveRequest()) m131xb9d46b71();
                        break;
                    case ACCOUNT_APPRECIATION:
                        if (!installer.hasActiveRequest() && isOfficialAccount(MainActivity.this.connection))
                            showManagedAccount(MainActivity.this.connection);
                        break;
                    case AUTOMATIC_UPLOAD:
                        if (current.canToggleAutomatic) m128x5965f8ae(null, SyncRuntime.isAutoUploadPaused(MainActivity.this));
                        break;
                    case RECHECK_SAVES:
                        if (selectedGamePackage() != null && !installer.hasActiveRequest()) m130x445a4530();
                        break;
                    case REVIEW_SETUP:
                        if (current.canChangeGame) m132x2f4e91b2();
                        break;
                    case LINK_PROFILE:
                        if (selectedGamePackage() != null && current.canChangeGame) editNamespace();
                        break;
                    case SYNC_NOTIFICATIONS:
                        if (Build.VERSION.SDK_INT >= 33) m133xa4c8b7f3();
                        break;
                    case EXPORT_LOGS:
                        if (!diagnosticExport.busy()) chooseDiagnosticDirectory();
                        break;
                    case MAINTENANCE:
                        if (current.hasMaintenance() || !details.isEmpty()) showSettingsMaintenance(owner);
                        break;
                    case SAVE_PATH:
                        break; // The trusted private path is read-only on Android.
                }
            }
            @Override public void back() {
                if (isCurrentSettingsAction(owner)) m125x62eb3f4e();
            }
        });
    }

    private boolean isForegroundSettings() {
        return this.resumed && !isDestroyed() && this.page == SyncPage.SETTINGS && !this.sharedAppreciationOnly;
    }

    private SettingsActionContext currentSettingsContext(int renderToken) {
        try {
            return new SettingsActionContext(renderToken, this.connection, this.facade, this.inspection,
                    SyncRuntime.currentSelectionToken(this) + "\n" + SyncRuntime.currentNamespace(this));
        } catch (Exception unavailable) { return null; }
    }

    private boolean isCurrentSettingsContext(SettingsActionContext owner) {
        return owner != null && owner.accepts(currentSettingsContext(this.sharedSettingsRenderToken), isForegroundSettings(), this.busy);
    }

    private boolean isCurrentSettingsAction(SettingsActionContext owner) {
        return this.settingsDialog == null && isCurrentSettingsContext(owner);
    }

    private SettingsCapabilities settingsCapabilities() {
        return new SettingsCapabilities(selectedGamePackage() != null, automaticObservationSupported(), restoreSupported(),
                backupRecoverySupported(), restoreNeedsAttention(), this.inspection != null && this.inspection.isRetryablePending(),
                this.connection != null, this.connection != null || !this.connectionLoadError.isEmpty(), this.installer.hasActiveRequest());
    }

    private String settingsConnectionValue() {
        if (this.connection == null) return this.connectionLoadError.isEmpty() ? "尚未连接" : "连接信息待检查";
        return (isOfficialAccount(this.connection) ? "官方账号" : "WebDAV")
                + (this.connection.username.isEmpty() ? "" : " · " + this.connection.username);
    }

    /** Concise observed status only. Root selection alone is never displayed as verified access. */
    private String settingsAccessStatus() {
        String mode = this.rootGame != null ? "Root" : this.readinessState.embeddedBridge ? "内置存档桥" : "旧版存档桥";
        String state;
        if (this.installer.hasActiveRequest()) state = "等待系统确认";
        else if (restoreNeedsAttention()) state = "需检查恢复";
        else if (readyForSaves()) state = "可读取存档";
        else if (this.readinessState.access == InstallationReadiness.AccessStatus.FIRST_LAUNCH_NEEDED) state = "需启动游戏";
        else if (this.readinessState.access == InstallationReadiness.AccessStatus.GAME_BUSY) state = "游戏运行中";
        else state = "待检查";
        return mode + " · " + state;
    }

    /** One entry point; every item invokes the existing operation/confirmation rather than a new flow. */
    private void showSettingsMaintenance(SettingsActionContext owner) {
        if (!isCurrentSettingsAction(owner)) return;
        SettingsCapabilities capabilities = settingsCapabilities();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (capabilities.canRecoverRestore) {
            labels.add("检查未完成恢复");
            actions.add(() -> { if (settingsCapabilities().canRecoverRestore) m135x8fbd0475(); });
        }
        if (capabilities.canDiscardRestore) {
            labels.add("保留备份并放弃未完成操作");
            actions.add(() -> { if (settingsCapabilities().canDiscardRestore) m112xc8b22aac(); });
        }
        if (capabilities.canRecoverBackup) {
            labels.add(this.rootGame != null ? "核对 Root 中断事务" : "恢复中断前备份");
            actions.add(() -> { if (settingsCapabilities().canRecoverBackup) m116x9e9ac3b0(); });
        }
        if (capabilities.canArchiveUpload) {
            labels.add("检查并归档中断的待上传操作");
            actions.add(() -> { if (settingsCapabilities().canArchiveUpload) m120x74835cb4(); });
        }
        if (capabilities.canForgetConnection) {
            labels.add("忘记已保存的连接信息");
            actions.add(() -> { if (settingsCapabilities().canForgetConnection) m124xed71190d(); });
        }
        if (!this.details.isEmpty()) {
            labels.add("查看诊断");
            actions.add(() -> showSettingsDiagnostics(owner));
        }
        if (labels.isEmpty()) return;
        showSettingsDialog(new AlertDialog.Builder(this).setTitle("恢复与维护")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    if (this.settingsDialog != dialog || !isCurrentSettingsContext(owner)) return;
                    this.settingsDialog = null;
                    actions.get(index).run();
                }).setNegativeButton("取消", null).create());
    }

    private void showSettingsDiagnostics(SettingsActionContext owner) {
        if (!isCurrentSettingsAction(owner) || this.details.isEmpty()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("诊断信息")
                .setMessage(safeText(this.details)).setNegativeButton("关闭", null);
        if (!this.diagnosticReport.isEmpty()) builder.setPositiveButton("复制诊断", (dialog, which) -> {
            if (this.settingsDialog == dialog && isCurrentSettingsContext(owner)) copyDiagnostic();
        });
        showSettingsDialog(builder.create());
    }

    private void showSettingsDialog(AlertDialog dialog) {
        this.settingsDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (this.settingsDialog == dialog) this.settingsDialog = null; });
        dialog.show();
    }

    private void dismissSettingsDialog() {
        AlertDialog dialog = this.settingsDialog;
        this.settingsDialog = null;
        if (dialog != null) dialog.dismiss();
    }

    /* renamed from: lambda$renderSettings$90$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m128x5965f8ae(CompoundButton compoundButton, final boolean z) {
        work(DiagnosticAction.CONFIGURE, "正在保存自动上传设置…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda38
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
        operation(DiagnosticAction.RESTORE, "正在核对未完成恢复…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda107
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
        operation(DiagnosticAction.RESTORE, "正在归档未完成恢复…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda2
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
        work(DiagnosticAction.RECOVERY, this.rootGame != null ? "正在核对 Root 中断事务…" : "正在恢复存档桥中断前备份…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda27
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
        work(DiagnosticAction.ARCHIVE, "正在归档待上传版本…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda148
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
        work(DiagnosticAction.CONFIGURE, "正在删除保存的连接信息…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda150
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
        final SettingsActionContext owner = currentSettingsContext(this.sharedSettingsRenderToken);
        if (!isCurrentSettingsAction(owner) || !settingsCapabilities().canChangeGame) return;
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
        showSettingsDialog(new AlertDialog.Builder(this).setTitle("关联同一游戏的 PC 档案").setView(linearLayoutColumn).setMessage("仅当你已确认两端是同一游戏、兼容存档格式时使用。官方破碎的已有空间通常为 shattered，档案通常为 default。留空游戏标识可恢复按来源隔离。").setNegativeButton("取消", (DialogInterface.OnClickListener) null).setPositiveButton("检查关联", new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda9
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                if (MainActivity.this.settingsDialog != dialogInterface || !isCurrentSettingsContext(owner)
                        || !settingsCapabilities().canChangeGame) return;
                MainActivity.this.settingsDialog = null;
                MainActivity.this.m47x6b6b32d0(editText, editText2, dialogInterface, i);
            }
        }).create());
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
        work(DiagnosticAction.CONFIGURE, "正在保存云端关联…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda42
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
        this.explicitHomeSelection = null;
        this.cloudVerified = false;
        this.progress = OnboardingProgress.PENDING;
        this.onboardingActive = true;
        loadDraft();
        this.draftProfile = str;
        this.page = SyncPage.CONNECTION;
        this.notice = "请测试连接，再比较存档。";
    }

    private void beginOnboarding(final SyncPage syncPage) {
        work(DiagnosticAction.INSPECT, "正在重新检查配置…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda103
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
        resetPathPreview();
        this.draftLoaded = false;
        this.page = syncPage;
        this.notice = "请检查所选游戏。";
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void rename(final String snapshotId) {
        if (snapshotId == null) {
            editCurrent();
            return;
        }
        final HomeActionContext owner = currentHomeContext(this.sharedHomeRenderToken);
        SyncHistory observedHistory = this.history;
        String alias = observedHistory == null ? null : observedHistory.getAliases().get(snapshotId);
        AndroidHomeDialogComposition.showNaming(this, this.sharedHomeRenderToken, alias == null ? "" : alias,
                "保存在此设备，不改写已发布的历史。", name -> {
                    if (!isCurrentHomeAction(owner)) {
                        this.notice = "存档已变化，请重新命名。";
                        render();
                        return;
                    }
                    work(DiagnosticAction.CONFIGURE, "正在保存名称…", () -> {
                        sync().renameHistory(snapshotId, name);
                        // An explicit update refreshes the full observation, never only its mirror.
                        readComparison();
                        return null;
                    }, result -> this.notice = "名称已保存。");
                }, null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void editCurrent() {
        if (this.inspection == null || !this.cloudVerified) {
            refreshComparison(SyncPage.HOME);
            return;
        }
        final HomeActionContext owner = currentHomeContext(this.sharedHomeRenderToken);
        final AndroidSyncFacade.Inspection observed = this.inspection;
        final String initialName = this.history == null ? "" : this.history.getCurrentName();
        AndroidHomeDialogComposition.showNaming(this, this.sharedHomeRenderToken, initialName, "", name -> {
            if (!isCurrentHomeAction(owner)) {
                this.notice = "存档已变化，请重新命名。";
                render();
                return;
            }
            work(DiagnosticAction.CONFIGURE, "正在保存名称…", () -> {
                sync().renameCurrent(observed, name);
                readComparison();
                return null;
            }, result -> this.notice = "名称已保存。");
        }, () -> {
            if (isCurrentHomeAction(owner)) chooseRunIdentity();
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
        operation(DiagnosticAction.RESOLVE, "正在记录新局身份…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda31
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
        operation(DiagnosticAction.RESOLVE, "正在记录单局身份…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda161
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
        work(DiagnosticAction.ACCESS_CHECK, "正在核对游戏内置存档桥激活入口，现有游戏保持不变…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda21
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
        resetPathPreview();
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
            work(DiagnosticAction.LAUNCH, "正在核对首次启动所需的游戏与内置存档桥身份…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda97
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
        resetPathPreview();
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
        work(DiagnosticAction.LAUNCH, "正在核对所选游戏…", new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda132
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

    private void chooseDiagnosticDirectory() {
        if (diagnosticExport.busy() || isDestroyed()) return;
        diagnosticPickerReturn = true;
        diagnosticExport.chooseDirectory(this);
        if (!diagnosticExport.selecting()) diagnosticPickerReturn = false;
        render();
    }

    void onDiagnosticExportChanged() {
        if (!this.resumed || isDestroyed()) return;
        String message = diagnosticExport.consumeOutcome();
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        if (!this.busy) render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 8103 && isForegroundSettings() && !this.busy) render();
    }

    @Override // android.app.Activity
    protected void onActivityResult(int i, int i2, Intent intent) {
        super.onActivityResult(i, i2, intent);
        if (i == AndroidDiagnosticExport.REQUEST_DIRECTORY) {
            diagnosticExport.onDirectoryResult(this, i2, intent);
            return;
        }
        if (i == 8103) {
            this.readinessState = InstallationReadiness.checking(this.prepared != null, this.installer.hasActiveRequest());
            this.bridgeAccessCode = "NOT_CHECKED";
            this.localPreview = null;
            resetPathPreview();
            this.cloudVerified = false;
            this.notice = i2 == -1 ? "游戏内置存档桥页面已返回，正在重新核验连接与存档访问。" : "游戏内置存档桥页面已关闭，正在重新检查；尚不能确认存档访问就绪。";
            requestReadinessRefresh();
            return;
        }
        if (i == PICK_APK) this.gameFilePickerOpen = false;
        if (i == PICK_APK && (i2 != RESULT_OK || intent == null)) {
            render(); // Cancel keeps the current prepared game and Root selection unchanged.
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
        if (this.sharedConnectionSession != null) {
            String editingPassword = this.sharedConnectionSession.getController().getState().getPassword();
            if (!editingPassword.isEmpty()) str = str.replace(editingPassword, "[已隐藏]");
        }
        String strReplaceAll = str.replaceAll("(?i)(authorization|password|passwd|token)\\s*[:=]\\s*\\S+", "$1=[已隐藏]");
        if (strReplaceAll.length() <= 1500) {
            return strReplaceAll;
        }
        return strReplaceAll.substring(0, 1500) + "…";
    }

    private String safeError(Exception exc) {
        if (exc instanceof ManagedAccountClient.ManagedAccountException)
            return ((ManagedAccountClient.ManagedAccountException) exc).userMessage();
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
            return isOfficialAccount(this.connection) ? "无法登录，请检查账号或续期状态。"
                    : "连接失败，请检查用户名、密码和访问权限。";
        }
        return NoviceCopy.feedback(strSafeText, "操作未完成，请查看详细信息后重试。");
    }
}
