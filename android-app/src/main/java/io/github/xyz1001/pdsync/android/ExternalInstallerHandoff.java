package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import io.github.xyz1001.pdsync.android.prepare.ValidatedPreparationCache;
import java.io.IOException;

/** Ordinary APK handoff only. Never transfers a PackageInstaller session or a confirmation token. */
public final class ExternalInstallerHandoff {
    public static final String MIME_TYPE = "application/vnd.android.package-archive";
    private static final String AUTHORITY_SUFFIX = ".external-apk";
    private ExternalInstallerHandoff() { }

    public static final class Ticket {
        public final String nonce, packageName, signerSha256, receiptId, apkSha256, apkSetSha256;
        public final long versionCode, length;
        public final Uri uri;
        private Ticket(Context context, ExternalInstallerFiles.Record record) {
            nonce = record.nonce; packageName = record.packageName; signerSha256 = record.signerSha256;
            receiptId = record.receiptId; apkSha256 = record.apkSha256; apkSetSha256 = record.apkSetSha256;
            versionCode = record.versionCode; length = record.length; uri = uri(context, nonce);
        }
    }

    /** Call on the setup worker. This creates no URI grant or installation session. */
    public static Ticket prepare(Context context, GamePreparationService.PreparedPair selected) throws Exception {
        if (selected == null) throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_HANDOFF_01.text());
        if (selected.gameApks.size() != 1)
            throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_HANDOFF_02.text());
        GamePreparationService preparation = new GamePreparationService(context);
        if (!selected.installationReceiptId().equals(preparation.currentReceiptId()))
            throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_HANDOFF_03.text());
        GamePreparationService.PreparedPair pair = preparation.reloadPreparedForInstallation(selected);
        if (pair == null || !pair.embeddedBridge() || pair.helperOnlyUpgrade || pair.gameApks.size() != 1
                || !selected.installationReceiptId().equals(pair.installationReceiptId()))
            throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_HANDOFF_04.text());
        ValidatedPreparationCache.requireUnchanged(pair);
        InstallSessionWriter.Snapshot snapshot = InstallSessionWriter.inspect(pair.gameApks);
        ValidatedPreparationCache.requireUnchanged(pair);
        InstallSessionWriter.Entry entry = snapshot.entries.get(0);
        ExternalInstallerFiles.Record record = ExternalInstallerFiles.create(context.getNoBackupFilesDir(), entry.file,
                entry.length, entry.sha256, pair.gamePackage, pair.gameVersionCode, pair.signingCertificateSha256,
                pair.installationReceiptId());
        try {
            ValidatedPreparationCache.requireUnchanged(pair);
            if (!pair.installationReceiptId().equals(preparation.currentReceiptId()))
                throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_HANDOFF_05.text());
            return new Ticket(context, record);
        } catch (Exception failure) {
            try { ExternalInstallerFiles.remove(context.getNoBackupFilesDir(), record.nonce); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Cold recovery/verification performs hashing; invoke on a worker, never in rendering. */
    public static Ticket load(Context context, String nonce) throws Exception {
        return new Ticket(context, ExternalInstallerFiles.loadVerified(context.getNoBackupFilesDir(), nonce));
    }

    /** Honor Android's current default APK handler; the OS decides whether a resolver is needed. */
    public static Intent installIntent(Ticket ticket) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(ticket.uri, MIME_TYPE);
        view.setClipData(ClipData.newRawUri(AndroidMessages.EXTERNAL_INSTALLER_HANDOFF_06.text(), ticket.uri));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // No package/component, WRITE, PERSISTABLE, PREFIX, session ID, or system confirmation token.
        return view;
    }

    /** Revoke only after verification, definitive launch failure, or explicit user-ended handoff. */
    public static void revoke(Context context, String nonce) throws IOException {
        ExternalInstallerFiles.checkedNonce(nonce);
        context.revokeUriPermission(uri(context, nonce), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ExternalInstallerFiles.remove(context.getNoBackupFilesDir(), nonce);
    }

    static Uri uri(Context context, String nonce) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath("apk").appendPath(nonce).appendPath(ExternalInstallerFiles.APK).build();
    }

    static String nonce(Context context, Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())
                || !(context.getPackageName() + AUTHORITY_SUFFIX).equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null)
            throw new IOException(AndroidMessages.EXTERNAL_INSTALLER_HANDOFF_07.text());
        return ExternalInstallerFiles.nonceFromEncodedPath(uri.getEncodedPath());
    }
}
