package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.util.Objects;

/**
 * Reuses one fresh authorization only inside one serialized foreground operation.
 * This is not an account cache: every use still checks the server-derived deadline.
 */
final class OfficialOperationLease implements AutoCloseable {
    interface Action { void run() throws Exception; }

    static final class Binding {
        final Object account;
        final long credentialsGeneration;
        final String endpoint, username, profile, selection, namespace;

        Binding(Object account, long credentialsGeneration, String endpoint, String username,
                String profile, String selection, String namespace) {
            this.account = account;
            this.credentialsGeneration = credentialsGeneration;
            this.endpoint = endpoint;
            this.username = username;
            this.profile = profile;
            this.selection = selection;
            this.namespace = namespace;
        }

        boolean matches(Binding other) {
            return other != null && account == other.account
                    && credentialsGeneration == other.credentialsGeneration
                    && Objects.equals(endpoint, other.endpoint) && Objects.equals(username, other.username)
                    && Objects.equals(profile, other.profile) && Objects.equals(selection, other.selection)
                    && Objects.equals(namespace, other.namespace);
        }
    }

    private Binding binding;
    private Object authorizedFacade;
    private Runnable revoke;
    private boolean closed;

    void authorize(Binding current, Object facade, String changedMessage,
                   Action refresh, Action check, Action requireCurrent, Runnable clear) throws Exception {
        try {
            if (closed || binding != null && !binding.matches(current)) throw new IOException(changedMessage);
            if (binding == null) binding = current;
            requireCurrent.run();
            if (authorizedFacade != facade) {
                invalidate();
                // Keep the revoker even when refresh fails after partially authorizing a facade.
                revoke = clear;
                refresh.run();
                requireCurrent.run();
                authorizedFacade = facade;
            }
            check.run();
            requireCurrent.run();
        } catch (Exception | Error failure) {
            invalidate();
            throw failure;
        }
    }

    /** Local maintenance must never inherit permission to issue remote requests. */
    void invalidate() {
        Runnable previous = revoke;
        revoke = null;
        authorizedFacade = null;
        if (previous != null) previous.run();
    }

    @Override public void close() {
        closed = true;
        invalidate();
    }
}
