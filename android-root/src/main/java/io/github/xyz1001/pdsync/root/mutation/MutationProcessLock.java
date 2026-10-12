package io.github.xyz1001.pdsync.root.mutation;

import java.util.concurrent.ConcurrentHashMap;

/** Avoid opening/closing a second lock FD in this JVM, which can release process-scoped POSIX locks. */
final class MutationProcessLock {
    private static final ConcurrentHashMap<String, Lease> HELD = new ConcurrentHashMap<>();
    static final class Lease implements AutoCloseable {
        private final String key;
        private Lease(String key) { this.key = key; }
        @Override public void close() { HELD.remove(key, this); }
    }
    static Lease acquire(long device, long inode) throws MutationOs.Failure {
        String key = device + ":" + inode;
        Lease lease = new Lease(key);
        if (HELD.putIfAbsent(key, lease) != null) throw new MutationOs.Failure(MutationOs.Error.BUSY);
        return lease;
    }
    private MutationProcessLock() { }
}
