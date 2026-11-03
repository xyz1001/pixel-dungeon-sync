package pdsync

import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong

/** Per-call evidence for the narrowly permitted DAV read replay. */
internal class DavReadAttemptState {
 @Volatile var connectStarts=0
 @Volatile var acquisitions=0
 @Volatile var requestHeaderStarts=0
 @Volatile var responseHeaderStarts=0
 @Volatile var protocol:Protocol?=null
 fun isReusedHttp11BeforeResponse() = connectStarts==0 && acquisitions==1 &&
  requestHeaderStarts==1 && responseHeaderStarts==0 && protocol==Protocol.HTTP_1_1
}

/** Observation only: preserves the caller's listener and all transport policies. No endpoint data retained. */
object ConnectionDiagnostics {
 private val ids=AtomicLong()
 @JvmStatic fun attach(client:OkHttpClient,target:DiagnosticDetails.NetworkTarget):OkHttpClient {
  val original=client.eventListenerFactory
  return client.newBuilder().eventListenerFactory { call ->
   val delegate=original.create(call) // Preserve factory failures, too.
   val trace=DiagnosticLog.currentTrace()
   Observer(delegate,call.request().tag(DiagnosticDetails.NetworkTarget::class.java)?:target,ids.incrementAndGet(), { result,failure,details ->
    val interrupted=Thread.interrupted()
    try {
     if(trace!=null) trace.record(DiagnosticStage.TRANSPORT,result,failure,details)
     else DiagnosticLog.record(DiagnosticStage.TRANSPORT,result,failure,details)
    } finally {if(interrupted)Thread.currentThread().interrupt()}
    }, readAttempt=call.request().tag(DavReadAttemptState::class.java))
  }.build()
 }

