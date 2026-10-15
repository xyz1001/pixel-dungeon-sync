@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.security.MessageDigest
import javax.imageio.ImageIO
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
        var metadataCalls = 0
        var imageCalls = 0
        var active = true
        var expired = false
        var reject = false
        var imageFailure = false
        var renewOnAppreciation = false
        private val image = ByteArrayOutputStream().use { out ->
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).apply {
                setRGB(0, 0, 0xFF326650.toInt())
            }, "png", out)
            out.toByteArray()
        }
        override fun authentication() = OfficialServiceRegistrationClient { request ->
            authCalls++
            response(request, """{"code":200,"message":"ok","data":{"webdav_url":"https://webdav.xyz1001.top:8443/","username":"alice","quota_mb":1,"quota_bytes":1048576,"upload_bandwidth_kb":0,"download_bandwidth_kb":0}}""")
        }
        override fun account(config: SharedConnectionConfig) = ManagedAccountClient(config.endpoint, config.username, config.password,
            object : ManagedAccountClient.Transport {
                override fun execute(request: Request): Response {
                    if (request.url.toString() == ManagedAccountClient.APPRECIATION_URL) {
                        metadataCalls++
                        if (imageFailure) return response(request, "{}", 503)
                        val hash = MessageDigest.getInstance("SHA-256").digest(image)
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        return response(request, """{"code":200,"data":{"version":1,"payment_mode":"donation","image_path":"${ManagedAccountClient.IMAGE_PATH}","image_sha256":"$hash","term":"calendar_year","remark":"username"}}""")
                    }
                    if (request.url.toString() == ManagedAccountClient.IMAGE_URL) {
                        imageCalls++
                        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(200).message("fixture").body(image.toResponseBody("image/png".toMediaType())).build()
                    }
                    check(request.url.toString() == ManagedAccountClient.ENTITLEMENT_URL)
                    entitlementCalls++
                    if (reject) return response(request, "{}", 401)
                    val renewed = renewOnAppreciation && entitlementCalls > 1
                    return response(request, """{"code":200,"data":{"source":"managed_ledger","username":"alice","expires_ms":${if (renewed) 1795032000000 else 1792353600000},"server_time_ms":${if (expired) 1792440000000 else 1792080000000},"active":${active || renewed},"managed":true}}""")
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
        val backs = mutableListOf<ConnectionEntryModel.Back>()
        val adapter = DesktopConnectionAdapter(initial, initial?.password.orEmpty(), file, temp.resolve("cache")) {
            ConnectionSetup(it, probe = { probes++; error("Built-in setup must never invoke a DAV probe") })
        }
        val controller = SharedConnectionController(ConnectionEntryModel.Origin.SETUP, adapter.initialConnection,
            adapter.profile, adapter, Executor { it.run() }, Executor { it.run() }, clients)
        init {
            controller.attach(object : SharedConnectionHost {
                override fun onBack(destination: ConnectionEntryModel.Back) { backs += destination }
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
        assertEquals(OnboardingProgress.COMPLETE, saved.onboardingProgress)
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

    @Test fun builtinAuthenticatedActiveInactiveAndExpiredScreensRenderInlineAppreciationAtEveryWidth() {
        for ((expired, active) in listOf(false to true, true to false, false to false)) {
            val clients = Clients().apply { this.expired = expired; this.active = active }
            Page(newConnectionConfig(), clients).use { page ->
                page.login()
                assertTrue(page.controller.state.showOfficialAccount)
                assertTrue(page.controller.state.canAppreciate)
                assertEquals(active && !expired, page.controller.state.canContinue)
                for (width in listOf(1165, 500, 320)) for (androidSave in listOf(false, true)) {
                    val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                        SharedConnectionScreen(page.controller, SharedGameIdentity("破碎的像素地牢", "3.3.8"),
                            onSaveImage = if (androidSave) ({}) else null)
                    }
                    try {
                        repeat(3) { scene.render().close() }
                        val labels = visibleTexts(scene)
                        assertTrue("用户名 alice" in labels)
                        assertTrue(page.controller.state.officialExpiry in labels)
                        assertTrue("连接云端" in labels)
                        assertTrue("3 / 3" in labels)
                        assertFalse("下一步" in labels)
                        assertEquals(1, labels.count { it == "用户名 alice" })
                        assertEquals(1, labels.count { it == page.controller.state.officialExpiry })
                        assertFalse(labels.any { it == "赞赏" || it == "赞赏续期" || it == "复制用户名" || it.contains("刷新") },
                            "Step 3 adds no appreciation navigation, duplicate account block, or refresh action")
                        assertTrue("赞赏可续期一年" in labels)
                        assertEquals(androidSave, "保存图片" in labels)
                        val nodes = semanticsNodes(scene)
                        assertTrue(nodes.any { "赞赏码" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                        assertEquals(expired || !active, button(nodes, "完成").config.contains(SemanticsProperties.Disabled))
                        assertFalse(button(nodes, "上一步").config.contains(SemanticsProperties.Disabled))
                        assertTrue(page.controller.state.appreciationInline)
                        assertTrue(page.controller.state.showInlineAppreciation)
                        val pixels = scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
                        val output = Path.of("build/ui-reference-builtin")
                        Files.createDirectories(output)
                        Files.write(output.resolve("builtin-${if (expired) "expired" else if (active) "active" else "inactive"}-${if (androidSave) "android" else "desktop"}-$width.png"), pixels)
                    } finally { scene.close() }
                }
                assertEquals(0, page.probes)
                assertEquals(0, page.commits)
                assertEquals(2, clients.entitlementCalls)
                assertEquals(1, clients.metadataCalls)
                assertEquals(1, clients.imageCalls)
            }
        }
    }

    @Test fun renewedEntitlementKeepsStepThreeInlineAppreciationWhileImageFailureLeavesAccountVisible() {
        for (renewed in listOf(false, true)) for (width in listOf(1165, 500, 320)) {
            val clients = Clients().apply {
                expired = true; active = false; imageFailure = true; renewOnAppreciation = renewed
            }
            Page(newConnectionConfig(), clients).use { page ->
                page.login()
                assertTrue(page.controller.state.officialExpired)
                val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                    SharedConnectionScreen(page.controller)
                }
                try {
                    repeat(4) { scene.render().close() }
                    val labels = visibleTexts(scene)
                    assertTrue("连接云端" in labels)
                        assertTrue("3 / 3" in labels)
                        assertFalse("下一步" in labels)
                    assertTrue("用户名 alice" in labels)
                    assertTrue(page.controller.state.officialExpiry in labels)
                    assertTrue("赞赏可续期一年" in labels)
                    assertTrue("赞赏码暂时无法加载。" in labels)
                    assertEquals(!renewed, "服务已到期" in labels)
                    assertEquals(!renewed, button(semanticsNodes(scene), "完成").config.contains(SemanticsProperties.Disabled))
                    assertEquals(renewed, page.controller.state.canContinue)
                    assertTrue(page.controller.state.officialAuthenticated, "An image failure cannot revoke account authentication")
                    assertEquals(2, clients.metadataCalls)
                    assertEquals(2, clients.entitlementCalls)
                    assertEquals(0, page.probes)
                    assertEquals(0, page.commits)
                } finally { scene.close() }
            }
        }
    }

    @Test fun inlinePanelReloadsForExplicitReloginButNotRecompositionOrForeground() {
        Page(newConnectionConfig()).use { page ->
            page.login()
            val scene = ImageComposeScene(width = 500, height = 757, density = Density(1f)) {
                SharedConnectionScreen(page.controller)
            }
            try {
                repeat(3) { scene.render().close() }
                val firstScope = assertNotNull(page.controller.currentAppreciationScope())
                repeat(3) {
                    page.controller.foreground(false)
                    scene.render().close()
                    assertTrue(page.controller.state.showInlineAppreciation)
                    assertTrue("赞赏可续期一年" in visibleTexts(scene))
                    page.controller.foreground(true)
                    scene.render().close()
                }
                assertSame(firstScope, page.controller.currentAppreciationScope())
                assertEquals(1, page.clients.metadataCalls)
                assertEquals(1, page.clients.imageCalls)
                assertEquals(2, page.clients.entitlementCalls)
                // A synchronous re-login may coalesce account-cleared and account-ready UI states.
                // The cleared appreciation scope must still cause exactly one new inline load.
                page.controller.login()
                repeat(3) { scene.render().close() }
                assertNotSame(firstScope, page.controller.currentAppreciationScope())
                assertTrue(page.controller.state.appreciationInline)
                assertTrue(page.controller.state.canContinue)
                assertEquals(2, page.clients.authCalls)
                assertEquals(2, page.clients.metadataCalls)
                assertEquals(2, page.clients.imageCalls)
                assertEquals(4, page.clients.entitlementCalls)
                assertEquals(0, page.probes)
                assertEquals(0, page.commits)
            } finally { scene.close() }
        }
    }

    @Test fun customAndUnloggedInBuiltinConnectionsHaveNoInlineAppreciationContentOrRequests() {
        for (mode in listOf(ConnectionEntryModel.Mode.CUSTOM, ConnectionEntryModel.Mode.OFFICIAL)) {
            Page(null).use { page ->
                page.controller.choose(mode)
                val scene = ImageComposeScene(width = 320, height = 757, density = Density(1f)) {
                    SharedConnectionScreen(page.controller, onSaveImage = {})
                }
                try {
                    repeat(3) { scene.render().close() }
                    val labels = visibleTexts(scene)
                    if (mode == ConnectionEntryModel.Mode.CUSTOM) {
                        assertTrue("WebDAV 地址" in labels)
                        assertTrue("测试连接" in labels)
                    } else {
                        assertTrue("登录" in labels)
                        assertTrue("注册" in labels)
                    }
                    assertFalse(labels.any { it.contains("赞赏") || it == "保存图片" })
                    assertFalse(page.controller.state.showInlineAppreciation)
                    assertEquals(0, page.clients.authCalls)
                    assertEquals(0, page.clients.entitlementCalls)
                    assertEquals(0, page.clients.metadataCalls)
                    assertEquals(0, page.clients.imageCalls)
                } finally { scene.close() }
            }
        }
    }

    private fun semanticsNodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        return nodes(owner.unmergedRootSemanticsNode)
    }

    private fun visibleTexts(scene: ImageComposeScene): List<String> = semanticsNodes(scene).flatMap {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
        }

    private fun button(nodes: List<SemanticsNode>, label: String): SemanticsNode = nodes.single {
        it.config.contains(SemanticsActions.OnClick) &&
            descendants(it).any { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
            }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    @Test fun sharedSourceOnlyRendersCustomTestAndAuthenticatedAppreciation() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("shared-ui/src/main/kotlin")) }
        val source = Files.readString(root.resolve("shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android/SharedConnectionScreen.kt"))
        assertTrue(source.contains("使用内置服务"))
        assertFalse(source.contains("使用官方服务"))
        assertEquals(1, Regex("SharedActionButton\\(\\\"测试连接\\\"").findAll(source).count())
        val authenticated = source.substringAfter("if (state.showOfficialAccount) {").substringBefore("} else {")
        assertTrue(authenticated.contains("state.officialExpiry"))
        assertTrue(authenticated.contains("if (!setup) Row"), "Only settings retain the separate appreciation navigation")
        assertTrue(authenticated.contains("if (state.showInlineAppreciation)"))
        assertTrue(source.contains("LaunchedEffect(controller, state.showInlineAppreciation, state.appreciation == null)"))
        assertTrue(authenticated.contains("SharedInlineAppreciationContent"))
        assertTrue(authenticated.contains("state.appreciationLabel"))
        assertTrue(authenticated.contains("赞赏可续期一年"))
        assertEquals(1, Regex("赞赏可续期一年").findAll(source).count())
        assertFalse(authenticated.contains("SharedTextField"))
        assertFalse(authenticated.contains("controller::register"))
    }
}
