@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.xyz1001.pdsync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.jetbrains.skia.EncodedImageFormat
import pdsync.*
import pdsync.android.app.*
import kotlinx.serialization.encodeToString
import pdsync.presentation.HistoryPresentation
import kotlin.io.path.*
import pdsync.i18n.SharedMessages
import pdsync.i18n.UiLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Production body and lifecycle with synthetic observations; no save or service writes. */
class SharedBranchChoiceDialogRenderTest {
    private var frame = 0L
    private fun ready() = SharedBranchChoiceState(Any(), listOf(
        SharedBranchChoice("snapshot-a", "Expedition A", "Desktop · 6 files"),
        SharedBranchChoice("snapshot-b", "Expedition B", "Android · 4 files")),
        pageActive = true, connected = true, observationCurrent = true,
        overwriteSupported = true, mergeSupported = true)

    private class Host(initial: SharedBranchChoiceState) : SharedBranchChoiceActions {
        var state by mutableStateOf(initial)
        var visible by mutableStateOf(true)
        var actions by mutableStateOf<SharedBranchChoiceActions>(this)
        lateinit var interactions: SharedBranchChoiceInteractions
        val calls = mutableListOf<String>()
        var uploadAction: (() -> Unit)? = null
        var overwriteAction: ((String) -> Unit)? = null
        var history by mutableStateOf<HistoryPresentation?>(null)
        override fun select(snapshotId: String) {
            calls += "select:$snapshotId"; state = state.copy(selectedId = snapshotId)
        }
        override fun back() { calls += "back"; visible = false; state = state.copy(pageActive = false) }
        override fun refresh() = start("refresh")
        override fun overwrite(snapshotId: String) { start("overwrite:$snapshotId"); overwriteAction?.invoke(snapshotId) }
        override fun merge() = start("merge")
        override fun preserveLocal() = start("preserve")
        private fun start(action: String) {
            // Hosts retain the synchronous operation lock used by the real platforms.
            if (!state.canInteract) return
            calls += action; state = state.copy(busy = true)
        }
    }

