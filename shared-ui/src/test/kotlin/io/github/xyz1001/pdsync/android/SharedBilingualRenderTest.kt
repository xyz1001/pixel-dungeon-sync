@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package io.github.xyz1001.pdsync.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executor
import org.jetbrains.skia.EncodedImageFormat
import pdsync.OnboardingKind
import pdsync.SaveSummary
import pdsync.i18n.CoreMessages
import pdsync.i18n.SharedMessages
import pdsync.i18n.UiLocale
import pdsync.presentation.HistoryNode
import pdsync.presentation.HistoryPresentation
import kotlin.test.*

/** Production Compose only. Synthetic metadata; no network, credentials, account or save writes. */
class SharedBilingualRenderTest {
    private var frame = 0L
    private val game = SharedGameIdentity("Shattered Pixel Dungeon Extended Adventure Edition", "3.3.8")
    private val longName = "A very long expedition save name across several dungeon floors"
    private val history get() = HistoryPresentation(listOf(
        HistoryNode("commit-a", "snapshot-a", longName, HistoryPresentation.time("2026-10-19T09:30:00Z"), "Desktop · " + CoreMessages.TEXT_1_S_SLOTS.text(2), CoreMessages.CURRENT_CLOUD_BRANCH.text(), 0, 0, selectable = true),
        HistoryNode("local-current", null, "Current local expedition", HistoryPresentation.time("2026-10-19T10:30:00Z"), CoreMessages.TEXT_1_S_SLOTS.text(2) + " · " + HistoryPresentation.plays(28), CoreMessages.NOT_SYNCED_TO_CLOUD.text(), 1, 0, dirty = true)
    ), emptyList(), 0, 1, 2, 2, false)

