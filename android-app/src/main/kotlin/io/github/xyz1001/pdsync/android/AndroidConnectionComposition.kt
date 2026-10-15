package io.github.xyz1001.pdsync.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
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
        private val io: ExecutorService,
    ) : AutoCloseable {
        override fun close() {
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
        return Session(SharedConnectionController(origin, fromStored(existing), profile,
            AndroidConnectionPorts(context.applicationContext), io, ui), io)
    }

    @JvmStatic
    fun show(activity: ComponentActivity, session: Session, game: SharedGameIdentity?) {
        activity.setContent {
            // Android target 36 is edge-to-edge. Keep system bars/IME handling out of desktop UI.
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                SharedConnectionScreen(session.controller, game)
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
            val success = SyncRuntime.facade(app, candidate).connect().isSuccess
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
            // Failed authentication, Back, cancellation and test-only paths never reach persistence.
            if (!candidate.isTemporary) SecureConnectionStore(app).save(candidate)
            AutoSyncService.cancelForConnectionChange(app)
            SyncRuntime.setConnection(candidate)
            SyncRuntime.setOnboardingProgress(app, candidate, OnboardingProgress.PENDING)
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
            ?.setPrimaryClip(ClipData.newPlainText("用户名", username))
    }

    override fun decodeImage(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
