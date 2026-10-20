package io.github.xyz1001.pdsync.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream

/**
 * A fresh ACTION_CREATE_DOCUMENT result. SAF works on every supported API (minSdk 26), with
 * only the chooser's temporary URI grant: no storage permission, gallery access, or persist grant.
 * The caller must never construct this adapter from a browsed or pre-existing document URI.
 */
internal class AndroidAppreciationImageDestination(context: Context, private val uri: Uri) : AppreciationImageDestination {
    private val resolver = context.applicationContext.contentResolver

    override fun open(): OutputStream? = resolver.openOutputStream(uri, "w")

    override fun discardCreatedDocument() {
        // ACTION_CREATE_DOCUMENT creates a new file (including system-handled name collisions).
        // Cleanup is restricted to that new file; unsupported/failed cleanup leaves it untouched.
        val flags = runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        if ((flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong()) != 0L) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
    }
}
