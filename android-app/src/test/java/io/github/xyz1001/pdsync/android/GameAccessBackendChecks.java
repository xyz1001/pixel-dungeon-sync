package io.github.xyz1001.pdsync.android;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Map;
import pdsync.android.app.AndroidGameBridge;
import pdsync.android.app.AndroidGameTarget;

/** Pure JVM contract checks. No Android service, provider, Root, or device is executed. */
public final class GameAccessBackendChecks {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        observationEvidence();
        capabilityMetadata();
        coreCompatibility();
        System.out.println("Game access backend contract checks passed: " + checks + " assertions.");
        System.out.println("Boundary: immutable values and core interface compatibility only; no Android/Root/device execution.");
    }

    private static void immutableBooleanValue(Class<?> type, int count) {
        check(Modifier.isFinal(type.getModifiers()), "Evidence value class is final");
        Field[] fields = type.getDeclaredFields();
        check(fields.length == count, "Only documented evidence fields exist");
        for (Field field : fields) {
            check(field.getType() == boolean.class, "Evidence is a boolean value");
            check(Modifier.isPublic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())
                    && !Modifier.isStatic(field.getModifiers()), "Evidence is immutable per observation");
        }
    }

    private static void observationEvidence() {
        immutableBooleanValue(GameAccessBackend.Observation.class, 4);
        for (int flags = 0; flags < 16; flags++) {
            boolean foreground = (flags & 1) != 0;
            boolean process = (flags & 2) != 0;
            boolean ready = (flags & 4) != 0;
            boolean reliable = (flags & 8) != 0;
            GameAccessBackend.Observation evidence = new GameAccessBackend.Observation(foreground, process, ready, reliable);
            check(evidence.foreground == foreground, "Foreground is preserved without inference");
            check(evidence.processPresent == process, "Process presence is preserved without inference");
            check(evidence.ready == ready, "Readiness is preserved without inference");
            check(evidence.reliable == reliable, "Reliability is independent of foreground/readiness");
        }
        GameAccessBackend.Observation unknown = GameAccessBackend.Observation.unknown();
        check(!unknown.foreground && !unknown.processPresent && !unknown.ready && !unknown.reliable,
                "Unknown observation never invents ready/reliable evidence");
    }

    private static void capabilityMetadata() {
        immutableBooleanValue(GameAccessBackend.Capabilities.class, 4);
        for (int flags = 0; flags < 16; flags++) {
            GameAccessBackend.Capabilities support = new GameAccessBackend.Capabilities(
                    (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0);
            check(support.exportSupported == ((flags & 1) != 0), "Export support is explicit");
            check(support.restoreSupported == ((flags & 2) != 0), "Restore support is explicit");
            check(support.lifecycleObservation == ((flags & 4) != 0), "Observation support is explicit");
            check(support.interruptedBackupRecovery == ((flags & 8) != 0), "Recovery support is explicit");
        }
        check(GameAccessBackend.Mode.EMBEDDED_BRIDGE != GameAccessBackend.Mode.LEGACY_HELPER_BRIDGE,
                "Legacy helper access is not mislabeled embedded");
        check(GameAccessBackend.Mode.ROOT_DIRECT != GameAccessBackend.Mode.EMBEDDED_BRIDGE,
                "Future Root mode is distinguishable without selecting it");
    }

    private static void coreCompatibility() throws Exception {
        check(AndroidGameBridge.class.isAssignableFrom(GameAccessBackend.class), "Core consumes the Android boundary unchanged");
        check(GameAccessBackend.class.getMethod("targetGame").getReturnType() == AndroidGameTarget.class,
                "Existing target identity type is preserved");
        check(GameAccessBackend.class.getMethod("exportTo", Path.class).getReturnType() == void.class,
                "Existing export contract is preserved");
        check(GameAccessBackend.class.getMethod("restoreFrom", Path.class, Map.class).getReturnType() == void.class,
                "Restore keeps expected-current hashes");
        for (String operation : new String[]{"inspectReady", "recoverInterruptedBackup", "observeLifecycle", "mode", "capabilities"}) {
            check(Modifier.isAbstract(GameAccessBackend.class.getMethod(operation).getModifiers()),
                    "Each backend must explicitly implement " + operation + "; no successful default");
        }
        check(GameAccessBackend.class.getMethod("inspectReady").getReturnType() == String.class, "Readiness result remains a string");
        check(GameAccessBackend.class.getMethod("recoverInterruptedBackup").getReturnType() == String.class, "Recovery result remains a string");
    }
}
