package io.github.xyz1001.pdsync.android

import androidx.compose.ui.graphics.ImageBitmap
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import java.security.MessageDigest
import javax.imageio.ImageIO
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.text.SimpleDateFormat
import java.util.Date
import pdsync.DiagnosticLog
import pdsync.DiagnosticHttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Controller tests use the production parsers/flow and only in-memory HTTP transports. */
class SharedConnectionControllerTest {
    private class Queue : Executor {
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
    private class Ports : SharedConnectionPorts {
        var savedConnection: SharedConnectionConfig? = null
        var cachePath: java.nio.file.Path? = null
        var probes = 0
        var saves = 0
        var officialSaves = 0
        var boot = 1
        var elapsed = 0L
        var probeResult = true
        var last: SharedConnectionConfig? = null
        var copied: String? = null
        var copyFailure = false
        var onProbe: (() -> Unit)? = null
        var onSave: (() -> Unit)? = null
        var decodedImage: ImageBitmap? = null
        var decodeCalls = 0
        override fun isSavedConnection(config: SharedConnectionConfig) = config == savedConnection
        override fun testConnection(config: SharedConnectionConfig): Boolean {
            probes++
            onProbe?.invoke()
            return probeResult
        }
        override fun saveAndActivate(config: SharedConnectionConfig) {
            onSave?.invoke(); saves++; last = config; savedConnection = config
        }
        override fun saveAuthenticatedOfficial(config: SharedConnectionConfig) {
            officialSaves++
            saveAndActivate(config)
        }
        override fun cacheDirectory() = cachePath
        override fun elapsedRealtimeMs() = elapsed
        override fun bootCount() = boot
        override fun copyUsername(username: String) {
            if (copyFailure) throw IllegalStateException("SECRET clipboard state")
            copied = username
        }
        override fun decodeImage(bytes: ByteArray): ImageBitmap? { decodeCalls++; return decodedImage }
    }
    private class Host : SharedConnectionHost {
        val backs = mutableListOf<ConnectionEntryModel.Back>()
        val commits = mutableListOf<SharedConnectionConfig>()
        val busy = mutableListOf<Boolean>()
        var reloads = 0
        val accountRefreshes = mutableListOf<SharedConnectionConfig>()
        val accountAccess = mutableListOf<Pair<SharedConnectionConfig, Boolean>>()
        val accountEvents = mutableListOf<String>()
        val accountUpdates = mutableListOf<Triple<SharedConnectionConfig, Long?, Long>>()
        override fun onAccountRefreshStarted(config: SharedConnectionConfig) {
            accountRefreshes += config
            accountEvents += "started"
        }
        override fun onAccountAccessChanged(config: SharedConnectionConfig, available: Boolean) {
            accountAccess += config to available
            accountEvents += "available:$available"
        }
        override fun onAccountStatus(config: SharedConnectionConfig, expiresMs: Long?, serverTimeMs: Long) {
            accountUpdates += Triple(config, expiresMs, serverTimeMs)
        }
        override fun onBack(destination: ConnectionEntryModel.Back) { backs += destination }
        override fun onCommitted(config: SharedConnectionConfig) { commits += config }
        override fun onBusyChanged(busy: Boolean) { this.busy += busy }
        override fun onReloadSavedConnection() { reloads++ }
    }
    private class Clients : SharedOfficialClients {
        var authCalls = 0
        var entitlementCalls = 0
        var publicCalls = 0
        var privateConstructs = 0
        var metadataCalls = 0
        var imageCalls = 0
        var publicStatus = 503
        var publicFailures = 0
        var imageStatus = 200
        var imageBytes: ByteArray? = null
        var metadataHash: String? = null
        var active = true
        // Tests use service deadlines; only the HTTP boundary adds the server's extra year.
        var expires = 10000L
        var rawExpiresOverride: Long? = null
        var nullExpiry = false
        var status: Int? = null
        var serverTime: Long? = 1000L
        var entitlementHttpStatus = 200
        var entitlementFailure = false
        var authFailure = false
        var onAuth: (() -> Unit)? = null
        var onEntitlement: (() -> Unit)? = null
        val requests = mutableListOf<Request>()
        override fun authentication() = OfficialServiceRegistrationClient { request ->
            authCalls++
            requests += request
            onAuth?.invoke()
            if (authFailure) throw IOException("SECRET request details")
            response(request, """{"code":200,"message":"ok","data":{"webdav_url":"https://webdav.xyz1001.top/","username":"alice","quota_mb":1,"quota_bytes":1048576,"upload_bandwidth_kb":0,"download_bandwidth_kb":0}}""")
        }
        override fun account(config: SharedConnectionConfig): ManagedAccountClient {
            privateConstructs++
            return ManagedAccountClient(config.endpoint, config.username, config.password, transport())
        }
        override fun publicAppreciation(): ManagedAccountClient = ManagedAccountClient.publicAppreciation(transport())
        private fun transport() = object : ManagedAccountClient.Transport {
            override fun execute(request: Request): Response {
                requests += request
                if (request.url.toString() == ManagedAccountClient.ENTITLEMENT_URL) {
                    entitlementCalls++
                    onEntitlement?.invoke()
                    if (entitlementFailure) return response(request, "{}", 401)
                    if (entitlementHttpStatus != 200) return response(request, "{}", entitlementHttpStatus)
                    val rawExpiry = if (nullExpiry) null else rawExpiresOverride ?: if (expires == 0L) 0L else
                        Instant.ofEpochMilli(expires).atZone(ZoneOffset.UTC).plusYears(1).toInstant().toEpochMilli()
                    val serverClock = serverTime?.let { ",\"server_time_ms\":$it" }.orEmpty()
                    val explicitStatus = status?.let { ",\"status\":$it" }.orEmpty()
                    return response(request, """{"code":200,"data":{"source":"managed_ledger","username":"alice","expires_ms":$rawExpiry,"active":$active$serverClock,"managed":true$explicitStatus}}""")
                }
                publicCalls++
                if (request.url.toString() == ManagedAccountClient.APPRECIATION_URL) {
                    metadataCalls++
                    if (publicFailures > 0) { publicFailures--; return response(request, "{}", 503) }
                    if (publicStatus != 200) return response(request, "{}", publicStatus)
                    val hash = metadataHash ?: MessageDigest.getInstance("SHA-256").digest(imageBytes!!)
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    return response(request, """{"code":200,"data":{"version":1,"payment_mode":"donation","image_path":"${ManagedAccountClient.IMAGE_PATH}","image_sha256":"$hash","term":"calendar_year","remark":"username"}}""")
                }
                imageCalls++
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(imageStatus).message("fixture")
                    .body(imageBytes!!.toResponseBody("image/png".toMediaType())).build()
            }
        }
        companion object {
            fun response(request: Request, json: String, status: Int = 200) = Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }
    }
    private class Fixture(existing: SharedConnectionConfig? = null,
                          origin: ConnectionEntryModel.Origin = ConnectionEntryModel.Origin.SETUP,
                          persisted: SharedConnectionConfig? = null,
                          cached: TrustedEntitlementCache.Record? = null,
                          cachedVersion: Int? = null) {
        val io = Queue()
        val ui = Queue()
        val ports = Ports().apply {
            savedConnection = persisted
            if (cached != null) {
                cachePath = Files.createTempDirectory("shared-account-cache-")
                TrustedEntitlementCache(cachePath).write(cached)
                if (cachedVersion != null) Files.list(cachePath).use { paths ->
                    val file = paths.findFirst().orElseThrow()
                    val bytes = Files.readAllBytes(file)
                    java.nio.ByteBuffer.wrap(bytes).putInt(cachedVersion)
                    Files.write(file, bytes)
                }
            }
        }
        val clients = Clients()
        val host = Host()
        val controller = SharedConnectionController(origin, existing, "slot", ports, io, ui, clients)
        init { controller.attach(host); controller.foreground(true) }
        fun finish() { io.drain(); ui.drain() }
        fun enableImage(width: Int = 1) {
            clients.publicStatus = 200
            clients.imageBytes = ByteArrayOutputStream().use { out ->
                ImageIO.write(BufferedImage(width, 1, BufferedImage.TYPE_INT_ARGB), "png", out)
                out.toByteArray()
            }
            ports.decodedImage = ImageBitmap(width, 1)
        }
        fun official() {
            controller.choose(ConnectionEntryModel.Mode.OFFICIAL)
            controller.editUsername("alice")
            controller.editPassword("secret-password")
        }
        fun custom() {
            controller.choose(ConnectionEntryModel.Mode.CUSTOM)
            controller.editEndpoint("https://dav.example.test/")
            controller.editUsername("custom-user")
            controller.editPassword("")
        }
    }

    @Test fun newSetupUsesChooserAndBackVisitsChooserThenPath() {
        val f = Fixture()
        assertEquals(ConnectionEntryModel.Mode.CHOICE, f.controller.state.mode)
        f.custom()
        f.controller.back()
        assertEquals(ConnectionEntryModel.Mode.CHOICE, f.controller.state.mode)
        assertTrue(f.host.backs.isEmpty())
        f.controller.back()
        assertEquals(listOf(ConnectionEntryModel.Back.PATH), f.host.backs)
        assertEquals(0, f.clients.authCalls)
    }

    @Test fun savedSetupStartsAtItsFormAndOffersChangeService() {
        val saved = SharedConnectionConfig("https://dav.example.test/", "saved", "", "slot", false)
        val f = Fixture(saved)
        assertEquals(ConnectionEntryModel.Mode.CUSTOM, f.controller.state.mode)
        assertTrue(f.controller.state.showChangeService)
        assertFalse(f.controller.state.canContinue)
        f.controller.showChooser()
        f.controller.choose(ConnectionEntryModel.Mode.CUSTOM)
        assertEquals("saved", f.controller.state.username)
    }

    @Test fun settingsBackLeavesForSettings() {
        val f = Fixture(origin = ConnectionEntryModel.Origin.SETTINGS)
        f.controller.back()
        assertEquals(listOf(ConnectionEntryModel.Back.SETTINGS), f.host.backs)
    }

    @Test fun separateDraftsSurviveServiceSwitchWithoutKeepingProof() {
        val f = Fixture()
        f.custom()
        f.controller.testConnection(); f.finish()
        assertTrue(f.controller.state.canContinue)
        f.controller.showChooser(); f.official()
        f.controller.showChooser(); f.controller.choose(ConnectionEntryModel.Mode.CUSTOM)
        assertEquals("custom-user", f.controller.state.username)
        assertEquals("", f.controller.state.password)
        assertFalse(f.controller.state.canContinue)
        f.controller.showChooser(); f.controller.choose(ConnectionEntryModel.Mode.OFFICIAL)
        assertEquals("alice", f.controller.state.username)
    }

    @Test fun customEmptyPasswordCanTestButOnlyExplicitContinueSaves() {
        val f = Fixture(); f.custom()
        f.controller.testConnection(); f.finish()
        assertTrue(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves)
        f.controller.continueConnection(); f.finish()
        assertEquals(2, f.ports.probes)
        assertEquals(1, f.ports.saves)
        assertEquals("", f.ports.last!!.password)
        assertEquals(1, f.host.commits.size)
    }

    @Test fun continueWithoutProofDoesNotProbeOrSave() {
        val f = Fixture(); f.custom()
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
    }

    @Test fun editInvalidatesExactSourceProof() {
        val f = Fixture(); f.custom()
        f.controller.testConnection(); f.finish()
        f.controller.editPassword("changed")
        assertFalse(f.controller.state.canContinue)
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.saves)
    }

