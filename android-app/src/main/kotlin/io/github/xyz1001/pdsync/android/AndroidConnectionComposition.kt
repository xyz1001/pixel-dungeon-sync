package io.github.xyz1001.pdsync.android

import pdsync.i18n.AndroidHostMessages.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import pdsync.OnboardingProgress
import pdsync.DiagnosticLog
import pdsync.DiagnosticFailure
import pdsync.DiagnosticResult
import pdsync.DiagnosticStage

/** Android owns platform services only. The same screen and controller compile on desktop. */
object AndroidConnectionComposition {
    /** No Activity is retained here. Only the controller's detachable host points at the current UI. */
    class Session internal constructor(
        val controller: SharedConnectionController,
        val imageExport: AppreciationImageExport,
        private val app: Context,
        private val io: ExecutorService,
        private val ports: AndroidConnectionPorts,
    ) : AutoCloseable {
        fun completedConnection(config: SharedConnectionConfig): SecureConnectionStore.Connection? =
            ports.completedConnection(config)

        fun requestImageSave(): Boolean = imageExport.request(
            controller.currentAppreciationPng(), controller.currentAppreciationScope())

        fun selectedImageDestination(uri: android.net.Uri?) {
            imageExport.selected(uri?.let { AndroidAppreciationImageDestination(app, it) })
        }

        override fun close() {
            imageExport.close()
            controller.detach()
            controller.close()
            io.shutdownNow()
        }
    }

    @JvmStatic
    fun create(context: Context, existing: SecureConnectionStore.Connection?, profile: String,
               origin: ConnectionEntryModel.Origin): Session {
        val io = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        val ui = Executor { task -> handler.post(task) }
        val ports = AndroidConnectionPorts(context.applicationContext)
        return Session(SharedConnectionController(origin, fromStored(existing), profile, ports, io, ui),
            AppreciationImageExport(io, ui), context.applicationContext, io, ports)
    }

    internal data class ImageSaveUi(
        val onSaveImage: (() -> Unit)?,
        val busy: Boolean,
        val message: String,
    )

    /** Setup and settings keep this launcher at a stable, unconditional composition position. */
    @Composable
    internal fun rememberImageSave(session: Session?): ImageSaveUi {
        // A retained session owns the exact requested PNG through the picker and rotation.
        // Process death loses those pending bytes and safely ignores a returned destination.
        val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
            session?.selectedImageDestination(it)
        }
        val export = session?.imageExport?.state
        val image = session?.controller?.state?.appreciation?.image
        val source = session?.controller?.currentAppreciationScope()
        val png = remember(session, image, source) { session?.controller?.currentAppreciationPng() }
        return ImageSaveUi(
            onSaveImage = session?.let { owner ->
                {
                    if (owner.requestImageSave()) {
                        try { saveImage.launch("appreciation.png") }
                        catch (_: RuntimeException) { owner.imageExport.launchFailed() }
                    }
                }
            },
            busy = export?.busy == true,
            message = session?.imageExport?.messageFor(source, png).orEmpty(),
        )
    }

    @JvmStatic
    fun show(activity: ComponentActivity, session: Session, game: SharedGameIdentity?) {
        activity.setContent {
            val imageSave = rememberImageSave(session)
            // Android target 36 is edge-to-edge. Keep system bars/IME handling out of desktop UI.
            AndroidAppViewport {
                SharedConnectionScreen(session.controller, game,
                    onSaveImage = imageSave.onSaveImage,
                    imageSaveBusy = imageSave.busy,
                    imageSaveMessage = imageSave.message,
                )
            }
        }
    }

    @JvmStatic
    fun fromStored(value: SecureConnectionStore.Connection?): SharedConnectionConfig? = value?.let {
        SharedConnectionConfig(it.endpoint, it.username, it.password, it.profile,
            it.provider == SecureConnectionStore.Connection.PROVIDER_OFFICIAL)
    }

    @JvmStatic
    fun toStored(value: SharedConnectionConfig): SecureConnectionStore.Connection =
        SecureConnectionStore.Connection(value.endpoint, value.username, value.password, value.profile,
            if (value.official) SecureConnectionStore.Connection.PROVIDER_OFFICIAL
            else SecureConnectionStore.Connection.PROVIDER_CUSTOM)
}

/** Application-context-only, typed adapter. It never chooses a game, source, namespace, or baseline. */
internal class AndroidConnectionPorts(context: Context) : SharedConnectionPorts {
    private val app = context.applicationContext
    // Captured metadata only. It never opens Root or reads save files.
    private val entrySelection = runCatching { SyncRuntime.currentSelectionToken(app) }.getOrNull()
    private val entryNamespace = runCatching { SyncRuntime.currentNamespace(app) }.getOrNull()
    private val entryConnection = SyncRuntime.currentConnection()
    @Volatile private var completed: SecureConnectionStore.Connection? = null

