@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
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

    private val syncExplanation = "通过云端服务在多设备之间保持进度一致。请选择您的存档同步服务："
    private val builtinCost = "服务说明：内置服务并非永久免费，赞赏用于平摊云端高可用服务器开销。\n新用户默认享 3 天免费试用。最低赞赏 5 元即可使用 1 整年。"

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
                setRGB(0, 0, 0xFF006877.toInt())
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
                    val serviceExpiry = if (renewed) 1795032000000 else 1792353600000
                    val rawExpiry = Instant.ofEpochMilli(serviceExpiry).atZone(ZoneOffset.UTC)
                        .plusYears(1).toInstant().toEpochMilli()
                    return response(request, """{"code":200,"data":{"source":"managed_ledger","username":"alice","expires_ms":$rawExpiry,"server_time_ms":${if (expired) 1792440000000 else 1792080000000},"active":${active || renewed},"status":${if (!active && !expired && !renewed) 0 else 1},"managed":true}}""")
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
                assertEquals(active || expired, page.controller.state.canContinue)
                for (width in listOf(1280, 390, 320)) for (androidSave in listOf(false, true)) {
                    val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                        SharedConnectionScreen(page.controller, SharedGameIdentity("破碎的像素地牢", "3.3.8"),
                            onSaveImage = if (androidSave) ({}) else null)
                    }
                    try {
                        repeat(3) { scene.render().close() }
                        val labels = visibleTexts(scene)
                        assertTrue("alice" in labels)
                        assertTrue(page.controller.state.officialExpiry in labels)
                        assertTrue("连接云端" in labels)
                        assertTrue("第 2 步，共 2 步" in labels)
                        assertFalse("下一步" in labels)
                        assertBuiltinDisclosure(scene, width)
                        assertFooterFits(scene, width)
                        assertEquals(1, labels.count { it == "alice" })
                        assertEquals(1, labels.count { it == page.controller.state.officialExpiry })
                        assertFalse(labels.any { it == "赞赏" || it == "赞赏续期" },
                            "Step 3 keeps appreciation inline without duplicate account blocks")
                        assertEquals(1, labels.count { it == "刷新" }, "Approved refresh is available inline")
                        assertTrue("赞赏时请备注用户名 alice" in labels)
                        assertEquals(androidSave, "保存赞赏码图片" in labels)
                        val nodes = semanticsNodes(scene)
                        assertTrue(nodes.any { "赞赏码" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                        assertEquals(!active && !expired, button(nodes, "完成配置").config.contains(SemanticsProperties.Disabled))
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
        for (renewed in listOf(false, true)) for (width in listOf(1280, 390, 320)) {
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
                        assertTrue("第 2 步，共 2 步" in labels)
                        assertFalse("下一步" in labels)
                    assertTrue("alice" in labels)
                    assertTrue(page.controller.state.officialExpiry in labels)
                    assertBuiltinDisclosure(scene, width)
                    assertFooterFits(scene, width)
                    assertTrue("赞赏时请备注用户名 alice" in labels)
                    assertTrue("赞赏码暂时无法加载。" in labels)
                    assertEquals(!renewed, "服务已到期" in labels)
                    assertFalse(button(semanticsNodes(scene), "完成配置").config.contains(SemanticsProperties.Disabled))
                    assertTrue(page.controller.state.canContinue)
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
                    assertTrue("赞赏时请备注用户名 alice" in visibleTexts(scene))
                    assertBuiltinDisclosure(scene, 500)
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

    @Test fun serviceChoiceExplainsWebdavAndSwitchingModesKeepsDisclosuresScopedWithoutRequests() {
        for (width in listOf(1280, 390, 320)) {
            Page(null).use { page ->
                val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                    SharedConnectionScreen(page.controller, onSaveImage = {})
                }
                try {
                    repeat(2) {
                        repeat(3) { scene.render().close() }
                        assertEquals(ConnectionEntryModel.Mode.CHOICE, page.controller.state.mode)
                        assertCompleteTextLayout(scene, syncExplanation, width)
                        assertNoBuiltinDisclosure(scene)
                        assertEquals(setOf("上一步", "完成配置", "使用内置服务", "配置 WebDAV"), buttonLabels(scene))
                        assertTrue("第 2 步，共 2 步" in visibleTexts(scene))
                        assertFalse("下一步" in visibleTexts(scene))
                        assertFooterFits(scene, width)
                        saveScreenshot(scene, "service-choice-$width")

                        page.controller.choose(ConnectionEntryModel.Mode.OFFICIAL)
                        repeat(3) { scene.render().close() }
                        assertBuiltinDisclosure(scene, width)
                        assertFalse(syncExplanation in visibleTexts(scene))
                        page.controller.showChooser()
                        repeat(3) { scene.render().close() }
                        assertCompleteTextLayout(scene, syncExplanation, width)
                        assertNoBuiltinDisclosure(scene)

                        page.controller.choose(ConnectionEntryModel.Mode.CUSTOM)
                        repeat(3) { scene.render().close() }
                        assertNoBuiltinDisclosure(scene)
                        assertFalse(syncExplanation in visibleTexts(scene))
                        assertFooterFits(scene, width)
                        clickButton(scene, "上一步")
                        repeat(3) { scene.render().close() }
                        assertEquals(ConnectionEntryModel.Mode.CHOICE, page.controller.state.mode)
                    }
                    assertFalse(page.controller.state.showInlineAppreciation)
                    assertNoRequestsOrWrites(page)
                } finally { scene.close() }
            }
        }
    }

    @Test fun customAndUnloggedInBuiltinDisclosuresDoNotLoadAppreciationOnRenderingEditingOrFocus() {
        for (width in listOf(1280, 390, 320)) {
            for (mode in listOf(ConnectionEntryModel.Mode.CUSTOM, ConnectionEntryModel.Mode.OFFICIAL)) {
                Page(null).use { page ->
                    page.controller.choose(mode)
                    val scene = ImageComposeScene(width = width, height = 757, density = Density(1f)) {
                        SharedConnectionScreen(page.controller, onSaveImage = {})
                    }
                    try {
                        repeat(3) { scene.render().close() }
                        repeat(3) { iteration ->
                            val labels = visibleTexts(scene)
                            assertTrue("第 2 步，共 2 步" in labels)
                            assertFalse("下一步" in labels)
                            if (mode == ConnectionEntryModel.Mode.CUSTOM) {
                                assertTrue("WebDAV 服务器地址" in labels)
                                assertTrue("测试连接" in labels)
                                assertNoBuiltinDisclosure(scene)
                                assertFalse(labels.any { it.contains("赞赏") || it.contains("试用") })
                                assertEquals(setOf("上一步", "完成配置", "测试连接", "切换至其他服务类型"), buttonLabels(scene))
                            } else {
                                assertBuiltinDisclosure(scene, width)
                                assertTrue("登录账号" in labels)
                                assertTrue("注册新用户" in labels)
                                assertEquals(setOf("上一步", "完成配置", "登录账号", "注册新用户", "切换至其他服务类型"), buttonLabels(scene))
                            }
                            assertFalse(labels.any { it == "赞赏" || it == "赞赏续期" || it == "保存图片" })
                            assertFalse(semanticsNodes(scene).any {
                                "赞赏码" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                            })
                            assertFalse(page.controller.state.showInlineAppreciation)
                            assertTrue(button(semanticsNodes(scene), "完成配置").config.contains(SemanticsProperties.Disabled))
                            assertFooterFits(scene, width)
                            saveScreenshot(scene, "service-${mode.name.lowercase()}-unlogged-$width")

                            // Real Compose field focus and draft changes must remain local UI work.
                            focusField(scene, "用户名")
                            page.controller.editUsername("draft-$iteration")
                            repeat(3) { scene.render().close() }
                            focusField(scene, "密码凭据")
                            page.controller.editPassword("fixture-password-$iteration")
                            page.controller.foreground(false)
                            repeat(3) { scene.render().close() }
                            page.controller.foreground(true)
                            repeat(3) { scene.render().close() }
                            assertNoRequestsOrWrites(page)
                        }
                    } finally { scene.close() }
                }
            }
        }
    }

    private fun assertBuiltinDisclosure(scene: ImageComposeScene, width: Int) {
        assertCompleteTextLayout(scene, builtinCost, width)
        assertFalse(syncExplanation in visibleTexts(scene))
    }

    private fun assertNoBuiltinDisclosure(scene: ImageComposeScene) {
        val labels = visibleTexts(scene)
        assertFalse(builtinCost in labels)
    }

    /** Semantics alone retain truncated strings, so also inspect the actual rendered text layout. */
    private fun assertCompleteTextLayout(scene: ImageComposeScene, expected: String, width: Int) {
        val node = semanticsNodes(scene).single {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == expected }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        val getLayout = assertNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
        assertTrue(getLayout(layouts))
        val layout = layouts.single()
        assertEquals(expected, layout.layoutInput.text.text)
        val diagnostic = "viewport=$width, text=$expected, size=${layout.size}, " +
            "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, bounds=${node.boundsInRoot}, " +
            "constraints=${layout.layoutInput.constraints}, widthOverflow=${layout.didOverflowWidth}, " +
            "heightOverflow=${layout.didOverflowHeight}, exceedMaxLines=${layout.multiParagraph.didExceedMaxLines}, " +
            "lines=" + (0 until layout.lineCount).joinToString { line ->
                "[$line: ${layout.getLineStart(line)}..${layout.getLineEnd(line)}, " +
                    "x=${layout.getLineLeft(line)}..${layout.getLineRight(line)}, " +
                    "y=${layout.getLineTop(line)}..${layout.getLineBottom(line)}, " +
                    "ellipsis=${layout.isLineEllipsized(line)}]"
            }
        // Desktop paragraphs retain the available line-layout width while Text shrink-wraps to
        // the actual line widths (e.g. paragraph=510, Text=288, lineRight=287.25). Consequently
        // didOverflowWidth/hasVisualOverflow can be true without any clipped text. Check actual
        // line extents against Text's measured size instead; allow only one pixel of rounding.
        assertFalse(layout.didOverflowHeight, "Disclosure must not lose vertical content: $diagnostic")
        assertFalse(layout.multiParagraph.didExceedMaxLines, "Disclosure must lay out every line: $diagnostic")
        assertTrue(layout.lineCount > 0)
        assertEquals(expected.length, layout.getLineEnd(layout.lineCount - 1), "All copy must be laid out")
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line), diagnostic)
            assertTrue(layout.getLineLeft(line) >= -1 && layout.getLineRight(line) <= layout.size.width + 1,
                "Every line must fit the actual Text width: $diagnostic")
            assertTrue(layout.getLineTop(line) >= -1 && layout.getLineBottom(line) <= layout.size.height + 1,
                "Every line must fit the actual Text height: $diagnostic")
        }
        if (width == 320 && expected == builtinCost) assertTrue(layout.lineCount > 1, "Narrow copy must wrap")
        val bounds = node.boundsInRoot
        assertTrue(bounds.left >= 0 && bounds.right <= width,
            "Disclosure must stay within the horizontal viewport: $bounds at $width px")
        assertTrue(bounds.width + 1 >= layout.size.width && bounds.height + 1 >= layout.size.height,
            "Disclosure must not be clipped by its scroll viewport: $bounds vs ${layout.size}")
    }

    private fun focusField(scene: ImageComposeScene, label: String) {
        val field = semanticsNodes(scene).single {
            label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                it.config.contains(SemanticsActions.SetText)
        }
        assertTrue(assertNotNull(field.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
        repeat(3) { scene.render().close() }
        assertTrue(semanticsNodes(scene).any {
            label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                it.config.getOrNull(SemanticsProperties.Focused) == true
        }, "$label field should really receive Compose focus")
    }

    private fun assertFooterFits(scene: ImageComposeScene, width: Int) {
        val nodes = semanticsNodes(scene)
        for (label in listOf("上一步", "完成配置").filter { it in visibleTexts(scene) }) {
            val bounds = button(nodes, label).boundsInRoot
            assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 757,
                "$label footer must stay within the viewport at $width px: $bounds")
            assertTrue(bounds.height >= 42, "$label footer must retain its full touch target: $bounds")
        }
    }

    private fun clickButton(scene: ImageComposeScene, label: String) {
        val node = button(semanticsNodes(scene), label)
        assertFalse(node.config.contains(SemanticsProperties.Disabled))
        scene.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center)
        scene.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center)
    }

    private fun buttonLabels(scene: ImageComposeScene): Set<String> = semanticsNodes(scene)
        .filter { it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsActions.SetText) }
        .flatMap { descendants(it) }
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        .map { it.text }.toSet()

    private fun assertNoRequestsOrWrites(page: Page) {
        assertEquals(0, page.clients.authCalls)
        assertEquals(0, page.clients.entitlementCalls)
        assertEquals(0, page.clients.metadataCalls)
        assertEquals(0, page.clients.imageCalls)
        assertEquals(0, page.probes)
        assertEquals(0, page.commits)
        assertFalse(Files.exists(temp.resolve("config.json")))
    }

    private fun saveScreenshot(scene: ImageComposeScene, name: String) {
        val output = Path.of("build/ui-reference-builtin", "$name.png")
        Files.createDirectories(output.parent)
        scene.render().use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> Files.write(output, data.bytes) }
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
        assertTrue(source.contains("SharedMessages.USE_BUILT_IN.text()"))
        assertEquals("使用内置服务", pdsync.i18n.SharedMessages.USE_BUILT_IN.chinese())
        assertFalse(source.contains("使用官方服务"))
        assertTrue(source.contains("ConnectionEntryModel.Mode.CUSTOM -> Column"))
        assertTrue(source.contains("controller::testConnection"))
        val authenticated = source.substringAfter("internal fun SharedOfficialAccountPanel(").substringBefore("private fun SharedAccountAppreciation(")
        assertTrue(authenticated.contains("state.officialExpiry"))
        assertTrue(authenticated.contains("state.officialAuthenticated"))
        assertTrue(authenticated.contains("if (state.showInlineAppreciation)"))
        assertTrue(source.contains("LaunchedEffect(controller, state.showInlineAppreciation, state.appreciation == null)"))
        assertTrue(authenticated.contains("SharedAccountAppreciation"))
        assertTrue(authenticated.contains("state.appreciationLabel"))
        assertFalse(source.contains("[赞赏码示意]"))
        assertFalse(authenticated.contains("SharedTextField"))
        assertFalse(authenticated.contains("controller::register"))
    }
}
