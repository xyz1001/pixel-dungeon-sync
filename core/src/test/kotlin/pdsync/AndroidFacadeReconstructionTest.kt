package pdsync

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.*
import kotlin.io.path.*
import kotlin.test.*

/** Reconstructed production facade exercised with real gzip JSON, synthetic storage, and no Android device. */
class AndroidFacadeReconstructionTest {
    @TempDir lateinit var temp: Path
    private var sequence = 0
    private val identity = AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon", "Synthetic Shattered",
        "a".repeat(64), "android-test", AndroidSaveCapabilities(802, 896, AndroidMergePolicy.SHATTERED_896))
    private val connection = AndroidConnection("https://dav.example.test/synthetic/", "synthetic", "test-only", "hero")
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        GZIPOutputStream(bytes).use { it.write(text.encodeToByteArray()) }
    }.toByteArray()
    private fun fixture(turn: Int) = linkedMapOf(
        "game1/game.dat" to gzip("""{"version":896,"init_ver":896,"turn":$turn,"hero":{"name":"synthetic","lvl":1}}"""),
        "game1/depth1.dat" to gzip("""{"version":896,"depth":1,"turn":$turn}""")
    )

    private inner class FakeBridge(initial: Map<String, ByteArray> = fixture(1)) : AndroidGameBridge {
        var files = initial.mapValues { it.value.copyOf() }.toMutableMap()
        var target = AndroidGameTarget(identity, "synthetic-896", 896, "b".repeat(64), "c".repeat(64))
        var exports = 0; var restores = 0; var targets = 0
        var failRestore = false
        var afterExport: (() -> Unit)? = null
        var beforeRestore: ((Path, Map<String, String>) -> Unit)? = null
        override fun targetGame(): AndroidGameTarget { targets++; return target }
        override fun exportTo(emptyDestination: Path) {
            exports++
            assertTrue(Files.list(emptyDestination).use { !it.findAny().isPresent })
            files.forEach { (name, bytes) ->
                val file = emptyDestination.resolve(name); file.parent.createDirectories(); file.writeBytes(bytes)
                // Each export deliberately has fresh timestamps. Equal contents must leave the mirror alone.
                Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_000L + exports * 1000L))
            }
            afterExport?.also { afterExport = null }?.invoke()
        }
        override fun restoreFrom(preparedDirectory: Path, expectedCurrentFiles: Map<String, String>) {
            restores++
            require(files.mapValues { hash(it.value) } == expectedCurrentFiles) { "Synthetic bridge CAS mismatch" }
            beforeRestore?.invoke(preparedDirectory, expectedCurrentFiles)
            if (failRestore) error("Synthetic helper unavailable")
            files = readFiles(preparedDirectory).toMutableMap()
        }
    }
    private class MemoryAppendStore : AppendStore {
        val commits = linkedMapOf<String, ByteArray>(); val objects = linkedMapOf<String, ByteArray>()
        var writes = 0
        var beforeObject: (() -> Unit)? = null
        override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
        override fun readCommit(id: String) = commits[id]?.copyOf()
        override fun readObject(id: String) = objects[id]?.copyOf()
        override fun writeObject(id: String, bytes: ByteArray) { beforeObject?.also { beforeObject = null }?.invoke(); writes++; objects[id] = bytes.copyOf() }
        override fun writeCommit(id: String, bytes: ByteArray) { writes++; commits[id] = bytes.copyOf() }
        override fun legacyPresent() = false
        fun decoded() = commits.values.map { json.decodeFromString<AppendCommit>(it.decodeToString()) }
        fun latest() = decoded().last()
        fun noopChild(parent: AppendCommit, kind: String = "snapshot"): AppendCommit {
            val snapshot = parent.snapshot.copy(id = UUID.randomUUID().toString())
            objects[snapshot.id] = objects.getValue(parent.snapshot.id).copyOf()
            val child = parent.copy(id = UUID.randomUUID().toString(), parents = listOf(parent.id), snapshot = snapshot, kind = kind)
            commits[child.id] = json.encodeToString(child).encodeToByteArray()
            return child
        }
    }
    private data class Rig(val root: Path, val facade: AndroidSyncFacade)
    private fun rig(bridge: FakeBridge, store: MemoryAppendStore, root: Path = temp.resolve("workspace-${sequence++}"), game: AndroidGameIdentity = identity) =
        Rig(root, AndroidSyncFacade(root, connection, bridge, game, store, 0))
    private fun readFiles(root: Path): Map<String, ByteArray> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && Shattered.includes(root.relativize(it).toString().replace('\\', '/')) && !isDirectoryMetadata(it.fileName.toString()) }
            .toList().associate { root.relativize(it).toString().replace('\\', '/') to it.readBytes() }
    }
    private fun mirror(root: Path): Path = Files.walk(root.resolve("connections")).use { paths ->
        paths.filter { it.fileName.toString() == "mirror" && Files.isDirectory(it) }.findFirst().orElseThrow()
    }
    private fun fingerprint(root: Path): Map<String, String> = Files.walk(root).use { paths -> paths.toList().associate { path ->
        val attr = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        root.relativize(path).toString() to "${attr.fileKey()}:${attr.lastModifiedTime()}:${attr.size()}:${if (attr.isRegularFile) hash(path.readBytes()) else "directory"}"
    } }
    private fun journal(root: Path): Path = root.resolve("restore-journals/${identity.originalSourceIdentity}.json")
    private fun publish(bridge: FakeBridge, store: MemoryAppendStore): Rig = rig(bridge, store).also {
        assertEquals("UPLOADED", it.facade.autoUpload().status)
    }
    private data class MergeRig(val store: MemoryAppendStore, val local: Rig, val bridge: FakeBridge, val expected: Map<String, String>)
    private fun mergeRig(): MergeRig {
        val initial = (1..2).flatMap { slot ->
            fixture(1).map { (name, bytes) -> name.replaceFirst("game1/", "game$slot/") to bytes }
        }.toMap()
        val store = MemoryAppendStore()
        val remoteBridge = FakeBridge(initial); val remote = publish(remoteBridge, store)
        val bridge = FakeBridge(emptyMap()); val local = rig(bridge, store)
        local.facade.restoreSelected(local.facade.inspect(), store.latest().snapshot.id, false)
        // Only the depth changes. Explicit continuation confirmation preserves the inherited
        // identity of each whole run; independently imported slots cannot prove a safe merge.
        remoteBridge.files["game1/depth1.dat"] = gzip("""{"version":896,"depth":1,"turn":2}""")
        remote.facade.confirmRunIdentity(remote.facade.inspect(), "game1", true)
        assertEquals("UPLOADED", remote.facade.autoUpload().status)
        bridge.files["game2/depth1.dat"] = gzip("""{"version":896,"depth":1,"turn":3}""")
        local.facade.confirmRunIdentity(local.facade.inspect(), "game2", true)
        val expected = (bridge.files + ("game1/depth1.dat" to remoteBridge.files.getValue("game1/depth1.dat")))
            .mapValues { hash(it.value) }
        return MergeRig(store, local, bridge, expected)
    }
    private fun assertMergeRejectedWithoutSideEffects(
        fixture: MergeRig,
        inspection: AndroidSyncFacade.Inspection,
        facade: AndroidSyncFacade = fixture.local.facade,
    ) {
        val writes = fixture.store.writes; val commits = fixture.store.commits.keys.toSet()
        val restores = fixture.bridge.restores; val actual = fixture.bridge.files.mapValues { hash(it.value) }
        val beforeMirror = fingerprint(mirror(fixture.local.root))
        listOf<() -> Unit>(
            { facade.previewMerge(inspection); Unit },
            { facade.mergeConflict(inspection); Unit },
        ).forEach { action ->
            assertFailsWith<IllegalArgumentException> { action() }
            assertEquals(writes, fixture.store.writes); assertEquals(commits, fixture.store.commits.keys)
            assertEquals(restores, fixture.bridge.restores); assertEquals(actual, fixture.bridge.files.mapValues { hash(it.value) })
            assertEquals(beforeMirror, fingerprint(mirror(fixture.local.root)))
            assertFalse(facade.hasUnresolvedRestore()); assertFalse(journal(fixture.local.root).exists())
        }
    }

    @Test fun officialRootTransportRetainsBaselineHistoryAndExactLocalScopes() {
        val root = temp.resolve("official-root-migration")
        val bridge = FakeBridge(); val store = MemoryAppendStore()
        val old = AndroidConnection("https://webdav.xyz1001.top:8443/dav/", "synthetic", "test-only", "hero")
        fun facade(connection: AndroidConnection) = AndroidSyncFacade(root, connection, bridge, identity, store, 0)
        val first = facade(old)
        assertEquals("UPLOADED", first.autoUpload().status)
        val commit = store.latest(); val beforeMirror = mirror(root)
        val current = AndroidConnection("https://webdav.xyz1001.top:8443/", old.username, "changed-test-only", old.profile, true)
        val migrated = facade(current)
        assertEquals("UNCHANGED", migrated.autoUpload().status)
        val inspection = migrated.inspect()
        assertTrue(inspection.canCompleteWithoutTransfer)
        assertTrue(inspection.remoteVersions.any { it.commitId == commit.id })
        assertEquals(beforeMirror, mirror(root))
        assertEquals(1L, Files.list(root.resolve("connections")).use { it.count() })
        bridge.files = fixture(2)
        assertEquals("UPLOADED", migrated.autoUpload().status)
        assertEquals(listOf(commit.id), store.latest().parents)
        assertEquals(current.endpoint, "https://webdav.xyz1001.top:8443/")
        assertEquals(old.endpoint, current.stateEndpoint)
        // No implicit origin alias: the same URL without explicit official provenance is isolated.
        facade(AndroidConnection(current.endpoint, old.username, old.password, old.profile))
        assertEquals(2L, Files.list(root.resolve("connections")).use { it.count() })
        facade(AndroidConnection(current.endpoint, "other-account", old.password, old.profile, true))
        facade(AndroidConnection(current.endpoint, old.username, old.password, "other-profile", true))
        assertEquals(4L, Files.list(root.resolve("connections")).use { it.count() })
        for (endpoint in listOf("https://other.invalid/", old.endpoint, current.endpoint + "child/", current.endpoint + "?q=1"))
            assertFailsWith<IllegalArgumentException> { AndroidConnection(endpoint, old.username, old.password, old.profile, true) }
    }

    @Test fun officialRootTransportRetainsFrozenPendingPublication() {
        val root = temp.resolve("official-root-pending")
        val bridge = FakeBridge(); val store = MemoryAppendStore()
        val old = AndroidConnection("https://webdav.xyz1001.top:8443/dav/", "synthetic", "test-only", "hero")
        val before = AndroidSyncFacade(root, old, bridge, identity, store, 0)
        val inspection = before.inspect()
        store.beforeObject = { throw java.io.IOException("Synthetic interruption") }
        assertFails { before.upload(inspection) }
        assertEquals(0, store.writes)
        val files = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
        val marker = files.single { it.fileName.toString() == "active-plan.txt" }
        val pendingId = marker.readText()
        val pending = marker.parent.resolve("uploads/$pendingId.json")
        val frozen = pending.readBytes()
        val current = AndroidConnection("https://webdav.xyz1001.top:8443/", old.username, old.password, old.profile, true)
        val migrated = AndroidSyncFacade(root, current, bridge, identity, store, 0)
        assertContentEquals(frozen, pending.readBytes())
        val retry = migrated.inspect(); assertTrue(retry.isRetryablePending)
        assertEquals(1L, Files.list(root.resolve("connections")).use { it.count() })
        assertEquals(0, store.writes)
        migrated.upload(retry)
        assertEquals(pendingId, store.latest().id)
        assertContentEquals(frozen, store.commits.getValue(pendingId))
    }

    @Test fun officialRootTransportRetainsUnresolvedRestoreJournal() {
        val store = MemoryAppendStore(); publish(FakeBridge(), store)
        val root = temp.resolve("official-root-recovery"); val bridge = FakeBridge(emptyMap())
        val old = AndroidConnection("https://webdav.xyz1001.top:8443/dav/", "synthetic", "test-only", "hero")
        val before = AndroidSyncFacade(root, old, bridge, identity, store, 0)
        val inspection = before.inspect(); bridge.failRestore = true
        assertFails { before.restoreSelected(inspection, store.latest().snapshot.id, false) }
        val journalBytes = journal(root).readBytes()
        val current = AndroidConnection("https://webdav.xyz1001.top:8443/", old.username, old.password, old.profile, true)
        val migrated = AndroidSyncFacade(root, current, bridge, identity, store, 0)
        assertTrue(migrated.hasUnresolvedRestore()); assertTrue(migrated.inspect().needsRestoreRecovery)
        assertContentEquals(journalBytes, journal(root).readBytes())
        bridge.failRestore = false; migrated.recoverRestore()
        assertFalse(migrated.hasUnresolvedRestore())
        assertEquals(1L, Files.list(root.resolve("connections")).use { it.count() })
    }

    @Test fun freshEmptyMirrorWithThreeLocalSlotsAndEmptyDavComparesWithoutAnyPut() {
        val slots=(1..3).associate { slot -> "game$slot/game.dat" to fixture(slot).getValue("game1/game.dat") }
        val bridge=FakeBridge(slots)
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest)=when(request.method) {
                    "PROPFIND" -> okhttp3.mockwebserver.MockResponse().setResponseCode(207)
                        .setBody("""<d:multistatus xmlns:d="DAV:"></d:multistatus>""")
                    "GET" -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                    else -> okhttp3.mockwebserver.MockResponse().setResponseCode(405)
                }
            }
            server.start()
            val dav=AppendOnlyWebDav(server.url("/dav/").toString(),identity.namespace,"hero","","")
            val root=temp.resolve("three-slot-workspace")
            val facade=AndroidSyncFacade(root,connection,bridge,identity,dav,0)
            // previewLocal does not initialize the mirror; Step 3 must capture that empty mirror.
            assertEquals(3,facade.previewLocal().fileCount)
            repeat(2) {
                val comparison=facade.inspect()
                assertEquals("LOCAL_ONLY",comparison.kind);assertEquals(3,comparison.localFileCount)
                assertTrue(comparison.remoteVersions.isEmpty())
                assertEquals(slots.mapValues { hash(it.value) },facade.historyPresentation(comparison).currentFiles)
            }
            assertEquals(0,bridge.restores)
            assertTrue(server.requestCount>0)
            repeat(server.requestCount) { assertTrue(server.takeRequest().method in setOf("GET","PROPFIND")) }
            assertEquals(slots.mapValues { hash(it.value) },readFiles(mirror(root)).mapValues { hash(it.value) })
        }
    }

    @Test fun inspectThenRenderThenUploadUsesOneImmutableObservation() {
        val bridge = FakeBridge(); val store = MemoryAppendStore(); val rig = rig(bridge, store)
        val inspection = rig.facade.inspect(); assertEquals("LOCAL_ONLY", inspection.kind)
        val before = fingerprint(mirror(rig.root)); val exportCount = bridge.exports
        val history = rig.facade.historyPresentation(inspection)
        assertEquals(inspection.localFileCount, history.currentFiles.size)
        assertEquals(exportCount, bridge.exports); assertEquals(before, fingerprint(mirror(rig.root)))
        val result = rig.facade.upload(inspection)
        assertFalse(result.isConflict); assertFalse(result.isPending); assertNotNull(result.snapshotId)
        assertEquals(2, store.writes); assertEquals(0, bridge.restores)
        assertEquals(bridge.files.mapValues { hash(it.value) }, store.latest().snapshot.files)
    }
    @Test fun repeatedEqualExportsPreserveMirrorIdentityTimestampsAndSidecar() {
        val bridge = FakeBridge(); val store = MemoryAppendStore(); val rig = rig(bridge, store)
        rig.facade.inspect(); val before = fingerprint(mirror(rig.root))
        val second = rig.facade.inspect(); assertEquals(before, fingerprint(mirror(rig.root)))
        rig.facade.historyPresentation(second); rig.facade.previewLocal()
        assertEquals(before, fingerprint(mirror(rig.root))); assertEquals(0, store.writes); assertEquals(0, bridge.restores)
    }
    @Test fun actualByteChangesInvalidateOldInspectionBeforePublication() {
        val bridge = FakeBridge(); val store = MemoryAppendStore(); val facade = rig(bridge, store).facade
        val inspection = facade.inspect(); bridge.files = fixture(2)
        assertFails { facade.upload(inspection) }; assertEquals(0, store.writes); assertEquals(0, bridge.restores)
        val fresh = facade.inspect(); facade.upload(fresh)
        assertEquals(bridge.files.mapValues { hash(it.value) }, store.latest().snapshot.files)
    }
    @Test fun comparisonOwnedByAnotherFacadeIsRejectedWithoutExportOrWrites() {
        val bridge = FakeBridge(); val store = MemoryAppendStore(); val a = rig(bridge, store).facade; val b = rig(bridge, store).facade
        val old = a.inspect(); b.inspect(); val exports = bridge.exports
        assertFails { b.upload(old) }; assertFails { b.historyPresentation(old) }
        assertEquals(exports, bridge.exports); assertEquals(0, store.writes)
    }
    @Test fun targetChangesAfterComparisonOrDuringExportAreBlocked() {
        val bridge = FakeBridge(); val store = MemoryAppendStore(); val facade = rig(bridge, store).facade
        val inspection = facade.inspect(); bridge.target = bridge.target.copy(apkSha256 = "d".repeat(64))
        assertFailsWith<AndroidTargetChangedException> { facade.upload(inspection) }; assertEquals(0, store.writes)
        val secondBridge = FakeBridge(); val second = rig(secondBridge, store).facade
        secondBridge.afterExport = { secondBridge.target = secondBridge.target.copy(sourceApkSha256 = "e".repeat(64)) }
        assertFailsWith<AndroidTargetChangedException> { second.inspect() }; assertEquals(0, store.writes)
    }
    @Test fun remoteAdvanceInvalidatesOldInspectionBeforeNewWrite() {
        val store = MemoryAppendStore(); val aBridge = FakeBridge(); val a = publish(aBridge, store).facade
        val old = a.inspect(); store.noopChild(store.latest()); val writes = store.writes
        assertFails { a.upload(old) }; assertEquals(writes, store.writes); assertEquals(0, aBridge.restores)
    }
    @Test fun unchangedRemoteDescendantsAdvanceThenUploadRealLocalProgress() {
        val store = MemoryAppendStore(); val bridge = FakeBridge(); val rig = publish(bridge, store)
        val first = store.latest(); val child = store.noopChild(first); val tip = store.noopChild(child, "restore")
        bridge.files = fixture(2)
        assertEquals("UPLOADED", rig.facade.autoUpload().status)
        assertEquals(listOf(tip.id), store.latest().parents); assertEquals(4, store.commits.size)
        assertEquals(0, bridge.restores); assertEquals(bridge.files.mapValues { hash(it.value) }, store.latest().snapshot.files)
    }
    @Test fun equalNoopAdvanceDoesNotPublishOrTouchMirror() {
        val store = MemoryAppendStore(); val bridge = FakeBridge(); val rig = publish(bridge, store)
        val before = fingerprint(mirror(rig.root)); val writes = store.writes; val child = store.noopChild(store.latest())
        assertEquals("UNCHANGED", rig.facade.autoUpload().status)
        assertEquals(writes, store.writes); assertEquals(before, fingerprint(mirror(rig.root)))
        assertEquals(child.id, rig.facade.historyPresentation(rig.facade.inspect()).baseline)
    }
    @Test fun changedRemoteHistoryRequiresChoiceAndDoesNotOverwriteLocalProgress() {
        val store = MemoryAppendStore(); val aBridge = FakeBridge(); val a = publish(aBridge, store).facade
        val bBridge = FakeBridge(); val b = rig(bBridge, store).facade
        b.adoptIdentical(b.inspect()); bBridge.files = fixture(2); assertEquals("UPLOADED", b.autoUpload().status)
        aBridge.files = fixture(3); val writes = store.writes
        val result = a.autoUpload(); assertTrue(result.needsUserChoice); assertNotEquals("UPLOADED", result.status)
        assertEquals(writes, store.writes); assertEquals(0, aBridge.restores)
        assertEquals(hash(fixture(3).getValue("game1/game.dat")), hash(aBridge.files.getValue("game1/game.dat")))
    }
    @Test fun mergeRejectsNewCloudTipAfterPreviewBeforeWritesRestoreOrJournal() {
        val fixture = mergeRig(); val facade = fixture.local.facade
        val inspection = facade.inspect(); assertEquals("DIFFERENT", inspection.kind)
        assertTrue(facade.previewMerge(inspection).isAllowed)
        fixture.store.noopChild(fixture.store.latest())
        assertMergeRejectedWithoutSideEffects(fixture, inspection)
    }
    @Test fun mergeRejectsActualGameProgressAfterPreviewBeforeWritesRestoreOrJournal() {
        val fixture = mergeRig(); val inspection = fixture.local.facade.inspect()
        assertTrue(fixture.local.facade.previewMerge(inspection).isAllowed)
        fixture.bridge.files["game2/depth1.dat"] = gzip("""{"version":896,"depth":1,"turn":99}""")
        assertMergeRejectedWithoutSideEffects(fixture, inspection)
    }
    @Test fun mergeRejectsChangedBaselineAfterPreviewBeforeWritesRestoreOrJournal() {
        val fixture = mergeRig(); val facade = fixture.local.facade; val inspection = facade.inspect()
        assertTrue(facade.previewMerge(inspection).isAllowed)
        val directory = Files.walk(mirror(fixture.local.root).parent.resolve("state")).use { paths ->
            paths.filter { Files.isDirectory(it) && it.fileName.toString() == "append-v2" }.toList().single()
        }
        val repository = AppendRepository(directory, AndroidGameAdapter(identity), connection.profile)
        val graph = repository.discover(fixture.store); val replacement = graph.tips.single()
        assertNotEquals(repository.baseline(), replacement)
        repository.setBaseline(replacement, graph)
        assertMergeRejectedWithoutSideEffects(fixture, inspection)
        assertEquals(replacement, repository.baseline())
    }
    @Test fun mergeRejectsAnotherAccountObservationBeforeExportWritesRestoreOrJournal() {
        val fixture = mergeRig(); val inspection = fixture.local.facade.inspect()
        assertTrue(fixture.local.facade.previewMerge(inspection).isAllowed)
        val otherConnection = AndroidConnection(connection.endpoint, "other-account", connection.password, connection.profile)
        val other = AndroidSyncFacade(temp.resolve("other-account-workspace"), otherConnection,
            fixture.bridge, identity, fixture.store, 0)
        val exports = fixture.bridge.exports
        assertMergeRejectedWithoutSideEffects(fixture, inspection, other)
        assertEquals(exports, fixture.bridge.exports)
    }
    @Test fun mergeWithSameObservationRestoresExactPreviewThroughReadyJournalAndCas() {
        val fixture = mergeRig(); val facade = fixture.local.facade; val bridge = fixture.bridge
        val inspection = facade.inspect(); assertEquals("DIFFERENT", inspection.kind)
        val before = bridge.files.mapValues { hash(it.value) }; val beforeMirror = fingerprint(mirror(fixture.local.root))
        val writes = fixture.store.writes; val restores = bridge.restores
        val preview = facade.previewMerge(inspection)
        assertTrue(preview.isAllowed, preview.message); assertEquals(2, preview.slotCount)
        assertEquals(writes, fixture.store.writes); assertEquals(restores, bridge.restores)
        assertEquals(beforeMirror, fingerprint(mirror(fixture.local.root)))
        assertFalse(facade.hasUnresolvedRestore())
        var checkedJournal = false
        bridge.beforeRestore = { prepared, expectedCurrent ->
            val persisted = json.parseToJsonElement(journal(fixture.local.root).readText()).jsonObject
            assertTrue(persisted.getValue("ready").jsonPrimitive.boolean)
            assertEquals("merge", persisted.getValue("action").jsonPrimitive.content)
            assertEquals(before, persisted.getValue("expectedBefore").jsonObject.mapValues { it.value.jsonPrimitive.content })
            assertEquals(fixture.expected, persisted.getValue("expectedAfter").jsonObject.mapValues { it.value.jsonPrimitive.content })
            assertEquals(before, expectedCurrent)
            assertEquals(fixture.expected, readFiles(prepared).mapValues { hash(it.value) })
            checkedJournal = true
        }
        val result = facade.mergeConflict(inspection)
        assertFalse(result.isConflict); assertFalse(result.isPending)
        assertEquals(fixture.store.latest().snapshot.id, result.snapshotId)
        assertEquals("resolution", fixture.store.latest().kind)
        assertTrue(checkedJournal); assertEquals(restores + 1, bridge.restores)
        assertTrue(fixture.store.writes > writes)
        assertEquals(fixture.expected, fixture.store.latest().snapshot.files)
        assertEquals(fixture.expected, bridge.files.mapValues { hash(it.value) })
        assertEquals(fixture.expected, readFiles(mirror(fixture.local.root)).mapValues { hash(it.value) })
        assertFalse(facade.hasUnresolvedRestore()); assertFalse(journal(fixture.local.root).exists())
    }
    @Test fun failedMergeHelperRestoreKeepsReadyJournalAndRecoversWithoutRepublishing() {
        val fixture = mergeRig(); val facade = fixture.local.facade; val bridge = fixture.bridge
        val inspection = facade.inspect(); assertTrue(facade.previewMerge(inspection).isAllowed)
        val before = bridge.files.mapValues { hash(it.value) }; val restores = bridge.restores
        bridge.failRestore = true
        val failure = assertFailsWith<IllegalStateException> { facade.mergeConflict(inspection) }
        assertEquals("Synthetic helper unavailable", failure.message)
        assertEquals(restores + 1, bridge.restores); assertEquals(before, bridge.files.mapValues { hash(it.value) })
        assertEquals("resolution", fixture.store.latest().kind)
        assertEquals(fixture.expected, fixture.store.latest().snapshot.files)
        assertEquals(fixture.expected, readFiles(mirror(fixture.local.root)).mapValues { hash(it.value) })
        assertTrue(facade.hasUnresolvedRestore())
        val persisted = json.parseToJsonElement(journal(fixture.local.root).readText()).jsonObject
        assertTrue(persisted.getValue("ready").jsonPrimitive.boolean)
        assertEquals("merge", persisted.getValue("action").jsonPrimitive.content)
        assertEquals(before, persisted.getValue("expectedBefore").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(fixture.expected, persisted.getValue("expectedAfter").jsonObject.mapValues { it.value.jsonPrimitive.content })
        val writes = fixture.store.writes
        assertTrue(facade.inspect().needsRestoreRecovery)
        assertEquals("RESTORE_RECOVERY_REQUIRED", facade.autoUpload().reasonCode)
        assertEquals(writes, fixture.store.writes)
        bridge.failRestore = false; facade.recoverRestore()
        assertEquals(restores + 2, bridge.restores); assertEquals(writes, fixture.store.writes)
        assertEquals(fixture.expected, bridge.files.mapValues { hash(it.value) })
        assertFalse(facade.hasUnresolvedRestore()); assertFalse(journal(fixture.local.root).exists())
        assertFails { facade.recoverRestore() }; assertEquals(restores + 2, bridge.restores)
    }
    @Test fun remoteRestoreAppliesExactBytesAndVerifiesBeforeClearingJournal() {
        val store = MemoryAppendStore(); publish(FakeBridge(), store)
        val bridge = FakeBridge(emptyMap()); val rig = rig(bridge, store); val inspection = rig.facade.inspect()
        assertEquals("REMOTE_ONLY", inspection.kind); val writes = store.writes
        val result = rig.facade.restoreSelected(inspection, store.latest().snapshot.id, false)
        assertFalse(result.isConflict); assertEquals(1, bridge.restores)
        assertEquals(store.latest().snapshot.files, bridge.files.mapValues { hash(it.value) })
        assertFalse(rig.facade.hasUnresolvedRestore()); assertFalse(journal(rig.root).exists()); assertEquals(writes, store.writes)
    }
    @Test fun failedRestoreRemainsBlockedAndRecoveryIsIdempotent() {
        val store = MemoryAppendStore(); publish(FakeBridge(), store)
        val bridge = FakeBridge(emptyMap()); val rig = rig(bridge, store); val inspection = rig.facade.inspect()
        bridge.failRestore = true
        assertFails { rig.facade.restoreSelected(inspection, store.latest().snapshot.id, false) }
        assertTrue(rig.facade.hasUnresolvedRestore()); assertTrue(json.parseToJsonElement(journal(rig.root).readText()).jsonObject.getValue("ready").jsonPrimitive.boolean)
        val writes = store.writes; assertTrue(rig.facade.inspect().needsRestoreRecovery)
        assertEquals("RESTORE_RECOVERY_REQUIRED", rig.facade.autoUpload().reasonCode); assertEquals(writes, store.writes)
        bridge.failRestore = false; rig.facade.recoverRestore()
        assertEquals(2, bridge.restores); assertFalse(rig.facade.hasUnresolvedRestore())
        assertFails { rig.facade.recoverRestore() }; assertEquals(2, bridge.restores)
    }
    @Test fun interveningGameProgressBlocksRecoveryAndDiscardNeverRestores() {
        val store = MemoryAppendStore(); publish(FakeBridge(), store)
        val bridge = FakeBridge(emptyMap()); val rig = rig(bridge, store); val inspection = rig.facade.inspect()
        bridge.failRestore = true; assertFails { rig.facade.restoreSelected(inspection, store.latest().snapshot.id, false) }
        bridge.files = fixture(9); bridge.failRestore = false; val restores = bridge.restores
        assertFails { rig.facade.recoverRestore() }; assertEquals(restores, bridge.restores); assertTrue(rig.facade.hasUnresolvedRestore())
        rig.facade.discardInterruptedRestore(); assertFalse(rig.facade.hasUnresolvedRestore()); assertEquals(restores, bridge.restores)
        assertEquals(hash(fixture(9).getValue("game1/game.dat")), hash(bridge.files.getValue("game1/game.dat")))
    }
    @Test fun changedBuildAndDifferentGameCannotClaimRestoreJournal() {
        val store = MemoryAppendStore(); publish(FakeBridge(), store)
        val bridge = FakeBridge(emptyMap()); val rig = rig(bridge, store); val inspection = rig.facade.inspect()
        bridge.failRestore = true; assertFails { rig.facade.restoreSelected(inspection, store.latest().snapshot.id, false) }
        bridge.target = bridge.target.copy(apkSha256 = "e".repeat(64)); bridge.failRestore = false
        val reopened = rig(bridge, store, rig.root).facade; val restores = bridge.restores
        assertFails { reopened.recoverRestore() }; assertTrue(reopened.hasUnresolvedRestore()); assertEquals(restores, bridge.restores)
        val other = identity.copy(originalSourceIdentity = "f".repeat(64), namespace = "other-game")
        bridge.target = bridge.target.copy(identity = other)
        val switched = rig(bridge, store, rig.root, other).facade
        assertTrue(switched.inspect().needsRestoreRecovery); assertFails { switched.discardInterruptedRestore() }
        assertTrue(switched.hasUnresolvedRestore()); assertEquals(restores, bridge.restores)
    }
    @Test fun corruptRecoveryMetadataCannotBeAppliedOrSilentlyCleared() {
        val store = MemoryAppendStore(); publish(FakeBridge(), store)
        val bridge = FakeBridge(emptyMap()); val rig = rig(bridge, store); val inspection = rig.facade.inspect()
        bridge.failRestore = true; assertFails { rig.facade.restoreSelected(inspection, store.latest().snapshot.id, false) }
        val path = journal(rig.root); val saved = path.readText(); val restores = bridge.restores
        path.writeText(saved.replaceFirst("{", "{\"schema\":99,"))
        assertFails { rig.facade.recoverRestore() }; assertFails { rig.facade.discardInterruptedRestore() }
        assertTrue(path.exists()); assertEquals(restores, bridge.restores)
        path.writeText(saved.replaceFirst("{", "{\"ready\":true,"))
        assertFails { rig.facade.recoverRestore() }; assertFails { rig.facade.discardInterruptedRestore() }
        assertTrue(path.exists()); assertEquals(restores, bridge.restores)
    }
    @Test fun gameChangesBetweenConfirmationAndApplyAreNotOverwritten() {
        val store = MemoryAppendStore(); publish(FakeBridge(), store)
        val bridge = FakeBridge(emptyMap()); val rig = rig(bridge, store); val inspection = rig.facade.inspect()
        bridge.afterExport = { bridge.files = fixture(9) }
        val writes = store.writes
        assertFails { rig.facade.restoreSelected(inspection, store.latest().snapshot.id, false) }
        assertEquals(0, bridge.restores); assertEquals(writes, store.writes); assertTrue(rig.facade.hasUnresolvedRestore())
        assertEquals(hash(fixture(9).getValue("game1/game.dat")), hash(bridge.files.getValue("game1/game.dat")))
    }
    @Test fun malformedSavesFailClosedWithoutPublishingOrBecomingEmpty() {
        val bridge = FakeBridge(mapOf("game1/game.dat" to gzip("invalid-json")))
        val store = MemoryAppendStore(); val facade = rig(bridge, store).facade
        val result = facade.autoUpload()
        assertEquals("SKIPPED_UNCERTAIN", result.status); assertEquals("UNSUPPORTED_SAVE_FORMAT", result.reasonCode)
        assertEquals(0, store.writes); assertEquals(0, bridge.restores)
        assertTrue(bridge.files.isNotEmpty())
    }
}
