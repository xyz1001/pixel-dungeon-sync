package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Minimal appreciation copy; dates are server-confirmed values, never locally extended. */
final class ManualPaymentCopy {
    private ManualPaymentCopy() { }
    static String usernameRemark() { return AndroidMessages.MANUAL_PAYMENT_COPY_01.text(); }
    static String expiry(ManualOrderClient.Entitlement entitlement) {
        if (entitlement == null) return AndroidMessages.MANUAL_PAYMENT_COPY_02.text();
        if (entitlement.expiresMs == null) return AndroidMessages.MANUAL_PAYMENT_COPY_03.text();
        return AndroidMessages.MANUAL_PAYMENT_COPY_04.text() + new SimpleDateFormat("yyyy-MM-dd HH:mm", pdsync.i18n.UiLocale.formattingLocale())
                .format(new Date(entitlement.expiresMs));
    }
}
