package io.github.xyz1001.pdsync.android

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedAccountImageFlowTest {
    private fun visible() = ManagedAccountFlow("alice").apply { foreground(true) }
    private val firstHash = "a".repeat(64)
    private val changedHash = "b".repeat(64)

    @Test fun backgroundRetainsPublicBytesButInvalidatesIdentityAndLateRequests() {
        val flow = visible()
        val ticket = assertNotNull(flow.refresh())
        assertTrue(flow.metadata(ticket, firstHash))
        assertTrue(flow.image(ticket, byteArrayOf(1, 2, 3)))
        assertTrue(flow.entitlement(ticket, "alice", 100L, true, true, 10L))
        flow.foreground(false)
        assertContentEquals(byteArrayOf(1, 2, 3), flow.view().image)
        assertNull(flow.view().username)
        assertNull(flow.view().localUsername)
        assertNull(flow.view().expiresMs)
        assertFalse(flow.view().entitlementLoaded)
        assertFalse(flow.image(ticket, byteArrayOf(9)))
        assertFalse(flow.metadata(ticket, changedHash))
        assertFalse(flow.entitlement(ticket, "alice", 999L, true, true, 11L))
        assertFalse(flow.copyUsername(flow.view().generation) { error("Background copy") })
        flow.foreground(true)
        val resumed = assertNotNull(flow.refresh())
        assertTrue(flow.hasImage(resumed))
        assertTrue(flow.view().cachedExpiry)
        assertFalse(flow.view().entitlementLoaded)
        assertTrue(flow.metadata(resumed, firstHash))
        assertTrue(flow.hasImage(resumed))
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
