package io.github.xyz1001.pdsync.android.prepare;

import io.github.xyz1001.pdsync.android.prepare.InstallCompatibility;
import io.github.xyz1001.pdsync.android.prepare.InstalledPreparationReuse;
import java.io.IOException;
import java.util.Collections;
import java.util.Properties;

public final class HelperUpdatePolicy {

    static final class Retained {
        final InstalledPreparationReuse.Receipt game;
        final String helperHash;
        final long helperVersion;
        final boolean legacyUnboundHelper;

        Retained(InstalledPreparationReuse.Receipt receipt, long j, String str, boolean z) {
            this.game = receipt;
            this.helperVersion = j;
            this.helperHash = str;
            this.legacyUnboundHelper = z;
        }
    }

    private HelperUpdatePolicy() {
    }

    static GameSourceIdentity requireCompatible(Retained retained, InstalledPreparationReuse.Installed installed, InstalledPreparationReuse.Installed installed2, String str, long j) throws IOException {
        if (retained == null || retained.game == null) {
            throw new IOException("A validated retained preparation is required for a helper-only update");
        }
        if (retained.legacyUnboundHelper) {
            throw new IOException("The historical unbound helper cannot use a helper-only update. Resolve any pending restore and prepare a source-bound pair first.");
        }
        if (str == null) {
            throw new IOException("The original phone signing key is missing. A helper-only update never creates a replacement key; preserve the installed game and helper.");
        }
        GameSourceIdentity gameSourceIdentityRequireMatch = InstalledPreparationReuse.requireMatch(installed, retained.game, str);
        if (retained.helperVersion < 1 || !validHash(retained.helperHash)) {
            throw new IOException("Invalid retained helper identity");
        }
        if (installed2 == null) {
            throw new IOException("The retained helper is not installed. A helper-only compatible update requires the existing verified helper.");
        }
        if (!gameSourceIdentityRequireMatch.helperPackage.equals(installed2.packageName)) {
            throw new IOException("The installed helper package differs from the retained source identity");
        }
        if (!Collections.singletonList(str).equals(installed2.certificates)) {
            throw new IOException("The installed helper uses a different signing identity; a helper-only update cannot replace it");
        }
        if (!gameSourceIdentityRequireMatch.sharedUid.equals(installed2.sharedUid)) {
            throw new IOException("The installed helper has a different shared UID; this path never uninstalls a package");
        }
        if (j < 1 || j < installed2.versionCode || j < retained.helperVersion) {
            throw new IOException("The bundled helper is older than the installed or retained helper; a downgrade is not allowed");
        }
        if (installed2.versionCode != retained.helperVersion || !InstalledPreparationReuse.sameApkSet(installed2.apkHashes, Collections.singletonList(retained.helperHash))) {
            throw new IOException("The installed helper differs from the exact retained helper. Reload its matching preparation before updating.");
        }
        InstallCompatibility.Decision decisionDecide = InstallCompatibility.decide(true, true, true, installed2.versionCode, j);
        if (decisionDecide.strategy == InstallCompatibility.Strategy.COMPATIBLE_UPDATE) {
            return gameSourceIdentityRequireMatch;
        }
        throw new IOException("The helper update is not compatible: " + decisionDecide.reason);
    }

    static boolean helperOnlyUpgrade(Properties properties) throws IOException {
        String property = properties.getProperty("helperOnlyUpgrade", "false");
        if ("true".equals(property)) {
            return true;
        }
        if ("false".equals(property)) {
            return false;
        }
        throw new IOException("Invalid helper-only upgrade restriction in preparation receipt");
    }

    public static void requireInstallAction(boolean z, String str, String str2, boolean z2) throws IOException {
        if (z) {
            if (z2) {
                throw new IOException("A helper-only update never uninstalls the game or helper. Preserve the existing installation and select a regular preparation if needed.");
            }
            if (str == null || !str.equals(str2)) {
                throw new IOException("A helper-only update can install only its bound helper; reinstalling the game is prohibited");
            }
        }
    }

    public static void requireResumeConfirmation(boolean z, String str, String str2, String str3, boolean z2) throws IOException {
        if (z) {
            if (!"install".equals(str3)) {
                throw new IOException("A helper-only update cannot resume a non-install system request");
            }
            requireInstallAction(true, str, str2, false);
            if (z2) {
                throw new IOException("A save restore is unresolved. Resolve it before continuing the helper-update confirmation.");
            }
        }
    }

    static Properties updatedReceipt(Properties properties, String str, long j, String str2, String str3, String str4) throws NumberFormatException, IOException {
        if (properties == null || !"2".equals(properties.getProperty("format")) || !"false".equals(properties.getProperty("legacyUnboundHelper"))) {
            throw new IOException("Only a source-bound format-2 preparation can receive a helper-only update");
        }
        if (str != null && str.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") && !str.equals(properties.getProperty("id"))) {
            try {
                long j2 = Long.parseLong(properties.getProperty("helperVersionCode", ""));
                if (j2 < 1 || j < j2) {
                    throw new IOException("The helper update would downgrade the retained version");
                }
                if (!validHash(str2) || !validHash(str3) || !validHash(str4)) {
                    throw new IOException("Invalid updated helper digest");
                }
                Properties properties2 = new Properties();
                properties2.putAll(properties);
                properties2.setProperty("id", str);
                properties2.setProperty("helperOnlyUpgrade", "true");
                properties2.setProperty("helperVersionCode", Long.toString(j));
                properties2.setProperty("helperSha256", str2);
                properties2.setProperty("unsignedHelperSha256", str3);
                properties2.setProperty("templateSha256", str4);
                return properties2;
            } catch (NumberFormatException e) {
                throw new IOException("Invalid retained helper version", e);
            }
        }
        throw new IOException("A helper update requires a new staging identity");
    }

    private static boolean validHash(String str) {
        return str != null && str.matches("[0-9a-f]{64}");
    }
}
