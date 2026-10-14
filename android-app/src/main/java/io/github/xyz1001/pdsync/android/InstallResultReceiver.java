package io.github.xyz1001.pdsync.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/* loaded from: classes.dex */
public final class InstallResultReceiver extends BroadcastReceiver {
    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
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
                    editorPutInt.putString("state", "RESULT").putString("message", "系统未返回安装确认界面。");
                } else {
                    try {
                        editorPutInt.putString("state", "PENDING_CONFIRMATION").putString("confirmation", SystemConfirmationToken.retain(context, string, intentNestedIntent)).putString("message", "等待你的 Android 系统确认。点击“继续系统确认”。");
                    } catch (Exception unused) {
                        editorPutInt.putString("state", "PENDING_CONFIRMATION").remove("confirmation").putString("message", "系统确认令牌未能保留。请使用“检查失效系统请求”，不要猜测操作已完成。");
                    }
                }
            } else {
                SystemConfirmationToken.cancel(context, string, sharedPreferences.getString("confirmation", null));
                editorPutInt.putString("state", "RESULT").remove("confirmation").putString("message", intExtra == 0 ? "系统报告操作成功，正在等待应用状态复核。" : "系统操作被取消或未成功，安装包仍然保留。");
            }
            if (editorPutInt.commit()) {
                context.sendBroadcast(new Intent("io.github.xyz1001.pdsync.android.INSTALL_CHANGED").setPackage(context.getPackageName()));
            }
        }
    }
}
