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

/** Expired-account download never needs a writable cloud or creates a retryable upload. */
class AndroidReadOnlyRestoreTest {
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
        var failRestore = false
        var throwAfterRestore = false
        var actualIdentity = identity
        override fun targetGame() = AndroidGameTarget(actualIdentity, "synthetic-896", 896, "b".repeat(64), "c".repeat(64))
        override fun exportTo(emptyDestination: Path) {
            files.forEach { (name, bytes) -> emptyDestination.resolve(name).also { it.parent.createDirectories(); it.writeBytes(bytes) } }
        }
        override fun restoreFrom(preparedDirectory: Path, expectedCurrentFiles: Map<String, String>) {
            restores++
            require(hashes() == expectedCurrentFiles) { "Synthetic bridge CAS mismatch" }
            if (failRestore) error("Synthetic helper unavailable")
            files = Files.walk(preparedDirectory).use { paths -> paths.filter { it.isRegularFile() }.toList().associate {
                preparedDirectory.relativize(it).toString().replace('\\', '/') to it.readBytes()
            } }
            if (throwAfterRestore) error("Synthetic uncertain helper reply")
        }
        fun hashes() = files.mapValues { hash(it.value) }
    }
    private class Store : AppendStore {
        val commits = linkedMapOf<String, ByteArray>()
        val objects = linkedMapOf<String, ByteArray>()
        var expired = false
        var writes = 0
        override fun listCommits(cursor: String?) = CommitPage(commits.keys.toList())
        override fun readCommit(id: String) = commits[id]?.copyOf()
        override fun readObject(id: String) = objects[id]?.copyOf()
        override fun writeObject(id: String, bytes: ByteArray) { writes++; check(!expired) { "403 expired: no DAV writes" }; objects[id] = bytes.copyOf() }
        override fun writeCommit(id: String, bytes: ByteArray) { writes++; check(!expired) { "403 expired: no DAV writes" }; commits[id] = bytes.copyOf() }
        override fun legacyPresent() = false
        fun latest() = json.decodeFromString<AppendCommit>(commits.values.last().decodeToString())
        fun cloneCommit(source: AppendCommit, parents: List<String> = listOf(source.id)): AppendCommit {
            val (child, zip) = changedHashFixture(source, objects.getValue(source.snapshot.id), parents, commits.size)
            objects[child.snapshot.id] = zip
            return child.also { commits[it.id] = json.encodeToString(it).encodeToByteArray() }
        }
    }
    private inner class Rig(val store: Store = Store()) {
        val root = temp.resolve("workspace-${sequence++}")
        val bridge = Bridge()
        val facade = reopen()
        fun reopen() = AndroidSyncFacade(root, connection, bridge, identity, store, 0)
        fun repo() = AppendRepository(find("append-v2"), AndroidGameAdapter(identity), connection.profile)
        fun find(name: String): Path = Files.walk(root).use { paths -> paths.filter { it.fileName.toString() == name }.findFirst().orElseThrow() }
        fun sidecar() = assertIs<SidecarRead.Valid>(DirectoryMetadataStore(find("mirror")).read()).value
        fun publishTwo(): Pair<AppendCommit, AppendCommit> {
            assertEquals("UPLOADED", facade.autoUpload().status)
            val first = store.latest()
            bridge.files = fixture(2)
            assertEquals("UPLOADED", facade.autoUpload().status)
            return first to store.latest()
        }
        fun expire() { store.expired = true; store.writes = 0 }
        fun assertReadOnlyComplete(expected: Map<String, String>) {
            assertEquals(expected, bridge.hashes()); assertEquals(0, store.writes)
            assertNull(repo().pending()); assertFalse(facade.hasUnresolvedRestore())
            assertFalse(find("append-v2").resolve("apply-required.txt").exists())
        }
    }

    @Test fun expiredTipRestoreRequiresUniqueOverwriteApprovalWithoutCreatingLocalBackup() {
        val source = Rig(); assertEquals("UPLOADED", source.facade.autoUpload().status)
        val selected = source.store.latest()
        val rig = Rig(source.store); rig.bridge.files = fixture(9); rig.expire()
        val inspection = rig.facade.inspect()
        assertFails { rig.facade.restoreSelectedReadOnly(inspection, selected.snapshot.id, false) }
        assertEquals(fixture(9).mapValues { hash(it.value) }, rig.bridge.hashes()); assertEquals(0, rig.bridge.restores)
        val result = rig.facade.restoreSelectedReadOnly(inspection, selected.snapshot.id, true)
        assertFalse(result.isPending); assertFalse(result.isConflict)
        rig.assertReadOnlyComplete(selected.snapshot.files)
        assertEquals(selected.id, rig.repo().baseline())
        assertEquals(selected.snapshot.directoryMetadata?.runs, rig.sidecar().runs)
        assertEquals(selected.id, rig.sidecar().lastSynced?.commitId)
        assertFalse(rig.facade.listVersions().any { it.reason == "恢复前自动备份" })
        assertEquals(1, rig.store.commits.size); assertEquals(1, rig.bridge.restores)

    }

    @Test fun expiredEmptyTargetDownloadsTipWithoutCreatingCloudWritesOrPending() {
        val source = Rig(); assertEquals("UPLOADED", source.facade.autoUpload().status)
        val selected = source.store.latest(); val rig = Rig(source.store)
        rig.bridge.files = emptyMap(); rig.expire()
        rig.facade.restoreSelectedReadOnly(rig.facade.inspect(), selected.snapshot.id, false)
        rig.assertReadOnlyComplete(selected.snapshot.files)
        assertEquals(selected.id, rig.repo().baseline()); assertEquals(1, rig.bridge.restores)
    }

    @Test fun historicalDownloadAdoptsSelectedBaselineAndRenewalDoesNotAutoPublishOldBytes() {
        val rig = Rig(); val (first, second) = rig.publishTwo(); rig.expire()
        val result = rig.facade.restoreSelectedReadOnly(rig.facade.inspect(), first.snapshot.id, false)
        assertFalse(result.isPending); rig.assertReadOnlyComplete(first.snapshot.files)
        assertEquals(first.id, rig.repo().baseline()); assertEquals(first.id,rig.sidecar().lastSynced?.commitId)
        assertEquals(first.snapshot.directoryMetadata?.runs, rig.sidecar().runs)
        assertEquals(2, rig.store.commits.size)
        rig.store.expired = false
        assertEquals("SKIPPED_CONFLICT", rig.facade.autoUpload().status)
        assertEquals(2,rig.store.commits.size);assertEquals(second.id,rig.store.latest().id)
        assertEquals(first.id,rig.repo().baseline());assertNull(rig.repo().pending())

    }

    @Test fun historicalDownloadAdoptsVerifiedSelectedBaselineForNewInstallation() {
        val source = Rig(); val (first, _) = source.publishTwo()
        val rig = Rig(source.store); rig.bridge.files = emptyMap(); rig.expire()
        rig.facade.restoreSelectedReadOnly(rig.facade.inspect(), first.snapshot.id, false)
        rig.assertReadOnlyComplete(first.snapshot.files)
        assertEquals(first.id,rig.repo().baseline()); assertEquals(first.id,rig.sidecar().lastSynced?.commitId)
        assertFalse(rig.facade.inspect().canAutoUpload)
    }

    @Test fun legacySnapshotRetainsOnlyIdentitiesProvenByUnchangedCompleteRunBytes() {
        for (sameBytes in listOf(true, false)) {
            val source = Rig(); assertEquals("UPLOADED", source.facade.autoUpload().status)
            val selected = source.store.latest().let { it.copy(snapshot = it.snapshot.copy(directoryMetadata = null)) }
            source.store.commits[selected.id] = json.encodeToString(selected).encodeToByteArray()
            val rig = Rig(source.store); if (!sameBytes) rig.bridge.files = fixture(9)
            rig.expire(); val inspection = rig.facade.inspect()
            val prior = DirectoryMetadataStore(rig.find("mirror")).observe(rig.bridge.hashes())
            assertNotNull(prior.runs.getValue("game1").runUUID)
            rig.facade.restoreSelectedReadOnly(inspection, selected.snapshot.id, true)
            rig.assertReadOnlyComplete(selected.snapshot.files)
            if (sameBytes) assertEquals(prior.runs, rig.sidecar().runs)
            else assertNull(rig.sidecar().runs.getValue("game1").runUUID)
            // Equal current-tip bytes are a verified no-op, not a new baseline adoption.
            assertEquals(if (sameBytes) null else selected.id, rig.repo().baseline())
            assertEquals(if (sameBytes) 0 else 1, rig.bridge.restores)
        }
    }

    @Test fun renewalAfterRemoteAdvancePreservesSiblingInsteadOfAbsorbingOrRewinding() {
        val rig = Rig(); val (first, second) = rig.publishTwo(); rig.expire()
        rig.facade.restoreSelectedReadOnly(rig.facade.inspect(), first.snapshot.id, false)
        rig.assertReadOnlyComplete(first.snapshot.files)
        rig.store.expired = false
        val otherDevice = Rig(rig.store); otherDevice.bridge.files = emptyMap()
        otherDevice.facade.restoreSelectedReadOnly(otherDevice.facade.inspect(), second.snapshot.id, false)
        otherDevice.bridge.files = fixture(3)
        assertEquals("UPLOADED", otherDevice.facade.autoUpload().status)
        val sibling = rig.store.latest()
        assertEquals("SKIPPED_CONFLICT", rig.facade.autoUpload().status)
        val result = rig.facade.upload(rig.facade.inspect())
        assertFalse(result.isPending); assertFalse(result.isConflict)
        assertEquals(sibling.id,rig.store.latest().id); assertEquals(3,rig.store.commits.size)
        assertEquals(setOf(sibling.id),rig.repo().discover(rig.store).tips)
        assertEquals(first.id,rig.repo().baseline()); assertEquals(2,rig.store.writes)
    }

    @Test fun choosingCloudBranchDownloadsButNeverPublishesResolutionOrDiscardsOtherTips() {
        val rig = Rig(); val (first, second) = rig.publishTwo()
        val sibling = rig.store.cloneCommit(first, emptyList()); rig.expire()
        val result = rig.facade.restoreSelectedReadOnly(rig.facade.inspect(), sibling.snapshot.id, false)
        assertTrue(result.isConflict); assertFalse(result.isPending)
        rig.assertReadOnlyComplete(sibling.snapshot.files)
        assertEquals(sibling.id, rig.repo().baseline())
        assertEquals(setOf(second.id, sibling.id), rig.repo().discover(rig.store).tips)
        assertEquals("BRANCHED", rig.facade.inspect().kind); assertEquals(3, rig.store.commits.size)
    }

    @Test fun changedGameOrRemoteInvalidatesConfirmationWithoutChangingActualGame() {
        for (changeGame in listOf(true, false)) {
            val rig = Rig(); val (first, second) = rig.publishTwo(); rig.expire()
            val inspection = rig.facade.inspect()
            if (changeGame) rig.bridge.files = fixture(8) else rig.store.cloneCommit(second)
            val before = rig.bridge.hashes()
            assertFails { rig.facade.restoreSelectedReadOnly(inspection, first.snapshot.id, false) }
            rig.assertReadOnlyComplete(before); assertEquals(0, rig.bridge.restores)
        }
    }

    @Test fun corruptSnapshotOrMutatedMetadataFailsBeforeHelperWrites() {
        for (corruptObject in listOf(true, false)) {
            val rig = Rig(); val (first, second) = rig.publishTwo(); rig.expire()
            val inspection = rig.facade.inspect()
            if (corruptObject) rig.store.objects[first.snapshot.id] = byteArrayOf(0)
            else rig.store.commits[first.id] = json.encodeToString(first.copy(snapshot = first.snapshot.copy(
                directoryMetadata = first.snapshot.directoryMetadata?.copy(contentSha256 = "0".repeat(64))))).encodeToByteArray()
            assertFails { rig.facade.restoreSelectedReadOnly(inspection, first.snapshot.id, false) }
            rig.assertReadOnlyComplete(second.snapshot.files); assertEquals(0, rig.bridge.restores)
        }
    }

    @Test fun verifiedHashesDoNotBypassLoaderVersionOrPackageFamilyChecks() {
        for (newerLoader in listOf(true, false)) {
            val source = Rig()
            if (newerLoader) source.bridge.files = fixture(1).mapValues { it.value.decodeToString().replace("896", "999").encodeToByteArray() }
            assertEquals("UPLOADED", source.facade.autoUpload().status)
            val selected = source.store.latest().let { if (newerLoader) it else it.copy(snapshot = it.snapshot.copy(gamePackage = "com.example.othergame")) }
            source.store.commits[selected.id] = json.encodeToString(selected).encodeToByteArray()
            val rig = Rig(source.store); rig.bridge.files = emptyMap(); rig.expire()
            assertFails { rig.facade.restoreSelectedReadOnly(rig.facade.inspect(), selected.snapshot.id, false) }
            rig.assertReadOnlyComplete(emptyMap())
            assertNull(rig.repo().baseline()); assertEquals(0, rig.bridge.restores)
        }
    }

    @Test fun existingFrozenUploadIsNotArchivedOrReplacedByReadOnlyRequest() {
        val rig = Rig(); val (first, _) = rig.publishTwo(); rig.expire()
        rig.bridge.files = fixture(3)
        rig.facade.autoUpload()
        val frozen = assertNotNull(rig.repo().pending()); val writes = rig.store.writes
        assertFails { rig.facade.restoreSelectedReadOnly(rig.facade.inspect(), first.snapshot.id, true) }
        assertEquals(frozen.commit, rig.repo().pending()?.commit)
        assertContentEquals(frozen.zip, rig.repo().pending()?.zip)
        assertEquals(writes, rig.store.writes); assertEquals(0, rig.bridge.restores)
        assertEquals(fixture(3).mapValues { hash(it.value) }, rig.bridge.hashes())
    }

    @Test fun helperFailureKeepsRecoveryJournalWithoutAnyUploadPlanAndCanResumeReadOnly() {
        val rig = Rig(); val (first, second) = rig.publishTwo(); rig.expire(); rig.bridge.failRestore = true
        assertFails { rig.facade.restoreSelectedReadOnly(rig.facade.inspect(), first.snapshot.id, false) }
        assertEquals(second.snapshot.files, rig.bridge.hashes()); assertNull(rig.repo().pending())
        assertTrue(rig.facade.hasUnresolvedRestore()); assertEquals(0, rig.store.writes)
        assertEquals("RESTORE_RECOVERY_REQUIRED", rig.facade.autoUpload().reasonCode)
        rig.bridge.failRestore = false; rig.reopen().recoverRestore()
        rig.assertReadOnlyComplete(first.snapshot.files); assertEquals(first.id, rig.repo().baseline())
    }

    @Test fun uncertainHelperReplyRecoversWithoutRestoringAlreadyAppliedGameAgain() {
        val rig = Rig(); val (first, _) = rig.publishTwo(); rig.expire(); rig.bridge.throwAfterRestore = true
        assertFails { rig.facade.restoreSelectedReadOnly(rig.facade.inspect(), first.snapshot.id, false) }
        assertEquals(first.snapshot.files, rig.bridge.hashes()); assertTrue(rig.facade.hasUnresolvedRestore())
        rig.bridge.throwAfterRestore = false; rig.reopen().recoverRestore()
        rig.assertReadOnlyComplete(first.snapshot.files); assertEquals(1, rig.bridge.restores)
    }

    @Test fun helperFailureCanExplicitlyDiscardStagedDownloadAndKeepOriginalActualFiles() {
        val rig = Rig(); val (first, second) = rig.publishTwo(); rig.expire(); rig.bridge.failRestore = true
        assertFails { rig.facade.restoreSelectedReadOnly(rig.facade.inspect(), first.snapshot.id, false) }
        rig.facade.discardInterruptedRestore()
        rig.assertReadOnlyComplete(second.snapshot.files); assertEquals(first.id, rig.repo().baseline())
        assertEquals(2, rig.store.commits.size)
    }

    @Test fun localApplyFailureRetainsPartialMirrorAndRecoveryGateWithoutBackup() {
        val rig = Rig(); val (first, second) = rig.publishTwo(); rig.expire()
        val inspection = rig.facade.inspect()
        val sidecar = rig.find("mirror").resolve(DIRECTORY_METADATA_FILE)
        val originalSidecar = sidecar.readBytes()
        sidecar.deleteExisting(); sidecar.createDirectory() // Force metadata adoption to fail after file replacement.
        assertFails { rig.facade.restoreSelectedReadOnly(inspection, first.snapshot.id, false) }
        assertEquals(second.snapshot.files, rig.bridge.hashes())
        assertEquals(first.snapshot.files, Snapshots.capture(rig.find("mirror"), AndroidGameAdapter(identity), connection.profile, "test", null).manifest.files)
        assertTrue(rig.facade.hasUnresolvedRestore())
        assertTrue(rig.find("append-v2").resolve("apply-required.txt").exists())
        assertNull(rig.repo().pending()); assertEquals(0, rig.store.writes)
        assertFalse(Files.walk(rig.root).use { paths -> paths.anyMatch { it.parent?.fileName.toString()=="backups" && it.fileName.toString().endsWith(".zip") } })
        sidecar.deleteExisting(); sidecar.writeBytes(originalSidecar)
        val reopened = rig.reopen()
        assertFails { reopened.recoverRestore() } // A failed metadata adoption does not produce a ready helper transaction.
        reopened.discardInterruptedRestore()
        rig.assertReadOnlyComplete(second.snapshot.files)
        reopened.restoreSelectedReadOnly(reopened.inspect(), first.snapshot.id, false)
        rig.assertReadOnlyComplete(first.snapshot.files)
    }

    @Test fun foreignInspectionAndChangedTargetRemainRejectedInDownloadMode() {
        val rig = Rig(); val (first, _) = rig.publishTwo(); rig.expire()
        val inspection = rig.facade.inspect()
        assertFails { rig.reopen().restoreSelectedReadOnly(inspection, first.snapshot.id, false) }
        rig.bridge.actualIdentity = AndroidGameIdentity(identity.packageName, "Changed", "d".repeat(64), identity.saveCapabilities)
        assertFails { rig.facade.restoreSelectedReadOnly(inspection, first.snapshot.id, false) }
        assertEquals(0, rig.store.writes); assertEquals(0, rig.bridge.restores)
    }
}