    @org.junit.jupiter.api.io.TempDir lateinit var flowRoot: Path
    private val flowIdentity=AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon","Synthetic", "a".repeat(64),AndroidSaveCapabilities(802,896,AndroidMergePolicy.SHATTERED_896))
    private val flowConnection=AndroidConnection("https://dav.example.test/synthetic/","synthetic","fixture-only","hero")
    private fun flowFiles(turn:Int)=linkedMapOf("game1/game.dat" to """{"version":896,"init_ver":896,"turn":$turn}""".encodeToByteArray(),"game1/depth1.dat" to byteArrayOf(turn.toByte()))
    private inner class FlowBridge:AndroidGameBridge {
        var files=flowFiles(1)
        override fun targetGame()=AndroidGameTarget(flowIdentity,"synthetic",896,"b".repeat(64),"c".repeat(64))
        override fun exportTo(emptyDestination:Path) { files.forEach {(name,bytes)->emptyDestination.resolve(name).also {it.parent.createDirectories();it.writeBytes(bytes)}} }
        override fun readSnapshot()=AndroidSaveObservation(targetGame(),files.mapValues {AndroidSaveFile(hash(it.value),it.value.size.toLong(),1700000000000)},files.filterKeys {it.endsWith("/game.dat")})
        override fun restoreFrom(preparedDirectory:Path,expectedCurrentFiles:Map<String,String>)=error("No game restore allowed")
    }
    private class FlowStore:AppendStore {
        val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>();val receipts=linkedMapOf<String,ByteArray>()
        val writes=mutableListOf<String>();var failDelete=false
        override fun legacyPresent()=false
        override fun listCommits(cursor:String?)=CommitPage(commits.keys.toList())
        override fun readCommit(id:String)=commits[id]?.copyOf()
        override fun readObject(id:String)=objects[id]?.copyOf()
        override fun writeCommit(id:String,bytes:ByteArray){writes+="PUT commit";commits[id]=bytes.copyOf()}
        override fun writeObject(id:String,bytes:ByteArray){writes+="PUT object";objects[id]=bytes.copyOf()}
        override fun readDeletion(id:String)=receipts[id]?.copyOf()
        override fun writeDeletion(id:String,bytes:ByteArray){writes+="PUT receipt";receipts[id]=bytes.copyOf()}
        override fun deleteCommit(id:String){writes+="DELETE commit";if(failDelete)throw java.io.IOException("fixture");commits.remove(id)}
        override fun deleteObject(id:String){writes+="DELETE object";objects.remove(id)}
    }
    @Test fun actualHomeUploadCancelRefreshConfirmAndRestartUseRealFacadeAndProductionMapping() {
        for(legacy in listOf(false,true))for(deleteFailure in listOf(false,true)) {
            val root=flowRoot.resolve("$legacy-$deleteFailure");val bridge=FlowBridge();val store=FlowStore()
            var facade=AndroidSyncFacade(root,flowConnection,bridge,flowIdentity,store,0)
            facade.upload(facade.inspectHome());bridge.files=flowFiles(2);facade.upload(facade.inspectHome())
            val beforeCloud=facade.historyPresentation(facade.inspectHome());val b=beforeCloud.graph.commits.getValue(requireNotNull(beforeCloud.baseline))
            if(legacy){bridge.files=flowFiles(9);facade.upload(facade.inspectHome())}
            val source=root.resolve("fixture-cloud");source.createDirectories();flowFiles(3).forEach {(name,bytes)->source.resolve(name).also {it.parent.createDirectories();it.writeBytes(bytes)}}
            val snapshot=Snapshots.capture(source,AndroidGameAdapter(flowIdentity),flowConnection.profile,"fixture",null)
            val id=contentSha256(snapshot.manifest.files);val c=b.copy(id=id,parents=listOf(b.id),snapshot=snapshot.manifest.copy(id=id),objectBytes=snapshot.zip.size)
            store.commits[id]=json.encodeToString(c).encodeToByteArray();store.objects[id]=snapshot.zip
            bridge.files=flowFiles(if(legacy)10 else 9)
            var observed=facade.inspectHome()
            val host=Host(androidCloudOverwriteState(observed,facade.historyPresentation(observed),null,true,false,true,true,false,"",""))
            host.visible=false
            fun refresh(){ observed=facade.inspectHome();host.history=HistoryPresentation.from(facade.historyPresentation(observed),0) }
            refresh()
            val originalBaseline=facade.historyPresentation(observed).baseline
            val writes=store.writes.size;val commitIds=store.commits.keys.toSet()
            host.uploadAction={
                val result=facade.upload(observed)
                if(result.isConflict){observed=requireNotNull(result.refreshedInspection);host.state=androidCloudOverwriteState(observed,facade.historyPresentation(observed),null,true,false,true,true,false,"","");host.visible=true}
                else {host.visible=false;refresh()}
            }
            host.overwriteAction={ target ->
                try {facade.overwriteCloudSuccessors(observed,target)}catch(expected:java.io.IOException){assertTrue(deleteFailure)}
                host.visible=false;refresh()
            }
            val scene=scene(host)
            fun clickHomeUpload(){val n=nodes(scene).single {it.config.contains(SemanticsActions.OnClick)&&SharedMessages.UPLOAD_CLOUD.text() in texts(descendants(it))};val at=n.boundsInRoot.center;scene.sendPointerEvent(PointerEventType.Press,at);scene.sendPointerEvent(PointerEventType.Release,at)}
            try {
                render(scene,"flow-home-$legacy-$deleteFailure");clickHomeUpload();render(scene,"flow-conflict-$legacy-$deleteFailure")
                assertEquals(writes,store.writes.size);assertEquals(commitIds,store.commits.keys)
                assertFalse(button(scene,SharedMessages.OVERWRITE_CLOUD.text()).config.contains(SemanticsProperties.Disabled))
                assertFalse(SharedMessages.HANDLE_CONFLICT.text() in modalTexts(scene))
                click(scene,SharedMessages.CANCEL.text());refresh();render(scene,"flow-cancel-$legacy-$deleteFailure")
                assertEquals(writes,store.writes.size);assertEquals(commitIds,store.commits.keys)
                facade=AndroidSyncFacade(root,flowConnection,bridge,flowIdentity,store,0);refresh()
                assertEquals(originalBaseline,facade.historyPresentation(observed).baseline)
                assertEquals(writes,store.writes.size)
                clickHomeUpload();render(scene,"flow-reopen-$legacy-$deleteFailure")
                store.failDelete=deleteFailure;click(scene,SharedMessages.OVERWRITE_CLOUD.text());render(scene,"flow-result-$legacy-$deleteFailure")
                if(deleteFailure){assertTrue(observed.isRetryablePending);store.failDelete=false;facade=AndroidSyncFacade(root,flowConnection,bridge,flowIdentity,store,0);refresh();clickHomeUpload();render(scene,"flow-retry-$legacy")}
                assertFalse(observed.isRetryablePending);assertFalse(c.id in store.commits)
                val reopened=AndroidSyncFacade(root,flowConnection,bridge,flowIdentity,store,0)
                val final=reopened.historyPresentation(reopened.inspectHome())
                assertEquals(1,final.graph.tips.size);assertEquals(bridge.files.mapValues {hash(it.value)},final.graph.commits.getValue(final.graph.tips.single()).snapshot.files)
                assertEquals((commitIds-c.id)+final.graph.tips.single(),final.graph.commits.keys)
                assertEquals(listOf(originalBaseline),final.graph.commits.getValue(final.graph.tips.single()).parents)
                assertEquals(1,HistoryPresentation.from(final,0).nodes.count {it.status==pdsync.i18n.CoreMessages.CURRENT_CLOUD_BRANCH.text()})
                assertTrue(store.writes.indexOfLast {it=="PUT commit"}<store.writes.indexOfFirst {it=="DELETE commit"})
            } finally {scene.close()}
        }
    }

