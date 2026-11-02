@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pdsync.OnboardingKind
import pdsync.presentation.HistoryEdge
import pdsync.presentation.HistoryNode
import pdsync.presentation.HistoryPresentation

/**
 * Production Compose at the reference's exact 1280x900 / 390x900 content viewports.
 * Data and action sinks are synthetic; these tests never contact a service, open saves or perform
 * a platform operation. This is shared-component evidence, not an Android emulator/native UI test.
 * Canonical reference filenames describe scenarios, not necessarily their displayed page: many
 * "*-initial" images still show game selection. Outputs here name the actual rendered screen.
 */
class SharedM3ReferenceRenderTest {
    private val widths = listOf(1280, 390)
    private val game = SharedGameIdentity("Shattered Pixel Dungeon", "3.3.8")

    @Test fun scaffoldPagesRenderAtReferenceViewportsWithoutPlatformSideEffects() {
        for (width in widths) {
            val calls = mutableListOf<String>()
            for (mode in ConnectionEntryModel.Mode.values()) {
                val controller = SharedConnectionController(ConnectionEntryModel.Origin.SETUP, null, "fixture",
                    object : SharedConnectionPorts {
                        override fun testConnection(config: SharedConnectionConfig): Boolean = error("No network in render tests")
                        override fun saveAndActivate(config: SharedConnectionConfig) = error("No credential persistence in render tests")
                        override fun cacheDirectory(): Path? = null
                        override fun elapsedRealtimeMs() = 0L
                        override fun bootCount() = 1
                        override fun copyUsername(username: String) { calls += "copy" }
                        override fun decodeImage(bytes: ByteArray) = null
                    }, Executor { error("No background account work in scaffold capture") }, Executor { it.run() })
                try {
                    if (mode != ConnectionEntryModel.Mode.CHOICE) controller.choose(mode)
                    RenderSession(width) { SharedConnectionScreen(controller, game) }.use {
                        it.capture("connect-${mode.name.lowercase()}")
                        assertTrue("连接云端" in it.texts())
                        assertTrue(it.texts().any { text -> text == "第 2 步，共 2 步" || text == "2 / 2" })
                    }
                } finally { controller.close() }
            }
            val gameActions = object : SharedGameSelectionActions {
                override fun perform(control: SharedGameSelectionControl) { calls += control.name }
                override fun chooseCandidate(id: String) { calls += id }
                override fun next() { calls += "next" }
            }
            RenderSession(width) {
                SharedGameSelectionScreen(SharedGameSelectionState(game = game, selectionId = "fixture-game",
                    rows = listOf(SharedGameSelectionRow(SharedGameSelectionControl.CHOOSE_FILE, "更换游戏…")),
                    pageActive = true, canContinue = true), gameActions)
            }.use { it.capture("game") }
            val settingsActions = object : SharedSettingsActions {
                override fun perform(key: SharedSettingsKey) { calls += key.name }
                override fun back() { calls += "back" }
            }
            RenderSession(width) {
                SharedSettingsScreen(SharedSettingsState(game, listOf(
                    SharedSettingsRow(SharedSettingsKey.GAME, "游戏管理", game.summary, "更换游戏", true),
                    SharedSettingsRow(SharedSettingsKey.CLOUD_CONNECTION, "云端连接", "内置同步服务", "连接设置", true),
                    SharedSettingsRow(SharedSettingsKey.AUTOMATIC_UPLOAD, "自动备份", "已暂停自动上传", "开启自动备份", true),
                    SharedSettingsRow(SharedSettingsKey.REVIEW_SETUP, "使用引导", "重新查看初次配置步骤向导", "查看向导", true),
                    SharedSettingsRow(SharedSettingsKey.SAVE_PATH, "存档目录", "/synthetic/player/Shattered Pixel Dungeon", "更改路径", true)
                ), pageActive = true), settingsActions)
            }.use { it.capture("settings") }
            assertTrue(calls.isEmpty(), "Composition alone must never invoke a platform callback")
        }
    }

