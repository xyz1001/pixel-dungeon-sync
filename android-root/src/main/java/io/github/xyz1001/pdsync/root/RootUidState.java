package io.github.xyz1001.pdsync.root;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parser for known AOSP numeric-and-symbolic process-state schema pairs. */
public final class RootUidState {
    public static final int MAX_OUTPUT = 4096;
    public static final long TIMEOUT_MILLIS = 3000;
    private static final Pattern STATE = Pattern.compile("([0-9]{1,2}) \\(([A-Z_]+)\\)\\r?\\n?");
    private static final String[] API26 = {
        "PERSISTENT", "PERSISTENT_UI", "TOP", "BOUND_FOREGROUND_SERVICE", "FOREGROUND_SERVICE",
        "TOP_SLEEPING", "IMPORTANT_FOREGROUND", "IMPORTANT_BACKGROUND", "TRANSIENT_BACKGROUND",
        "BACKUP", "HEAVY_WEIGHT", "SERVICE", "RECEIVER", "HOME", "LAST_ACTIVITY", "CACHED_ACTIVITY",
        "CACHED_ACTIVITY_CLIENT", "CACHED_EMPTY", "NONEXISTENT"
    };
    private static final String[] API28 = {
        "PERSISTENT", "PERSISTENT_UI", "TOP", "FOREGROUND_SERVICE", "BOUND_FOREGROUND_SERVICE",
        "IMPORTANT_FOREGROUND", "IMPORTANT_BACKGROUND", "TRANSIENT_BACKGROUND", "BACKUP", "SERVICE",
        "RECEIVER", "TOP_SLEEPING", "HEAVY_WEIGHT", "HOME", "LAST_ACTIVITY", "CACHED_ACTIVITY",
        "CACHED_ACTIVITY_CLIENT", "CACHED_RECENT", "CACHED_EMPTY", "NONEXISTENT"
    };
    private static final String[] API29 = {
        "PERSISTENT", "PERSISTENT_UI", "TOP", "FOREGROUND_SERVICE_LOCATION", "BOUND_TOP", "FOREGROUND_SERVICE",
        "BOUND_FOREGROUND_SERVICE", "IMPORTANT_FOREGROUND", "IMPORTANT_BACKGROUND", "TRANSIENT_BACKGROUND",
        "BACKUP", "SERVICE", "RECEIVER", "TOP_SLEEPING", "HEAVY_WEIGHT", "HOME", "LAST_ACTIVITY",
        "CACHED_ACTIVITY", "CACHED_ACTIVITY_CLIENT", "CACHED_RECENT", "CACHED_EMPTY", "NONEXISTENT"
    };
    private static final String[] API36 = {
        "PERSISTENT", "PERSISTENT_UI", "TOP", "BOUND_TOP", "FOREGROUND_SERVICE", "BOUND_FOREGROUND_SERVICE",
        "IMPORTANT_FOREGROUND", "IMPORTANT_BACKGROUND", "TRANSIENT_BACKGROUND", "BACKUP", "SERVICE",
        "RECEIVER", "TOP_SLEEPING", "HEAVY_WEIGHT", "HOME", "LAST_ACTIVITY", "CACHED_ACTIVITY",
        "CACHED_ACTIVITY_CLIENT", "CACHED_RECENT", "CACHED_EMPTY", "NONEXISTENT"
    };
    public static final class Observation {
        public final String state;
        public final boolean reliable;
        private Observation(String state, boolean reliable) { this.state = state; this.reliable = reliable; }
        public boolean foreground() { return reliable && state.equals("FOREGROUND"); }
        public boolean processPresent() { return reliable && !state.equals("ABSENT"); }
        public boolean quiescent() { return reliable && (state.equals("CACHED") || state.equals("ABSENT")); }
        public static Observation unknown() { return new Observation("UNKNOWN", false); }
    }
    private RootUidState() { }
    public static String[] command(int boundUid) {
        if (boundUid < 10000 || boundUid % 100000 < 10000 || boundUid % 100000 > 19999)
            throw new IllegalArgumentException("Invalid bound application UID");
        return new String[] {"/system/bin/cmd", "activity", "get-uid-state", Integer.toString(boundUid)};
    }
    public static Observation parse(int api, int exitCode, boolean timedOut, byte[] output) {
        if (timedOut || exitCode != 0 || output == null || output.length == 0 || output.length > MAX_OUTPUT)
            return Observation.unknown();
        if (api < 26) return Observation.unknown();
        // Schema recognition is independent of the Android API number. OEMs may backport
        // enum layouts; only an exact known integer/name pair supplies semantic evidence.
        for (byte value : output) if (value < 0 || value == 0) return Observation.unknown();
        Matcher parsed = STATE.matcher(new String(output, StandardCharsets.US_ASCII));
        if (!parsed.matches()) return Observation.unknown();
        int value = Integer.parseInt(parsed.group(1));
        String name = parsed.group(2);
        boolean known = false;
        for (String[] schema : new String[][] { API26, API28, API29, API36 })
            if (value < schema.length && schema[value].equals(name)) { known = true; break; }
        if (!known) return Observation.unknown();
        String state = name.equals("NONEXISTENT") ? "ABSENT" : name.startsWith("CACHED_") ? "CACHED"
                : name.equals("TOP") || name.equals("TOP_SLEEPING") || name.equals("PERSISTENT_UI")
                    || name.equals("BOUND_TOP") || name.equals("IMPORTANT_FOREGROUND") ? "FOREGROUND" : "BACKGROUND";
        return new Observation(state, true);
    }
}