    private fun currentSelection(): Boolean = runCatching {
        entrySelection != null && entryNamespace != null &&
            entrySelection == SyncRuntime.currentSelectionToken(app) &&
            entryNamespace == SyncRuntime.currentNamespace(app)
    }.getOrDefault(false)

    private fun requireEntryContext() {
        check(currentSelection() && SyncRuntime.currentConnection() === entryConnection) { SETTINGS_HAVE_CHANGED_PLEASE_RETRY_137.text() }
    }

    /** A delayed callback cannot activate a different game's or account's completion. */
    fun completedConnection(config: SharedConnectionConfig): SecureConnectionStore.Connection? = runCatching {
        val candidate = completed ?: return@runCatching null
        candidate.takeIf {
            currentSelection() && SyncRuntime.currentConnection() === candidate &&
                AndroidConnectionComposition.fromStored(candidate) == config &&
                SyncRuntime.onboardingProgress(app, candidate) == OnboardingProgress.COMPLETE
        }
    }.getOrNull()

    /** Durable credential equality is only permission to start fresh verification on Next. */
    override fun isSavedConnection(config: SharedConnectionConfig): Boolean = runCatching {
        val candidate = AndroidConnectionComposition.toStored(config)
        candidate.validate()
        if (candidate.isTemporary) return@runCatching false
        val stored = SecureConnectionStore(app).load() ?: return@runCatching false
        // Keep the store's existing official-root migration and provider rules authoritative.
        stored.endpoint == candidate.endpoint && stored.username == candidate.username &&
            stored.password == candidate.password && stored.profile == candidate.profile &&
            stored.provider == candidate.provider
    }.getOrDefault(false)

    override fun testConnection(config: SharedConnectionConfig): Boolean = diagnostic(DiagnosticStage.CONNECTION) {
        synchronized(SyncRuntime.OPERATION_LOCK) {
            val candidate = AndroidConnectionComposition.toStored(config)
            candidate.validate()
            requireEntryContext()
            val success = SyncRuntime.facade(app, candidate).connect().isSuccess
            requireEntryContext()
            if (!success) throw IOException("Connection probe failed")
            success
        }
    }

    /** Custom WebDAV still requires the controller's current, real DAV proof. */
    override fun saveAndActivate(config: SharedConnectionConfig) = persistAndActivate(config)

    /** Built-in activation uses current authenticated account access, without claiming DAV was tested. */
    override fun saveAuthenticatedOfficial(config: SharedConnectionConfig) {
        require(config.official && config.endpoint == TrustedEntitlementCache.OFFICIAL_ENDPOINT)
        persistAndActivate(config)
    }

    private fun persistAndActivate(config: SharedConnectionConfig) = diagnostic(DiagnosticStage.WORK) {
        synchronized(SyncRuntime.OPERATION_LOCK) {
            val candidate = AndroidConnectionComposition.toStored(config)
            candidate.validate()
            requireEntryContext()
            // Fail closed if saving credentials or the final completion marker fails.
            SyncRuntime.setOnboardingProgress(app, candidate, OnboardingProgress.PENDING)
            if (!candidate.isTemporary) SecureConnectionStore(app).save(candidate)
            requireEntryContext()
            SyncRuntime.setOnboardingProgress(app, candidate, OnboardingProgress.COMPLETE)
            requireEntryContext()
            AutoSyncService.cancelForConnectionChange(app)
            SyncRuntime.setConnection(candidate)
            completed = candidate
        }
    }

    private fun <T> diagnostic(stage: DiagnosticStage, work: () -> T): T {
        // The common controller owns one operation trace, including auth, DAV and persistence.
        DiagnosticLog.record(stage, DiagnosticResult.STARTED)
        try {
            val result = work()
            DiagnosticLog.record(stage, DiagnosticResult.SUCCEEDED)
            return result
        } catch (failure: Exception) {
            val category = AndroidDiagnosticLogging.classify(failure)
            DiagnosticLog.record(stage,
                if (category == DiagnosticFailure.CANCELED) DiagnosticResult.CANCELED else DiagnosticResult.FAILED,
                category, AndroidDiagnosticLogging.failureDetails(failure))
            throw failure
        }
    }

    override fun cacheDirectory(): Path = app.filesDir.toPath().resolve("managed-account-expiry-v1")
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun bootCount(): Int = try {
        Settings.Global.getInt(app.contentResolver, "boot_count", -1).coerceAtLeast(-1)
    } catch (_: RuntimeException) { -1 }

    override fun copyUsername(username: String) {
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(USERNAME.text(), username))
    }

    override fun decodeImage(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