 internal class Observer(private val delegate:EventListener,private val target:DiagnosticDetails.NetworkTarget,
  private val id:Long,private val output:(DiagnosticResult,DiagnosticFailure,DiagnosticDetails)->Unit,
   private val clock:()->Long=System::nanoTime,private val readAttempt:DavReadAttemptState?=null):EventListener() {
  private var stage="CALL_START"
  private var protocol="UNKNOWN"
  private var attempts=0L;private var starts=0L;private var ends=0L;private var acquired=0L;private var headers=0L
  private var reused=false;private var newConnection=false
  private var secureAt:Long?=null;private var secureNanos=0L
  private var started=clock();private var emitted=false
  private var firstFailure:DiagnosticDetails?=null
  private var firstCategory=DiagnosticFailure.NONE
  private fun protocolToken(value:Protocol?)=if(value==Protocol.HTTP_2) "H2" else value?.name?:"UNKNOWN"
  private fun evidence(call:Call,error:IOException?):DiagnosticDetails {
   val b=DiagnosticDetails.builder().networkTarget(target)
    .number(DiagnosticDetails.Field.CONNECTION_CALL_ID,id).connectionStage(stage).connectionProtocol(protocol)
    .connectionRoute(when {reused&&newConnection->"MIXED";reused->"REUSED";attempts>0->"CONNECT_ATTEMPTED";else->"NO_CONNECTION"})
    .number(DiagnosticDetails.Field.CONNECTION_ATTEMPTS,attempts)
    .number(DiagnosticDetails.Field.SECURE_STARTS,starts).number(DiagnosticDetails.Field.SECURE_ENDS,ends)
    .number(DiagnosticDetails.Field.CONNECTION_ACQUISITIONS,acquired).number(DiagnosticDetails.Field.RESPONSE_HEADER_EVENTS,headers)
    .requestDurationMs(((clock()-started)/1_000_000).coerceAtLeast(0))
   if(starts>0)b.number(DiagnosticDetails.Field.SECURE_ELAPSED_MS,((secureNanos+(secureAt?.let{clock()-it}?:0))/1_000_000).coerceAtLeast(0))
   if(error!=null)b.transportFailure(error,call.isCanceled(),Thread.currentThread().isInterrupted())
    .messagePattern(TransportFailureKind.messagePattern(error))
   return b.build()
  }
  private fun failed(call:Call,error:IOException) {
   if(firstFailure==null){firstFailure=evidence(call,error);firstCategory=DiagnosticLog.classify(error)}
  }
  private fun finish(call:Call,error:IOException?) {
   if(emitted)return
   emitted=true
   // Preserve the first transport failure before cancellation/cleanup can obscure its stage.
   val first=firstFailure
   if(first!=null)output(DiagnosticResult.FAILED,firstCategory,first)
   else output(if(error==null)DiagnosticResult.SUCCEEDED else DiagnosticResult.FAILED,
    if(error==null)DiagnosticFailure.NONE else DiagnosticLog.classify(error),evidence(call,error))
  }
  override fun callStart(call:Call){started=clock();delegate.callStart(call)}
   override fun connectStart(call:Call,address:InetSocketAddress,proxy:Proxy){readAttempt?.let{it.connectStarts++};attempts++;stage="CONNECT";delegate.connectStart(call,address,proxy)}
  override fun secureConnectStart(call:Call){starts++;secureAt=clock();stage="SECURE_CONNECT";delegate.secureConnectStart(call)}
  override fun secureConnectEnd(call:Call,handshake:Handshake?){ends++;secureAt?.let{secureNanos+=clock()-it};secureAt=null;delegate.secureConnectEnd(call,handshake)}
  override fun connectEnd(call:Call,address:InetSocketAddress,proxy:Proxy,protocol:Protocol?){this.protocol=protocolToken(protocol);delegate.connectEnd(call,address,proxy,protocol)}
  override fun connectFailed(call:Call,address:InetSocketAddress,proxy:Proxy,protocol:Protocol?,error:IOException){failed(call,error);delegate.connectFailed(call,address,proxy,protocol,error)}
   override fun connectionAcquired(call:Call,connection:Connection){readAttempt?.let{it.acquisitions++;it.protocol=connection.protocol()};acquired++;if(attempts==0L)reused=true else newConnection=true;stage="ACQUIRED";protocol=protocolToken(connection.protocol());delegate.connectionAcquired(call,connection)}
   override fun requestHeadersStart(call:Call){readAttempt?.let{it.requestHeaderStarts++};stage="REQUEST_HEADERS";delegate.requestHeadersStart(call)}
   override fun responseHeadersStart(call:Call){readAttempt?.let{it.responseHeaderStarts++};headers++;stage="RESPONSE_HEADERS";delegate.responseHeadersStart(call)}
  override fun responseBodyStart(call:Call){stage="RESPONSE_BODY";delegate.responseBodyStart(call)}
  override fun requestFailed(call:Call,error:IOException){failed(call,error);delegate.requestFailed(call,error)}
  override fun responseFailed(call:Call,error:IOException){failed(call,error);delegate.responseFailed(call,error)}
  override fun callEnd(call:Call){finish(call,null);delegate.callEnd(call)}
  override fun callFailed(call:Call,error:IOException){finish(call,error);delegate.callFailed(call,error)}
  override fun proxySelectStart(call:Call,url:HttpUrl)=delegate.proxySelectStart(call,url)
  override fun proxySelectEnd(call:Call,url:HttpUrl,proxies:List<Proxy>)=delegate.proxySelectEnd(call,url,proxies)
  override fun dnsStart(call:Call,domainName:String)=delegate.dnsStart(call,domainName)
  override fun dnsEnd(call:Call,domainName:String,addresses:List<InetAddress>)=delegate.dnsEnd(call,domainName,addresses)
  override fun connectionReleased(call:Call,connection:Connection)=delegate.connectionReleased(call,connection)
  override fun requestHeadersEnd(call:Call,request:Request)=delegate.requestHeadersEnd(call,request)
  override fun requestBodyStart(call:Call)=delegate.requestBodyStart(call)
  override fun requestBodyEnd(call:Call,byteCount:Long)=delegate.requestBodyEnd(call,byteCount)
  override fun responseHeadersEnd(call:Call,response:Response)=delegate.responseHeadersEnd(call,response)
  override fun responseBodyEnd(call:Call,byteCount:Long)=delegate.responseBodyEnd(call,byteCount)
  override fun canceled(call:Call)=delegate.canceled(call)
  override fun satisfactionFailure(call:Call,response:Response)=delegate.satisfactionFailure(call,response)
  override fun cacheHit(call:Call,response:Response)=delegate.cacheHit(call,response)
  override fun cacheMiss(call:Call)=delegate.cacheMiss(call)
  override fun cacheConditionalHit(call:Call,response:Response)=delegate.cacheConditionalHit(call,response)
 }
}