    private fun cloud(row: Int = 0) = HistoryNode("commit-a", "snapshot-a", "云端存档", "10-09 09:00",
        "MacBook · 2 个槽位", "云端当前分支", 0, row, selectable = true)
    private fun local(row: Int = 0, synced: Boolean = false) = HistoryNode("local-current", null,
        "本机存档", "10-09 10:00", "2 个槽位 · 已进行28场游戏",
        if (synced) "已同步云端" else "未同步云端", 1, row, dirty = !synced)
    private fun history(nodes: List<HistoryNode>, synced: Boolean = false) = HistoryPresentation(
        nodes, if (synced) listOf(HistoryEdge("local-current", "commit-a", sameContent = true)) else emptyList(),
        page = 0, pages = 1, rows = maxOf(1, (nodes.maxOfOrNull { it.row } ?: -1) + 1),
        cloudCount = nodes.count { it.column == 0 }, localEmpty = nodes.none { it.column == 1 })

    /** Capabilities are explicit host facts. Status wording does not grant a transfer permission. */
    private fun homeStates(): Map<String, SharedHomeState> {
        val base = SharedHomeState(game, "", pageActive = true, canLaunch = true)
        val target = SharedHomeRestoreTarget("snapshot-a", "云端存档", "MacBook", "10-09 09:00")
        val conflict = base.copy(status = "检测到双方进度不同 · 本机与云端均有新变更",
            canUpload = true, canRestore = true, restoreTarget = target,
            history = history(listOf(local(), cloud(1))))
        return linkedMapOf(
            "loading" to base.copy(status = "正在读取存档…", busy = true, history = null),
            "empty" to base.copy(status = "暂无存档记录", history = history(emptyList())),
            "local-only" to base.copy(status = "本机有待上传的最新游戏进度", canUpload = true,
                history = history(listOf(local()))),
            "cloud-only" to base.copy(status = "云端有可恢复的游戏进度", canRestore = true,
                restoreTarget = target, history = history(listOf(cloud()))),
            "conflict" to conflict,
            "synced" to base.copy(status = "本机与云端进度一致", restoreTarget = target,
                history = history(listOf(cloud(), local(synced = true)), synced = true)),
            "running" to conflict.copy(status = "游戏正在运行，请退出后同步", canUpload = false,
                canRestore = false, canLaunch = false),
            // Expiry cannot manufacture recovery permission; a host must supply any permitted action.
            "expired" to conflict.copy(status = "内置服务已到期，请前往设置续期", canUpload = false, canRestore = false),
            "upload-busy" to conflict.copy(busy = true, pending = SharedHomeOperation.UPLOAD),
            "restore-busy" to conflict.copy(busy = true, pending = SharedHomeOperation.RESTORE),
            "failure" to conflict.copy(canUpload = false, canRestore = false,
                notice = "读取云端失败，请重试。", noticeError = true),
            "inactive" to conflict.copy(pageActive = false),
        )
    }

