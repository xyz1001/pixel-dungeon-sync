package io.github.xyz1001.pdsync.android

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Host-only controlled streams/executors; these do not claim Android provider/device coverage. */
class AppreciationImageExportTest {
    private class Queue : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Destination : AppreciationImageDestination {
        val bytes = ByteArrayOutputStream()
        var opens = 0
        var flushes = 0
        var closes = 0
        var discards = 0
        var failAt = ""
        var onOpen: (() -> Unit)? = null
        override fun open(): OutputStream? {
            opens++
            onOpen?.invoke()
            when (failAt) {
                "permission" -> throw SecurityException("SECRET content URI")
                "null" -> return null
            }
            return object : OutputStream() {
                override fun write(b: Int) {
                    if (failAt == "write" || (failAt == "partial" && bytes.size() == 8))
                        throw IOException("SECRET disk full path")
                    bytes.write(b)
                }
                override fun flush() {
                    flushes++
                    if (failAt == "flush") throw IOException("SECRET flush details")
                }
                override fun close() {
                    closes++
                    if (failAt == "close") throw IOException("SECRET close details")
                }
            }
        }
        override fun discardCreatedDocument() {
            discards++
            if (failAt == "cleanup") throw IOException("SECRET cleanup details")
        }
    }
    private class Fixture {
        val io = Queue()
        val ui = Queue()
        val export = AppreciationImageExport(io, ui)
        val scope = Any()
        fun request(png: ByteArray = png()) = export.request(png, scope)
        fun finish() { io.drain(); ui.drain() }
    }

    @Test fun freezesExactOriginalPngBeforePickerAndNeverReencodes() {
        val f = Fixture()
        val original = png()
        val expected = original.copyOf()
        val destination = Destination()
        assertTrue(f.request(original))
        original.fill(0) // The controller may replace/release its own image while the chooser is open.
        assertTrue(f.export.state.busy)
        f.export.selected(destination)
        assertEquals(0, destination.opens, "opening/writing must be background work")
        f.io.drain()
        assertTrue(f.export.state.busy, "UI completion is dispatched separately")
        f.ui.drain()
        assertContentEquals(expected, destination.bytes.toByteArray())
        assertEquals(1, destination.flushes)
        assertEquals(1, destination.closes)
        assertEquals(0, destination.discards)
        assertFalse(f.export.state.busy)
        assertEquals("图片已保存。", f.export.messageFor(f.scope, expected))
    }

    @Test fun missingInvalidAndOversizedImagesNeverStartPicker() {
        val f = Fixture()
        for (invalid in listOf(null, byteArrayOf(), ByteArray(56), ByteArray(100),
            ByteArray(ManagedAccountClient.MAX_IMAGE_BYTES + 1))) {
            assertFalse(f.export.request(invalid))
            assertFalse(f.export.state.busy)
        }
        val destination = Destination()
        f.export.selected(destination)
        f.finish()
        assertEquals(0, destination.opens)
    }

    @Test fun cancelDoesNotWriteOrShowErrorAndAllowsAnotherRequest() {
        val f = Fixture()
        assertTrue(f.request())
        f.export.selected(null)
        f.finish()
        assertFalse(f.export.state.busy)
        assertEquals("", f.export.state.message)
        assertTrue(f.request())
    }

    @Test fun duplicateClicksAndRepeatedResultsProduceOneWrite() {
        val f = Fixture()
        assertTrue(f.request())
        assertFalse(f.request())
        val first = Destination()
        val duplicate = Destination()
        f.export.selected(first)
        f.export.selected(duplicate)
        f.export.selected(null)
        assertFalse(f.request())
        f.finish()
        f.export.selected(duplicate)
        f.finish()
        assertEquals(1, first.opens)
        assertEquals(0, duplicate.opens)
        assertEquals("图片已保存。", f.export.state.message)
    }

    @Test fun permissionNullDiskFullFlushAndCloseFailuresAreSafeAndRetryable() {
        for (failure in listOf("permission", "null", "write", "partial", "flush", "close")) {
            val f = Fixture()
            val destination = Destination().apply { failAt = failure }
            assertTrue(f.request())
            f.export.selected(destination)
            f.finish()
            assertEquals(1, destination.opens)
            assertEquals(1, destination.discards)
            if (failure == "partial") assertEquals(8, destination.bytes.size())
            assertEquals(if (failure == "permission" || failure == "null") 0 else 1, destination.closes)
            assertFalse(f.export.state.busy)
            assertEquals("图片保存失败，请重试。", f.export.state.message)
            assertFalse(f.export.state.message.contains("SECRET"))
            assertTrue(f.request(), "failed export must not leave the guard stuck")
        }
    }

