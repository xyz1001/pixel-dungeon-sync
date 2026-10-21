package io.github.xyz1001.pdsync.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pdsync.AppendCommit
import pdsync.CommitGraph
import pdsync.Manifest
import pdsync.SaveSummary
import pdsync.SyncHistory

class SharedHomeContentTest {
    private val files = mapOf("game1/game.dat" to "a".repeat(64), "game1/level1.dat" to "b".repeat(64),
        "badges.dat" to "c".repeat(64))
    private fun commit(id: String, content: Map<String, String> = files, parents: List<String> = emptyList()) =
        AppendCommit(id = id, parents = parents, objectBytes = 1,
            snapshot = Manifest(game = "shattered", profile = "real-profile", version = "922",
                id = "snapshot-$id", parent = null, sha256 = "d".repeat(64), files = content))
    private fun history(commits: List<AppendCommit> = listOf(commit("tip")), current: Map<String, String> = files,
        baseline: String? = null) = SyncHistory(CommitGraph(commits.associateBy { it.id }, emptyMap()),
        baseline, current, "同名存档", SaveSummary(mapOf("game1" to "2026-10-15T01:00:00Z"), 10, current.size), emptyMap())
    private fun content(history: SyncHistory?, selected: String? = null, verified: Boolean = true,
        pending: Boolean = false, conflict: Boolean = false) = SharedHomeContent.from(history, selected, verified, pending, conflict)

    @Test fun completeFileMapsMatchIndependentlyOfOrderingBaselineAndLabels() {
        val history = history(current = files.entries.reversed().associate { it.toPair() })
        assertEquals(SharedHomeContent(true, true, matchesCloudTip = true), content(history))
        assertEquals(SharedHomeContent(true, true, matchesCloudTip = true), content(history.copy(baseline = "absent-baseline",
            currentName = "改名", aliases = mapOf("snapshot-tip" to "另一名称"), summary = null)))
        assertEquals(SharedHomeContent(true, true, matchesCloudTip = true), content(history.copy(baseline = "tip")))
    }

    @Test fun metadataMatchesNeverSuppressTransferWithoutVerifiedObjectIds() {
        val metadata = history().copy(verifiedCommitIds = emptySet())
        assertEquals(SharedHomeContent(matchesCloudTip = true), content(metadata))
        assertFalse(metadata.currentProtected)
        assertEquals(SharedHomeContent(matchesCloudTip = true), content(metadata.copy(verifiedCommitIds = setOf("another-account-tip"))))
        assertEquals(SharedHomeContent(true, true, matchesCloudTip = true), content(metadata.copy(verifiedCommitIds = setOf("tip"))))
        val old = commit("old")
        val tip = commit("tip", parents = listOf("old"))
        val partlyVerified = history(listOf(old, tip)).copy(verifiedCommitIds = setOf("old"))
        assertEquals(SharedHomeContent(false, true, matchesCloudTip = true), content(partlyVerified, "snapshot-old"))
        assertEquals(SharedHomeContent(matchesCloudTip = true), content(partlyVerified, "snapshot-tip"))
    }

    @Test fun homeMetadataEqualitySuppressesOnlyTheMatchingTipWithoutChangingProof() {
        val old = commit("old", files + ("badges.dat" to "e".repeat(64)))
        val tip = commit("tip", parents = listOf("old"))
        val observed = history(listOf(old, tip)).copy(verifiedCommitIds = emptySet())
        val before = observed.copy(currentFiles = observed.currentFiles.toMap())
        fun home(selected: String? = null, verified: Boolean = true, pending: Boolean = false,
            conflict: Boolean = false) = SharedHomeContent.fromHome(observed, selected, verified, pending, conflict)
        assertEquals(SharedHomeContent(true, true, true), home())
        assertEquals(SharedHomeContent(true, true, true), home("snapshot-tip"))
        assertEquals(SharedHomeContent(true, false, true), home("snapshot-old"))
        assertEquals(SharedHomeContent(true, false, true), home("missing"))
        assertEquals(SharedHomeContent(), home(verified = false))
        assertEquals(SharedHomeContent(), home(pending = true))
        assertEquals(SharedHomeContent(), home(conflict = true))
        assertEquals(SharedHomeContent(matchesCloudTip = true), content(observed))
        assertFalse(observed.currentProtected)
        assertEquals(before, observed)
    }

    @Test fun everyPathAndEveryFileHashMattersEvenWithTheSameCounts() {
        for (changed in listOf(files + ("game1/level1.dat" to "e".repeat(64)),
            files + ("badges.dat" to "f".repeat(64)),
            files - "badges.dat" + ("journal.dat" to files.getValue("badges.dat")),
            files - "game1/level1.dat", files + ("game1/level2.dat" to "e".repeat(64)))) {
            assertEquals(SharedHomeContent(), content(history(current = changed)))
        }
    }

