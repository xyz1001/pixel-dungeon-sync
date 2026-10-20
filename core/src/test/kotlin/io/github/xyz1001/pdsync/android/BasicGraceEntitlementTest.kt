package io.github.xyz1001.pdsync.android

import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

/** Pure-client correction of the unchanged code79 Basic entitlement response; no network. */
class BasicGraceEntitlementTest {
    private fun ms(value: String) = Instant.parse(value).toEpochMilli()
    private fun previousYear(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC)
        .minusYears(1).toInstant().toEpochMilli()
    private fun payload(expires: Long?, server: Long, active: Boolean, extra: String = "") =
        """{"code":200,"data":{"source":"managed_ledger","username":"alice","expires_ms":${expires ?: "null"},"server_time_ms":$server,"active":$active,"managed":true$extra}}"""
    private fun read(json: String): ManagedAccountClient.Entitlement {
        var calls = 0
        val client = ManagedAccountClient(ManagedAccountClient.DAV_URL, "alice", "secret",
            ManagedAccountClient.Transport { req ->
                calls++
                assertEquals(ManagedAccountClient.ENTITLEMENT_URL, req.url.toString())
                assertEquals("GET", req.method)
                assertEquals(Credentials.basic("alice", "secret", Charsets.UTF_8), req.header("Authorization"))
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(json.toResponseBody("application/json".toMediaType())).build()
            })
        return client.entitlement().also { assertEquals(1, calls) }
    }
    private fun invalid(json: String) {
        val failure = assertFailsWith<ManagedAccountClient.ManagedAccountException> { read(json) }
        assertEquals(ManagedAccountClient.FailureKind.INVALID_RESPONSE, failure.kind)
    }
    private fun denied(result: ManagedAccountClient.Entitlement, kind: ManagedAccountClient.FailureKind) {
        assertEquals(kind, assertFailsWith<ManagedAccountClient.ManagedAccountException> {
            result.requireSyncAccess()
        }.kind)
    }

    @Test fun realServerSampleBecomesPreviousUtcCalendarYearWithoutAnyMarker() {
        val raw = ms("2026-10-21T22:54:21.530Z")
        val service = ms("2025-10-21T22:54:21.530Z")
        val result = read(payload(raw, ms("2026-10-20T00:00:00Z"), true))
        assertEquals(raw, result.serverExpiresMs)
        assertEquals(service, result.expiresMs)
        assertFalse(result.active)
        repeat(2) { denied(result, ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED) }
        assertEquals(service, result.expiresMs) // Callers never subtract a second time.
    }

    @Test fun leapDayClampsToFebruary28AndKeepsTimeAndMilliseconds() {
        val raw = ms("2024-02-29T12:34:56.789Z")
        val service = ms("2023-02-28T12:34:56.789Z")
        val result = read(payload(raw, service - 1, true))
        assertEquals(service, result.expiresMs)
        result.requireSyncAccess()
        denied(read(payload(raw, service, true)), ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED)
    }

