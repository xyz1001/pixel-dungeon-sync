package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Bundle;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** JVM-only package metadata doubles; not an Android Binder or installer execution. */
public final class EmbeddedBridgeRuntimeChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejected(Fixture fixture) throws Exception {
        try { PackageTrust.requireBridgeForExchange(fixture.context, fixture.pair, fixture.pin); }
        catch (SecurityException expected) { checks++; return; }
        throw new AssertionError("Untrusted embedded bridge was accepted");
    }
    public static void main(String[] args) throws Exception {
        readiness();
        trust();
        activation();
        System.out.println("Embedded bridge host checks passed: " + checks + " assertions (SDK 26 and 36). No device/Binder execution.");
    }
    private static void readiness() {
        for (InstallationReadiness.PackageStatus game : InstallationReadiness.PackageStatus.values()) {
            if (game == InstallationReadiness.PackageStatus.NOT_APPLICABLE) continue;
            InstallationReadiness state = InstallationReadiness.embedded(game, true, false, false, InstallationReadiness.AccessStatus.READY);
            check(state.helper == InstallationReadiness.PackageStatus.NOT_APPLICABLE, "No fake helper success");
            check(state.embeddedBridge, "Explicit bridge mode survives state construction");
            check(state.canContinue() == (game == InstallationReadiness.PackageStatus.VERIFIED), "Exact game verification required");
            boolean install = game == InstallationReadiness.PackageStatus.FRESH_INSTALL
                    || game == InstallationReadiness.PackageStatus.COMPATIBLE_UPDATE || game == InstallationReadiness.PackageStatus.REQUIRES_UNINSTALL;
            check(state.canContinueInstallation() == install, "Only game state controls one-package installation");
            check(!state.requiresHelperUninstall(), "No helper uninstall");
            check(!state.withSystemRequest(true).canContinueInstallation(), "Active system request blocks repeated submission");
            check(state.withAccessCode("PROVIDER_ACQUIRE_NULL").embeddedBridge, "Mode survives access failure");
        }
        InstallationReadiness ready = InstallationReadiness.embedded(InstallationReadiness.PackageStatus.VERIFIED,
                true, false, false, InstallationReadiness.AccessStatus.FIRST_LAUNCH_NEEDED);
        check(ready.canInitializeGame(), "First launch requires trusted bridge");
        check(!InstallationReadiness.embedded(InstallationReadiness.PackageStatus.VERIFIED,
                false, false, false, InstallationReadiness.AccessStatus.READY).canContinue(), "Installed APK alone never establishes bridge identity");
        check(!InstallationReadiness.embedded(InstallationReadiness.PackageStatus.VERIFIED,
                true, false, true, InstallationReadiness.AccessStatus.FIRST_LAUNCH_NEEDED).canInitializeGame(), "Pending restore blocks first launch");
        check(!ready.withSystemRequest(true).canInitializeGame(), "System transition blocks first launch");
    }
    private static void trust() throws Exception {
        List<Consumer<Fixture>> corruptions = List.of(
            f -> f.pair.invalidReceipt = true,
            f -> f.pin = "invalid",
            f -> f.pair.signingCertificateSha256 = "c".repeat(64),
            f -> f.game.signatures = new Signature[]{new Signature(new byte[]{7})},
            f -> f.game.signingInfo.signatures = new Signature[]{new Signature(new byte[]{7})},
            f -> f.pair.mainAppCertSha256 = "c".repeat(64),
            f -> f.game.applicationInfo.uid = 100,
            f -> f.game.sharedUserId = "old.shared.uid",
            f -> f.main.sharedUserId = "other.shared.uid",
            f -> f.gameMembers = new String[]{"game", "helper"},
            f -> f.gameMembers = null,
            f -> f.mainMembers = new String[]{f.mainName, "other"},
            f -> f.mainMembers = null,
            f -> f.pair.bridgeHostPackage = "helper",
            f -> f.pair.authority = "helper.saves",
            f -> f.provider = null,
            f -> f.provider.authority = "other.authority",
            f -> f.provider.packageName = "helper",
            f -> f.provider.name = "other.Provider",
            f -> f.provider.processName = "game",
            f -> f.provider.processName = "game:other",
            f -> f.provider.enabled = false,
            f -> f.provider.applicationInfo = null,
            f -> f.game.applicationInfo.enabled = false,
            f -> f.provider.exported = false,
            f -> f.provider.grantUriPermissions = true,
            f -> f.provider.readPermission = PackageTrust.ACCESS_BRIDGE,
            f -> f.provider.writePermission = "other.permission",
            f -> f.game.applicationInfo.metaData = null,
            f -> f.pair.bridgeRuntimeVersion = "2",
            f -> f.game.applicationInfo.metaData.put(Fixture.META + "BRIDGE_MODE", "legacy-helper-v2"),
            f -> f.game.applicationInfo.metaData.put(Fixture.META + "BRIDGE_RUNTIME_VERSION", "2"),
            f -> f.game.applicationInfo.metaData.put(Fixture.META + "BOUND_GAME_PACKAGE", "other"),
            f -> f.game.applicationInfo.metaData.put(Fixture.META + "BOUND_ORIGIN_IDENTITY", "b".repeat(64)),
            f -> f.game.applicationInfo.metaData.put(Fixture.META + "BOUND_NAMESPACE_ID", "other"),
            f -> f.game.applicationInfo.metaData.put(Fixture.META + "BOUND_SOURCE_APK_SHA256", "c".repeat(64)),
            f -> f.game.applicationInfo.metaData.put(Fixture.META + "MAIN_APP_CERT_SHA256", "c".repeat(64)));
        for (int sdk : new int[]{26, 36}) {
            Build.VERSION.SDK_INT = sdk;
            try (Fixture f = new Fixture()) {
                check(!PackageTrust.requireBridgeForExchange(f.context, f.pair, f.pin), "Embedded trust succeeds with real matching file digests");
                check(f.pair.validationCount == 1, "Prepared receipt validated before provider use");
                check(f.helperLookups == 0, "No helper package lookup");
                f.provider.readPermission = ""; f.provider.writePermission = "";
                check(!PackageTrust.requireBridgeForExchange(f.context, f.pair, f.pin), "Explicit empty provider permissions accepted");
                Files.writeString(f.installed, "tampered");
                rejected(f);
            }
            for (int i=0; i<corruptions.size(); i++) {
                // Each SDK reads only its own current-signer representation.
                if (sdk < 28 && i == 4 || sdk >= 28 && i == 3) continue;
                try (Fixture f = new Fixture()) { corruptions.get(i).accept(f); rejected(f); }
            }
        }
        Build.VERSION.SDK_INT = 36;
    }
    private static void activation() {
        HelperActivationPolicy.requireEmbeddedActivity("game", HelperActivationPolicy.ACTIVITY, "game", "game:pdsync_bridge", "", true, true, true, 200, 200);
        checks++;
        try { HelperActivationPolicy.requireEmbeddedActivity("game", HelperActivationPolicy.ACTIVITY, "helper", "game:pdsync_bridge", null, true, true, true, 200, 200); }
        catch (SecurityException expected) { checks++; return; }
        throw new AssertionError("External helper activation accepted");
    }
    private static final class Fixture extends PackageManager implements AutoCloseable {
        static final String META = "io.github.xyz1001.pdsync.";
        final String mainName = "io.github.xyz1001.pdsync.android";
        final GamePreparationService.PreparedPair pair = new GamePreparationService.PreparedPair();
        final PackageInfo game = info("game", 200, new byte[]{1,2,3});
        final PackageInfo main = info(mainName, 100, new byte[]{4,5,6});
        final Path root = Files.createTempDirectory("embedded-main-trust-");
        final Path installed = root.resolve("installed.apk");
        ProviderInfo provider = new ProviderInfo();
        String[] gameMembers = {"game"}, mainMembers = {mainName};
        String pin;
        int helperLookups;
        final Context context = new Context() {
            public PackageManager getPackageManager() { return Fixture.this; }
            public String getPackageName() { return mainName; }
            public File getFilesDir() { return root.toFile(); }
        };
        Fixture() throws Exception {
            Files.writeString(installed, "fixture APK bytes");
            Path prepared = root.resolve("prepared.apk"); Files.copy(installed, prepared);
            game.applicationInfo.sourceDir = installed.toString();
            pair.gameApks = List.of(prepared.toFile());
            pair.gamePackage = "game"; pair.bridgeHostPackage = "game"; pair.bridgeMode = "embedded-v1";
            pair.bridgeRuntimeVersion = "1"; pair.originIdentity = "a".repeat(64); pair.sourceApkSetSha256 = "b".repeat(64);
            pair.namespaceId = "namespace"; pair.authority = "io.github.xyz1001.pdsync.embedded.g" + "a".repeat(32) + ".files";
            pin = PackageTrust.signer(game); pair.signingCertificateSha256 = pin; pair.mainAppCertSha256 = PackageTrust.signer(main);
            Bundle metadata = new Bundle(); game.applicationInfo.metaData = metadata;
            metadata.put(META + "BRIDGE_MODE", pair.bridgeMode); metadata.put(META + "BRIDGE_RUNTIME_VERSION", pair.bridgeRuntimeVersion);
            metadata.put(META + "BOUND_GAME_PACKAGE", pair.gamePackage); metadata.put(META + "BOUND_ORIGIN_IDENTITY", pair.originIdentity);
            metadata.put(META + "BOUND_NAMESPACE_ID", pair.namespaceId); metadata.put(META + "BOUND_SOURCE_APK_SHA256", pair.sourceApkSetSha256);
            metadata.put(META + "MAIN_APP_CERT_SHA256", pair.mainAppCertSha256);
            provider.authority = pair.authority; provider.packageName = "game"; provider.name = "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider";
            provider.processName = "game:pdsync_bridge"; provider.enabled = true; provider.exported = true; provider.applicationInfo = game.applicationInfo;
        }
        static PackageInfo info(String name, int uid, byte[] certificate) {
            PackageInfo info = new PackageInfo(); info.packageName = name; info.applicationInfo = new ApplicationInfo();
            info.applicationInfo.uid = uid; info.applicationInfo.enabled = true;
            info.signatures = new Signature[]{new Signature(certificate)};
            info.signingInfo = new SigningInfo(); info.signingInfo.signatures = info.signatures;
            return info;
        }
        public PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
            if ("game".equals(name)) return game; if (mainName.equals(name)) return main;
            helperLookups++; throw new NameNotFoundException();
        }
        public String[] getPackagesForUid(int uid) { return uid == 200 ? gameMembers : mainMembers; }
        public ProviderInfo resolveContentProvider(String authority, int flags) { return provider; }
        public void close() throws Exception {
            try (var stream = Files.walk(root)) {
                for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
