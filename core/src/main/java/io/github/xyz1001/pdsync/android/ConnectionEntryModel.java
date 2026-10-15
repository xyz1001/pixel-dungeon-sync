package io.github.xyz1001.pdsync.android;

import java.util.Objects;

/**
 * Pure UI navigation for connection entry. The caller owns drafts, saved connections,
 * authentication and proof invalidation; this model never reads or writes any of them.
 * All calls belong on the UI thread. Busy is supplied by the caller, not persisted here.
 */
public final class ConnectionEntryModel {
    public enum Origin { SETUP, SETTINGS }
    public enum Mode { CHOICE, CUSTOM, OFFICIAL }
    public enum Back { STAY, CHOICE, PATH, SETTINGS }

    private final Origin origin;
    private Mode mode;
    private Mode selectedService;

    /**
     * First-time setup starts at CHOICE. Settings starts at the explicitly supplied
     * service, or CUSTOM for an existing connection without provider metadata.
     * No endpoint inference is performed. existingMode must be null for new setup.
     */
    public ConnectionEntryModel(Origin origin, Mode existingMode) {
        this.origin = Objects.requireNonNull(origin, "origin");
        if (origin == Origin.SETUP) {
            if (existingMode != null) throw new IllegalArgumentException("New setup has no existing service");
            this.mode = Mode.CHOICE;
        } else {
            this.selectedService = requireService(existingMode == null ? Mode.CUSTOM : existingMode);
            this.mode = this.selectedService;
        }
    }

    public Origin origin() { return origin; }
    public Mode mode() { return mode; }

    /** Last selected service, retained while showing CHOICE; null before first selection. */
    public Mode selectedService() { return selectedService; }

    /**
     * Selects a form unless busy. Returns whether the visible mode changed, so the
     * caller can invalidate source-bound proof whenever a different entry is opened.
     * CHOICE to the retained service is a change; a repeated same-form choice is not.
     */
    public boolean choose(Mode service, boolean busy) {
        requireService(service);
        if (busy) return false;
        boolean changed = mode != service;
        selectedService = service;
        mode = service;
        return changed;
    }

    /** Shows the service chooser without changing the selection or any external draft. */
    public boolean showChooser(boolean busy) {
        if (busy || mode == Mode.CHOICE) return false;
        mode = Mode.CHOICE;
        return true;
    }

    /** External destinations are returned for the caller; only CHOICE changes this model. */
    public Back back(boolean busy) {
        if (busy) return Back.STAY;
        if (origin == Origin.SETTINGS) return Back.SETTINGS;
        if (mode == Mode.CHOICE) return Back.PATH;
        mode = Mode.CHOICE;
        return Back.CHOICE;
    }

    /**
     * Restores only non-secret UI state from the three getter values. Android callers
     * can store enum names in a Bundle; credentials, auth proof and busy state stay out.
     * Invalid combinations fail rather than inventing or inferring a provider.
     */
    public static ConnectionEntryModel restore(Origin origin, Mode mode, Mode selectedService) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(mode, "mode");
        if (selectedService != null) requireService(selectedService);
        if (origin == Origin.SETTINGS && selectedService == null) {
            throw new IllegalArgumentException("Settings must retain its selected service");
        }
        if (mode != Mode.CHOICE && mode != selectedService) {
            throw new IllegalArgumentException("Visible form must match the selected service");
        }
        ConnectionEntryModel restored = new ConnectionEntryModel(origin,
                origin == Origin.SETTINGS ? selectedService : null);
        restored.mode = mode;
        restored.selectedService = selectedService;
        return restored;
    }

    private static Mode requireService(Mode service) {
        Objects.requireNonNull(service, "service");
        if (service == Mode.CHOICE) throw new IllegalArgumentException("Choose CUSTOM or OFFICIAL");
        return service;
    }
}
