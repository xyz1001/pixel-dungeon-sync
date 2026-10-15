package io.github.xyz1001.pdsync.android

import androidx.compose.ui.graphics.ImageBitmap
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.nio.file.Files
import pdsync.DiagnosticLog
import pdsync.DiagnosticHttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        var probes = 0
        var saves = 0
        var elapsed = 0L
        var probeResult = true
        var last: SharedConnectionConfig? = null
        var copied: String? = null
        var copyFailure = false
        var onProbe: (() -> Unit)? = null
        var onSave: (() -> Unit)? = null
        override fun testConnection(config: SharedConnectionConfig): Boolean {
            probes++
            onProbe?.invoke()
            return probeResult
        }
        override fun saveAndActivate(config: SharedConnectionConfig) { onSave?.invoke(); saves++; last = config }
        override fun cacheDirectory() = null
        override fun elapsedRealtimeMs() = elapsed
        override fun bootCount() = 1
        override fun copyUsername(username: String) {
            if (copyFailure) throw IllegalStateException("SECRET clipboard state")
            copied = username
        }
        override fun decodeImage(bytes: ByteArray): ImageBitmap? = null
    }
    private class Host : SharedConnectionHost {
        val backs = mutableListOf<ConnectionEntryModel.Back>()
        val commits = mutableListOf<SharedConnectionConfig>()
        val busy = mutableListOf<Boolean>()
        var reloads = 0
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
        var active = true
        var authFailure = false
        var onAuth: (() -> Unit)? = null
        var onEntitlement: (() -> Unit)? = null
        val requests = mutableListOf<Request>()
        override fun authentication() = OfficialServiceRegistrationClient { request ->
            authCalls++
            requests += request
            onAuth?.invoke()
            if (authFailure) throw IOException("SECRET request details")
            response(request, """{"code":200,"message":"ok","data":{"webdav_url":"https://webdav.xyz1001.top:8443/","username":"alice","quota_mb":1,"quota_bytes":1048576,"upload_bandwidth_kb":0,"download_bandwidth_kb":0}}""")
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
                    return response(request, """{"code":200,"data":{"source":"managed_ledger","username":"alice","expires_ms":10000,"server_time_ms":1000,"active":$active,"managed":true}}""")
                }
                publicCalls++
                return response(request, "{}", 503)
            }
        }
        companion object {
            fun response(request: Request, json: String, status: Int = 200) = Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }
    }
    private class Fixture(existing: SharedConnectionConfig? = null,
                          origin: ConnectionEntryModel.Origin = ConnectionEntryModel.Origin.SETUP) {
        val io = Queue()
        val ui = Queue()
        val ports = Ports()
        val clients = Clients()
        val host = Host()
        val controller = SharedConnectionController(origin, existing, "slot", ports, io, ui, clients)
        init { controller.attach(host); controller.foreground(true) }
        fun finish() { io.drain(); ui.drain() }
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

    @Test fun officialLoginRequiresAuthenticatedEntitlementAndDavThenRechecksOnSave() {
        val f = Fixture(); f.official()
        f.controller.login(); f.finish()
        assertEquals(1, f.clients.authCalls)
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(1, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertTrue(f.controller.state.canContinue)
        f.controller.continueConnection(); f.finish()
        assertEquals(2, f.clients.entitlementCalls)
        assertEquals(2, f.ports.probes)
        assertEquals(1, f.ports.saves)
        assertTrue(f.ports.last!!.official)
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
        val f = Fixture(); f.official(); f.clients.active = false
        f.controller.login(); f.finish()
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertFalse(f.controller.state.canContinue)
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.saves)
    }

    @Test fun successfulEntitlementWithFailedDavDoesNotGrantProof() {
        val f = Fixture(); f.official(); f.ports.probeResult = false
        f.controller.login(); f.finish()
        assertEquals(1, f.clients.entitlementCalls)
        assertEquals(1, f.ports.probes)
        assertFalse(f.controller.state.canContinue)
        assertEquals(0, f.ports.saves)
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

    @Test fun unverifiedAppreciationOnlyUsesPublicClientAndCopiesLocalUsername() {
        val f = Fixture(); f.official()
        f.controller.openAppreciation(); f.finish()
        assertEquals(1, f.clients.publicCalls)
        assertEquals(0, f.clients.privateConstructs)
        assertEquals(0, f.clients.entitlementCalls)
        assertEquals(0, f.ports.probes)
        assertEquals(0, f.ports.saves)
        assertFalse(f.controller.state.appreciation!!.authenticated)
        assertTrue(f.clients.requests.all { it.header("Authorization") == null })
        f.controller.copyUsername()
        assertEquals("alice", f.ports.copied)
        f.controller.back()
        assertNull(f.controller.state.appreciation)
    }

    @Test fun backgroundCancelsAppreciationAndLateReplyCannotRevealIdentity() {
        val f = Fixture(); f.official()
        f.controller.openAppreciation()
        f.controller.foreground(false); f.finish()
        assertNull(f.controller.state.appreciation!!.username)
        assertEquals(0, f.clients.publicCalls)
        f.controller.copyUsername()
        assertNull(f.ports.copied)
        f.controller.foreground(true)
        assertTrue(f.controller.state.appreciation!!.canRefresh)
    }

    @Test fun appreciationOnlyBackReturnsToSettings() {
        val existing = SharedConnectionConfig(ManagedAccountClient.DAV_URL, "alice", "secret-password", "slot", true)
        val f = Fixture(existing, ConnectionEntryModel.Origin.SETTINGS)
        f.controller.openAppreciationOnly(); f.controller.back(); f.finish()
        assertEquals(listOf(ConnectionEntryModel.Back.SETTINGS), f.host.backs)
        assertEquals(0, f.clients.publicCalls)
    }

    @Test fun elapsedExpiryInvalidatesOfficialProofBeforeSave() {
        val f = Fixture(); f.official()
        f.controller.login(); f.finish()
        assertTrue(f.controller.state.canContinue)
        f.ports.elapsed = 9001L
        f.controller.continueConnection(); f.finish()
        assertEquals(0, f.ports.saves)
        assertFalse(f.controller.state.canContinue)
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

    @Test fun backgroundOfficialAccountCanRetestWithoutReplayingRegistration() {
        val f = Fixture(); f.official()
        f.controller.register(); f.finish()
        f.controller.foreground(false); f.controller.foreground(true)
        assertTrue(f.controller.state.canRetestOfficial)
        f.controller.testConnection(); f.finish()
        assertEquals(1, f.clients.authCalls)
        assertEquals(2, f.clients.entitlementCalls)
        assertTrue(f.controller.state.canContinue)
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

    @Test fun authenticationAndDavShareOneDiagnosticOperationWithoutSecrets() {
        val directory = Files.createTempDirectory("shared-connection-diagnostics-")
        DiagnosticLog.configure(directory, "1.0", "linux")
        val f = Fixture(); f.official()
        f.ports.onProbe = { DiagnosticLog.http(DiagnosticHttpMethod.PROPFIND, 207) }
        f.controller.login(); f.finish()
        val lines = diagnosticLines(directory)
        val ids = lines.mapNotNull { Regex("\"operationId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }.toSet()
        assertEquals(1, ids.size)
        assertTrue(lines.any { it.contains("\"httpMethod\":\"POST\"") })
        assertTrue(lines.any { it.contains("\"httpMethod\":\"PROPFIND\"") })
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

}
