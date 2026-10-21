package pdsync

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.*
import kotlin.io.path.*
import kotlin.test.*

/** Historical Android downloads verify the helper without manufacturing cloud publications. */
class AndroidHistoricalRestorePublicationTest {
    @TempDir lateinit var temp: Path
    private var sequence = 0
    private val identity = AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon", "Synthetic Shattered",
        "a".repeat(64), AndroidSaveCapabilities(802, 896, AndroidMergePolicy.SHATTERED_896))
    private val connection = AndroidConnection("https://dav.example.test/synthetic/", "synthetic", "test-only", "hero")
    private fun fixture(turn: Int) = linkedMapOf(
        "game1/game.dat" to """{"version":896,"init_ver":896,"turn":$turn,"hero":{"name":"synthetic","lvl":1}}""".encodeToByteArray(),
        "game1/depth1.dat" to """{"version":896,"depth":1,"turn":$turn}""".encodeToByteArray()
    )

    private inner class Bridge : AndroidGameBridge {
        var files: Map<String, ByteArray> = fixture(1)
        var restores = 0
        var verifiedExportsAfterRestore = 0
        var failRestore = false
        var throwAfterRestore = false
        var afterRestoreExport: (() -> Unit)? = null
        override fun targetGame() = AndroidGameTarget(identity, "synthetic-896", 896, "b".repeat(64), "c".repeat(64))
        override fun exportTo(emptyDestination: Path) {
            files.forEach { (name, bytes) ->
                emptyDestination.resolve(name).also { it.parent.createDirectories(); it.writeBytes(bytes) }
            }
            if (restores > 0) {
                verifiedExportsAfterRestore++
                afterRestoreExport?.also { afterRestoreExport = null }?.invoke()
            }
        }
        override fun restoreFrom(preparedDirectory: Path, expectedCurrentFiles: Map<String, String>) {
            restores++
            require(hashes() == expectedCurrentFiles) { "Synthetic bridge CAS mismatch" }
            if (failRestore) error("Synthetic helper unavailable")
            files = Files.walk(preparedDirectory).use { paths ->
                paths.filter { it.isRegularFile() }.toList().associate {
                    preparedDirectory.relativize(it).toString().replace('\\', '/') to it.readBytes()
                }
            }
            if (throwAfterRestore) error("Synthetic uncertain helper reply")
        }
        fun hashes() = files.mapValues { hash(it.value) }
    }
    private inner class Store : AppendStore {
        val commits = linkedMapOf<String, ByteArray>()
        val objects = linkedMapOf<String, ByteArray>()
        var objectAttempts = 0
        var commitAttempts = 0
        var failObject = false
        var failCommit = false
        var throwAfterObject = false
        var throwAfterCommit = false
        var corruptObject = false
        var beforeWrite: (() -> Unit)? = null
        var afterObject: (() -> Unit)? = null
        override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
        override fun readCommit(id: String) = commits[id]?.copyOf()
        override fun readObject(id: String) = objects[id]?.copyOf()
        override fun writeObject(id: String, bytes: ByteArray) {
            objectAttempts++; beforeWrite?.invoke()
            if (failObject) error("Synthetic object transport failure")
            objects[id] = if (corruptObject) byteArrayOf(0) else bytes.copyOf()
            afterObject?.also { afterObject = null }?.invoke()
            if (throwAfterObject) error("Synthetic lost object response")
        }
        override fun writeCommit(id: String, bytes: ByteArray) {
            commitAttempts++; beforeWrite?.invoke()
            if (failCommit) error("Synthetic commit transport failure")
            commits[id] = bytes.copyOf()
            if (throwAfterCommit) error("Synthetic lost commit response")
        }
        override fun legacyPresent() = false
        fun latest() = json.decodeFromString<AppendCommit>(commits.values.last().decodeToString())
        fun remoteChild(parent: AppendCommit): AppendCommit {
            // Advance with genuinely changed bytes: a schema3 client cannot create a
            // second record merely by assigning another UUID to the same progress.
            val root = temp.resolve("remote-child-${sequence++}").createDirectory()
            fixture(3).forEach { (name, bytes) -> root.resolve(name).also {
                it.parent.createDirectories(); it.writeBytes(bytes)
            } }
            val captured = Snapshots.capture(root, AndroidGameAdapter(identity), connection.profile, parent.snapshot.version, null)
            val id = contentSha256(captured.manifest.files)
            val snapshot = captured.manifest.copy(id = id)
            objects[id] = captured.zip.copyOf()
            val commit = parent.copy(schema = 3, id = id, parents = listOf(contentSha256(parent.snapshot.files)),
                snapshot = snapshot, objectBytes = captured.zip.size)
            commits[commit.id] = json.encodeToString(commit).encodeToByteArray()
            return commit
        }
    }
    private inner class Rig {
        val root = temp.resolve("workspace-${sequence++}")
        val bridge = Bridge()
        val store = Store()
        val facade = reopen()
        fun reopen() = AndroidSyncFacade(root, connection, bridge, identity, store, 0)
        fun pending(): UploadPlan? = Files.walk(root).use { paths ->
            val directory = paths.filter { it.fileName.toString() == "append-v2" }.findFirst().orElseThrow()
            AppendRepository(directory, AndroidGameAdapter(identity), connection.profile).pending()
        }
        fun publishTwo(): Pair<AppendCommit, AppendCommit> {
            assertEquals("UPLOADED", facade.autoUpload().status)
            val first = store.latest()
            bridge.files = fixture(2)
            assertEquals("UPLOADED", facade.autoUpload().status)
            return first to store.latest()
        }
    }

    @Test fun historicalRestoreAdoptsSelectedCommitWithoutPublicationAfterHelperVerification() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        val originalCommits = rig.store.commits.mapValues { it.value.copyOf() }
        val originalObjects = rig.store.objects.mapValues { it.value.copyOf() }
        rig.store.beforeWrite = { fail("Historical download must not write to the cloud") }
        val result = rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
        assertFalse(result.isPending); assertFalse(result.isConflict)
        assertNull(rig.pending()); assertFalse(rig.facade.hasUnresolvedRestore())
        assertEquals(2, rig.store.commits.size); assertEquals(2, rig.store.objects.size)
        assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
        assertEquals(first.snapshot.id, result.snapshotId); assertEquals(first.snapshot.files, rig.bridge.hashes())
        assertEquals(1, rig.bridge.restores); assertTrue(rig.bridge.verifiedExportsAfterRestore > 0)
        originalCommits.forEach { (id, bytes) -> assertContentEquals(bytes, rig.store.commits.getValue(id)) }
        originalObjects.forEach { (id, bytes) -> assertContentEquals(bytes, rig.store.objects.getValue(id)) }
        assertEquals(first.id, rig.facade.historyPresentation(rig.facade.inspect()).baseline)
    }



    @Test fun helperFailureRequiresRecoveryWithoutCreatingAnyUploadPlan() {
        val rig = Rig(); val (first, second) = rig.publishTwo()
        rig.bridge.failRestore = true
        assertFails { rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false) }
        assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
        assertEquals(second.snapshot.files, rig.bridge.hashes()); assertTrue(rig.facade.hasUnresolvedRestore())
        assertNull(rig.pending()); assertEquals("RESTORE_RECOVERY_REQUIRED", rig.facade.autoUpload().reasonCode)
        rig.bridge.failRestore = false
        rig.facade.recoverRestore()
        assertEquals(first.snapshot.files, rig.bridge.hashes()); assertEquals(2, rig.bridge.restores)
        assertEquals(2, rig.store.commits.size); assertFalse(rig.facade.hasUnresolvedRestore()); assertNull(rig.pending())
        assertEquals(first.id, rig.facade.historyPresentation(rig.facade.inspect()).baseline)
        assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
    }

    @Test fun equalGameBytesDoNotPublishRestoreOrChangeBaselineOrCallHelper() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        rig.bridge.files = fixture(1)
        assertEquals("UPLOADED", rig.facade.autoUpload().status)
        val previous = rig.store.latest()
        val result = rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
        assertFalse(result.isPending); assertFalse(result.isConflict)
        assertEquals(first.snapshot.id, result.snapshotId)
        assertEquals(0, rig.bridge.restores); assertEquals(2, rig.store.commits.size)
        assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
        assertEquals(first.id, rig.facade.historyPresentation(rig.facade.inspect()).baseline)
        assertNull(rig.pending()); assertFalse(rig.facade.hasUnresolvedRestore())
    }

    @Test fun uncertainHelperReplyDoesNotPublishOrRepeatAnAlreadyAppliedRestore() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        rig.bridge.throwAfterRestore = true
        assertFails { rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false) }
        assertEquals(first.snapshot.files, rig.bridge.hashes())
        assertEquals(2, rig.store.commits.size); assertTrue(rig.facade.hasUnresolvedRestore()); assertNull(rig.pending())
        rig.bridge.throwAfterRestore = false
        val reopened = rig.reopen(); reopened.recoverRestore()
        assertEquals(1, rig.bridge.restores); assertFalse(reopened.hasUnresolvedRestore()); assertNull(rig.pending())
        assertEquals(first.id, reopened.historyPresentation(reopened.inspect()).baseline)
        assertEquals(2, rig.store.commits.size)
        assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
    }

    @Test fun configuredCloudWriteFailuresCannotBlockHistoricalDownloadOrCreateRetryPlan() {
        for (failAtObject in listOf(true, false)) {
            val rig = Rig(); val (first, _) = rig.publishTwo()
            rig.store.failObject = failAtObject; rig.store.failCommit = !failAtObject
            val result = rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
            assertFalse(result.isPending); assertEquals(first.snapshot.files, rig.bridge.hashes())
            assertFalse(rig.facade.hasUnresolvedRestore()); assertEquals(1, rig.bridge.restores)
            assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
            assertEquals(2, rig.store.commits.size); assertNull(rig.pending())
            val reopened = rig.reopen(); val inspection = reopened.inspect()
            assertFalse(inspection.isRetryablePending)
            assertEquals(first.id, reopened.historyPresentation(inspection).baseline)
        }
    }

    @Test fun gameProgressAfterFinalReadbackSurvivesWithoutAnyCloudPublication() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        rig.bridge.afterRestoreExport = { rig.bridge.files = fixture(9) }
        val result = rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
        assertEquals(fixture(9).mapValues { hash(it.value) }, rig.bridge.hashes())
        assertEquals(2, rig.store.commits.size); assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
        assertFalse(result.isPending); assertFalse(rig.facade.hasUnresolvedRestore()); assertNull(rig.pending())
        val inspection = rig.facade.inspect()
        assertEquals("DIFFERENT", inspection.kind)
        assertEquals(first.id, rig.facade.historyPresentation(inspection).baseline)
    }

    @Test fun lostWriteResponseConfigurationIsNeverExercisedByHistoricalDownload() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        rig.store.throwAfterObject = true; rig.store.throwAfterCommit = true
        rig.store.beforeWrite = { fail("Restore unexpectedly attempted publication") }
        val result = rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
        assertFalse(result.isPending); assertFalse(result.isConflict)
        assertEquals(2, rig.store.commits.size)
        assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
        assertEquals(1, rig.bridge.restores); assertNull(rig.pending())
        assertEquals(first.snapshot.files, rig.bridge.hashes())
    }

    @Test fun failedHelperReceiptKeepsRecoveryGateWithoutCreatingAnUploadPlan() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        rig.bridge.afterRestoreExport = {
            Files.walk(rig.root).use { paths ->
                assertFalse(paths.anyMatch { it.fileName.toString() == "active-plan.txt" })
            }
            error("Synthetic final helper readback unavailable")
        }
        assertFails { rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false) }
        assertEquals(first.snapshot.files, rig.bridge.hashes()); assertTrue(rig.facade.hasUnresolvedRestore())
        assertNull(rig.pending()); assertEquals(2, rig.store.commits.size)
        assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
        val reopened = rig.reopen(); reopened.recoverRestore()
        assertEquals(1, rig.bridge.restores); assertFalse(reopened.hasUnresolvedRestore()); assertNull(rig.pending())
        assertEquals(2, rig.store.commits.size)
    }

    @Test fun corruptWriteReadbackConfigurationCannotAffectReadOnlyHistoricalRestore() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        val originals = rig.store.objects.mapValues { it.value.copyOf() }
        rig.store.corruptObject = true
        val result = rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
        assertFalse(result.isPending); assertEquals(first.snapshot.files, rig.bridge.hashes())
        assertFalse(rig.facade.hasUnresolvedRestore()); assertNull(rig.pending())
        assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
        assertEquals(2, rig.store.commits.size); assertEquals(1, rig.bridge.restores)
        originals.forEach { (id, bytes) -> assertContentEquals(bytes, rig.store.objects.getValue(id)) }
    }

    @Test fun concurrentRemoteAdvanceDuringHelperReceiptRemainsTheOnlyCloudTip() {
        val rig = Rig(); val (first, second) = rig.publishTwo()
        var concurrent: AppendCommit? = null
        rig.bridge.afterRestoreExport = { concurrent = rig.store.remoteChild(second) }
        val result = rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
        assertFalse(result.isConflict); assertFalse(result.isPending)
        assertEquals(3, rig.store.commits.size)
        val advanced = assertNotNull(concurrent)
        assertNotEquals(second.snapshot.files, advanced.snapshot.files)
        assertEquals(fixture(3).mapValues { hash(it.value) }, advanced.snapshot.files)
        assertEquals(listOf(second.id), advanced.parents)
        val inspection = rig.facade.inspect()
        assertEquals("DIFFERENT", inspection.kind)
        assertEquals(first.id, rig.facade.historyPresentation(inspection).baseline)
        assertTrue(inspection.remoteVersions.any { it.commitId == advanced.id })
        assertEquals(first.snapshot.files, rig.bridge.hashes())
        assertNull(rig.pending()); assertFalse(rig.facade.hasUnresolvedRestore())
        assertEquals(1, rig.bridge.restores)
        assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
        assertTrue(rig.store.commits.values.none { json.decodeFromString<AppendCommit>(it.decodeToString()).kind == "restore" })
    }
}
