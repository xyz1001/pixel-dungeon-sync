package io.github.xyz1001.pdsync.android;

/** Executes the production ownership/capability projection; not an Android lifecycle or I/O test. */
public final class SettingsHostChecks {
    private static int checks;
    private static final Object ACCOUNT = new EqualObject();
    private static final Object FACADE = new EqualObject();
    private static final Object INSPECTION = new EqualObject();

    private static final class EqualObject {
        @Override public boolean equals(Object other) { return other instanceof EqualObject; }
        @Override public int hashCode() { return 1; }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static SettingsActionContext context(int render, Object account, Object facade, Object inspection, String backend) {
        return new SettingsActionContext(render, account, facade, inspection, backend);
    }

    public static void main(String[] args) {
        SettingsActionContext owner = context(4, ACCOUNT, FACADE, INSPECTION, "root:game\nspace");
        SettingsActionContext same = context(4, ACCOUNT, FACADE, INSPECTION, new String("root:game\nspace"));
        check(owner.accepts(same, true, false), "Current foreground settings accepts real capabilities");
        check(!owner.accepts(same, false, false), "Different page, appreciation, pause or destruction rejects callbacks");
        check(!owner.accepts(same, true, true), "Busy state rejects repeat actions");
        check(!owner.accepts(null, true, false), "Missing context fails closed");
        check(!owner.accepts(context(5, ACCOUNT, FACADE, INSPECTION, "root:game\nspace"), true, false),
                "Re-render and leave-return invalidate old settings rows and dialogs");
        check(owner.sameTarget(context(5, ACCOUNT, FACADE, INSPECTION, "root:game\nspace")),
                "Existing confirmation may change render/page while retaining its exact target");
        for (SettingsActionContext changed : new SettingsActionContext[] {
                context(4, new EqualObject(), FACADE, INSPECTION, "root:game\nspace"),
                context(4, ACCOUNT, new EqualObject(), INSPECTION, "root:game\nspace"),
                context(4, ACCOUNT, FACADE, new EqualObject(), "root:game\nspace"),
                context(4, ACCOUNT, FACADE, INSPECTION, "embedded:game\nspace"),
                context(4, ACCOUNT, FACADE, INSPECTION, "root:other-game\nspace"),
                context(4, ACCOUNT, FACADE, INSPECTION, "root:game\nother-space") }) {
            check(!owner.accepts(changed, true, false), "Account, facade, observation, backend, game or namespace replacement rejects action");
            check(!owner.sameTarget(changed), "Settings confirmation cannot inherit replacement identity");
        }
        SettingsActionContext absent = context(1, null, null, null, "none\n");
        check(absent.accepts(context(1, null, null, null, "none\n"), true, false), "Unconfigured settings retains setup and log access");
        check(!context(1, null, null, null, null).sameTarget(absent), "Unavailable backend identity never authorizes a callback");

        SettingsCapabilities normal = new SettingsCapabilities(true, true, true, true, false, false, true, true, false);
        check(normal.canChangeGame && normal.canToggleAutomatic, "Selected supported backend keeps change/automatic actions");
        check(normal.canRecoverBackup && normal.canForgetConnection && normal.hasMaintenance(), "Real backup and saved connection remain reachable");
        check(!normal.canRecoverRestore && !normal.canDiscardRestore && !normal.canArchiveUpload, "No pending work means no invented recovery/archival actions");
        SettingsCapabilities pending = new SettingsCapabilities(true, true, true, true, true, true, true, true, false);
        check(!pending.canChangeGame, "Pending restore cannot change game/backend/namespace");
        check(pending.canRecoverRestore && pending.canDiscardRestore && pending.canArchiveUpload, "Actual pending recovery and upload remain reachable");
        SettingsCapabilities readOnly = new SettingsCapabilities(true, false, false, false, true, false, true, true, false);
        check(!readOnly.canToggleAutomatic && !readOnly.canRecoverRestore && !readOnly.canRecoverBackup, "Unsupported observation/writes/backup are not advertised");
        check(readOnly.canDiscardRestore && readOnly.canForgetConnection, "Read-only backend still permits existing host metadata actions");
        SettingsCapabilities empty = new SettingsCapabilities(false, true, true, true, false, false, false, false, false);
        check(empty.canChangeGame && !empty.canToggleAutomatic && !empty.canRecoverBackup && !empty.hasMaintenance(), "No game does not inherit embedded default capabilities");
        SettingsCapabilities brokenConfig = new SettingsCapabilities(false, false, false, false, true, true, false, true, false);
        check(brokenConfig.canForgetConnection && brokenConfig.hasMaintenance(), "Unreadable saved settings can still be forgotten through original confirmation");
        check(!brokenConfig.canRecoverRestore && !brokenConfig.canDiscardRestore && !brokenConfig.canArchiveUpload, "Missing usable connection cannot promise facade operations");
        SettingsCapabilities installing = new SettingsCapabilities(true, true, true, true, true, true, true, true, true);
        check(!installing.canChangeGame && !installing.canToggleAutomatic && !installing.hasMaintenance(), "Outstanding system request disables host mutation entry points");
        System.out.println("SettingsHostChecks: " + checks + " checks passed");
    }
}
