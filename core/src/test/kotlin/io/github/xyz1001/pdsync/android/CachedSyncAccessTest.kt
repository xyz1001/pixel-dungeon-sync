package io.github.xyz1001.pdsync.android

import kotlin.test.*

class CachedSyncAccessTest {
 private val endpoint=TrustedEntitlementCache.OFFICIAL_ENDPOINT
 @Test fun cachedLeaseKeepsOriginalDeadlineWithoutNetwork() {
  var now=20_000_000L
  val clock=OfficialSyncAccess.Clock {now}
  val record=TrustedEntitlementCache.Record(endpoint,"cached_user",1100, true,1000,10,7)
  val lease=OfficialSyncAccess.fromCached(record,endpoint,"cached_user",7,clock)
  lease.check();assertEquals(1100,lease.expiresMs)
  now=110_000_000L
  assertTrue(assertFailsWith<OfficialSyncAccess.AccessException>{lease.check()}.isExpired)
 }
 @Test fun wrongAccountRebootExpiredOrFailedRefreshCannotUseCache() {
  val record=TrustedEntitlementCache.Record(endpoint,"cache_cases",1100,true,1000,10,7)
  val clock=OfficialSyncAccess.Clock {20_000_000L}
  assertFails {OfficialSyncAccess.fromCached(record,endpoint,"other",7,clock)}
  assertFails {OfficialSyncAccess.fromCached(record,endpoint,"cache_cases",8,clock)}
  assertFails {OfficialSyncAccess.fromCached(record,endpoint,"cache_cases",7,OfficialSyncAccess.Clock {110_000_000L})}
  TrustedEntitlementCache.beginRefresh(endpoint,"cache_cases")
  assertFails {OfficialSyncAccess.fromCached(record,endpoint,"cache_cases",7,clock)}
  TrustedEntitlementCache.confirmedRefresh(endpoint,"cache_cases")
  OfficialSyncAccess.fromCached(record,endpoint,"cache_cases",7,clock).check()
 }
 @Test fun missingUnknownDisabledAndClockRollbackCannotAuthorize() {
  val clock=OfficialSyncAccess.Clock {20_000_000L}
  assertFails {OfficialSyncAccess.fromCached(null,endpoint,"cache_invalid",7,clock)}
  val disabled=TrustedEntitlementCache.Record(endpoint,"cache_invalid",null,false,1000,10,7,false,true)
  assertFails {OfficialSyncAccess.fromCached(disabled,endpoint,"cache_invalid",7,clock)}
  val unknown=TrustedEntitlementCache.Record(endpoint,"cache_invalid",null,true,null,10,7)
  assertFails {OfficialSyncAccess.fromCached(unknown,endpoint,"cache_invalid",7,clock)}
  val record=TrustedEntitlementCache.Record(endpoint,"cache_invalid",null,true,1000,30,7)
  assertFails {OfficialSyncAccess.fromCached(record,endpoint,"cache_invalid",7,clock)}
 }
}
