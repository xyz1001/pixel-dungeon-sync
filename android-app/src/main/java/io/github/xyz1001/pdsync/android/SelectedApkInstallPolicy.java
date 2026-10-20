package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.IOException;

/** A document selection requires its own system installation, even for identical APK bytes. */
final class SelectedApkInstallPolicy {
    static boolean pending(String requiredReceipt) {
        return requiredReceipt != null && !requiredReceipt.isEmpty();
    }

    static boolean mayReuseInstalledSelection(String requiredReceipt, String currentReceipt,
            String completedReceipt, String replacedReceipt) {
        return !pending(requiredReceipt) && (!pending(currentReceipt)
                || currentReceipt.equals(completedReceipt) || currentReceipt.equals(replacedReceipt));
    }

    static boolean requiresInstallation(String requiredReceipt, String currentReceipt) throws IOException {
        if (!pending(requiredReceipt)) return false;
        if (!requiredReceipt.equals(currentReceipt))
            throw new IOException(AndroidMessages.SELECTED_APK_INSTALL_POLICY_01.text());
        return true;
    }

    static boolean recoverIntent(String requiredReceipt, boolean selectedFile, String currentReceipt,
            String completedReceipt, String replacedReceipt) {
        return !pending(requiredReceipt) && selectedFile && pending(currentReceipt)
                && !currentReceipt.equals(completedReceipt) && !currentReceipt.equals(replacedReceipt);
    }

    /** Component presence is consulted only after this system installation reaches a return boundary. */
    static boolean mayCheckInstalledComponent(String operation, String requiredReceipt, String requestReceipt,
            String state, boolean activityReturned, boolean sessionPresent) {
        if (!"install".equals(operation) || !pending(requestReceipt)) return false;
        if (!"INSTALLING".equals(state) && !"PENDING_CONFIRMATION".equals(state) && !"RESULT".equals(state)) return false;
        if (pending(requiredReceipt) && !requiredReceipt.equals(requestReceipt)) return false;
        return "RESULT".equals(state) || activityReturned || !sessionPresent;
    }

    static boolean acceptsInstalledComponent(boolean resultBoundary, boolean componentPresent) {
        return resultBoundary && componentPresent;
    }

    private SelectedApkInstallPolicy() { }
}
