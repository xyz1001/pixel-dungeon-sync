package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.xyz1001.pdsync.android.SecureConnectionStore;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import pdsync.OnboardingGate;
import pdsync.OnboardingProgress;
import pdsync.android.app.AndroidConnection;
import pdsync.android.app.AndroidGameIdentity;
import pdsync.android.app.AndroidSyncFacade;

/* loaded from: classes.dex */
final class SyncRuntime {
    static final Object OPERATION_LOCK = new Object();
    private static final String PREFS = "sync-presentation-v2";
    private static volatile SecureConnectionStore.Connection connection;
    private static final java.util.concurrent.atomic.AtomicLong connectionGeneration = new java.util.concurrent.atomic.AtomicLong();
    private static volatile GamePreparationService.PreparedPair selected;
    // SharedPreferences may expose new memory values even when its disk commit fails.
    private static final java.util.Set<String> failedOnboardingWrites = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static RootGameBridge rootBackend;
    private static String rootBackendToken;

    SyncRuntime() {
    }

    /** In-memory identity only; never loads credentials or requests game access. */
    static SecureConnectionStore.Connection currentConnection() { return connection; }

    static void setConnection(SecureConnectionStore.Connection connection2) {
        if (connection != connection2) connectionGeneration.incrementAndGet();
        connection = connection2;
    }

    /** Includes target changes; an old response must not authorize a replacement configuration. */
    static long connectionGeneration() { return connectionGeneration.get(); }

    static SecureConnectionStore.Connection connection(Context context) throws Exception {
        SecureConnectionStore.Connection connection2 = connection;
        if (connection2 != null) {
            return connection2;
        }
        SecureConnectionStore.Connection connectionLoad = new SecureConnectionStore(context).load();
        setConnection(connectionLoad);
        return connectionLoad;
    }

    static synchronized void setPreparedGame(Context context, GamePreparationService.PreparedPair preparedPair) {
        GamePreparationService.PreparedPair preparedPair2 = selected;
        if (preparedPair2 != preparedPair && (preparedPair2 == null || preparedPair == null
                || !preparedPair2.gamePackage.equals(preparedPair.gamePackage)
                || !selectionToken(preparedPair2).equals(selectionToken(preparedPair))))
            connectionGeneration.incrementAndGet();
        if (preparedPair2 != null && (preparedPair == null || !selectionToken(preparedPair2).equals(selectionToken(preparedPair)))) {
            AutoSyncService.cancelForConnectionChange(context);
        }
        if (preparedPair != null) {
            try {
                new InstalledBridgeSelectionStore(context.getFilesDir()).clear();
                new RootSelectionStore(context.getFilesDir()).clear();
            } catch (IOException failure) { throw new IllegalStateException(AndroidMessages.SYNC_RUNTIME_01.text(), failure); }
        }
        selected = preparedPair;
    }

    /** Restored selection is metadata, never proof that a provider handshake succeeded. */
    static InstalledBridgeBinding installedBridge(Context context) throws Exception {
        InstalledBridgeBinding binding = new InstalledBridgeSelectionStore(context.getFilesDir()).load();
        return binding == null ? null : InstalledBridgeResolver.requireUnchanged(context, binding);
    }

    static void setInstalledBridge(Context context, InstalledBridgeBinding binding) throws Exception {
        synchronized (OPERATION_LOCK) {
            InstalledBridgeResolver.requireUnchanged(context, binding);
            InstalledBridgeBinding previous = new InstalledBridgeSelectionStore(context.getFilesDir()).load();
            if (binding.matches(previous) && rootGame(context) == null) return;
            requireBackendSwitchAllowed(context);
            requireFreshBackendComparison(context);
            connectionGeneration.incrementAndGet();
            AutoSyncService.cancelForConnectionChange(context);
            closeRootBackend();
            new InstalledBridgeSelectionStore(context.getFilesDir()).save(binding);
            new RootSelectionStore(context.getFilesDir()).clear();
            selected = null;
        }
    }

    static AndroidGameIdentity gameIdentity(Context context, InstalledBridgeBinding binding) {
        return AndroidGameIdentity.forPackage(binding.gamePackage, binding.gameLabel, binding.originIdentity);
    }

    static synchronized GamePreparationService.PreparedPair requirePreparedGame(Context context) throws Exception {
        if (selected == null) {
            selected = new GamePreparationService(context).reloadPrepared();
        }
        if (selected == null) {
            throw new IOException(AndroidMessages.SYNC_RUNTIME_02.text());
        }
        return selected;
    }

