package io.github.xyz1001.pdsync.android

import java.time.Instant
import java.time.ZoneOffset
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import pdsync.AppendOnlyWebDav
import kotlin.test.*

class OfficialSyncAccessTest {
    private class Fixture {
        var now = 0L
        var server = 1000L
        var expires = 1100L
        var status = 1
        var delay = 0L
        var includeStatus = true
        var reportedActive: Boolean? = null
        var includeServerTime = true
        val requests = mutableListOf<Request>()
        var wallOffset = 0L
        val clock = object: OfficialSyncAccess.Clock {
            override fun nanoTime() = now
            override fun wallTimeMillis() = now / 1_000_000 + wallOffset
        }
        val client = ManagedAccountClient(ManagedAccountClient.DAV_URL, "alice", "secret",
            ManagedAccountClient.Transport { request ->
                requests += request
                now += delay
                val extra = if (includeStatus) "\"status\":$status," else ""
                val time = if (includeServerTime) "\"server_time_ms\":$server," else ""
                val raw = if (expires == 0L) null else Instant.ofEpochMilli(expires)
                    .atZone(ZoneOffset.UTC).plusYears(1).toInstant().toEpochMilli()
                val active = reportedActive ?: (status == 1 && (raw == null || server < raw))
                val content = """{"code":200,"data":{"source":"managed_ledger","username":"alice",$extra$time"expires_ms":${raw ?: "null"},"active":$active,"managed":true}}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(content.toResponseBody("application/json".toMediaType())).build()
            })
        fun begin() = OfficialSyncAccess.beginOperation(client, clock)
    }

    @Test fun originalLedgerDeadlineIsAdjustedOnceAndMissingServerTimeCannotAuthorize() {
        val f=Fixture();f.includeStatus=false
        assertEquals(1100L,f.client.entitlement().expiresMs)
        val lease = f.begin()
        assertEquals(1100L, lease.expiresMs)
        lease.check()
        f.includeServerTime=false
        assertNull(f.client.entitlement().serverTimeMs) // Display remains compatible.
        assertFalse(assertFailsWith<OfficialSyncAccess.AccessException>{f.begin()}.isExpired)
        f.includeStatus=true
        assertNull(f.client.entitlement().serverTimeMs)
        assertFalse(assertFailsWith<OfficialSyncAccess.AccessException>{f.begin()}.isExpired)
    }
    @Test fun serverActiveCannotExtendOrShortenTheCorrectedDeadline() {
        val f = Fixture()
        f.reportedActive = false
        f.begin().check()
        f.reportedActive = true
        f.server = f.expires
        assertTrue(assertFailsWith<OfficialSyncAccess.AccessException> { f.begin() }.isExpired)
        f.status = 0
        assertFalse(assertFailsWith<OfficialSyncAccess.AccessException> { f.begin() }.isExpired)
    }
    @Test fun wallClockJumpFailsClosedWithoutInventingExpiryAndRefreshRecovers() {
        val f=Fixture();val lease=f.begin();f.wallOffset=3_000
        assertFalse(assertFailsWith<OfficialSyncAccess.AccessException>{lease.check()}.isExpired)
        f.begin().check()
        val backwards=f.begin();f.wallOffset=-3_000
        assertFalse(assertFailsWith<OfficialSyncAccess.AccessException>{backwards.check()}.isExpired)
    }
    @Test fun shortSuspendCrossingDeadlineDeniesWithoutCallingItExpiry() {
        val f=Fixture();val lease=f.begin();f.wallOffset=100
        assertFalse(assertFailsWith<OfficialSyncAccess.AccessException>{lease.check()}.isExpired)
    }
    @Test fun exactDeadlineStopsAndCannotBeRevived() {
        val f=Fixture(); val lease=f.begin()
        f.now=99_999_999;lease.check()
        f.now=100_000_000
        assertTrue(assertFailsWith<OfficialSyncAccess.AccessException>{lease.check()}.isExpired)
        f.now=0
        assertTrue(assertFailsWith<OfficialSyncAccess.AccessException>{lease.check()}.isExpired)
        assertEquals(1,f.requests.size) // No per-file authentication.
    }
    @Test fun eachOperationAndRestartRefreshesExistingBasicEntitlement() {
        val f=Fixture();f.begin();f.begin()
        assertEquals(2,f.requests.size)
        assertTrue(f.requests[0].header("Authorization")!!.startsWith("Basic "))
        assertTrue(f.requests.all { it.url.toString() == ManagedAccountClient.ENTITLEMENT_URL && it.header("Authorization")!!.startsWith("Basic ") })
        f.server=f.expires
        assertTrue(assertFailsWith<OfficialSyncAccess.AccessException>{f.begin()}.isExpired)
        val restarted=Fixture().apply{server=expires}
        assertTrue(assertFailsWith<OfficialSyncAccess.AccessException>{restarted.begin()}.isExpired)
    }
    @Test fun delayedResponseConsumesRemainingLifetime() {
        val f=Fixture();f.delay=100_000_000
        assertTrue(assertFailsWith<OfficialSyncAccess.AccessException>{f.begin()}.isExpired)
    }
    @Test fun unlimitedLegacyAndInactiveHaveDistinctSemantics() {
        val f=Fixture();f.expires=0; val lease=f.begin();f.now=Long.MAX_VALUE;lease.check()
        f.status=0
        assertFalse(assertFailsWith<OfficialSyncAccess.AccessException>{f.begin()}.isExpired)
    }
    @Test fun monotonicRegressionFailsClosedWithoutClaimingExpiry() {
        val f=Fixture();f.now=100;val lease=f.begin();f.now=99
        assertFalse(assertFailsWith<OfficialSyncAccess.AccessException>{lease.check()}.isExpired)
    }
    @Test fun actualOriginCannotBeBypassedWithPathOrProvider() {
        assertTrue(OfficialSyncAccess.isOfficialEndpoint("https://webdav.xyz1001.top:8443/dav/"))
        assertTrue(OfficialSyncAccess.isOfficialEndpoint("https://WEBDAV.XYZ1001.TOP:8443/"))
        assertTrue(OfficialSyncAccess.isOfficialEndpoint("https://webdav.xyz1001.top.:8443/"))
        assertTrue(OfficialSyncAccess.isOfficialEndpoint("https://%77ebdav.xyz1001.top:8443/"))
        assertFalse(OfficialSyncAccess.isOfficialEndpoint("https://example.com:8443/"))
        assertFalse(OfficialSyncAccess.isOfficialEndpoint("https://webdav.xyz1001.top:8443.evil/"))
    }
    @Test fun operationCrossingDeadlineStopsBeforeNextDavRequest() {
        val f=Fixture();val lease=f.begin();var calls=0
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            calls++;f.now=100_000_000
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404)
                .message("Not found").body("".toResponseBody()).build()
        }.build()
        val dav=AppendOnlyWebDav("https://example.com/","shattered","hero","alice","secret",client,lease)
        assertNull(dav.readCommit("ab".repeat(32)))
        assertTrue(assertFailsWith<OfficialSyncAccess.AccessException>{dav.listCommits(null)}.isExpired)
        assertEquals(1,calls)
    }
    @Test fun allDavOperationsStopBeforeTransportAfterDeadline() {
        val f=Fixture();val lease=f.begin();f.now=100_000_000
        val client=OkHttpClient.Builder().addInterceptor { error("Transport must not run") }.build()
        val dav=AppendOnlyWebDav("https://example.com/","shattered","hero","alice","secret",client,lease)
        val id="ab".repeat(32)
        val actions=listOf<()->Any?>({dav.listCommits(null)},{dav.readCommit(id)},
            {dav.readObject(id)},{dav.writeCommit(id,byteArrayOf())},{dav.writeObject(id,byteArrayOf())},{dav.ensureNamespace()})
        actions.forEach{action-> assertTrue(assertFailsWith<OfficialSyncAccess.AccessException>{action()}.isExpired)}
    }
}
