package io.github.xyz1001.pdsync.android;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Minimal appreciation copy; dates are server-confirmed values, never locally extended. */
final class ManualPaymentCopy {
    private ManualPaymentCopy() { }
    static String usernameRemark() { return "赞赏时请备注用户名"; }
    static String expiry(ManualOrderClient.Entitlement entitlement) {
        if (entitlement == null) return "到期时间：未获取";
        if (entitlement.expiresMs == null) return "到期时间：暂无";
        return "到期时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(entitlement.expiresMs));
    }
}