    /** Local selection only. Loading/rendering this never launches a privileged process. */
    static SelectedRootGame rootGame(Context context) throws Exception {
        return new RootSelectionStore(context.getFilesDir()).load();
    }

    static boolean rootMode(Context context) throws Exception { return rootGame(context) != null; }

    static void setRootGame(Context context, SelectedRootGame game) throws Exception {
        synchronized (OPERATION_LOCK) {
            requireBackendSwitchAllowed(context);
            requireFreshBackendComparison(context);
            new RootTargetResolver(context).requireUnchanged(game);
            connectionGeneration.incrementAndGet();
            AutoSyncService.cancelForConnectionChange(context);
            closeRootBackend();
            new RootSelectionStore(context.getFilesDir()).save(game);
            new InstalledBridgeSelectionStore(context.getFilesDir()).clear();
            selected = null;
        }
    }

    static void useEmbeddedBackend(Context context) throws Exception {
        synchronized (OPERATION_LOCK) {
            requireBackendSwitchAllowed(context);
            requireFreshBackendComparison(context);
            connectionGeneration.incrementAndGet();
            AutoSyncService.cancelForConnectionChange(context);
            closeRootBackend();
            new RootSelectionStore(context.getFilesDir()).clear();
        }
    }

    private static void requireFreshBackendComparison(Context context) throws IOException {
        if (!prefs(context).edit().putBoolean("backendComparisonRequired", true).commit())
            throw new IOException(AndroidMessages.SYNC_RUNTIME_03.text());
    }

    private static void requireBackendSwitchAllowed(Context context) throws Exception {
        if (new InstallCoordinator(context).hasActiveRequest())
            throw new IOException(AndroidMessages.SYNC_RUNTIME_04.text());
        if (AndroidSyncFacade.hasUnresolvedRestore(new File(context.getFilesDir().getCanonicalFile(), "sync").toPath()))
            throw new IOException(AndroidMessages.SYNC_RUNTIME_05.text());
    }

    /** Explicit persisted Root selection opts in to deliberate operations; the su manager grants access. */
    static boolean rootAuthorized(Context context) {
        try { return rootGame(context) != null; }
        catch (Exception invalid) { return false; }
    }

    /** Metadata-only cancellation gate. It never probes root or reads target save data. */
    static boolean rootSelectionMatches(Context context, SelectedRootGame expected) {
        try {
            SelectedRootGame current = rootGame(context);
            return expected != null && expected.matches(current)
                    && expected.selectionToken().equals(current.selectionToken());
        } catch (Exception invalid) { return false; }
    }

    /** Must be reached only from an explicit user authorization action. */
    static String probeRootAuthorization(Context context) throws Exception {
        synchronized (OPERATION_LOCK) {
            SelectedRootGame game = rootGame(context);
            if (game == null) throw new IOException(AndroidMessages.SYNC_RUNTIME_06.text());
            // Recovery needs root on this exact existing selection even when a restore is pending.
            if (new InstallCoordinator(context).hasActiveRequest())
                throw new IOException(AndroidMessages.SYNC_RUNTIME_07.text());
            String result;
            try (RootGameBridge probe = new RootGameBridge(context, game, rootIdentity(context, game))) {
                result = probe.probeAuthorization();
            }
            SelectedRootGame current = rootGame(context);
            if (current == null || !current.matches(game)) throw new IOException(AndroidMessages.SYNC_RUNTIME_08.text());
            return result;
        }
    }

    static synchronized GameAccessBackend backend(Context context) throws Exception {
        SelectedRootGame root = rootGame(context);
        if (root != null) {
            String token = root.selectionToken() + ":" + currentNamespace(context);
            if (rootBackend == null || !token.equals(rootBackendToken)) {
                closeRootBackend();
                rootBackend = (RootGameBridge) GameAccessBackends.forRootGame(context, root, rootIdentity(context, root));
                rootBackendToken = token;
            }
            return rootBackend;
        }
        closeRootBackend();
        InstalledBridgeBinding installed = installedBridge(context);
        if (installed != null) return GameAccessBackends.forInstalledBridge(context, installed, gameIdentity(context, installed));
        GamePreparationService.PreparedPair prepared = requirePreparedGame(context);
        return GameAccessBackends.forPreparedGame(context, prepared, gameIdentity(context, prepared));
    }

