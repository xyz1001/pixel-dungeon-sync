package io.github.xyz1001.pdsync.android;

import java.util.Objects;

/** Memory-only, one-shot recovery read. Account/page data never authorizes a transfer. */
final class RenewalArchiveReadState {
    private Object account;
    private String selection, namespace;
    private boolean needsRead, available, pending;
    synchronized void open(Object owner, String game, String space, boolean homeAvailable) {
        if (!matches(owner, game, space)) {
            account = owner; selection = game; namespace = space;
            needsRead = !homeAvailable; available = homeAvailable; pending = false;
        } else if (!homeAvailable) needsRead = true;
    }
    synchronized boolean matches(Object owner, String game, String space) {
        return account != null && account == owner && Objects.equals(selection, game) && Objects.equals(namespace, space);
    }
    synchronized void started() { available = false; pending = false; }
    synchronized void result(boolean active) {
        available = active;
        if (!active) { needsRead = true; pending = false; }
        else if (needsRead) pending = true;
    }
    synchronized boolean take(Object owner, String game, String space) {
        if (!matches(owner, game, space)) { clear(); return false; }
        if (!available || !pending) return false;
        pending = false; needsRead = false;
        return true;
    }
    synchronized void clear() { account = null; selection = namespace = null; needsRead = available = pending = false; }
}
