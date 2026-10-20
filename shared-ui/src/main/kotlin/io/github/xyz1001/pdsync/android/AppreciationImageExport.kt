package io.github.xyz1001.pdsync.android

import pdsync.i18n.SharedMessages
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** A destination created for this export, never an arbitrary existing file. */
interface AppreciationImageDestination {
    fun open(): OutputStream?
    /** Optional, best-effort cleanup of only the document created by this request. */
    fun discardCreatedDocument() {}
}

data class AppreciationImageExportState(val busy: Boolean = false, val message: String = "")

/**
 * In-memory export only: no network, QR interpretation, bitmap re-encoding, or persisted state.
 * UI methods and [ui] callbacks run on the UI thread. [io] owns streams and may retain only an
 * application-scoped destination. Retain this coordinator across rotation, close it on real exit.
 */
class AppreciationImageExport(private val io: Executor, private val ui: Executor) : AutoCloseable {
    private class Request(val png: ByteArray, var choosing: Boolean = true)
    private val alive = AtomicBoolean(true)
    private var request: Request? = null
    private var source: Any? = null
    private var imageDigest: ByteArray? = null

    var state by mutableStateOf(AppreciationImageExportState())
        private set

    /**
     * The controller supplies its currently displayed, validated PNG. Keep that exact snapshot
     * through the chooser even if a refresh replaces the displayed image. The lightweight checks
     * below are defensive; the managed-account client owns full PNG and digest validation.
     */
    fun request(png: ByteArray?, source: Any? = null): Boolean {
        if (!alive.get() || request != null) return false
        if (png == null || png.size !in 57..ManagedAccountClient.MAX_IMAGE_BYTES ||
            !PNG_SIGNATURE.indices.all { png[it] == PNG_SIGNATURE[it] }) {
            state = AppreciationImageExportState(message = SharedMessages.NO_IMAGE_SAVE.text())
            return false
        }
        request = Request(png.copyOf())
        this.source = source
        imageDigest = digest(png)
        state = AppreciationImageExportState(busy = true)
        return true
    }

    /** Same page and exact PNG may be decoded again on resume; object identity is insufficient. */
    fun messageFor(source: Any?, png: ByteArray?): String {
        if (state.message.isEmpty() || source !== this.source || png == null) return ""
        return if (imageDigest?.contentEquals(digest(png)) == true) state.message else ""
    }

    /** A null destination is ordinary picker cancellation and never opens or writes a stream. */
    fun selected(destination: AppreciationImageDestination?) {
        val pending = request ?: return
        if (!alive.get() || !pending.choosing) return
        pending.choosing = false
        if (destination == null) {
            finish(pending, "")
            return
        }
        try {
            io.execute {
                // A result queued before session disposal must not start writing afterward.
                if (!alive.get()) return@execute
                val message = try {
                    val output = destination.open() ?: throw IOException("No output stream")
                    output.use {
                        if (!alive.get() || Thread.currentThread().isInterrupted) throw IOException("Export canceled")
                        it.write(pending.png)
                        it.flush()
                    }
                    SharedMessages.IMAGE_SAVED.text()
                } catch (_: Exception) {
                    // Never delete through a generic path/URI. The adapter can remove only this
                    // CreateDocument result, and only if its provider supports deletion.
                    runCatching { destination.discardCreatedDocument() }
                    SharedMessages.IMAGE_SAVE_FAILED.text()
                }
                ui.execute { finish(pending, message) }
            }
        } catch (_: RuntimeException) {
            finish(pending, SharedMessages.IMAGE_SAVE_FAILED.text())
        }
    }

    /** Opening the system chooser can fail on a device without a working document provider. */
    fun launchFailed() {
        val pending = request ?: return
        if (pending.choosing) finish(pending, SharedMessages.SAVE_LOCATION_FAILED.text())
    }

    private fun finish(pending: Request, message: String) {
        if (!alive.get() || request !== pending) return
        request = null
        state = AppreciationImageExportState(message = message)
    }

    override fun close() {
        alive.set(false)
        request = null
        source = null
        imageDigest = null
        state = AppreciationImageExportState()
    }

    private fun digest(png: ByteArray) = MessageDigest.getInstance("SHA-256").digest(png)

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    }
}
