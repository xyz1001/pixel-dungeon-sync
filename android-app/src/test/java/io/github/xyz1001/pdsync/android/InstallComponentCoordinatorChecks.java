package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Executes production coordinator/receiver/resolver/writer. Framework and service boundaries are doubles. */
public final class InstallComponentCoordinatorChecks {
    private static final String MAIN = "io.github.xyz1001.pdsync.android";
    private static final String GAME = "com.example.pickedgame";
    private static final String PREFIX = "io.github.xyz1001.pdsync.";
    private static final String RECEIPT = "selected-receipt-v49";
    private static final String INSTALLED_ORIGIN = "c".repeat(64);
    private static final String INSTALLED_SOURCE = "d".repeat(64);
    private static final byte[] SELECTED_BYTES = "selected-version-7-content".getBytes(StandardCharsets.UTF_8);
    private static int assertions, cases;
    private static Path root;

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]);
        run("before-request and stale no-request checkpoint never accept", InstallComponentCoordinatorChecks::beforeRequest);
        run("selected bytes commit before old component can be accepted", InstallComponentCoordinatorChecks::commitBoundary);
        run("success callback accepts different installed source/version", () -> callbackAccepts(0));
        run("cancel callback accepts valid component", () -> callbackAccepts(3));
        run("failure callback accepts valid component", () -> callbackAccepts(1));
        run("pending confirmation waits; explicit UI return accepts", InstallComponentCoordinatorChecks::confirmationReturn);
        run("failed confirmation launch preserves retryable request", InstallComponentCoordinatorChecks::confirmationLaunchFailure);
        run("lost token and vanished session release checkpoint after cold restart", InstallComponentCoordinatorChecks::expiredConfirmationReleases);
        run("lost token never cancels a live session", InstallComponentCoordinatorChecks::expiredConfirmationKeepsLive);
        run("valid token protects a vanished session checkpoint", InstallComponentCoordinatorChecks::validConfirmationKeepsCheckpoint);
        run("lost token with unknown session remains recoverable", InstallComponentCoordinatorChecks::unknownSessionKeepsCheckpoint);
        run("lost uninstall token never infers cancellation", InstallComponentCoordinatorChecks::uninstallTokenKeepsCheckpoint);
        run("disabled confirmation component is blocked without launch or cancellation", InstallComponentCoordinatorChecks::disabledConfirmation);
        run("visibility miss is not proof of disabled installer", InstallComponentCoordinatorChecks::unknownConfirmationResolver);
        run("automatic confirmation attempt survives launch failure and recreation", InstallComponentCoordinatorChecks::durableConfirmationAttempt);
        run("activity return without callback accepts", InstallComponentCoordinatorChecks::activityReturn);
        run("missing callback plus vanished session survives cold recreation", InstallComponentCoordinatorChecks::sessionGoneRestart);
        run("returned marker survives cold recreation with a live session", InstallComponentCoordinatorChecks::returnedMarkerRestart);
        run("pending receipt mismatch never probes or succeeds", InstallComponentCoordinatorChecks::receiptMismatch);
        run("absent package waits until explicit settling", InstallComponentCoordinatorChecks::absentUntilSettled);
        run("absent component waits then late component succeeds", InstallComponentCoordinatorChecks::lateComponent);
        run("malformed own component waits then repaired metadata succeeds", InstallComponentCoordinatorChecks::malformedComponent);
        run("duplicate and late callbacks cannot process twice", InstallComponentCoordinatorChecks::duplicateResults);
        run("completed metadata proof persists without hash or preparation reads", InstallComponentCoordinatorChecks::completedProof);
        run("code48 returned READY checkpoint recovers component-only", InstallComponentCoordinatorChecks::version48Recovery);
        run("mutated selected bytes cannot reach a commit or success", InstallComponentCoordinatorChecks::sourceMutation);
        run("callback creation failure clears uncommitted request only", InstallComponentCoordinatorChecks::callbackCreationFailure);
        run("external route owns an independent durable checkpoint", InstallComponentCoordinatorChecks::externalCheckpoint);
        run("external handoff closes only matching own live session", InstallComponentCoordinatorChecks::externalClosesOwnSession);
        run("external handoff closes a live RESULT checkpoint too", InstallComponentCoordinatorChecks::externalClosesResultSession);
        run("external handoff rejects unknown session identity", InstallComponentCoordinatorChecks::externalRejectsUnknownSession);
        run("external handoff rejects mismatched session target", InstallComponentCoordinatorChecks::externalRejectsWrongSession);
        run("external handoff rejects unowned session despite same target", InstallComponentCoordinatorChecks::externalRejectsUnownedSession);
        run("external handoff rejects a still-active installer session", InstallComponentCoordinatorChecks::externalRejectsActiveSession);
        run("external handoff rejects split APKs before cancelling old session", InstallComponentCoordinatorChecks::externalRejectsSplits);
        run("external handoff rejects unresolved restore before staging or cancellation", InstallComponentCoordinatorChecks::externalRejectsRestore);
        run("external handoff rejects a pending uninstall", InstallComponentCoordinatorChecks::externalRejectsUninstall);
        run("external staging failure preserves own existing request", InstallComponentCoordinatorChecks::externalStagingFailure);
        run("external verification rejects old version signer and bytes", InstallComponentCoordinatorChecks::externalRejectsMismatch);
        run("external exact installed content survives cold recreation", InstallComponentCoordinatorChecks::externalAcceptsExact);
        run("external verification rejects replacement between initial PM reads", InstallComponentCoordinatorChecks::externalRejectsReplacement);
        run("external explicit end remains available after failed verification", InstallComponentCoordinatorChecks::externalEndAfterBadComponent);
        run("external explicit end never claims an absent installation succeeded", InstallComponentCoordinatorChecks::externalEndAbsent);
        run("external launch failure never accepts a preexisting exact component", InstallComponentCoordinatorChecks::externalLaunchFailure);
        run("external return releases wait without revoke or inferred success", InstallComponentCoordinatorChecks::externalReturnUnlocks);
        run("external returned observation survives restart and accepts delayed exact bytes", InstallComponentCoordinatorChecks::externalDelayedResult);
        run("external returned mismatch stays unverified and retryable", InstallComponentCoordinatorChecks::externalReturnedMismatch);
        run("external stale return cannot release newer request", InstallComponentCoordinatorChecks::externalStaleReturn);
        run("external observation superseded by source choice never selects old result", InstallComponentCoordinatorChecks::externalSuperseded);
        run("external retry failure preserves previous shared APK", InstallComponentCoordinatorChecks::externalRetryFailure);
        System.out.println("PASS InstallComponentCoordinatorChecks: " + cases + " scenarios, " + assertions + " assertions");
        System.out.println("Boundary: real production coordinator, receiver, resolver/binding, session writer, policies and token lifecycle; Android/PackageInstaller/PM/preferences, preparation, trust accessors, runtime selection and diagnostics are host doubles. No Android device/UI/APK install or save-access evidence.");
    }

    private static void beforeRequest() throws Exception {
        Fixture f = fixture(); f.pm.publishValid();
        for (int i = 0; i < 4; i++) f.coordinator.reconcile();
        check(!f.coordinator.installationResultBoundary(), "ordinary READY is not a result boundary");
        check(f.pm.componentReads == 0 && SyncRuntime.selections == 0, "ordinary resume does not discover existing component");
        f.register();
        check(f.coordinator.requiresSelectedFileInstallation(f.pair), "picked file intent remains pending");
        check(!f.coordinator.mayReuseInstalledSelection(), "preexisting bridge cannot replace picked file");
        f.coordinator.reconcile(true);
        check(f.pm.componentReads == 0, "even stray return before any request cannot scan");
        f.prefs.edit().putString("state", "RESULT").putString("operation", "install")
                .putString("package", GAME).remove("installRequestReceipt").commit();
        f.coordinator.reconcile(true);
        check(f.pm.componentReads == 0 && SyncRuntime.selections == 0, "stale RESULT without request receipt cannot authorize component");
        check(!f.prefs.getBoolean("installedVerified", false), "stale result never verifies install");
    }

    private static void commitBoundary() throws Exception {
        Fixture f = fixture(); f.pm.publishValid(); f.register();
        check(GAME.equals(f.coordinator.nextPackage(f.pair)), "selected file requires install despite old component");
        check(f.pm.componentReads == 0, "nextPackage never discovers old component before selected install");
        f.request();
        PackageInstaller.Session session = f.pm.installer.sessions.get(f.session);
        check(f.pm.installer.commits == 1, "exactly one system commit");
        check(Arrays.equals(SELECTED_BYTES, session.contents.get("base.apk").toByteArray()), "real writer delivered selected bytes");
        check(session.fsyncs == 1 && session.params.size == SELECTED_BYTES.length, "source stream synced with exact length");
        check(session.params.requireUserAction == PackageInstaller.SessionParams.USER_ACTION_REQUIRED, "fixture receives required user action");
        check(RECEIPT.equals(f.prefs.getString("installRequestReceipt", "")), "real request persists matching receipt before commit");
        for (int i = 0; i < 4; i++) f.coordinator.reconcile();
        check(!f.coordinator.installationResultBoundary(), "active session is not a return boundary");
        check(f.pm.componentReads == 0 && SyncRuntime.selections == 0, "commit pending cannot consume old component");
        check(f.coordinator.hasPendingResult(), "request remains pending");
        f.assertMetadataOnly();
    }

    private static void callbackAccepts(int status) throws Exception {
        Fixture f = started(); f.pm.publishValid(); f.callback(status);
        check("RESULT".equals(f.coordinator.state()), "real receiver persists terminal status");
        check(f.prefs.getInt("systemStatus", -99) == status, "actual system status retained");
        f.removePreparedSource(); f.coordinator.reconcile(); f.assertSuccess();
        check(SyncRuntime.installed.versionCode == 99, "installed version 99 accepted independently of selected version 7");
        check(INSTALLED_SOURCE.equals(SyncRuntime.installed.sourceApkSetSha256), "installed metadata source retained independently of selected bytes");
        check(INSTALLED_ORIGIN.equals(SyncRuntime.installed.originIdentity), "installed origin retained independently of prepared origin");
    }

    private static void confirmationReturn() throws Exception {
        Fixture f = started(); f.pm.publishValid();
        f.deliver(f.callbackIntent(-1).putExtra("android.intent.extra.INTENT", new Intent("android.install.CONFIRM")));
        check("PENDING_CONFIRMATION".equals(f.coordinator.state()) && f.coordinator.hasConfirmation(), "pending callback retains real confirmation token");
        f.coordinator.reconcile();
        check(f.pm.componentReads == 0, "confirmation still pending cannot inspect old component");
        f.coordinator.confirmationLaunched();
        check(f.coordinator.confirmationWasLaunched(), "confirmation launch nonce persisted");
        f.coordinator.reconcile();
        check(f.pm.componentReads == 0, "launch alone is not the return boundary");
        f.coordinator.reconcile(true); f.assertSuccess();
    }

    private static void confirmationLaunchFailure() throws Exception {
        Fixture f = started();
        // Reopening confirmation must validate preparation; this is not result reconciliation.
        GamePreparationService.forbidReload = false;
        f.deliver(f.callbackIntent(-1).putExtra("android.intent.extra.INTENT", new Intent("android.install.CONFIRM")));
        String nonce = f.coordinator.requestNonce();
        String retained = f.prefs.getString("confirmation", null);
        android.app.PendingIntent token = f.coordinator.confirmationToken();
        int session = f.prefs.getInt("session", -1);
        f.coordinator.confirmationLaunched();
        check(f.coordinator.confirmationWasLaunched(), "launch attempt sets nonce latch");
        f.coordinator.confirmationLaunchFailed();
        check(!f.coordinator.confirmationWasLaunched(), "failed launch clears nonce latch");
        check("PENDING_CONFIRMATION".equals(f.coordinator.state()), "failed launch returns to pending confirmation");
        check(nonce.equals(f.coordinator.requestNonce()), "failed launch preserves request nonce");
        check(retained.equals(f.prefs.getString("confirmation", null)), "failed launch preserves retained token identity");
        check(session == f.prefs.getInt("session", -1), "failed launch preserves session identity");
        check(f.pm.installer.getSessionInfo(session) != null, "failed launch does not abandon active session");
        check(f.coordinator.confirmationToken() == token, "same original token remains usable for explicit retry");
        check(f.pm.installer.commits == 1, "confirmation retry does not commit another install");
        check(!f.coordinator.installationResultBoundary(), "launch failure is not a completed system return");
    }

    private static Fixture pendingConfirmation() throws Exception {
        Fixture f = started();
        f.deliver(f.callbackIntent(-1).putExtra("android.intent.extra.INTENT", new Intent("android.install.CONFIRM")));
        return f;
    }
    private static void loseToken(Fixture f) {
        SystemConfirmationToken.cancel(f.context, f.coordinator.requestNonce(), f.prefs.getString("confirmation", null));
    }
    private static void expiredConfirmationReleases() throws Exception {
        Fixture f = pendingConfirmation(); loseToken(f); f.pm.installer.finishSession(f.session);
        f.removePreparedSource(); f.recreate(); f.coordinator.reconcile();
        check(!f.coordinator.hasActiveRequest() && !f.coordinator.hasPendingResult(), "dead request releases business lock");
        check(!f.coordinator.confirmationUnavailable(), "no stale recovery flag after cleanup");
        check(f.coordinator.requiresSelectedFileInstallation(f.pair), "original picked-file intent preserved for explicit retry");
        check(f.pm.installer.commits == 1 && f.pm.installer.abandons == 0, "expiry neither commits nor abandons");
        check(SyncRuntime.selections == 0 && !f.prefs.getBoolean("installedVerified", false), "expiry does not pretend installation succeeded");
        f.recreate(); f.coordinator.reconcile();
        check(!f.coordinator.hasPendingResult(), "released state survives recreation");
        f.assertMetadataOnly();
    }
    private static void expiredConfirmationKeepsLive() throws Exception {
        Fixture f = pendingConfirmation(); loseToken(f);
        try { f.coordinator.confirmationToken(); throw new AssertionError("missing token must fail"); }
        catch (java.io.IOException expected) { }
        check(f.coordinator.hasActiveRequest() && f.coordinator.confirmationUnavailable(), "live session preserved with explicit recovery state");
        check(!f.coordinator.hasConfirmation(), "unusable token is not repeatedly launchable");
        check(f.pm.installer.getSessionInfo(f.session) != null, "live session not abandoned");
        f.recreate(); f.coordinator.reconcile();
        check(f.coordinator.confirmationUnavailable(), "recovery survives restart");
        check(f.pm.installer.commits == 1 && f.pm.installer.abandons == 0, "no implicit operation on live session");
        f.pm.installer.finishSession(f.session); f.coordinator.reconcile();
        check(!f.coordinator.hasActiveRequest(), "later proven disappearance releases dead lock");
        f.assertMetadataOnly();
    }
    private static void validConfirmationKeepsCheckpoint() throws Exception {
        Fixture f = pendingConfirmation(); f.pm.installer.finishSession(f.session); f.recreate(); f.coordinator.reconcile();
        check(f.coordinator.hasActiveRequest() && f.coordinator.hasConfirmation(), "valid token remains current despite absent session");
        check(!f.coordinator.confirmationUnavailable(), "valid token not mislabeled expired");
        check(f.pm.installer.abandons == 0 && f.pm.installer.commits == 1, "valid token causes no extra operation");
    }
    private static void unknownSessionKeepsCheckpoint() throws Exception {
        Fixture f = pendingConfirmation(); loseToken(f); f.prefs.edit().remove("session").commit();
        f.coordinator.reconcile();
        check(f.coordinator.hasActiveRequest() && f.coordinator.confirmationUnavailable(), "unknown session cannot prove cancellation");
        check(f.pm.installer.abandons == 0 && f.pm.installer.commits == 1, "unknown session never touched");
    }
    private static void uninstallTokenKeepsCheckpoint() throws Exception {
        Fixture f = pendingConfirmation(); loseToken(f); f.pm.publishOrdinary();
        f.pm.game.applicationInfo.sourceDir = f.source.toFile().getCanonicalPath();
        f.prefs.edit().putString("operation", "uninstall").commit();
        f.coordinator.reconcile();
        check(f.coordinator.hasActiveRequest() && f.coordinator.confirmationUnavailable(), "uninstall requires explicit recovery");
        check(f.pm.installer.abandons == 0 && f.pm.installer.commits == 1, "uninstall is not auto cancelled or resubmitted");
    }

    private static void disabledConfirmation() throws Exception {
        Fixture f = pendingConfirmation(); GamePreparationService.forbidReload = false;
        android.content.pm.ResolveInfo match = new android.content.pm.ResolveInfo();
        match.activityInfo = new android.content.pm.ActivityInfo();
        match.activityInfo.name = "DisabledInstaller"; match.activityInfo.packageName = "android.installer";
        match.activityInfo.applicationInfo = f.pm.main.applicationInfo;
        f.pm.confirmationMatches = List.of(match); f.pm.componentEnabled = 2;
        try { f.coordinator.confirmationToken(); throw new AssertionError("disabled component must fail"); }
        catch (java.io.IOException expected) { check(expected.getMessage().contains("停用"), "disabled reason is actionable"); }
        check(f.coordinator.confirmationUnavailable() && f.coordinator.hasConfirmation(), "blocked launch retains original token for explicit recheck");
        check(f.coordinator.hasActiveRequest() && f.pm.installer.abandons == 0 && f.pm.installer.commits == 1, "preflight neither cancels nor resubmits");
        f.recreate(); f.coordinator.reconcile(); check(f.coordinator.confirmationUnavailable(), "blocked state survives recreation");
        f.pm.componentEnabled = 1;
        check(f.coordinator.confirmationToken() != null && !f.coordinator.confirmationUnavailable(), "explicit retry can observe re-enabled component");
    }
    private static void unknownConfirmationResolver() throws Exception {
        Fixture f = pendingConfirmation(); GamePreparationService.forbidReload = false;
        check(f.coordinator.confirmationToken() != null, "empty visibility query does not falsely reject real token");
    }
    private static void durableConfirmationAttempt() throws Exception {
        Fixture f = pendingConfirmation(); f.coordinator.markConfirmationAttempt();
        f.coordinator.confirmationLaunched(); f.coordinator.confirmationLaunchFailed(); f.recreate();
        check(f.coordinator.automaticConfirmationAttempted(), "failed platform call cannot automatically loop after recreation");
        check(f.coordinator.hasConfirmation(), "explicit retry still has original token");
        check(f.pm.installer.commits == 1 && f.pm.installer.abandons == 0, "attempt budget never re-commits");
        f.coordinator.reconcile(); check(f.coordinator.automaticConfirmationAttempted(), "ordinary reconcile cannot reset attempt budget");
    }

    private static void activityReturn() throws Exception {
        Fixture f = started(); f.pm.publishValid();
        check(f.pm.installer.getSessionInfo(f.session) != null, "session deliberately still published");
        f.coordinator.reconcile(true); f.assertSuccess();
        check(AndroidDiagnosticLogging.callbacks == 0, "success does not require session callback");
    }

    private static void sessionGoneRestart() throws Exception {
        Fixture f = started(); f.pm.publishValid(); f.pm.installer.finishSession(f.session);
        f.removePreparedSource(); f.recreate();
        check(f.coordinator.hasPendingResult(), "new coordinator reloads durable outstanding request");
        check(f.coordinator.installationResultBoundary(), "gone session recovers missing final callback");
        f.coordinator.reconcile(); f.assertSuccess();
        check(AndroidDiagnosticLogging.callbacks == 0, "cold recovery did not fabricate a callback");
    }

    private static void receiptMismatch() throws Exception {
        Fixture f = started(); f.pm.publishValid(); f.callback(0);
        f.prefs.edit().putString("selectedFileInstallReceipt", "different-selection").commit();
        f.recreate(); f.coordinator.reconcile(true);
        check(!f.coordinator.installationResultBoundary(), "mismatched receipt blocks return boundary");
        check(f.pm.componentReads == 0 && SyncRuntime.selections == 0, "mismatched receipt cannot inspect or select component");
        check(f.coordinator.hasPendingResult(), "mismatched request not silently consumed");
        f.assertMetadataOnly();
    }

    private static void returnedMarkerRestart() throws Exception {
        Fixture f = started(); f.coordinator.reconcile(true);
        check(f.coordinator.hasPendingResult(), "return before PM publication remains pending");
        check(f.prefs.getBoolean("installationReturned", false), "activity return persisted");
        f.recreate(); f.pm.publishValid(); f.removePreparedSource();
        check(f.pm.installer.getSessionInfo(f.session) != null, "fixture retains live session across recreation");
        f.coordinator.reconcile(); f.assertSuccess();
    }

    private static void absentUntilSettled() throws Exception {
        Fixture f = started(); f.callback(0);
        for (int i = 0; i < 3; i++) {
            f.coordinator.reconcile();
            check(f.coordinator.hasPendingResult(), "package absence leaves request pending for publication");
        }
        check(!f.prefs.getBoolean("installedVerified", false), "OS success alone not accepted");
        f.coordinator.settleClosedSystemRequest();
        check(!f.coordinator.hasPendingResult() && "READY".equals(f.coordinator.state()), "explicit settle ends unavailable result");
        check(f.coordinator.requiresSelectedFileInstallation(f.pair), "failed flow retains picked file requirement");
        int probes = f.pm.componentReads;
        f.pm.publishValid(); f.coordinator.reconcile();
        check(f.pm.componentReads == probes && SyncRuntime.selections == 0, "late unrelated publication after settle cannot reopen result");
        f.assertMetadataOnly();
    }

    private static void lateComponent() throws Exception {
        Fixture f = started(); f.pm.publishOrdinary(); f.callback(3);
        f.coordinator.reconcile(); f.coordinator.reconcile();
        check(f.coordinator.hasPendingResult() && SyncRuntime.selections == 0, "ordinary installed package is not own component");
        f.pm.publishValid(); f.coordinator.reconcile(); f.assertSuccess();
    }

    private static void malformedComponent() throws Exception {
        Fixture f = started(); f.pm.publishValid(); f.callback(0);
        f.pm.game.providers[0].exported = false;
        f.coordinator.reconcile();
        check(f.coordinator.hasPendingResult() && SyncRuntime.selections == 0, "malformed provider cannot authorize completion");
        check(AndroidDiagnosticLogging.rejected == 1, "malformed component rejection diagnosed");
        f.pm.publishValid(); f.pm.game.applicationInfo.metaData.put(PREFIX + "BOUND_GAME_VERSION_CODE", "98");
        f.coordinator.reconcile();
        check(f.coordinator.hasPendingResult() && SyncRuntime.selections == 0, "installed component must agree with its own installed version");
        f.pm.publishValid(); f.pm.game.applicationInfo.metaData.remove(PREFIX + "BOUND_NAMESPACE_ID");
        f.coordinator.reconcile();
        check(f.coordinator.hasPendingResult() && SyncRuntime.selections == 0, "missing required metadata also remains pending");
        check(AndroidDiagnosticLogging.rejected == 3, "both security and IO metadata rejections diagnosed");
        f.pm.publishValid(); f.coordinator.reconcile(); f.assertSuccess();
    }

    private static void duplicateResults() throws Exception {
        Fixture f = started(); f.pm.publishValid();
        Intent wrongNonce = f.callbackIntent(0).setAction(InstallCoordinator.CHANGED + ".wrong").putExtra("requestNonce", "wrong");
        f.deliver(wrongNonce);
        f.deliver(f.callbackIntent(0).putExtra("android.content.pm.extra.SESSION_ID", f.session + 5));
        check("INSTALLING".equals(f.coordinator.state()) && f.context.broadcasts == 0, "wrong nonce and session callbacks rejected");
        Intent valid = f.callbackIntent(0); f.deliver(valid); f.deliver(valid);
        f.coordinator.reconcile(); f.assertSuccess();
        int broadcasts = f.context.broadcasts, probes = f.pm.componentReads;
        f.deliver(valid); f.deliver(f.callbackIntent(3)); f.coordinator.reconcile(); f.coordinator.reconcile(true);
        check(f.context.broadcasts == broadcasts, "completed request nonce rejects late callbacks");
        check(SyncRuntime.selections == 1 && f.pm.componentReads == probes, "duplicates and late results never process completion twice");
    }

    private static void completedProof() throws Exception {
        Fixture f = started(); f.pm.publishValid(); f.callback(0); f.removePreparedSource();
        f.coordinator.reconcile(); InstalledBridgeBinding accepted = SyncRuntime.installed; f.assertSuccess();
        check(f.coordinator.acceptedInstalledComponent(accepted), "completed component token accepted");
        check(!f.coordinator.acceptedInstalledComponent(null), "null component never accepted");
        f.recreate();
        check(f.coordinator.acceptedInstalledComponent(accepted), "completion proof survives fresh preferences/context/coordinator");
        int probes = f.pm.componentReads;
        for (int i = 0; i < 4; i++) f.coordinator.reconcile();
        check(f.pm.componentReads == probes && SyncRuntime.selections == 1, "completed ordinary resumes do not rescan or reselect");
        f.pm.game.lastUpdateTime++;
        InstalledBridgeBinding changed = InstalledBridgeResolver.resolve(f.context, GAME);
        check(!f.coordinator.acceptedInstalledComponent(changed), "different installed component token is not completion proof");
        check(!f.coordinator.requiresSelectedFileInstallation(f.pair), "completed receipt prevents picked intent resurrection");
        check(f.coordinator.mayReuseInstalledSelection(), "completed picked receipt can reuse installed selection");
        f.assertMetadataOnly();
    }

    private static void version48Recovery() throws Exception {
        Fixture f = started(); f.pm.publishValid();
        f.prefs.edit().putString("state", "READY").putBoolean("installedVerified", false)
                .remove("nonce").remove("session").remove("confirmation").commit();
        f.removePreparedSource(); f.recreate();
        check(f.coordinator.hasPendingResult(), "code48 returned bound READY request is recoverable");
        f.coordinator.reconcile(); f.assertSuccess();
    }

    private static void sourceMutation() throws Exception {
        Fixture f = fixture(); f.pm.publishValid(); f.register();
        Files.writeString(f.source, "different modified bytes after selection");
        boolean rejected = false;
        try { f.coordinator.requestInstall(GAME); } catch (InstallDiagnostics.Failure expected) { rejected = true; }
        check(rejected && f.pm.installer.commits == 0, "real writer rejects changed selected bytes before commit");
        check(f.pm.installer.abandons == 1, "failed uncommitted session cleaned up");
        f.coordinator.reconcile(true);
        check(f.pm.componentReads == 0 && SyncRuntime.selections == 0, "source failure cannot fall back to old component");
    }

    private static void callbackCreationFailure() throws Exception {
        Fixture f = fixture(); f.pm.publishValid(); f.register();
        android.app.PendingIntent.failBroadcastCreation = true;
        InstallDiagnostics.Failure failure = null;
        try { f.coordinator.requestInstall(GAME); }
        catch (InstallDiagnostics.Failure expected) { failure = expected; }
        finally { android.app.PendingIntent.failBroadcastCreation = false; }
        check(failure != null && failure.stage == InstallDiagnostics.Stage.CALLBACK && !failure.commitAttempted,
                "callback failure remains definitely before commit");
        check(f.pm.installer.commits == 0 && f.pm.installer.abandons == 1, "uncommitted own session abandoned");
        check(!f.coordinator.hasPendingResult() && !f.coordinator.installationResultBoundary(), "uncommitted checkpoint cannot look like returned request");
        check(!f.prefs.contains("installRequestReceipt") && !f.prefs.contains("operation") && !f.prefs.contains("session"),
                "only uncommitted request identity cleared");
        check(f.coordinator.requiresSelectedFileInstallation(f.pair), "picked-file intent survives callback failure");
        f.recreate(); f.coordinator.reconcile(true);
        check(f.pm.componentReads == 0 && SyncRuntime.selections == 0, "restart cannot accept old component after callback creation failed");
        f.assertMetadataOnly();
    }

    private static Fixture externalStarted() throws Exception {
        Fixture f = fixture(); f.register(); f.coordinator.prepareExternalInstall();
        GamePreparationService.forbidReload = true; PackageTrust.allowExternalHashes = true;
        return f;
    }
    private static void externalCheckpoint() throws Exception {
        Fixture f = externalStarted();
        check(f.coordinator.externalInstallPending() && f.coordinator.hasActiveRequest(), "external route retains business lock");
        check(!f.coordinator.canOfferExternalInstaller() && !f.coordinator.hasConfirmation(), "external route cannot spawn a second handoff or token");
        check(f.pm.installer.commits == 0 && f.pm.installer.abandons == 0, "external route does not create a PackageInstaller session");
        String nonce = f.coordinator.requestNonce();
        f.recreate(); f.coordinator.reconcile(true);
        check(f.coordinator.externalInstallPending() && nonce.equals(f.coordinator.requestNonce()), "generic activity return preserves durable external checkpoint");
        check(PackageTrust.hashes == 0 && f.pm.componentReads == 0, "ordinary reconciliation never verifies external bytes");
        try { f.coordinator.settleClosedSystemRequest(); throw new AssertionError("legacy settle must reject external route"); }
        catch (IOException expected) { }
        check(ExternalInstallerHandoff.tickets.contains(nonce), "legacy settle cannot revoke active external share");
    }
    private static void externalClosesOwnSession() throws Exception {
        Fixture f = pendingConfirmation(); GamePreparationService.forbidReload = false;
        Intent late = f.callbackIntent(0); String old = f.coordinator.requestNonce();
        f.coordinator.prepareExternalInstall();
        check(f.pm.installer.abandons == 1 && f.pm.installer.getSessionInfo(f.session) == null, "matching prior session is closed before handoff");
        check(f.coordinator.externalInstallPending() && !old.equals(f.coordinator.requestNonce()), "external request gets separate nonce");
        f.deliver(late);
        check(f.coordinator.externalInstallPending() && f.pm.installer.commits == 1, "late own callback cannot overwrite external checkpoint");
    }
    private static void externalClosesResultSession() throws Exception {
        Fixture f = pendingConfirmation(); GamePreparationService.forbidReload = false;
        f.callback(1); check(f.pm.installer.getSessionInfo(f.session) != null, "RESULT fixture deliberately retains session");
        f.coordinator.prepareExternalInstall();
        check(f.pm.installer.abandons == 1 && f.pm.installer.getSessionInfo(f.session) == null,
                "RESULT state cannot bypass old session closure");
        check(f.coordinator.externalInstallPending(), "closed RESULT may hand off");
    }
    private static void externalRejectsUnknownSession() throws Exception {
        Fixture f = pendingConfirmation(); GamePreparationService.forbidReload = false;
        f.prefs.edit().remove("session").commit();
        try { f.coordinator.prepareExternalInstall(); throw new AssertionError("unknown own session cannot hand off"); }
        catch (IOException expected) { }
        check(ExternalInstallerHandoff.tickets.isEmpty() && ExternalInstallerHandoff.revokes == 1 && f.pm.installer.abandons == 0, "unknown session neither shared nor abandoned");
    }
    private static void externalRejectsWrongSession() throws Exception {
        Fixture f = pendingConfirmation(); GamePreparationService.forbidReload = false;
        f.pm.installer.sessions.get(f.session).params.packageName = "different.game";
        try { f.coordinator.prepareExternalInstall(); throw new AssertionError("wrong session target cannot hand off"); }
        catch (IOException expected) { }
        check(ExternalInstallerHandoff.tickets.isEmpty() && ExternalInstallerHandoff.revokes == 1 && f.pm.installer.abandons == 0, "unrelated session untouched");
    }
    private static void externalRejectsUnownedSession() throws Exception {
        Fixture f = pendingConfirmation(); GamePreparationService.forbidReload = false;
        f.pm.installer.sessions.get(f.session).owned = false;
        try { f.coordinator.prepareExternalInstall(); throw new AssertionError("same-package foreign session cannot be abandoned"); }
        catch (IOException expected) { }
        check(f.pm.installer.abandons == 0 && f.coordinator.hasConfirmation() && ExternalInstallerHandoff.tickets.isEmpty(),
                "ownership proof is required before changing any session");
    }
    private static void externalRejectsActiveSession() throws Exception {
        Fixture f = pendingConfirmation(); GamePreparationService.forbidReload = false;
        f.pm.installer.sessions.get(f.session).active = true;
        try { f.coordinator.prepareExternalInstall(); throw new AssertionError("active installer work cannot be interrupted for handoff"); }
        catch (IOException expected) { }
        check(f.pm.installer.abandons == 0 && f.coordinator.hasConfirmation() && ExternalInstallerHandoff.tickets.isEmpty(),
                "active session remains intact and no external ticket remains");
    }
    private static void externalRejectsSplits() throws Exception {
        Fixture f = pendingConfirmation(); GamePreparationService.forbidReload = false;
        Path split = f.directory.resolve("split.apk"); Files.writeString(split, "selected split");
        GamePreparationService.current = new GamePreparationService.PreparedPair(GAME,
                List.of(f.source.toFile(), split.toFile()), RECEIPT);
        try { f.coordinator.prepareExternalInstall(); throw new AssertionError("split set cannot use single APK handoff"); }
        catch (IOException expected) { check(expected.getMessage().contains("拆分"), "split limitation explained"); }
        check(ExternalInstallerHandoff.prepares == 0 && f.pm.installer.abandons == 0, "split rejection preserves old session");
    }
    private static void externalRejectsRestore() throws Exception {
        Fixture f = pendingConfirmation(); pdsync.android.app.AndroidSyncFacade.unresolvedRestore = true;
        try { f.coordinator.prepareExternalInstall(); throw new AssertionError("unresolved restore must block external replacement"); }
        catch (IOException expected) { check(expected.getMessage().contains("恢复"), "restore blocker explained"); }
        check(ExternalInstallerHandoff.prepares == 0 && f.pm.installer.abandons == 0 && f.coordinator.hasConfirmation(),
                "restore guard runs before preparation reload, share creation, or old-session cancellation");
    }
    private static void externalRejectsUninstall() throws Exception {
        Fixture f = pendingConfirmation(); f.prefs.edit().putString("operation", "uninstall").commit();
        check(!f.coordinator.canOfferExternalInstaller(), "pending uninstall does not offer external replacement");
        try { f.coordinator.prepareExternalInstall(); throw new AssertionError("pending uninstall cannot hand off"); }
        catch (IOException expected) { }
        check(ExternalInstallerHandoff.prepares == 0 && f.pm.installer.abandons == 0, "pending uninstall remains untouched");
    }
    private static void externalStagingFailure() throws Exception {
        Fixture f = pendingConfirmation(); GamePreparationService.forbidReload = false;
        ExternalInstallerHandoff.failPrepare = true;
        try { f.coordinator.prepareExternalInstall(); throw new AssertionError("failed stage cannot hand off"); }
        catch (IOException expected) { }
        check(f.pm.installer.abandons == 0 && f.coordinator.hasConfirmation() && !f.coordinator.externalInstallPending(),
                "failed external staging preserves the still-usable own request");
    }
    private static void externalRejectsMismatch() throws Exception {
        Fixture f = externalStarted();
        f.publishExternal(SELECTED_BYTES); f.pm.game.versionCode = 6;
        f.coordinator.checkExternalInstall();
        check(f.coordinator.externalInstallPending() && SyncRuntime.selections == 0, "old installed version cannot satisfy external request");
        f.publishExternal(SELECTED_BYTES); f.pm.game.fixtureSigner = "f".repeat(64);
        f.coordinator.checkExternalInstall();
        check(f.coordinator.externalInstallPending() && SyncRuntime.selections == 0, "different signer cannot satisfy external request");
        f.publishExternal("old installed bytes".getBytes(StandardCharsets.UTF_8));
        f.coordinator.checkExternalInstall();
        check(f.coordinator.externalInstallPending() && SyncRuntime.selections == 0, "same version signer but different bytes cannot satisfy request");
        f.publishExternal(SELECTED_BYTES);
        Path split = f.directory.resolve("unexpected-installed-split.apk"); Files.writeString(split, "unexpected split");
        f.pm.game.applicationInfo.splitSourceDirs = new String[] { split.toString() };
        f.coordinator.checkExternalInstall();
        check(f.coordinator.externalInstallPending() && SyncRuntime.selections == 0, "full installed APK set is checked including splits");
        check(f.coordinator.requiresSelectedFileInstallation(f.pair), "mismatch preserves picked-file requirement");
        check(ExternalInstallerHandoff.revokes == 0, "mismatch leaves sharing available for completion");
    }
    private static void externalAcceptsExact() throws Exception {
        Fixture f = externalStarted(); String nonce = f.coordinator.requestNonce();
        f.publishExternal(SELECTED_BYTES); f.removePreparedSource(); f.recreate();
        f.coordinator.checkExternalInstall();
        check(!f.coordinator.hasPendingResult() && f.prefs.getBoolean("installedVerified", false), "exact external package completes after restart");
        check(SyncRuntime.selections == 1 && f.coordinator.acceptedInstalledComponent(SyncRuntime.installed), "verified stable binding is selected once");
        check(!f.coordinator.requiresSelectedFileInstallation(f.pair), "only exact proof consumes picked-file intent");
        check(!ExternalInstallerHandoff.tickets.contains(nonce) && ExternalInstallerHandoff.revokes == 1, "success revokes only this sharing ticket");
        check(PackageTrust.hashes == 1 && GamePreparationService.reloads == 2, "external result hashes installed bytes without reloading source");
        f.coordinator.checkExternalInstall();
        check(SyncRuntime.selections == 1 && PackageTrust.hashes == 1, "completed external check is idempotent");
    }
    private static void externalRejectsReplacement() throws Exception {
        Fixture f = externalStarted();
        f.publishExternal(SELECTED_BYTES); PackageInfo expected = f.pm.game;
        f.publishExternal("replacement bytes".getBytes(StandardCharsets.UTF_8)); PackageInfo replacement = f.pm.game;
        f.pm.game = expected; f.pm.gameReads = 0; f.pm.replaceOnGameRead = 2; f.pm.replacement = replacement;
        try { f.coordinator.checkExternalInstall(); } catch (IOException | SecurityException expectedChange) { }
        check(f.coordinator.externalInstallPending() && SyncRuntime.selections == 0,
                "replacement after first PM read cannot bind unverified package");
    }
    private static void externalEndAfterBadComponent() throws Exception {
        Fixture f = externalStarted(); f.publishExternal(SELECTED_BYTES);
        f.pm.game.providers[0].exported = false;
        f.coordinator.endExternalInstallWait();
        check(!f.coordinator.hasActiveRequest() && ExternalInstallerHandoff.tickets.isEmpty(), "explicit end can revoke despite failed component verification");
        check(!f.prefs.getBoolean("installedVerified", false) && f.coordinator.requiresSelectedFileInstallation(f.pair), "failed verification is never reported as success");
        check(f.pm.installer.abandons == 0 && SyncRuntime.selections == 0, "end wait never cancels another installer or binds bad component");
    }
    private static void externalEndAbsent() throws Exception {
        Fixture f = externalStarted(); f.coordinator.endExternalInstallWait();
        check(!f.coordinator.hasActiveRequest() && !f.coordinator.hasPendingResult(), "explicit end releases own wait");
        check(f.coordinator.requiresSelectedFileInstallation(f.pair) && !f.prefs.getBoolean("installedVerified", false), "absent package is not success");
        check(ExternalInstallerHandoff.revokes == 1 && f.pm.installer.abandons == 0, "end only revokes own content sharing");
    }
    private static void externalLaunchFailure() throws Exception {
        Fixture f = externalStarted(); f.publishExternal(SELECTED_BYTES);
        f.coordinator.externalLaunchFailed();
        check(!f.coordinator.hasPendingResult() && ExternalInstallerHandoff.tickets.isEmpty(), "failed chooser revokes and ends only own request");
        check(!f.prefs.getBoolean("installedVerified", false) && SyncRuntime.selections == 0 && PackageTrust.hashes == 0
                && f.pm.componentReads == 0, "failed chooser never probes or accepts an existing matching component");
        check(f.coordinator.requiresSelectedFileInstallation(f.pair), "failed chooser preserves explicit selected-file requirement");
    }

    private static void externalReturnUnlocks() throws Exception {
        Fixture f = externalStarted(); String nonce = f.coordinator.requestNonce();
        f.coordinator.externalActivityReturned(nonce);
        check(!f.coordinator.hasActiveRequest() && !f.coordinator.hasPendingResult(), "return ends business lock without ending installer work");
        check(f.coordinator.externalInstallObservation() && f.coordinator.canOfferExternalInstaller(), "returned observation permits explicit retry");
        f.coordinator.checkExternalInstall();
        check(ExternalInstallerHandoff.revokes == 0 && ExternalInstallerHandoff.tickets.contains(nonce), "absent result keeps readable snapshot");
        check(!f.prefs.getBoolean("installedVerified", false) && f.coordinator.requiresSelectedFileInstallation(f.pair), "absence is not success and selected-file intent survives");
        f.coordinator.assertCanPrepare();
        check(f.pm.installer.commits == 0 && f.pm.installer.abandons == 0 && SyncRuntime.selections == 0, "return never commits cancels or selects");
    }
    private static void externalDelayedResult() throws Exception {
        Fixture f = externalStarted(); String nonce = f.coordinator.requestNonce();
        f.coordinator.externalActivityReturned(nonce); f.coordinator.checkExternalInstall(); f.recreate();
        check(f.coordinator.externalInstallObservation() && !f.coordinator.hasActiveRequest(), "cold restart preserves unlocked observation");
        f.publishExternal(SELECTED_BYTES); f.coordinator.checkExternalInstall();
        check(f.prefs.getBoolean("installedVerified", false) && SyncRuntime.selections == 1, "late exact bytes are verified and selected");
        check(!f.coordinator.requiresSelectedFileInstallation(f.pair) && !ExternalInstallerHandoff.tickets.contains(nonce), "only verified completion retires receipt and share");
        f.coordinator.checkExternalInstall(); f.coordinator.externalActivityReturned(nonce);
        check(SyncRuntime.selections == 1 && !f.coordinator.externalInstallObservation(), "duplicate completion cannot reopen observation");
    }
    private static void externalReturnedMismatch() throws Exception {
        Fixture f = externalStarted(); f.coordinator.externalActivityReturned(f.coordinator.requestNonce());
        f.publishExternal("wrong content".getBytes(StandardCharsets.UTF_8)); f.coordinator.checkExternalInstall();
        check(!f.coordinator.hasActiveRequest() && f.coordinator.externalInstallObservation(), "mismatch does not recreate business lock");
        check(!f.prefs.getBoolean("installedVerified", false) && SyncRuntime.selections == 0 && ExternalInstallerHandoff.revokes == 0, "mismatch does not verify or revoke");
        GamePreparationService.current = new GamePreparationService.PreparedPair(GAME, f.pair.gameApks, "new-receipt");
        f.publishExternal(SELECTED_BYTES); f.coordinator.checkExternalInstall();
        check(SyncRuntime.selections == 0, "receipt replacement prevents old exact package satisfying new source");
    }
    private static void externalStaleReturn() throws Exception {
        Fixture f = externalStarted(); String old = f.coordinator.requestNonce();
        int oldCode = f.coordinator.externalActivityRequestCode();
        f.coordinator.externalActivityReturned(old); GamePreparationService.forbidReload = false;
        f.coordinator.prepareExternalInstall(); String current = f.coordinator.requestNonce();
        check(f.coordinator.externalActivityRequestCode() != oldCode, "new Activity request code rejects a late result from old manually-ended UI");
        check(!current.equals(old), "each new launch owns a fresh nonce");
        f.coordinator.externalActivityReturned(old);
        check(f.coordinator.externalInstallPending() && f.coordinator.hasActiveRequest(), "stale return cannot unlock a newer handoff");
        check(ExternalInstallerHandoff.tickets.contains(old) && ExternalInstallerHandoff.tickets.contains(current), "new request never revokes old background consumer");
    }
    private static void externalSuperseded() throws Exception {
        Fixture f = externalStarted(); String old = f.coordinator.requestNonce();
        f.coordinator.externalActivityReturned(old); f.coordinator.supersedeExternalObservation();
        f.publishExternal(SELECTED_BYTES); f.coordinator.checkExternalInstall();
        check(!f.coordinator.externalInstallObservation() && SyncRuntime.selections == 0, "explicit new mode/source invalidates old observation");
        check(ExternalInstallerHandoff.tickets.contains(old) && ExternalInstallerHandoff.revokes == 0, "source choice leaves external work resources untouched");
    }
    private static void externalRetryFailure() throws Exception {
        Fixture f = externalStarted(); String old = f.coordinator.requestNonce();
        f.coordinator.externalActivityReturned(old); GamePreparationService.forbidReload = false;
        f.coordinator.prepareExternalInstall(); f.coordinator.externalLaunchFailed();
        check(ExternalInstallerHandoff.tickets.contains(old) && ExternalInstallerHandoff.tickets.size() == 1, "failed newer launch revokes only its own never-launched snapshot");
        check(!f.coordinator.hasActiveRequest() && !f.prefs.getBoolean("installedVerified", false), "failed retry remains unlocked and unverified");
    }

    private static Fixture fixture() throws Exception { return new Fixture(root.resolve("case-" + cases)); }
    private static Fixture started() throws Exception { Fixture f = fixture(); f.register(); f.request(); return f; }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void run(String name, Action action) throws Exception {
        cases++; action.run(); System.out.println("PASS " + name);
    }
    private interface Action { void run() throws Exception; }

    private static final class Fixture {
        final Path directory, source;
        final Packages pm = new Packages();
        final GamePreparationService.PreparedPair pair;
        TestContext context;
        DiskPreferences prefs;
        InstallCoordinator coordinator;
        int session;
        String nonce;
        Fixture(Path directory) throws Exception {
            this.directory = directory; Files.createDirectories(directory);
            source = directory.resolve("selected.apk"); Files.write(source, SELECTED_BYTES);
            pair = new GamePreparationService.PreparedPair(GAME, List.of(source.toFile()), RECEIPT);
            GamePreparationService.current = pair; GamePreparationService.reloads = 0; GamePreparationService.forbidReload = false;
            SyncRuntime.selections = 0; SyncRuntime.installed = null;
            PackageTrust.hashes = 0; PackageTrust.allowExternalHashes = false; ExternalInstallerHandoff.reset();
            pdsync.android.app.AndroidSyncFacade.unresolvedRestore = false;
            AndroidDiagnosticLogging.rejected = AndroidDiagnosticLogging.callbacks = 0;
            android.app.PendingIntent.failBroadcastCreation = false;
            recreate();
        }
        void recreate() throws Exception {
            context = new TestContext(directory, pm); prefs = context.preferences;
            coordinator = new InstallCoordinator(context);
        }
        void register() throws Exception { coordinator.registerPrepared(pair, true); }
        void request() throws Exception {
            coordinator.requestInstall(GAME); session = prefs.getInt("session", -1); nonce = coordinator.requestNonce();
            check(session >= 0 && !nonce.isEmpty(), "real request checkpoint persisted");
            GamePreparationService.forbidReload = true;
        }
        Intent callbackIntent(int status) {
            return new Intent(InstallCoordinator.CHANGED + "." + nonce).putExtra("requestNonce", nonce)
                    .putExtra("android.content.pm.extra.SESSION_ID", session).putExtra("android.content.pm.extra.STATUS", status);
        }
        void deliver(Intent intent) { new InstallResultReceiver().onReceive(context, intent); }
        void callback(int status) { deliver(callbackIntent(status)); }
        void removePreparedSource() throws Exception { Files.delete(source); }
        void publishExternal(byte[] bytes) throws Exception {
            pm.publishValid(); pm.game.versionCode = 7; pm.game.versionName = "installed-v7";
            pm.game.fixtureSigner = pair.signingCertificateSha256;
            pm.game.applicationInfo.metaData.put(PREFIX + "BOUND_GAME_VERSION_CODE", "7");
            pm.game.applicationInfo.metaData.put(PREFIX + "BOUND_GAME_VERSION_NAME", "installed-v7");
            Path installed = directory.resolve("installed-" + java.util.UUID.randomUUID() + ".apk"); Files.write(installed, bytes);
            pm.game.applicationInfo.sourceDir = installed.toFile().getCanonicalPath();
        }
        void assertSuccess() throws Exception {
            check("READY".equals(coordinator.state()) && !coordinator.hasPendingResult(), "accepted component settles request");
            check(prefs.getBoolean("installedVerified", false) && SyncRuntime.selections == 1, "valid component accepted exactly once");
            check(RECEIPT.equals(prefs.getString("completedFileInstallReceipt", "")), "completed picked receipt persisted");
            check(!prefs.contains("selectedFileInstallReceipt") && !prefs.contains("installRequestReceipt"), "pending receipt consumed on success");
            check(!prefs.contains("nonce") && !prefs.contains("session"), "request identity cleared on success");
            check(coordinator.acceptedInstalledComponent(SyncRuntime.installed), "metadata proof is reusable for accepted component");
            assertMetadataOnly();
        }
        void assertMetadataOnly() {
            check(PackageTrust.hashes == 0, "no installed APK hashing");
            check(GamePreparationService.reloads == 2, "one registration and one request reload, none at result");
        }
    }

    private static final class Packages extends PackageManager {
        final PackageInstaller installer = new PackageInstaller();
        final PackageInfo main = packageInfo(MAIN, 10001, 49);
        PackageInfo game;
        PackageInfo replacement;
        int componentReads, gameReads, replaceOnGameRead = -1;
        void publishOrdinary() { game = packageInfo(GAME, 10199, 99); }
        void publishValid() {
            publishOrdinary();
            Bundle meta = new Bundle();
            meta.put(PREFIX + "BRIDGE_MODE", "embedded-v1");
            meta.put(PREFIX + "BOUND_GAME_PACKAGE", GAME);
            meta.put(PREFIX + "BOUND_GAME_VERSION_CODE", "99");
            meta.put(PREFIX + "BOUND_GAME_VERSION_NAME", "installed-v99");
            meta.put(PREFIX + "BOUND_ORIGIN_IDENTITY", INSTALLED_ORIGIN);
            meta.put(PREFIX + "BOUND_NAMESPACE_ID", "android-" + INSTALLED_ORIGIN);
            meta.put(PREFIX + "BOUND_SOURCE_APK_SHA256", INSTALLED_SOURCE);
            meta.put(PREFIX + "BRIDGE_RUNTIME_VERSION", "1");
            meta.put(PREFIX + "BRIDGE_ACCESS_PROTOCOL", "1");
            game.applicationInfo.metaData = meta;
            ProviderInfo provider = new ProviderInfo();
            provider.packageName = GAME; provider.name = InstalledBridgeResolver.PROVIDER;
            provider.authority = "io.github.xyz1001.pdsync.embedded.g" + INSTALLED_ORIGIN.substring(0, 32) + ".files";
            provider.processName = GAME + ":pdsync_bridge"; provider.exported = true;
            provider.applicationInfo = game.applicationInfo; game.providers = new ProviderInfo[] { provider };
        }
        private static PackageInfo packageInfo(String name, int uid, long version) {
            PackageInfo p = new PackageInfo(); p.packageName = name; p.versionCode = version;
            p.versionName = "installed-v" + version; p.fixtureSigner = "e".repeat(64);
            p.firstInstallTime = 100; p.lastUpdateTime = 200;
            p.applicationInfo = new ApplicationInfo(); p.applicationInfo.packageName = name; p.applicationInfo.uid = uid;
            // These installed paths deliberately do not exist: only PM metadata may be consulted.
            p.applicationInfo.sourceDir = "/unreadable-installed-apks/" + name + "/base.apk";
            p.applicationInfo.dataDir = "/data/user/0/" + name; return p;
        }
        @Override public PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
            if (GAME.equals(name) && ++gameReads == replaceOnGameRead) game = replacement;
            if (GAME.equals(name) && (flags & GET_PROVIDERS) != 0) componentReads++;
            if (MAIN.equals(name)) return main;
            if (GAME.equals(name) && game != null) return game;
            throw new NameNotFoundException();
        }
        @Override public String[] getPackagesForUid(int uid) {
            if (uid == main.applicationInfo.uid) return new String[] { MAIN };
            return game != null && uid == game.applicationInfo.uid ? new String[] { GAME } : null;
        }
        @Override public ProviderInfo resolveContentProvider(String authority, int flags) {
            if (game == null || game.providers == null) return null;
            for (ProviderInfo p : game.providers) if (authority.equals(p.authority)) return p;
            return null;
        }
        @Override public PackageInstaller getPackageInstaller() { return installer; }
    }

    private static final class TestContext extends Context {
        final Path directory;
        final Packages pm;
        final DiskPreferences preferences;
        int broadcasts;
        TestContext(Path directory, Packages pm) throws IOException {
            this.directory = directory; this.pm = pm; preferences = new DiskPreferences(directory.resolve("checkpoint.properties"));
        }
        @Override public PackageManager getPackageManager() { return pm; }
        @Override public String getPackageName() { return MAIN; }
        @Override public File getFilesDir() { return directory.toFile(); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            check(InstallCoordinator.PREFS.equals(name), "production checkpoint preference name"); return preferences;
        }
        @Override public void sendBroadcast(Intent intent) { broadcasts++; }
    }

    /** Disk-backed rather than shared-map preferences so a fresh context really reads committed state. */
    private static final class DiskPreferences implements SharedPreferences {
        final Path path;
        final Properties values = new Properties();
        DiskPreferences(Path path) throws IOException {
            this.path = path;
            if (Files.exists(path)) try (InputStream input = Files.newInputStream(path)) { values.load(input); }
        }
        @Override public String getString(String key, String fallback) { return values.getProperty(key, fallback); }
        @Override public int getInt(String key, int fallback) { return contains(key) ? Integer.parseInt(values.getProperty(key)) : fallback; }
        @Override public long getLong(String key, long fallback) { return contains(key) ? Long.parseLong(values.getProperty(key)) : fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { return contains(key) ? Boolean.parseBoolean(values.getProperty(key)) : fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() {
            return new Editor() {
                final Map<String, String> updates = new HashMap<>();
                final Set<String> removals = new HashSet<>();
                @Override public Editor putString(String key, String value) { updates.put(key, value); removals.remove(key); return this; }
                @Override public Editor putInt(String key, int value) { return putString(key, Integer.toString(value)); }
                @Override public Editor putLong(String key, long value) { return putString(key, Long.toString(value)); }
                @Override public Editor putBoolean(String key, boolean value) { return putString(key, Boolean.toString(value)); }
                @Override public Editor remove(String key) { updates.remove(key); removals.add(key); return this; }
                @Override public boolean commit() {
                    removals.forEach(values::remove); values.putAll(updates);
                    try (OutputStream output = Files.newOutputStream(path)) { values.store(output, "Host install checkpoint fixture"); return true; }
                    catch (IOException failure) { throw new AssertionError("Cannot persist test preferences", failure); }
                }
            };
        }
    }
}
