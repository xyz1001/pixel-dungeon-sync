package pdsync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI-thread read leases: an old completion cannot clear a newer page's loading state. */
internal class DesktopArchiveReads {
 private var next = 0L
 private var active by mutableStateOf(emptySet<Long>())
 val loading: Boolean get() = active.isNotEmpty()
 fun begin(): Long = (++next).also { active = active + it }
 fun end(read: Long) { active = active - read }
 fun invalidate() { active = emptySet() }
 suspend fun <T> reading(block: suspend () -> T): T {
  val read = begin()
  return try { block() } finally { end(read) }
 }
}