    @Test fun customTestAllowsFieldEditAndRejectsItsQueuedLateResult() {
        val edits: List<(SharedConnectionController) -> Unit> = listOf(
            { it.editEndpoint("https://changed.example.test/") },
            { it.editUsername("changed-user") },
            { it.editPassword("changed-password") },
        )
        edits.forEach { edit ->
            val f = Fixture(); f.custom()
            f.controller.testConnection(); f.io.drain()
            assertTrue(f.controller.state.busy)
            assertTrue(f.controller.state.canEdit)
            assertTrue(f.controller.state.canGoBack)
            edit(f.controller)
            assertFalse(f.controller.state.busy)
            f.ui.drain()
            assertFalse(f.controller.state.canContinue)
            assertEquals("", f.controller.state.notice)
            f.controller.continueConnection(); f.finish()
            assertEquals(0, f.ports.saves)
        }
    }

    @Test fun backDuringCustomTestPreservesDraftAndRejectsLateProof() {
        val f = Fixture(); f.custom(); f.controller.editPassword("kept-password")
        f.controller.testConnection(); f.io.drain(); f.controller.back(); f.ui.drain()
        assertEquals(ConnectionEntryModel.Mode.CHOICE, f.controller.state.mode)
        assertFalse(f.controller.state.busy)
        assertFalse(f.controller.state.canContinue)
        f.controller.choose(ConnectionEntryModel.Mode.CUSTOM)
        assertEquals("https://dav.example.test/", f.controller.state.endpoint)
        assertEquals("custom-user", f.controller.state.username)
        assertEquals("kept-password", f.controller.state.password)
        assertFalse(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves)
    }

    @Test fun backBeforeCustomProbeDispatchPreventsProbeAndLeavesSavedSettingsDirectly() {
        val saved = SharedConnectionConfig("https://dav.example.test/", "saved", "", "slot", false)
        val f = Fixture(saved, ConnectionEntryModel.Origin.SETTINGS)
        f.controller.testConnection(); f.controller.back(); f.finish()
        assertEquals(listOf(ConnectionEntryModel.Back.SETTINGS), f.host.backs)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertFalse(f.controller.state.canContinue)
    }

    @Test fun authenticationAndRegistrationDoNotBecomeEditableOrNavigable() {
        listOf(false, true).forEach { register ->
            val f = Fixture(); f.official()
            if (register) f.controller.register() else f.controller.login()
            assertFalse(f.controller.state.canEdit)
            assertFalse(f.controller.state.canGoBack)
            f.controller.back()
            assertEquals("alice", f.controller.state.username)
            assertEquals("secret-password", f.controller.state.password)
            assertTrue(f.controller.state.busy)
            assertTrue(f.host.backs.isEmpty())
            f.finish()
            assertEquals(1, f.clients.authCalls)
        }
    }

    @Test fun applyingConfigurationRemainsLockedBeforeAndDuringDurableWrite() {
        val f = Fixture(); f.custom(); f.controller.testConnection(); f.finish()
        f.controller.continueConnection()
        fun assertLocked() {
            assertFalse(f.controller.state.canEdit)
            assertFalse(f.controller.state.canGoBack)
            f.controller.editUsername("ignored")
            f.controller.back()
            assertEquals("custom-user", f.controller.state.username)
            assertTrue(f.host.backs.isEmpty())
        }
        assertLocked()
        f.ports.onSave = { assertLocked() }
        f.finish()
        assertEquals(1, f.ports.saves)
        assertEquals(1, f.host.commits.size)
    }

    @Test fun officialLoginAndNextUseAuthenticatedAccountAccessWithoutDavProbe() {
        val f = Fixture(); f.official()
        assertFalse(f.controller.state.officialAuthenticated)
        assertFalse(f.controller.state.canAppreciate)
        f.controller.login(); f.finish()
        assertEquals(1, f.clients.authCalls)
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertTrue(f.controller.state.officialAuthenticated)
        assertTrue(f.controller.state.officialExpiry.startsWith("使用期限至 "))
        assertTrue(f.controller.state.canAppreciate)
        assertTrue(f.controller.state.canContinue)
        f.controller.continueConnection(); f.controller.continueConnection(); f.finish()
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(1, f.ports.officialSaves)
        assertEquals(1, f.ports.saves)
        assertEquals(1, f.host.commits.size)
        assertTrue(f.ports.last!!.official)
    }

    @Test fun officialCredentialsWithoutAuthenticationCannotCompleteConfiguration() {
        val f = Fixture(); f.official()
        assertFalse(f.controller.state.officialAuthenticated)
        assertFalse(f.controller.state.canContinue)
        assertNull(f.ports.savedConnection)
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.clients.authCalls)
        assertEquals(0, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertTrue(f.host.commits.isEmpty())
    }

    @Test fun officialAuthenticationNeverPersistsCredentialsWithoutExplicitCompletion() {
        for (register in listOf(false, true)) for (kind in listOf("active", "expired", "unknown", "no-clock")) {
            val f = Fixture(); f.official()
            f.clients.active = kind != "expired" && kind != "unknown"
            f.clients.serverTime = when (kind) { "expired" -> 10001L; "no-clock" -> null; else -> 1000L }
            f.clients.nullExpiry = kind == "unknown"
            if (register) f.controller.register() else f.controller.login()
            f.finish()
            assertTrue(f.controller.state.officialAuthenticated, kind)
            assertTrue(f.controller.state.canContinue, kind)
            f.controller.detach(); f.controller.attach(f.host); f.controller.foreground(true); f.finish()
            assertTrue(f.controller.state.canContinue, kind)
            assertEquals("alice", f.controller.state.username, kind)
            assertEquals("secret-password", f.controller.state.password, kind)
            assertEquals(1, f.clients.authCalls, kind)
            assertEquals(1, f.clients.entitlementCalls, kind)
            assertEquals(0, f.ports.officialSaves, kind)
            assertEquals(0, f.ports.saves, kind)
            assertNull(f.ports.savedConnection, kind)
            assertTrue(f.host.commits.isEmpty(), kind)
            f.controller.close()
        }
    }

    @Test fun doubleClickRegistrationAndReattachmentNeverDuplicateRequest() {
        val f = Fixture(); f.official()
        f.controller.register(); f.controller.register()
        f.controller.detach()
        val replacement = Host()
        f.controller.attach(replacement); f.controller.foreground(true)
        f.controller.register()
        f.finish()
        assertEquals(1, f.clients.authCalls)
        assertEquals(0, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertFalse(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves)
    }

    @Test fun inactiveOfficialAccountNeverContactsDavOrSaves() {
        val f = Fixture(); f.official(); f.clients.active = false; f.clients.status = 0
        f.controller.login(); f.finish()
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertFalse(f.controller.state.canContinue)
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.saves)
    }

    @Test fun explicitDisabledStatusTakesPrecedenceOverElapsedAndUnknownExpiry() {
        for (unknown in listOf(false, true)) {
            val f = Fixture(); f.official()
            f.clients.status = 0; f.clients.active = false
            f.clients.serverTime = 10001L; f.clients.nullExpiry = unknown
            f.controller.login(); f.finish()
            assertTrue(f.controller.state.officialAuthenticated)
            assertEquals("服务未启用", f.controller.state.officialAccessStatus)
            assertFalse(f.controller.state.officialExpired)
            assertFalse(f.controller.state.canContinue)
            f.controller.continueConnection(); f.finish()
            assertEquals(0, f.ports.probes); assertEquals(0, f.ports.saves)
            f.controller.close()
        }
    }

    @Test fun authenticatedAccountWithMissingClockOrUnknownDeadlineCanCompleteConfiguration() {
        for (kind in listOf("finite-no-clock", "zero-no-clock", "null-no-clock", "null-unconfirmed")) {
            val f = Fixture(); f.official()
            f.clients.serverTime = if (kind == "null-unconfirmed") 10001L else null
            f.clients.expires = if (kind == "zero-no-clock") 0L else 10000L
            f.clients.nullExpiry = kind.startsWith("null")
            f.clients.active = kind != "null-unconfirmed"
            f.controller.login(); f.finish()
            assertTrue(f.controller.state.officialAuthenticated, kind)
            assertEquals("使用期限待确认", f.controller.state.officialAccessStatus, kind)
            assertFalse(f.controller.state.officialExpired, kind)
            assertTrue(f.controller.state.canContinue, kind)
            if (kind == "null-unconfirmed") assertEquals("使用期限待确认", f.controller.state.officialExpiry)
            assertEquals(0, f.ports.saves, "Authentication alone must not save: $kind")
            assertNull(f.ports.savedConnection, kind)
            f.controller.continueConnection(); f.finish()
            assertEquals(0, f.ports.probes, kind)
            assertEquals(1, f.ports.officialSaves, kind)
            assertEquals(1, f.ports.saves, kind)
            assertEquals(1, f.host.commits.size, kind)
            f.controller.close()
        }
    }

    @Test fun bothConfirmedUnlimitedShapesRemainUsableWithServerClock() {
        for (nullExpiry in listOf(false, true)) {
            val f = Fixture(); f.official()
            f.clients.expires = 0L; f.clients.nullExpiry = nullExpiry
            f.controller.login(); f.finish()
            assertTrue(f.controller.state.canContinue)
            assertFalse(f.controller.state.officialExpired)
            assertEquals("", f.controller.state.officialAccessStatus)
            f.controller.close()
        }
    }

