package io.github.xyz1001.pdsync.android;

/** Successful directory preparation only. Never caches listings, account access, or save proofs. */
final class OfficialNamespacePreparation {
    private OfficialOperationLease.Binding prepared;

    synchronized boolean contains(OfficialOperationLease.Binding current) {
        if (prepared != null && !prepared.matches(current)) prepared = null;
        return prepared != null;
    }

    synchronized void succeeded(OfficialOperationLease.Binding current) { prepared = current; }

    /** Failed observations must not keep an assumption that the configured collections exist. */
    synchronized void clear() { prepared = null; }
}