    @Test fun cloudOverwriteRendersOnlyDirectConfirmationAndBindsOneTarget() {
        val host = Host(ready().copy(selectedId = "snapshot-b", cloudOverwrite = true))
        val scene = scene(host)
        try {
            render(scene, "cloud-overwrite-direct")
            val text = modalTexts(scene)
            for (old in listOf("Expedition A", "Expedition B", SharedMessages.HANDLE_CONFLICT.text(),
                    SharedMessages.CHOOSE_PROGRESS.text(), SharedMessages.CHOOSE_CLOUD_SAVE.text(), SharedMessages.MERGE.text()))
                assertFalse(old in text, "old chooser must not render: $old")
            assertTrue(SharedMessages.OVERWRITE_CLOUD_WARNING.text() in text)
            assertTrue(host.calls.isEmpty(), "rendering never writes")
            click(scene, SharedMessages.OVERWRITE_CLOUD.text())
            assertEquals(listOf("overwrite:snapshot-b"), host.calls)
            render(scene, "cloud-overwrite-busy")
            click(scene, SharedMessages.OVERWRITE_CLOUD.text())
            assertEquals(1, host.calls.size)
        } finally { scene.close() }
        val missing = Host(ready().copy(cloudOverwrite = true))
        val blocked = scene(missing)
        try {
            render(blocked, "cloud-overwrite-no-unique-target")
            assertTrue(button(blocked, SharedMessages.OVERWRITE_CLOUD.text()).config.contains(SemanticsProperties.Disabled))
            click(blocked, SharedMessages.OVERWRITE_CLOUD.text());assertTrue(missing.calls.isEmpty())
            click(blocked, SharedMessages.CANCEL.text());assertEquals(listOf("back"), missing.calls)
        } finally { blocked.close() }
    }