    @Test fun correctionUsesCalendarYearRatherThanFixed365DaysOrLocalTimezone() {
        val raw = ms("2024-03-01T00:00:00.530Z")
        val service = ms("2023-03-01T00:00:00.530Z")
        val originalZone = TimeZone.getDefault()
        try {
            for (zone in listOf("Pacific/Kiritimati", "America/Los_Angeles", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(service, read(payload(raw, service - 1, true)).expiresMs)
            }
        } finally {
            TimeZone.setDefault(originalZone)
        }
        assertEquals(366L * 24 * 60 * 60 * 1000, raw - service)
    }

    @Test fun oldServerActiveFlagDoesNotOverrideAdjustedEligibility() {
        val raw = ms("2027-01-02T00:00:00Z")
        val service = ms("2026-01-02T00:00:00Z")
        val eligible = read(payload(raw, service - 1, false))
        assertTrue(eligible.active)
        eligible.requireSyncAccess()
        val expired = read(payload(raw, service, true))
        assertFalse(expired.active)
        denied(expired, ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED)
    }

    @Test fun explicitZeroAndConfirmedLegacyNullStayUnlimited() {
        for ((raw, active) in listOf(0L to true, 0L to false, null to true)) {
            val result = read(payload(raw, Long.MAX_VALUE, active))
            assertEquals(raw, result.serverExpiresMs)
            assertNull(result.expiresMs)
            assertTrue(result.hasConfirmedExpiry)
            assertTrue(result.active)
            result.requireSyncAccess()
        }
    }

    @Test fun legacyNullWithFalseActiveRemainsUnknownRatherThanUnlimited() {
        val result = read(payload(null, 1000, false))
        assertNull(result.expiresMs)
        assertFalse(result.hasConfirmedExpiry)
        assertFalse(result.active)
        denied(result, ManagedAccountClient.FailureKind.INVALID_RESPONSE)
        denied(read(payload(null, 1000, false, """, "status":1""")), ManagedAccountClient.FailureKind.INVALID_RESPONSE)
        denied(read(payload(null, 1000, false, """, "status":0""")), ManagedAccountClient.FailureKind.ACCOUNT_INACTIVE)
    }

    @Test fun optionalStatusDistinguishesExplicitDisabledAccountFromExpiry() {
        val raw = ms("2027-01-02T00:00:00Z")
        val service = previousYear(raw)
        for (now in listOf(service - 1, service, service + 1)) {
            val result = read(payload(raw, now, true, """, "status":0"""))
            assertFalse(result.active)
            denied(result, ManagedAccountClient.FailureKind.ACCOUNT_INACTIVE)
        }
        denied(read(payload(null, 1000, true, """, "status":0""")), ManagedAccountClient.FailureKind.ACCOUNT_INACTIVE)
        read(payload(raw, service - 1, false, """, "status":1""")).requireSyncAccess()
    }

    @Test fun optionalStatusMustBeStrictZeroOrOne() {
        for (status in listOf("null", "-1", "2", "1.0", "1e0", "\"1\"", "true", "9223372036854775808")) {
            invalid(payload(2000, 1000, true, """, "status":$status"""))
        }
    }

    @Test fun unrelatedExtensionFieldsCannotChangeTheSingleExpiryInput() {
        val raw = ms("2027-01-02T00:00:00Z")
        val now = previousYear(raw) - 1
        // Old experimental extension fields have no authority and are no longer required.
        for (extra in listOf("", """, "sftpgo_expires_ms":2000""", """, "expiry_grace":null""",
            """, "expiry_grace":{"service_expires_ms":999999,"transport_expires_ms":999999}""")) {
            val result = read(payload(raw, now, true, extra))
            assertEquals(previousYear(raw), result.expiresMs)
            result.requireSyncAccess()
        }
    }

    @Test fun metadataWithoutServerClockCannotAuthorizeSync() {
        for (raw in listOf(ms("2027-01-02T00:00:00Z"), 0L, null)) {
            val result = read(payload(raw, 1000, true).replace("\"server_time_ms\":1000,", ""))
            assertNull(result.serverTimeMs)
            assertFalse(result.active)
            denied(result, ManagedAccountClient.FailureKind.INVALID_RESPONSE)
        }
    }

    @Test fun longBoundsAndEarlyEpochRemainSafeAfterNormalization() {
        val service = previousYear(Long.MAX_VALUE)
        val result = read(payload(Long.MAX_VALUE, service - 1, true))
        assertEquals(Long.MAX_VALUE, result.serverExpiresMs)
        assertEquals(service, result.expiresMs)
        result.requireSyncAccess()
        assertEquals(1L, result.expiresMs!! - result.serverTimeMs!!)
        denied(read(payload(Long.MAX_VALUE, Long.MAX_VALUE, true)), ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED)
        val early = read(payload(1, 0, true))
        assertEquals(previousYear(1), early.expiresMs)
        assertTrue(early.expiresMs!! < 0)
        denied(early, ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED)
        for (value in listOf("-1", "1.0", "\"1\"", "true", "9223372036854775808")) {
            invalid(payload(1000, 0, true).replace("\"expires_ms\":1000", "\"expires_ms\":$value"))
            invalid(payload(1000, 0, true).replace("\"server_time_ms\":0", "\"server_time_ms\":$value"))
        }
    }
}
