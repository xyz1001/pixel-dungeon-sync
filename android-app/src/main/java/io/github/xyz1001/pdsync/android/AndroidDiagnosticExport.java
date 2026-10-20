package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import pdsync.DiagnosticAction;
import pdsync.DiagnosticLog;
import pdsync.DiagnosticResult;
import pdsync.DiagnosticStage;
import pdsync.DiagnosticTrace;

/** Local-only SAF export. Does not load a connection, invoke Root or inspect game readiness. */
final class AndroidDiagnosticExport {
    static final int REQUEST_DIRECTORY = 8104;
    private static final int TEMPORARY_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final AndroidDiagnosticExport INSTANCE = new AndroidDiagnosticExport();
    private final DiagnosticExportState state = new DiagnosticExportState();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<MainActivity> owner = new WeakReference<>(null);
    private String outcome;
    private DiagnosticTrace trace;

    static AndroidDiagnosticExport instance() { return INSTANCE; }
    synchronized void attach(MainActivity activity) { owner = new WeakReference<>(activity); }
    synchronized void detach(MainActivity activity) { if (owner.get() == activity) owner.clear(); }
    boolean busy() { return state.busy(); }
    boolean selecting() { return state.selecting(); }
    void restorePicker(boolean pending) { state.restorePicker(pending); }
    synchronized String consumeOutcome() { String result = outcome; outcome = null; return result; }

    void chooseDirectory(MainActivity activity) {
        if (state.request() == 0) return;
        trace = DiagnosticLog.begin(DiagnosticAction.EXPORT_LOGS, false);
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        picker.addFlags(TEMPORARY_GRANTS);
        try { activity.startActivityForResult(picker, REQUEST_DIRECTORY); }
        catch (RuntimeException unavailable) {
            state.cancelSelection();
            trace.failure(DiagnosticStage.EXPORT, unavailable);
            complete(AndroidMessages.ANDROID_DIAGNOSTIC_EXPORT_01.text());
        }
    }

    void onDirectoryResult(Context context, int resultCode, Intent data) {
        if (!state.selecting()) return; // Duplicate/stale results cannot start another write.
        if (resultCode != Activity.RESULT_OK) {
            state.cancelSelection();
            if (trace != null) trace.record(DiagnosticStage.EXPORT, DiagnosticResult.CANCELED);
            notifyOwner(); // Cancellation intentionally has no notice or error.
            return;
        }
        final long ticket = state.selected();
        if (ticket == 0) return;
        final DiagnosticTrace exportTrace = trace != null ? trace : DiagnosticLog.begin(DiagnosticAction.EXPORT_LOGS, false);
        final Uri tree = data == null ? null : data.getData();
        final int grants = data == null ? 0 : data.getFlags();
        final Context app = context.getApplicationContext();
        worker.execute(() -> {
            String message;
            try (AutoCloseable ignored = DiagnosticLog.bind(exportTrace)) {
                if (tree == null || !"content".equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree)
                        || (grants & TEMPORARY_GRANTS) != TEMPORARY_GRANTS)
                    throw new SecurityException(AndroidMessages.ANDROID_DIAGNOSTIC_EXPORT_02.text());
                // Snapshot is bounded and immutable before any destination document is created.
                byte[] snapshot = DiagnosticLog.snapshotExport();
                DiagnosticExportWriter.write(snapshot, uniqueFilename(), new SafDestination(app.getContentResolver(), tree));
                exportTrace.record(DiagnosticStage.EXPORT, DiagnosticResult.SUCCEEDED);
                message = AndroidMessages.ANDROID_DIAGNOSTIC_EXPORT_03.text();
            } catch (Exception failure) {
                exportTrace.failure(DiagnosticStage.EXPORT, failure);
                message = AndroidMessages.ANDROID_DIAGNOSTIC_EXPORT_04.text();
            }
            if (state.finish(ticket)) complete(message);
        });
    }

    private static String uniqueFilename() {
        String base = DiagnosticLog.suggestedFilename();
        return base.substring(0, base.length() - 4) + "-" + UUID.randomUUID().toString() + ".zip";
    }
    private synchronized void complete(String message) { outcome = message; notifyOwner(); }
    private void notifyOwner() {
        main.post(() -> {
            MainActivity activity;
            synchronized (AndroidDiagnosticExport.this) { activity = owner.get(); }
            if (activity != null && !activity.isDestroyed()) activity.onDiagnosticExportChanged();
        });
    }

    private static final class SafDestination implements DiagnosticExportWriter.Destination {
        private final ContentResolver resolver;
        private final Uri tree;
        private Uri created;
        SafDestination(ContentResolver resolver, Uri tree) { this.resolver = resolver; this.tree = tree; }
        public void createNew(String filename) throws IOException {
            Uri directory = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor metadata = resolver.query(directory,
                    new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
                if (metadata == null || !metadata.moveToFirst()
                        || !DocumentsContract.Document.MIME_TYPE_DIR.equals(metadata.getString(0))
                        || (metadata.getLong(1) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0)
                    throw new IOException(AndroidMessages.ANDROID_DIAGNOSTIC_EXPORT_05.text());
            }
            // Refuse collisions and providers that return an old document instead of creating.
            Set<String> existing = new HashSet<>();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = resolver.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor == null) throw new IOException(AndroidMessages.ANDROID_DIAGNOSTIC_EXPORT_06.text());
                int rows = 0;
                while (cursor.moveToNext()) {
                    if (++rows > 10000 || filename.equals(cursor.getString(1)))
                        throw new IOException(AndroidMessages.ANDROID_DIAGNOSTIC_EXPORT_07.text());
                    existing.add(cursor.getString(0));
                }
            }
            Uri candidate = DocumentsContract.createDocument(resolver, directory, "application/zip", filename);
            if (candidate == null || !"content".equals(candidate.getScheme())
                    || !tree.getAuthority().equals(candidate.getAuthority())
                    || existing.contains(DocumentsContract.getDocumentId(candidate)))
                throw new IOException(AndroidMessages.ANDROID_DIAGNOSTIC_EXPORT_08.text());
            created = candidate;
        }
        public OutputStream openWrite() throws IOException { return resolver.openOutputStream(created, "w"); }
        public InputStream openRead() throws IOException { return resolver.openInputStream(created); }
        public void removeCreated() throws IOException {
            if (created != null) DocumentsContract.deleteDocument(resolver, created);
        }
    }
}
