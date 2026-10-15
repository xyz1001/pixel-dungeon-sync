package io.github.xyz1001.pdsync.android;

import java.util.Objects;

/** Ownership of settings callbacks. Identity checks protect host actions, never authorize sync I/O. */
final class SettingsActionContext {
    private final int renderToken;
    private final Object connection;
    private final Object facade;
    private final Object inspection;
    private final String backend;

    SettingsActionContext(int renderToken, Object connection, Object facade, Object inspection, String backend) {
        this.renderToken = renderToken;
        this.connection = connection;
        this.facade = facade;
        this.inspection = inspection;
        this.backend = backend;
    }

    boolean accepts(SettingsActionContext current, boolean pageActive, boolean busy) {
        return pageActive && !busy && sameTarget(current) && renderToken == current.renderToken;
    }

    /** A confirmation changes page/render, but cannot inherit a replacement account or backend. */
    boolean sameTarget(SettingsActionContext current) {
        return current != null && backend != null && connection == current.connection
                && facade == current.facade && inspection == current.inspection
                && Objects.equals(backend, current.backend);
    }
}
