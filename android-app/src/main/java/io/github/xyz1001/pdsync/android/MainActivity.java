package io.github.xyz1001.pdsync.android;

import static pdsync.i18n.AndroidHostMessages.*;
import pdsync.i18n.UiLocale;

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
import pdsync.DiagnosticTiming;
import pdsync.DiagnosticPhase;
import pdsync.AppendCommit;
import pdsync.DirectorySnapshotMetadata;
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
    private static final int ALLOW_INSTALL = 8106;
    private static final int SYSTEM_INSTALL = 8105;
    private static final int EXTERNAL_INSTALL = 8107;
    private final AndroidDiagnosticExport diagnosticExport = AndroidDiagnosticExport.instance();
    private boolean diagnosticPickerReturn;
    private final SharedDiagnosticAction wizardDiagnosticExport = new SharedDiagnosticAction(this::chooseDiagnosticDirectory);
    private LinearLayout body;
    private boolean busy;
    private boolean cloudVerified;
    private HomeActionContext homeObservationOwner;
    private String confirmationButton;
    private boolean confirmationDestructive;
    private Runnable confirmationMerge;
    private SyncPage confirmationReturn;
    private String confirmationText;
    private String confirmationTitle;
    private Runnable confirmedAction;
    private SecureConnectionStore.Connection connection;
    private OfficialNamespacePreparation preparedOfficialNamespace = new OfficialNamespacePreparation();
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
    private SharedHistoryNamingController homeNaming;
    private int sharedSettingsRenderToken;
    private SharedSettingsState confirmationSettingsBackdrop;
    private int settingsConfirmationToken;
    private AlertDialog settingsDialog;
    private AlertDialog gameModeDialog;
    private int gameModeDialogToken;
    private String explicitHomeSelection;
    private DiagnosticAction activeWorkAction = DiagnosticAction.OTHER;
    private AndroidSyncFacade.Inspection inspection;
    private InstallCoordinator installer;
    private SaveSummary localPreview;
    private GamePreparationService preparation;
    private GamePreparationService.PreparedPair prepared;
    private GamePreparationService.InstalledGame selectedInstalledGame;
    private InstalledBridgeBinding installedBridge;
    private Exception setupProbeFailure;
    private String setupOutcome;
    private SetupSession setupSession = new SetupSession();
    private String openedConfirmationNonce = "";
    private boolean systemReturnPending;
    private boolean activationReturnPending;
    private int pendingSystemChecks;
    private final Handler setupHandler = new Handler(Looper.getMainLooper());
    private final HomeArchiveReadState archiveReads = new HomeArchiveReadState();

    private interface SetupDone<T> { void accept(MainActivity owner, T value) throws Exception; }
    private static final class SetupSession {
        boolean systemUiDeparted;
        boolean externalCheckPending;
        long confirmationLaunchAttemptAt;
        final GameSetupFlow flow = new GameSetupFlow();
        final GameModeChoice<Uri> modeChoice = new GameModeChoice<>();
        GameModeChoice.Source<Uri> selectedSource;
        GameModeChoice.Mode accessMode, previousAccessMode;
        SharedGameIdentity pendingGameIdentity, selectedFileIdentity;
        GameModeChoice.Request<Uri> fileIdentityRequest;
        String modeChoiceError;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        MainActivity owner;
        Runnable completion;
        boolean running;
        boolean installationAdvancePending;
        boolean activationAdvancePending;
        String automaticInstallTarget;
        String externalOfferNonce;
        void attach(MainActivity activity) {
            owner = activity;
            if (completion != null) { Runnable next = completion; completion = null; next.run(); }
        }
        void complete(Runnable result) {
            if (owner == null || owner.isDestroyed()) completion = result; else result.run();
        }
    }

    private static final class SetupSnapshot {
        final GamePreparationService.PreparedPair prepared;
        final GamePreparationService.InstalledGame candidate;
        final InstalledBridgeBinding binding;
        final SelectedRootGame rootGame;
        final GameAccessBackend.Capabilities capabilities;
        final String namespace, bridgeCode, connectionError, outcome;
        final InstallationReadiness readiness;
        final SecureConnectionStore.Connection connection;
        final OnboardingProgress progress;
        final boolean initialLoad;
        SetupSnapshot(MainActivity activity) {
            prepared = activity.prepared; candidate = activity.selectedInstalledGame; binding = activity.installedBridge;
            rootGame = activity.rootGame; capabilities = activity.accessCapabilities; namespace = activity.selectedNamespace;
            bridgeCode = activity.bridgeAccessCode; connectionError = activity.connectionLoadError; outcome = activity.setupOutcome;
            readiness = activity.readinessState; connection = activity.connection; progress = activity.progress;
            initialLoad = activity.initialLoad;
        }
    }

    private void applySetupSnapshot(SetupSnapshot snapshot) {
        this.prepared = snapshot.prepared; this.rootGame = snapshot.rootGame;
        this.selectedInstalledGame = snapshot.candidate; this.installedBridge = snapshot.binding;
        this.accessCapabilities = snapshot.capabilities; this.selectedNamespace = snapshot.namespace;
        this.readinessState = snapshot.readiness; this.bridgeAccessCode = snapshot.bridgeCode;
        this.connection = snapshot.connection; this.connectionLoadError = snapshot.connectionError; this.setupOutcome = snapshot.outcome;
        this.progress = snapshot.progress; this.initialLoad = snapshot.initialLoad;
    }

    private <T> void setupWork(DiagnosticAction action, String message, Task<T> task, SetupDone<T> done) {
        if (this.busy || this.setupSession.running || isDestroyed()) return;
        cancelDetachedHomeRead();
        final SetupSession session = this.setupSession;
        final long generation = session.flow.generation();
        final String target = session.flow.target();
        final long token = session.flow.beginWork();
        session.running = true; this.busy = true; this.setupProbeFailure = null; this.feedbackError = false; this.notice = message; render();
        final MainActivity source = this;
        final DiagnosticTrace trace = DiagnosticLog.begin(action, false);
        session.executor.execute(() -> {
            T value = null; Exception failure = null;
            try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(trace)) {
                trace.record(AndroidDiagnosticLogging.stage(action), DiagnosticResult.STARTED);
                synchronized (SyncRuntime.OPERATION_LOCK) { value = task.run(); }
                if (source.setupProbeFailure != null) {
                    AndroidDiagnosticLogging.failure(trace, DiagnosticStage.WORK, source.setupProbeFailure);
                    AndroidDiagnosticLogging.failure(trace, DiagnosticStage.FINISHED, source.setupProbeFailure);
                } else {
                    trace.record(DiagnosticStage.WORK, DiagnosticResult.SUCCEEDED);
                    trace.record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED);
                }
            } catch (Exception error) {
                failure = error; AndroidDiagnosticLogging.failure(trace, DiagnosticStage.FINISHED, error);
            }
            final T result = value; final Exception error = failure;
            new Handler(Looper.getMainLooper()).post(() -> session.complete(() -> {
                MainActivity owner = session.owner;
                if (owner == null || !session.flow.finishWork(token)) return;
                session.running = false; owner.busy = false;
                if (!session.flow.matches(generation, target)) { owner.render(); return; }
                owner.applySetupSnapshot(new SetupSnapshot(source));
                if (error != null) {
                    if (session.flow.pending() == GameSetupFlow.Pending.ACTIVATION) session.flow.cancelPending();
                    if (action == DiagnosticAction.PREPARE && session.selectedSource != null) {
                        session.modeChoice.offer(session.selectedSource);
                        session.modeChoiceError = owner.safeError(error);
                    }
                    owner.reportError(error);
                    if (action == DiagnosticAction.INSTALL && owner.resumed && owner.installer.confirmationLaunchBlocked()
                            && !owner.installer.requestNonce().equals(session.externalOfferNonce)) {
                        session.externalOfferNonce = owner.installer.requestNonce();
                        owner.offerExternalInstaller();
                    }
                }
                else try { done.accept(owner, result); } catch (Exception nextError) { owner.reportError(nextError); }
                owner.render(); owner.drainReadinessRefresh(); owner.drainAutomaticHistoryRefresh(); owner.drainExternalInstallation();
            }));
        });
    }
    private SelectedRootGame rootGame;
    private GameAccessBackend.Capabilities accessCapabilities;
    private final PathPreviewState pathPreviewState = new PathPreviewState();
    private boolean pathPreviewPosted;
    private int sharedPathRenderToken;
    private int sharedGameRenderToken;
    private boolean gameFilePickerOpen;
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
    private RenewalArchiveReadState renewalArchiveRead = new RenewalArchiveReadState();
    private RetainedConnectionUi retainedConnectionUi;
    private OnBackPressedCallback navigationBack;

    /** Memory-only rotation state. Credentials and pending auth never enter saved instance state. */
    private static final class RetainedConnectionUi {
        final boolean onboarding, draftLoaded, appreciationOnly, filePickerOpen;
        final SyncPage page;
        final String profile;
        final AndroidConnectionComposition.Session session;
        final SharedConnectionConfig pendingCommit;
        final AutomaticHistoryState automaticHistory;
        final RenewalArchiveReadState renewalRead;
        final OfficialNamespacePreparation preparedNamespace;
        final SetupSession setup;
        final SetupSnapshot snapshot;
        final InstallCoordinator installer;
        final String openedNonce;
        final boolean systemReturn, activationReturn;
        final int checks;
        RetainedConnectionUi(MainActivity activity) {
            automaticHistory = activity.automaticHistoryState;
            renewalRead = activity.renewalArchiveRead;
            preparedNamespace = activity.preparedOfficialNamespace;
            setup = activity.setupSession; snapshot = new SetupSnapshot(activity); installer = activity.installer;
            openedNonce = activity.openedConfirmationNonce; systemReturn = activity.systemReturnPending;
            activationReturn = activity.activationReturnPending; checks = activity.pendingSystemChecks;
            onboarding = activity.onboardingActive;
            draftLoaded = activity.draftLoaded;
            page = activity.page;
            profile = activity.draftProfile;
            session = activity.sharedConnectionSession;
            pendingCommit = activity.pendingSharedCommit;
            appreciationOnly = activity.sharedAppreciationOnly;
            filePickerOpen = activity.gameFilePickerOpen;
        }
    }

    /* renamed from: io, reason: collision with root package name */
    private final ExecutorService f9io = Executors.newSingleThreadExecutor();
    private final ExecutorService homeReader = Executors.newSingleThreadExecutor();
    private volatile DetachedHomeRead detachedHomeRead;
    private final ThreadLocal<OfficialOperationLease> officialOperationLease = new ThreadLocal<>();
    private SyncPage page = SyncPage.DISCOVERY;
    private boolean initialLoad = true;
    private boolean onboardingActive = true;
    private String selectedNamespace = "";
    private String notice = SELECT_A_GAME_0.text();
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
            } else if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                    || Intent.ACTION_PACKAGE_REPLACED.equals(intent.getAction())) {
                if (intent.getData() != null && MainActivity.this.installer.externalInstallObservation()
                        && MainActivity.this.installer.pendingPackage().equals(intent.getData().getSchemeSpecificPart())) {
                    MainActivity.this.setupSession.externalCheckPending = true;
                    MainActivity.this.drainExternalInstallation();
                }
            } else {
                MainActivity.this.pendingSystemChecks = 0;
                MainActivity.this.drainPendingSetup();
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
        UiLocale.setLocale(getResources().getConfiguration().getLocales().get(0));
        super.onCreate(bundle);
        // One inset owner on every supported SDK, including before enforced edge-to-edge.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
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
            this.page = AndroidUiFlowPolicy.restoredPage(this.page);
            this.selected = bundle.getString("selected");
            this.explicitHomeSelection = bundle.getString("home.explicitSelection");
            this.historyPage = bundle.getInt("historyPage", 0);
            this.installedPickerState.setQuery(bundle.getString("installedPicker.query", ""));
            this.installedPickerState.setShowAll(bundle.getBoolean("installedPicker.showAll", false));
            this.gameFilePickerOpen = bundle.getBoolean("gameFilePicker.open", false);
        }
        if (this.retainedConnectionUi != null) restoreConnectionUi(this.retainedConnectionUi);
        this.setupSession.attach(this);
        this.busy = this.setupSession.running;
        render();
    }

    @Override public Object onRetainCustomNonConfigurationInstance() {
        if (this.sharedConnectionSession != null) this.sharedConnectionSession.getController().detach();
        return new RetainedConnectionUi(this);
    }

    private void restoreConnectionUi(RetainedConnectionUi retained) {
        this.automaticHistoryState = retained.automaticHistory;
        this.renewalArchiveRead = retained.renewalRead;
        this.preparedOfficialNamespace = retained.preparedNamespace;
        this.setupSession = retained.setup; this.installer = retained.installer;
        this.openedConfirmationNonce = retained.openedNonce; this.systemReturnPending = retained.systemReturn;
        this.activationReturnPending = retained.activationReturn; this.pendingSystemChecks = retained.checks;
        applySetupSnapshot(retained.snapshot);
        this.onboardingActive = retained.onboarding;
        this.draftProfile = retained.profile;
        this.draftLoaded = retained.draftLoaded;
        this.page = AndroidUiFlowPolicy.restoredPage(retained.page);
        this.sharedConnectionSession = retained.session;
        this.pendingSharedCommit = retained.pendingCommit;
        this.sharedAppreciationOnly = retained.appreciationOnly;
        this.gameFilePickerOpen = retained.filePickerOpen;
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
        bundle.putBoolean("gameFilePicker.open", this.gameFilePickerOpen);
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        final int commitBeforeResume = this.sharedCommitSerial;
        this.resumed = true;
        if (this.installer.externalInstallObservation()) this.setupSession.externalCheckPending = true;
        if (this.setupSession.systemUiDeparted && this.installer.hasPendingResult()) {
            this.systemReturnPending = true; this.pendingSystemChecks = 0;
        }
        this.setupSession.systemUiDeparted = false;
        if (this.sharedConnectionSession != null) this.sharedConnectionSession.getController().foreground(true);
        if (!this.receiverRegistered) {
            IntentFilter changes = new IntentFilter("io.github.xyz1001.pdsync.android.INSTALL_CHANGED");
            changes.addAction(AutoSyncService.HISTORY_CHANGED);
            RuntimePlatformCompat.registerInternalReceiver(this, this.installChanged, changes);
            this.receiverRegistered = true;
            // Separate protected system-only filter: no exported custom event or URI grant.
            IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            packages.addAction(Intent.ACTION_PACKAGE_REPLACED); packages.addDataScheme("package");
            registerReceiver(this.installChanged, packages);
        }
        onDiagnosticExportChanged();
        if (diagnosticPickerReturn || diagnosticExport.busy()) {
            if (!diagnosticExport.selecting()) diagnosticPickerReturn = false;
            return; // Returning from the local log picker must not inspect Root or the network.
        }
        // A queued commit enters HOME, whose refresh already checks readiness.
        // Do not queue a second refresh that would erase that result.
        if (commitBeforeResume != this.sharedCommitSerial || drainSharedCommit()) return;
        try {
            AutoSyncService.knownReturn(this);
        } catch (RuntimeException unused) {
        }
        if (this.setupSession.modeChoice.pending() != null) { render(); return; }
        if (this.gameFilePickerOpen) return;
        if (this.initialLoad && !this.setupSession.running) refreshReadiness();
        else { drainRenewalArchiveRead(); drainExternalInstallation(); drainPendingSetup(); }
    }

    @Override // android.app.Activity
    protected void onPause() {
        if (this.setupSession.flow.pending() == GameSetupFlow.Pending.SYSTEM_CONFIRMATION
                && this.installer.confirmationWasLaunched()) {
            this.setupSession.systemUiDeparted = true;
            AndroidDiagnosticLogging.confirmationEvent("CONFIRMATION_DEPARTURE", DiagnosticResult.PENDING);
        }
        this.resumed = false;
        cancelDetachedHomeRead();
        ++this.sharedHomeRenderToken;
        if (this.homeNaming != null) this.homeNaming.close();
        ++this.sharedSettingsRenderToken;
        dismissSettingsDialog();
        dismissGameModeDialog();
        invalidatePathPreview();
        if (this.sharedConnectionSession != null) this.sharedConnectionSession.getController().foreground(false);
        if (!diagnosticExport.selecting()) AutoSyncService.recordDeparture(this);
        super.onPause();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        cancelDetachedHomeRead();
        this.archiveReads.close();
        ++this.sharedHomeRenderToken;
        if (this.homeNaming != null) this.homeNaming.close();
        ++this.sharedSettingsRenderToken;
        dismissSettingsDialog();
        dismissGameModeDialog();
        invalidatePathPreview();
        diagnosticExport.detach(this);
        if (this.sharedConnectionSession != null) {
            this.sharedConnectionSession.getController().detach();
            if (!isChangingConfigurations()) closeSharedConnection();
        }
        if (this.receiverRegistered) {
            unregisterReceiver(this.installChanged);
        }
        if (this.setupSession.owner == this) this.setupSession.owner = null;
        this.setupHandler.removeCallbacksAndMessages(null);
        if (!isChangingConfigurations()) {
            this.setupSession.modeChoice.discard();
            this.setupSession.executor.shutdown();
        }
        this.f9io.shutdown();
        this.homeReader.shutdownNow();
        super.onDestroy();
    }

    @Override // android.app.Activity
    public void onBackPressed() {
        if (this.sharedConnectionSession != null && (this.page == SyncPage.CONNECTION || this.sharedAppreciationOnly)) {
            this.sharedConnectionSession.getController().back();
            return;
        }
        if (this.busy) {
            this.notice = AN_OPERATION_IS_IN_PROGRESS_PLEASE_1.text();
            if (this.page == SyncPage.PATH && !this.sharedAppreciationOnly) renderSharedSavePath();
            else if (this.status != null) this.status.setText(AN_OPERATION_IS_IN_PROGRESS_PLEASE_1.text());
            else render();
            return;
        }
        if (this.page == SyncPage.DISCOVERY && this.setupSession.modeChoice.awaiting(this.setupSession.modeChoice.pending())) {
            cancelInlineAccessSelection();
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
        cancelDetachedHomeRead();
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
        final DiagnosticTiming.Scope queueWait = DiagnosticTiming.start(trace, DiagnosticPhase.QUEUE_WAIT);
        this.f9io.execute(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda56
            @Override // java.lang.Runnable
            public final void run() {
                queueWait.close();
                MainActivity.this.m150lambda$work$2$iogithubxyz1001pdsyncandroidMainActivity(task, done, trace);
            }
        });
    }

    /* renamed from: lambda$work$2$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m150lambda$work$2$iogithubxyz1001pdsyncandroidMainActivity(Task task, final Done done, final DiagnosticTrace trace) {
        final Object objRun;
        try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(trace)) {
            trace.record(AndroidDiagnosticLogging.stage(trace.getAction()), DiagnosticResult.STARTED);
            try (DiagnosticTiming.Scope lockWait = DiagnosticTiming.start(trace, DiagnosticPhase.OPERATION_LOCK_WAIT)) {
                synchronized (SyncRuntime.OPERATION_LOCK) {
                    lockWait.close();
                    try (OfficialOperationLease lease = new OfficialOperationLease()) {
                        officialOperationLease.set(lease);
                        try { objRun = task.run(); }
                        catch (Exception failure) {
                            invalidatePreparedOfficialNamespace();
                            throw failure;
                        }
                        finally { officialOperationLease.remove(); }
                    }
                }
            }
            final DiagnosticResult result = diagnosticWorkResult(trace.getAction(), objRun);
            final DiagnosticFailure failure = diagnosticWorkFailure(trace.getAction(), objRun, result);
            final DiagnosticDetails details = diagnosticWorkDetails();
            trace.record(DiagnosticStage.WORK, result, failure, details);
            final DiagnosticTiming.Scope uiCompletion = DiagnosticTiming.start(trace, DiagnosticPhase.UI_COMPLETION);
            runOnUiThread(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda105
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m148lambda$work$0$iogithubxyz1001pdsyncandroidMainActivity(done, objRun, trace, result, failure, details, uiCompletion);
                }
            });
        } catch (Exception e) {
            AndroidDiagnosticLogging.failure(trace, AndroidDiagnosticLogging.stage(trace.getAction()), e);
            final DiagnosticTiming.Scope uiCompletion = DiagnosticTiming.start(trace, DiagnosticPhase.UI_COMPLETION);
            runOnUiThread(new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda106
                @Override // java.lang.Runnable
                public final void run() {
                    try {
                        if (MainActivity.this.isDestroyed()) uiCompletion.canceled();
                        MainActivity.this.m149lambda$work$1$iogithubxyz1001pdsyncandroidMainActivity(e);
                    } catch (RuntimeException | Error failure) {
                        uiCompletion.failed(failure);
                        throw failure;
                    } finally {
                        uiCompletion.close();
                        AndroidDiagnosticLogging.failure(trace, DiagnosticStage.FINISHED, e);
                    }
                }
            });
        }
    }

    /* renamed from: lambda$work$0$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m148lambda$work$0$iogithubxyz1001pdsyncandroidMainActivity(Done done, Object obj, DiagnosticTrace trace, DiagnosticResult result, DiagnosticFailure failure, DiagnosticDetails details, DiagnosticTiming.Scope uiCompletion) {
        if (isDestroyed()) {
            uiCompletion.canceled();
            trace.record(DiagnosticStage.FINISHED, result, failure, details);
            return;
        }
        this.busy = false;
        Throwable completionFailure = null;
        try {
            try (AndroidDiagnosticLogging.Scope ignored = AndroidDiagnosticLogging.bind(trace)) {
                done.accept(obj);
                if (trace.getAction() == DiagnosticAction.LAUNCH)
                    trace.record(DiagnosticStage.GAME_LAUNCH, DiagnosticResult.SUCCEEDED);
                rememberCurrentAutomaticHistory();
            } catch (Exception e) {
                completionFailure = e;
                AndroidDiagnosticLogging.failure(trace, trace.getAction() == DiagnosticAction.LAUNCH
                        ? DiagnosticStage.GAME_LAUNCH : DiagnosticStage.REFRESH, e);
                reportError(e);
            }
            render();
            drainReadinessRefresh();
            drainRenewalArchiveRead();
            drainAutomaticHistoryRefresh();
            drainExternalInstallation();
        } catch (RuntimeException | Error error) {
            completionFailure = error;
            throw error;
        } finally {
            if (completionFailure != null) {
                uiCompletion.failed(completionFailure);
                AndroidDiagnosticLogging.failure(trace, DiagnosticStage.FINISHED, completionFailure);
            } else {
                uiCompletion.close();
                trace.record(DiagnosticStage.FINISHED, result, failure, details);
            }
        }
    }

    /* renamed from: lambda$work$1$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m149lambda$work$1$iogithubxyz1001pdsyncandroidMainActivity(Exception exc) {
        if (isDestroyed()) {
            return;
        }
        this.busy = false;
        if (this.sharedReloadPending) {
            this.sharedReloadPending = false;
            this.connectionLoadError = COULD_NOT_READ_CONNECTION_SETTINGS_PLEASE_2.text();
        }
        if (!(exc instanceof StaleArchiveReadException)) reportError(exc);
        rememberCurrentAutomaticHistory();
        render();
        drainReadinessRefresh();
        drainRenewalArchiveRead();
        drainAutomaticHistoryRefresh();
        drainExternalInstallation();
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
            this.notice = NoviceCopy.feedback(safeText(BridgeDiagnostics.find(exc).getMessage()), COULD_NOT_READ_SAVES_PLEASE_RETRY_3.text());
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
                this.diagnosticReport += "\nbridge_mode=embedded-v1\nprovider_auth=actual_binder_package\nsignature_permission_required=false";
            if (BridgeDiagnostics.find(exc).saveLayout != null)
                this.diagnosticReport += DIAGNOSTIC_PRIVACY.text();
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
            Toast.makeText(this, THE_SYSTEM_CLIPBOARD_IS_UNAVAILABLE_4.text(), 0).show();
            return;
        }
        ClipData clipDataNewPlainText = ClipData.newPlainText(DIAGNOSTIC_CLIPBOARD_LABEL.text(), this.diagnosticReport);
        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clipDataNewPlainText.getDescription().setExtras(persistableBundle);
        try {
            clipboardManager.setPrimaryClip(clipDataNewPlainText);
            Toast.makeText(this, REDACTED_DIAGNOSTIC_INFORMATION_COPIED_5.text(), 0).show();
        } catch (RuntimeException unused) {
            Toast.makeText(this, COULD_NOT_WRITE_TO_THE_CLIPBOARD_6.text(), 0).show();
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

    private boolean canAdvanceGameSelection() {
        if (this.setupSession.modeChoice.pending() != null) return false;
        if (this.setupSession.flow.rootAuthorized()) return !restoreNeedsAttention();
        if (this.rootGame == null && this.installer.acceptedInstalledComponent(this.installedBridge))
            return !this.installer.hasActiveRequest() && !restoreNeedsAttention();
        return this.rootGame == null ? canRequestSaveAccess() : readyForSaves() && !restoreNeedsAttention();
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
        this.notice = THE_CURRENT_SAVE_BACKEND_DOES_NOT_7.text();
        render();
        return false;
    }

    private void requireRestoreSupport() throws Exception {
        if (!SyncRuntime.backend(this).capabilities().restoreSupported)
            throw new IOException(THE_CURRENT_SAVE_ACCESS_METHOD_DOES_8.text());
    }

    private String selectedGamePackage() {
        return this.selectedInstalledGame != null ? this.selectedInstalledGame.packageName
                : this.rootGame != null ? this.rootGame.gamePackage : this.installedBridge != null ? this.installedBridge.gamePackage
                : this.prepared == null ? null : this.prepared.gamePackage;
    }

    private SharedGameIdentity selectedGameIdentity() {
        if (this.selectedInstalledGame != null) return new SharedGameIdentity(this.selectedInstalledGame.label,
                this.selectedInstalledGame.versionName, AndroidGameIcon.load(this, this.selectedInstalledGame.packageName, "installed", null));
        if (this.installedBridge != null) return new SharedGameIdentity(this.installedBridge.gameLabel,
                this.installedBridge.gameVersionName, AndroidGameIcon.load(this, this.installedBridge.gamePackage, this.installedBridge.sourceApkSetSha256, null));
        if (this.rootGame != null) return new SharedGameIdentity(this.rootGame.gameLabel, this.rootGame.gameVersionName,
                AndroidGameIcon.load(this, this.rootGame.gamePackage, this.rootGame.sourceApkSetSha256, null));
        if (this.prepared != null) return new SharedGameIdentity(this.prepared.gameLabel, this.prepared.gameVersionName,
                AndroidGameIcon.loadArchive(this, this.prepared.gamePackage, this.prepared.gameApk));
        if (this.setupSession.selectedSource != null && this.setupSession.selectedSource.apks != null)
            return this.setupSession.selectedFileIdentity;
        return null;
    }

    private String selectedGameLabel() {
        return this.rootGame != null ? this.rootGame.gameLabel + " " + this.rootGame.gameVersionName
                : this.prepared == null ? NO_GAME_SELECTED_9.text() : this.prepared.gameLabel + " " + this.prepared.gameVersionName;
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
        PackageInfo installed = PackageTrust.installed(this, this.rootGame.gamePackage);
        if (installed.applicationInfo == null || installed.applicationInfo.uid != this.rootGame.uid
                || PackageTrust.versionCode(installed) != this.rootGame.gameVersionCode
                || installed.lastUpdateTime != this.rootGame.lastUpdateTime)
            throw new IOException(THE_SELECTED_GAME_HAS_CHANGED_PLEASE_10.text());
        this.readinessState = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                true, this.installer.hasActiveRequest(), pending, InstallationReadiness.AccessStatus.NOT_CHECKED);
    }

    private void checkRootAccess() throws Exception {
        checkRootAccess(false);
    }

    private void checkRootAccess(boolean setupOnly) throws Exception {
        if (!this.resumed) throw new IOException(RETURN_TO_THE_APP_BEFORE_CHECKING_11.text());
        readInstallationReadiness();
        if (this.rootGame == null) throw new IOException(THE_SELECTED_ACCESS_METHOD_HAS_CHANGED_12.text());
        if (this.installer.hasActiveRequest()) throw new IOException(HANDLE_THE_CURRENT_SYSTEM_INSTALLATION_REQUEST_13.text());
        if (hasAnyRestoreJournal()) {
            // Same-target authorization remains possible after a crash. Switching is still blocked.
            SyncRuntime.probeRootAuthorization(this);
            this.bridgeAccessCode = "ROOT_RECOVERY_REQUIRED";
            this.readinessState = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                    true, false, true, InstallationReadiness.AccessStatus.RECOVERY_NEEDED);
            return;
        }
        if (setupOnly) SyncRuntime.probeRootAuthorization(this);
        else SyncRuntime.backend(this).inspectReady();
        this.bridgeAccessCode = "READY";
        this.readinessState = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                true, false, false, InstallationReadiness.AccessStatus.READY);
    }

    private void requestRootAuthorization() {
        if (!this.resumed || this.busy) return;
        final String packageName = selectedGamePackage();
        if (packageName == null) return;
        final long generation = this.setupSession.flow.generation();
        this.setupOutcome = null;
        setupWork(DiagnosticAction.ACCESS_CHECK, REQUESTING_ROOT_ACCESS_14.text(), () -> {
            try { RootSetupAuthorization.probe(getApplicationContext(), packageName); }
            catch (IOException failure) {
                String code = failure.getMessage();
                if (code == null) code = "ROOT_ACCESS_FAILED";
                switch (code) {
                    case "ROOT_UNAVAILABLE": case "ROOT_DENIED_OR_EXITED": case "ROOT_NOT_ROOT":
                    case "ROOT_TIMEOUT": case "ROOT_CANCELLED": case "ROOT_TARGET_CHANGED":
                    case "ROOT_DIAGNOSTIC_LIMIT": case "ROOT_PIPE_FAILED": case "ROOT_INVALID_REPLY": break;
                    default: code = "ROOT_ACCESS_FAILED";
                }
                throw new BridgeDiagnostics.Failure(BridgeDiagnostics.Stage.SAVE_READINESS, code,
                        ROOT_ACCESS_WAS_NOT_GRANTED_RETRY_15.text(), failure);
            }
            return null;
        }, (owner, ignored) -> {
            owner.setupSession.flow.authorizeRoot(generation, packageName);
            owner.notice = ""; // The selected Root card already shows its verified status.
        });
    }

    private void launchSelectedRootGame() {
        if (this.rootGame == null) return;
        if (this.readinessState.access != InstallationReadiness.AccessStatus.FIRST_LAUNCH_NEEDED) {
            launchGame();
            return;
        }
        final SelectedRootGame chosen = this.rootGame;
        work(DiagnosticAction.LAUNCH, CHECKING_THE_SELECTED_GAME_S_LAUNCH_16.text(), () -> {
            if (this.installer.hasActiveRequest() || hasAnyRestoreJournal())
                throw new IOException(HANDLE_THE_UNFINISHED_RESTORE_OR_SYSTEM_17.text());
            if (!chosen.matches(SyncRuntime.rootGame(this))) throw new IOException(THE_SELECTED_GAME_HAS_CHANGED_PLEASE_10.text());
            SyncRuntime.backend(this).targetGame();
            Intent launch = getPackageManager().getLaunchIntentForPackage(chosen.gamePackage);
            if (launch == null) throw new IOException(THE_SELECTED_GAME_HAS_NO_AVAILABLE_18.text());
            cancelControlledLaunch();
            AutoSyncService.cancelForConnectionChange(this);
            return launch;
        }, launch -> {
            this.readinessState = this.readinessState.withAccessCode("NOT_CHECKED");
            this.localPreview = null;
            this.inspection = null;
            this.history = null;
            this.cloudVerified = false;
            this.notice = GAME_LAUNCH_REQUESTED_AFTER_EXITING_NORMALLY_19.text();
            startActivity(launch);
        });
    }

    private void chooseRootInstalled() {
        String selectedPackage = selectedGamePackage();
        work(DiagnosticAction.INSPECT, LISTING_INSTALLED_APPS_WITHOUT_MAKING_CHANGES_20.text(), () -> {
            if (this.installer.hasActiveRequest() || hasAnyRestoreJournal())
                throw new IOException(HANDLE_THE_UNFINISHED_RESTORE_OR_SYSTEM_21.text());
            return InstalledAppCatalog.load(this, selectedPackage);
        }, games -> InstalledAppPickerDialog.show(this, ROOT_MODE_SELECT_AN_INSTALLED_GAME_22.text(), games,
                selectedPackage, installedPickerState, this::selectRootGame));
    }

    private void selectRootGame(String packageName) {
        work(DiagnosticAction.INSPECT, CHECKING_THE_INSTALLED_GAME_S_IDENTITY_23.text(), () -> {
            if (restoreNeedsAttention()) throw new IOException(CHECK_THE_CURRENT_GAME_S_UNFINISHED_24.text());
            SelectedRootGame chosen = new RootTargetResolver(this).selectInstalled(packageName);
            SyncRuntime.setRootGame(this, chosen);
            cancelControlledLaunch();
            resetAccessSelection();
            readInstallationReadiness();
            return null;
        }, ignored -> this.notice = ROOT_MODE_SELECTED_SAVE_ACCESS_HAS_25.text());
    }

    private void switchToEmbedded() {
        work(DiagnosticAction.CONFIGURE, SWITCHING_TO_THE_BUILT_IN_SAVE_26.text(), () -> {
            if (restoreNeedsAttention()) throw new IOException(CHECK_THE_CURRENT_GAME_S_UNFINISHED_27.text());
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
        if (!this.readinessRefreshPending || !this.resumed || this.busy || isDestroyed()
                || this.setupSession.modeChoice.pending() != null) {
            return;
        }
        refreshReadiness();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refreshReadiness() {
        if (this.busy || isDestroyed() || this.setupSession.modeChoice.pending() != null) {
            return;
        }
        this.readinessRefreshPending = false;
        if (this.automaticHistoryState.outcome != null) this.automaticHistoryState.outcome.applied = false;
        final boolean z = this.initialLoad;
        this.readinessState = InstallationReadiness.checking(this.prepared != null, this.installer.hasActiveRequest());
        this.localPreview = null;
        resetPathPreview();
        this.cloudVerified = false;
        this.inspection = null;
        this.history = null;
        setupWork(DiagnosticAction.ACCESS_CHECK, CHECKING_GAME_COMPONENTS_28.text(), () -> m68x764cc8a4(z),
                (owner, value) -> {
                    if (owner.setupSession.flow.target() == null && owner.selectedGamePackage() != null)
                        owner.setupSession.flow.select(owner.selectedGamePackage());
                    owner.m69xebc6eee5(z, value); owner.maybeActivateSetupComponent(); owner.drainPendingSetup();
                });
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
        if (this.progress != OnboardingProgress.COMPLETE) {
            z2 = true;
        }
        this.onboardingActive = z2;
        if (!canRequestSaveAccess() && !canAdvanceGameSelection()) {
            this.onboardingActive = true;
            this.page = this.rootGame != null && this.readinessState.restorePending ? SyncPage.SETTINGS : SyncPage.DISCOVERY;
        } else if (this.connection == null && this.page != SyncPage.CONNECTION) {
            this.onboardingActive = true;
            this.page = SyncPage.DISCOVERY;
        } else {
            this.page = this.onboardingActive ? SyncPage.CONNECTION : SyncPage.HOME;
        }
        if (!this.feedbackError) this.notice = !readyForSaves() ? this.readinessState.statusDescription() : "";
        if (this.retainedConnectionUi != null
                && (canRequestSaveAccess() || canAdvanceGameSelection() || this.retainedConnectionUi.session != null)) {
            RetainedConnectionUi retained = this.retainedConnectionUi;
            this.retainedConnectionUi = null;
            // onCreate already restored/attached the live controller. Readiness may finish
            // after a commit callback; never overwrite its pending result with the old snapshot.
            this.onboardingActive = retained.onboarding;
            this.page = AndroidUiFlowPolicy.restoredPage(retained.page);
            readInitialHomeHistory();
            return;
        }
        if (this.connectionLoadError.isEmpty()) {
            readInitialHomeHistory();
            return;
        }
        if (canRequestSaveAccess()) {
            this.page = SyncPage.CONNECTION;
        }
        this.notice = COULD_NOT_READ_CONNECTION_SETTINGS_PLEASE_2.text();
        this.feedbackError = true;
    }

    /** One read after cold-start readiness; render/resume do not enqueue additional reads. */
    private void readInitialHomeHistory() {
        if (this.page == SyncPage.HOME && this.connection != null && this.rootGame == null
                && readyForSaves() && !hasAutomaticHistoryRefresh()) {
            // The verified bridge handshake is already complete. Do not repeat readiness, or
            // turn metadata-only Root restoration into a permission request/save export.
            refreshComparison(SyncPage.HOME, false);
        }
    }

    private void authenticateInstalledBridge(InstalledBridgeBinding binding) throws Exception {
        this.bridgeAccessCode = "NOT_CHECKED";
        this.readinessState = InstallationReadiness.embedded(InstallationReadiness.PackageStatus.VERIFIED,
                true, this.installer.hasActiveRequest(), hasAnyRestoreJournal(), InstallationReadiness.AccessStatus.NOT_CHECKED);
        try {
            new BridgeClient(this, binding, null).authenticateAccess();
            this.bridgeAccessCode = "READY";
        } catch (Exception error) {
            this.setupProbeFailure = error;
            this.bridgeAccessCode = BridgeDiagnostics.code(error);
            if (this.bridgeAccessCode == null) this.bridgeAccessCode = "PACKAGE_TRUST_FAILED";
            DiagnosticLog.record(DiagnosticStage.READINESS, DiagnosticResult.FAILED,
                    AndroidDiagnosticLogging.classify(error), AndroidDiagnosticLogging.failureDetails(error));
            captureDiagnostic(error);
        }
        this.readinessState = this.readinessState.withAccessCode(this.bridgeAccessCode);
        this.selectedNamespace = SyncRuntime.currentNamespace(this);
    }

    private void readInstallationReadiness() throws Exception {
        this.bridgeAccessCode = "NOT_CHECKED";
        this.rootGame = SyncRuntime.rootGame(this);
        if (this.rootGame != null) { readRootReadiness(); return; }
        this.accessCapabilities = null;
        if (this.installer.externalInstallObservation()) this.installer.checkExternalInstall();
        if (!this.installer.mayReuseInstalledSelection() && this.prepared == null)
            this.prepared = this.preparation.reloadPreparedMetadata();
        if (keepSelectedFileInstallationPending()) return;
        this.installedBridge = SyncRuntime.installedBridge(this);
        if (this.installedBridge == null && this.prepared == null) {
            this.prepared = this.preparation.reloadPreparedMetadata();
            if (keepSelectedFileInstallationPending()) return;
        }
        if (this.installedBridge == null && this.prepared != null)
            this.installedBridge = InstalledBridgeResolver.resolve(this, this.prepared.gamePackage);
        if (this.installedBridge != null) {
            SyncRuntime.setInstalledBridge(this, this.installedBridge);
            if (this.page == SyncPage.DISCOVERY && this.installer.acceptedInstalledComponent(this.installedBridge)
                    && (this.connection == null || SyncRuntime.onboardingProgress(this, this.connection) != OnboardingProgress.COMPLETE)) {
                // Step one confirms the package component only. Access/activation starts with HOME inspection.
                this.readinessState = InstallationReadiness.embedded(InstallationReadiness.PackageStatus.VERIFIED,
                        true, false, hasAnyRestoreJournal(), InstallationReadiness.AccessStatus.NOT_CHECKED);
                this.selectedNamespace = SyncRuntime.currentNamespace(this);
            } else authenticateInstalledBridge(this.installedBridge);
            return;
        }
        this.readinessState = this.prepared == null ? InstallationReadiness.noSelection()
                : InstallationReadiness.embedded(packageStatus(false, this.prepared.gameStrategy), false,
                        this.installer.hasActiveRequest(), hasAnyRestoreJournal(), InstallationReadiness.AccessStatus.NOT_CHECKED);
    }

    private boolean keepSelectedFileInstallationPending() throws Exception {
        if (!this.installer.requiresSelectedFileInstallation(this.prepared)) return false;
        this.installedBridge = null;
        this.readinessState = InstallationReadiness.embedded(packageStatus(false, this.prepared.gameStrategy),
                false, this.installer.hasActiveRequest(), hasAnyRestoreJournal(), InstallationReadiness.AccessStatus.NOT_CHECKED);
        return true;
    }

    private static InstallationReadiness.PackageStatus packageStatus(boolean z, GamePreparationService.InstallStrategy installStrategy) {
        if (z) {
            return InstallationReadiness.PackageStatus.VERIFIED;
        }
        return InstallationReadiness.PackageStatus.valueOf(installStrategy.name());
    }

    private void navigate(SyncPage syncPage) {
        syncPage = AndroidUiFlowPolicy.currentPage(syncPage);
        if (this.busy) {
            return;
        }
        if (this.page != syncPage) {
            cancelDetachedHomeRead();
            this.archiveReads.invalidate();
            ++this.sharedHomeRenderToken;
            if (this.homeNaming != null) this.homeNaming.close();
            ++this.sharedSettingsRenderToken;
            dismissSettingsDialog();
            if (this.page == SyncPage.CONFIRM) ++this.settingsConfirmationToken;
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
        if (syncPage == SyncPage.HOME) { drainRenewalArchiveRead(); drainAutomaticHistoryRefresh(); }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void render() {
        if (isDestroyed()) return;
        this.page = AndroidUiFlowPolicy.currentPage(this.page);
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
        linearLayoutRow.addView(label(PIXEL_DUNGEON_SAVE_SYNC_29.text(), 14, INK), new LinearLayout.LayoutParams(0, -2, 1.0f));
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
            case 7:
                renderConflict();
                break;
            case 8:
                renderConfirmation();
                break;
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
        addButton(linearLayoutRow, BACK_30.text(), runnable, true);
        addButton(linearLayoutRow, NEXT_31.text(), runnable2, true).setEnabled(z);
    }

    /** The shared page only consumes this already-observed history; drawing never exports saves. */
    private void renderHome() {
        final int renderToken = ++this.sharedHomeRenderToken;
        if (this.homeNaming != null) this.homeNaming.close();
        this.homeNaming = new SharedHistoryNamingController();
        final HomeActionContext owner = currentHomeContext(renderToken);
        final SharedHomeContent content = homeContent();
        final boolean serviceExpired = homeAccountExpired();
        String homeStatus = homeStatus(content);
        String homeNotice = TrustedEntitlementCache.withoutExpiryNotice(visibleNotice());
        String capabilityNotice = restoreNeedsAttention()
                ? AN_UNFINISHED_RESTORE_EXISTS_CHECK_ITS_37.text()
                : this.inspection != null && this.inspection.isRetryablePending()
                ? THE_PREVIOUS_UPLOAD_IS_UNFINISHED_PLEASE_38.text()
                : !readyForSaves() ? CHECK_SAVES_AGAIN_IN_SETTINGS_39.text()
                : !restoreSupported() ? THE_CURRENT_ACCESS_METHOD_DOES_NOT_40.text() : "";
        if (!capabilityNotice.isEmpty() && !homeNotice.contains(capabilityNotice))
            homeNotice = homeNotice.isEmpty() ? capabilityNotice : homeNotice + "\n" + capabilityNotice;
        // These are platform capabilities, not permission inferred from a label, PID or timestamp.
        final boolean canUpload = canHomeUpload();
        final boolean canRestore = canHomeRestore();
        final boolean canLaunch = canRequestSaveAccess() && !restoreNeedsAttention();
        this.root = null;
        this.status = null;
        this.body = null;
        this.footer = null;
        this.scroll = null;
        this.renderedPage = this.page;
        AndroidOnboardingComposition.showHome(this, this.history, this.historyPage, selectedGameIdentity(),
                homeStatus, serviceExpired ? SharedHomeStatusKind.EXPIRED : homeStatusKind(content), isForegroundHome(), this.busy, canUpload, canRestore, canLaunch, homeOperation(),
                isHomeArchiveLoading(),
                this.selected, this.explicitHomeSelection, content, homeNotice, this.feedbackError, "",
                new SharedHomeActions() {
                    @Override public void settings() {
                        if (isCurrentHomeNavigation(owner)) navigate(SyncPage.SETTINGS);
                    }
                    @Override public void appreciate() {
                        if (isCurrentHomeNavigation(owner) && homeAccountExpired() && !installer.hasActiveRequest())
                            showManagedAccount(MainActivity.this.connection);
                    }
                    @Override public void refreshHistory() {
                        if (isCurrentHomeAction(owner)) refreshHomeHistory();
                    }
                    @Override public void upload() {
                        if (isCurrentHomeAction(owner) && canHomeUpload()) requestUpload();
                    }
                    @Override public void restore() {
                        if (isCurrentHomeAction(owner) && canHomeRestore()) requestRestore();
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
                }, this.homeNaming, serviceExpired && isOfficialAccount(this.connection) && !this.installer.hasActiveRequest(), canRefreshHomeHistory());
    }

    /** Display-only HOME status is reused behind the conflict modal; never grants permission. */
    private String homeStatus(SharedHomeContent content) {
        final boolean serviceExpired = homeAccountExpired();
        String homeStatus = serviceExpired ? pdsync.i18n.CoreSessionMessages.SERVICE_EXPIRED.text() : this.cloudVerified
                ? homeDisplayMatchesCloudTip(content) ? PROGRESS_SYNCED_LOCAL_AND_CLOUD_SAVES_32.text() : CLOUD_CONNECTED_33.text()
                : this.connection == null ? WAITING_FOR_CONNECTION_34.text() : SAVES_NEED_CHECKING_35.text();
        if (!automaticObservationSupported() || SyncRuntime.isAutoUploadPaused(this) || this.progress != OnboardingProgress.COMPLETE)
            homeStatus += AUTO_UPLOAD_PAUSED_36.text();
        String automaticStatus = TrustedEntitlementCache.withoutExpiryNotice(automaticStatusBrief());
        if (!automaticStatus.isEmpty()) homeStatus += "\n" + automaticStatus;
        return homeStatus;
    }

    private boolean homeAccountExpired() {
        if (!isOfficialAccount(this.connection)) return false;
        TrustedEntitlementCache.Record record = entitlementCache(getApplicationContext())
                .read(OfficialServiceRegistrationClient.DAV_URL, this.connection.username);
        return TrustedEntitlementCache.currentExpired(record, OfficialServiceRegistrationClient.DAV_URL,
                this.connection.username, android.os.SystemClock.elapsedRealtime(), bootCount(getApplicationContext()));
    }

    /** Uses only the paired in-memory inspection/history; failure is never equality evidence. */
    private SharedHomeStatusKind homeStatusKind(SharedHomeContent content) {
        if (this.busy) return SharedHomeStatusKind.RUNNING;
        if (kind() == OnboardingKind.BRANCHED) return SharedHomeStatusKind.CONFLICT;
        if (restoreNeedsAttention() || this.feedbackError || (this.inspection != null && this.inspection.isRetryablePending()))
            return SharedHomeStatusKind.WARNING;
        if (homeDisplayMatchesCloudTip(content)) return SharedHomeStatusKind.SYNCED;
        return SharedHomeStatusKind.NEUTRAL;
    }

    /** A display match must belong to the current successful observation, never a previous account. */
    private boolean homeDisplayMatchesCloudTip(SharedHomeContent content) {
        return content.getMatchesCloudTip() && this.cloudVerified && !this.feedbackError
                && this.homeObservationOwner != null
                && this.homeObservationOwner.accepts(currentHomeContext(0), true, false)
                && this.connection == SyncRuntime.currentConnection()
                && !this.archiveReads.loading() && !isHomeArchiveLoading();
    }

    private SharedHomeContent homeContent() {
        return SharedHomeContent.fromHome(this.history, this.selected,
                this.cloudVerified && this.connection != null && this.facade != null && this.inspection != null
                        && this.progress == OnboardingProgress.COMPLETE && this.readinessState.canContinue()
                        && !restoreNeedsAttention() && kind() != OnboardingKind.BLOCKED,
                this.inspection != null && this.inspection.isRetryablePending(), kind() == OnboardingKind.BRANCHED);
    }

    /** The published observation belongs to this exact account, game and backend. */
    private SharedHomeArchiveAvailability homeArchiveAvailability() {
        boolean current = this.homeObservationOwner != null
                && this.homeObservationOwner.accepts(currentHomeContext(0), true, false);
        return SharedHomeArchiveAvailability.from(this.history, this.selected,
                current && this.cloudVerified && this.connection != null
                        && this.connection == SyncRuntime.currentConnection() && this.facade != null
                        && this.inspection != null && selectedGamePackage() != null
                        && this.progress == OnboardingProgress.COMPLETE && readyForSaves()
                        && !restoreNeedsAttention() && kind() != OnboardingKind.BLOCKED
                        && !this.archiveReads.loading() && !isHomeArchiveLoading());
    }

    private boolean homeAccountAccessAvailable() {
        if (!isOfficialAccount(this.connection)) return true;
        TrustedEntitlementCache.Record record = entitlementCache(getApplicationContext())
                .read(OfficialServiceRegistrationClient.DAV_URL, this.connection.username);
        return TrustedEntitlementCache.currentAccessAvailable(record, OfficialServiceRegistrationClient.DAV_URL,
                this.connection.username, android.os.SystemClock.elapsedRealtime(), bootCount(getApplicationContext()));
    }

    private boolean canRefreshHomeHistory() {
        return this.connection != null && this.progress == OnboardingProgress.COMPLETE
                && isForegroundHome() && !this.busy && !isHomeArchiveLoading()
                && !this.installer.hasActiveRequest() && readyForSaves() && homeAccountAccessAvailable();
    }

    private void refreshHomeHistory() {
        if (!canRefreshHomeHistory()) return;
        final SecureConnectionStore.Connection account = this.connection;
        try {
            final String selection = SyncRuntime.currentSelectionToken(this);
            final String namespace = SyncRuntime.currentNamespace(this);
            // An explicit read also fulfils an unconsumed renewal read for this same identity.
            this.renewalArchiveRead.take(account, selection, namespace);
            readHomeArchives(account, selection, namespace);
        } catch (Exception unavailable) { reportError(unavailable); render(); }
    }

    private boolean canHomeUpload() {
        return homeAccountAccessAvailable() && isForegroundHome() && !this.busy && canRequestSaveAccess() && !restoreNeedsAttention()
                && homeArchiveAvailability().getUploadSourceAvailable() && !homeContent().getUploadUnnecessary();
    }

    private boolean canHomeRestore() {
        return homeAccountAccessAvailable() && isForegroundHome() && !this.busy && restoreSupported() && canRequestSaveAccess()
                && this.connection != null && !restoreNeedsAttention()
                && this.inspection != null && !this.inspection.isRetryablePending()
                && homeArchiveAvailability().getRestoreSourceAvailable() && !homeContent().getRestoreUnnecessary();
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

    private boolean isCurrentHomeNavigation(HomeActionContext owner) {
        return owner != null && owner.accepts(currentHomeContext(this.sharedHomeRenderToken), isForegroundHome(), this.busy);
    }

    private boolean isCurrentHomeAction(HomeActionContext owner) {
        return owner != null && !isHomeArchiveLoading()
                && owner.accepts(currentHomeContext(this.sharedHomeRenderToken), isForegroundHome(), this.busy);
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
                    case CHOOSE_ROOT: chooseInlineRoot(); break;
                    case SWITCH_TO_EMBEDDED: chooseInlineApkMode(); break;
                    case CHECK_ACCESS: requestRootAuthorization(); break;
                    case LAUNCH_GAME: launchSelectedRootGame(); break;
                    case ACTIVATE_BRIDGE: activateHelper(); break;
                    case INITIALIZE_GAME: launchGameForInitialization(); break;
                    case OPEN_RECOVERY: navigate(SyncPage.SETTINGS); break;
                    case CONTINUE_INSTALLATION: beginInlineInstallation(); break;
                    case OPEN_SYSTEM_CONFIRMATION: retryInstallConfirmation(); break;
                    case USE_OTHER_INSTALLER: offerExternalInstaller(); break;
                    case CHECK_EXTERNAL_INSTALL: checkExternalInstallation(); break;
                    case CANCEL_INSTALLATION: m96x62f97958(); break;
                    case CHECK_EXPIRED_REQUEST: m100xdbe735b1(); break;
                    case CLEAN_LEGACY_REQUEST: confirmObsoleteLegacyCleanup(); break;
                    case OPEN_INSTALL_SETTINGS: m102xc6db8233(); break;
                    case RESCAN: refreshReadiness(); break;
                    case COPY_DIAGNOSTICS: break;
                    case CANCEL_ACCESS_SELECTION: cancelInlineAccessSelection(); break;
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
                && this.page == SyncPage.DISCOVERY && !this.sharedAppreciationOnly && !this.busy && !this.gameFilePickerOpen
                && (this.setupSession.modeChoice.pending() == null
                    || this.setupSession.modeChoice.awaiting(this.setupSession.modeChoice.pending()));
    }

    /** Describes capabilities only. APK preparation, Root probes and destructive confirmations remain in their original adapters. */
    private SharedGameSelectionState gameSelectionState() {
        List<SharedGameSelectionRow> rows = new ArrayList<>();
        boolean active = this.installer.hasActiveRequest();
        boolean recovery = restoreNeedsAttention();
        GameModeChoice.Request<Uri> pending = this.setupSession.modeChoice.pending();
        boolean awaiting = this.setupSession.modeChoice.awaiting(pending);
        GameModeChoice.Mode mode = currentAccessMode();
        boolean selected = pending != null || selectedGamePackage() != null;
        rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_INSTALLED, SELECT_AN_INSTALLED_APP_41.text(), !active && !recovery));
        rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_FILE, SELECT_AN_APK_FILE_42.text(), !active && !recovery));
        rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_ROOT, ROOT_MODE_43.text(), selected && !active && !recovery));
        rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.SWITCH_TO_EMBEDDED, MODIFY_APK_MODE_44.text(), selected && !active && !recovery));
        if (active && this.installer.hasConfirmation())
            rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.OPEN_SYSTEM_CONFIRMATION, CONTINUE_SYSTEM_CONFIRMATION_45.text(), true));
        if (active && this.installer.confirmationUnavailable() && !this.installer.hasConfirmation())
            rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CHECK_EXPIRED_REQUEST, CHECK_EXPIRED_REQUEST_46.text(), true));
        if (this.installer.externalInstallPending())
            rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CHECK_EXTERNAL_INSTALL, CHECK_INSTALLATION_RESULT_47.text(), true));
        if (active) rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CANCEL_INSTALLATION,
                this.installer.externalInstallPending() ? STOP_WAITING_48.text() : CANCEL_INSTALLATION_49.text(), true));
        if (awaiting) rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.CANCEL_ACCESS_SELECTION, CANCEL_THIS_SELECTION_50.text(), true));
        if (recovery) rows.add(new SharedGameSelectionRow(SharedGameSelectionControl.OPEN_RECOVERY, VIEW_RESTORE_RECORDS_51.text(), true));
        SharedGameIdentity game = pending != null && this.setupSession.pendingGameIdentity != null
                ? this.setupSession.pendingGameIdentity : selectedGameIdentity();
        String location = pending != null ? pending.source.installedPackage != null ? pending.source.installedPackage : "selected-apk"
                : selectedGamePackage();
        boolean proven = pending == null && canAdvanceGameSelection();
        SharedGameAccessStatus accessStatus = this.busy ? SharedGameAccessStatus.WORKING
                : this.feedbackError || this.installer.confirmationUnavailable() ? SharedGameAccessStatus.FAILED
                : active ? SharedGameAccessStatus.WORKING : proven ? SharedGameAccessStatus.READY
                : SharedGameAccessStatus.UNCHECKED;
        // Discovery access modes have no bottom status notices. Internal outcomes and
        // readiness still drive the cards, action locks and independently verified Next.
        return new SharedGameSelectionState(game, location, game == null ? "" : proven ? READY_52.text() : SELECTED_53.text(),
                location == null ? "" : location, rows, Collections.emptyList(), this.resumed && this.page == SyncPage.DISCOVERY && !this.sharedAppreciationOnly,
                this.busy || this.gameFilePickerOpen || (pending != null && !awaiting),
                canAdvanceGameSelection(), "", false,
                "", "", false,
                new SharedGameAccessState(mode == GameModeChoice.Mode.ROOT ? SharedGameAccessMode.ROOT
                        : mode == GameModeChoice.Mode.APK ? SharedGameAccessMode.APK : SharedGameAccessMode.NONE, accessStatus));
    }

    private GameModeChoice.Mode currentAccessMode() {
        if (this.setupSession.modeChoice.pending() != null) return this.setupSession.accessMode;
        if (this.setupSession.accessMode != null) return this.setupSession.accessMode;
        if (this.rootGame != null || this.setupSession.flow.rootAuthorized()) return GameModeChoice.Mode.ROOT;
        return this.prepared != null || this.installedBridge != null ? GameModeChoice.Mode.APK : null;
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
        if (this.installer.externalInstallPending()) {
            confirm(STOP_WAITING_FOR_THE_OTHER_INSTALLER_54.text(), FIRST_CONFIRM_THAT_THE_OTHER_INSTALLER_55.text(), FINISHED_REVOKE_ACCESS_56.text(), () ->
                    setupWork(DiagnosticAction.INSTALL, CHECKING_AND_ENDING_THE_WAIT_57.text(), () -> {
                        this.installer.endExternalInstallWait(); return null;
                    }, (owner, ignored) -> { owner.setupOutcome = owner.installer.summary(); owner.refreshReadiness(); }));
            return;
        }
        confirm(CANCEL_THE_PENDING_INSTALLATION_58.text(), THE_PREPARED_APK_WILL_BE_KEPT_59.text(), CANCEL_INSTALLATION_49.text(), new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda157
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m95xed7f5317();
            }
        });
    }

    /* renamed from: lambda$renderDiscovery$16$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m93x28b0695() throws Exception {
        this.installer.settleClosedSystemRequest();
        this.setupSession.flow.cancelPending();
        this.setupSession.installationAdvancePending = false;
        return null;
    }

    /* renamed from: lambda$renderDiscovery$17$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m94x78052cd6(Object obj) throws Exception {
        refreshReadiness();
    }

    /* renamed from: lambda$renderDiscovery$18$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m95xed7f5317() {
        work(DiagnosticAction.INSTALL, CANCELING_THE_PENDING_INSTALLATION_60.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda114
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
        confirm(HAVE_YOU_CANCELED_THE_LEGACY_SYSTEM_61.text(),
                FIRST_TAP_CANCEL_AND_CLOSE_THE_62.text(),
                CANCELED_AND_CLOSED_CHECK_AND_CLEAN_63.text(), () -> work(DiagnosticAction.INSTALL, CHECKING_AND_CLEARING_THE_LEGACY_REQUEST_64.text(),
                        () -> { this.installer.cancelObsoleteLegacyRequest(true); return null; },
                        ignored -> refreshReadiness()));
    }

    /* renamed from: lambda$renderDiscovery$23$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m100xdbe735b1() {
        confirm(IS_THE_OLD_SYSTEM_CONFIRMATION_SCREEN_65.text(), USE_ONLY_WHEN_THE_SYSTEM_CONFIRMATION_66.text(), OLD_SCREEN_CLOSED_CHECK_REQUEST_67.text(), new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda101
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
        work(DiagnosticAction.INSTALL, CHECKING_THE_SYSTEM_REQUEST_68.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda17
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
        confirm(YOU_DECIDE_WHETHER_TO_ALLOW_INSTALLATION_69.text(), THIS_ONLY_OPENS_ANDROID_S_INSTALL_70.text(), OPEN_SETTINGS_71.text(), new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda131
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
        if (!canAdvanceGameSelection()) return;
        if (this.setupSession.flow.rootAuthorized()) {
            final String packageName = selectedGamePackage();
            setupWork(DiagnosticAction.ACCESS_CHECK, CONFIRMING_GAME_72.text(), () -> {
                SelectedRootGame chosen = new RootTargetResolver(this).selectInstalled(packageName);
                SyncRuntime.setRootGame(this, chosen);
                this.rootGame = chosen; this.prepared = null; this.installedBridge = null;
                this.accessCapabilities = SyncRuntime.backend(this).capabilities();
                this.selectedNamespace = SyncRuntime.currentNamespace(this);
                this.readinessState = InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                        true, false, false, InstallationReadiness.AccessStatus.NOT_CHECKED);
                return null;
            }, (owner, ignored) -> { owner.onboardingActive = true; owner.navigate(SyncPage.CONNECTION); });
        } else {
            this.onboardingActive = true; navigate(SyncPage.CONNECTION);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pickApks() {
        if (this.busy || this.gameFilePickerOpen || isDestroyed()) return;
        if (this.installer.hasActiveRequest()) {
            this.notice = FINISH_OR_CANCEL_THE_CURRENT_SYSTEM_73.text();
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
            this.notice = NO_SYSTEM_FILE_PICKER_IS_AVAILABLE_74.text();
            render();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void chooseInstalled() {
        if (this.busy || this.installer.hasActiveRequest()) return;
        final String selectedPackage = selectedGamePackage();
        setupWork(DiagnosticAction.INSPECT, READING_INSTALLED_APPS_75.text(), () -> {
            this.installer.assertCanPrepare();
            return InstalledAppCatalog.load(this, selectedPackage);
        }, (owner, games) -> InstalledAppPickerDialog.show(owner, SELECT_AN_INSTALLED_APP_41.text(), games, selectedPackage,
                owner.installedPickerState,
                () -> owner.resumed && !owner.isDestroyed() && owner.setupSession.owner == owner
                        && owner.page == SyncPage.DISCOVERY && !owner.busy && !owner.installer.hasActiveRequest(),
                owner::selectInstalledForSetup, owner::pickApks));
    }

    private void selectInstalledForSetup(String packageName) {
        offerGameModeChoice(GameModeChoice.Source.installed(packageName));
    }

    private void offerGameModeChoice(GameModeChoice.Source<Uri> source) {
        if (this.busy || this.installer.hasActiveRequest() || restoreNeedsAttention() || isDestroyed()) return;
        dismissGameModeDialog();
        if (this.setupSession.modeChoice.pending() == null) this.setupSession.previousAccessMode = currentAccessMode();
        this.setupSession.modeChoice.offer(source);
        this.setupSession.accessMode = null;
        this.setupSession.modeChoiceError = null;
        this.setupSession.fileIdentityRequest = null;
        this.setupSession.pendingGameIdentity = pendingGameIdentity(source);
        this.feedbackError = false;
        render();
        if (source.apks != null) readPendingApkIdentity(this.setupSession.modeChoice.pending());
    }

    /** Reads display metadata only. The source remains inert until an explicit access action. */
    private SharedGameIdentity pendingGameIdentity(GameModeChoice.Source<Uri> source) {
        if (source.installedPackage != null) {
            try {
                PackageInfo info = getPackageManager().getPackageInfo(source.installedPackage, 0);
                String label = info.applicationInfo == null ? source.installedPackage
                        : getPackageManager().getApplicationLabel(info.applicationInfo).toString();
                return new SharedGameIdentity(label, info.versionName,
                        AndroidGameIcon.load(this, source.installedPackage, "installed", null));
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                return new SharedGameIdentity(source.installedPackage, WAITING_TO_CHECK_THE_INSTALLED_APP_76.text());
            }
        }
        return new SharedGameIdentity(APK_FILE_SELECTED_77.text(), WAITING_TO_CHECK_THE_INSTALLATION_PACKAGE_78.text());
    }

    /** Presentation work does not set busy, select a package, or change installation readiness. */
    private void readPendingApkIdentity(GameModeChoice.Request<Uri> request) {
        final SetupSession session = this.setupSession;
        session.fileIdentityRequest = request;
        final Context context = getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        session.executor.execute(() -> {
            SharedGameIdentity identity = SelectedApkIdentity.read(context, request.source.apks);
            handler.post(() -> {
                // Request identity distinguishes even repeated selections of the same URI.
                // Cancel/replacement invalidates completion, including a repeated URI.
                if (session.fileIdentityRequest != request || identity == null) return;
                if (session.modeChoice.pending() == request) session.pendingGameIdentity = identity;
                else if (session.modeChoice.pending() == null && session.selectedSource != null
                        && request.source.apks.equals(session.selectedSource.apks))
                    session.selectedFileIdentity = identity;
                else return;
                MainActivity owner = session.owner;
                if (owner != null && !owner.isDestroyed()) owner.render();
            });
        });
    }

    /** Existing token ownership, foreground and capability gates also protect inline card callbacks. */
    private GameModeChoice.Request<Uri> inlineAccessRequest() {
        if (!this.resumed || isDestroyed() || this.setupSession.owner != this || this.busy
                || this.page != SyncPage.DISCOVERY || this.installer.hasActiveRequest() || restoreNeedsAttention()) return null;
        GameModeChoice.Request<Uri> request = this.setupSession.modeChoice.pending();
        if (request != null) return this.setupSession.modeChoice.awaiting(request) ? request : null;
        GameModeChoice.Source<Uri> source = this.setupSession.selectedSource;
        if (source == null && selectedGamePackage() != null) source = GameModeChoice.Source.installed(selectedGamePackage());
        if (source == null) return null;
        this.setupSession.previousAccessMode = currentAccessMode();
        this.setupSession.pendingGameIdentity = selectedGameIdentity();
        return this.setupSession.modeChoice.offer(source);
    }

    private void chooseInlineRoot() {
        GameModeChoice.Request<Uri> request = inlineAccessRequest();
        if (request == null) return;
        this.setupSession.accessMode = GameModeChoice.Mode.ROOT;
        dispatchInlineAccess(request, GameModeChoice.Mode.ROOT);
    }

    /** The explicit APK choice processes the current source; it is not merely a visual mode toggle. */
    private void chooseInlineApkMode() {
        GameModeChoice.Request<Uri> request = inlineAccessRequest();
        if (request == null) return;
        this.setupSession.accessMode = GameModeChoice.Mode.APK;
        this.feedbackError = false;
        dispatchInlineAccess(request, GameModeChoice.Mode.APK);
    }

    private void beginInlineInstallation() {
        if (currentAccessMode() != GameModeChoice.Mode.APK) return;
        GameModeChoice.Request<Uri> request = this.setupSession.modeChoice.pending();
        if (request != null) { dispatchInlineAccess(request, GameModeChoice.Mode.APK); return; }
        if (canAdvanceGameSelection()) {
            GameModeChoice.Source<Uri> source = this.setupSession.selectedSource;
            if (source != null && source.apks != null) requestPrepare(null, source.apks);
            else if (selectedGamePackage() != null) requestPrepare(selectedGamePackage(), null);
            return;
        }
        if (hasInstalledCandidate()) beginModification(); else continueInstallation();
    }

    private void dispatchInlineAccess(GameModeChoice.Request<Uri> request, GameModeChoice.Mode mode) {
        if (!this.resumed || isDestroyed() || this.setupSession.owner != this || this.busy
                || this.page != SyncPage.DISCOVERY || this.installer.hasActiveRequest() || restoreNeedsAttention()
                || !this.setupSession.modeChoice.awaiting(request)) return;
        try { this.installer.supersedeExternalObservation(); }
        catch (IOException failure) { reportError(failure); return; }
        this.setupSession.externalCheckPending = false;
        this.setupSession.modeChoice.dispatch(request, mode, new GameModeChoice.Actions<Uri>() {
            @Override public void root(GameModeChoice.Request<Uri> selected) { resolveRootSetupSource(selected); }
            @Override public void apk(GameModeChoice.Request<Uri> selected) { chooseApkSetupSource(selected); }
        });
    }

    private void cancelInlineAccessSelection() {
        GameModeChoice.Request<Uri> request = this.setupSession.modeChoice.pending();
        if (!this.setupSession.modeChoice.cancel(request)) return;
        this.setupSession.accessMode = this.setupSession.previousAccessMode;
        this.setupSession.pendingGameIdentity = null;
        this.setupSession.fileIdentityRequest = null;
        this.setupSession.modeChoiceError = null;
        this.feedbackError = false;
        render();
    }

    private void dismissGameModeDialog() {
        ++this.gameModeDialogToken;
        AlertDialog dialog = this.gameModeDialog;
        this.gameModeDialog = null;
        if (dialog != null) dialog.dismiss(); // Rotation/background disposal is not a choice or cancellation.
    }

    private static final class SetupSourceResult {
        final GamePreparationService.InstalledGame candidate;
        final Exception failure;
        SetupSourceResult(GamePreparationService.InstalledGame candidate, Exception failure) {
            this.candidate = candidate; this.failure = failure;
        }
    }

    private void retryGameModeChoice(GameModeChoice.Request<Uri> request, Exception failure) {
        if (!this.setupSession.modeChoice.retry(request)) return;
        this.setupSession.modeChoiceError = safeError(failure);
        reportError(failure);
    }

    /** Source inspection is read-only; permission is requested only after the installed target is known. */
    private void resolveRootSetupSource(GameModeChoice.Request<Uri> request) {
        final GameModeChoice.Source<Uri> source = request.source;
        setupWork(DiagnosticAction.INSPECT, CONFIRMING_THE_INSTALLED_GAME_79.text(), () -> {
            try {
                if (hasAnyRestoreJournal()) throw new IOException(HANDLE_THE_UNFINISHED_RESTORE_BEFORE_CHANGING_80.text());
                String packageName = source.installedPackage != null ? source.installedPackage
                        : this.preparation.inspectSelectedApkPackage(source.apks);
                GamePreparationService.InstalledGame candidate = this.preparation.inspectInstalledGame(packageName);
                if (candidate == null) throw new IOException(INSTALL_THIS_GAME_BEFORE_USING_ROOT_81.text());
                return new SetupSourceResult(candidate, null);
            } catch (Exception failure) {
                this.setupProbeFailure = failure;
                return new SetupSourceResult(null, failure);
            }
        }, (owner, result) -> {
            if (result.failure != null) { owner.retryGameModeChoice(request, result.failure); return; }
            if (!owner.setupSession.modeChoice.complete(request)) return;
            owner.selectSetupSource(source, result.candidate);
            owner.requestRootAuthorization();
        });
    }

    /** This method is reachable only from an explicit modification choice. */
    private void chooseApkSetupSource(GameModeChoice.Request<Uri> request) {
        final GameModeChoice.Source<Uri> source = request.source;
        if (source.installedPackage == null) {
            if (!this.setupSession.modeChoice.complete(request)) return;
            selectSetupSource(source, null);
            requestPrepare(null, source.apks);
            return;
        }
        final String packageName = source.installedPackage;
        setupWork(DiagnosticAction.ACCESS_CHECK, CHECKING_GAME_COMPONENTS_28.text(), () -> {
            try {
                if (hasAnyRestoreJournal()) throw new IOException(HANDLE_THE_UNFINISHED_RESTORE_BEFORE_CHANGING_80.text());
                GamePreparationService.InstalledGame candidate = this.preparation.inspectInstalledGame(packageName);
                if (candidate == null) throw new IOException(THE_SELECTED_APP_IS_NO_LONGER_82.text());
                return new SetupSourceResult(candidate, null);
            } catch (Exception failure) {
                this.setupProbeFailure = failure;
                return new SetupSourceResult(null, failure);
            }
        }, (owner, result) -> {
            if (result.failure != null) { owner.retryGameModeChoice(request, result.failure); return; }
            if (!owner.setupSession.modeChoice.complete(request)) return;
            owner.installer.selectInstalledSource();
            owner.selectSetupSource(source, result.candidate);
            owner.beginModification();
        });
    }

    /** Commits only the in-memory step-one candidate, after a mode button was pressed. */
    private void selectSetupSource(GameModeChoice.Source<Uri> source, GamePreparationService.InstalledGame candidate) {
        this.setupSession.selectedSource = source;
        this.setupSession.selectedFileIdentity = source.apks == null ? null : this.setupSession.pendingGameIdentity;
        this.setupSession.modeChoiceError = null;
        this.setupSession.automaticInstallTarget = null;
        this.setupSession.installationAdvancePending = false;
        this.setupSession.activationAdvancePending = false;
        this.systemReturnPending = false; this.activationReturnPending = false;
        this.setupSession.flow.select(candidate == null ? "selected-apk" : candidate.packageName);
        this.setupOutcome = null; this.feedbackError = false; this.readinessRefreshPending = false;
        this.selectedInstalledGame = candidate; this.installedBridge = null;
        this.prepared = null; this.rootGame = null; this.accessCapabilities = null;
        this.bridgeAccessCode = "NOT_CHECKED"; this.readinessState = InstallationReadiness.noSelection();
        this.facade = null; this.history = null; this.inspection = null; this.localPreview = null;
        this.cloudVerified = false; this.selected = null; this.explicitHomeSelection = null;
        this.progress = OnboardingProgress.PENDING; this.onboardingActive = true; this.page = SyncPage.DISCOVERY;
        resetPathPreview();
    }

    private boolean hasInstalledCandidate() { return this.selectedInstalledGame != null || this.prepared == null; }

    private void beginModification() {
        final String packageName = selectedGamePackage();
        if (packageName == null || this.busy) return;
        this.setupSession.flow.select(packageName);
        final GameModeChoice.Source<Uri> source = this.setupSession.selectedSource;
        if (source != null && source.apks != null) {
            requestPrepare(null, source.apks);
            return;
        }
        setupWork(DiagnosticAction.ACCESS_CHECK, CHECKING_GAME_COMPONENTS_28.text(), () -> {
            if (hasAnyRestoreJournal()) throw new IOException(HANDLE_THE_UNFINISHED_RESTORE_BEFORE_CHANGING_80.text());
            InstalledBridgeBinding binding = InstalledBridgeResolver.resolve(this, packageName);
            if (binding != null) {
                SyncRuntime.setInstalledBridge(this, binding);
                this.installedBridge = binding; this.rootGame = null;
                authenticateInstalledBridge(binding);
            }
            return binding;
        }, (owner, binding) -> {
            if (binding != null && !"PURE_HANDSHAKE_REQUIRED".equals(owner.bridgeAccessCode)) {
                owner.notice = owner.readyForSaves() ? GAME_COMPONENTS_CONNECTED_83.text() : CONNECTING_GAME_COMPONENTS_84.text();
                owner.maybeActivateSetupComponent();
                return;
            }
            owner.rootGame = null; owner.installedBridge = null;
            owner.requestPrepare(packageName, null);
        });
    }

    private void requestPrepare(final String str, final List<Uri> list) {
        if (this.busy || this.installer.hasActiveRequest()) return;
        this.setupSession.selectedSource = list == null ? GameModeChoice.Source.installed(str) : GameModeChoice.Source.files(list);
        this.setupOutcome = null;
        this.setupSession.automaticInstallTarget = null;
        this.setupSession.flow.select(str == null ? "selected-apk" : str);
        this.rootGame = null; this.installedBridge = null;
        prepare(str, list, false);
    }

    /* renamed from: lambda$requestPrepare$30$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ String m138x3dde23b5(List list, String str) throws Exception {
        if (SyncRuntime.rootMode(this)) throw new IOException(ROOT_MODE_DOES_NOT_PREPARE_OR_85.text());
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
            confirm(CREATE_A_GAME_SIGNING_KEY_ON_86.text(), A_PRIVATE_KEY_STORED_ONLY_IN_87.text(), AGREE_AND_PREPARE_88.text(), new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda158
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
        this.diagnosticReport = ""; this.details = "";
        setupWork(DiagnosticAction.PREPARE, InstallUiCopy.PREPARING.text(), () -> {
            SyncRuntime.useEmbeddedBackend(this);
            return m58lambda$prepare$33$iogithubxyz1001pdsyncandroidMainActivity(list, str, z);
        }, (owner, pair) -> {
            owner.prepared = pair; owner.selectedInstalledGame = null; owner.installedBridge = null;
            owner.setupSession.flow.select(pair.gamePackage);
            owner.readinessState = InstallationReadiness.checking(true, false);
            owner.facade = null; owner.history = null; owner.inspection = null; owner.localPreview = null;
            owner.cloudVerified = false; owner.selected = null; owner.explicitHomeSelection = null;
            owner.progress = OnboardingProgress.PENDING; owner.onboardingActive = true; owner.page = SyncPage.DISCOVERY;
            owner.continueInstallation();
        });
    }

    /* renamed from: lambda$prepare$33$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ GamePreparationService.PreparedPair m58lambda$prepare$33$iogithubxyz1001pdsyncandroidMainActivity(List list, String str, boolean z) throws Exception {
        if (SyncRuntime.rootMode(this)) throw new IOException(ROOT_MODE_DOES_NOT_PREPARE_OR_85.text());
        this.installer.assertCanPrepare();
        PreparationDiagnostics.Stage stage = PreparationDiagnostics.Stage.EXTRACTION;
        try (InputStream runtimeDex = getAssets().open("bridge-runtime.dex")) {
            GamePreparationService.PreparedPair pair = list == null
                    ? this.preparation.prepareInstalledGame(str, z, runtimeDex)
                    : this.preparation.prepareSelectedApks(list, z, runtimeDex);
            stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
            this.installer.registerPrepared(pair, list != null);
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
        this.notice = THE_GAME_APK_WITH_THE_BUILT_89.text();
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
        sb.append(GAME_APK_S_90.text(preparedPair.gameApks.size(), preparedPair.protectedEntryCount));
        sb.append(InstallationReadiness.packageDescription(this.readinessState.game));
        sb.append(preparedPair.embeddedBridge() ? NSAVE_BRIDGE_BUILT_INTO_THE_GAME_92.text() : NLEGACY_PREPARATION_RECORD_PREPARE_THE_GAME_93.text());
        sb.append(NSYNC_SPACE_94.text());
        sb.append(SyncRuntime.gameIdentity(this, preparedPair).getNamespace());
        return sb.toString();
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class InstallNext {
        String name;
        GamePreparationService.PreparedPair pair;
        String receipt;
        boolean uninstall;
        boolean externalInstaller;

        private InstallNext() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void continueInstallation() {
        if (!this.resumed) { this.setupSession.installationAdvancePending = true; return; }
        if (this.busy || this.setupSession.running) return;
        this.setupSession.installationAdvancePending = false;
        this.setupOutcome = null;
        if (this.rootGame != null) return;
        if (this.setupSession.flow.target() == null && this.prepared != null) this.setupSession.flow.select(this.prepared.gamePackage);
        if (this.installer.hasConfirmation()) { openInstallConfirmation(); return; }
        setupWork(DiagnosticAction.INSTALL, InstallUiCopy.PREPARING.text(), this::m32xc4841ad1,
                (owner, next) -> owner.m39x9de049ed(next));
    }

    /* renamed from: lambda$continueInstallation$35$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ InstallNext m32xc4841ad1() throws Exception {
        if (SyncRuntime.rootMode(this)) throw new IOException(ROOT_MODE_DOES_NOT_INSTALL_OR_95.text());
        this.installer.reconcile();
        if (this.installer.hasActiveRequest()) {
            throw new IOException(THE_SYSTEM_REQUEST_IS_STILL_BEING_96.text());
        }
        InstallNext installNext = new InstallNext();
        installNext.pair = this.installer.freshPair();
        if (this.setupSession.automaticInstallTarget != null
                && !this.setupSession.automaticInstallTarget.equals(installNext.pair.gamePackage))
            throw new IOException(THE_INSTALLATION_TARGET_HAS_CHANGED_AUTOMATIC_97.text());
        installNext.name = this.installer.nextPackage(installNext.pair);
        if (installNext.name != null) {
            installNext.externalInstaller = installNext.pair.embeddedBridge()
                    && !installNext.pair.helperOnlyUpgrade && installNext.pair.gameApks.size() == 1;
            if (installNext.externalInstaller) return installNext; // Honor the default APK handler before creating any session.
            installNext.uninstall = installNext.pair.gameStrategy == GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL;
            if (installNext.uninstall) {
                if (hasAnyRestoreJournal()) {
                    throw new IOException(AN_UNFINISHED_SAVE_RESTORE_EXISTS_HANDLE_98.text());
                }
                installNext.receipt = this.installer.uninstallConsentReceipt(installNext.name, installNext.pair);
                return installNext;
            }
        }
        return installNext;
    }

    /* renamed from: lambda$continueInstallation$42$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m39x9de049ed(final InstallNext next) throws Exception {
        this.prepared = next.pair;
        SyncRuntime.setPreparedGame(this, next.pair);
        if (!this.resumed) { this.setupSession.installationAdvancePending = true; return; }
        if (next.name == null) {
            this.notice = INSTALLATION_COMPLETE_CONNECTING_GAME_COMPONENTS_99.text();
            refreshReadiness();
            return;
        }
        if (next.externalInstaller) {
            launchExternalInstaller();
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            this.setupSession.flow.beginPending(GameSetupFlow.Pending.UNKNOWN_SOURCES);
            this.notice = InstallUiCopy.STARTING.text();
            try {
                startActivityForResult(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",
                        Uri.parse("package:" + getPackageName())), ALLOW_INSTALL);
            } catch (RuntimeException unavailable) {
                this.setupSession.flow.cancelPending();
                throw new IOException(COULD_NOT_OPEN_THE_INSTALLATION_PERMISSION_100.text(), unavailable);
            }
            return;
        }
        if (next.uninstall) {
            final long generation = this.setupSession.flow.generation();
            confirm(CONFIRM_BEFORE_UNINSTALLING_101.text(), InstallUiCopy.uninstallWarning(next.pair.gameStrategyReason), CONTINUE_UNINSTALLING_102.text(), () -> {
                if (this.setupSession.flow.matches(generation, next.name)) m35x24f28d94(next);
            });
        } else m38x286623ac(next);
    }

    /* renamed from: lambda$continueInstallation$36$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m33x39fe4112(InstallNext installNext) throws Exception {
        if (SyncRuntime.rootMode(this)) throw new IOException(ROOT_MODE_DOES_NOT_INSTALL_OR_95.text());
        this.installer.requestUninstall(installNext.name, installNext.receipt);
        return null;
    }

    /* renamed from: lambda$continueInstallation$38$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m35x24f28d94(final InstallNext next) {
        setupWork(DiagnosticAction.INSTALL, InstallUiCopy.STARTING.text(), () -> m33x39fe4112(next),
                (owner, ignored) -> { owner.pendingSystemChecks = 0; owner.notice = InstallUiCopy.STARTING.text(); owner.drainPendingSetup(); });
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
        if (SyncRuntime.rootMode(this)) throw new IOException(ROOT_MODE_DOES_NOT_INSTALL_OR_95.text());
        this.installer.requestInstall(installNext.name);
        this.setupSession.automaticInstallTarget = null;
        return null;
    }

    /* renamed from: lambda$continueInstallation$41$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m38x286623ac(final InstallNext next) {
        setupWork(DiagnosticAction.INSTALL, InstallUiCopy.STARTING.text(), () -> m36x9a6cb3d5(next),
                (owner, ignored) -> { owner.pendingSystemChecks = 0; owner.notice = InstallUiCopy.STARTING.text(); owner.drainPendingSetup(); });
    }

    /* renamed from: lambda$continueInstallation$40$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m37xb2ebfd6b(Object obj) throws Exception {
        this.notice = this.installer.summary();
        if (this.installer.hasConfirmation()) {
            openInstallConfirmation();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    private void offerExternalInstaller() {
        if (this.busy || this.setupSession.running || restoreNeedsAttention() || this.prepared == null || !this.installer.canOfferExternalInstaller()) return;
        confirm(CHOOSE_ANOTHER_INSTALLER_103.text(), THE_OLD_INSTALLATION_SESSION_CREATED_BY_104.text(), CHOOSE_INSTALLER_105.text(), this::launchExternalInstaller);
    }

    /** New single-APK installs use the default handler, or Android resolver when none is set. */
    private void launchExternalInstaller() {
        if (this.busy || this.setupSession.running || restoreNeedsAttention() || this.prepared == null || !this.installer.canOfferExternalInstaller()) return;
        setupWork(DiagnosticAction.INSTALL, PREPARING_THE_READ_ONLY_PACKAGE_AND_106.text(), this.installer::prepareExternalInstall,
                (owner, ticket) -> {
                    if (!owner.resumed || owner.isDestroyed()) {
                        owner.notice = ANOTHER_INSTALLER_REQUEST_IS_READY_STOP_107.text();
                        return;
                    }
                    try {
                        owner.startActivityForResult(ExternalInstallerHandoff.installIntent(ticket), owner.installer.externalActivityRequestCode());
                        owner.notice = COMPLETE_THE_OPERATION_IN_THE_SELECTED_108.text();
                    } catch (RuntimeException unavailable) {
                        owner.setupWork(DiagnosticAction.INSTALL, REVOKING_PACKAGE_ACCESS_109.text(), () -> {
                            owner.installer.externalLaunchFailed(); return null;
                        }, (current, ignored) -> current.reportError(new IOException(
                                NO_APK_INSTALLER_IS_AVAILABLE_OR_110.text(), unavailable)));
                    }
                });
    }

    /** Activity result is a UI boundary only. Result codes never authorize success/cancellation. */
    private void externalInstallerReturned(int requestCode) {
        if (requestCode != this.installer.externalActivityRequestCode()) return;
        try { this.installer.externalActivityReturned(this.installer.requestNonce()); }
        catch (IOException failure) { reportError(failure); return; }
        if (this.installer.externalInstallObservation()) this.setupSession.externalCheckPending = true;
        drainExternalInstallation();
    }

    /** Retain a return received while paused/busy; setup completion and resume drain it once. */
    private void drainExternalInstallation() {
        if (!this.resumed || this.busy || this.setupSession.running || isDestroyed()
                || !this.setupSession.externalCheckPending || this.setupSession.modeChoice.pending() != null) return;
        this.setupSession.externalCheckPending = false;
        checkExternalInstallation();
    }

    private void checkExternalInstallation() {
        if (this.busy || this.setupSession.running
                || (!this.installer.externalInstallPending() && !this.installer.externalInstallObservation())) return;
        setupWork(DiagnosticAction.INSTALL, VERIFYING_THE_ACTUAL_INSTALLATION_RESULT_111.text(), () -> {
            this.installer.checkExternalInstall(); return null;
        }, (owner, ignored) -> {
            owner.notice = owner.installer.summary(); owner.setupOutcome = owner.notice;
            if (!owner.installer.externalInstallPending()) owner.refreshReadiness();
        });
    }

    private void retryInstallConfirmation() {
        // This path is only an explicit foreground button action. Never spin-relaunch a
        // silently denied IntentSender, or replay a flow that already departed/returned.
        if (!this.resumed || this.busy || this.setupSession.running || !this.installer.hasConfirmation()
                || this.setupSession.systemUiDeparted || this.systemReturnPending
                || this.installer.installationResultBoundary()) return;
        if (this.openedConfirmationNonce.equals(this.installer.requestNonce())
                && android.os.SystemClock.elapsedRealtime() - this.setupSession.confirmationLaunchAttemptAt < 2000L) return;
        this.openedConfirmationNonce = "";
        openInstallConfirmation(true);
    }

    public void openInstallConfirmation() { openInstallConfirmation(false); }

    private void openInstallConfirmation(boolean explicitRetry) {
        if (!explicitRetry && (this.installer.automaticConfirmationAttempted() || this.installer.confirmationUnavailable())) return;
        if (this.busy || !this.resumed || !this.installer.hasConfirmation()) return;
        final String nonce = this.installer.requestNonce();
        if (nonce.isEmpty() || nonce.equals(this.openedConfirmationNonce)) return;
        setupWork(DiagnosticAction.INSTALL, InstallUiCopy.STARTING.text(), this.installer::confirmationToken,
                (owner, token) -> {
                    if (!owner.resumed || !nonce.equals(owner.installer.requestNonce())) return;
                    owner.installer.markConfirmationAttempt();
                    owner.openedConfirmationNonce = nonce;
                    owner.setupSession.confirmationLaunchAttemptAt = android.os.SystemClock.elapsedRealtime();
                    owner.setupSession.flow.beginPending(GameSetupFlow.Pending.SYSTEM_CONFIRMATION);
                    AndroidDiagnosticLogging.confirmationEvent("CONFIRMATION_LAUNCH", DiagnosticResult.STARTED);
                    try {
                        owner.installer.confirmationLaunched();
                        owner.startIntentSenderForResult(token.getIntentSender(), SYSTEM_INSTALL, null, 0, 0, 0,
                                RuntimePlatformCompat.systemConfirmationLaunchOptions());
                        // This confirms only that Android accepted the call, not that UI appeared.
                        AndroidDiagnosticLogging.confirmationEvent("CONFIRMATION_LAUNCH", DiagnosticResult.PENDING);
                    } catch (Exception error) {
                        owner.openedConfirmationNonce = "";
                        owner.setupSession.flow.cancelPending();
                        try { owner.installer.confirmationLaunchFailed(); }
                        catch (Exception checkpointError) { error.addSuppressed(checkpointError); }
                        AndroidDiagnosticLogging.confirmationEvent("CONFIRMATION_LAUNCH", DiagnosticResult.FAILED);
                        throw error;
                    }
                });
    }

    /** Only an actual pending system flow reaches here. Ordinary resume performs no readiness I/O. */
    private void drainPendingSetup() {
        if (this.rootGame != null || this.setupSession.flow.rootAuthorized()) return;
        if (!this.resumed || this.busy || this.setupSession.running || isDestroyed()
                || this.setupSession.modeChoice.pending() != null) return;
        if (this.setupSession.activationAdvancePending) {
            this.setupSession.activationAdvancePending = false; maybeActivateSetupComponent(); return;
        }
        if (this.activationReturnPending) {
            this.activationReturnPending = false;
            refreshReadiness();
            return;
        }
        if (this.setupSession.installationAdvancePending) { continueInstallation(); return; }
        if (this.installer.externalInstallPending()) { render(); return; }
        if (!this.installer.hasPendingResult() && !this.installer.mayContinueAfterUninstall()) return;
        if (this.installer.confirmationUnavailable()) { render(); return; }
        if (this.installer.hasConfirmation() && !this.installer.installationResultBoundary()
                && !this.installer.confirmationWasLaunched()
                && !this.installer.automaticConfirmationAttempted()
                && !this.installer.requestNonce().equals(this.openedConfirmationNonce)) {
            openInstallConfirmation(); return;
        }
        if (this.pendingSystemChecks >= 12) {
            if (!this.installer.installationResultBoundary()) return;
            this.pendingSystemChecks = 0; // The pending system flow ended after the earlier bounded wait.
        }
        final boolean returned = this.systemReturnPending;
        this.pendingSystemChecks++;
        setupWork(DiagnosticAction.INSTALL, InstallUiCopy.STARTING.text(), () -> {
            this.installer.reconcile(returned);
            if (this.pendingSystemChecks >= 12 && this.installer.hasPendingResult()
                    && !this.installer.confirmationUnavailable()
                    && (returned || this.installer.installationResultBoundary()))
                this.installer.settleClosedSystemRequest();
            return this.installer.hasPendingResult();
        }, (owner, stillPending) -> {
            if (!stillPending) {
                owner.systemReturnPending = false;
                owner.setupSession.flow.cancelPending();
                if (owner.installer.mayContinueAfterUninstall()) {
                    // This flag is set only after the requested target package is confirmed absent.
                    owner.setupSession.automaticInstallTarget = owner.installer.pendingPackage();
                    owner.installer.clearAutomaticContinuation();
                    owner.continueInstallation();
                } else {
                    owner.setupOutcome = owner.installer.lastResultMessage();
                    owner.notice = owner.setupOutcome;
                    owner.refreshReadiness();
                }
            } else if (owner.installer.confirmationUnavailable()) {
                owner.notice = owner.installer.summary();
            } else if (owner.pendingSystemChecks < 12) {
                owner.setupHandler.postDelayed(owner::drainPendingSetup, 500L);
            } else {
                owner.notice = SYSTEM_INSTALLATION_IS_UNFINISHED_YOU_CAN_112.text();
                owner.render();
            }
        });
    }

    private void maybeActivateSetupComponent() {
        if (this.busy || this.installedBridge == null || this.readyForSaves()
                || (this.page == SyncPage.DISCOVERY && this.installer.acceptedInstalledComponent(this.installedBridge))
                || this.setupSession.modeChoice.pending() != null) return;
        if (!this.resumed) { this.setupSession.activationAdvancePending = true; return; }
        if (!HelperActivationPolicy.canOffer(this.readinessState, this.bridgeAccessCode, false)) return;
        if (!this.setupSession.flow.beginActivation()) return;
        final InstalledBridgeBinding binding = this.installedBridge;
        setupWork(DiagnosticAction.ACCESS_CHECK, CONNECTING_GAME_COMPONENTS_84.text(),
                () -> HelperActivation.installedIntent(this, binding), (owner, launch) -> {
                    if (!owner.resumed) { owner.setupSession.flow.cancelPending(); return; }
                    try { owner.startActivityForResult(launch, ACTIVATE_HELPER); }
                    catch (RuntimeException error) {
                        owner.setupSession.flow.cancelPending(); throw new IOException(GAME_COMPONENTS_COULD_NOT_START_PLEASE_113.text(), error);
                    }
                });
    }

    private String savePathLabel() {
        if (this.rootGame != null) return new java.io.File(this.rootGame.dataDir, "files").getPath();
        String gamePackage = selectedGamePackage();
        if (gamePackage == null) return NO_GAME_SELECTED_9.text();
        try {
            return new java.io.File(getPackageManager().getApplicationInfo(gamePackage, 0).dataDir, "files").getPath();
        } catch (PackageManager.NameNotFoundException unavailable) {
            return gamePackage + " / files";
        }
    }



    private String automaticStatusBrief() {
        if (!automaticObservationSupported() || SyncRuntime.isAutoUploadPaused(this) || this.progress != OnboardingProgress.COMPLETE) return "";
        String result = AutoSyncService.status(this);
        switch (AutoSyncService.resultKind(this)) {
            case "IDLE": return "";
            case "HISTORY_CONFLICT": return A_SAVE_CONFLICT_EXISTS_AUTO_UPLOAD_115.text();
            case "UPLOADED_CONFLICT": return LOCAL_SAVES_UPLOADED_A_SAVE_CONFLICT_117.text();
            case "UPLOADED_NEWER_CLOUD": return LOCAL_SAVES_UPLOADED_CHOOSE_WHICH_CLOUD_119.text();
            case "INCOMPLETE":
                if (this.details.isEmpty()) this.details = safeText(result);
                return AUTO_UPLOAD_IS_UNFINISHED_PLEASE_CHECK_122.text();
            default: return result;
        }
    }

    private String visibleNotice() {
        if (this.busy) return this.notice;
        if (this.feedbackError) return PreparationDiagnostics.isSafeUserMessage(this.notice)
                ? this.notice : NoviceCopy.feedback(this.notice, THE_OPERATION_DID_NOT_FINISH_PLEASE_123.text());
        if (this.page == SyncPage.DISCOVERY) return this.notice;
        if (!pdsync.i18n.AndroidHostMessages.isBriefNotice(this.notice)
                && !NoviceCopy.isComparisonMessage(this.notice)) return "";
        return this.notice;
    }

    /** Android resolves and reads the real game path; shared Compose owns only presentation. */
    private void renderSharedSavePath() {
        bindPathPreviewContext();
        final int renderToken = ++this.sharedPathRenderToken;
        String pathNotice = visibleNotice();
        if (pathNotice.isEmpty() && !readyForSaves() && !this.pathPreviewState.inFlight())
            pathNotice = SAVES_CANNOT_BE_READ_RIGHT_NOW_125.text();
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
                this.busy, pathNotice, "", readyForSaves() && this.localPreview != null,
                readStatus, this.pathPreviewState.continueAfterRead(),
                canRequestSaveAccess() || readStatus == SharedSavePathReadStatus.FAILED, selectedGameIdentity(),
                this.readinessState.withSystemRequest(this.installer.hasActiveRequest()).canInitializeGame(),
                readyForSaves() ? CHECK_SAVES_AGAIN_126.text() : CHECK_STATUS_AGAIN_127.text(), false,
                () -> { if (isCurrentPathAction(renderToken)) m108xe2dc5156(); },
                () -> {
                    if (isCurrentPathAction(renderToken) && !this.pathPreviewState.inFlight()
                            && this.readinessState.withSystemRequest(this.installer.hasActiveRequest()).canInitializeGame())
                        launchGameForInitialization();
                },
                () -> { if (isCurrentPathAction(renderToken)) m109x58567797(); },
                () -> { if (isCurrentPathAction(renderToken)) requestPathNext(); },
                () -> {
                    // Technical diagnostics are available only in the global log export.
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
        else if (this.rootGame != null) previewPath(false);
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
        this.notice = READING_SAVES_128.text();
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
        if (!isCurrentPathRead(request)) throw new IOException(SAVE_CHECK_CANCELED_129.text());
        this.installer.reconcile();
        SelectedRootGame chosen = SyncRuntime.rootGame(this);
        if (chosen == null || !chosen.selectionToken().equals(request.context.selectionToken))
            throw new IOException(THE_SELECTED_ACCESS_METHOD_HAS_CHANGED_12.text());
        backend.targetGame();
        if (!backend.capabilities().exportSupported) throw new IOException(THE_CURRENT_SAVE_BACKEND_DOES_NOT_130.text());
        if (this.installer.hasActiveRequest()) throw new IOException(HANDLE_THE_CURRENT_SYSTEM_INSTALLATION_REQUEST_13.text());
        if (hasAnyRestoreJournal()) {
            // Reading never performs recovery. The original explicit recovery flow remains available.
            return InstallationReadiness.rootDirect(InstallationReadiness.PackageStatus.VERIFIED,
                    true, false, true, InstallationReadiness.AccessStatus.RECOVERY_NEEDED);
        }
        if (!isCurrentPathRead(request)) throw new IOException(SAVE_CHECK_CANCELED_129.text());
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
            throw new IOException(COMPLETE_THE_SAVE_ACCESS_CHECK_FIRST_131.text());
        }
        if (!isCurrentPathRead(request)) throw new IOException(SAVE_CHECK_CANCELED_129.text());
        if (this.installer.hasActiveRequest() || hasAnyRestoreJournal())
            throw new IOException(COMPLETE_THE_SAVE_ACCESS_CHECK_OR_132.text());
        try (TrustedPrivateDirectory.TemporaryDirectory temporaryDirectory = TrustedPrivateDirectory.resolve(
                getDataDir().toPath(), getCacheDir().toPath(), Process.myUid(), MainActivity::lambda$previewPath$49)
                .createTemporaryDirectory("save-preview-")) {
            if (!isCurrentPathRead(request)) throw new IOException(SAVE_CHECK_CANCELED_129.text());
            backend.exportTo(temporaryDirectory.path());
            return SaveSummary.Companion.read(temporaryDirectory.path());
        }
    }

    static /* synthetic */ TrustedPrivateDirectory.State lambda$previewPath$49(Path path) throws IOException {
        try {
            StructStat structStatLstat = Os.lstat(path.toString());
            return new TrustedPrivateDirectory.State(structStatLstat.st_dev, structStatLstat.st_ino, structStatLstat.st_uid, OsConstants.S_ISDIR(structStatLstat.st_mode));
        } catch (ErrnoException e) {
            throw new IOException(COULD_NOT_VERIFY_THE_LOCAL_PREVIEW_133.text(), e);
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
                    : new IOException(SAVE_ACCESS_STATE_HAS_CHANGED_PLEASE_134.text()));
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
            this.notice = NoviceCopy.feedback(safeText(BridgeDiagnostics.find(failure).getMessage()), COULD_NOT_READ_SAVES_PLEASE_RETRY_3.text());
        } else this.notice = safeError(failure);
    }

    private void loadDraft() {
        this.draftProfile = this.connection == null ? "default" : this.connection.profile;
        this.draftLoaded = true;
    }

    /** Game selection and service configuration use the shared Compose pages. */
    private void renderSharedConnection() {
        if (this.initialLoad && this.sharedConnectionSession == null) return;
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
                this.connectionLoadError.isEmpty() ? "" : COULD_NOT_READ_CONNECTION_SETTINGS_PLEASE_2.text(),
                canRestore ? (this.connectionLoadError.isEmpty() ? RESTORE_SAVED_CONNECTION_135.text() : RETRY_READING_SAVED_CONNECTION_136.text()) : "");
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
            drainRenewalArchiveRead();
            return;
        }
        navigate(this.onboardingActive ? SyncPage.DISCOVERY : SyncPage.SETTINGS);
    }

    private boolean currentRenewalAccount(SharedConnectionConfig source) {
        SecureConnectionStore.Connection current = this.connection;
        if (isDestroyed() || this.sharedConnectionSession == null || !isOfficialAccount(current)
                || !source.getEndpoint().equals(current.endpoint) || !source.getUsername().equals(current.username)
                || !source.getPassword().equals(current.password) || !source.getProfile().equals(current.profile)) return false;
        try { return this.renewalArchiveRead.matches(current, SyncRuntime.currentSelectionToken(this), SyncRuntime.currentNamespace(this)); }
        catch (Exception unavailable) { return false; }
    }

    @Override public void onAccountRefreshStarted(SharedConnectionConfig source) {
        if (currentRenewalAccount(source)) this.renewalArchiveRead.started();
    }

    @Override public void onAccountAccessChanged(SharedConnectionConfig source, boolean available) {
        if (currentRenewalAccount(source)) this.renewalArchiveRead.result(available);
    }

    private void drainRenewalArchiveRead() {
        if (!isForegroundHome() || this.busy || this.installer.hasActiveRequest()) return;
        final SecureConnectionStore.Connection account = this.connection;
        final String selection, namespace;
        try {
            selection = SyncRuntime.currentSelectionToken(this); namespace = SyncRuntime.currentNamespace(this);
            if (!this.renewalArchiveRead.take(account, selection, namespace)) return;
        } catch (Exception unavailable) { this.renewalArchiveRead.clear(); return; }
        if (!homeAccountAccessAvailable()) return;
        readHomeArchives(account, selection, namespace);
    }

    private static final class DetachedHomeRead {
        final SecureConnectionStore.Connection account;
        final OfficialOperationLease.Binding binding;
        final long ticket;
        final AutoSyncHistoryRefresh.Ticket automatic;
        final HomeReadCancellation cancellation = new HomeReadCancellation();
        DetachedHomeRead(SecureConnectionStore.Connection account, OfficialOperationLease.Binding binding,
                         long ticket, AutoSyncHistoryRefresh.Ticket automatic) {
            this.account = account; this.binding = binding; this.ticket = ticket; this.automatic = automatic;
        }
    }

    private static final class DetachedHomeOutcome {
        AndroidSyncFacade facade;
        AndroidSyncFacade.Inspection inspection;
        SyncHistory history;
        Exception error;
        boolean notReady;
        long observationGeneration = -1;
    }

    private void cancelDetachedHomeRead() {
        DetachedHomeRead old = this.detachedHomeRead;
        this.detachedHomeRead = null;
        if (old == null) return;
        this.archiveReads.finish(old.ticket);
        old.cancellation.cancel();
        if (old.automatic != null) {
            this.automaticHistoryState.inFlight = false;
            this.automaticHistoryState.refresh.deferBeforeRead(old.automatic);
        }
    }

    private void requireDetachedHomeRead(DetachedHomeRead read) throws Exception {
        read.cancellation.checkActive();
        if (this.detachedHomeRead != read || !this.archiveReads.accepts(read.ticket)
                || !isForegroundHome() || this.busy || this.installer.hasActiveRequest()
                || !read.binding.matches(officialOperationBinding(read.account))
                || read.automatic != null && !currentAutomaticHistory(read.automatic, read.account))
            throw new StaleArchiveReadException();
    }

    private boolean startDetachedHomeRead(SecureConnectionStore.Connection account, boolean prepareNamespace,
                                         boolean checkReadiness, AutoSyncHistoryRefresh.Ticket automatic) {
        if (!isForegroundHome() || this.busy || this.installer.hasActiveRequest() || account == null) return false;
        final OfficialOperationLease.Binding binding;
        try { binding = officialOperationBinding(account); }
        catch (Exception changed) { return false; }
        cancelDetachedHomeRead();
        final DetachedHomeRead read = new DetachedHomeRead(account, binding, this.archiveReads.begin(), automatic);
        this.detachedHomeRead = read;
        if (automatic != null) this.automaticHistoryState.inFlight = true;
        this.cloudVerified = false; this.inspection = null; this.history = null; this.homeObservationOwner = null;
        this.details = ""; this.feedbackError = false; this.notice = CHECKING_SAVES_144.text();
        render();
        final DiagnosticTrace trace = DiagnosticLog.begin(DiagnosticAction.INSPECT, false);
        final DiagnosticTiming.Scope queueWait = DiagnosticTiming.start(trace, DiagnosticPhase.QUEUE_WAIT);
        read.cancellation.attach(this.homeReader.submit(() -> {
            queueWait.close();
            DetachedHomeOutcome outcome = new DetachedHomeOutcome();
            boolean cancelled = false;
            try (AndroidDiagnosticLogging.Scope diagnostic = AndroidDiagnosticLogging.bind(trace);
                 OfficialOperationLease lease = new OfficialOperationLease()) {
                trace.record(DiagnosticStage.VERIFY, DiagnosticResult.STARTED);
                requireDetachedHomeRead(read);
                synchronized (SyncRuntime.OPERATION_LOCK) {
                    requireDetachedHomeRead(read);
                    if (checkReadiness) {
                        readInstallationReadiness();
                        if (this.rootGame != null) checkRootAccess();
                    }
                    requireDetachedHomeRead(read);
                    if (!readyForSaves()) outcome.notReady = true;
                    else outcome.facade = SyncRuntime.localFacade(this, account);
                }
                if (!outcome.notReady) {
                    final AndroidSyncFacade owner = outcome.facade;
                    // This read owns its lease through capture/network/accept. No work() lease is revived.
                    lease.authorize(binding, owner, SETTINGS_HAVE_CHANGED_PLEASE_RETRY_137.text(),
                            () -> SyncRuntime.requireOfficialSyncAccess(getApplicationContext(), account, owner),
                            () -> SyncRuntime.checkOfficialSyncAccess(account, owner),
                            () -> requireDetachedHomeRead(read), () -> SyncRuntime.clearOfficialSyncAccess(owner));
                    try (AutoCloseable identity = owner.beginHomeReadIdentityScope()) {
                        final AndroidSyncFacade.HomeReadContext context;
                        synchronized (SyncRuntime.OPERATION_LOCK) {
                            requireDetachedHomeRead(read);
                            if (prepareNamespace && isOfficialAccount(account) && !this.preparedOfficialNamespace.contains(binding)) {
                                AndroidSyncFacade.ConnectionStatus prepared = owner.connect();
                                if (!prepared.isSuccess()) throw new IOException(THE_CLOUD_IS_TEMPORARILY_UNAVAILABLE_PLEASE_145.text());
                                requireDetachedHomeRead(read);
                                this.preparedOfficialNamespace.succeeded(binding);
                            }
                            context = owner.captureHomeReadContext();
                            requireDetachedHomeRead(read);
                        }
                        if (context == null) {
                            // Unsupported helpers/recovery retain the serialized legacy boundary.
                            synchronized (SyncRuntime.OPERATION_LOCK) {
                                requireDetachedHomeRead(read);
                                outcome.inspection = owner.inspectHome();
                                outcome.history = "BLOCKED".equals(outcome.inspection.getKind()) ? null : owner.historyPresentation(outcome.inspection);
                                outcome.observationGeneration = owner.observationGeneration();
                            }
                        } else {
                            read.cancellation.attach(context);
                            try (AndroidSyncFacade.HomeReadContext ignored = context) {
                                requireDetachedHomeRead(read);
                                AndroidSyncFacade.HomeReadResult remote = owner.readHomeRemote(context);
                                synchronized (SyncRuntime.OPERATION_LOCK) {
                                    requireDetachedHomeRead(read);
                                    outcome.inspection = owner.acceptHomeObservation(remote);
                                    if (outcome.inspection == null) throw new StaleArchiveReadException();
                                    outcome.history = "BLOCKED".equals(outcome.inspection.getKind()) ? null : owner.historyPresentation(outcome.inspection);
                                    outcome.observationGeneration = owner.observationGeneration();
                                }
                            }
                        }
                    }
                    requireDetachedHomeRead(read);
                }
            } catch (java.util.concurrent.CancellationException | StaleArchiveReadException stale) {
                cancelled = true;
            } catch (Exception error) {
                try {
                    requireDetachedHomeRead(read);
                    outcome.inspection = null; outcome.history = null;
                    outcome.error = SyncInspectionDiagnostics.at(SyncInspectionDiagnostics.Stage.INSPECT, error);
                }
                catch (Exception stale) { cancelled = true; }
            }
            final DetachedHomeOutcome completed = outcome;
            final boolean discarded = cancelled;
            final DiagnosticTiming.Scope uiCompletion = DiagnosticTiming.start(trace, DiagnosticPhase.UI_COMPLETION);
            runOnUiThread(() -> {
                try { finishDetachedHomeRead(read, completed, discarded); }
                finally {
                    uiCompletion.close();
                    if (discarded) trace.record(DiagnosticStage.FINISHED, DiagnosticResult.SKIPPED);
                    else if (completed.error != null) AndroidDiagnosticLogging.failure(trace, DiagnosticStage.FINISHED, completed.error);
                    else trace.record(DiagnosticStage.FINISHED, DiagnosticResult.SUCCEEDED);
                }
            });
        }));
        return true;
    }

    private void finishDetachedHomeRead(DetachedHomeRead read, DetachedHomeOutcome outcome, boolean discarded) {
        try { requireDetachedHomeRead(read); }
        catch (Exception stale) { discarded = true; }
        if (outcome.inspection != null && outcome.facade.observationGeneration() != outcome.observationGeneration) discarded = true;
        if (this.detachedHomeRead != read) return;
        this.detachedHomeRead = null;
        this.archiveReads.finish(read.ticket);
        if (read.automatic != null) {
            this.automaticHistoryState.inFlight = false;
            if (discarded || outcome.notReady || !AutoSyncService.claimHistoryRefresh(this, read.automatic)) {
                this.automaticHistoryState.refresh.deferBeforeRead(read.automatic);
                if (isForegroundHome()) render();
                return;
            }
        }
        if (discarded) { if (isForegroundHome()) render(); return; }
        if (read.automatic != null) {
            AutomaticHistoryOutcome automatic = new AutomaticHistoryOutcome(read.automatic, read.account);
            automatic.facade = outcome.facade; automatic.inspection = outcome.inspection;
            automatic.history = outcome.history; automatic.error = outcome.error;
            automatic.observationGeneration = outcome.observationGeneration;
            this.automaticHistoryState.outcome = automatic;
            drainAutomaticHistoryRefresh();
            return;
        }
        if (outcome.error != null) {
            invalidatePreparedOfficialNamespace(); reportError(outcome.error);
        } else if (outcome.notReady) {
            this.notice = this.readinessState.statusDescription(); maybeActivateSetupComponent();
        } else {
            this.facade = outcome.facade; this.inspection = outcome.inspection; this.history = outcome.history;
            this.cloudVerified = outcome.history != null;
            this.homeObservationOwner = this.cloudVerified ? currentHomeContext(0) : null;
            if (!this.cloudVerified) invalidatePreparedOfficialNamespace();
            boolean found = false;
            if (this.history != null) for (AppendCommit commit : this.history.getCloud())
                if (commit.getSnapshot().getId().equals(this.selected)) found = true;
            if (!found) { this.selected = this.history == null ? null : this.history.getDefaultSnapshot(); this.explicitHomeSelection = null; }
            this.notice = comparisonCopy();
            this.details = this.inspection == null ? "" : safeText(this.inspection.getMessage());
        }
        render();
        drainRenewalArchiveRead();
        drainAutomaticHistoryRefresh();
    }

    private boolean isCurrentHomeRead(SecureConnectionStore.Connection account, String selection, String namespace) {
        if (!isForegroundHome() || this.connection != account || SyncRuntime.currentConnection() != account) return false;
        try { return selection.equals(SyncRuntime.currentSelectionToken(this)) && namespace.equals(SyncRuntime.currentNamespace(this)); }
        catch (Exception unavailable) { return false; }
    }

    /** Shared by renewal and explicit Refresh; never provisions or writes the remote namespace. */
    private void readHomeArchives(final SecureConnectionStore.Connection account, final String selection, final String namespace) {
        if (!isCurrentHomeRead(account, selection, namespace) || this.busy || this.installer.hasActiveRequest()) return;
        startDetachedHomeRead(account, false, false, null);
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
        AndroidConnectionComposition.Session owner = this.sharedConnectionSession;
        SecureConnectionStore.Connection committed = owner == null ? null : owner.completedConnection(config);
        this.pendingSharedCommit = null;
        if (committed == null) {
            this.notice = SETTINGS_HAVE_CHANGED_PLEASE_RETRY_137.text();
            this.feedbackError = true;
            return false;
        }
        this.sharedCommitSerial++;
        // The adapter has persisted completion and verified its captured game/account context.
        this.connection = committed;
        this.initialLoad = false;
        this.facade = null;
        this.connectionLoadError = "";
        this.inspection = null;
        this.history = null;
        this.selected = null;
        this.explicitHomeSelection = null;
        this.cloudVerified = false;
        this.progress = OnboardingProgress.COMPLETE;
        this.onboardingActive = false;
        this.notice = SETUP_COMPLETE_124.text();
        this.feedbackError = false;
        closeSharedConnection();
        this.page = SyncPage.HOME;
        render();
        refreshComparison(SyncPage.HOME);
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

    private String officialAppreciationLabel(String username) {
        TrustedEntitlementCache.Record cached = entitlementCache(getApplicationContext())
                .read(OfficialServiceRegistrationClient.DAV_URL, username);
        return cached != null && cached.expired(android.os.SystemClock.elapsedRealtime(), bootCount(getApplicationContext()))
                ? RENEW_SUPPORT_138.text() : SUPPORT_139.text();
    }

    private void showManagedAccount(SecureConnectionStore.Connection source) {
        if (this.busy || !isOfficialAccount(source) || isDestroyed()) return;
        cancelDetachedHomeRead();
        closeSharedConnection();
        try {
            this.renewalArchiveRead.open(source, SyncRuntime.currentSelectionToken(this), SyncRuntime.currentNamespace(this),
                    homeAccountAccessAvailable() && this.cloudVerified && this.inspection != null && this.history != null);
        } catch (Exception unavailable) { this.renewalArchiveRead.clear(); }
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
        work(DiagnosticAction.CONFIGURE, READING_THE_SAVED_CONNECTION_140.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda110
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
            throw new IOException(NO_SAVED_CONNECTION_FILE_EXISTS_THE_141.text());
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
        this.notice = SAVED_CONNECTION_RESTORED_TEST_IT_BEFORE_142.text();
    }

    private AndroidSyncFacade sync() throws Exception {
        AndroidSyncFacade owner = requireSyncFacade();
        requireOfficialSyncAccess(this.connection);
        return owner;
    }

    /** Local maintenance never requires renewed cloud access, and cannot accidentally perform DAV I/O. */
    private AndroidSyncFacade localSync() throws Exception {
        OfficialOperationLease lease = this.officialOperationLease.get();
        if (lease != null) lease.invalidate();
        AndroidSyncFacade owner = requireSyncFacade();
        SyncRuntime.clearOfficialSyncAccess(owner);
        return owner;
    }

    private AndroidSyncFacade requireSyncFacade() throws Exception {
        SecureConnectionStore.Connection connection = this.connection;
        if (connection == null) throw new IOException(SET_UP_THE_CLOUD_CONNECTION_FIRST_143.text());
        if (this.facade == null) this.facade = SyncRuntime.localFacade(this, connection);
        if (this.connection != connection) throw new IOException(THE_CONNECTION_HAS_CHANGED_COMPARE_AGAIN_158.text());
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
        final long credentialsGeneration;
        AndroidSyncFacade facade;
        AndroidSyncFacade.Inspection inspection;
        SyncHistory history;
        Exception error;
        boolean applied;
        long observationGeneration = -1;
        AutomaticHistoryOutcome(AutoSyncHistoryRefresh.Ticket ticket, SecureConnectionStore.Connection connection) {
            this.ticket = ticket; this.connection = connection;
            this.credentialsGeneration = SyncRuntime.connectionGeneration();
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
        current.observationGeneration = this.facade.observationGeneration();
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
            if (completed.credentialsGeneration == SyncRuntime.connectionGeneration()
                    && (completed.inspection == null || completed.facade.observationGeneration() == completed.observationGeneration)
                    && currentAutomaticHistory(completed.ticket, completed.connection)) {
                if (!completed.applied) {
                    completed.applied = true;
                    if (completed.error != null) {
                        invalidatePreparedOfficialNamespace();
                        this.cloudVerified = false;
                        this.inspection = null;
                        this.history = null;
                        this.homeObservationOwner = null;
                        reportError(completed.error);
                    } else {
                        this.facade = completed.facade;
                        this.inspection = completed.inspection;
                        this.history = completed.history;
                        if (completed.history == null) invalidatePreparedOfficialNamespace();
                        this.cloudVerified = completed.history != null;
                        this.homeObservationOwner = this.cloudVerified ? currentHomeContext(0) : null;
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
        if (!startDetachedHomeRead(captured, false, false, ticket)) {
            state.inFlight = false;
            state.refresh.deferBeforeRead(ticket);
        }
    }

    /** Idempotent app-folder preparation after configuration; it never creates a save or baseline. */
    private void prepareOfficialHomeNamespace(AndroidSyncFacade owner) throws Exception {
        final SecureConnectionStore.Connection account = this.connection;
        if (this.page != SyncPage.HOME || this.progress != OnboardingProgress.COMPLETE
                || !isOfficialAccount(account)) return;
        final String selection = SyncRuntime.currentSelectionToken(this);
        final String namespace = SyncRuntime.currentNamespace(this);
        final long generation = SyncRuntime.connectionGeneration();
        final OfficialOperationLease.Binding key = new OfficialOperationLease.Binding(account, generation,
                account.endpoint, account.username, account.profile, selection, namespace);
        try {
            if (!currentOfficialHomeNamespace(owner, account, selection, namespace)
                    || generation != SyncRuntime.connectionGeneration())
                throw new IOException(SETTINGS_HAVE_CHANGED_PLEASE_RETRY_137.text());
            if (this.preparedOfficialNamespace.contains(key)) return;
            AndroidSyncFacade.ConnectionStatus preparedNamespace = owner.connect();
            if (!preparedNamespace.isSuccess()) throw new IOException(THE_CLOUD_IS_TEMPORARILY_UNAVAILABLE_PLEASE_145.text());
            if (!currentOfficialHomeNamespace(owner, account, selection, namespace)
                    || generation != SyncRuntime.connectionGeneration())
                throw new IOException(SETTINGS_HAVE_CHANGED_PLEASE_RETRY_137.text());
            this.preparedOfficialNamespace.succeeded(key);
        } catch (Exception failure) {
            invalidatePreparedOfficialNamespace();
            throw failure;
        }
    }

    private void invalidatePreparedOfficialNamespace() { this.preparedOfficialNamespace.clear(); }

    private boolean currentOfficialHomeNamespace(AndroidSyncFacade owner, SecureConnectionStore.Connection account,
                                                 String selection, String namespace) throws Exception {
        return this.resumed && !isDestroyed() && this.page == SyncPage.HOME && this.facade == owner
                && this.connection == account && SyncRuntime.currentConnection() == account
                && selection.equals(SyncRuntime.currentSelectionToken(this))
                && namespace.equals(SyncRuntime.currentNamespace(this));
    }

    /** Snapshot-only loading state; querying it must never enqueue another archive read. */
    private boolean isHomeArchiveLoading() {
        return isForegroundHome() && (this.archiveReads.loading()
                || (this.automaticHistoryState.inFlight
                    && currentAutomaticHistory(this.automaticHistoryState.ticket, this.automaticHistoryState.connection)));
    }

    private void publishArchiveReadState() {
        runOnUiThread(() -> {
            if (isForegroundHome()) render();
        });
    }

    private static final class StaleArchiveReadException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private void requireCurrentArchiveRead(long ticket, SecureConnectionStore.Connection account,
            String selection, String namespace, SyncPage origin) throws StaleArchiveReadException {
        try {
            if (this.archiveReads.accepts(ticket) && !isDestroyed() && this.connection == account
                    && SyncRuntime.currentConnection() == account && this.page == origin
                    && selection.equals(SyncRuntime.currentSelectionToken(this))
                    && namespace.equals(SyncRuntime.currentNamespace(this))) return;
        } catch (Exception unavailable) { /* An unverifiable scope cannot publish an old observation. */ }
        throw new StaleArchiveReadException();
    }

    private void readComparison() throws Exception { readComparison(true); }

    private void readComparison(boolean prepareNamespace) throws Exception { readComparison(prepareNamespace, true); }

    private void readComparison(boolean prepareNamespace, boolean allowHomePreview) throws Exception {
        final SecureConnectionStore.Connection archiveAccount = this.connection;
        final String archiveSelection = SyncRuntime.currentSelectionToken(this);
        final String archiveNamespace = SyncRuntime.currentNamespace(this);
        final SyncPage archivePage = this.page;
        final boolean homePreview = allowHomePreview && isForegroundHome() && this.progress == OnboardingProgress.COMPLETE;
        final long archiveRead = this.archiveReads.begin();
        publishArchiveReadState();
        try {
            requireCurrentArchiveRead(archiveRead, archiveAccount, archiveSelection, archiveNamespace, archivePage);
            this.cloudVerified = false;
            this.history = null;
            this.inspection = null;
            this.homeObservationOwner = null;
            // One facade and one observation own both the action proof and its timeline.
            // Drawing, paging, selecting, or dismissing the timeline must never re-export saves.
            SyncInspectionDiagnostics.Stage stage = SyncInspectionDiagnostics.Stage.RUNTIME;
            AutoSyncStatusPolicy.Record oldIdleFailure = AutoSyncService.beginManualInspection(this);
            try {
                AndroidSyncFacade activeFacade = sync();
                stage = SyncInspectionDiagnostics.Stage.INSPECT;
                if (prepareNamespace) prepareOfficialHomeNamespace(activeFacade);
                final AndroidSyncFacade.Inspection inspectionInspect;
                final SyncHistory observedHistory;
                // The final installed-package check is part of the read. Do not publish even a
                // BLOCKED observation until close succeeds; a failed close leaves HOME unknown.
                try (AutoCloseable identity = homePreview ? activeFacade.beginHomeReadIdentityScope() : () -> {}) {
                    inspectionInspect = homePreview ? activeFacade.inspectHome() : activeFacade.inspect();
                    DiagnosticLog.record(DiagnosticStage.VERIFY,
                            AndroidDiagnosticLogging.inspectionResult(inspectionInspect.getKind(), false),
                            inspectionInspect.getNeedsRestoreRecovery() ? DiagnosticFailure.RECOVERY_REQUIRED : DiagnosticFailure.NONE,
                            diagnosticInspectionDetails(inspectionInspect));
                    if (!prepareNamespace && !isForegroundHome()) throw new StaleArchiveReadException();
                    if ("BLOCKED".equals(inspectionInspect.getKind())) observedHistory = null;
                    else {
                        stage = SyncInspectionDiagnostics.Stage.HISTORY;
                        observedHistory = activeFacade.historyPresentation(inspectionInspect);
                    }
                }
                if (!prepareNamespace && !isForegroundHome()) throw new StaleArchiveReadException();
                requireCurrentArchiveRead(archiveRead, archiveAccount, archiveSelection, archiveNamespace, archivePage);
                if ("BLOCKED".equals(inspectionInspect.getKind())) {
                    invalidatePreparedOfficialNamespace();
                    this.inspection = inspectionInspect;
                    return;
                }
                this.inspection = inspectionInspect;
                this.history = observedHistory;
                this.cloudVerified = true;
                this.homeObservationOwner = currentHomeContext(0);
                if (inspectionInspect.isObjectVerified())
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
                if (!prepareNamespace && !isForegroundHome()) throw new StaleArchiveReadException();
                if (error instanceof StaleArchiveReadException) throw error;
                requireCurrentArchiveRead(archiveRead, archiveAccount, archiveSelection, archiveNamespace, archivePage);
                invalidatePreparedOfficialNamespace();
                if (InstallDiagnostics.find(error) != null || PreparationDiagnostics.find(error) != null
                        || BridgeDiagnostics.find(error) != null) throw error;
                throw SyncInspectionDiagnostics.at(stage, error);
            }
        } finally {
            this.archiveReads.finish(archiveRead);
            publishArchiveReadState();
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
        if (syncPage == SyncPage.HOME && isForegroundHome() && this.progress == OnboardingProgress.COMPLETE) {
            startDetachedHomeRead(this.connection, true, z, null);
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
        work(DiagnosticAction.INSPECT, CHECKING_SAVES_144.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda141
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
        readComparison();
        return true;
    }

    /* renamed from: lambda$refreshComparison$62$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m67xf48d062(SyncPage syncPage, Boolean bool) throws Exception {
        this.page = syncPage;
        if (!bool.booleanValue()) {
            this.notice = this.readinessState.statusDescription();
            // The removed path page no longer activates a stopped provider before setup.
            // Reuse the bounded one-shot activation only after this explicit HOME inspection.
            if (syncPage == SyncPage.HOME) maybeActivateSetupComponent();
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
        if (this.cloudVerified && this.inspection != null && !this.inspection.isObjectVerified()
                && kind() == OnboardingKind.IDENTICAL) return CLOUD_CONNECTED_33.text();
        if (this.cloudVerified && this.inspection != null && this.inspection.getCanKeepLocalProgress()) {
            return THIS_DEVICE_HAS_NEW_GAME_PROGRESS_146.text();
        }
        return NoviceCopy.comparison(this.cloudVerified || kind() == OnboardingKind.BLOCKED ? kind() : null, this.busy);
    }

    /** Manual HOME operations use a fresh observation and never reopen setup comparison. */
    public void requestUpload() {
        if (!canHomeUpload()) return;
        if (this.connection == null) {
            navigate(SyncPage.CONNECTION);
            return;
        }
        if (this.progress != OnboardingProgress.COMPLETE) {
            this.onboardingActive = true;
            navigate(SyncPage.CONNECTION);
        } else if (this.inspection == null || !this.cloudVerified) {
            refreshComparison(SyncPage.HOME);
        } else {
            upload();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void upload() {
        if (this.page == SyncPage.HOME && !canHomeUpload()) return;
        final AndroidSyncFacade.Inspection inspection = this.inspection;
        if (inspection == null) {
            refreshComparison(SyncPage.HOME);
            return;
        }
        operation(DiagnosticAction.PUSH, UPLOADING_LOCAL_SAVES_147.text(), () -> {
            return m146lambda$upload$71$iogithubxyz1001pdsyncandroidMainActivity(inspection);
        }, LOCAL_SAVES_UPLOADED_AND_VERIFIED_148.text());
    }

    /* renamed from: lambda$upload$71$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m146lambda$upload$71$iogithubxyz1001pdsyncandroidMainActivity(AndroidSyncFacade.Inspection inspection) throws Exception {
        return sync().upload(inspection);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void adopt() {
        final AndroidSyncFacade.Inspection observed = this.inspection;
        if (observed == null || !observed.getCanAdoptRemote()) return;
        operation(DiagnosticAction.RESOLVE, LINKING_IDENTICAL_SAVES_149.text(), () -> {
            return m15lambda$adopt$72$iogithubxyz1001pdsyncandroidMainActivity(observed);
        }, IDENTICAL_LOCAL_AND_CLOUD_CONTENT_LINKED_150.text());
    }

    /* renamed from: lambda$adopt$72$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m15lambda$adopt$72$iogithubxyz1001pdsyncandroidMainActivity(AndroidSyncFacade.Inspection inspection) throws Exception {
        return sync().adoptIdentical(inspection);
    }

    private boolean mayRestoreWithoutReplacementConfirmation(SyncHistory observed) {
        if (observed == null) return false;
        if (observed.getCurrentProtected()) return true;
        // This only preserves the existing action route. restoreSelected still upgrades the
        // metadata observation and checks complete object bytes before allowing replacement.
        if (this.inspection != null && !this.inspection.isObjectVerified()) {
            for (AppendCommit commit : observed.getCloud())
                if (commit.getSnapshot().getFiles().equals(observed.getCurrentFiles())) return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestRestore() {
        if (!canHomeRestore()) return;
        if (!allowRestoreAction()) return;
        if (this.connection == null) {
            navigate(SyncPage.CONNECTION);
            return;
        }
        if (this.progress != OnboardingProgress.COMPLETE) {
            this.onboardingActive = true;
            navigate(SyncPage.CONNECTION);
            return;
        }
        if (this.inspection == null || !this.cloudVerified) {
            refreshComparison(SyncPage.HOME);
            return;
        }
        if (kind() == OnboardingKind.BRANCHED) {
            navigate(SyncPage.CONFLICT);
            return;
        }
        final String str = this.selected;
        if (str == null) {
            this.notice = SELECT_A_CLOUD_SAVE_FIRST_151.text();
            render();
            return;
        }
        final AndroidSyncFacade.Inspection inspection = this.inspection;
        SyncHistory syncHistory = this.history;
        if (mayRestoreWithoutReplacementConfirmation(syncHistory)) {
            operation(DiagnosticAction.RESTORE, VALIDATING_BACKING_UP_AND_RESTORING_THE_152.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda13
                @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
                public final Object run() throws Exception {
                    return MainActivity.this.m141x92df934d(inspection, str);
                }
            }, GAME_SAVES_RESTORED_AND_VERIFIED_153.text());
            return;
        }
        showDestructiveConfirmation(RESTORE_SAVE_154.text(), SELECTED_SAVE_155.text() + selectedName() + N_NLOCAL_SAVES_WILL_BE_BACKED_156.text(), OVERWRITE_LOCAL_SAVES_157.text(), new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda14
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
        AndroidSyncFacade owner = sync();
        return owner.restoreSelected(inspection, snapshotId, replace);
    }

    private void requireOfficialSyncAccess(SecureConnectionStore.Connection account) throws Exception {
        final AndroidSyncFacade owner = this.facade;
        final OfficialOperationLease lease = this.officialOperationLease.get();
        try {
            if (account == null || this.connection != account) throw new IOException(THE_CONNECTION_HAS_CHANGED_COMPARE_AGAIN_158.text());
            if (lease == null) {
                SyncRuntime.requireOfficialSyncAccess(getApplicationContext(), account, owner);
            } else {
                final OfficialOperationLease.Binding binding = officialOperationBinding(account);
                lease.authorize(binding, owner, THE_CONNECTION_HAS_CHANGED_COMPARE_AGAIN_158.text(),
                        () -> SyncRuntime.requireOfficialSyncAccess(getApplicationContext(), account, owner),
                        () -> SyncRuntime.checkOfficialSyncAccess(account, owner),
                        () -> {
                            if (this.facade != owner || !binding.matches(officialOperationBinding(account)))
                                throw new IOException(THE_CONNECTION_HAS_CHANGED_COMPARE_AGAIN_158.text());
                        }, () -> SyncRuntime.clearOfficialSyncAccess(owner));
            }
            if (this.connection != account) throw new IOException(THE_CONNECTION_HAS_CHANGED_COMPARE_AGAIN_158.text());
        } catch (Exception | Error failure) {
            if (lease != null) lease.invalidate();
            SyncRuntime.clearOfficialSyncAccess(owner);
            throw failure;
        }
    }

    private OfficialOperationLease.Binding officialOperationBinding(SecureConnectionStore.Connection account) throws Exception {
        final long credentialsGeneration = SyncRuntime.connectionGeneration();
        if (this.connection != account || SyncRuntime.currentConnection() != account)
            throw new IOException(THE_CONNECTION_HAS_CHANGED_COMPARE_AGAIN_158.text());
        OfficialOperationLease.Binding binding = new OfficialOperationLease.Binding(account, credentialsGeneration,
                account.endpoint, account.username, account.profile,
                SyncRuntime.currentSelectionToken(this), SyncRuntime.currentNamespace(this));
        if (this.connection != account || SyncRuntime.currentConnection() != account
                || credentialsGeneration != SyncRuntime.connectionGeneration())
            throw new IOException(THE_CONNECTION_HAS_CHANGED_COMPARE_AGAIN_158.text());
        return binding;
    }

    /* renamed from: lambda$requestRestore$75$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m143x7dd3dfcf(final AndroidSyncFacade.Inspection inspection, final String str) {
        if (this.busy || this.inspection != inspection || !java.util.Objects.equals(this.selected, str)
                || !homeArchiveAvailability().getRestoreSourceAvailable()) return;
        operation(DiagnosticAction.RESTORE, VALIDATING_BACKING_UP_AND_RESTORING_THE_152.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda91
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m142x859b98e(inspection, str);
            }
        }, GAME_SAVES_RESTORED_AND_VERIFIED_153.text());
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
                visibleNotice(), "", this.historyPage, this.explicitHomeSelection,
                homeStatus(homeContent()), homeAccountExpired() ? SharedHomeStatusKind.EXPIRED : homeStatusKind(homeContent()), actions), new LinearLayout.LayoutParams(-1, -1));
    }

    /* renamed from: lambda$renderConflict$76$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m83x9a039aa7() {
        refreshComparison(SyncPage.HOME);
    }

    /* renamed from: lambda$renderConflict$77$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m84xf7dc0e8() {
        navigate(this.onboardingActive ? SyncPage.CONNECTION : SyncPage.HOME);
    }

    /* renamed from: lambda$renderConflict$80$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m87x12f15700() {
        if (!allowRestoreAction()) return;
        final String str = this.selected;
        if (str == null) {
            return;
        }
        final AndroidSyncFacade.Inspection inspection = this.inspection;
        showDestructiveConfirmation(RESTORE_SAVE_154.text(), SELECTED_SAVE_155.text() + selectedName() + N_NLOCAL_SAVES_WILL_BE_BACKED_159.text(), OVERWRITE_LOCAL_SAVES_157.text(), new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda159
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
        operation(DiagnosticAction.RESOLVE, VERIFYING_AND_PUBLISHING_THE_BRANCH_SELECTION_160.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda102
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m85x84f7e729(inspection, str);
            }
        }, THE_SELECTED_BRANCH_WAS_WRITTEN_TO_161.text());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void previewMerge() {
        if (!allowRestoreAction()) return;
        final AndroidSyncFacade.Inspection inspection = this.inspection;
        if (inspection == null) {
            refreshComparison(SyncPage.HOME);
        } else {
            work(DiagnosticAction.MERGE, CHECKING_THE_COMMON_BASELINE_RUN_IDENTITY_162.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda40
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
            this.notice = A_SAFE_MERGE_IS_NOT_POSSIBLE_163.text();
            this.details = safeText(mergePreview.getMessage());
            return;
        }
        StringBuilder sb = new StringBuilder(THE_MERGE_WILL_USE_164.text(mergePreview.getSlotCount()));
        for (String str : mergePreview.getDecisions()) {
            sb.append("\n• ");
            sb.append(safeText(str));
        }
        sb.append(N_NLOCAL_SAVES_WILL_BE_BACKED_166.text());
        showConfirmation(MERGE_SAVES_167.text(), sb.toString(), MERGE_AND_UPLOAD_168.text(), new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda10
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
        operation(DiagnosticAction.MERGE, APPLYING_AND_VERIFYING_THE_SAFE_MERGE_169.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda118
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m61xf9a4e77a(inspection);
            }
        }, THE_MERGED_VERSION_WAS_WRITTEN_TO_170.text());
    }

    /* JADX INFO: Access modifiers changed from: private */
    static final class OperationOutcome {
        String refreshFailure;
        DiagnosticFailure refreshDiagnosticFailure = DiagnosticFailure.NONE;
        boolean requiresBridgeUpgrade;
        boolean stale;
        OperationReceipt receipt;
        AndroidSyncFacade.OperationResult result;

        private OperationOutcome() {
        }
    }

    private static final class OperationReceiptContext {
        final AndroidSyncFacade facade;
        final SecureConnectionStore.Connection account;
        final OfficialOperationLease.Binding binding;
        OperationReceiptContext(AndroidSyncFacade facade, SecureConnectionStore.Connection account,
                                OfficialOperationLease.Binding binding) {
            this.facade = facade; this.account = account; this.binding = binding;
        }
    }

    private static final class OperationReceipt {
        final OperationReceiptContext context;
        final AndroidSyncFacade.Inspection inspection;
        final SyncHistory history;
        OperationReceipt(OperationReceiptContext context, AndroidSyncFacade.Inspection inspection, SyncHistory history) {
            this.context = context; this.inspection = inspection; this.history = history;
        }
    }

    /** Optional optimization context: its absence must never prevent offline local recovery. */
    private OperationReceiptContext captureOperationReceiptContext() {
        final AndroidSyncFacade owner = this.facade;
        final SecureConnectionStore.Connection account = this.connection;
        if (owner == null || account == null) return null;
        try { return new OperationReceiptContext(owner, account, officialOperationBinding(account)); }
        catch (Exception unavailable) { return null; }
    }

    private boolean currentOperationReceipt(OperationReceiptContext context) {
        if (context == null || isDestroyed() || this.facade != context.facade || this.connection != context.account) return false;
        try { return context.binding.matches(officialOperationBinding(context.account)); }
        catch (Exception unavailable) { return false; }
    }

    private boolean isStaleOperationReceipt(AndroidSyncFacade.OperationResult result, OperationReceiptContext context) {
        return result.getRefreshedInspection() != null && !currentOperationReceipt(context);
    }

    /** Core's verified apply receipt replaces only redundant post-restore export/completion work. */
    private boolean prepareOperationReceipt(OperationOutcome outcome, OperationReceiptContext context) throws Exception {
        final AndroidSyncFacade.Inspection refreshed = outcome.result.getRefreshedInspection();
        if (refreshed == null) return false;
        if (!currentOperationReceipt(context)) { outcome.stale = true; return true; }
        if (outcome.result.isPending() || refreshed.getNeedsRestoreRecovery()
                || hasAnyRestoreJournal()) return false;
        final SyncHistory refreshedHistory = context.facade.historyPresentation(refreshed);
        if (!currentOperationReceipt(context)) { outcome.stale = true; return true; }
        if (!outcome.result.isConflict() && outcome.result.getConfigurationComplete())
            SyncRuntime.setOnboardingProgress(this, context.account, OnboardingProgress.COMPLETE);
        if (!currentOperationReceipt(context)) { outcome.stale = true; return true; }
        outcome.receipt = new OperationReceipt(context, refreshed, refreshedHistory);
        return true;
    }

    /** UI publication is paired and rechecks ownership after the worker has finished. */
    private boolean applyOperationReceipt(OperationOutcome outcome) {
        if (outcome.stale) return false;
        final OperationReceipt receipt = outcome.receipt;
        if (receipt == null) return true;
        if (!currentOperationReceipt(receipt.context)) return false;
        this.inspection = receipt.inspection;
        this.history = receipt.history;
        this.cloudVerified = true;
        this.homeObservationOwner = currentHomeContext(0);
        boolean found = false;
        for (AppendCommit commit : this.history.getCloud())
            if (commit.getSnapshot().getId().equals(this.selected)) found = true;
        if (!found) {
            this.selected = this.history.getDefaultSnapshot();
            this.explicitHomeSelection = null;
        }
        return true;
    }

    private void operation(DiagnosticAction action, String str, final Task<AndroidSyncFacade.OperationResult> task, final String str2) {
        this.confirmedAction = null;
        this.confirmationMerge = null;
        work(action, str, new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda36
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                AndroidSyncFacade owner = MainActivity.this.facade;
                if ((action == DiagnosticAction.RESTORE || action == DiagnosticAction.RESOLVE) && owner != null)
                    return owner.withRestoreReadScope(() -> MainActivity.this.m54xc977a20(task));
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
        final OperationReceiptContext receiptContext = captureOperationReceiptContext();
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
        try {
            if (prepareOperationReceipt(operationOutcome, receiptContext)) return operationOutcome;
            readComparison(true, false);
            if (this.cloudVerified && kind() == OnboardingKind.IDENTICAL && (syncHistory = this.history) != null && syncHistory.getBaselineCommit() != null && !operationOutcome.result.isPending() && !operationOutcome.result.isConflict()) {
                sync().completeOnboarding(this.inspection);
                SyncRuntime.setOnboardingProgress(this, this.connection, OnboardingProgress.COMPLETE);
            }
        } catch (Exception refreshError) {
            if (isStaleOperationReceipt(operationOutcome.result, receiptContext)) {
                operationOutcome.stale = true;
                return operationOutcome;
            }
            // The operation already completed. A separate cloud refresh cannot undo local recovery.
            DiagnosticLog.failure(DiagnosticStage.REFRESH, refreshError);
            this.cloudVerified = false;
            this.inspection = null;
            this.history = null;
            operationOutcome.refreshFailure = safeError(refreshError);
            operationOutcome.refreshDiagnosticFailure = AndroidDiagnosticLogging.classify(refreshError);
        }
        return operationOutcome;
    }

    /* renamed from: lambda$operation$86$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m55x8211a061(String str, OperationOutcome operationOutcome) throws Exception {
        if (!applyOperationReceipt(operationOutcome)) return;
        if (operationOutcome.requiresBridgeUpgrade) {
            this.facade = null;
            this.inspection = null;
            this.history = null;
            this.cloudVerified = false;
            this.readinessState = InstallationReadiness.checking(this.prepared != null, this.installer.hasActiveRequest());
            this.progress = OnboardingProgress.PENDING;
            this.onboardingActive = true;
            this.page = SyncPage.DISCOVERY;
            this.notice = str + LEGACY_RESTORE_RECORDS_HANDLED_PREPARE_THE_171.text();
            this.details = EXISTING_HISTORY_AND_PENDING_UPLOADS_ARE_172.text();
            return;
        }
        OnboardingProgress onboardingProgress = SyncRuntime.onboardingProgress(this, this.connection);
        this.progress = onboardingProgress;
        if (onboardingProgress == OnboardingProgress.COMPLETE) {
            this.onboardingActive = false;
        }
        if (operationOutcome.result.isConflict() || kind() == OnboardingKind.BRANCHED) {
            this.notice = A_SAVE_CONFLICT_WAS_FOUND_BRANCHES_173.text();
            this.page = SyncPage.CONFLICT;
        } else if (operationOutcome.result.isPending()) {
            this.notice = THE_OPERATION_RETAINED_A_PENDING_UPLOAD_174.text();
            this.page = this.onboardingActive ? SyncPage.CONNECTION : SyncPage.HOME;
        } else {
            this.notice = str;
            this.page = this.onboardingActive ? SyncPage.CONNECTION : SyncPage.HOME;
        }
        if (operationOutcome.refreshFailure != null) {
            this.notice += THE_LATEST_STATE_HAS_NOT_BEEN_175.text();
            this.details = operationOutcome.refreshFailure;
        }
    }

    private void showDestructiveConfirmation(String title, String message, String label, Runnable action, Runnable merge) {
        showConfirmation(title, message, label, action, merge, true);
    }

    private void showConfirmation(String title, String message, String label, Runnable action, Runnable merge) {
        showConfirmation(title, message, label, action, merge, false);
    }

    private void showConfirmation(String str, String str2, String str3, Runnable runnable, Runnable runnable2, boolean destructive) {
        this.confirmationDestructive = destructive;
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
                this.notice = SETTINGS_HAVE_CHANGED_SELECT_THE_ACTION_176.text();
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
        String title = this.confirmationTitle == null ? CONFIRM_ACTION_177.text() : this.confirmationTitle;
        if (merge != null) title = HOW_SHOULD_THE_PROGRESS_CONFLICT_BE_178.text();
        SharedConfirmationState state = new SharedConfirmationState(token, title,
                this.confirmationText == null ? THIS_CONFIRMATION_HAS_EXPIRED_PLEASE_SELECT_179.text() : this.confirmationText,
                this.confirmationButton == null ? CONFIRM_AND_CONTINUE_180.text() : this.confirmationButton,
                confirm != null, merge == null ? null : SMART_MERGE_BOTH_SETS_OF_PROGRESS_181.text(), merge != null, this.busy,
                this.feedbackError ? visibleNotice() : "", this.confirmationDestructive);
        // Replace the legacy header/body/footer with the same inactive shared page behind the modal.
        final SharedHomeContent observedContent = homeContent();
        final String backdropStatus = this.cloudVerified
                ? observedContent.getUploadUnnecessary() ? PROGRESS_SYNCED_LOCAL_AND_CLOUD_SAVES_32.text() : CLOUD_CONNECTED_33.text()
                : this.connection == null ? WAITING_FOR_CONNECTION_34.text() : SAVES_NEED_CHECKING_35.text();
        this.root.removeAllViews();
        this.root.setPadding(0, 0, 0, 0);
        this.root.setOnApplyWindowInsetsListener(null);
        this.root.addView(AndroidHomeDialogComposition.confirmationView(this, state,
                this.history, this.historyPage, selectedGameIdentity(), backdropStatus, homeStatusKind(observedContent), this.explicitHomeSelection,
                this.confirmationReturn == SyncPage.SETTINGS ? this.confirmationSettingsBackdrop : null,
                () -> {
                    if (isCurrentConfirmation(token) && this.confirmedAction == confirm && confirm != null) confirm.run();
                }, merge == null ? null : () -> {
                    if (isCurrentConfirmation(token) && this.confirmationMerge == merge) merge.run();
                }, () -> {
                    if (isCurrentConfirmation(token)) cancelConfirmation();
                }), new LinearLayout.LayoutParams(-1, -1));
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
        this.notice = SELECTION_CANCELED_BOTH_SAVES_ARE_UNCHANGED_182.text();
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
        return str2 == null ? NOT_SELECTED_183.text() : str2.substring(0, Math.min(8, str2.length()));
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
        rows.add(new SharedSettingsRow(SharedSettingsKey.GAME, GAME_184.text(), selectedGameLabel()
                + (selectedGame ? "\n" + settingsAccessStatus() : ""), CHANGE_185.text(), capabilities.canChangeGame));
        rows.add(new SharedSettingsRow(SharedSettingsKey.CLOUD_CONNECTION, CLOUD_CONNECTION_186.text(), settingsConnectionValue(),
                EDIT_187.text(), !this.installer.hasActiveRequest()));
        if (isOfficialAccount(this.connection)) {
            rows.add(new SharedSettingsRow(SharedSettingsKey.ACCOUNT_APPRECIATION, BUILT_IN_ACCOUNT_188.text(), this.connection.username,
                    officialAppreciationLabel(this.connection.username), !this.installer.hasActiveRequest()));
        }
        if (selectedGame) rows.add(new SharedSettingsRow(SharedSettingsKey.SAVE_PATH, SAVE_LOCATION_189.text(), savePathLabel(), null, false));
        rows.add(new SharedSettingsRow(SharedSettingsKey.AUTOMATIC_UPLOAD, AUTO_UPLOAD_190.text(),
                !selectedGame ? NO_GAME_SELECTED_9.text() : !automaticSupported ? UNSUPPORTED_BY_THE_CURRENT_ACCESS_METHOD_191.text()
                        : automaticPaused ? PAUSED_192.text() : this.progress == OnboardingProgress.COMPLETE ? ENABLED_193.text() : ENABLED_ACTIVE_AFTER_SETUP_194.text(),
                automaticSupported ? automaticPaused ? ENABLE_195.text() : PAUSE_196.text() : null, capabilities.canToggleAutomatic, automaticSupported && !automaticPaused, false));
        if (selectedGame) rows.add(new SharedSettingsRow(SharedSettingsKey.RECHECK_SAVES, SAVE_CHECK_197.text(),
                restoreNeedsAttention() ? UNFINISHED_RESTORE_198.text() : this.cloudVerified ? CHECKED_199.text() : NEEDS_CHECKING_200.text(), CHECK_AGAIN_201.text(),
                !this.installer.hasActiveRequest()));
        rows.add(new SharedSettingsRow(SharedSettingsKey.REVIEW_SETUP, SETUP_GUIDE_202.text(),
                this.progress == OnboardingProgress.COMPLETE ? COMPLETE_203.text() : INCOMPLETE_204.text(), CHECK_AGAIN_201.text(), capabilities.canChangeGame, null, this.progress == OnboardingProgress.COMPLETE));
        if (Build.VERSION.SDK_INT >= 33) {
            boolean notificationsAllowed = checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
            rows.add(new SharedSettingsRow(SharedSettingsKey.SYNC_NOTIFICATIONS, SYNC_NOTIFICATIONS_205.text(),
                    notificationsAllowed ? ALLOWED_206.text() : NOT_ALLOWED_207.text(), notificationsAllowed ? null : ALLOW_208.text(), !notificationsAllowed));
        }
        rows.add(new SharedSettingsRow(SharedSettingsKey.EXPORT_LOGS, LOGS_209.text(), diagnosticExport.busy() ? EXPORTING_210.text() : EXPORT_TO_SELECTED_FOLDER_211.text(),
                EXPORT_LOGS_212.text(), !diagnosticExport.busy()));
        if (capabilities.hasMaintenance()) {
            rows.add(new SharedSettingsRow(SharedSettingsKey.MAINTENANCE, RECOVERY_AND_MAINTENANCE_213.text(),
                    restoreNeedsAttention() ? UNFINISHED_RESTORE_198.text() : this.inspection != null && this.inspection.isRetryablePending()
                            ? PENDING_UPLOAD_214.text() : CHECK_AS_NEEDED_215.text(), OPEN_216.text(), true));
        }
        this.root = null;
        this.status = null;
        this.body = null;
        this.footer = null;
        this.scroll = null;
        this.renderedPage = this.page;
        SharedSettingsState state = new SharedSettingsState(selectedGameIdentity(), rows, isForegroundSettings(),
                this.busy, true, visibleNotice(), this.feedbackError);
        this.confirmationSettingsBackdrop = state;
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
                    case SYNC_NOTIFICATIONS:
                        if (Build.VERSION.SDK_INT >= 33) m133xa4c8b7f3();
                        break;
                    case EXPORT_LOGS:
                        if (!diagnosticExport.busy()) chooseDiagnosticDirectory();
                        break;
                    case MAINTENANCE:
                        if (current.hasMaintenance()) showSettingsMaintenance(owner);
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
        if (this.connection == null) return this.connectionLoadError.isEmpty() ? NOT_CONNECTED_217.text() : CONNECTION_DETAILS_NEED_CHECKING_218.text();
        return (isOfficialAccount(this.connection) ? BUILT_IN_ACCOUNT_188.text() : "WebDAV")
                + (this.connection.username.isEmpty() ? "" : " · " + this.connection.username);
    }

    /** Concise observed status only. Root selection alone is never displayed as verified access. */
    private String settingsAccessStatus() {
        String mode = this.rootGame != null ? "Root" : this.readinessState.embeddedBridge ? BUILT_IN_SAVE_BRIDGE_219.text() : LEGACY_SAVE_BRIDGE_220.text();
        String state;
        if (this.installer.hasActiveRequest()) state = WAITING_FOR_SYSTEM_CONFIRMATION_221.text();
        else if (restoreNeedsAttention()) state = RECOVERY_CHECK_NEEDED_222.text();
        else if (readyForSaves()) state = SAVES_READABLE_223.text();
        else if (this.readinessState.access == InstallationReadiness.AccessStatus.FIRST_LAUNCH_NEEDED) state = LAUNCH_THE_GAME_FIRST_224.text();
        else if (this.readinessState.access == InstallationReadiness.AccessStatus.GAME_BUSY) state = GAME_RUNNING_225.text();
        else state = NEEDS_CHECKING_200.text();
        return mode + " · " + state;
    }

    /** One entry point; every item invokes the existing operation/confirmation rather than a new flow. */
    private void showSettingsMaintenance(SettingsActionContext owner) {
        if (!isCurrentSettingsAction(owner)) return;
        SettingsCapabilities capabilities = settingsCapabilities();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (capabilities.canRecoverRestore) {
            labels.add(CHECK_UNFINISHED_RESTORE_226.text());
            actions.add(() -> { if (settingsCapabilities().canRecoverRestore) m135x8fbd0475(); });
        }
        if (capabilities.canDiscardRestore) {
            labels.add(KEEP_BACKUP_AND_ABANDON_UNFINISHED_OPERATION_227.text());
            actions.add(() -> { if (settingsCapabilities().canDiscardRestore) m112xc8b22aac(); });
        }
        if (capabilities.canRecoverBackup) {
            labels.add(this.rootGame != null ? CHECK_INTERRUPTED_ROOT_TRANSACTION_228.text() : RESTORE_PRE_INTERRUPTION_BACKUP_229.text());
            actions.add(() -> { if (settingsCapabilities().canRecoverBackup) m116x9e9ac3b0(); });
        }
        if (capabilities.canArchiveUpload) {
            labels.add(CHECK_AND_ARCHIVE_INTERRUPTED_PENDING_UPLOAD_230.text());
            actions.add(() -> { if (settingsCapabilities().canArchiveUpload) m120x74835cb4(); });
        }
        if (capabilities.canForgetConnection) {
            labels.add(FORGET_SAVED_CONNECTION_DETAILS_231.text());
            actions.add(() -> { if (settingsCapabilities().canForgetConnection) m124xed71190d(); });
        }
        if (labels.isEmpty()) return;
        showSettingsDialog(new AlertDialog.Builder(this).setTitle(RECOVERY_AND_MAINTENANCE_213.text())
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    if (this.settingsDialog != dialog || !isCurrentSettingsContext(owner)) return;
                    this.settingsDialog = null;
                    actions.get(index).run();
                }).setNegativeButton(CANCEL_232.text(), null).create());
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
        work(DiagnosticAction.CONFIGURE, SAVING_AUTO_UPLOAD_SETTINGS_233.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda38
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
        this.notice = z ? AUTO_UPLOAD_ENABLED_IT_TAKES_EFFECT_234.text() : AUTO_UPLOAD_PAUSED_235.text();
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
            this.notice = SYNC_NOTIFICATION_PERMISSION_GRANTED_236.text();
            render();
        }
    }

    /* renamed from: lambda$renderSettings$96$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m134x1a42de34() throws Exception {
        requireRestoreSupport();
        return localSync().recoverRestore();
    }

    /* renamed from: lambda$renderSettings$97$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m135x8fbd0475() {
        operation(DiagnosticAction.RESTORE, CHECKING_THE_UNFINISHED_RESTORE_237.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda107
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m134x1a42de34();
            }
        }, RESTORE_STATE_VERIFIED_238.text());
    }

    /* renamed from: lambda$renderSettings$100$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m112xc8b22aac() {
        showConfirmation(END_UNFINISHED_RESTORE_239.text(), ARCHIVE_THE_PENDING_RESTORE_CONTENT_AND_240.text(), ARCHIVE_AND_CHECK_AGAIN_241.text(), new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda35
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m137x7ab150f7();
            }
        }, null);
    }

    /* renamed from: lambda$renderSettings$98$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m136x5372ab6() throws Exception {
        return localSync().discardInterruptedRestore();
    }

    /* renamed from: lambda$renderSettings$99$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m137x7ab150f7() {
        operation(DiagnosticAction.RESTORE, ARCHIVING_UNFINISHED_RESTORE_242.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda2
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m136x5372ab6();
            }
        }, UNFINISHED_RESTORE_ARCHIVED_GAME_FILES_WERE_243.text());
    }

    /* renamed from: lambda$renderSettings$104$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m116x9e9ac3b0() {
        if (!backupRecoverySupported()) { this.notice = THE_CURRENT_ACCESS_METHOD_DOES_NOT_244.text(); render(); return; }
        showDestructiveConfirmation(this.rootGame != null ? CHECK_INTERRUPTED_ROOT_TRANSACTION_228.text() : RESTORE_ORIGINAL_SAVES_FROM_BEFORE_INTERRUPTION_245.text(),
                this.rootGame != null ? CHECK_THE_SELECTED_GAME_S_RETAINED_246.text()
                : USE_ONLY_WHEN_THE_SAVE_BRIDGE_247.text(),
                this.rootGame != null ? CHECK_AND_RECOVER_248.text() : RESTORE_ORIGINAL_BACKUP_249.text(), new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda16
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m115x29209d6f();
            }
        }, null);
    }

    /* renamed from: lambda$renderSettings$103$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m115x29209d6f() {
        work(DiagnosticAction.RECOVERY, this.rootGame != null ? CHECKING_INTERRUPTED_ROOT_TRANSACTION_250.text() : RESTORING_THE_SAVE_BRIDGE_S_PRE_251.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda27
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
        if (!backend.capabilities().interruptedBackupRecovery) throw new IOException(THE_CURRENT_ACCESS_METHOD_DOES_NOT_252.text());
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
        this.notice = (this.rootGame != null ? ROOT_TRANSACTION_CHECK_COMPLETE_253.text() : SAVE_BRIDGE_BACKUP_PROCESSING_COMPLETE_254.text()) + this.readinessState.statusDescription();
        if (this.diagnosticReport.isEmpty()) {
            this.details = safeText(str);
        }
    }

    /* renamed from: lambda$renderSettings$108$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m120x74835cb4() {
        showConfirmation(END_PENDING_UPLOAD_255.text(), ONLY_THE_PENDING_UPLOAD_MARKER_IS_256.text(), CONFIRM_AND_CONTINUE_180.text(), new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda152
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m119xff093673();
            }
        }, null);
    }

    /* renamed from: lambda$renderSettings$105$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ Object m117x1414e9f1() throws Exception {
        localSync().archivePending();
        return null;
    }

    /* renamed from: lambda$renderSettings$106$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m118x898f1032(Object obj) throws Exception {
        this.inspection = null;
        this.history = null;
        this.page = SyncPage.HOME;
        this.notice = PENDING_UPLOAD_ARCHIVED_PLEASE_CHECK_AGAIN_257.text();
    }

    /* renamed from: lambda$renderSettings$107$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m119xff093673() {
        work(DiagnosticAction.ARCHIVE, ARCHIVING_PENDING_UPLOAD_258.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda148
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
        showConfirmation(FORGET_CONNECTION_DETAILS_259.text(), DELETE_THIS_APP_S_SAVED_CONNECTION_260.text(), FORGET_261.text(), new Runnable() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda126
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
        work(DiagnosticAction.CONFIGURE, DELETING_SAVED_CONNECTION_DETAILS_262.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda150
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
        this.notice = SAVED_CONNECTION_DETAILS_FORGOTTEN_263.text();
    }

    /* renamed from: lambda$renderSettings$113$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m125x62eb3f4e() {
        navigate(SyncPage.HOME);
    }

    private void beginOnboarding(final SyncPage syncPage) {
        work(DiagnosticAction.INSPECT, CHECKING_CONFIGURATION_AGAIN_264.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda103
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
        this.notice = CHECK_THE_SELECTED_GAME_265.text();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void rename(final String snapshotId) {
        if (this.homeNaming == null) return;
        if (snapshotId == null) {
            renameCurrentInline();
            return;
        }
        final HomeActionContext owner = currentHomeContext(this.sharedHomeRenderToken);
        final AndroidSyncFacade.Inspection observed = this.inspection;
        SyncHistory observedHistory = this.history;
        String alias = observedHistory == null ? null : observedHistory.getAliases().get(snapshotId);
        this.homeNaming.open(snapshotId, alias == null ? "" : alias,
                SAVED_ON_THIS_DEVICE_PUBLISHED_HISTORY_266.text(), name -> {
                    if (!isCurrentHomeAction(owner)) {
                        this.notice = SAVES_HAVE_CHANGED_PLEASE_RENAME_AGAIN_267.text();
                        render();
                        return;
                    }
                    work(DiagnosticAction.CONFIGURE, SAVING_NAME_268.text(), () -> {
                        localSync().renameHistory(observed, snapshotId, name);
                        // An explicit update refreshes the full observation, never only its mirror.
                        readComparison();
                        return null;
                    }, result -> this.notice = NAME_SAVED_269.text());
                }, null);
    }

    /** The inline draft retains the actual inspection; saving still goes through facade checks. */
    private void renameCurrentInline() {
        if (this.inspection == null || !this.cloudVerified) {
            refreshComparison(SyncPage.HOME);
            return;
        }
        final HomeActionContext owner = currentHomeContext(this.sharedHomeRenderToken);
        final AndroidSyncFacade.Inspection observed = this.inspection;
        final String initialName = this.history == null ? "" : this.history.getCurrentName();
        this.homeNaming.open(null, initialName, "", name -> {
            if (!isCurrentHomeAction(owner)) {
                this.notice = SAVES_HAVE_CHANGED_PLEASE_RENAME_AGAIN_267.text();
                render();
                return;
            }
            work(DiagnosticAction.CONFIGURE, SAVING_NAME_268.text(), () -> {
                localSync().renameCurrent(observed, name);
                readComparison();
                return null;
            }, result -> this.notice = NAME_SAVED_269.text());
        }, () -> {
            if (isCurrentHomeAction(owner)) chooseRunIdentity();
        });
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
                this.notice = SAVES_HAVE_CHANGED_PLEASE_RENAME_AGAIN_267.text();
                render();
                return;
            }
            work(DiagnosticAction.CONFIGURE, SAVING_NAME_268.text(), () -> {
                localSync().renameCurrent(observed, name);
                readComparison();
                return null;
            }, result -> this.notice = NAME_SAVED_269.text());
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
            new AlertDialog.Builder(this).setTitle(SELECT_A_SLOT_WHOSE_RUN_IDENTITY_270.text()).setItems(strArr, new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda30
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int i) {
                    MainActivity.this.m28x9a90bca9(strArr, dialogInterface, i);
                }
            }).setNegativeButton(CANCEL_232.text(), (DialogInterface.OnClickListener) null).show();
        } else {
            this.notice = NO_GAME_SLOTS_CURRENTLY_NEED_IDENTITY_271.text();
            render();
        }
    }

    /* renamed from: lambda$chooseRunIdentity$131$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m28x9a90bca9(String[] strArr, DialogInterface dialogInterface, int i) {
        final String str = strArr[i];
        final AndroidSyncFacade.Inspection inspection = this.inspection;
        new AlertDialog.Builder(this).setTitle(CONFIRM_RUN_IDENTITY_FOR_272.text(str)).setMessage(ONLY_CHOOSE_CONTINUE_THE_SAME_RUN_274.text()).setNegativeButton(CANCEL_232.text(), (DialogInterface.OnClickListener) null).setNeutralButton(THIS_IS_A_NEW_RUN_275.text(), new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda25
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface2, int i2) {
                MainActivity.this.m25x971d2691(inspection, str, dialogInterface2, i2);
            }
        }).setPositiveButton(CONTINUE_THE_SAME_RUN_276.text(), new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda26
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface2, int i2) {
                MainActivity.this.m27x25169668(inspection, str, dialogInterface2, i2);
            }
        }).show();
    }

    /* renamed from: lambda$chooseRunIdentity$127$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m24x21a30050(AndroidSyncFacade.Inspection inspection, String str) throws Exception {
        return localSync().confirmRunIdentity(inspection, str, false);
    }

    /* renamed from: lambda$chooseRunIdentity$128$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m25x971d2691(final AndroidSyncFacade.Inspection inspection, final String str, DialogInterface dialogInterface, int i) {
        operation(DiagnosticAction.RESOLVE, RECORDING_NEW_RUN_IDENTITY_277.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda31
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m24x21a30050(inspection, str);
            }
        }, NEW_RUN_IDENTITY_RECORDED_278.text());
    }

    /* renamed from: lambda$chooseRunIdentity$129$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ AndroidSyncFacade.OperationResult m26xc974cd2(AndroidSyncFacade.Inspection inspection, String str) throws Exception {
        return localSync().confirmRunIdentity(inspection, str, true);
    }

    /* renamed from: lambda$chooseRunIdentity$130$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m27x25169668(final AndroidSyncFacade.Inspection inspection, final String str, DialogInterface dialogInterface, int i) {
        operation(DiagnosticAction.RESOLVE, RECORDING_RUN_IDENTITY_279.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda161
            @Override // io.github.xyz1001.pdsync.android.MainActivity.Task
            public final Object run() throws Exception {
                return MainActivity.this.m26xc974cd2(inspection, str);
            }
        }, IDENTITY_DECISION_RECORDED_280.text());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void activateHelper() { maybeActivateSetupComponent(); }

    private void readAfterHelperPreparation() {
        this.page = SyncPage.DISCOVERY;
        refreshReadiness();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void launchGameForInitialization() {
        if (this.rootGame != null) { this.notice = ROOT_MODE_DOES_NOT_PREPARE_OR_281.text(); render(); return; }
        if (this.readinessState.withSystemRequest(this.installer.hasActiveRequest()).canInitializeGame()) {
            this.readinessState = InstallationReadiness.checking(true, this.installer.hasActiveRequest());
            work(DiagnosticAction.LAUNCH, CHECKING_THE_GAME_AND_BUILT_IN_282.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda97
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
        if (SyncRuntime.rootMode(this)) throw new IOException(ROOT_MODE_DOES_NOT_PREPARE_OR_85.text());
        this.installer.reconcile();
        if (this.installer.hasActiveRequest()) {
            throw new IOException(FINISH_OR_CANCEL_THE_CURRENT_SYSTEM_283.text());
        }
        GamePreparationService.PreparedPair preparedPairFreshPair = this.installer.freshPair();
        if (this.installer.nextPackage(preparedPairFreshPair) != null) {
            throw new IOException(THE_INSTALLED_APP_HAS_CHANGED_CHECK_284.text());
        }
        if (hasAnyRestoreJournal()) {
            throw new IOException(AN_UNFINISHED_RESTORE_EXISTS_CHECK_RESTORE_285.text());
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
            throw new IOException(THE_GAME_LAUNCH_ACTIVITY_IS_UNAVAILABLE_286.text());
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
        this.notice = GAME_LAUNCH_REQUESTED_EXIT_NORMALLY_RETURN_287.text();
        startActivity(intent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void launchGame() {
        final boolean zAllowsAutomatic = OnboardingGate.allowsAutomatic(this.progress, this.onboardingActive,
                this.cloudVerified && this.inspection != null && this.inspection.isObjectVerified());
        work(DiagnosticAction.LAUNCH, CHECKING_THE_SELECTED_GAME_288.text(), new Task() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda132
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
            throw new IOException(AN_UNFINISHED_RESTORE_EXISTS_CHECK_RESTORE_285.text());
        }
        if (selectedGamePackage() == null) {
            throw new IOException(SELECT_A_GAME_FIRST_289.text());
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
                        verifyLaunchInspection();
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
        throw new IOException(THE_GAME_LAUNCH_ACTIVITY_IS_UNAVAILABLE_290.text());
    }

    private void verifyLaunchInspection() throws Exception {
        if (!this.cloudVerified || this.inspection == null || this.inspection.isObjectVerified()) return;
        final AndroidSyncFacade.Inspection observed = this.inspection;
        final SecureConnectionStore.Connection account = this.connection;
        final HomeActionContext context = currentHomeContext(this.sharedHomeRenderToken);
        final AndroidSyncFacade owner = sync();
        AndroidSyncFacade.Inspection verified = owner.verifyHomeInspection(observed);
        SyncHistory verifiedHistory = owner.historyPresentation(verified);
        if (context == null || !context.accepts(currentHomeContext(this.sharedHomeRenderToken), isForegroundHome(), false)
                || this.facade != owner || account != SyncRuntime.currentConnection()) throw new StaleArchiveReadException();
        this.inspection = verified;
        this.history = verifiedHistory;
        this.homeObservationOwner = currentHomeContext(0);
    }

    /* renamed from: lambda$launchGame$139$io-github-xyz1001-pdsync-android-MainActivity, reason: not valid java name */
    /* synthetic */ void m49x6e86027e(boolean z, Intent intent) throws Exception {
        boolean allowsAutomatic = OnboardingGate.allowsAutomatic(this.progress, this.onboardingActive,
                this.cloudVerified && this.inspection != null && this.inspection.isObjectVerified());
        this.inspection = null;
        this.cloudVerified = false;
        if (!automaticObservationSupported() || this.connection == null || !allowsAutomatic || SyncRuntime.isAutoUploadPaused(this)) {
            cancelControlledLaunch();
            this.notice = GAME_LAUNCH_REQUESTED_EXIT_MONITORING_IS_291.text();
        } else {
            SyncRuntime.setConnection(this.connection);
            try {
                AutoSyncService.arm(this, this.controlledLaunchToken);
                this.notice = CONFLICT_FREE_AUTO_UPLOAD_AFTER_EXIT_292.text();
            } catch (RuntimeException unused) {
                cancelControlledLaunch();
                this.notice = ANDROID_DID_NOT_ALLOW_BACKGROUND_MONITORING_293.text();
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
        new AlertDialog.Builder(this).setTitle(str).setMessage(str2).setNegativeButton(CANCEL_232.text(), (DialogInterface.OnClickListener) null).setPositiveButton(str3, new DialogInterface.OnClickListener() { // from class: io.github.xyz1001.pdsync.android.MainActivity$$ExternalSyntheticLambda96
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

    /** The existing export action is rendered in the wizard's shared step header. */
    public SharedDiagnosticAction wizardDiagnosticAction() {
        wizardDiagnosticExport.setEnabled(!diagnosticExport.busy());
        return wizardDiagnosticExport;
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
        wizardDiagnosticExport.setEnabled(!diagnosticExport.busy());
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
        if (i == ACTIVATE_HELPER) {
            if (this.setupSession.flow.consumePending(GameSetupFlow.Pending.ACTIVATION)) {
                this.activationReturnPending = true;
                this.notice = CONNECTING_GAME_COMPONENTS_84.text();
                drainPendingSetup();
            }
            return;
        }
        if (i == ALLOW_INSTALL) {
            if (this.setupSession.flow.consumePending(GameSetupFlow.Pending.UNKNOWN_SOURCES)) {
                if (getPackageManager().canRequestPackageInstalls()) {
                    this.setupSession.installationAdvancePending = true; drainPendingSetup();
                } else { this.setupOutcome = APP_INSTALLATION_WAS_NOT_ALLOWED_THIS_294.text(); this.notice = this.setupOutcome; render(); }
            }
            return;
        }
        if (i == EXTERNAL_INSTALL || (i >= 16000 && i <= 65534)) {
            android.util.Log.i("PDSyncInstall", "externalActivityResult code=" + i2 + " state=" + this.installer.state());
            // Finishing the external Activity ends our UI wait, not necessarily its background work.
            externalInstallerReturned(i);
            return;
        }
        if (i == SYSTEM_INSTALL) {
            AndroidDiagnosticLogging.confirmationEvent("CONFIRMATION_RETURN", DiagnosticResult.PENDING);
            if (this.setupSession.flow.consumePending(GameSetupFlow.Pending.SYSTEM_CONFIRMATION)) {
                this.systemReturnPending = true; this.pendingSystemChecks = 0; drainPendingSetup();
            }
            return;
        }
        if (i == PICK_APK) this.gameFilePickerOpen = false;
        if (i == PICK_APK && (i2 != RESULT_OK || intent == null)) {
            render(); // Cancel keeps the current prepared game and Root selection unchanged.
            return;
        }
        if (i == 8101 && i2 == -1 && intent != null) {
            LinkedHashSet<Uri> linkedHashSet = new LinkedHashSet<>();
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
            offerGameModeChoice(GameModeChoice.Source.files(new ArrayList<>(linkedHashSet)));
            return;
        }
        if (i != 8102 || this.busy) {
            return;
        }
        this.systemReturnPending = true; this.pendingSystemChecks = 0; drainPendingSetup();
    }

    private String safeText(String str) {
        if (str == null) {
            return CHECK_AGAIN_BEFORE_RETRYING_295.text();
        }
        SecureConnectionStore.Connection connection = this.connection;
        if (connection != null && !connection.password.isEmpty()) {
            str = str.replace(this.connection.password, HIDDEN_296.text());
        }
        if (this.sharedConnectionSession != null) {
            String editingPassword = this.sharedConnectionSession.getController().getState().getPassword();
            if (!editingPassword.isEmpty()) str = str.replace(editingPassword, HIDDEN_296.text());
        }
        String strReplaceAll = str.replaceAll("(?i)(authorization|password|passwd|token)\\s*[:=]\\s*\\S+", HIDDEN_297.text());
        if (strReplaceAll.length() <= 1500) {
            return strReplaceAll;
        }
        return strReplaceAll.substring(0, 1500) + "…";
    }

    private String safeError(Exception exc) {
        // Inspection adds diagnostic context; keep the existing sanitized account state visible.
        Throwable accountFailure = exc;
        for (int depth = 0; depth < 8 && accountFailure != null; depth++, accountFailure = accountFailure.getCause()) {
            if (accountFailure instanceof OfficialSyncAccess.AccessException) return accountFailure.getMessage();
            if (accountFailure instanceof ManagedAccountClient.ManagedAccountException)
                return ((ManagedAccountClient.ManagedAccountException) accountFailure).userMessage();
        }
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
        if (exc.getMessage() != null && exc.getMessage().startsWith("ROOT_"))
            return ROOT_ACCESS_WAS_NOT_GRANTED_RETRY_15.text();
        String strSafeText = safeText(exc.getMessage());
        String lowerCase = strSafeText.toLowerCase(Locale.ROOT);
        if (lowerCase.contains("ssl") || lowerCase.contains("certificate path") || lowerCase.contains("handshake")) {
            return HTTPS_CERTIFICATE_OR_SECURE_CONNECTION_VERIFICATION_298.text();
        }
        if (lowerCase.contains("split") || lowerCase.contains("分包")) {
            return SPLIT_APK_VALIDATION_FAILED_SELECT_THE_300.text();
        }
        if (lowerCase.contains("signature") || lowerCase.contains("signer") || lowerCase.contains("certificate")) {
            return SIGNATURE_VERIFICATION_FAILED_INSTALLATION_STOPPED_CHECK_301.text();
        }
        if (lowerCase.contains("unsupported") || lowerCase.contains("not supported") || lowerCase.contains("unrecognized")) {
            return THE_SELECTED_GAME_S_SAVE_FORMAT_302.text();
        }
        if (lowerCase.contains("restore") && (lowerCase.contains("recover") || lowerCase.contains("interrupted"))) {
            return AN_UNFINISHED_RESTORE_EXISTS_CHECK_RESTORE_303.text();
        }
        if (lowerCase.contains("changed") || lowerCase.contains("compare again")) {
            return LOCAL_OR_CLOUD_STATE_HAS_CHANGED_304.text();
        }
        if (lowerCase.contains("401") || lowerCase.contains("403") || lowerCase.contains("unauthorized")) {
            return isOfficialAccount(this.connection) ? COULD_NOT_SIGN_IN_CHECK_YOUR_305.text()
                    : CONNECTION_FAILED_CHECK_YOUR_USERNAME_PASSWORD_306.text();
        }
        return NoviceCopy.feedback(strSafeText, THE_OPERATION_DID_NOT_FINISH_EXPORT_307.text());
    }
}
