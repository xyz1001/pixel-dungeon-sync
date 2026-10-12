package io.github.xyz1001.pdsync.android;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import java.util.Objects;

/** Executes production SDK branches against call-recording doubles, not Android or ART. */
public final class RuntimePlatformCompatibilityChecks {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        int originalSdk = Build.VERSION.SDK_INT;
        try {
            for (int sdk : new int[]{26, 27, 28, 29, 30, 31, 32, 33, 35, 36}) {
                Build.VERSION.SDK_INT = sdk;
                packageIdentity(sdk);
                insets(sdk);
                foreground(sdk);
                confirmation(sdk);
                receiver(sdk);
            }
            // Existing correlated callback/retained-token adversarial checks also run on API26.
            Build.VERSION.SDK_INT = 26;
            MainRuntimeRecoveryChecks.installConfirmation();
        } finally {
            Build.VERSION.SDK_INT = originalSdk;
        }
        System.out.println("Runtime SDK compatibility host checks passed: " + assertions + " assertions, SDK 26/27/28/29/30/31/32/33/35/36.");
        System.out.println("Boundary: host doubles detect wrong SDK branches; Android 8 UI, installer, Binder, ART linkage and services still need device/emulator testing.");
    }

    static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message + " (SDK " + Build.VERSION.SDK_INT + ")");
    }

    static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + " actual=" + actual);
    }

    static void rejected(MainRuntimeRecoveryChecks.Checked call) throws Exception {
        try { call.run(); }
        catch (SecurityException expected) { assertions++; return; }
        throw new AssertionError("Untrusted metadata accepted on SDK " + Build.VERSION.SDK_INT);
    }

    static MainRuntimeRecoveryChecks.TrustFixture fixture() throws Exception {
        // Constructing this existing fixture computes its pin from the platform-selected field.
        int sdk = Build.VERSION.SDK_INT;
        Build.VERSION.SDK_INT = 36;
        MainRuntimeRecoveryChecks.TrustFixture f;
        try { f = new MainRuntimeRecoveryChecks.TrustFixture(); }
        finally { Build.VERSION.SDK_INT = sdk; }
        for (PackageInfo info : new PackageInfo[]{f.main, f.game, f.helper}) {
            info.signatures = info.signingInfo.signatures.clone();
            if (sdk < 28) info.signingInfo = null;
        }
        return f;
    }

    static void packageIdentity(int sdk) throws Exception {
        MainRuntimeRecoveryChecks.TrustFixture valid = fixture();
        int flag = sdk >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        equal(flag, PackageTrust.signingFlags(), "correct signer query for OS");
        check(!PackageTrust.requireBridgeForExchange(valid.context, valid.pair, valid.pin), "bound bridge identity accepted");
        equal(flag | PackageManager.GET_META_DATA, valid.lastFlags, "metadata preserved with signer query");
        equal(valid.pin, PackageTrust.signer(valid.game), "same current signer digest across OS versions");
        valid.game.versionCode = 123;
        valid.game.version = (7L << 32) | 123;
        equal(sdk >= 28 ? valid.game.version : 123L, PackageTrust.versionCode(valid.game), "correct version API");
        if (sdk < 28) {
            valid.game.versionCode = -1;
            equal(0xffffffffL, PackageTrust.versionCode(valid.game), "legacy version preserves unsigned low bits");
        }
        Signature[] detached = PackageTrust.currentSigners(valid.game);
        detached[0] = new Signature(new byte[]{99});
        equal(valid.pin, PackageTrust.signer(valid.game), "returned signer array does not mutate metadata");

        for (Signature[] invalid : new Signature[][]{null, new Signature[0], new Signature[]{null},
                new Signature[]{new Signature(new byte[]{1}), new Signature(new byte[]{2})}}) {
            MainRuntimeRecoveryChecks.TrustFixture f = fixture();
            if (sdk >= 28) f.game.signingInfo.signatures = invalid;
            else f.game.signatures = invalid;
            rejected(() -> PackageTrust.requireBridgeForExchange(f.context, f.pair, f.pin));
        }
        MainRuntimeRecoveryChecks.TrustFixture wrongSigner = fixture();
        Signature[] different = {new Signature(new byte[]{9})};
        if (sdk >= 28) wrongSigner.helper.signingInfo.signatures = different;
        else wrongSigner.helper.signatures = different;
        rejected(() -> PackageTrust.requireBridgeForExchange(wrongSigner.context, wrongSigner.pair, wrongSigner.pin));
        if (sdk >= 28) {
            MainRuntimeRecoveryChecks.TrustFixture missingModern = fixture();
            missingModern.game.signingInfo = null;
            rejected(() -> PackageTrust.signer(missingModern.game)); // No fallback to old/history signer.
            MainRuntimeRecoveryChecks.TrustFixture multiple = fixture();
            multiple.game.signingInfo.multiple = true;
            rejected(() -> PackageTrust.signer(multiple.game));
        }
        MainRuntimeRecoveryChecks.TrustFixture extraUidMember = fixture();
        extraUidMember.sharedMembers = new String[]{"game", "helper", "intruder"};
        rejected(() -> PackageTrust.requireBridgeForExchange(extraUidMember.context, extraUidMember.pair, extraUidMember.pin));
        MainRuntimeRecoveryChecks.TrustFixture wrongBinding = fixture();
        wrongBinding.helper.applicationInfo.metaData.put("io.github.xyz1001.pdsync.BOUND_SOURCE_APK_SHA256", "other");
        rejected(() -> PackageTrust.requireBridgeForExchange(wrongBinding.context, wrongBinding.pair, wrongBinding.pin));
        rejected(() -> PackageTrust.currentSigners(null));
    }

    static void insets(int sdk) {
        View view = new View();
        WindowInsets insets = new WindowInsets(2, 24, 3, 210);
        insets.cutout = new DisplayCutout(7, 30, 1, 0);
        insets.modern = new android.graphics.Insets(9, 32, 11, 230);
        for (int repeat = 0; repeat < 2; repeat++) {
            RuntimePlatformCompat.applyInsets(view, insets, 16, 12, 16);
            equal(16 + (sdk >= 30 ? 9 : sdk >= 28 ? 7 : 2), view.left, "left inset/cutout with base padding");
            equal(12 + (sdk >= 30 ? 32 : sdk >= 28 ? 30 : 24), view.top, "top inset/cutout with base padding");
            equal(16 + (sdk >= 30 ? 11 : 3), view.right, "right inset with base padding");
            equal(16 + (sdk >= 30 ? 230 : 210), view.bottom, "IME inset preserves footer access");
        }
        equal(sdk >= 30 ? 143 : 0, insets.requestedTypes, "modern insets union only on API30+");
        insets.left = insets.top = insets.right = insets.bottom = 0;
        insets.cutout = null;
        insets.modern = new android.graphics.Insets(0, 0, 0, 0);
        RuntimePlatformCompat.applyInsets(view, insets, 16, 12, 16);
        equal(16, view.left, "zero inset restores base padding");
        equal(12, view.top, "zero top inset restores base padding");
        equal(16, view.bottom, "IME dismissal restores base padding");
    }

    static void foreground(int sdk) {
        Service service = new Service();
        Notification notification = new Notification();
        RuntimePlatformCompat.startDataSyncForeground(service, 7301, notification);
        equal(1, service.calls, "exactly one foreground registration");
        equal(7301, service.notificationId, "notification identity preserved");
        check(service.notification == notification, "same notification supplied");
        equal(sdk >= 29 ? 1 : -1, service.foregroundType, "typed foreground only where supported");
    }

    static void confirmation(int sdk) {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams();
        RuntimePlatformCompat.requireInstallConfirmation(params);
        equal(sdk >= 31 ? 1 : 0, params.changes, "explicit install requirement only where supported");
        equal(sdk >= 31 ? PackageInstaller.SessionParams.USER_ACTION_REQUIRED : 0,
                params.requireUserAction, "never requests installation without user action");
        equal(PendingIntent.FLAG_UPDATE_CURRENT | (sdk >= 31 ? PendingIntent.FLAG_MUTABLE : 0),
                AndroidCompat.mutableCallbackFlags(), "mutable system callback on every supported SDK");
    }

    static void receiver(int sdk) throws Exception {
        Context context = fixture().context;
        RuntimePlatformCompat.registerInternalReceiver(context, new BroadcastReceiver() {
            public void onReceive(Context ignored, Intent intent) {}
        }, new IntentFilter("io.github.xyz1001.pdsync.android.INSTALL_CHANGED"));
        equal(1, context.registrations, "one receiver registration");
        equal(sdk >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0, context.receiverFlags, "not-exported flag only where supported");
        equal(sdk >= 33 ? null : PackageTrust.ACCESS_BRIDGE, context.receiverPermission,
                "legacy receiver requires existing signature permission");
    }
}
