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

class SharedHomeArchiveAvailabilityTest {
    private val localFiles = mapOf("game1/game.dat" to "a".repeat(64))
    private val cloudFiles = mapOf("game2/game.dat" to "b".repeat(64))
    private fun commit(id: String = "tip", files: Map<String, String> = cloudFiles,
        parents: List<String> = emptyList()) = AppendCommit(id = id, parents = parents, objectBytes = 1,
        snapshot = Manifest(game = "shattered", profile = "isolated-test", version = "922",
            id = "snapshot-$id", parent = null, sha256 = "c".repeat(64), files = files))
    private fun history(local: Map<String, String> = localFiles,
        commits: List<AppendCommit> = listOf(commit())) = SyncHistory(
        CommitGraph(commits.associateBy { it.id }, emptyMap()), null, local, "本机进度", null, emptyMap())
    private fun availability(history: SyncHistory?, selected: String? = null, verified: Boolean = true) =
        SharedHomeArchiveAvailability.from(history, selected, verified)

    @Test fun doubleEmptyDoesNotOfferUploadOrRestore() {
        assertEquals(SharedHomeArchiveAvailability(false, false), availability(history(emptyMap(), emptyList())))
    }

    @Test fun localOnlyOffersUploadAndCloudOnlyOffersRestore() {
        assertEquals(SharedHomeArchiveAvailability(true, false), availability(history(commits = emptyList())))
        assertEquals(SharedHomeArchiveAvailability(false, true), availability(history(local = emptyMap())))
    }

    @Test fun differentArchivesOfferBothSourcesWhileMatchingContentStillHasBothSources() {
        assertEquals(SharedHomeArchiveAvailability(true, true), availability(history()))
        val matching = history(local = cloudFiles)
        assertEquals(SharedHomeArchiveAvailability(true, true), availability(matching))
        assertEquals(SharedHomeContent(true, true, matchesCloudTip = true), SharedHomeContent.from(matching, null, true, false, false),
            "Source existence and the existing no-op protection remain separate policies")
    }

    @Test fun emptyCloudSnapshotDoesNotOfferRestoreEvenWhenSelectedOrDefault() {
        val empty = commit(files = emptyMap())
        assertEquals(SharedHomeArchiveAvailability(true, false), availability(history(commits = listOf(empty))))
        assertEquals(SharedHomeArchiveAvailability(true, false),
            availability(history(commits = listOf(empty)), empty.snapshot.id))
        assertEquals(SharedHomeArchiveAvailability(false, false),
            availability(history(emptyMap(), listOf(empty)), empty.snapshot.id))
    }

    @Test fun missingExplicitSelectionNeverFallsBackToANonemptyDefaultOrBranches() {
        assertEquals(SharedHomeArchiveAvailability(true, false), availability(history(), "missing-snapshot"))
        assertEquals(SharedHomeArchiveAvailability(true, false), availability(history(), ""))
        val branches = history(commits = listOf(commit("left"), commit("right")))
        assertEquals(SharedHomeArchiveAvailability(true, false), availability(branches, "missing-snapshot"))
    }

    @Test fun selectedHistoricalCloudSnapshotCanRestoreWithoutChangingTheDefault() {
        val older = commit("older")
        val newest = commit("newest", emptyMap(), listOf(older.id))
        val observed = history(commits = listOf(older, newest))
        assertFalse(availability(observed).restoreSourceAvailable)
        assertTrue(availability(observed, older.snapshot.id).restoreSourceAvailable)
        assertEquals(newest.snapshot.id, observed.defaultSnapshot)
    }

    @Test fun branchesRetainTheExistingChooserOnlyWhenANonemptyTipExists() {
        val left = commit("left")
        val right = commit("right", emptyMap())
        val branches = history(commits = listOf(left, right))
        assertEquals(null, branches.defaultSnapshot)
        assertTrue(availability(branches).restoreSourceAvailable)
        assertFalse(availability(branches, right.snapshot.id).restoreSourceAvailable)
        assertTrue(availability(branches, left.snapshot.id).restoreSourceAvailable)
        assertFalse(availability(history(commits = listOf(left.copy(snapshot = left.snapshot.copy(files = emptyMap())), right)))
            .restoreSourceAvailable)
        val ancestor = commit("ancestor")
        val emptyBranches = history(commits = listOf(ancestor,
            commit("empty-left", emptyMap(), listOf(ancestor.id)),
            commit("empty-right", emptyMap(), listOf(ancestor.id))))
        assertFalse(availability(emptyBranches).restoreSourceAvailable,
            "An old nonempty ancestor must not fabricate a default branch choice")
        assertTrue(availability(emptyBranches, ancestor.snapshot.id).restoreSourceAvailable)
    }

    @Test fun nullUnverifiedAndUnsettledObservationsFailClosedForBothDirections() {
        assertEquals(SharedHomeArchiveAvailability(), availability(null))
        assertEquals(SharedHomeArchiveAvailability(), availability(history(), verified = false))
        val observed = history()
        assertEquals(SharedHomeArchiveAvailability(), availability(observed.copy(
            graph = observed.graph.copy(pending = mapOf("unverified" to "missing archive")))))
    }

    @Test fun labelsCountsAndBaselineAreNotArchiveEvidenceAndCalculationIsPure() {
        val empty = history(emptyMap(), emptyList()).copy(currentName = "已有存档", baseline = "old-baseline",
            summary = SaveSummary(mapOf("game1" to "2026-10-19T00:00:00Z"), 999, 20),
            aliases = mapOf("absent" to "云端存档"))
        val original = empty.copy()
        repeat(20) { assertEquals(SharedHomeArchiveAvailability(), availability(empty)) }
        assertEquals(original, empty)
    }
}
