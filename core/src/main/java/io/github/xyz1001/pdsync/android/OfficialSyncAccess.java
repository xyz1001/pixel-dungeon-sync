package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import pdsync.WebDavRequestCheck;
import pdsync.i18n.CoreSessionMessages;

/** Fresh per-operation authorization. No persisted entitlement can create an access lease. */
public final class OfficialSyncAccess {
    private OfficialSyncAccess() { }

    /** Provider labels and legacy DAV paths cannot bypass the official origin's service deadline. */
    public static boolean isOfficialEndpoint(String endpoint) {
        try {
            okhttp3.HttpUrl uri = okhttp3.HttpUrl.get(endpoint);
            String host = uri.host();
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            return "https".equals(uri.scheme()) && "webdav.xyz1001.top".equals(host) && uri.port() == 8443;
        } catch (Exception invalid) { return false; }
    }

    public static Lease beginOperation(String endpoint, String username, String password) throws IOException {
        if (!isOfficialEndpoint(endpoint)) throw new IllegalArgumentException("Not the official service");
        return beginOperation(new ManagedAccountClient(ManagedAccountClient.DAV_URL, username, password), System::nanoTime);
    }

    interface Clock { long nanoTime(); default long wallTimeMillis() { return System.currentTimeMillis(); } }

    static Lease beginOperation(ManagedAccountClient client, Clock clock) throws IOException {
        // Start before the existing Basic-auth entitlement request. Counting all RTT is conservative: never
        // grant the response's remaining duration again after an arbitrarily delayed response.
        long started = clock.nanoTime();
        long wallStarted = clock.wallTimeMillis();
        ManagedAccountClient.Entitlement entitlement;
        try {
            entitlement = client.entitlement();
            entitlement.requireSyncAccess();
        } catch (ManagedAccountClient.ManagedAccountException failure) {
            throw new AccessException(failure.kind == ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED,
                    failure.userMessage());
        }
        Lease lease = new Lease(entitlement, started, clock, wallStarted, true);
        lease.check();
        return lease;
    }

    /** Only authenticated fresh metadata may enter here; record the clock before requesting it. */
    static Lease fromEntitlement(ManagedAccountClient.Entitlement entitlement, long requestStartedNanos, Clock clock)
            throws IOException {
        try { entitlement.requireSyncAccess(); }
        catch (ManagedAccountClient.ManagedAccountException failure) {
            throw new AccessException(failure.kind == ManagedAccountClient.FailureKind.ACCOUNT_EXPIRED,
                    failure.userMessage());
        }
        Lease lease = new Lease(entitlement, requestStartedNanos, clock, 0, false);
        lease.check();
        return lease;
    }

    public static final class AccessException extends IOException {
        private final boolean expired;
        private AccessException(boolean expired, String message) { super(message); this.expired = expired; }
        public boolean isExpired() { return expired; }
    }

    public static final class Lease implements WebDavRequestCheck {
        private final ManagedAccountClient.Entitlement entitlement;
        private final long started;
        private final Clock clock;
        private final long wallStarted;
        private final boolean checkWall;
        private boolean denied;
        private boolean expired;
        private long lastElapsed;
        private Lease(ManagedAccountClient.Entitlement entitlement, long started, Clock clock, long wallStarted, boolean checkWall) {
            this.entitlement = entitlement; this.started = started; this.clock = clock;
            this.wallStarted = wallStarted; this.checkWall = checkWall;
        }
        public String getUsername() { return entitlement.username; }
        public Long getExpiresMs() { return entitlement.expiresMs; }
        public Long getServerTimeMs() { return entitlement.serverTimeMs; }
        public synchronized boolean isActive() { observe(); return !denied; }
        public synchronized boolean isExpired() { observe(); return expired; }
        private void observe() {
            long elapsed = clock.nanoTime() - started;
            // nanoTime subtraction handles wraparound for practical (<292 year) operations.
            if (elapsed < 0 || elapsed < lastElapsed) { denied = true; return; }
            lastElapsed = elapsed;
            if (checkWall) {
                long wallElapsed = clock.wallTimeMillis() - wallStarted;
                long delta = wallElapsed - elapsed / 1_000_000L;
                if (delta < -2_000 || delta > 2_000) { denied = true; return; }
                // Even a short suspend can cross a near deadline. Wall time may only deny,
                // never prove service expiry; a new operation obtains fresh server evidence.
                if (entitlement.expiresMs != null && wallElapsed >= entitlement.expiresMs - entitlement.serverTimeMs
                        && elapsed / 1_000_000L < entitlement.expiresMs - entitlement.serverTimeMs) {
                    denied = true; return;
                }
            }
            if (entitlement.expiresMs != null
                    && elapsed / 1_000_000L >= entitlement.expiresMs - entitlement.serverTimeMs) {
                denied = true; expired = true;
            }
        }
        @Override public synchronized void check() throws AccessException {
            observe();
            if (denied) throw new AccessException(expired, expired ? CoreSessionMessages.SERVICE_EXPIRED.text()
                    : CoreSessionMessages.THE_SERVICE_RETURNED_AN_INVALID_RESPONSE.text());
        }
        @Override public String toString() { return "Official sync access [private fields hidden]"; }
    }
}