    @Test fun failedCleanupNeverReplacesSafeFailureMessage() {
        val f = Fixture()
        val destination = object : AppreciationImageDestination {
            override fun open(): OutputStream? = throw IOException("SECRET write")
            override fun discardCreatedDocument() = throw SecurityException("SECRET cleanup")
        }
        assertTrue(f.request())
        f.export.selected(destination)
        f.finish()
        assertEquals("图片保存失败，请重试。", f.export.state.message)
    }

    @Test fun pickerLaunchFailureClearsPendingAndAllowsRetry() {
        val f = Fixture()
        assertTrue(f.request())
        f.export.launchFailed()
        assertFalse(f.export.state.busy)
        assertEquals("无法打开保存位置，请重试。", f.export.state.message)
        assertTrue(f.request())
    }

    @Test fun rejectedWorkerDoesNotOpenDestinationAndClearsBusy() {
        val export = AppreciationImageExport(Executor { throw RejectedExecutionException("SECRET") }, Executor { it.run() })
        val destination = Destination()
        assertTrue(export.request(png()))
        export.selected(destination)
        assertEquals(0, destination.opens)
        assertFalse(export.state.busy)
        assertEquals("图片保存失败，请重试。", export.state.message)
    }

    @Test fun staleResultAfterSessionCloseDoesNothing() {
        val f = Fixture()
        assertTrue(f.request())
        f.export.close()
        val destination = Destination()
        f.export.selected(destination)
        f.finish()
        assertEquals(0, destination.opens)
        assertEquals(0, destination.discards)
        assertFalse(f.request())
        assertEquals("", f.export.state.message)
    }

    @Test fun closedSessionPreventsQueuedWorkerFromOpening() {
        val f = Fixture()
        val destination = Destination()
        assertTrue(f.request())
        f.export.selected(destination)
        f.export.close()
        f.finish()
        assertEquals(0, destination.opens)
        assertEquals("", f.export.state.message)
    }

    @Test fun closeDuringOpenClosesStreamAndCannotPublishSuccess() {
        val f = Fixture()
        val destination = Destination().apply { onOpen = f.export::close }
        assertTrue(f.request())
        f.export.selected(destination)
        f.finish()
        assertEquals(0, destination.bytes.size())
        assertEquals(1, destination.closes)
        assertEquals(1, destination.discards)
        assertEquals("", f.export.state.message)
    }

    @Test fun completionAlreadyQueuedBeforeCloseCannotPublishLater() {
        val f = Fixture()
        assertTrue(f.request())
        f.export.selected(Destination())
        f.io.drain()
        f.export.close()
        f.ui.drain()
        assertEquals("", f.export.state.message)
    }

    @Test fun recreatedUiWithSamePngSeesResultButAnotherPageOrImageDoesNot() {
        val f = Fixture()
        assertTrue(f.request())
        f.export.selected(Destination())
        f.finish()
        assertEquals("图片已保存。", f.export.messageFor(f.scope, png().copyOf()))
        assertEquals("", f.export.messageFor(Any(), png()), "another account/page may use identical public PNG")
        assertEquals("", f.export.messageFor(f.scope, null))
        assertEquals("", f.export.messageFor(f.scope, png().apply { this[lastIndex] = 0 }))
    }

    @Test fun newRequestResetsPreviousResultAndUsesItsOwnBytes() {
        val f = Fixture()
        assertTrue(f.request())
        f.export.selected(Destination())
        f.finish()
        val next = png().apply { this[lastIndex] = 0 }
        assertTrue(f.request(next))
        assertEquals("", f.export.state.message)
        val destination = Destination()
        f.export.selected(destination)
        f.finish()
        assertContentEquals(next, destination.bytes.toByteArray())
    }

    companion object {
        // Synthetic 1x1 test data, never the service's actual appreciation PNG.
        private fun png() = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJ1sAAAAASUVORK5CYII=")
    }
}