    private static synchronized void closeRootBackend() {
        if (rootBackend != null) rootBackend.close();
        rootBackend = null; rootBackendToken = null;
    }

    static String currentSelectionToken(Context context) throws Exception {
        SelectedRootGame root = rootGame(context);
        if (root != null) return root.selectionToken();
        InstalledBridgeBinding installed = installedBridge(context);
        return installed == null ? selectionToken(requirePreparedGame(context)) : installed.selectionToken();
    }

    static String currentGamePackage(Context context) throws Exception {
        SelectedRootGame root = rootGame(context);
        if (root != null) return root.gamePackage;
        InstalledBridgeBinding installed = installedBridge(context);
        return installed == null ? requirePreparedGame(context).gamePackage : installed.gamePackage;
    }

    static AndroidGameIdentity currentGameIdentity(Context context) throws Exception {
        SelectedRootGame root = rootGame(context);
        if (root != null) return rootIdentity(context, root);
        InstalledBridgeBinding installed = installedBridge(context);
        return installed == null ? gameIdentity(context, requirePreparedGame(context)) : gameIdentity(context, installed);
    }

    private static String currentOrigin(Context context) throws Exception {
        SelectedRootGame root = rootGame(context);
        if (root != null) return root.originIdentity;
        InstalledBridgeBinding installed = installedBridge(context);
        return installed == null ? requirePreparedGame(context).originIdentity : installed.originIdentity;
    }

    private static AndroidGameIdentity rootIdentity(Context context, SelectedRootGame root) {
        return AndroidGameIdentity.forPackage(root.gamePackage, root.gameLabel, root.originIdentity);
    }

    static String selectionToken(GamePreparationService.PreparedPair preparedPair) {
        return preparedPair.originIdentity + ":" + preparedPair.sourceApkSetSha256 + ":" + (preparedPair.embeddedBridge() ? preparedPair.bridgeMode + ":" + preparedPair.runtimeDexSha256 : preparedPair.sharedUid);
    }

    /** Canonical package token for armed listeners, status, and stale-action guards. */
    static String currentNamespace(Context context) throws Exception {
        return currentGameIdentity(context).getNamespace();
    }

    static String currentCloudNamespace(Context context) throws Exception {
        return currentGameIdentity(context).getNamespace();
    }

    static AndroidGameIdentity gameIdentity(Context context, GamePreparationService.PreparedPair preparedPair) {
        return AndroidGameIdentity.forPackage(preparedPair.gamePackage, preparedPair.gameLabel, preparedPair.originIdentity);
    }

    // Facades can outlive a service deadline. Their request hook reads the latest operation lease.
    private static final java.util.Map<AndroidSyncFacade, OfficialOperationGate> officialGates =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static final class OfficialOperationGate implements pdsync.WebDavRequestCheck {
        final SecureConnectionStore.Connection account;
        volatile OfficialSyncAccess.Lease lease;
        private long authorizedGeneration = -1;
        OfficialOperationGate(SecureConnectionStore.Connection account) { this.account = account; }
        void refresh(Context context) throws Exception {
            lease = null; // Failed refresh must never retain a previously active authorization.
            final long generation = connectionGeneration();
            int boot = -1;
            try { boot = android.provider.Settings.Global.getInt(context.getContentResolver(), "boot_count", -1); }
            catch (RuntimeException unavailable) { /* Missing boot identity requires an online refresh. */ }
            TrustedEntitlementCache.Record cached = new TrustedEntitlementCache(
                    new File(context.getFilesDir(), "managed-account-expiry-v1").toPath()).read(account.endpoint, account.username);
            if (TrustedEntitlementCache.currentAccessAvailable(cached, account.endpoint, account.username,
                    android.os.SystemClock.elapsedRealtime(), boot)) {
                OfficialSyncAccess.Lease candidate = OfficialSyncAccess.fromCached(cached, account.endpoint,
                        account.username, boot, android.os.SystemClock::elapsedRealtimeNanos);
                if (generation != connectionGeneration()) throw new IOException(AndroidMessages.SYNC_RUNTIME_09.text());
                authorizedGeneration = generation;
                lease = candidate;
                check();
                return;
            }
            TrustedEntitlementCache.beginRefresh(account.endpoint, account.username);
            long requestStartedNanos = android.os.SystemClock.elapsedRealtimeNanos();
            ManagedAccountClient.Entitlement entitlement = new ManagedAccountClient(
                    OfficialServiceRegistrationClient.DAV_URL, account.username, account.password).entitlement();
            if (generation != connectionGeneration()) throw new IOException(AndroidMessages.SYNC_RUNTIME_09.text());
            rememberEntitlement(context, account, entitlement);
            entitlement.requireSyncAccess();
            TrustedEntitlementCache.confirmedRefresh(account.endpoint, account.username);
            authorizedGeneration = generation;
            lease = OfficialSyncAccess.fromEntitlement(entitlement, requestStartedNanos,
                    android.os.SystemClock::elapsedRealtimeNanos);
            try { check(); }
            catch (Exception failure) { lease = null; throw failure; }
        }
        @Override public void check() throws IOException {
            OfficialSyncAccess.Lease current = lease;
            if (current == null || authorizedGeneration != connectionGeneration()) {
                lease = null;
                throw new IOException(AndroidMessages.SYNC_RUNTIME_09.text());
            }
            current.check();
        }
    }

