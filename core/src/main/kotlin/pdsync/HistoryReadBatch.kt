package pdsync

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** One bounded batch; all workers terminate before either results or failure leave the owner. */
internal fun <K,V> historyReadBatch(keys:List<K>, cancellation:MetadataReadCancellation, read:(K)->V):Map<K,V> {
 require(keys.size<=4)
 cancellation.checkActive()
 if(keys.isEmpty())return emptyMap()
 val trace=DiagnosticLog.currentTrace()
 val executor=Executors.newFixedThreadPool(keys.size) { Thread(it,"pdsync-history-read").apply {isDaemon=true} }
 val completed=ExecutorCompletionService<Pair<K,V>>(executor)
 val futures=mutableListOf<Future<Pair<K,V>>>()
 try {
  keys.forEach { key -> futures+=completed.submit(Callable {
   val binding=trace?.let(DiagnosticLog::bind)
   try {cancellation.checkActive();key to read(key)} finally {binding?.close()}
  }) }
  val results=mutableMapOf<K,V>()
  repeat(keys.size) {
   val result=try {completed.take().get()}catch(e:ExecutionException){throw(e.cause?:e)}
   results[result.first]=result.second
  }
  cancellation.checkActive()
  return keys.associateWith {results.getValue(it)}
 } catch(failure:Throwable) {
  cancellation.cancel()
  throw failure
 } finally {
  futures.forEach {it.cancel(true)}
  executor.shutdownNow()
  // Never permit a later publication while a canceled read is still running.
  var interrupted=Thread.interrupted()
  while(!executor.isTerminated) {
   try {executor.awaitTermination(100,TimeUnit.MILLISECONDS)}
   catch(_:InterruptedException){interrupted=true;cancellation.cancel()}
  }
  if(interrupted)Thread.currentThread().interrupt()
 }
}
