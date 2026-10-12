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
