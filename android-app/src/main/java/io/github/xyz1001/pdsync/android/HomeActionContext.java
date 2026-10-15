package io.github.xyz1001.pdsync.android;

import java.util.Objects;

/**
 * Identity of one rendered home and its real observation. This guards retained UI callbacks;
 * the existing facade, operation lock and restore/entitlement checks still authorize I/O.
 * Object identity is intentional: a replacement account or inspection cannot inherit an action.
 */
final class HomeActionContext {
    private final int renderToken;
    private final Object connection;
    private final Object facade;
    private final Object inspection;
    private final Object history;
    private final String backend;

    HomeActionContext(int renderToken, Object connection, Object facade, Object inspection,
                      Object history, String backend) {
        this.renderToken = renderToken;
        this.connection = connection;
        this.facade = facade;
        this.inspection = inspection;
        this.history = history;
        this.backend = backend;
    }

    boolean accepts(HomeActionContext current, boolean pageActive, boolean busy) {
        return pageActive && !busy && current != null && backend != null
                && renderToken == current.renderToken
                && connection == current.connection && facade == current.facade
                && inspection == current.inspection && history == current.history
                && Objects.equals(backend, current.backend);
    }
}
