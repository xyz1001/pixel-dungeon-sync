package io.github.xyz1001.pdsync.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import pdsync.DiagnosticAction
import pdsync.DiagnosticFailure
import pdsync.DiagnosticLog
import pdsync.DiagnosticResult
import pdsync.DiagnosticStage

/** Credentials stay in this retained, in-memory controller, never a saved-state bundle. */
data class SharedConnectionConfig(
    val endpoint: String,
    val username: String,
    val password: String,
    val profile: String,
    val official: Boolean,
) {
    override fun toString() = "Shared connection [private fields hidden]"
}

/** Durable platform services only. Implementations must not retain an Activity or a window. */
interface SharedConnectionPorts {
    /**
     * Read-only durable credential match. This permits an explicit Next to revalidate a saved
     * custom connection or authenticate a built-in account. It never grants either kind of proof.
     * Missing, unreadable or temporary credentials must return false.
     */
    fun isSavedConnection(config: SharedConnectionConfig): Boolean = false
    /** Validate and perform the real platform DAV probe; do not save or activate. */
    fun testConnection(config: SharedConnectionConfig): Boolean
    /** Custom WebDAV only: explicit Next/Save after a fresh successful DAV probe. */
    fun saveAndActivate(config: SharedConnectionConfig)
    /** Built-in service only: authenticated, current account access. This is not DAV verification. */
    fun saveAuthenticatedOfficial(config: SharedConnectionConfig) { error("Built-in account activation is not supported") }
    fun cacheDirectory(): Path?
    fun elapsedRealtimeMs(): Long
    fun bootCount(): Int
    fun copyUsername(username: String)
    fun decodeImage(bytes: ByteArray): ImageBitmap?
}

/** Replaceable UI attachment; no worker or network operation captures this object. */
interface SharedConnectionHost {
    fun onBack(destination: ConnectionEntryModel.Back)
    fun onCommitted(config: SharedConnectionConfig)
    fun onBusyChanged(busy: Boolean)
    fun onReloadSavedConnection() {}
}

data class SharedAppreciationState(
    val username: String?,
    val authenticated: Boolean,
    val expiry: String,
    val accessStatus: String,
    val message: String,
    val image: ImageBitmap?,
    val busy: Boolean,
    val canRefresh: Boolean,
)

data class SharedConnectionState(
    val origin: ConnectionEntryModel.Origin,
    val mode: ConnectionEntryModel.Mode,
    val endpoint: String,
    val username: String,
    val password: String,
    val busy: Boolean,
    val canCancel: Boolean,
    val canEdit: Boolean,
    val canGoBack: Boolean,
    val officialAuthenticated: Boolean,
    val showOfficialAccount: Boolean,
    val officialExpiry: String,
    val officialAccessStatus: String,
    /** Built-in account access or permission to verify custom DAV; never a synchronization baseline. */
    val canContinue: Boolean,
    val notice: String,
    val error: Boolean,
    val showChangeService: Boolean,
    val appreciationLabel: String,
    val canAppreciate: Boolean,
    val appreciation: SharedAppreciationState?,
    val recoveryMessage: String,
    val recoveryLabel: String,
) {
    override fun toString() = "Shared connection UI [private fields hidden]"
}

/** Internal test seam uses the real protocol clients with in-memory transports. */
internal interface SharedOfficialClients {
    fun authentication(): OfficialServiceRegistrationClient
    fun account(config: SharedConnectionConfig): ManagedAccountClient
    fun publicAppreciation(): ManagedAccountClient
}

private object ProductionOfficialClients : SharedOfficialClients {
    override fun authentication() = OfficialServiceRegistrationClient()
    override fun account(config: SharedConnectionConfig) =
        ManagedAccountClient(config.endpoint, config.username, config.password)
    override fun publicAppreciation() = ManagedAccountClient.publicAppreciation()
}

/**
 * The same state machine runs on both platforms. UI methods and [ui] callbacks must run on
 * the platform UI thread. [io] performs blocking I/O. Attachment changes never replay an
 * authentication attempt. Android backgrounding invalidates in-flight mutation/DAV permission,
 * but retains confirmed account display and its server-time expiry. Window focus is not observed.
 */
