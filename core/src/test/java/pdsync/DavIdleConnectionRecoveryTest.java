package pdsync;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import javax.net.SocketFactory;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Network-generation fault injection; no device, real account or external server. */
public final class DavIdleConnectionRecoveryTest {
 static final String ID="11111111-1111-4111-8111-111111111111";
 static final String ACTIVE="22222222-2222-4222-8222-222222222222";
 static final class EpochFactory extends SocketFactory {
  final AtomicInteger epoch=new AtomicInteger(), created=new AtomicInteger();
  final class EpochSocket extends Socket {
   final int born=epoch.get(); volatile boolean protectedActive;
   void valid() throws SocketException {if(!protectedActive && born!=epoch.get())throw new SocketException("synthetic expired generation");}
   @Override public InputStream getInputStream() throws IOException {return new FilterInputStream(super.getInputStream()){
    @Override public int read() throws IOException{valid();return in.read();}
    @Override public int read(byte[] b,int o,int n)throws IOException{valid();return in.read(b,o,n);}
   };}
   @Override public OutputStream getOutputStream() throws IOException{return new FilterOutputStream(super.getOutputStream()){
    @Override public void write(int b)throws IOException{valid();out.write(b);}
    @Override public void write(byte[] b,int o,int n)throws IOException{valid();out.write(b,o,n);}
   };}
  }
  @Override public Socket createSocket(){created.incrementAndGet();return new EpochSocket();}
  @Override public Socket createSocket(String h,int p)throws IOException{Socket s=createSocket();s.connect(new InetSocketAddress(h,p));return s;}
  @Override public Socket createSocket(InetAddress h,int p)throws IOException{return createSocket(h.getHostAddress(),p);}
  @Override public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{Socket s=createSocket();s.bind(new InetSocketAddress(l,lp));s.connect(new InetSocketAddress(h,p));return s;}
  @Override public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{return createSocket(h.getHostAddress(),p,l,lp);}
 }
 static AppendOnlyWebDav store(MockWebServer s,OkHttpClient c){return new AppendOnlyWebDav(s.url("/dav/").toString(),"synthetic","probe","test","synthetic-only",c,()->{});}
 static void read(AppendOnlyWebDav dav){assertArrayEquals(new byte[]{'o','k'},dav.readCommit(ID));}
 static void stop(OkHttpClient c){c.connectionPool().evictAll();c.dispatcher().executorService().shutdown();}