    @Test fun versionOneRawExpiryCacheIsNotShownWhileAuthenticationIsUnavailable() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val raw = TrustedEntitlementCache.Record(saved.endpoint, saved.username, 1_830_000_000_000L, true, 1000L, 0, 1)
        val f = Fixture(saved, persisted = saved, cached = raw, cachedVersion = 1)
        f.clients.entitlementFailure = true; f.finish()
        assertFalse(f.controller.state.showOfficialAccount)
        assertEquals("", f.controller.state.officialExpiry)
        assertEquals("", f.controller.state.officialAccessStatus)
        assertFalse(f.controller.state.canContinue)
        f.controller.close()
    }

    @Test fun unavailableDavDoesNotGateBuiltInAccountActivationOrCreateDavProof() {
        val f = Fixture(); f.official(); f.ports.probeResult = false
        f.controller.login(); f.finish()
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertTrue(f.controller.state.canContinue)
        f.controller.testConnection(); f.finish()
        assertEquals(0, f.ports.probes)
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.ports.officialSaves)
    }

    @Test fun backgroundAfterAuthenticationPreventsEntitlementDavAndSave() {
        val f = Fixture(); f.official()
        f.clients.onAuth = { f.controller.foreground(false) }
        f.controller.register(); f.finish()
        assertEquals(1, f.clients.authCalls)
        assertEquals(0, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        f.controller.foreground(true)
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.saves)
    }

    @Test fun backgroundAfterEntitlementPreventsDav() {
        val f = Fixture(); f.official()
        f.clients.onEntitlement = { f.controller.foreground(false) }
        f.controller.login(); f.finish()
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertFalse(f.controller.state.canContinue)
    }

    @Test fun backgroundAfterDavDoesNotRestoreProofOnReturn() {
        val f = Fixture(); f.custom()
        f.ports.onProbe = { f.controller.foreground(false) }
        f.controller.testConnection(); f.finish()
        f.controller.foreground(true)
        assertFalse(f.controller.state.canContinue)
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.saves)
    }

    @Test fun cancelBeforeDispatchPreventsAnyRegistrationRequest() {
        val f = Fixture(); f.official()
        f.controller.register(); f.controller.cancel(); f.finish()
        assertEquals(0, f.clients.authCalls)
        assertFalse(f.controller.state.canContinue)
        assertTrue(f.controller.state.notice.contains("注册结果未确认"))
    }

    @Test fun cancelledLateLoginCannotOverwriteDifferentAccount() {
        val f = Fixture(); f.official()
        f.controller.login(); f.io.drain()
        f.controller.cancel()
        f.controller.editUsername("bob")
        f.ui.drain()
        assertEquals("bob", f.controller.state.username)
        assertFalse(f.controller.state.canContinue)
        assertFalse(f.controller.state.notice.contains("连接成功"))
        assertEquals(0, f.ports.saves)
    }

    @Test fun queuedLateResultCannotGrantProofToDifferentService() {
        val f = Fixture(); f.official()
        f.controller.login(); f.io.drain()
        f.controller.cancel(); f.controller.showChooser(); f.custom(); f.ui.drain()
        assertEquals(ConnectionEntryModel.Mode.CUSTOM, f.controller.state.mode)
        assertFalse(f.controller.state.canContinue)
    }

    @Test fun unknownRegistrationOutcomeRetainsGuidanceAndNeverRetries() {
        val f = Fixture(); f.official(); f.clients.authFailure = true
        f.controller.register(); f.finish()
        assertTrue(f.controller.state.notice.contains("注册结果未确认"))
        assertFalse(f.controller.state.notice.contains("SECRET"))
        f.controller.detach(); f.controller.attach(Host()); f.controller.foreground(true); f.finish()
        assertEquals(1, f.clients.authCalls)
        assertFalse(f.controller.state.canContinue)
    }

    @Test fun failedOrCanceledRefreshCannotEnableHomeFromOldActiveMetadata() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish(); f.enableImage()
        val oldActive = TrustedEntitlementCache.Record(ManagedAccountClient.DAV_URL, "alice", 10000L,
            true, 1000L, 0L, 1)
        fun available() = TrustedEntitlementCache.currentAccessAvailable(oldActive,
            ManagedAccountClient.DAV_URL, "alice", 0L, 1)
        assertTrue(available())
        f.controller.openAppreciation()
        assertFalse(available(), "Pending refresh must not reuse old active eligibility")
        f.finish(); assertTrue(available())
        f.clients.entitlementHttpStatus = 503
        f.controller.refreshAppreciation(); f.finish()
        assertFalse(available(), "Network failure keeps old expiry but not button eligibility")
        f.clients.entitlementHttpStatus = 200
        f.controller.refreshAppreciation(); f.finish()
        assertTrue(available(), "Only a new authenticated result clears uncertainty")
        f.controller.refreshAppreciation(); f.io.drain(); f.controller.back(); f.ui.drain()
        assertFalse(available(), "Late canceled results cannot restore button eligibility")
    }

    @Test fun manualRefreshUsesOneReadAndOnlyNewEntitlementRestoresExpiredAccess() {
        val f = Fixture(); f.official(); f.clients.expires = 500L
        f.controller.login(); f.finish(); f.enableImage()
        f.controller.openInlineAppreciation(); f.finish()
        assertTrue(f.controller.state.officialExpired)
        assertEquals(listOf(false), f.host.accountAccess.map { it.second })
        val before = f.clients.entitlementCalls
        val starts = f.host.accountRefreshes.size
        repeat(5) { f.controller.refreshAppreciation() }
        assertEquals(starts + 1, f.host.accountRefreshes.size, "Duplicate clicks cannot start another host refresh")
        assertEquals(listOf(false), f.host.accountAccess.map { it.second }, "Pending work cannot report renewal")
        assertTrue(f.controller.state.appreciation!!.busy)
        assertFalse(f.controller.state.appreciation!!.canRefresh)
        assertTrue(f.controller.state.officialExpired, "Starting a refresh is not renewal")
        f.clients.expires = 5000L
        f.finish()
        assertEquals(before + 1, f.clients.entitlementCalls)
        assertFalse(f.controller.state.officialExpired)
        assertEquals(5000L, f.host.accountUpdates.last().second)
        assertEquals(listOf(false, true), f.host.accountAccess.map { it.second })
        assertTrue(f.controller.state.officialAuthenticated)
        assertEquals(1, f.clients.authCalls)
        assertEquals(0, f.ports.saves)
        assertEquals(0, f.ports.probes)
        assertTrue(f.clients.requests.filter { it.url.toString() == ManagedAccountClient.ENTITLEMENT_URL }
            .all { it.method == "GET" && it.header("Authorization")?.startsWith("Basic ") == true })
        // A further read normalizes fresh raw server data once, rather than subtracting again.
        val expiry = f.controller.state.officialExpiry
        f.controller.refreshAppreciation(); f.finish()
        assertEquals(expiry, f.controller.state.officialExpiry)
        assertEquals(listOf(false, true, true), f.host.accountAccess.map { it.second })
        f.clients.expires = 500L
        f.controller.refreshAppreciation(); f.finish()
        assertTrue(f.controller.state.officialExpired)
        assertEquals(listOf(false, true, true, false), f.host.accountAccess.map { it.second })
        assertTrue(f.host.accountAccess.all { it.first.username == "alice" && it.first.password == "secret-password" })
    }

    @Test fun failedManualRefreshNeverChangesKnownExpiryOrGrantsExpiredAccountAccess() {
        for (initialExpiry in listOf(500L, 5000L)) {
            val f = Fixture(); f.official(); f.clients.expires = initialExpiry
            f.controller.login(); f.finish(); f.enableImage()
            f.controller.openAppreciation(); f.finish()
            val old = f.controller.state.officialExpiry
            val before = f.host.accountAccess.size
            f.clients.entitlementHttpStatus = 503
            f.controller.refreshAppreciation(); f.finish()
            assertEquals(old, f.controller.state.officialExpiry)
            assertEquals(initialExpiry == 500L, f.controller.state.officialExpired)
            assertTrue(f.controller.state.appreciation!!.message.isNotBlank())
            assertFalse(f.controller.state.appreciation!!.busy)
            assertTrue(f.controller.state.appreciation!!.canRefresh)
            assertEquals(before + 1, f.host.accountAccess.size)
            assertFalse(f.host.accountAccess.last().second, "Failure cannot preserve earlier active scheduling permission")
            assertEquals(0, f.ports.saves)
            assertEquals(0, f.ports.probes)
        }
    }

    @Test fun manualRefreshLateRenewalCannotOverwriteBackAccountChangeOrClose() {
        for (exit in listOf("back", "account", "close")) {
            val f = Fixture(); f.official(); f.clients.expires = 500L
            f.controller.login(); f.finish(); f.enableImage()
            f.controller.openAppreciation(); f.finish()
            f.clients.expires = 5000L
            val updates = f.host.accountUpdates.size
            val access = f.host.accountAccess.size
            f.controller.refreshAppreciation(); f.io.drain()
            when (exit) {
                "back" -> f.controller.back()
                "account" -> f.controller.editUsername("bob")
                else -> f.controller.close()
            }
            f.ui.drain()
            assertNull(f.controller.state.appreciation)
            assertEquals(updates, f.host.accountUpdates.size, "Late $exit response cannot update host status")
            assertEquals(access, f.host.accountAccess.size, "Late $exit response cannot queue host history reads")
            if (exit == "back") assertTrue(f.controller.state.officialExpired)
            if (exit == "account") assertFalse(f.controller.state.officialAuthenticated)
            assertEquals(0, f.ports.saves)
            assertEquals(0, f.ports.probes)
        }
    }

    @Test fun accountReadNotificationRequiresFreshUsableEntitlementNotJustFutureExpiryOrQr() {
        for (kind in listOf("active", "expired", "inactive", "unknown", "no-clock", "unlimited", "failure")) {
            val f = Fixture(); f.official(); f.controller.login(); f.finish(); f.enableImage()
            when (kind) {
                "expired" -> { f.clients.expires = 500L; f.clients.active = false }
                "inactive" -> { f.clients.status = 0; f.clients.active = false }
                "unknown" -> { f.clients.nullExpiry = true; f.clients.active = false }
                "no-clock" -> f.clients.serverTime = null
                "unlimited" -> f.clients.expires = 0L
                "failure" -> f.clients.entitlementHttpStatus = 503
            }
            f.controller.openAppreciation()
            assertEquals(listOf("started"), f.host.accountEvents, kind)
            f.finish()
            val expected = kind == "active" || kind == "unlimited"
            assertEquals(listOf(expected), f.host.accountAccess.map { it.second }, kind)
            assertEquals(listOf("started", "available:$expected"), f.host.accountEvents, kind)
            assertEquals(0, f.ports.saves, kind)
            assertEquals(0, f.ports.probes, kind)
            assertTrue(f.clients.requests.none { it.url.encodedPath.contains("/dav/") }, kind)
            f.controller.close()
        }
    }

    @Test fun detachedRenewalResultIsDeliveredOnceToReplacementHostWithoutRestartingRefresh() {
        for (available in listOf(true, false)) {
            val f = Fixture(); f.official(); f.clients.expires = 500L
            f.controller.login(); f.finish(); f.enableImage()
            f.controller.openAppreciation(); f.finish()
            val previous = f.host.accountAccess.size
            f.clients.expires = 5000L
            if (!available) f.clients.entitlementHttpStatus = 503
            f.controller.refreshAppreciation()
            f.controller.detach()
            f.finish()
            assertEquals(previous, f.host.accountAccess.size, "Detached host cannot receive a result")
            val replacement = Host()
            f.controller.attach(replacement); f.controller.foreground(true); f.finish()
            assertEquals(listOf(available), replacement.accountAccess.map { it.second })
            assertEquals(listOf("available:$available"), replacement.accountEvents,
                "The retained host queue already received the original refresh start")
            val requests = f.clients.requests.size
            repeat(3) {
                f.controller.detach(); f.controller.attach(replacement); f.controller.foreground(true); f.finish()
            }
            assertEquals(listOf(available), replacement.accountAccess.map { it.second }, "Reattachment cannot replay a consumed result")
            assertEquals(requests, f.clients.requests.size, "Rotation cannot make another account or DAV request")
            assertEquals(0, f.ports.probes)
            assertEquals(0, f.ports.saves)
            f.controller.close()
        }
    }

    @Test fun staleDetachedRenewalCannotSurviveAccountChangeOrControllerClose() {
        for (exit in listOf("account", "close")) {
            val f = Fixture(); f.official(); f.clients.expires = 500L
            f.controller.login(); f.finish(); f.enableImage()
            f.controller.openAppreciation(); f.finish()
            f.clients.expires = 5000L
            f.controller.refreshAppreciation(); f.controller.detach(); f.finish()
            if (exit == "account") f.controller.editUsername("bob") else f.controller.close()
            val replacement = Host()
            f.controller.attach(replacement); f.controller.foreground(true); f.finish()
            assertTrue(replacement.accountAccess.isEmpty(), "Stale detached $exit result cannot revive a queue")
            assertTrue(replacement.accountRefreshes.isEmpty(), "Stale detached $exit request cannot reach a new owner")
            assertEquals(0, f.ports.probes)
            assertEquals(0, f.ports.saves)
            f.controller.close()
        }
    }

    @Test fun unverifiedAccountCannotOpenAppreciationOrReadPrivateMetadata() {
        val f = Fixture(); f.official()
        f.controller.openAppreciation(); f.finish()
        assertFalse(f.controller.state.canAppreciate)
        assertNull(f.controller.state.appreciation)
        assertEquals(0, f.clients.publicCalls)
        assertEquals(0, f.clients.privateConstructs)
        assertEquals(0, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
    }

    @Test fun backgroundDoesNotCancelAnAlreadyRequestedReadOrRefetchOnReturn() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish(); f.enableImage()
        f.controller.openAppreciation()
        f.controller.foreground(false); f.finish()
        assertEquals("alice", f.controller.state.appreciation!!.username)
        assertTrue(f.controller.state.appreciation!!.authenticated)
        assertNotNull(f.controller.state.appreciation!!.image)
        assertFalse(f.controller.state.appreciation!!.busy)
        val requests = f.clients.requests.size
        f.controller.copyUsername()
        assertNull(f.ports.copied, "inactive Android page cannot initiate a clipboard action")
        f.controller.foreground(true); f.finish()
        assertEquals(requests, f.clients.requests.size)
        assertEquals(1, f.clients.metadataCalls)
    }

    @Test fun saveAccessorReturnsOnlyCurrentDisplayedOriginalAndSurvivesChooserBackground() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish()
        assertNull(f.controller.currentAppreciationPng())
        assertNull(f.controller.currentAppreciationScope())
        f.enableImage(); f.controller.openAppreciation(); f.finish()
        val source = assertNotNull(f.controller.currentAppreciationScope())
        val original = assertNotNull(f.controller.currentAppreciationPng())
        kotlin.test.assertContentEquals(f.clients.imageBytes, original)
        original.fill(0)
        kotlin.test.assertContentEquals(f.clients.imageBytes, f.controller.currentAppreciationPng())
        val calls = f.clients.requests.size
        f.controller.foreground(false)
        assertSame(source, f.controller.currentAppreciationScope())
        kotlin.test.assertContentEquals(f.clients.imageBytes, f.controller.currentAppreciationPng())
        assertEquals(calls, f.clients.requests.size, "a save accessor never requests another image")
        f.controller.foreground(true); f.finish()
        assertSame(source, f.controller.currentAppreciationScope())
        f.enableImage(width = 2); f.clients.imageStatus = 503
        f.controller.refreshAppreciation(); f.finish()
        assertNull(f.controller.currentAppreciationPng(), "known changed but unavailable image cannot be saved")
        f.controller.back()
        assertNull(f.controller.currentAppreciationPng())
        assertNull(f.controller.currentAppreciationScope())
    }

    @Test fun appreciationRetainsIdentityExpiryAndImageAcrossRotationWithoutAnyNetwork() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish(); f.enableImage()
        f.controller.openAppreciation(); f.finish()
        val before = f.controller.state.appreciation!!
        val requests = f.clients.requests.size
        f.clients.publicStatus = 503; f.clients.entitlementFailure = true
        repeat(4) {
            f.controller.detach()
            assertEquals(before.copy(canRefresh = false), f.controller.state.appreciation)
            f.controller.attach(Host()); f.controller.foreground(true); f.finish()
            assertEquals(before, f.controller.state.appreciation)
        }
        assertEquals(requests, f.clients.requests.size)
        f.controller.back()
        assertTrue(f.controller.state.canContinue)
        // A later explicit page entry is a natural read, and actual denial revokes access.
        f.controller.openAppreciation(); f.finish(); f.controller.back()
        assertFalse(f.controller.state.canContinue)
    }

    @Test fun sameHashReusesImageAndRepeatedForegroundOrOpenDoesNotRefetch() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish(); f.enableImage()
        f.controller.openAppreciation(); f.finish()
        val image = f.controller.state.appreciation!!.image
        f.controller.foreground(false); f.controller.foreground(true); f.finish()
        assertSame(image, f.controller.state.appreciation!!.image)
        assertEquals(1, f.clients.metadataCalls)
        assertEquals(1, f.clients.imageCalls)
        assertEquals(1, f.ports.decodeCalls)
        repeat(4) {
            f.controller.attach(Host()); f.controller.foreground(true)
            f.controller.openAppreciation(); f.controller.refreshAccountStatus()
        }
        f.finish()
        assertEquals(1, f.clients.metadataCalls)
        assertEquals(1, f.clients.imageCalls)
        f.controller.refreshAppreciation(); f.finish()
        assertEquals(2, f.clients.metadataCalls)
        assertEquals(1, f.clients.imageCalls, "An explicit same-hash read reuses the validated PNG")
    }

    @Test fun changedServerHashInvalidatesOldImageEvenWhenReplacementFails() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish(); f.enableImage()
        f.controller.openAppreciation(); f.finish()
        assertNotNull(f.controller.state.appreciation!!.image)
        f.enableImage(width = 2); f.clients.imageStatus = 503
        f.controller.refreshAppreciation(); f.finish()
        assertNull(f.controller.state.appreciation!!.image)
        assertEquals(3, f.clients.imageCalls, "Only the missing replacement gets one retry")
        assertFalse(f.controller.state.appreciation!!.busy)
        assertFalse(f.controller.state.appreciation!!.message.contains("刷新"))
        f.clients.imageStatus = 200
        f.controller.refreshAppreciation(); f.finish()
        assertSame(f.ports.decodedImage, f.controller.state.appreciation!!.image)
    }

    @Test fun imageCacheAndLateResultsNeverCrossAccountChanges() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish(); f.enableImage()
        f.controller.openAppreciation(); f.io.drain()
        f.controller.editUsername("bob"); f.ui.drain()
        assertNull(f.controller.state.appreciation)
        assertFalse(f.controller.state.canAppreciate)
        assertFalse(f.controller.state.canContinue)
        f.controller.editUsername("alice"); f.controller.editPassword("secret-password")
        f.controller.login(); f.finish()
        f.controller.openAppreciation(); f.finish()
        assertNotNull(f.controller.state.appreciation!!.image)
        assertEquals(2, f.clients.imageCalls, "A different account/draft cannot inherit the old public cache")
    }

    @Test fun missingImageRetriesOnceWithoutRepeatingPrivateOrAuthenticationRequests() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish(); f.enableImage()
        val privateBefore = f.clients.entitlementCalls
        f.clients.publicFailures = 1
        f.controller.openAppreciation(); f.finish()
        assertNotNull(f.controller.state.appreciation!!.image)
        assertEquals(2, f.clients.metadataCalls)
        assertEquals(1, f.clients.imageCalls)
        assertEquals(privateBefore + 1, f.clients.entitlementCalls)
        assertEquals(1, f.clients.authCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertTrue(f.clients.requests.filter { it.url.toString() != ManagedAccountClient.ENTITLEMENT_URL &&
            it.url.toString() in listOf(ManagedAccountClient.APPRECIATION_URL, ManagedAccountClient.IMAGE_URL) }
            .all { it.header("Authorization") == null })
    }

    @Test fun persistentImageFailureStopsAfterTwoReadsAndNeverPolls() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish()
        val privateBefore = f.clients.entitlementCalls
        f.controller.openAppreciation(); f.finish()
        assertEquals(2, f.clients.metadataCalls)
        assertEquals(privateBefore + 1, f.clients.entitlementCalls)
        assertNull(f.controller.state.appreciation!!.image)
        assertFalse(f.controller.state.appreciation!!.busy)
        assertEquals("赞赏码暂时无法加载。", f.controller.state.appreciation!!.message)
        repeat(5) { f.controller.refreshAccountStatus(); f.controller.foreground(true); f.finish() }
        assertEquals(2, f.clients.metadataCalls)
    }

    @Test fun rejectedOrInvalidImagesAreNotRetried() {
        for (status in listOf(401, 403, 404, 429)) {
            val f = Fixture(); f.official(); f.controller.login(); f.finish()
            f.clients.publicStatus = status
            f.controller.openAppreciation(); f.finish()
            assertEquals(1, f.clients.metadataCalls, "HTTP $status must not be repeated")
            assertNull(f.controller.state.appreciation!!.image)
            assertFalse(f.controller.state.appreciation!!.busy)
        }
        val f = Fixture(); f.official(); f.controller.login(); f.finish(); f.enableImage()
        f.clients.metadataHash = "0".repeat(64)
        f.controller.openAppreciation(); f.finish()
        assertEquals(1, f.clients.metadataCalls)
        assertEquals(1, f.clients.imageCalls)
        assertEquals(0, f.ports.decodeCalls, "Integrity failures never reach the platform decoder")
        assertNull(f.controller.state.appreciation!!.image)
        f.controller.back(); f.clients.metadataHash = null; f.ports.decodedImage = null
        f.controller.openAppreciation(); f.finish()
        assertEquals(2, f.clients.imageCalls, "A decode failure is not retried")
        assertNull(f.controller.state.appreciation!!.image)
        assertFalse(f.controller.state.appreciation!!.busy)
    }

    @Test fun appreciationOnlyAuthenticatesSavedAccountThenBackReturnsToSettings() {
        val existing = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val f = Fixture(existing, ConnectionEntryModel.Origin.SETTINGS, persisted = existing)
        f.controller.openAppreciationOnly(); f.finish()
        assertTrue(f.controller.state.officialAuthenticated)
        assertTrue(f.controller.state.appreciation != null)
        f.controller.back(); f.finish()
        assertEquals(listOf(ConnectionEntryModel.Back.SETTINGS), f.host.backs)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
    }

    @Test fun inlineAppreciationWaitsForSavedAuthenticationAndRepeatedEntryDoesNotRepeatReads() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val f = Fixture(saved, ConnectionEntryModel.Origin.SETTINGS, persisted = saved)
        f.enableImage()
        repeat(4) { f.controller.openAppreciationOnly() }
        assertNull(f.controller.state.appreciation)
        assertEquals(0, f.clients.entitlementCalls, "Saved authentication is still queued")
        f.finish(); f.finish()
        assertNotNull(f.controller.state.appreciation?.image)
        assertEquals(0, f.clients.authCalls, "Saved credentials need no repeated login request")
        assertEquals(2, f.clients.entitlementCalls, "One saved-account check and one appreciation read")
        assertEquals(1, f.clients.metadataCalls)
        assertEquals(1, f.clients.imageCalls)
        val scope = f.controller.currentAppreciationScope()
        repeat(5) {
            f.controller.openAppreciationOnly()
            f.controller.foreground(false)
            f.controller.foreground(true)
            f.controller.refreshAccountStatus()
            f.finish()
        }
        assertSame(scope, f.controller.currentAppreciationScope())
        assertEquals(2, f.clients.entitlementCalls)
        assertEquals(1, f.clients.metadataCalls)
        assertEquals(1, f.clients.imageCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertTrue(f.host.commits.isEmpty())
        f.controller.close()
    }

    @Test fun inlineAppreciationFailedSavedAuthenticationDoesNotFetchImageOrRetryOnResume() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val f = Fixture(saved, ConnectionEntryModel.Origin.SETTINGS, persisted = saved)
        f.enableImage(); f.clients.entitlementFailure = true
        f.controller.openAppreciationOnly(); f.finish(); f.finish()
        assertNull(f.controller.state.appreciation)
        assertTrue(f.controller.state.error)
        assertFalse(f.controller.state.busy)
        repeat(3) {
            f.controller.openAppreciationOnly()
            f.controller.foreground(false); f.controller.foreground(true)
            f.finish()
        }
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(0, f.clients.publicCalls)
        assertEquals(0, f.clients.authCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertTrue(f.host.commits.isEmpty())
        f.controller.close()
    }

    @Test fun inlineAppreciationMissingImageUsesBoundedExistingRetryWithoutBlockingOtherWork() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val f = Fixture(saved, ConnectionEntryModel.Origin.SETTINGS, persisted = saved)
        f.controller.openAppreciationOnly(); f.finish(); f.finish()
        val appreciation = assertNotNull(f.controller.state.appreciation)
        assertNull(appreciation.image)
        assertFalse(appreciation.busy)
        assertTrue(appreciation.message.isNotBlank())
        assertEquals(2, f.clients.metadataCalls, "Only the existing bounded public-image retry runs")
        repeat(3) { f.controller.openAppreciationOnly(); f.finish() }
        assertEquals(2, f.clients.metadataCalls)
        assertEquals(2, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        f.controller.close()
    }

    @Test fun inlineAppreciationDoesNothingForCustomDavOrAfterPageClose() {
        val custom = SharedConnectionConfig("https://dav.example.test/", "custom-user", "", "slot", false)
        val f = Fixture(custom, ConnectionEntryModel.Origin.SETTINGS, persisted = custom)
        f.controller.openAppreciationOnly(); f.finish()
        assertNull(f.controller.state.appreciation)
        assertEquals(0, f.clients.entitlementCalls)
        assertEquals(0, f.clients.publicCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        f.controller.close()
        f.controller.openAppreciationOnly(); f.finish()
        assertNull(f.controller.state.appreciation)
        assertEquals(0, f.clients.publicCalls)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun realInlineComposableRecomposesWithoutStartingAnotherAppreciationLoad() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val f = Fixture(saved, ConnectionEntryModel.Origin.SETTINGS, persisted = saved)
        f.enableImage()
        val scene = androidx.compose.ui.ImageComposeScene(width = 500, height = 600) {
            SharedConnectionTheme { SharedInlineAppreciation(f.controller) }
        }
        try {
            repeat(4) { scene.render().close(); f.finish() }
            assertNotNull(f.controller.state.appreciation?.image)
            assertEquals(1, f.clients.metadataCalls)
            assertEquals(1, f.clients.imageCalls)
            repeat(5) {
                f.controller.refreshAccountStatus()
                scene.render().close()
                f.finish()
            }
            assertEquals(1, f.clients.metadataCalls)
            assertEquals(1, f.clients.imageCalls)
            assertEquals(2, f.clients.entitlementCalls)
            assertEquals(0, f.ports.probes)
            assertEquals(0, f.ports.saves)
        } finally { scene.close(); f.controller.close() }
    }

    @Test fun elapsedExpiryKeepsAuthenticatedIdentityEligibleForExplicitConfigurationSave() {
        val f = Fixture(); f.official()
        f.controller.login(); f.finish()
        assertTrue(f.controller.state.canContinue)
        f.ports.elapsed = 9001L
        f.controller.refreshAccountStatus()
        assertTrue(f.controller.state.officialExpired)
        assertTrue(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves)
        assertNull(f.ports.savedConnection)
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.ports.officialSaves)
        assertEquals(1, f.ports.saves)
        assertEquals(1, f.host.commits.size)
        assertEquals(0, f.ports.probes)
    }

    @Test fun closePreventsLateCallbacksAndClearsSecrets() {
        val f = Fixture(); f.official()
        f.controller.login(); f.io.drain()
        f.controller.close(); f.ui.drain()
        assertEquals("", f.controller.state.password)
        assertFalse(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves)
        assertTrue(f.host.commits.isEmpty())
    }

    @Test fun staleProofCannotSaveAfterDetachAndReattach() {
        val f = Fixture(); f.custom()
        f.controller.testConnection(); f.finish()
        f.controller.detach(); f.controller.attach(Host()); f.controller.foreground(true)
        f.controller.continueConnection(); f.finish()
        assertFalse(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves)
    }

    @Test fun invalidOfficialUsernameRejectedLocallyWithoutNetwork() {
        val f = Fixture(); f.official(); f.controller.editUsername("Invalid Name")
        f.controller.register(); f.finish()
        assertEquals(0, f.clients.authCalls)
        assertFalse(f.controller.state.canContinue)
        assertEquals(0, f.ports.probes)
    }

    @Test fun busyHostIsReplaceableAndCommitDeliveredOnlyOnce() {
        val f = Fixture(); f.custom()
        f.controller.testConnection(); f.finish()
        f.controller.continueConnection(); f.io.drain()
        f.controller.detach()
        val replacement = Host()
        f.controller.attach(replacement); f.controller.foreground(true)
        f.ui.drain()
        assertEquals(1, f.ports.saves)
        assertTrue(f.host.commits.isEmpty())
        assertEquals(1, replacement.commits.size)
        f.controller.attach(replacement); f.controller.foreground(true)
        assertEquals(1, replacement.commits.size)
        assertTrue(replacement.busy.contains(true))
        assertEquals(false, replacement.busy.last())
    }
    @Test fun customIdentityChangeClearsPriorServicePassword() {
        val f = Fixture(SharedConnectionConfig("https://old.example.test/", "user", "old-secret", "slot", false))
        f.controller.editEndpoint("https://new.example.test/")
        assertEquals("", f.controller.state.password)
        f.controller.editPassword("replacement")
        f.controller.editUsername("another-user")
        assertEquals("", f.controller.state.password)
    }

    @Test fun anonymousCustomConnectionAndRequestedProfileArePreserved() {
        val f = Fixture(SharedConnectionConfig("https://public.example.test/", "", "", "old-slot", false))
        f.controller.testConnection(); f.finish()
        assertTrue(f.controller.state.canContinue)
        f.controller.continueConnection(); f.finish()
        assertEquals("slot", f.ports.last!!.profile)
        assertEquals("", f.ports.last!!.username)
    }

    @Test fun confirmedIdentitySurvivesBackgroundAndRealExpiryWithoutReauthentication() {
        val f = Fixture(); f.official(); f.ports.boot = -1
        f.controller.register(); f.finish()
        val expiry = f.controller.state.officialExpiry
        val requests = f.clients.requests.size
        repeat(4) {
            f.controller.foreground(false)
            assertTrue(f.controller.state.officialAuthenticated)
            assertEquals(expiry, f.controller.state.officialExpiry)
            assertFalse(f.controller.state.canContinue, "background still cannot commit configuration")
            f.controller.foreground(true); f.finish()
            assertTrue(f.controller.state.canContinue)
        }
        assertEquals(requests, f.clients.requests.size)
        f.ports.elapsed = 9001L
        f.controller.foreground(false); f.controller.foreground(true); f.finish()
        assertEquals("服务已到期", f.controller.state.officialAccessStatus)
        assertEquals(expiry, f.controller.state.officialExpiry)
        assertTrue(f.controller.state.canContinue)
        assertEquals(requests, f.clients.requests.size)
        assertEquals(0, f.ports.saves)
        assertNull(f.ports.savedConnection)
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.ports.officialSaves)
        assertEquals(1, f.ports.saves)
        assertEquals(0, f.ports.probes)
    }

    @Test fun appreciationExpiryStillAdvancesLocallyWithoutFocusRequestsOrPolling() {
        val f = Fixture(); f.official(); f.ports.boot = -1
        f.controller.login(); f.finish(); f.enableImage()
        f.controller.openAppreciation(); f.finish()
        val before = f.controller.state.appreciation!!
        val requests = f.clients.requests.size
        f.controller.foreground(false); f.ports.elapsed = 9001L
        f.controller.refreshAccountStatus(); f.finish()
        assertEquals("服务已到期", f.controller.state.appreciation!!.accessStatus)
        assertEquals(before.expiry, f.controller.state.appreciation!!.expiry)
        assertEquals(before.username, f.controller.state.appreciation!!.username)
        assertSame(before.image, f.controller.state.appreciation!!.image)
        f.controller.foreground(true); f.finish()
        assertEquals(requests, f.clients.requests.size)
        f.controller.back()
        assertTrue(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves)
    }

    @Test fun cancelAtFinalSaveCannotClaimAlreadyAdmittedCommitWasCancelled() {
        val f = Fixture(); f.custom()
        f.controller.testConnection(); f.finish()
        f.ports.onSave = { f.controller.cancel() }
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.ports.saves)
        assertEquals(1, f.host.commits.size)
        assertFalse(f.controller.state.notice.contains("已取消"))
    }

    @Test fun cancellationBeforeFinalSaveAdmissionPreventsWrite() {
        val f = Fixture(); f.custom()
        f.controller.testConnection(); f.finish()
        f.ports.onProbe = { f.controller.cancel() }
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.saves)
        assertEquals(0, f.host.commits.size)
    }

    @Test fun committedResultWaitsForForegroundAndIsDeliveredOnce() {
        val f = Fixture(); f.custom()
        f.controller.testConnection(); f.finish()
        f.ports.onSave = { f.controller.detach() }
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.ports.saves)
        assertTrue(f.host.commits.isEmpty())
        val replacement = Host()
        f.controller.attach(replacement); f.controller.foreground(true)
        assertEquals(1, replacement.commits.size)
        f.controller.attach(replacement); f.controller.foreground(true)
        assertEquals(1, replacement.commits.size)
    }

    @Test fun sameHostForegroundResumeDeliversCommitOnce() {
        val f = Fixture(); f.custom()
        f.controller.testConnection(); f.finish()
        f.ports.onSave = { f.controller.foreground(false) }
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.ports.saves)
        assertTrue(f.host.commits.isEmpty())
        f.controller.foreground(true)
        assertEquals(1, f.host.commits.size)
        f.controller.foreground(true)
        assertEquals(1, f.host.commits.size)
    }

    @Test fun attachingHostDoesNotMakeBackgroundControllerForeground() {
        val io = Queue(); val ui = Queue(); val ports = Ports(); val clients = Clients()
        val controller = SharedConnectionController(ConnectionEntryModel.Origin.SETUP, null, "slot", ports, io, ui, clients)
        controller.attach(Host())
        controller.choose(ConnectionEntryModel.Mode.OFFICIAL)
        controller.editUsername("alice"); controller.editPassword("secret-password")
        controller.register(); io.drain(); ui.drain()
        assertEquals(0, clients.authCalls)
        controller.foreground(true); controller.register(); io.drain(); ui.drain()
        assertEquals(1, clients.authCalls)
    }

    private fun diagnosticLines(directory: java.nio.file.Path): List<String> =
        Files.list(directory.resolve("diagnostics-v1")).use { files ->
            files.filter { it.fileName.toString().startsWith("events-") }
                .flatMap { Files.readAllLines(it).stream() }.toList()
        }

    @Test fun builtInAuthenticationDiagnosticsNeverClaimDavVerification() {
        val directory = Files.createTempDirectory("shared-connection-diagnostics-")
        DiagnosticLog.configure(directory, "1.0", "linux")
        val f = Fixture(); f.official()
        f.ports.onProbe = { DiagnosticLog.http(DiagnosticHttpMethod.PROPFIND, 207) }
        f.controller.login(); f.finish()
        val lines = diagnosticLines(directory)
        val ids = lines.mapNotNull { Regex("\"operationId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }.toSet()
        assertEquals(1, ids.size)
        assertTrue(lines.any { it.contains("\"httpMethod\":\"POST\"") })
        assertFalse(lines.any { it.contains("\"httpMethod\":\"PROPFIND\"") })
        assertEquals(1, lines.count { it.contains("\"stage\":\"FINISHED\"") })
        assertFalse(lines.joinToString().contains("alice"))
        assertFalse(lines.joinToString().contains("secret-password"))
        assertFalse(lines.joinToString().contains("https://"))
    }

    @Test fun registrationFailureHasOneSafeFailedDiagnosticTerminal() {
        val directory = Files.createTempDirectory("shared-connection-failure-")
        DiagnosticLog.configure(directory, "1.0", "linux")
        val f = Fixture(); f.official(); f.clients.authFailure = true
        f.controller.register(); f.finish()
        val terminals = diagnosticLines(directory).filter { it.contains("\"stage\":\"FINISHED\"") }
        assertEquals(1, terminals.size)
        assertTrue(terminals.single().contains("\"result\":\"FAILED\""))
        assertTrue(terminals.single().contains("\"failure\":\"NETWORK_IO\""))
        assertFalse(diagnosticLines(directory).joinToString().contains("SECRET"))
    }

    @Test fun cancelledLateResultLogsCanceledInsteadOfSucceededAndNeverSaves() {
        val directory = Files.createTempDirectory("shared-connection-cancel-")
        DiagnosticLog.configure(directory, "1.0", "linux")
        val f = Fixture(); f.official()
        f.controller.login(); f.io.drain(); f.controller.cancel(); f.ui.drain()
        val terminals = diagnosticLines(directory).filter { it.contains("\"stage\":\"FINISHED\"") }
        assertEquals(1, terminals.size)
        assertTrue(terminals.single().contains("\"result\":\"CANCELED\""))
        assertEquals(0, f.ports.saves)
        assertFalse(f.controller.state.canContinue)
    }

    @Test fun failedAuthenticationNeverReplacesSavedConnection() {
        val saved = SharedConnectionConfig("https://custom.example.test/", "saved-user", "saved-password", "slot", false)
        val f = Fixture(saved, ConnectionEntryModel.Origin.SETTINGS)
        f.controller.showChooser(); f.official(); f.clients.authFailure = true
        f.controller.login(); f.finish()
        assertEquals(0, f.ports.saves)
        assertNull(f.ports.last)
        f.controller.showChooser(); f.controller.choose(ConnectionEntryModel.Mode.CUSTOM)
        assertEquals("saved-user", f.controller.state.username)
        assertEquals("saved-password", f.controller.state.password)
    }

    @Test fun savedRecoveryDispatchesOnlyExplicitClickToCurrentHost() {
        val f = Fixture(origin = ConnectionEntryModel.Origin.SETTINGS)
        f.controller.setSavedConnectionRecovery("连接设置读取失败，请重试。", "重试读取已保存连接")
        assertEquals(0, f.host.reloads)
        f.controller.reloadSavedConnection()
        assertEquals(1, f.host.reloads)
        f.controller.detach()
        f.controller.reloadSavedConnection()
        assertEquals(1, f.host.reloads)
        val replacement = Host()
        f.controller.attach(replacement); f.controller.foreground(true)
        f.controller.reloadSavedConnection()
        assertEquals(1, replacement.reloads)
        assertEquals(0, f.ports.saves)
    }

    @Test fun unavailableClipboardReportsOnlySafeMessage() {
        val f = Fixture(); f.official(); f.ports.copyFailure = true
        f.controller.login(); f.finish()
        f.controller.openAppreciation(); f.finish(); f.controller.copyUsername()
        assertTrue(f.controller.state.appreciation!!.message.contains("无法复制"))
        assertFalse(f.controller.state.appreciation!!.message.contains("SECRET"))
    }

    @Test fun savedWizardKeepsSetupPresentationButBackReturnsDirectlyToPath() {
        val saved = SharedConnectionConfig("https://dav.example.test/", "saved", "", "slot", false)
        val f = Fixture(saved, ConnectionEntryModel.Origin.SETUP)
        assertEquals(ConnectionEntryModel.Origin.SETUP, f.controller.state.origin)
        assertEquals(ConnectionEntryModel.Mode.CUSTOM, f.controller.state.mode)
        assertTrue(f.controller.state.showChangeService)
        f.controller.back()
        assertEquals(listOf(ConnectionEntryModel.Back.PATH), f.host.backs)
        val changed = Fixture(saved, ConnectionEntryModel.Origin.SETUP)
        changed.controller.showChooser(); changed.controller.back()
        assertEquals(listOf(ConnectionEntryModel.Back.PATH), changed.host.backs)
    }

    @Test fun savedUnchangedConnectionReentersThroughFreshVerificationOnly() {
        val first = Fixture(); first.custom()
        first.controller.testConnection(); first.finish()
        first.controller.continueConnection(); first.finish()
        val saved = first.ports.last!!
        first.controller.close()
        val returned = Fixture(saved, persisted = saved)
        assertTrue(returned.controller.state.canContinue)
        assertEquals("", returned.controller.state.notice)
        assertEquals(0, returned.ports.probes)
        assertEquals(0, returned.ports.saves)
        returned.controller.continueConnection(); returned.controller.continueConnection()
        assertFalse(returned.controller.state.canContinue)
        returned.finish()
        assertEquals(1, returned.ports.probes)
        assertEquals(1, returned.ports.saves)
        assertEquals(listOf(saved), returned.host.commits)
    }

    @Test fun unsavedTestCannotEnableRecreatedController() {
        val first = Fixture(); first.custom()
        first.controller.testConnection(); first.finish()
        val draft = SharedConnectionConfig(first.controller.state.endpoint, first.controller.state.username,
            first.controller.state.password, "slot", false)
        first.controller.close()
        val returned = Fixture(draft)
        assertFalse(returned.controller.state.canContinue)
        returned.controller.continueConnection(); returned.finish()
        assertEquals(0, returned.ports.probes)
        assertEquals(0, returned.ports.saves)
    }

    @Test fun savedCredentialMismatchNeverEnablesDirectRevalidation() {
        val saved = SharedConnectionConfig("https://dav.example.test/", "saved", "secret", "slot", false)
        val changes = listOf(saved.copy(endpoint = "https://different.example.test/"),
            saved.copy(username = "another"), saved.copy(password = ""), saved.copy(official = true))
        changes.forEach { changed ->
            val f = Fixture(changed, persisted = saved)
            assertFalse(f.controller.state.canContinue)
            f.controller.continueConnection(); f.finish()
            assertEquals(0, f.ports.probes)
            assertEquals(0, f.ports.saves)
        }
        val differentProfile = Fixture(saved, persisted = saved.copy(profile = "other-slot"))
        assertFalse(differentProfile.controller.state.canContinue)
    }

    @Test fun editingThenRestoringSavedPasswordCannotRestoreReentryPermission() {
        val saved = SharedConnectionConfig("https://dav.example.test/", "saved", "secret", "slot", false)
        val f = Fixture(saved, persisted = saved)
        assertTrue(f.controller.state.canContinue)
        f.controller.editPassword("changed")
        f.controller.editPassword(saved.password)
        assertFalse(f.controller.state.canContinue)
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        f.controller.testConnection(); f.finish()
        assertTrue(f.controller.state.canContinue)
    }

    @Test fun switchingSavedAccountOrServiceConsumesReentryPermission() {
        val saved = SharedConnectionConfig("https://dav.example.test/", "saved", "secret", "slot", false)
        val f = Fixture(saved, persisted = saved)
        f.controller.editUsername("another")
        assertEquals("", f.controller.state.password)
        f.controller.editUsername(saved.username); f.controller.editPassword(saved.password)
        assertFalse(f.controller.state.canContinue)
        val switched = Fixture(saved, persisted = saved)
        switched.controller.showChooser(); switched.controller.choose(ConnectionEntryModel.Mode.CUSTOM)
        assertEquals(saved.username, switched.controller.state.username)
        assertFalse(switched.controller.state.canContinue)
    }

    @Test fun savedReentryFailureDoesNotWriteOrLeaveAReusablePermission() {
        val saved = SharedConnectionConfig("https://dav.example.test/", "saved", "secret", "slot", false)
        val f = Fixture(saved, persisted = saved); f.ports.probeResult = false
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertEquals(saved, f.ports.savedConnection)
        assertFalse(f.controller.state.canContinue)
        assertTrue(f.controller.state.error)
        assertTrue(f.host.commits.isEmpty())
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.ports.probes)
    }

    @Test fun savedReentrySurvivesIdleBackgroundButNeverRevivesInterruptedVerification() {
        val saved = SharedConnectionConfig("https://dav.example.test/", "saved", "secret", "slot", false)
        val f = Fixture(saved, persisted = saved)
        f.controller.foreground(false)
        assertFalse(f.controller.state.canContinue)
        f.controller.foreground(true)
        assertTrue(f.controller.state.canContinue)
        assertEquals(0, f.ports.probes)
        f.ports.onProbe = { f.controller.foreground(false) }
        f.controller.continueConnection(); f.finish()
        f.controller.foreground(true)
        assertFalse(f.controller.state.canContinue)
        assertEquals(1, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertTrue(f.host.commits.isEmpty())
    }

    @Test fun savedReentryCancelledBeforeDispatchCannotWrite() {
        val saved = SharedConnectionConfig("https://dav.example.test/", "saved", "secret", "slot", false)
        val f = Fixture(saved, persisted = saved)
        f.controller.continueConnection(); f.controller.cancel(); f.finish()
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertFalse(f.controller.state.canContinue)
    }

    @Test fun savedOfficialReentryRequiresFreshReadOnlyAuthenticationBeforeNext() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val f = Fixture(saved, persisted = saved)
        assertFalse(f.controller.state.canContinue)
        assertFalse(f.controller.state.officialAuthenticated)
        assertFalse(f.controller.state.canAppreciate)
        assertEquals(0, f.clients.entitlementCalls)
        f.finish()
        assertTrue(f.controller.state.officialAuthenticated)
        assertTrue(f.controller.state.canContinue)
        assertEquals(0, f.clients.authCalls)
        assertEquals(1, f.clients.entitlementCalls)
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(1, f.ports.officialSaves)
    }

    @Test fun inactiveSavedOfficialAccountCannotReachDavOrPersistenceOnReentry() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val f = Fixture(saved, persisted = saved); f.clients.active = false; f.clients.status = 0
        f.finish()
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertFalse(f.controller.state.canContinue)
        assertTrue(f.host.commits.isEmpty())
    }

    @Test fun expiredAuthenticatedAccountShowsExpiryAndAppreciationAndCanExplicitlySave() {
        val f = Fixture(); f.official(); f.clients.active = false; f.clients.serverTime = 10001L
        f.controller.login(); f.finish()
        assertTrue(f.controller.state.officialAuthenticated)
        assertTrue(f.controller.state.canAppreciate)
        assertEquals("赞赏续期", f.controller.state.appreciationLabel)
        assertEquals("服务已到期", f.controller.state.officialAccessStatus)
        assertTrue(f.controller.state.officialExpiry.startsWith("使用期限至 "))
        assertTrue(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves)
        assertNull(f.ports.savedConnection)
        f.controller.openAppreciation(); f.finish()
        assertTrue(f.controller.state.appreciation != null)
        f.controller.back()
        assertTrue(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves, "Viewing appreciation must not save the credentials")
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.probes)
        assertEquals(1, f.ports.officialSaves)
        assertEquals(1, f.ports.saves)
        assertEquals(1, f.host.commits.size)
        assertEquals("alice", f.ports.last!!.username)
        assertEquals("secret-password", f.ports.last!!.password)
    }

    @Test fun expiredSetupInlineAppreciationLoadsOnceAndBackUsesNormalChooserNavigation() {
        val f = Fixture(); f.official(); f.clients.active = false; f.clients.serverTime = 10001
        f.controller.login(); f.finish(); f.enableImage()
        val expiry = f.controller.state.officialExpiry
        assertTrue(f.controller.state.officialExpired)
        f.controller.openInlineAppreciation(); f.finish()
        assertTrue(f.controller.state.appreciationInline)
        assertNotNull(f.controller.state.appreciation?.image)
        assertTrue(f.controller.state.showOfficialAccount)
        assertEquals("alice", f.controller.state.username)
        assertEquals(expiry, f.controller.state.officialExpiry)
        assertTrue(f.controller.state.canContinue)
        val requests = f.clients.requests.size
        repeat(4) {
            f.controller.openInlineAppreciation()
            f.controller.detach(); f.controller.attach(f.host); f.controller.foreground(true)
            f.controller.refreshAccountStatus(); f.finish()
        }
        assertEquals(requests, f.clients.requests.size)
        assertEquals(1, f.clients.metadataCalls)
        assertEquals(1, f.clients.imageCalls)
        assertEquals(2, f.clients.entitlementCalls)
        assertTrue(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves, "Opening and resuming inline appreciation is not a completion click")
        assertNull(f.ports.savedConnection)
        assertEquals(0, f.ports.probes)
        f.controller.back()
        assertEquals(ConnectionEntryModel.Mode.CHOICE, f.controller.state.mode)
        assertNull(f.controller.state.appreciation)
        assertFalse(f.controller.state.appreciationInline)
        assertTrue(f.host.backs.isEmpty(), "One Back reaches the normal chooser, without an extra QR page")
        f.controller.back()
        assertEquals(listOf(ConnectionEntryModel.Back.PATH), f.host.backs)
        f.controller.close()
    }

    @Test fun activeSetupInlineAppreciationRetainsAccountAndImageAcrossBackgroundAndReattachment() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish(); f.enableImage()
        assertTrue(f.controller.state.showInlineAppreciation)
        val expiry = f.controller.state.officialExpiry
        f.controller.openInlineAppreciation()
        assertTrue(f.controller.state.canContinue, "Public image loading cannot block authenticated Next")
        f.finish()
        assertTrue(f.controller.state.appreciationInline)
        assertTrue(f.controller.state.canContinue)
        val image = assertNotNull(f.controller.state.appreciation?.image)
        val scope = assertNotNull(f.controller.currentAppreciationScope())
        val requests = f.clients.requests.size
        repeat(4) {
            f.controller.detach()
            assertTrue(f.controller.state.showInlineAppreciation, "Background cannot hide the inline panel")
            assertTrue(f.controller.state.officialAuthenticated)
            assertEquals(expiry, f.controller.state.officialExpiry)
            assertSame(image, f.controller.state.appreciation?.image)
            f.controller.attach(f.host); f.controller.foreground(true)
            f.controller.openInlineAppreciation(); f.controller.refreshAccountStatus(); f.finish()
            assertTrue(f.controller.state.canContinue)
            assertSame(scope, f.controller.currentAppreciationScope())
            assertSame(image, f.controller.state.appreciation?.image)
        }
        assertEquals(requests, f.clients.requests.size)
        assertEquals(2, f.clients.entitlementCalls)
        assertEquals(1, f.clients.metadataCalls)
        assertEquals(1, f.clients.imageCalls)
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.ports.officialSaves)
        assertEquals(1, f.host.commits.size)
        assertEquals(0, f.ports.probes)
        f.controller.close()
    }

    @Test fun inlineAccountEditsServiceExitAndLogoutRejectLateImagesAndEntitlement() {
        val resets: List<(SharedConnectionController) -> Unit> = listOf(
            { it.editUsername("bob") },
            { it.editPassword("changed-password") },
            { it.showChooser() },
            { it.back() },
            { it.close() },
        )
        for (expired in listOf(false, true)) for (finishRead in listOf(false, true)) for (reset in resets) {
            val f = Fixture(); f.official()
            f.clients.active = !expired; f.clients.serverTime = if (expired) 10001 else 1000
            f.controller.login(); f.finish(); f.enableImage()
            f.controller.openInlineAppreciation(); f.io.drain()
            if (finishRead) f.ui.drain()
            reset(f.controller)
            f.finish()
            assertNull(f.controller.state.appreciation)
            assertNull(f.controller.currentAppreciationPng())
            assertNull(f.controller.currentAppreciationScope())
            assertFalse(f.controller.state.appreciationInline)
            assertFalse(f.controller.state.showInlineAppreciation)
            assertFalse(f.controller.state.showOfficialAccount)
            assertFalse(f.controller.state.officialAuthenticated)
            assertFalse(f.controller.state.canContinue)
            val requests = f.clients.requests.size
            f.controller.openInlineAppreciation(); f.controller.foreground(true); f.finish()
            assertEquals(requests, f.clients.requests.size)
            assertEquals(0, f.ports.saves)
            assertEquals(0, f.ports.probes)
            f.controller.close()
        }
    }

    @Test fun activeInlineImageFailureCannotRevokeLoginOrBlockNextAndNeverRetriesOnFocus() {
        for (status in listOf(401, 403, 429, 503)) {
            val f = Fixture(); f.official(); f.controller.login(); f.finish()
            f.clients.publicStatus = status
            val expiry = f.controller.state.officialExpiry
            f.controller.openInlineAppreciation(); f.finish()
            assertTrue(f.controller.state.officialAuthenticated)
            assertTrue(f.controller.state.canContinue)
            assertTrue(f.controller.state.showInlineAppreciation)
            assertEquals(expiry, f.controller.state.officialExpiry)
            assertNull(f.controller.state.appreciation?.image)
            assertFalse(f.controller.state.appreciation!!.busy)
            assertEquals(if (status == 503) 2 else 1, f.clients.metadataCalls)
            val requests = f.clients.requests.size
            repeat(4) {
                f.controller.foreground(false); f.controller.foreground(true)
                f.controller.refreshAccountStatus(); f.controller.openInlineAppreciation(); f.finish()
            }
            assertEquals(requests, f.clients.requests.size)
            assertEquals(2, f.clients.entitlementCalls)
            assertEquals(1, f.clients.authCalls)
            assertEquals(0, f.ports.saves)
            assertEquals(0, f.ports.probes)
            f.controller.close()
        }
    }

    @Test fun savedExpiredInlineBackReturnsToPathInOneClickAndRejectsLateImageAndRenewal() {
        for (finishRead in listOf(false, true)) {
            val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
            val f = Fixture(saved, persisted = saved)
            f.clients.active = false; f.clients.serverTime = 10001
            f.finish(); f.enableImage(); f.controller.openInlineAppreciation()
            if (!finishRead) { f.clients.active = true; f.clients.expires = 20000 }
            f.io.drain()
            if (finishRead) f.ui.drain()
            f.controller.back(); f.finish()
            assertEquals(listOf(ConnectionEntryModel.Back.PATH), f.host.backs)
            assertNull(f.controller.state.appreciation)
            assertFalse(f.controller.state.officialAuthenticated)
            assertFalse(f.controller.state.canContinue)
            assertEquals(0, f.ports.probes)
            assertEquals(0, f.ports.saves)
            f.controller.close()
        }
    }

    @Test fun freshRenewalDuringInlineReadKeepsNextAvailableWithoutManufacturingDavProof() {
        val f = Fixture(); f.official(); f.clients.active = false; f.clients.serverTime = 10001
        f.controller.login(); f.finish(); f.enableImage()
        f.controller.openInlineAppreciation()
        f.clients.active = true; f.clients.expires = 20000
        f.finish()
        assertTrue(f.controller.state.appreciationInline)
        assertTrue(f.controller.state.showInlineAppreciation)
        assertFalse(f.controller.state.officialExpired)
        assertTrue(f.controller.state.officialAuthenticated)
        assertTrue(f.controller.state.canContinue)
        assertEquals("", f.controller.state.officialAccessStatus)
        f.controller.testConnection(); f.finish()
        assertEquals(0, f.ports.probes)
        f.controller.continueConnection()
        assertFalse(f.controller.state.canGoBack, "Inline content cannot bypass the commit navigation lock")
        f.controller.back()
        assertEquals(ConnectionEntryModel.Mode.OFFICIAL, f.controller.state.mode)
        f.finish()
        assertEquals(1, f.ports.officialSaves)
        assertEquals(1, f.host.commits.size)
        assertEquals(0, f.ports.probes)
        f.controller.close()
    }

    @Test fun inlineImageFailureNeverChangesAccountAuthorityOrRetriesOnFocus() {
        for (renewed in listOf(false, true)) {
            val f = Fixture(); f.official(); f.clients.active = false; f.clients.serverTime = 10001
            f.controller.login(); f.finish()
            f.controller.openInlineAppreciation()
            if (renewed) { f.clients.active = true; f.clients.expires = 20000 }
            f.finish()
            assertTrue(f.controller.state.officialAuthenticated)
            assertEquals(!renewed, f.controller.state.officialExpired)
            assertTrue(f.controller.state.canContinue, "Authenticated identity remains valid with or without renewal")
            assertNull(f.controller.state.appreciation?.image)
            assertFalse(f.controller.state.appreciation!!.busy)
            assertEquals(2, f.clients.metadataCalls, "Only the bounded public-image retry is allowed")
            repeat(3) {
                f.controller.openInlineAppreciation()
                f.controller.foreground(false); f.controller.foreground(true)
                f.controller.refreshAccountStatus(); f.finish()
            }
            assertEquals(2, f.clients.entitlementCalls)
            assertEquals(2, f.clients.metadataCalls)
            assertEquals(0, f.ports.saves)
            assertNull(f.ports.savedConnection)
            f.controller.continueConnection(); f.finish()
            assertEquals(1, f.ports.officialSaves)
            assertEquals(1, f.ports.saves)
            assertEquals(1, f.host.commits.size)
            assertEquals(0, f.ports.probes)
            f.controller.close()
        }
    }

    @Test fun inlineEntryWaitsForSavedExpiredAuthenticationAndPreservesHistoryAfterDenial() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val cached = TrustedEntitlementCache.Record(saved.endpoint, saved.username, 1000, false, 1001, 0, 1)
        val f = Fixture(saved, persisted = saved, cached = cached)
        f.enableImage(); f.clients.entitlementFailure = true
        assertTrue(f.controller.state.officialExpired)
        f.controller.openInlineAppreciation()
        assertNull(f.controller.state.appreciation, "The saved account entry read is still pending")
        f.finish(); f.finish()
        assertTrue(f.controller.state.appreciationInline)
        assertNotNull(f.controller.state.appreciation?.image)
        assertTrue(f.controller.state.officialExpired)
        assertTrue(f.controller.state.showOfficialAccount)
        assertFalse(f.controller.state.officialAuthenticated)
        assertFalse(f.controller.state.canContinue)
        assertEquals(2, f.clients.entitlementCalls)
        assertEquals(0, f.clients.authCalls)
        f.controller.back()
        assertEquals(listOf(ConnectionEntryModel.Back.PATH), f.host.backs)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        f.controller.close()
    }

    @Test fun savedHistoryRenewedByEntryReadStillOpensDeferredInlineAppreciation() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val cached = TrustedEntitlementCache.Record(saved.endpoint, saved.username, 1000, false, 1001, 0, 1)
        val f = Fixture(saved, persisted = saved, cached = cached)
        f.enableImage(); f.controller.openInlineAppreciation(); f.finish(); f.finish()
        assertFalse(f.controller.state.officialExpired)
        assertTrue(f.controller.state.canContinue)
        assertTrue(f.controller.state.showInlineAppreciation)
        assertTrue(f.controller.state.appreciationInline)
        assertNotNull(f.controller.state.appreciation?.image)
        assertEquals(2, f.clients.entitlementCalls)
        assertEquals(1, f.clients.metadataCalls)
        assertEquals(1, f.clients.imageCalls)
        f.controller.close()
    }

    @Test fun expiryDuringBackgroundDefersExactlyOneInlineReadUntilForeground() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish(); f.enableImage()
        f.controller.foreground(false)
        f.ports.elapsed = 9001
        f.controller.refreshAccountStatus()
        assertTrue(f.controller.state.officialExpired)
        repeat(3) { f.controller.openInlineAppreciation() }
        f.finish()
        assertNull(f.controller.state.appreciation)
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(0, f.clients.publicCalls)
        f.clients.serverTime = 10001; f.clients.active = false
        f.controller.foreground(true); f.finish()
        assertTrue(f.controller.state.appreciationInline)
        assertNotNull(f.controller.state.appreciation?.image)
        assertTrue(f.controller.state.canContinue)
        val requests = f.clients.requests.size
        repeat(4) {
            f.controller.foreground(false); f.controller.foreground(true)
            f.controller.openInlineAppreciation(); f.finish()
        }
        assertEquals(requests, f.clients.requests.size)
        assertEquals(2, f.clients.entitlementCalls)
        assertEquals(1, f.clients.metadataCalls)
        assertEquals(1, f.clients.imageCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        f.controller.close()
    }

    @Test fun desktopLiveExpirySurvivesInline401AsDisplayHistoryWithoutGrantingAccess() {
        val f = Fixture(); f.official(); f.ports.boot = -1
        f.controller.login(); f.finish(); f.enableImage()
        f.ports.elapsed = 9001
        f.controller.refreshAccountStatus()
        assertTrue(f.controller.state.officialExpired)
        f.clients.entitlementFailure = true
        f.controller.openInlineAppreciation(); f.finish()
        assertTrue(f.controller.state.officialExpired, "Current-controller elapsed expiry survives account denial")
        assertEquals("服务已到期", f.controller.state.officialAccessStatus)
        assertTrue(f.controller.state.officialExpiry.startsWith("上次确认"))
        assertTrue(f.controller.state.showOfficialAccount)
        assertFalse(f.controller.state.officialAuthenticated)
        assertFalse(f.controller.state.canContinue)
        assertTrue(f.controller.state.appreciationInline)
        assertNotNull(f.controller.state.appreciation?.image)
        val requests = f.clients.requests.size
        f.controller.foreground(false); f.controller.foreground(true)
        f.controller.openInlineAppreciation(); f.finish()
        assertEquals(requests, f.clients.requests.size)
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        f.controller.editUsername("bob")
        assertFalse(f.controller.state.officialExpired)
        assertFalse(f.controller.state.showOfficialAccount)
        assertNull(f.controller.state.appreciation)
        f.controller.close()

        // A previous process's elapsed origin with an unknown boot ID is never promoted.
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val cached = TrustedEntitlementCache.Record(saved.endpoint, saved.username, 10000, true, 1000, 0, -1)
        val restored = Fixture(saved, persisted = saved, cached = cached)
        restored.ports.boot = -1; restored.ports.elapsed = 9001
        restored.clients.entitlementFailure = true
        restored.finish(); restored.controller.openInlineAppreciation(); restored.finish()
        assertFalse(restored.controller.state.officialExpired)
        assertFalse(restored.controller.state.canContinue)
        assertNull(restored.controller.state.appreciation)
        assertEquals(0, restored.clients.publicCalls)
        restored.controller.close()
    }

    @Test fun authenticatedInactiveAccountShowsInlineAppreciationWithoutGrantingNext() {
        val inactive = Fixture(); inactive.official(); inactive.clients.active = false; inactive.clients.status = 0; inactive.enableImage()
        inactive.controller.login(); inactive.finish(); inactive.controller.openInlineAppreciation(); inactive.finish()
        assertFalse(inactive.controller.state.officialExpired)
        assertTrue(inactive.controller.state.officialAuthenticated)
        assertTrue(inactive.controller.state.showInlineAppreciation)
        assertTrue(inactive.controller.state.appreciationInline)
        assertNotNull(inactive.controller.state.appreciation?.image)
        assertEquals("服务未启用", inactive.controller.state.officialAccessStatus)
        assertFalse(inactive.controller.state.canContinue)
        inactive.controller.continueConnection(); inactive.finish()
        assertEquals(0, inactive.ports.saves)
        assertEquals(0, inactive.ports.probes)
        assertEquals(2, inactive.clients.entitlementCalls)
        assertEquals(1, inactive.clients.metadataCalls)
        assertEquals(1, inactive.clients.imageCalls)
        inactive.controller.foreground(false)
        assertTrue(inactive.controller.state.showInlineAppreciation)
        inactive.controller.foreground(true); inactive.finish()
        assertEquals(2, inactive.clients.entitlementCalls)
        assertEquals(1, inactive.clients.metadataCalls)
        inactive.controller.close()
    }

    @Test fun inlineEntryIgnoresUnknownCustomAndSettingsAccounts() {
        val unknown = Fixture(); unknown.official(); unknown.clients.entitlementFailure = true
        unknown.controller.login(); unknown.finish(); unknown.controller.openInlineAppreciation(); unknown.finish()
        assertFalse(unknown.controller.state.officialExpired)
        assertFalse(unknown.controller.state.showOfficialAccount)
        assertNull(unknown.controller.state.appreciation)
        assertEquals(0, unknown.clients.publicCalls)
        unknown.controller.close()
        val custom = Fixture(); custom.custom(); custom.controller.openInlineAppreciation(); custom.finish()
        assertNull(custom.controller.state.appreciation)
        assertEquals(0, custom.clients.requests.size)
        custom.controller.close()
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val settings = Fixture(saved, ConnectionEntryModel.Origin.SETTINGS, persisted = saved)
        settings.clients.serverTime = 10001; settings.finish()
        settings.controller.openInlineAppreciation(); settings.finish()
        assertTrue(settings.controller.state.officialExpired)
        assertNull(settings.controller.state.appreciation)
        assertEquals(0, settings.clients.publicCalls)
        settings.controller.openAppreciation(); settings.finish()
        assertNotNull(settings.controller.state.appreciation)
        assertFalse(settings.controller.state.appreciationInline)
        settings.controller.back()
        assertTrue(settings.host.backs.isEmpty(), "Settings keeps its existing separate appreciation page")
        settings.controller.close()
    }

    @Test fun failedLoginAndFailedSavedAuthenticationNeverHideFormOrShowAppreciation() {
        val f = Fixture(); f.official(); f.clients.authFailure = true
        f.controller.login(); f.finish()
        assertFalse(f.controller.state.officialAuthenticated)
        assertFalse(f.controller.state.canAppreciate)
        assertFalse(f.controller.state.canContinue)
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val restored = Fixture(saved, persisted = saved); restored.clients.entitlementFailure = true
        restored.finish()
        assertFalse(restored.controller.state.officialAuthenticated)
        assertFalse(restored.controller.state.canAppreciate)
        assertFalse(restored.controller.state.canContinue)
        assertTrue(restored.controller.state.error)
        assertEquals(saved, restored.ports.savedConnection)
    }

    @Test fun trustedExpiryUsesMonotonicSessionEvenWhenDesktopBootIdIsUnavailable() {
        val f = Fixture(); f.official(); f.ports.boot = -1
        f.controller.login(); f.finish()
        assertTrue(f.controller.state.canContinue)
        f.ports.elapsed = 9001
        f.controller.refreshAccountStatus()
        assertEquals("服务已到期", f.controller.state.officialAccessStatus)
        assertTrue(f.controller.state.canContinue)
        assertTrue(f.controller.state.canAppreciate)
        assertEquals(0, f.ports.saves)
        f.controller.continueConnection(); f.finish()
        assertEquals(1, f.ports.officialSaves)
        assertEquals(1, f.ports.saves)
        assertEquals(0, f.ports.probes)
    }

    @Test fun returningFromAppreciationRetainsAuthenticationAndRenewedAccess() {
        val f = Fixture(); f.official(); f.clients.active = false; f.clients.serverTime = 10001
        f.controller.login(); f.finish()
        assertTrue(f.controller.state.canContinue)
        f.controller.openAppreciation()
        f.clients.active = true; f.clients.expires = 20000
        f.finish(); f.controller.back()
        assertTrue(f.controller.state.officialAuthenticated)
        assertTrue(f.controller.state.canContinue)
        assertEquals("", f.controller.state.officialAccessStatus)
        assertEquals(0, f.ports.probes)
    }

    @Test fun savedOfficialBackBeforeAuthenticationDispatchCancelsWithoutSaving() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val f = Fixture(saved, persisted = saved)
        assertTrue(f.controller.state.canGoBack)
        f.controller.back(); f.finish()
        assertEquals(listOf(ConnectionEntryModel.Back.PATH), f.host.backs)
        assertEquals(0, f.clients.entitlementCalls)
        assertFalse(f.controller.state.officialAuthenticated)
        assertEquals(0, f.ports.saves)
    }

    @Test fun builtInCancelAndBackgroundBeforeSaveAdmissionNeverPersist() {
        for (kind in listOf("active", "expired", "unknown", "no-clock")) {
            for (interruption in listOf("cancel", "background", "background-return", "detach")) {
                val f = Fixture(); f.official()
                f.clients.active = kind != "expired" && kind != "unknown"
                f.clients.serverTime = when (kind) { "expired" -> 10001L; "no-clock" -> null; else -> 1000L }
                f.clients.nullExpiry = kind == "unknown"
                f.controller.login(); f.finish()
                assertTrue(f.controller.state.canContinue, "$kind/$interruption")
                f.controller.continueConnection()
                when (interruption) {
                    "cancel" -> f.controller.cancel()
                    "background" -> f.controller.foreground(false)
                    "background-return" -> { f.controller.foreground(false); f.controller.foreground(true) }
                    "detach" -> { f.controller.detach(); f.controller.attach(f.host); f.controller.foreground(true) }
                }
                f.finish()
                assertEquals(0, f.ports.officialSaves, "$kind/$interruption")
                assertEquals(0, f.ports.saves, "$kind/$interruption")
                assertEquals(0, f.ports.probes, "$kind/$interruption")
                assertTrue(f.host.commits.isEmpty(), "$kind/$interruption")
                f.controller.close()
            }
        }
    }

    @Test fun expiredSavedAccountKeepsTrustedHistoryAndAppreciationAfter401WithoutGrantingNext() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val cached = TrustedEntitlementCache.Record(saved.endpoint, saved.username, 1000, false, 1001, 0, 1)
        val f = Fixture(saved, persisted = saved, cached = cached)
        f.clients.entitlementFailure = true; f.finish()
        assertFalse(f.controller.state.officialAuthenticated)
        assertTrue(f.controller.state.showOfficialAccount)
        assertTrue(f.controller.state.canAppreciate)
        assertTrue(f.controller.state.officialExpiry.startsWith("上次确认使用期限至 "))
        assertEquals("服务已到期", f.controller.state.officialAccessStatus)
        assertFalse(f.controller.state.canContinue)
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.saves)
        f.controller.openAppreciation(); f.finish()
        assertTrue(f.controller.state.appreciation != null)
        f.controller.back()
        assertFalse(f.controller.state.canContinue)
        f.controller.editPassword("changed")
        assertFalse(f.controller.state.showOfficialAccount)
        assertFalse(f.controller.state.canAppreciate)
    }

    @Test fun cachedHistoryWithoutExactDurableCredentialsNeverHidesLoginOrGrantsAccess() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val cached = TrustedEntitlementCache.Record(saved.endpoint, saved.username, 1000, false, 1001, 0, 1)
        val f = Fixture(saved.copy(password = "other-password"), persisted = saved, cached = cached)
        f.finish()
        assertFalse(f.controller.state.showOfficialAccount)
        assertFalse(f.controller.state.canAppreciate)
        assertFalse(f.controller.state.canContinue)
        assertEquals(0, f.clients.entitlementCalls)
    }

    @Test fun appreciation401RevokesCurrentAccessButKeepsAuthenticatedHistoryVisible() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish()
        assertTrue(f.controller.state.canContinue)
        f.clients.entitlementFailure = true
        f.controller.openAppreciation(); f.finish(); f.controller.back()
        assertFalse(f.controller.state.officialAuthenticated)
        assertTrue(f.controller.state.showOfficialAccount)
        assertTrue(f.controller.state.canAppreciate)
        assertFalse(f.controller.state.canContinue)
        assertTrue(f.controller.state.officialExpiry.startsWith("上次确认"))
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.saves)
    }

    @Test fun builtInExpiryBetweenNextClickAndSaveDispatchDoesNotRevokeAuthenticatedIdentity() {
        val f = Fixture(); f.official(); f.controller.login(); f.finish()
        f.controller.continueConnection()
        f.ports.elapsed = 9001
        f.finish()
        assertEquals(1, f.ports.officialSaves)
        assertEquals(1, f.ports.saves)
        assertEquals(0, f.ports.probes)
        assertEquals(1, f.host.commits.size)
        assertTrue(f.controller.state.officialAuthenticated)
        assertTrue(f.controller.state.officialExpired)
        assertEquals("服务已到期", f.controller.state.officialAccessStatus)
    }

    @Test fun editingDuringSavedAccountRefreshRejectsItsLateAuthentication() {
        val saved = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val f = Fixture(saved, persisted = saved)
        f.io.drain()
        f.controller.editUsername("bob")
        f.ui.drain()
        assertEquals("bob", f.controller.state.username)
        assertEquals("", f.controller.state.password)
        assertFalse(f.controller.state.officialAuthenticated)
        assertFalse(f.controller.state.canAppreciate)
        assertFalse(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves)
    }

}
