package io.github.xyz1001.pdsync.android;

/** Process-local export state. No document URI, credential or snapshot is saved to a Bundle. */
final class DiagnosticExportState {
    enum Phase { IDLE, SELECTING, WRITING }
    private Phase phase = Phase.IDLE;
    private long generation;

    synchronized long request() {
        if (phase != Phase.IDLE) return 0;
        phase = Phase.SELECTING;
        return ++generation;
    }
    synchronized boolean selecting() { return phase == Phase.SELECTING; }
    synchronized boolean busy() { return phase != Phase.IDLE; }
    synchronized long selected() {
        if (phase != Phase.SELECTING) return 0;
        phase = Phase.WRITING;
        return generation;
    }
    synchronized boolean cancelSelection() {
        if (phase != Phase.SELECTING) return false;
        phase = Phase.IDLE;
        return true;
    }
    synchronized boolean finish(long ticket) {
        if (phase != Phase.WRITING || ticket != generation) return false;
        phase = Phase.IDLE;
        return true;
    }
    /** Only a pending system picker can survive process recreation; never repeat a write. */
    synchronized void restorePicker(boolean pendingPicker) {
        if (pendingPicker && phase == Phase.IDLE && generation == 0) request();
    }
}