    @Test fun explicitSelectionConfirmationCancelAndRepeatKeepOriginalWriteSemantics() {
        val host = Host(ready())
        val scene = scene(host)
        try {
            render(scene, "chooser")
            assertTrue(host.calls.isEmpty(), "rendering never performs a read or write")
            click(scene, "Expedition B")
            render(scene, "selected")
            assertEquals("snapshot-b", host.state.selectedId)
            assertEquals(listOf("select:snapshot-b"), host.calls)

            click(scene, SharedMessages.HANDLE_CONFLICT.text())
            render(scene, "confirmation")
            val cancelled = host.interactions.presentation!!.token
            assertTrue(SharedMessages.CONFLICT_TITLE.text() in modalTexts(scene))
            assertFalse(SharedMessages.CHOOSE_CLOUD_SAVE.text() in modalTexts(scene),
                "the confirmation replaces the chooser inside the same modal body")
            click(scene, SharedMessages.CANCEL.text())
            render(scene, "confirmation-cancelled")
            assertNull(host.interactions.presentation)
            host.interactions.confirm(cancelled)
            assertEquals(listOf("select:snapshot-b"), host.calls)
            assertEquals("snapshot-b", host.state.selectedId)

            click(scene, SharedMessages.HANDLE_CONFLICT.text())
            render(scene, "confirmation-reopened")
            val current = host.interactions.presentation!!.token
            assertTrue(current > cancelled)
            click(scene, SharedMessages.USE_SELECTED.text())
            repeat(3) { host.interactions.confirm(current); host.interactions.merge(current) }
            assertEquals(listOf("select:snapshot-b", "overwrite:snapshot-b"), host.calls)
            render(scene, "overwrite-busy")
            for (label in listOf(SharedMessages.CANCEL.text(), SharedMessages.HANDLE_CONFLICT.text())) {
                assertTrue(button(scene, label).config.contains(SemanticsProperties.Disabled))
                click(scene, label)
            }
            host.interactions.dismissBranchDialog()
            assertEquals(2, host.calls.size, "busy chooser cannot dismiss or submit again")
        } finally { scene.close() }

        val merge = Host(ready())
        val mergeScene = scene(merge)
        try {
            render(mergeScene, "merge-chooser")
            click(mergeScene, SharedMessages.HANDLE_CONFLICT.text())
            render(mergeScene, "merge-confirmation")
            assertTrue(button(mergeScene, SharedMessages.USE_SELECTED.text()).config.contains(SemanticsProperties.Disabled))
            click(mergeScene, SharedMessages.MERGE.text())
            assertEquals(listOf("merge"), merge.calls, "merge does not invent a selected snapshot")
        } finally { mergeScene.close() }
    }

    @Test fun backOutsideRemovalAndReopenDisposeOldConfirmationAndUseLatestActions() {
        val host = Host(ready().copy(selectedId = "snapshot-a"))
        val scene = scene(host)
        try {
            render(scene, "dismiss-chooser")
            host.interactions.primary()
            render(scene, "dismiss-confirmation")
            val old = host.interactions
            val oldToken = old.presentation!!.token
            old.dismissBranchDialog()
            render(scene, "dismiss-inner")
            assertNull(old.presentation)
            assertTrue(host.visible)
            assertTrue(host.calls.isEmpty(), "Back/outside first cancels only the confirmation")
            old.dismissBranchDialog()
            render(scene, "dismiss-outer")
            assertFalse(host.visible)
            assertEquals(listOf("back"), host.calls)
            assertTrue(SharedMessages.SAVE_HISTORY.text() in texts(nodes(scene)),
                "home remains composed after dismissal")
            old.confirm(oldToken); old.merge(oldToken); old.primary(); old.select("snapshot-b")
            old.preserveLocal(); old.dismissBranchDialog()
            assertEquals(listOf("back"), host.calls, "removed modal callbacks are inert")

            host.state = ready().copy(selectedId = "snapshot-a")
            host.visible = true
            render(scene, "fresh-chooser")
            assertNotSame(old, host.interactions)
            assertNull(host.interactions.presentation, "reopening never resurrects a confirmation")
            host.interactions.primary()
            render(scene, "fresh-confirmation")
            old.confirm(oldToken)
            assertEquals(listOf("back"), host.calls)
            val live = host.interactions
            val liveToken = live.presentation!!.token
            host.visible = false
            render(scene, "external-removal")
            live.confirm(liveToken); live.merge(liveToken)
            assertEquals(listOf("back"), host.calls, "external navigation also disposes write callbacks")

            host.visible = true
            render(scene, "actions-original")
            val latestCalls = mutableListOf<String>()
            host.actions = object : SharedBranchChoiceActions by host {
                override fun back() { latestCalls += "latest-back" }
            }
            render(scene, "actions-updated")
            host.interactions.dismissBranchDialog()
            assertEquals(listOf("latest-back"), latestCalls)
            assertEquals(listOf("back"), host.calls)
        } finally { scene.close() }
    }

