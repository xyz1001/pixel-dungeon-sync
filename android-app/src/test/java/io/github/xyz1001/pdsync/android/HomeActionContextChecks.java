package io.github.xyz1001.pdsync.android;

/** Executes the production home callback owner; no Android, I/O or invented sync proof. */
public final class HomeActionContextChecks {
    private static int checks;
    private static final Object ACCOUNT = new EqualObject();
    private static final Object FACADE = new EqualObject();
    private static final Object INSPECTION = new EqualObject();
    private static final Object HISTORY = new EqualObject();

    private static final class EqualObject {
        @Override public boolean equals(Object other) { return other instanceof EqualObject; }
        @Override public int hashCode() { return 1; }
    }

    private static HomeActionContext current() {
        return new HomeActionContext(10, ACCOUNT, FACADE, INSPECTION, HISTORY, "root:real-game\nprofile");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        HomeActionContext owner = current();
        check(owner.accepts(current(), true, false), "Unchanged observed foreground home accepts actions");
        check(!owner.accepts(current(), false, false), "Navigation, pause and destruction reject old callbacks");
        check(!owner.accepts(current(), true, true), "Synchronous busy boundary rejects repeated actions");
        check(!owner.accepts(null, true, false), "Unavailable current ownership fails closed");
        check(!owner.accepts(new HomeActionContext(11, ACCOUNT, FACADE, INSPECTION, HISTORY,
                "root:real-game\nprofile"), true, false), "Re-render and leave-return reject old views");
        check(!owner.accepts(new HomeActionContext(10, new EqualObject(), FACADE, INSPECTION, HISTORY,
                "root:real-game\nprofile"), true, false), "Equal replacement account cannot inherit actions");
        check(!owner.accepts(new HomeActionContext(10, ACCOUNT, new EqualObject(), INSPECTION, HISTORY,
                "root:real-game\nprofile"), true, false), "Equal replacement facade cannot inherit actions");
        check(!owner.accepts(new HomeActionContext(10, ACCOUNT, FACADE, new EqualObject(), HISTORY,
                "root:real-game\nprofile"), true, false), "Equal replacement inspection cannot inherit actions");
        check(!owner.accepts(new HomeActionContext(10, ACCOUNT, FACADE, INSPECTION, new EqualObject(),
                "root:real-game\nprofile"), true, false), "Replaced history invalidates callbacks before rendering");
        check(!owner.accepts(new HomeActionContext(10, ACCOUNT, FACADE, INSPECTION, HISTORY,
                "embedded:real-game\nprofile"), true, false), "Backend switch rejects retained callbacks");
        check(!owner.accepts(new HomeActionContext(10, ACCOUNT, FACADE, INSPECTION, HISTORY,
                "root:real-game\nother-profile"), true, false), "Namespace switch rejects retained callbacks");
        check(owner.accepts(new HomeActionContext(10, ACCOUNT, FACADE, INSPECTION, HISTORY,
                new String("root:real-game\nprofile")), true, false), "Backend identity is compared by value");
        HomeActionContext unconnected = new HomeActionContext(1, null, null, null, null, "root:real-game\nprofile");
        check(unconnected.accepts(new HomeActionContext(1, null, null, null, null,
                "root:real-game\nprofile"), true, false), "Unconnected home still permits settings/navigation");
        HomeActionContext missingBackend = new HomeActionContext(1, null, null, null, null, null);
        check(!missingBackend.accepts(missingBackend, true, false), "Missing backend identity never grants an action");
        System.out.println("HomeActionContextChecks: " + checks + " checks passed");
    }
}
