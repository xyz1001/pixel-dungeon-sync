package pdsync;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Keep the actual Java adapter call shape compiled, including nested enums and getters. */
class DiagnosticDetailsJavaTest {
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