    @Test fun busyStaleAndRecoveryCapabilitiesRemainFailClosedInTheModal() {
        val host = Host(ready().copy(selectedId = "snapshot-a", canPreserveLocal = true))
        val scene = scene(host)
        try {
            render(scene, "guards-ready")
            host.interactions.primary()
            render(scene, "guards-confirmation")
            val token = host.interactions.presentation!!.token
            host.state = host.state.copy(busy = true)
            render(scene, "guards-busy")
            for (label in listOf(SharedMessages.CANCEL.text(), SharedMessages.USE_SELECTED.text(), SharedMessages.MERGE.text())) {
                assertTrue(button(scene, label).config.contains(SemanticsProperties.Disabled))
                click(scene, label)
            }
            host.interactions.dismissBranchDialog()
            assertNotNull(host.interactions.presentation, "busy keeps the current confirmation locked")
            assertTrue(host.calls.isEmpty())
            host.state = host.state.copy(busy = false, observation = Any())
            render(scene, "guards-new-observation")
            assertNull(host.interactions.presentation)
            host.interactions.confirm(token); host.interactions.merge(token)
            assertTrue(host.calls.isEmpty())

            host.state = host.state.copy(resolutionBlocked = true)
            render(scene, "guards-pending-upload")
            assertTrue(button(scene, SharedMessages.HANDLE_CONFLICT.text()).config.contains(SemanticsProperties.Disabled))
            click(scene, SharedMessages.UPLOAD_KEEP_BOTH.text())
            assertEquals(listOf("preserve"), host.calls)
            host.state = host.state.copy(busy = false, observationCurrent = false, branches = emptyList())
            render(scene, "guards-stale-observation")
            assertTrue(button(scene, SharedMessages.UPLOAD_KEEP_BOTH.text()).config.contains(SemanticsProperties.Disabled))
            click(scene, SharedMessages.COMPARE_AGAIN.text())
            assertEquals(listOf("preserve", "refresh"), host.calls)
        } finally { scene.close() }
    }

    @Test fun detailsStayCollapsedAndPreserveExpansionWhenInnerConfirmationIsCancelled() {
        val host = Host(ready().copy(details = "Synthetic maintenance details"))
        val scene = scene(host)
        try {
            render(scene, "details-collapsed")
            assertFalse(host.state.details in modalTexts(scene))
            click(scene, SharedMessages.DETAILS.text())
            render(scene, "details-expanded")
            assertTrue(host.state.details in modalTexts(scene))
            host.interactions.primary()
            render(scene, "details-confirmation")
            host.interactions.dismissBranchDialog()
            render(scene, "details-return")
            assertTrue(host.state.details in modalTexts(scene))
            host.state = host.state.copy(observation = Any())
            render(scene, "details-new-observation")
            assertFalse(host.state.details in modalTexts(scene))
            assertTrue(host.calls.isEmpty())
        } finally { scene.close() }
    }

