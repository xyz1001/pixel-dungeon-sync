package pdsync;

/** Closed transport evidence. Never exports exception messages or arbitrary class names. */
public enum TransportFailureKind {
    TIMEOUT, DNS, CONNECT, NO_ROUTE, TLS, EOF, PROTOCOL, SOCKET, INTERRUPTED, IO, OTHER;

    public static TransportFailureKind of(Throwable error) {
        if (error instanceof java.net.SocketTimeoutException) return TIMEOUT;
        if (error instanceof java.net.UnknownHostException) return DNS;
        if (error instanceof java.net.ConnectException) return CONNECT;
        if (error instanceof java.net.NoRouteToHostException) return NO_ROUTE;
        if (error instanceof javax.net.ssl.SSLException) return TLS;
        if (error instanceof java.io.EOFException) return EOF;
        if (error instanceof java.net.ProtocolException) return PROTOCOL;
        if (error instanceof java.net.SocketException) return SOCKET;
        if (error instanceof java.io.InterruptedIOException) return INTERRUPTED;
        if (error instanceof java.io.IOException) return IO;
        return OTHER;
    }

    public enum Detail {
        TLS_HANDSHAKE, TLS_PROTOCOL, TLS_PEER_VERIFY, TLS_GENERIC,
        CERT_PATH, CERT_EXPIRED, CERT_NOT_YET_VALID, CERT_OTHER,
        TIMEOUT, DNS, CONNECT, NO_ROUTE, EOF, PROTOCOL, SOCKET, INTERRUPTED, IO, OTHER
    }

    public enum MessagePattern { PEER_CLOSED, RESET, READ_ERROR, HANDSHAKE, PROTOCOL, UNKNOWN }

    /** A bounded local inspection; only the fixed category escapes, never the original text. */
    public static MessagePattern messagePattern(Throwable error) {
        if (!(error instanceof java.io.IOException)) return MessagePattern.UNKNOWN;
        String raw;
        try { raw = error.getMessage(); }
        catch (RuntimeException unavailable) { return MessagePattern.UNKNOWN; }
        if (raw == null) return MessagePattern.UNKNOWN;
        String text = raw.substring(0, Math.min(raw.length(), 2048)).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("connection reset") || text.contains("econnreset")) return MessagePattern.RESET;
        if (text.contains("peer closed") || text.contains("connection closed by peer")
                || text.contains("unexpected end of stream")) return MessagePattern.PEER_CLOSED;
        if (text.contains("ssl_read") || text.contains("read error")) return MessagePattern.READ_ERROR;
        if (text.contains("handshake") || text.contains("ssl_do_handshake")) return MessagePattern.HANDSHAKE;
        if (text.contains("wrong version number") || text.contains("protocol version")) return MessagePattern.PROTOCOL;
        return MessagePattern.UNKNOWN;
    }

    public static Detail detail(Throwable error) {
        if (error instanceof javax.net.ssl.SSLHandshakeException) return Detail.TLS_HANDSHAKE;
        if (error instanceof javax.net.ssl.SSLProtocolException) return Detail.TLS_PROTOCOL;
        if (error instanceof javax.net.ssl.SSLPeerUnverifiedException) return Detail.TLS_PEER_VERIFY;
        if (error instanceof javax.net.ssl.SSLException) return Detail.TLS_GENERIC;
        if (error instanceof java.security.cert.CertPathValidatorException) return Detail.CERT_PATH;
        if (error instanceof java.security.cert.CertificateExpiredException) return Detail.CERT_EXPIRED;
        if (error instanceof java.security.cert.CertificateNotYetValidException) return Detail.CERT_NOT_YET_VALID;
        if (error instanceof java.security.cert.CertificateException) return Detail.CERT_OTHER;
        return Detail.valueOf(of(error).name());
    }

    /** Bounded traversal also terminates for cyclic cause chains. */
    public static TransportFailureKind deepest(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < 8 && current != null && current.getCause() != null; depth++) {
            current = current.getCause();
        }
        return of(current);
    }
}
