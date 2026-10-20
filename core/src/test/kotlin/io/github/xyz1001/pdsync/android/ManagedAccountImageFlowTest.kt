package io.github.xyz1001.pdsync.android

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedAccountImageFlowTest {
    private fun visible() = ManagedAccountFlow("alice").apply { foreground(true) }
    private val firstHash = "a".repeat(64)
    private val changedHash = "b".repeat(64)

    @Test fun backgroundRetainsAccountDisplayAndAllowsExistingReadToComplete() {
        val flow = visible()
        val ticket = assertNotNull(flow.refresh())
        assertTrue(flow.metadata(ticket, firstHash))
        assertTrue(flow.image(ticket, byteArrayOf(1, 2, 3)))
        assertTrue(flow.entitlement(ticket, "alice", 100L, true, true, 10L))
        val generation = flow.view().generation
        flow.foreground(false)
        assertContentEquals(byteArrayOf(1, 2, 3), flow.view().image)
        assertEquals("alice", flow.view().username)
        assertEquals("alice", flow.view().localUsername)
        assertEquals(100L, flow.view().expiresMs)
        assertTrue(flow.view().entitlementLoaded)
        assertTrue(flow.view().busy)
        assertEquals(generation, flow.view().generation)
        assertFalse(flow.view().canRefresh)
        assertNull(flow.refresh())
        assertTrue(flow.accepts(ticket))
        assertTrue(flow.entitlement(ticket, "alice", 200L, true, true, 11L))
        assertTrue(flow.complete(ticket))
        assertFalse(flow.view().busy)
        assertFalse(flow.copyUsername(generation) { error("Background copy") })
        flow.foreground(true)
        assertEquals(generation, flow.view().generation)
        assertEquals("alice", flow.view().username)
        assertEquals(200L, flow.view().expiresMs)
        assertTrue(flow.view().entitlementLoaded)
        assertFalse(flow.view().cachedExpiry)
        assertTrue(flow.view().canRefresh)
        assertFalse(flow.view().busy)
        assertContentEquals(byteArrayOf(1, 2, 3), flow.view().image)
        assertFalse(flow.complete(ticket))
    }

    @Test fun queuedPublicImageAndFailureCanFinishWhileBackgrounded() {
        val flow = visible()
        val ticket = assertNotNull(flow.refresh())
        flow.foreground(false)
        assertTrue(flow.metadata(ticket, firstHash))
        assertTrue(flow.image(ticket, byteArrayOf(9)))
        assertTrue(flow.fail(ticket, "账号信息暂时无法更新。"))
        assertFalse(flow.view().busy)
        flow.foreground(true)
        assertContentEquals(byteArrayOf(9), flow.view().image)
        assertEquals("账号信息暂时无法更新。", flow.view().message)
        assertNull(flow.view().username)
        assertTrue(flow.view().canRefresh)
    }

    @Test fun liveServerClockExpiresAcrossPauseWithoutDesktopBootId() {
        var elapsed = 500L
        val clock = object : ManagedAccountFlow.Clock {
            override fun elapsedMs() = elapsed
            override fun bootCount() = -1
        }
        val cached = TrustedEntitlementCache.Record(TrustedEntitlementCache.OFFICIAL_ENDPOINT,
            "alice", 1_100L, true, 1_000L, elapsed, -1)
        val flow = ManagedAccountFlow("alice", cached, clock).apply { foreground(true) }
        elapsed = 600L
        assertFalse(flow.view().expired) // A persisted unknown-boot clock cannot prove elapsed time.
        val ticket = assertNotNull(flow.refresh())
        assertTrue(flow.entitlement(ticket, "alice", 1_100L, true, true, 1_000L))
        assertTrue(flow.complete(ticket))
        flow.foreground(false)
        elapsed = 699L
        assertFalse(flow.view().expired)
        elapsed = 700L
        assertTrue(flow.view().expired)
        assertEquals("服务已到期", ManagedAccountFlow.accessStatus(flow.view()))
        flow.foreground(true)
        assertTrue(flow.view().expired)
        assertEquals(1_100L, flow.view().expiresMs)
        assertFalse(flow.view().busy)
        val refresh = assertNotNull(flow.refresh())
        assertTrue(flow.view().expired) // This process still owns the trusted monotonic anchor.
        assertTrue(flow.entitlement(refresh, "alice", 1_300L, true, true, 1_100L))
        assertTrue(flow.complete(refresh))
        assertFalse(flow.view().expired)
        elapsed = 600L
        assertFalse(flow.view().expired) // A regressed monotonic clock cannot invent expiry.
    }

    @Test fun closeAndAccountReplacementRejectPausedReadCallbacks() {
        val flow = visible()
        val ticket = assertNotNull(flow.refresh())
        flow.entitlement(ticket, "alice", 100L, true, true, 10L)
        flow.image(ticket, byteArrayOf(1))
        flow.foreground(false)
        flow.close()
        assertNull(flow.view().username)
        assertNull(flow.view().localUsername)
        assertNull(flow.view().expiresMs)
        assertNull(flow.view().image)
        assertFalse(flow.view().busy)
        assertFalse(flow.entitlement(ticket, "alice", 200L, true, true, 11L))
        assertFalse(flow.metadata(ticket, firstHash))
        assertFalse(flow.image(ticket, byteArrayOf(2)))
        assertFalse(flow.complete(ticket))
        assertFalse(flow.fail(ticket, "stale"))
        flow.foreground(true)
        assertNull(flow.refresh())
        val replacement = ManagedAccountFlow("bob").apply { foreground(true) }
        assertNotNull(replacement.refresh())
        assertFalse(replacement.entitlement(ticket, "bob", 200L, true, true, 11L))
        assertFalse(replacement.image(ticket, byteArrayOf(3)))
        assertNull(replacement.view().username)
        assertNull(replacement.view().image)
    }

    @Test fun transientFailureKeepsImageAndNeverCreatesAuthenticationProof() {
        val flow = visible()
        var ticket = assertNotNull(flow.refresh())
        flow.metadata(ticket, firstHash); flow.image(ticket, byteArrayOf(7)); flow.complete(ticket)
        ticket = assertNotNull(flow.refresh())
        flow.fail(ticket, "账号信息暂时无法更新。")
        assertContentEquals(byteArrayOf(7), flow.view().image)
        assertNull(flow.view().username)
        assertFalse(flow.view().entitlementLoaded)
        assertFalse(flow.view().active)
    }

    @Test fun knownMetadataChangeInvalidatesImageUntilValidatedReplacementArrives() {
        val flow = visible()
        var ticket = assertNotNull(flow.refresh())
        flow.metadata(ticket, firstHash); flow.image(ticket, byteArrayOf(1)); flow.complete(ticket)
        ticket = assertNotNull(flow.refresh())
        assertFalse(flow.metadata(ticket, "untrusted-hash"))
        assertTrue(flow.hasImage(ticket))
        assertTrue(flow.metadata(ticket, changedHash))
        assertNull(flow.view().image)
        flow.fail(ticket, "赞赏码暂时无法加载。")
        assertNull(flow.view().image)
        ticket = assertNotNull(flow.refresh())
        flow.metadata(ticket, changedHash); flow.image(ticket, byteArrayOf(2)); flow.complete(ticket)
        assertContentEquals(byteArrayOf(2), flow.view().image)
    }

    @Test fun cachedBytesRemainDefensiveCopiesAndCloseDiscardsThem() {
        val flow = visible()
        val ticket = assertNotNull(flow.refresh())
        val original = byteArrayOf(1, 2, 3)
        flow.metadata(ticket, firstHash); flow.image(ticket, original); flow.complete(ticket)
        original[0] = 9
        val exported = assertNotNull(flow.view().image)
        exported[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), flow.view().image)
        flow.close()
        assertNull(flow.view().image)
        assertFalse(flow.image(ticket, original))
        assertFalse(flow.metadata(ticket, changedHash))
        val otherAccount = ManagedAccountFlow("bob").apply { foreground(true) }
        assertNull(otherAccount.view().image)
        assertFalse(otherAccount.image(ticket, original))
    }
}
