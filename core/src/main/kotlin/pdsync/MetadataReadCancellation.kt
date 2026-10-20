package pdsync

import java.util.concurrent.CancellationException

/** One metadata discovery owns cancellation through each transport's response-body read. */
class MetadataReadCancellation {
 private val lock = Any()
 private var cancelled = false
 private class Registration(val cancel: () -> Unit)
 private val active = linkedSetOf<Registration>()

 fun checkActive() {
  if (Thread.currentThread().isInterrupted || synchronized(lock) { cancelled }) throw CancellationException()
 }

 fun cancel() {
  synchronized(lock) {
   if (cancelled) return
   cancelled = true
   val registrations = active.toList()
   active.clear()
   registrations.forEach { runCatching { it.cancel() } }
  }
 }

 internal fun register(cancel: () -> Unit): AutoCloseable {
  val registration = Registration(cancel)
  val cancelNow = synchronized(lock) {
   if (cancelled) true else { active += registration; false }
  }
  if (cancelNow) registration.cancel()
  return AutoCloseable { synchronized(lock) { active.remove(registration) } }
 }
}
