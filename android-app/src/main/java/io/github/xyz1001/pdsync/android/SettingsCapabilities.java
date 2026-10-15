package io.github.xyz1001.pdsync.android;

/** Already observed platform capabilities. This projection does no probing or permission work. */
final class SettingsCapabilities {
    final boolean canChangeGame;
    final boolean canToggleAutomatic;
    final boolean canRecoverRestore;
    final boolean canDiscardRestore;
    final boolean canRecoverBackup;
    final boolean canArchiveUpload;
    final boolean canForgetConnection;

    SettingsCapabilities(boolean selectedGame, boolean automaticObservation, boolean restoreWrite,
                         boolean backupRecovery, boolean restorePending, boolean uploadPending,
                         boolean connected, boolean savedSettings, boolean systemRequest) {
        canChangeGame = !restorePending && !systemRequest;
        canToggleAutomatic = selectedGame && automaticObservation && !systemRequest;
        canRecoverRestore = selectedGame && connected && restorePending && restoreWrite && !systemRequest;
        canDiscardRestore = restorePending && connected && !systemRequest;
        canRecoverBackup = selectedGame && backupRecovery && !systemRequest;
        canArchiveUpload = connected && uploadPending && !systemRequest;
        canForgetConnection = savedSettings && !systemRequest;
    }

    boolean hasMaintenance() {
        return canRecoverRestore || canDiscardRestore || canRecoverBackup || canArchiveUpload || canForgetConnection;
    }
}
