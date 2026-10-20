package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Bounded snapshot writer, shared by the SAF adapter and host provider-failure tests. */
final class DiagnosticExportWriter {
    static final int MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024;
    interface Destination {
        /** Must create a new document; must never return an existing document. */
        void createNew(String filename) throws IOException;
        OutputStream openWrite() throws IOException;
        InputStream openRead() throws IOException;
        /** Only the newly-created document from this attempt may be removed. */
        void removeCreated() throws IOException;
    }
    static void write(byte[] snapshot, String filename, Destination destination) throws IOException {
        if (snapshot == null || snapshot.length == 0 || snapshot.length > MAX_SNAPSHOT_BYTES)
            throw new IOException(AndroidMessages.DIAGNOSTIC_EXPORT_WRITER_01.text());
        boolean created = false;
        boolean verified = false;
        try {
            destination.createNew(filename);
            created = true;
            try (OutputStream output = destination.openWrite()) {
                if (output == null) throw new IOException(AndroidMessages.DIAGNOSTIC_EXPORT_WRITER_02.text());
                output.write(snapshot);
                output.flush();
            }
            // Closing the writer is part of success. Verify all bytes and EOF, even when a
            // provider returns no size metadata or silently truncates a full-disk write.
            try (InputStream input = destination.openRead()) {
                if (input == null) throw new IOException(AndroidMessages.DIAGNOSTIC_EXPORT_WRITER_03.text());
                byte[] buffer = new byte[8192];
                int offset = 0;
                while (offset < snapshot.length) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, snapshot.length - offset));
                    if (count <= 0) throw new IOException(AndroidMessages.DIAGNOSTIC_EXPORT_WRITER_04.text());
                    for (int i = 0; i < count; i++)
                        if (buffer[i] != snapshot[offset + i]) throw new IOException(AndroidMessages.DIAGNOSTIC_EXPORT_WRITER_05.text());
                    offset += count;
                }
                if (input.read() != -1) throw new IOException(AndroidMessages.DIAGNOSTIC_EXPORT_WRITER_06.text());
            }
            verified = true;
        } finally {
            if (created && !verified) {
                try { destination.removeCreated(); }
                catch (Exception ignored) { /* Best effort only; never touch another document. */ }
            }
        }
    }
}
