package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/* loaded from: classes.dex */
public final class InstallResultReceiver extends BroadcastReceiver {
    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        synchronized (SystemConfirmationToken.CHECKPOINT_LOCK) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("install-checkpoint-v1", 0);
        String string = sharedPreferences.getString("nonce", "");
        if (string.isEmpty()) {
            return;
        }
        if (("io.github.xyz1001.pdsync.android.INSTALL_CHANGED." + string).equals(intent.getAction()) && string.equals(intent.getStringExtra("requestNonce")) && InstallProgressPolicy.acceptsCallback("uninstall".equals(sharedPreferences.getString("operation", "")), sharedPreferences.getInt("session", -1), intent.getIntExtra("android.content.pm.extra.SESSION_ID", -2), string, intent.getStringExtra("requestNonce"))) {
            int intExtra = intent.getIntExtra("android.content.pm.extra.STATUS", 1);
            AndroidDiagnosticLogging.installerResult(intExtra);
            SharedPreferences.Editor editorPutInt = sharedPreferences.edit().putInt("systemStatus", intExtra);
            if (intExtra == -1) {
                Intent intentNestedIntent = AndroidCompat.nestedIntent(intent, "android.intent.extra.INTENT");
                if (intentNestedIntent == null) {
                    AndroidDiagnosticLogging.confirmationEvent("CONFIRMATION_RETAIN", pdsync.DiagnosticResult.FAILED);
                    editorPutInt.putString("state", "RESULT").putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_RESULT_RECEIVER_01.text()));
                } else {
                    try {
                        editorPutInt.remove("confirmationUnavailable").putString("state", "PENDING_CONFIRMATION").putString("confirmation", SystemConfirmationToken.retain(context, string, intentNestedIntent)).putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_RESULT_RECEIVER_02.text()));
                        AndroidDiagnosticLogging.confirmationEvent("CONFIRMATION_RETAIN", pdsync.DiagnosticResult.SUCCEEDED);
                    } catch (Exception unused) {
                        AndroidDiagnosticLogging.confirmationEvent("CONFIRMATION_RETAIN", pdsync.DiagnosticResult.FAILED);
                        editorPutInt.putBoolean("confirmationUnavailable", true).putString("state", "PENDING_CONFIRMATION").remove("confirmation").putString("message", StoredAndroidText.store(AndroidMessages.INSTALL_RESULT_RECEIVER_03.text()));
                    }
                }
            } else {
                SystemConfirmationToken.cancel(context, string, sharedPreferences.getString("confirmation", null));
                editorPutInt.putString("state", "RESULT").remove("confirmation").putString("message", StoredAndroidText.store(intExtra == 0 ? AndroidMessages.INSTALL_RESULT_RECEIVER_04.text() : AndroidMessages.INSTALL_RESULT_RECEIVER_05.text()));
            }
            if (editorPutInt.commit()) {
                context.sendBroadcast(new Intent("io.github.xyz1001.pdsync.android.INSTALL_CHANGED").setPackage(context.getPackageName()));
            }
        }
    }
    }
}