 @Test void oneAndMultipleStaleConnectionsDoNotPoisonNextExplicitReadOrWrite()throws Exception{
  for(int size:new int[]{1,4})for(boolean write:new boolean[]{false,true}){
   EpochFactory f=new EpochFactory();ExecutorService workers=Executors.newFixedThreadPool(size);
   OkHttpClient c=null;
   try(MockWebServer s=new MockWebServer()){
    CountDownLatch arrived=new CountDownLatch(size);
    s.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public MockResponse dispatch(RecordedRequest r)throws InterruptedException{
     arrived.countDown();assertTrue(arrived.await(5,TimeUnit.SECONDS));return new MockResponse().setBody("ok");}});
    InetAddress loop=InetAddress.getByName("127.0.0.1");s.start(loop,0);
    c=new OkHttpClient.Builder().dns(h->Collections.singletonList(loop)).socketFactory(f).build();
    AppendOnlyWebDav dav=store(s,c);List<Future<?>> warm=new ArrayList<>();
    for(int i=0;i<size;i++)warm.add(workers.submit(()->read(dav)));for(Future<?> w:warm)w.get(10,TimeUnit.SECONDS);
    assertEquals(size,c.connectionPool().idleConnectionCount());f.epoch.incrementAndGet();
    if(write) assertThrows(IOException.class,()->dav.writeObject("ab".repeat(32),new byte[]{1}));
    else assertThrows(IOException.class,()->read(dav));
    assertEquals(size,s.getRequestCount(),"failed read/write must not replay or reach server");
    assertEquals(size,f.created.get(),"no automatic reconnect");
    assertEquals(0,c.connectionPool().connectionCount(),"all stale idle connections removed");
    read(dav);assertEquals(size+1,s.getRequestCount());assertEquals(size+1,f.created.get());
    read(dav);assertEquals(size+1,f.created.get(),"normal reuse retained");
   }finally{workers.shutdownNow();if(c!=null)stop(c);}
  }
 }

 @Test void cleanupDoesNotCloseAnActiveRequest()throws Exception{
  EpochFactory f=new EpochFactory();ExecutorService worker=Executors.newSingleThreadExecutor();
  CountDownLatch active=new CountDownLatch(1),release=new CountDownLatch(1);OkHttpClient c=null;
  try(MockWebServer s=new MockWebServer()){
   s.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public MockResponse dispatch(RecordedRequest r)throws InterruptedException{
    if(r.getPath().contains(ACTIVE)){active.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return new MockResponse().setBody("ok");}});
   InetAddress loop=InetAddress.getByName("127.0.0.1");s.start(loop,0);
   c=new OkHttpClient.Builder().dns(h->Collections.singletonList(loop)).socketFactory(f).eventListener(new okhttp3.EventListener(){
    @Override public void connectionAcquired(Call call,Connection connection){if(call.request().url().encodedPath().contains(ACTIVE))((EpochFactory.EpochSocket)connection.socket()).protectedActive=true;}
   }).build();
   AppendOnlyWebDav dav=store(s,c);
   Future<byte[]> pending=worker.submit(()->dav.readCommit(ACTIVE));assertTrue(active.await(5,TimeUnit.SECONDS));
   read(dav);assertEquals(2,c.connectionPool().connectionCount());assertEquals(1,c.connectionPool().idleConnectionCount());
   f.epoch.incrementAndGet();assertThrows(IOException.class,()->read(dav));
   assertEquals(1,c.connectionPool().connectionCount());assertEquals(0,c.connectionPool().idleConnectionCount());
   release.countDown();assertArrayEquals(new byte[]{'o','k'},pending.get(5,TimeUnit.SECONDS));
   read(dav);
  }finally{release.countDown();worker.shutdownNow();if(c!=null)stop(c);}
 }

 @Test void bodySocketAndEofFailuresClearIdleConnectionsWithoutReplay()throws Exception{
  for(boolean eof:new boolean[]{false,true}){
   AtomicBoolean fail=new AtomicBoolean();AtomicInteger attempts=new AtomicInteger();OkHttpClient c=null;
   try(MockWebServer s=new MockWebServer()){
    s.enqueue(new MockResponse().setBody("ok"));s.enqueue(new MockResponse().setBody("ok"));s.start(InetAddress.getByName("127.0.0.1"),0);
    c=new OkHttpClient.Builder().addInterceptor(chain->{attempts.incrementAndGet();
     if(!fail.get())return chain.proceed(chain.request());
     return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(new ResponseBody(){
      @Override public MediaType contentType(){return null;} @Override public long contentLength(){return -1;}
      @Override public okio.BufferedSource source(){return okio.Okio.buffer(new okio.Source(){
       @Override public long read(okio.Buffer sink,long count)throws IOException{if(eof)throw new EOFException("synthetic");throw new SocketException("synthetic");}
       @Override public okio.Timeout timeout(){return okio.Timeout.NONE;} @Override public void close(){}
      });}
     }).build();}).build();
    AppendOnlyWebDav dav=store(s,c);read(dav);assertEquals(1,c.connectionPool().idleConnectionCount());
    fail.set(true);assertThrows(IOException.class,()->read(dav));assertEquals(2,attempts.get());assertEquals(0,c.connectionPool().connectionCount());
    fail.set(false);read(dav);assertEquals(3,attempts.get());assertEquals(2,s.getRequestCount());
   }finally{if(c!=null)stop(c);}
  }
 }

 @Test void cancellationInterruptionTimeoutDnsTlsAndHttpErrorsPreserveIdlePool()throws Exception{
  for(String kind:new String[]{"cancel","interrupt","timeout","dns","tls","io","wrappedTls","wrappedTimeout","wrappedDns","wrappedCancel","http"}){
   AtomicBoolean fail=new AtomicBoolean();OkHttpClient c=null;
   try(MockWebServer s=new MockWebServer()){
    s.enqueue(new MockResponse().setBody("ok"));s.start(InetAddress.getByName("127.0.0.1"),0);
    c=new OkHttpClient.Builder().addInterceptor(chain->{
     if(!fail.get())return chain.proceed(chain.request());
     switch(kind){
      case "cancel":chain.call().cancel();throw new SocketException("synthetic");
      case "interrupt":Thread.currentThread().interrupt();throw new SocketException("synthetic");
      case "timeout":throw new SocketTimeoutException("synthetic");
      case "dns":throw new UnknownHostException("synthetic");
      case "tls":throw new javax.net.ssl.SSLException("synthetic");
      case "io":throw new IOException("synthetic");
      case "wrappedTls":throw new IOException("synthetic",new javax.net.ssl.SSLException(new EOFException("synthetic")));
      case "wrappedTimeout":{IOException e=new SocketTimeoutException("synthetic");e.initCause(new EOFException("synthetic"));throw new IOException("synthetic",e);}
      case "wrappedDns":{IOException e=new UnknownHostException("synthetic");e.initCause(new EOFException("synthetic"));throw new IOException("synthetic",e);}
      case "wrappedCancel":chain.call().cancel();throw new IOException("synthetic",new EOFException("synthetic"));
      default:return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("synthetic").body(ResponseBody.create(new byte[0],null)).build();
     }
    }).build();
    AppendOnlyWebDav dav=store(s,c);read(dav);fail.set(true);
    try{assertThrows(IOException.class,()->read(dav));}finally{if(kind.equals("interrupt"))assertTrue(Thread.interrupted());}
    assertEquals(1,c.connectionPool().idleConnectionCount(),kind);assertEquals(1,s.getRequestCount());
   }finally{Thread.interrupted();if(c!=null)stop(c);}
  }
 }
 @Test void wrappedEofClearsIdlePoolWithoutReplayingRequest() throws Exception {
  AtomicBoolean fail=new AtomicBoolean(); AtomicInteger attempts=new AtomicInteger();
  OkHttpClient c=null;
  try(MockWebServer server=new MockWebServer()) {
   server.enqueue(new MockResponse().setBody("ok")); server.enqueue(new MockResponse().setBody("ok"));
   server.start(InetAddress.getByName("127.0.0.1"),0);
   c=new OkHttpClient.Builder().addInterceptor(chain->{
    attempts.incrementAndGet();
    if(fail.get())throw new IOException("synthetic wrapper",new EOFException("synthetic"));
    return chain.proceed(chain.request());
   }).build();
   AppendOnlyWebDav dav=store(server,c);read(dav);
   assertEquals(1,c.connectionPool().idleConnectionCount());
   fail.set(true);assertThrows(IOException.class,()->read(dav));
   assertEquals(2,attempts.get());assertEquals(0,c.connectionPool().connectionCount());
   fail.set(false);read(dav);assertEquals(3,attempts.get());assertEquals(2,server.getRequestCount());
  } finally {if(c!=null)stop(c);}
 }

}