    /** Fresh authorization for a reused facade, including an automatic listener after a long game. */
    static void requireOfficialSyncAccess(Context context, SecureConnectionStore.Connection account,
                                          AndroidSyncFacade facade) throws Exception {
        if (account == null) throw new IllegalStateException(AndroidMessages.SYNC_RUNTIME_09.text());
        if (!OfficialSyncAccess.isOfficialEndpoint(account.endpoint)) return;
        OfficialOperationGate gate = officialGates.get(facade);
        if (gate == null || gate.account != account)
            throw new IOException(AndroidMessages.SYNC_RUNTIME_09.text());
        gate.refresh(context);
    }

    /** Same-operation reuse checks the real deadline without another entitlement request. */
    static void checkOfficialSyncAccess(SecureConnectionStore.Connection account, AndroidSyncFacade facade)
            throws IOException {
        if (account == null) throw new IOException(AndroidMessages.SYNC_RUNTIME_09.text());
        if (!OfficialSyncAccess.isOfficialEndpoint(account.endpoint)) return;
        OfficialOperationGate gate = officialGates.get(facade);
        if (gate == null || gate.account != account) throw new IOException(AndroidMessages.SYNC_RUNTIME_09.text());
        gate.check();
    }

    private static void rememberEntitlement(Context context, SecureConnectionStore.Connection account,
                                            ManagedAccountClient.Entitlement entitlement) {
        if (!account.username.equals(entitlement.username)) return;
        try {
            int boot = -1;
            try { boot = Math.max(-1, android.provider.Settings.Global.getInt(context.getContentResolver(), "boot_count", -1)); }
            catch (RuntimeException unavailable) { /* Cache remains display-only without a boot counter. */ }
            new TrustedEntitlementCache(new File(context.getFilesDir(), "managed-account-expiry-v1").toPath())
                    .write(new TrustedEntitlementCache.Record(OfficialServiceRegistrationClient.DAV_URL, entitlement.username,
                            entitlement.expiresMs, entitlement.active, entitlement.serverTimeMs,
                            android.os.SystemClock.elapsedRealtime(), boot, entitlement.enabled, entitlement.hasConfirmedExpiry));
        } catch (IOException | RuntimeException unavailable) { /* Display-cache failure cannot grant access. */ }
    }

    static AndroidSyncFacade facade(Context context, SecureConnectionStore.Connection connection2) throws Exception {
        return createFacade(context, connection2, true);
    }

    /** Local recovery must remain available offline and after service expiry. */
    static AndroidSyncFacade localFacade(Context context, SecureConnectionStore.Connection connection2) throws Exception {
        return createFacade(context, connection2, false);
    }

    static void clearOfficialSyncAccess(AndroidSyncFacade facade) {
        OfficialOperationGate gate = officialGates.get(facade);
        if (gate != null) gate.lease = null;
    }

