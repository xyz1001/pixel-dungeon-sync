package io.github.xyz1001.pdsync.android;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;

/* loaded from: classes.dex */
final class ConnectionEndpointPolicy {
    private ConnectionEndpointPolicy() {
    }

    static void validate(String str, String str2, String str3) throws IOException {
        if (str.length() > 4096 || str2.length() > 1024 || str3.length() > 4096) {
            throw new IOException("连接信息长度超出安全限制。");
        }
        try {
            URI uri = new URI(str);
            boolean z = "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getRawAuthority() != null && uri.getRawAuthority().matches("127\\.0\\.0\\.1(?::[0-9]{1,5})?");
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !z) || uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IOException("服务器必须使用 HTTPS，且不能含用户名、密码、查询参数或片段。仅匿名 127.0.0.1 本机测试允许 HTTP。");
            }
            if (z && (!str2.isEmpty() || !str3.isEmpty())) {
                throw new IOException("本机 HTTP 测试必须匿名，请将用户名和密码均留空。");
            }
            if (str2.indexOf(58) >= 0 || hasUnsafeCharacter(str2) || hasUnsafeCharacter(str3)) {
                throw new IOException("账号或密码含不支持的控制字符，用户名不能含冒号。");
            }
            String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (rawPath.contains("//") || hasUnsafeCharacter(str) || str.indexOf(92) >= 0) {
                throw new IOException("WebDAV 目录地址含不安全的路径。");
            }
            for (String str4 : rawPath.split("/", -1)) {
                try {
                    String strDecode = URLDecoder.decode(str4.replace("+", "%2B"), "UTF-8");
                    if (strDecode.equals(".") || strDecode.equals("..") || strDecode.indexOf(47) >= 0 || strDecode.indexOf(92) >= 0 || strDecode.indexOf(37) >= 0 || hasUnsafeCharacter(strDecode)) {
                        throw new IOException("WebDAV 目录地址含不安全的路径。");
                    }
                } catch (Exception unused) {
                    throw new IOException("WebDAV 目录地址含无效编码。");
                }
            }
        } catch (Exception unused2) {
            throw new IOException("服务器地址格式无效，请填写不含凭据的 HTTPS 目录地址。");
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
