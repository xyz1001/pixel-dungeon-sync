package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;

/* loaded from: classes.dex */
final class ConnectionEndpointPolicy {
    private ConnectionEndpointPolicy() {
    }

    static void validate(String str, String str2, String str3) throws IOException {
        if (str.length() > 4096 || str2.length() > 1024 || str3.length() > 4096) {
            throw new IOException(AndroidMessages.CONNECTION_ENDPOINT_POLICY_01.text());
        }
        try {
            URI uri = new URI(str);
            boolean z = "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getRawAuthority() != null && uri.getRawAuthority().matches("127\\.0\\.0\\.1(?::[0-9]{1,5})?");
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !z) || uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IOException(AndroidMessages.CONNECTION_ENDPOINT_POLICY_02.text());
            }
            if (z && (!str2.isEmpty() || !str3.isEmpty())) {
                throw new IOException(AndroidMessages.CONNECTION_ENDPOINT_POLICY_03.text());
            }
            if (str2.indexOf(58) >= 0 || hasUnsafeCharacter(str2) || hasUnsafeCharacter(str3)) {
                throw new IOException(AndroidMessages.CONNECTION_ENDPOINT_POLICY_04.text());
            }
            String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (rawPath.contains("//") || hasUnsafeCharacter(str) || str.indexOf(92) >= 0) {
                throw new IOException(AndroidMessages.CONNECTION_ENDPOINT_POLICY_05.text());
            }
            for (String str4 : rawPath.split("/", -1)) {
                try {
                    String strDecode = URLDecoder.decode(str4.replace("+", "%2B"), "UTF-8");
                    if (strDecode.equals(".") || strDecode.equals("..") || strDecode.indexOf(47) >= 0 || strDecode.indexOf(92) >= 0 || strDecode.indexOf(37) >= 0 || hasUnsafeCharacter(strDecode)) {
                        throw new IOException(AndroidMessages.CONNECTION_ENDPOINT_POLICY_05.text());
                    }
                } catch (Exception unused) {
                    throw new IOException(AndroidMessages.CONNECTION_ENDPOINT_POLICY_06.text());
                }
            }
        } catch (Exception unused2) {
            throw new IOException(AndroidMessages.CONNECTION_ENDPOINT_POLICY_07.text());
        }
    }

    private static boolean hasUnsafeCharacter(String str) {
        for (int i = 0; i < str.length(); i++) {
            if (Character.isISOControl(str.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