    private static AndroidSyncFacade createFacade(Context context, SecureConnectionStore.Connection connection2,
                                                  boolean authorizeRemote) throws Exception {
        if (connection2 == null) {
            throw new IllegalStateException(AndroidMessages.SYNC_RUNTIME_09.text());
        }
        connection2.validate();
        OfficialOperationGate gate = null;
        if (OfficialSyncAccess.isOfficialEndpoint(connection2.endpoint)) {
            gate = new OfficialOperationGate(connection2);
            if (authorizeRemote) gate.refresh(context);
        }
        AndroidGameIdentity androidGameIdentityGameIdentity = currentGameIdentity(context);
        AndroidSyncFacade result = gate == null
                ? new AndroidSyncFacade(new File(context.getFilesDir().getCanonicalFile(), "sync").toPath(), new AndroidConnection(connection2.endpoint, connection2.username, connection2.password, connection2.profile, connection2.retainsLegacyOfficialScope()), backend(context), androidGameIdentityGameIdentity)
                : new AndroidSyncFacade(new File(context.getFilesDir().getCanonicalFile(), "sync").toPath(), new AndroidConnection(connection2.endpoint, connection2.username, connection2.password, connection2.profile, connection2.retainsLegacyOfficialScope()), backend(context), androidGameIdentityGameIdentity, gate);
        if (gate != null) officialGates.put(result, gate);
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("sync-presentation-v2", 0);
    }

    /** Non-secret account/profile/game binding for a persisted automatic session. */
    static String connectionScope(Context context, SecureConnectionStore.Connection connection) throws Exception {
        return scope(context, connection);
    }

    private static String scope(Context context, SecureConnectionStore.Connection connection2) throws Exception {
        String origin = currentOrigin(context);
        if (connection2 == null) {
            throw new IOException(AndroidMessages.SYNC_RUNTIME_10.text());
        }
        return PackageTrust.hex(MessageDigest.getInstance("SHA-256").digest((AndroidGameIdentity.LOCAL_STATE_GENERATION + "\n" + origin + "\n" + currentNamespace(context) + "\n" + connection2.stateEndpoint() + "\n" + connection2.username + "\n" + connection2.profile).getBytes(StandardCharsets.UTF_8)));
    }

    static OnboardingProgress onboardingProgress(Context context, SecureConnectionStore.Connection connection2) {
        if (prefs(context).getBoolean("backendComparisonRequired", false)) return OnboardingProgress.PENDING;
        try {
            String key = "onboarding." + scope(context, connection2);
            if (failedOnboardingWrites.contains(key)) return OnboardingProgress.PENDING;
            return OnboardingProgress.valueOf(prefs(context).getString(key, "PENDING"));
        } catch (Exception unused) {
            return OnboardingProgress.PENDING;
        }
    }

    static void setOnboardingProgress(Context context, SecureConnectionStore.Connection connection2, OnboardingProgress onboardingProgress) {
        String key = null;
        try {
            key = "onboarding." + scope(context, connection2);
            if (!prefs(context).edit().putString(key, onboardingProgress.name())
                    .putBoolean("backendComparisonRequired", false).commit()) {
                throw new IOException(AndroidMessages.SYNC_RUNTIME_11.text());
            }
            failedOnboardingWrites.remove(key);
        } catch (Exception e) {
            if (key != null) failedOnboardingWrites.add(key);
            throw new IllegalStateException(AndroidMessages.SYNC_RUNTIME_12.text(), e);
        }
    }

    static boolean isAutoUploadPaused(Context context) {
        return prefs(context).getBoolean("autoUploadPaused", false);
    }

    static void setAutoUploadPaused(Context context, boolean z) {
        if (!prefs(context).edit().putBoolean("autoUploadPaused", z).commit()) {
            throw new IllegalStateException(AndroidMessages.SYNC_RUNTIME_13.text());
        }
        if (z) {
            AutoSyncService.cancelForConnectionChange(context);
        }
    }

    static boolean backendSupportsAutomatic(GameAccessBackend.Mode mode, GameAccessBackend.Capabilities capabilities) {
        return mode != null && mode != GameAccessBackend.Mode.LEGACY_HELPER_BRIDGE && capabilities != null
                && capabilities.exportSupported;
    }

    static boolean automaticAllowed(Context context, SecureConnectionStore.Connection connection2) {
        if (isAutoUploadPaused(context) || connection2 == null) {
            return false;
        }
        try {
            GameAccessBackend access = backend(context); // Construction and capabilities never invoke su.
            if (!backendSupportsAutomatic(access.mode(), access.capabilities())) return false;
            boolean pending = AndroidSyncFacade.hasUnresolvedRestore(
                    new File(context.getFilesDir().getCanonicalFile(), "sync").toPath());
            return OnboardingGate.allowsAutomatic(onboardingProgress(context, connection2), pending, true);
        } catch (Exception unavailableSelection) {
            return false;
        }
    }
}
