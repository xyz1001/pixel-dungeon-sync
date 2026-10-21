package pdsync;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.TimeUnit;

/** Standalone dependency-level check, also usable on JDK 11; not an Android network simulation. */
public final class TransportReconnectHostChecks {
    static void check(boolean value,String reason) { if(!value) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        check(TransportFailureKind.of(new SocketException("secret"))==TransportFailureKind.SOCKET,"socket kind");
        check(TransportFailureKind.deepest(new IOException("secret",new ConnectException("secret")))==TransportFailureKind.CONNECT,"cause kind");
        IOException a=new IOException("secret"), b=new IOException("secret"); a.initCause(b); b.initCause(a);
        check(TransportFailureKind.deepest(a)==TransportFailureKind.IO,"cyclic causes terminate");
        try(MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
            // Isolate reconnect behavior from dual-stack localhost DNS fallback.
            InetAddress loopback=InetAddress.getByName("127.0.0.1");
            server.start(loopback,0);
            OkHttpClient client=new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .dns(hostname -> java.util.Collections.singletonList(loopback))
                .followRedirects(false).followSslRedirects(false).callTimeout(5,TimeUnit.SECONDS).build();
            Request request=new Request.Builder().url(server.url("/synthetic")).build();
            try { client.newCall(request).execute().close(); throw new AssertionError("disconnect should fail"); }
            catch(IOException expected) { }
            check(server.getRequestCount()==1,"failed GET was replayed");
            try(Response response=client.newCall(request).execute()) { check(response.code()==200,"same client could not recover"); }
            check(server.getRequestCount()==2,"explicit retry count");
            Call canceled=client.newCall(request);canceled.cancel();
            try { canceled.execute(); throw new AssertionError("canceled call succeeded"); } catch(IOException expected) { }
            server.enqueue(new MockResponse().setResponseCode(200));
            try(Response response=client.newCall(request).execute()) { check(response.code()==200,"cancel poisoned client"); }
            check(server.getRequestCount()==3,"cancel issued request");
            client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();
        }
        System.out.println("OkHttp 4.12.0 disconnect, explicit retry, cancellation and bounded evidence checks passed");
    }
}
