package pdsync
import org.junit.jupiter.api.Test
import kotlin.test.*

/** New tests for bytecode-derived timing semantics. */
class ReconstructedDiagnosticTimingTest {
 @Test fun scopeClosesOnceAndCapturesOriginTrace(){val trace=DiagnosticTrace("t",DiagnosticAction.INSPECT,false);var now=10L;val s=DiagnosticTiming.Scope(trace,DiagnosticPhase.SAVE_EXPORT){now};now=2_000_010;s.close();s.failed(Exception());val value=trace.timings.snapshot().getValue(DiagnosticPhase.SAVE_EXPORT);assertEquals(2,value.durationMs);assertEquals(1,value.count);assertEquals(0,value.failed)}
 @Test fun failuresRemainFailures(){val trace=DiagnosticTrace("t",DiagnosticAction.INSPECT,false);val s=DiagnosticTiming.Scope(trace,DiagnosticPhase.LOCAL_EXPORT_VERIFY){1};s.failed(IllegalStateException());s.close();assertEquals(1,trace.timings.snapshot().values.single().failed)}
 @Test fun measureRethrowsAndRecords(){val trace=DiagnosticTrace("t",DiagnosticAction.INSPECT,false);DiagnosticLog.bind(trace).use {assertFailsWith<IllegalStateException>{DiagnosticTiming.measure(DiagnosticPhase.COMPARISON){throw IllegalStateException("synthetic")}}};assertEquals(1,trace.timings.snapshot().values.single().failed)}
 @Test fun canonicalEvidenceRejectsUnknownAndDuplicates(){val value=DiagnosticTimingEvidence.encode(mapOf(DiagnosticPhase.SAVE_EXPORT to DiagnosticPhaseMeasurement(2000,1)))!!;assertNotNull(DiagnosticTimingEvidence.decode(value));assertNull(DiagnosticTimingEvidence.decode(value.replace("SAVE_EXPORT","SECRET_PATH")));assertNull(DiagnosticTimingEvidence.decode(value.replace("\"count\":1","\"count\":1,\"count\":1")))}
 @Test fun totalsAreBounded(){val trace=DiagnosticTrace("t",DiagnosticAction.INSPECT,false);trace.timings.add(DiagnosticPhase.QUEUE_WAIT,Long.MAX_VALUE,DiagnosticResult.SUCCEEDED);trace.timings.add(DiagnosticPhase.QUEUE_WAIT,Long.MAX_VALUE,DiagnosticResult.CANCELED);val total=trace.timings.snapshot().values.single();assertEquals(604800000,total.durationMs);assertEquals(2,total.count);assertEquals(1,total.canceled)}
}
