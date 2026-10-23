package pdsync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.test.*

class DiagnosticBatchDurabilityTest {
 @TempDir lateinit var root:Path
 private fun events():String=ZipInputStream(DiagnosticLog.snapshotExport().inputStream()).use { zip ->
  var result=""
  while(true){val entry=zip.nextEntry?:break;if(entry.name=="events.jsonl")result=zip.readBytes().decodeToString()}
  result
 }
 @Test fun slowForceIsBatchedAndConcurrentHttpThreadsKeepTheirTrace() {
  DiagnosticLog.configure(root,"0.5.87-test","android")
  val forces=AtomicInteger()
  DiagnosticLog.forceForTests={Thread.sleep(100);it.force(true);forces.incrementAndGet()}
  val pool=Executors.newFixedThreadPool(4)
  try {
   val traces=(1..4).map {DiagnosticLog.begin(DiagnosticAction.PUSH,false)}
   val tasks=traces.map {trace->pool.submit {DiagnosticLog.bind(trace).use {repeat(5){assertTrue(DiagnosticLog.http(DiagnosticHttpMethod.GET,200))}}}}
   tasks.forEach {it.get(5,TimeUnit.SECONDS)}
   assertEquals(0,forces.get(),"24 appends must not issue per-request force")
   assertTrue(traces[0].record(DiagnosticStage.FINISHED,DiagnosticResult.SUCCEEDED))
   assertEquals(1,forces.get())
   val lines=events().lines().filter(String::isNotBlank)
   assertEquals(25,lines.size)
   traces.forEach {trace->assertEquals(if(trace===traces[0])7 else 6,lines.count {it.contains(trace.id)})}
   assertEquals(1,forces.get(),"already durable export must not force again")
  } finally {pool.shutdownNow();DiagnosticLog.forceForTests=null}
 }
 @Test fun boundedBatchExportAndInterruptedCancellationPreserveTerminalRecords() {
  DiagnosticLog.configure(root,"0.5.87-test","android")
  val forces=AtomicInteger();DiagnosticLog.forceForTests={it.force(true);forces.incrementAndGet()}
  try {
   val trace=DiagnosticLog.begin(DiagnosticAction.PUSH,false)
   repeat(70){assertTrue(trace.record(DiagnosticStage.HTTP,DiagnosticResult.SUCCEEDED))}
   assertEquals(2,forces.get())
   assertEquals(71,events().lines().count(String::isNotBlank));assertEquals(3,forces.get())
   Thread.currentThread().interrupt()
   try {assertTrue(trace.record(DiagnosticStage.FINISHED,DiagnosticResult.CANCELED,DiagnosticFailure.CANCELED));assertTrue(Thread.currentThread().isInterrupted)}
   finally {Thread.interrupted()}
   assertEquals(4,forces.get());assertTrue(events().contains("\"result\":\"CANCELED\""))
  } finally {DiagnosticLog.forceForTests=null;Thread.interrupted()}
 }
 @Test fun failedForceCanRetryAndReconfigurationFlushesPreviousDirectory() {
  DiagnosticLog.configure(root.resolve("first"),"0.5.87-test","android")
  val trace=DiagnosticLog.begin(DiagnosticAction.INSPECT,false)
  DiagnosticLog.forceForTests={throw java.io.IOException("synthetic")}
  assertFalse(trace.record(DiagnosticStage.FINISHED,DiagnosticResult.FAILED,DiagnosticFailure.NETWORK_IO))
  val forces=AtomicInteger();DiagnosticLog.forceForTests={it.force(true);forces.incrementAndGet()}
  try {
   DiagnosticLog.configure(root.resolve("second"),"0.5.87-test","android")
   assertEquals(1,forces.get())
   DiagnosticLog.configure(root.resolve("first"),"0.5.87-test","android")
   assertTrue(events().contains("\"result\":\"FAILED\""))
  } finally {DiagnosticLog.forceForTests=null}
 }
}
