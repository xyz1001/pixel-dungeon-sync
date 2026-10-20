package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

/** Activity-independent ownership for one selected target and bounded external returns. */
public final class GameSetupFlow {
    public enum Pending { NONE, UNKNOWN_SOURCES, ACTIVATION, SYSTEM_CONFIRMATION }
    private long generation, serial, activeWork;
    private String target;
    private Pending pending = Pending.NONE;
    private long pendingGeneration;
    private String pendingTarget;
    private boolean activationAttempted;
    private boolean rootAuthorized;

    public synchronized long select(String packageName) {
        generation++; target = packageName; activeWork = 0; pending = Pending.NONE;
        pendingTarget = null; activationAttempted = false; rootAuthorized = false;
        return generation;
    }
    public synchronized long generation() { return generation; }
    public synchronized String target() { return target; }
    public synchronized boolean matches(long expected, String packageName) {
        return generation == expected && java.util.Objects.equals(target, packageName);
    }
    public synchronized long beginWork() { activeWork = ++serial; return activeWork; }
    public synchronized boolean finishWork(long token) {
        if (token == 0 || token != activeWork) return false;
        activeWork = 0; return true;
    }
    public synchronized boolean busy() { return activeWork != 0; }
    public synchronized void authorizeRoot(long expected, String packageName) {
        if (matches(expected, packageName)) rootAuthorized = true;
    }
    public synchronized boolean rootAuthorized() { return rootAuthorized; }
    public synchronized boolean beginActivation() {
        if (activationAttempted || pending != Pending.NONE || target == null) return false;
        activationAttempted = true; beginPending(Pending.ACTIVATION); return true;
    }
    public synchronized void beginPending(Pending kind) {
        if (kind == Pending.NONE || target == null) throw new IllegalStateException(AndroidMessages.GAME_SETUP_FLOW_01.text());
        pending = kind; pendingTarget = target; pendingGeneration = generation;
    }
    public synchronized boolean consumePending(Pending kind) {
        if (pending != kind) return false;
        boolean current = matches(pendingGeneration, pendingTarget);
        pending = Pending.NONE; pendingTarget = null;
        return current;
    }
    public synchronized Pending pending() { return pending; }
    public synchronized void cancelPending() { pending = Pending.NONE; pendingTarget = null; }
}