    @Test fun productionScreensRenderChineseEnglishAndFallbackAtNarrowLargeFontAndRtl() {
        val previous = Locale.getDefault()
        val failures = mutableListOf<String>()
        try {
            for (tag in listOf("zh-CN", "en-US", "ar")) {
                // Process-only test override models the host passing its OS language configuration.
                // No device or operating-system settings are changed.
                UiLocale.setLocale(Locale.forLanguageTag(tag))
                assertEquals(tag.startsWith("zh"), UiLocale.isChinese())
                for ((width, scale) in listOf(960 to 1f, 360 to 1f, 360 to 1.3f)) {
                    for (screen in listOf("connection", "credentials", "game", "comparison", "branches", "home",
                        "history", "settings", "save-path", "confirmation", "naming", "appreciation",
                        "picker-empty", "picker-list", "home-loading")) {
                        val controller = controller()
                        if (screen == "credentials") controller.choose(ConnectionEntryModel.Mode.OFFICIAL)
                        var observedDirection: LayoutDirection? = null
                        val scene = ImageComposeScene(width = width, height = 1200, density = Density(1f, scale)) {
                            CompositionLocalProvider(LocalLayoutDirection provides
                                if (tag == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                SharedConnectionTheme {
                                    observedDirection = LocalLayoutDirection.current
                                    screen(screen, controller)
                                }
                            }
                        }
                        val id = "$screen-$tag-$width-font$scale"
                        try {
                            repeat(12) { frame += 16_666_667L; scene.render(frame).close() }
                            screenshot(scene, id)
                            assertEquals(LayoutDirection.Ltr, observedDirection, "$id renders English/Chinese safely LTR even under an RTL host")
                            val nodes = nodes(scene)
                            val texts = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                            assertTrue(texts.isNotEmpty(), "$id contains production text")
                            if (tag != "zh-CN") assertFalse(texts.any { Regex("[\\u4e00-\\u9fff]").containsMatchIn(it) },
                                "$id must not contain untranslated Chinese: $texts")
                            val expected = when (screen) {
                                "connection", "credentials" -> SharedMessages.CONNECT_CLOUD.text()
                                "game" -> SharedMessages.CHOOSE_GAME.text()
                                "picker-empty", "picker-list" -> SharedMessages.CHOOSE_MANAGED_GAME.text()
                                "home-loading" -> SharedMessages.LOADING.text()
                                "settings" -> SharedMessages.SETTINGS_CENTER.text()
                                "history", "home" -> SharedMessages.SAVE_HISTORY.text()
                                "save-path" -> SharedMessages.SAVE_PATH.text()
                                "confirmation" -> SharedMessages.CONFLICT_TITLE.text()
                                "naming" -> SharedMessages.SAVE_NAME.text()
                                "appreciation" -> SharedMessages.COPY_USERNAME.text()
                                "comparison" -> SharedMessages.CHOOSE_SAVE.text()
                                else -> SharedMessages.CHOOSE_CLOUD_SAVE.text()
                            }
                            assertTrue(texts.any { it == expected }, "$id expected '$expected'; got $texts")
                            if (screen == "appreciation") {
                                assertTrue(SharedMessages.REFRESH.text() in texts, "$id explicit refresh action")
                                val refresh = nodes.single { node ->
                                    node.config.contains(SemanticsActions.OnClick) &&
                                        containsText(node, SharedMessages.REFRESH.text())
                                }
                                assertFalse(refresh.config.contains(SemanticsProperties.Disabled),
                                    "$id refresh is available when the account permits it")
                            }
                            if (screen.startsWith("picker-")) {
                                assertTrue(SharedMessages.SEARCH_APPS.text() in texts, "$id search label")
                                assertTrue(SharedMessages.SHOW_ALL_APPS.text() in texts, "$id existing filter")
                                assertTrue(SharedMessages.CANCEL.text() in texts, "$id dismiss action")
                                if (screen == "picker-empty") assertTrue(SharedMessages.NO_MATCHING_APPS.text() in texts)
                                else assertTrue(game.name in texts, "$id real supplied candidate name")
                            }
                            for (node in nodes) {
                                if (node.config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true) {
                                    assertTrue(node.boundsInRoot.left >= -1f && node.boundsInRoot.right <= width + 1f,
                                        "$id text outside horizontal viewport: ${node.boundsInRoot}")
                                }
                                if (node.config.contains(SemanticsProperties.EditableText)) continue
                                val layouts = mutableListOf<TextLayoutResult>()
                                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                                for (layout in layouts) {
                                    val label = layout.layoutInput.text.text
                                    if (label.isEmpty()) continue
                                    // Desktop shrink-wrap keeps a wider paragraph constraint. Validate
                                    // actual glyph lines rather than didOverflowWidth (see existing dialog tests).
                                    assertFalse(layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines,
                                        "$id vertical overflow: '$label'")
                                    for (line in 0 until layout.lineCount) {
                                        assertFalse(layout.isLineEllipsized(line), "$id ellipsized: '$label'")
                                        assertTrue(layout.getLineLeft(line) >= -1f &&
                                            layout.getLineRight(line) <= layout.size.width + 1f,
                                            "$id glyph line outside text width: '$label'")
                                        assertTrue(layout.getLineTop(line) >= -1f &&
                                            layout.getLineBottom(line) <= layout.size.height + 1f,
                                            "$id glyph line outside text height: '$label'; line=$line top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)} baseline=${layout.getLineBaseline(line)} size=${layout.size} bounds=${node.boundsInRoot} style=${layout.layoutInput.style}")
                                    }
                                }
                            }
                        } catch (failure: AssertionError) {
                            failures += "$id: ${failure.message}"
                        } finally { scene.close(); controller.close() }
                    }
                }
            }
        } finally { Locale.setDefault(previous); UiLocale.useSystemLocale() }
        Files.createDirectories(Path.of("../../code76-evidence"))
        Files.writeString(Path.of("../../code76-evidence/bilingual-render-assertions.txt"),
            if (failures.isEmpty()) "PASS: 135 production screen renders; zh-CN, en-US, ar system fallback under RTL host, safe LTR content; 960dp, 360dp, 360dp at 1.3 font scale.\n" else failures.joinToString("\n"))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Composable private fun screen(name: String, connection: SharedConnectionController) {
        when (name) {
            "connection", "credentials" -> SharedConnectionScreen(connection)
            "game" -> SharedGameSelectionScreen(SharedGameSelectionState(game = game,
                selectionId = "/games/selected.jar", location = "/games/selected.jar", pageActive = true, canContinue = true),
                object : SharedGameSelectionActions {
                    override fun perform(control: SharedGameSelectionControl) = Unit
                    override fun chooseCandidate(id: String) = Unit
                    override fun next() = Unit
                })
            "picker-empty", "picker-list" -> SharedGamePickerDialog(
                if (name == "picker-empty") emptyList() else listOf(
                    SharedGameCandidate("game.1", game, "io.example.synthetic.long.game.package"),
                    SharedGameCandidate("game.2", SharedGameIdentity("Another Pixel Dungeon Adventure", "1.2.3"), "io.example.another.game")
                ), selectedId = "game.1", enabled = true, onChoose = {}, onDismiss = {},
                searchQuery = "", noSearchMatches = name == "picker-empty")
            "comparison" -> SharedComparisonScreen(SharedComparisonState(OnboardingKind.DIFFERENT,
                cloudVerified = true, pageActive = true, configured = true, restoreSupported = true, game = game,
                localLabel = "Local · Desktop", localDetails = "28 games played",
                remoteChoices = listOf(SharedComparisonRemoteChoice("remote-a", "Cloud · Android", "30 games played")), selectedId = "remote-a"),
                object : SharedComparisonActions {
                    override fun back() = Unit
                    override fun refresh() = Unit
                    override fun selectProgress(choice: SharedProgressChoice) = Unit
                    override fun selectRemote(snapshotId: String) = Unit
                    override fun finish(mode: SharedComparisonCompletion) = Unit
                    override fun handleConflict() = Unit
                    override fun upload() = Unit
                    override fun openRecovery() = Unit
                })
            "branches" -> SharedBranchChoiceScreen(SharedBranchChoiceState(Any(), listOf(
                SharedBranchChoice("snapshot-a", longName, "Desktop · 2 slots · 28 games played"),
                SharedBranchChoice("snapshot-b", "Another cloud expedition", "Android · 3 slots · 30 games played")),
                selectedId = "snapshot-a", game = game, pageActive = true, connected = true,
                observationCurrent = true, overwriteSupported = true),
                object : SharedBranchChoiceActions {
                    override fun select(snapshotId: String) = Unit
                    override fun back() = Unit
                    override fun refresh() = Unit
                    override fun overwrite(snapshotId: String) = Unit
                    override fun preserveLocal() = Unit
                })
            "home", "home-loading" -> SharedHomeScreen(SharedHomeState(game, SharedMessages.PROGRESS_SYNCED.text(), pageActive = true,
                archiveLoading = name == "home-loading", canUpload = true, canRestore = true, canLaunch = true, history = history,
                restoreTarget = SharedHomeRestoreTarget("snapshot-a", longName)), object : SharedHomeActions {
                    override fun settings() = Unit
                    override fun upload() = Unit
                    override fun restore() = Unit
                    override fun launch() = Unit
                    override fun selectSnapshot(snapshotId: String) = Unit
                    override fun rename(snapshotId: String?) = Unit
                    override fun changeHistoryPage(page: Int) = Unit
                })
            "history" -> SharedAppShell(SharedMessages.SAVE_HISTORY.text()) {
                SharedHistorySection(history, null, false, {}, {}, {})
            }
            "settings" -> SharedSettingsScreen(SharedSettingsState(game, listOf(
                SharedSettingsRow(SharedSettingsKey.GAME, SharedMessages.CHOOSE_GAME.text(), game.summary,
                    SharedMessages.CHANGE_GAME.text(), true),
                SharedSettingsRow(SharedSettingsKey.SAVE_PATH, SharedMessages.SAVE_LOCATION.text(),
                    "/home/player/very-long-selected-game-save-directory/files", SharedMessages.CHANGE_PATH.text(), true)
            ), pageActive = true), object : SharedSettingsActions {
                override fun perform(key: SharedSettingsKey) = Unit
                override fun back() = Unit
            })
            "save-path" -> SharedSavePathScreen(SharedSavePathState(
                "/home/player/very-long-selected-game-save-directory/files", false,
                SaveSummary(mapOf("game1" to "2026-10-19T09:30:00Z"), 28, 30),
                readStatus = SharedSavePathReadStatus.READY, canContinue = true), object : SharedSavePathActions {
                override fun editPath(path: String) = Unit
                override fun chooseDirectory() = Unit
                override fun useDefault() = Unit
                override fun retry() = Unit
                override fun initializeGame() = Unit
                override fun back() = Unit
                override fun next() = Unit
            }, game)
            "confirmation" -> SharedConfirmationDialogBody(SharedConfirmationState(1,
                SharedMessages.CONFLICT_TITLE.text(), SharedMessages.USE_SELECTED_WARNING.text(),
                SharedMessages.USE_SELECTED.text(), confirmEnabled = true,
                secondaryLabel = null, secondaryEnabled = false), {}, onDismiss = {}, modifier = Modifier.fillMaxWidth())
            "naming" -> SharedNamingDialogBody(SharedNamingState(1, ""), {}, {}, {}, Modifier.fillMaxWidth())
            "appreciation" -> SharedAppShell(SharedMessages.SUPPORT_RENEW_YEAR.text()) {
                SharedAppreciationContent(SharedAppreciationState("layout-user", true, "2027-10-19", "", "", null, false, true), {},
                    refresh = {})
            }
        }
    }

    private fun controller() = SharedConnectionController(ConnectionEntryModel.Origin.SETUP, null, "slot",
        object : SharedConnectionPorts {
            override fun testConnection(config: SharedConnectionConfig): Boolean = error("No network")
            override fun saveAndActivate(config: SharedConnectionConfig) = error("No persistence")
            override fun cacheDirectory(): Path? = null
            override fun elapsedRealtimeMs() = 0L
            override fun bootCount() = 1
            override fun copyUsername(username: String) = Unit
            override fun decodeImage(bytes: ByteArray) = null
        }, Executor { error("No async work") }, Executor { it.run() })
    private fun screenshot(scene: ImageComposeScene, name: String) {
        val dir = Path.of("../../code76-evidence/bilingual")
        Files.createDirectories(dir)
        scene.render(frame + 16_666_667L).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(dir.resolve("$name.png"), it.bytes) }
        }
    }
    private fun containsText(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.children.any { containsText(it, label) }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val layers = backing.javaClass.getDeclaredField("layers").run { isAccessible = true; get(backing) } as List<*>
        val roots = listOf(root) + layers.map { layer ->
            requireNotNull(layer).javaClass.getDeclaredField("owner").run { isAccessible = true; get(layer) }
        }
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return roots.flatMap { item ->
            val owner = item.javaClass.getMethod("getSemanticsOwner").invoke(item) as SemanticsOwner
            descend(owner.unmergedRootSemanticsNode)
        }
    }
}