    @Test fun bilingualLongListsScrollWithReachableActionsAtNarrowAndLargeFontSizes() {
        val previous = UiLocale.locale()
        try {
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
                UiLocale.setLocale(locale)
                for ((width, height, scale) in listOf(Triple(1280, 900, 1f), Triple(390, 640, 1f),
                    Triple(320, 420, 1f), Triple(320, 640, 1.5f), Triple(320, 640, 2f))) {
                    val chinese = locale.language == "zh"
                    val host = Host(ready().copy(branches = (1..12).map {
                        SharedBranchChoice("snapshot-$it",
                            if (chinese) "测试存档 $it · 很长的设备与存档名称" else "Expedition $it with a very long device and save name",
                            if (chinese) "测试设备 · 6 个文件" else "Synthetic device · 6 files")
                    }, selectedId = "snapshot-1"))
                    val scene = scene(host, width, height, scale)
                    val id = "${locale.language}-$width-$height-$scale"
                    try {
                        render(scene, "layout-$id")
                        assertModalLayout(scene, width, height)
                        val before = listOf(SharedMessages.CANCEL.text(), SharedMessages.HANDLE_CONFLICT.text())
                            .associateWith { button(scene, it).boundsInRoot }
                        val scroll = modalNodes(scene).single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                        assertTrue(scroll.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!.maxValue() > 0f)
                        scene.sendPointerEvent(PointerEventType.Scroll, Offset(width / 2f, before.values.first().top - 30f),
                            scrollDelta = Offset(0f, 20f))
                        render(scene, "scrolled-$id")
                        assertTrue(modalNodes(scene).single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                            .config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!.value() > 0f)
                        before.forEach { (label, bounds) -> assertEquals(bounds, button(scene, label).boundsInRoot) }
                        assertTrue(host.calls.isEmpty())
                        click(scene, SharedMessages.HANDLE_CONFLICT.text())
                        render(scene, "confirmation-$id")
                        assertModalLayout(scene, width, height)
                        for (label in listOf(SharedMessages.CANCEL.text(), SharedMessages.USE_SELECTED.text(), SharedMessages.MERGE.text())) {
                            assertFalse(button(scene, label).config.contains(SemanticsProperties.Disabled))
                        }
                        click(scene, SharedMessages.CANCEL.text())
                        render(scene, "cancelled-$id")
                        assertTrue(host.calls.isEmpty(), "layout, scrolling and cancellation never write")
                    } finally { scene.close() }
                }
            }
        } finally { UiLocale.setLocale(previous) }
    }

    private fun scene(host: Host, width: Int = 390, height: Int = 900, scale: Float = 1f) =
        ImageComposeScene(width = width, height = height, density = Density(1f, scale)) {
            SharedConnectionTheme {
                Box(Modifier.fillMaxSize().background(SharedUiColors.background)) {
                    SharedHomeScreen(SharedHomeState(null, "Synthetic home", pageActive = !host.visible, canUpload = host.uploadAction != null, history = host.history),
                        object : SharedHomeActions {
                            override fun settings() = Unit
                            override fun upload() { host.uploadAction?.invoke() }
                            override fun restore() = Unit
                            override fun launch() = Unit
                            override fun selectSnapshot(snapshotId: String) = Unit
                            override fun rename(snapshotId: String?) = Unit
                            override fun changeHistoryPage(page: Int) = Unit
                        })
                    if (host.visible) {
                        val interactions = rememberSharedBranchChoiceDialogInteractions(host.state, host.actions)
                        host.interactions = interactions
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f))
                            .padding(horizontal = 19.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                            SharedBranchChoiceDialogBody(host.state, interactions,
                                Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = (height - 48).dp)
                                    .testTag("branch-dialog"))
                        }
                    }
                }
            }
        }

    private fun assertModalLayout(scene: ImageComposeScene, width: Int, height: Int) {
        val modal = modalRoot(scene).boundsInRoot
        assertTrue(modal.left >= 19f && modal.right <= width - 19f && modal.width <= 480f)
        assertTrue(modal.top >= 24f && modal.bottom <= height - 24f)
        val labels = if (SharedMessages.CONFLICT_TITLE.text() in modalTexts(scene))
            listOf(SharedMessages.CANCEL.text(), SharedMessages.USE_SELECTED.text(), SharedMessages.MERGE.text())
        else listOf(SharedMessages.CANCEL.text(), SharedMessages.HANDLE_CONFLICT.text())
        val bounds = labels.map { button(scene, it).boundsInRoot }
        bounds.forEach {
            assertTrue(it.width > 0f && it.height >= 42f && it.left >= modal.left && it.right <= modal.right)
            assertTrue(it.top >= modal.top && it.bottom <= modal.bottom, "footer is fully visible: $it in $modal")
        }
        bounds.zipWithNext().forEach { (a, b) ->
            assertTrue(a.right <= b.left || a.bottom <= b.top, "footer buttons never overlap")
        }
        for (node in modalNodes(scene)) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.forEach { layout ->
                assertFalse(layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines,
                    "text stays readable: ${layout.layoutInput.text.text}")
                for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line))
                    assertTrue(layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                }
            }
        }
    }

    private fun render(scene: ImageComposeScene, name: String) {
        repeat(4) { scene.render(++frame * 16_000_000L).close() }
        scene.render(++frame * 16_000_000L).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                val file = Path.of("build/reports/upload-dialog", "$name.png")
                Files.createDirectories(file.parent); Files.write(file, data.bytes)
            }
        }
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val point = button(scene, label).boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, point)
        scene.sendPointerEvent(PointerEventType.Release, point)
    }
    private fun button(scene: ImageComposeScene, label: String) = modalNodes(scene).single { node ->
        node.config.contains(SemanticsActions.OnClick) && label in texts(descendants(node))
    }
    private fun modalRoot(scene: ImageComposeScene) = nodes(scene).single {
        it.config.getOrNull(SemanticsProperties.TestTag) == "branch-dialog"
    }
    private fun modalNodes(scene: ImageComposeScene) = descendants(modalRoot(scene))
    private fun modalTexts(scene: ImageComposeScene) = texts(modalNodes(scene))
    private fun texts(nodes: List<SemanticsNode>) = nodes.flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty()
    }.map { it.text }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val backing = ImageComposeScene::class.java.getDeclaredField("scene").run { isAccessible = true; get(scene) }
        val root = backing.javaClass.getDeclaredField("mainOwner").run { isAccessible = true; get(backing) }
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        return descendants(owner.unmergedRootSemanticsNode)
    }
}