    private class HomeHost(initial: SharedHomeState) : SharedHomeActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        var lockOnOperation = false
        var leaveOnSettings = false
        private fun operation(name: String, pending: SharedHomeOperation) {
            calls += name
            if (lockOnOperation) state = state.copy(busy = true, pending = pending)
        }
        override fun settings() { calls += "settings"; if (leaveOnSettings) state = state.copy(pageActive = false) }
        override fun upload() = operation("upload", SharedHomeOperation.UPLOAD)
        override fun restore() = operation("restore", SharedHomeOperation.RESTORE)
        override fun launch() = operation("launch", SharedHomeOperation.LAUNCH)
        override fun selectSnapshot(snapshotId: String) { calls += "select:$snapshotId" }
        override fun rename(snapshotId: String?) { calls += "rename:$snapshotId" }
        override fun changeHistoryPage(page: Int) { calls += "page:$page" }
    }

    @Test fun truthfulHomeStatesRenderAndEveryVisibleActionHonorsItsCapability() {
        for (width in widths) for ((name, initial) in homeStates()) {
            val host = HomeHost(initial)
            val bounds = mutableMapOf<String, Rect>()
            val observer = object : SharedHomeLayoutObserver {
                override fun element(name: String, boundsInRoot: Rect) { bounds[name] = boundsInRoot }
            }
            RenderSession(width) {
                CompositionLocalProvider(LocalSharedHomeLayoutObserver provides observer) {
                    SharedHomeScreen(host.state, host)
                }
            }.use { session ->
                session.capture("home-$name")
                assertTrue(host.calls.isEmpty(), "Rendering $name must not dispatch")
                assertTrue(initial.status in session.texts())
                val upload = bounds.getValue("upload")
                val restore = bounds.getValue("restore")
                if (width <= 600) assertTrue(restore.top >= upload.bottom, "Narrow actions stack")
                else assertTrue(restore.left >= upload.right, "Wide actions form two columns")
                for (key in listOf("settings", "status", "upload", "restore", "launch")) {
                    session.assertVisible(bounds.getValue(key), "$name/$key")
                }
                val expected = linkedMapOf("settings" to initial.navigationAllowed,
                    "upload" to initial.uploadEnabled, "restore" to initial.restoreEnabled,
                    "launch" to initial.launchEnabled)
                for ((key, enabled) in expected) {
                    val node = session.actionAt(bounds.getValue(key))
                    assertEquals(!enabled, node.config.contains(SemanticsProperties.Disabled), "$name/$key semantics")
                    session.click(bounds.getValue(key).center)
                }
                assertEquals(expected.filterValues { it }.keys.toList(), host.calls, "$name actual pointer dispatch")
                assertEquals(initial, host.state, "The presentation does not change host facts")
                if (name == "local-only") assertEquals(0, initial.history!!.cloudCount)
                if (name == "cloud-only") assertTrue(initial.history!!.localEmpty)
                if (name.endsWith("busy")) assertTrue(host.calls.isEmpty())
            }
        }
    }

    @Test fun homeBusyReentryAndNavigationRejectRepeatedAndRetainedActions() {
        for (width in widths) {
            val ready = homeStates().getValue("conflict")
            val host = HomeHost(ready).apply { lockOnOperation = true; leaveOnSettings = true }
            val bounds = mutableMapOf<String, Rect>()
            val observer = object : SharedHomeLayoutObserver {
                override fun element(name: String, boundsInRoot: Rect) { bounds[name] = boundsInRoot }
            }
            RenderSession(width) {
                CompositionLocalProvider(LocalSharedHomeLayoutObserver provides observer) { SharedHomeScreen(host.state, host) }
            }.use { session ->
                session.capture("home-interaction-ready")
                val retainedUpload = assertNotNull(session.actionAt(bounds.getValue("upload"))
                    .config.getOrNull(SemanticsActions.OnClick)?.action)
                session.click(bounds.getValue("upload").center)
                session.capture("home-interaction-upload-started")
                assertTrue(host.state.busy)
                assertEquals(SharedHomeOperation.UPLOAD, host.state.pending)
                for (key in listOf("upload", "restore", "launch", "settings")) session.click(bounds.getValue(key).center)
                retainedUpload.invoke()
                assertEquals(listOf("upload"), host.calls, "Busy rerender rejects pointers and retained callbacks")
                // The fake host reports operation completion without fabricating a new synchronization result.
                host.state = ready
                session.capture("home-interaction-returned")
                session.click(bounds.getValue("restore").center)
                session.capture("home-interaction-restore-started")
                assertEquals(listOf("upload", "restore"), host.calls)
                host.state = ready
                session.settle()
                session.click(bounds.getValue("settings").center)
                session.capture("home-interaction-inactive")
                assertFalse(host.state.pageActive)
                retainedUpload.invoke()
                for (key in listOf("upload", "restore", "launch", "settings")) session.click(bounds.getValue(key).center)
                assertEquals(listOf("upload", "restore", "settings"), host.calls,
                    "An inactive retained page cannot dispatch after navigation")
            }
        }
    }

    private class ComparisonHost(initial: SharedComparisonState) : SharedComparisonActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        private fun operation(name: String) { calls += name; state = state.copy(busy = true) }
        override fun back() { calls += "back"; state = state.copy(pageActive = false) }
        override fun refresh() = operation("refresh")
        override fun selectProgress(choice: SharedProgressChoice) { calls += "select:$choice"; state = state.copy(selectedProgress = choice) }
        override fun selectRemote(snapshotId: String) { calls += "remote:$snapshotId"; state = state.copy(selectedId = snapshotId) }
        override fun finish(mode: SharedComparisonCompletion) = operation("finish:$mode")
        override fun handleConflict() = operation("conflict")
        override fun upload() = operation("retry-upload")
        override fun openRecovery() = operation("recovery")
    }

    @Test fun legacyComparisonStatesKeepRealGatesAtTheSameViewports() {
        val base = SharedComparisonState(OnboardingKind.BOTH_EMPTY, cloudVerified = true,
            pageActive = true, configured = true, restoreSupported = true, game = game,
            remoteChoices = listOf(SharedComparisonRemoteChoice("snapshot-a", "云端存档", "MacBook · 2 个槽位")),
            selectedId = "snapshot-a")
        val states = linkedMapOf(
            "empty" to (base to "finish:EMPTY"),
            "identical" to (base.copy(kind = OnboardingKind.IDENTICAL, canCompleteWithoutTransfer = true) to "finish:CONFIRM"),
            "identical-unproven" to (base.copy(kind = OnboardingKind.IDENTICAL) to "refresh"),
            "local-unselected" to (base.copy(kind = OnboardingKind.LOCAL_ONLY) to null),
            "cloud-unselected" to (base.copy(kind = OnboardingKind.REMOTE_ONLY) to null),
            "local-selected" to (base.copy(kind = OnboardingKind.LOCAL_ONLY, selectedProgress = SharedProgressChoice.LOCAL) to "finish:UPLOAD_LOCAL"),
            "cloud-selected" to (base.copy(kind = OnboardingKind.REMOTE_ONLY, selectedProgress = SharedProgressChoice.CLOUD) to "finish:RESTORE_REMOTE"),
            "different" to (base.copy(kind = OnboardingKind.DIFFERENT) to "conflict"),
            "branched" to (base.copy(kind = OnboardingKind.BRANCHED) to "conflict"),
            "unknown" to (base.copy(kind = null, cloudVerified = false) to "refresh"),
            "pending-upload" to (base.copy(kind = OnboardingKind.BLOCKED, retryablePending = true) to "retry-upload"),
            "restore-recovery" to (base.copy(kind = OnboardingKind.BLOCKED, needsRestoreRecovery = true) to "recovery"),
            "busy" to (base.copy(busy = true) to null),
            "inactive" to (base.copy(pageActive = false) to null),
        )
        for (width in widths) for ((name, scenario) in states) {
            val (initial, expected) = scenario
            val host = ComparisonHost(initial)
            RenderSession(width) { SharedComparisonScreen(host.state, host) }.use { session ->
                session.capture("legacy-comparison-$name", "Retained comparison component; not a third setup step")
                assertTrue(host.calls.isEmpty())
                val primary = session.button(initial.primaryLabel)
                assertEquals(expected == null, primary.config.contains(SemanticsProperties.Disabled), name)
                session.assertVisible(primary.boundsInRoot, "comparison primary")
                session.click(primary.boundsInRoot.center)
                session.settle()
                assertEquals(listOfNotNull(expected), host.calls, name)
                // Host busy/inactive transitions must stop a repeated click on the same primary.
                session.click(session.button(initial.primaryLabel).boundsInRoot.center)
                assertEquals(listOfNotNull(expected), host.calls, "$name repeated click")
                if (name == "identical") assertFalse("上一步" in session.texts())
            }
        }
    }

    @Test fun comparisonChoiceClicksEnableOnlyTheirActualCompletion() {
        for (width in widths) for (choice in SharedProgressChoice.values()) {
            val state = SharedComparisonState(if (choice == SharedProgressChoice.LOCAL) OnboardingKind.LOCAL_ONLY
                else OnboardingKind.REMOTE_ONLY, cloudVerified = true, pageActive = true, configured = true,
                restoreSupported = true, remoteChoices = listOf(SharedComparisonRemoteChoice("snapshot-a", "云端存档", "2 个槽位")),
                selectedId = "snapshot-a")
            val host = ComparisonHost(state)
            RenderSession(width) { SharedComparisonScreen(host.state, host) }.use { session ->
                session.capture("legacy-comparison-$choice-before-selection")
                session.click(session.button("完成").boundsInRoot.center)
                assertTrue(host.calls.isEmpty())
                session.click(session.button(if (choice == SharedProgressChoice.LOCAL) "使用本机进度" else "使用云端进度").boundsInRoot.center)
                session.capture("legacy-choice-${choice.name.lowercase()}-selected")
                assertEquals(choice, host.state.selectedProgress)
                assertFalse(session.button("完成").config.contains(SemanticsProperties.Disabled))
                session.click(session.button("完成").boundsInRoot.center)
                session.settle()
                assertEquals(listOf("select:$choice", "finish:${if (choice == SharedProgressChoice.LOCAL) "UPLOAD_LOCAL" else "RESTORE_REMOTE"}"), host.calls)
            }
        }
    }

    private class BranchHost(initial: SharedBranchChoiceState) : SharedBranchChoiceActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        override fun select(snapshotId: String) { calls += "select:$snapshotId"; state = state.copy(selectedId = snapshotId) }
        override fun back() { calls += "back"; state = state.copy(pageActive = false) }
        override fun refresh() { calls += "refresh"; state = state.copy(busy = true) }
        override fun overwrite(snapshotId: String) { calls += "overwrite:$snapshotId" }
        override fun preserveLocal() { calls += "preserve"; state = state.copy(busy = true) }
    }

    @Test fun branchSelectionBackRefreshAndDisabledActionsDispatchWithoutWriting() {
        val ready = SharedBranchChoiceState(Any(), listOf(
            SharedBranchChoice("snapshot-a", "手机进度", "手机 · 10-09 09:00 · 2 个槽位"),
            SharedBranchChoice("snapshot-b", "电脑进度", "电脑 · 10-08 09:00 · 2 个槽位")),
            game = game, pageActive = true, connected = true, observationCurrent = true,
            overwriteSupported = true)
        for (width in widths) {
            val host = BranchHost(ready)
            RenderSession(width) { SharedBranchChoiceScreen(host.state, host) }.use { session ->
                session.capture("branches-unselected")
                session.click(session.button("电脑进度").boundsInRoot.center)
                session.settle(frames = 24) // Let the release ripple finish before the static parity capture.
                session.capture("branches-selected")
                assertEquals("snapshot-b", host.state.selectedId)
                assertEquals(listOf("select:snapshot-b"), host.calls)
                session.click(session.button("返回").boundsInRoot.center)
                session.settle()
                session.click(session.button("手机进度").boundsInRoot.center)
                session.click(session.button("处理冲突").boundsInRoot.center)
                assertEquals(listOf("select:snapshot-b", "back"), host.calls, "Inactive page rejects selection/resolution")
                host.state = ready.copy(observationCurrent = false)
                session.capture("branches-stale")
                session.click(session.button("重新比较").boundsInRoot.center)
                session.settle()
                session.click(session.button("重新比较").boundsInRoot.center)
                assertEquals(listOf("select:snapshot-b", "back", "refresh"), host.calls)
            }
            for ((name, state) in linkedMapOf("busy" to ready.copy(busy = true),
                "blocked" to ready.copy(resolutionBlocked = true),
                "unsupported" to ready.copy(overwriteSupported = false))) {
                val blocked = BranchHost(state)
                RenderSession(width) { SharedBranchChoiceScreen(blocked.state, blocked) }.use { session ->
                    session.capture("branches-$name")
                    val primary = session.button("处理冲突")
                    assertTrue(primary.config.contains(SemanticsProperties.Disabled))
                    session.click(primary.boundsInRoot.center)
                    assertTrue(blocked.calls.isEmpty(), "$name cannot resolve")
                }
            }
        }
    }

    @Test fun sharedConfirmationButtonsCancelOverwriteMergeAndBusyUseRealPointerEvents() {
        for (width in widths) for (action in listOf("cancel", "overwrite", "merge")) {
            var dialog by mutableStateOf<SharedConfirmationState?>(SharedConfirmationState(17,
                "如何处理进度冲突？", "将先备份，再替换本机存档；所选存档中没有的进度会移除。",
                "覆盖本机", true, "智能合并双方进度", true,
                details = "合并只使用主机已验证的计划；显示文字不会授予合并权限。"))
            val calls = mutableListOf<String>()
            RenderSession(width) {
                SharedConnectionTheme {
                    Box(Modifier.fillMaxSize().background(SharedUiColors.background)) {
                        SharedHomeScreen(homeStates().getValue("conflict"), HomeHost(homeStates().getValue("conflict")))
                        dialog?.let { current ->
                            Box(Modifier.fillMaxSize().background(Color(0xFF0F172A).copy(alpha = .5f)).padding(horizontal = 19.dp, vertical = 24.dp),
                                contentAlignment = Alignment.Center) {
                                SharedConfirmationDialogBody(current,
                                    { calls += "overwrite:$it"; dialog = current.copy(busy = true) },
                                    { calls += "merge:$it"; dialog = current.copy(busy = true) },
                                    { calls += "cancel:$it"; dialog = null },
                                    Modifier.widthIn(max = 480.dp).fillMaxWidth())
                            }
                        }
                    }
                }
            }.use { session ->
                session.capture("confirmation-body-over-synthetic-home-$action-ready", "Production dialog body over a synthetic backdrop; native modal/Back/Escape excluded")
                val labels = listOf("取消", "覆盖本机", "智能合并双方进度")
                for (label in labels) session.assertVisible(session.button(label).boundsInRoot, label)
                val retainedConfirm = assertNotNull(session.button("覆盖本机").config.getOrNull(SemanticsActions.OnClick)?.action)
                val label = when (action) { "cancel" -> "取消"; "overwrite" -> "覆盖本机"; else -> "智能合并双方进度" }
                session.click(session.button(label).boundsInRoot.center)
                session.capture("confirmation-body-over-synthetic-home-$action-after", "Component callbacks only; no transfer or persistence performed")
                assertEquals(listOf("$action:17"), calls)
                if (action == "cancel") {
                    assertEquals(null, dialog)
                    assertFalse("覆盖本机" in session.texts(), "Cancel removes the shared dialog body")
                } else {
                    assertTrue(assertNotNull(dialog).busy)
                    for (buttonLabel in labels) {
                        val button = session.button(buttonLabel)
                        assertTrue(button.config.contains(SemanticsProperties.Disabled))
                        session.click(button.boundsInRoot.center)
                    }
                    retainedConfirm.invoke()
                    assertEquals(listOf("$action:17"), calls, "Busy prevents repeated confirmation")
                }
            }
        }
    }

    @Test fun confirmationReplacedTokenAndMissingPermissionCannotDispatch() {
        for (width in widths) {
            var state by mutableStateOf(SharedConfirmationState(41, "确认恢复", "先备份，再覆盖。", "覆盖本机", true,
                secondaryLabel = "合并", secondaryEnabled = true))
            val calls = mutableListOf<String>()
            RenderSession(width) {
                SharedConnectionTheme {
                    Box(Modifier.fillMaxSize().background(SharedUiColors.background).padding(16.dp), contentAlignment = Alignment.Center) {
                        SharedConfirmationDialogBody(state, { calls += "overwrite:$it" }, { calls += "merge:$it" },
                            { calls += "cancel:$it" }, Modifier.widthIn(max = 480.dp).fillMaxWidth())
                    }
                }
            }.use { session ->
                session.settle()
                val oldConfirm = assertNotNull(session.button("覆盖本机").config.getOrNull(SemanticsActions.OnClick)?.action)
                val oldMerge = assertNotNull(session.button("合并").config.getOrNull(SemanticsActions.OnClick)?.action)
                state = state.copy(token = 42, confirmEnabled = false, secondaryEnabled = false)
                session.capture("confirmation-body-isolated-permission-revoked", "Production body only on an empty test canvas; not a production host/modal capture")
                oldConfirm.invoke(); oldMerge.invoke()
                session.click(session.button("覆盖本机").boundsInRoot.center)
                session.click(session.button("合并").boundsInRoot.center)
                assertTrue(calls.isEmpty(), "Old token and missing current permission cannot dispatch")
                session.click(session.button("取消").boundsInRoot.center)
                assertEquals(listOf("cancel:42"), calls, "Cancel is still available without transfer permission")
            }
        }
    }

    /** Test-only adapter around the already-pinned Compose scene API; no alternative page layouts. */
    private class RenderSession(val width: Int, content: @Composable () -> Unit) : AutoCloseable {
        private val scene = ImageComposeScene(width = width, height = 900, density = Density(1f), content = content)
        private var frame = 0L
        fun settle(frames: Int = 8) { repeat(frames) { scene.render(++frame * 16_666_667L).close() } }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
        }
        fun assertVisible(bounds: Rect, label: String) {
            assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.top >= 0 &&
                bounds.right <= width && bounds.bottom <= 900, "$label must be visible at ${width}x900: $bounds")
        }
        fun nodes(): List<SemanticsNode> {
            val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
            val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
            val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
            return descendants(owner.unmergedRootSemanticsNode)
        }
        fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
        fun button(label: String): SemanticsNode = nodes().single {
            it.config.contains(SemanticsActions.OnClick) && descendants(it).any { child ->
                child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
            }
        }
        fun actionAt(bounds: Rect): SemanticsNode = nodes().filter {
            it.config.contains(SemanticsActions.OnClick) && it.boundsInRoot.contains(bounds.center)
        }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
            ?: error("No actual Compose action at $bounds")
        fun capture(name: String, note: String = "Shared production component with a synthetic host") {
            settle()
            val output = Path.of("build/reports/m3-reference", "$width-$name")
            Files.createDirectories(output.parent)
            Files.writeString(Path.of("$output.viewport.txt"),
                "content=${width}x900; density=1.0; fontScale=1.0; synthetic=true; renderer=production Compose; " +
                    "native decorations excluded; native OS/device behavior not tested\n$note\n")
            val semantics = nodes().filter { it.config.contains(SemanticsActions.OnClick) }.joinToString("\n") { node ->
                val label = descendants(node).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                    .joinToString(" | ") { it.text }
                "$label; disabled=${node.config.contains(SemanticsProperties.Disabled)}; bounds=${node.boundsInRoot}"
            }
            Files.writeString(Path.of("$output.semantics.txt"), texts().joinToString("\n") + "\n\nACTIONS\n" + semantics)
            scene.render(++frame * 16_666_667L).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> Files.write(Path.of("$output.png"), data.bytes) }
            }
        }
        override fun close() = scene.close()
        companion object {
            private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        }
    }
}
