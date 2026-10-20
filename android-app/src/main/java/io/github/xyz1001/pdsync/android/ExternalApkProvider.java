package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Non-exported provider: Android URI grants authorize one exact immutable snapshot, read-only. */
public final class ExternalApkProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private ExternalInstallerFiles.Record record(Uri uri) throws IOException {
        if (getContext() == null) throw new IOException(AndroidMessages.EXTERNAL_APK_PROVIDER_01.text());
        return ExternalInstallerFiles.load(getContext().getNoBackupFilesDir(), ExternalInstallerHandoff.nonce(getContext(), uri));
    }

    @Override public String getType(Uri uri) {
        try { record(uri); return ExternalInstallerHandoff.MIME_TYPE; }
        catch (IOException invalid) { throw new IllegalArgumentException(AndroidMessages.EXTERNAL_APK_PROVIDER_02.text(), invalid); }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        if (selection != null || args != null || sortOrder != null)
            throw new IllegalArgumentException(AndroidMessages.EXTERNAL_APK_PROVIDER_03.text());
        try {
            ExternalInstallerFiles.Record record = record(uri);
            String[] columns = projection == null
                    ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection.clone();
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = ExternalInstallerFiles.APK;
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = record.length;
                else values[i] = null; // Never publish local paths, receipt IDs, or other metadata.
            }
            MatrixCursor cursor = new MatrixCursor(columns, 1); cursor.addRow(values); return cursor;
        } catch (IOException invalid) { throw new IllegalArgumentException(AndroidMessages.EXTERNAL_APK_PROVIDER_02.text(), invalid); }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException(AndroidMessages.EXTERNAL_APK_PROVIDER_04.text());
        try { return ParcelFileDescriptor.open(record(uri).apk, ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (IOException invalid) { throw new FileNotFoundException(AndroidMessages.EXTERNAL_APK_PROVIDER_05.text()); }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(AndroidMessages.EXTERNAL_APK_PROVIDER_06.text()); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(AndroidMessages.EXTERNAL_APK_PROVIDER_06.text()); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(AndroidMessages.EXTERNAL_APK_PROVIDER_06.text()); }
}