    @Test fun uploadUsesSoleTipButRestoreUsesTheActualSelectedHistoryTarget() {
        val older = commit("old")
        val newer = commit("tip", files + ("badges.dat" to "e".repeat(64)), listOf("old"))
        val history = history(listOf(older, newer), baseline = "old")
        assertTrue(history.clean, "A baseline match is not a cloud-tip match")
        assertEquals(SharedHomeContent(false, true), content(history, "snapshot-old"))
        assertEquals(SharedHomeContent(), content(history))
        assertEquals(SharedHomeContent(), content(history, "missing-target"))
        val atTip = history.copy(currentFiles = newer.snapshot.files)
        assertEquals(SharedHomeContent(true, false, matchesCloudTip = true), content(atTip, "snapshot-old"))
        assertEquals(SharedHomeContent(true, false, matchesCloudTip = true), content(atTip, "missing-target"))
    }

    @Test fun successiveRestoreReceiptsEnableDifferentTargetsAndDisableOnlyActualBytes() {
        val a = commit("a")
        val b = commit("b", files + ("badges.dat" to "e".repeat(64)), listOf("a"))
        val c = commit("c", files + ("badges.dat" to "f".repeat(64)), listOf("b"))
        val graph = history(listOf(a, b, c))
        // The sole cloud tip remains C while verified local receipts move A -> B -> A.
        // Neither baseline identity, previous selection nor newest-cloud status disables B.
        for (actual in listOf(a, b, a)) {
            val receipt = graph.copy(currentFiles = actual.snapshot.files, baseline = actual.id,
                baselineDirection = "restore")
            for (target in listOf(a, b, c)) {
                val state = SharedHomeContent.fromHome(receipt, target.snapshot.id, true, false, false)
                assertEquals(actual.snapshot.files == target.snapshot.files, state.restoreUnnecessary,
                    "actual=${actual.id}, selected=${target.id}")
            }
            assertFalse(SharedHomeContent.fromHome(receipt, c.snapshot.id, true, false, false).uploadUnnecessary)
        }
    }

    @Test fun unknownFailedEmptyOrUnsettledHistoryDoesNotSuppressHostRecovery() {
        assertEquals(SharedHomeContent(), content(null))
        assertEquals(SharedHomeContent(), content(history(), verified = false))
        assertEquals(SharedHomeContent(), content(history(current = emptyMap())))
        assertEquals(SharedHomeContent(), content(history(emptyList())))
        assertEquals(SharedHomeContent(), content(history(emptyList(), emptyMap())))
        val history = history()
        assertEquals(SharedHomeContent(), content(history.copy(graph = history.graph.copy(pending = mapOf("missing" to "unverified")))))
    }

    @Test fun pendingRetriesAndBranchSelectionAreNeverRemovedForMatchingBytes() {
        assertEquals(SharedHomeContent(), content(history(), pending = true))
        assertEquals(SharedHomeContent(), content(history(), conflict = true))
        val branched = history(listOf(commit("left"), commit("right")))
        assertEquals(SharedHomeContent(), content(branched))
        assertEquals(SharedHomeContent(), content(branched, "snapshot-left"))
    }

    @Test fun repeatedRenderingIsPureAndRetainedCallbacksRecheckChangedCapabilities() {
        val history = history().copy(verifiedCommitIds = emptySet())
        val original = history.copy(currentFiles = history.currentFiles.toMap())
        repeat(20) { assertEquals(SharedHomeContent(true, true, matchesCloudTip = true), SharedHomeContent.fromHome(history, null, true, false, false)) }
        assertEquals(original, history)

        var state = SharedHomeState(null, "云端已连接", pageActive = true, canUpload = true, canRestore = true)
        val calls = mutableListOf<String>()
        val actions = object : SharedHomeActions {
            override fun upload() { calls += "upload" }
            override fun restore() { calls += "restore" }
            override fun settings() = Unit
            override fun launch() = Unit
            override fun selectSnapshot(snapshotId: String) = Unit
            override fun rename(snapshotId: String?) = Unit
            override fun changeHistoryPage(page: Int) = Unit
        }
        val interactions = SharedHomeInteractions({ state }, { actions })
        val retainedUpload = interactions::upload
        val retainedRestore = interactions::restore
        val same = SharedHomeContent.fromHome(history, null, true, false, false)
        state = state.copy(canUpload = !same.uploadUnnecessary, canRestore = !same.restoreUnnecessary)
        retainedUpload(); retainedRestore()
        assertTrue(calls.isEmpty())
        val different = content(history.copy(currentFiles = files + ("badges.dat" to "f".repeat(64))))
        state = state.copy(canUpload = !different.uploadUnnecessary, canRestore = !different.restoreUnnecessary)
        retainedUpload(); retainedRestore()
        assertEquals(listOf("upload", "restore"), calls)
        assertFalse(content(history, verified = false).uploadUnnecessary)
    }
}
