@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.AppConfig
import pdsync.ConfigFile
import pdsync.ConnectionSetup
import pdsync.DesktopConnectionAdapter
import pdsync.OnboardingProgress
import pdsync.newConnectionConfig
import kotlin.test.*

/** Real shared controller, production protocol parsing and desktop persistence; no external I/O. */
class SharedBuiltinConnectionFlowTest {
    @TempDir lateinit var temp: Path

    private class Clients : SharedOfficialClients {
        var authCalls = 0
        var entitlementCalls = 0
        var active = true
        var expired = false
        var reject = false
        override fun authentication() = OfficialServiceRegistrationClient { request ->
            authCalls++
            response(request, """{"code":200,"message":"ok","data":{"webdav_url":"https://webdav.xyz1001.top:8443/","username":"alice","quota_mb":1,"quota_bytes":1048576,"upload_bandwidth_kb":0,"download_bandwidth_kb":0}}""")
        }
        override fun account(config: SharedConnectionConfig) = ManagedAccountClient(config.endpoint, config.username, config.password,
            object : ManagedAccountClient.Transport {
                override fun execute(request: Request): Response {
                    check(request.url.toString() == ManagedAccountClient.ENTITLEMENT_URL)
                    entitlementCalls++
                    if (reject) return response(request, "{}", 401)
                    return response(request, """{"code":200,"data":{"source":"managed_ledger","username":"alice","expires_ms":1792353600000,"server_time_ms":${if (expired) 1792440000000 else 1792080000000},"active":$active,"managed":true}}""")
                }
            })
        override fun publicAppreciation(): ManagedAccountClient = error("No public metadata in this flow")
        private fun response(request: Request, json: String, status: Int = 200) = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }

    private inner class Page(initial: AppConfig?, val clients: Clients = Clients()) : AutoCloseable {
        val file = ConfigFile(temp.resolve("config.json"))
        var probes = 0
        var commits = 0
        val adapter = DesktopConnectionAdapter(initial, initial?.password.orEmpty(), file, temp.resolve("cache")) {
            ConnectionSetup(it, probe = { probes++; error("Built-in setup must never invoke a DAV probe") })
        }
        val controller = SharedConnectionController(ConnectionEntryModel.Origin.SETUP, adapter.initialConnection,
            adapter.profile, adapter, Executor { it.run() }, Executor { it.run() }, clients)
        init {
            controller.attach(object : SharedConnectionHost {
                override fun onBack(destination: ConnectionEntryModel.Back) {}
                override fun onCommitted(config: SharedConnectionConfig) { commits++ }
                override fun onBusyChanged(busy: Boolean) {}
            })
            controller.foreground(true)
        }
        fun login() {
            controller.choose(ConnectionEntryModel.Mode.OFFICIAL)
            controller.editUsername("alice")
            controller.editPassword("fixture-password")
            controller.login()
        }
        override fun close() { controller.close(); adapter.close() }
    }

    @Test fun authenticatedBuiltinNextReachesRealHostPersistenceWithoutDavAndRestartReauthenticates() {
        val initial = newConnectionConfig().copy(targetGame = "/fixture/game.jar", selectedSaveRoot = "/fixture/saves")
        val first = Page(initial)
        first.login()
        assertTrue(first.controller.state.canContinue)
        assertFalse(Files.exists(temp.resolve("config.json")))
        first.controller.continueConnection()
        assertEquals(1, first.commits)
        assertEquals(0, first.probes)
        assertNotNull(first.adapter.appliedConnection)
        val saved = assertNotNull(first.file.load())
        assertEquals("official", saved.connectionProvider)
        assertEquals(initial.targetGame, saved.targetGame)
        assertEquals(initial.selectedSaveRoot, saved.selectedSaveRoot)
        assertEquals(OnboardingProgress.PENDING, saved.onboardingProgress)
        first.close()
        Page(saved).use { returned ->
            assertEquals(0, returned.clients.authCalls)
            assertEquals(1, returned.clients.entitlementCalls)
            assertTrue(returned.controller.state.officialAuthenticated)
            assertTrue(returned.controller.state.canContinue)
            returned.controller.continueConnection()
            assertEquals(1, returned.commits)
            assertEquals(0, returned.probes)
        }
    }

    @Test fun builtinAuthenticatedAndExpiredScreensRenderAtDesktopAndNarrowWidths() {
        for (expired in listOf(false, true)) {
            val clients = Clients().apply { this.expired = expired; active = !expired }
            Page(newConnectionConfig(), clients).use { page ->
                page.login()
                assertTrue(page.controller.state.showOfficialAccount)
                assertTrue(page.controller.state.canAppreciate)
                assertEquals(!expired, page.controller.state.canContinue)
                for (width in listOf(1165, 500, 320)) {
                    val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                        SharedConnectionScreen(page.controller, SharedGameIdentity("破碎的像素地牢", "3.3.8"))
                    }
                    try {
                        repeat(3) { scene.render() }
                        val pixels = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
                        val output = Path.of("build/ui-reference-builtin")
                        Files.createDirectories(output)
                        Files.write(output.resolve("builtin-${if (expired) "expired" else "active"}-$width.png"), pixels)
                    } finally { scene.close() }
                }
                assertEquals(0, page.probes)
                assertEquals(0, page.commits)
            }
        }
    }

    @Test fun sharedSourceOnlyRendersCustomTestAndAuthenticatedAppreciation() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("shared-ui/src/main/kotlin")) }
        val source = Files.readString(root.resolve("shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedConnectionScreen.kt"))
        assertTrue(source.contains("使用内置服务"))
        assertFalse(source.contains("使用官方服务"))
        assertEquals(1, Regex("SharedActionButton\\(\\\"测试连接\\\"").findAll(source).count())
        val authenticated = source.substringAfter("if (state.showOfficialAccount) {").substringBefore("} else {")
        assertTrue(authenticated.contains("state.officialExpiry"))
        assertTrue(authenticated.contains("state.appreciationLabel"))
        assertFalse(authenticated.contains("SharedTextField"))
        assertFalse(authenticated.contains("controller::register"))
    }
}
