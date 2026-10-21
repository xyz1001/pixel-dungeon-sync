package pdsync;

/** Closed transport evidence. Never reads exception messages or arbitrary class names. */
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

    /** Bounded traversal also terminates for cyclic cause chains. */
    public static TransportFailureKind deepest(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < 8 && current != null && current.getCause() != null; depth++) {
            current = current.getCause();
        }
        return of(current);
    }
}