class SharedConnectionController internal constructor(
    private val origin: ConnectionEntryModel.Origin,
    existing: SharedConnectionConfig?,
    private val profile: String,
    private val ports: SharedConnectionPorts,
    private val io: Executor,
    private val ui: Executor,
    private val clients: SharedOfficialClients,
) {
    constructor(
        origin: ConnectionEntryModel.Origin,
        existing: SharedConnectionConfig?,
        profile: String,
        ports: SharedConnectionPorts,
        io: Executor,
        ui: Executor,
    ) : this(origin, existing, profile, ports, io, ui, ProductionOfficialClients)

    private val existingOnEntry = existing != null
    private val entry = if (existing == null && origin == ConnectionEntryModel.Origin.SETUP) {
        ConnectionEntryModel(origin, null)
    } else {
        val mode = if (existing?.official == true) ConnectionEntryModel.Mode.OFFICIAL else ConnectionEntryModel.Mode.CUSTOM
        if (origin == ConnectionEntryModel.Origin.SETUP) ConnectionEntryModel.restore(origin, mode, mode)
        else ConnectionEntryModel(origin, mode)
    }
    private var custom = existing?.takeUnless { it.official }?.copy(profile = profile)
        ?: SharedConnectionConfig("", "", "", profile, false)
    private var officialUser = existing?.takeIf { isOfficial(it) }?.username ?: ""
    private var officialPassword = existing?.takeIf { isOfficial(it) }?.password ?: ""
    private var saved = existing?.copy(profile = profile)
    // A saved draft may enter verification directly on re-entry. It is not a DAV proof, is
    // never populated by testing, and is consumed by any edit or explicit connection attempt.
    private var savedReentry = saved?.takeIf { candidate ->
        candidate.endpoint.isNotBlank() && (!candidate.official || isOfficial(candidate)) &&
            runCatching { ports.isSavedConnection(candidate) }.getOrDefault(false)
    }
    private var officialSession: OfficialSession? = null
    private var pendingAppreciationOnly = false
    private var host: SharedConnectionHost? = null
    private val alive = AtomicBoolean(true)
    private val foreground = AtomicBoolean(false)
    private var entered = false
    private val generation = AtomicLong(0)
    private val lifetime = AtomicLong(0)
    private var operation: Operation? = null
    private var proof: Proof? = null
    private var notice = ""
    private var error = false
    private var pendingCommit: SharedConnectionConfig? = null
    private var appreciationFlow: ManagedAccountFlow? = null
    private var appreciationSource: SharedConnectionConfig? = null
    private var appreciationClient: ManagedAccountClient? = null
    private var appreciationImage: ImageBitmap? = null
    private var appreciationOnly = false
    private var appreciationLocalMessage = ""
    private var reportedBusy = false
    private var recoveryMessage = ""
    private var recoveryLabel = ""
    private val cache = runCatching { ports.cacheDirectory()?.let(::TrustedEntitlementCache) }.getOrNull()
    // Exact durable credentials may show previously authenticated metadata, including after an
    // expired account returns 401. Display history can never enable Next or create a session.
    private var savedOfficialHistory = savedReentry?.takeIf(::isOfficial)?.let { config ->
        cache?.read(config.endpoint, config.username)?.let { config to it }
    }

    var state by mutableStateOf(snapshot())
        private set

    private data class Operation(val generation: Long, val lifetime: Long, val config: SharedConnectionConfig,
                                 val register: Boolean = false, val commit: Boolean = false,
                                 val editableDavTest: Boolean = false, val accountRefresh: Boolean = false) {
        @Volatile var commitStarted: Boolean = false
        val trace = DiagnosticLog.begin(if (commit) DiagnosticAction.CONFIGURE else DiagnosticAction.CONNECT, false)
    }
    private data class Proof(val generation: Long, val lifetime: Long, val config: SharedConnectionConfig)
    private data class OfficialSession(val config: SharedConnectionConfig, val record: TrustedEntitlementCache.Record,
                                       val generation: Long)
    private data class Authentication(val config: SharedConnectionConfig, val record: TrustedEntitlementCache.Record?)

    fun attach(host: SharedConnectionHost) {
        if (!alive.get()) return
        this.host = host
        reportedBusy = operation != null
        host.onBusyChanged(reportedBusy)
        publish()
        deliverCommit()
    }

    /** Retain the controller itself across configuration changes, not this host. */
    fun detach() {
        host = null
        foreground(false)
    }

    fun foreground(value: Boolean) {
        if (!alive.get()) return
        fun transition() {
            if (foreground.getAndSet(value) != value && !value) {
                lifetime.incrementAndGet()
                proof = null
            }
        }
        val pending = operation
        if (pending == null) transition() else synchronized(pending) { transition() }
        appreciationFlow?.foreground(value)
        publish()
        if (value) {
            deliverCommit()
            // Restore saved credentials once on actual page entry, never on focus/resume.
            if (!entered) {
                entered = true
                restoreOfficialAccount()
            }
        }
    }

    fun close() {
        if (!alive.compareAndSet(true, false)) return
        host = null
        foreground.set(false)
        generation.incrementAndGet()
        lifetime.incrementAndGet()
        operation = null
        proof = null
        closeAppreciation()
        custom = custom.copy(password = "")
        officialPassword = ""
        officialSession = null
        savedOfficialHistory = null
        pendingAppreciationOnly = false
        saved = null
        savedReentry = null
        pendingCommit = null
        publish()
    }

    fun editEndpoint(value: String) {
        if (operation?.commit == true || entry.mode() != ConnectionEntryModel.Mode.CUSTOM || custom.endpoint == value) return
        custom = custom.copy(endpoint = value, password = "")
        edited()
    }
    fun editUsername(value: String) {
        if (operation?.commit == true) return
        if (entry.mode() == ConnectionEntryModel.Mode.OFFICIAL) {
            if (officialUser == value) return
            officialUser = value
            officialPassword = ""
        } else if (entry.mode() == ConnectionEntryModel.Mode.CUSTOM) {
            if (custom.username == value) return
            custom = custom.copy(username = value, password = "")
        } else return
        edited()
    }
    fun editPassword(value: String) {
        if (operation?.commit == true) return
        if (entry.mode() == ConnectionEntryModel.Mode.OFFICIAL) {
            if (officialPassword == value) return
            officialPassword = value
        } else if (entry.mode() == ConnectionEntryModel.Mode.CUSTOM) {
            if (custom.password == value) return
            custom = custom.copy(password = value)
        } else return
        edited()
    }
    private fun edited() {
        invalidateSource()
        notice = ""
        error = false
        publish()
    }

    fun setSavedConnectionRecovery(message: String, label: String) {
        recoveryMessage = message
        recoveryLabel = label
        publish()
    }
    fun reloadSavedConnection() {
        if (canStart() && recoveryLabel.isNotEmpty()) host?.onReloadSavedConnection()
    }

    fun choose(mode: ConnectionEntryModel.Mode) {
        if (!alive.get() || !entry.choose(mode, operation != null)) return
        edited()
    }
    fun showChooser() {
        if (!alive.get() || !entry.showChooser(operation != null)) return
        edited()
    }
    fun back() {
        if (!alive.get()) return
        if (appreciationFlow != null) {
            val standalone = appreciationOnly
            closeAppreciation()
            publish()
            if (standalone) host?.onBack(ConnectionEntryModel.Back.SETTINGS)
            return
        }
        // A DAV-only test has no durable effect; Back invalidates its late callback before navigating.
        // Authentication/registration and the persistence boundary keep their existing explicit cancellation.
        if (operation?.editableDavTest == true || operation?.accountRefresh == true) invalidateSource()
        // Editing a saved connection inside the wizard historically returns directly to Path.
        // New setup alone visits its chooser first; this distinction is shared by both hosts.
        val destination = if (origin == ConnectionEntryModel.Origin.SETUP && existingOnEntry && operation == null)
            ConnectionEntryModel.Back.PATH else entry.back(operation != null)
        if (destination == ConnectionEntryModel.Back.STAY) return
        invalidateSource()
        notice = ""
        error = false
        publish()
        if (destination != ConnectionEntryModel.Back.CHOICE) host?.onBack(destination)
    }

    /** Cancelling may not undo an already-sent registration. Never silently resend it. */
    fun cancel() {
        val pending = operation ?: return
        synchronized(pending) {
            if (pending.commitStarted) return
            invalidateSource()
        }
        notice = if (pending.register) "注册结果未确认，请用相同账号和密码登录。" else "已取消。"
        error = pending.register
        publish()
    }

    fun login() = authenticate(false)
    fun register() = authenticate(true)

    private fun authenticate(register: Boolean) {
        if (!canStart() || entry.mode() != ConnectionEntryModel.Mode.OFFICIAL) return
        closeAppreciation()
        savedReentry = null
        proof = null
        officialSession = null
        val requested = draft()
        val pending = Operation(generation.get(), lifetime.get(), requested, register)
        operation = pending
        notice = "正在登录…"
        error = false
        publish()
        val attempt = ConnectionAuthAttempt("${pending.generation}:${pending.lifetime}") {
            val account = clients.authentication().let {
                if (register) it.register(requested.username, requested.password)
                else it.login(requested.username, requested.password)
            }
            val candidate = SharedConnectionConfig(account.endpoint, account.username, account.password, profile, true)
            if (!canWork(pending)) Authentication(candidate, null)
            else Authentication(candidate, readOfficialAccount(candidate, pending))
        }
        execute(pending, { attempt.await() }) { result ->
            acceptOfficialAccount(result.config, result.record, pending)
        }
    }

    /** Re-entry authenticates saved credentials with the read-only entitlement endpoint, never DAV. */
    private fun restoreOfficialAccount() {
        if (!canStart() || appreciationFlow != null || entry.mode() != ConnectionEntryModel.Mode.OFFICIAL) return
        val candidate = savedReentry?.takeIf { it.official && it == draft() }
            ?: return
        savedReentry = null
        val pending = Operation(generation.get(), lifetime.get(), candidate, accountRefresh = true)
        operation = pending
        notice = "正在登录…"
        error = false
        publish()
        execute(pending, { readOfficialAccount(candidate, pending) }) { record ->
            acceptOfficialAccount(candidate, record, pending)
        }
    }

    private fun readOfficialAccount(candidate: SharedConnectionConfig, pending: Operation): TrustedEntitlementCache.Record {
        checkWork(pending)
        check(isOfficial(candidate))
        val entitlement = clients.account(candidate).entitlement()
        checkWork(pending)
        return remember(candidate, entitlement)
    }

    private fun acceptOfficialAccount(candidate: SharedConnectionConfig, record: TrustedEntitlementCache.Record?, pending: Operation) {
        officialSession = record?.takeIf { canWork(pending) && candidate == draft() }?.let {
            OfficialSession(candidate, it, pending.generation)
        }
        notice = if (officialSession != null) "" else "请重新登录。"
        error = officialSession == null
    }

    /** Updates elapsed expiry without any network request or wall-clock-based authorization. */
    fun refreshAccountStatus() { publish() }

    fun testConnection() {
        if (!canStart() || entry.mode() != ConnectionEntryModel.Mode.CUSTOM) return
        val candidate = draft()
        savedReentry = null
        proof = null
        val pending = Operation(generation.get(), lifetime.get(), candidate, editableDavTest = true)
        operation = pending
        notice = "正在测试连接…"
        error = false
        publish()
        execute(pending, { verify(candidate, pending) }) {
            if (canWork(pending)) proof = Proof(pending.generation, pending.lifetime, candidate)
            notice = if (proof != null) "连接测试通过。" else "请重新测试当前连接。"
            error = proof == null
        }
    }

    fun continueConnection() {
        if (!canStart()) return
        val candidate = if (entry.mode() == ConnectionEntryModel.Mode.OFFICIAL) {
            officialSession?.takeIf { hasOfficialAccess(it) }?.config
        } else if (hasProof()) proof!!.config else savedDraftForRevalidation()
        if (candidate == null) {
            proof = null
            notice = if (entry.mode() == ConnectionEntryModel.Mode.OFFICIAL) "请先登录有效的内置服务账号。" else "请先测试当前连接。"
            error = true
            publish()
            return
        }
        savedReentry = null
        proof = null
        val pending = Operation(generation.get(), lifetime.get(), candidate, commit = true)
        operation = pending
        notice = "正在应用连接设置…"
        error = false
        publish()
        execute(pending, {
            if (candidate.official) {
                checkWork(pending)
                check(officialSession?.let { it.config == candidate && hasOfficialAccess(it) } == true)
            } else verify(candidate, pending)
            // Cancellation/background/source edits before this point cannot write configuration.
            synchronized(pending) {
                checkWork(pending)
                if (candidate.official) check(officialSession?.let { it.config == candidate && hasOfficialAccess(it) } == true)
                pending.commitStarted = true
            }
            ui.execute { if (operation === pending) publish() }
            if (candidate.official) ports.saveAuthenticatedOfficial(candidate) else ports.saveAndActivate(candidate)
            candidate
        }) { committed ->
            saved = committed
            notice = "连接设置已保存。"
            pendingCommit = committed
        }
    }

    private fun verify(candidate: SharedConnectionConfig, pending: Operation) {
        checkWork(pending)
        require(candidate.endpoint.isNotBlank()) { "Invalid connection" }
        check(!candidate.official) // Account authorization is never promoted to a DAV proof.
        checkWork(pending)
        if (!ports.testConnection(candidate)) throw ProbeRejected()
        checkWork(pending)
    }

    fun openAppreciationOnly() {
        pendingAppreciationOnly = true
        openAppreciation()
        if (appreciationFlow != null) {
            appreciationOnly = true
            pendingAppreciationOnly = false
        }
    }

    fun openAppreciation() {
        if (!canStart() || entry.mode() != ConnectionEntryModel.Mode.OFFICIAL) return
        val source = officialAccountForDraft() ?: return
        if (appreciationFlow != null && appreciationSource == source) return
        closeAppreciation()
        appreciationSource = source
        appreciationFlow = ManagedAccountFlow(source.username, cache?.read(source.endpoint, source.username),
            object : ManagedAccountFlow.Clock {
                override fun elapsedMs() = ports.elapsedRealtimeMs()
                override fun bootCount() = ports.bootCount()
            })
        appreciationFlow!!.foreground(foreground.get())
        notice = ""
        error = false
        publish()
        refreshAppreciation()
    }

    /** One load per explicit page open; focus/resume never requests data. Missing images retry once. */
    fun refreshAppreciation() {
        val flow = appreciationFlow ?: return
        val source = appreciationSource ?: return
        val ticket = flow.refresh() ?: return
        cancelAppreciationRequest()
        appreciationLocalMessage = ""
        var initialMessage: String? = null
        val client = try {
            if (source.password.isEmpty()) clients.publicAppreciation() else clients.account(source)
        } catch (failure: ManagedAccountClient.ManagedAccountException) {
            initialMessage = failure.userMessage()
            clients.publicAppreciation()
        }
        appreciationClient = client
        publish()
        fun current() = alive.get() && flow.accepts(ticket)
        try {
            io.execute {
                var message = initialMessage
                var imageFailure: String? = null
                // Public GETs only. Never repeat entitlement, authentication, registration or DAV.
                for (attempt in 0..1) {
                    try {
                        if (!current()) return@execute
                        val metadata = client.appreciation()
                        if (!flow.metadata(ticket, metadata.imageSha256)) return@execute
                        ui.execute metadata@{
                            if (!current() || appreciationFlow !== flow || appreciationClient !== client) return@metadata
                            if (!flow.hasImage(ticket)) appreciationImage = null
                            publish()
                        }
                        if (flow.hasImage(ticket)) break
                        val bytes = client.paymentImage(metadata)
                        if (!current()) return@execute
                        val decoded = runCatching { ports.decodeImage(bytes) }.getOrNull()
                        if (decoded != null) {
                            ui.execute image@{
                                if (!current() || appreciationFlow !== flow || appreciationClient !== client) return@image
                                if (flow.image(ticket, bytes)) {
                                    appreciationImage = decoded
                                    publish()
                                }
                            }
                            imageFailure = null
                            break
                        }
                        imageFailure = "赞赏码暂时无法显示。"
                        break
                    } catch (failure: Exception) {
                        if (!current()) return@execute
                        if (flow.hasImage(ticket)) break
                        imageFailure = "赞赏码暂时无法加载。"
                        // Security, malformed responses and rate limits are not transient retries.
                        if (failure !is ManagedAccountClient.ManagedAccountException ||
                            failure.kind !in setOf(ManagedAccountClient.FailureKind.NETWORK_ERROR,
                                ManagedAccountClient.FailureKind.TIMEOUT, ManagedAccountClient.FailureKind.SERVER_ERROR)) break
                    }
                }
                // The public image neither sends credentials nor grants permission to read private metadata.
                if (client.canReadEntitlement() && current()) {
                    try {
                        val entitlement = client.entitlement()
                        if (!current()) return@execute
                        ui.execute entitlement@{
                            if (!current() || appreciationFlow !== flow || appreciationClient !== client) return@entitlement
                            if (flow.entitlement(ticket, entitlement.username, entitlement.expiresMs,
                                    entitlement.active, entitlement.managed, entitlement.serverTimeMs)) {
                                val record = remember(source, entitlement)
                                officialSession = OfficialSession(source, record, generation.get())
                                publish()
                            }
                        }
                    } catch (failure: Exception) {
                        message = safeFailure(failure, "账号信息暂时无法更新。")
                            .replace("请稍后刷新", "请稍后再试").replace("请刷新", "请稍后再试")
                        if (failure is ManagedAccountClient.ManagedAccountException &&
                            failure.kind in setOf(ManagedAccountClient.FailureKind.AUTHENTICATION_REQUIRED,
                                ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED,
                                ManagedAccountClient.FailureKind.ACCOUNT_INACTIVE,
                                ManagedAccountClient.FailureKind.FORBIDDEN)) {
                            ui.execute {
                                if (current() && appreciationFlow === flow && appreciationClient === client) {
                                    officialSession?.takeIf { it.config == source }?.let {
                                        savedOfficialHistory = it.config to it.record
                                    }
                                    officialSession = null
                                    publish()
                                }
                            }
                        }
                    }
                }
                val result = imageFailure ?: message
                ui.execute completion@{
                    if (!current() || appreciationFlow !== flow || appreciationClient !== client) return@completion
                    if (result != null) flow.fail(ticket, result) else flow.complete(ticket)
                    publish()
                }
            }
        } catch (_: RuntimeException) {
            flow.fail(ticket, "暂时无法读取。")
            publish()
        }
    }

    /** Opaque page identity survives configuration changes but never crosses account/page exit. */
    fun currentAppreciationScope(): Any? = appreciationFlow?.takeIf {
        alive.get() && appreciationSource == draft()
    }

    /** Defensive copy of the validated original backing the currently displayed bitmap. No I/O. */
    fun currentAppreciationPng(): ByteArray? {
        val flow = appreciationFlow ?: return null
        if (currentAppreciationScope() !== flow || appreciationImage == null) return null
        return flow.view().image
    }

    fun copyUsername() {
        val flow = appreciationFlow ?: return
        try {
            flow.copyUsername(flow.view().generation) { username -> ports.copyUsername(username) }
        } catch (_: RuntimeException) {
            appreciationLocalMessage = "暂时无法复制，请重试。"
            publish()
        }
    }

    private fun remember(source: SharedConnectionConfig, entitlement: ManagedAccountClient.Entitlement): TrustedEntitlementCache.Record {
        check(isOfficial(source) && source.username == entitlement.username)
        val record = TrustedEntitlementCache.Record(source.endpoint, entitlement.username, entitlement.expiresMs,
            entitlement.active, entitlement.serverTimeMs, ports.elapsedRealtimeMs(), ports.bootCount())
        runCatching { cache?.write(record) }
        return record
    }

    private fun <T> execute(pending: Operation, work: () -> T, success: (T) -> Unit) {
        try {
            io.execute {
                if (!accepts(pending)) {
                    pending.trace.record(DiagnosticStage.FINISHED, DiagnosticResult.CANCELED, DiagnosticFailure.CANCELED)
                    return@execute
                }
                val result = DiagnosticLog.bind(pending.trace).use {
                    pending.trace.record(DiagnosticStage.WORK, DiagnosticResult.STARTED)
                    runCatching(work)
                }
                ui.execute completion@{
                    val current = accepts(pending) && operation === pending
                    val failure = if (!current || (!pending.commitStarted && !canWork(pending))) DiagnosticFailure.CANCELED
                        else result.exceptionOrNull()?.let(::diagnosticFailure) ?: DiagnosticFailure.NONE
                    pending.trace.record(DiagnosticStage.FINISHED,
                        when (failure) {
                            DiagnosticFailure.NONE -> DiagnosticResult.SUCCEEDED
                            DiagnosticFailure.CANCELED -> DiagnosticResult.CANCELED
                            else -> DiagnosticResult.FAILED
                        }, failure)
                    if (!current) return@completion
                    operation = null
                    result.fold(success, { failure ->
                        proof = null
                        if (pending.accountRefresh) {
                            officialSession?.let { savedOfficialHistory = it.config to it.record }
                            officialSession = null
                        }
                        notice = safeFailure(failure, if (pending.config.official) "暂时无法完成，请重试。" else "连接测试未通过，请重试。")
                        error = true
                    })
                    publish()
                    deliverCommit()
                    if (pendingAppreciationOnly && officialAccountForDraft() != null) openAppreciationOnly()
                }
            }
        } catch (_: RuntimeException) {
            pending.trace.record(DiagnosticStage.FINISHED, DiagnosticResult.FAILED, DiagnosticFailure.UNAVAILABLE)
            if (operation === pending) {
                operation = null
                proof = null
                notice = "暂时无法连接，请重试。"
                error = true
                publish()
            }
        }
    }

    private fun safeFailure(failure: Throwable, fallback: String): String = when (failure) {
        is OfficialServiceRegistrationClient.RegistrationException -> failure.userMessage()
        is ManagedAccountClient.ManagedAccountException -> failure.userMessage()
        is StaleOperation -> "页面状态已变化，请重试。"
        else -> fallback // Never display raw transport, platform or server exception messages.
    }
    private fun diagnosticFailure(failure: Throwable): DiagnosticFailure = when (failure) {
        is StaleOperation -> DiagnosticFailure.CANCELED
        is ProbeRejected -> DiagnosticFailure.CONNECTION
        is ManagedAccountClient.ManagedAccountException -> when (failure.kind) {
            ManagedAccountClient.FailureKind.AUTHENTICATION_REQUIRED -> DiagnosticFailure.AUTHENTICATION
            ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED, ManagedAccountClient.FailureKind.ACCOUNT_INACTIVE,
            ManagedAccountClient.FailureKind.FORBIDDEN -> DiagnosticFailure.AUTHORIZATION
            ManagedAccountClient.FailureKind.TIMEOUT -> DiagnosticFailure.TIMEOUT
            ManagedAccountClient.FailureKind.TLS_ERROR -> DiagnosticFailure.TLS
            ManagedAccountClient.FailureKind.NETWORK_ERROR -> DiagnosticFailure.NETWORK_IO
            ManagedAccountClient.FailureKind.INVALID_INPUT, ManagedAccountClient.FailureKind.INVALID_RESPONSE,
            ManagedAccountClient.FailureKind.IMAGE_INTEGRITY_ERROR -> DiagnosticFailure.VALIDATION
            else -> DiagnosticFailure.HTTP
        }
        is OfficialServiceRegistrationClient.RegistrationException -> when (failure.kind) {
            OfficialServiceRegistrationClient.FailureKind.INVALID_CREDENTIALS -> DiagnosticFailure.AUTHENTICATION
            OfficialServiceRegistrationClient.FailureKind.TIMEOUT -> DiagnosticFailure.TIMEOUT
            OfficialServiceRegistrationClient.FailureKind.TLS_ERROR -> DiagnosticFailure.TLS
            OfficialServiceRegistrationClient.FailureKind.NETWORK_ERROR -> DiagnosticFailure.NETWORK_IO
            OfficialServiceRegistrationClient.FailureKind.REGISTRATION_USERNAME_INVALID,
            OfficialServiceRegistrationClient.FailureKind.LOGIN_USERNAME_INVALID,
            OfficialServiceRegistrationClient.FailureKind.REGISTRATION_PASSWORD_LENGTH,
            OfficialServiceRegistrationClient.FailureKind.LOGIN_PASSWORD_LENGTH,
            OfficialServiceRegistrationClient.FailureKind.PASSWORD_CHARACTERS_INVALID,
            OfficialServiceRegistrationClient.FailureKind.REGISTRATION_PASSWORD_PREFIX,
            OfficialServiceRegistrationClient.FailureKind.PASSWORD_REJECTED,
            OfficialServiceRegistrationClient.FailureKind.REQUEST_FORMAT_REJECTED,
            OfficialServiceRegistrationClient.FailureKind.INVALID_RESPONSE -> DiagnosticFailure.VALIDATION
            else -> DiagnosticFailure.HTTP
        }
        else -> DiagnosticLog.classify(failure)
    }
    private class StaleOperation : Exception()
    private class ProbeRejected : Exception()
    private fun accepts(pending: Operation) = alive.get() && generation.get() == pending.generation
    private fun canWork(pending: Operation) = accepts(pending) && foreground.get() && lifetime.get() == pending.lifetime
    private fun checkWork(pending: Operation) { if (!canWork(pending)) throw StaleOperation() }
    private fun canEditDraft() = alive.get() && pendingCommit == null &&
        (operation == null || operation?.editableDavTest == true || operation?.accountRefresh == true)
    private fun canStart() = alive.get() && foreground.get() && host != null && operation == null && pendingCommit == null
    private fun hasProof(): Boolean {
        val current = proof ?: return false
        return alive.get() && foreground.get() && operation == null && appreciationFlow == null &&
            current.generation == generation.get() && current.lifetime == lifetime.get() && current.config == draft()
    }
    private fun savedDraftForRevalidation(): SharedConnectionConfig? = savedReentry?.takeIf {
        canStart() && appreciationFlow == null && entry.mode() == ConnectionEntryModel.Mode.CUSTOM && !it.official && it == draft()
    }
    private fun draft() = if (entry.mode() == ConnectionEntryModel.Mode.OFFICIAL)
        SharedConnectionConfig(TrustedEntitlementCache.OFFICIAL_ENDPOINT, officialUser, officialPassword, profile, true)
    else custom
    private fun officialAccountForDraft(): SharedConnectionConfig? =
        (officialSession?.config ?: savedOfficialHistory?.first)?.takeIf {
        entry.mode() == ConnectionEntryModel.Mode.OFFICIAL && isOfficial(it) && it == draft()
    }
    private fun hasOfficialAccess(session: OfficialSession): Boolean {
        val record = session.record
        return alive.get() && foreground.get() && session.generation == generation.get() &&
            session.config == draft() && record.active &&
            ports.elapsedRealtimeMs() >= record.elapsedMs &&
            (record.expiresMs == null || record.serverTimeMs != null) && !officialExpired(record)
    }
    // This record was obtained in this controller lifetime. Unlike persisted display history, its
    // monotonic timestamp is valid on desktop too, where the operating-system boot ID is unknown.
    private fun officialExpired(record: TrustedEntitlementCache.Record): Boolean =
        record.expiresMs != null && record.serverTimeMs != null &&
            (record.serverTimeMs >= record.expiresMs ||
                ports.elapsedRealtimeMs() - record.elapsedMs >= record.expiresMs - record.serverTimeMs)
    private fun invalidateSource() {
        savedReentry = null
        generation.incrementAndGet()
        operation = null
        proof = null
        officialSession = null
        savedOfficialHistory = null
        pendingAppreciationOnly = false
        closeAppreciation()
    }
    private fun cancelAppreciationRequest() {
        appreciationClient?.cancelPending()
        appreciationClient = null
    }
    private fun closeAppreciation() {
        appreciationFlow?.close()
        appreciationFlow = null
        appreciationOnly = false
        appreciationLocalMessage = ""
        appreciationSource = null
        appreciationImage = null
        cancelAppreciationRequest()
    }
    private fun snapshot(): SharedConnectionState {
        val current = draft()
        val visibleAccount = officialAccountForDraft()
        val account = officialSession?.takeIf { it.config == visibleAccount }
        val history = savedOfficialHistory?.takeIf { it.first == visibleAccount }
        val record = account?.record ?: history?.second
        val expired = if (account != null) officialExpired(account.record)
            else record?.expired(ports.elapsedRealtimeMs(), ports.bootCount()) == true
        val expiry = record?.let {
            if (it.expiresMs == null) "使用期限未设置"
            else "${if (account == null) "上次确认" else ""}使用期限至 ${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(it.expiresMs))}"
        }.orEmpty()
        val appreciation = appreciationFlow?.view()?.let {
            SharedAppreciationState(it.username ?: it.localUsername, it.entitlementLoaded,
                ManagedAccountFlow.expiry(it), ManagedAccountFlow.accessStatus(it), appreciationLocalMessage.ifEmpty { it.message },
                appreciationImage.takeIf { _ -> it.image != null }, it.busy, it.canRefresh)
        }
        return SharedConnectionState(origin, entry.mode(), current.endpoint, current.username, current.password,
            operation != null, operation != null && operation?.commitStarted != true,
            canEditDraft(), appreciation != null || canEditDraft(),
            account != null, visibleAccount != null, expiry, when {
                expired -> "服务已到期"
                record?.active == false -> "服务未启用"
                record?.expiresMs != null && record.serverTimeMs == null -> "使用期限待确认"
                else -> ""
            },
            if (entry.mode() == ConnectionEntryModel.Mode.OFFICIAL)
                canStart() && appreciation == null && account?.let(::hasOfficialAccess) == true
            else hasProof() || savedDraftForRevalidation() != null, notice, error,
            saved != null || origin == ConnectionEntryModel.Origin.SETTINGS,
            if (expired) "赞赏续期" else "赞赏",
            visibleAccount != null, appreciation,
            recoveryMessage, recoveryLabel)
    }
    private fun deliverCommit() {
        val committed = pendingCommit ?: return
        val target = host ?: return
        if (!foreground.get()) return
        pendingCommit = null
        target.onCommitted(committed)
    }
    private fun publish() {
        state = snapshot()
        if (reportedBusy != state.busy) {
            reportedBusy = state.busy
            host?.onBusyChanged(reportedBusy)
        }
    }
    private fun validUsername(value: String) = value.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}"))
    private fun isOfficial(config: SharedConnectionConfig) = config.official &&
        config.endpoint == TrustedEntitlementCache.OFFICIAL_ENDPOINT && validUsername(config.username)
}
