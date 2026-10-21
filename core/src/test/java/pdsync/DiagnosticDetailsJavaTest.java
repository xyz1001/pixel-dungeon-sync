package pdsync;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Keep the actual Java adapter call shape compiled, including nested enums and getters. */
class DiagnosticDetailsJavaTest {
    @Test void javaCanCaptureTypedRestoreEvidenceAndCallSingleRecordApi() throws Exception {
        java.util.Map<HomeRestoreDiagnosticEvidence.Gate, Boolean> gates =
                new java.util.EnumMap<>(HomeRestoreDiagnosticEvidence.Gate.class);
        for (HomeRestoreDiagnosticEvidence.Gate gate : HomeRestoreDiagnosticEvidence.Gate.values()) gates.put(gate, true);
        HomeRestoreDiagnosticEvidence evidence = HomeRestoreDiagnosticEvidence.capture(
                java.util.Map.of(), java.util.Map.of(), "12345678-1234-4567-8123-123456789abc", false, gates);
        DiagnosticDetails details = DiagnosticDetails.builder().homeRestoreEvidence(evidence).build();
        assertTrue(details.isValid());
        assertEquals("true", evidence.getValues().get("filemaps_equal"));
        assertThrows(UnsupportedOperationException.class, () -> evidence.getValues().put("path", "secret"));
        // Verify the exact Java adapter signature without depending on global recorder configuration.
        assertEquals(boolean.class, DiagnosticLog.class.getMethod("recordStandalone", DiagnosticAction.class,
                DiagnosticStage.class, DiagnosticResult.class, DiagnosticFailure.class, DiagnosticDetails.class).getReturnType());
    }

    @Test void javaAdaptersCanBuildImmutableClosedDetailsWithoutKotlinDefaults() {
        DiagnosticDetails details = DiagnosticDetails.builder()
                .mode(DiagnosticDetails.Mode.ROOT_DIRECT)
                .readinessCode("ROOT_AUTH_REQUIRED")
                .access("CAPABILITY_UNAVAILABLE")
                .gamePackageStatus("VERIFIED")
                .helperPackageStatus("NOT_APPLICABLE")
                .identityVerified(true).systemRequestActive(false).restorePending(false)
                .rootStage("EUID").rootCategory("PERMISSION").errno(13).uidState(20)
                .networkTarget(DiagnosticDetails.NetworkTarget.OFFICIAL_AUTH)
                .requestDurationMs(100).gameVersionCode(123456789L).saveVersion(902)
                .saveLayout("EMPTY_DIRECTORY", 0, 0, 0, false, true)
                .build();
        assertTrue(details.isValid());
        assertEquals("ROOT_AUTH_REQUIRED", details.getValues().get(DiagnosticDetails.Field.READINESS_CODE));
        assertThrows(UnsupportedOperationException.class,
                () -> details.getValues().put(DiagnosticDetails.Field.READINESS_CODE, "PRIVATE"));
    }
}
