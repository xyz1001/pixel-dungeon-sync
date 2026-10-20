package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

/** Only these short preparation phases belong in the normal setup UI. */
public final class InstallUiCopy {
    public static final pdsync.i18n.UiText PREPARING = AndroidMessages.INSTALL_UI_COPY_01;
    public static final pdsync.i18n.UiText STARTING = AndroidMessages.INSTALL_RESULT_RECEIVER_02;
    private InstallUiCopy() { }
    public static String uninstallWarning(String reason) {
        String value = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
        String why = value.contains("signing certificate") || value.contains("signer")
                ? AndroidMessages.INSTALL_UI_COPY_02.text()
                : value.contains("shared") || value.contains("uid")
                ? AndroidMessages.INSTALL_UI_COPY_03.text()
                : value.contains("newer") || value.contains("downgrade") || value.contains("version")
                ? AndroidMessages.INSTALL_UI_COPY_04.text()
                : AndroidMessages.INSTALL_UI_COPY_05.text();
        return why + AndroidMessages.INSTALL_UI_COPY_06.text();
    }
}
