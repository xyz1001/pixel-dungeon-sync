package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.*
import kotlin.io.path.*
import kotlin.test.*

/** Historical Android restore must publish only after the real helper target was verified. */
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
    private class Store : AppendStore {
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
            val snapshot = parent.snapshot.copy(id = UUID.randomUUID().toString())
            objects[snapshot.id] = objects.getValue(parent.snapshot.id).copyOf()
            val commit = parent.copy(id = UUID.randomUUID().toString(), parents = listOf(parent.id), snapshot = snapshot)
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

    @Test fun protectedHistoricalRestorePublishesInSameOperationAfterHelperVerification() {
        val rig = Rig(); val (first, second) = rig.publishTwo()
        val originalCommits = rig.store.commits.mapValues { it.value.copyOf() }
        rig.store.beforeWrite = {
            assertEquals(first.snapshot.files, rig.bridge.hashes())
            assertEquals(1, rig.bridge.restores)
            assertTrue(rig.bridge.verifiedExportsAfterRestore > 0)
            assertFalse(AndroidSyncFacade.hasUnresolvedRestore(rig.root))
        }
        val result = rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
        assertFalse(result.isPending, "Verified historical restore must attempt its initial publication")
        assertFalse(result.isConflict)
        assertNull(rig.pending()); assertFalse(rig.facade.hasUnresolvedRestore())
        assertEquals(3, rig.store.commits.size)
        val restored = rig.store.latest()
        assertEquals("restore", restored.kind); assertEquals(listOf(second.id), restored.parents)
        assertEquals(restored.snapshot.id, result.snapshotId)
        assertEquals(first.snapshot.files, restored.snapshot.files)
        assertEquals(first.snapshot.metadata, restored.snapshot.metadata)
        assertEquals(first.snapshot.directoryMetadata, restored.snapshot.directoryMetadata)
        originalCommits.forEach { (id, bytes) -> assertContentEquals(bytes, rig.store.commits.getValue(id)) }
        assertEquals(restored.id, rig.facade.historyPresentation(rig.facade.inspect()).baseline)
        repeat(2) { assertEquals("UNCHANGED", rig.facade.autoUpload().status) }
        assertEquals(3, rig.store.commits.size); assertEquals(1, rig.bridge.restores)
    }

    @Test fun helperFailureNeverPublishesBeforeExplicitRecoveryAndRetry() {
        val rig = Rig(); val (first, second) = rig.publishTwo()
        rig.bridge.failRestore = true
        assertFails { rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false) }
        assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
        assertEquals(second.snapshot.files, rig.bridge.hashes())
        assertTrue(rig.facade.hasUnresolvedRestore())
        val frozen = assertNotNull(rig.pending())
        assertEquals("RESTORE_RECOVERY_REQUIRED", rig.facade.autoUpload().reasonCode)
        rig.bridge.failRestore = false
        rig.facade.recoverRestore()
        assertEquals(2, rig.store.commits.size); assertFalse(rig.facade.hasUnresolvedRestore())
        val result = rig.facade.upload(rig.facade.inspect())
        assertFalse(result.isPending); assertEquals(frozen.commit, rig.store.latest())
    }

    @Test fun equalGameBytesStillPublishSelectedHistoryWithoutCallingHelperRestore() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        rig.bridge.files = fixture(1)
        assertEquals("UPLOADED", rig.facade.autoUpload().status)
        val previous = rig.store.latest()
        val result = rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
        assertFalse(result.isPending); assertFalse(result.isConflict)
        assertEquals(0, rig.bridge.restores); assertEquals(4, rig.store.commits.size)
        assertEquals("restore", rig.store.latest().kind)
        assertEquals(listOf(previous.id), rig.store.latest().parents)
        assertEquals(first.snapshot.directoryMetadata, rig.store.latest().snapshot.directoryMetadata)
        assertNull(rig.pending()); assertFalse(rig.facade.hasUnresolvedRestore())
    }

    @Test fun uncertainHelperReplyDoesNotPublishOrRepeatAnAlreadyAppliedRestore() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        rig.bridge.throwAfterRestore = true
        assertFails { rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false) }
        assertEquals(first.snapshot.files, rig.bridge.hashes())
        assertEquals(2, rig.store.commits.size); assertTrue(rig.facade.hasUnresolvedRestore())
        rig.bridge.throwAfterRestore = false
        val reopened = rig.reopen()
        reopened.recoverRestore()
        assertEquals(1, rig.bridge.restores); assertFalse(reopened.hasUnresolvedRestore())
        reopened.upload(reopened.inspect())
        assertEquals(3, rig.store.commits.size); assertEquals("restore", rig.store.latest().kind)
    }

    @Test fun failedInitialPublicationRetainsExactFrozenPlanForExplicitRestartRetry() {
        for (failAtObject in listOf(true, false)) {
            val rig = Rig(); val (first, second) = rig.publishTwo()
            rig.store.failObject = failAtObject; rig.store.failCommit = !failAtObject
            assertFails { rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false) }
            assertEquals(first.snapshot.files, rig.bridge.hashes())
            assertFalse(rig.facade.hasUnresolvedRestore()); assertEquals(1, rig.bridge.restores)
            assertEquals(3, rig.store.objectAttempts)
            assertEquals(if (failAtObject) 2 else 3, rig.store.commitAttempts)
            assertEquals(2, rig.store.commits.size)
            val frozen = assertNotNull(rig.pending())
            assertEquals("restore", frozen.commit.kind); assertEquals(listOf(second.id), frozen.commit.parents)
            rig.store.failObject = false; rig.store.failCommit = false
            val reopened = rig.reopen(); val inspection = reopened.inspect()
            assertTrue(inspection.isRetryablePending)
            assertFalse(reopened.upload(inspection).isPending)
            assertEquals(frozen.commit, rig.store.latest())
            assertContentEquals(frozen.manifestBytes, rig.store.commits.getValue(frozen.commit.id))
            assertContentEquals(frozen.zip, rig.store.objects.getValue(frozen.commit.snapshot.id))
            assertNull(rig.pending()); assertEquals(1, rig.bridge.restores)
        }
    }

    @Test fun gameProgressAfterRestoreVerificationBlocksInitialPublicationWithoutLosingPlan() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        rig.bridge.afterRestoreExport = { rig.bridge.files = fixture(9) }
        assertFails { rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false) }
        assertEquals(fixture(9).mapValues { hash(it.value) }, rig.bridge.hashes())
        assertEquals(2, rig.store.commits.size); assertEquals(2, rig.store.objectAttempts)
        assertFalse(rig.facade.hasUnresolvedRestore()); assertNotNull(rig.pending())
    }

    @Test fun acceptedWritesWithLostResponsesAreVerifiedWithoutRepeatingRestoreOrPublication() {
        val rig = Rig(); val (first, second) = rig.publishTwo()
        rig.store.throwAfterObject = true; rig.store.throwAfterCommit = true
        val result = rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
        assertFalse(result.isPending); assertFalse(result.isConflict)
        assertEquals(3, rig.store.commits.size)
        assertEquals(3, rig.store.objectAttempts); assertEquals(3, rig.store.commitAttempts)
        assertEquals(1, rig.bridge.restores); assertNull(rig.pending())
        assertEquals(listOf(second.id), rig.store.latest().parents)
        assertEquals(first.snapshot.files, rig.store.latest().snapshot.files)
    }

    @Test fun lostFrozenPlanCannotFallBackToCapturingAnOrdinarySnapshot() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        rig.bridge.afterRestoreExport = {
            Files.walk(rig.root).use { paths ->
                paths.filter { it.fileName.toString() == "active-plan.txt" }.findFirst().orElseThrow().deleteExisting()
            }
        }
        assertFails { rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false) }
        assertEquals(first.snapshot.files, rig.bridge.hashes())
        assertEquals(2, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
        assertEquals(2, rig.store.commits.size); assertEquals(1, rig.bridge.restores)
    }

    @Test fun corruptObjectReadbackBlocksCommitAndRetainsFrozenRestoreWithoutAutomaticRetry() {
        val rig = Rig(); val (first, _) = rig.publishTwo()
        rig.store.corruptObject = true
        assertFailsWith<RemoteIntegrityException> {
            rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
        }
        assertEquals(first.snapshot.files, rig.bridge.hashes())
        assertFalse(rig.facade.hasUnresolvedRestore()); assertNotNull(rig.pending())
        assertEquals(3, rig.store.objectAttempts); assertEquals(2, rig.store.commitAttempts)
        assertEquals(2, rig.store.commits.size); assertEquals(1, rig.bridge.restores)
    }

    @Test fun concurrentRemoteAdvancePreservesBothBranchesAndReturnsConflict() {
        val rig = Rig(); val (first, second) = rig.publishTwo()
        var concurrent: AppendCommit? = null
        rig.store.afterObject = { concurrent = rig.store.remoteChild(second) }
        val result = rig.facade.restoreSelected(rig.facade.inspect(), first.snapshot.id, false)
        assertTrue(result.isConflict); assertFalse(result.isPending)
        assertEquals(4, rig.store.commits.size)
        val restored = rig.store.latest(); val sibling = assertNotNull(concurrent)
        assertEquals("restore", restored.kind); assertEquals(listOf(second.id), restored.parents)
        assertEquals(first.snapshot.files, restored.snapshot.files)
        assertEquals(second.snapshot.files, sibling.snapshot.files)
        val inspection = rig.facade.inspect()
        assertEquals("BRANCHED", inspection.kind)
        assertEquals(setOf(restored.id, sibling.id), inspection.remoteVersions.map { it.commitId }.toSet())
        assertEquals(first.snapshot.files, rig.bridge.hashes())
        assertNull(rig.pending()); assertFalse(rig.facade.hasUnresolvedRestore())
        assertEquals(1, rig.bridge.restores)
    }
}
